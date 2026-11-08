// Ported from vehicle-command@a4b43c1 pkg/vehicle/climate.go (Apache-2.0) — Level
package dev.mirolab.teslable.model

/**
 * 좌석 히터·쿨러 단계(FR-061, FR-063). Go `Level`의 4개 상수와 같은 순서다. 와이어 값은 명령마다 다르다: 히터는 단계마다 `Void` 멤버 하나,
 * 쿨러는 Go처럼 `level + 1`(OFF 1 … HIGH 4)이다.
 */
public enum class SeatClimateLevel {
    /** 끔. Go `LevelOff`. */
    OFF,

    /** 약. Go `LevelLow`. */
    LOW,

    /** 중. Go `LevelMed`. */
    MED,

    /** 강. Go `LevelHigh`. */
    HIGH,
}
