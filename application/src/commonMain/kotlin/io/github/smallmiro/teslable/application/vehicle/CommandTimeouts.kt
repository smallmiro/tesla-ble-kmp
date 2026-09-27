package io.github.smallmiro.teslable.application.vehicle

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * D29/ADR-0010의 시간 값. M3 `TeslaBleConfig`가 채운다. Go `tesla-control`의 `-command-timeout 5s`,
 * `defaultExpiration 5s`, `-connect-timeout 20s`.
 */
public data class CommandTimeouts(
    /** 명령 하나의 전체 시간(전송 재시도·응답 대기 포함). */
    val commandTimeout: Duration = 5.seconds,
    /** 인가 명령의 `expires_at` 수명(Go `defaultExpiration`). */
    val commandLifetime: Duration = 5.seconds,
    /** 핸드셰이크 전체 시간. */
    val handshakeTimeout: Duration = 20.seconds,
)
