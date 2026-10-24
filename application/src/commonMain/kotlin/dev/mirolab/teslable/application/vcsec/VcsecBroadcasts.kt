package dev.mirolab.teslable.application.vcsec

import com.squareup.wire.ProtoReader
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.AuthenticationLevel_E
import com.tesla.generated.vcsec.FromVCSECMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.observe.isPlainBroadcast
import dev.mirolab.teslable.application.observe.unknownFieldNumbers
import dev.mirolab.teslable.application.observe.unknownFieldOccurrences
import dev.mirolab.teslable.model.AuthenticationLevel
import dev.mirolab.teslable.model.AuthenticationReason
import dev.mirolab.teslable.model.VehicleEvent
import dev.mirolab.teslable.protocol.decodeOrNull
import dev.mirolab.teslable.protocol.unknownVarint
import okio.Buffer
import okio.ByteString
import okio.IOException
import com.tesla.generated.vcsec.AuthenticationReason_E as WireReason
import com.tesla.generated.vcsec.AuthenticationRequest as WireAuthenticationRequest
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

/**
 * 원본에 없음 — 평문 VCSEC 브로드캐스트 프레임 → 공개 [VehicleEvent](FR-035, 설계 §5.1–5.2). 원본 Go는 이 프레임을 버리고
 * (`internal/dispatcher/dispatcher.go` `Destination_Domain` 분기 "Dropping message to …") 아무것도 내보내지 않는다.
 *
 * - 입력은 `Dispatcher.plainBroadcasts`의 원소(받은 `RoutableMessage` 프레임 전체)다. 같은 게이트([isPlainBroadcast])를 다시 확인해
 *   통과하지 못하거나 `RoutableMessage`로 디코딩되지 않는 프레임은 이벤트가 없다(빈 목록). 복호화는 하지 않는다(FR-112).
 * - `vehicleStatus`(1)는 [VehicleEvent.Status](매핑은 [VcsecInfoMapping.status], 조회와 같은 규칙), `authenticationRequest`(3)는
 *   [VehicleEvent.AuthenticationRequest]. 둘 다 있으면 이 순서로 둘 다, 둘 다 없거나 페이로드가 없거나 깨졌으면
 *   [VehicleEvent.Unrecognized] 하나. 한 프레임의 이벤트는 모두 같은 `raw`와 같은 `unknownFields`를 갖는다.
 * - Teslemetry 스냅샷은 3을 `FromVCSECMessage`의 oneof `sub_message`에 넣었다(공식 Go 스키마에는 3이 없어 모르는 필드다). Wire는
 *   oneof에서 마지막 멤버만 남기므로 1과 3의 바이트를 따로 모아 디코딩한다 — 같이 와도 어느 쪽도 잃지 않는다. 같은 필드가 여러 번
 *   오면 protobuf 규칙대로 합친다(바이트를 이어 붙여 한 번에 디코딩).
 * - "깨진 페이로드"의 기준은 Wire의 `FromVCSECMessage` 디코딩이다(관찰 로그의 `undecodable`과 같은 기준).
 * - `unknownFields`: [VehicleEvent.unknownFields]의 경로 규칙. 최상위는 이 함수가 필드를 하나씩 읽는 순서(도착 순서), 하위 메시지는
 *   Wire가 모은 `unknownFields` 순서. 반복은 그대로 둔다([unknownFieldOccurrences]).
 */
@OptIn(InternalTeslableApi::class)
internal object VcsecBroadcasts {
    private const val VEHICLE_STATUS_TAG = 1 // FromVCSECMessage.vehicleStatus
    private const val AUTHENTICATION_REQUEST_TAG = 3 // FromVCSECMessage.authenticationRequest (TESLEMETRY-EXT)
    private const val CLOSURE_STATUSES_TAG = 1 // VehicleStatus.closureStatuses
    private const val DETAILED_CLOSURE_STATUS_TAG = 5 // VehicleStatus.detailedClosureStatus
    private const val TOKEN_TAG = 2 // AuthenticationRequest.sessionInfo = AuthenticationRequestToken (이름 미확인)
    private const val REQUESTED_LEVEL_TAG = 3 // AuthenticationRequest.requestedLevel (이름 미확인)
    private const val REASONS_TAG = 4 // AuthenticationRequest.reasonsForAuth (이름 미확인)

    fun events(frame: ByteArray): List<VehicleEvent> {
        val message = RoutableMessage.ADAPTER.decodeOrNull(frame)
        if (message == null || !isPlainBroadcast(message)) return emptyList()
        val payload =
            message.protobuf_message_as_bytes?.let(::read)
                ?: return listOf(VehicleEvent.Unrecognized(frame, emptyList()))
        val events = mutableListOf<VehicleEvent>()
        payload.status?.let { events += VehicleEvent.Status(VcsecInfoMapping.status(it), frame, payload.unknownFields) }
        payload.request?.let { events += VehicleEvent.AuthenticationRequest(level(it), reasons(it), frame, payload.unknownFields) }
        return events.ifEmpty { listOf(VehicleEvent.Unrecognized(frame, payload.unknownFields)) }
    }

    private class Payload(
        val status: WireVehicleStatus?,
        val request: WireAuthenticationRequest?,
        val unknownFields: List<String>,
    )

