import SwiftUI

/// 넓은 화면의 두 영역 배치(SFR-024, 접는 폰의 안쪽 화면 등). 공식 Tesla 앱의 큰 화면 첫 화면을 따른다:
/// 왼쪽은 차량 머리글, 한 줄 요약이 붙은 메뉴, 바닥 줄. 오른쪽은 늘 보이는 차량(말풍선 제어), 기어와 속도, 아래 동작 줄.
/// 항목 화면은 폰 탭과 같은 뷰를 그대로 쓴다(복사 없음). 어느 항목을 열었는지는 `AppNavigation` 하나라서 접어도 이어진다.
/// 색은 모두 시스템 의미 색이라 라이트와 다크 모드를 따른다.
struct TwoPaneView: View {
    @ObservedObject var monitor: MonitorModel
    let continuity: ScreenContinuity
    @Binding var nav: AppNavigation
    let width: CGFloat

    var body: some View {
        HStack(spacing: 0) {
            HomeLeftPane(
                monitor: monitor, control: monitor.control, keyControl: monitor.keyControl,
                diagnostics: monitor.diagnostics, continuity: continuity, nav: $nav
            )
            .frame(width: DuoLayout.leftPaneWidth(total: width))
            Divider()
            // 오른쪽 가장자리의 도크(안전 영역)는 SwiftUI 가 이 영역의 안쪽 여백으로 처리한다. 배경만 가장자리까지 칠한다.
            HomeRightPane(monitor: monitor, model: monitor.keyControl, diagnostics: monitor.diagnostics, continuity: continuity)
                .frame(minWidth: DuoLayout.rightPaneMinWidth, maxWidth: .infinity)
        }
    }
}

// MARK: - 왼쪽: 머리글, 카드와 줄, 바닥 줄

/// 공식 Tesla 앱의 큰 화면 왼쪽 열을 따른다: 큰 제목과 상태 한 줄, 채워진 둥근 카드(오른쪽 화살표) 둘, 카드 없는 줄들.
/// 열어 둔 항목의 줄은 화살표 없는 채워진 카드로 바뀐다. 카드 없는 줄의 값은 클러스터가 이미 가진 것이고, 없으면 `—`.
/// Query, Log, Control, 키·제어, Live 상세 페이지는 이 열 안에서 열리고, 오른쪽 가장자리의 탭 막대(`ContentView`)가 같은 선택을 보인다.
private struct HomeLeftPane: View {
    @ObservedObject var monitor: MonitorModel
    @ObservedObject var control: SampleModel
    @ObservedObject var keyControl: KeyControlModel
    @ObservedObject var diagnostics: StateDiagnosticsModel
    let continuity: ScreenContinuity
    @Binding var nav: AppNavigation
    @State private var now = Date()

