import Foundation

// 키·제어 탭의 Infotainment 공조·충전(SFR-025)의 순수 규칙. 라이브러리(Kotlin) 타입이 없어서 차량 없이 시험한다.
// 규칙의 정본: `samples/documents/md/05-key-control-tab.md` §Infotainment controls. Android 앱의 `InfotainmentControl.kt` 와 같은 규칙과 같은 문구다.
// 라이브러리 호출 순서의 정본: `documents/md/15-lib-commands.md` §Call order, `14-lib-reading-state.md` A8.2, `16-lib-errors.md` §Per-command re-query.
// 값(`VehicleData`)은 화면에만 가고 줄(로그)에는 가지 않는다(FR-112).

private let readClimateLabel = "공조 상태 읽기"
private let readChargeLabel = "충전 상태 읽기"
private let noValue = "—"

/// 명령이 바꾸는 상태 범주. 명령 뒤 재조회와 읽기 버튼이 `getState(category)` 를 쓴다(`StateCategory.climate` / `.charge`).
enum InfoCategory: CaseIterable, Sendable, Hashable {
    case climate, charge

    var title: String {
        switch self {
        case .climate: return "공조"
        case .charge: return "충전"
        }
    }

    var readLabel: String {
        switch self {
        case .climate: return readClimateLabel
        case .charge: return readChargeLabel
        }
    }

    var unconfirmedText: String {
        switch self {
        case .climate: return "공조 결과 미확인"
        case .charge: return "충전 결과 미확인"
        }
    }
}

/// 버튼 표(`05-key-control-tab.md` §Infotainment controls). `isRead` 는 `getState` 읽기 버튼이고 나머지는 명령이다.
enum InfoCommand: CaseIterable, Sendable, Hashable, Identifiable {
    case climateOn, climateOff, readClimate
    case chargeStart, chargeStop, readCharge

    var id: Self { self }

    var label: String {
        switch self {
        case .climateOn: return "공조 켜기"
        case .climateOff: return "공조 끄기"
        case .readClimate: return readClimateLabel
        case .chargeStart: return "충전 시작"
        case .chargeStop: return "충전 중지"
        case .readCharge: return readChargeLabel
        }
    }

    var category: InfoCategory {
        switch self {
        case .climateOn, .climateOff, .readClimate: return .climate
        case .chargeStart, .chargeStop, .readCharge: return .charge
        }
    }

    var isRead: Bool { self == .readClimate || self == .readCharge }

    var symbol: String {
        switch self {
        case .climateOn: return "fan.fill"
        case .climateOff: return "fan.slash"
        case .readClimate: return "thermometer.medium"
        case .chargeStart: return "bolt.fill"
        case .chargeStop: return "bolt.slash.fill"
        case .readCharge: return "battery.100percent"
        }
    }
}

/// 버튼 활성 규칙: 4단계 규칙과 같다. 세션은 누르면 시작하므로 입력에 세션 항목이 없다.
enum InfotainmentControl {
    static let sectionTitle = "Infotainment (공조·충전)"

    static func enabled(_ i: KeyControlInputs) -> Bool { KeyControlSteps(i).control.buttonEnabled }

    /// 지금 보내도 되는가. 안 되면 이유 한 줄(VCSEC 버튼과 같은 문구), 되면 `nil`.
    static func blockedReason(_ i: KeyControlInputs) -> String? { KeyControlSteps.controlBlockReason(i) }
}

// MARK: - 한 번 누르기의 결과

/// 한 번 누르기의 끝.
enum InfoKind: Sendable, Equatable {
    case success, uncertain, failure, notSent
}

/// 차량에 보내지 않고 멈춘 이유. 오류는 **종류 이름**만 담는다.
enum InfoNotSentReason: Sendable, Equatable {
    case asleep
    case sleepUnknown
    case sleepReadFailed(errorType: String)
    case sessionFailed(errorType: String)
}

/// 한 번 누르기의 결과. `errorType` 은 라이브러리 오류 종류 이름이고 `message` 나 차량이 보낸 글은 담지 않는다.
/// `status` 는 이번에 읽은 `vehicleStatus()`(상태 카드용), `enrollment` 는 이 결과로 알게 된 등록 상태, `durationMs` 는 첫 호출부터 끝까지.
struct InfoOutcome<Value: Sendable>: Sendable {
    let kind: InfoKind
    var value: Value?
    var errorType: String?
    var notSent: InfoNotSentReason?
    var enrollment: Enrollment?
    var status: StatusCard?
    var durationMs: Int