    /** `FromVCSECMessage`를 최상위 필드 단위로 읽는다. Wire가 디코딩하지 못하는 페이로드는 null. */
    private fun read(payload: ByteString): Payload? {
        val whole = FromVCSECMessage.ADAPTER.decodeOrNull(payload.toByteArray()) ?: return null
        // 최상위 필드는 모두 메시지라 같은 번호가 아는 필드와 모르는 필드에 같이 나올 수 없다
        val unknownTopLevel = unknownFieldNumbers(whole.unknownFields).toSet()
        val status = Buffer()
        val request = Buffer()
        var hasStatus = false
        var hasRequest = false
        val paths = mutableListOf<String>()
        try {
            val reader = ProtoReader(Buffer().write(payload))
            reader.forEachTag { tag ->
                when (tag) {
                    VEHICLE_STATUS_TAG -> {
                        val bytes = reader.readBytes()
                        status.write(bytes)
                        hasStatus = true
                        paths += statusPaths(bytes)
                    }

                    AUTHENTICATION_REQUEST_TAG -> {
                        val bytes = reader.readBytes()
                        request.write(bytes)
                        hasRequest = true
                        paths += requestPaths(bytes)
                    }

                    else -> {
                        if (tag in unknownTopLevel) paths += tag.toString()
                        reader.skip()
                    }
                }
            }
        } catch (ignored: IOException) {
            return null // Wire가 이미 디코딩한 바이트라 오지 않는다
        }
        return Payload(
            status = if (hasStatus) WireVehicleStatus.ADAPTER.decodeOrNull(status.readByteArray()) else null,
            request = if (hasRequest) WireAuthenticationRequest.ADAPTER.decodeOrNull(request.readByteArray()) else null,
            unknownFields = paths,
        )
    }

    private fun statusPaths(bytes: ByteString): List<String> {
        val s = WireVehicleStatus.ADAPTER.decodeOrNull(bytes.toByteArray()) ?: return emptyList()
        val prefix = "$VEHICLE_STATUS_TAG"
        return paths(prefix, s.unknownFields) +
            paths("$prefix.$CLOSURE_STATUSES_TAG", s.closureStatuses?.unknownFields) +
            paths("$prefix.$DETAILED_CLOSURE_STATUS_TAG", s.detailedClosureStatus?.unknownFields)
    }

    private fun requestPaths(bytes: ByteString): List<String> {
        val r = WireAuthenticationRequest.ADAPTER.decodeOrNull(bytes.toByteArray()) ?: return emptyList()
        val prefix = "$AUTHENTICATION_REQUEST_TAG"
        return paths(prefix, r.unknownFields) + paths("$prefix.$TOKEN_TAG", r.sessionInfo?.unknownFields)
    }

    private fun paths(
        prefix: String,
        unknownFields: ByteString?,
    ): List<String> = unknownFields?.let { unknownFieldOccurrences(it).map { tag -> "$prefix.$tag" } }.orEmpty()

    private fun level(r: WireAuthenticationRequest): AuthenticationLevel {
        if (r.unknownFields.unknownVarint(REQUESTED_LEVEL_TAG) != null) return AuthenticationLevel.UNKNOWN
        return when (r.requestedLevel) {
            AuthenticationLevel_E.AUTHENTICATION_LEVEL_NONE -> AuthenticationLevel.NONE
            AuthenticationLevel_E.AUTHENTICATION_LEVEL_UNLOCK -> AuthenticationLevel.UNLOCK
            AuthenticationLevel_E.AUTHENTICATION_LEVEL_DRIVE -> AuthenticationLevel.DRIVE
        }
    }

    /** 아는 값은 받은 순서로, 스키마 밖 값(Wire가 `unknownFields`로 옮긴 것)은 하나마다 [AuthenticationReason.UNKNOWN]으로 그 뒤에. */
    private fun reasons(r: WireAuthenticationRequest): List<AuthenticationReason> =
        r.reasonsForAuth.map(::reason) +
            List(unknownFieldOccurrences(r.unknownFields).count { it == REASONS_TAG }) { AuthenticationReason.UNKNOWN }

    private fun reason(value: WireReason): AuthenticationReason =
        when (value) {
            WireReason.AUTHENTICATIONREASON_NOT_DOCUMENTED -> AuthenticationReason.NOT_DOCUMENTED
            WireReason.AUTHENTICATIONREASON_IDENTIFICATION -> AuthenticationReason.IDENTIFICATION
            WireReason.AUTHENTICATIONREASON_POWER_ON_VEHICLE_REQUEST -> AuthenticationReason.POWER_ON_VEHICLE_REQUEST
            WireReason.AUTHENTICATIONREASON_GTW_REQUEST -> AuthenticationReason.GTW_REQUEST
            WireReason.AUTHENTICATIONREASON_UI_UNLOCK_PASSIVE_AUTH -> AuthenticationReason.UI_UNLOCK_PASSIVE_AUTH
            WireReason.AUTHENTICATIONREASON_PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL -> AuthenticationReason.PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL
            WireReason.AUTHENTICATIONREASON_PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL -> AuthenticationReason.PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL
            WireReason.AUTHENTICATIONREASON_PASSIVE_UNLOCK_AUTOPRESENT_DOOR -> AuthenticationReason.PASSIVE_UNLOCK_AUTOPRESENT_DOOR
            WireReason.AUTHENTICATIONREASON_ENTERED_HIGHER_AUTH_ZONE -> AuthenticationReason.ENTERED_HIGHER_AUTH_ZONE
            WireReason.AUTHENTICATIONREASON_WALK_UP_UNLOCK -> AuthenticationReason.WALK_UP_UNLOCK
            WireReason.AUTHENTICATIONREASON_IMMOBILIZER -> AuthenticationReason.IMMOBILIZER
        }
}
