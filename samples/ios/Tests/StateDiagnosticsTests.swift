import XCTest
@testable import TeslableSample

/// SFR-023 Query 탭 상태 진단의 순수 규칙: 버튼 활성, 값 표시, 로그 문구.
/// 규칙의 정본은 `samples/documents/md/04-monitor-app.md` §State diagnostics.
final class StateDiagnosticsRulesTests: XCTestCase {
    private func inputs(
        connected: Bool = true, hasKey: Bool = true, enrolled: Bool = true, sleep: String = "AWAKE", busy: Bool = false
    ) -> StateInputs {
        StateInputs(connected: connected, hasKey: hasKey, enrolled: enrolled, sleep: sleep, busy: busy)
    }

    // MARK: 버튼

    func testButtonsNeedKeyLinkAndAwakeCar() {
        XCTAssertTrue(StateDiagnostics.buttonsEnabled(inputs()))
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(inputs(connected: false)), "no link")
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(inputs(hasKey: false)), "connected without a private key")
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(inputs(enrolled: false)), "key not enrolled (or not known yet)")
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(inputs(sleep: "ASLEEP")))
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(inputs(sleep: "UNKNOWN")), "UNKNOWN is not AWAKE")
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(inputs(sleep: "")), "no vehicleStatus() yet")
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(inputs(busy: true)))
    }

    func testEveryDisabledButtonHasAReason() {
        XCTAssertNil(StateDiagnostics.disabledHint(inputs()))
        for blocked in [
            inputs(connected: false), inputs(hasKey: false), inputs(enrolled: false),
            inputs(sleep: "ASLEEP"), inputs(sleep: "UNKNOWN"), inputs(busy: true),
        ] {
            XCTAssertNotNil(StateDiagnostics.disabledHint(blocked), "\(blocked)")
        }
    }

    func testSleepExperimentSkipsOnlyTheSleepCheck() {
        XCTAssertTrue(StateDiagnostics.experimentEnabled(inputs(sleep: "ASLEEP")))
        XCTAssertTrue(StateDiagnostics.experimentEnabled(inputs(sleep: "UNKNOWN")))
        XCTAssertFalse(StateDiagnostics.experimentEnabled(inputs(connected: false)))
        XCTAssertFalse(StateDiagnostics.experimentEnabled(inputs(hasKey: false)))
        XCTAssertFalse(StateDiagnostics.experimentEnabled(inputs(enrolled: false)), "it still needs an enrolled key")
        XCTAssertFalse(StateDiagnostics.experimentEnabled(inputs(sleep: "ASLEEP", busy: true)))
    }

    func testSleepIsReadFromTheStatusName() {
        XCTAssertEqual(SleepRead.from(name: "AWAKE"), .awake)
        XCTAssertEqual(SleepRead.from(name: "ASLEEP"), .asleep)
        XCTAssertEqual(SleepRead.from(name: "UNKNOWN"), .unknown)
        XCTAssertEqual(SleepRead.from(name: "SOMETHING_NEW"), .unknown, "a value we do not know never counts as awake")
    }

    // MARK: 값 표시

    func testSpeedShowsRawValueWithUnknownUnit() {
        XCTAssertEqual(StateDiagnostics.speedText(0), "0 (단위 미확인)", "0 stays 0")
        XCTAssertEqual(StateDiagnostics.speedText(87), "87 (단위 미확인)")
        XCTAssertEqual(StateDiagnostics.speedText(nil), "—", "absent is not 0")
        XCTAssertFalse(StateDiagnostics.speedText(100).contains("km"), "no conversion, no unit claimed")
        XCTAssertFalse(StateDiagnostics.speedText(100).contains("mph"))
    }

    func testDriveFieldsKeepAbsentAsDashAndZeroAsZero() {
        let fields = StateDiagnostics.fields(drive: DriveValues(speed: 0, speedFloat: nil, shift: "P", power: nil, odometer: 123, timestamp: 1_700_000_000))
        XCTAssertEqual(fields.first { $0.label == "speed" }?.value, "0 (단위 미확인)")
        XCTAssertEqual(fields.first { $0.label == "speed_float" }?.value, "—")
        XCTAssertEqual(fields.first { $0.label == "shift_state" }?.value, "P")
        XCTAssertEqual(fields.first { $0.label == "power" }?.value, "—")
        XCTAssertEqual(fields.first { $0.label == "odometer_in_hundredths_of_a_mile" }?.value, "123")
    }

    func testSpeedFloatKeepsTheUnitUnknown() {
        let fields = StateDiagnostics.fields(drive: DriveValues(speed: nil, speedFloat: 12.5, shift: nil, power: nil, odometer: nil, timestamp: nil))
        XCTAssertEqual(fields.first { $0.label == "speed_float" }?.value, "12.5 (단위 미확인)")
    }

    func testDriveShowsOnlyNamedFieldsAndNeverLocationOrRoute() {
        let labels = StateDiagnostics.fields(drive: DriveValues()).map(\.label)
        XCTAssertEqual(labels, ["speed", "speed_float", "shift_state", "power", "odometer_in_hundredths_of_a_mile", "timestamp"])
        for label in labels {
            XCTAssertFalse(label.contains("route"), label)
            XCTAssertFalse(label.contains("latitude"), label)
            XCTAssertFalse(label.contains("longitude"), label)
            XCTAssertFalse(label.contains("destination"), label)
        }
    }

    func testMediaShowsPlaybackVolumeAndTheTitleOnScreen() {
        let fields = StateDiagnostics.fields(media: MediaValues(playback: "PLAYING", volume: 3.5, title: "곡 제목", timestamp: nil))
        XCTAssertEqual(fields.map(\.label), ["media_playback_status", "audio_volume", "now_playing_title", "timestamp"])
        XCTAssertEqual(fields.first { $0.label == "media_playback_status" }?.value, "PLAYING")
        XCTAssertEqual(fields.first { $0.label == "audio_volume" }?.value, "3.5")
        XCTAssertEqual(fields.first { $0.label == "now_playing_title" }?.value, "곡 제목")
        XCTAssertEqual(StateDiagnostics.fields(media: MediaValues()).first { $0.label == "now_playing_title" }?.value, "—")
    }

    func testMediaDetailShowsDurationAndElapsedOnly() {
        let fields = StateDiagnostics.fields(detail: MediaDetailValues(duration: 200, elapsed: 0, timestamp: nil))
        XCTAssertEqual(fields.map(\.label), ["now_playing_duration", "now_playing_elapsed", "timestamp"])
        XCTAssertEqual(fields.first { $0.label == "now_playing_elapsed" }?.value, "0", "0 stays 0")
        XCTAssertEqual(StateDiagnostics.fields(detail: MediaDetailValues()).map(\.value), ["—", "—", "—"])
    }

    // MARK: 로그

    func testResultLogHoldsMetadataOnly() {
        let secret = "SECRET-TRACK-TITLE"
        let reading = StateReading(
            category: .media, outcome: .success, at: Date(), durationMs: 321,
            fields: StateDiagnostics.fields(media: MediaValues(playback: "PLAYING", volume: 4, title: secret, timestamp: 1_700_000_000))
        )
        let line = LogLine.stateQuery(ts: "00:00:02.000", entry: reading.logEntry, startedAt: "00:00:01.679").toJSON()
        XCTAssertFalse(line.contains(secret), "the title is shown on the screen only")
        XCTAssertFalse(line.contains("PLAYING"))
        XCTAssertFalse(line.contains("audio_volume"))
        XCTAssertTrue(line.contains("\"kind\":\"query\""))
        XCTAssertTrue(line.contains("getState(MEDIA)"))
        XCTAssertTrue(line.contains("Success"))
        XCTAssertTrue(line.contains("durationMs=321"))
    }

    func testLogLineCarriesTheErrorTypeNotThePayload() {
        let uncertain = StateReading(category: .drive, outcome: .uncertain(.responseTooLarge), at: Date(), durationMs: 90, fields: [])
        let json = LogLine.stateQuery(ts: "00:00:02.000", entry: uncertain.logEntry, startedAt: nil).toJSON()
        XCTAssertTrue(json.contains("Uncertain"))
        XCTAssertTrue(json.contains("error=ResponseTooLarge"))
        let notSent = StateReading(category: .drive, outcome: .notSent(.asleep), at: Date(), durationMs: 5, fields: [])
        let json2 = LogLine.stateQuery(ts: "00:00:02.000", entry: notSent.logEntry, startedAt: nil).toJSON()
        XCTAssertTrue(json2.contains("NotSent"))
        XCTAssertTrue(json2.contains("reason=asleep"))
    }

    // MARK: 결과 문구

    func testUncertainAndFailureAreDistinct() {
        let size = StateOutcome.uncertain(.responseTooLarge).text
        let timeout = StateOutcome.uncertain(.noReply(afterSend: true)).text
        let notSentTimeout = StateOutcome.failure(.noReply(afterSend: false)).text
        let noSession = StateOutcome.failure(.noSession).text
        let all = [size, timeout, notSentTimeout, noSession]
        XCTAssertEqual(Set(all).count, all.count, "four different sentences: \(all)")
        XCTAssertTrue(size.contains("응답"), "a size fault means the car answered")
        XCTAssertTrue(timeout.contains("응답이 없"), "a timeout means nothing came back")
        XCTAssertTrue(noSession.contains("보내지 않"), "no session means nothing was sent")
        XCTAssertEqual(StateOutcome.success.kind, "Success")
        XCTAssertEqual(StateOutcome.uncertain(.responseTooLarge).kind, "Uncertain")
        XCTAssertEqual(StateOutcome.failure(.noSession).kind, "Failure")
        XCTAssertEqual(StateOutcome.notSent(.asleep).kind, "NotSent")
    }

    func testNotSentReasonsAreDistinct() {
        let reasons: [NotSentReason] = [.asleep, .sleepUnknown, .sleepReadFailed(.noReply(afterSend: true)), .sessionFailed(.noSession)]
        let texts = reasons.map { StateOutcome.notSent($0).text }
        XCTAssertEqual(Set(texts).count, texts.count)
        for text in texts { XCTAssertFalse(text.contains("깨우지 않"), "never claims the car is not woken: \(text)") }
    }

    func testNoTextClaimsTheCarStaysAsleep() {
        let samples: [StateOutcome] = [
            .success, .uncertain(.responseTooLarge), .uncertain(.noReply(afterSend: true)), .failure(.noSession),
            .notSent(.asleep), .notSent(.sleepUnknown),
        ]
        for outcome in samples { XCTAssertFalse(outcome.text.contains("깨우지 않습니다"), outcome.text) }
    }
}

