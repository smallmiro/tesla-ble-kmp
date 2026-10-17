package dev.mirolab.teslable

import dev.mirolab.teslable.port.LogLevel
import kotlin.test.Test

class DebugLoggerIosTest {
    @Test
    fun logsPlainKotlinStringsWithoutCrashing() {
        // 2026-10-17 실기기 크래시: NSLog("%@", kotlinString)는 Kotlin/Native가 가변 인자로 C 문자열을 넘겨 EXC_BAD_ACCESS.
        val logger = DebugLogger()
        LogLevel.entries.forEach { level -> logger.log(level, "ble") { "Connected to S609a019fbe407ebcC (block length 512)" } }
        logger.log(LogLevel.INFO, "teslable") { "percent %@ %s %d and 한국어" }
    }
}
