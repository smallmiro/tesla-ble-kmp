package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.CabinOverheatProtectionTemperature
import dev.mirolab.teslable.model.ClimateKeeperMode
import dev.mirolab.teslable.model.SeatClimateLevel
import dev.mirolab.teslable.model.SeatPosition
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.fixtures.ProtocolVectors
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 공조 명령(FR-060~069)이 보내는 `CarServer.Action` 평문. 기대값은 Go `pkg/vehicle/climate.go`의 빌더를 손으로 옮긴 독립 hex다
 * (구현 코드로 만들지 않는다).
 *
 * 읽는 법: `12 len`은 `Action.vehicleAction`(2), 그 안의 첫 바이트는 `VehicleAction` oneof 멤버의 태그 `(번호 << 3) | 2`다. 번호 16부터는
 * 태그가 varint 2바이트다(36 → `a2 02`, 44 → `e2 02`, 48 → `82 03`, 49 → `8a 03`, 50 → `92 03`, 66 → `92 04`). 값이 기본값이라 비어 있는
 * 멤버 메시지와 `Void`는 `태그 00`으로 나간다 — Wire는 기본값 스칼라 필드는 생략하지만 null이 아닌 메시지 필드와 oneof 멤버는 보낸다(Go도 같다).
 * float은 fixed32 리틀엔디언이다(22.0 = `0000b041`, 21.5 = `0000ac41`).
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class ClimateCommandsTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    private suspend fun connected(h: DispatcherHarness): InfotainmentCommands {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
        return session.infotainment
    }

    /** [calls]를 차례로 보낸다. 모두 `Success(Unit)`이고, 차량이 검증한 평문(받은 순서)이 각 hex와 같아야 한다. */
    private suspend fun DispatcherHarness.assertSends(calls: List<Pair<String, suspend () -> VehicleResult<Unit>>>) {
        for ((hex, call) in calls) assertEquals(VehicleResult.Success(Unit), call(), hex)
        assertEquals(calls.map { it.first }, fake.authenticatedPayloads(infotainment).map { it.toHex() })
    }

    @Test
    fun climateOnIsTheProtocolDocPlaintextAndClimateOffStillSendsTheEmptyMember() =
        // climate.go ClimateOn/ClimateOff: hvacAutoAction(10){power_on}. power_on = false면 멤버가 비지만 oneof라 `52 00`으로 나간다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    ProtocolVectors.HVAC_ON_PLAINTEXT to { c.climateOn() }, // 12 04 | 52 02 | 08 01
                    "12025200" to { c.climateOff() }, // 12 02 | 52 00
                ),
            )
            assertEquals("120452020801", ProtocolVectors.HVAC_ON_PLAINTEXT) // protocol.md §8.2.1의 protoc 출력
        }

    @Test
    fun setClimateTempAlwaysCarriesTheTempMaxLevel() =
        // climate.go ChangeClimateTemp: hvacTemperatureAdjustmentAction(14){level(5){TEMP_MAX(3){}}, driver(6), passenger(7)}.
        // level은 Go에서도 늘 채워진다(필드 번호 순서 5, 6, 7). 0도는 proto3 스칼라라 생략되고 level만 남는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    // 12 10 | 72 0e | 2a 02 1a 00 | 35 0000b041 | 3d 0000ac41
                    "1210720e2a021a00350000b0413d0000ac41" to { c.setClimateTemp(22.0f, 21.5f) },
                    // 12 06 | 72 04 | 2a 02 1a 00
                    "120672042a021a00" to { c.setClimateTemp(0.0f, 0.0f) },
                ),
            )
        }

    @Test
    fun seatHeaterMapsEverySeatAndLevelAndSortsBySeatDeclarationOrder() =
        // climate.go SetSeatHeater: hvacSeatHeaterActions(36){hvacSeatHeaterAction(1)*}. 원소마다 단계 Void(OFF 2, LOW 3, MED 4, HIGH 5)
        // 다음 좌석 Void(UNKNOWN 6, FRONT_LEFT 7 … THIRD_ROW_RIGHT 15). Go는 맵 순회 순서(무작위)로 보내지만 이 라이브러리는 SeatPosition
        // 선언 순서로 정렬한다(M5 계획 결정 기록) — 넣은 순서를 거꾸로 해도 바이트가 같다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val levels =
                linkedMapOf(
                    SeatPosition.THIRD_ROW_RIGHT to SeatClimateLevel.LOW,
                    SeatPosition.THIRD_ROW_LEFT to SeatClimateLevel.OFF,
                    SeatPosition.SECOND_ROW_RIGHT_BACK to SeatClimateLevel.HIGH,
                    SeatPosition.SECOND_ROW_RIGHT to SeatClimateLevel.MED,
                    SeatPosition.SECOND_ROW_CENTER to SeatClimateLevel.LOW,
                    SeatPosition.SECOND_ROW_LEFT_BACK to SeatClimateLevel.OFF,
                    SeatPosition.SECOND_ROW_LEFT to SeatClimateLevel.HIGH,
                    SeatPosition.FRONT_RIGHT to SeatClimateLevel.MED,
                    SeatPosition.FRONT_LEFT to SeatClimateLevel.LOW,
                    SeatPosition.UNKNOWN to SeatClimateLevel.OFF,
                )
            assertEquals(SeatPosition.entries.toSet(), levels.keys)
            val expected =
                "123fa2023c" + // 12 3f | a2 02 3c (원소 10개 × 6바이트, 원소마다 0a 04 | 단계 | 좌석)
                    "0a0412003200" + // OFF(2) UNKNOWN(6)
                    "0a041a003a00" + // LOW(3) FRONT_LEFT(7)
                    "0a0422004200" + // MED(4) FRONT_RIGHT(8)
                    "0a042a004a00" + // HIGH(5) SECOND_ROW_LEFT → CAR_SEAT_REAR_LEFT(9)
                    "0a0412005200" + // OFF SECOND_ROW_LEFT_BACK → REAR_LEFT_BACK(10)
                    "0a041a005a00" + // LOW SECOND_ROW_CENTER → REAR_CENTER(11)
                    "0a0422006200" + // MED SECOND_ROW_RIGHT → REAR_RIGHT(12)
                    "0a042a006a00" + // HIGH SECOND_ROW_RIGHT_BACK → REAR_RIGHT_BACK(13)
                    "0a0412007200" + // OFF THIRD_ROW_LEFT(14)
                    "0a041a007a00" // LOW THIRD_ROW_RIGHT(15)
            h.assertSends(listOf(expected to { c.setSeatHeater(levels) }))
        }

    @Test
    fun seatHeaterWithOneSeatAndWithAnEmptyMap() =
        // 빈 맵도 보낸다(Go: actions가 nil이어도 hvacSeatHeaterActions 멤버는 채워진다)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    // 12 09 | a2 02 06 | 0a 04 | 2a 00 (HIGH) 3a 00 (FRONT_LEFT)
                    "1209a202060a042a003a00" to { c.setSeatHeater(mapOf(SeatPosition.FRONT_LEFT to SeatClimateLevel.HIGH)) },
                    "1203a20200" to { c.setSeatHeater(emptyMap()) }, // 12 03 | a2 02 00
                ),
            )
        }

    @Test
    fun seatCoolerSendsLevelPlusOneForTheFrontSeats() =
        // climate.go SetSeatCooler: hvacSeatCoolerActions(49){hvacSeatCoolerAction(1){seat_cooler_level(1) = level + 1, seat_position(2)}}.
        // 쿨러 단계는 Unknown 0, Off 1 … High 4라 OFF도 0이 아니다. 좌석은 FrontLeft 1, FrontRight 2
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    // 12 09 | 8a 03 06 | 0a 04 | 08 LL | 10 PP
                    "12098a03060a0408011001" to { c.setSeatCooler(SeatClimateLevel.OFF, SeatPosition.FRONT_LEFT) },
                    "12098a03060a0408021002" to { c.setSeatCooler(SeatClimateLevel.LOW, SeatPosition.FRONT_RIGHT) },
                    "12098a03060a0408031001" to { c.setSeatCooler(SeatClimateLevel.MED, SeatPosition.FRONT_LEFT) },
                    "12098a03060a0408041002" to { c.setSeatCooler(SeatClimateLevel.HIGH, SeatPosition.FRONT_RIGHT) },
                ),
            )
        }

    @Test
    fun seatCoolerRejectsEveryOtherSeatWithoutSendingAnything() =
        // climate.go:17-20: seatMap에 없는 좌석은 executeCarServerAction 전에 "invalid seat position"으로 끝난다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val framesBefore = h.transport.sent.size
            val others = SeatPosition.entries - setOf(SeatPosition.FRONT_LEFT, SeatPosition.FRONT_RIGHT)
            assertEquals(8, others.size)

            for (seat in others) {
                assertEquals(
                    VehicleResult.Failure(VehicleError.InvalidArgument("invalid seat position for seat cooler: $seat")),
                    c.setSeatCooler(SeatClimateLevel.LOW, seat),
                )
            }

            assertEquals(framesBefore, h.transport.sent.size)
            assertTrue(h.fake.authenticatedPayloads(infotainment).isEmpty())
        }

    @Test
    fun autoSeatAndClimateKeepsOnlyTheGoSeatsInTheirOrder() =
        // climate.go AutoSeatAndClimate: autoSeatClimateAction(48){carseat(1)*{on(1), seat_position(2)}}. lookup에 없는 좌석은 조용히 거르고
        // UNKNOWN(0)은 남긴다. 순서와 중복을 유지하고, 남은 좌석이 없어도 보낸다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val mixed =
                listOf(
                    SeatPosition.FRONT_LEFT,
                    SeatPosition.SECOND_ROW_LEFT,
                    SeatPosition.UNKNOWN,
                    SeatPosition.FRONT_RIGHT,
                    SeatPosition.THIRD_ROW_RIGHT,
                    SeatPosition.FRONT_LEFT,
                )
            h.assertSends(
                listOf(
                    // 12 19 | 82 03 16 | 0a 04 0801 1001 (FL) | 0a 02 0801 (UNKNOWN: seat_position 0은 생략) | 0a 04 0801 1002 (FR) | FL
                    "1219820316" + "0a0408011001" + "0a020801" + "0a0408011002" + "0a0408011001" to {
                        c.autoSeatAndClimate(mixed, enabled = true)
                    },
                    // on = false는 생략된다: 0a 02 1002 (FR) | 0a 00 (UNKNOWN, 빈 CarSeat)
                    "1209820306" + "0a021002" + "0a00" to {
                        c.autoSeatAndClimate(listOf(SeatPosition.FRONT_RIGHT, SeatPosition.UNKNOWN), enabled = false)
                    },
                    "1203820300" to { c.autoSeatAndClimate(listOf(SeatPosition.SECOND_ROW_CENTER), enabled = true) },
                    "1203820300" to { c.autoSeatAndClimate(emptyList(), enabled = true) },
                ),
            )
        }

    @Test
    fun onOffCommandsSendTheEmptyMemberForFalse() =
        // climate.go SetSteeringWheelHeater(13){power_on}, SetPreconditioningMax(12){on, manual_override},
        // SetBioweaponDefenseMode(35){on, manual_override}, SetCabinOverheatProtection(50){on, fan_only}. false는 생략되지만 멤버는 나간다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "12046a020801" to { c.setSteeringWheelHeater(true) },
                    "12026a00" to { c.setSteeringWheelHeater(false) },
                    "120462020801" to { c.setPreconditioningMax(enabled = true, manualOverride = false) },
                    "120462021001" to { c.setPreconditioningMax(enabled = false, manualOverride = true) },
                    "1206620408011001" to { c.setPreconditioningMax(enabled = true, manualOverride = true) },
                    "12026200" to { c.setPreconditioningMax(enabled = false, manualOverride = false) },
                    "12059a02020801" to { c.setBioweaponDefenseMode(enabled = true, manualOverride = false) },
                    "12059a02021001" to { c.setBioweaponDefenseMode(enabled = false, manualOverride = true) },
                    "12039a0200" to { c.setBioweaponDefenseMode(enabled = false, manualOverride = false) },
                    "120792030408011001" to { c.setCabinOverheatProtection(enabled = true, fanOnly = true) },
                    "12059203020801" to { c.setCabinOverheatProtection(enabled = true, fanOnly = false) },
                    "12059203021001" to { c.setCabinOverheatProtection(enabled = false, fanOnly = true) },
                    "1203920300" to { c.setCabinOverheatProtection(enabled = false, fanOnly = false) },
                ),
            )
        }

    @Test
    fun climateKeeperModesMapToTheGoEnumValues() =
        // climate.go SetClimateKeeperMode: hvacClimateKeeperAction(44){ClimateKeeperAction(1), manual_override(2)}.
        // Off 0, On 1, Dog 2, Camp 3. OFF는 0이라 생략되어 빈 멤버가 된다. TESLEMETRY-EXT manual_override_mode(3)는 보내지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1203e20200" to { c.setClimateKeeperMode(ClimateKeeperMode.OFF, manualOverride = false) },
                    "1205e202021001" to { c.setClimateKeeperMode(ClimateKeeperMode.OFF, manualOverride = true) },
                    "1205e202020801" to { c.setClimateKeeperMode(ClimateKeeperMode.ON, manualOverride = false) },
                    "1207e2020408021001" to { c.setClimateKeeperMode(ClimateKeeperMode.DOG, manualOverride = true) },
                    "1205e202020803" to { c.setClimateKeeperMode(ClimateKeeperMode.CAMP, manualOverride = false) },
                ),
            )
        }

    @Test
    fun cabinOverheatProtectionTemperaturesMapToTheClimateStateValues() =
        // climate.go SetCabinOverheatProtectionTemperature: setCopTempAction(66){copActivationTemp(1)}, Unspecified 0 … High 3
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val temperature: suspend (CabinOverheatProtectionTemperature) -> VehicleResult<Unit> = {
                c.setCabinOverheatProtectionTemperature(it)
            }
            h.assertSends(
                listOf(
                    "1203920400" to { temperature(CabinOverheatProtectionTemperature.UNSPECIFIED) }, // 0은 생략: 빈 멤버
                    "12059204020801" to { temperature(CabinOverheatProtectionTemperature.LOW) },
                    "12059204020802" to { temperature(CabinOverheatProtectionTemperature.MEDIUM) },
                    "12059204020803" to { temperature(CabinOverheatProtectionTemperature.HIGH) },
                ),
            )
        }
}
