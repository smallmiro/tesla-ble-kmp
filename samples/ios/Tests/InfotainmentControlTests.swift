import XCTest
@testable import TeslableSample

/// 차 없이 키·제어 탭의 Infotainment 공조·충전 규칙(SFR-025, `05-key-control-tab.md` §Infotainment controls)을 시험한다.
/// 실행 순서, 재전송 없음, `Uncertain` 표시, 늦은 결과 버리기, 오류 종류 이름, 값이 줄에 들어가지 않는 것.
/// Android 의 `InfotainmentControlTest` 와 같은 경우를 같은 문구로 본다.
final class InfotainmentControlTests: XCTestCase {
    private func inputs(
        keyReady: Bool = true, connected: Bool = true, hasKey: Bool = true,
        enrollment: Enrollment = .enrolled, busy: Bool = false
    ) -> KeyControlInputs {
        KeyControlInputs(
            keyReady: keyReady, keyLocked: false, keyCorrupted: false, vinSaved: true, connected: connected, hasKey: hasKey,
            enrollment: enrollment, busy: busy
        )
    }

    private func card(_ sleep: String) -> StatusCard {
        StatusCard(lock: "LOCKED", trunk: "CLOSED", frunk: "CLOSED", sleep: sleep, at: Date(timeIntervalSince1970: 0))
    }

    // MARK: - 버튼 활성 규칙: 4단계와 같다

    func testButtonsAreEnabledOnlyWithKeyConnectionEnrollmentAndNoBusyCall() {
        XCTAssertTrue(InfotainmentControl.enabled(inputs()))
        XCTAssertFalse(InfotainmentControl.enabled(inputs(connected: false)), "no connection")
        XCTAssertFalse(InfotainmentControl.enabled(inputs(hasKey: false)), "connected without a key")
        XCTAssertFalse(InfotainmentControl.enabled(inputs(keyReady: false)), "key not ready")
        XCTAssertFalse(InfotainmentControl.enabled(inputs(enrollment: .notEnrolled)))
        XCTAssertFalse(InfotainmentControl.enabled(inputs(enrollment: .unknown)))
        XCTAssertFalse(InfotainmentControl.enabled(inputs(enrollment: .checking)))
        XCTAssertFalse(InfotainmentControl.enabled(inputs(busy: true)), "another call runs")
    }

    func testEnablingIsExactlyTheStepFourRule() {
        let enrollments: [Enrollment] = [.unknown, .checking, .notEnrolled, .enrolled]
        for ready in [true, false] {
            for connected in [true, false] {
                for hasKey in [true, false] {
                    for e in enrollments {
                        for busy in [true, false] {
                            let i = inputs(keyReady: ready, connected: connected, hasKey: hasKey, enrollment: e, busy: busy)
                            XCTAssertEqual(KeyControlSteps(i).control.buttonEnabled, InfotainmentControl.enabled(i), "\(i)")
                            XCTAssertEqual(KeyControlSteps.controlBlockReason(i), InfotainmentControl.blockedReason(i), "\(i)")
                            XCTAssertEqual(InfotainmentControl.blockedReason(i) == nil, InfotainmentControl.enabled(i), "\(i)")
                        }
                    }
                }
            }
        }
    }

    func testNoSessionStateIsNeededToEnableTheButtons() {
        // 세션은 탭이 시작한다: 입력에는 세션 항목이 없고, 등록된 키로 연결돼 있으면 바로 켜진다.
        XCTAssertNil(InfotainmentControl.blockedReason(inputs()))
    }

    // MARK: - 버튼 표

