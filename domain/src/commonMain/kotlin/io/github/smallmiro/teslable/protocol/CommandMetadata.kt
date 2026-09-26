// Ported from vehicle-command@a4b43c1 internal/authentication/peer.go (Apache-2.0) — Peer.extractMetadata
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import com.tesla.generated.universalmessage.Domain

private const val DOMAIN_MAX_VALUE = 255
private const val EPOCH_SIZE = 16

/** 명령 메타데이터 조립 (03-protocol.md §8.2). */
public object CommandMetadata {
    /**
     * `epochLength = 2^30 s` — 만료 시각 상한.
     * `1u shl 30`은 상수식으로 접히지 않는다(Kotlin은 `shl`/`shr`의 상수 폴딩을 Int/Long에만 지원하고
     * UInt에는 지원하지 않는다). 값은 동일하게 유지하고 리터럴로 표기한다.
     */
    public const val EPOCH_LENGTH_SECONDS: UInt = 1_073_741_824u

    /**
     * 요청 메타데이터: SIGNATURE_TYPE, DOMAIN, PERSONALIZATION, EPOCH, EXPIRES_AT, COUNTER, [FLAGS ≠ 0].
     * 하위 호환을 위해 FLAGS는 0이 아닐 때만 넣는다(MITM이 비트를 지우면 해시가 어긋난다).
     *
     * @throws IllegalArgumentException [epoch]가 16바이트가 아니거나, [expiresAt]가 [EPOCH_LENGTH_SECONDS]를
     *   초과하면 발생한다.
     */
    @Suppress("LongParameterList") // TLV 필드 하나당 파라미터 하나 — Go peer.go extractMetadata와 1:1 대응, 그룹화하면 스펙 대응이 흐려진다.
    public fun build(
        domain: Domain,
        personalization: ByteArray,
        epoch: ByteArray,
        expiresAt: UInt,
        counter: UInt,
        flags: UInt,
        signatureType: SignatureType = SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED,
    ): Metadata {
        require(domain.value in 0..DOMAIN_MAX_VALUE) { "domain out of range" }
        require(epoch.size == EPOCH_SIZE) { "epoch must be $EPOCH_SIZE bytes" }
        require(expiresAt <= EPOCH_LENGTH_SECONDS) { "out of bounds expiration time" }
        val meta =
            Metadata()
                .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(signatureType.value.toByte()))
                .add(Tag.TAG_DOMAIN, byteArrayOf(domain.value.toByte()))
                .add(Tag.TAG_PERSONALIZATION, personalization)
                .add(Tag.TAG_EPOCH, epoch)
                .addUInt32(Tag.TAG_EXPIRES_AT, expiresAt)
                .addUInt32(Tag.TAG_COUNTER, counter)
        if (flags > 0u) meta.addUInt32(Tag.TAG_FLAGS, flags)
        return meta
    }
}
