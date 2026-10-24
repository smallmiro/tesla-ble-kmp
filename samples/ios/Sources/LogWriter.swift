import Foundation

/// 로그를 받는 곳(`04-monitor-app.md` §Log format). 지금은 파일 구현(`LogWriter`)뿐이다. 클라우드 싱크는 ADR 뒤에 추가한다.
protocol LogSink: Sendable {
    /// 줄에 찍을 현재 시각(ISO-8601, 오프셋 포함, 기기 시계).
    func stamp() -> String
    /// 줄을 넘긴다. 절대 멈추지 않는다(수집 쪽이 막히면 안 된다).
    func write(_ line: LogLine)
    /// 지금까지 넘긴 줄이 파일에 쓰일 때까지 기다린다.
    func flush() async
    /// 새 파일로 넘어간다(첫 줄은 `session`).
    func newFile()
}

/// 파일 한 개에 쓰는 출력. 시험에서 실패를 흉내 낼 때 바꿔 끼운다.
protocol LogOutput: AnyObject, Sendable {
    func write(_ data: Data) throws
    func close()
}

/// 이어 쓰기로 여는 파일 출력. 줄마다 바로 파일에 넘긴다(프로세스가 죽어도 마지막 줄까지 남는다).
final class FileLogOutput: LogOutput, @unchecked Sendable {
    private var handle: FileHandle?

    init(url: URL) throws {
        if !FileManager.default.fileExists(atPath: url.path) {
            guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown, userInfo: [NSFilePathErrorKey: url.path])
            }
        }
        let h = try FileHandle(forWritingTo: url)
        try h.seekToEnd()
        handle = h
    }

    func write(_ data: Data) throws {
        guard let handle else { throw CocoaError(.fileWriteUnknown) }
        try handle.write(contentsOf: data)
    }

    func close() {
        try? handle?.close()
        handle = nil
    }
}

/// Log 탭이 보여 주는 숫자와 마지막 줄들.
struct LogStats: Sendable, Equatable {
    var totalLines = 0
    var byKind: [String: Int] = [:]
    var unrecognized = 0
    /// 큐가 가득 차거나 쓰기가 실패해서 버린 줄 수.
    var dropped = 0
    var lastLines: [String] = []
    var currentFile: String?
}

/// 파일 로그 쓰기(JSON Lines). 자기 직렬 큐가 줄을 비우며 쓰므로 `write` 는 멈추지 않는다. 대기 줄이 `queueCapacity` 를 넘으면 줄을 버리고 센다.
///
/// - 파일 이름 `monitor-<yyyyMMdd-HHmmss>.jsonl`, 모든 파일의 첫 줄은 `session`.
/// - `maxFileBytes` 를 넘기게 되면 새 파일(기본 20 MB). 폴더 합계가 `maxTotalBytes` 를 넘으면 가장 오래된 파일부터 지운다(기본 200 MB).
/// - 새 파일 이름이 이미 있는 가장 새 파일보다 늦도록 초를 올려서 이름 순서가 곧 시간 순서가 되게 한다.
/// - 쓰기 실패(디스크 가득 참, 폴더 삭제)는 앱을 죽이지 않는다: 열려 있는 파일을 닫고, 그 줄은 버린 것으로 세고, 다음 줄에서 파일을 다시 연다.
/// Android 앱의 `LogWriter` 와 같은 동작이다.
final class LogWriter: LogSink, @unchecked Sendable {
    static let defaultMaxFileBytes = 20_000_000
    static let defaultMaxTotalBytes = 200_000_000
    static let defaultQueueCapacity = 4096
    static let lastLineCount = 30

    private let directory: URL
    private let session: SessionInfo
    private let clock: @Sendable () -> Date
    private let timeZone: TimeZone
    private let maxFileBytes: Int
    private let maxTotalBytes: Int
    private let queueCapacity: Int
    private let queue: DispatchQueue
    private let open: @Sendable (URL) throws -> LogOutput
    private let onChange: (@Sendable (LogStats) -> Void)?

    /// 통계와 대기 줄 수. 어느 스레드에서든 읽고 쓴다.
    private let lock = NSLock()
    private var storedStats = LogStats()
    private var pending = 0

    /// 아래는 `queue` 안에서만 만진다.
    private var output: LogOutput?
    private var current: URL?
    private var currentBytes = 0
    private var othersBytes = 0

    private let stampFormatter: DateFormatter
    private let nameFormatter: DateFormatter
    private let formatLock = NSLock()