// MARK: - 모델

/// 한 연결에 대한 가짜 링크. 호출 순서와 횟수를 센다.
final class FakeStateLink: StateLink, @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [String] = []
    var sleep: SleepRead = .awake
    var session: StateSessionOutcome = .ready
    var fetch: StateFetch = .success([StateField(label: "speed", value: "0 (단위 미확인)")])
    /// 있으면 `getState` 가 이 문이 열릴 때까지 멈춘다.
    var getStateGate: TestGate?

    var log: [String] { lock.withLock { calls } }
    func count(_ name: String) -> Int { log.filter { $0 == name }.count }

    private func note(_ name: String) { lock.withLock { calls.append(name) } }

    func readSleep() async -> SleepRead {
        note("sleep")
        return sleep
    }

    func ensureInfotainmentSession() async -> StateSessionOutcome {
        note("session")
        return session
    }

    func getState(_ category: DiagnosticCategory) async -> StateFetch {
        note("getState:\(category.logName)")
        await getStateGate?.wait()
        return fetch
    }
}

final class FakeStateBackend: StateDiagnosticsBackend, @unchecked Sendable {
    private let lock = NSLock()
    var link: FakeStateLink?
    private var entries: [StateLogEntry] = []
    private var opens = 0

    var logged: [StateLogEntry] { lock.withLock { entries } }
    var openCalls: Int { lock.withLock { opens } }

