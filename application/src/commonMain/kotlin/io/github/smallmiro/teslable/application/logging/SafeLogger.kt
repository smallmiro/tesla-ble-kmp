package io.github.smallmiro.teslable.application.logging

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger

/**
 * 사용자 로거를 감싸 로거·메시지 람다가 던진 예외를 삼킨다(M2 인계). 로그 호출은 수신 코루틴 안에서도 일어나므로
 * 앱 로거의 버그가 BLE 수신을 멈추게 해서는 안 된다. `log`는 suspend가 아니라 취소 예외가 지나갈 일은 없다.
 */
public class SafeLogger(
    private val delegate: TeslaLogger,
) : TeslaLogger {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 로깅 실패는 어떤 경우에도 호출자에게 전파하지 않는다
    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        try {
            delegate.log(level, tag, message)
        } catch (e: Exception) {
            // 의도적으로 무시
        }
    }
}
