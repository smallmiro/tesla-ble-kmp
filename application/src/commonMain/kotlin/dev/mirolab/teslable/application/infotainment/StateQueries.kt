// Ported from vehicle-command@a4b43c1 pkg/vehicle/state.go (Apache-2.0) — StateCategory.submessage, GetState (request assembly)
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.GetChargeScheduleState
import com.tesla.generated.carserver.server.GetChargeState
import com.tesla.generated.carserver.server.GetClimateState
import com.tesla.generated.carserver.server.GetClosuresState
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetLocationState
import com.tesla.generated.carserver.server.GetMediaDetailState
import com.tesla.generated.carserver.server.GetMediaState
import com.tesla.generated.carserver.server.GetParentalControlsState
import com.tesla.generated.carserver.server.GetPreconditioningScheduleState
import com.tesla.generated.carserver.server.GetSoftwareUpdateState
import com.tesla.generated.carserver.server.GetTirePressureState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.VehicleAction
import dev.mirolab.teslable.model.StateCategory

/** Go `GetState`의 요청: `Action{vehicleAction{getVehicleData{선택자 하나}}}`. */
internal fun StateCategory.toAction(): Action = Action(vehicleAction = VehicleAction(getVehicleData = submessage()))

/** Go `submessage`: 종류 하나에 선택자 하나. 공식 Go에 없는 `TESLEMETRY-EXT` `Get*` 필드는 고르지 않는다(ADR-0002 결정 3). */
private fun StateCategory.submessage(): GetVehicleData =
    when (this) {
        StateCategory.CHARGE -> GetVehicleData(getChargeState = GetChargeState())
        StateCategory.CLIMATE -> GetVehicleData(getClimateState = GetClimateState())
        StateCategory.DRIVE -> GetVehicleData(getDriveState = GetDriveState())
        StateCategory.LOCATION -> GetVehicleData(getLocationState = GetLocationState())
        StateCategory.CLOSURES -> GetVehicleData(getClosuresState = GetClosuresState())
        StateCategory.CHARGE_SCHEDULE -> GetVehicleData(getChargeScheduleState = GetChargeScheduleState())
        StateCategory.PRECONDITIONING_SCHEDULE -> GetVehicleData(getPreconditioningScheduleState = GetPreconditioningScheduleState())
        StateCategory.TIRE_PRESSURE -> GetVehicleData(getTirePressureState = GetTirePressureState())
        StateCategory.MEDIA -> GetVehicleData(getMediaState = GetMediaState())
        StateCategory.MEDIA_DETAIL -> GetVehicleData(getMediaDetailState = GetMediaDetailState())
        StateCategory.SOFTWARE_UPDATE -> GetVehicleData(getSoftwareUpdateState = GetSoftwareUpdateState())
        StateCategory.PARENTAL_CONTROLS -> GetVehicleData(getParentalControlsState = GetParentalControlsState())
    }
