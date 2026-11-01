import Foundation

// 키 등록 흐름(`addKeyRequest` + 등록 확인)이 화면에 알리는 변화. 라이브러리(Kotlin) 타입이 없어 차량 없이 시험한다.
// Control 탭은 `line` 을 결과 줄로 쓰고, 키·제어 탭은 이 값으로 진행 팝업을 그린다(`05-key-control-tab.md` §Enrollment progress popup).

/// 등록 흐름의 한 단계. `BleClient.pair` 가 순서대로 보낸다.
enum EnrollmentUpdate: Sendable, Equatable {
    /// 시작하지 못했다(연결이나 키가 없다). 문장은 그대로 결과 줄이다.
    case blocked(String)
    case sent
    case waitingForTap
    case accepted
    case rejected(String)
    /// 모르는 응답(16진수 문자열).
    case unknown(String)
    case failed(String)
    /// 등록 요청이 시간 안에 끝나지 않았다. 등록 여부를 확인하러 넘어간다.
    case timedOut
    /// 등록이 확인됐다(`line` 은 결과 줄).
    case confirmed(String)
    /// 확인 시간(2분) 안에 등록이 확인되지 않았다.
    case notConfirmed(String)
    /// 등록 확인이 실패로 끝났다(예: 연결이 끊김). 그 밖의 `Failure` 라 더 묻지 않았다.
    case confirmFailed(String)
    /// 등록 확인의 `attempt` 번째 시도를 시작한다(`elapsed` 는 확인을 시작한 뒤 지난 시간, 초).
    case confirming(attempt: Int, elapsed: TimeInterval)
    /// 사용자가 기다리기를 멈췄다. 이미 보낸 요청은 되돌릴 수 없다.
    case cancelled

    /// 마지막 확인 결과를 알림으로 고른다. 취소가 먼저고, 그다음 실패, 아니면 시간 안에 확인되지 않음.
    static func finished(enrolled: Bool, line: String, failure: String?, cancelled: Bool) -> EnrollmentUpdate {
        if enrolled { return .confirmed(line) }
        if cancelled { return .cancelled }
        if let failure { return .confirmFailed(failure) }
        return .notConfirmed(line)
    }

    /// Control 탭 결과 줄에 쓰는 한 문장. 진행 알림(`confirming`)은 줄을 남기지 않는다(3초마다 와서 목록을 채운다).
    var line: String? {
        switch self {
        case .blocked(let text): return text
        case .sent: return "요청 보냄 — 중앙 콘솔에 키카드를 대고 화면에서 승인하세요"
        case .waitingForTap: return "카드 태그 대기 중"
        case .accepted: return "차량이 승인함 — Infotainment 동기화 확인 중"
        case .rejected(let why): return "거부: \(why)"
        case .unknown(let hex): return "모르는 응답 \(hex)"
        case .failed(let why): return "실패: \(why)"
        case .timedOut: return "시간 초과 — 등록 여부를 확인합니다"
        case .confirmed(let line), .notConfirmed(let line), .confirmFailed(let line): return line
        case .confirming: return nil
        case .cancelled: return "등록 기다리기를 멈췄습니다(이미 보낸 요청은 되돌릴 수 없음)"
        }
    }
}

/// 키 등록 진행 팝업의 상태(SFR-021). `EnrollmentUpdate` 를 차례로 받아 단계 표시·문구·버튼 규칙을 정한다(순수).
struct EnrollmentPopup: Equatable, Sendable {
    /// 등록 확인을 기다리는 최대 시간(초). `EnrollmentWaiter.wait` 의 기본값과 같다.
    static let timeout: TimeInterval = 120

    enum Phase: Equatable, Sendable {
        /// 요청을 차량에 보내는 중.
        case requesting
        /// 요청을 보냈다: 키카드를 대고 차량 화면에서 승인한다.
        case waitingForCard
        /// 차량이 승인했거나 요청이 시간 초과라 등록 여부를 3초마다 확인하는 중.
        case confirming
        case succeeded
        /// 2분 안에 확인되지 않았다.
        case notConfirmed
        case failed(String)
        case cancelled
    }

    enum Mark: Equatable, Sendable { case done, current, pending, failed }

    struct Row: Equatable, Sendable {
        let title: String
        let mark: Mark
    }

    private static let titles = ["등록 요청 보내기", "키카드 태그 + 차량 화면에서 승인", "등록 확인 (3초마다, 최대 2분)"]

