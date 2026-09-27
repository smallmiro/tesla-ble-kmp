package io.github.smallmiro.teslable.port

/** 로그 레벨. Go `internal/log`의 Error/Warning/Info/Debug. */
public enum class LogLevel {
    /** TX/RX hex 등 상세. */
    DEBUG,

    /** 세션·연결 이벤트. */
    INFO,

    /** 드롭·폐기 사유. */
    WARN,

    /** 복구 불가 상황. */
    ERROR,
}

/**
 * 로거 포트(SDD §10). 기본은 [NoOp]. [message]는 지연 평가되며 VIN은 마스킹된 채로, 키·nonce·평문은 어떤 레벨에서도 넣지 않는다.
 */
public fun interface TeslaLogger {
    /** 한 줄을 기록한다. */
    public fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    )

    /** 기본 구현. */
    public companion object {
        /** 아무것도 하지 않는 로거. */
        public val NoOp: TeslaLogger = TeslaLogger { _, _, _ -> }
    }
}
