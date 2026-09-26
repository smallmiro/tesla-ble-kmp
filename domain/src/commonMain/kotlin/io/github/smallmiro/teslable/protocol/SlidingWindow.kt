// Ported from vehicle-command@a4b43c1 internal/authentication/window.go (Apache-2.0)
package io.github.smallmiro.teslable.protocol

private const val SHIFT_THRESHOLD = 64

internal class WindowUpdate(
    val counter: UInt,
    val window: ULong,
    val ok: Boolean,
)

/**
 * Go `updateSlidingWindow`. Go의 uint64 시프트는 64 이상이면 0이지만 Kotlin `shl`은 하위 6비트만 쓰므로 명시적으로 분기한다.
 */
internal fun updateSlidingWindow(
    counter: UInt,
    window: ULong,
    newCounter: UInt,
    size: Int,
): WindowUpdate {
    if (counter == newCounter) return WindowUpdate(counter, window, ok = false)
    if (newCounter < counter) {
        val age = (counter - newCounter).toInt()
        if (age > size) return WindowUpdate(counter, window, ok = false)
        val bit = 1uL shl (age - 1)
        if (window and bit != 0uL) return WindowUpdate(counter, window, ok = false)
        return WindowUpdate(counter, window or bit, ok = true)
    }
    val shift = (newCounter - counter).toLong()
    var updated = if (shift >= SHIFT_THRESHOLD) 0uL else window shl shift.toInt()
    if (shift - 1 < SHIFT_THRESHOLD) updated = updated or (1uL shl (shift - 1).toInt())
    return WindowUpdate(newCounter, updated, ok = true)
}

/**
 * 요청별 응답 counter 재사용 검사. 첫 counter는 무조건 수용한다.
 */
public class SlidingWindow(
    private val size: Int = DEFAULT_SIZE,
) {
    private var used = false
    private var counter: UInt = 0u
    private var history: ULong = 0uL

    init {
        require(size in MIN_WINDOW_SIZE..MAX_WINDOW_SIZE) { "window size must be 1..64" }
    }

    /**
     * 새 counter를 수용하면 true를 돌려준다. 중복되거나 너무 오래되면 false.
     */
    public fun update(newCounter: UInt): Boolean {
        if (!used) {
            used = true
            counter = newCounter
            return true
        }
        val result = updateSlidingWindow(counter, history, newCounter, size)
        counter = result.counter
        history = result.window
        return result.ok
    }

    public companion object {
        /**
         * 기본 sliding window 크기 (32 개 counter).
         */
        public const val DEFAULT_SIZE: Int = 32
        private const val MIN_WINDOW_SIZE: Int = 1
        private const val MAX_WINDOW_SIZE: Int = 64

        /**
         * 테스트용: Go 테이블처럼 상태를 직접 놓는다.
         */
        internal fun restore(
            counter: UInt,
            history: ULong,
            size: Int = DEFAULT_SIZE,
        ): SlidingWindow =
            SlidingWindow(size).apply {
                this.used = true
                this.counter = counter
                this.history = history
            }
    }
}
