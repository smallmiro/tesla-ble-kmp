import SwiftUI
import UIKit

/// 앱의 뼈대. 좁은 화면(폰, 접은 폰의 바깥 화면)은 다섯 탭(SFR-010, SFR-018): Live, Query, Log, Control, 키·제어.
/// 가로 크기 클래스가 regular 이고 너비가 충분한 화면(접는 폰의 안쪽 화면 등)은 두 영역(SFR-024, `TwoPaneView`).
/// 화면이 보이는 동안(scene 이 active)만 화면을 켜 둔다(SFR-013).
struct ContentView: View {
    @ObservedObject var monitor: MonitorModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// 폰 TabView 의 선택과 두 영역의 왼쪽 항목이 같은 값을 쓴다. 접거나 펼쳐도 보던 곳이 이어진다(SFR-024).
    @State private var nav = AppNavigation()
    /// 열린 시트와 입력 중인 글. 배치가 바뀌어 화면이 다시 만들어져도 이어지게 여기서 하나 든다.
    @State private var continuity = ScreenContinuity()
    /// 안전 영역을 뺀 쓸 수 있는 너비. 처음 그리기 전에는 모르므로 `nil`(맞는다고 보고 크기 클래스만으로 정한다).
    @State private var width: CGFloat?

    var body: some View {
        let known = width ?? .greatestFiniteMagnitude
        let mode = DuoLayout.mode(isRegularWidth: sizeClass == .regular, width: known)
        // 두 배치가 같은 TabView 를 쓴다. 탭 막대는 시스템이 그린다(접는 폰에서는 안전 영역 오른쪽 도크 아래 세로 막대).
        TabView(selection: Binding(get: { nav.tab }, set: { nav.tab = $0 })) {
            ForEach(AppTab.allCases, id: \.self) { tab in
                content(of: tab, mode: mode, width: known)
                    .tabItem { Label(tab.title, systemImage: tab.symbol) }
                    .tag(tab)
            }
        }
        .background(WidthReader(width: $width))
        .onAppear {
            monitor.start()
            monitor.scenePhaseChanged(Self.name(of: scenePhase)) // 처음 화면 상태도 남긴다
            keepScreenOn(scenePhase == .active)
            if mode == .twoPane { monitor.keyControl.tabAppeared() }
        }
        .onChange(of: scenePhase) {
            monitor.scenePhaseChanged(Self.name(of: $0))
            keepScreenOn($0 == .active)
        }
        // 키·제어 탭이 나타날 때와 같이, 두 영역이 되면 다른 곳에서 등록이 바뀌었을 수 있어 등록 상태를 다시 확인한다.
        .onChange(of: mode) { if $0 == .twoPane { monitor.keyControl.tabAppeared() } }
    }

    /// 폰 배치는 탭마다 자기 화면. 두 영역은 고른 탭 하나만 두 영역 전체를 그리고 나머지는 비워 둔다(탭 막대만 쓴다).
    @ViewBuilder
    private func content(of tab: AppTab, mode: DuoLayout.Mode, width: CGFloat) -> some View {
        switch mode {
        case .phone: phonePage(tab)
        case .twoPane:
            if nav.tab == tab {
                TwoPaneView(monitor: monitor, continuity: continuity, nav: $nav, width: width)
            } else {
                Color.clear
            }
        }
    }

    @ViewBuilder
    private func phonePage(_ tab: AppTab) -> some View {
        switch tab {
        case .live: NavigationStack { LiveTab(monitor: monitor, continuity: continuity).navigationTitle("Live") }
        case .query: NavigationStack { QueryTab(monitor: monitor).navigationTitle("Query") }
        case .log: NavigationStack { LogTab(monitor: monitor, continuity: continuity).navigationTitle("Log") }
        case .control: NavigationStack { ControlTab(monitor: monitor, model: monitor.control).navigationTitle("Control") }
        case .keyControl: NavigationStack { KeyControlTab(monitor: monitor, model: monitor.keyControl).navigationTitle("키·제어") }
        }
    }

    /// 로그 `app` 줄(`scenePhase`)에 쓰는 이름.
    private static func name(of phase: ScenePhase) -> String {
        switch phase {
        case .active: return "active"
        case .inactive: return "inactive"
        case .background: return "background"
        @unknown default: return "unknown"
        }
    }

    /// SFR-013: 앱이 보일 때만 화면이 꺼지지 않게 한다. 백그라운드와 비활성에서는 시스템 기본으로 돌아간다.
    private func keepScreenOn(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
    }
}

private extension AppTab {
    var title: String {
        switch self {
        case .live: return "Live"
        case .query: return "Query"
        case .log: return "Log"
        case .control: return "Control"
        case .keyControl: return "키·제어"
        }
    }

    var symbol: String {
        switch self {
        case .live: return "dot.radiowaves.left.and.right"
        case .query: return "arrow.triangle.2.circlepath"
        case .log: return "doc.text"
        case .control: return "slider.horizontal.3"
        case .keyControl: return "key.fill"
        }
    }
}

/// 이 뷰가 받은 너비(안전 영역 안쪽)를 `width` 에 적어 준다. 아무것도 그리지 않는다.
private struct WidthReader: View {
    @Binding var width: CGFloat?

    var body: some View {
        GeometryReader { proxy in
            Color.clear
                .onAppear { width = proxy.size.width }
                .onChange(of: proxy.size.width) { width = $0 }
        }
    }
}
