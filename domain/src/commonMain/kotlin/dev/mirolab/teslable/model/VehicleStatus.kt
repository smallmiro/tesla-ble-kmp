package dev.mirolab.teslable.model

/** 차량 잠금 상태(`VCSEC.VehicleLockState_E`). 모르는 값은 [UNKNOWN]. */
public enum class LockState {
    /** 열림. */
    UNLOCKED,

    /** 잠김. */
    LOCKED,

    /** 내부 잠금. */
    INTERNAL_LOCKED,

    /** 일부만 열림. */
    SELECTIVE_UNLOCKED,

    /** 알 수 없음(더 새 펌웨어). */
    UNKNOWN,
}

/** 문·트렁크·충전구 상태(`VCSEC.ClosureState_E`). 모르는 값은 [UNKNOWN]. */
public enum class ClosureState {
    /** 닫힘. */
    CLOSED,

    /** 열림. */
    OPEN,

    /** 반쯤 열림. */
    AJAR,

    /** 알 수 없음(차량 보고 또는 모르는 값). */
    UNKNOWN,

    /** 래치 해제 실패. */
    FAILED_UNLATCH,

    /** 여는 중. */
    OPENING,

    /** 닫는 중. */
    CLOSING,
}

/** 차량 수면 상태. */
public enum class SleepStatus {
    /** 알 수 없음. */
    UNKNOWN,

    /** 깨어 있음. */
    AWAKE,

    /** 잠듦. */
    ASLEEP,
}

/** 차 안 사용자 존재. */
public enum class UserPresence {
    /** 알 수 없음. */
    UNKNOWN,

    /** 없음. */
    NOT_PRESENT,

    /** 있음. */
    PRESENT,
}

/**
 * 기어(`VCSEC.Gear_E`, `VehicleStatus` 필드 7). Teslemetry 스키마의 TESLEMETRY-EXT 필드로 공식 Go 스키마에는 없다(FR-036).
 * 모르는 값은 [UNKNOWN].
 */
public enum class Gear {
    /** 알 수 없음. 차량이 보내지 않은 경우, `GEAR_UNKNOWN`(0), 모르는 값(더 새 펌웨어)을 구별하지 않는다. */
    UNKNOWN,

    /** P(주차). */
    PARK,

    /** D(주행). */
    DRIVE,

    /** R(후진). */
    REVERSE,

    /** N(중립). */
    NEUTRAL,
}

/** 개폐부 8곳(`VCSEC.ClosureStatuses`). */
public data class Closures(
    /** 운전석 앞문. */
    val frontDriverDoor: ClosureState,
    /** 조수석 앞문. */
    val frontPassengerDoor: ClosureState,
    /** 운전석 뒷문. */
    val rearDriverDoor: ClosureState,
    /** 조수석 뒷문. */
    val rearPassengerDoor: ClosureState,
    /** 트렁크. */
    val rearTrunk: ClosureState,
    /** 프렁크. */
    val frontTrunk: ClosureState,
    /** 충전구. */
    val chargePort: ClosureState,
    /** 톤노 커버(사이버트럭). */
    val tonneau: ClosureState,
)

/** `vehicleStatus()` 결과(FR-031, FR-036, SDD §7.3). 인증·깨우기 없이 읽는다. */
public data class VehicleStatus(
    /** 잠금 상태. */
    val lockState: LockState,
    /** 개폐부. 차량이 보내지 않으면 모두 CLOSED — Go getter 기본값과 같음. */
    val closures: Closures,
    /** 수면 상태. */
    val sleepStatus: SleepStatus,
    /** 사용자 존재. */
    val userPresence: UserPresence,
    /** 톤노 열린 비율(%). 보고하지 않으면 null. */
    val tonneauPercentOpen: Int?,
    /**
     * 기어(FR-036). proto3 enum이라 차량이 보내지 않은 것과 `GEAR_UNKNOWN`(0)을 구별할 수 없어 둘 다 [Gear.UNKNOWN]이다.
     * 실차에서는 브로드캐스트로만 확인했다(조회 응답에 실리는지는 미확인).
     */
    val gear: Gear,
)
