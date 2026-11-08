// Ported from vehicle-command@a4b43c1 pkg/vehicle/charge.go (Apache-2.0) — ChargingPolicy
package dev.mirolab.teslable.model

/** 예약 출발의 사전 공조·오프피크 충전을 하는 요일(FR-075). Go `ChargingPolicy`의 3개 상수와 같은 순서다. */
public enum class ChargingPolicy {
    /** 하지 않는다. 하위 메시지를 보내지 않는다. Go `ChargingPolicyOff`. */
    OFF,

    /** 매일. Go `ChargingPolicyAllDays`. */
    ALL_DAYS,

    /** 평일만. Go `ChargingPolicyWeekdays`. */
    WEEKDAYS,
}
