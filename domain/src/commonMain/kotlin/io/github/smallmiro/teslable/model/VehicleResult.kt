package io.github.smallmiro.teslable.model

/**
 * 모든 명령·조회의 결과(FR-100, ADR-0006). 호출자는 세 갈래를 반드시 다룬다.
 * 응답 없이 시간이 초과되면 [Uncertain]이며 라이브러리는 자동 재전송하지 않는다.
 */
public sealed interface VehicleResult<out T> {
    /** 차량이 확인 응답을 보냈다. */
    public data class Success<T>(
        /** 결과 값. */
        public val value: T,
    ) : VehicleResult<T>

    /** 차량이 실행했을 수 있으나 확인하지 못했다. `vehicleStatus()`/`getState()`로 재조회한다(FR-102). */
    public data class Uncertain(
        /** 원인. `mayHaveSucceeded == true`. */
        public val error: VehicleError,
    ) : VehicleResult<Nothing>

    /** 실행되지 않았다. */
    public data class Failure(
        /** 원인. */
        public val error: VehicleError,
    ) : VehicleResult<Nothing>
}
