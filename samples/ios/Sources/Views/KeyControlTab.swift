import SwiftUI

/// 키·제어 탭(SFR-018): 키 → 연결 → 등록 → 제어 네 단계와 버튼 격자.
/// 규칙은 `KeyControlSteps`(순수), 호출 순서는 `KeyControlRunner`(순수)가 정한다. 이 화면은 그린다.
struct KeyControlTab: View {
    let monitor: MonitorModel
    @ObservedObject var model: KeyControlModel
    @State private var showOtherModels = false
    /// 명령 확인 창을 이 탭이 띄운다. 넓은 화면에서 오른쪽 빠른 제어가 띄울 때는 `false`(SFR-024): 같은 창이 둘 뜨지 않게.
    var ownsCommandDialog = true

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
        .onAppear { model.tabAppeared() }
        // SFR-021: 등록 진행 팝업. 진행 중에는 끌어내려 닫을 수 없고(취소 버튼으로 멈춘다), 끝나면 닫기 버튼으로 닫는다.
        .sheet(isPresented: Binding(get: { model.enrollmentPopup != nil }, set: { _ in })) {
            if let popup = model.enrollmentPopup {
                EnrollmentPopupView(popup: popup, onCancel: { model.cancelEnroll() }, onClose: { model.closeEnrollmentPopup() })
                    .interactiveDismissDisabled(true)
                    .presentationDetents([.medium, .large])
            }
        }
        .confirmationDialog(
            model.replaceConfirmation.title,
            isPresented: $model.pendingReplaceConfirm,
            titleVisibility: .visible
        ) {
            Button(model.replaceConfirmation.action, role: .destructive) { Task { await model.replaceKey() } }
            Button("취소", role: .cancel) {}
        } message: {
            Text(model.replaceConfirmation.message)
        }
        .commandConfirmation(model, enabled: ownsCommandDialog)
    }

    // MARK: - 단계

    private func stepsCard(_ steps: KeyControlSteps) -> some View {
        Card(title: "단계") {
            StepRow(number: 1, title: "키", info: steps.key) {
                Button(steps.key.state == .done ? "다시 불러오기" : "키 만들기/불러오기") { Task { await model.prepareKey() } }
                    .disabled(!steps.key.buttonEnabled)
                // SFR-020: 되돌릴 수 없다. 확인 대화상자 뒤에만 지운다.
                Button("키 삭제 후 다시 만들기", role: .destructive) { model.pendingReplaceConfirm = true }
                    .disabled(!steps.replaceKeyEnabled)
            }
            Divider()
            StepRow(number: 2, title: "연결", info: steps.connect) {
                Button(steps.reconnectNeeded ? "연결 끊고 다시 연결" : "연결") { Task { await model.connect() } }
                    .disabled(!steps.connect.buttonEnabled)
            }
            Divider()
            StepRow(number: 3, title: "등록", info: steps.enroll) {
                HStack {
                    Button("키 등록 요청") { model.startEnroll() }
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
            infotainmentSection(enabled: InfotainmentControl.enabled(model.inputs))
            Text("경적·조명·창문·미디어 등 나머지 Infotainment 명령은 M5에서 이어서 추가됩니다.").font(.caption).foregroundStyle(.secondary)
        }
    }

    // MARK: - Infotainment 공조·충전 (SFR-025)

    /// 범주마다 명령 둘과 읽기 버튼, 결과 미확인 표시, 마지막 읽기. 버튼 활성 규칙은 4단계와 같고 확인 창은 없다. 값은 화면에만 보이고 결과 줄에는 가지 않는다.
    private func infotainmentSection(enabled: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            Text(InfotainmentControl.sectionTitle).font(.subheadline.weight(.semibold))
            ForEach(InfoCategory.allCases, id: \.self) { category in
                VStack(alignment: .leading, spacing: 6) {
                    Text(category.title).font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                    LazyVGrid(columns: Self.columns, spacing: 10) {
                        ForEach(InfoCommand.allCases.filter { $0.category == category }) { cmd in
                            Button { model.pressInfo(cmd) } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: cmd.symbol).font(.title3)
                                    Text(cmd.label).font(.footnote).multilineTextAlignment(.center)
                                }
                                .frame(maxWidth: .infinity, minHeight: 64)
                            }
                            .buttonStyle(.bordered)
                            .disabled(!enabled)
                        }
                    }
                    if model.info.unconfirmed.contains(category) {
                        Text("\(category.unconfirmedText) — '\(category.readLabel)'로 확인하세요(다시 보내지 않습니다)")
                            .font(.footnote).foregroundStyle(.red)
                    }
                    if let reading = model.info.readings[category] { readout(reading) }
                }
            }
        }
    }

    private func readout(_ reading: InfoReading) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(reading.fields, id: \.label) { MonoSmall("\($0.label): \($0.value)") }
            MonoSmall("timestamp: \(reading.timestampText)")
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text("읽은 시각 \(Formatting.clockText(reading.at)) (\(Formatting.ago(since: reading.at, now: context.date)))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if reading.sameTimestampAsPrevious {
                Text("timestamp가 그대로입니다: 새로 잰 값이라고 단정할 수 없습니다").font(.caption).foregroundStyle(.secondary)
            }
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
                            Text("\(line.title): \(line.text)").font(.footnote).foregroundStyle(line.tone.color)
                                .textSelection(.enabled)
                        }
                    }
                }
                Button("결과 지우기", role: .destructive) { model.clearResults() }.font(.footnote)
            }
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

/// 키 등록 진행 팝업(SFR-021). 모양만 그린다: 단계·문구·버튼 규칙은 `EnrollmentPopup`(순수)이 정한다.
private struct EnrollmentPopupView: View {
    let popup: EnrollmentPopup
    let onCancel: () -> ()
    let onClose: () -> ()

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(popup.title).font(.title3.bold())
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(popup.rows.enumerated()), id: \.offset) { _, row in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Image(systemName: symbol(row.mark)).foregroundStyle(color(row.mark)).frame(width: 24)
                        Text(row.title).font(.subheadline)
                    }
                }
            }
            if popup.phase == .confirming, popup.attempt > 0 {
                ProgressView(value: popup.progressFraction)
            } else if popup.isActive {
                ProgressView()
            }
            Text(popup.message)
                .font(.footnote)
                .foregroundStyle(popup.isActive ? Color.secondary : Color.primary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if popup.isActive {
                Button("취소", role: .cancel, action: onCancel)
                    .buttonStyle(.bordered)
                    .disabled(!popup.canCancel)
                Text("취소하면 기다리기를 멈춥니다. 이미 보낸 요청은 차량에서 되돌릴 수 없습니다.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                Button("닫기", action: onClose).buttonStyle(.borderedProminent)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func symbol(_ mark: EnrollmentPopup.Mark) -> String {
        switch mark {
        case .done: return "checkmark.circle.fill"
        case .current: return "circle.dotted"
        case .pending: return "circle"
        case .failed: return "xmark.circle.fill"
        }
    }

    private func color(_ mark: EnrollmentPopup.Mark) -> Color {
        switch mark {
        case .done: return .green
        case .current: return .accentColor
        case .pending: return .secondary
        case .failed: return .red
        }
    }
}
