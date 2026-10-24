package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRetain
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
import platform.Security.kSecAttrAccessible
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
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnRef
import platform.Security.kSecValueRef

/** [SecureEnclaveKeys.find] 결과. 일시 실패(잠긴 기기 등)를 "없음"과 구분한다. */
internal sealed interface EnclaveFind {
    data object Missing : EnclaveFind

    /**
     * [reportedAccessibility]는 항목이 보고한 보호 등급(FR-037). null이면 Keychain이 알려 주지 않은 것이고, [key]는 잠긴 사용을
     * 과장하지 않는 `WHEN_UNLOCKED`로 보고한다(저장소가 WARN을 남긴다).
     */
    class Found(
        val key: VehicleKey,
        val reportedAccessibility: KeyAccessibility?,
    ) : EnclaveFind

    data class Failed(
        val status: Int,
    ) : EnclaveFind
}

/**
 * Secure Enclave 영속 키(설계 구체화 10). 쓸 수 없으면 [create]가 null, 기기가 잠겨 만들 수 없으면 [KeyStoreLockedException].
 * 새 키의 보호 등급은 [create]의 접근성이다(FR-037). 반환 `Int`는 OSStatus.
 */
internal interface SecureEnclaveKeys {
    fun find(alias: String): EnclaveFind

    fun create(
        alias: String,
        accessibility: KeyAccessibility,
    ): VehicleKey?

    fun delete(alias: String): Int
}

private const val P256 = 256
private const val TAG_PREFIX = "dev.mirolab.teslable.key."

/**
 * `SecItemCopyMatching`(참조 + 속성)이 돌려준 딕셔너리에서 키 참조와 보호 등급(`kSecAttrAccessible`)을 읽는다(FR-037).
 * 키 참조는 딕셔너리가 가진 것이라 키 몫을 retain해 키에 넘긴다 — [result]를 해제한 뒤에도 키가 쓰고, 키가 수거될 때 해제된다
 * (공개키를 읽지 못해 키를 만들다 실패해도). [result]는 호출자가 해제한다.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun enclaveFoundOf(
    alias: String,
    result: CFDictionaryRef,
): EnclaveFind.Found {
    val reported = KeychainAccessibility.fromSecAttr(CFDictionaryGetValue(result, kSecAttrAccessible))

    @Suppress("UNCHECKED_CAST")
    val reference = checkNotNull(CFDictionaryGetValue(result, kSecValueRef)) { "no key reference" } as SecKeyRef
    CFRetain(reference)
    return EnclaveFind.Found(SecureEnclaveKey(alias, reference, reported ?: KeyAccessibility.WHEN_UNLOCKED), reported)
}

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
                        kSecReturnAttributes to kCFBooleanTrue,
                        kSecMatchLimit to kSecMatchLimitOne,
                    )
                releasing(query) {
                    val result = alloc<CFTypeRefVar>()
                    when (val status = SecItemCopyMatching(query, result.ptr)) {
                        errSecSuccess -> {
                            // 반환 형식을 둘 이상 요청하면 결과는 딕셔너리다(kSecValueRef + 속성)
                            @Suppress("UNCHECKED_CAST")
                            val dictionary = result.value as CFDictionaryRef
                            releasing(dictionary) { enclaveFoundOf(alias, dictionary) }
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
    override fun create(
        alias: String,
        accessibility: KeyAccessibility,
    ): VehicleKey? =
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
                            KeychainAccessibility.secAttrOf(accessibility),
                            kSecAccessControlPrivateKeyUsage,
                            error.ptr,
                        )
                    if (access == null) {
                        error.takeDescription()
                        null
                    } else {
                        releasing(access) { createKey(alias, accessibility, tag, bitsNumber, access) }
                    }
                }
            }
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun createKey(
        alias: String,
        accessibility: KeyAccessibility,
        tag: CFDataRef,
        bitsNumber: CFNumberRef,
        access: SecAccessControlRef,
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
                val key = memScoped { createRandomKey(attributes) }
                if (key == null) {
                    null
                } else {
                    try {
                        SecureEnclaveKey(alias, key, accessibility) // 참조는 키 몫이다(만들다 실패해도 수거될 때 해제)
                    } catch (e: Exception) {
                        delete(alias) // 영속 키가 남아 소프트웨어 대체 경로를 가리지 않게 한다
                        null
                    }
                }
            }
        }
    }

    /**
     * 시뮬레이터처럼 Secure Enclave가 없으면 null(소프트웨어로). 기기가 잠겨 실패하면(-25308) [KeyStoreLockedException]:
     * 일시적인 잠금 때문에 소프트웨어 키로 넘어가지 않는다(FR-037). CFError는 판정한 뒤 해제한다.
     */
    private fun MemScope.createRandomKey(attributes: CFDictionaryRef): SecKeyRef? {
        val error = alloc<CFErrorRefVar>()
        val key = SecKeyCreateRandomKey(attributes, error.ptr)
        if (key == null) {
            val locked = KeychainAccessibility.isDeviceLocked(error.value)
            error.takeDescription()
            if (locked) throw KeyStoreLockedException()
        }
        return key
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

/**
 * Secure Enclave 개인키 핸들. 공개키는 `SecKeyCopyPublicKey` -> 외부 표현(X9.63 65바이트).
 * 생성자가 [privateKey]의 +1 참조를 맡는다(생성자가 실패해도): 이 키가 수거될 때 해제되고, 넘긴 쪽은 해제하지 않는다([ReleaseWhenCollected]).
 * [accessibility]는 만들 때 고른 값이거나 항목에서 다시 읽은 값이다(FR-037).
 */
@OptIn(ExperimentalForeignApi::class)
internal class SecureEnclaveKey(
    override val alias: String,
    private val privateKey: SecKeyRef,
    override val accessibility: KeyAccessibility,
) : VehicleKey {
    // 맨 먼저: 아래 초기화(공개키 읽기)가 실패해도 넘겨받은 참조가 새지 않는다
    @Suppress("unused")
    private val release = ReleaseWhenCollected(privateKey)

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
                            ?: throw error.takeKeyExchangeFailure()
                    result.use { it.toByteArray() }
                }
            }
        }
}
