package dev.mirolab.teslable

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequest
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.UnsignedMessage
import dev.mirolab.teslable.ble.GattProbe
import dev.mirolab.teslable.crypto.platformCryptoPrimitives
import dev.mirolab.teslable.crypto.platformRandomSource
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.RandomSource
import dev.mirolab.teslable.protocol.decodeOrNull
import dev.mirolab.teslable.transport.LocalName
import kotlinx.coroutines.CancellationException
import okio.ByteString.Companion.toByteString

/** M3 Task 1 실차 스파이크 진입점(ADR-0003). M3 Task 15 마무리에서 지운다 — 앱 코드에서 쓰지 않는다. */
@OptIn(InternalTeslableApi::class)
public object TeslableDiagnostics {
    /** [vin] 차량에 인증 없는 `GET_WHITELIST_INFO`를 보내고 과정을 [onLine]으로 알린다. */
    @Suppress("TooGenericExceptionCaught") // 스파이크: Swift로 예외가 새면 앱이 죽으므로 전부 로그로 바꾼다
    public suspend fun probe(
        vin: String,
        forceIndication: Boolean,
        onLine: (String) -> Unit,
    ) {
        val parsed = runCatching { Vin(vin) }.getOrNull()
        if (parsed == null) {
            onLine("invalid VIN")
            return
        }
        try {
            val localName = LocalName.of(parsed, platformCryptoPrimitives())
            GattProbe.run(
                localName = localName.value,
                request = ProbeRequest.build(platformRandomSource()),
                forceIndication = forceIndication,
                log = onLine,
                onMessage = { onLine(ProbeRequest.describe(it)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLine("error: ${e::class.simpleName}: ${e.message}")
        }
    }
}

/** 프로브 요청·응답 요약(Go `getVCSECInfo` + `getReceiver` 모양, 인증 없음). */
@OptIn(InternalTeslableApi::class)
internal object ProbeRequest {
    private const val ADDRESS_LENGTH = 16
    private const val UUID_LENGTH = 16
    private const val FLAG_ENCRYPT_RESPONSE = 2
    private const val HEX_RADIX = 16

    fun build(random: RandomSource): ByteArray {
        val payload =
            UnsignedMessage(
                VCSEC_InformationRequest =
                    InformationRequest(informationRequestType = InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO),
            ).encode().toByteString()
        return RoutableMessage(
            to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
            from_destination = Destination(routing_address = random.nextBytes(ADDRESS_LENGTH).toByteString()),
            protobuf_message_as_bytes = payload,
            uuid = random.nextBytes(UUID_LENGTH).toByteString(),
            flags = FLAG_ENCRYPT_RESPONSE,
        ).encode()
    }

    fun describe(message: ByteArray): String {
        val routable = RoutableMessage.ADAPTER.decodeOrNull(message) ?: return "RX message: undecodable (${message.size}B)"
        val from = routable.protobuf_message_as_bytes?.let { FromVCSECMessage.ADAPTER.decodeOrNull(it.toByteArray()) }
        val info = from?.whitelistInfo
        return when {
            info != null -> "RX whitelistInfo: entries=${info.numberOfEntries} slotMask=0x${info.slotMask.toUInt().toString(HEX_RADIX)}"
            routable.signedMessageStatus != null -> "RX status: ${routable.signedMessageStatus}"
            else -> "RX message: ${message.size}B"
        }
    }
}
