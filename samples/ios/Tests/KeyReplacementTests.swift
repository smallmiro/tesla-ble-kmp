import XCTest
@testable import TeslableSample

/// SFR-020: 모니터 키 삭제 후 다시 만들기의 순서와 결과(`KeyReplacer`), 확인 문구, 버튼 활성 규칙.
/// 규칙의 정본은 `samples/documents/md/05-key-control-tab.md` §Delete and recreate the key 이다.
final class KeyReplacerTests: XCTestCase {
    /// 호출 순서를 기록하고, 단계별 결과를 정해 주는 장치.
    private final class Rig {
        var calls: [String] = []
        var probe = KeyProbe.present
        var delete = KeyDeleteResult.deleted
        var create = KeyCreateResult.created
        var restore = KeyProbe.present
        /// 연결을 끊은 뒤 연결이 정말 없는가.
        var disconnected = true
        /// 다시 연결할지(사용자 의도 포함). `nil` 이면 쓰던 연결이 있었을 때만.
        var wantsReconnect: Bool?

        func run(wasActive: Bool) async -> KeyReplaceOutcome {
            await KeyReplacer.run(
                wasActive: wasActive,
                probe: { self.calls.append("probe"); return self.probe },
                disconnect: { self.calls.append("disconnect"); return self.disconnected },
                forgetHandle: { self.calls.append("forget") },
                delete: { self.calls.append("delete"); return self.delete },
                create: { self.calls.append("create"); return self.create },
                restore: { self.calls.append("restore"); return self.restore },
                wantsReconnect: { self.wantsReconnect ?? wasActive },
                reconnect: { self.calls.append("reconnect") }
            )
        }
    }

    // MARK: 성공

