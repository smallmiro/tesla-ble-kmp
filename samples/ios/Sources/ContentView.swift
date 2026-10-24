import SwiftUI
import UIKit

/// 네 탭(SFR-010): Live, Query, Log, Control. 화면이 보이는 동안(scene 이 active)만 화면을 켜 둔다(SFR-013).
struct ContentView: View {
    @ObservedObject var monitor: MonitorModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            NavigationStack { LiveTab(monitor: monitor).navigationTitle("Live") }
                .tabItem { Label("Live", systemImage: "dot.radiowaves.left.and.right") }
            NavigationStack { QueryTab(monitor: monitor).navigationTitle("Query") }
                .tabItem { Label("Query", systemImage: "arrow.triangle.2.circlepath") }
            NavigationStack { LogTab(monitor: monitor).navigationTitle("Log") }
                .tabItem { Label("Log", systemImage: "doc.text") }
            NavigationStack { ControlTab(monitor: monitor, model: monitor.control).navigationTitle("Control") }
                .tabItem { Label("Control", systemImage: "slider.horizontal.3") }
        }
        .onAppear {
            monitor.start()
            keepScreenOn(scenePhase == .active)
        }
        .onChange(of: scenePhase) { keepScreenOn($0 == .active) }
    }

    /// SFR-013: 앱이 보일 때만 화면이 꺼지지 않게 한다. 백그라운드와 비활성에서는 시스템 기본으로 돌아간다.
    private func keepScreenOn(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
    }
}