    private(set) var phase = Phase.requesting
    private(set) var attempt = 0
    private(set) var elapsed: TimeInterval = 0
    /// 요청이 시간 초과로 끝나 확인으로 넘어왔다.
    private var requestTimedOut = false
    private var cancelling = false
    /// 도달한 가장 뒤 단계(0 요청, 1 카드, 2 확인).
    private var reached = 0

    init() {}

    var isActive: Bool {
        switch phase {
        case .requesting, .waitingForCard, .confirming: return true
        case .succeeded, .notConfirmed, .failed, .cancelled: return false
        }
    }

    /// 취소 버튼은 진행 중이고 아직 취소를 요청하지 않았을 때만.
    var canCancel: Bool { isActive && !cancelling }
    /// 닫기 버튼은 끝난 뒤에만(진행 중에는 닫을 수 없다: 취소로 멈춘다).
    var canClose: Bool { !isActive }

    var remaining: TimeInterval { max(0, Self.timeout - elapsed) }
    var progressFraction: Double { min(1, max(0, elapsed / Self.timeout)) }

    var progressText: String {
        "확인 \(attempt)번째 · 경과 \(Self.clockText(elapsed)) / \(Self.clockText(Self.timeout)) · 남은 시간 \(Self.clockText(remaining))"
    }

    var title: String {
        switch phase {
        case .requesting, .waitingForCard, .confirming: return "키 등록 중"
        case .succeeded: return "등록 완료"
        case .notConfirmed: return "등록이 확인되지 않았습니다"
        case .failed: return "등록 실패"
        case .cancelled: return "등록 기다리기를 멈췄습니다"
        }
    }

    var message: String {
        if cancelling, isActive { return "멈추는 중…" }
        switch phase {
        case .requesting:
            return "등록 요청을 차량에 보내는 중입니다…"
        case .waitingForCard:
            return "요청을 보냈습니다. 차량 중앙 콘솔에 키카드를 대고, 차량 화면에서 승인하세요."
        case .confirming:
            if attempt > 0 { return progressText }
            return requestTimedOut ? "시간 초과 — 등록 여부를 확인하는 중입니다…" : "차량이 승인했습니다. 등록을 확인하는 중입니다…"
        case .succeeded:
            return "키가 차량에 등록되었습니다. 4단계 버튼을 쓸 수 있습니다."
        case .notConfirmed:
            return "2분 안에 등록이 확인되지 않았습니다. 차량 화면에서 승인했는지 확인한 뒤 '등록 상태 확인'을 누르세요."
        case .failed(let why):
            return "실패: \(why)"
        case .cancelled:
            return "기다리기를 멈췄습니다. 이미 차량에 보낸 요청은 되돌릴 수 없어, 차량 화면의 승인 창이 남아 있을 수 있습니다. 그 뒤 카드를 대면 등록될 수 있으니 '등록 상태 확인'으로 확인하세요."
        }
    }

    /// 세 단계의 표시. 끝난 뒤의 실패·취소는 도달한 단계에 `failed` 를 단다.
    var rows: [Row] {
        Self.titles.enumerated().map { index, title in
            let mark: Mark
            if phase == .succeeded {
                mark = .done
            } else if index < reached {
                mark = .done
            } else if index == reached {
                mark = isActive ? .current : .failed
            } else {
                mark = .pending
            }
            return Row(title: title, mark: mark)
        }
    }

    mutating func apply(_ update: EnrollmentUpdate) {
        guard isActive else { return } // 끝난 뒤의 늦은 알림은 버린다
        switch update {
        case .blocked(let text): finish(.failed(text))
        case .sent, .waitingForTap: phase = .waitingForCard; reached = max(reached, 1)
        case .accepted: phase = .confirming; reached = 2
        case .timedOut: phase = .confirming; reached = 2; requestTimedOut = true
        case .confirming(let n, let seconds): phase = .confirming; reached = 2; attempt = n; elapsed = seconds
        case .rejected(let why), .failed(let why), .confirmFailed(let why): finish(.failed(why))
        case .unknown: break
        case .confirmed: finish(.succeeded)
        case .notConfirmed: finish(.notConfirmed)
        case .cancelled: finish(.cancelled)
        }
    }

    /// 사용자가 취소를 눌렀다. 실제 멈춤은 흐름이 `cancelled` 를 보낼 때 끝난다.
    mutating func requestCancel() {
        guard canCancel else { return }
        cancelling = true
    }

    private mutating func finish(_ end: Phase) {
        phase = end
        cancelling = false
    }

    /// `m:ss`.
    static func clockText(_ seconds: TimeInterval) -> String {
        let total = Int(max(0, seconds))
        return "\(total / 60):" + String(format: "%02d", total % 60)
    }
}
