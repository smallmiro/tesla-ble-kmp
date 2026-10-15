package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
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

/** Secure Enclave 영속 키(설계 구체화 10). 쓸 수 없으면 [create]가 null. */
internal interface SecureEnclaveKeys {
    fun find(alias: String): VehicleKey?

    fun create(alias: String): VehicleKey?

    fun delete(alias: String)
}

private const val P256 = 256
private const val TAG_PREFIX = "io.github.smallmiro.teslable.key."

/** Security.framework 구현. 키는 `kSecAttrApplicationTag`(`TAG_PREFIX + alias`)로 찾는다. */
@OptIn(ExperimentalForeignApi::class)
internal class AppleSecureEnclaveKeys : SecureEnclaveKeys {
    override fun find(alias: String): VehicleKey? =
        memScoped {
            val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
            releasing(tag) {
                val query =
                    cfDictionary(
                        kSecClass to kSecClassKey,
                        kSecAttrApplicationTag to tag,
                        kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                        kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                        kSecReturnRef to kCFBooleanTrue,
                    )
                releasing(query) {
                    val result = alloc<CFTypeRefVar>()
                    if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) {
                        null
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        SecureEnclaveKey(alias, result.value as SecKeyRef)
                    }
                }
            }
        }

    override fun create(alias: String): VehicleKey? =
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val access =
                SecAccessControlCreateWithFlags(
                    null,
                    kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                    kSecAccessControlPrivateKeyUsage,
                    error.ptr,
                )
            if (access == null) {
                error.takeDescription()
                return@memScoped null
            }
            val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
            val bits = alloc<IntVar>().apply { value = P256 }
            val bitsNumber = checkNotNull(CFNumberCreate(null, kCFNumberIntType, bits.ptr)) { "CFNumberCreate failed" }
            releasing(access, tag, bitsNumber) {
                val privateAttrs =
                    cfDictionary(
                        kSecAttrIsPermanent to kCFBooleanTrue,
                        kSecAttrApplicationTag to tag,
                        kSecAttrAccessControl to access,
                    )
                releasing(privateAttrs) {
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
                            SecureEnclaveKey(alias, key)
                        }
                    }
                }
            }
        }

    override fun delete(alias: String) {
        val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
        releasing(tag) {
            val query = cfDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tag)
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
