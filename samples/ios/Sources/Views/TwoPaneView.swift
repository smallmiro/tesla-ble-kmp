import SwiftUI

/// 넓은 화면의 두 영역 배치(SFR-024, 접는 폰의 안쪽 화면 등). 공식 Tesla 앱의 큰 화면 배치를 따른다:
/// 왼쪽은 항목 메뉴(고른 항목이 그 자리에 나온다), 오른쪽은 늘 보이는 Live 클러스터와 아래의 빠른 제어.
/// 항목 화면은 폰 탭과 같은 뷰를 그대로 쓴다(복사 없음). 어느 항목을 열었는지는 `AppNavigation` 하나라서 접어도 이어진다.
struct TwoPaneView: View {
    @ObservedObject var monitor: MonitorModel
    let continuity: ScreenContinuity
    @Binding var nav: AppNavigation
    let width: CGFloat

    var body: some View {
        HStack(spacing: 0) {
            leftPane.frame(width: DuoLayout.leftPaneWidth(total: width))
            Divider()
            // 오른쪽 가장자리의 도크(안전 영역)는 SwiftUI 가 이 영역의 안쪽 여백으로 처리한다. 배경만 가장자리까지 칠한다.
            LiveTab(monitor: monitor, continuity: continuity, quickControls: monitor.keyControl)
                .frame(minWidth: DuoLayout.rightPaneMinWidth, maxWidth: .infinity)
        }
    }

    private var leftPane: some View {
        NavigationStack(path: path) {
            List {
                ForEach(PaneSection.allCases, id: \.self) { section in
                    NavigationLink(value: section) { Label(section.title, systemImage: section.symbol) }
                }
            }
            .navigationTitle("메뉴")
            .navigationDestination(for: PaneSection.self) { destination($0) }
        }
    }

    /// 열린 항목을 NavigationStack 경로로 바꾼다: 메뉴(`nil`)는 빈 경로, 항목은 길이 1.
    private var path: Binding<[PaneSection]> {
        Binding(get: { nav.pane.map { [$0] } ?? [] }, set: { nav.pane = $0.last })
    }

    @ViewBuilder
    private func destination(_ section: PaneSection) -> some View {
        switch section {
        case .liveDetail: ClusterDetailForm(monitor: monitor).navigationTitle("Live 상세")
        case .query: QueryTab(monitor: monitor).navigationTitle("Query")
        case .log: LogTab(monitor: monitor, continuity: continuity).navigationTitle("Log")
        case .control: ControlTab(monitor: monitor, model: monitor.control).navigationTitle("Control")
        case .keyControl:
            // 명령 확인 창은 오른쪽 빠른 제어가 띄운다(같은 모델의 같은 창).
            KeyControlTab(monitor: monitor, model: monitor.keyControl, ownsCommandDialog: false).navigationTitle("키·제어")
        }
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

/// Live 클러스터 아래의 빠른 제어(SFR-024). 키·제어 탭과 같은 `KeyControlModel` 로 같은 규칙을 따른다:
/// 4단계(제어)가 켜져 있을 때만 켜지고, 세션을 확인한 뒤 한 번만 보내며, 뒤에 `vehicleStatus()` 로 다시 읽는다. `Uncertain` 은 다시 보내지 않는다.
/// 꺼져 있으면 키·제어 탭과 같은 안내 문구를 보인다.
struct QuickControlsBar: View {
    @ObservedObject var model: KeyControlModel

    var body: some View {
        let control = model.steps.control
        VStack(spacing: 8) {
            Text(control.hint)
                .font(.caption)
                .foregroundStyle(control.state == .blocked ? Color.orange : Color.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
            // 좁은 오른쪽 영역에서는 줄이 바뀐다(한 줄에 72pt 이상씩).
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 8)], spacing: 8) {
                ForEach(QuickControls.buttons) { button in
                    Button { model.press(button) } label: {
                        VStack(spacing: 4) {
                            Image(systemName: button.symbol).font(.title3)
                            Text(button.title).font(.caption2).multilineTextAlignment(.center).lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, minHeight: 54)
                    }
                    .buttonStyle(.bordered)
                    .tint(.white)
                    .disabled(!control.buttonEnabled)
                }
            }
            if let last = model.results.first {
                Text("\(last.title): \(last.text)")
                    .font(.caption)
                    .foregroundStyle(last.tone.color)
                    .lineLimit(3)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        // 키·제어 탭이 나타날 때와 같이, 다른 곳에서 등록이 바뀌었을 수 있어 등록 상태를 다시 확인한다.
        .onAppear { model.tabAppeared() }
        .commandConfirmation(model)
    }
}
