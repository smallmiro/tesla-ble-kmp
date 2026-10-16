package dev.mirolab.teslable

import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import platform.Foundation.NSLog

/** NSLog 로거(Xcode 콘솔·Console.app). */
public actual class DebugLogger actual constructor() : TeslaLogger {
    actual override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        NSLog("[teslable/%@] %@: %@", tag, level.name, message())
    }
}
