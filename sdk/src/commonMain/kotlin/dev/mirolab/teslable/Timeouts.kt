package dev.mirolab.teslable

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Swift에서 `timeout:` 인자를 넘기는 도우미(설계 구체화 8). Kotlin `Duration`은 Swift에서 값 타입으로 보이지 않는다 — 반환형이 nullable이라
 * 박싱된 채로 `timeout:`(`id`)에 그대로 넘어간다: `try await vehicle.lock(timeout: Timeouts.shared.seconds(seconds: 10))`. Kotlin은 `10.seconds`.
 */
public object Timeouts {
    /** [seconds]초. 0 이하·NaN·무한이면 `IllegalArgumentException`. */
    public fun seconds(seconds: Double): Duration? {
        require(seconds > 0 && seconds.isFinite()) { "timeout must be a positive finite number of seconds: $seconds" }
        return seconds.seconds
    }

    /** [timeout]의 밀리초(null이면 -1). Swift 쪽 왕복 확인용. */
    public fun millisecondsOf(timeout: Duration?): Long = timeout?.inWholeMilliseconds ?: -1
}
