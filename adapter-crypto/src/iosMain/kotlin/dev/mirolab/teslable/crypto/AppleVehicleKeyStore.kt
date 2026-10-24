package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyAccessibility
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
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess

/**
 * iOS 키스토어(SDD §8.2): Secure Enclave -> Keychain 소프트웨어 키. 시뮬레이터에는 Secure Enclave가 없어 소프트웨어 키가 쓰인다.
 * 새 키의 보호 등급은 `getOrCreate`의 접근성(기본 `WHEN_UNLOCKED`)이고, 이미 있는 키는 항목의 보호 등급을 다시 읽어 알린다.
 * 잠긴 기기(`errSecInteractionNotAllowed`)는 `KeyStoreError.Locked`다(FR-037, ADR-0014).
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
    accessibility: KeyAccessibility,
): VehicleKey =
    try {
        SoftwareVehicleKey(alias, pair, accessibility)
    } finally {
        pair.privateScalar.fill(0)
    }

/** Keychain OSStatus → 공개 오류. `errSecInteractionNotAllowed`(-25308, 잠긴 기기)만 [VehicleError.KeyStoreError.Locked]다(FR-037). */
internal fun keychainFailure(status: Int): VehicleError =
    if (status == errSecInteractionNotAllowed) {
        VehicleError.KeyStoreError.Locked
    } else {
        VehicleError.KeyStoreError.PlatformFailure("keychain status $status")
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
        when (val found = enclave.find(alias)) {
            is EnclaveFind.Found -> return Loaded.Key(found.key).also { if (found.reportedAccessibility == null) warnUnreported(alias) }
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
                    val pair =
                        SoftwareKeyPair.fromExternal(read.bytes)
                            ?: return Loaded.Error(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"))
                    val accessibility = read.accessibility ?: KeyAccessibility.WHEN_UNLOCKED.also { warnUnreported(alias) }
                    Loaded.Key(softwareKeyOf(alias, pair, accessibility))
                } finally {
                    read.bytes.fill(0)
                }
            }
        }
    }

    // 항목이 보호 등급을 알려 주지 않음(예상하지 않음, 실기기 확인 대상): 잠긴 사용을 과장하지 않고 WHEN_UNLOCKED로 보고하되 드러낸다
    private fun warnUnreported(alias: String) {
        logger.log(LogLevel.WARN, TAG) { "Keychain did not report the protection class of key $alias; reporting WHEN_UNLOCKED" }
    }

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
        accessibility: KeyAccessibility,
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
                        hardware = listOf(KeyAttempt { enclave.create(alias, accessibility) }),
                        software = KeyAttempt { createSoftware(alias, accessibility) },
                        logger = { message -> logger.log(LogLevel.INFO, TAG) { message } },
                    )
                }
            }
        }
    }

    /** 잠긴 기기에서 쓰지 못하면 [KeyStoreLockedException](KeyCreationChain이 `Locked`로 바꾼다). */
    private fun createSoftware(
        alias: String,
        accessibility: KeyAccessibility,
    ): VehicleKey {
        val pair = generateSoftwareKeyPair()
        try {
            val external = pair.toExternal()
            val status =
                try {
                    keychain.write(alias, external, accessibility)
                } finally {
                    external.fill(0)
                }
            if (status == errSecInteractionNotAllowed) throw KeyStoreLockedException()
            check(status == 0) { "keychain status $status" }
        } catch (e: Exception) {
            pair.privateScalar.fill(0)
            throw e
        }
        return softwareKeyOf(alias, pair, accessibility)
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
