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

/// 모니터 키가 지금 어떤 상태인가(키·제어 탭의 1단계).
enum KeyState: Sendable, Equatable {
    /// 아직 읽지 않았다.
    case none
    /// 키를 읽을 수 있다.
    case ready
    /// 폰 잠금 때문에 키를 읽을 수 없다(키는 멀쩡하다).
    case locked
    /// 읽거나 만들지 못했다.
    case error
    /// 저장된 키가 손상됐다(`PlatformFailure` 의 `stored key is corrupted`). "키 삭제 후 다시 만들기"로만 고친다.
    case corrupted
}

/// 모니터 키의 상태(Control 탭).
struct KeyStatus: Sendable, Equatable {
    var text = "키 없음"
    /// 키가 있는데 잠금 중 사용이 안 될 때 사용자에게 알릴 문장.
    var warning: String?
    var state = KeyState.none
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
    /// 이 연결에서 성공한 `vehicleStatus()` 의 수면 상태(`AWAKE`, `ASLEEP`, `UNKNOWN`). Query 탭 상태 진단의 버튼 활성에 쓴다(SFR-023).
    case sleep(String)
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
    /// 모니터 키 핸들. 키를 바꾸는 중에는 문(`keyGate`)과 세대(`KeyHandleCache.epoch`)가 지워질 옛 키를 막는다.
    private let keyCache = KeyHandleCache<VehicleKey>()
    /// 키를 쓰는 동작과 키 교체를 서로 막는다(Control 탭의 동작, Live/Control 탭의 연결 포함).
    let keyGate = KeyOperationGate()
    private var live = LiveState()
    private var queries: [String: QueryResult] = [:]
    private var eventsTask: Task<(), Never>?
    private var pollTask: Task<(), Never>?
    private let queryNow = WakeSignal()
    /// 연결 직후 첫 조회가 끝나기 전에는 키·제어 탭의 자동 등록 확인(`listKeys()`)을 늦춘다(#128).
    private let initialQueries = InitialQueryGate()
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
        if loop.state == .idle, !keyGate.isReplacing, parsedVin() != nil { startLoop(.launch) }
    }

    /// 연결 버튼. 기다리지 않고 바로 시도한다. VIN 이 없거나 형식이 틀리면 `false`.
    @discardableResult
    func connectNow() -> Bool {
        guard parsedVin() != nil else { return false }
        // 키를 바꾸는 중에는 연결을 시작하지 않는다(옛 키 핸들로 연결되면 안 된다). 누른 것을 기억해 두었다가 교체가 끝나면 따른다.
        if keyGate.note(.connect) { return true }
        startLoop(.connectButton)
        return true
    }

    /// 연결 끊기 버튼. 자동 재연결도 멈춘다(SFR-012).
    func disconnect() async {
        keyGate.note(.stop) // 키를 바꾸는 중이었다면 교체가 끝난 뒤의 다시 연결을 취소한다(마지막 의도가 이긴다)
        await loop.stop()
    }

    /// 폰이 잠금 해제되면 기다리던 재연결을 바로 시도한다(키 저장소가 `Locked` 였을 때). 멈춘 상태는 건드리지 않는다.
    func protectedDataBecameAvailable() {
        noteProtectedData(available: true)
        if case .backoff = loop.state, !keyGate.isReplacing { startLoop(.protectedData) }
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
        keyGate.note(.stop)
        await loop.stop()
        defaults.removeObject(forKey: Self.vinKey)
        emit.yield(.vin(nil))
        // 다음 VIN 이 다른 차일 수 있다
        emit.yield(.live(lock.withLock { () -> LiveState in
            live = LiveReducer.cleared(live)
            return live
        }))
    }

    /// 연결 루프를 시작하는 곳은 여기 하나다. 왜 불렀는지와 그 결과를 `app` 줄(`start`)로 남긴다(루프가 `connecting` 을 알리기 전에 쓴다).
    private func startLoop(_ reason: StartReason) {
        loop.start { [log] effect in log.write(LogLine.start(ts: log.stamp(), reason: reason, effect: effect)) }
    }

    /// 앱 화면 상태(`active`, `inactive`, `background`)가 바뀌었다. 백그라운드에서 재연결이 멈추는 일(F3)을 읽을 때 쓴다.
    func noteScenePhase(_ phase: String) {
        log.write(LogLine.scenePhase(ts: log.stamp(), phase: phase))
    }

    /// 보호된 데이터(= 폰 잠금)가 열렸거나 닫힐 예정이다.
    func noteProtectedData(available: Bool) {
        log.write(LogLine.protectedData(ts: log.stamp(), available: available))
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

    func currentKey() -> VehicleKey? { keyCache.get() }

    enum KeyOutcome {
        case ready(VehicleKey)
        case locked
        case failed(String)
    }

    /// 모니터 키를 읽거나 만든다. 접근성은 만들 때 정해진다(`13-lib-pairing.md` §Key accessibility). `Locked` 이면 키를 지우거나 다시 만들지 않는다.
    func ensureKey() async -> KeyOutcome {
        // 키를 바꾸는 중에는 읽지도 만들지도 않는다: 지워질 키가 캐시에 오르거나 지운 자리에 키가 만들어지면 안 된다.
        if keyGate.isReplacing { return .failed("키를 바꾸는 중입니다. 끝난 뒤 다시 하세요") }
        return await readOrCreateKey()
    }

    /// `ensureKey()` 본체. 키 교체가 자기 안에서 새 키를 만들 때도 쓴다.
    private func readOrCreateKey() async -> KeyOutcome {
        if let existing = currentKey() { return .ready(existing) }
        let epoch = keyCache.epoch
        do {
            let result = try await ble.keys.getOrCreate(alias: Self.monitorKeyAlias, policy: .preferHardware, accessibility: .afterFirstUnlock)
            switch onEnum(of: result) {
            case .success(let s):
                guard let k = s.value as? VehicleKey else {
                    emit.yield(.key(KeyStatus(text: "키 오류: 예상하지 못한 결과 형식", state: .error)))
                    return .failed("예상하지 못한 결과 형식")
                }
                // 읽는 사이에 키 교체가 핸들을 버렸으면(세대가 바뀜) 이 키는 지워질 것이라 캐시에 올리지 않는다.
                guard keyCache.set(k, ifEpoch: epoch) else { return .failed("키가 바뀌는 중이라 읽은 키를 쓰지 않았습니다") }
                emit.yield(.key(describe(k)))
                return .ready(k)
            case .failure(let f):
                if f.error is VehicleErrorKeyStoreErrorLocked {
                    emit.yield(.key(KeyStatus(text: "키 잠김: 폰을 잠금 해제하면 다시 읽습니다(키는 지우지 않음)", state: .locked)))
                    return .locked
                }
                if Self.isCorrupted(f.error) {
                    emit.yield(.key(KeyStatus(text: Self.corruptedKeyText, state: .corrupted)))
                    return .failed(f.error.message)
                }
                emit.yield(.key(KeyStatus(text: "키 오류: \(f.error.message)", state: .error)))
                return .failed(f.error.message)
            case .uncertain(let u): // 키 저장소는 돌려주지 않는다
                emit.yield(.key(KeyStatus(text: "키 불확실: \(u.error.message)", state: .error)))
                return .failed(u.error.message)
            }
        } catch {
            emit.yield(.key(KeyStatus(text: "키 오류: \(error)", state: .error)))
            return .failed("\(error)")
        }
    }

    // MARK: - 키 삭제 후 다시 만들기 (SFR-020)

    /// 모니터 키를 지우고 새로 만든다(`13-lib-pairing.md` §Deleting or replacing a key locally). 순서는 `KeyReplacer` 가 정한다:
    /// 잠겨 있으면 아무것도 하지 않고, 쓰던 연결은 먼저 끊고(세션 캐시가 옛 키 것이다), 지우기가 실패하면 새 키를 만들지 않는다.
    /// 접근성(`afterFirstUnlock`)·보호(`preferHardware`)는 `ensureKey()` 가 만들 때 쓰는 값 그대로다. 연결을 쓰던 중이었으면 새 키로 다시 연결한다.
    func replaceKey() async -> KeyReplaceOutcome {
        // 다른 탭의 등록·명령이 도는 동안, 또는 이미 바꾸는 중이면 시작하지 않는다.
        guard keyGate.beginReplace() else { return .busy }
        defer { keyGate.endReplace() }
        let wasActive = KeyReplacer.isActive(loop.state)
        return await KeyReplacer.run(
            wasActive: wasActive,
            probe: { await self.readStoredKey().probe },
            disconnect: {
                await self.loop.stop()
                // 끊는 사이에 (문이 닫히기 전에 눌린) 연결이 남았는지 본다.
                return !KeyReplacer.isActive(self.loop.state)
            },
            forgetHandle: {
                self.keyCache.forget()
                self.emit.yield(.key(KeyStatus(text: "키 삭제 중", state: .none)))
            },
            delete: { await self.deleteStoredKey() },
            create: {
                switch await self.readOrCreateKey() {
                case .ready: return .created
                case .locked: return .locked
                case .failed(let why): return .failed(why)
                }
            },
            restore: { await self.restoreStoredKey() },
            wantsReconnect: { self.keyGate.shouldReconnect(wasActive: wasActive) },
            reconnect: {
                // 새 키가 캐시에 있다. 문을 풀면서 다시 연결할지 한 번에 정하고(교체 중에 끊기를 눌렀으면 연결하지 않는다) 연결한다.
                if self.keyGate.finishReplace(wasActive: wasActive), self.parsedVin() != nil { self.startLoop(.keyReplaced) }
            }
        )
    }


    private static let corruptedKeyText = "키 손상: 저장된 키를 읽을 수 없습니다. 키·제어 탭의 '키 삭제 후 다시 만들기'로 복구하세요(옛 키는 복구할 수 없음)"

    /// 문서에 적힌 손상(`KeyStoreError.PlatformFailure` 의 `cause` 가 정확히 `stored key is corrupted`)인지.
    static func isCorrupted(_ error: VehicleError) -> Bool {
        guard let failure = error as? VehicleErrorKeyStoreErrorPlatformFailure else { return false }
        return KeyStoreFailure.isCorrupted(cause: failure.cause)
    }

    /// 키 저장소에서 키를 읽기만 한다(만들지 않는다).
    private func readStoredKey() async -> (probe: KeyProbe, key: VehicleKey?) {
        do {
            let result = try await ble.keys.get(alias: Self.monitorKeyAlias)
            switch onEnum(of: result) {
            case .success(let s):
                if let k = s.value as? VehicleKey { return (.present, k) }
                return (.absent, nil)
            case .failure(let f):
                if f.error is VehicleErrorKeyStoreErrorLocked { return (.locked, nil) }
                if Self.isCorrupted(f.error) { return (.corrupted, nil) }
                return (.failed(f.error.message), nil)
            case .uncertain(let u): return (.failed(u.error.message), nil)
            }
        } catch {
            return (.failed("\(error)"), nil)
        }
    }

    private func deleteStoredKey() async -> KeyDeleteResult {
        do {
            let result = try await ble.keys.delete(alias: Self.monitorKeyAlias)
            switch onEnum(of: result) {
            case .success: return .deleted
            case .failure(let f):
                if f.error is VehicleErrorKeyStoreErrorLocked { return .locked }
                return .failed(f.error.message)
            case .uncertain(let u): return .failed(u.error.message)
            }
        } catch {
            return .failed("\(error)")
        }
    }

    /// 지우기가 실패한 뒤 옛 키를 다시 읽어 화면 상태를 되돌리고, 읽은 결과를 돌려준다. 읽기만 하고 새로 만들지 않는다.
    private func restoreStoredKey() async -> KeyProbe {
        let read = await readStoredKey()
        switch read.probe {
        case .present:
            if let k = read.key {
                _ = keyCache.set(k, ifEpoch: keyCache.epoch)
                emit.yield(.key(describe(k)))
            }
        case .absent:
            emit.yield(.key(KeyStatus(text: "키 없음", state: .none)))
        case .locked:
            emit.yield(.key(KeyStatus(text: "키 잠김: 폰을 잠금 해제하면 다시 읽습니다(키는 지우지 않음)", state: .locked)))
        case .corrupted:
            emit.yield(.key(KeyStatus(text: Self.corruptedKeyText, state: .corrupted)))
        case .failed(let why):
            emit.yield(.key(KeyStatus(text: "키 오류: \(why)", state: .error)))
        }
        return read.probe
    }

    private func describe(_ k: VehicleKey) -> KeyStatus {
        let text = "\(k.alias): \(k.protection.name), 접근성 \(k.accessibility.name)"
        let warning = k.accessibility == .afterFirstUnlock
            ? nil
            : "이 키는 폰이 잠긴 동안 쓸 수 없습니다(WHEN_UNLOCKED). 키를 지우고 새로 만든 뒤 차량에 다시 등록해야 합니다."
        return KeyStatus(text: text, warning: warning, state: .ready)
    }

    // MARK: - 연결 루프에 끼우는 함수

    private func attemptConnect() async -> ConnectOutcome<VehicleBox> {
        guard let vin = parsedVin() else { return .failed("저장된 VIN이 없거나 형식이 틀립니다") }
        if keyGate.isReplacing { return .failed("키를 바꾸는 중입니다") }
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
        // 화면(키·제어 탭)이 연결을 알기 전에 문을 닫아 둔다: 그래야 탭의 자동 확인이 첫 조회와 겹치지 않는다.
        if case .connected = state { initialQueries.arm() }
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
        cancelConnectionJobs() // 문(`initialQueries`)은 건드리지 않는다: 이 연결의 것이 `onTransition` 에서 이미 닫혔다
        queryNow.clear()
        // 새 연결의 세대를 여기서 먼저 올린다. 조회가 이벤트 구독보다 먼저 끝나도 새 연결의 것으로 센다.
        let started = lock.withLock { () -> LiveState in
            live = LiveReducer.linkStarted(live)
            return live
        }
        emit.yield(.live(started))
        let epoch = started.linkEpoch
        let events = Task { [self] in await collectEvents(box) }
        let poll = Task { [self] in await pollLoop(box, epoch: epoch) }
        lock.withLock {
            eventsTask = events
            pollTask = poll
        }
    }

    private func stopConnectionJobs() {
        cancelConnectionJobs()
        initialQueries.open() // 연결이 끝났다: 기다리던 쪽을 풀어 준다
    }

    private func cancelConnectionJobs() {
        let (events, poll) = lock.withLock { () -> (Task<(), Never>?, Task<(), Never>?) in
            let e = eventsTask, p = pollTask
            eventsTask = nil
            pollTask = nil
            return (e, p)
        }
        events?.cancel()
        poll?.cancel()
    }

    /// 연결 직후 모니터의 첫 조회가 끝날 때까지 기다린다. 연결이 없거나 이미 끝났으면 바로 돌아온다.
    func waitForInitialQueries() async { await initialQueries.wait() }

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

    private func pollLoop(_ box: VehicleBox, epoch: Int) async {
        var first = true
        while !Task.isCancelled {
            await runQueries(box, epoch: epoch)
            if first {
                first = false
                // 취소된(끝난 연결의) 조회가 새 연결의 문을 열면 안 된다.
                if !Task.isCancelled { initialQueries.open() }
            }
            if Task.isCancelled { return }
            await queryNow.wait(seconds: TimeInterval(pollSeconds))
        }
    }

    /// 세 조회를 차례로. `Uncertain`/`Failure` 도 그대로 기록하고 다음 주기에 다시 조회한다(조회는 다시 보내도 안전하다, `16-lib-errors.md`).
    private func runQueries(_ box: VehicleBox, epoch: Int) async {
        let vehicle = box.vehicle
        if Task.isCancelled { return }
        let status = await record("vehicleStatus", { try await vehicle.vehicleStatus(timeout: nil) }) { value in
            (value as? VehicleStatus_).map { LogLine.statusSummary(StatusSnapshot($0)) } ?? "형식 오류"
        }
        // 성공한 조회는 방송을 기다리지 않고 이 연결에서 들은 것으로 친다(SFR-022). 이전 연결의 늦은 결과는 세대가 달라 걸러진다.
        if let status = status as? VehicleStatus_ {
            let snapshot = StatusSnapshot(status)
            let (next, current) = lock.withLock { () -> (LiveState, Bool) in
                live = LiveReducer.statusQueried(live, snapshot, epoch: epoch, now: Date())
                return (live, live.linkEpoch == epoch)
            }
            emit.yield(.live(next))
            // 수면 상태도 이 연결의 것일 때만 알린다(이전 연결의 늦은 결과는 세대가 달라 걸러진다).
            if current { emit.yield(.sleep(snapshot.sleep)) }
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

    /// 성공했을 때만 값을 돌려준다(`Uncertain`, `Failure`, 취소는 nil).
    @discardableResult
    private func record(_ call: String, _ query: () async throws -> VehicleResult, summarize: (Any?) -> String) async -> Any? {
        let startedAt = log.stamp() // 요청을 보내는 시각(결과 줄의 `ts` 는 끝난 시각이다)
        let outcome: String
        let summary: String
        var success: Any?
        do {
            let result = try await query()
            switch onEnum(of: result) {
            case .success(let s):
                (outcome, summary) = ("Success", summarize(s.value))
                success = s.value
            case .uncertain(let u): (outcome, summary) = ("Uncertain", u.error.message)
            case .failure(let f): (outcome, summary) = ("Failure", f.error.message)
            }
        } catch {
            if Task.isCancelled { return nil } // 연결이 끝나서 취소된 조회는 기록하지 않는다
            (outcome, summary) = ("Failure", "\(error)")
        }
        if Task.isCancelled { return nil } // 연결이 끝나는 중에 끝난 조회는 새 연결의 기록과 섞지 않는다
        let next = lock.withLock { () -> [String: QueryResult] in
            queries[call] = QueryResult(call: call, outcome: outcome, at: Date(), summary: summary)
            return queries
        }
        emit.yield(.queries(next))
        log.write(LogLine.query(ts: log.stamp(), call: call, result: outcome, summary: summary, startedAt: startedAt))
        return success
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

/// Query 탭 상태 진단(SFR-023)이 엔진에서 얻는 것: 지금 연결과 로그 파일. `getState` 의 값은 이 타입을 지나 로그로 가지 않는다
/// (`record` 도 쓰지 않는다: 그 요약이 로그로 간다). 로그에는 `StateLogEntry`(범주, 결과, 소요 시간, 오류 종류)만 쓴다.
struct LiveStateBackend: StateDiagnosticsBackend {
    let engine: MonitorEngine

    func open() -> StateLink? { engine.currentVehicle().map { LiveStateLink(box: VehicleBox(vehicle: $0)) } }
    func stamp() -> String { engine.log.stamp() }

    func writeLog(_ entry: StateLogEntry, startedAt: String) {
        engine.log.write(LogLine.stateQuery(ts: engine.log.stamp(), entry: entry, startedAt: startedAt))
    }
}

/// 한 연결의 `Vehicle` 에 하는 호출 셋: `vehicleStatus`, `startSession(INFOTAINMENT)`, `getState`. 모두 `timeout: nil`(문서의 기본값).
/// `wakeUp()` 은 부르지 않는다. 결과는 오류 종류와 이름 붙은 필드로만 돌려주고 `VehicleData` 를 들고 다니지 않는다.
final class LiveStateLink: StateLink, @unchecked Sendable {
    private let box: VehicleBox

    init(box: VehicleBox) { self.box = box }

    /// `14-lib-reading-state.md` A8.2: `vehicleStatus()` 는 세션도 키도 필요 없고 차를 깨우지 않는다.
    func readSleep() async -> SleepRead {
        do {
            let result = try await box.vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let status = s.value as? VehicleStatus_ else { return .failed(.other("UnexpectedResultType")) }
                return SleepRead.from(name: status.sleepStatus.name)
            case .uncertain(let u): return .failed(StateFault(u.error))
            case .failure(let f): return .failed(StateFault(f.error))
            }
        } catch {
            return .failed(.other("Exception"))
        }
    }

    /// `startSession(setOf(INFOTAINMENT))`. 이미 `Ready` 이면 요청을 보내지 않는다(`15-lib-commands.md`).
    func ensureInfotainmentSession() async -> StateSessionOutcome {
        let domains: Set<VehicleDomain> = [VehicleDomain.infotainment]
        do {
            let result = try await box.vehicle.startSession(domains: domains, timeout: nil)
            switch onEnum(of: result) {
            case .success: return .ready
            case .uncertain(let u): return .failed(StateFault(u.error))
            case .failure(let f): return .failed(StateFault(f.error))
            }
        } catch {
            return .failed(.other("Exception"))
        }
    }

    /// `14-lib-reading-state.md` A8.3 의 Swift 형태: 값은 `s.value as? VehicleData` 로 꺼내고 숫자는 `KotlinInt?` 로 읽는다.
    func getState(_ category: DiagnosticCategory) async -> StateFetch {
        do {
            let result = try await box.vehicle.getState(category: category.stateCategory, timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let data = s.value as? VehicleData else { return .failure(.other("UnexpectedResultType")) }
                return .success(StateFields.extract(category, from: data))
            case .uncertain(let u): return .uncertain(StateFault(u.error))
            case .failure(let f): return .failure(StateFault(f.error))
            }
        } catch {
            return .failure(.other("Exception"))
        }
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
