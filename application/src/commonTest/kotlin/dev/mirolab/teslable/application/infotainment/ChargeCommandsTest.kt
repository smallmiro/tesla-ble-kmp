package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.common.PreconditionSchedule
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.ChargingPolicy
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 충전·전원 명령(FR-052, FR-070~083)이 보내는 `CarServer.Action` 평문. 기대값은 Go `pkg/vehicle/charge.go`(충전 포트는 `actions.go`와
 * 같은 바이트)의 빌더를 손으로 옮긴 독립 hex다(구현 코드로 만들지 않는다). 읽는 법은 `ClimateCommandsTest`와 같다: `12 len` 다음이
 * `VehicleAction` oneof 멤버의 태그 `(번호 << 3) | 2`(41 → `ca 02`, 42 → `d2 02`, 43 → `da 02`, 61 → `ea 03`, 62 → `f2 03`,
 * 97 → `8a 06`, 98 → `92 06`, 99 → `9a 06`, 100 → `a2 06`, 107 → `da 06`, 108 → `e2 06`, 130 → `92 08`, 138 → `d2 08`)다.
 * int32·uint64 음수는 10바이트 varint다(zigzag가 아니다).
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class ChargeCommandsTest {
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
    fun startStopAndRangeEachSelectTheirOneofMember() =
        // charge.go ChargeStart/ChargeStop/ChargeStandardRange/ChargeMaxRange: chargingStartStopAction(6){start 2 | start_standard 3 |
        // start_max_range 4 | stop 5}. 멤버는 빈 Void라 `태그 00`이다 — 하나도 빠지면 Go와 다른 명령이 된다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "120432021200" to { c.chargeStart() }, // 12 04 | 32 02 | 12 00
                    "120432022a00" to { c.chargeStop() }, // 2a 00
                    "120432021a00" to { c.chargeStandardRange() }, // 1a 00
                    "120432022200" to { c.chargeMaxRange() }, // 22 00
                ),
            )
        }

    @Test
    fun chargeLimitAndAmpsCarryTheValueWithoutLocalChecks() =
        // charge.go ChangeChargeLimit: chargingSetLimitAction(5){percent(1)}, SetChargingAmps: setChargingAmpsAction(43){charging_amps(1)}.
        // Go는 값을 검사하지 않는다 — 범위 밖의 값도 그대로 보내고 차량이 판단한다. TESLEMETRY-EXT one_time(2)은 보내지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "12042a020850" to { c.setChargeLimit(80) }, // 12 04 | 2a 02 | 08 50
                    "12022a00" to { c.setChargeLimit(0) }, // 0은 생략: 빈 멤버
                    "120d2a0b08ffffffffffffffffff01" to { c.setChargeLimit(-1) }, // int32 -1 = 10바이트 varint
                    "1205da02020820" to { c.setChargingAmps(32) }, // 12 05 | da 02 02 | 08 20
                    "1203da0200" to { c.setChargingAmps(0) },
                ),
            )
        }

    @Test
    fun scheduleChargingCarriesMinutesAfterMidnight() =
        // charge.go ScheduleCharging: scheduledChargingAction(41){enabled(1), charging_time(2) = 자정 뒤 분}. 시각을 검사하지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1207ca020408011078" to { c.scheduleCharging(enabled = true, minutesAfterMidnight = 120) }, // 08 01 | 10 78
                    "1205ca02020801" to { c.scheduleCharging(enabled = true, minutesAfterMidnight = 0) },
                    "1203ca0200" to { c.scheduleCharging(enabled = false, minutesAfterMidnight = 0) }, // tesla-control의 취소
                ),
            )
        }

    @Test
    fun scheduleDepartureBuildsThePolicyMessagesAndAcceptsTheWholeDay() =
        // charge.go ScheduleDeparture: scheduledDepartureAction(42){enabled(1) = true, departure_time(2), preconditioning_times(3),
        // off_peak_charging_times(4), off_peak_hours_end_time(5)}. 정책은 times oneof의 all_week(1) | weekdays(2), OFF면 하위 메시지가 없다.
        // 출발 시각은 0..24h(1440분)를 포함해 받고, 오프피크 종료 시각은 검사하지 않는다(Go와 같다)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    // 12 13 | d2 02 10 | 08 01 | 10 e003 (480) | 1a 02 0a00 (all_week) | 22 02 1200 (weekdays) | 28 e802 (360)
                    "1213d20210" + "0801" + "10e003" + "1a020a00" + "22021200" + "28e802" to {
                        c.scheduleDeparture(480, 360, ChargingPolicy.ALL_DAYS, ChargingPolicy.WEEKDAYS)
                    },
                    // 12 10 | d2 02 0d | 08 01 | 10 a00b (1440) | 1a 02 1200 (weekdays) | 22 02 0a00 (all_week); 0분 종료는 생략
                    "1210d2020d" + "0801" + "10a00b" + "1a021200" + "22020a00" to {
                        c.scheduleDeparture(1440, 0, ChargingPolicy.WEEKDAYS, ChargingPolicy.ALL_DAYS)
                    },
                    // 자정 출발, 정책 OFF: enabled만 남는다
                    "1205d202020801" to { c.scheduleDeparture(0, 0, ChargingPolicy.OFF, ChargingPolicy.OFF) },
                    // 08 01 | 10 3c (60) | 28 d00f (2000: 하루를 넘지만 Go는 검사하지 않는다)
                    "120ad202070801103c28d00f" to { c.scheduleDeparture(60, 2000, ChargingPolicy.OFF, ChargingPolicy.OFF) },
                ),
            )
        }

    @Test
    fun scheduleDepartureOutsideTheDayIsRejectedWithoutSending() =
        // charge.go:216-218: departAt < 0 || departAt > 24h면 executeCarServerAction 전에 "invalid departure time"으로 끝난다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val framesBefore = h.transport.sent.size

            for (minutes in listOf(-1, 1441, Int.MIN_VALUE, Int.MAX_VALUE)) {
                assertEquals(
                    VehicleResult.Failure(
                        VehicleError.InvalidArgument("invalid departure time: $minutes minutes after midnight (expected 0..1440)"),
                    ),
                    c.scheduleDeparture(minutes, 0, ChargingPolicy.ALL_DAYS, ChargingPolicy.ALL_DAYS),
                )
            }

            assertEquals(framesBefore, h.transport.sent.size)
            assertTrue(h.fake.authenticatedPayloads(infotainment).isEmpty())
        }

    @Test
    fun clearScheduledDepartureSendsOnlyEnabledFalse() =
        // charge.go ClearScheduledDeparture: scheduledDepartureAction{enabled: false} — 빈 멤버
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(listOf("1203d20200" to { c.clearScheduledDeparture() }))
        }

    @Test
    fun powerModesSendTheirFlag() =
        // charge.go SetLowPowerMode: setLowPowerModeAction(130){low_power_mode(1)}, SetKeepAccessoryPowerMode:
        // setKeepAccessoryPowerModeAction(138){keep_accessory_power_mode(1)}
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "12059208020801" to { c.setLowPowerMode(true) }, // 12 05 | 92 08 02 | 08 01
                    "1203920800" to { c.setLowPowerMode(false) },
                    "1205d208020801" to { c.setKeepAccessoryPowerMode(true) }, // 12 05 | d2 08 02 | 08 01
                    "1203d20800" to { c.setKeepAccessoryPowerMode(false) },
                ),
            )
        }

    @Test
    fun chargeSchedulesPassTheGivenWireMessageThrough() =
        // charge.go AddChargeSchedule: addChargeScheduleAction(97) = 받은 ChargeSchedule 그대로. RemoveChargeSchedule:
        // removeChargeScheduleAction(98){id(1) uint64}, BatchRemoveChargeSchedules: batchRemoveChargeSchedulesAction(108){home, work,
        // other}. id는 Wire uint64 표현의 Long이라 Long.MAX_VALUE보다 큰 id는 음수로 넘긴다(-1 = 0xffffffffffffffff). 좌표는 합성값이다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val schedule =
                ChargeSchedule(
                    id = 1,
                    name = "Home",
                    days_of_week = 127,
                    start_enabled = true,
                    start_time = 120,
                    end_enabled = true,
                    end_time = 360,
                    one_time = false,
                    enabled = true,
                    latitude = 1.5f,
                    longitude = -2.25f,
                )
            h.assertSends(
                listOf(
                    // 12 22 | 8a 06 1f | 0801 | 12 04 "Home" | 18 7f | 20 01 | 28 78 | 30 01 | 38 e802 | (one_time 생략) 48 01 |
                    // 55 0000c03f (1.5) | 5d 000010c0 (-2.25)
                    "12228a061f" + "0801" + "1204486f6d65" + "187f" + "2001" + "2878" + "3001" + "38e802" + "4801" + "550000c03f" +
                        "5d000010c0" to { c.addChargeSchedule(schedule) },
                    "1209920606" + "0880e2cfaa06" to { c.removeChargeSchedule(1_700_000_000L) }, // id varint 80 e2 cf aa 06
                    "120e92060b" + "08ffffffffffffffffff01" to { c.removeChargeSchedule(-1L) }, // uint64 최댓값
                    "1207e2060408011801" to { c.batchRemoveChargeSchedules(home = true, work = false, other = true) },
                    "1205e206021001" to { c.batchRemoveChargeSchedules(home = false, work = true, other = false) },
                    "1203e20600" to { c.batchRemoveChargeSchedules(home = false, work = false, other = false) },
                ),
            )
            val sent = Action.ADAPTER.decode(h.fake.authenticatedPayloads(infotainment).first())
            assertEquals(schedule, sent.vehicleAction?.addChargeScheduleAction)
        }

    @Test
    fun preconditionSchedulesPassTheGivenWireMessageThrough() =
        // charge.go AddPreconditionSchedule: addPreconditionScheduleAction(99) = 받은 PreconditionSchedule 그대로,
        // RemovePreconditionSchedule(100){id}, BatchRemovePreconditionSchedules(107){home, work, other}
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val schedule =
                PreconditionSchedule(
                    id = 2,
                    name = "Work",
                    days_of_week = 31,
                    precondition_time = 450,
                    one_time = true,
                    enabled = true,
                    latitude = 1.5f,
                    longitude = -2.25f,
                )
            h.assertSends(
                listOf(
                    // 12 1e | 9a 06 1b | 0802 | 12 04 "Work" | 18 1f | 20 c203 (450) | 28 01 | 30 01 | 3d 0000c03f | 45 000010c0
                    "121e9a061b" + "0802" + "1204576f726b" + "181f" + "20c203" + "2801" + "3001" + "3d0000c03f" + "45000010c0" to {
                        c.addPreconditionSchedule(schedule)
                    },
                    "1205a206020802" to { c.removePreconditionSchedule(2L) },
                    "1207da060410011801" to { c.batchRemovePreconditionSchedules(home = false, work = true, other = true) },
                    "1203da0600" to { c.batchRemovePreconditionSchedules(home = false, work = false, other = false) },
                ),
            )
            val sent = Action.ADAPTER.decode(h.fake.authenticatedPayloads(infotainment).first())
            assertEquals(schedule, sent.vehicleAction?.addPreconditionScheduleAction)
        }

    @Test
    fun chargePortDoorOpenAndClose() =
        // charge.go OpenChargePort/CloseChargePort(= actions.go ChargePortOpen/ChargePortClose): chargePortDoorOpen(62) {},
        // chargePortDoorClose(61) {}
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1203f20300" to { c.openChargePort() }, // 12 03 | f2 03 00
                    "1203ea0300" to { c.closeChargePort() }, // 12 03 | ea 03 00
                ),
            )
        }
}
