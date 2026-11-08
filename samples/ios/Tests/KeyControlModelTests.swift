import XCTest
@testable import TeslableSample

/// 키·제어 탭 상태(`KeyControlModel`)를 가짜 `KeyControlBackend` 로 시험한다(차량·BLE 없이).
/// 연결·키 변화에 따른 팝업 정리, 취소, 세대(generation) 버림, 키 교체 결과 반영을 본다.
/// 실제 BLE 로만 확인되는 것(Kotlin 흐름이 취소를 받아 끝나는지 등)은 `samples/ios/README.md` 의 P4·R 항목에 있다.
final class FakeKeyControlBackend: KeyControlBackend, @unchecked Sendable {
    typealias Emit = @Sendable (EnrollmentUpdate) async -> ()
    var pairHandler: (@Sendable (@escaping Emit) async -> Bool)?
    var replaceOutcome = KeyReplaceOutcome.replaced
    private let lock = NSLock()
    private var pairs = 0
    private var replaces = 0

    var pairCalls: Int { lock.withLock { pairs } }
    var replaceCalls: Int { lock.withLock { replaces } }

    func ensureKey() async -> KeyEnsureResult { .ready }
    func disconnect() async {}
    func connectNow() -> Bool { true }
    func replaceKey() async -> KeyReplaceOutcome {
        lock.withLock { replaces += 1 }
        return replaceOutcome
    }
    func checkEnrollment() async -> EnrollmentCheckOutcome { .notEnrolled }
    func pair(onEvent: @escaping Emit) async -> Bool {
        lock.withLock { pairs += 1 }
        guard let handler = pairHandler else { return false }
        return await handler(onEvent)
    }
    func ensureVcsecSession() async -> SessionOutcome { .ready }
    func send(_ button: ControlButton) async -> CommandOutcome { .success }
    func statusCard() async -> RequeryOutcome { RequeryOutcome(text: "ok", card: nil) }
    /// 시험이 끼우는 Infotainment 호출 묶음(SFR-025). 없으면 연결이 없는 것처럼 `nil`.
    var infotainment: InfotainmentLink?
    func openInfotainment() -> InfotainmentLink? { infotainment }
}

/// 한 번 열리는 문(시험이 흐름을 멈췄다가 풀어 준다).
final class TestGate: @unchecked Sendable {
    private let lock = NSLock()
    private var open = false

    func release() { lock.withLock { open = true } }
    var isOpen: Bool { lock.withLock { open } }
    /// 열릴 때까지(최대 `timeout` 초) 기다린다. 열리지 않으면 시험이 매달리지 않고 돌아와 실패로 끝난다.
    func wait(timeout: TimeInterval = 5) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !lock.withLock({ open }), Date() < deadline { try? await Task.sleep(nanoseconds: 5_000_000) }
    }
}

@MainActor
final class KeyControlModelTests: XCTestCase {
    private var backend: FakeKeyControlBackend!
    private var model: KeyControlModel!

    override func setUp() async throws {
        try await super.setUp()
        backend = FakeKeyControlBackend()
        model = KeyControlModel(backend: backend)
    }

    /// 키가 준비되고 키를 가진 연결이 있는 상태로 만든다(등록 확인이 자동으로 한 번 돈다: 가짜는 `notEnrolled`).
    private func connectReady() async {
        model.vinChanged(saved: true)
        model.keyChanged(.ready)
        model.connectionChanged(connected: true, hasKey: true)
        await waitUntil { self.model.inputs.enrollment == .notEnrolled && !self.model.inputs.busy }
    }

    private func waitUntil(_ condition: @MainActor () -> Bool, timeout: TimeInterval = 3, line: UInt = #line) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertTrue(condition(), "timed out waiting for the condition", line: line)
    }

    /// 취소될 때까지 기다린다(최대 5초: 취소가 안 오면 매달리지 않고 돌아온다).
    private static func untilCancelled() async {
        let deadline = Date().addingTimeInterval(5)
        while !Task.isCancelled, Date() < deadline { try? await Task.sleep(nanoseconds: 5_000_000) }
    }

    private func resultTexts() -> [String] { model.results.map { "\($0.title): \($0.text)" } }

    // MARK: 등록 팝업

    func testStartEnrollIsRefusedWhenStep3IsNotAvailable() {
        model.startEnroll() // 키도 연결도 없다
        XCTAssertNil(model.enrollmentPopup)
        XCTAssertEqual(backend.pairCalls, 0)
    }

