import SwiftUI

/// 키·제어 탭(SFR-018): 키 → 연결 → 등록 → 제어 네 단계와 버튼 격자.
/// 규칙은 `KeyControlSteps`(순수), 호출 순서는 `KeyControlRunner`(순수)가 정한다. 이 화면은 그린다.
struct KeyControlTab: View {
    let monitor: MonitorModel
    @ObservedObject var model: KeyControlModel
    @State private var showOtherModels = false

    var body: some View {
        let steps = model.steps
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                LinkBanner(monitor: monitor)
                stepsCard(steps)
                statusCard(steps)
                controlCard(steps)
                resultsCard
            }
            .padding(16)
        }
        .background(Color(.systemGroupedBackground))
        .confirmationDialog(
            model.pendingConfirm?.confirmation?.title ?? "",
            isPresented: Binding(get: { model.pendingConfirm != nil }, set: { if !$0 { model.pendingConfirm = nil } }),
            titleVisibility: .visible,
            presenting: model.pendingConfirm
        ) { button in
            Button(button.confirmation?.action ?? button.title, role: .destructive) {
                Task { await model.run(button) }
            }
            Button("취소", role: .cancel) {}
        } message: { button in
            Text(button.confirmation?.message ?? "")
        }
    }

    // MARK: - 단계

    private func stepsCard(_ steps: KeyControlSteps) -> some View {
        Card(title: "단계") {
            StepRow(number: 1, title: "키", info: steps.key) {
                Button(steps.key.state == .done ? "다시 불러오기" : "키 만들기/불러오기") { Task { await model.prepareKey() } }
                    .disabled(!steps.key.buttonEnabled)
            }
            Divider()
            StepRow(number: 2, title: "연결", info: steps.connect) {
                Button("연결") { Task { await model.connect() } }
                    .disabled(!steps.connect.buttonEnabled)
            }
            Divider()
            StepRow(number: 3, title: "등록", info: steps.enroll) {
                HStack {
                    Button("키 등록 요청") { Task { await model.enroll() } }
                        .disabled(!steps.enroll.buttonEnabled)
                    Button("등록 상태 확인") { Task { await model.checkEnrollment() } }
                        .disabled(!steps.checkEnrollmentEnabled)
                }
                if model.inputs.enrollment == .checking {
                    ProgressView().controlSize(.small)
                }
                if !model.progress.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(model.progress.enumerated()), id: \.offset) {
                            Text($0.element).font(.caption.monospaced()).textSelection(.enabled)
                        }
                    }
                }
            }
        }
    }

    // MARK: - 상태

    private func statusCard(_ steps: KeyControlSteps) -> some View {
        Card(title: "차량 상태") {
            if let s = model.status {
                Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                    GridRow { Label("잠금", systemImage: "lock").foregroundStyle(.secondary); Text(s.lockText) }
                    GridRow { Label("트렁크", systemImage: "car.rear").foregroundStyle(.secondary); Text(s.trunkText) }
                    GridRow { Label("프렁크", systemImage: "car.front.waves.up").foregroundStyle(.secondary); Text(s.frunkText) }
                    GridRow { Label("수면", systemImage: "moon.zzz").foregroundStyle(.secondary); Text(s.sleepText) }
                }
                .font(.subheadline)
                Text("마지막 읽음 \(Formatting.clockText(s.at))").font(.caption).foregroundStyle(.secondary)
            } else {
                Text("아직 읽지 않았습니다").font(.subheadline).foregroundStyle(.secondary)
            }
            Button {
                Task { await model.refreshStatus() }
            } label: {
                Label("상태 새로고침", systemImage: "arrow.clockwise")
            }
            .buttonStyle(.bordered)
            .disabled(!steps.refreshStatusEnabled)
        }
    }

    // MARK: - 제어

    private static let columns = [GridItem(.adaptive(minimum: 100), spacing: 10)]

    private func controlCard(_ steps: KeyControlSteps) -> some View {
        let enabled = steps.control.buttonEnabled
        return Card(title: "제어") {
            StepHint(info: steps.control)
            ForEach(ControlButton.Group.primary, id: \.self) { group($0, enabled: enabled) }
            DisclosureGroup("다른 차종 (Model X, Cybertruck)", isExpanded: $showOtherModels) {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(ControlButton.Group.otherModels, id: \.self) { group($0, enabled: enabled) }
                }
                .padding(.top, 8)
            }
            .font(.subheadline)
            Text("공조·충전·경적·조명은 M5 이후에 추가됩니다.").font(.caption).foregroundStyle(.secondary)
        }
    }

    private func group(_ group: ControlButton.Group, enabled: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(group.title).font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
            LazyVGrid(columns: Self.columns, spacing: 10) {
                ForEach(ControlButton.allCases.filter { $0.group == group }) { button in
                    Button { model.press(button) } label: {
                        VStack(spacing: 6) {
                            Image(systemName: button.symbol).font(.title3)
                            Text(button.title).font(.footnote).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity, minHeight: 64)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!enabled)
                }
            }
        }
    }

    // MARK: - 결과

    private var resultsCard: some View {
        Card(title: "결과 (최신이 위)") {
            if model.results.isEmpty {
                Text("아직 없습니다").font(.subheadline).foregroundStyle(.secondary)
            } else {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(model.results) { line in
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(Formatting.clockText(line.at)).font(.caption.monospaced()).foregroundStyle(.secondary)
                            Text("\(line.title): \(line.text)").font(.footnote).foregroundStyle(color(line.tone))
                                .textSelection(.enabled)
                        }
                    }
                }
                Button("결과 지우기", role: .destructive) { model.clearResults() }.font(.footnote)
            }
        }
    }

    private func color(_ tone: KeyControlModel.ResultLine.Tone) -> Color {
        switch tone {
        case .ok: return .green
        case .info: return .primary
        case .warn: return .orange
        case .fail: return .red
        }
    }
}

// MARK: - 부품

private struct Card<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

private struct StepHint: View {
    let info: StepInfo

    var body: some View {
        Text(info.hint)
            .font(.footnote)
            .foregroundStyle(info.state == .blocked ? Color.orange : Color.secondary)
    }
}

private struct StepRow<Actions: View>: View {
    let number: Int
    let title: String
    let info: StepInfo
    @ViewBuilder let actions: Actions

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol).font(.title3).foregroundStyle(color).frame(width: 28)
            VStack(alignment: .leading, spacing: 6) {
                Text("\(number). \(title)").font(.subheadline.weight(.semibold))
                StepHint(info: info)
                actions.buttonStyle(.bordered)
            }
        }
    }

    private var symbol: String {
        switch info.state {
        case .done: return "checkmark.circle.fill"
        case .todo: return "circle"
        case .blocked: return "exclamationmark.circle"
        }
    }

    private var color: Color {
        switch info.state {
        case .done: return .green
        case .todo: return .accentColor
        case .blocked: return .orange
        }
    }
}
