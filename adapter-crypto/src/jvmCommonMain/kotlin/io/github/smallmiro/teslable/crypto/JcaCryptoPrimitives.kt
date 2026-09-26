// Ported from vehicle-command@a4b43c1 internal/authentication/native.go (Apache-2.0) — NativeSession.Encrypt/Decrypt, subkey
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.port.AesGcmOutput
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.RandomSource
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG_BITS = 128
private const val TAG_BYTES = 16

/** JCA(Java Cryptography Architecture) 기반 [CryptoPrimitives] 구현. JVM/Android 공용. */
public class JcaCryptoPrimitives : CryptoPrimitives {
    /** `MessageDigest("SHA-1")`. */
    override fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    /** `MessageDigest("SHA-256")`. */
    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** `Mac("HmacSHA256")`. */
    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** `Cipher("AES/GCM/NoPadding")`, 암호화. 태그(16바이트)를 암호문에서 분리해 돌려준다. */
    override fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): AesGcmOutput {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val out = cipher.doFinal(plaintext)
        val split = out.size - TAG_BYTES
        return AesGcmOutput(out.copyOfRange(0, split), out.copyOfRange(split, out.size))
    }

    /** `Cipher("AES/GCM/NoPadding")`, 복호화. 태그 검증 실패([AEADBadTagException])면 `null`. */
    override fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext + tag)
        } catch (_: AEADBadTagException) {
            null
        }

    /** `MessageDigest.isEqual` — JCA가 제공하는 상수 시간 비교. */
    override fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)
}

/** [SecureRandom] 기반 [RandomSource] 구현. JVM/Android 공용. */
public class JcaRandomSource : RandomSource {
    private val rng = SecureRandom()

    /** `SecureRandom.nextBytes`. */
    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also(rng::nextBytes)
}
