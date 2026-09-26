// Ported from vehicle-command@a4b43c1 internal/authentication/metadata.go (Apache-2.0)
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.Tag

/**
 * 인증 필드가 255바이트를 넘음 (Go `ErrMetadataFieldTooLong`). 런타임 오류 — VIN 등 입력에서 발생 가능.
 */
public class MetadataFieldTooLongException : IllegalArgumentException("metadata fields can't be more than 255 bytes long")

/**
 * 메타데이터 TLV 직렬화: `tag(1) || len(1) || value`, 태그 오름차순, 마지막 `0xFF`.
 * Go 구현은 해시 컨텍스트에 바로 쓰지만 결과 바이트는 같다. 해시는 호출자가 `serialize()`에 적용한다.
 */
public class Metadata {
    private var buffer = ByteArray(0)
    private var last = -1

    /**
     * [value]가 null이면 건너뛴다. 태그가 이전보다 작으면 프로그래밍 오류(IllegalArgumentException).
     */
    public fun add(
        tag: Tag,
        value: ByteArray?,
    ): Metadata {
        require(tag.value >= last) { "metadata items need to be added in increasing tag order" }
        if (value == null) return this
        if (value.size > MAX_VALUE_LENGTH) throw MetadataFieldTooLongException()
        last = tag.value
        buffer += byteArrayOf(tag.value.toByte(), value.size.toByte()) + value
        return this
    }

    /**
     * UInt32를 빅엔디안으로 인코딩하여 추가한다.
     */
    public fun addUInt32(
        tag: Tag,
        value: UInt,
    ): Metadata =
        add(
            tag,
            byteArrayOf(
                (value shr BYTE_3_SHIFT).toByte(),
                (value shr BYTE_2_SHIFT).toByte(),
                (value shr BYTE_1_SHIFT).toByte(),
                value.toByte(),
            ),
        )

    /**
     * `metadata || 0xFF || message` — Go `Checksum(message)`의 해시 입력.
     */
    public fun serialize(message: ByteArray = EMPTY): ByteArray = buffer + END_MARKER + message

    private companion object {
        const val MAX_VALUE_LENGTH = 255
        const val BYTE_3_SHIFT = 24
        const val BYTE_2_SHIFT = 16
        const val BYTE_1_SHIFT = 8
        val EMPTY = ByteArray(0)
        val END_MARKER = byteArrayOf(Tag.TAG_END.value.toByte())
    }
}