    var body: some View {
        let state = ClusterState(live: monitor.live, link: monitor.link, now: now)
        let drive = DriveSummary(reading: diagnostics.lastDrive, now: now)
        VStack(alignment: .leading, spacing: 0) {
            // 페이지를 열면 이 영역이 줄어든다(넘치면 스크롤).
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header(state)
                    VStack(spacing: 8) {
                        ForEach(Self.cards, id: \.self) { section in
                            MenuRow(
                                style: .card, symbol: section.symbol, title: section.title, summary: summary(section),
                                selected: nav.pane == section
                            ) { toggle(section) }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 6)
                    VStack(spacing: 0) {
                        // 상태만 있는 줄은 누르면 Live 상세를 연다.
                        ForEach(HomeStatus.rows(state: state, drive: drive)) { row in
                            MenuRow(style: .plain, symbol: row.symbol, title: row.title, summary: row.subtitle, selected: false) {
                                nav.pane = .liveDetail
                            }
                        }
                        ForEach(Self.pages, id: \.self) { section in
                            MenuRow(
                                style: .plain, symbol: section.symbol, title: section.title, summary: summary(section),
                                selected: nav.pane == section
                            ) { toggle(section) }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.bottom, 10)
                }
            }
            .frame(maxHeight: nav.pane == nil ? .infinity : 360)
            if let section = nav.pane {
                page(section)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .padding(.horizontal, 8)
                    .padding(.bottom, 8)
                    .frame(maxHeight: .infinity)
            } else {
                footer(drive)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .tickingNow($now)
    }

    /// 위쪽 카드 둘: 가장 쓸모 있는 두 항목.
    private static let cards: [PaneSection] = [.liveDetail, .keyControl]
    /// 카드 없는 줄 가운데 페이지가 있는 것.
    private static let pages: [PaneSection] = [.query, .log, .control]

    private func toggle(_ section: PaneSection) { nav.pane = nav.pane == section ? nil : section }

    // MARK: 머리글과 바닥 줄

    private func header(_ state: ClusterState) -> some View {
        let nickname = control.keyRows.first(where: \.isOwn).flatMap { control.nickname(for: $0) }
        let status = HomeHeader.statusLine(gear: state.gear, lockText: state.lockText, sleepText: state.sleepText)
        return VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                Text(HomeHeader.title(ownKeyNickname: nickname)).font(.largeTitle.weight(.bold)).lineLimit(1)
                Circle().fill(state.isStale ? Color.gray : .green).frame(width: 9, height: 9)
                    .accessibilityLabel(state.isStale ? "연결 없음 또는 오래된 값" : "연결됨")
            }
            if !status.isEmpty { Text(status).font(.subheadline).foregroundStyle(.secondary) }
            if let masked = monitor.maskedVin { Text(masked).font(.caption.monospaced()).foregroundStyle(.tertiary) }
        }
        .opacity(state.isStale ? 0.7 : 1)
        .padding(.horizontal, 22)
        .padding(.top, 20)
        .padding(.bottom, 18)
    }

    private func footer(_ drive: DriveSummary) -> some View {
        let lines = HomeFooter.lines(version: Self.appVersion, drive: drive)
        return VStack(alignment: .leading, spacing: 2) {
            Divider().padding(.bottom, 8)
            ForEach(lines, id: \.self) { Text($0).font(.footnote).foregroundStyle(.secondary) }
        }
        .padding(.horizontal, 22)
        .padding(.bottom, 14)
    }

    private static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }

    // MARK: 요약과 페이지

    private func summary(_ section: PaneSection) -> String {
        switch section {
        case .liveDetail: return MenuSummary.liveDetail(lastEventAt: monitor.live.recent.first?.at)
        case .query: return MenuSummary.query(monitor.queries["vehicleStatus"], now: now)
        case .log: return MenuSummary.log(totalLines: monitor.logStats.totalLines)
        case .control: return MenuSummary.control(connected: monitor.isConnected)
        case .keyControl: return MenuSummary.keyControl(keyControl.steps)
        }
    }

    @ViewBuilder
    private func page(_ section: PaneSection) -> some View {
        switch section {
        case .liveDetail: ClusterDetailForm(monitor: monitor)
        case .query: QueryTab(monitor: monitor)
        case .log: LogTab(monitor: monitor, continuity: continuity)
        case .control: ControlTab(monitor: monitor, model: control)
        case .keyControl:
            // 명령 확인 창은 오른쪽 말풍선과 동작 줄이 띄운다(같은 모델의 같은 창).
            KeyControlTab(monitor: monitor, model: keyControl, ownsCommandDialog: false)
        }
    }
}

private struct MenuRow: View {
    enum Style { case card, plain }

    let style: Style
    let symbol: String
    let title: String
    let summary: String
    let selected: Bool
    let action: () -> ()

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: symbol).font(.title3).frame(width: 30).foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.title3.weight(.medium))
                    Text(summary).font(.footnote).foregroundStyle(.secondary).lineLimit(2).multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
                // 열어 둔 줄은 화살표 없는 카드가 된다.
                if style == .card, !selected {
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                }
            }
            .padding(.vertical, style == .card ? 14 : 12)
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: 14))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// 카드는 채워진 둥근 배경, 열어 둔 줄은 한 단계 진한 배경, 카드 없는 줄은 배경 없음. 모두 시스템 의미 색이라 라이트와 다크를 따른다.
    private var fill: Color {
        if selected { return Color(uiColor: .secondarySystemFill) }
        return style == .card ? Color(uiColor: .secondarySystemBackground) : .clear
    }
}

