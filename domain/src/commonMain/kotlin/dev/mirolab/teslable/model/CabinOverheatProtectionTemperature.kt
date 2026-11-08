// Ported from vehicle-command@a4b43c1 pkg/vehicle/climate.go (Apache-2.0) — SetCabinOverheatProtectionTemperature
package dev.mirolab.teslable.model

/**
 * 실내 과열 방지가 동작하는 온도 단계(FR-069). 와이어 값은 `ClimateState.CopActivationTemp`(Unspecified 0, Low 1, Medium 2, High 3)와 같다.
 * Go는 `Level`을 그대로 캐스트해 보낸다(`LevelOff` → Unspecified …). 같은 와이어 값을 이름이 맞는 전용 타입으로 받는 것은 이 라이브러리의
 * 선택이다(M5 계획 결정 기록).
 */
public enum class CabinOverheatProtectionTemperature {
    /** `CopActivationTempUnspecified`(0). Go `LevelOff`를 캐스트한 값. */
    UNSPECIFIED,

    /** `CopActivationTempLow`(1). */
    LOW,

    /** `CopActivationTempMedium`(2). */
    MEDIUM,

    /** `CopActivationTempHigh`(3). */
    HIGH,
}