    func testButtonTableMatchesTheSpecAndTheAndroidLabels() {
        let labels = Dictionary(uniqueKeysWithValues: InfoCommand.allCases.map { ($0, $0.label) })
        XCTAssertEqual(labels, [
            .climateOn: "공조 켜기",
            .climateOff: "공조 끄기",
            .readClimate: "공조 상태 읽기",
            .chargeStart: "충전 시작",
            .chargeStop: "충전 중지",
            .readCharge: "충전 상태 읽기",
        ])
        XCTAssertEqual(Set(InfoCommand.allCases.filter(\.isRead)), [.readClimate, .readCharge])
        XCTAssertEqual(InfoCommand.climateOff.category, .climate)
        XCTAssertEqual(InfoCommand.chargeStop.category, .charge)
        XCTAssertEqual(Set(InfoCommand.allCases.map(\.label)).count, InfoCommand.allCases.count)
        XCTAssertEqual(InfoCategory.climate.title, "공조")
        XCTAssertEqual(InfoCategory.charge.readLabel, "충전 상태 읽기")
        XCTAssertEqual(InfoCategory.climate.unconfirmedText, "공조 결과 미확인")
        XCTAssertEqual(InfoCategory.charge.unconfirmedText, "충전 결과 미확인")
        XCTAssertEqual(InfotainmentControl.sectionTitle, "Infotainment (공조·충전)")
    }

    // MARK: - 한 번 누르기의 순서

    /// 호출 순서와 횟수를 적는 가짜.
    private final class Fakes: @unchecked Sendable {
        let status: InfoStatusRead
        let session: InfoSessionResult
        let call: InfoCallResult<InfoNoValue>
        let log = Locked<[String]>([])
        let clock = Locked<TimeInterval>(0)

        init(status: InfoStatusRead, session: InfoSessionResult = .ready, call: InfoCallResult<InfoNoValue> = .success(InfoNoValue())) {
            self.status = status
            self.session = session
            self.call = call
        }

        var calls: [String] { log.value }

        func run() async -> InfoOutcome<InfoNoValue> {
            await InfotainmentRunner.run(
                readStatus: { self.log.mutate { $0.append("status") }; return self.status },
                ensureSession: { self.log.mutate { $0.append("session") }; return self.session },
                call: { self.log.mutate { $0.append("call") }; return self.call },
                now: { self.clock.value }
            )
        }
    }

    private var awake: InfoStatusRead { .success(card("AWAKE")) }

    func testAwakeCarRunsStatusThenSessionThenOneCallAndNothingElse() async {
        let f = Fakes(status: awake)

        let out = await f.run()

        XCTAssertEqual(f.calls, ["status", "session", "call"])
        XCTAssertEqual(out.kind, .success)
        XCTAssertEqual(out.status, card("AWAKE"))
    }

    func testASleepingCarSendsNothingStartsNoSessionAndNeverWakes() async {
        let f = Fakes(status: .success(card("ASLEEP")))

        let out = await f.run()

        XCTAssertEqual(f.calls, ["status"])
        XCTAssertEqual(out.kind, .notSent)
        XCTAssertEqual(out.notSent, .asleep)
        XCTAssertEqual(out.status, card("ASLEEP"), "a successful read still refreshes the status card")
    }

    func testUnknownSleepIsNotSentEither() async {
        let f = Fakes(status: .success(card("UNKNOWN")))

        let out = await f.run()

        XCTAssertEqual(f.calls, ["status"])
        XCTAssertEqual(out.notSent, .sleepUnknown)
    }

    func testAFailedOrUncertainStatusReadIsNotSentAndNamesTheErrorType() async {
        let failed = Fakes(status: .failed(errorType: "NotConnected"))
        let uncertain = Fakes(status: .failed(errorType: InfotainmentErrors.timeout(afterSend: true)))

        let a = await failed.run()
        let b = await uncertain.run()

        XCTAssertEqual(failed.calls, ["status"])
        XCTAssertEqual(uncertain.calls, ["status"])
        XCTAssertEqual(a.notSent, .sleepReadFailed(errorType: "NotConnected"))
        XCTAssertEqual(b.notSent, .sleepReadFailed(errorType: "Timeout(afterSend=true)"))
        XCTAssertNil(a.status)
    }

    func testAFailedSessionSendsNoCommand() async {
        let f = Fakes(status: awake, session: .failed(errorType: "NoSession"))

        let out = await f.run()

        XCTAssertEqual(f.calls, ["status", "session"])
        XCTAssertEqual(out.kind, .notSent)
        XCTAssertEqual(out.notSent, .sessionFailed(errorType: "NoSession"))
    }

