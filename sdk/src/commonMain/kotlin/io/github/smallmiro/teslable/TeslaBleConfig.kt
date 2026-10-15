package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 파사드 설정(SDD §2.3, D29). */
public data class TeslaBleConfig(
    /** 로거(기본 NoOp, NFR-006). 던져도 라이브러리는 멈추지 않는다. */
    val logger: TeslaLogger = TeslaLogger.NoOp,
    /** 세션 캐시. null이면 플랫폼 기본(Android 파일, iOS Keychain, JVM 메모리). */
    val sessionCache: SessionCache? = null,
    /** 명령 기본 시간 제한. */
    val commandTimeout: Duration = 5.seconds,
    /** 인증 명령의 `expires_at` 수명. */
    val commandLifetime: Duration = 5.seconds,
    /** 핸드셰이크 기본 시간 제한. */
    val handshakeTimeout: Duration = 20.seconds,
    /** 스캔·연결 기본 시간 제한. */
    val connectTimeout: Duration = 30.seconds,
) {
    /** Swift용 생성 도우미(기본 인자·`Duration`을 쓰지 않고 만든다). */
    public companion object {
        /** 모든 값을 기본값으로, 로거만 바꾼다. */
        public fun default(logger: TeslaLogger = TeslaLogger.NoOp): TeslaBleConfig = TeslaBleConfig(logger = logger)
    }
}
