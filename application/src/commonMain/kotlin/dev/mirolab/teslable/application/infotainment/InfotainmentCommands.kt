// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — getCarServerResponse, executeCarServerAction;
// pkg/vehicle/state.go GetState; pkg/vehicle/climate.go, charge.go, actions.go (command methods; builders in ClimateActions.kt,
// ChargeActions.kt, VehicleControlActions.kt)
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.common.PreconditionSchedule
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.application.dispatcher.valueOr
import dev.mirolab.teslable.application.vehicle.SendWithRetry
import dev.mirolab.teslable.model.CabinOverheatProtectionTemperature
import dev.mirolab.teslable.model.ChargingPolicy
import dev.mirolab.teslable.model.ClimateKeeperMode
import dev.mirolab.teslable.model.SeatClimateLevel
import dev.mirolab.teslable.model.SeatPosition
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.AuthMethod
import kotlin.time.Duration

/**
 * Go `executeCarServerAction`/`getCarServerResponse`: `Action`을 Infotainment에 보내고(단일 응답, GCM) `Response`로 해석한다.
 *
 * 명령 메서드(M5)는 Go 메서드 하나에 하나다. 모두 [execute]의 명령 경로로 보낸다: 세션을 시작하거나 차를 깨우지 않고(세션이 없으면
 * `Failure(NoSession)`), 응답이 없으면 다시 보내지 않고 `Uncertain(Timeout(afterSend = true))`이며, 쓰기 실패 뒤에는 새 사본을 인가해
 * 보내지 않는다(ADR-0010 2026-11-08 개정). 로컬 인자 검증은 Go에 있는 것만 하고, 실패하면 아무것도 보내지 않고 `Failure(InvalidArgument)`다.
 * 성공 응답의 내용은 Go처럼 버린다(`Success(Unit)`).
 */
