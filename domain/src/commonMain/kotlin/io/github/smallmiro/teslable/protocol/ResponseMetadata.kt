// Ported from vehicle-command@a4b43c1 internal/authentication/peer.go (Apache-2.0) — Peer.responseMetadata
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import com.tesla.generated.universalmessage.Domain

/** 응답 메타데이터 조립 (03-protocol.md §9.2). */
public object ResponseMetadata {
    /** 응답 메타데이터: FLAGS는 0이어도 **항상** 포함. AAD = SHA256(serialize()) (SDD §12 불일치 1 참조). */
    @Suppress("LongParameterList") // TLV 필드 하나당 파라미터 하나 — Go peer.go responseMetadata와 1:1 대응.
    public fun build(
        fromDomain: Domain,
        personalization: ByteArray,
        counter: UInt,
        flags: UInt,
        requestHash: ByteArray,
        fault: UInt,
    ): Metadata =
        Metadata()
            .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE.value.toByte()))
            .add(Tag.TAG_DOMAIN, byteArrayOf(fromDomain.value.toByte()))
            .add(Tag.TAG_PERSONALIZATION, personalization)
            .addUInt32(Tag.TAG_COUNTER, counter)
            .addUInt32(Tag.TAG_FLAGS, flags)
            .add(Tag.TAG_REQUEST_HASH, requestHash)
            .addUInt32(Tag.TAG_FAULT, fault)
}
