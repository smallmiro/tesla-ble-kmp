package io.github.smallmiro.teslable

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.WhitelistInfo
import io.github.smallmiro.teslable.testing.FixedRandom
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalTeslableApi::class)
class ProbeRequestTest {
    @Test
    fun buildsUnauthenticatedWhitelistInfoRequestLikeGoListKeys() {
        val address = ByteArray(16) { 0x11 }
        val uuid = ByteArray(16) { 0x22 }
        val decoded = RoutableMessage.ADAPTER.decode(ProbeRequest.build(FixedRandom(address, uuid)))

        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, decoded.to_destination?.domain)
        assertEquals(address.toHex(), decoded.from_destination?.routing_address?.hex())
        assertEquals(uuid.toHex(), decoded.uuid.hex())
        assertEquals(2, decoded.flags) // vehicle.go:22 DefaultFlags = 1 << FLAG_ENCRYPT_RESPONSE (getReceiver가 인증 없는 요청에도 싣는다)
        // protocol.md:78의 실제 list-keys TX와 같은 페이로드(필드 단위 비교, 로드맵 공통 규칙)
        val goTx = RoutableMessage.ADAPTER.decode(PROTOCOL_DOC_LIST_KEYS_TX.hexToBytes())
        assertEquals(goTx.protobuf_message_as_bytes, decoded.protobuf_message_as_bytes)
        assertEquals("0a020805", decoded.protobuf_message_as_bytes?.hex())
        assertEquals(goTx.to_destination?.domain, decoded.to_destination?.domain)
    }

    @Test
    fun describesWhitelistInfoReply() {
        val payload = FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 3, slotMask = 0x7)).encode().toByteString()
        val reply = RoutableMessage(protobuf_message_as_bytes = payload).encode()
        assertEquals("RX whitelistInfo: entries=3 slotMask=0x7", ProbeRequest.describe(reply))
    }

    @Test
    fun describesUndecodableReply() {
        assertEquals("RX message: undecodable (1B)", ProbeRequest.describe(byteArrayOf(0xFF.toByte())))
    }

    private companion object {
        // pkg/protocol/protocol.md:78 — tesla-control -ble -debug list-keys의 TX(2023-12, flags 필드 도입 전 캡처)
        const val PROTOCOL_DOC_LIST_KEYS_TX =
            "320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805"
    }
}
