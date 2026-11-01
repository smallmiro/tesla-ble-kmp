import XCTest
@testable import TeslableSample

/// SFR-021: 키 등록 진행 팝업의 단계·상태 규칙(`EnrollmentPopup`)과 등록 확인 반복의 진행 알림·취소.
/// 규칙의 정본은 `samples/documents/md/05-key-control-tab.md` §Enrollment progress popup 이다.
final class EnrollmentPopupTests: XCTestCase {
    private func popup(_ updates: EnrollmentUpdate...) -> EnrollmentPopup {
        var p = EnrollmentPopup()
        for u in updates { p.apply(u) }
        return p
    }

    private func marks(_ p: EnrollmentPopup) -> [EnrollmentPopup.Mark] { p.rows.map(\.mark) }

    // MARK: 처음

    func testStartsAsRequestingAndCanBeCancelledButNotClosed() {
        let p = EnrollmentPopup()
        XCTAssertEqual(p.phase, .requesting)
        XCTAssertTrue(p.isActive)
        XCTAssertTrue(p.canCancel)
        XCTAssertFalse(p.canClose)
        XCTAssertEqual(marks(p), [.current, .pending, .pending])
    }

    func testRowsNameTheThreeSteps() {
        let titles = EnrollmentPopup().rows.map(\.title)
        XCTAssertEqual(titles.count, 3)
        XCTAssertTrue(titles[0].contains("요청"))
        XCTAssertTrue(titles[1].contains("키카드") && titles[1].contains("승인"))
        XCTAssertTrue(titles[2].contains("3초"))
    }

    // MARK: 요청 → 카드 태그

    func testSentMovesToWaitingForCardWithTheInstruction() {
        let p = popup(.sent)
        XCTAssertEqual(p.phase, .waitingForCard)
        XCTAssertTrue(p.message.contains("키카드"))
        XCTAssertTrue(p.message.contains("중앙 콘솔"))
        XCTAssertTrue(p.message.contains("승인"))
        XCTAssertEqual(marks(p), [.done, .current, .pending])
        XCTAssertTrue(p.canCancel)
    }

    func testWaitingForTapKeepsTheSamePhase() {
        XCTAssertEqual(popup(.sent, .waitingForTap).phase, .waitingForCard)
    }

    func testUnknownReplyChangesNothing() {
        let before = popup(.sent)
        var after = before
        after.apply(.unknown("0a0b"))
        XCTAssertEqual(after, before)
    }

    // MARK: 확인 중(3초마다, 최대 2분)

    func testAcceptedMovesToConfirming() {
        let p = popup(.sent, .accepted)
        XCTAssertEqual(p.phase, .confirming)
        XCTAssertEqual(marks(p), [.done, .done, .current])
        XCTAssertTrue(p.canCancel)
    }

    func testTimedOutRequestStillMovesToConfirmingAndSaysSo() {
        let p = popup(.sent, .timedOut)
        XCTAssertEqual(p.phase, .confirming)
        XCTAssertTrue(p.message.contains("시간 초과"))
    }

    func testConfirmingShowsAttemptElapsedAndRemaining() {
        let p = popup(.sent, .accepted, .confirming(attempt: 3, elapsed: 6))
        XCTAssertEqual(p.attempt, 3)
        XCTAssertEqual(p.elapsed, 6)
        XCTAssertEqual(p.remaining, 114)
        XCTAssertEqual(p.progressText, "확인 3번째 · 경과 0:06 / 2:00 · 남은 시간 1:54")
        XCTAssertEqual(p.progressFraction, 0.05, accuracy: 0.0001)
    }

    func testRemainingNeverGoesBelowZero() {
        let p = popup(.accepted, .confirming(attempt: 41, elapsed: 125))
        XCTAssertEqual(p.remaining, 0)
        XCTAssertEqual(p.progressFraction, 1, accuracy: 0.0001)
        XCTAssertEqual(p.progressText, "확인 41번째 · 경과 2:05 / 2:00 · 남은 시간 0:00")
    }

