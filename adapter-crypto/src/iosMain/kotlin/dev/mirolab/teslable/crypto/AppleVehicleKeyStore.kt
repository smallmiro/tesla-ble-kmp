package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.port.VehicleKeyStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess

/**
 * iOS 키스토어(SDD §8.2): Secure Enclave -> Keychain 소프트웨어 키. 시뮬레이터에는 Secure Enclave가 없어 소프트웨어 키가 쓰인다.
 * Security.framework 작업은 `Dispatchers.IO`에서 해 호출 스레드(메인)를 막지 않는다.
 */
@OptIn(ExperimentalForeignApi::class)
public fun appleVehicleKeyStore(logger: TeslaLogger = TeslaLogger.NoOp): VehicleKeyStore =
    AppleVehicleKeyStore(AppleKeychainItems(KEY_SERVICE), AppleSecureEnclaveKeys(), logger)

private const val KEY_SERVICE = "dev.mirolab.teslable.key"

/** [SoftwareVehicleKey]는 스칼라를 복사해 가지므로 임시 사본은 바로 지운다. */
private fun softwareKeyOf(
    alias: String,
    pair: SoftwareKeyPair,
): VehicleKey =
    try {
        SoftwareVehicleKey(alias, pair)
    } finally {
        pair.privateScalar.fill(0)
    }

private fun keychainFailure(status: Int): VehicleError = VehicleError.KeyStoreError.PlatformFailure("keychain status $status")

@Suppress("TooGenericExceptionCaught") // Security.framework 예외는 KeyStoreError 값이 된다(ADR-0006)
internal class AppleVehicleKeyStore(
    private val keychain: KeychainItems,
    private val enclave: SecureEnclaveKeys,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
) : VehicleKeyStore {
    private val mutex = Mutex()

    private sealed interface Loaded {
        data object Missing : Loaded

        class Key(
            val key: VehicleKey,
        ) : Loaded

        class Error(
            val error: VehicleError,
        ) : Loaded
    }

    private fun load(alias: String): Loaded {
        when (val found = enclave.find(alias)) {
            is EnclaveFind.Found -> return Loaded.Key(found.key)
            is EnclaveFind.Failed -> return Loaded.Error(keychainFailure(found.status))
            EnclaveFind.Missing -> Unit
        }
        return when (val read = keychain.read(alias)) {
            KeychainRead.Missing -> {
                Loaded.Missing
            }

            is KeychainRead.Failed -> {
                Loaded.Error(keychainFailure(read.status))
            }

            is KeychainRead.Found -> {
                try {
                    SoftwareKeyPair.fromExternal(read.bytes)?.let { Loaded.Key(softwareKeyOf(alias, it)) }
                        ?: Loaded.Error(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"))
                } finally {
                    read.bytes.fill(0)
                }
            }
        }
    }

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            when (val loaded = load(alias)) {
                is Loaded.Key -> {
                    VehicleResult.Success(loaded.key)
                }

                is Loaded.Error -> {
                    VehicleResult.Failure(loaded.error)
                }

                Loaded.Missing -> {
                    KeyCreationChain.create(
                        policy,
                        hardware = listOf(KeyAttempt { enclave.create(alias) }),
                        software = KeyAttempt { createSoftware(alias) },
                        logger = { message -> logger.log(LogLevel.INFO, TAG) { message } },
                    )
                }
            }
        }
    }

    private fun createSoftware(alias: String): VehicleKey {
        val pair = generateSoftwareKeyPair()
        try {
            val external = pair.toExternal()
            val status =
                try {
                    keychain.write(alias, external)
                } finally {
                    external.fill(0)
                }
            check(status == 0) { "keychain status $status" }
        } catch (e: Exception) {
            pair.privateScalar.fill(0)
            throw e
        }
        return softwareKeyOf(alias, pair)
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            when (val loaded = load(alias)) {
                is Loaded.Key -> VehicleResult.Success(loaded.key)
                is Loaded.Error -> VehicleResult.Failure(loaded.error)
                Loaded.Missing -> VehicleResult.Success(null)
            }
        }
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            val statuses = listOf(enclave.delete(alias), keychain.delete(alias))
            val failed = statuses.firstOrNull { it != errSecSuccess && it != errSecItemNotFound }
            if (failed == null) VehicleResult.Success(Unit) else VehicleResult.Failure(keychainFailure(failed))
        }
    }

    // Security.framework 호출(Secure Enclave 생성 ~100 ms, Keychain I/O)은 호출자(Swift에서는 흔히 메인) 스레드를 막지 않게 IO에서 한다.
    private suspend fun <T> guarded(block: suspend () -> VehicleResult<T>): VehicleResult<T> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure(e::class.simpleName ?: "keystore error"))
                }
            }
        }

    private companion object {
        const val TAG = "keystore"
    }
}