    func testEnrollSuccessWalksThePopupToDoneAndMarksEnrolled() async {
        await connectReady()
        backend.pairHandler = { emit in
            await emit(.sent)
            await emit(.accepted)
            await emit(.confirming(attempt: 1, elapsed: 0))
            await emit(.confirmed("등록 확인됨(Infotainment)"))
            return true
        }
        model.startEnroll()
        XCTAssertEqual(model.enrollmentPopup?.phase, .requesting, "the sheet opens at once")
        await waitUntil { self.model.enrollmentPopup?.phase == .succeeded && !self.model.inputs.busy }
        XCTAssertEqual(model.inputs.enrollment, .enrolled)
        XCTAssertTrue(resultTexts().contains("키 등록: 등록 확인됨"))
        XCTAssertFalse(model.progress.isEmpty)
        XCTAssertEqual(model.enrollmentPopup?.canClose, true)
        model.closeEnrollmentPopup()
        XCTAssertNil(model.enrollmentPopup)
    }

    func testPopupCannotBeClosedWhileTheFlowRuns() async {
        await connectReady()
        let gate = TestGate()
        backend.pairHandler = { emit in
            await emit(.sent)
            await gate.wait()
            return false
        }
        model.startEnroll()
        await waitUntil { self.model.enrollmentPopup?.phase == .waitingForCard }
        model.closeEnrollmentPopup()
        XCTAssertNotNil(model.enrollmentPopup, "closing is only for a finished flow")
        gate.release()
        await waitUntil { !self.model.inputs.busy }
    }

    func testSecondStartWhileRunningDoesNotStartAnotherFlow() async {
        await connectReady()
        let gate = TestGate()
        backend.pairHandler = { emit in
            await emit(.sent)
            await gate.wait()
            return false
        }
        model.startEnroll()
        await waitUntil { self.model.enrollmentPopup?.phase == .waitingForCard }
        model.startEnroll()
        XCTAssertEqual(backend.pairCalls, 1)
        gate.release()
        await waitUntil { !self.model.inputs.busy }
    }

    func testCancelStopsTheWaitingAndShowsWhatItCannotUndo() async {
        await connectReady()
        backend.pairHandler = { emit in
            await emit(.sent)
            await Self.untilCancelled() // 취소가 와야 끝난다
            await emit(.cancelled)
            return false
        }
        model.startEnroll()
        await waitUntil { self.model.enrollmentPopup?.phase == .waitingForCard }
        model.cancelEnroll()
        XCTAssertEqual(model.enrollmentPopup?.canCancel, false, "cancel is off right after pressing it")
        await waitUntil { self.model.enrollmentPopup?.phase == .cancelled && !self.model.inputs.busy }
        XCTAssertTrue(model.enrollmentPopup?.message.contains("되돌릴 수 없") == true)
        XCTAssertNotEqual(model.inputs.enrollment, .enrolled, "cancelling does not change the enrollment state")
        XCTAssertTrue(resultTexts().contains { $0.hasPrefix("키 등록: 기다리기를 멈췄습니다") })
    }

    func testLinkLossDismissesThePopupStopsTheFlowAndDropsLateUpdates() async {
        await connectReady()
        let cancelSeen = TestGate()
        let late = TestGate()
        backend.pairHandler = { emit in
            await emit(.sent)
            await Self.untilCancelled()
            if Task.isCancelled { cancelSeen.release() }
            await late.wait()
            await emit(.confirmed("늦게 온 확인")) // 이미 다음 상태로 넘어간 뒤의 알림
            return true
        }
        model.startEnroll()
        await waitUntil { self.model.enrollmentPopup?.phase == .waitingForCard }
        model.connectionChanged(connected: false, hasKey: false)
        XCTAssertNil(model.enrollmentPopup, "the popup goes away at once")
        XCTAssertTrue(resultTexts().contains { $0.hasPrefix("키 등록: 연결이 바뀌어 등록 기다리기를 중단했습니다") })
        await cancelSeen.wait() // 흐름이 실제로 취소를 받았다(받지 못하면 5초 뒤 아래 단언이 실패한다)
        XCTAssertTrue(cancelSeen.isOpen, "the flow itself must receive the cancellation")
        late.release()
        await waitUntil { !self.model.inputs.busy }
        XCTAssertNil(model.enrollmentPopup)
        XCTAssertNotEqual(model.inputs.enrollment, .enrolled, "an update from the old connection must not mark the new state")
        XCTAssertFalse(resultTexts().contains { $0.hasPrefix("키 등록: 등록 확인됨") })
    }

