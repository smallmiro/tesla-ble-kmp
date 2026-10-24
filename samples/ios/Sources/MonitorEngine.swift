import Foundation
import Teslable
import UIKit

/// 조회 한 번의 결과(Query 탭). `outcome` 은 `Success` / `Uncertain` / `Failure`.
struct QueryResult: Sendable, Equatable {
    let call: String
    let outcome: String
    let at: Date
    let summary: String
}

/// 모니터 키의 상태(Control 탭).
struct KeyStatus: Sendable, Equatable {
    var text = "키 없음"
    /// 키가 있는데 잠금 중 사용이 안 될 때 사용자에게 알릴 문장.
    var warning: String?
}

/// 엔진이 화면(`MonitorModel`)에 알리는 변화. 한 줄기(AsyncStream)로 보내서 순서가 바뀌지 않는다.
enum MonitorUpdate: Sendable {
    case link(LinkState<VehicleBox>)
    case live(LiveState)
    case queries([String: QueryResult])
    case sessions(String)
    case key(KeyStatus)
    case vin(String?)
    case pollSeconds(Int)
    case log(LogStats)
}

/// 라이브러리 로그를 DebugLogger(통합 로그)로 그대로 보내고, 관찰 로그(태그 `observe`)는 로그 파일에도 한 줄로 쓴다.
/// BLE 의 `TX:`/`RX:` 줄은 파일에 쓰지 않는다(통합 로그에만 간다). Kotlin 수신 스레드에서 불리므로 싱크는 멈추지 않는 것만 쓴다.
final class ObserveLogger: NSObject, TeslaLogger, @unchecked Sendable {
    private let debug = DebugLogger()
    private let sink: LogSink

    init(sink: LogSink) {
        self.sink = sink
        super.init()
    }

    func log(level: LogLevel, tag: String, message: @escaping () -> String) {
        if tag == "observe" {
            let text = message()
            sink.write(LogLine.observe(ts: sink.stamp(), text: text))
            debug.log(level: level, tag: tag, message: { text })
        } else {
            debug.log(level: level, tag: tag, message: message)
        }
    }
}

/// 다른 Task 가 채워 주는 값 한 칸.
final class ResultBox<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: T?

    func set(_ new: T) { lock.withLock { value = new } }
    func get() -> T? { lock.withLock { value } }
}

/// 신호가 올 때까지(또는 시간이 다할 때까지) 기다린다. 기다리기 전에 온 신호는 하나만 기억한다(Android 의 CONFLATED 채널).
final class WakeSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var pending = false
    private var waiting: Gate?

    func send() {
        let gate: Gate? = lock.withLock {
            if let g = waiting {
                waiting = nil // 같은 문을 두 번 쓰지 않는다: 다음 신호는 기억해 두었다가 다음 기다림에서 쓴다
                return g
            }
            pending = true
            return nil
        }
        gate?.fire()
    }

    func clear() { lock.withLock { pending = false } }

    func wait(seconds: TimeInterval) async {
        let gate = Gate()
        let skip: Bool = lock.withLock {
            if pending {
                pending = false
                return true
            }
            waiting = gate
            return false
        }
        if skip { return }
        let timer = Task {
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            gate.fire()
        }
        await withTaskCancellationHandler { await gate.wait() } onCancel: { gate.fire() }
        timer.cancel()
        lock.withLock { waiting = nil }
    }
}

/// 로그 통계를 화면에 보낼 때 줄마다 보내지 않고 0.25초에 한 번, 가장 새 값만 보낸다(줄이 몰려도 화면 쪽 큐가 쌓이지 않게).
final class StatsPublisher: @unchecked Sendable {
    private let lock = NSLock()
    private var latest: LogStats?
    private var scheduled = false
    private let emit: AsyncStream<MonitorUpdate>.Continuation

    init(emit: AsyncStream<MonitorUpdate>.Continuation) { self.emit = emit }

    func submit(_ stats: LogStats) {
        let schedule: Bool = lock.withLock {
            latest = stats
            if scheduled { return false }
            scheduled = true
            return true
        }
        guard schedule else { return }
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.25) { [self] in
            let stats: LogStats? = lock.withLock {
                scheduled = false
                return latest
            }
            if let stats { emit.yield(.log(stats)) }
        }
    }
}

