import CoreGraphics
import Foundation

/// Live 클러스터의 차량 사진 위에서 열림을 아이콘으로 알리는 부위(SFR-022). 좌핸들 기준으로 왼쪽이 운전석이다.
enum ClusterPart: CaseIterable, Sendable {
    case doorFrontLeft, doorRearLeft, doorFrontRight, doorRearRight, frunk, trunk, chargePort

    var liveKey: LiveItemKey {
        switch self {
        case .doorFrontLeft: return .driverDoor
        case .doorRearLeft: return .rearDriverDoor
        case .doorFrontRight: return .passengerDoor
        case .doorRearRight: return .rearPassengerDoor
        case .frunk: return .frunk
        case .trunk: return .trunk
        case .chargePort: return .chargePort
        }
    }
}

enum ClusterGear: CaseIterable, Sendable {
    case park, reverse, neutral, drive

    init?(raw: String?) {
        switch raw {
        case "PARK": self = .park
        case "REVERSE": self = .reverse
        case "NEUTRAL": self = .neutral
        case "DRIVE": self = .drive
        default: return nil
        }
    }

    var letter: String {
        switch self {
        case .park: return "P"
        case .reverse: return "R"
        case .neutral: return "N"
        case .drive: return "D"
        }
    }
}

/// `CLOSED` 만 닫힘이다. 여는 중, 반쯤 열림, 래치 실패는 사용자가 봐야 할 상태라 열림으로 그린다. 원본 값은 상세 시트에 그대로 나온다.
enum ClusterClosure: Sendable, Equatable {
    case closed, open, unknown

    init(raw: String?) {
        switch raw {
        case "CLOSED": self = .closed
        case nil, "UNKNOWN": self = .unknown
        default: self = .open
        }
    }
}

/// 값이 없거나 모르는 값은 추측하지 않고 `—` 로 둔다.
enum ClusterText {
    static let none = "—"

    static func lock(_ raw: String?) -> String {
        switch raw {
        case "LOCKED": return "잠김"
        case "INTERNAL_LOCKED": return "내부 잠금"
        case "UNLOCKED": return "잠금 해제됨"
        case "SELECTIVE_UNLOCKED": return "일부 해제됨"
        default: return none
        }
    }

    static func presence(_ raw: String?) -> String {
        switch raw {
        case "PRESENT": return "사용자 있음"
        case "NOT_PRESENT": return "사용자 없음"
        default: return none
        }
    }

    static func sleep(_ raw: String?) -> String {
        switch raw {
        case "AWAKE": return "깨어 있음"
        case "ASLEEP": return "잠듦"
        default: return none
        }
    }
}

/// `LiveState` 와 연결 상태를 클러스터 화면 값으로 줄인다. 차량 없이 시험한다.
struct ClusterState: Sendable, Equatable {
    /// 속도를 읽는 공개 API(`getState`, M5)가 아직 없어 값을 지어내지 않는다.
    let speedText = ClusterText.none
    let gear: ClusterGear?
    let lockText: String
    let presenceText: String
    let sleepText: String
    /// 지금 값이라고 볼 수 없으면 true. 화면을 어둡게 한다.
    let isStale: Bool
    let headline: String
    /// 값이 언제 것인지. 연결 사유가 길어도 잘리지 않게 `headline` 과 따로 둔다.
    let freshness: String?
    private let closures: [ClusterPart: ClusterClosure]

    init<V: Sendable & Equatable>(live: LiveState, link: LinkState<V>, now: Date) {
        gear = ClusterGear(raw: live.items[.gear]?.value)
        lockText = ClusterText.lock(live.items[.lock]?.value)
        presenceText = ClusterText.presence(live.items[.presence]?.value)
        sleepText = ClusterText.sleep(live.items[.sleep]?.value)
        closures = Dictionary(uniqueKeysWithValues: ClusterPart.allCases.map {
            ($0, ClusterClosure(raw: live.items[$0.liveKey]?.value))
        })

        let isConnected: Bool
        if case .connected = link { isConnected = true } else { isConnected = false }
        isStale = !isConnected || !live.heardOnThisLink
        headline = LinkText.of(link)
        switch (live.lastStatusAt, isStale) {
        case (let last?, false): freshness = Formatting.ago(since: last, now: now)
        case (let last?, true): freshness = "마지막 수신 \(Formatting.clockText(last))"
        case (nil, _): freshness = isConnected ? "받은 상태 없음" : nil
        }
    }

    func closure(_ part: ClusterPart) -> ClusterClosure { closures[part] ?? .unknown }

    /// 열린 곳을 색만이 아니라 글자로도 알린다. 모르는 부위가 있으면 모두 닫혔다고 말하지 않고, 트렁크처럼 방송이 안 올 수 있어 방송 기준임을 밝힌다.
    var closureSummary: String {
        let open = ClusterPart.allCases.filter { closure($0) == .open }
        if !open.isEmpty { return open.map(\.liveKey.label).joined(separator: ", ") + " 열림" }
        return ClusterPart.allCases.allSatisfy { closure($0) == .closed } ? "방송 기준 모두 닫힘" : ClusterText.none
    }
}
