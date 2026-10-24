import Foundation

// 키·제어 탭(SFR-018)의 순수 규칙. 라이브러리(Kotlin) 타입이 없어서 차량 없이 시험한다.
// 규칙의 정본: `samples/documents/md/05-key-control-tab.md`. Android 앱의 `KeyControl.kt` 와 같은 규칙이다.

/// 모니터 키가 차량에 등록되어 있는지(탭이 아는 만큼).
enum Enrollment: Sendable, Equatable {
    case unknown
    case checking
    case notEnrolled
    case enrolled
}

// MARK: - 단계 표

enum StepState: Sendable, Equatable {
    case done
    case todo
    case blocked
}

/// 단계 표의 입력(`05-key-control-tab.md` §Steps and enabling rules).
struct KeyControlInputs: Sendable, Equatable {
    /// 모니터 키가 있고 읽을 수 있다.
    var keyReady = false
    /// 폰 잠금 때문에 키를 읽을 수 없다.
    var keyLocked = false
    var vinSaved = false
    var connected = false
    var enrollment = Enrollment.unknown
    /// 이 탭의 라이브러리 호출이 진행 중이다.
    var busy = false
}

struct StepInfo: Sendable, Equatable {
    let state: StepState
    let hint: String
    let buttonEnabled: Bool
}

/// 네 단계의 상태·힌트·버튼 활성 여부를 입력에서 계산한다(순수 함수).
struct KeyControlSteps: Sendable, Equatable {
    static let keyLockedHint = "폰을 잠금 해제한 뒤 다시 누르세요"
    static let noVinHint = "Live 탭에서 VIN을 저장하세요"
    static let finishFirstHint = "1·2단계를 먼저 끝내세요"
    static let notEnrolledHint = "키를 차량에 등록하면 버튼이 켜집니다"

    let key: StepInfo
    let connect: StepInfo
    let enroll: StepInfo
    let control: StepInfo
    /// "등록 상태 확인" 버튼. 이미 등록으로 보여도 다시 확인할 수 있다(차량에서 키를 지웠을 수 있다).
    let checkEnrollmentEnabled: Bool
    /// "상태 새로고침" 버튼. `vehicleStatus()` 는 키 없이도 되므로 연결만 필요하다.
    let refreshStatusEnabled: Bool

    init(_ i: KeyControlInputs) {
        let idle = !i.busy
        let ready = i.keyReady && i.connected

        // 1 Key: 키가 준비되면 완료(준비된 키는 잠겨 있을 수 없다). 잠금이면 막힘, 버튼은 잠금 해제 뒤 다시 누르는 용도로 켠다.
        if i.keyReady {
            key = StepInfo(state: .done, hint: "모니터 키가 준비되었습니다", buttonEnabled: idle)
        } else if i.keyLocked {
            key = StepInfo(state: .blocked, hint: Self.keyLockedHint, buttonEnabled: idle)
        } else {
            key = StepInfo(state: .todo, hint: "키를 만들거나 불러옵니다", buttonEnabled: idle)
        }

        // 2 Connect
        if i.connected {
            connect = StepInfo(state: .done, hint: "차량과 연결되었습니다", buttonEnabled: false)
        } else if !i.vinSaved {
            connect = StepInfo(state: .blocked, hint: Self.noVinHint, buttonEnabled: false)
        } else {
            connect = StepInfo(state: .todo, hint: "저장된 VIN으로 차량에 연결합니다", buttonEnabled: idle)
        }

        // 3 Enroll
        if i.enrollment == .enrolled {
            enroll = StepInfo(state: .done, hint: "키가 차량에 등록되어 있습니다", buttonEnabled: false)
        } else if !ready {
            enroll = StepInfo(state: .blocked, hint: Self.finishFirstHint, buttonEnabled: false)
        } else {
            let hint: String
            switch i.enrollment {
            case .checking: hint = "등록 상태를 확인하는 중입니다"
            case .notEnrolled: hint = "차량에 등록되어 있지 않습니다. 등록을 요청하세요"
            default: hint = "등록 상태를 모릅니다. 확인하거나 등록을 요청하세요"
            }
            enroll = StepInfo(state: .todo, hint: hint, buttonEnabled: idle && i.enrollment != .checking)
        }

        // 4 Control: 끝난 상태가 없다(.todo = 쓸 수 있음).
        if i.enrollment != .enrolled {
            control = StepInfo(state: .blocked, hint: Self.notEnrolledHint, buttonEnabled: false)
        } else {
            control = StepInfo(state: .todo, hint: "버튼을 눌러 차량을 제어합니다", buttonEnabled: idle && ready)
        }

        checkEnrollmentEnabled = idle && ready && i.enrollment != .checking
        refreshStatusEnabled = idle && i.connected
    }
}

