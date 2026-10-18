package dev.mirolab.teslable.application.observe

import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleStatus
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.toHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalTeslableApi::class)
class ObservationDetailTest {
    private val log = ObservationLog(TestCrypto.primitives)

    // 마스킹 검사용 가짜 VIN(실제 VIN 아님)
    private val vin = "5YJ30123456789ABC"
    private val maskedVin = "5YJ**********9ABC"

    private fun varint(value: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            out += ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        out += v.toByte()
        return out.toByteArray()
    }

    private fun len(
        tag: Int,
        body: ByteArray,
    ) = varint((tag.toLong() shl 3) or 2) + varint(body.size.toLong()) + body

    private fun vint(
        tag: Int,
        value: Long,
    ) = varint(tag.toLong() shl 3) + varint(value)

    private fun fixed32(
        tag: Int,
        value: Int,
    ) = varint((tag.toLong() shl 3) or 5) + ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun fixed64(
        tag: Int,
        value: Long,
    ) = varint((tag.toLong() shl 3) or 1) + ByteArray(8) { (value ushr (8 * it)).toByte() }

    private fun text(s: String) = s.encodeToByteArray()

    private fun frame(
        payload: ByteArray,
        to: Destination? = Destination(domain = Domain.DOMAIN_BROADCAST),
        uuid: ByteArray = ByteArray(0),
        signature: SignatureData? = null,
    ): String {
        val message =
            RoutableMessage(
                from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                to_destination = to,
                request_uuid = uuid.toByteString(),
                signature_data = signature,
                protobuf_message_as_bytes = payload.toByteString(),
            )
        return log.routable(ObservedAt.NO_HANDLER, message, size = 99, owner = null)
    }

    private fun top(field: ByteArray) = frame(field)

    private fun detail(line: String) = line.substringAfter("unknownFields=")

    @Test
    fun field48ShowsVarintAndAsciiChild() {
        val line = top(len(48, vint(1, 3) + len(2, text("ABCD"))))
        assertTrue(line.endsWith("unknownFields=[48:len(8){1:varint=3(0x3), 2:len(4)\"ABCD\"}]}"), line)
    }

    @Test
    fun fixedWidthsAreHexAndVarintIsUnsigned() {
        val body = fixed32(1, 0x2A) + fixed64(2, 0x0102030405060708L) + vint(3, -1L)
        val d = detail(top(len(48, body)))
        assertTrue(d.contains("1:fixed32=0x0000002a"), d)
        assertTrue(d.contains("2:fixed64=0x0102030405060708"), d)
        assertTrue(d.contains("3:varint=18446744073709551615(0xffffffffffffffff)"), d)
    }

    @Test
    fun nestedMessagesRecurseAndRepeatedFieldsKeepOrder() {
        val inner = vint(1, 7)
        val body = len(1, inner) + vint(2, 9) + vint(2, 5)
        val d = detail(top(len(53, body)))
        assertEquals("[53:len(${body.size}){1:len(2){1:varint=7(0x7)}, 2:varint=9(0x9), 2:varint=5(0x5)}]}", d)
    }

    @Test
    fun nonProtobufNonAsciiBytesAreHex() {
        val d = detail(top(len(48, byteArrayOf(0xFF.toByte(), 0x00, 0x01))))
        assertTrue(d.startsWith("[48:len(3)0xff0001"), d)
    }

    @Test
    fun asciiControlCharsAreEscaped() {
        val d = detail(top(len(48, text("hello\nworld"))))
        assertTrue(d.contains("\"hello\\nworld\""), d)
        assertFalse(d.contains('\n'))
    }

    @Test
    fun vehicleStatusFields7And8AreDecoded() {
        val status = VehicleStatus(vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED).encode() + len(7, vint(1, 2)) + vint(8, 6)
        val line = top(len(1, status))
        assertTrue(line.contains("unknownFields=[7:len(2){1:varint=2(0x2)}, 8:varint=6(0x6)]"), line)
    }

    @Test
    fun otherUnknownFieldsStayNumberOnly() {
        val line = top(len(48, vint(1, 1)) + vint(9, 4) + len(12, text("zz")))
        assertTrue(line.endsWith("unknownFields=[48:len(2){1:varint=1(0x1)}, 9, 12]}"), line)
    }

    @Test
    fun depthLimitIsTruncated() {
        // 48 -> 1 -> 1 -> 1 -> 1 : 깊이 3을 넘는다
        val deep = len(1, len(1, len(1, len(1, vint(1, 1)))))
        val d = detail(top(len(48, deep)))
        assertTrue(d.contains("truncated"), d)
    }

