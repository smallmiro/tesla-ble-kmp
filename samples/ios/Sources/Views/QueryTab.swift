import SwiftUI

/// Query 탭(SFR-015): 주기 조회 결과(Success / Uncertain / Failure 와 시각), 조회 주기, 지금 조회.
/// 그 아래에 사용자가 직접 `getState` 를 부르는 상태 진단(SFR-023)이 있다.
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
            StateDiagnosticsSection(model: monitor.diagnostics, now: now)
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

/// 상태 진단(SFR-023): Drive / Media / MediaDetail 버튼, 결과, 잠든 차 시험.
/// 조회는 버튼을 누를 때만 한 번 하고 자동으로 다시 하지 않는다. `wakeUp()` 을 호출하지 않는다. 값은 이 화면에만 나온다.
private struct StateDiagnosticsSection: View {
    @ObservedObject var model: StateDiagnosticsModel
    let now: Date
    @State private var confirmExperiment = false
    @State private var experimentCategory = DiagnosticCategory.media

    var body: some View {
        Section("상태 진단 (getState)") {
            Text("누를 때만 getState() 를 한 번 보냅니다. 자동 재시도와 주기 조회는 없고 wakeUp() 을 호출하지 않습니다. 보내기 전에 vehicleStatus() 로 수면 상태를 다시 읽고 AWAKE 일 때만 보냅니다. 값은 이 화면에만 나오고, 로그에는 범주·결과·소요 시간·오류 종류만 남습니다.")
                .font(.footnote)
            HStack {
                ForEach(DiagnosticCategory.allCases) { category in
                    Button(category.title) { Task { await model.run(category, ignoringSleep: false) } }
                        .buttonStyle(.bordered)
                        .disabled(!StateDiagnostics.buttonsEnabled(model.inputs))
                        .frame(maxWidth: .infinity)
                }
            }
            if let running = model.running {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("\(running.title) 조회 중…").font(.footnote)
                }
            } else if let hint = StateDiagnostics.disabledHint(model.inputs) {
                Text(hint).font(.footnote).foregroundStyle(.secondary)
            }
            if let notice = model.notice {
                Text(notice).font(.footnote).foregroundStyle(.orange)
            }
        }
        Section("진단 결과") {
            if let reading = model.reading {
                Text("\(reading.category.title)  ·  \(reading.outcome.kind)  ·  \(Formatting.clockText(reading.at)) (\(Formatting.ago(since: reading.at, now: now)))  ·  \(reading.durationMs) ms")
                Text(reading.outcome.text).font(.footnote)
                ForEach(reading.fields, id: \.label) { field in
                    MonoSmall("\(field.label): \(field.value)")
                }
            } else {
                Text("아직 조회하지 않았습니다. 연결이 바뀌면 결과를 비웁니다").font(.footnote)
            }
        }
        Section("잠든 차 시험 (실험)") {
            Text("수면 확인만 건너뛰고 같은 순서로 한 번 보냅니다. 연결과 등록된 키는 그대로 필요하고 wakeUp() 을 호출하지 않습니다. Infotainment 세션 시작이 잠든 차에 어떤 영향을 주는지는 확인되지 않았습니다.")
                .font(.footnote)
            Picker("범주", selection: $experimentCategory) {
                ForEach(DiagnosticCategory.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            Button("잠든 차에 보내기(실험)") { confirmExperiment = true }
                .disabled(!StateDiagnostics.experimentEnabled(model.inputs))
        }
        .alert("잠든 차에 보낼까요?", isPresented: $confirmExperiment) {
            Button("보내기") { Task { await model.run(experimentCategory, ignoringSleep: true) } }
            Button("취소", role: .cancel) {}
        } message: {
            Text("수면 확인을 건너뛰고 \(experimentCategory.title) 조회를 한 번 보냅니다. 이 요청 때문에 차가 깨어날 수 있습니다. wakeUp() 은 호출하지 않습니다. 응답이 없으면 불확실로 표시하고 다시 보내지 않습니다.")
        }
    }
}
