package io.github.smallmiro.teslable

import android.util.Log
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger

/** Logcat 로거. 태그는 `teslable/<tag>`. */
public actual class DebugLogger actual constructor() : TeslaLogger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        val text = message()
        val name = "teslable/$tag"
        when (level) {
            LogLevel.DEBUG -> Log.d(name, text)
            LogLevel.INFO -> Log.i(name, text)
            LogLevel.WARN -> Log.w(name, text)
            LogLevel.ERROR -> Log.e(name, text)
        }
    }
}
