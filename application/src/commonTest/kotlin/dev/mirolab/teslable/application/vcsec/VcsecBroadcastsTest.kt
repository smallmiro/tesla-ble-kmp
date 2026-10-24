package dev.mirolab.teslable.application.vcsec

import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.AuthenticationLevel_E
import com.tesla.generated.vcsec.AuthenticationReason_E
import com.tesla.generated.vcsec.AuthenticationRequestToken
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.Gear_E
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import dev.mirolab.teslable.model.AuthenticationLevel
import dev.mirolab.teslable.model.AuthenticationReason
import dev.mirolab.teslable.model.ClosureState
import dev.mirolab.teslable.model.Gear
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.UserPresence
import dev.mirolab.teslable.model.VehicleEvent
import okio.Buffer
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import com.tesla.generated.vcsec.AuthenticationRequest as WireAuthenticationRequest
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

/**
 * FR-035(계획 Task 5): 평문 VCSEC 브로드캐스트 프레임 → 공개 [VehicleEvent]. 입력 모양은 실차 관찰(14-lib Part B3)을 따른다
 * (바이트 수까지 같지는 않다). 프레임에 VIN은 없다.
 */
class VcsecBroadcastsTest {
    /** 게이트를 통과하는 프레임(14-lib B1): from VCSEC, to BROADCAST, 요청 UUID·서명·세션 정보 없음. */
    private fun broadcast(payload: ByteArray): RoutableMessage =
        RoutableMessage(
            from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
            to_destination = Destination(domain = Domain.DOMAIN_BROADCAST),
            protobuf_message_as_bytes = payload.toByteString(),
        )

    private fun frame(payload: ByteArray): ByteArray = RoutableMessage.ADAPTER.encode(broadcast(payload))

    /** 스키마 밖 필드를 Wire 인코더로 만든다. */
    private fun lenField(
        tag: Int,
        content: ByteArray,
    ): ByteArray = Buffer().also { ProtoAdapter.BYTES.encodeWithTag(ProtoWriter(it), tag, content.toByteString()) }.readByteArray()

    private fun varintField(
        tag: Int,
        value: Int,
    ): ByteArray = Buffer().also { ProtoAdapter.UINT32.encodeWithTag(ProtoWriter(it), tag, value) }.readByteArray()

    /** `VehicleStatus` 필드 8(B3.1, 스키마에 없음): `{1: 1, 2: 2, 3: 1}`. */
    private val field8 = lenField(8, varintField(1, 1) + varintField(2, 2) + varintField(3, 1))

    /** `FromVCSECMessage` 필드 53 한 항목(B3.3): `{1: id, 2: 35바이트}`. 블롭은 더미이고 항목끼리 마지막 2바이트만 다르다(실차 모양). */
    private fun field53(id: Int): ByteArray =
        lenField(
            53,
            varintField(1, id) + lenField(2, ByteArray(33) { 0x5A } + byteArrayOf(id.toByte(), 0)),
        )

