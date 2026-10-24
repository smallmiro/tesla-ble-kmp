// Ported from vehicle-command@a4b43c1 internal/authentication/native.go (Apache-2.0) — NativeECDHKey.sharedSecret (Security.framework)
package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.EcdhPrivateKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard

private const val SHARED_X_SIZE = 32
private const val P256_BITS = 256
private const val KEY_ATTRIBUTE_CAPACITY = 3L

/**
 * Security.framework 소프트웨어 키. [privateKey]는 [createSecKey]로 만든 개인키 참조이고 [publicKey]와 짝이어야 한다.
 * 생성자가 그 +1 참조를 맡는다: 이 키가 수거될 때 해제되고, 넘긴 쪽은 해제하지 않는다([ReleaseWhenCollected]).
 * M3의 Secure Enclave 키도 같은 `SecKeyCopyKeyExchangeResult` 경로를 쓴다.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleSoftwareEcdhKey(
    private val privateKey: SecKeyRef,
    override val publicKey: PublicKeyBytes,
) : EcdhPrivateKey {
    // 맨 먼저: 뒤에 초기화가 늘어 실패해도 넘겨받은 참조가 새지 않는다
    @Suppress("unused")
    private val release = ReleaseWhenCollected(privateKey)

    /** 개인키 데이터 `0x04 || X || Y || d`(97바이트)로 참조를 만든다. */
    constructor(privateScalar: ByteArray, publicKey: PublicKeyBytes) :
        this(createSecKey(publicKey.toByteArray() + privateScalar, kSecAttrKeyClassPrivate), publicKey)

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray =
        memScoped {
            val peerKey =
                try {
                    createSecKey(peer.toByteArray(), kSecAttrKeyClassPublic)
                } catch (e: IllegalStateException) {
                    throw IllegalArgumentException("invalid peer public key", e)
                }
            try {
                val cfError = alloc<CFErrorRefVar>()
                val params =
                    checkNotNull(
                        CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
                    )
                try {
                    val result =
                        SecKeyCopyKeyExchangeResult(privateKey, kSecKeyAlgorithmECDHKeyExchangeStandard, peerKey, params, cfError.ptr)
                            ?: throw cfError.takeKeyExchangeFailure()
                    result.use { data ->
                        val sharedX = data.toByteArray()
                        check(sharedX.size == SHARED_X_SIZE) { "unexpected shared secret length ${sharedX.size}" }
                        sharedX
                    }
                } finally {
                    CFRelease(params)
                }
            } finally {
                CFRelease(peerKey)
            }
        }
}

/**
 * `SecKeyCreateWithData`로 P-256 키를 만든다. 실패하면 IllegalStateException. 돌려준 +1 참조는 호출자 몫이다:
 * 키 객체에 넘기거나(그 키가 수거될 때 해제, [ReleaseWhenCollected]) 다 쓰면 `CFRelease`한다.
 * [data]는 사용 후(성공/실패 모두) `fill(0)`으로 지운다 — 개인키 스칼라 등 민감한 바이트가 힙에 남지 않도록 한다.
 * `SecKeyCreateWithData`에 넘기는 `CFData` 사본은 불변(immutable)이라 지울 수 없다.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun createSecKey(
    data: ByteArray,
    keyClass: CFStringRef?,
): SecKeyRef =
    memScoped {
        try {
            val attributes: CFDictionaryRef =
                checkNotNull(
                    CFDictionaryCreateMutable(
                        null,
                        KEY_ATTRIBUTE_CAPACITY,
                        kCFTypeDictionaryKeyCallBacks.ptr,
                        kCFTypeDictionaryValueCallBacks.ptr,
                    ),
                )
            try {
                val bits = alloc<IntVar>().apply { value = P256_BITS }
                val bitsNumber = checkNotNull(CFNumberCreate(null, kCFNumberIntType, bits.ptr))
                try {
                    CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
                    CFDictionaryAddValue(attributes, kSecAttrKeyClass, keyClass)
                    CFDictionaryAddValue(attributes, kSecAttrKeySizeInBits, bitsNumber)
                    val cfError = alloc<CFErrorRefVar>()
                    val key = data.toCFData().use { cfData -> SecKeyCreateWithData(cfData, attributes, cfError.ptr) }
                    key ?: error("SecKeyCreateWithData failed: ${cfError.takeDescription()}")
                } finally {
                    CFRelease(bitsNumber)
                }
            } finally {
                CFRelease(attributes)
            }
        } finally {
            data.fill(0)
        }
    }
