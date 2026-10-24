import XCTest
@testable import TeslableSample

/// SFR-018 키·제어 탭의 순수 규칙: 단계 표(`KeyControlSteps`)와 명령 실행 순서(`KeyControlRunner`).
/// 규칙의 정본은 `samples/documents/md/05-key-control-tab.md` 이다.
final class KeyControlStepsTests: XCTestCase {
    private func steps(
        keyReady: Bool = false, keyLocked: Bool = false, vinSaved: Bool = false, connected: Bool = false,
        enrollment: Enrollment = .unknown, busy: Bool = false
    ) -> KeyControlSteps {
        KeyControlSteps(KeyControlInputs(
            keyReady: keyReady, keyLocked: keyLocked, vinSaved: vinSaved, connected: connected, enrollment: enrollment, busy: busy
        ))
    }

    // MARK: 1 Key

    func testKeyStepIsToDoWithoutAKey() {
        let s = steps().key
        XCTAssertEqual(s.state, .todo)
        XCTAssertTrue(s.buttonEnabled)
    }

    func testKeyStepIsDoneWhenTheKeyIsReady() {
        XCTAssertEqual(steps(keyReady: true).key.state, .done)
    }

    func testKeyStepIsBlockedWhenThePhoneLockBlocksTheKey() {
        let s = steps(keyLocked: true).key
        XCTAssertEqual(s.state, .blocked)
        XCTAssertEqual(s.hint, "폰을 잠금 해제한 뒤 다시 누르세요")
        XCTAssertTrue(s.buttonEnabled, "잠금 해제 뒤 다시 누를 수 있어야 한다")
    }

    func testKeyButtonIsDisabledWhileBusy() {
        XCTAssertFalse(steps(busy: true).key.buttonEnabled)
        XCTAssertFalse(steps(keyLocked: true, busy: true).key.buttonEnabled)
    }

    // MARK: 2 Connect

    func testConnectStepIsBlockedWithoutASavedVin() {
        let s = steps().connect
        XCTAssertEqual(s.state, .blocked)
        XCTAssertEqual(s.hint, "Live 탭에서 VIN을 저장하세요")
        XCTAssertFalse(s.buttonEnabled)
    }

    func testConnectStepIsToDoWithAVinWhenNotConnected() {
        let s = steps(vinSaved: true).connect
        XCTAssertEqual(s.state, .todo)
        XCTAssertTrue(s.buttonEnabled)
    }

    func testConnectStepIsDoneWhenConnectedAndTheButtonIsOff() {
        let s = steps(vinSaved: true, connected: true).connect
        XCTAssertEqual(s.state, .done)
        XCTAssertFalse(s.buttonEnabled)
    }

    func testConnectButtonIsDisabledWhileBusy() {
        XCTAssertFalse(steps(vinSaved: true, busy: true).connect.buttonEnabled)
    }

    // MARK: 3 Enroll

    func testEnrollStepIsBlockedWithoutKeyOrConnection() {
        for (ready, connected) in [(false, false), (true, false), (false, true)] {
            let s = steps(keyReady: ready, vinSaved: true, connected: connected).enroll
            XCTAssertEqual(s.state, .blocked, "key=\(ready) connected=\(connected)")
            XCTAssertEqual(s.hint, "1·2단계를 먼저 끝내세요")
            XCTAssertFalse(s.buttonEnabled)
        }
    }

    func testEnrollStepIsToDoForUnknownAndNotEnrolled() {
        for e in [Enrollment.unknown, .notEnrolled] {
            let s = steps(keyReady: true, vinSaved: true, connected: true, enrollment: e).enroll
            XCTAssertEqual(s.state, .todo, "\(e)")
            XCTAssertTrue(s.buttonEnabled, "\(e)")
        }
    }

    func testEnrollButtonIsOffWhileChecking() {
        let s = steps(keyReady: true, vinSaved: true, connected: true, enrollment: .checking).enroll
        XCTAssertEqual(s.state, .todo)
        XCTAssertFalse(s.buttonEnabled)
    }

    func testEnrollStepIsDoneWhenEnrolled() {
        let s = steps(keyReady: true, vinSaved: true, connected: true, enrollment: .enrolled).enroll
        XCTAssertEqual(s.state, .done)
        XCTAssertFalse(s.buttonEnabled)
    }

    func testEnrollButtonIsDisabledWhileBusy() {
        XCTAssertFalse(steps(keyReady: true, vinSaved: true, connected: true, enrollment: .notEnrolled, busy: true).enroll.buttonEnabled)
    }

    // MARK: 4 Control

