import Foundation

// Query 탭의 상태 진단(SFR-023): 사용자가 버튼을 눌렀을 때만 `getState` 를 한 번 보내고, 값은 화면에만 보여 준다.
// 이 파일은 라이브러리(Kotlin) 타입이 없는 순수 규칙과 화면 상태다. 규칙의 정본: `samples/documents/md/04-monitor-app.md` §State diagnostics.
// 라이브러리 호출 순서의 정본: `documents/md/14-lib-reading-state.md` A8.2(`getState` 는 세션을 만들지도 차를 깨우지도 않는다).

/// 진단 버튼. `Location` 은 넣지 않는다(위치 값을 다룰 이유가 없다).
enum DiagnosticCategory: String, CaseIterable, Sendable, Identifiable {
    case drive, media, mediaDetail

    var id: Self { self }

    var title: String {
        switch self {
        case .drive: return "Drive"
        case .media: return "Media"
        case .mediaDetail: return "MediaDetail"
        }
    }

    /// 로그에 쓰는 이름(`StateCategory` 의 Kotlin 이름).
    var logName: String {
        switch self {
        case .drive: return "DRIVE"
        case .media: return "MEDIA"
        case .mediaDetail: return "MEDIA_DETAIL"
        }
    }
}

/// 화면에 보이는 한 줄. 값은 화면에만 간다(로그와 `observe` 줄로는 가지 않는다, FR-112).
struct StateField: Equatable, Sendable {
    let label: String
    let value: String
}

/// 조회가 끝나지 않은 이유. 오류 문구는 **종류**만 담는다(차량이 보낸 글은 담지 않는다).
enum StateFault: Equatable, Sendable {
    /// `Uncertain(ProtocolFault(RESPONSE_MTU_EXCEEDED))`: 차가 요청을 받았고 응답이 너무 커서 데이터가 오지 않았다.
    case responseTooLarge
    /// `Timeout`: `afterSend` 가 참이면 요청을 보낸 뒤 응답이 없었다. 거짓이면 보내기 전(또는 재시도를 기다리는 중)에 시간이 다했다.
    case noReply(afterSend: Bool)
    /// `Failure(NoSession)`: 요청을 보내지 않았다(세션이 없거나 차가 캐시된 세션을 받아들이지 않았다).
    case noSession
    /// 그 밖의 오류. 값은 오류 타입 이름이다.
    case other(String)

    /// 로그에 남는 이름(오류 종류만).
    var logName: String {
        switch self {
        case .responseTooLarge: return "ResponseTooLarge"
        case .noReply(let afterSend): return "Timeout(afterSend=\(afterSend))"
        case .noSession: return "NoSession"
        case .other(let name): return name
        }
    }

    var text: String {
        switch self {
        case .responseTooLarge:
            return "차량이 응답했지만 응답이 너무 커서 데이터가 오지 않았습니다(RESPONSE_MTU_EXCEEDED). 차량은 닿는 상태입니다"
        case .noReply(let afterSend):
            return afterSend
                ? "요청을 보냈지만 시간 안에 응답이 없습니다(수면 중이거나, 응답이 사라졌거나, 링크가 끊겼을 수 있습니다). 값은 확인되지 않았습니다"
                : "요청을 보내기 전, 또는 재시도를 기다리는 중에 시간이 다했습니다"
        case .noSession:
            return "Infotainment 세션이 없어 보내지 않았습니다. 다시 누르면 세션을 새로 시작합니다"
        case .other(let name):
            return "오류: \(name)"
        }
    }
}

/// 요청을 차량에 보내지 않은 이유.
enum NotSentReason: Equatable, Sendable {
    case asleep
    case sleepUnknown
    case sleepReadFailed(StateFault)
    case sessionFailed(StateFault)

    var logName: String {
        switch self {
        case .asleep: return "asleep"
        case .sleepUnknown: return "sleepUnknown"
        case .sleepReadFailed(let f): return "sleepReadFailed:\(f.logName)"
        case .sessionFailed(let f): return "sessionFailed:\(f.logName)"
        }
    }

