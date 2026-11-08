// Ported from vehicle-command@a4b43c1 pkg/vehicle/climate.go (Apache-2.0) — ClimateOn, ClimateOff, ChangeClimateTemp, SetSeatHeater
// (SeatPosition.addToHeaterAction, Level.addToHeaterAction), SetSeatCooler, SetSteeringWheelHeater, AutoSeatAndClimate,
// SetPreconditioningMax, SetClimateKeeperMode, SetBioweaponDefenseMode, SetCabinOverheatProtection, SetCabinOverheatProtectionTemperature
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.AutoSeatClimateAction
import com.tesla.generated.carserver.server.HvacAutoAction
import com.tesla.generated.carserver.server.HvacBioweaponModeAction
import com.tesla.generated.carserver.server.HvacClimateKeeperAction
import com.tesla.generated.carserver.server.HvacSeatCoolerActions
import com.tesla.generated.carserver.server.HvacSeatHeaterActions
import com.tesla.generated.carserver.server.HvacSetPreconditioningMaxAction
import com.tesla.generated.carserver.server.HvacSteeringWheelHeaterAction
import com.tesla.generated.carserver.server.HvacTemperatureAdjustmentAction
import com.tesla.generated.carserver.server.SetCabinOverheatProtectionAction
import com.tesla.generated.carserver.server.SetCopTempAction
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.vehicle.ClimateState
import dev.mirolab.teslable.model.CabinOverheatProtectionTemperature
import dev.mirolab.teslable.model.ClimateKeeperMode
import dev.mirolab.teslable.model.SeatClimateLevel
import dev.mirolab.teslable.model.SeatPosition
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult

/**
 * Go climate.go의 `VehicleAction` 빌더(FR-060~069). [InfotainmentCommands]가 `Action`에 담아 보낸다(Go `getCarServerResponse`).
 *
 * - 와이어 값은 모두 명시적 `when`으로 대응한다. 공개 enum의 선언 순서(`ordinal`)를 와이어 값으로 쓰지 않는다.
 * - 값이 기본값이라 비어 있는 멤버 메시지도 `VehicleAction` oneof에 담겨 그대로 나간다(예: [climateOff]는 `hvacAutoAction {}`). Go와 같은 바이트다.
 * - 로컬 검증은 Go에 있는 것만 한다(좌석 쿨러 좌석). 실패하면 `Failure(InvalidArgument)`이고 아무것도 보내지 않는다.
 */
@Suppress("TooManyFunctions") // climate.go의 명령 메서드 12개와 1:1 + 와이어 값 대응
internal object ClimateActions {
    /** Go `ClimateOn`: `hvacAutoAction{power_on: true}`. */
    fun climateOn(): VehicleAction = VehicleAction(hvacAutoAction = HvacAutoAction(power_on = true))

    /** Go `ClimateOff`: `hvacAutoAction{power_on: false}` — 비어 있는 멤버다. */
    fun climateOff(): VehicleAction = VehicleAction(hvacAutoAction = HvacAutoAction(power_on = false))

    /** Go `ChangeClimateTemp`: 운전석·조수석 섭씨와 늘 `level{TEMP_MAX}`. */
    fun climateTemp(
        driverCelsius: Float,
        passengerCelsius: Float,
    ): VehicleAction =
        VehicleAction(
            hvacTemperatureAdjustmentAction =
                HvacTemperatureAdjustmentAction(
                    driver_temp_celsius = driverCelsius,
                    passenger_temp_celsius = passengerCelsius,
                    level = HvacTemperatureAdjustmentAction.Temperature(TEMP_MAX = Void()),
                ),
        )

    /**
     * Go `SetSeatHeater`: 좌석마다 `HvacSeatHeaterAction{단계 Void, 좌석 Void}` 하나. Go는 맵 순회 순서(무작위)로 담지만 여기서는
     * [SeatPosition] 선언 순서로 정렬해 바이트를 결정적으로 만든다(공개 `Map`은 순서를 보장하지 않는다, M5 계획 결정 기록). 빈 맵도 보낸다.
     */
    fun seatHeater(levels: Map<SeatPosition, SeatClimateLevel>): VehicleAction =
        VehicleAction(
            hvacSeatHeaterActions =
                HvacSeatHeaterActions(
                    hvacSeatHeaterAction = levels.entries.sortedBy { it.key }.map { (seat, level) -> heaterAction(seat, level) },
                ),
        )