    func open() -> StateLink? {
        lock.withLock { opens += 1 }
        return link
    }

    func stamp() -> String { "00:00:00.000" }
    func writeLog(_ entry: StateLogEntry, startedAt: String) { lock.withLock { entries.append(entry) } }
}

@MainActor
final class StateDiagnosticsModelTests: XCTestCase {
    private var backend: FakeStateBackend!
    private var link: FakeStateLink!
    private var gate: KeyOperationGate!
    private var otherBusy = false
    private var model: StateDiagnosticsModel!

    override func setUp() async throws {
        try await super.setUp()
        link = FakeStateLink()
        backend = FakeStateBackend()
        backend.link = link
        gate = KeyOperationGate()
        otherBusy = false
        model = StateDiagnosticsModel(backend: backend, gate: gate, otherOperationRunning: { [unowned self] in otherBusy })
    }

    private func becomeReady(sleep: String = "AWAKE") {
        model.connectionChanged(connected: true, hasKey: true)
        model.enrollmentChanged(true)
        model.sleepQueried(sleep)
    }

    func testQueryReadsSleepThenEnsuresTheSessionThenAsksOnce() async {
        becomeReady()
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(link.log, ["sleep", "session", "getState:DRIVE"], "vehicleStatus first, then the session, then one getState")
        XCTAssertEqual(model.reading?.outcome, .success)
        XCTAssertEqual(model.reading?.category, .drive)
        XCTAssertEqual(model.reading?.fields.first?.label, "speed")
        XCTAssertEqual(backend.logged.count, 1)
        XCTAssertNil(model.running)
        XCTAssertEqual(gate.activeOperations, 0, "the key gate is released")
    }