    var text: String {
        switch self {
        case .asleep:
            return "차량이 수면 중입니다. 요청을 보내지 않았습니다"
        case .sleepUnknown:
            return "수면 상태를 알 수 없습니다(UNKNOWN). 요청을 보내지 않았습니다"
        case .sleepReadFailed(let f):
            return "수면 상태를 읽지 못해 요청을 보내지 않았습니다. \(f.text)"
        case .sessionFailed(let f):
            return "Infotainment 세션을 시작하지 못해 요청을 보내지 않았습니다. \(f.text)"
        }
    }
}

/// 조회 한 번의 결과. `Success` / `Uncertain` / `Failure` 는 라이브러리 결과이고 `NotSent` 는 샘플이 보내기 전에 멈춘 경우다.
enum StateOutcome: Equatable, Sendable {
    case success
    case uncertain(StateFault)
    case failure(StateFault)
    case notSent(NotSentReason)

    var kind: String {
        switch self {
        case .success: return "Success"
        case .uncertain: return "Uncertain"
        case .failure: return "Failure"
        case .notSent: return "NotSent"
        }
    }

    var text: String {
        switch self {
        case .success: return "성공"
        case .uncertain(let f): return "불확실: \(f.text). 다시 보내지 않았습니다"
        case .failure(let f): return "실패: \(f.text)"
        case .notSent(let r): return "보내지 않음: \(r.text)"
        }
    }
}

/// 로그에 남는 것의 전부: 범주, 결과, 소요 시간, 오류 종류. 값과 곡 제목은 담을 자리가 없다.
struct StateLogEntry: Equatable, Sendable {
    let category: DiagnosticCategory
    let outcome: String
    /// `error` 또는 `reason`, 그리고 그 값. 성공이면 `nil`.
    let detail: (key: String, value: String)?
    let durationMs: Int

    static func == (lhs: StateLogEntry, rhs: StateLogEntry) -> Bool {
        lhs.category == rhs.category && lhs.outcome == rhs.outcome && lhs.durationMs == rhs.durationMs
            && lhs.detail?.key == rhs.detail?.key && lhs.detail?.value == rhs.detail?.value
    }

    /// 로그 줄의 `summary`.
    var summary: String {
        var text = "durationMs=\(durationMs)"
        if let detail { text += " \(detail.key)=\(detail.value)" }
        return text
    }
}

/// 조회 한 번의 결과와 화면에 보일 값. 값은 이 연결에서 읽은 것이라 연결이 바뀌면 버린다.
struct StateReading: Equatable, Sendable {
    let category: DiagnosticCategory
    let outcome: StateOutcome
    let at: Date
    let durationMs: Int
    let fields: [StateField]

    /// 로그용 요약. `fields` 는 여기를 지나가지 않는다.
    var logEntry: StateLogEntry {
        let detail: (key: String, value: String)?
        switch outcome {
        case .success: detail = nil
        case .uncertain(let f), .failure(let f): detail = ("error", f.logName)
        case .notSent(let r): detail = ("reason", r.logName)
        }
        return StateLogEntry(category: category, outcome: outcome.kind, detail: detail, durationMs: durationMs)
    }
}

/// `vehicleStatus()` 의 수면 상태.
enum SleepRead: Equatable, Sendable {
    case awake, asleep, unknown
    /// `vehicleStatus()` 가 실패했거나 불확실했다.
    case failed(StateFault)

    /// Kotlin enum 이름(`AWAKE`, `ASLEEP`, `UNKNOWN`). 모르는 값은 깨어 있는 것으로 치지 않는다.
    static func from(name: String) -> SleepRead {
        switch name {
        case "AWAKE": return .awake
        case "ASLEEP": return .asleep
        default: return .unknown
        }
    }

    /// 버튼 활성에 쓰는 이름.
    var name: String? {
        switch self {
        case .awake: return "AWAKE"
        case .asleep: return "ASLEEP"
        case .unknown: return "UNKNOWN"
        case .failed: return nil
        }
    }
}

enum StateSessionOutcome: Equatable, Sendable {
    case ready
    case failed(StateFault)
}

/// `getState` 한 번의 결과. 값은 이미 이름 붙은 필드로만 옮겨졌다(`VehicleData` 전체를 들고 다니지 않는다).
enum StateFetch: Equatable, Sendable {
    case success([StateField])
    case uncertain(StateFault)
    case failure(StateFault)
}

