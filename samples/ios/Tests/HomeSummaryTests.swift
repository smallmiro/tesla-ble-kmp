import XCTest
@testable import TeslableSample

/// SFR-024 두 영역 첫 화면의 순수 규칙: 잠금 말풍선 동작, 속도 표시와 나이, 왼쪽 머리글·메뉴 요약·바닥 줄.
/// 있는 값만 쓴다. 없는 값(배터리, 위치, 모델명 등)은 만들지 않는다.
final class HomeSummaryTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_792_803_600)

    // MARK: 잠금 말풍선

    func testLockedCarOffersUnlock() {
        for raw in ["LOCKED", "INTERNAL_LOCKED"] {
            let callout = LockCallout.of(lock: raw)
            XCTAssertEqual(callout.button, .unlock, raw)
            XCTAssertEqual(callout.symbol, "lock.fill", raw)
        }
    }

    func testUnlockedCarOffersLock() {
        for raw in ["UNLOCKED", "SELECTIVE_UNLOCKED"] {
            let callout = LockCallout.of(lock: raw)
            XCTAssertEqual(callout.button, .lock, raw)
            XCTAssertEqual(callout.symbol, "lock.open.fill", raw)
        }
    }

    func testUnknownLockStateOffersNothing() {
        for raw in [nil, "UNKNOWN", "SOMETHING_NEW", ""] as [String?] {
            XCTAssertNil(LockCallout.of(lock: raw).button, "\(String(describing: raw))")
        }
    }

    // MARK: 속도 (마지막 수동 Drive 조회)

    private func driveReading(
        speed: String, odometer: String = "—", at: Date, outcome: StateOutcome = .success, category: DiagnosticCategory = .drive
    ) -> StateReading {
        StateReading(
            category: category, outcome: outcome, at: at, durationMs: 120,
            fields: [
                StateField(label: "speed", value: speed),
                StateField(label: "shift_state", value: "P"),
                StateField(label: "odometer_in_hundredths_of_a_mile", value: odometer),
            ]
        )
    }

    func testNoDriveReadingShowsDashAndNoAge() {
        let summary = DriveSummary(reading: nil, now: t0)
        XCTAssertEqual(summary.speedText, "—")
        XCTAssertNil(summary.unitNote)
        XCTAssertNil(summary.ageText)
        XCTAssertNil(summary.odometerText)
    }

    func testSpeedIsTheRawNumberWithUnitMarkedUnconfirmedAndAge() {
        let summary = DriveSummary(reading: driveReading(speed: "87 (단위 미확인)", odometer: "54117", at: t0), now: t0.addingTimeInterval(12))
        XCTAssertEqual(summary.speedText, "87")
        XCTAssertEqual(summary.unitNote, "단위 미확인")
        XCTAssertEqual(summary.ageText, "12초 전 조회")
        XCTAssertEqual(summary.odometerText, "54117")
    }

    func testZeroStaysZero() {
        XCTAssertEqual(DriveSummary(reading: driveReading(speed: "0 (단위 미확인)", at: t0), now: t0).speedText, "0")
    }

    func testAbsentSpeedInASuccessfulReadingIsADashWithoutUnitNote() {
        let summary = DriveSummary(reading: driveReading(speed: "—", at: t0), now: t0.addingTimeInterval(3))
        XCTAssertEqual(summary.speedText, "—")
        XCTAssertNil(summary.unitNote, "no value, no unit")
        XCTAssertEqual(summary.ageText, "3초 전 조회", "the query itself still happened")
    }

    func testOnlyASuccessfulDriveReadingCounts() {
        XCTAssertEqual(DriveSummary(reading: driveReading(speed: "5 (단위 미확인)", at: t0, outcome: .failure(.other("X"))), now: t0).speedText, "—")
        XCTAssertEqual(
            DriveSummary(reading: driveReading(speed: "5 (단위 미확인)", at: t0, outcome: .uncertain(.noReply(afterSend: true))), now: t0).speedText, "—"
        )
        XCTAssertEqual(DriveSummary(reading: driveReading(speed: "5 (단위 미확인)", at: t0, category: .media), now: t0).speedText, "—")
    }

    func testAgeNeverGoesNegative() {
        XCTAssertEqual(DriveSummary(reading: driveReading(speed: "1 (단위 미확인)", at: t0.addingTimeInterval(5)), now: t0).ageText, "0초 전 조회")
    }

    // MARK: 왼쪽 머리글

    func testTitleIsTheKeyNicknameElseTesla() {
        XCTAssertEqual(HomeHeader.title(ownKeyNickname: "Titan II"), "Titan II")
        XCTAssertEqual(HomeHeader.title(ownKeyNickname: "  Titan  "), "Titan")
        XCTAssertEqual(HomeHeader.title(ownKeyNickname: nil), "Tesla")
        XCTAssertEqual(HomeHeader.title(ownKeyNickname: "   "), "Tesla")
    }

    func testStatusLineJoinsKnownPartsOnly() {
        XCTAssertEqual(HomeHeader.statusLine(gear: .park, lockText: "잠김", sleepText: "깨어 있음"), "주차 중 · 잠김 · 깨어 있음")
        XCTAssertEqual(HomeHeader.statusLine(gear: .drive, lockText: "잠금 해제됨", sleepText: ClusterText.none), "주행 중 · 잠금 해제됨")
        XCTAssertEqual(HomeHeader.statusLine(gear: nil, lockText: ClusterText.none, sleepText: "잠듦"), "잠듦")
        XCTAssertEqual(HomeHeader.statusLine(gear: nil, lockText: ClusterText.none, sleepText: ClusterText.none), "")
    }

    func testReverseAndNeutralCountAsDriving() {
        XCTAssertTrue(HomeHeader.statusLine(gear: .reverse, lockText: ClusterText.none, sleepText: ClusterText.none).hasPrefix("주행 중"))
        XCTAssertTrue(HomeHeader.statusLine(gear: .neutral, lockText: ClusterText.none, sleepText: ClusterText.none).hasPrefix("주행 중"))
    }

    // MARK: 메뉴 요약

    func testQuerySummaryShowsLastOutcomeAndTime() {
        XCTAssertEqual(MenuSummary.query(nil, now: t0), "아직 조회하지 않았습니다")
        let result = QueryResult(call: "vehicleStatus", outcome: "Success", at: t0, summary: "x")
        let text = MenuSummary.query(result, now: t0.addingTimeInterval(7))
        XCTAssertTrue(text.hasPrefix("Success · "), text)
        XCTAssertTrue(text.hasSuffix("(7초 전)"), text)
    }

    func testLogSummaryCountsLines() {
        XCTAssertEqual(MenuSummary.log(totalLines: 0), "아직 쓴 줄 없음")
        XCTAssertEqual(MenuSummary.log(totalLines: 42), "쓴 줄 42")
    }

    func testControlSummaryIsTheConnectionState() {
        XCTAssertEqual(MenuSummary.control(connected: true), "연결됨")
        XCTAssertEqual(MenuSummary.control(connected: false), "연결 안 됨")
    }

    func testKeyControlSummaryIsTheFirstUnfinishedStep() {
        XCTAssertEqual(MenuSummary.keyControl(KeyControlSteps(KeyControlInputs())), KeyControlSteps(KeyControlInputs()).key.hint, "no key yet: step 1")
        let keyOnly = KeyControlInputs(keyReady: true)
        XCTAssertEqual(MenuSummary.keyControl(KeyControlSteps(keyOnly)), KeyControlSteps(keyOnly).connect.hint, "key ready: step 2")
        let ready = KeyControlInputs(keyReady: true, vinSaved: true, connected: true, hasKey: true)
        XCTAssertEqual(MenuSummary.keyControl(KeyControlSteps(ready)), KeyControlSteps(ready).enroll.hint, "connected: step 3")
        var enrolled = ready
        enrolled.enrollment = .enrolled
        XCTAssertEqual(MenuSummary.keyControl(KeyControlSteps(enrolled)), "등록됨 · 제어할 수 있습니다")
    }

    func testLiveDetailSummaryIsTheLastEventTime() {
        XCTAssertEqual(MenuSummary.liveDetail(lastEventAt: nil), "이벤트 없음")
        XCTAssertEqual(MenuSummary.liveDetail(lastEventAt: t0), "마지막 이벤트 \(Formatting.clockText(t0))")
    }

    // MARK: 상태 줄 (왼쪽 메뉴의 카드 없는 줄)

    private func clusterState(_ snapshot: StatusSnapshot?) -> ClusterState {
        var live = LiveState()
        if let snapshot {
            live = LiveReducer.reduce(live, MonitorEvent(kind: .status(snapshot), raw: [1], unknownFields: []), now: t0)
        }
        return ClusterState(live: live, link: LinkState<Int>.connected(1), now: t0)
    }

    func testStatusRowsReadTheClusterStateInOrder() {
        let rows = HomeStatus.rows(state: clusterState(StatusSnapshot()), drive: DriveSummary(reading: nil, now: t0))
        XCTAssertEqual(rows.map(\.title), ["잠금", "문·트렁크", "수면", "재실", "주행"])
        XCTAssertEqual(rows.map(\.subtitle), ["잠김", "방송 기준 모두 닫힘", "깨어 있음", "사용자 없음", "기어 P · 속도 —"])
    }

    func testOpenPartsAreNamedInTheDoorsRow() {
        var snapshot = StatusSnapshot()
        snapshot.trunk = "OPEN"
        let rows = HomeStatus.rows(state: clusterState(snapshot), drive: DriveSummary(reading: nil, now: t0))
        XCTAssertTrue(rows[1].subtitle.hasSuffix("열림"), rows[1].subtitle)
    }

    func testStatusRowsWithoutAnyStateShowDashes() {
        let rows = HomeStatus.rows(state: clusterState(nil), drive: DriveSummary(reading: nil, now: t0))
        XCTAssertEqual(rows.map(\.subtitle), ["—", "—", "—", "—", "기어 — · 속도 —"], "never a guessed value")
    }

    func testDriveRowShowsTheSpeedOfTheLastManualDriveQuery() {
        let reading = driveReading(speed: "12 (단위 미확인)", at: t0)
        let rows = HomeStatus.rows(state: clusterState(StatusSnapshot()), drive: DriveSummary(reading: reading, now: t0))
        XCTAssertEqual(rows[4].subtitle, "기어 P · 속도 12 (단위 미확인)")
    }

    // MARK: 바닥 줄

    func testFooterHasVersionAndOnlyAKnownOdometer() {
        XCTAssertEqual(HomeFooter.lines(version: "0.5.0 (22)", drive: DriveSummary(reading: nil, now: t0)), ["0.5.0 (22)"])
        let drive = DriveSummary(reading: driveReading(speed: "0 (단위 미확인)", odometer: "54117", at: t0), now: t0.addingTimeInterval(30))
        let lines = HomeFooter.lines(version: "0.5.0 (22)", drive: drive)
        XCTAssertEqual(lines.count, 2)
        XCTAssertEqual(lines[0], "0.5.0 (22)")
        XCTAssertTrue(lines[1].contains("54117"), lines[1])
        XCTAssertTrue(lines[1].contains("1/100마일"), "unit named by the field name, not converted")
        XCTAssertTrue(lines[1].contains("30초 전 조회"), lines[1])
    }

    // MARK: 빠른 제어 목록

    func testCalloutsAndActionRowAreExistingKeyControlButtons() {
        XCTAssertEqual(QuickControls.frunk, .openFrunk)
        XCTAssertEqual(QuickControls.trunk, .actuateTrunk)
        XCTAssertEqual(QuickControls.actionRow, [.wakeUp, .closeTrunk])
        XCTAssertFalse(QuickControls.actionRow.contains(.remoteDrive), "never one tap")
        XCTAssertFalse(QuickControls.actionRow.contains(.openFrunk), "frunk is a callout and asks first")
    }
}

