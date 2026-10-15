package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** iOS 키스토어(SDD §8.2): Secure Enclave -> Keychain 소프트웨어 키. 시뮬레이터에는 Secure Enclave가 없어 소프트웨어 키가 쓰인다. */
@OptIn(ExperimentalForeignApi::class)
public fun appleVehicleKeyStore(logger: TeslaLogger = TeslaLogger.NoOp): VehicleKeyStore =
    AppleVehicleKeyStore(AppleKeychainItems(KEY_SERVICE), AppleSecureEnclaveKeys(), logger)

private const val KEY_SERVICE = "io.github.smallmiro.teslable.key"

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
        enclave.find(alias)?.let { return Loaded.Key(it) }
        return when (val read = keychain.read(alias)) {
            KeychainRead.Missing -> {
                Loaded.Missing
            }

            is KeychainRead.Failed -> {
                Loaded.Error(VehicleError.KeyStoreError.PlatformFailure("keychain status ${read.status}"))
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
            enclave.delete(alias)
            keychain.delete(alias)
            VehicleResult.Success(Unit)
        }
    }

    private suspend fun <T> guarded(block: suspend () -> VehicleResult<T>): VehicleResult<T> =
        mutex.withLock {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure(e::class.simpleName ?: "keystore error"))
            }
        }

    private companion object {
        const val TAG = "keystore"
    }
}
