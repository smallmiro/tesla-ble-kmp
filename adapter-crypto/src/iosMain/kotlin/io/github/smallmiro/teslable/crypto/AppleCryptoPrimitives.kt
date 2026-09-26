// Ported from vehicle-command@a4b43c1 internal/authentication/native.go (Apache-2.0) — NativeSession semantics on Apple platforms
package io.github.smallmiro.teslable.crypto

import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit
import io.github.smallmiro.teslable.port.AesGcmOutput
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.RandomSource
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA1
import platform.CoreCrypto.CC_SHA1_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

private const val GCM_TAG_BYTES = 16

/**
 * iOS: SHA·HMAC은 CommonCrypto, AES-GCM은 cryptography-kotlin CryptoKit 프로바이더(ADR-0004).
 * CommonCrypto의 GCM은 비공개 SPI이므로 쓰지 않는다.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class, DelicateCryptographyApi::class)
public class AppleCryptoPrimitives : CryptoPrimitives {
    private val aesGcm = CryptographyProvider.CryptoKit.get(AES.GCM)

    override fun sha1(data: ByteArray): ByteArray =
        digest(data, CC_SHA1_DIGEST_LENGTH) { input, length, output -> CC_SHA1(input, length, output) }

    override fun sha256(data: ByteArray): ByteArray =
        digest(data, CC_SHA256_DIGEST_LENGTH) { input, length, output -> CC_SHA256(input, length, output) }

    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val output = UByteArray(CC_SHA256_DIGEST_LENGTH)
        output.usePinned { out ->
            key.usePinnedOrNull { keyPtr ->
                data.usePinnedOrNull { dataPtr ->
                    CCHmac(kCCHmacAlgSHA256, keyPtr, key.size.toULong(), dataPtr, data.size.toULong(), out.addressOf(0))
                }
            }
        }
        return output.asByteArray()
    }

    override fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): AesGcmOutput {
        val gcmKey = aesGcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
        // 출력은 ciphertext || tag(16). IV는 포함되지 않는다.
        val sealed =
            gcmKey
                .cipher(tagSize = (GCM_TAG_BYTES * 8).bits)
                .encryptWithIvBlocking(iv = nonce, plaintext = plaintext, associatedData = aad)
        val split = sealed.size - GCM_TAG_BYTES
        return AesGcmOutput(sealed.copyOfRange(0, split), sealed.copyOfRange(split, sealed.size))
    }

    @Suppress("TooGenericExceptionCaught") // 프로바이더가 인증 실패에 던지는 예외 타입이 문서화되어 있지 않다
    override fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray? =
        try {
            val gcmKey = aesGcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            gcmKey
                .cipher(tagSize = (GCM_TAG_BYTES * 8).bits)
                .decryptWithIvBlocking(iv = nonce, ciphertext = ciphertext + tag, associatedData = aad)
        } catch (_: Exception) {
            null
        }

    override fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var accumulator = 0
        for (index in a.indices) accumulator = accumulator or (a[index].toInt() xor b[index].toInt())
        return accumulator == 0
    }

    private inline fun digest(
        data: ByteArray,
        length: Int,
        block: (input: CValuesRef<*>?, length: UInt, output: CValuesRef<UByteVar>) -> Unit,
    ): ByteArray {
        val output = UByteArray(length)
        output.usePinned { out ->
            data.usePinnedOrNull { input -> block(input, data.size.toUInt(), out.addressOf(0)) }
        }
        return output.asByteArray()
    }

    /** 빈 배열은 `addressOf(0)`이 불가하므로 null 포인터를 넘긴다. */
    private inline fun <T> ByteArray.usePinnedOrNull(block: (CValuesRef<*>?) -> T): T =
        if (isEmpty()) block(null) else usePinned { block(it.addressOf(0)) }
}

@OptIn(ExperimentalForeignApi::class)
public class AppleRandomSource : RandomSource {
    override fun nextBytes(count: Int): ByteArray {
        if (count == 0) return ByteArray(0)
        val bytes = ByteArray(count)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.toULong(), it.addressOf(0)) }
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
        return bytes
    }
}
