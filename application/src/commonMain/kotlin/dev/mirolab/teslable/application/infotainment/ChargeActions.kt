// Ported from vehicle-command@a4b43c1 pkg/vehicle/charge.go (Apache-2.0) — ChargeStart, ChargeStop, ChargeStandardRange, ChargeMaxRange,
// ChangeChargeLimit, SetChargingAmps, ScheduleCharging, ScheduleDeparture, ClearScheduledDeparture, SetLowPowerMode,
// SetKeepAccessoryPowerMode, AddChargeSchedule, RemoveChargeSchedule, BatchRemoveChargeSchedules, AddPreconditionSchedule,
// RemovePreconditionSchedule, BatchRemovePreconditionSchedules, OpenChargePort, CloseChargePort (= pkg/vehicle/actions.go ChargePortOpen,
// ChargePortClose)
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.common.OffPeakChargingTimes
import com.tesla.generated.carserver.common.PreconditionSchedule
import com.tesla.generated.carserver.common.PreconditioningTimes
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.BatchRemoveChargeSchedulesAction
import com.tesla.generated.carserver.server.BatchRemovePreconditionSchedulesAction
import com.tesla.generated.carserver.server.ChargePortDoorClose
import com.tesla.generated.carserver.server.ChargePortDoorOpen
import com.tesla.generated.carserver.server.ChargingSetLimitAction
import com.tesla.generated.carserver.server.ChargingStartStopAction
import com.tesla.generated.carserver.server.RemoveChargeScheduleAction
import com.tesla.generated.carserver.server.RemovePreconditionScheduleAction
import com.tesla.generated.carserver.server.ScheduledChargingAction
import com.tesla.generated.carserver.server.ScheduledDepartureAction
import com.tesla.generated.carserver.server.SetChargingAmpsAction
import com.tesla.generated.carserver.server.SetKeepAccessoryPowerModeAction
import com.tesla.generated.carserver.server.SetLowPowerModeAction
import com.tesla.generated.carserver.server.VehicleAction
import dev.mirolab.teslable.model.ChargingPolicy
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult

/**
 * Go charge.go의 `VehicleAction` 빌더(FR-052, FR-070~083). [InfotainmentCommands]가 `Action`에 담아 보낸다(Go `getCarServerResponse`).
 *
 * - 시간 값은 차량 현지 자정 기준 분이다. Go는 `time.Duration`을 받아 `/ time.Minute`로 자르지만 여기서는 분을 그대로 받는다.
 * - 로컬 검증은 Go에 있는 것만 한다: [scheduleDeparture]의 출발 시각(0..1440분, 양 끝 포함). 충전 한도·전류·그 밖의 시각은 검사하지 않는다.
 * - 일정 추가는 받은 Wire 메시지를 그대로 담는다(Go `AddChargeSchedule(*ChargeSchedule)`와 같다).
 * - 값이 기본값이라 비어 있는 멤버 메시지도 oneof에 담겨 그대로 나간다(예: [clearScheduledDeparture]는 `scheduledDepartureAction {}`).
 */
@Suppress("TooManyFunctions") // charge.go의 명령 메서드 19개와 1:1
internal object ChargeActions {
    /** Go `ScheduleDeparture`의 `departAt > 24*time.Hour` 상한(분). 1440 자체는 허용한다. */
    private const val MINUTES_PER_DAY = 24 * 60

    /** Go `ChargeStart`: `chargingStartStopAction{start: {}}`. */
    fun chargeStart(): VehicleAction = startStop(ChargingStartStopAction(start = Void()))

    /** Go `ChargeStop`: `chargingStartStopAction{stop: {}}`. */
    fun chargeStop(): VehicleAction = startStop(ChargingStartStopAction(stop = Void()))

    /** Go `ChargeStandardRange`: `chargingStartStopAction{start_standard: {}}`. */
    fun chargeStandardRange(): VehicleAction = startStop(ChargingStartStopAction(start_standard = Void()))

    /** Go `ChargeMaxRange`: `chargingStartStopAction{start_max_range: {}}`. */
    fun chargeMaxRange(): VehicleAction = startStop(ChargingStartStopAction(start_max_range = Void()))

    /** Go `ChangeChargeLimit`: `chargingSetLimitAction{percent}`. TESLEMETRY-EXT `one_time`은 쓰지 않는다. */
    fun chargeLimit(percent: Int): VehicleAction = VehicleAction(chargingSetLimitAction = ChargingSetLimitAction(percent = percent))

    /** Go `SetChargingAmps`: `setChargingAmpsAction{charging_amps}`. */
    fun chargingAmps(amps: Int): VehicleAction = VehicleAction(setChargingAmpsAction = SetChargingAmpsAction(charging_amps = amps))

    /** Go `ScheduleCharging`: `scheduledChargingAction{enabled, charging_time}`. */
    fun scheduleCharging(
        enabled: Boolean,
        minutesAfterMidnight: Int,
    ): VehicleAction =
        VehicleAction(scheduledChargingAction = ScheduledChargingAction(enabled = enabled, charging_time = minutesAfterMidnight))