    init(
        _ kind: InfoKind, value: Value? = nil, errorType: String? = nil, notSent: InfoNotSentReason? = nil,
        enrollment: Enrollment? = nil, status: StatusCard? = nil, durationMs: Int = 0
    ) {
        self.kind = kind
        self.value = value
        self.errorType = errorType
        self.notSent = notSent
        self.enrollment = enrollment
        self.status = status
        self.durationMs = durationMs
    }
}

/// `vehicleStatus()` 한 번(수면 확인). 성공하면 상태 카드 값을 들고 있다.
enum InfoStatusRead: Sendable, Equatable {
    case success(StatusCard)
    /// 실패이거나 불확실: 오류 종류 이름.
    case failed(errorType: String)
}

/// `startSession(setOf(INFOTAINMENT))` 한 번. `Failure`·`Uncertain` 모두 `failed`(아직 아무것도 보내지 않았다).
enum InfoSessionResult: Sendable, Equatable {
    case ready
    case failed(errorType: String)
}

/// 값이 없는 명령 결과의 자리(`Void` 는 `Sendable` 이 아니라서 쓰지 못한다).
struct InfoNoValue: Sendable, Equatable {}

/// 명령이나 `getState` 한 번의 결과.
enum InfoCallResult<Value: Sendable>: Sendable {
    case success(Value)
    case uncertain(errorType: String)
    case failure(errorType: String)
}

/// 로그에 가는 유일한 오류 정보: 종류 이름(`ResponseTooLarge`, `Timeout(afterSend=…)`, `NoSession`, 아니면 클래스 이름).
/// 라이브러리 오류에서 이 이름을 만드는 곳은 `VehicleMapping.swift` 의 `VehicleError.infotainmentTypeName` 이다.
enum InfotainmentErrors {
    static let keyNotPaired = "KeyNotPaired"
    static let locked = "Locked"
    static let responseTooLarge = "ResponseTooLarge"

    static func timeout(afterSend: Bool) -> String { "Timeout(afterSend=\(afterSend))" }
}

// MARK: - 실행 규칙

