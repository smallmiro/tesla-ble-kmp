package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError

/** 스캔·연결 직전 검사(FR-006, 설계 구체화 7). 문제가 없으면 null. */
public fun interface BleAvailability {
    /** 권한이 없으면 `PermissionDenied`, 그 밖에 알 수 있는 문제면 해당 오류. */
    public fun check(): VehicleError.TransportError?

    /** 기본값. */
    public companion object {
        /** 항상 통과(JVM·테스트). */
        public val ALWAYS: BleAvailability = BleAvailability { null }
    }
}