    func testControlIsBlockedUntilTheKeyIsEnrolled() {
        for e in [Enrollment.unknown, .checking, .notEnrolled] {
            let s = steps(keyReady: true, vinSaved: true, connected: true, enrollment: e).control
            XCTAssertEqual(s.state, .blocked, "\(e)")
            XCTAssertEqual(s.hint, "키를 차량에 등록하면 버튼이 켜집니다")
            XCTAssertFalse(s.buttonEnabled, "\(e)")
        }
    }

    func testControlButtonsAreOnWhenEnrolledAndConnected() {
        let s = steps(keyReady: true, vinSaved: true, connected: true, enrollment: .enrolled).control
        XCTAssertNotEqual(s.state, .blocked)
        XCTAssertTrue(s.buttonEnabled)
    }

    func testControlButtonsAreOffWhileBusy() {
        XCTAssertFalse(steps(keyReady: true, vinSaved: true, connected: true, enrollment: .enrolled, busy: true).control.buttonEnabled)
    }

    func testControlButtonsAreOffWithoutKeyOrConnectionEvenIfEnrollmentLooksEnrolled() {
        XCTAssertFalse(steps(keyReady: false, vinSaved: true, connected: true, enrollment: .enrolled).control.buttonEnabled)
        XCTAssertFalse(steps(keyReady: true, vinSaved: true, connected: false, enrollment: .enrolled).control.buttonEnabled)
    }

    // MARK: 부가 버튼

    func testCheckEnrollmentButtonNeedsKeyAndConnectionAndIsOffWhileCheckingOrBusy() {
        XCTAssertTrue(steps(keyReady: true, vinSaved: true, connected: true, enrollment: .unknown).checkEnrollmentEnabled)
        XCTAssertTrue(steps(keyReady: true, vinSaved: true, connected: true, enrollment: .enrolled).checkEnrollmentEnabled, "다시 확인할 수 있다")
        XCTAssertFalse(steps(keyReady: true, vinSaved: true, connected: true, enrollment: .checking).checkEnrollmentEnabled)
        XCTAssertFalse(steps(keyReady: true, vinSaved: true, connected: true, busy: true).checkEnrollmentEnabled)
        XCTAssertFalse(steps(keyReady: false, vinSaved: true, connected: true).checkEnrollmentEnabled)
        XCTAssertFalse(steps(keyReady: true, vinSaved: true, connected: false).checkEnrollmentEnabled)
    }

    func testRefreshStatusButtonNeedsOnlyAConnectionAndNoBusy() {
        XCTAssertTrue(steps(connected: true).refreshStatusEnabled, "vehicleStatus() 는 키 없이도 된다")
        XCTAssertFalse(steps(connected: false).refreshStatusEnabled)
        XCTAssertFalse(steps(connected: true, busy: true).refreshStatusEnabled)
    }
}

final class ControlButtonTests: XCTestCase {
    func testOnlyFrunkAndRemoteDriveAskForConfirmation() {
        let confirming = Set(ControlButton.allCases.filter { $0.confirmation != nil })
        XCTAssertEqual(confirming, [.openFrunk, .remoteDrive])
    }

    func testFrunkConfirmationSaysItCannotBeClosedRemotely() {
        XCTAssertTrue(ControlButton.openFrunk.confirmation?.message.contains("원격으로 닫을 수 없습니다") == true)
    }

    func testOtherModelsGroupHoldsModelXAndCybertruckButtons() {
        XCTAssertEqual(Set(ControlButton.allCases.filter { $0.group == .otherModels }), [
            .autoSecure, .openTonneau, .closeTonneau, .stopTonneau,
        ])
    }

    func testEverySpecButtonExists() {
        XCTAssertEqual(ControlButton.allCases.count, 11)
        XCTAssertEqual(Set(ControlButton.allCases.map(\.title)).count, 11, "라벨이 겹치지 않는다")
    }
}