// MARK: - 값 표시

struct DriveValues: Sendable, Equatable {
    var speed: Int?
    var speedFloat: Float?
    var shift: String?
    var power: Int?
    var odometer: Int?
    var timestamp: Int64?
}

struct MediaValues: Sendable, Equatable {
    var playback: String?
    var volume: Float?
    var title: String?
    var timestamp: Int64?
}

struct MediaDetailValues: Sendable, Equatable {
    var duration: Int?
    var elapsed: Int?
    var timestamp: Int64?
}

struct StateInputs: Sendable, Equatable {
    var connected: Bool
    var hasKey: Bool
    var enrolled: Bool
    /// 마지막으로 읽은 수면 상태(`AWAKE`, `ASLEEP`, `UNKNOWN`). 읽은 적이 없으면 `UNKNOWN`.
    var sleep: String
    var busy: Bool
}

enum StateDiagnostics {
    static let absent = "—"
    static let unitUnknown = "단위 미확인"

    // MARK: 버튼

    /// 일반 버튼: 연결, 개인키, 등록된 키, 최근 `vehicleStatus()` 의 `AWAKE` 가 모두 있어야 켠다. `UNKNOWN` 은 끈다.
    static func buttonsEnabled(_ i: StateInputs) -> Bool {
        i.connected && i.hasKey && i.enrolled && i.sleep == "AWAKE" && !i.busy
    }

    /// 잠든 차 실험 버튼: 수면 확인만 건너뛴다. 연결, 개인키, 등록된 키는 그대로 필요하다.
    static func experimentEnabled(_ i: StateInputs) -> Bool {
        i.connected && i.hasKey && i.enrolled && !i.busy
    }

    /// 버튼이 꺼진 이유(켜져 있으면 `nil`). 실험 버튼은 수면을 따지지 않는다.
    static func disabledHint(_ i: StateInputs, experiment: Bool = false) -> String? {
        if i.busy { return "조회 중입니다" }
        if !i.connected { return "차량에 연결되어 있지 않습니다" }
        if !i.hasKey { return "키 없이 연결되었습니다. 연결을 끊고 다시 연결하세요" }
        if !i.enrolled { return "등록된 키가 필요합니다. 키·제어 탭에서 등록을 확인하세요" }
        if experiment { return nil }
        switch i.sleep {
        case "AWAKE": return nil
        case "ASLEEP": return "차량이 수면 중입니다. 일반 버튼은 꺼 둡니다(실험 버튼은 수면 확인을 건너뜁니다)"
        default: return "수면 상태를 아직 모릅니다(UNKNOWN). 다음 vehicleStatus() 결과를 기다립니다"
        }
    }

    // MARK: 값

    /// 속도는 원시 값과 "단위 미확인". 없는 값은 `—`. 0으로 바꾸지 않고 km/h 로 바꾸지 않는다(`14-lib-reading-state.md` A8.5).
    static func speedText(_ raw: Int?) -> String {
        raw.map { "\($0) (\(unitUnknown))" } ?? absent
    }

    static func fields(drive v: DriveValues) -> [StateField] {
        [
            StateField(label: "speed", value: speedText(v.speed)),
            StateField(label: "speed_float", value: v.speedFloat.map { "\(number($0)) (\(unitUnknown))" } ?? absent),
            StateField(label: "shift_state", value: v.shift ?? absent),
            StateField(label: "power", value: text(v.power)),
            StateField(label: "odometer_in_hundredths_of_a_mile", value: text(v.odometer)),
            StateField(label: "timestamp", value: v.timestamp.map { String($0) } ?? absent),
        ]
    }

    /// 곡 제목(`now_playing_title`)은 화면에만 나온다. 로그로는 `StateLogEntry` 를 거치지 않고는 나갈 길이 없다.
    static func fields(media v: MediaValues) -> [StateField] {
        [
            StateField(label: "media_playback_status", value: v.playback ?? absent),
            StateField(label: "audio_volume", value: v.volume.map(number) ?? absent),
            StateField(label: "now_playing_title", value: v.title ?? absent),
            StateField(label: "timestamp", value: v.timestamp.map { String($0) } ?? absent),
        ]
    }

