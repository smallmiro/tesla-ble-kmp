import CoreGraphics

/// 폰 모양 TabView 의 탭(SFR-010, SFR-018).
enum AppTab: Hashable, CaseIterable { case live, query, log, control, keyControl }

/// 두 영역 배치에서 왼쪽 영역이 보여 주는 항목. Live 는 오른쪽에 늘 있으므로 여기서는 "Live 상세"(SFR-014)가 된다.
enum PaneSection: Hashable, CaseIterable { case query, log, control, keyControl, liveDetail }

/// 선택 상태 하나. 폰 TabView 의 선택(`tab`)과 두 영역 배치의 왼쪽 항목(`pane`)이 같은 값을 읽고 쓰므로,
/// 접거나 펼쳐 배치가 바뀌어도 사용자가 보던 곳이 이어진다(SFR-024).
struct AppNavigation: Equatable {
    /// 왼쪽 영역이 열어 둔 항목. `nil` 이면 메뉴가 보이는 상태(폰에서는 Live 탭).
    var pane: PaneSection?

    var tab: AppTab {
        get {
            switch pane {
            case nil, .liveDetail?: return .live
            case .query?: return .query
            case .log?: return .log
            case .control?: return .control
            case .keyControl?: return .keyControl
            }
        }
        set {
            // 폰 TabView 가 지금 탭을 다시 써도 기억한 "Live 상세"를 지우지 않는다.
            guard newValue != tab else { return }
            switch newValue {
            case .live: pane = nil
            case .query: pane = .query
            case .log: pane = .log
            case .control: pane = .control
            case .keyControl: pane = .keyControl
            }
        }
    }
}

/// 넓은 화면 배치 규칙(SFR-024). 기기 종류, 방향, 접힘 각도는 보지 않고 크기 클래스와 쓸 수 있는 너비만 본다.
enum DuoLayout {
    enum Mode: Equatable { case phone, twoPane }

    /// 두 영역에 필요한 최소 너비(pt): 왼쪽 최소 260 + 오른쪽 최소 280. 접는 폰 안쪽 화면(669pt)에서 오른쪽 도크 안전 영역을 뺀 너비도 들어오게 잡았다. 안전 영역을 뺀 쓸 수 있는 너비로 잰다.
    static let twoPaneMinWidth: CGFloat = 540
    static let leftPaneMinWidth: CGFloat = 260
    static let leftPaneMaxWidth: CGFloat = 520
    static let rightPaneMinWidth: CGFloat = 280
    static let leftPaneFraction: CGFloat = 0.4

    /// 가로 크기 클래스가 regular 이고 너비가 충분할 때만 두 영역. 그 밖에는 폰 배치(탭).
    static func mode(isRegularWidth: Bool, width: CGFloat) -> Mode {
        isRegularWidth && width >= twoPaneMinWidth ? .twoPane : .phone
    }

    /// 왼쪽 영역 너비: 전체의 40%, 260 ~ 520pt.
    static func leftPaneWidth(total: CGFloat) -> CGFloat {
        min(max(total * leftPaneFraction, leftPaneMinWidth), leftPaneMaxWidth)
    }
}

/// 오른쪽 영역의 빠른 제어: 모두 키·제어 탭이 이미 가진 버튼이고 `KeyControlModel.press` 로만 보낸다. 새 명령은 만들지 않는다.
/// 잠금은 지금 잠금 상태에 따라 잠그기 또는 잠금 해제(`LockCallout`). 프렁크는 확인 창이 먼저 뜬다.
enum QuickControls {
    /// 차 앞쪽 말풍선.
    static let frunk: ControlButton = .openFrunk
    /// 차 뒤쪽 말풍선.
    static let trunk: ControlButton = .actuateTrunk
    /// 아래 동작 줄: 말풍선에 없고, 키·제어 탭의 기본 목록에 있으며, 한 번 눌러도 되는 것. `remoteDrive` 는 한 번 누르기로 두지 않는다.
    /// 자동 잠금(`autoSecure`)은 Model X 용이라 키·제어 탭도 "다른 차종" 아래에 두므로 차종을 모르는 여기에는 넣지 않는다.
    static let actionRow: [ControlButton] = [.wakeUp, .closeTrunk]
}