// MARK: - 버튼 표

/// 제어 버튼(`05-key-control-tab.md` §Buttons). 부르는 라이브러리 함수는 `BleClient.send(_:)` 가 고른다.
enum ControlButton: CaseIterable, Sendable, Hashable, Identifiable {
    case lock, unlock, wakeUp
    case actuateTrunk, closeTrunk, openFrunk
    case remoteDrive
    case autoSecure
    case openTonneau, closeTonneau, stopTonneau

    /// 화면의 버튼 묶음. 앞의 셋은 늘 보이고, `modelX`·`cybertruck` 은 접힌 "다른 차종" 칸에 있다.
    enum Group: CaseIterable, Sendable, Equatable {
        case doors, trunk, drive, modelX, cybertruck

        static let primary: [Group] = [.doors, .trunk, .drive]
        static let otherModels: [Group] = [.modelX, .cybertruck]

        var title: String {
            switch self {
            case .doors: return "문"
            case .trunk: return "트렁크"
            case .drive: return "주행"
            case .modelX: return "Model X"
            case .cybertruck: return "Cybertruck"
            }
        }
    }

    struct Confirmation: Sendable, Equatable {
        let title: String
        let message: String
        let action: String
    }

    var id: Self { self }

    var title: String {
        switch self {
        case .lock: return "잠금"
        case .unlock: return "잠금 해제"
        case .wakeUp: return "깨우기"
        case .actuateTrunk: return "트렁크 열기/닫기"
        case .closeTrunk: return "트렁크 닫기"
        case .openFrunk: return "프렁크 열기"
        case .remoteDrive: return "원격 주행 허용"
        case .autoSecure: return "자동 잠금"
        case .openTonneau: return "토노 열기"
        case .closeTonneau: return "토노 닫기"
        case .stopTonneau: return "토노 정지"
        }
    }

    var symbol: String {
        switch self {
        case .lock: return "lock.fill"
        case .unlock: return "lock.open.fill"
        case .wakeUp: return "bolt.fill"
        case .actuateTrunk: return "arrow.up.and.down.square"
        case .closeTrunk: return "arrow.down.square"
        case .openFrunk: return "car.front.waves.up"
        case .remoteDrive: return "steeringwheel"
        case .autoSecure: return "lock.shield.fill"
        case .openTonneau: return "arrow.up.to.line"
        case .closeTonneau: return "arrow.down.to.line"
        case .stopTonneau: return "stop.fill"
        }
    }

    var group: Group {
        switch self {
        case .lock, .unlock, .wakeUp: return .doors
        case .actuateTrunk, .closeTrunk, .openFrunk: return .trunk
        case .remoteDrive: return .drive
        case .autoSecure: return .modelX
        case .openTonneau, .closeTonneau, .stopTonneau: return .cybertruck
        }
    }

    /// 누르기 전에 물어볼 말. 되돌릴 수 없거나 영향이 큰 두 버튼만.
    var confirmation: Confirmation? {
        switch self {
        case .openFrunk:
            return Confirmation(title: "프렁크를 열까요?", message: "프렁크는 원격으로 닫을 수 없습니다.", action: "프렁크 열기")
        case .remoteDrive:
            return Confirmation(title: "원격 주행을 허용할까요?", message: "원격 주행 허용 명령을 차량에 보냅니다.", action: "원격 주행 허용")
        default:
            return nil
        }
    }
}

// MARK: - 상태 카드

/// 마지막 `vehicleStatus()` 의 잠금·트렁크·프렁크·수면(값은 Kotlin enum 이름).
struct StatusCard: Sendable, Equatable {
    let lock: String
    let trunk: String
    let frunk: String
    let sleep: String
    let at: Date

    /// 매핑은 `StatusSnapshot(_: VehicleStatus_)` 한 곳에서 하고, 카드는 거기서 네 값만 고른다.
    init(_ s: StatusSnapshot, at: Date) {
        self.init(lock: s.lock, trunk: s.trunk, frunk: s.frunk, sleep: s.sleep, at: at)
    }

