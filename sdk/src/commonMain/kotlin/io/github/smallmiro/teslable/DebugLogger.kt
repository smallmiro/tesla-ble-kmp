package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.TeslaLogger

/** 플랫폼 로그(Android Logcat, iOS 통합 로그, JVM java.util.logging)로 보내는 개발용 로거(SDD §10). 릴리스 빌드에는 쓰지 않는다. */
public expect class DebugLogger() : TeslaLogger
