package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.vcsec.Gear_E
import dev.mirolab.teslable.model.Gear
import kotlin.test.Test
import kotlin.test.assertEquals
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

/** Wire VCSEC → 공개 값 객체 매핑(SDD §7.3). 응답 경로 전체는 `VehicleInfoQueriesTest`가 본다. */
class VcsecInfoMappingTest {
    @Test
    fun gearDriveMapsToDrive() {
        // FR-036: VehicleStatus.gear(필드 7, TESLEMETRY-EXT — 공식 Go 스키마에는 없음) → 공개 Gear
        assertEquals(Gear.DRIVE, VcsecInfoMapping.status(WireVehicleStatus(gear = Gear_E.GEAR_DRIVE)).gear)
    }

    @Test
    fun gearAbsentIsUnknown() {
        // proto3 enum이라 "없음"과 GEAR_UNKNOWN(0)을 구별할 수 없다 — 둘 다 UNKNOWN
        assertEquals(Gear.UNKNOWN, VcsecInfoMapping.status(WireVehicleStatus()).gear)
    }

    @Test
    fun gearOutsideSchemaIsUnknown() {
        // VehicleStatus{gear(7) = 9}: Wire는 스키마 밖 enum 값을 unknownFields로 옮긴다(잠금·개폐부와 같은 규칙)
        val wire = WireVehicleStatus.ADAPTER.decode(byteArrayOf(0x38, 0x09))
        assertEquals(Gear.UNKNOWN, VcsecInfoMapping.status(wire).gear)
    }

    @Test
    fun everyWireGearMapsToTheGearOfTheSameName() {
        // 값이 뒤바뀐 매핑(예: REVERSE ↔ NEUTRAL)을 잡는다
        for (wire in Gear_E.entries) {
            assertEquals(wire.name.removePrefix("GEAR_"), VcsecInfoMapping.status(WireVehicleStatus(gear = wire)).gear.name)
        }
    }
}