    func testKeyChangeDismissesThePopupToo() async {
        await connectReady()
        let gate = TestGate()
        backend.pairHandler = { emit in
            await emit(.sent)
            await gate.wait()
            return false
        }
        model.startEnroll()
        await waitUntil { self.model.enrollmentPopup?.phase == .waitingForCard }
        model.keyChanged(.none) // 키가 읽을 수 없게 바뀌었다
        XCTAssertNil(model.enrollmentPopup)
        XCTAssertTrue(resultTexts().contains { $0.contains("키가 바뀌어") })
        gate.release()
        await waitUntil { !self.model.inputs.busy }
    }

    func testFinishedPopupAlsoGoesAwayWhenTheConnectionChanges() async {
        await connectReady()
        backend.pairHandler = { emit in
            await emit(.sent)
            await emit(.failed("boom"))
            return false
        }
        model.startEnroll()
        await waitUntil { self.model.enrollmentPopup?.phase == .failed("boom") && !self.model.inputs.busy }
        model.connectionChanged(connected: false, hasKey: false)
        XCTAssertNil(model.enrollmentPopup)
        // 끝난 팝업이라 중단 줄은 남기지 않는다.
        XCTAssertFalse(resultTexts().contains { $0.contains("중단했습니다") })
    }

    func testEnrollCanStartAgainAfterAFlowEnds() async {
        await connectReady()
        backend.pairHandler = { emit in
            await emit(.failed("boom"))
            return false
        }
        model.startEnroll()
        await waitUntil { self.model.enrollmentPopup?.phase == .failed("boom") && !self.model.inputs.busy }
        model.closeEnrollmentPopup()
        await waitUntil { self.model.steps.enroll.buttonEnabled }
        model.startEnroll()
        await waitUntil { self.backend.pairCalls == 2 }
        await waitUntil { !self.model.inputs.busy }
    }

    // MARK: 키 교체 결과 반영

    func testReplacedResetsEnrollmentStatusProgressAndTellsTheControlTab() async {
        await connectReady()
        var notified = 0
        model.onKeyReplaced = { notified += 1 }
        backend.replaceOutcome = .replaced
        await model.replaceKey()
        XCTAssertEqual(backend.replaceCalls, 1)
        XCTAssertEqual(model.inputs.enrollment, .notEnrolled)
        XCTAssertNil(model.status)
        XCTAssertTrue(model.progress.isEmpty)
        XCTAssertEqual(notified, 1)
        XCTAssertTrue(resultTexts().contains { $0.hasPrefix("키 삭제 후 다시 만들기: 새 키를 만들었습니다") })
    }

    func testLockedOrBusyOutcomeChangesNothingOnTheScreen() async {
        await connectReady()
        var notified = 0
        model.onKeyReplaced = { notified += 1 }
        model.enrollmentChangedElsewhere(.enrolled)
        for outcome in [KeyReplaceOutcome.locked, .busy, .notDeleted("x")] {
            backend.replaceOutcome = outcome
            await model.replaceKey()
        }
        XCTAssertEqual(model.inputs.enrollment, .enrolled)
        XCTAssertEqual(notified, 0)
        XCTAssertEqual(backend.replaceCalls, 3)
    }

    func testReplaceIsNotSentWhenTheButtonIsOffAndSaysWhy() async {
        // 키를 아직 읽지 않았고 손상도 아니다(그 밖의 오류와 같다).
        model.keyChanged(.error)
        await model.replaceKey()
        XCTAssertEqual(backend.replaceCalls, 0)
        XCTAssertTrue(resultTexts().contains { $0.contains("보내지 않음") })
    }

    func testCorruptedKeyEnablesReplaceAndUsesTheCorruptedConfirmation() async {
        model.keyChanged(.corrupted)
        XCTAssertTrue(model.steps.replaceKeyEnabled)
        XCTAssertTrue(model.replaceConfirmation.message.contains("복구할 수 없"))
        await model.replaceKey()
        XCTAssertEqual(backend.replaceCalls, 1)
    }

    func testOnlyTheCorruptedStateOpensTheDamagedKeyPath() {
        for state in [KeyState.none, .error] {
            model.keyChanged(state)
            XCTAssertFalse(model.steps.replaceKeyEnabled, "\(state) must stay disabled")
        }
        model.keyChanged(.locked)
        XCTAssertFalse(model.steps.replaceKeyEnabled)
        model.keyChanged(.ready)
        XCTAssertTrue(model.steps.replaceKeyEnabled)
        XCTAssertEqual(model.replaceConfirmation, KeyReplacement.confirmation)
    }

    // MARK: 연결 변화와 상태 카드

    func testConnectionChangeDropsTheStatusCardAndEnrollmentState() async {
        await connectReady()
        model.connectionChanged(connected: false, hasKey: false)
        XCTAssertNil(model.status)
        XCTAssertEqual(model.inputs.enrollment, .unknown)
    }
}