/// 모니터의 심장. 앱 수명 동안 하나만 있다(`TeslaBle` 은 프로세스당 하나, `12-lib-getting-started.md`).
/// 연결 루프, 이벤트 구독, 주기 조회, 로그 쓰기를 모두 여기서 한다. Kotlin 객체는 이 안에서만 만지고 화면에는 값(`MonitorUpdate`)만 보낸다.
/// Android 앱의 `MonitorEngine` 과 같은 동작이다.
final class MonitorEngine: @unchecked Sendable {
    static let monitorKeyAlias = "teslable-monitor"
    static let defaultPollSeconds = 30
    static let minPollSeconds = 5
    private static let vinKey = "vin"
    private static let pollKey = "pollSeconds"

    let ble: TeslaBle
    let log: LogWriter
    let logFolder: URL
    /// 화면으로 가는 변화. 한 곳에서만 읽는다(`MonitorModel`).
    let updates: AsyncStream<MonitorUpdate>
    private let emit: AsyncStream<MonitorUpdate>.Continuation

    private let defaults: UserDefaults
    private let lock = NSLock()
    private var key: VehicleKey?
    private var live = LiveState()
    private var queries: [String: QueryResult] = [:]
    private var eventsTask: Task<(), Never>?
    private var pollTask: Task<(), Never>?
    private let queryNow = WakeSignal()
    private var loop: ConnectionLoop<VehicleBox>!

    init(defaults: UserDefaults = .standard, logFolder: URL? = nil) {
        self.defaults = defaults
        let folder = logFolder ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("logs", isDirectory: true)
        self.logFolder = folder
        var continuation: AsyncStream<MonitorUpdate>.Continuation!
        updates = AsyncStream(bufferingPolicy: .unbounded) { continuation = $0 }
        emit = continuation
        let stats = StatsPublisher(emit: continuation)
        let writer = LogWriter(directory: folder, session: Self.sessionInfo(), onChange: { stats.submit($0) })
        log = writer
        ble = TeslaBle.companion.create(
            platform: PlatformContext(),
            config: TeslaBleConfig.companion.default(logger: ObserveLogger(sink: writer))
        )
        loop = ConnectionLoop<VehicleBox>(
            connect: { [unowned self] in await attemptConnect() },
            awaitLoss: { [unowned self] in await awaitLoss($0) },
            release: { vehicle in try? await vehicle.vehicle.disconnect() }, // 링크가 끊겨도 반드시 부른다(12-lib-getting-started.md)
            onTransition: { [unowned self] in onTransition($0) }
        )
    }

    var link: LinkState<VehicleBox> { loop.state }
    var savedVin: String? { defaults.string(forKey: Self.vinKey) }
    var pollSeconds: Int {
        let n = defaults.integer(forKey: Self.pollKey)
        return n >= Self.minPollSeconds ? n : Self.defaultPollSeconds
    }

