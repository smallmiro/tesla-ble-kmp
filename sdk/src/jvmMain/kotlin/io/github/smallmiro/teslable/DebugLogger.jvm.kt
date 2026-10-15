package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import java.util.logging.Level
import java.util.logging.Logger

/** java.util.logging 로거. 로거 이름은 `teslable.<tag>`. */
public actual class DebugLogger actual constructor() : TeslaLogger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        val jul =
            when (level) {
                LogLevel.DEBUG -> Level.FINE
                LogLevel.INFO -> Level.INFO
                LogLevel.WARN -> Level.WARNING
                LogLevel.ERROR -> Level.SEVERE
            }
        val logger = Logger.getLogger("teslable.$tag")
        if (logger.isLoggable(jul)) logger.log(jul, message())
    }
}
