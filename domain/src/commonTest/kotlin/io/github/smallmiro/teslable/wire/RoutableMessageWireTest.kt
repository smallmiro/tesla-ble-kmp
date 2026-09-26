package io.github.smallmiro.teslable.wire

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.InformationRequest
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.UnsignedMessage
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RoutableMessageWireTest {
    // tesla-control -ble -debug list-keys 의 TX (03-protocol.md §15)
    private val listKeysTx =
        "320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805"

    @Test
    fun decodesListKeysRequestFromProtocolDoc() {
        val message = RoutableMessage.ADAPTER.decode(listKeysTx.decodeHex())
        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, message.to_destination?.domain)
        assertEquals("0a7962c10d38b61dd2a7722780a4f096", message.from_destination?.routing_address?.hex())
        assertEquals("05514f57616bcc81a8ce0f9d7b483229", message.uuid.hex())
        assertEquals("0a020805", message.protobuf_message_as_bytes?.hex())

        val payload = UnsignedMessage.ADAPTER.decode(assertNotNull(message.protobuf_message_as_bytes))
        assertEquals(
            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO,
            payload.VCSEC_InformationRequest?.informationRequestType,
        )
    }

    @Test
    fun encodesAndDecodesStructurallyEqualMessage() {
        // Go 인코더는 uuid(51)를 payload(10)보다 앞에 놓으므로 바이트 동일성은 기대하지 않는다 (로드맵 공통 규칙)
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                from_destination = Destination(routing_address = "0a7962c10d38b61dd2a7722780a4f096".decodeHex()),
                protobuf_message_as_bytes = "0a020805".decodeHex(),
                uuid = "05514f57616bcc81a8ce0f9d7b483229".decodeHex(),
            )
        val bytes = RoutableMessage.ADAPTER.encode(message)
        assertEquals(message, RoutableMessage.ADAPTER.decode(bytes))
        assertEquals(RoutableMessage.ADAPTER.decode(listKeysTx.decodeHex()), message)
    }

    @Test
    fun encodesGetWhitelistInfoPayloadLikeGo() {
        val payload =
            UnsignedMessage(
                VCSEC_InformationRequest =
                    InformationRequest(
                        informationRequestType = InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO,
                    ),
            )
        assertEquals("0a020805", UnsignedMessage.ADAPTER.encode(payload).let { okio.ByteString.of(*it).hex() })
    }
}
