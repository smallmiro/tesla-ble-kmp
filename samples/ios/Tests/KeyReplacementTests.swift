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

        func run(wasActive: Bool) async -> KeyReplaceOutcome {
            await KeyReplacer.run(
                wasActive: wasActive,
                probe: { self.calls.append("probe"); return self.probe },
                disconnect: { self.calls.append("disconnect") },
                forgetHandle: { self.calls.append("forget") },
                delete: { self.calls.append("delete"); return self.delete },
                create: { self.calls.append("create"); return self.create },
                restore: { self.calls.append("restore"); return self.restore },
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

    func testFailedDeleteWhoseKeyIsNowLockedReportsLockedAndReconnects() async {
        let r = Rig()
        r.delete = .failed("boom")
        r.restore = .locked
        let outcome = await r.run(wasActive: true)
        XCTAssertEqual(outcome, .locked)
        XCTAssertEqual(r.calls, ["probe", "disconnect", "forget", "delete", "restore", "reconnect"])
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
        XCTAssertFalse(steps(keyReady: false).replaceKeyEnabled)
    }
}
