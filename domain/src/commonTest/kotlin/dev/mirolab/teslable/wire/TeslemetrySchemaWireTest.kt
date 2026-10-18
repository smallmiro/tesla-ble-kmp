package dev.mirolab.teslable.wire

import com.tesla.generated.vcsec.AuthenticationLevel_E
import com.tesla.generated.vcsec.AuthenticationReason_E
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.Gear_E
import com.tesla.generated.vcsec.UIDesire_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleStatus
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.protocol.unknownVarint
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 원본에 없음 — Teslemetry/tesla-protocol 스냅샷(ADR-0002 개정 2026-10-18)이 정의한 VCSEC 필드의 wire 디코딩.
 * 이름은 스키마 출처의 것이고 실차 의미 확인 전이다. 스냅샷에도 없는 번호(VehicleStatus 8, FromVCSECMessage 48·53)는
 * 계속 unknownFields에 원본 그대로 남아야 한다.
 */
@OptIn(InternalTeslableApi::class)
class TeslemetrySchemaWireTest {
    @Test
    fun vehicleStatusField7IsGear() {
        val expected =
            listOf(Gear_E.GEAR_UNKNOWN, Gear_E.GEAR_PARK, Gear_E.GEAR_DRIVE, Gear_E.GEAR_REVERSE, Gear_E.GEAR_NEUTRAL)
        expected.forEachIndexed { value, gear ->
            // tag 7 varint = 0x38
            val status = VehicleStatus.ADAPTER.decode("380$value".decodeHex())
            assertEquals(gear, status.gear)
            assertEquals(0, status.unknownFields.size, "gear $value")
        }
    }

    @Test
    fun vehicleStatusField6IsUiDesire() {
        // tag 6 varint = 0x30
        assertEquals(UIDesire_E.UI_DESIRE_HAS_DATA, VehicleStatus.ADAPTER.decode("3001".decodeHex()).uiDesire)
        assertEquals(UIDesire_E.UI_DESIRE_NONE, VehicleStatus.ADAPTER.decode("".decodeHex()).uiDesire)
    }

    @Test
    fun vehicleStatusField8StaysUnknownAndRoundTrips() {
        // lock=LOCKED(2:1), uiDesire=HAS_DATA(6:1), gear=DRIVE(7:2), 8:6(스냅샷에 없음)
        val bytes = "1001300138024006".decodeHex()
        val status = VehicleStatus.ADAPTER.decode(bytes)
        assertEquals(VehicleLockState_E.VEHICLELOCKSTATE_LOCKED, status.vehicleLockState)
        assertEquals(Gear_E.GEAR_DRIVE, status.gear)
        assertEquals(UIDesire_E.UI_DESIRE_HAS_DATA, status.uiDesire)
        assertEquals("4006", status.unknownFields.hex())
        assertEquals(6, status.unknownFields.unknownVarint(8))
        assertEquals(bytes, VehicleStatus.ADAPTER.encodeByteString(status))
    }

    @Test
    fun unknownGearValueIsPreservedRaw() {
        // Wire는 범위를 벗어난 enum 값을 기본값으로 두고 원본을 unknownFields로 옮긴다
        val status = VehicleStatus.ADAPTER.decode("3809".decodeHex())
        assertEquals(Gear_E.GEAR_UNKNOWN, status.gear)
        assertEquals(9, status.unknownFields.unknownVarint(7))
    }

    @Test
    fun fromVcsecField3IsAuthenticationRequest() {
        val token = "0a10" + "11".repeat(16) // AuthenticationRequestToken.token(1) 16B
        val body = "1212$token" + "1802" + "22020105" // sessionInfo(2), requestedLevel(3)=DRIVE, reasonsForAuth(4) packed [1, 5]
        val from = FromVCSECMessage.ADAPTER.decode("1a1a$body".decodeHex())
        val request = assertNotNull(from.authenticationRequest)
        assertEquals(AuthenticationLevel_E.AUTHENTICATION_LEVEL_DRIVE, request.requestedLevel)
        assertEquals(
            listOf(
                AuthenticationReason_E.AUTHENTICATIONREASON_IDENTIFICATION,
                AuthenticationReason_E.AUTHENTICATIONREASON_PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL,
            ),
            request.reasonsForAuth,
        )
        assertEquals(16, request.sessionInfo?.token?.size)
        assertEquals(0, from.unknownFields.size)
    }

    @Test
    fun fromVcsecFields48And53StayUnknownAndRoundTrip() {
        // 48 varint(0x80 0x03) = 1, 53 LEN(0xaa 0x03){1:1}
        val bytes = "800301aa03020801".decodeHex()
        val from = FromVCSECMessage.ADAPTER.decode(bytes)
        assertNull(from.vehicleStatus)
        assertNull(from.authenticationRequest)
        assertEquals(bytes, from.unknownFields)
        assertEquals(bytes, FromVCSECMessage.ADAPTER.encodeByteString(from))
    }
}
