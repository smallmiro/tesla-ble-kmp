import SwiftUI

/// Live 탭(SFR-011, SFR-014, SFR-022): 기어, 속도, 잠금, 차량 그림으로 만든 클러스터. VIN과 연결은 ⚙︎ 시트, 항목별 시각과 이벤트는 상세 시트에 있다.
struct LiveTab: View {
    @ObservedObject var monitor: MonitorModel
    @ObservedObject var continuity: ScreenContinuity
    /// 넓은 화면의 두 영역 배치에서만 주는 값(SFR-024): 클러스터 아래에 빠른 제어를 붙인다. 폰 탭에서는 `nil`.
    var quickControls: KeyControlModel?
    @State private var now = Date()

    var body: some View {
        let state = ClusterState(live: monitor.live, link: monitor.link, now: now)
        VStack(spacing: 8) {
            header(state)
            VStack(spacing: 2) {
                gearRow(state.gear)
                Text(state.speedText)
                    .font(.system(size: 88, weight: .bold, design: .rounded).monospacedDigit())
                    .accessibilityIdentifier("clusterSpeed")
                Text("km/h").font(.subheadline).foregroundStyle(.secondary)
                Text(state.lockText).font(.subheadline).foregroundStyle(.secondary).padding(.top, 4)
            }
            .opacity(state.isStale ? 0.4 : 1)
            Button { continuity.showsDetail = true } label: {
                ClusterCar(state: state).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .buttonStyle(.plain)
            .opacity(state.isStale ? 0.4 : 1)
            .accessibilityLabel("차량 상태: \(state.closureSummary)")
            .accessibilityHint("항목별 시각을 엽니다")
            footer(state).opacity(state.isStale ? 0.4 : 1)
            if let quickControls { QuickControlsBar(model: quickControls) }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // 시스템 화면 모드(라이트/다크)를 따른다. 다크 모드의 시스템 배경은 검정이다.
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .tickingNow($now)
        .clusterSheets(monitor: monitor, continuity: continuity)
    }

    private func header(_ state: ClusterState) -> some View {
        ClusterHeader(state: state) { continuity.showsSettings = true }
    }

    private func gearRow(_ gear: ClusterGear?) -> some View { ClusterGearRow(gear: gear) }

    private func footer(_ state: ClusterState) -> some View {
        VStack(spacing: 6) {
            Text(state.closureSummary).font(.subheadline.weight(.semibold))
                .foregroundStyle(state.closureSummary.hasSuffix("열림") ? Color.red : .secondary)
                .multilineTextAlignment(.center)
            HStack {
                Label(state.presenceText, systemImage: "person.fill")
                Spacer()
                Label(state.sleepText, systemImage: "moon.fill")
            }
            .font(.footnote).foregroundStyle(.secondary)
            Divider()
            Button { continuity.showsDetail = true } label: {
                Text(monitor.live.recent.first.map { "\(Formatting.clockText($0.at))  \($0.text)" } ?? "최근 이벤트 없음")
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
        }
    }
}

/// 연결 상태 점과 문구, 신선도, VIN과 연결 설정 버튼(⚙︎). Live 탭과 넓은 화면의 오른쪽 영역(SFR-024)이 같이 쓴다.
struct ClusterHeader: View {
    let state: ClusterState
    let onSettings: () -> ()

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Circle().fill(state.isStale ? Color.gray : .green).frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 0) {
                Text(state.headline).lineLimit(2).accessibilityIdentifier("linkBanner")
                if let freshness = state.freshness { Text(freshness) }
            }
            .font(.footnote).foregroundStyle(.secondary)
            Spacer()
            Button(action: onSettings) { Image(systemName: "gearshape.fill") }
                .accessibilityLabel("VIN과 연결 설정")
        }
    }
}

/// 기어 줄 P R N D. 지금 기어만 진하게 보인다.
struct ClusterGearRow: View {
    let gear: ClusterGear?

    var body: some View {
        HStack(spacing: 24) {
            ForEach(ClusterGear.allCases, id: \.self) { item in
                Text(item.letter)
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(item == gear ? Color.primary : Color.primary.opacity(0.25))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("기어 \(gear?.letter ?? ClusterText.none)")
    }
}

/// VIN이 처음 알려질 때 설정 시트를 자동으로 띄우고, 설정 시트와 상세 시트를 `ScreenContinuity` 의 값으로 연다.
private struct ClusterSheets: ViewModifier {
    @ObservedObject var monitor: MonitorModel
    @ObservedObject var continuity: ScreenContinuity

    func body(content: Content) -> some View {
        content
            .onReceive(monitor.$vinKnown) { known in if known && monitor.savedVin == nil { continuity.showsSettings = true } }
            .sheet(isPresented: $continuity.showsSettings) { ClusterSettingsSheet(monitor: monitor) }
            .sheet(isPresented: $continuity.showsDetail) { ClusterDetailSheet(monitor: monitor) }
    }
}

extension View {
    func clusterSheets(monitor: MonitorModel, continuity: ScreenContinuity) -> some View {
        modifier(ClusterSheets(monitor: monitor, continuity: continuity))
    }
}

/// 위에서 본 차량. 부위 이미지를 같은 캔버스에 겹치고, 열린 문은 경첩을 중심으로 돌린다.
struct ClusterCar: View {
    let state: ClusterState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Image("ClusterCarBase").resizable()
            ForEach(ClusterPart.allCases, id: \.self) { part in partView(part) }
        }
        .aspectRatio(400.0 / 760.0, contentMode: .fit)
    }

