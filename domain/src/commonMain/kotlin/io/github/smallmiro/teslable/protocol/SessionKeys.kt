// Ported from vehicle-command@a4b43c1 internal/authentication/native.go, crypto.go (Apache-2.0)
package io.github.smallmiro.teslable.protocol

import io.github.smallmiro.teslable.port.CryptoPrimitives

private const val SHARED_X_SIZE = 32

/** ECDH 공유 키 K 유도와 그 레이블 상수 (03-protocol.md §7.3~§7.4). */
public object SessionKeys {
    /** `SharedKeySizeBytes` — 128비트 AES-GCM 키 */
    public const val SHARED_KEY_SIZE: Int = 16

    /** `subkey(k, "session info")`의 레이블. */
    public const val LABEL_SESSION_INFO: String = "session info"

    /** `subkey(k, "authenticated command")`의 레이블. */
    public const val LABEL_AUTHENTICATED_COMMAND: String = "authenticated command"

    /**
     * `K = SHA1(BIG_ENDIAN(Sx, 32))[:16]`. SHA-1은 차량 호환용이며 충돌 저항이 필요 없다(원본 주석).
     * SHA-1 다이제스트(20바이트)의 나머지 4바이트도 K 파생 과정의 부산물이므로 반환 직후 0으로 덮는다.
     *
     * @throws IllegalArgumentException [sharedX]가 32바이트가 아니면 발생한다.
     */
    public fun deriveK(
        sharedX: ByteArray,
        crypto: CryptoPrimitives,
    ): ByteArray {
        require(sharedX.size == SHARED_X_SIZE) { "shared X coordinate must be $SHARED_X_SIZE bytes" }
        val digest = crypto.sha1(sharedX)
        try {
            return digest.copyOf(SHARED_KEY_SIZE)
        } finally {
            digest.fill(0)
        }
    }
}
