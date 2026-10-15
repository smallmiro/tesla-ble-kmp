package io.github.smallmiro.teslable

import android.content.Context

/** Android 컨텍스트(애플리케이션 컨텍스트로 보관). */
public actual class PlatformContext(
    context: Context,
) {
    /** 애플리케이션 컨텍스트. */
    public val context: Context = context.applicationContext
}
