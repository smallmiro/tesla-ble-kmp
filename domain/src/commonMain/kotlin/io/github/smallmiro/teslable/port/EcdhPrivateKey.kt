// Ported from vehicle-command@a4b43c1 internal/authentication/ecdh.go (Apache-2.0) — ECDHPrivateKey port interface
package io.github.smallmiro.teslable.port

import io.github.smallmiro.teslable.model.PublicKeyBytes

/**
 * 로컬 P-256 개인키 핸들. 개인키 바이트는 노출하지 않는다(D27).
 * Go 원본: internal/authentication/ecdh.go `ECDHPrivateKey` — 단 Go의 `Exchange`가 `Session`을 돌려주는 것과 달리
 * 공유점 X좌표(32바이트, 0-패딩)만 돌려주고 K 유도는 도메인(`SessionKeys`)이 맡는다.
 */
public interface EcdhPrivateKey {
    /** 이 키의 공개키, 65바이트 비압축. */
    public val publicKey: PublicKeyBytes

    /** ECDH 공유점의 X좌표 32바이트 (Go `sharedX.FillBytes(32)`). */
    public suspend fun sharedX(peer: PublicKeyBytes): ByteArray
}