    init(
        directory: URL,
        session: SessionInfo,
        clock: @escaping @Sendable () -> Date = { Date() },
        timeZone: TimeZone = .current,
        maxFileBytes: Int = LogWriter.defaultMaxFileBytes,
        maxTotalBytes: Int = LogWriter.defaultMaxTotalBytes,
        queueCapacity: Int = LogWriter.defaultQueueCapacity,
        queue: DispatchQueue = DispatchQueue(label: "dev.mirolab.teslable.sample.logwriter", qos: .utility),
        open: @escaping @Sendable (URL) throws -> LogOutput = { try FileLogOutput(url: $0) },
        onChange: (@Sendable (LogStats) -> Void)? = nil
    ) {
        self.directory = directory
        self.session = session
        self.clock = clock
        self.timeZone = timeZone
        self.maxFileBytes = maxFileBytes
        self.maxTotalBytes = maxTotalBytes
        self.queueCapacity = queueCapacity
        self.queue = queue
        self.open = open
        self.onChange = onChange

        let stamp = DateFormatter()
        stamp.locale = Locale(identifier: "en_US_POSIX")
        stamp.timeZone = timeZone
        stamp.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'.@MS@'xxx"
        stampFormatter = stamp
        let name = DateFormatter()
        name.locale = Locale(identifier: "en_US_POSIX")
        name.timeZone = timeZone
        name.dateFormat = "yyyyMMdd-HHmmss"
        nameFormatter = name

        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    var stats: LogStats {
        lock.lock(); defer { lock.unlock() }
        return storedStats
    }

    // MARK: - LogSink

    func stamp() -> String {
        let date = clock()
        let millis = Int64((date.timeIntervalSince1970 * 1000).rounded())
        let whole = Date(timeIntervalSince1970: Double(Int64((Double(millis) / 1000).rounded(.down))))
        formatLock.lock()
        let text = stampFormatter.string(from: whole)
        formatLock.unlock()
        return text.replacingOccurrences(of: "@MS@", with: String(format: "%03d", Int(millis % 1000)))
    }

    func write(_ line: LogLine) {
        lock.lock()
        if pending >= queueCapacity {
            storedStats.dropped += 1
            let snapshot = storedStats
            lock.unlock()
            onChange?(snapshot)
            return
        }
        pending += 1
        lock.unlock()
        queue.async { [self] in
            handleLine(line)
            lock.lock(); pending -= 1; lock.unlock()
        }
    }

    func flush() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            queue.async { continuation.resume() }
        }
    }

    func newFile() {
        queue.async { [self] in
            do { try openNewFile() } catch { closeFile() }
        }
    }

    // MARK: - 큐 안에서만

    private func handleLine(_ line: LogLine) {
        do {
            try append(line)
        } catch {
            closeFile()
            update { $0.dropped += 1 }
        }
    }

    private func append(_ line: LogLine) throws {
        let text = line.toJSON()
        let bytes = text.utf8.count + 1
        if output == nil || (currentBytes > 0 && currentBytes + bytes > maxFileBytes) { try openNewFile() }
        try writeRaw(text, bytes: bytes)
        pruneIfNeeded()
        update { s in
            s.totalLines += 1
            s.byKind[line.kind, default: 0] += 1
            if line.unrecognized { s.unrecognized += 1 }
            s.lastLines.append(text)
            if s.lastLines.count > Self.lastLineCount { s.lastLines.removeFirst(s.lastLines.count - Self.lastLineCount) }
        }
    }

    private func writeRaw(_ text: String, bytes: Int) throws {
        guard let output else { return }
        try output.write(Data((text + "\n").utf8))
        currentBytes += bytes
    }

    private func openNewFile() throws {
        closeFile()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = freshFile()
        othersBytes = listFiles().reduce(0) { $0 + size($1) }
        output = try open(file)
        current = file
        currentBytes = 0
        update { $0.currentFile = file.path }
        let head = LogLine.session(ts: stamp(), info: session).toJSON()
        try writeRaw(head, bytes: head.utf8.count + 1)
        pruneIfNeeded()
    }

    /// 폴더 합계(다른 파일 + 지금 파일)가 한도를 넘으면 지금 파일을 뺀 가장 오래된 것부터 지운다.
    private func pruneIfNeeded() {
        if othersBytes + currentBytes <= maxTotalBytes { return }
        for file in listFiles() {
            if othersBytes + currentBytes <= maxTotalBytes { break }
            if file == current { continue }
            let s = size(file)
            if (try? FileManager.default.removeItem(at: file)) != nil { othersBytes -= s }
        }
    }

    private func listFiles() -> [URL] {
        let all = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return all
            .filter { $0.lastPathComponent.hasPrefix("monitor-") && $0.pathExtension == "jsonl" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private func size(_ url: URL) -> Int {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.intValue ?? 0
    }

    /// 새 파일 이름. 시계가 뒤로 가도(서머타임 끝, 시계 조정) 이름 순서가 곧 만든 순서가 되도록, 이미 있는 가장 새 파일보다
    /// 늦은 시각만 쓴다(가장 오래된 파일부터 지우는 정리가 새 파일을 먼저 지우지 않게).
    private func freshFile() -> URL {
        var at = Date(timeIntervalSince1970: clock().timeIntervalSince1970.rounded(.down))
        formatLock.lock()
        defer { formatLock.unlock() }
        if let newest = listFiles().last?.deletingPathExtension().lastPathComponent.dropFirst("monitor-".count),
           let newestDate = nameFormatter.date(from: String(newest)), at <= newestDate {
            at = newestDate.addingTimeInterval(1)
        }
        return directory.appendingPathComponent("monitor-\(nameFormatter.string(from: at)).jsonl")
    }

    private func closeFile() {
        output?.close()
        output = nil
    }

    private func update(_ change: (inout LogStats) -> Void) {
        lock.lock()
        change(&storedStats)
        let snapshot = storedStats
        lock.unlock()
        onChange?(snapshot)
    }
}