/// 마지막 수동 Drive 조회(성공)만 모델이 기억한다. 연결이 바뀌면 지운다.
@MainActor
final class LastDriveReadingTests: XCTestCase {
    private var backend: FakeStateBackend!
    private var link: FakeStateLink!
    private var model: StateDiagnosticsModel!

    override func setUp() async throws {
        try await super.setUp()
        link = FakeStateLink()
        backend = FakeStateBackend()
        backend.link = link
        model = StateDiagnosticsModel(backend: backend, gate: KeyOperationGate(), otherOperationRunning: { false })
        model.connectionChanged(connected: true, hasKey: true)
        model.enrollmentChanged(true)
        model.sleepQueried("AWAKE")
    }

    func testNoDriveReadingBeforeAnyQuery() {
        XCTAssertNil(model.lastDrive)
    }

    func testSuccessfulDriveIsRemembered() async {
        link.fetch = .success([StateField(label: "speed", value: "12 (단위 미확인)")])
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(model.lastDrive?.category, .drive)
        XCTAssertEqual(model.lastDrive?.fields.first?.value, "12 (단위 미확인)")
    }

    func testOtherCategoriesAndFailuresDoNotReplaceIt() async {
        link.fetch = .success([StateField(label: "speed", value: "12 (단위 미확인)")])
        await model.run(.drive, ignoringSleep: false)
        let kept = model.lastDrive
        link.fetch = .success([StateField(label: "media_playback_status", value: "PLAYING")])
        await model.run(.media, ignoringSleep: false)
        XCTAssertEqual(model.lastDrive, kept, "a Media result is not a Drive result")
        link.fetch = .failure(.other("X"))
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(model.lastDrive, kept, "a failed Drive keeps the earlier success")
        link.fetch = .uncertain(.noReply(afterSend: true))
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(model.lastDrive, kept)
    }

    func testAConnectionChangeForgetsIt() async {
        await model.run(.drive, ignoringSleep: false)
        XCTAssertNotNil(model.lastDrive)
        model.connectionChanged(connected: false, hasKey: false)
        XCTAssertNil(model.lastDrive, "the value belongs to the connection it was read on")
    }

    func testALateResultFromAnOldConnectionIsNotRemembered() async {
        let gate = TestGate()
        link.getStateGate = gate
        let running = Task { await self.model.run(.drive, ignoringSleep: false) }
        while link.count("getState:DRIVE") == 0 { await Task.yield() }
        model.connectionChanged(connected: false, hasKey: false)
        gate.release()
        await running.value
        XCTAssertNil(model.lastDrive)
    }
}