    func testConfirmingWithoutAnAcceptedReplyStillCountsAsConfirming() {
        // 요청이 시간 초과로 끝난 뒤 확인으로 넘어가는 길.
        let p = popup(.timedOut, .confirming(attempt: 1, elapsed: 0))
        XCTAssertEqual(p.phase, .confirming)
        XCTAssertEqual(marks(p), [.done, .done, .current])
    }

    func testClockText() {
        XCTAssertEqual(EnrollmentPopup.clockText(0), "0:00")
        XCTAssertEqual(EnrollmentPopup.clockText(59.9), "0:59")
        XCTAssertEqual(EnrollmentPopup.clockText(120), "2:00")
        XCTAssertEqual(EnrollmentPopup.clockText(-3), "0:00")
    }

    // MARK: 끝

    func testConfirmedIsSuccessAndClosable() {
        let p = popup(.sent, .accepted, .confirming(attempt: 2, elapsed: 3), .confirmed("등록 확인됨(Infotainment)"))
        XCTAssertEqual(p.phase, .succeeded)
        XCTAssertTrue(p.canClose)
        XCTAssertFalse(p.canCancel)
        XCTAssertFalse(p.isActive)
        XCTAssertEqual(marks(p), [.done, .done, .done])
        XCTAssertTrue(p.message.contains("등록"))
    }

    func testNotConfirmedIsReportedWithAdviceAndNoRetryOfTheRequest() {
        let p = popup(.sent, .accepted, .confirming(attempt: 41, elapsed: 120), .notConfirmed("x"))
        XCTAssertEqual(p.phase, .notConfirmed)
        XCTAssertTrue(p.canClose)
        XCTAssertTrue(p.message.contains("2분"))
        XCTAssertTrue(p.message.contains("등록 상태 확인"))
        XCTAssertEqual(marks(p), [.done, .done, .failed])
    }

    func testConfirmFailedShowsTheMessage() {
        let p = popup(.sent, .accepted, .confirmFailed("NotConnected"))
        XCTAssertEqual(p.phase, .failed("NotConnected"))
        XCTAssertTrue(p.message.contains("NotConnected"))
        XCTAssertEqual(marks(p), [.done, .done, .failed])
        XCTAssertTrue(p.canClose)
    }

    func testRequestFailureAndRejectionShowTheMessageAtTheRightStep() {
        let failed = popup(.failed("BLE write failed"))
        XCTAssertEqual(failed.phase, .failed("BLE write failed"))
        XCTAssertTrue(failed.message.contains("BLE write failed"))
        XCTAssertEqual(marks(failed), [.failed, .pending, .pending])

        let rejected = popup(.sent, .rejected("no permission"))
        XCTAssertEqual(rejected.phase, .failed("no permission"))
        XCTAssertTrue(rejected.message.contains("no permission"))
        XCTAssertEqual(marks(rejected), [.done, .failed, .pending])
    }

    func testBlockedBeforeSendingIsAFailureOfTheFirstStep() {
        let p = popup(.blocked("키 등록: 먼저 연결하세요"))
        XCTAssertEqual(p.phase, .failed("키 등록: 먼저 연결하세요"))
        XCTAssertTrue(p.canClose)
    }

    func testTerminalStateIsSticky() {
        var p = popup(.sent, .accepted, .confirmed("ok"))
        p.apply(.confirming(attempt: 9, elapsed: 27))
        p.apply(.failed("late"))
        XCTAssertEqual(p.phase, .succeeded)
        XCTAssertEqual(p.attempt, 0)
    }

    // MARK: 취소

    func testRequestCancelDisablesCancelAndSaysItIsStopping() {
        var p = popup(.sent, .accepted)
        p.requestCancel()
        XCTAssertFalse(p.canCancel)
        XCTAssertTrue(p.isActive)
        XCTAssertFalse(p.canClose)
        XCTAssertTrue(p.message.contains("멈추는 중"))
    }

