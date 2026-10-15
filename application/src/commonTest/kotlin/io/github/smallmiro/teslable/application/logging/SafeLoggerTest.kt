package io.github.smallmiro.teslable.application.logging

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlin.test.Test
import kotlin.test.assertEquals

class SafeLoggerTest {
    @Test
    fun swallowsExceptionsFromTheUserLoggerAndFromTheMessageLambda() {
        // M2 인계: 사용자가 준 로거가 던지면 수신 코루틴이 죽는다
        var calls = 0
        val throwing =
            TeslaLogger { _, _, message ->
                calls++
                message()
                error("app logger bug")
            }
        val safe = SafeLogger(throwing)
        safe.log(LogLevel.INFO, "t") { "ok" }
        safe.log(LogLevel.INFO, "t") { error("message bug") }
        assertEquals(2, calls)
    }

    @Test
    fun passesThroughLevelTagAndMessage() {
        val seen = mutableListOf<String>()
        SafeLogger { level, tag, message -> seen += "$level/$tag/${message()}" }.log(LogLevel.WARN, "ble") { "x" }
        assertEquals(listOf("WARN/ble/x"), seen)
    }
}