/// 누르기 한 번의 실행 규칙과 결과 줄. 호출은 모두 클로저로 받아 차량 없이 시험한다.
enum InfotainmentRunner {
    /// 1. `readStatus`: `AWAKE` 일 때만 계속한다(아니면 NotSent, 세션도 시작하지 않고 깨우지도 않는다).
    /// 2. `ensureSession`: 실패나 `Uncertain` 이면 NotSent.
    /// 3. `call` 을 **정확히 한 번** 부른다(`Uncertain` 도 다시 보내지 않고, 끝난 뒤 `getState` 를 따로 부르지 않는다).
    /// `now` 는 단조 시계(초). 시험이 시간을 움직이려고 받는다.
    static func run<Value: Sendable>(
        readStatus: @Sendable () async -> InfoStatusRead,
        ensureSession: @Sendable () async -> InfoSessionResult,
        call: @Sendable () async -> InfoCallResult<Value>,
        now: @Sendable () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }
    ) async -> InfoOutcome<Value> {
        let start = now()
        func ms() -> Int { max(0, Int(((now() - start) * 1000).rounded())) }

        let read = await readStatus()
        var status: StatusCard?
        switch read {
        case .success(let card):
            status = card
            switch SleepRead.from(name: card.sleep) {
            case .awake: break
            case .asleep:
                return InfoOutcome(.notSent, notSent: .asleep, status: status, durationMs: ms())
            default:
                return InfoOutcome(.notSent, notSent: .sleepUnknown, status: status, durationMs: ms())
            }
        case .failed(let type):
            return InfoOutcome(.notSent, errorType: type, notSent: .sleepReadFailed(errorType: type), durationMs: ms())
        }

        if case .failed(let type) = await ensureSession() {
            return InfoOutcome(
                .notSent, errorType: type, notSent: .sessionFailed(errorType: type),
                enrollment: type == InfotainmentErrors.keyNotPaired ? .notEnrolled : nil, status: status, durationMs: ms()
            )
        }

        switch await call() {
        case .success(let value):
            return InfoOutcome(.success, value: value, status: status, durationMs: ms())
        case .uncertain(let type):
            return InfoOutcome(.uncertain, errorType: type, status: status, durationMs: ms())
        case .failure(let type):
            return InfoOutcome(
                .failure, errorType: type, enrollment: type == InfotainmentErrors.keyNotPaired ? .notEnrolled : nil,
                status: status, durationMs: ms()
            )
        }
    }

    /// 결과 줄: `<버튼>: <결과> (<오류 종류>) <n> ms` 와, `Uncertain`·실패·NotSent 에는 고정 문장 하나. 값과 `message` 는 담지 않는다.
    static func resultLine<Value>(_ cmd: InfoCommand, _ outcome: InfoOutcome<Value>) -> String {
        "\(cmd.label): \(resultText(cmd, outcome))"
    }

    /// 결과 줄에서 버튼 이름을 뺀 부분(결과 목록의 `title` 은 버튼 이름이다).
    static func resultText<Value>(_ cmd: InfoCommand, _ outcome: InfoOutcome<Value>) -> String {
        let word: String
        switch outcome.kind {
        case .success: word = "성공"
        case .uncertain: word = "불확실"
        case .failure: word = "실패"
        case .notSent: word = "보내지 않음"
        }
        let type = outcome.errorType.map { " (\($0))" } ?? ""
        let head = "\(word)\(type) \(outcome.durationMs) ms"
        return note(cmd, outcome).map { "\(head)\n  \($0)" } ?? head
    }

    private static func note<Value>(_ cmd: InfoCommand, _ outcome: InfoOutcome<Value>) -> String? {
        switch outcome.kind {
        case .success: return nil
        case .uncertain: return uncertainNote(cmd)
        case .failure: return failureNote(outcome.errorType)
        case .notSent: return notSentNote(outcome.notSent)
        }
    }

    private static func uncertainNote(_ cmd: InfoCommand) -> String {
        cmd.isRead
            ? "값을 확인하지 못했습니다. 조회는 차량을 바꾸지 않으니 다시 읽어도 됩니다. 마지막 값은 그대로 둡니다"
            : "차량이 실행했을 수 있습니다. 다시 보내지 않습니다. '\(cmd.category.readLabel)'로 상태를 확인하세요"
    }

    private static func failureNote(_ type: String?) -> String? {
        switch type {
        case "InfotainmentRejected": return "차량이 거부했습니다"
        case "NoSession": return "세션이 없어 보내지 않았습니다"
        case "KeyNotPaired": return "이 키가 차량에 등록되어 있지 않습니다"
        default: return nil
        }
    }

    private static func notSentNote(_ reason: InfoNotSentReason?) -> String? {
        switch reason {
        case .asleep:
            return "차량이 자고 있습니다. 앱은 차를 자동으로 깨우지 않습니다. 문 그룹의 깨우기 버튼으로 깨우고, 상태 카드가 깨어 있음으로 바뀌면 다시 누르세요"
        case .sleepUnknown:
            return "수면 상태를 알 수 없어 보내지 않았습니다. 상태 새로고침 뒤 다시 누르세요"
        case .sleepReadFailed:
            return "수면 상태를 읽지 못해 보내지 않았습니다"
        case .sessionFailed(let type):
            switch type {
            case "KeyNotPaired": return "이 키가 차량에 등록되어 있지 않아 보내지 않았습니다"
            case "Locked": return "폰을 잠금 해제한 뒤 다시 누르세요(보내지 않음)"
            default: return "Infotainment 세션을 시작하지 못해 보내지 않았습니다"
            }
        case nil:
            return nil
        }
    }

    /// 연결이 바뀐 뒤에 끝난 호출의 줄: 결과는 버리고 명령 이름만 남긴다.
    static func droppedLine(_ cmd: InfoCommand) -> String { "\(cmd.label): \(droppedText(cmd))" }

    static func droppedText(_ cmd: InfoCommand) -> String {
        cmd.isRead
            ? "연결이 바뀌어 결과를 버렸습니다"
            : "연결이 바뀌어 결과를 버렸습니다. 차량이 실행했을 수 있으니 다시 연결한 뒤 상태를 읽으세요"
    }
}

// MARK: - 읽은 값

/// `getState(.climate)` 의 이름 붙은 필드(없는 값은 `nil`, `0` 은 `0`). `VehicleData` 에서 꺼내는 곳은 `VehicleMapping.swift`.
struct InfoClimateValues: Sendable, Equatable {
    var isClimateOn: Bool?
    var insideTempCelsius: Float?
    var driverTempSetting: Float?
    var timestamp: Int64?

    init(isClimateOn: Bool? = nil, insideTempCelsius: Float? = nil, driverTempSetting: Float? = nil, timestamp: Int64? = nil) {
        self.isClimateOn = isClimateOn
        self.insideTempCelsius = insideTempCelsius
        self.driverTempSetting = driverTempSetting
        self.timestamp = timestamp
    }
}

/// `getState(.charge)` 의 이름 붙은 필드. `charging_state` 는 읽지 않는다(`05-key-control-tab.md` §Readout 의 알려진 공백): `charger_power` 가 충전 중인지의 눈에 보이는 신호다.
struct InfoChargeValues: Sendable, Equatable {
    var batteryLevel: Int?
    var chargeLimitSoc: Int?
    var chargerPower: Int?
    var chargePortDoorOpen: Bool?
    var timestamp: Int64?