    static func fields(detail v: MediaDetailValues) -> [StateField] {
        [
            StateField(label: "now_playing_duration", value: text(v.duration)),
            StateField(label: "now_playing_elapsed", value: text(v.elapsed)),
            StateField(label: "timestamp", value: v.timestamp.map { String($0) } ?? absent),
        ]
    }

    private static func text(_ n: Int?) -> String { n.map { String($0) } ?? absent }

    private static func number(_ f: Float) -> String { String(format: "%g", Double(f)) }
}

// MARK: - 라이브러리와의 이음매

/// 한 연결에 대한 호출. 조회를 시작할 때 한 번 얻고 이후의 호출은 모두 이 연결에 한다(중간에 연결이 바뀌어도 새 연결로 보내지 않는다).
/// `wakeUp()` 은 여기에 없다: 이 화면은 깨우는 호출을 하지 않는다.
protocol StateLink: Sendable {
    /// `vehicleStatus(timeout: nil)` 로 수면 상태를 읽는다(세션도 키도 필요 없다).
    func readSleep() async -> SleepRead
    /// `startSession(INFOTAINMENT)`. 이미 `Ready` 이면 요청 없이 성공하고 세션을 새로 만들지 않는다.
    func ensureInfotainmentSession() async -> StateSessionOutcome
    /// `getState(category, timeout: nil)` 한 번. 다시 보내지 않는다.
    func getState(_ category: DiagnosticCategory) async -> StateFetch
}

protocol StateDiagnosticsBackend: Sendable {
    /// 지금 연결의 호출 묶음. 연결이 없으면 `nil`.
    func open() -> StateLink?
    func stamp() -> String
    func writeLog(_ entry: StateLogEntry, startedAt: String)
}

// MARK: - 화면 상태

/// Query 탭 상태 진단의 화면 상태. 조회는 사용자 동작으로만 하고 자동으로 다시 하지 않는다.
/// 키를 바꾸는 중이거나 다른 탭의 키 동작이 도는 동안은 시작하지 않고, 도는 동안은 키 교체가 시작하지 못한다(`KeyOperationGate`).
@MainActor
final class StateDiagnosticsModel: ObservableObject {
    @Published private(set) var reading: StateReading?
    /// 이 연결에서 마지막으로 성공한 수동 Drive 조회(Live의 두 영역 화면이 속도를 보일 때 쓴다, SFR-024). 새로 조회하지 않고 읽기만 한다. 연결이 바뀌면 지운다.
    @Published private(set) var lastDrive: StateReading?
    /// 돌고 있는 조회의 범주.
    @Published private(set) var running: DiagnosticCategory?
    /// 조회를 시작하지 못한 이유(결과가 아니다).
    @Published private(set) var notice: String?
    @Published private(set) var inputs = StateInputs(connected: false, hasKey: false, enrolled: false, sleep: "UNKNOWN", busy: false)

    var sleepStatus: String { inputs.sleep }

    private let backend: StateDiagnosticsBackend
    private let gate: KeyOperationGate
    private let otherOperationRunning: @MainActor () -> Bool
    /// 연결이 바뀔 때마다 올라간다. 늦게 끝난 조회가 새 연결의 화면을 덮지 않게 한다.
    private var generation = 0
    /// 이 모델이 열어 둔 키 문(`KeyOperationGate`) 수. 다른 동작이 있는지 가를 때 내 것은 뺀다.
    private var ownOperations = 0

    init(
        backend: StateDiagnosticsBackend, gate: KeyOperationGate,
        otherOperationRunning: @escaping @MainActor () -> Bool = { false }
    ) {
        self.backend = backend
        self.gate = gate
        self.otherOperationRunning = otherOperationRunning
    }

    convenience init(engine: MonitorEngine, otherOperationRunning: @escaping @MainActor () -> Bool) {
        self.init(backend: LiveStateBackend(engine: engine), gate: engine.keyGate, otherOperationRunning: otherOperationRunning)
    }

    // MARK: 입력 변화 (MonitorModel 이 부른다)

