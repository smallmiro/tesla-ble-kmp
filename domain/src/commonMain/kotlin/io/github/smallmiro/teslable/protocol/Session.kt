// Ported from vehicle-command@a4b43c1 internal/authentication/native.go (Apache-2.0) — NativeSession
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.AesGcmOutput
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey

/**
 * ECDH 공유 키 K와 그 서브키를 보관하는 세션. K는 메모리에만 있고 [close]에서 0으로 덮는다(D27).
 * Go `NativeSession`과 달리 nonce는 호출자가 넘긴다(ADR-0008).
 *
 * 스레드 안전하지 않다: 인스턴스 하나는 반드시 하나의 코루틴/스레드에만 한정해서 사용해야 한다
 * (M1 `Signer`가 이 계약을 지킨다).
 *
 * @property localPublicKey 이 세션의 로컬(클라이언트) 공개키.
 * @property vehiclePublicKey 이 세션의 상대(차량) 공개키.
 */
public class Session private constructor(
    private val key: ByteArray,
    public val localPublicKey: PublicKeyBytes,
    public val vehiclePublicKey: PublicKeyBytes,
    private val crypto: CryptoPrimitives,
) : AutoCloseable {
    private var closed = false

    /**
     * `HMAC(K, label)` — Go `subkey`.
     * @throws IllegalStateException [close]로 세션이 닫힌 뒤 호출하면 발생한다.
     */
    public fun subkey(label: String): ByteArray {
        check(!closed) { "session closed" }
        return crypto.hmacSha256(key, label.encodeToByteArray())
    }

    /**
     * Go `SessionInfoHMAC`: HMAC(SESSION_INFO_KEY, TLV{SIG_TYPE=HMAC, PERSONALIZATION, CHALLENGE} || 0xFF || encodedInfo)
     * @throws IllegalStateException [close]로 세션이 닫힌 뒤 호출하면 발생한다([subkey]를 거쳐 전파됨).
     */
    public fun sessionInfoTag(
        personalization: ByteArray,
        challenge: ByteArray,
        encodedInfo: ByteArray,
    ): ByteArray {
        val meta =
            Metadata()
                .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(SignatureType.SIGNATURE_TYPE_HMAC.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION, personalization)
                .add(Tag.TAG_CHALLENGE, challenge)
        val infoKey = subkey(SessionKeys.LABEL_SESSION_INFO)
        try {
            return crypto.hmacSha256(infoKey, meta.serialize(encodedInfo))
        } finally {
            infoKey.fill(0)
        }
    }

    /** 상수 시간 비교. 세션이 닫혔으면 false. */
    public fun verifySessionInfoTag(
        personalization: ByteArray,
        challenge: ByteArray,
        encodedInfo: ByteArray,
        tag: ByteArray,
    ): Boolean {
        if (closed) return false
        return crypto.constantTimeEquals(sessionInfoTag(personalization, challenge, encodedInfo), tag)
    }

    /**
     * AES-128-GCM 암호화. [nonce]는 정확히 [NONCE_SIZE] 바이트여야 한다.
     * @throws IllegalStateException [close]로 세션이 닫힌 뒤 호출하면 발생한다.
     * @throws IllegalArgumentException [nonce] 길이가 [NONCE_SIZE]가 아니면 발생한다.
     */
    public fun encrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
    ): AesGcmOutput {
        check(!closed) { "session closed" }
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        return crypto.aesGcmEncrypt(key, nonce, plaintext, aad)
    }

    /**
     * AES-128-GCM 복호화. 인증 실패, 세션이 닫혔거나 [nonce] 길이가 [NONCE_SIZE]가 아니면 null
     * (와이어에서 온 신뢰할 수 없는 입력이므로 예외 대신 null로 처리한다).
     */
    public fun decrypt(
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray? {
        if (closed) return null
        if (nonce.size != NONCE_SIZE) return null
        return crypto.aesGcmDecrypt(key, nonce, ciphertext, tag, aad)
    }

    /** K를 0으로 덮고 세션을 비활성화한다. */
    override fun close() {
        key.fill(0)
        closed = true
    }

    override fun toString(): String = "Session(local=$localPublicKey, vehicle=$vehiclePublicKey)"

    /** [Session] 생성 팩토리. */
    public companion object {
        /** AES-GCM nonce 길이 (bytes). */
        public const val NONCE_SIZE: Int = 12

        /** Go `NativeECDHKey.Exchange`: ECDH → K. */
        public suspend fun establish(
            privateKey: EcdhPrivateKey,
            vehiclePublicKey: PublicKeyBytes,
            crypto: CryptoPrimitives,
        ): Session {
            val sharedX = privateKey.sharedX(vehiclePublicKey)
            try {
                val k = SessionKeys.deriveK(sharedX, crypto)
                return Session(k, privateKey.publicKey, vehiclePublicKey, crypto)
            } finally {
                sharedX.fill(0)
            }
        }

        /** 테스트 벡터용 (K를 직접 주입). */
        @InternalTeslableApi
        public fun fromSharedKey(
            k: ByteArray,
            localPublicKey: PublicKeyBytes,
            vehiclePublicKey: PublicKeyBytes,
            crypto: CryptoPrimitives,
        ): Session {
            require(k.size == SessionKeys.SHARED_KEY_SIZE) { "K must be ${SessionKeys.SHARED_KEY_SIZE} bytes" }
            return Session(k.copyOf(), localPublicKey, vehiclePublicKey, crypto)
        }
    }
}