    init(batteryLevel: Int? = nil, chargeLimitSoc: Int? = nil, chargerPower: Int? = nil, chargePortDoorOpen: Bool? = nil, timestamp: Int64? = nil) {
        self.batteryLevel = batteryLevel
        self.chargeLimitSoc = chargeLimitSoc
        self.chargerPower = chargerPower
        self.chargePortDoorOpen = chargePortDoorOpen
        self.timestamp = timestamp
    }
}

enum InfoValues: Sendable, Equatable {
    case climate(InfoClimateValues)
    case charge(InfoChargeValues)

    var category: InfoCategory {
        switch self {
        case .climate: return .climate
        case .charge: return .charge
        }
    }

    /// 응답에 그 범주의 메시지가 없을 때(모든 값이 `—`).
    static func empty(_ category: InfoCategory) -> InfoValues {
        switch category {
        case .climate: return .climate(InfoClimateValues())
        case .charge: return .charge(InfoChargeValues())
        }
    }
}

/// 화면에 보이는 한 줄(값은 화면에만 간다).
struct InfoField: Sendable, Equatable {
    let label: String
    let value: String
}

/// 한 범주의 마지막 읽기. `timestampSeconds` 는 응답의 `timestamp`(없으면 `nil`), `at` 은 읽은 시각.
struct InfoReading: Sendable, Equatable {
    let category: InfoCategory
    let fields: [InfoField]
    let timestampSeconds: Int64?
    let at: Date
    let sameTimestampAsPrevious: Bool

    /// 이름 붙은 필드만 읽는다. 없는 값은 `—`, `0` 은 `0`(`14-lib-reading-state.md` A8.3).
    static func of(_ values: InfoValues, at: Date, previous: InfoReading?) -> InfoReading {
        let fields: [InfoField]
        let ts: Int64?
        switch values {
        case .climate(let c):
            fields = [
                field("is_climate_on", c.isClimateOn),
                field("inside_temp_celsius", c.insideTempCelsius),
                field("driver_temp_setting", c.driverTempSetting),
            ]
            ts = c.timestamp
        case .charge(let c):
            fields = [
                field("battery_level", c.batteryLevel),
                field("charge_limit_soc", c.chargeLimitSoc),
                field("charger_power", c.chargerPower),
                field("charge_port_door_open", c.chargePortDoorOpen),
            ]
            ts = c.timestamp
        }
        return InfoReading(
            category: values.category, fields: fields, timestampSeconds: ts, at: at,
            sameTimestampAsPrevious: ts != nil && previous?.timestampSeconds == ts
        )
    }

    private static func field<V>(_ label: String, _ value: V?) -> InfoField {
        InfoField(label: label, value: value.map { "\($0)" } ?? noValue)
    }

    /// 화면의 `timestamp` 줄 값.
    var timestampText: String { timestampSeconds.map { String($0) } ?? noValue }
}

// MARK: - 화면 상태

/// 화면 상태: 결과가 미확인인 범주와 범주별 마지막 읽기. 연결이 바뀌면 `afterConnectionChange` 로 되돌린다.
struct InfoUiState: Sendable, Equatable {
    var unconfirmed: Set<InfoCategory> = []
    var readings: [InfoCategory: InfoReading] = [:]

    static let afterConnectionChange = InfoUiState()
}

/// 결과가 화면 상태에 미치는 영향. `current` 가 `false`(연결이 바뀐 뒤 늦게 끝난 호출)이면 상태를 전혀 바꾸지 않는다.
enum InfotainmentReducer {
    /// `Uncertain` → 그 범주를 미확인으로, `Success` → 미확인 해제. 그 밖의 결과는 표시를 바꾸지 않는다.
    static func afterCommand<Value>(_ state: InfoUiState, _ cmd: InfoCommand, _ outcome: InfoOutcome<Value>, current: Bool) -> InfoUiState {
        guard current else { return state }
        var next = state
        switch outcome.kind {
        case .uncertain: next.unconfirmed.insert(cmd.category)
        case .success: next.unconfirmed.remove(cmd.category)
        case .failure, .notSent: break
        }
        return next
    }

    /// `Success` → 읽은 값을 저장하고 미확인 해제. `Uncertain`/`Failure` 는 마지막 값과 표시를 그대로 둔다.
    static func afterRead(
        _ state: InfoUiState, _ category: InfoCategory, _ outcome: InfoOutcome<InfoValues>, current: Bool, at: Date
    ) -> InfoUiState {
        guard current, outcome.kind == .success else { return state }
        var next = state
        next.readings[category] = InfoReading.of(outcome.value ?? .empty(category), at: at, previous: state.readings[category])
        next.unconfirmed.remove(category)
        return next
    }
}
