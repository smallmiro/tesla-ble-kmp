// Ported from vehicle-command@a4b43c1 internal/authentication/native.go, internal/authentication/crypto.go
// (Apache-2.0) — CryptoPrimitives port interface (NativeSession, Session)
package io.github.smallmiro.teslable.port

/** AES-GCM 결과. Go `NativeSession.Encrypt`처럼 암호문과 태그를 분리해 돌려준다. */
public class AesGcmOutput(
    ciphertext: ByteArray,
    tag: ByteArray,
) {
    private val ciphertextBytes: ByteArray = ciphertext.copyOf()
    private val tagBytes: ByteArray = tag.copyOf()

    /** 암호문 복사본을 돌려준다. */
    public val ciphertext: ByteArray get() = ciphertextBytes.copyOf()

    /** 태그 복사본을 돌려준다. */
    public val tag: ByteArray get() = tagBytes.copyOf()
}

/**
 * 플랫폼이 제공하는 암호 원시연산 (NFR-005: 자체 구현 금지).
 * 구현: `:adapter-crypto` (JCA / CommonCrypto + CryptoKit 프로바이더).
 * Go 원본: internal/authentication/native.go (NativeSession), crypto.go (Session 인터페이스)
 */
public interface CryptoPrimitives {
    /** SHA-1 digest, 20 bytes. */
    public fun sha1(data: ByteArray): ByteArray

    /** SHA-256 digest, 32 bytes. */
    public fun sha256(data: ByteArray): ByteArray

    /** HMAC-SHA-256 digest, 32 bytes. */
    public fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    /** AES-128-GCM. [nonce]는 12바이트, 태그는 16바이트. 운영 코드는 항상 새 난수 nonce를 넘긴다(ADR-0008). */
    public fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): AesGcmOutput

    /** 인증 실패면 null. */
    public fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray?

    /** 상수 시간 비교 (Go `hmac.Equal`). 길이가 다르면 false. */
    public fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean
}