final class StatusCardTests: XCTestCase {
    func testKoreanLabelsForKnownValuesAndRawNameForOthers() {
        let card = StatusCard(lock: "LOCKED", trunk: "OPEN", frunk: "CLOSED", sleep: "ASLEEP", at: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(card.lockText, "잠김")
        XCTAssertEqual(card.trunkText, "열림")
        XCTAssertEqual(card.frunkText, "닫힘")
        XCTAssertEqual(card.sleepText, "수면 중")
        let odd = StatusCard(lock: "SOMETHING_NEW", trunk: "AJAR", frunk: "UNKNOWN", sleep: "AWAKE", at: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(odd.lockText, "SOMETHING_NEW")
        XCTAssertEqual(odd.trunkText, "살짝 열림")
        XCTAssertEqual(odd.frunkText, "알 수 없음")
        XCTAssertEqual(odd.sleepText, "깨어 있음")
    }
}

/// 명령 한 번의 순서: 세션 보장 → 명령(정확히 한 번) → 재조회. 가짜 클로저로 시험한다.
final class KeyControlRunnerTests: XCTestCase {
    private let card = StatusCard(lock: "UNLOCKED", trunk: "CLOSED", frunk: "CLOSED", sleep: "AWAKE", at: Date(timeIntervalSince1970: 1))

    /// 호출 순서를 기록하는 가짜.
    private final class Fakes: @unchecked Sendable {
        let calls = Locked<[String]>([])
        var session: SessionOutcome = .ready
        var command: CommandOutcome = .success
        var requery = RequeryOutcome(text: "요약", card: nil)

        func run() async -> KeyControlReport {
            await KeyControlRunner.run(
                ensureSession: { self.calls.mutate { $0.append("session") }; return self.session },
                command: { self.calls.mutate { $0.append("command") }; return self.command },
                requery: { self.calls.mutate { $0.append("requery") }; return self.requery }
            )
        }
    }

    func testSuccessRunsSessionThenCommandThenRequery() async {
        let f = Fakes()
        f.requery = RequeryOutcome(text: "요약", card: card)

        let report = await f.run()

        XCTAssertEqual(f.calls.value, ["session", "command", "requery"])
        XCTAssertEqual(report.kind, .success)
        XCTAssertEqual(report.line, "성공")
        XCTAssertEqual(report.status, card)
        XCTAssertNil(report.enrollment)
    }

    func testSessionFailureDoesNotSendTheCommandOrRequery() async {
        let f = Fakes()
        f.session = .failed("handshake timeout")

        let report = await f.run()

        XCTAssertEqual(f.calls.value, ["session"])
        XCTAssertEqual(report.kind, .notSent)
        XCTAssertTrue(report.line.contains("handshake timeout"))
        XCTAssertNil(report.enrollment)
        XCTAssertNil(report.status)
    }

    func testKeyNotPairedMeansNotEnrolledAndNothingIsSent() async {
        let f = Fakes()
        f.session = .notEnrolled

        let report = await f.run()

        XCTAssertEqual(f.calls.value, ["session"])
        XCTAssertEqual(report.kind, .notSent)
        XCTAssertEqual(report.enrollment, .notEnrolled)
        XCTAssertTrue(report.line.contains("KeyNotPaired"))
    }

    func testLockedKeyTellsTheUserToUnlockThePhoneAndSendsNothing() async {
        let f = Fakes()
        f.session = .locked

        let report = await f.run()

        XCTAssertEqual(f.calls.value, ["session"])
        XCTAssertEqual(report.kind, .notSent)
        XCTAssertTrue(report.line.contains("잠금 해제"))
        XCTAssertNil(report.enrollment, "잠금은 등록 상태를 바꾸지 않는다")
    }

    func testFailureIsReportedWithItsMessageAndStillRequeries() async {
        let f = Fakes()
        f.command = .failure("InsufficientPrivileges")

        let report = await f.run()

        XCTAssertEqual(f.calls.value, ["session", "command", "requery"])
        XCTAssertEqual(report.kind, .failure)
        XCTAssertEqual(report.line, "실패: InsufficientPrivileges")
    }

    func testUncertainIsNeverResentAndIsFollowedByTheRequery() async {
        let f = Fakes()
        f.command = .uncertain("timed out after send")
        f.requery = RequeryOutcome(text: "잠금 LOCKED", card: card)

        let report = await f.run()

        XCTAssertEqual(f.calls.value, ["session", "command", "requery"], "명령은 한 번만, 그 뒤에 재조회")
        XCTAssertEqual(report.kind, .uncertain)
        XCTAssertTrue(report.line.hasPrefix("불확실: timed out after send"))
        XCTAssertTrue(report.line.contains("잠금 LOCKED"), "재조회 결과를 줄에 붙인다")
        XCTAssertEqual(report.status, card)
    }

    func testCommandIsCalledExactlyOnceForEveryOutcome() async {
        for outcome in [CommandOutcome.success, .failure("x"), .uncertain("y")] {
            let f = Fakes()
            f.command = outcome
            _ = await f.run()
            XCTAssertEqual(f.calls.value.filter { $0 == "command" }.count, 1, "\(outcome)")
            XCTAssertEqual(f.calls.value.last, "requery", "\(outcome)")
        }
    }

    func testAFailedRequeryKeepsNoCardSoTheOldStatusStays() async {
        let f = Fakes()
        f.requery = RequeryOutcome(text: "재조회 실패: not connected", card: nil)

        let report = await f.run()

        XCTAssertNil(report.status)
    }
}
