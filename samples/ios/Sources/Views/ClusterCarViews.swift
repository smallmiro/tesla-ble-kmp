import SwiftUI

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

/// 위에서 본 차량과 그 둘레의 말풍선: 앞쪽에 "프렁크 열기", 뒤쪽에 "트렁크 열기/닫기", 충전구가 열렸을 때 그 표시.
/// 말풍선은 가는 선으로 차에 이어지고, 글자는 화면 가장자리에 붙여 폭이 좁아도 잘리지 않는다. 차를 누르면 항목별 상세 시트가 열린다.
struct CarWithCallouts: View {
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
