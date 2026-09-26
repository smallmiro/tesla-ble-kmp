package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.Tag
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(InternalTeslableApi::class)
class MetadataTest {
    @Test
    fun serializesHandshakeMetadataTlv() {
        val meta =
            Metadata()
                .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(0x06))
                .add(Tag.TAG_PERSONALIZATION, ProtocolVectors.VIN.encodeToByteArray())
                .add(Tag.TAG_CHALLENGE, ProtocolVectors.CHALLENGE.hexToBytes())
        assertEquals(ProtocolVectors.HANDSHAKE_METADATA, meta.serialize().toHex())
    }

    @Test
    fun appendsMessageAfterEndMarker() {
        val meta = Metadata().add(Tag.TAG_COUNTER, byteArrayOf(0, 0, 0, 100))
        assertEquals("050400000064ff0102", meta.serialize(byteArrayOf(0x01, 0x02)).toHex())
    }

    @Test
    fun encodesUInt32BigEndian() {
        assertEquals("050400000064ff", Metadata().addUInt32(Tag.TAG_COUNTER, 100u).serialize().toHex())
        assertEquals("0504ffffffffff", Metadata().addUInt32(Tag.TAG_COUNTER, 0xFFFFFFFFu).serialize().toHex())
    }

    @Test
    fun rejectsOutOfOrderTags() { // Go TestOutOfOrder
        val meta =
            Metadata()
                .add(Tag.TAG_DOMAIN, "hello".encodeToByteArray())
                .add(Tag.TAG_PERSONALIZATION, "world".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { meta.add(Tag.TAG_DOMAIN, "world".encodeToByteArray()) }
    }

    @Test
    fun allowsRepeatingTheSameTag() { // Go: tag < last 만 오류
        Metadata().add(Tag.TAG_DOMAIN, byteArrayOf(1)).add(Tag.TAG_DOMAIN, byteArrayOf(2))
    }

    @Test
    fun rejectsValueLongerThan255() { // Go TestValueTooLong
        assertFailsWith<MetadataFieldTooLongException> { Metadata().add(Tag.TAG_DOMAIN, ByteArray(256)) }
        Metadata().add(Tag.TAG_DOMAIN, ByteArray(255))
    }

    @Test
    fun skipsNullValues() {
        assertEquals("ff", Metadata().add(Tag.TAG_DOMAIN, null).serialize().toHex())
    }

    @Test
    fun sha256ChecksumMatchesGoTestVector() { // Go TestCheckSum
        val meta =
            Metadata()
                .add(Tag.TAG_SIGNATURE_TYPE, byteArrayOf(0x05))
                .add(Tag.TAG_DOMAIN, byteArrayOf(0x02))
                .add(Tag.TAG_PERSONALIZATION, "testVIN".encodeToByteArray())
                .add(Tag.TAG_EPOCH, ProtocolVectors.GO_CHECKSUM_EPOCH.hexToBytes())
                .add(Tag.TAG_EXPIRES_AT, byteArrayOf(0x00, 0x00, 0x0e, 0x74))
                .add(Tag.TAG_COUNTER, byteArrayOf(0x00, 0x00, 0x05, 0x3a))
        assertEquals(ProtocolVectors.GO_CHECKSUM_SHA256, TestCrypto.primitives.sha256(meta.serialize()).toHex())
    }
}