    private func partView(_ part: ClusterPart) -> some View {
        let closure = state.closure(part)
        let isOpen = closure == .open
        return ZStack {
            Image(part.assetName).resizable()
            if isOpen { Image(part.assetName).resizable().renderingMode(.template).foregroundStyle(Color.red) }
        }
        .opacity(closure == .unknown ? 0.45 : 1)
        .rotationEffect(.degrees(isOpen ? part.openAngle : 0), anchor: anchor(part))
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: isOpen)
    }

    private func anchor(_ part: ClusterPart) -> UnitPoint {
        guard let hinge = part.hinge else { return .center }
        return UnitPoint(x: hinge.x / 400, y: hinge.y / 760)
    }
}

/// VIN 저장·지우기, 연결·끊기. VIN이 없으면 Live 탭이 처음 열 때 자동으로 띄운다.
struct ClusterSettingsSheet: View {
    @ObservedObject var monitor: MonitorModel
    @State private var vinText = ""
    @State private var vinError = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section { LinkBanner(monitor: monitor).listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
                Section("차량") {
                    if let masked = monitor.maskedVin {
                        Text("저장된 VIN: \(masked)")
                    } else {
                        TextField("VIN (17자, 한 번만 입력하면 저장됩니다)", text: $vinText)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                        if vinError { Text("VIN 형식이 맞지 않습니다").font(.footnote).foregroundStyle(.red) }
                        Button("VIN 저장") { vinError = !monitor.saveVin(vinText) }
                    }
                    Button("연결") { _ = monitor.connect() }
                        .disabled(monitor.savedVin == nil)
                    Button("연결 끊기", role: .destructive) { Task { await monitor.disconnect() } }
                    Button("VIN 지우기", role: .destructive) { Task { await monitor.clearVin() } }
                        .disabled(monitor.savedVin == nil)
                    if monitor.isStopped {
                        Text("자동 재연결이 멈춰 있습니다. 연결 버튼을 누르면 다시 시작합니다.").font(.footnote)
                    }
                }
            }
            .navigationTitle("VIN과 연결")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("닫기") { dismiss() } }
        }
    }
}

/// 항목별 원본 값과 마지막 변경 시각, 최근 이벤트(SFR-014)를 시트로 보여 준다.
struct ClusterDetailSheet: View {
    @ObservedObject var monitor: MonitorModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ClusterDetailForm(monitor: monitor)
                .navigationTitle("차량 상태")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("닫기") { dismiss() } }
        }
    }
}

/// 항목별 원본 값과 마지막 변경 시각, 최근 이벤트(SFR-014). 시트와 넓은 화면의 왼쪽 영역(SFR-024)이 같이 쓴다.
struct ClusterDetailForm: View {
    @ObservedObject var monitor: MonitorModel
    @State private var now = Date()

    var body: some View {
        Form {
            Section("차량 상태 (방송 또는 조회)") {
                if let last = monitor.live.lastStatusAt {
                    Text("마지막 수신 \(Formatting.clockText(last)) (\(Formatting.ago(since: last, now: now)))").font(.footnote)
                } else {
                    Text("아직 받은 상태가 없습니다").font(.footnote)
                }
                ForEach(LiveItemKey.allCases, id: \.self) { key in
                    LabeledContent(key.label) {
                        if let item = monitor.live.items[key] {
                            Text("\(item.value)  ·  \(item.changed ? "바뀜" : "처음 관측") \(Formatting.clockText(item.since))")
                                .font(.footnote)
                        } else {
                            Text(ClusterText.none)
                        }
                    }
                }
                Text("참고: 실내 버튼으로 트렁크를 열고 닫으면 방송이 오지 않을 수 있습니다. 트렁크는 Query 탭의 vehicleStatus() 로 확인하세요.")
                    .font(.footnote)
            }
            Section("최근 이벤트 (바뀐 것, 인증 요청, 알 수 없는 방송)") {
                if monitor.live.recent.isEmpty { Text("없음").font(.footnote) }
                ForEach(monitor.live.recent) { MonoSmall("\(Formatting.clockText($0.at))  \($0.text)") }
            }
        }
        .tickingNow($now)
    }
}
