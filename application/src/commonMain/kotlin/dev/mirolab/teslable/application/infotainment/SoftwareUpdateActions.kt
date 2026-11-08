// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — ScheduleSoftwareUpdate, CancelSoftwareUpdate
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.server.VehicleControlCancelSoftwareUpdateAction
import com.tesla.generated.carserver.server.VehicleControlScheduleSoftwareUpdateAction

/** Go infotainment.go의 소프트웨어 업데이트 `VehicleAction` 빌더(FR-092). [InfotainmentCommands]가 `Action`에 담아 보낸다. */
internal object SoftwareUpdateActions {
    /** Go `ScheduleSoftwareUpdate`: `vehicleControlScheduleSoftwareUpdateAction{offset_sec}`. Go처럼 값을 검사하지 않는다. */
    fun schedule(delaySeconds: Int): VehicleAction =
        VehicleAction(
            vehicleControlScheduleSoftwareUpdateAction = VehicleControlScheduleSoftwareUpdateAction(offset_sec = delaySeconds),
        )

    /** Go `CancelSoftwareUpdate`: `vehicleControlCancelSoftwareUpdateAction {}`. */
    fun cancel(): VehicleAction = VehicleAction(vehicleControlCancelSoftwareUpdateAction = VehicleControlCancelSoftwareUpdateAction())
}
