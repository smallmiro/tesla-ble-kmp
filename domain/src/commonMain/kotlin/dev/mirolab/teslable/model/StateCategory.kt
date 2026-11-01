// Ported from vehicle-command@a4b43c1 pkg/vehicle/state.go (Apache-2.0) — StateCategory
package dev.mirolab.teslable.model

/**
 * `getState`로 조회하는 Infotainment 상태 종류(FR-033). Go `StateCategory`의 12개 상수와 같은 순서다(SDD §7.3).
 * 요청 하나에 종류 하나만 고르며, 답은 Wire `VehicleData`의 대응 필드에 담겨 온다.
 */
public enum class StateCategory {
    /** 충전. `VehicleData.charge_state`. */
    CHARGE,

    /** 공조. `VehicleData.climate_state`. */
    CLIMATE,

    /** 주행. `VehicleData.drive_state`. */
    DRIVE,

    /** 위치. `VehicleData.location_state`. */
    LOCATION,

    /** 개폐부. `VehicleData.closures_state`. */
    CLOSURES,

    /** 충전 일정. `VehicleData.charge_schedule_state`. */
    CHARGE_SCHEDULE,

    /** 사전 공조 일정. `VehicleData.preconditioning_schedule_state`. */
    PRECONDITIONING_SCHEDULE,

    /** 타이어 공기압. `VehicleData.tire_pressure_state`. */
    TIRE_PRESSURE,

    /** 미디어. `VehicleData.media_state`. */
    MEDIA,

    /** 미디어 상세. `VehicleData.media_detail_state`. */
    MEDIA_DETAIL,

    /** 소프트웨어 업데이트. `VehicleData.software_update_state`. */
    SOFTWARE_UPDATE,

    /** 자녀 보호. `VehicleData.parental_controls_state`. */
    PARENTAL_CONTROLS,
}