    init(lock: String, trunk: String, frunk: String, sleep: String, at: Date) {
        self.lock = lock
        self.trunk = trunk
        self.frunk = frunk
        self.sleep = sleep
        self.at = at
    }

    var lockText: String {
        Self.label(lock, ["LOCKED": "잠김", "UNLOCKED": "잠금 해제", "INTERNAL_LOCKED": "내부 잠김", "SELECTIVE_UNLOCKED": "일부 해제", "UNKNOWN": "알 수 없음"])
    }

    var trunkText: String { Self.closure(trunk) }
    var frunkText: String { Self.closure(frunk) }

    var sleepText: String {
        Self.label(sleep, ["AWAKE": "깨어 있음", "ASLEEP": "수면 중", "UNKNOWN": "알 수 없음"])
    }

    private static func closure(_ name: String) -> String {
        label(name, ["CLOSED": "닫힘", "OPEN": "열림", "AJAR": "살짝 열림", "UNKNOWN": "알 수 없음"])
    }

    /// 모르는 값은 원래 이름 그대로 보여 준다(새 값이 생겨도 숨기지 않는다).
    private static func label(_ name: String, _ table: [String: String]) -> String { table[name] ?? name }
}

// MARK: - 명령 실행

/// 세션 보장(`startSession(setOf(VCSEC))`)의 결과.
enum SessionOutcome: Sendable, Equatable {
    case ready
    /// `KeyNotPaired`: 이 키가 차량에 없다.
    case notEnrolled
    /// `KeyStoreError.Locked`: 폰 잠금 때문에 키를 읽지 못했다.
    case locked
    case failed(String)
}

enum CommandOutcome: Sendable, Equatable {
    case success
    case failure(String)
    case uncertain(String)
}

/// 재조회(`vehicleStatus()`)의 결과. 실패하면 `card` 가 `nil` 이라 화면의 이전 값이 남는다.
struct RequeryOutcome: Sendable, Equatable {
    let text: String
    let card: StatusCard?
}

struct KeyControlReport: Sendable, Equatable {
    enum Kind: Sendable, Equatable {
        case success, failure, uncertain
        /// 세션이 없어서 명령을 보내지 않았다.
        case notSent
    }

    let kind: Kind
    /// 결과 목록에 쓸 한 줄(버튼 이름은 붙이지 않았다).
    let line: String
    /// 등록 상태를 바꿔야 하면 그 값(`KeyNotPaired` → `.notEnrolled`).
    let enrollment: Enrollment?
    /// 새로 읽은 상태 카드.
    let status: StatusCard?
}

enum KeyControlRunner {
    /// 명령 한 번: (1) 세션 보장, 실패하면 명령을 보내지 않는다 (2) 명령은 정확히 한 번, `Uncertain` 도 다시 보내지 않는다
    /// (3) 보낸 명령 뒤에는 결과와 상관없이 재조회한다(`16-lib-errors.md` §Per-command re-query).
    static func run(
        ensureSession: @Sendable () async -> SessionOutcome,
        command: @Sendable () async -> CommandOutcome,
        requery: @Sendable () async -> RequeryOutcome
    ) async -> KeyControlReport {
        switch await ensureSession() {
        case .ready:
            break
        case .notEnrolled:
            return KeyControlReport(
                kind: .notSent, line: "보내지 않음: 이 키는 차량에 등록되어 있지 않습니다(KeyNotPaired). 3단계에서 등록하세요",
                enrollment: .notEnrolled, status: nil
            )
        case .locked:
            return KeyControlReport(
                kind: .notSent, line: "보내지 않음: 키가 잠겨 있습니다. 폰을 잠금 해제한 뒤 다시 누르세요",
                enrollment: nil, status: nil
            )
        case .failed(let why):
            return KeyControlReport(kind: .notSent, line: "보내지 않음: 세션 실패: \(why)", enrollment: nil, status: nil)
        }

        let outcome = await command()
        let after = await requery()
        switch outcome {
        case .success:
            return KeyControlReport(kind: .success, line: "성공", enrollment: nil, status: after.card)
        case .failure(let why):
            return KeyControlReport(kind: .failure, line: "실패: \(why)", enrollment: nil, status: after.card)
        case .uncertain(let why):
            return KeyControlReport(kind: .uncertain, line: "불확실: \(why) → 재조회: \(after.text)", enrollment: nil, status: after.card)
        }
    }
}