    @Test
    fun depthBoundaryDecodesThreeNestedLevelsAndTruncatesTheFourth() {
        val three = len(1, len(1, len(1, vint(1, 1))))
        val d3 = detail(top(len(48, three)))
        assertEquals("[48:len(${three.size}){1:len(${three.size - 2}){1:len(${three.size - 4}){1:len(2){1:varint=1(0x1)}}}}]}", d3)
        val four = len(1, three)
        val d4 = detail(top(len(48, four)))
        assertTrue(d4.contains("1:len(2, truncated)"), d4)
        assertFalse(d4.contains("varint=1"), d4)
    }

    @Test
    fun fieldCountLimitIsTruncated() {
        val many = (1..65).map { vint(it, 1) }.reduce { a, b -> a + b }
        val d = detail(top(len(48, many)))
        assertTrue(d.startsWith("[48:len(${many.size}, truncated)"), d)
    }

    @Test
    fun lengthLimitIsTruncated() {
        val d = detail(top(len(48, ByteArray(257) { 0x41 })))
        assertEquals("[48:len(257, truncated)]}", d)
    }

    @Test
    fun corruptTopLevelUnknownFieldsAreInvalid() {
        // 필드 48이 선언한 길이(100)가 실제 데이터보다 길다
        val corrupt = varint((48L shl 3) or 2) + varint(100) + byteArrayOf(1, 2, 3)
        val d = detail(top(corrupt))
        assertFalse(d.contains("0x010203"), d)
    }

    @Test
    fun vinInAsciiIsMasked() {
        val line = top(len(48, text(vin)))
        assertFalse(line.contains(vin), line)
        assertTrue(line.contains(maskedVin), line)
    }

    @Test
    fun vinInRawBytesIsMaskedBeforeHex() {
        val bytes = byteArrayOf(0x01, 0x00) + text(vin) + byteArrayOf(0x00)
        val line = top(len(48, bytes))
        assertFalse(line.contains(vin), line)
        assertFalse(line.contains(text(vin).toHex()), line)
        assertTrue(line.contains(text(maskedVin).toHex()), line)
    }

    @Test
    fun vinInNestedChildIsMasked() {
        val line = top(len(48, len(2, text("x" + vin + "y"))))
        assertFalse(line.contains(vin), line)
        assertTrue(line.contains("x$maskedVin" + "y"), line)
    }

    @Test
    fun keyLikeBlobsAreRedacted() {
        for (size in listOf(16, 32, 33, 64, 65)) {
            val blob = ByteArray(size) { (0x80 + it).toByte() }
            val line = top(len(48, len(1, blob)))
            assertTrue(line.contains("1:redacted(${size}B)") || (size == 65 && line.contains("sha1:")), line)
            assertFalse(line.contains(blob.toHex()), line)
        }
    }

    @Test
    fun uncompressedPublicKeyShowsSha1PrefixOnly() {
        val key = byteArrayOf(0x04) + ByteArray(64) { it.toByte() }
        val prefix =
            TestCrypto.primitives
                .sha1(key)
                .copyOf(4)
                .toHex()
        val line = top(len(48, len(1, key)))
        assertTrue(line.contains("1:sha1:$prefix"), line)
        assertFalse(line.contains(key.toHex()), line)
    }

    @Test
    fun topLevelKeyLikeFieldIsRedacted() {
        val line = top(len(53, ByteArray(32) { 0x55 }))
        assertTrue(line.endsWith("unknownFields=[53:redacted(32B)]}"), line)
    }

    @Test
    fun nonBroadcastDestinationKeepsNumberOnly() {
        val payload = len(48, vint(1, 3))
        val line = frame(payload, to = Destination(routing_address = ByteArray(16).toByteString()))
        assertTrue(line.endsWith("unknownFields=[48]}"), line)
    }

    @Test
    fun requestUuidOrSignatureKeepsNumberOnly() {
        val payload = len(48, vint(1, 3))
        assertTrue(frame(payload, uuid = ByteArray(16) { 1 }).endsWith("unknownFields=[48]}"))
        val sig = SignatureData(session_info_tag = null)
        val encrypted = frame(payload, signature = sig)
        assertFalse(encrypted.contains("varint"), encrypted)
    }

    @Test
    fun unwrappedReinterpretationKeepsNumberOnly() {
        val text = log.reinterpret(len(48, vint(1, 3)))
        assertEquals("FromVCSECMessage{unknownFields=[48]}", text)
    }
}
