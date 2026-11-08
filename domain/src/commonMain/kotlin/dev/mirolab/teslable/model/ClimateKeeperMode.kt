// Ported from vehicle-command@a4b43c1 pkg/vehicle/climate.go (Apache-2.0) — ClimateKeeperMode
package dev.mirolab.teslable.model

/** 공조 유지 모드(FR-067). Go `ClimateKeeperMode`(= `HvacClimateKeeperAction_ClimateKeeperAction_E`)의 4개 값과 같은 순서다. */
public enum class ClimateKeeperMode {
    /** 끔. Go `ClimateKeeperModeOff`. */
    OFF,

    /** 켬(Keep). Go `ClimateKeeperModeOn`. */
    ON,

    /** 펫 모드. Go `ClimateKeeperModeDog`. */
    DOG,

    /** 캠프 모드. Go `ClimateKeeperModeCamp`. */
    CAMP,
}
