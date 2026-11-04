import CoreGraphics

/// Live 클러스터의 차량 사진(SFR-022, SFR-024). 배치가 사진을 고르고, 열린 부위는 사진 위 빨간 아이콘으로만 보인다(문이 돌아가거나 부위를 겹쳐 그리지 않는다).
/// 아이콘 위치는 사진마다 정규화 좌표(왼쪽 위가 (0,0), 오른쪽 아래가 (1,1)) 표 하나다. 사진을 바꾸면 이 표만 고친다.
enum ClusterPhoto: CaseIterable, Sendable {
    /// 위에서 본 차(앞이 위). 폰 탭 배치(일반 폰, 접는 폰의 바깥 화면).
    case top
    /// 앞 왼쪽에서 비스듬히 본 차(앞이 왼쪽 아래, 뒤가 오른쪽 위). 두 영역 배치(접는 폰의 안쪽 화면, iPad).
    case side

    /// 쓰는 사진은 `DuoLayout` 규칙이 정한 배치 하나로만 정해진다.
    static func photo(for mode: DuoLayout.Mode) -> ClusterPhoto {
        switch mode {
        case .phone: return .top
        case .twoPane: return .side
        }
    }

    /// `Assets.xcassets` 의 이미지 이름.
    var assetName: String {
        switch self {
        case .top: return "ClusterCarTop"
        case .side: return "ClusterCarSide"
        }
    }

    /// 사진의 가로 ÷ 세로.
    var aspect: CGFloat {
        switch self {
        case .top: return 2.0 / 3.0
        case .side: return 3.0 / 2.0
        }
    }

    /// 아이콘에서 차 쪽으로 선을 긋는지. 폰 탭(top)은 말풍선 없이 부위 위에 아이콘만 둔다.
    var drawsLines: Bool { self == .side }

    /// 부위 하나의 위치. `car` 는 사진 속 그 부위(선이 닿는 곳), `icon` 은 아이콘이나 글자를 놓는 곳이다.
    /// 폰 탭(top)은 둘이 같다.
    func spot(_ part: ClusterPart) -> PhotoSpot {
        switch self {
        case .top: return topSpot(part)
        case .side: return sideSpot(part)
        }
    }

    /// 두 영역 배치의 잠금 아이콘(사진 위 가운데)과 지붕 위 끝점. 폰 탭에는 쓰지 않는다.
    var lockSpot: PhotoSpot {
        PhotoSpot(car: CGPoint(x: 0.5, y: 0.225), icon: CGPoint(x: 0.5, y: 0.045))
    }

    private func topSpot(_ part: ClusterPart) -> PhotoSpot {
        let point: CGPoint
        switch part {
        case .doorFrontLeft: point = CGPoint(x: 0.245, y: 0.52)
        case .doorRearLeft: point = CGPoint(x: 0.245, y: 0.72)
        case .doorFrontRight: point = CGPoint(x: 0.755, y: 0.52)
        case .doorRearRight: point = CGPoint(x: 0.755, y: 0.72)
        case .frunk: point = CGPoint(x: 0.5, y: 0.17)
        case .trunk: point = CGPoint(x: 0.5, y: 0.905)
        case .chargePort: point = CGPoint(x: 0.285, y: 0.885)
        }
        return PhotoSpot(car: point, icon: point)
    }

    private func sideSpot(_ part: ClusterPart) -> PhotoSpot {
        switch part {
        // 보이는 쪽은 운전석(왼쪽). 아이콘은 차 아래 검은 바탕에 두고 선으로 문에 잇는다.
        case .doorFrontLeft: return PhotoSpot(car: CGPoint(x: 0.674, y: 0.52), icon: CGPoint(x: 0.67, y: 0.80))
        case .doorRearLeft: return PhotoSpot(car: CGPoint(x: 0.80, y: 0.46), icon: CGPoint(x: 0.80, y: 0.72))
        // 먼 쪽(오른쪽) 문은 안 보인다. 지붕 유리 먼 쪽 가장자리를 가리킨다.
        case .doorFrontRight: return PhotoSpot(car: CGPoint(x: 0.60, y: 0.135), icon: CGPoint(x: 0.585, y: 0.235))
        case .doorRearRight: return PhotoSpot(car: CGPoint(x: 0.75, y: 0.115), icon: CGPoint(x: 0.76, y: 0.215))
        // 프렁크: 왼쪽 위 글자에서 곧게 내려와 보닛에 닿는다. 트렁크: 오른쪽 위 글자에서 차 뒤로.
        case .frunk: return PhotoSpot(car: CGPoint(x: 0.215, y: 0.57), icon: CGPoint(x: 0.215, y: 0.012))
        case .trunk: return PhotoSpot(car: CGPoint(x: 0.895, y: 0.33), icon: CGPoint(x: 0.895, y: 0.012))
        // 충전구: 차 뒤 오른쪽 옆, 짧은 가로선.
        case .chargePort: return PhotoSpot(car: CGPoint(x: 0.915, y: 0.22), icon: CGPoint(x: 0.965, y: 0.22))
        }
    }
}

/// 사진 위 한 부위의 정규화 좌표 한 쌍.
struct PhotoSpot: Equatable, Sendable {
    let car: CGPoint
    let icon: CGPoint
}

/// 열린 부위 하나의 아이콘. 열림만 그리므로 `closed`·`unknown` 은 목록에 들어오지 않는다.
struct OpenMarker: Equatable, Sendable {
    let part: ClusterPart
    let spot: PhotoSpot
}

extension ClusterPart {
    /// 열렸을 때 빨간색으로 그리는 SF Symbol. 왼쪽과 오른쪽 문은 문 손잡이 방향이 다르다.
    var openSymbol: String {
        switch self {
        case .doorFrontLeft, .doorRearLeft: return "door.left.hand.open"
        case .doorFrontRight, .doorRearRight: return "door.right.hand.open"
        case .frunk: return "car.side.front.open"
        case .trunk: return "car.side.rear.open"
        case .chargePort: return "bolt.fill"
        }
    }
}

extension ClusterState {
    /// 열린 부위의 아이콘 목록(`ClusterPart.allCases` 순서). 닫힘과 모름은 아이콘이 없다(모름은 요약 글자가 말한다).
    /// 옆 사진(side)에서는 충전구를 늘 보이는 번개 말풍선이 그리므로 목록에 넣지 않고,
    /// 프렁크와 트렁크 아이콘은 말풍선 글자가 아니라 선이 차에 닿는 곳(`car`)에 둔다. 화면은 이 목록을 그대로 그린다.
    func openMarkers(on photo: ClusterPhoto) -> [OpenMarker] {
        ClusterPart.allCases
            .filter { closure($0) == .open }
            .compactMap { part in
                let spot = photo.spot(part)
                guard photo == .side else { return OpenMarker(part: part, spot: spot) }
                switch part {
                case .chargePort: return nil
                case .frunk, .trunk: return OpenMarker(part: part, spot: PhotoSpot(car: spot.car, icon: spot.car))
                case .doorFrontLeft, .doorRearLeft, .doorFrontRight, .doorRearRight: return OpenMarker(part: part, spot: spot)
                }
            }
    }
}
