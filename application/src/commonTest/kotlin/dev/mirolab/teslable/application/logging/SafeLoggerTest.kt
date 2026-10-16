package dev.mirolab.teslable.application.logging

import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun rethrowsCancellationSoStructuredConcurrencyIsNotBroken() {
        val cancelling = TeslaLogger { _, _, _ -> throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { SafeLogger(cancelling).log(LogLevel.INFO, "t") { "x" } }
    }

    @Test
    fun passesThroughLevelTagAndMessage() {
        val seen = mutableListOf<String>()
        SafeLogger { level, tag, message -> seen += "$level/$tag/${message()}" }.log(LogLevel.WARN, "ble") { "x" }
        assertEquals(listOf("WARN/ble/x"), seen)
    }
}
