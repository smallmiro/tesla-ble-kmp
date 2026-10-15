package io.github.smallmiro.teslable.storage

import android.content.Context
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import java.io.File

/** Android 기본 세션 캐시: `filesDir/teslable/sessions`(MODE_PRIVATE 앱 전용 영역, SDD §2.6). */
public fun androidSessionCache(
    context: Context,
    crypto: CryptoPrimitives,
    logger: TeslaLogger = TeslaLogger.NoOp,
): SessionCache = FileSessionCache(File(context.applicationContext.filesDir, "teslable/sessions"), crypto, logger)