    func testSleepIsReadAgainEveryTimeEvenWhenTheCachedStateIsAwake() async {
        becomeReady(sleep: "AWAKE")
        await model.run(.drive, ignoringSleep: false)
        await model.run(.media, ignoringSleep: false)
        XCTAssertEqual(link.count("sleep"), 2)
    }

    func testAsleepSendsNothingAndNeverStartsASession() async {
        becomeReady()
        link.sleep = .asleep
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(link.log, ["sleep"])
        XCTAssertEqual(model.reading?.outcome, .notSent(.asleep))
        XCTAssertEqual(model.sleepStatus, "ASLEEP", "the re-read updates the state that enables the buttons")
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(model.inputs))
    }

    func testUnknownSleepSendsNothing() async {
        becomeReady()
        link.sleep = .unknown
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(link.log, ["sleep"])
        XCTAssertEqual(model.reading?.outcome, .notSent(.sleepUnknown))
    }

    func testFailedSleepReadSendsNothing() async {
        becomeReady()
        link.sleep = .failed(.noReply(afterSend: true))
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(link.log, ["sleep"])
        XCTAssertEqual(model.reading?.outcome, .notSent(.sleepReadFailed(.noReply(afterSend: true))))
    }

    func testSessionFailureSendsNoGetState() async {
        becomeReady()
        link.session = .failed(.other("KeyNotPaired"))
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(link.log, ["sleep", "session"])
        XCTAssertEqual(model.reading?.outcome, .notSent(.sessionFailed(.other("KeyNotPaired"))))
    }

    func testSleepExperimentSkipsOnlyTheSleepCheck() async {
        becomeReady(sleep: "ASLEEP")
        link.sleep = .asleep
        await model.run(.drive, ignoringSleep: true)
        XCTAssertEqual(link.log, ["session", "getState:DRIVE"], "no vehicleStatus(); the session and getState are as usual")
        XCTAssertEqual(model.reading?.outcome, .success)
    }

    func testSleepExperimentStillNeedsLinkAndEnrolledKey() async {
        model.connectionChanged(connected: true, hasKey: true)
        model.sleepQueried("ASLEEP") // not enrolled
        await model.run(.drive, ignoringSleep: true)
        XCTAssertEqual(backend.openCalls, 0)
        XCTAssertNotNil(model.notice)
        XCTAssertNil(model.reading)
    }

    func testUncertainIsShownAndNotRetried() async {
        becomeReady()
        link.fetch = .uncertain(.noReply(afterSend: true))
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(link.count("getState:DRIVE"), 1, "no automatic retry")
        XCTAssertEqual(model.reading?.outcome, .uncertain(.noReply(afterSend: true)))
        XCTAssertTrue(model.reading?.fields.isEmpty ?? false)
    }

    func testNoSessionAfterAStaleCacheIsShownAndTheNextTapStartsASession() async {
        becomeReady()
        link.fetch = .failure(.noSession)
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(model.reading?.outcome, .failure(.noSession))
        XCTAssertEqual(link.count("getState:DRIVE"), 1, "no automatic retry")
        XCTAssertEqual(link.count("session"), 1)
        link.fetch = .success([])
        await model.run(.drive, ignoringSleep: false) // the next tap
        XCTAssertEqual(link.count("session"), 2, "the session step runs again; the library handshakes if the cached one was dropped")
        XCTAssertEqual(model.reading?.outcome, .success)
    }

    func testDoubleTapRunsOneQuery() async {
        becomeReady()
        let hold = TestGate()
        link.getStateGate = hold
        let l: FakeStateLink = link
        let first = Task { await model.run(.drive, ignoringSleep: false) }
        await waitUntil("getState started") { l.count("getState:DRIVE") == 1 }
        XCTAssertEqual(model.running, .drive)
        XCTAssertFalse(StateDiagnostics.buttonsEnabled(model.inputs), "buttons are off while a query runs")
        await model.run(.drive, ignoringSleep: false) // the second tap, same category
        await model.run(.media, ignoringSleep: false) // and a different button
        hold.release()
        await first.value
        XCTAssertEqual(link.count("getState:DRIVE"), 1)
        XCTAssertEqual(link.count("getState:MEDIA"), 0)
        XCTAssertEqual(link.count("sleep"), 1)
        XCTAssertEqual(backend.logged.count, 1)
    }

    func testLateResultFromOldConnectionIsIgnored() async {
        becomeReady()
        let hold = TestGate()
        link.getStateGate = hold
        let l: FakeStateLink = link
        let old = Task { await model.run(.drive, ignoringSleep: false) }
        await waitUntil("getState started") { l.count("getState:DRIVE") == 1 }
        model.connectionChanged(connected: false, hasKey: false)
        model.connectionChanged(connected: true, hasKey: true) // a new connection
        model.enrollmentChanged(true)
        hold.release()
        await old.value
        XCTAssertNil(model.reading, "the late result does not paint the new connection's screen")
        XCTAssertNil(model.running)
        XCTAssertEqual(backend.logged.count, 0, "and it is not logged into the new connection's log")
        XCTAssertEqual(gate.activeOperations, 0, "the key gate is released all the same")
        XCTAssertEqual(model.sleepStatus, "UNKNOWN", "the new connection has not read the sleep state yet")
    }

    func testOldConnectionDoesNotBlockTheNextQuery() async {
        becomeReady()
        let hold = TestGate()
        link.getStateGate = hold
        let l: FakeStateLink = link
        let old = Task { await model.run(.drive, ignoringSleep: false) }
        await waitUntil("getState started") { l.count("getState:DRIVE") == 1 }
        model.connectionChanged(connected: false, hasKey: false)
        model.connectionChanged(connected: true, hasKey: true)
        model.enrollmentChanged(true)
        model.sleepQueried("AWAKE")
        let fresh = FakeStateLink()
        backend.link = fresh
        await model.run(.media, ignoringSleep: false)
        XCTAssertEqual(model.reading?.category, .media)
        XCTAssertEqual(model.reading?.outcome, .success)
        hold.release()
        await old.value
        XCTAssertEqual(model.reading?.category, .media, "the old result did not replace the new one")
    }

    func testQueryIsRefusedWhileKeyOperationRuns() async {
        becomeReady()
        // The key is being replaced (key-and-control tab).
        XCTAssertTrue(gate.beginReplace())
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(backend.openCalls, 0, "nothing was asked of the car")
        XCTAssertNotNil(model.notice)
        XCTAssertNil(model.reading)
        gate.endReplace()

        // A command or enrollment is running in another tab.
        otherBusy = true
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(backend.openCalls, 0)
        XCTAssertNotNil(model.notice)
        otherBusy = false

        // Control tab call holding the gate.
        XCTAssertTrue(gate.beginOperation())
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(backend.openCalls, 0)
        gate.endOperation()

        // Free again.
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(backend.openCalls, 1)
        XCTAssertNil(model.notice)
    }

    func testAQueryInFlightKeepsTheKeyFromBeingReplaced() async {
        becomeReady()
        let hold = TestGate()
        link.getStateGate = hold
        let l: FakeStateLink = link
        let task = Task { await model.run(.drive, ignoringSleep: false) }
        await waitUntil("getState started") { l.count("getState:DRIVE") == 1 }
        XCTAssertFalse(gate.beginReplace(), "the key cannot be replaced under a running query")
        hold.release()
        await task.value
        XCTAssertTrue(gate.beginReplace())
        gate.endReplace()
    }

    func testNothingRunsWithoutLinkKeyAndEnrollment() async {
        await model.run(.drive, ignoringSleep: false) // no link
        XCTAssertEqual(backend.openCalls, 0)
        model.connectionChanged(connected: true, hasKey: false)
        await model.run(.drive, ignoringSleep: false)
        XCTAssertEqual(backend.openCalls, 0)
        model.connectionChanged(connected: true, hasKey: true)
        await model.run(.drive, ignoringSleep: false) // not enrolled
        XCTAssertEqual(backend.openCalls, 0)
        model.enrollmentChanged(true)
        await model.run(.drive, ignoringSleep: false) // sleep unknown
        XCTAssertEqual(backend.openCalls, 0, "UNKNOWN keeps the normal buttons off")
        XCTAssertNotNil(model.notice)
    }

    func testDisconnectClearsTheScreenAndTheSleepState() async {
        becomeReady()
        await model.run(.drive, ignoringSleep: false)
        XCTAssertNotNil(model.reading)
        model.connectionChanged(connected: false, hasKey: false)
        XCTAssertNil(model.reading, "values belong to the connection that read them")
        XCTAssertEqual(model.sleepStatus, "UNKNOWN")
    }

    func testLoggedEntryHoldsMetadataOnly() async {
        becomeReady()
        link.fetch = .success([StateField(label: "now_playing_title", value: "SECRET-TRACK-TITLE")])
        await model.run(.media, ignoringSleep: false)
        XCTAssertEqual(model.reading?.fields.first?.value, "SECRET-TRACK-TITLE", "shown on the screen")
        let entry = try? XCTUnwrap(backend.logged.first)
        let json = entry.map { LogLine.stateQuery(ts: "t", entry: $0, startedAt: "s").toJSON() } ?? ""
        XCTAssertFalse(json.contains("SECRET-TRACK-TITLE"))
        XCTAssertTrue(json.contains("MEDIA"))
    }
}