    func connectionChanged(connected: Bool, hasKey: Bool) {
        let keyed = connected && hasKey
        guard connected != inputs.connected || keyed != inputs.hasKey else { return }
        generation += 1
        inputs.connected = connected
        inputs.hasKey = keyed
        inputs.sleep = "UNKNOWN" // 새 연결에서 아직 읽지 않았다
        inputs.busy = false
        running = nil
        reading = nil // 값은 읽은 연결의 것이다
        lastDrive = nil
        notice = nil
    }

    func enrollmentChanged(_ enrolled: Bool) {
        inputs.enrolled = enrolled
    }

    /// 성공한 `vehicleStatus()` 의 수면 상태(모니터의 주기 조회). 연결이 없으면 무시한다.
    func sleepQueried(_ name: String) {
        guard inputs.connected else { return }
        inputs.sleep = name
    }

    // MARK: 조회

    /// 조회 한 번. `ignoringSleep` 은 잠든 차 실험이고 수면 확인만 건너뛴다.
    /// 순서: `vehicleStatus()`(수면) → `startSession(INFOTAINMENT)` → `getState` 한 번. 다시 보내지 않고 `wakeUp()` 을 부르지 않는다.
    func run(_ category: DiagnosticCategory, ignoringSleep: Bool) async {
        guard running == nil else { return } // 두 번 누름
        let ready = ignoringSleep ? StateDiagnostics.experimentEnabled(inputs) : StateDiagnostics.buttonsEnabled(inputs)
        guard ready else {
            notice = StateDiagnostics.disabledHint(inputs, experiment: ignoringSleep) ?? "지금은 조회할 수 없습니다"
            return
        }
        // 키를 바꾸는 중이거나 다른 탭의 키 동작이 도는 중이면 시작하지 않는다.
        if otherOperationRunning() || gate.activeOperations > ownOperations || !gate.beginOperation() {
            notice = "다른 키 동작이 진행 중이라 지금은 조회하지 않습니다. 끝난 뒤 다시 누르세요"
            return
        }
        ownOperations += 1
        defer {
            ownOperations -= 1
            gate.endOperation()
        }
        guard let link = backend.open() else {
            notice = "차량에 연결되어 있지 않습니다"
            return
        }
        notice = nil
        let started = generation
        running = category
        inputs.busy = true
        let startedStamp = backend.stamp()
        let startedAt = Date()

        let (outcome, fields) = await execute(link, category, ignoringSleep: ignoringSleep, generation: started)

        // 연결이 바뀌었으면 이 결과는 옛 연결의 것이다: 화면에도 로그에도 올리지 않는다.
        guard started == generation else { return }
        let reading = StateReading(
            category: category, outcome: outcome, at: Date(),
            durationMs: max(0, Int((Date().timeIntervalSince(startedAt) * 1000).rounded())), fields: fields
        )
        self.reading = reading
        if category == .drive, outcome == .success { lastDrive = reading }
        running = nil
        inputs.busy = false
        backend.writeLog(reading.logEntry, startedAt: startedStamp)
    }

    private func execute(
        _ link: StateLink, _ category: DiagnosticCategory, ignoringSleep: Bool, generation started: Int
    ) async -> (StateOutcome, [StateField]) {
        if !ignoringSleep {
            let read = await link.readSleep()
            if started == generation, let name = read.name { inputs.sleep = name } // 성공한 읽기만 버튼 상태를 바꾼다
            switch read {
            case .awake: break
            case .asleep: return (.notSent(.asleep), [])
            case .unknown: return (.notSent(.sleepUnknown), [])
            case .failed(let f): return (.notSent(.sleepReadFailed(f)), [])
            }
        }
        // 이미 세션이 있으면 라이브러리가 요청 없이 성공한다. 캐시된 세션을 차가 받아들이지 않았다면
        // 이전 `getState` 의 `Failure(NoSession)` 이 세션을 `None` 으로 돌려 놓았으므로 여기서 새로 시작한다.
        if case .failed(let f) = await link.ensureInfotainmentSession() { return (.notSent(.sessionFailed(f)), []) }
        switch await link.getState(category) {
        case .success(let fields): return (.success, fields)
        case .uncertain(let f): return (.uncertain(f), [])
        case .failure(let f): return (.failure(f), [])
        }
    }
}
