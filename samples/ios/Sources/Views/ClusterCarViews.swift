import SwiftUI

/// 차량 사진(`ClusterPhoto`)과 열린 부위의 빨간 아이콘(SFR-022). 닫힘과 모름은 아이콘이 없다.
/// 사진의 검은 바탕은 밝은 모드에서도 어두운 둥근 판으로 둔다(다크 모드의 검정 배경에서는 판 가장자리가 보이지 않는다).
/// 말풍선이 있는 사진(side)은 아이콘에서 부위로 가는 선도 긋는다. 모든 좌표는 사진 크기를 곱한 정규화 좌표다.
struct ClusterCar: View {
    let photo: ClusterPhoto
    let state: ClusterState

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            ZStack {
                Image(photo.assetName).resizable().frame(width: size.width, height: size.height)
                ForEach(state.openMarkers(on: photo), id: \.part) { marker in
                    if photo.drawsLines {
                        PhotoLine(from: marker.spot.icon, to: marker.spot.car, size: size, color: Color.red.opacity(0.7))
                    }
                    OpenIcon(symbol: marker.part.openSymbol)
                        .at(marker.spot.icon, in: size)
                }
            }
        }
        .aspectRatio(photo.aspect, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

/// 두 영역 배치의 차량(SFR-024): 옆에서 본 사진과 공식 Tesla 앱처럼 사진 둘레의 제어. 사진 위 가운데 잠금 아이콘과 지붕까지 내려가는 선,
/// 왼쪽 위 "열기 전면"(프렁크)과 오른쪽 위 "열기/닫기 트렁크" 글자와 차 앞뒤로 가는 선, 차 뒤 옆의 충전구 번개(누를 수 없음).
/// 잠금, 프렁크, 트렁크는 키·제어 탭과 같은 버튼이라 같은 규칙(4단계가 켜졌을 때만, 한 번만 보냄, 프렁크는 확인 창)을 따른다.
/// 차를 누르면 항목별 상세 시트가 열린다.
struct CarWithCallouts: View {
    let state: ClusterState
    let lock: LockCallout
    let enabled: Bool
    let onOpenDetail: () -> ()
    let press: (ControlButton) -> ()

    private let photo = ClusterPhoto.photo(for: .twoPane)
    /// 글자와 아이콘의 누르는 영역 높이(pt). 44 이상.
    private let target: CGFloat = 44

    var body: some View {
        Button(action: onOpenDetail) {
            ClusterCar(photo: photo, state: state).opacity(state.isStale ? 0.4 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("차량 상태: \(state.closureSummary)")
        .accessibilityHint("항목별 시각을 엽니다")
        .overlay { GeometryReader { geo in callouts(geo.size) } }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .environment(\.colorScheme, .dark)
    }

    private func callouts(_ size: CGSize) -> some View {
        ZStack {
            lockCallout(size)
            textCallout(QuickControls.frunk, part: .frunk, top: "열기", bottom: "전면", size: size)
            textCallout(QuickControls.trunk, part: .trunk, top: "열기/닫기", bottom: "트렁크", size: size)
            chargePort(size)
        }
        .frame(width: size.width, height: size.height)
    }

    private func lockCallout(_ size: CGSize) -> some View {
        let spot = photo.lockSpot
        let active = enabled && lock.button != nil
        return ZStack {
            PhotoLine(from: CGPoint(x: spot.icon.x, y: spot.icon.y + target / 2 / size.height), to: spot.car, size: size, color: Color.white.opacity(0.5))
            Button { if let button = lock.button { press(button) } } label: {
                Image(systemName: lock.symbol).font(.title2).foregroundStyle(.white)
                    .frame(width: target, height: target).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!active)
            .opacity(active ? 1 : 0.7)
            .accessibilityLabel(lock.label)
            .at(spot.icon, in: size)
        }
    }

    /// 두 줄 글자(작은 회색 동작, 굵은 이름)와 그 아래로 곧게 내려가는 가는 선. 글자를 누르면 그 버튼을 보낸다.
    private func textCallout(_ button: ControlButton, part: ClusterPart, top: String, bottom: String, size: CGSize) -> some View {
        let spot = photo.spot(part)
        let labelCenter = CGPoint(x: spot.icon.x, y: spot.icon.y + target / 2 / size.height)
        return ZStack {
            PhotoLine(from: CGPoint(x: spot.icon.x, y: spot.icon.y + (target - 4) / size.height), to: spot.car, size: size, color: Color.white.opacity(0.5))
            Button { press(button) } label: {
                VStack(spacing: 0) {
                    Text(top).font(.system(size: 11)).foregroundStyle(Color.white.opacity(0.6))
                    Text(bottom).font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                }
                .frame(minWidth: 64, minHeight: target)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.7)
            .accessibilityLabel(button.title)
            .at(labelCenter, in: size)
        }
    }

    /// 충전구: 열리면 빨강, 아니면 회색. 누를 수 없는 표시라 상태 요약 글자와 상세 시트가 말로 알린다.
    private func chargePort(_ size: CGSize) -> some View {
        let spot = photo.spot(.chargePort)
        let isOpen = state.closure(.chargePort) == .open
        let color = isOpen ? Color.red : Color.white.opacity(0.45)
        return ZStack {
            PhotoLine(from: spot.icon, to: spot.car, size: size, color: color.opacity(0.8))
            Image(systemName: "bolt.fill").font(.system(size: 15, weight: .semibold)).foregroundStyle(color)
                .accessibilityHidden(true)
                .at(spot.icon, in: size)
        }
    }
}

/// 열린 부위의 빨간 아이콘. 어두운 반투명 원 위에 얹어 밝은 부분에서도 보인다.
private struct OpenIcon: View {
    let symbol: String

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Color.red)
            .frame(width: 26, height: 26)
            .background(Circle().fill(Color.black.opacity(0.7)))
            .overlay(Circle().stroke(Color.red.opacity(0.7), lineWidth: 1))
            .accessibilityHidden(true)
    }
}

/// 정규화 좌표 두 점을 잇는 가는 선.
private struct PhotoLine: View {
    let from: CGPoint
    let to: CGPoint
    let size: CGSize
    let color: Color

    var body: some View {
        Path { path in
            path.move(to: CGPoint(x: from.x * size.width, y: from.y * size.height))
            path.addLine(to: CGPoint(x: to.x * size.width, y: to.y * size.height))
        }
        .stroke(color, lineWidth: 1)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private extension View {
    /// 정규화 좌표를 중심으로 놓는다(가운데 정렬 ZStack 안에서). `position` 과 달리 누르는 영역이 이 뷰의 영역에 머문다.
    func at(_ point: CGPoint, in size: CGSize) -> some View {
        offset(x: (point.x - 0.5) * size.width, y: (point.y - 0.5) * size.height)
    }
}
