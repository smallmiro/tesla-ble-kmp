import XCTest
@testable import TeslableSample

/// 가짜 Infotainment 호출 묶음: 호출 순서를 적고, 명령 호출에서 시험이 멈췄다 풀어 줄 수 있다.
final class FakeInfotainmentLink: InfotainmentLink, @unchecked Sendable {
    var status: InfoStatusRead = .success(StatusCard(lock: "LOCKED", trunk: "CLOSED", frunk: "CLOSED", sleep: "AWAKE", at: Date()))
    var session: InfoSessionResult = .ready
    var command: InfoCallResult<InfoNoValue> = .success(InfoNoValue())
    var readResult: InfoCallResult<InfoValues> = .success(.climate(InfoClimateValues(isClimateOn: true, timestamp: 100)))
    /// 있으면 명령·읽기 호출이 열릴 때까지 멈춘다.
    var gate: TestGate?
    private let log = Locked<[String]>([])

    var calls: [String] { log.value }

    func readStatus() async -> InfoStatusRead {
        log.mutate { $0.append("status") }
        return status
    }

    func ensureSession() async -> InfoSessionResult {
        log.mutate { $0.append("session") }
        return session
    }

    func send(_ cmd: InfoCommand) async -> InfoCallResult<InfoNoValue> {
        log.mutate { $0.append("send:\(cmd.label)") }
        await gate?.wait()
        return command
    }

    func read(_ category: InfoCategory) async -> InfoCallResult<InfoValues> {
        log.mutate { $0.append("read:\(category.title)") }
        await gate?.wait()
        return readResult
    }
}

/// 키·제어 탭의 Infotainment 누르기(SFR-025)를 가짜 `KeyControlBackend` 와 가짜 호출 묶음으로 시험한다.
@MainActor
final class InfotainmentModelTests: XCTestCase {
    private var backend: FakeKeyControlBackend!
    private var link: FakeInfotainmentLink!
    private var model: KeyControlModel!

    override func setUp() async throws {
        try await super.setUp()
        backend = FakeKeyControlBackend()
        link = FakeInfotainmentLink()
        backend.infotainment = link
        model = KeyControlModel(backend: backend)
    }

    private func waitUntil(_ condition: @MainActor () -> Bool, timeout: TimeInterval = 3, line: UInt = #line) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline { try? await Task.sleep(nanoseconds: 5_000_000) }
        XCTAssertTrue(condition(), "timed out waiting for the condition", line: line)
    }

    /// 키가 준비되고 키를 가진 연결이 있는 상태(가짜의 자동 등록 확인은 `notEnrolled`)에서 등록된 것으로 만든다.
    private func connectEnrolled() async {
        model.vinChanged(saved: true)
        model.keyChanged(.ready)
        model.connectionChanged(connected: true, hasKey: true)
        await waitUntil { self.model.inputs.enrollment == .notEnrolled && !self.model.inputs.busy }
        model.enrollmentChangedElsewhere(.enrolled)
        XCTAssertTrue(InfotainmentControl.enabled(model.inputs))
    }

    func testAButtonThatIsOffSendsNothingAndSaysWhy() async {
        model.pressInfo(.climateOn) // 연결도 키도 없다

        await settle()
        XCTAssertEqual(link.calls, [])
        XCTAssertEqual(model.results.first?.title, "공조 켜기")
        XCTAssertEqual(model.results.first?.text.hasPrefix("보내지 않음 — "), true)
        XCTAssertEqual(model.results.first?.tone, .warn)
    }

    func testAnAwakeTapRunsStatusSessionAndOneCommandAndUpdatesTheStatusCard() async {
        await connectEnrolled()

        await model.runInfo(.chargeStart)

        XCTAssertEqual(link.calls, ["status", "session", "send:충전 시작"])
        XCTAssertEqual(model.status?.sleep, "AWAKE")
        let line = model.results.first
        XCTAssertEqual(line?.title, "충전 시작")
        XCTAssertEqual(line?.text.hasPrefix("성공 "), true)
        XCTAssertEqual(line?.tone, .ok)
        XCTAssertEqual(model.info, InfoUiState(), "a success of a command shows no readout")
    }

    func testASleepingCarIsNotSentAndStartsNoSession() async {
        await connectEnrolled()
        link.status = .success(StatusCard(lock: "LOCKED", trunk: "CLOSED", frunk: "CLOSED", sleep: "ASLEEP", at: Date()))

        await model.runInfo(.climateOn)

        XCTAssertEqual(link.calls, ["status"])
        XCTAssertEqual(model.status?.sleep, "ASLEEP", "the status read still refreshes the card")
        XCTAssertEqual(model.results.first?.text.contains("보내지 않음"), true)
        XCTAssertEqual(model.results.first?.tone, .warn)
    }

