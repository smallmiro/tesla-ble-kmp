import XCTest
@testable import TeslableSample

/// 손으로 움직이는 시계(파일 이름의 초가 겹치지 않게 한다). 2026-10-24T01:00:00.123Z = 10:00:00.123+09:00.
private final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var instant = Date(timeIntervalSince1970: 1_792_803_600.123)

    var now: Date {
        get { lock.lock(); defer { lock.unlock() }; return instant }
        set { lock.lock(); defer { lock.unlock() }; instant = newValue }
    }

    func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds) }
}

/// 두 번째 쓰기부터 실패하는 출력(첫 쓰기는 `session` 줄).
private final class FailingAfterFirstWrite: LogOutput, @unchecked Sendable {
    private let real: LogOutput
    private var writes = 0

    init(_ real: LogOutput) { self.real = real }

    func write(_ data: Data) throws {
        writes += 1
        if writes > 1 { throw NSError(domain: "test", code: 5, userInfo: [NSLocalizedDescriptionKey: "I/O error"]) }
        try real.write(data)
    }

    func close() { real.close() }
}

final class LogWriterTests: XCTestCase {
    // 실제 VIN이 아닌 가짜 값(형식만 같다)
    private let vin = "5YJS0000000000000"
    private let session = SessionInfo(platform: "ios", app: "0.2.0(10)", os: "27.0.1", phone: "iPhone")
    private let tz = TimeZone(secondsFromGMT: 9 * 3600)!
    private var dirs: [URL] = []

    override func tearDown() {
        dirs.forEach { try? FileManager.default.removeItem(at: $0) }
        dirs = []
        super.tearDown()
    }

