package io.github.smallmiro.teslable.port

/** AES-GCM 결과. Go `NativeSession.Encrypt`처럼 암호문과 태그를 분리해 돌려준다. */
public class AesGcmOutput(
    ciphertext: ByteArray,
    tag: ByteArray,
) {
    public val ciphertext: ByteArray = ciphertext.copyOf()
    public val tag: ByteArray = tag.copyOf()
}

/**
 * 플랫폼이 제공하는 암호 원시연산 (NFR-005: 자체 구현 금지).
 * 구현: `:adapter-crypto` (JCA / CommonCrypto + CryptoKit 프로바이더).
 * Go 원본: internal/authentication/native.go (NativeSession), crypto.go (Session 인터페이스)
 */
public interface CryptoPrimitives {
    public fun sha1(data: ByteArray): ByteArray

    public fun sha256(data: ByteArray): ByteArray

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
