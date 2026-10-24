import SwiftUI
import UIKit

/// Log 탭(SFR-016): 줄 수, 알 수 없는 이벤트 수, 마지막 줄들, 파일 위치, 메모와 Mark, 새 파일.
/// 예전 "관찰 모드"의 메모·표시와 복사는 여기로 합쳐졌다: 이제 `observe` 줄은 늘 로그 파일에 쓰이고, 메모는 `mark` 줄이 된다.
struct LogTab: View {
    @ObservedObject var monitor: MonitorModel
    @State private var memo = ""
    @State private var copied = false
    @State private var copyReset: Task<(), Never>?

    var body: some View {
        let stats = monitor.logStats
        Form {
            Section { LinkBanner(monitor: monitor).listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
            Section("기록") {
                Text("쓴 줄 \(stats.totalLines) (" + stats.byKind.sorted { $0.key < $1.key }.map { "\($0.key) \($0.value)" }.joined(separator: ", ") + ")")
                Text("알 수 없는 이벤트(unrecognized) \(stats.unrecognized)  ·  버린 줄 \(stats.dropped)")
                Text("파일: \(stats.currentFile ?? "아직 없음")").font(.footnote.monospaced()).textSelection(.enabled)
                Text("Documents/logs/ (Finder, 파일 앱, tools/samples/pull-monitor-logs.sh ios)").font(.footnote)
            }
            Section("메모 (행동 직전/직후 적기, 비밀 입력 금지)") {
                TextField("최대 \(LogLine.memoMax)자, 예: before: open driver door", text: $memo)
                    .onChange(of: memo) { memo = String($0.prefix(LogLine.memoMax)) }
                Button("Mark") {
                    monitor.mark(memo)
                    memo = ""
                }
                .disabled(memo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("새 파일") { monitor.newLogFile() }
            }
            Section("마지막 줄 (최신이 아래)") {
                Button(copied ? "복사됨 ✓" : "마지막 줄 복사") {
                    UIPasteboard.general.string = stats.lastLines.joined(separator: "\n")
                    copied = true
                    copyReset?.cancel()
                    copyReset = Task {
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                        if !Task.isCancelled { copied = false }
                    }
                }
                .disabled(stats.lastLines.isEmpty)
                if stats.lastLines.isEmpty { Text("없음").font(.footnote) }
                ForEach(Array(stats.lastLines.enumerated()), id: \.offset) { MonoSmall($0.element) }
            }
        }
    }
}