    func testReplacesInOrderAndReconnectsWhenTheLinkWasActive() async {
        let r = Rig()
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .replaced)
        // 연결을 먼저 끊고(세션 캐시가 옛 키 것이다), 핸들을 버린 뒤 지우고 만든다. 새 키로 다시 연결한다.
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "create", "reconnect"])
    }

    func testDoesNotTouchTheLinkWhenItWasNotActive() async {
        let r = Rig()
        let outcome = await r.run(wasActive: false)
        XCTAssertEqual(outcome, .replaced)
        XCTAssertEqual(r.calls, ["probe", "forget", "delete", "create"])
    }

    func testAbsentKeyIsJustCreated() async {
        let r = Rig()
        r.probe = .absent
        let outcome = await r.run(wasActive: false)
        XCTAssertEqual(outcome, .replaced)
        XCTAssertEqual(r.calls, ["probe", "forget", "delete", "create"])
    }

    // MARK: 연결이 정말 끊겼는지 확인한다

    func testStopsBeforeDeletingWhenAConnectionRemains() async {
        let r = Rig()
        r.disconnected = false // 끊는 사이에 다른 탭이 연결을 시작했다
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .notDeleted("연결을 끊지 못했습니다"))
        XCTAssertEqual(r.calls, ["probe", "disconnect"]) // 지우지도, 핸들을 버리지도, 연결을 다시 만들지도 않는다
    }

    func testWasActiveFollowsTheLinkState() {
        XCTAssertFalse(KeyReplacer.isActive(LinkState<Int>.idle))
        XCTAssertFalse(KeyReplacer.isActive(LinkState<Int>.stopped))
        XCTAssertTrue(KeyReplacer.isActive(LinkState<Int>.connecting(1)))
        XCTAssertTrue(KeyReplacer.isActive(LinkState<Int>.connected(7)))
        XCTAssertTrue(KeyReplacer.isActive(LinkState<Int>.backoff(attempt: 2, delay: 4, reason: nil)))
    }

    func testBusyOutcomeChangesNothing() {
        XCTAssertEqual(KeyReplaceOutcome.busy.tone, .warn)
        XCTAssertNil(KeyReplaceOutcome.busy.enrollmentAfter)
        XCTAssertFalse(KeyReplaceOutcome.busy.dropsConnectionState)
        XCTAssertTrue(KeyReplaceOutcome.busy.line.contains("다시 누르세요"))
    }

    // MARK: 손상된 키 — 읽을 수 없어도 지우고 다시 만들 수 있다

    func testCorruptedKeyIsReplacedWithoutNeedingAReadableKey() async {
        let r = Rig()
        r.probe = .corrupted
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .replaced)
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "create", "reconnect"])
    }

    func testRestoreThatReadsACorruptedKeyIsUnverified() async {
        let r = Rig()
        r.delete = .failed("boom")
        r.restore = .corrupted
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .unverified("boom"))
        XCTAssertFalse(r.calls.contains("reconnect"))
    }

    func testOtherReadFailuresStillStopBeforeAnythingChanges() async {
        let r = Rig()
        r.probe = .failed("some other keychain error")
        let outcome = await r.run(wasActive: false)
        XCTAssertEqual(outcome, .notDeleted("some other keychain error"))
        XCTAssertEqual(r.calls, ["probe"])
    }

    /// 오탐은 건강한 등록 키를 확인 뒤 영구히 지우게 한다. 문서에 적힌 `cause` 와 정확히 같을 때만 손상이다.
    func testCorruptedIsRecognisedOnlyFromTheExactDocumentedCause() {
        XCTAssertTrue(KeyStoreFailure.isCorrupted(cause: "stored key is corrupted"))
    }

    func testALongerTextThatMerelyContainsTheDocumentedTextIsNotCorrupted() {
        let looksLike = [
            "key store failure: stored key is corrupted", // 라이브러리가 message 에 붙이는 접두어가 붙은 꼴(cause 가 아니다)
            "stored key is corrupted: errSecInteractionNotAllowed",
            "backup of the stored key is corrupted and the stored key is corrupted",
            "the stored key is corrupted",
            "stored key is corrupted ",
            " stored key is corrupted",
            "Stored key is corrupted",
            "stored key is corrupted\n",
            "stored key is corrupted-ish",
            "",
            "errSecItemNotFound",
        ]
        for text in looksLike {
            XCTAssertFalse(KeyStoreFailure.isCorrupted(cause: text), "must not match: \(text)")
        }
    }

    // MARK: 잠금 — 아무것도 바꾸지 않는다

    func testLockedKeyStoreStopsBeforeAnythingChanges() async {
        let r = Rig()
        r.probe = .locked
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .locked)
        XCTAssertEqual(r.calls, ["probe"]) // 연결도 끊지 않고, 지우지도 않는다
    }

    func testLockedDuringDeleteKeepsTheOldKeyAndRestores() async {
        let r = Rig()
        r.delete = .locked
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .locked)
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "restore", "reconnect"])
        XCTAssertFalse(r.calls.contains("create"))
    }

    // MARK: 실패

    func testProbeFailureTouchesNothing() async {
        let r = Rig()
        r.probe = .failed("keychain error")
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .notDeleted("keychain error"))
        XCTAssertEqual(r.calls, ["probe"])
    }

    func testFailedDeleteNeverCreatesASecondKeyAndRestoresTheOldOne() async {
        let r = Rig()
        r.delete = .failed("boom")
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .notDeleted("boom"))
        XCTAssertFalse(r.calls.contains("create"))
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "restore", "reconnect"])
    }

    func testFailedDeleteWithoutActiveLinkDoesNotReconnect() async {
        let r = Rig()
        r.delete = .failed("boom")
        _ = await r.run(wasActive: false)
        XCTAssertEqual(r.calls, ["probe", "forget", "delete", "restore"])
    }

    func testFailedCreateLeavesNoKeyAndDoesNotReconnect() async {
        let r = Rig()
        r.create = .failed("no secure enclave")
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .notCreated("no secure enclave"))
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "create"])
    }

    func testLockedCreateIsReportedAsNotCreatedWithoutRetry() async {
        let r = Rig()
        r.create = .locked
        let outcome = await r.run(wasActive: false)
        guard case .notCreated(let why) = outcome else { return XCTFail("expected notCreated, got \(outcome)") }
        XCTAssertTrue(why.contains("잠금"))
        XCTAssertEqual(r.calls.filter { $0 == "create" }.count, 1)
    }

    // MARK: 지우기가 실패한 뒤 복원 결과를 따른다

    func testFailedDeleteThatLeavesNoKeyIsReportedAsLostAndDoesNotReconnect() async {
        let r = Rig()
        r.delete = .failed("boom")
        r.restore = .absent // 실패했다고 했는데 키가 없다: 다시 연결하면 getOrCreate 가 조용히 새 키를 만든다
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .lost("boom"))
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "restore"])
        XCTAssertFalse(outcome.line.contains("옛 키는 그대로"))
        XCTAssertTrue(outcome.line.contains("1단계"))
    }

    func testFailedDeleteWhoseStateCannotBeReadIsUnverifiedAndDoesNotReconnect() async {
        let r = Rig()
        r.delete = .failed("boom")
        r.restore = .failed("keychain error")
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .unverified("boom"))
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "restore"])
        XCTAssertFalse(outcome.line.contains("옛 키는 그대로"))
    }

    /// 삭제가 실패했고 키 저장소가 잠겨 읽을 수 없다: 키가 지워졌는지 모른다. 다시 연결하면 잠금 해제 뒤 `getOrCreate` 가 키를 조용히 만들 수 있다.
    func testFailedDeleteWhoseKeyCannotBeReadBecauseOfTheLockDoesNotReconnect() async {
        let r = Rig()
        r.delete = .failed("boom")
        r.restore = .locked
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .unknownLocked("boom"))
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "restore"])
        XCTAssertFalse(outcome.line.contains("아무것도 바꾸지 않았습니다"), "the connection was cycled and the key state is unknown")
        XCTAssertTrue(outcome.line.contains("알 수 없"))
        XCTAssertTrue(outcome.line.contains("잠금 해제"))
        XCTAssertEqual(outcome.tone, .fail)
        XCTAssertTrue(outcome.dropsConnectionState)
        XCTAssertNil(outcome.enrollmentAfter)
    }

    func testLockedDeleteWhoseKeyCannotBeReadEitherDoesNotReconnect() async {
        let r = Rig()
        r.delete = .locked
        r.restore = .locked
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .unknownLocked("폰이 잠겨 있습니다"))
        XCTAssertFalse(r.calls.contains("reconnect"))
    }

    func testLockedDeleteWhoseKeyIsStillThereReportsLockedAndReconnects() async {
        let r = Rig()
        r.delete = .locked
        r.restore = .present
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .locked)
        XCTAssertEqual(r.calls.last, "reconnect")
    }

    // MARK: 교체 중 사용자 의도(마지막 의도가 이긴다)

    func testAConnectPressedDuringTheReplacementReconnectsAfterSuccessEvenIfNothingWasRunning() async {
        let r = Rig()
        r.wantsReconnect = true
        let outcome = await r.run(wasActive: false)
        XCTAssertEqual(outcome, .replaced)
        XCTAssertEqual(r.calls, ["probe", "forget", "delete", "create", "reconnect"])
    }

    func testADisconnectPressedDuringTheReplacementWinsOverTheReconnect() async {
        let r = Rig()
        r.wantsReconnect = false
        let outcome = await r.run(wasActive: true) // 연결이 돌고 있었고, 교체 중에 사용자가 끊었다
        XCTAssertEqual(outcome, .replaced)
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "create"])
    }

    func testTheUserStopAlsoWinsAfterAFailedDeleteThatLeftTheOldKey() async {
        let r = Rig()
        r.delete = .failed("boom")
        r.wantsReconnect = false
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .notDeleted("boom"))
        XCTAssertFalse(r.calls.contains("reconnect"))
    }

    func testLockedDeleteThatLeavesNoKeyIsLostToo() async {
        let r = Rig()
        r.delete = .locked
        r.restore = .absent
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .lost("폰이 잠겨 있습니다"))
        XCTAssertFalse(r.calls.contains("reconnect"))
    }

    func testLostAndUnverifiedDropTheConnectionState() {
        XCTAssertTrue(KeyReplaceOutcome.lost("x").dropsConnectionState)
        XCTAssertTrue(KeyReplaceOutcome.unverified("x").dropsConnectionState)
        XCTAssertEqual(KeyReplaceOutcome.lost("x").tone, .fail)
        XCTAssertEqual(KeyReplaceOutcome.unverified("x").tone, .fail)
        XCTAssertNil(KeyReplaceOutcome.lost("x").enrollmentAfter)
    }

    // MARK: 결과가 화면에 미치는 것

    func testOutcomeLinesAndTones() {
        XCTAssertEqual(KeyReplaceOutcome.replaced.tone, .ok)
        XCTAssertTrue(KeyReplaceOutcome.replaced.line.contains("3단계"))
        XCTAssertEqual(KeyReplaceOutcome.locked.tone, .warn)
        XCTAssertTrue(KeyReplaceOutcome.locked.line.contains("잠금 해제"))
        XCTAssertEqual(KeyReplaceOutcome.notDeleted("x").tone, .fail)
        XCTAssertTrue(KeyReplaceOutcome.notDeleted("x").line.contains("옛 키는 그대로"))
        XCTAssertEqual(KeyReplaceOutcome.notCreated("x").tone, .fail)
        XCTAssertTrue(KeyReplaceOutcome.notCreated("x").line.contains("1단계"))
    }

    func testOnlyReplacementResetsEnrollmentToNotEnrolled() {
        XCTAssertEqual(KeyReplaceOutcome.replaced.enrollmentAfter, .notEnrolled)
        XCTAssertNil(KeyReplaceOutcome.locked.enrollmentAfter)
        XCTAssertNil(KeyReplaceOutcome.notDeleted("x").enrollmentAfter)
        XCTAssertNil(KeyReplaceOutcome.notCreated("x").enrollmentAfter)
    }

    func testStatusCardIsDroppedWheneverTheKeyWasDeleted() {
        XCTAssertTrue(KeyReplaceOutcome.replaced.dropsConnectionState)
        XCTAssertTrue(KeyReplaceOutcome.notCreated("x").dropsConnectionState)
        XCTAssertFalse(KeyReplaceOutcome.locked.dropsConnectionState)
    }
}