    func testCancelledStatesWhatCannotBeUndone() {
        let p = popup(.sent, .cancelled)
        XCTAssertEqual(p.phase, .cancelled)
        XCTAssertTrue(p.canClose)
        XCTAssertFalse(p.canCancel)
        XCTAssertTrue(p.message.contains("되돌릴 수 없"), "the request already sent cannot be recalled")
        XCTAssertTrue(p.message.contains("등록 상태 확인"))
        XCTAssertEqual(marks(p), [.done, .failed, .pending])
    }

    func testCancelAfterAFinalResultChangesNothing() {
        var p = popup(.sent, .accepted, .confirmed("ok"))
        let before = p
        p.requestCancel()
        XCTAssertEqual(p, before)
    }

    // MARK: 취소 결과 고르기

    func testFinishedPicksTheRightUpdate() {
        XCTAssertEqual(EnrollmentUpdate.finished(enrolled: true, line: "ok", failure: nil, cancelled: true), .confirmed("ok"))
        XCTAssertEqual(EnrollmentUpdate.finished(enrolled: false, line: "x", failure: nil, cancelled: true), .cancelled)
        XCTAssertEqual(EnrollmentUpdate.finished(enrolled: false, line: "x", failure: "NotConnected", cancelled: false), .confirmFailed("NotConnected"))
        XCTAssertEqual(EnrollmentUpdate.finished(enrolled: false, line: "x", failure: nil, cancelled: false), .notConfirmed("x"))
        // 취소가 실패보다 먼저다(취소하면서 호출이 오류로 끝났을 수 있다).
        XCTAssertEqual(EnrollmentUpdate.finished(enrolled: false, line: "x", failure: "e", cancelled: true), .cancelled)
    }

    func testControlTabLinesStayTheSameAndSkipProgressTicks() {
        XCTAssertEqual(EnrollmentUpdate.sent.line, "요청 보냄 — 중앙 콘솔에 키카드를 대고 화면에서 승인하세요")
        XCTAssertEqual(EnrollmentUpdate.waitingForTap.line, "카드 태그 대기 중")
        XCTAssertEqual(EnrollmentUpdate.timedOut.line, "시간 초과 — 등록 여부를 확인합니다")
        XCTAssertEqual(EnrollmentUpdate.confirmFailed("등록 확인 실패: x").line, "등록 확인 실패: x")
        XCTAssertNil(EnrollmentUpdate.confirming(attempt: 1, elapsed: 0).line)
        XCTAssertNotNil(EnrollmentUpdate.cancelled.line)
    }
}

/// 등록 확인 반복의 진행 알림과 취소.
final class EnrollmentWaiterProgressTests: XCTestCase {
    func testReportsEveryAttemptWithElapsedTime() async {
        var now: TimeInterval = 0
        var reports: [(Int, TimeInterval)] = []
        var probes: [EnrollmentProbe] = [.notYet, .uncertain("x"), .enrolled]
        let result = await EnrollmentWaiter.wait(
            timeout: 120, interval: 3,
            probe: { probes.removeFirst() },
            sleep: { now += $0 },
            now: { now },
            onAttempt: { reports.append(($0, $1)) }
        )
        XCTAssertTrue(result.enrolled)
        XCTAssertEqual(reports.map(\.0), [1, 2, 3])
        XCTAssertEqual(reports.map(\.1), [0, 3, 6])
    }

    func testCancellingStopsTheWaitingForReal() async {
        let started = Date()
        let task = Task {
            await EnrollmentWaiter.wait(
                timeout: 120, interval: 3,
                probe: { .notYet },
                sleep: { try await Task.sleep(nanoseconds: UInt64($0 * 1_000_000_000)) },
                now: { Date().timeIntervalSince1970 }
            )
        }
        try? await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        let result = await task.value
        XCTAssertFalse(result.enrolled)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2, "cancelling must end the 2-minute wait at once")
    }
}
