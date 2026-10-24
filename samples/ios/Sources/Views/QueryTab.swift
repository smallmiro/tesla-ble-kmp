import SwiftUI

/// Query 탭(SFR-015): 주기 조회 결과(Success / Uncertain / Failure 와 시각), 조회 주기, 지금 조회.
struct QueryTab: View {
    @ObservedObject var monitor: MonitorModel
    @State private var secondsText = ""
    @State private var now = Date()

    private static let calls = ["vehicleStatus", "keySummary", "listKeys"]

    var body: some View {
        Form {
            Section { LinkBanner(monitor: monitor).listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
            Section("주기") {
                Text("연결돼 있는 동안 \(monitor.pollSeconds)초마다 vehicleStatus(), keySummary(), listKeys() 를 조회합니다. 깨우지 않습니다.")
                    .font(.footnote)
                HStack {
                    TextField("조회 주기(초)", text: $secondsText)
                        .keyboardType(.numberPad)
                        .onChange(of: secondsText) { secondsText = String($0.filter(\.isNumber).prefix(4)) }
                    Button("적용") { if let n = Int(secondsText) { monitor.setPollSeconds(n) } }
                        .buttonStyle(.borderless)
                }
                Button("지금 조회") { monitor.queryNow() }.disabled(!monitor.isConnected)
                Text(monitor.sessions).font(.footnote)
            }
            ForEach(Self.calls, id: \.self) { call in
                Section(call) {
                    if let r = monitor.queries[call] {
                        Text("\(r.outcome)  ·  \(Formatting.clockText(r.at)) (\(Formatting.ago(since: r.at, now: now)))")
                        // Uncertain 은 "다시 보내지 말고 다시 조회"(16-lib-errors.md): 조회는 다음 주기나 "지금 조회"로 다시 한다.
                        MonoSmall(r.summary)
                    } else {
                        Text("아직 조회하지 않았습니다").font(.footnote)
                    }
                }
            }
        }
        .tickingNow($now)
        .onAppear { secondsText = String(monitor.pollSeconds) }
        .onChange(of: monitor.pollSeconds) { secondsText = String($0) }
    }
}
