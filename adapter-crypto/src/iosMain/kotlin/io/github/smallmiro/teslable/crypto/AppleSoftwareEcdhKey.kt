// Ported from vehicle-command@a4b43c1 internal/authentication/native.go (Apache-2.0) — NativeECDHKey.sharedSecret (Security.framework)
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.EcdhPrivateKey
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
 * Security.framework 소프트웨어 키. 개인키 데이터 형식은 `0x04 || X || Y || d` (97바이트).
 * M3의 Secure Enclave 키도 같은 `SecKeyCopyKeyExchangeResult` 경로를 쓴다.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleSoftwareEcdhKey(
    privateScalar: ByteArray,
    override val publicKey: PublicKeyBytes,
) : EcdhPrivateKey {
    private val privateKey: SecKeyRef = createSecKey(publicKey.toByteArray() + privateScalar, kSecAttrKeyClassPrivate)

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
                            ?: throw IllegalArgumentException("ECDH failed: ${cfError.takeDescription()}")
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
 * `SecKeyCreateWithData`로 P-256 키를 만든다. 실패하면 IllegalStateException. 호출자가 `CFRelease` 책임.
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