@Suppress("TooManyFunctions") // Go Vehicle의 Infotainment 명령 메서드와 1:1 (PRD §5)
public class InfotainmentCommands(
    private val send: SendWithRetry,
) {
    /**
     * [action]을 실행한다. 시간 초과·재시도 규칙은 [SendWithRetry.send]와 같다: 명령으로 다루므로 쓰기 실패 뒤에는 새 사본을 인가해 보내지
     * 않는다(ADR-0010 2026-11-08 개정). 조회는 [getState]를 쓴다.
     */
    public suspend fun execute(
        action: Action,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Response> = interpret(send.send(Domain.DOMAIN_INFOTAINMENT, action.encode(), AuthMethod.GCM, timeout = timeout))

    /** Go `getCarServerResponse`의 해석: 전송·프로토콜 오류는 그대로, 응답이면 `CarServer.Response`로. */
    private fun interpret(sent: VehicleResult<RoutableMessage>): VehicleResult<Response> {
        val reply = sent.valueOr { return it.toResult() }
        return InfotainmentResponses.interpret(reply.protobuf_message_as_bytes?.toByteArray())
    }

    /**
     * Go `GetState`(FR-033): [category] 하나를 조회한다. 세션을 시작하거나 차를 깨우지 않으므로 세션이 없으면 `Failure(NoSession)`이고,
     * 시간 초과·거부·해석 규칙은 [execute]와 같다. 재시도는 반복해도 안전한 조회로 보낸다([SendWithRetry.query]): 쓰기가 실패해 같은 바이트를
     * 다시 보낸 뒤에도 Go처럼 재시도할 오류에 새 사본으로 다시 묻는다(ADR-0010 2026-11-08 개정의 면제 — 조회는 차량 상태를 바꾸지 않는다).
     * 응답의 내용을 [category]와 맞춰 보지 않는다(Go와 같다).
     *
     * 응답에 `vehicleData`가 없으면 `Success(VehicleData())`다. Go는 `(nil, nil)`을 돌려주지만 nil 메시지의 필드는 빈 메시지처럼 읽히고,
     * 호출자는 어차피 필드마다 null을 확인하므로 반환 타입을 nullable로 넓히지 않는다(M5 계획 결정 기록).
     *
     * 결과에는 위치·목적지가 들어 있을 수 있다. `toString()`을 포함해 로그에 남기지 않는다(FR-112).
     */
    public suspend fun getState(
        category: StateCategory,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<VehicleData> =
        when (val result = interpret(send.query(Domain.DOMAIN_INFOTAINMENT, category.toAction().encode(), AuthMethod.GCM, timeout))) {
            is VehicleResult.Success -> VehicleResult.Success(result.value.vehicleData ?: VehicleData())
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }

    /** 공조 켜기(FR-060). Go `ClimateOn`. */
    public suspend fun climateOn(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ClimateActions.climateOn(), timeout)

    /** 공조 끄기(FR-060). Go `ClimateOff`. */
    public suspend fun climateOff(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ClimateActions.climateOff(), timeout)

    /** 좌석 히터(FR-061). Go `SetSeatHeater`. 좌석은 [SeatPosition] 선언 순서로 보내고, 빈 맵도 보낸다. */
    public suspend fun setSeatHeater(
        levels: Map<SeatPosition, SeatClimateLevel>,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.seatHeater(levels), timeout)

    /** 운전석·조수석 온도, 섭씨(FR-062). Go `ChangeClimateTemp`. 범위를 검사하지 않는다(Go와 같다). */
    public suspend fun setClimateTemp(
        driverCelsius: Float,
        passengerCelsius: Float,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.climateTemp(driverCelsius, passengerCelsius), timeout)

    /** 좌석 쿨러(FR-063). Go `SetSeatCooler`. [seat]가 앞좌석이 아니면 보내지 않고 `Failure(InvalidArgument)`. */
    public suspend fun setSeatCooler(
        level: SeatClimateLevel,
        seat: SeatPosition,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.seatCooler(level, seat), timeout)

    /** 스티어링 휠 히터(FR-064). Go `SetSteeringWheelHeater`. */
    public suspend fun setSteeringWheelHeater(
        enabled: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.steeringWheelHeater(enabled), timeout)

    /** 자동 좌석 공조(FR-065). Go `AutoSeatAndClimate`. Go가 다루지 않는 좌석은 조용히 거르고, 남은 좌석이 없어도 보낸다. */
    public suspend fun autoSeatAndClimate(
        seats: List<SeatPosition>,
        enabled: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.autoSeatAndClimate(seats, enabled), timeout)

    /** 최대 사전 공조(FR-066). Go `SetPreconditioningMax`. */
    public suspend fun setPreconditioningMax(
        enabled: Boolean,
        manualOverride: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.preconditioningMax(enabled, manualOverride), timeout)

    /** 공조 유지 모드(FR-067). Go `SetClimateKeeperMode`. */
    public suspend fun setClimateKeeperMode(
        mode: ClimateKeeperMode,
        manualOverride: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.climateKeeperMode(mode, manualOverride), timeout)

    /** 생화학 무기 방어 모드(FR-068). Go `SetBioweaponDefenseMode`. */
    public suspend fun setBioweaponDefenseMode(
        enabled: Boolean,
        manualOverride: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.bioweaponDefenseMode(enabled, manualOverride), timeout)

    /** 실내 과열 방지(FR-069). Go `SetCabinOverheatProtection`. */
    public suspend fun setCabinOverheatProtection(
        enabled: Boolean,
        fanOnly: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.cabinOverheatProtection(enabled, fanOnly), timeout)

    /** 실내 과열 방지 온도 단계(FR-069). Go `SetCabinOverheatProtectionTemperature`. */
    public suspend fun setCabinOverheatProtectionTemperature(
        temperature: CabinOverheatProtectionTemperature,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ClimateActions.cabinOverheatProtectionTemperature(temperature), timeout)

    /** 충전 시작(FR-070). Go `ChargeStart`. */
    public suspend fun chargeStart(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ChargeActions.chargeStart(), timeout)

    /** 충전 중지(FR-070). Go `ChargeStop`. */
    public suspend fun chargeStop(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ChargeActions.chargeStop(), timeout)

    /** 충전 한도(%)(FR-071). Go `ChangeChargeLimit`. 값을 검사하지 않는다(Go와 같다). */
    public suspend fun setChargeLimit(
        percent: Int,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.chargeLimit(percent), timeout)

    /** 충전 전류(A)(FR-072). Go `SetChargingAmps`. 값을 검사하지 않는다(Go와 같다). */
    public suspend fun setChargingAmps(
        amps: Int,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.chargingAmps(amps), timeout)

    /** 표준 범위 충전(FR-073). Go `ChargeStandardRange`. */
    public suspend fun chargeStandardRange(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ChargeActions.chargeStandardRange(), timeout)

    /** 최대 범위 충전(FR-073). Go `ChargeMaxRange`. */
    public suspend fun chargeMaxRange(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ChargeActions.chargeMaxRange(), timeout)

    /** 예약 충전(FR-074). Go `ScheduleCharging`. [minutesAfterMidnight]는 차량 현지 자정 뒤 분이며 검사하지 않는다(Go와 같다). */
    public suspend fun scheduleCharging(
        enabled: Boolean,
        minutesAfterMidnight: Int,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.scheduleCharging(enabled, minutesAfterMidnight), timeout)

    /**
     * 예약 출발(FR-075). Go `ScheduleDeparture`. 시각은 차량 현지 자정 뒤 분이다. [departAtMinutes]가 0..1440 밖이면 보내지 않고
     * `Failure(InvalidArgument)`이고, [offPeakEndMinutes]는 검사하지 않는다(Go와 같다).
     */
    public suspend fun scheduleDeparture(
        departAtMinutes: Int,
        offPeakEndMinutes: Int,
        preconditioning: ChargingPolicy,
        offPeak: ChargingPolicy,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.scheduleDeparture(departAtMinutes, offPeakEndMinutes, preconditioning, offPeak), timeout)

    /** 예약 출발 해제(FR-075). Go `ClearScheduledDeparture`. */
    public suspend fun clearScheduledDeparture(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ChargeActions.clearScheduledDeparture(), timeout)

    /** 저전력 모드(FR-076). Go `SetLowPowerMode`. */
    public suspend fun setLowPowerMode(
        enabled: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.lowPowerMode(enabled), timeout)

    /** 액세서리 전원 유지(FR-077). Go `SetKeepAccessoryPowerMode`. */
    public suspend fun setKeepAccessoryPowerMode(
        enabled: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.keepAccessoryPowerMode(enabled), timeout)

    /** 충전 일정 추가(FR-078). Go `AddChargeSchedule`. [schedule]을 그대로 보낸다. */
    public suspend fun addChargeSchedule(
        schedule: ChargeSchedule,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.addChargeSchedule(schedule), timeout)

    /** 충전 일정 삭제(FR-079). Go `RemoveChargeSchedule`. [id]는 Wire uint64 표현이다. */
    public suspend fun removeChargeSchedule(
        id: Long,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.removeChargeSchedule(id), timeout)

    /** 종류별 충전 일정 일괄 삭제(FR-080). Go `BatchRemoveChargeSchedules`. */
    public suspend fun batchRemoveChargeSchedules(
        home: Boolean,
        work: Boolean,
        other: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.batchRemoveChargeSchedules(home, work, other), timeout)

    /** 사전 공조 일정 추가(FR-081). Go `AddPreconditionSchedule`. [schedule]을 그대로 보낸다. */
    public suspend fun addPreconditionSchedule(
        schedule: PreconditionSchedule,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.addPreconditionSchedule(schedule), timeout)

    /** 사전 공조 일정 삭제(FR-082). Go `RemovePreconditionSchedule`. [id]는 Wire uint64 표현이다. */
    public suspend fun removePreconditionSchedule(
        id: Long,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.removePreconditionSchedule(id), timeout)

    /** 종류별 사전 공조 일정 일괄 삭제(FR-083). Go `BatchRemovePreconditionSchedules`. */
    public suspend fun batchRemovePreconditionSchedules(
        home: Boolean,
        work: Boolean,
        other: Boolean,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(ChargeActions.batchRemovePreconditionSchedules(home, work, other), timeout)

    /** 충전 포트 열기(FR-052). Go `OpenChargePort`(= `ChargePortOpen`). */
    public suspend fun openChargePort(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ChargeActions.openChargePort(), timeout)

    /** 충전 포트 닫기(FR-052). Go `CloseChargePort`(= `ChargePortClose`). */
    public suspend fun closeChargePort(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(ChargeActions.closeChargePort(), timeout)

    /** 창문 환기(FR-050). Go `VentWindows`. */
    public suspend fun ventWindows(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(VehicleControlActions.ventWindows(), timeout)

    /** 창문 닫기(FR-050). Go `CloseWindows`. */
    public suspend fun closeWindows(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(VehicleControlActions.closeWindows(), timeout)

    /** 선루프 절대 단계(FR-051). Go `ChangeSunroofState`. 값을 검사하지 않고 0도 보낸다(Go와 같다). */
    public suspend fun setSunroof(
        level: Int,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Unit> = command(VehicleControlActions.sunroof(level), timeout)

    /** 경적(FR-053). Go `HonkHorn`. */
    public suspend fun honkHorn(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(VehicleControlActions.honkHorn(), timeout)

    /** 라이트 점멸(FR-053). Go `FlashLights`. */
    public suspend fun flashLights(timeout: Duration = send.commandTimeout): VehicleResult<Unit> =
        command(VehicleControlActions.flashLights(), timeout)

    /** Go `executeCarServerAction`: [vehicleAction]을 `Action`에 담아 [execute]로 보내고, 성공 응답의 내용은 버린다. */
    private suspend fun command(
        vehicleAction: VehicleAction,
        timeout: Duration,
    ): VehicleResult<Unit> =
        when (val result = execute(Action(vehicleAction = vehicleAction), timeout)) {
            is VehicleResult.Success -> VehicleResult.Success(Unit)
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }

    /** 로컬 검증을 거친 빌더 결과. 검증에 실패했으면 Go처럼 보내기 전에 그 오류로 끝난다. */
    private suspend fun command(
        built: VehicleResult<VehicleAction>,
        timeout: Duration,
    ): VehicleResult<Unit> = command(built.valueOr { return it.toResult() }, timeout)
}