    static func sessionInfo() -> SessionInfo {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        var system = utsname()
        uname(&system)
        let machine = withUnsafeBytes(of: &system.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        let model = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? machine
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return SessionInfo(platform: "ios", app: "\(version)(\(build))", os: "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)", phone: model)
    }

    /// 시작할 때 화면이 한 번 그리도록 현재 값을 모두 보낸다.
    func publishAll() {
        emit.yield(.vin(savedVin))
        emit.yield(.pollSeconds(pollSeconds))
        emit.yield(.link(loop.state))
        emit.yield(.log(log.stats))
    }

    // MARK: - 사용자 동작

    /// 저장된 VIN 이 있으면 연결을 시작한다. 이 프로세스에서 아직 아무것도 시작하지 않았을 때만(앱 시작 자동 연결, SFR-011).
    func autoConnectIfSaved() {
        if loop.state == .idle, parsedVin() != nil { loop.start() }
    }

    /// 연결 버튼. 기다리지 않고 바로 시도한다. VIN 이 없거나 형식이 틀리면 `false`.
    @discardableResult
    func connectNow() -> Bool {
        guard parsedVin() != nil else { return false }
        loop.start()
        return true
    }

    /// 연결 끊기 버튼. 자동 재연결도 멈춘다(SFR-012).
    func disconnect() async { await loop.stop() }

    /// 폰이 잠금 해제되면 기다리던 재연결을 바로 시도한다(키 저장소가 `Locked` 였을 때). 멈춘 상태는 건드리지 않는다.
    func protectedDataBecameAvailable() {
        if case .backoff = loop.state { loop.start() }
    }

    /// VIN 을 저장한다. 형식이 틀리면 `false`.
    @discardableResult
    func saveVin(_ text: String) -> Bool {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard let vin = Vin.companion.parseOrNull(value: normalized) else { return false }
        defaults.set(vin.value, forKey: Self.vinKey)
        emit.yield(.vin(vin.value))
        return true
    }

    /// 저장된 VIN 을 지운다. 먼저 연결을 끊는다.
    func clearVin() async {
        await loop.stop()
        defaults.removeObject(forKey: Self.vinKey)
        emit.yield(.vin(nil))
    }

    func parsedVin() -> Vin? { savedVin.flatMap { Vin.companion.parseOrNull(value: $0) } }

    func setPollSeconds(_ seconds: Int) {
        let n = max(seconds, Self.minPollSeconds)
        defaults.set(n, forKey: Self.pollKey)
        emit.yield(.pollSeconds(n))
        queryNow.send() // 새 주기로 바로 한 번 조회하고 다시 센다
    }

    func requestQuery() { queryNow.send() }

    /// Mark 버튼. 메모가 비어 있으면 무시한다.
    func mark(_ memo: String) {
        if let line = LogLine.mark(ts: log.stamp(), memo: memo) { log.write(line) }
    }

    func newLogFile() { log.newFile() }

    // MARK: - Control 탭이 쓰는 것

    func currentVehicle() -> Vehicle? {
        if case .connected(let box) = loop.state { return box.vehicle }
        return nil
    }

    func currentKey() -> VehicleKey? { lock.withLock { key } }

    enum KeyOutcome {
        case ready(VehicleKey)
        case locked
        case failed(String)
    }

    /// 모니터 키를 읽거나 만든다. 접근성은 만들 때 정해진다(`13-lib-pairing.md` §Key accessibility). `Locked` 이면 키를 지우거나 다시 만들지 않는다.
    func ensureKey() async -> KeyOutcome {
        if let existing = currentKey() { return .ready(existing) }
        do {
            let result = try await ble.keys.getOrCreate(alias: Self.monitorKeyAlias, policy: .preferHardware, accessibility: .afterFirstUnlock)
            switch onEnum(of: result) {
            case .success(let s):
                guard let k = s.value as? VehicleKey else {
                    emit.yield(.key(KeyStatus(text: "키 오류: 예상하지 못한 결과 형식")))
                    return .failed("예상하지 못한 결과 형식")
                }
                lock.withLock { key = k }
                emit.yield(.key(describe(k)))
                return .ready(k)
            case .failure(let f):
                if f.error is VehicleErrorKeyStoreErrorLocked {
                    emit.yield(.key(KeyStatus(text: "키 잠김: 폰을 잠금 해제하면 다시 읽습니다(키는 지우지 않음)")))
                    return .locked
                }
                emit.yield(.key(KeyStatus(text: "키 오류: \(f.error.message)")))
                return .failed(f.error.message)
            case .uncertain(let u): // 키 저장소는 돌려주지 않는다
                emit.yield(.key(KeyStatus(text: "키 불확실: \(u.error.message)")))
                return .failed(u.error.message)
            }
        } catch {
            emit.yield(.key(KeyStatus(text: "키 오류: \(error)")))
            return .failed("\(error)")
        }
    }

    private func describe(_ k: VehicleKey) -> KeyStatus {
        let text = "\(k.alias): \(k.protection.name), 접근성 \(k.accessibility.name)"
        let warning = k.accessibility == .afterFirstUnlock
            ? nil
            : "이 키는 폰이 잠긴 동안 쓸 수 없습니다(WHEN_UNLOCKED). 키를 지우고 새로 만든 뒤 차량에 다시 등록해야 합니다."
        return KeyStatus(text: text, warning: warning)
    }

    // MARK: - 연결 루프에 끼우는 함수

    private func attemptConnect() async -> ConnectOutcome<VehicleBox> {
        guard let vin = parsedVin() else { return .failed("저장된 VIN이 없거나 형식이 틀립니다") }
        let outcome = await ensureKey()
        // Locked 면 기다렸다가 다시 읽는다(백오프). 그 밖의 키 오류는 키 없이 연결한다: 방송과 조회는 키가 필요 없다(14 B2.4).
        if case .locked = outcome { return .failed("키 저장소가 잠겨 있습니다(폰 잠금 해제 대기)") }
        do {
            let result = try await ble.connect(vin: vin, key: currentKey(), target: nil, options: ConnectOptions(timeout: nil))
            switch onEnum(of: result) {
            case .success(let s):
                guard let v = s.value as? Vehicle else { return .failed("예상하지 못한 결과 형식") }
                return .connected(VehicleBox(vehicle: v))
            case .uncertain(let u): return .failed("불확실: \(u.error.message)")
            case .failure(let f): return .failed(f.error.message)
            }
        } catch {
            return .failed("\(error)")
        }
    }

    /// 링크가 끊길 때까지 기다리고 이유를 돌려준다. 호출한 Task 가 취소되면(사용자가 끊었다) 바로 돌아온다:
    /// Kotlin 흐름이 취소에 늦게 반응해도 `stop()` 이 매달리지 않도록 관찰은 별도 Task 에서 하고 문(`Gate`)으로 기다린다.
    private func awaitLoss(_ box: VehicleBox) async -> String? {
        let gate = Gate()
        let reason = ResultBox<String?>()
        let watcher = Task { [self] in
            reason.set(await watchDisconnect(box))
            gate.fire()
        }
        await withTaskCancellationHandler { await gate.wait() } onCancel: {
            watcher.cancel()
            gate.fire()
        }
        return reason.get() ?? nil
    }

    private func watchDisconnect(_ box: VehicleBox) async -> String? {
        nonisolated(unsafe) let connection = box.vehicle.connection
        for await state in connection {
            if case .disconnected(let d) = onEnum(of: state) {
                let reason = d.reason?.message
                if !Task.isCancelled { log.write(LogLine.link(ts: log.stamp(), state: "disconnected", reason: reason)) }
                return reason
            }
        }
        return nil
    }

    private func onTransition(_ state: LinkState<VehicleBox>) {
        let ts = log.stamp()
        emit.yield(.link(state))
        switch state {
        case .idle: break
        case .connecting(let attempt):
            stopConnectionJobs()
            log.write(LogLine.link(ts: ts, state: "connecting", attempt: attempt))
        case .connected(let box):
            log.write(LogLine.link(ts: ts, state: "connected"))
            startConnectionJobs(box)
        case .backoff(let attempt, let delay, let reason):
            stopConnectionJobs()
            log.write(LogLine.link(ts: ts, state: "backoff", reason: reason, attempt: attempt, delayMs: Int((delay * 1000).rounded())))
        case .stopped:
            stopConnectionJobs()
            log.write(LogLine.link(ts: ts, state: "stopped"))
        }
    }

    // MARK: - 연결마다 새로 도는 것: 이벤트 구독과 주기 조회

    private func startConnectionJobs(_ box: VehicleBox) {
        stopConnectionJobs()
        queryNow.clear()
        let events = Task { [self] in await collectEvents(box) }
        let poll = Task { [self] in await pollLoop(box) }
        lock.withLock {
            eventsTask = events
            pollTask = poll
        }
    }

    private func stopConnectionJobs() {
        let (events, poll) = lock.withLock { () -> (Task<(), Never>?, Task<(), Never>?) in
            let e = eventsTask, p = pollTask
            eventsTask = nil
            pollTask = nil
            return (e, p)
        }
        events?.cancel()
        poll?.cancel()
    }

    /// 이벤트 흐름은 링크가 끊기거나 `disconnect()` 가 불리면 저절로 끝난다. 재연결 판단은 `connection` 으로 한다(루프가 한다).
    private func collectEvents(_ box: VehicleBox) async {
        nonisolated(unsafe) let events = box.vehicle.events
        for await event in events {
            if Task.isCancelled { break } // 끊긴 연결의 늦은 이벤트가 새 연결의 로그와 Live 를 덮지 않게
            let mapped = MonitorEvent(event) // raw 는 여기서 한 번만 읽고 VIN 을 가려 쓴다
            log.write(LogLine.event(ts: log.stamp(), mapped))
            let next = lock.withLock { () -> LiveState in
                live = LiveReducer.reduce(live, mapped, now: Date())
                return live
            }
            emit.yield(.live(next))
        }
    }

    private func pollLoop(_ box: VehicleBox) async {
        while !Task.isCancelled {
            await runQueries(box)
            if Task.isCancelled { return }
            await queryNow.wait(seconds: TimeInterval(pollSeconds))
        }
    }

    /// 세 조회를 차례로. `Uncertain`/`Failure` 도 그대로 기록하고 다음 주기에 다시 조회한다(조회는 다시 보내도 안전하다, `16-lib-errors.md`).
    private func runQueries(_ box: VehicleBox) async {
        let vehicle = box.vehicle
        if Task.isCancelled { return }
        await record("vehicleStatus", { try await vehicle.vehicleStatus(timeout: nil) }) { value in
            (value as? VehicleStatus_).map { LogLine.statusSummary(StatusSnapshot($0)) } ?? "형식 오류"
        }
        if Task.isCancelled { return }
        await record("keySummary", { try await vehicle.keySummary(timeout: nil) }) { value in
            guard let s = value as? WhitelistSummary else { return "형식 오류" }
            return "entries=\(s.numberOfEntries) slots=\(LogLine.listText(s.occupiedSlots.map { String($0.intValue) }))"
        }
        if Task.isCancelled { return }
        await record("listKeys", { try await vehicle.listKeys(timeout: nil) }) { value in
            guard let list = value as? [WhitelistEntry] else { return "형식 오류" }
            return "\(list.count) keys: " + list.map { "slot\($0.slot)/\($0.role.name)/\($0.formFactor.name)" }.joined(separator: ", ")
        }
        if Task.isCancelled { return }
        emit.yield(.sessions(sessionsText(vehicle)))
    }

    private func record(_ call: String, _ query: () async throws -> VehicleResult, summarize: (Any?) -> String) async {
        let outcome: String
        let summary: String
        do {
            let result = try await query()
            switch onEnum(of: result) {
            case .success(let s): (outcome, summary) = ("Success", summarize(s.value))
            case .uncertain(let u): (outcome, summary) = ("Uncertain", u.error.message)
            case .failure(let f): (outcome, summary) = ("Failure", f.error.message)
            }
        } catch {
            if Task.isCancelled { return } // 연결이 끝나서 취소된 조회는 기록하지 않는다
            (outcome, summary) = ("Failure", "\(error)")
        }
        if Task.isCancelled { return } // 연결이 끝나는 중에 끝난 조회는 새 연결의 기록과 섞지 않는다
        let next = lock.withLock { () -> [String: QueryResult] in
            queries[call] = QueryResult(call: call, outcome: outcome, at: Date(), summary: summary)
            return queries
        }
        emit.yield(.queries(next))
        log.write(LogLine.query(ts: log.stamp(), call: call, result: outcome, summary: summary))
    }

    /// 도메인별 세션 상태 한 줄.
    func sessionsText(_ vehicle: Vehicle) -> String {
        let map = vehicle.sessions.value
        let parts = map.keys.sorted { $0.name < $1.name }.map { d -> String in
            let text: String
            switch onEnum(of: map[d]!) {
            case .none: text = "None"
            case .handshaking: text = "Handshaking"
            case .ready(let r): text = r.fromCache ? "Ready(fromCache)" : "Ready"
            }
            return "\(d.name)=\(text)"
        }
        return "세션: " + (parts.isEmpty ? "없음" : parts.joined(separator: ", "))
    }
}

/// 링크 상태를 사람이 읽는 한 줄로(배너 공용).
enum LinkText {
    static func of<V: Sendable & Equatable>(_ state: LinkState<V>) -> String {
        switch state {
        case .idle: return "대기 중 (연결 전)"
        case .connecting(let attempt): return "연결 중… (시도 \(attempt))"
        case .connected: return "연결됨"
        case .backoff(let attempt, let delay, let reason):
            return "재연결 대기 (연속 실패 \(attempt)회, \(Int(delay))초 뒤)" + (reason.map { ": \($0)" } ?? "")
        case .stopped: return "연결 끊음 (자동 재연결 중지)"
        }
    }
}