    /** Go `SetSeatCooler`: 앞좌석만(그 밖의 좌석은 보내기 전에 거부), 쿨러 단계는 Go `level + 1`(OFF 1 … HIGH 4). */
    fun seatCooler(
        level: SeatClimateLevel,
        seat: SeatPosition,
    ): VehicleResult<VehicleAction> {
        val position =
            when (seat) {
                SeatPosition.FRONT_LEFT -> HvacSeatCoolerActions.HvacSeatCoolerPosition_E.HvacSeatCoolerPosition_FrontLeft
                SeatPosition.FRONT_RIGHT -> HvacSeatCoolerActions.HvacSeatCoolerPosition_E.HvacSeatCoolerPosition_FrontRight
                else -> return VehicleResult.Failure(VehicleError.InvalidArgument("invalid seat position for seat cooler: $seat"))
            }
        val coolerLevel =
            when (level) {
                SeatClimateLevel.OFF -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Off
                SeatClimateLevel.LOW -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Low
                SeatClimateLevel.MED -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_Med
                SeatClimateLevel.HIGH -> HvacSeatCoolerActions.HvacSeatCoolerLevel_E.HvacSeatCoolerLevel_High
            }
        val action = HvacSeatCoolerActions.HvacSeatCoolerAction(seat_cooler_level = coolerLevel, seat_position = position)
        return VehicleResult.Success(VehicleAction(hvacSeatCoolerActions = HvacSeatCoolerActions(hvacSeatCoolerAction = listOf(action))))
    }

    /** Go `SetSteeringWheelHeater`: `hvacSteeringWheelHeaterAction{power_on}`. */
    fun steeringWheelHeater(enabled: Boolean): VehicleAction =
        VehicleAction(hvacSteeringWheelHeaterAction = HvacSteeringWheelHeaterAction(power_on = enabled))

    /**
     * Go `AutoSeatAndClimate`: Go의 lookup에 있는 좌석([SeatPosition.UNKNOWN]·[SeatPosition.FRONT_LEFT]·[SeatPosition.FRONT_RIGHT])만
     * `CarSeat{on, seat_position}`으로 담고 나머지는 조용히 거른다. 순서와 중복을 유지하고, 남은 좌석이 없어도 보낸다(Go와 같다).
     */
    fun autoSeatAndClimate(
        seats: List<SeatPosition>,
        enabled: Boolean,
    ): VehicleAction =
        VehicleAction(
            autoSeatClimateAction =
                AutoSeatClimateAction(
                    carseat =
                        seats.mapNotNull { seat ->
                            autoSeatPosition(seat)?.let { AutoSeatClimateAction.CarSeat(on = enabled, seat_position = it) }
                        },
                ),
        )

    /** Go `SetPreconditioningMax`: `hvacSetPreconditioningMaxAction{on, manual_override}`(`manual_override_mode`는 Go처럼 비운다). */
    fun preconditioningMax(
        enabled: Boolean,
        manualOverride: Boolean,
    ): VehicleAction =
        VehicleAction(hvacSetPreconditioningMaxAction = HvacSetPreconditioningMaxAction(on = enabled, manual_override = manualOverride))

    /** Go `SetClimateKeeperMode`: `hvacClimateKeeperAction{ClimateKeeperAction, manual_override}`. TESLEMETRY-EXT 필드는 쓰지 않는다. */
    fun climateKeeperMode(
        mode: ClimateKeeperMode,
        manualOverride: Boolean,
    ): VehicleAction =
        VehicleAction(
            hvacClimateKeeperAction = HvacClimateKeeperAction(ClimateKeeperAction = mode.wire(), manual_override = manualOverride),
        )

    /** Go `SetBioweaponDefenseMode`: `hvacBioweaponModeAction{on, manual_override}`. */
    fun bioweaponDefenseMode(
        enabled: Boolean,
        manualOverride: Boolean,
    ): VehicleAction = VehicleAction(hvacBioweaponModeAction = HvacBioweaponModeAction(on = enabled, manual_override = manualOverride))

    /** Go `SetCabinOverheatProtection`: `setCabinOverheatProtectionAction{on, fan_only}`. */
    fun cabinOverheatProtection(
        enabled: Boolean,
        fanOnly: Boolean,
    ): VehicleAction = VehicleAction(setCabinOverheatProtectionAction = SetCabinOverheatProtectionAction(on = enabled, fan_only = fanOnly))

    /** Go `SetCabinOverheatProtectionTemperature`: `setCopTempAction{copActivationTemp}`(Go는 `Level`을 캐스트한다 — 와이어 값은 같다). */
    fun cabinOverheatProtectionTemperature(temperature: CabinOverheatProtectionTemperature): VehicleAction =
        VehicleAction(setCopTempAction = SetCopTempAction(copActivationTemp = temperature.wire()))