    func testAnUncertainSessionIsAlsoNotSentBecauseNothingWasSentYet() async {
        let f = Fakes(status: awake, session: .failed(errorType: "Timeout(afterSend=true)"))

        let out = await f.run()

        XCTAssertEqual(f.calls, ["status", "session"])
        XCTAssertEqual(out.notSent, .sessionFailed(errorType: "Timeout(afterSend=true)"))
    }

    func testKeyNotPairedFromTheSessionMeansNotEnrolledAndNothingIsSent() async {
        let f = Fakes(status: awake, session: .failed(errorType: "KeyNotPaired"))

        let out = await f.run()

        XCTAssertEqual(f.calls, ["status", "session"])
        XCTAssertEqual(out.enrollment, .notEnrolled)
    }

    func testKeyNotPairedFromTheCommandAlsoMeansNotEnrolled() async {
        let f = Fakes(status: awake, call: .failure(errorType: "KeyNotPaired"))

        let out = await f.run()

        XCTAssertEqual(out.kind, .failure)
        XCTAssertEqual(out.enrollment, .notEnrolled)
    }

    func testLockedKeyStoreInTheSessionSaysToUnlockThePhone() async {
        let f = Fakes(status: awake, session: .failed(errorType: "Locked"))

        let line = InfotainmentRunner.resultLine(.climateOn, await f.run())

        XCTAssertTrue(line.contains("잠금 해제"), line)
    }

    func testUncertainIsNeverResentAndNoGetStateRunsByItself() async {
        let f = Fakes(status: awake, call: .uncertain(errorType: "Timeout(afterSend=true)"))

        let out = await f.run()

        XCTAssertEqual(f.calls, ["status", "session", "call"], "exactly one call, no re-query")
        XCTAssertEqual(out.kind, .uncertain)
        XCTAssertEqual(out.errorType, "Timeout(afterSend=true)")
    }

    func testARejectionIsAFailureWithoutTheVehicleText() async {
        // 차량이 보낸 글은 러너로 오지 않는다: 오류 종류 이름만 받는다.
        let f = Fakes(status: awake, call: .failure(errorType: "InfotainmentRejected"))

        let out = await f.run()
        let line = InfotainmentRunner.resultLine(.chargeStart, out)

        XCTAssertEqual(out.kind, .failure)
        XCTAssertEqual(out.errorType, "InfotainmentRejected")
        XCTAssertTrue(line.contains("InfotainmentRejected"), line)
        XCTAssertTrue(line.contains("차량이 거부했습니다"), line)
    }

    func testAReadReturnsTheValueOnSuccess() async {
        let values = InfoValues.climate(InfoClimateValues(isClimateOn: true))
        let awakeCard = card("AWAKE")

        let out: InfoOutcome<InfoValues> = await InfotainmentRunner.run(
            readStatus: { .success(awakeCard) },
            ensureSession: { .ready },
            call: { .success(values) }
        )

        XCTAssertEqual(out.kind, .success)
        XCTAssertEqual(out.value, values)
    }

    func testDurationCoversTheWholeTap() async {
        let clock = Locked<TimeInterval>(0)
        let awakeCard = card("AWAKE")

        let out: InfoOutcome<InfoNoValue> = await InfotainmentRunner.run(
            readStatus: { clock.value += 0.1; return .success(awakeCard) },
            ensureSession: { clock.value += 0.25; return .ready },
            call: { clock.value += 1.0; return .success(InfoNoValue()) },
            now: { clock.value }
        )

        XCTAssertEqual(out.durationMs, 1350)
    }

    // MARK: - 결과 줄

    private func outcome(
        _ kind: InfoKind, type: String? = nil, notSent: InfoNotSentReason? = nil, ms: Int = 42
    ) -> InfoOutcome<InfoNoValue> {
        InfoOutcome(kind, errorType: type, notSent: notSent, durationMs: ms)
    }

    func testSuccessLineHasLabelOutcomeAndDurationOnly() {
        XCTAssertEqual(InfotainmentRunner.resultLine(.climateOn, outcome(.success)), "공조 켜기: 성공 42 ms")
    }

