package dev.mirolab.teslable

import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import platform.Foundation.NSLog

/**
 * NSLog 로거(Xcode 콘솔·Console.app). 메시지는 Kotlin에서 한 문자열로 만든 뒤 `%s`로 넘긴다 — Kotlin/Native는 C 가변 인자로
 * Kotlin `String`을 NSString 객체가 아니라 C 문자열로 넘기므로 `%@`를 쓰면 EXC_BAD_ACCESS로 죽는다(2026-10-17 실기기 크래시).
 */
public actual class DebugLogger actual constructor() : TeslaLogger {
    actual override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        NSLog("%s", "[teslable/$tag] ${level.name}: ${message()}")
    }
}
