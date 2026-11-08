// Ported from vehicle-command@a4b43c1 pkg/vehicle/actions.go (Apache-2.0) — VentWindows, CloseWindows, ChangeSunroofState, HonkHorn,
// FlashLights
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.server.VehicleControlFlashLightsAction
import com.tesla.generated.carserver.server.VehicleControlHonkHornAction
import com.tesla.generated.carserver.server.VehicleControlSunroofOpenCloseAction
import com.tesla.generated.carserver.server.VehicleControlWindowAction

/**
 * Go actions.go의 Infotainment 차체 `VehicleAction` 빌더(FR-050, FR-051, FR-053). [InfotainmentCommands]가 `Action`에 담아 보낸다.
 * Go처럼 값을 검사하지 않는다. 값이 0이어도 oneof 멤버는 그대로 나간다(예: 선루프 단계 0은 `absolute_level` 0을 보낸다).
 */
internal object VehicleControlActions {
    /** Go `VentWindows`: `vehicleControlWindowAction{vent: {}}`. */
    fun ventWindows(): VehicleAction = VehicleAction(vehicleControlWindowAction = VehicleControlWindowAction(vent = Void()))

    /** Go `CloseWindows`: `vehicleControlWindowAction{close: {}}`. */
    fun closeWindows(): VehicleAction = VehicleAction(vehicleControlWindowAction = VehicleControlWindowAction(close = Void()))

    /** Go `ChangeSunroofState`: `vehicleControlSunroofOpenCloseAction{absolute_level}`(oneof라 0도 보낸다). */
    fun sunroof(level: Int): VehicleAction =
        VehicleAction(vehicleControlSunroofOpenCloseAction = VehicleControlSunroofOpenCloseAction(absolute_level = level))

    /** Go `HonkHorn`: `vehicleControlHonkHornAction {}`. */
    fun honkHorn(): VehicleAction = VehicleAction(vehicleControlHonkHornAction = VehicleControlHonkHornAction())

    /** Go `FlashLights`: `vehicleControlFlashLightsAction {}`. */
    fun flashLights(): VehicleAction = VehicleAction(vehicleControlFlashLightsAction = VehicleControlFlashLightsAction())
}