    func testAnUncertainCommandMarksTheCategoryAndAReadClearsItAndShowsTheValues() async {
        await connectEnrolled()
        link.command = .uncertain(errorType: "Timeout(afterSend=true)")

        await model.runInfo(.climateOn)

        XCTAssertEqual(model.info.unconfirmed, [.climate])
        XCTAssertEqual(link.calls.filter { $0.hasPrefix("send") }.count, 1, "never resent, no automatic getState")
        XCTAssertFalse(link.calls.contains { $0.hasPrefix("read") })

        await model.runInfo(.readClimate)

        XCTAssertEqual(model.info.unconfirmed, [])
        XCTAssertEqual(model.info.readings[.climate]?.fields.first?.value, "true")
        XCTAssertEqual(model.info.readings[.climate]?.timestampSeconds, 100)
    }

    func testKeyNotPairedFromTheSessionSetsNotEnrolled() async {
        await connectEnrolled()
        link.session = .failed(errorType: "KeyNotPaired")

        await model.runInfo(.climateOff)

        XCTAssertEqual(model.inputs.enrollment, .notEnrolled)
        XCTAssertEqual(link.calls, ["status", "session"])
    }

    func testAFailureIsShownAsFailedWithTheTypeOnly() async {
        await connectEnrolled()
        link.command = .failure(errorType: "InfotainmentRejected")

        await model.runInfo(.chargeStop)

        XCTAssertEqual(model.results.first?.tone, .fail)
        XCTAssertEqual(model.results.first?.text.contains("(InfotainmentRejected)"), true)
    }

    func testAResultThatArrivesAfterTheConnectionChangedIsDropped() async {
        await connectEnrolled()
        let gate = TestGate()
        link.gate = gate
        link.command = .uncertain(errorType: "Timeout(afterSend=true)")

        model.pressInfo(.climateOn)
        await waitUntil { self.link.calls.contains("send:공조 켜기") }
        model.connectionChanged(connected: false, hasKey: false)
        model.connectionChanged(connected: true, hasKey: true)
        gate.release()
        await waitUntil { !self.model.inputs.busy }

        XCTAssertEqual(model.info, InfoUiState(), "no marker from a dropped Uncertain")
        XCTAssertNil(model.status, "no status card from the old connection")
        // 다시 연결하면 자동 등록 확인 줄이 뒤에 붙는다: 버튼 줄은 이름으로 찾는다.
        let lines = model.results.filter { $0.title == "공조 켜기" }
        XCTAssertEqual(lines.count, 1, "one dropped line, no result line")
        XCTAssertEqual(lines.first?.text, InfotainmentRunner.droppedText(.climateOn))
    }

    func testAConnectionChangeClearsReadoutsAndMarkers() async {
        await connectEnrolled()
        link.command = .uncertain(errorType: "Timeout(afterSend=true)")
        await model.runInfo(.climateOn)
        await model.runInfo(.readClimate)
        await model.runInfo(.chargeStart)
        XCTAssertFalse(model.info.readings.isEmpty)
        XCTAssertFalse(model.info.unconfirmed.isEmpty)

        model.connectionChanged(connected: false, hasKey: false)

        XCTAssertEqual(model.info, InfoUiState())
    }

    func testASecondTapWhileOneRunsDoesNothingAndSaysBusy() async {
        await connectEnrolled()
        let gate = TestGate()
        link.gate = gate

        model.pressInfo(.climateOn)
        await waitUntil { self.model.inputs.busy }
        model.pressInfo(.climateOff)
        await settle()

        XCTAssertEqual(link.calls.filter { $0.hasPrefix("send") }, ["send:공조 켜기"])
        XCTAssertEqual(model.results.first?.title, "공조 끄기")
        XCTAssertEqual(model.results.first?.text, "보내지 않음 — 다른 호출이 진행 중입니다")
        gate.release()
        await waitUntil { !self.model.inputs.busy }
    }

    func testWithoutAConnectionTheTapSaysToConnectFirst() async {
        await connectEnrolled()
        backend.infotainment = nil

        await model.runInfo(.readCharge)

        XCTAssertEqual(model.results.first?.text, "먼저 연결하세요")
    }
}
