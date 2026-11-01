import XCTest
@testable import TeslableSample

/// 연결 직후 모니터의 첫 조회가 끝나기 전에는 키·제어 탭의 자동 등록 확인(`listKeys()`)이 나가지 않는다(#128, 빌드 18 분석 F11).
final class InitialQueryGateTests: XCTestCase {
    func testWaitReturnsAtOnceWhenNothingIsPending() async {
        let gate = InitialQueryGate()
        await gate.wait()
    }

    func testWaitBlocksUntilTheFirstQueriesAreDone() async {
        let gate = InitialQueryGate()
        gate.arm()
        let passed = Locked(false)
        let waiter = Task {
            await gate.wait()
            passed.value = true
        }
        await settle()
        XCTAssertFalse(passed.value, "첫 조회가 끝나기 전에는 기다린다")

        gate.open()
        await waiter.value
        XCTAssertTrue(passed.value)
    }

    func testOpenedGateStaysOpenUntilTheNextConnection() async {
        let gate = InitialQueryGate()
        gate.arm()
        gate.open()
        await gate.wait()
        gate.arm() // 새 연결
        let passed = Locked(false)
        let waiter = Task {
            await gate.wait()
            passed.value = true
        }
        await settle()
        XCTAssertFalse(passed.value)
        gate.open()
        await waiter.value
    }

    func testArmingAgainReleasesWaitersOfTheOldConnection() async {
        let gate = InitialQueryGate()
        gate.arm()
        let old = Task { await gate.wait() }
        await settle()
        gate.arm() // 옛 연결이 끝나고 새 연결이 시작: 옛 기다림이 매달려 있으면 안 된다
        await old.value
        gate.open()
    }
}