final class KeyReplacementUITextTests: XCTestCase {
    func testCorruptedConfirmationSaysTheOldKeyCannotBeRecovered() {
        let c = KeyReplacement.confirmation(corrupted: true)
        XCTAssertTrue(c.message.contains("손상"))
        XCTAssertTrue(c.message.contains("복구할 수 없"))
        for needle in ["영구", "공개키", "차량에서", "키카드", "다시 등록", "연결"] {
            XCTAssertTrue(c.message.contains(needle), "missing: \(needle)")
        }
        XCTAssertEqual(c.action, "삭제 후 다시 만들기")
        XCTAssertEqual(KeyReplacement.confirmation(corrupted: false), KeyReplacement.confirmation)
    }

    func testConfirmationSaysEverythingTheUserMustKnow() {
        let c = KeyReplacement.confirmation
        XCTAssertTrue(c.message.contains("영구"), "removed from this phone permanently")
        XCTAssertTrue(c.message.contains("공개키"), "the car still lists the old public key")
        XCTAssertTrue(c.message.contains("차량에서"), "until it is removed on the car")
        XCTAssertTrue(c.message.contains("키카드"), "enroll the new key again with the key card")
        XCTAssertTrue(c.message.contains("다시 등록"))
        XCTAssertTrue(c.message.contains("연결"), "the connection is dropped and re-made")
        XCTAssertEqual(c.action, "삭제 후 다시 만들기")
    }
}

