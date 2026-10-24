package dev.mirolab.teslable.application.observe

import com.squareup.wire.ProtoReader
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.AuthenticationRequest
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistEntryInfo
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.protocol.decodeOrNull
import dev.mirolab.teslable.util.toHex
import okio.Buffer
import okio.ByteString
import okio.IOException

/** 관찰 지점(설계 구체화 1). [label]이 로그 줄의 머리다. */
internal enum class ObservedAt(
    val label: String,
) {
    UNPARSEABLE("drop:unparseable"),
    MISSING_SOURCE("drop:missing-source"),
    MISSING_DESTINATION("drop:missing-destination"),
    TO_DOMAIN("drop:to-domain"),
    MALFORMED("drop:malformed"),
    NO_HANDLER("drop:no-handler"),
    HANDLER_CLOSED("drop:handler-closed"),
    QUEUE_FULL("drop:queue-full"),
    VCSEC_REPLY("reply:vcsec"),
}

/** 라우팅 주소의 주인. */
internal enum class AddressOwner(
    val label: String,
) {
    /** 이 디스패처의 Infotainment 주소. */
    SELF("self"),

    /** 닫히지 않은 요청의 주소. */
    OPEN_REQUEST("open-request"),

    /** 그 밖(다른 클라이언트, 이미 닫힌 요청). */
    OTHER("other"),
}

/**
 * 원본에 없음 — 차량 푸시 관찰 로그 한 줄(M4 Task 1, 설계 구체화 1). M5 `vehicle.events` 설계의 근거를 모은다.
 * 바이트 필드는 길이만, 공개키는 SHA-1 앞 4바이트만, 암호문은 길이만 쓴다(절대 규칙 8, FR-112). VIN은 메시지에 없다.
 */
