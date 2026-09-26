// Ported from vehicle-command@a4b43c1 internal/authentication/peer.go (Apache-2.0) — RequestID
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage

private const val VCSEC_HMAC_TAG_LENGTH = 16

/** 요청 서명으로부터 응답 메타데이터의 REQUEST_HASH를 구성 (03-protocol.md §9.2, Go `RequestID`). */
public object RequestHash {
    /**
     * `sigType(1B) || tag`. HMAC 요청이 VCSEC 대상이면 태그를 16바이트로 절단.
     * @throws IllegalArgumentException HMAC+VCSEC 조합인데 [tag]가 16바이트보다 짧으면 발생한다
     *   (묵시적 0-패딩 대신 명시적으로 실패한다).
     */
    public fun of(
        signatureType: SignatureType,
        tag: ByteArray,
        toDomain: Domain,
    ): ByteArray {
        val truncated =
            if (signatureType == SignatureType.SIGNATURE_TYPE_HMAC_PERSONALIZED && toDomain == Domain.DOMAIN_VEHICLE_SECURITY) {
                require(tag.size >= VCSEC_HMAC_TAG_LENGTH) { "HMAC tag too short" }
                tag.copyOfRange(0, VCSEC_HMAC_TAG_LENGTH)
            } else {
                tag
            }
        return byteArrayOf(signatureType.value.toByte()) + truncated
    }

    /** 서명 데이터가 없거나 지원하지 않는 서명 타입이면 null (Go `RequestID` 반환 nil). */
    public fun of(message: RoutableMessage): ByteArray? {
        val signature = message.signature_data ?: return null
        val domain = message.to_destination?.domain ?: Domain.DOMAIN_BROADCAST
        signature.AES_GCM_Personalized_data?.let {
            return of(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED, it.tag.toByteArray(), domain)
        }
        signature.HMAC_Personalized_data?.let {
            return of(SignatureType.SIGNATURE_TYPE_HMAC_PERSONALIZED, it.tag.toByteArray(), domain)
        }
        return null
    }
}
