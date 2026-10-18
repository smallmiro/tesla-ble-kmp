package dev.mirolab.teslable

import dev.mirolab.teslable.port.SessionCache
import dev.mirolab.teslable.port.TeslaLogger
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
    /** add-key-request 진행 이벤트를 기다리는 기본 시간(설계 구체화 4). */
    val pairingTimeout: Duration = 60.seconds,
) {
    /** Swift용 생성 도우미(기본 인자·`Duration`을 쓰지 않고 만든다). */
    public companion object {
        /** 모든 값을 기본값으로, 로거만 바꾼다. */
        public fun default(logger: TeslaLogger = TeslaLogger.NoOp): TeslaBleConfig = TeslaBleConfig(logger = logger)

        /**
         * 시간 제한을 초(Double)로 받는 Swift용 팩토리. 모든 인자 필수(Swift에는 기본 인자 오버로드가 없다).
         * 0 이하·NaN·무한이면 `IllegalArgumentException`. `commandLifetime`은 기본값을 쓴다.
         */
        public fun withSeconds(
            logger: TeslaLogger,
            commandTimeoutSeconds: Double,
            handshakeTimeoutSeconds: Double,
            connectTimeoutSeconds: Double,
            pairingTimeoutSeconds: Double,
        ): TeslaBleConfig = TeslaBleConfig(
            logger = logger,
            commandTimeout = positive(commandTimeoutSeconds, "commandTimeoutSeconds"),
            handshakeTimeout = positive(handshakeTimeoutSeconds, "handshakeTimeoutSeconds"),
            connectTimeout = positive(connectTimeoutSeconds, "connectTimeoutSeconds"),
            pairingTimeout = positive(pairingTimeoutSeconds, "pairingTimeoutSeconds"),
        )

        private fun positive(value: Double, name: String): Duration {
            require(value > 0 && value.isFinite()) { "$name must be a positive finite number of seconds: $value" }
            return value.seconds
        }
    }
}