    /** 운전석에 앉아 문을 닫은 채 반복되는 상태(B3.1): INTERNAL_LOCKED / AWAKE / PRESENT, 기어 D. */
    private fun seated(unknown: ByteArray = ByteArray(0)): WireVehicleStatus =
        WireVehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
            gear = Gear_E.GEAR_DRIVE,
            unknownFields = unknown.toByteString(),
        )

    /** 토큰(B3.2): 20바이트 더미. */
    private val token = AuthenticationRequestToken(token = ByteArray(20) { (it + 1).toByte() }.toByteString())

    private fun statusFrame(status: WireVehicleStatus): ByteArray = frame(FromVCSECMessage(vehicleStatus = status).encode())

    @Test
    fun statusOnly() {
        val frame = statusFrame(seated())
        val event = assertIs<VehicleEvent.Status>(VcsecBroadcasts.events(frame).single())
        assertEquals(LockState.INTERNAL_LOCKED, event.status.lockState)
        assertEquals(UserPresence.PRESENT, event.status.userPresence)
        assertEquals(Gear.DRIVE, event.status.gear)
        assertEquals(ClosureState.CLOSED, event.status.closures.frontDriverDoor) // closureStatuses 없음 = 모두 CLOSED(조회와 같은 규칙)
        assertEquals(emptyList(), event.unknownFields)
        assertContentEquals(frame, event.raw)
    }

    @Test
    fun statusAndAuthTogether() {
        // Review Focus 2. 실내 손잡이로 운전석 문을 연 순간의 모양(B3.2): authenticationRequest, 필드 53 항목 5개, 문이 열린 vehicleStatus.
        // 스냅샷은 1과 3을 같은 oneof에 넣었다 — Wire 디코딩만으로는 마지막 멤버(여기서는 vehicleStatus)만 남는다.
        val request =
            WireAuthenticationRequest(
                sessionInfo = token,
                requestedLevel = AuthenticationLevel_E.AUTHENTICATION_LEVEL_DRIVE,
                reasonsForAuth = listOf(AuthenticationReason_E.AUTHENTICATIONREASON_PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL),
            )
        val doorOpen =
            seated(field8).copy(
                vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
                gear = Gear_E.GEAR_PARK,
                closureStatuses = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN),
            )
        val payload =
            FromVCSECMessage(authenticationRequest = request).encode() +
                listOf(1, 3, 6, 7, 4).map(::field53).reduce(ByteArray::plus) +
                FromVCSECMessage(vehicleStatus = doorOpen).encode()
        val frame = frame(payload)
        val events = VcsecBroadcasts.events(frame)
        assertEquals(2, events.size, events.toString())
        val status = assertIs<VehicleEvent.Status>(events[0])
        val auth = assertIs<VehicleEvent.AuthenticationRequest>(events[1])
        assertEquals(ClosureState.OPEN, status.status.closures.frontDriverDoor)
        assertEquals(Gear.PARK, status.status.gear)
        assertEquals(AuthenticationLevel.DRIVE, auth.requestedLevel)
        assertEquals(listOf(AuthenticationReason.PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL), auth.reasons)
        assertContentEquals(frame, status.raw)
        assertContentEquals(status.raw, auth.raw)
        // 모르는 부분은 프레임(raw)의 성질이라 두 이벤트가 같다: 최상위는 도착 순서, 반복 유지
        assertEquals(listOf("53", "53", "53", "53", "53", "1.8"), status.unknownFields)
        assertEquals(status.unknownFields, auth.unknownFields)
    }

    @Test
    fun unknownOnly() {
        // 연결 직후의 필드 53 한 항목(id 4, B3.3)과 필드 48 하트비트(20바이트, B3.4): 둘 다 해석할 메시지가 없다
        val field53Frame = frame(field53(4))
        val unrecognized = assertIs<VehicleEvent.Unrecognized>(VcsecBroadcasts.events(field53Frame).single())
        assertEquals(listOf("53"), unrecognized.unknownFields)
        assertContentEquals(field53Frame, unrecognized.raw)
        val heartbeat = assertIs<VehicleEvent.Unrecognized>(VcsecBroadcasts.events(frame(lenField(48, ByteArray(20) { 0x30 }))).single())
        assertEquals(listOf("48"), heartbeat.unknownFields)
    }

    @Test
    fun vehicleStatusField8Recorded() {
        val event = assertIs<VehicleEvent.Status>(VcsecBroadcasts.events(statusFrame(seated(field8))).single())
        assertEquals(listOf("1.8"), event.unknownFields)
        assertEquals(LockState.INTERNAL_LOCKED, event.status.lockState) // 모르는 필드가 있어도 아는 필드는 해석한다
    }

    @Test
    fun undecodablePayload() {
        // 게이트는 통과하지만 페이로드가 잘렸다: 필드 1이 5바이트라고 하고 2바이트만 있다
        val frame = frame(byteArrayOf(0x0A, 0x05, 0x08, 0x01))
        val event = assertIs<VehicleEvent.Unrecognized>(VcsecBroadcasts.events(frame).single())
        assertEquals(emptyList(), event.unknownFields)
        assertContentEquals(frame, event.raw)
    }

    @Test
    fun rawIsDefensiveCopy() {
        val frame = statusFrame(seated())
        val original = frame.copyOf()
        val event = VcsecBroadcasts.events(frame).single()
        event.raw.fill(0) // 받은 배열을 바꿔도
        frame.fill(0) // 입력 배열을 바꿔도
        assertContentEquals(original, event.raw) // 다음 get은 그대로
        assertNotSame(event.raw, event.raw) // get마다 새 복사본
    }

    @Test
    fun reasonOutsideSchemaIsUnknown() {
        // reasonsForAuth packed [6, 11]: 11은 스키마(0~10) 밖이다. 모르는 값은 그 필드 번호("3.4")로도 남는다.
        val request =
            WireAuthenticationRequest(sessionInfo = token, requestedLevel = AuthenticationLevel_E.AUTHENTICATION_LEVEL_DRIVE).encode() +
                lenField(4, byteArrayOf(6, 11))
        val auth = assertIs<VehicleEvent.AuthenticationRequest>(VcsecBroadcasts.events(frame(lenField(3, request))).single())
        assertEquals(listOf(AuthenticationReason.PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL, AuthenticationReason.UNKNOWN), auth.reasons)
        assertEquals(AuthenticationLevel.DRIVE, auth.requestedLevel)
        assertEquals(listOf("3.4"), auth.unknownFields)
    }

    @Test
    fun levelOutsideSchemaIsUnknown() {
        // requestedLevel = 5(스키마는 0~2): NONE(proto3 기본값)이 아니라 UNKNOWN
        val request = varintField(3, 5) + lenField(4, byteArrayOf(8))
        val auth = assertIs<VehicleEvent.AuthenticationRequest>(VcsecBroadcasts.events(frame(lenField(3, request))).single())
        assertEquals(AuthenticationLevel.UNKNOWN, auth.requestedLevel)
        assertEquals(listOf(AuthenticationReason.ENTERED_HIGHER_AUTH_ZONE), auth.reasons)
        assertEquals(listOf("3.3"), auth.unknownFields)
    }

    @Test
    fun everyWireAuthenticationValueMapsToThePublicValueOfTheSameName() {
        // 값이 뒤바뀐 매핑을 잡는다
        for (wire in AuthenticationLevel_E.entries) {
            val request = WireAuthenticationRequest(requestedLevel = wire)
            val auth = assertIs<VehicleEvent.AuthenticationRequest>(VcsecBroadcasts.events(authFrame(request)).single())
            assertEquals(wire.name.removePrefix("AUTHENTICATION_LEVEL_"), auth.requestedLevel.name)
        }
        val request = WireAuthenticationRequest(reasonsForAuth = AuthenticationReason_E.entries)
        val auth = assertIs<VehicleEvent.AuthenticationRequest>(VcsecBroadcasts.events(authFrame(request)).single())
        assertEquals(AuthenticationReason_E.entries.map { it.name.removePrefix("AUTHENTICATIONREASON_") }, auth.reasons.map { it.name })
    }

    @Test
    fun nestedUnknownFieldsHaveLongerPaths() {
        // 하위 메시지 안의 모르는 필드: closureStatuses(1.1) 안 9번, 토큰 메시지(3.2) 안 2번. 그 메시지 자신의 필드 다음에 온다.
        val status =
            seated(field8).copy(
                closureStatuses =
                    ClosureStatuses(
                        chargePort = ClosureState_E.CLOSURESTATE_OPEN,
                        unknownFields = varintField(9, 1).toByteString(),
                    ),
            )
        assertEquals(listOf("1.8", "1.1.9"), VcsecBroadcasts.events(statusFrame(status)).single().unknownFields)
        val request = WireAuthenticationRequest(sessionInfo = token.copy(unknownFields = varintField(2, 1).toByteString()))
        assertEquals(listOf("3.2.2"), VcsecBroadcasts.events(authFrame(request)).single().unknownFields)
    }

    @Test
    fun frameThatFailsTheGateHasNoEvents() {
        // 디스패처와 같은 게이트를 다시 확인한다: 서명된 프레임, 다른 클라이언트 주소로 온 응답, RoutableMessage가 아닌 바이트
        val payload = FromVCSECMessage(vehicleStatus = seated()).encode()
        val signed =
            broadcast(payload).copy(
                signature_data =
                    SignatureData(
                        AES_GCM_Response_data =
                            AES_GCM_Response_Signature_Data(
                                nonce = ByteArray(12).toByteString(),
                                counter = 1,
                                tag = ByteArray(16).toByteString(),
                            ),
                    ),
            )
        val addressed =
            broadcast(payload).copy(
                to_destination = Destination(routing_address = ByteArray(16) { 9 }.toByteString()),
                request_uuid = ByteArray(16) { 7 }.toByteString(),
            )
        val rejected = listOf(RoutableMessage.ADAPTER.encode(signed), RoutableMessage.ADAPTER.encode(addressed), byteArrayOf(0xFF.toByte()))
        for (frame in rejected) assertEquals(emptyList(), VcsecBroadcasts.events(frame))
    }

    private fun authFrame(request: WireAuthenticationRequest): ByteArray = frame(FromVCSECMessage(authenticationRequest = request).encode())
}
