import SwiftUI

enum Formatting {
    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    static func clockText(_ at: Date) -> String { clock.string(from: at) }

    /// 지난 시간을 짧게: 7초, 3분 12초, 1시간 5분.
    static func ago(since: Date, now: Date) -> String {
        let s = max(0, Int(now.timeIntervalSince(since)))
        switch s {
        case ..<60: return "\(s)초 전"
        case ..<3_600: return "\(s / 60)분 \(s % 60)초 전"
        default: return "\(s / 3_600)시간 \((s % 3_600) / 60)분 전"
        }
    }
}

/// 1초마다 바뀌는 현재 시각("n초 전" 표시용). Form 안에서 행 구성을 바꾸지 않도록 TimelineView 대신 타이머를 쓴다.
private struct NowTicker: ViewModifier {
    @Binding var now: Date

    func body(content: Content) -> some View {
        content.onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { now = $0 }
    }
}

extension View {
    func tickingNow(_ now: Binding<Date>) -> some View { modifier(NowTicker(now: now)) }
}

struct MonoSmall: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(.caption2.monospaced()).textSelection(.enabled)
    }
}

/// 연결 배너(모든 탭 위).
struct LinkBanner: View {
    @ObservedObject var monitor: MonitorModel

    var body: some View {
        Text(LinkText.of(monitor.link))
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(color, in: RoundedRectangle(cornerRadius: 8))
            .accessibilityIdentifier("linkBanner")
    }

    private var color: Color {
        switch monitor.link {
        case .connected: return .green.opacity(0.25)
        case .connecting, .backoff: return .orange.opacity(0.25)
        case .idle, .stopped: return .gray.opacity(0.2)
        }
    }
}
