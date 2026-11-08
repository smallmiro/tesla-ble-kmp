import XCTest
@testable import TeslableSample

/// `Timeout(afterSend)` 를 사람에게 설명하는 문구(SFR-018, SFR-023). 정본: `documents/md/16-lib-errors.md`
/// (`Timeout(afterSend)` 행, §Retries and timeouts, "Sent means the write was started").
final class TimeoutNoteTests: XCTestCase {
    func testAfterSendSaysTheCarMayHaveRunItAndToReadTheStateAgain() {
        let note = TimeoutNote.command(afterSend: true)
        XCTAssertTrue(note.contains("보냈을 수 있음 — 차가 실행했을 수 있으니 상태를 다시 조회하세요"), note)
    }

    func testAfterSendMentionsSlowWritesInTheBackground() {
        XCTAssertTrue(TimeoutNote.command(afterSend: true).contains("백그라운드"))
        XCTAssertTrue(TimeoutNote.read(afterSend: true).contains("백그라운드"))
    }

    func testNotSentMeansNothingRanAndNeverClaimsTheCarMayHaveRunIt() {
        for note in [TimeoutNote.command(afterSend: false), TimeoutNote.read(afterSend: false)] {
            XCTAssertTrue(note.contains("실행되지 않았"), note)
            XCTAssertFalse(note.contains("보냈을 수 있음"), note)
            XCTAssertFalse(note.contains("백그라운드"), "the background note belongs to the possibly-sent case: \(note)")
        }
    }

    func testReadVariantSaysAQueryChangesNothingInsteadOfClaimingExecution() {
        let note = TimeoutNote.read(afterSend: true)
        XCTAssertTrue(note.contains("보냈을 수 있음"), note)
        XCTAssertFalse(note.contains("실행했을 수"), "a read is not executed: \(note)")
        XCTAssertTrue(note.contains("다시 조회"), note)
    }

    func testResultLineAddsTheNoteOnlyForATimeout() {
        XCTAssertEqual(TimeoutNote.line(message: "boom", timeoutAfterSend: nil), "boom")
        let sent = TimeoutNote.line(message: "timed out", timeoutAfterSend: true)
        XCTAssertTrue(sent.hasPrefix("timed out"), sent)
        XCTAssertTrue(sent.contains(TimeoutNote.command(afterSend: true)), sent)
        let notSent = TimeoutNote.line(message: "timed out", timeoutAfterSend: false)
        XCTAssertTrue(notSent.contains(TimeoutNote.command(afterSend: false)), notSent)
    }

    func testStateFaultTextUsesTheReadVariant() {
        XCTAssertTrue(StateFault.noReply(afterSend: true).text.contains(TimeoutNote.read(afterSend: true)))
        XCTAssertTrue(StateFault.noReply(afterSend: false).text.contains(TimeoutNote.read(afterSend: false)))
        XCTAssertNotEqual(StateFault.noReply(afterSend: true).text, StateFault.noReply(afterSend: false).text)
    }
}