    /** Go `SetSeatHeater`의 원소 하나: `Level.addToHeaterAction` 다음 `SeatPosition.addToHeaterAction`(단계 멤버, 좌석 멤버 하나씩). */
    private fun heaterAction(
        seat: SeatPosition,
        level: SeatClimateLevel,
    ): HvacSeatHeaterActions.HvacSeatHeaterAction = heaterLevel(level).withHeaterSeat(seat)

    /** Go `Level.addToHeaterAction`. Go의 `default`(UNKNOWN)는 4값 enum이라 생기지 않는다. */
    private fun heaterLevel(level: SeatClimateLevel): HvacSeatHeaterActions.HvacSeatHeaterAction =
        when (level) {
            SeatClimateLevel.OFF -> HvacSeatHeaterActions.HvacSeatHeaterAction(SEAT_HEATER_OFF = Void())
            SeatClimateLevel.LOW -> HvacSeatHeaterActions.HvacSeatHeaterAction(SEAT_HEATER_LOW = Void())
            SeatClimateLevel.MED -> HvacSeatHeaterActions.HvacSeatHeaterAction(SEAT_HEATER_MED = Void())
            SeatClimateLevel.HIGH -> HvacSeatHeaterActions.HvacSeatHeaterAction(SEAT_HEATER_HIGH = Void())
        }

    /** Go `SeatPosition.addToHeaterAction`: `SECOND_ROW_*`는 proto의 `CAR_SEAT_REAR_*`다. UNKNOWN은 Go `default`처럼 `CAR_SEAT_UNKNOWN`. */
    private fun HvacSeatHeaterActions.HvacSeatHeaterAction.withHeaterSeat(seat: SeatPosition): HvacSeatHeaterActions.HvacSeatHeaterAction =
        when (seat) {
            SeatPosition.UNKNOWN -> copy(CAR_SEAT_UNKNOWN = Void())
            SeatPosition.FRONT_LEFT -> copy(CAR_SEAT_FRONT_LEFT = Void())
            SeatPosition.FRONT_RIGHT -> copy(CAR_SEAT_FRONT_RIGHT = Void())
            SeatPosition.SECOND_ROW_LEFT -> copy(CAR_SEAT_REAR_LEFT = Void())
            SeatPosition.SECOND_ROW_LEFT_BACK -> copy(CAR_SEAT_REAR_LEFT_BACK = Void())
            SeatPosition.SECOND_ROW_CENTER -> copy(CAR_SEAT_REAR_CENTER = Void())
            SeatPosition.SECOND_ROW_RIGHT -> copy(CAR_SEAT_REAR_RIGHT = Void())
            SeatPosition.SECOND_ROW_RIGHT_BACK -> copy(CAR_SEAT_REAR_RIGHT_BACK = Void())
            SeatPosition.THIRD_ROW_LEFT -> copy(CAR_SEAT_THIRD_ROW_LEFT = Void())
            SeatPosition.THIRD_ROW_RIGHT -> copy(CAR_SEAT_THIRD_ROW_RIGHT = Void())
        }

    /** Go `AutoSeatAndClimate`의 lookup. 없는 좌석은 null(거른다). */
    private fun autoSeatPosition(seat: SeatPosition): AutoSeatClimateAction.AutoSeatPosition_E? =
        when (seat) {
            SeatPosition.UNKNOWN -> AutoSeatClimateAction.AutoSeatPosition_E.AutoSeatPosition_Unknown
            SeatPosition.FRONT_LEFT -> AutoSeatClimateAction.AutoSeatPosition_E.AutoSeatPosition_FrontLeft
            SeatPosition.FRONT_RIGHT -> AutoSeatClimateAction.AutoSeatPosition_E.AutoSeatPosition_FrontRight
            else -> null
        }

    private fun ClimateKeeperMode.wire(): HvacClimateKeeperAction.ClimateKeeperAction_E =
        when (this) {
            ClimateKeeperMode.OFF -> HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Off
            ClimateKeeperMode.ON -> HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_On
            ClimateKeeperMode.DOG -> HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Dog
            ClimateKeeperMode.CAMP -> HvacClimateKeeperAction.ClimateKeeperAction_E.ClimateKeeperAction_Camp
        }

    private fun CabinOverheatProtectionTemperature.wire(): ClimateState.CopActivationTemp =
        when (this) {
            CabinOverheatProtectionTemperature.UNSPECIFIED -> ClimateState.CopActivationTemp.CopActivationTempUnspecified
            CabinOverheatProtectionTemperature.LOW -> ClimateState.CopActivationTemp.CopActivationTempLow
            CabinOverheatProtectionTemperature.MEDIUM -> ClimateState.CopActivationTemp.CopActivationTempMedium
            CabinOverheatProtectionTemperature.HIGH -> ClimateState.CopActivationTemp.CopActivationTempHigh
        }
}