    func testResultTextIsTheLineWithoutTheLabel() {
        XCTAssertEqual(InfotainmentRunner.resultText(.climateOn, outcome(.success)), "성공 42 ms")
    }

    func testUncertainLineShowsTheTypeSaysNotResentAndPointsToTheRead() {
        let line = InfotainmentRunner.resultLine(.chargeStop, outcome(.uncertain, type: "Timeout(afterSend=true)", ms: 5012))

        XCTAssertEqual(line.components(separatedBy: "\n").first, "충전 중지: 불확실 (Timeout(afterSend=true)) 5012 ms")
        XCTAssertTrue(line.contains("다시 보내지 않습니다"), line)
        XCTAssertTrue(line.contains("충전 상태 읽기"), line)
    }

    func testClimateUncertainPointsToTheClimateRead() {
        let line = InfotainmentRunner.resultLine(.climateOff, outcome(.uncertain, type: "Timeout(afterSend=true)"))

        XCTAssertTrue(line.contains("공조 상태 읽기"), line)
    }

    func testUncertainReadSaysTheValueIsUnconfirmedAndAskingAgainIsSafe() {
        let line = InfotainmentRunner.resultLine(.readClimate, outcome(.uncertain, type: "ResponseTooLarge"))

        XCTAssertTrue(line.hasPrefix("공조 상태 읽기: 불확실 (ResponseTooLarge)"), line)
        XCTAssertFalse(line.contains("다시 보내지 않습니다"), "a read changes nothing, the command wording does not apply")
    }

    func testNotSentLineExplainsAsleepWithoutWaking() {
        let line = InfotainmentRunner.resultLine(.climateOn, outcome(.notSent, notSent: .asleep))

        XCTAssertTrue(line.hasPrefix("공조 켜기: 보내지 않음"), line)
        XCTAssertTrue(line.contains("깨우기"), line)
        XCTAssertTrue(line.contains("자동으로 깨우지 않"), line)
    }

    func testFailureNotesNameOnlyFixedSentences() {
        XCTAssertTrue(InfotainmentRunner.resultLine(.climateOn, outcome(.failure, type: "NoSession")).contains("세션이 없어"))
        XCTAssertTrue(InfotainmentRunner.resultLine(.climateOn, outcome(.failure, type: "KeyNotPaired")).contains("등록되어 있지 않습니다"))
        XCTAssertEqual(
            InfotainmentRunner.resultLine(.climateOn, outcome(.failure, type: "NotConnected")),
            "공조 켜기: 실패 (NotConnected) 42 ms"
        )
    }

    func testNoLineCarriesAFieldValueOrAnErrorMessage() {
        let kinds: [InfoKind] = [.success, .uncertain, .failure, .notSent]
        let forbidden = ["message", "is_climate_on", "battery_level", "charger_power", "reason"]
        for cmd in InfoCommand.allCases {
            for kind in kinds {
                let line = InfotainmentRunner.resultLine(
                    cmd, outcome(kind, type: "Timeout(afterSend=true)", notSent: .sessionFailed(errorType: "NoSession"))
                )
                for word in forbidden { XCTAssertFalse(line.contains(word), "\(word) in \(line)") }
            }
        }
    }

    // MARK: - 읽은 값(화면에만, 줄에는 가지 않는다)

    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    func testClimateReadoutShowsOnlyTheNamedFieldsAndAbsentIsADash() {
        let r = InfoReading.of(.climate(InfoClimateValues(isClimateOn: true, insideTempCelsius: 21.5)), at: t0, previous: nil)

        XCTAssertEqual(r.fields, [
            InfoField(label: "is_climate_on", value: "true"),
            InfoField(label: "inside_temp_celsius", value: "21.5"),
            InfoField(label: "driver_temp_setting", value: "—"),
        ])
        XCTAssertNil(r.timestampSeconds)
        XCTAssertEqual(r.timestampText, "—")
        XCTAssertFalse(r.sameTimestampAsPrevious)
    }

