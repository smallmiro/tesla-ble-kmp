package dev.mirolab.teslable.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.port.VehicleKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Android 키스토어(SDD §8.2): StrongBox → TEE → 감싼 소프트웨어 키 파일. */
public fun androidVehicleKeyStore(
    context: Context,
    logger: TeslaLogger = TeslaLogger.NoOp,
): VehicleKeyStore = AndroidVehicleKeyStore(File(context.applicationContext.filesDir, "teslable/keys"), logger)

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

/** 버릴 소프트웨어 키의 공개키와 ECDH를 해 본다. [AndroidKeystoreKey.sharedX]는 Keystore 실패를 `IllegalArgumentException`으로 낸다. */
private suspend fun passesEcdhSelfTest(key: AndroidKeystoreKey): Boolean {
    val peer = generateSoftwareKeyPair()
    peer.privateScalar.fill(0) // 공개키만 쓴다
    return try {
        key.sharedX(peer.publicKey).fill(0)
        true
    } catch (
        @Suppress("SwallowedException") e: IllegalArgumentException, // 이유는 호출자의 WARN 로그로 충분하다(공유 X는 로그 금지)
    ) {
        false
    }
}

@Suppress("TooGenericExceptionCaught") // Keystore 예외는 KeyStoreError 값이 된다(ADR-0006)
internal class AndroidVehicleKeyStore(
    directory: File,
    private val logger: TeslaLogger,
) : VehicleKeyStore {
    private val mutex = Mutex()
    private val files = WrappedKeyFiles(directory) { AndroidKeystoreWrapper(it) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            when (val existing = load(alias)) {
                is Loaded.Key -> {
                    VehicleResult.Success(existing.key)
                }

                Loaded.Corrupted -> {
                    VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"))
                }

                Loaded.Missing -> {
                    KeyCreationChain.create(
                        policy,
                        hardware =
                            listOf(
                                KeyAttempt { generateKeystoreKey(alias, strongBox = true) },
                                KeyAttempt { generateKeystoreKey(alias, strongBox = false) },
                            ),
                        software = KeyAttempt { createSoftwareKey(alias) },
                        logger = { message -> logger.log(LogLevel.INFO, TAG) { message } },
                    )
                }
            }
        }
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            when (val existing = load(alias)) {
                is Loaded.Key -> VehicleResult.Success(existing.key)
                Loaded.Missing -> VehicleResult.Success(null)
                Loaded.Corrupted -> VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"))
            }
        }
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            val store = keyStore()
            val keyAlias = KeystoreAliases.key(alias)
            if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
            files.delete(alias)
            VehicleResult.Success(Unit)
        }
    }

    private sealed interface Loaded {
        data object Missing : Loaded

        data object Corrupted : Loaded

        class Key(
            val key: VehicleKey,
        ) : Loaded
    }

    private fun load(alias: String): Loaded {
        val store = keyStore()
        val privateKey = store.getKey(KeystoreAliases.key(alias), null) as? PrivateKey
        val certificate = store.getCertificate(KeystoreAliases.key(alias))
        if (privateKey != null && certificate != null) {
            val publicKey = certificate.publicKey as ECPublicKey
            return Loaded.Key(AndroidKeystoreKey(alias, privateKey, encode(publicKey), protectionOf(privateKey)))
        }
        return when (val read = files.read(alias)) {
            WrappedKeyFiles.ReadResult.Missing -> Loaded.Missing
            WrappedKeyFiles.ReadResult.Corrupted -> Loaded.Corrupted
            is WrappedKeyFiles.ReadResult.Found -> Loaded.Key(softwareKeyOf(alias, read.pair))
        }
    }

    /** 새 소프트웨어 키를 만들어 감싸 저장한다. */
    private fun createSoftwareKey(alias: String): VehicleKey {
        val pair = generateSoftwareKeyPair()
        try {
            files.write(alias, pair)
        } catch (e: Exception) {
            pair.privateScalar.fill(0)
            throw e
        }
        return softwareKeyOf(alias, pair)
    }

    /**
     * null = 이 수준 불가. 생성은 됐지만 실제 보관 수준이 SOFTWARE(에뮬레이터 등)면 지우고 null — 소프트웨어 대체 경로가 맡는다.
     * 하드웨어 키는 버릴 소프트웨어 P-256 공개키와 ECDH 자체 시험을 한다(최종 리뷰 I3): 만들어지지만 실제로는 합의할 수 없는
     * StrongBox/TEE 키(기기별 결함)가 첫 핸드셰이크에서야 실패하지 않게, 실패하면 별칭을 지우고 null로 다음 수준에 넘긴다.
     * 호스트 단위 테스트로는 재현할 수 없어(AndroidKeyStore 공급자 없음) 실기기 확인 항목이다.
     */
    private suspend fun generateKeystoreKey(
        alias: String,
        strongBox: Boolean,
    ): VehicleKey? {
        val keyAlias = KeystoreAliases.key(alias)
        val spec =
            KeyGenParameterSpec
                .Builder(keyAlias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setIsStrongBoxBacked(strongBox)
                .build()
        val pair =
            try {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
                    initialize(spec)
                    generateKeyPair()
                }
            } catch (
                @Suppress("SwallowedException") e: StrongBoxUnavailableException,
            ) {
                return null
            }
        return try {
            val protection = protectionOf(pair.private)
            if (protection.isHardware) {
                AndroidKeystoreKey(alias, pair.private, encode(pair.public as ECPublicKey), protection).takeIf { passesEcdhSelfTest(it) }
                    ?: run {
                        logger.log(LogLevel.WARN, TAG) { "$protection key failed the ECDH self-test; trying the next key level" }
                        keyStore().deleteEntry(keyAlias)
                        null
                    }
            } else {
                keyStore().deleteEntry(keyAlias)
                null
            }
        } catch (e: Exception) {
            runCatching { keyStore().deleteEntry(keyAlias) } // 만들다 만 항목을 남기지 않는다
            throw e
        }
    }

    private fun protectionOf(privateKey: PrivateKey): KeyProtection {
        val info = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEY_STORE).getKeySpec(privateKey, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtection.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtection.TEE
            else -> KeyProtection.SOFTWARE
        }
    }

    private fun encode(publicKey: ECPublicKey) = EcPublicKeyEncoding.encode(publicKey.w.affineX, publicKey.w.affineY)

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
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TAG = "keystore"
    }
}
