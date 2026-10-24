import SwiftUI

@main
struct TeslableSampleApp: App {
    /// 앱 수명 동안 하나(SFR-010): 라이브러리, 연결 루프, 로그 쓰기가 화면과 함께 사라지지 않는다.
    @StateObject private var monitor = MonitorModel()

    var body: some Scene {
        WindowGroup { ContentView(monitor: monitor) }
    }
}