final class KeyReplacementStepsTests: XCTestCase {
    private func steps(keyReady: Bool = true, keyLocked: Bool = false, busy: Bool = false, connected: Bool = false) -> KeyControlSteps {
        KeyControlSteps(KeyControlInputs(
            keyReady: keyReady, keyLocked: keyLocked, vinSaved: true, connected: connected, hasKey: connected && keyReady,
            enrollment: .unknown, busy: busy
        ))
    }

    func testReplaceIsEnabledForAReadyKeyEvenWithoutAConnection() {
        XCTAssertTrue(steps(connected: false).replaceKeyEnabled)
        XCTAssertTrue(steps(connected: true).replaceKeyEnabled)
    }

    func testReplaceIsOffWhileBusy() {
        XCTAssertFalse(steps(busy: true).replaceKeyEnabled)
    }

    func testReplaceIsOffWhenTheKeyIsLockedOrNotReady() {
        XCTAssertFalse(steps(keyReady: false, keyLocked: true).replaceKeyEnabled)
        XCTAssertFalse(steps(keyReady: false).replaceKeyEnabled) // 그 밖의 오류·아직 읽지 않음은 계속 꺼 둔다
    }

    func testReplaceIsOnForADocumentedCorruptedKeyEvenThoughTheKeyIsNotReady() {
        let s = KeyControlSteps(KeyControlInputs(keyReady: false, keyLocked: false, keyCorrupted: true, vinSaved: true))
        XCTAssertTrue(s.replaceKeyEnabled)
        XCTAssertEqual(s.key.state, .blocked)
        XCTAssertTrue(s.key.hint.contains("손상"))
        XCTAssertFalse(KeyControlSteps(KeyControlInputs(keyCorrupted: true, busy: true)).replaceKeyEnabled)
        // 잠금이 손상보다 먼저다(잠금은 키가 멀쩡하다).
        XCTAssertFalse(KeyControlSteps(KeyControlInputs(keyLocked: true, keyCorrupted: true)).replaceKeyEnabled)
    }
}