@OptIn(InternalTeslableApi::class)
@Suppress("TooManyFunctions") // 메시지 종류마다 요약 함수 하나씩: 쪼개면 오히려 읽기 어렵다
internal class ObservationLog(
    private val crypto: CryptoPrimitives,
) {
    private val detail = ObservationDetail(crypto)

    /** RoutableMessage 한 통. [size]는 받은 프레임 바이트 수, [owner]는 목적지가 라우팅 주소일 때만. */
    fun routable(
        at: ObservedAt,
        message: RoutableMessage,
        size: Int,
        owner: AddressOwner?,
    ): String =
        buildString {
            append('[')
                .append(at.label)
                .append("] ")
                .append(size)
                .append('B')
            append(" from=").append(destination(message.from_destination, owner = null))
            append(" to=").append(destination(message.to_destination, owner))
            append(" request_uuid=").append(if (message.request_uuid.size == 0) "empty" else "${message.request_uuid.size}B")
            message.signedMessageStatus?.let {
                append(" status=").append(it.operation_status.name).append('/').append(it.signed_message_fault.name)
            }
            if (message.session_info_request != null) append(" session_info_request")
            message.session_info?.let { append(" session_info=").append(it.size).append('B') }
            append(" payload=").append(payload(message))
        }

    /** 같은 바이트를 감싸지 않은 `FromVCSECMessage`로 다시 해석한 결과(처리는 하지 않는다). */
    fun reinterpret(bytes: ByteArray): String = FromVCSECMessage.ADAPTER.decodeOrNull(bytes)?.let(::vcsec) ?: "undecodable"

    /** RoutableMessage로 해석되지 않은 프레임. */
    fun unparseable(bytes: ByteArray): String = "[${ObservedAt.UNPARSEABLE.label}] ${bytes.size}B reinterpreted=${reinterpret(bytes)}"

    /** `FromVCSECMessage`의 oneof 종류와 내용 요약. */
    fun vcsec(
        from: FromVCSECMessage,
        detailed: Boolean = false,
    ): String {
        val parts = mutableListOf<String>()
        from.vehicleStatus?.let { parts += "vehicleStatus{${vehicleStatus(it, detailed)}}" }
        from.authenticationRequest?.let { parts += "authenticationRequest{${authenticationRequest(it)}}" }
        from.commandStatus?.let { parts += "commandStatus{${commandStatus(it)}}" }
        from.whitelistInfo?.let {
            parts += "whitelistInfo{entries=${it.numberOfEntries}, slotMask=0x${it.slotMask.toUInt().toString(
                HEX,
            )}}"
        }
        from.whitelistEntryInfo?.let { parts += "whitelistEntryInfo{${entry(it)}}" }
        from.nominalError?.let { parts += "nominalError{${it.genericError.name}}" }
        unknownFields(from.unknownFields, if (detailed) FROM_VCSEC_DETAIL_FIELDS else emptySet()).takeIf { it.isNotEmpty() }?.let {
            parts += "unknownFields=$it"
        }
        return "FromVCSECMessage{${parts.joinToString(", ")}}"
    }

    /** 상세 디코드의 입구: VCSEC가 보낸 원래 평문 브로드캐스트(서명·세션 정보·요청 UUID 없음)만. */
    private fun isPlainBroadcast(message: RoutableMessage): Boolean =
        message.from_destination?.domain == Domain.DOMAIN_VEHICLE_SECURITY &&
            message.to_destination?.domain == Domain.DOMAIN_BROADCAST &&
            message.request_uuid.size == 0 &&
            message.signature_data == null &&
            message.session_info == null

    /** 도착 순서의 unknownFields 요약. [detailed] 번호만 값을 풀고 나머지는 번호만. */
    private fun unknownFields(
        bytes: ByteString,
        detailed: Set<Int>,
    ): List<String> = if (detailed.isEmpty()) unknownFieldNumbers(bytes).map(Int::toString) else detail.unknownFields(bytes, detailed)

    private fun destination(
        d: Destination?,
        owner: AddressOwner?,
    ): String {
        if (d == null) return "none"
        d.routing_address?.let { address -> return "address(${address.size}B" + (owner?.let { ", ${it.label}" } ?: "") + ")" }
        d.domain?.let { return "domain(${it.name})" }
        return "empty"
    }

    private fun payload(message: RoutableMessage): String {
        val bytes = message.protobuf_message_as_bytes ?: return "none"
        val signature = message.signature_data
        if (signature != null && signature.session_info_tag == null) return "encrypted(${bytes.size}B)"
        val raw = bytes.toByteArray()
        return when (message.from_destination?.domain) {
            Domain.DOMAIN_VEHICLE_SECURITY -> {
                FromVCSECMessage.ADAPTER.decodeOrNull(raw)?.let { vcsec(it, detailed = isPlainBroadcast(message)) }
                    ?: "undecodable(${bytes.size}B)"
            }

            Domain.DOMAIN_INFOTAINMENT -> {
                Response.ADAPTER.decodeOrNull(raw)?.let(::infotainment) ?: "undecodable(${bytes.size}B)"
            }

            else -> {
                "${bytes.size}B"
            }
        }
    }

    // proto3 enum은 빠진 것과 0번 값을 구분할 수 없다(lock=UNLOCKED, sleep/presence=UNKNOWN). 메시지 필드만 absent로 알 수 있다.
    private fun vehicleStatus(
        s: VehicleStatus,
        detailed: Boolean,
    ): String =
        buildString {
            append("lock=").append(s.vehicleLockState.name)
            append(", sleep=").append(s.vehicleSleepStatus.name)
            append(", presence=").append(s.userPresence.name)
            // Teslemetry 스냅샷 이름(TESLEMETRY-EXT, 실차 의미 미확인 — ADR-0002 개정 2026-10-18)
            append(", uiDesire=").append(s.uiDesire.name)
            append(", gear=").append(s.gear.name)
            s.closureStatuses?.let { append(", closures={").append(closures(it)).append('}') }
            s.detailedClosureStatus?.let { append(", tonneauPercentOpen=").append(it.tonneauPercentOpen) }
            val absent =
                listOfNotNull(
                    "closureStatuses".takeIf { s.closureStatuses == null },
                    "detailedClosureStatus".takeIf { s.detailedClosureStatus == null },
                )
            if (absent.isNotEmpty()) append(", absent=").append(absent)
            unknownFields(s.unknownFields, if (detailed) VEHICLE_STATUS_DETAIL_FIELDS else emptySet())
                .takeIf { it.isNotEmpty() }
                ?.let { append(", unknownFields=").append(it) }
        }

    private fun closures(c: ClosureStatuses): String =
        listOf(
            "frontDriverDoor" to c.frontDriverDoor,
            "frontPassengerDoor" to c.frontPassengerDoor,
            "rearDriverDoor" to c.rearDriverDoor,
            "rearPassengerDoor" to c.rearPassengerDoor,
            "rearTrunk" to c.rearTrunk,
            "frontTrunk" to c.frontTrunk,
            "chargePort" to c.chargePort,
            "tonneau" to c.tonneau,
        ).joinToString(", ") { (name, state) -> "$name=${state.name.removePrefix("CLOSURESTATE_")}" }

    /** Teslemetry 스냅샷의 `FromVCSECMessage` 3(TESLEMETRY-EXT, 필드 이름 미확인). 토큰은 길이만(절대 규칙 8). */
    private fun authenticationRequest(r: AuthenticationRequest): String =
        buildString {
            append("requestedLevel=").append(r.requestedLevel.name)
            append(", reasons=").append(r.reasonsForAuth.map { it.name })
            r.sessionInfo?.let { append(", token=").append(it.token.size).append('B') }
            unknownFieldNumbers(r.unknownFields).takeIf { it.isNotEmpty() }?.let { append(", unknownFields=").append(it) }
        }

    private fun commandStatus(s: CommandStatus): String =
        buildString {
            append("op=").append(s.operationStatus.name)
            s.whitelistOperationStatus?.let { append(", whitelist=").append(it.whitelistOperationInformation.name) }
            if (s.signedMessageStatus != null) append(", signedMessageStatus")
        }

    private fun entry(e: WhitelistEntryInfo): String =
        buildString {
            append("slot=").append(e.slot).append(", role=").append(e.keyRole.name)
            e.metadataForKey?.let { append(", formFactor=").append(it.keyFormFactor.name) }
            val raw = e.publicKey?.PublicKeyRaw?.takeIf { it.size > 0 }
            if (raw != null) {
                append(", key=sha1:").append(crypto.sha1(raw.toByteArray()).copyOf(KEY_PREFIX).toHex())
            } else {
                e.keyId?.publicKeySHA1?.takeIf { it.size >= KEY_PREFIX }?.let {
                    append(
                        ", keyId=sha1:",
                    ).append(it.substring(0, KEY_PREFIX).hex())
                }
            }
        }

    private fun infotainment(r: Response): String {
        val fields =
            listOfNotNull(
                "actionStatus".takeIf { r.actionStatus != null },
                "vehicleData".takeIf { r.vehicleData != null },
                "getSessionInfoResponse".takeIf { r.getSessionInfoResponse != null },
                "getNearbyChargingSites".takeIf { r.getNearbyChargingSites != null },
                "ping".takeIf { r.ping != null },
            )
        val unknown = unknownFieldNumbers(r.unknownFields)
        return "Response{fields=$fields" + (if (unknown.isEmpty()) "" else ", unknownFields=$unknown") + "}"
    }

    companion object {
        /** 관찰 로그 태그. 레벨은 항상 DEBUG. */
        const val TAG: String = "observe"
        private const val KEY_PREFIX = 4
        private const val HEX = 16
        private val FROM_VCSEC_DETAIL_FIELDS = setOf(48, 53)

        // 7(gear)은 스냅샷이 정의하고 브로드캐스트에서 실차 확인됐지만(14-lib B3.1), 정의 밖 값이 unknownFields로 오면 원시 varint를 남긴다. 8은 스냅샷에도 없다.
        private val VEHICLE_STATUS_DETAIL_FIELDS = setOf(7, 8)
    }
}

/** Wire `unknownFields`의 필드 번호(중복 제거, 나온 순서). 손상된 버퍼는 읽은 데까지. */
internal fun unknownFieldNumbers(bytes: ByteString): List<Int> {
    if (bytes.size == 0) return emptyList()
    val tags = mutableListOf<Int>()
    try {
        val reader = ProtoReader(Buffer().write(bytes))
        reader.forEachTag { tag ->
            tags += tag
            reader.skip()
        }
    } catch (ignored: IOException) {
        // 읽은 데까지만 남긴다
    }
    return tags.distinct()
}
