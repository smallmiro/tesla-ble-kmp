package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecAccessControlRef
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef

/** [SecureEnclaveKeys.find] 결과. 일시 실패(잠긴 기기 등)를 "없음"과 구분한다. */
internal sealed interface EnclaveFind {
    data object Missing : EnclaveFind

    class Found(
        val key: VehicleKey,
    ) : EnclaveFind

    data class Failed(
        val status: Int,
    ) : EnclaveFind
}

/** Secure Enclave 영속 키(설계 구체화 10). 쓸 수 없으면 [create]가 null. 반환 `Int`는 OSStatus. */
internal interface SecureEnclaveKeys {
    fun find(alias: String): EnclaveFind

    fun create(alias: String): VehicleKey?

    fun delete(alias: String): Int
}

private const val P256 = 256
private const val TAG_PREFIX = "dev.mirolab.teslable.key."

/** Security.framework 구현. 키는 `kSecAttrApplicationTag`(`TAG_PREFIX + alias`)로 찾는다. */
@OptIn(ExperimentalForeignApi::class)
internal class AppleSecureEnclaveKeys : SecureEnclaveKeys {
    override fun find(alias: String): EnclaveFind =
        memScoped {
            val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
            releasing(tag) {
                val query =
                    cfDictionary(
                        kSecClass to kSecClassKey,
                        kSecAttrApplicationTag to tag,
                        kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                        kSecAttrKeyClass to kSecAttrKeyClassPrivate,
                        kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                        kSecReturnRef to kCFBooleanTrue,
                    )
                releasing(query) {
                    val result = alloc<CFTypeRefVar>()
                    when (val status = SecItemCopyMatching(query, result.ptr)) {
                        errSecSuccess -> {
                            @Suppress("UNCHECKED_CAST")
                            EnclaveFind.Found(SecureEnclaveKey(alias, result.value as SecKeyRef))
                        }

                        errSecItemNotFound -> {
                            EnclaveFind.Missing
                        }

                        else -> {
                            EnclaveFind.Failed(status)
                        }
                    }
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // 공개키 내보내기 실패 시 만들다 만 키를 남기지 않는다
    override fun create(alias: String): VehicleKey? =
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
            releasing(tag) {
                val bits = alloc<IntVar>().apply { value = P256 }
                val bitsNumber = checkNotNull(CFNumberCreate(null, kCFNumberIntType, bits.ptr)) { "CFNumberCreate failed" }
                releasing(bitsNumber) {
                    val access =
                        SecAccessControlCreateWithFlags(
                            null,
                            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                            kSecAccessControlPrivateKeyUsage,
                            error.ptr,
                        )
                    if (access == null) {
                        error.takeDescription()
                        null
                    } else {
                        releasing(access) { createKey(alias, tag, bitsNumber, access, error) }
                    }
                }
            }
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun createKey(
        alias: String,
        tag: CFDataRef,
        bitsNumber: CFNumberRef,
        access: SecAccessControlRef,
        error: CFErrorRefVar,
    ): VehicleKey? {
        val privateAttrs =
            cfDictionary(
                kSecAttrIsPermanent to kCFBooleanTrue,
                kSecAttrApplicationTag to tag,
                kSecAttrAccessControl to access,
            )
        return releasing(privateAttrs) {
            val attributes =
                cfDictionary(
                    kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                    kSecAttrKeySizeInBits to bitsNumber,
                    kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                    kSecPrivateKeyAttrs to privateAttrs,
                )
            releasing(attributes) {
                val key = SecKeyCreateRandomKey(attributes, error.ptr)
                if (key == null) {
                    error.takeDescription() // CFError 해제. 시뮬레이터: Secure Enclave 없음 -> null -> 소프트웨어로
                    null
                } else {
                    try {
                        SecureEnclaveKey(alias, key)
                    } catch (e: Exception) {
                        CFRelease(key)
                        delete(alias) // 영속 키가 남아 소프트웨어 대체 경로를 가리지 않게 한다
                        null
                    }
                }
            }
        }
    }

    override fun delete(alias: String): Int {
        val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
        return releasing(tag) {
            val query =
                cfDictionary(
                    kSecClass to kSecClassKey,
                    kSecAttrApplicationTag to tag,
                    kSecAttrKeyClass to kSecAttrKeyClassPrivate,
                )
            releasing(query) { SecItemDelete(query) }
        }
    }
}

/** Secure Enclave 개인키 핸들. 공개키는 `SecKeyCopyPublicKey` -> 외부 표현(X9.63 65바이트). [privateKey]를 소유한다. */
@OptIn(ExperimentalForeignApi::class)
internal class SecureEnclaveKey(
    override val alias: String,
    private val privateKey: SecKeyRef,
) : VehicleKey {
    override val protection: KeyProtection = KeyProtection.SECURE_ENCLAVE
    override val publicKey: PublicKeyBytes =
        memScoped {
            val publicRef = checkNotNull(SecKeyCopyPublicKey(privateKey)) { "no public key" }
            releasing(publicRef) {
                val error = alloc<CFErrorRefVar>()
                val data = SecKeyCopyExternalRepresentation(publicRef, error.ptr)
                if (data == null) error("export failed: ${error.takeDescription()}")
                data.use { PublicKeyBytes(it.toByteArray()) }
            }
        }

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray =
        memScoped {
            val peerKey = createSecKey(peer.toByteArray(), kSecAttrKeyClassPublic)
            releasing(peerKey) {
                val params = cfDictionary()
                releasing(params) {
                    val error = alloc<CFErrorRefVar>()
                    val result =
                        SecKeyCopyKeyExchangeResult(privateKey, kSecKeyAlgorithmECDHKeyExchangeStandard, peerKey, params, error.ptr)
                            ?: throw IllegalArgumentException("ECDH failed: ${error.takeDescription()}")
                    result.use { it.toByteArray() }
                }
            }
        }
}