    func testChargeReadoutKeepsZeroAsZero() {
        let r = InfoReading.of(
            .charge(InfoChargeValues(batteryLevel: 0, chargerPower: 0, chargePortDoorOpen: false)), at: t0, previous: nil
        )

        XCTAssertEqual(r.fields, [
            InfoField(label: "battery_level", value: "0"),
            InfoField(label: "charge_limit_soc", value: "—"),
            InfoField(label: "charger_power", value: "0"),
            InfoField(label: "charge_port_door_open", value: "false"),
        ])
    }

    func testAnEmptyReplyGivesAllDashes() {
        let r = InfoReading.of(.empty(.charge), at: t0, previous: nil)

        XCTAssertTrue(r.fields.allSatisfy { $0.value == "—" })
    }

    func testAnUnchangedTimestampIsFlaggedAsNotNecessarilyNew() {
        let same = InfoValues.climate(InfoClimateValues(timestamp: 1_000))
        let first = InfoReading.of(same, at: t0, previous: nil)

        let second = InfoReading.of(same, at: t0.addingTimeInterval(30), previous: first)
        let moved = InfoReading.of(.climate(InfoClimateValues(timestamp: 1_005)), at: t0.addingTimeInterval(60), previous: second)

        XCTAssertEqual(first.timestampSeconds, 1_000)
        XCTAssertFalse(first.sameTimestampAsPrevious)
        XCTAssertTrue(second.sameTimestampAsPrevious)
        XCTAssertFalse(moved.sameTimestampAsPrevious)
    }

    func testAMissingTimestampIsNeverCalledUnchanged() {
        let first = InfoReading.of(.empty(.climate), at: t0, previous: nil)
        let second = InfoReading.of(.empty(.climate), at: t0, previous: first)

        XCTAssertFalse(second.sameTimestampAsPrevious)
    }

    // MARK: - 미확인 표시와 늦은 결과

    private var uncertainOutcome: InfoOutcome<InfoNoValue> { outcome(.uncertain, type: "Timeout(afterSend=true)") }
    private var okOutcome: InfoOutcome<InfoNoValue> { outcome(.success) }

    func testAnUncertainCommandMarksOnlyItsCategoryUnconfirmed() {
        let s = InfotainmentReducer.afterCommand(InfoUiState(), .climateOn, uncertainOutcome, current: true)

        XCTAssertEqual(s.unconfirmed, [.climate])
    }

    func testALaterSuccessOfTheSameCategoryClearsTheMarker() {
        let marked = InfotainmentReducer.afterCommand(InfoUiState(), .chargeStart, uncertainOutcome, current: true)

        let cleared = InfotainmentReducer.afterCommand(marked, .chargeStop, okOutcome, current: true)
        let other = InfotainmentReducer.afterCommand(marked, .climateOn, okOutcome, current: true)

        XCTAssertEqual(cleared.unconfirmed, [])
        XCTAssertEqual(other.unconfirmed, [.charge], "another category does not settle it")
    }

    func testOtherFailuresAndNotSentNeitherSetNorClearTheMarker() {
        let marked = InfotainmentReducer.afterCommand(InfoUiState(), .climateOn, uncertainOutcome, current: true)

        let failed = InfotainmentReducer.afterCommand(marked, .climateOff, outcome(.failure, type: "InfotainmentRejected"), current: true)
        let notSent = InfotainmentReducer.afterCommand(marked, .climateOff, outcome(.notSent), current: true)
        let fresh = InfotainmentReducer.afterCommand(InfoUiState(), .climateOn, outcome(.failure, type: "NoSession"), current: true)

        XCTAssertEqual(failed.unconfirmed, marked.unconfirmed)
        XCTAssertEqual(notSent.unconfirmed, marked.unconfirmed)
        XCTAssertEqual(fresh.unconfirmed, [])
    }

    private func readOutcome(_ kind: InfoKind, _ values: InfoValues? = nil) -> InfoOutcome<InfoValues> {
        InfoOutcome(kind, value: values, errorType: kind == .success ? nil : "Timeout(afterSend=true)", durationMs: 7)
    }

