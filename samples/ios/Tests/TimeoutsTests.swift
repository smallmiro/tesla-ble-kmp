import XCTest
import Teslable

final class TimeoutsTests: XCTestCase {
    func testBoxedDurationRoundTripsThroughKotlin() {
        XCTAssertEqual(Timeouts.shared.millisecondsOf(timeout: Timeouts.shared.seconds(seconds: 2.5)), 2500)
        XCTAssertEqual(Timeouts.shared.millisecondsOf(timeout: nil), -1)
    }

    func testConfigFactoryTakesSeconds() {
        let config = TeslaBleConfig.companion.withSeconds(logger: DebugLogger(), commandTimeoutSeconds: 10, handshakeTimeoutSeconds: 20,
                                                          connectTimeoutSeconds: 30, pairingTimeoutSeconds: 90)
        XCTAssertNotNil(config)
        XCTAssertNotNil(TeslaBleConfig.companion.default(logger: DebugLogger())) // 기존 호출이 그대로 컴파일된다
    }
}