private extension PaneSection {
    var title: String {
        switch self {
        case .liveDetail: return "Live 상세"
        case .query: return "Query"
        case .log: return "Log"
        case .control: return "Control"
        case .keyControl: return "키·제어"
        }
    }

    var symbol: String {
        switch self {
        case .liveDetail: return "dot.radiowaves.left.and.right"
        case .query: return "arrow.triangle.2.circlepath"
        case .log: return "doc.text"
        case .control: return "slider.horizontal.3"
        case .keyControl: return "key.fill"
        }
    }
}

// MARK: - 오른쪽: 차량, 기어와 속도, 동작 줄

/// Live 클러스터를 차량 중심으로 다시 놓은 화면(SFR-024). 말풍선과 동작 줄은 키·제어 탭과 같은 `KeyControlModel` 로 같은 규칙을 따른다:
/// 4단계(제어)가 켜져 있을 때만 켜지고, 세션을 확인한 뒤 한 번만 보내며, 뒤에 `vehicleStatus()` 로 다시 읽는다. `Uncertain` 은 다시 보내지 않는다.
/// 꺼져 있으면 말풍선을 흐리게 하고 키·제어 탭과 같은 주황색 안내 문구를 한 번 보인다.
private struct HomeRightPane: View {
    @ObservedObject var monitor: MonitorModel
    @ObservedObject var model: KeyControlModel
    @ObservedObject var diagnostics: StateDiagnosticsModel
    @ObservedObject var continuity: ScreenContinuity
    @State private var now = Date()