    func testASuccessfulReadStoresTheReadoutAndClearsTheMarker() {
        let marked = InfotainmentReducer.afterCommand(InfoUiState(), .climateOn, uncertainOutcome, current: true)
        let values = InfoValues.climate(InfoClimateValues(isClimateOn: true))

        let s = InfotainmentReducer.afterRead(marked, .climate, readOutcome(.success, values), current: true, at: t0)

        XCTAssertEqual(s.unconfirmed, [])
        XCTAssertEqual(s.readings[.climate]?.fields.first { $0.label == "is_climate_on" }?.value, "true")
    }

    func testAnUncertainReadKeepsTheMarkerAndTheLastReadout() {
        let values = InfoValues.climate(InfoClimateValues(isClimateOn: true))
        let base = InfotainmentReducer.afterRead(InfoUiState(), .climate, readOutcome(.success, values), current: true, at: t0)
        let marked = InfotainmentReducer.afterCommand(base, .climateOff, uncertainOutcome, current: true)

        let s = InfotainmentReducer.afterRead(marked, .climate, readOutcome(.uncertain), current: true, at: t0)

        XCTAssertEqual(s.unconfirmed, [.climate])
        XCTAssertEqual(s.readings, base.readings, "last value stays")
    }

    func testAFailedReadKeepsTheLastReadout() {
        let values = InfoValues.charge(InfoChargeValues(batteryLevel: 50))
        let base = InfotainmentReducer.afterRead(InfoUiState(), .charge, readOutcome(.success, values), current: true, at: t0)

        let s = InfotainmentReducer.afterRead(base, .charge, readOutcome(.failure), current: true, at: t0)

        XCTAssertEqual(s, base)
    }

    func testASecondReadRemembersThePreviousTimestamp() {
        let values = InfoValues.charge(InfoChargeValues(timestamp: 2_000))
        let one = InfotainmentReducer.afterRead(InfoUiState(), .charge, readOutcome(.success, values), current: true, at: t0)

        let two = InfotainmentReducer.afterRead(one, .charge, readOutcome(.success, values), current: true, at: t0.addingTimeInterval(10))

        XCTAssertEqual(two.readings[.charge]?.sameTimestampAsPrevious, true)
    }

    func testAResultFromAnOldConnectionIsDroppedCompletely() {
        let marked = InfotainmentReducer.afterCommand(InfoUiState(), .climateOn, uncertainOutcome, current: true)
        let values = InfoValues.charge(InfoChargeValues(batteryLevel: 50))

        let lateCommand = InfotainmentReducer.afterCommand(InfoUiState(), .chargeStart, uncertainOutcome, current: false)
        let lateSuccess = InfotainmentReducer.afterCommand(marked, .climateOff, okOutcome, current: false)
        let lateRead = InfotainmentReducer.afterRead(InfoUiState(), .charge, readOutcome(.success, values), current: false, at: t0)

        XCTAssertEqual(lateCommand, InfoUiState(), "no marker from a dropped Uncertain")
        XCTAssertEqual(lateSuccess, marked, "a dropped success does not clear a marker")
        XCTAssertEqual(lateRead, InfoUiState(), "no readout from a dropped read")
    }

    func testDroppedLineNamesTheCommandOnlyAndSaysToReadAgainForACommand() {
        let command = InfotainmentRunner.droppedLine(.chargeStart)
        let read = InfotainmentRunner.droppedLine(.readCharge)

        XCTAssertTrue(command.hasPrefix("충전 시작: 연결이 바뀌어 결과를 버렸습니다"), command)
        XCTAssertTrue(command.contains("차량이 실행했을 수 있"), command)
        XCTAssertTrue(read.hasPrefix("충전 상태 읽기: 연결이 바뀌어 결과를 버렸습니다"), read)
        XCTAssertFalse(read.contains("실행했을 수"), read)
    }

    func testAConnectionChangeStartsFromAnEmptyState() {
        let s = InfotainmentReducer.afterCommand(InfoUiState(), .climateOn, uncertainOutcome, current: true)

        XCTAssertEqual(InfoUiState.afterConnectionChange, InfoUiState())
        XCTAssertFalse(s.unconfirmed.isEmpty)
    }
}
