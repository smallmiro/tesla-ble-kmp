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
        Group {
            switch DuoLayout.mode(isRegularWidth: sizeClass == .regular, width: known) {
            case .phone: phoneTabs
            case .twoPane: TwoPaneView(monitor: monitor, continuity: continuity, nav: $nav, width: known)
            }
        }
        .background(WidthReader(width: $width))
        .onAppear {
            monitor.start()
            monitor.scenePhaseChanged(Self.name(of: scenePhase)) // 처음 화면 상태도 남긴다
            keepScreenOn(scenePhase == .active)
        }
        .onChange(of: scenePhase) {
            monitor.scenePhaseChanged(Self.name(of: $0))
            keepScreenOn($0 == .active)
        }
    }

    private var phoneTabs: some View {
        TabView(selection: Binding(get: { nav.tab }, set: { nav.tab = $0 })) {
            NavigationStack { LiveTab(monitor: monitor, continuity: continuity).navigationTitle("Live") }
                .tabItem { Label("Live", systemImage: "dot.radiowaves.left.and.right") }
                .tag(AppTab.live)
            NavigationStack { QueryTab(monitor: monitor).navigationTitle("Query") }
                .tabItem { Label("Query", systemImage: "arrow.triangle.2.circlepath") }
                .tag(AppTab.query)
            NavigationStack { LogTab(monitor: monitor, continuity: continuity).navigationTitle("Log") }
                .tabItem { Label("Log", systemImage: "doc.text") }
                .tag(AppTab.log)
            NavigationStack { ControlTab(monitor: monitor, model: monitor.control).navigationTitle("Control") }
                .tabItem { Label("Control", systemImage: "slider.horizontal.3") }
                .tag(AppTab.control)
            NavigationStack { KeyControlTab(monitor: monitor, model: monitor.keyControl).navigationTitle("키·제어") }
                .tabItem { Label("키·제어", systemImage: "key.fill") }
                .tag(AppTab.keyControl)
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
