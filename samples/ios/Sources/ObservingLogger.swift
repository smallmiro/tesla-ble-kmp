import Foundation
import Teslable

/// 라이브러리 로그를 DebugLogger로 보내고, 관찰 로그(태그 `observe`)는 스트림으로도 내보낸다(M4 Task 1).
/// Kotlin 수신 스레드에서 불리므로 스레드 안전한 continuation만 쓴다.
/// 화면과 복사본에는 `observe` 줄만 간다. 다른 줄(특히 BLE의 `TX:`/`RX:` 원문 헥사)은 DebugLogger(통합 로그)로만 간다.
final class ObservingLogger: NSObject, TeslaLogger, @unchecked Sendable {
    private let debug = DebugLogger()
    let lines: AsyncStream<String>
    private let continuation: AsyncStream<String>.Continuation

    override init() {
        var made: AsyncStream<String>.Continuation?
        lines = AsyncStream(bufferingPolicy: .bufferingNewest(500)) { made = $0 }
        continuation = made!
        super.init()
    }

    func log(level: LogLevel, tag: String, message: @escaping () -> String) {
        if tag == "observe" {
            let text = message()
            debug.log(level: level, tag: tag, message: { text })
            continuation.yield(text)
        } else {
            debug.log(level: level, tag: tag, message: message)
        }
    }
}