    private func tempDir() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("monitor-logs-\(UUID().uuidString)")
        dirs.append(url)
        return url
    }

    private func files(_ dir: URL) -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return all.filter { $0.pathExtension == "jsonl" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private func size(_ url: URL) -> Int {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int) ?? 0
    }

    private func rawLines(_ url: URL) -> [String] {
        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        return text.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
    }

    private func lines(_ url: URL) throws -> [[String: Any]] {
        try rawLines(url).map { try XCTUnwrap(JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any]) }
    }

    private func memos(_ urls: [URL]) throws -> [String] {
        try urls.flatMap { try lines($0) }.filter { $0["kind"] as? String == "mark" }.compactMap { $0["memo"] as? String }
    }

    private func writer(
        _ dir: URL,
        clock: TestClock = TestClock(),
        maxFile: Int = LogWriter.defaultMaxFileBytes,
        maxTotal: Int = LogWriter.defaultMaxTotalBytes,
        capacity: Int = 1024,
        queue: DispatchQueue = DispatchQueue(label: "test.logwriter"),
        open: @escaping @Sendable (URL) throws -> LogOutput = { try FileLogOutput(url: $0) }
    ) -> LogWriter {
        LogWriter(
            directory: dir, session: session, clock: { clock.now }, timeZone: tz, maxFileBytes: maxFile, maxTotalBytes: maxTotal,
            queueCapacity: capacity, queue: queue, open: open
        )
    }

    private func status(unknown: [String] = [], raw: [UInt8] = [1, 2, 3]) -> MonitorEvent {
        MonitorEvent(kind: .status(StatusSnapshot()), raw: raw, unknownFields: unknown)
    }

    private func mark(_ w: LogWriter, _ memo: String) -> LogLine { LogLine.mark(ts: w.stamp(), memo: memo)! }

    func testDefaultsAre20MbPerFileAnd200MbInTotal() {
        XCTAssertEqual(LogWriter.defaultMaxFileBytes, 20_000_000)
        XCTAssertEqual(LogWriter.defaultMaxTotalBytes, 200_000_000)
    }

    func testFirstLineOfTheFileIsTheSessionLine() async throws {
        let dir = tempDir()
        let w = writer(dir)
        w.write(mark(w, "hello"))
        await w.flush()

        let file = try XCTUnwrap(files(dir).first)
        XCTAssertEqual(files(dir).count, 1)
        XCTAssertNotNil(file.lastPathComponent.range(of: #"^monitor-\d{8}-\d{6}\.jsonl$"#, options: .regularExpression), file.lastPathComponent)
        let first = try XCTUnwrap(lines(file).first)
        XCTAssertEqual(first["kind"] as? String, "session")
        XCTAssertEqual(first["platform"] as? String, "ios")
        XCTAssertEqual(first["app"] as? String, "0.2.0(10)")
        XCTAssertEqual(first["os"] as? String, "27.0.1")
        XCTAssertEqual(first["phone"] as? String, "iPhone")
        XCTAssertEqual(first["ts"] as? String, "2026-10-24T10:00:00.123+09:00")
        XCTAssertEqual(file.lastPathComponent, "monitor-20261024-100000.jsonl")
    }

    func testEventLineMatchesSchema() async throws {
        let dir = tempDir()
        let w = writer(dir)
        let raw: [UInt8] = [0x0a, 0x11] + Array(vin.utf8) + [0x7f]
        w.write(LogLine.event(ts: w.stamp(), status(unknown: ["1.8"], raw: raw)))
        await w.flush()

        let file = try XCTUnwrap(files(dir).first)
        let o = try lines(file)[1]
        XCTAssertEqual(Set(o.keys), ["ts", "kind", "type", "summary", "unknownFields", "unrecognized", "raw"])
        // 필드 순서: ts, kind, type, summary … (JSON 파서는 순서를 보존하지 않으므로 원문으로 확인한다)
        let text = rawLines(file)[1]
        XCTAssertTrue(text.hasPrefix("{\"ts\":\""), text)
        XCTAssertTrue(text.contains("\",\"kind\":\"event\",\"type\":\"Status\",\"summary\":\""), text)
        XCTAssertTrue(text.contains("\"unknownFields\":[\"1.8\"],\"unrecognized\":true,\"raw\":\""), text)
        XCTAssertEqual(o["kind"] as? String, "event")
        XCTAssertEqual(o["type"] as? String, "Status")
        XCTAssertTrue((o["summary"] as? String ?? "").hasPrefix("lock=LOCKED gear=PARK presence=NOT_PRESENT"), "\(o["summary"] ?? "")")
        XCTAssertEqual(o["unknownFields"] as? [String], ["1.8"])
        XCTAssertEqual(o["unrecognized"] as? Bool, true)
        // raw: 소문자 16진수, VIN은 같은 길이로 가려진다
        let expected = ([0x0a, 0x11] + Array("5YJ**********0000".utf8) + [0x7f] as [UInt8]).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(o["raw"] as? String, expected)
        XCTAssertFalse(try String(contentsOf: file, encoding: .utf8).contains(vin))
    }

    func testStatusSummaryHasAllItemsInOrder() {
        let s = StatusSnapshot(
            lock: "UNLOCKED", gear: "DRIVE", presence: "PRESENT", sleep: "AWAKE",
            frontDriverDoor: "OPEN", frontPassengerDoor: "CLOSED", rearDriverDoor: "CLOSED", rearPassengerDoor: "OPEN",
            frunk: "CLOSED", trunk: "OPEN", chargePort: "CLOSED"
        )
        XCTAssertEqual(
            LogLine.statusSummary(s),
            "lock=UNLOCKED gear=DRIVE presence=PRESENT sleep=AWAKE doors=OPEN/CLOSED/CLOSED/OPEN frunk=CLOSED trunk=OPEN chargePort=CLOSED"
        )
    }

    func testOtherEventSummaries() throws {
        let auth = MonitorEvent(kind: .authenticationRequest(level: "UNLOCK", reasons: ["WALK_UP_UNLOCK", "UNKNOWN"]), raw: [1], unknownFields: [])
        let unrecognized = MonitorEvent(kind: .unrecognized, raw: [2], unknownFields: ["53", "48"])
        let a = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(LogLine.event(ts: "t", auth).toJSON().utf8)) as? [String: Any])
        let u = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(LogLine.event(ts: "t", unrecognized).toJSON().utf8)) as? [String: Any])
        XCTAssertEqual(a["type"] as? String, "AuthenticationRequest")
        XCTAssertEqual(a["summary"] as? String, "level=UNLOCK reasons=[WALK_UP_UNLOCK, UNKNOWN]")
        XCTAssertEqual(u["type"] as? String, "Unrecognized")
        XCTAssertEqual(u["summary"] as? String, "unknownFields=[53, 48]")
    }

    func testOtherKindsMatchSchema() async throws {
        let dir = tempDir()
        let w = writer(dir)
        let ts = w.stamp()
        w.write(LogLine.observe(ts: ts, text: "[drop:no-handler] 83B from=domain(X) vin=\(vin)"))
        w.write(LogLine.query(ts: ts, call: "vehicleStatus", result: "Success", summary: "lock=LOCKED"))
        w.write(LogLine.link(ts: ts, state: "backoff", reason: "link lost", attempt: 3, delayMs: 8000))
        w.write(LogLine.link(ts: ts, state: "stopped"))
        await w.flush()

        let all = try lines(try XCTUnwrap(files(dir).first))
        XCTAssertEqual(Set(all[1].keys), ["ts", "kind", "line"])
        XCTAssertEqual(all[1]["kind"] as? String, "observe")
        XCTAssertEqual(all[1]["line"] as? String, "[drop:no-handler] 83B from=domain(X) vin=5YJ**********0000")
        XCTAssertEqual(Set(all[2].keys), ["ts", "kind", "call", "result", "summary"])
        XCTAssertEqual(Set(all[3].keys), ["ts", "kind", "state", "reason", "attempt", "delayMs"])
        XCTAssertEqual(all[3]["delayMs"] as? Int, 8000)
        XCTAssertEqual(all[3]["attempt"] as? Int, 3)
        XCTAssertEqual(Set(all[4].keys), ["ts", "kind", "state"])
        XCTAssertEqual(all[4]["state"] as? String, "stopped")
    }

    func testUnrecognizedFlag() async throws {
        let dir = tempDir()
        let w = writer(dir)
        let ts = w.stamp()
        w.write(LogLine.event(ts: ts, status()))
        w.write(LogLine.event(ts: ts, status(unknown: ["1.8"])))
        w.write(LogLine.event(ts: ts, MonitorEvent(kind: .unrecognized, raw: [9], unknownFields: [])))
        w.write(LogLine.event(ts: ts, MonitorEvent(kind: .unrecognized, raw: [9], unknownFields: ["53"])))
        await w.flush()

        let events = try lines(try XCTUnwrap(files(dir).first)).filter { $0["kind"] as? String == "event" }
        XCTAssertEqual(events.map { $0["unrecognized"] as? Bool }, [false, true, true, true])
        XCTAssertEqual(events.map { $0["type"] as? String }, ["Status", "Status", "Unrecognized", "Unrecognized"])
        XCTAssertEqual(w.stats.unrecognized, 3)
        XCTAssertEqual(w.stats.byKind["event"], 4)
    }

    func testMarkMemoTruncatedTo80AndMasked() throws {
        let long = String(repeating: "m", count: 70) + vin
        let line = try XCTUnwrap(LogLine.mark(ts: "2026-10-24T10:00:00.000+09:00", memo: long))
        let memo = try XCTUnwrap((JSONSerialization.jsonObject(with: Data(line.toJSON().utf8)) as? [String: Any])?["memo"] as? String)

        XCTAssertEqual(memo.count, 80)
        XCTAssertEqual(memo, String(repeating: "m", count: 70) + "5YJ*******")
        XCTAssertFalse(line.toJSON().contains(vin))
    }

    func testMarkWithEmptyMemoIsIgnoredAndShortMemoIsKept() throws {
        XCTAssertNil(LogLine.mark(ts: "t", memo: ""))
        XCTAssertNil(LogLine.mark(ts: "t", memo: "   \n"))
        let line = try XCTUnwrap(LogLine.mark(ts: "t", memo: " before: open driver door "))
        let o = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(line.toJSON().utf8)) as? [String: Any])
        XCTAssertEqual(o["memo"] as? String, "before: open driver door")
    }

    func testJsonEscapesQuotesAndControlCharacters() throws {
        let line = try XCTUnwrap(LogLine.mark(ts: "t", memo: "a\"b\\c\nd\u{01}"))
        let o = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(line.toJSON().utf8)) as? [String: Any])
        XCTAssertEqual(o["memo"] as? String, "a\"b\\c\nd\u{01}")
        XCTAssertFalse(line.toJSON().contains("\n"))
    }

    func testRotatesAtTheFileLimit() async throws {
        let dir = tempDir()
        let clock = TestClock()
        // 20 MB 대신 400바이트로 줄여서 같은 경로를 시험한다
        let w = writer(dir, clock: clock, maxFile: 400)
        for i in 0..<30 {
            clock.advance(1)
            w.write(mark(w, "line \(i)"))
        }
        await w.flush()

        let all = files(dir)
        XCTAssertGreaterThan(all.count, 2, "files=\(all.count)")
        for f in all {
            XCTAssertLessThanOrEqual(size(f), 400, f.lastPathComponent)
            XCTAssertEqual(try lines(f).first?["kind"] as? String, "session")
        }
        // 줄은 하나도 잃지 않고, 파일 이름 순서 = 시간 순서
        XCTAssertEqual(try memos(all), (0..<30).map { "line \($0)" })
        XCTAssertEqual(all.map(\.lastPathComponent), all.map(\.lastPathComponent).sorted())
        XCTAssertEqual(w.stats.currentFile, all.last?.path)
    }

    func testPrunesAtTheTotalLimit() async throws {
        let dir = tempDir()
        let clock = TestClock()
        // 200 MB 대신 1500바이트로 줄여서 같은 경로를 시험한다
        let w = writer(dir, clock: clock, maxFile: 400, maxTotal: 1_500)
        for i in 0..<80 {
            clock.advance(1)
            w.write(mark(w, "line \(i)"))
            await w.flush()
            XCTAssertLessThanOrEqual(files(dir).map(size).reduce(0, +), 1_500, "after line \(i)")
        }

        XCTAssertGreaterThanOrEqual(files(dir).count, 2)
        let last = try memos(files(dir))
        XCTAssertEqual(last.last, "line 79")
        XCTAssertFalse(last.contains("line 0"), "the oldest file should be gone")
        XCTAssertEqual(last, last.sorted { Int($0.dropFirst(5))! < Int($1.dropFirst(5))! })
    }

    func testNewFileStartsAFreshFileWithASessionLine() async throws {
        let dir = tempDir()
        let w = writer(dir)
        w.write(mark(w, "one"))
        w.newFile()
        w.write(mark(w, "two"))
        await w.flush()

        let all = files(dir)
        XCTAssertEqual(all.count, 2)
        XCTAssertEqual(try lines(all[0]).map { $0["kind"] as? String }, ["session", "mark"])
        XCTAssertEqual(try lines(all[1]).map { $0["kind"] as? String }, ["session", "mark"])
        XCTAssertEqual(try lines(all[1])[1]["memo"] as? String, "two")
    }

    func testNewFileNameStaysAfterTheNewestFileWhenTheClockGoesBack() async throws {
        let dir = tempDir()
        let clock = TestClock()
        let w = writer(dir, clock: clock)
        w.write(mark(w, "one"))
        await w.flush()
        clock.advance(-3600) // 서머타임 끝이나 시계 조정으로 시계가 뒤로 감
        w.newFile()
        w.write(mark(w, "two"))
        await w.flush()

        let names = files(dir).map(\.lastPathComponent)
        XCTAssertEqual(names.count, 2)
        XCTAssertEqual(names, names.sorted())
        XCTAssertEqual(try lines(try XCTUnwrap(files(dir).last))[1]["memo"] as? String, "two")
    }

    func testNewFileIsNotLostWhenTheQueueIsFull() async throws {
        let dir = tempDir()
        let w = writer(dir, capacity: 1)
        w.write(mark(w, "one"))
        w.newFile() // 큐 한도와 상관없이 잃지 않는다
        await w.flush()

        XCTAssertEqual(files(dir).count, 2)
    }

    func testFailureToOpenTheFileDropsTheLineAndTheNextLineRetries() async throws {
        let dir = tempDir()
        let opens = Locked(0)
        let w = writer(dir, open: { url in
            var n = 0
            opens.mutate { $0 += 1; n = $0 }
            if n == 1 { throw NSError(domain: "test", code: 28, userInfo: [NSLocalizedDescriptionKey: "disk full"]) }
            return try FileLogOutput(url: url)
        })
        w.write(mark(w, "lost"))
        await w.flush() // 실패해도 flush는 돌아온다
        XCTAssertEqual(w.stats.dropped, 1)

        w.write(mark(w, "kept"))
        await w.flush()

        XCTAssertEqual(try memos(files(dir)), ["kept"])
        XCTAssertEqual(w.stats.dropped, 1)
    }

    func testFailureWhileWritingClosesTheFileCountsTheLineAndReopens() async throws {
        let dir = tempDir()
        let opens = Locked(0)
        let w = writer(dir, open: { url in
            var n = 0
            opens.mutate { $0 += 1; n = $0 }
            let real = try FileLogOutput(url: url)
            return n == 1 ? FailingAfterFirstWrite(real) : real
        })
        w.write(mark(w, "fails"))
        await w.flush()
        w.write(mark(w, "ok"))
        await w.flush()

        XCTAssertEqual(w.stats.dropped, 1)
        let last = try XCTUnwrap(files(dir).last)
        XCTAssertEqual(try lines(last).map { $0["kind"] as? String }, ["session", "mark"])
        XCTAssertEqual(try lines(last)[1]["memo"] as? String, "ok")
    }

    func testFullQueueDropsLinesInsteadOfBlocking() async throws {
        let dir = tempDir()
        let queue = DispatchQueue(label: "test.logwriter.inactive", attributes: .initiallyInactive)
        let w = writer(dir, capacity: 2, queue: queue)
        // 큐가 아직 돌지 않으므로 2줄까지만 받는다. write()는 멈추지 않는다.
        for i in 0..<10 { w.write(mark(w, "m\(i)")) }
        XCTAssertEqual(w.stats.dropped, 8)
        queue.activate()
        await w.flush()

        XCTAssertEqual(w.stats.dropped, 8)
        XCTAssertEqual(try memos(files(dir)), ["m0", "m1"])
    }

    func testStatsKeepTheLastLines() async throws {
        let dir = tempDir()
        let w = writer(dir)
        for i in 0..<(LogWriter.lastLineCount + 5) { w.write(mark(w, "m\(i)")) }
        await w.flush()

        let last = w.stats.lastLines
        XCTAssertEqual(last.count, LogWriter.lastLineCount)
        XCTAssertTrue(last.last?.contains("m\(LogWriter.lastLineCount + 4)") ?? false)
        XCTAssertEqual(w.stats.totalLines, LogWriter.lastLineCount + 5)
    }

    func testOnChangeReportsStats() async throws {
        let dir = tempDir()
        let seen = Locked<[Int]>([])
        let w = LogWriter(
            directory: dir, session: session, clock: { Date(timeIntervalSince1970: 1_792_803_600) }, timeZone: tz,
            onChange: { stats in seen.mutate { $0.append(stats.totalLines) } }
        )
        w.write(mark(w, "a"))
        w.write(mark(w, "b"))
        await w.flush()

        XCTAssertEqual(seen.value.last, 2)
    }
}