    var body: some View {
        let state = ClusterState(live: monitor.live, link: monitor.link, now: now)
        let drive = DriveSummary(reading: diagnostics.lastDrive, now: now)
        let control = model.steps.control
        let lock = LockCallout.of(lock: monitor.live.items[.lock]?.value)
        VStack(spacing: 6) {
            ClusterHeader(state: state) { continuity.showsSettings = true }
            ClusterGearRow(gear: state.gear).padding(.top, 4)
            speed(drive).opacity(state.isStale ? 0.5 : 1)
            lockCallout(lock, enabled: control.buttonEnabled)
            CarWithCallouts(state: state, enabled: control.buttonEnabled, onOpenDetail: { continuity.showsDetail = true }) { model.press($0) }
            statusLines(control, state: state)
            actionRow(enabled: control.buttonEnabled)
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .tickingNow($now)
        .commandConfirmation(model)
        .clusterSheets(monitor: monitor, continuity: continuity)
    }

    /// 속도: 마지막 수동 Drive 조회(SFR-023)의 원시 값과 "단위 미확인", 조회한 지 얼마나 됐는지. 조회가 없으면 `—`. 자동으로 조회하지 않는다.
    private func speed(_ drive: DriveSummary) -> some View {
        VStack(spacing: 0) {
            Text(drive.speedText)
                .font(.system(size: 64, weight: .bold, design: .rounded).monospacedDigit())
                .accessibilityIdentifier("clusterSpeed")
            Text(drive.unitNote ?? StateDiagnostics.unitUnknown).font(.footnote).foregroundStyle(.secondary)
            Text(drive.ageText ?? "Query 탭의 Drive 로 조회").font(.caption).foregroundStyle(.tertiary)
        }
    }

    private func lockCallout(_ lock: LockCallout, enabled: Bool) -> some View {
        let active = enabled && lock.button != nil
        return VStack(spacing: 0) {
            Button { if let button = lock.button { model.press(button) } } label: {
                VStack(spacing: 2) {
                    Image(systemName: lock.symbol).font(.title2)
                    Text(lock.label).font(.caption2)
                }
                .frame(minWidth: 64, minHeight: 44)
            }
            .buttonStyle(.plain)
            .disabled(!active)
            .opacity(active ? 1 : 0.35)
            Rectangle().fill(Color.secondary.opacity(0.5)).frame(width: 1, height: 10)
        }
    }

    private func statusLines(_ control: StepInfo, state: ClusterState) -> some View {
        VStack(spacing: 2) {
            if control.state == .blocked {
                Text(control.hint).font(.caption).foregroundStyle(Color.orange).multilineTextAlignment(.center)
            }
            if let last = model.results.first {
                Text("\(last.title): \(last.text)").font(.caption).foregroundStyle(last.tone.color).lineLimit(3)
                    .multilineTextAlignment(.center)
            }
            Button { continuity.showsDetail = true } label: {
                Text(state.closureSummary).font(.footnote.weight(.semibold))
                    .foregroundStyle(state.closureSummary.hasSuffix("열림") ? Color.red : .secondary)
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity)
    }

    private func actionRow(enabled: Bool) -> some View {
        HStack(spacing: 0) {
            ForEach(QuickControls.actionRow) { button in
                Button { model.press(button) } label: {
                    VStack(spacing: 4) {
                        Image(systemName: button.symbol).font(.title2)
                        Text(button.title).font(.caption)
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.35)
            }
        }
    }
}

/// 위에서 본 차량과 그 둘레의 말풍선: 앞쪽에 "프렁크 열기", 뒤쪽에 "트렁크 열기/닫기", 충전구가 열렸을 때 그 표시.
/// 말풍선은 가는 선으로 차에 이어지고, 글자는 화면 가장자리에 붙여 폭이 좁아도 잘리지 않는다. 차를 누르면 항목별 상세 시트가 열린다.
private struct CarWithCallouts: View {
    let state: ClusterState
    let enabled: Bool
    let onOpenDetail: () -> ()
    let press: (ControlButton) -> ()

    private let aspect = 400.0 / 760.0

    var body: some View {
        GeometryReader { geo in
            let carHeight = min(geo.size.height, geo.size.width / aspect)
            let carWidth = carHeight * aspect
            let top = (geo.size.height - carHeight) / 2
            let left = (geo.size.width - carWidth) / 2
            ZStack(alignment: .topLeading) {
                Button(action: onOpenDetail) { ClusterCar(state: state) }
                    .buttonStyle(.plain)
                    .opacity(state.isStale ? 0.4 : 1)
                    .frame(width: carWidth, height: carHeight)
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
                    .accessibilityLabel("차량 상태: \(state.closureSummary)")
                    .accessibilityHint("항목별 시각을 엽니다")
                // 앞쪽(그림 위쪽)
                callout(QuickControls.frunk, leading: true, reach: left + carWidth * 0.30)
                    .offset(y: top + carHeight * 0.06)
                // 뒤쪽(그림 아래쪽)
                callout(QuickControls.trunk, leading: false, reach: left + carWidth * 0.30)
                    .offset(y: top + carHeight * 0.86)
                if state.closure(.chargePort) == .open {
                    chargePortNote(reach: left + carWidth * 0.30)
                        .offset(y: top + carHeight * 0.74)
                }
            }
        }
    }

    /// 글자 하나와 가는 선. `leading` 이면 왼쪽 가장자리에서 시작해 차 쪽으로, 아니면 오른쪽 가장자리에서 차 쪽으로 뻗는다.
    private func callout(_ button: ControlButton, leading: Bool, reach: CGFloat) -> some View {
        Button { press(button) } label: {
            VStack(alignment: leading ? .leading : .trailing, spacing: 2) {
                Text(button.title).font(.caption.weight(.medium))
                Rectangle().fill(Color.secondary.opacity(0.5)).frame(width: max(reach, 24), height: 1)
            }
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .frame(maxWidth: .infinity, alignment: leading ? .leading : .trailing)
    }

    private func chargePortNote(reach: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Label("충전구 열림", systemImage: "bolt.fill").font(.caption.weight(.medium)).foregroundStyle(Color.red)
            Rectangle().fill(Color.red.opacity(0.5)).frame(width: max(reach, 24), height: 1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
