import SwiftUI
import UIKit

/// 다섯 탭(SFR-010, SFR-018): Live, Query, Log, Control, 키·제어. 화면이 보이는 동안(scene 이 active)만 화면을 켜 둔다(SFR-013).
struct ContentView: View {
    @ObservedObject var monitor: MonitorModel
    @Environment(\.scenePhase) private var scenePhase
    /// 선택한 탭을 직접 들고 있다. 접힘 상태가 바뀌어 화면이 다시 그려져도 같은 탭이 유지된다(SFR-024).
    @State private var selection = AppTab.live

    private enum AppTab: Hashable { case live, query, log, control, keyControl }

    var body: some View {
        TabView(selection: $selection) {
            NavigationStack { LiveTab(monitor: monitor).navigationTitle("Live") }
                .tabItem { Label("Live", systemImage: "dot.radiowaves.left.and.right") }
                .tag(AppTab.live)
            NavigationStack { QueryTab(monitor: monitor).navigationTitle("Query") }
                .tabItem { Label("Query", systemImage: "arrow.triangle.2.circlepath") }
                .tag(AppTab.query)
            NavigationStack { LogTab(monitor: monitor).navigationTitle("Log") }
                .tabItem { Label("Log", systemImage: "doc.text") }
                .tag(AppTab.log)
            NavigationStack { ControlTab(monitor: monitor, model: monitor.control).navigationTitle("Control") }
                .tabItem { Label("Control", systemImage: "slider.horizontal.3") }
                .tag(AppTab.control)
            NavigationStack { KeyControlTab(monitor: monitor, model: monitor.keyControl).navigationTitle("키·제어") }
                .tabItem { Label("키·제어", systemImage: "key.fill") }
                .tag(AppTab.keyControl)
        }
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
