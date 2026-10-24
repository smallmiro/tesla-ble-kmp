import Foundation

/// Live 탭에 보이는 항목(SFR-014). 순서는 화면 순서다. Android 앱의 `LiveItemKey` 와 같다.
enum LiveItemKey: CaseIterable, Sendable {
    case lock, gear, presence, sleep, driverDoor, passengerDoor, rearDriverDoor, rearPassengerDoor, frunk, trunk, chargePort

    var label: String {
        switch self {
        case .lock: return "잠금"
        case .gear: return "기어"
        case .presence: return "사용자 존재"
        case .sleep: return "수면"
        case .driverDoor: return "운전석 문"
        case .passengerDoor: return "조수석 문"
        case .rearDriverDoor: return "뒤 운전석 문"
        case .rearPassengerDoor: return "뒤 조수석 문"
        case .frunk: return "프렁크"
        case .trunk: return "트렁크"
        case .chargePort: return "충전 포트"
        }
    }
}

/// 항목의 현재 값과 그 값이 시작된 시각. `changed` 가 false 이면 처음 관측한 값이다(바뀐 것인지 모른다).
/// 같은 값이 다시 와도 `since` 는 그대로다.
struct LiveItem: Sendable, Equatable {
    let value: String
    let since: Date
    let changed: Bool
}

struct RecentEvent: Sendable, Equatable, Identifiable {
    let at: Date
    let text: String
    var id: String { "\(at.timeIntervalSince1970)-\(text)" }
}

struct LiveState: Sendable, Equatable {
    var items: [LiveItemKey: LiveItem] = [:]
    /// 마지막 `Status` 가 온 시각(같은 값이어도 갱신).
    var lastStatusAt: Date?
    /// 최근 이벤트, 최신이 앞. 상태는 바뀐 것만 올라간다.
    var recent: [RecentEvent] = []
}

/// `Vehicle.events` 의 이벤트를 `LiveState` 로 줄인다. 차량 없이 시험한다.
enum LiveReducer {
    static let maxRecent = 100

    static func reduce(_ state: LiveState, _ event: MonitorEvent, now: Date) -> LiveState {
        switch event.kind {
        case .status(let snapshot): return reduceStatus(state, snapshot, now: now)
        case .authenticationRequest(let level, let reasons):
            return withRecent(state, now, "인증 요청 level=\(level) reasons=\(LogLine.listText(reasons))")
        case .unrecognized:
            return withRecent(state, now, "알 수 없는 방송 unknownFields=\(LogLine.listText(event.unknownFields))")
        }
    }

    private static func reduceStatus(_ state: LiveState, _ snapshot: StatusSnapshot, now: Date) -> LiveState {
        let seen = !state.items.isEmpty
        var items: [LiveItemKey: LiveItem] = [:]
        var changes: [String] = []
        for (key, value) in values(snapshot) {
            if let old = state.items[key] {
                if old.value == value {
                    items[key] = old // 같은 값 반복은 변화가 아니다
                } else {
                    changes.append("\(key.label) \(old.value) → \(value)")
                    items[key] = LiveItem(value: value, since: now, changed: true)
                }
            } else {
                items[key] = LiveItem(value: value, since: now, changed: false)
            }
        }
        var next = state
        next.items = items
        next.lastStatusAt = now
        return seen && !changes.isEmpty ? withRecent(next, now, changes.joined(separator: ", ")) : next
    }

    private static func values(_ s: StatusSnapshot) -> [(LiveItemKey, String)] {
        [
            (.lock, s.lock), (.gear, s.gear), (.presence, s.presence), (.sleep, s.sleep),
            (.driverDoor, s.frontDriverDoor), (.passengerDoor, s.frontPassengerDoor),
            (.rearDriverDoor, s.rearDriverDoor), (.rearPassengerDoor, s.rearPassengerDoor),
            (.frunk, s.frunk), (.trunk, s.trunk), (.chargePort, s.chargePort),
        ]
    }

    private static func withRecent(_ state: LiveState, _ at: Date, _ text: String) -> LiveState {
        var next = state
        next.recent = Array(([RecentEvent(at: at, text: text)] + state.recent).prefix(maxRecent))
        return next
    }
}