    /**
     * Go `ScheduleDeparture`: 출발 시각이 0..1440분 밖이면 보내기 전에 거부한다. `enabled`는 늘 true이고, 정책이 OFF면 그 하위 메시지를
     * 넣지 않는다. 오프피크 종료 시각은 검사하지 않는다(Go와 같다).
     */
    fun scheduleDeparture(
        departAtMinutes: Int,
        offPeakEndMinutes: Int,
        preconditioning: ChargingPolicy,
        offPeak: ChargingPolicy,
    ): VehicleResult<VehicleAction> {
        if (departAtMinutes !in 0..MINUTES_PER_DAY) {
            return VehicleResult.Failure(
                VehicleError.InvalidArgument(
                    "invalid departure time: $departAtMinutes minutes after midnight (expected 0..$MINUTES_PER_DAY)",
                ),
            )
        }
        return VehicleResult.Success(
            VehicleAction(
                scheduledDepartureAction =
                    ScheduledDepartureAction(
                        enabled = true,
                        departure_time = departAtMinutes,
                        preconditioning_times = preconditioning.preconditioningTimes(),
                        off_peak_charging_times = offPeak.offPeakChargingTimes(),
                        off_peak_hours_end_time = offPeakEndMinutes,
                    ),
            ),
        )
    }

    /** Go `ClearScheduledDeparture`: `scheduledDepartureAction{enabled: false}` — 비어 있는 멤버다. */
    fun clearScheduledDeparture(): VehicleAction = VehicleAction(scheduledDepartureAction = ScheduledDepartureAction(enabled = false))

    /** Go `SetLowPowerMode`: `setLowPowerModeAction{low_power_mode}`. */
    fun lowPowerMode(enabled: Boolean): VehicleAction =
        VehicleAction(setLowPowerModeAction = SetLowPowerModeAction(low_power_mode = enabled))

    /** Go `SetKeepAccessoryPowerMode`: `setKeepAccessoryPowerModeAction{keep_accessory_power_mode}`. */
    fun keepAccessoryPowerMode(enabled: Boolean): VehicleAction =
        VehicleAction(setKeepAccessoryPowerModeAction = SetKeepAccessoryPowerModeAction(keep_accessory_power_mode = enabled))

    /** Go `AddChargeSchedule`: `addChargeScheduleAction` = [schedule] 그대로. */
    fun addChargeSchedule(schedule: ChargeSchedule): VehicleAction = VehicleAction(addChargeScheduleAction = schedule)

    /** Go `RemoveChargeSchedule`: `removeChargeScheduleAction{id}`. [id]는 Wire uint64 표현이다. */
    fun removeChargeSchedule(id: Long): VehicleAction = VehicleAction(removeChargeScheduleAction = RemoveChargeScheduleAction(id = id))

    /** Go `BatchRemoveChargeSchedules`: `batchRemoveChargeSchedulesAction{home, work, other}`. */
    fun batchRemoveChargeSchedules(
        home: Boolean,
        work: Boolean,
        other: Boolean,
    ): VehicleAction =
        VehicleAction(batchRemoveChargeSchedulesAction = BatchRemoveChargeSchedulesAction(home = home, work = work, other = other))

    /** Go `AddPreconditionSchedule`: `addPreconditionScheduleAction` = [schedule] 그대로. */
    fun addPreconditionSchedule(schedule: PreconditionSchedule): VehicleAction = VehicleAction(addPreconditionScheduleAction = schedule)

    /** Go `RemovePreconditionSchedule`: `removePreconditionScheduleAction{id}`. [id]는 Wire uint64 표현이다. */
    fun removePreconditionSchedule(id: Long): VehicleAction =
        VehicleAction(removePreconditionScheduleAction = RemovePreconditionScheduleAction(id = id))

    /** Go `BatchRemovePreconditionSchedules`: `batchRemovePreconditionSchedulesAction{home, work, other}`. */
    fun batchRemovePreconditionSchedules(
        home: Boolean,
        work: Boolean,
        other: Boolean,
    ): VehicleAction =
        VehicleAction(
            batchRemovePreconditionSchedulesAction = BatchRemovePreconditionSchedulesAction(home = home, work = work, other = other),
        )

    /** Go `OpenChargePort`(= `ChargePortOpen`): `chargePortDoorOpen {}`. */
    fun openChargePort(): VehicleAction = VehicleAction(chargePortDoorOpen = ChargePortDoorOpen())

    /** Go `CloseChargePort`(= `ChargePortClose`): `chargePortDoorClose {}`. */
    fun closeChargePort(): VehicleAction = VehicleAction(chargePortDoorClose = ChargePortDoorClose())

    private fun startStop(action: ChargingStartStopAction): VehicleAction = VehicleAction(chargingStartStopAction = action)

    /** Go `ScheduleDeparture`의 사전 공조 정책: OFF면 null(하위 메시지 없음). */
    private fun ChargingPolicy.preconditioningTimes(): PreconditioningTimes? =
        when (this) {
            ChargingPolicy.OFF -> null
            ChargingPolicy.ALL_DAYS -> PreconditioningTimes(all_week = Void())
            ChargingPolicy.WEEKDAYS -> PreconditioningTimes(weekdays = Void())
        }

    /** Go `ScheduleDeparture`의 오프피크 충전 정책: OFF면 null(하위 메시지 없음). */
    private fun ChargingPolicy.offPeakChargingTimes(): OffPeakChargingTimes? =
        when (this) {
            ChargingPolicy.OFF -> null
            ChargingPolicy.ALL_DAYS -> OffPeakChargingTimes(all_week = Void())
            ChargingPolicy.WEEKDAYS -> OffPeakChargingTimes(weekdays = Void())
        }
}
