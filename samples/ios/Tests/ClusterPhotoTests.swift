import UIKit
import XCTest
@testable import TeslableSample

/// SFR-022, SFR-024: 배치가 차량 사진을 고르고, 열린 부위만 사진 위 아이콘이 된다. 아이콘 위치는 사진마다 정규화 좌표(0...1) 표다.
final class ClusterPhotoTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_792_803_600)

    private func cluster(_ snapshot: StatusSnapshot) -> ClusterState {
        let live = LiveReducer.reduce(LiveState(), MonitorEvent(kind: .status(snapshot), raw: [1], unknownFields: []), now: t0)
        return ClusterState(live: live, link: LinkState<Int>.connected(1), now: t0)
    }

    private let allClosed = StatusSnapshot(
        frontDriverDoor: "CLOSED", frontPassengerDoor: "CLOSED", rearDriverDoor: "CLOSED", rearPassengerDoor: "CLOSED",
        frunk: "CLOSED", trunk: "CLOSED", chargePort: "CLOSED"
    )

    // MARK: 사진 고르기

    func testPhoneLayoutUsesTheTopDownPhotoAndTwoPaneUsesTheSidePhoto() {
        XCTAssertEqual(ClusterPhoto.photo(for: .phone), .top)
        XCTAssertEqual(ClusterPhoto.photo(for: .twoPane), .side)
    }

    func testLayoutRuleDecidesThePhotoForPhoneDuoOuterIpadAndDuoInner() {
        // 일반 폰·Duo 바깥 화면: compact 또는 좁음 → 탭 → top. Duo 안쪽·iPad: regular 이고 넓음 → 두 영역 → side.
        XCTAssertEqual(ClusterPhoto.photo(for: DuoLayout.mode(isRegularWidth: false, width: 402)), .top)
        XCTAssertEqual(ClusterPhoto.photo(for: DuoLayout.mode(isRegularWidth: true, width: 669)), .side)
        XCTAssertEqual(ClusterPhoto.photo(for: DuoLayout.mode(isRegularWidth: true, width: 1_024)), .side)
    }

    func testEachPhotoIsInTheAssetCatalogWithItsAspect() throws {
        for photo in ClusterPhoto.allCases {
            let image = try XCTUnwrap(UIImage(named: photo.assetName, in: Bundle(for: MonitorModel.self), compatibleWith: nil), photo.assetName)
            XCTAssertEqual(image.size.width / image.size.height, photo.aspect, accuracy: 0.002, photo.assetName)
        }
        XCTAssertGreaterThan(ClusterPhoto.side.aspect, 1)
        XCTAssertLessThan(ClusterPhoto.top.aspect, 1)
    }

    // MARK: 열린 부위만 아이콘

    func testAllClosedAndUnknownShowNoIcons() {
        for photo in ClusterPhoto.allCases {
            XCTAssertEqual(cluster(allClosed).openMarkers(on: photo), [], "\(photo)")
            XCTAssertEqual(cluster(StatusSnapshot()).openMarkers(on: photo), [], "unknown \(photo)")
        }
    }

    func testOnlyOpenPartsGetIconsInPartOrder() {
        var snapshot = allClosed
        snapshot.frontPassengerDoor = "OPEN"
        snapshot.trunk = "AJAR"
        snapshot.chargePort = "UNKNOWN"
        for photo in ClusterPhoto.allCases {
            let markers = cluster(snapshot).openMarkers(on: photo)
            XCTAssertEqual(markers.map(\.part), [.doorFrontRight, .trunk], "\(photo)")
            XCTAssertEqual(markers.map(\.spot), [photo.spot(.doorFrontRight), photo.spot(.trunk)], "\(photo)")
        }
    }

    func testEverySevenPartsCanBeOpenAtOnce() {
        let open = StatusSnapshot(
            frontDriverDoor: "OPEN", frontPassengerDoor: "OPEN", rearDriverDoor: "OPEN", rearPassengerDoor: "OPEN",
            frunk: "OPEN", trunk: "OPEN", chargePort: "OPEN"
        )
        XCTAssertEqual(cluster(open).openMarkers(on: .top).count, 7)
        XCTAssertEqual(cluster(open).openMarkers(on: .side).count, 7)
    }

    func testIconSymbolsExistAndLeftRightDoorsDiffer() {
        for part in ClusterPart.allCases {
            XCTAssertNotNil(UIImage(systemName: part.openSymbol), part.openSymbol)
        }
        XCTAssertNotEqual(ClusterPart.doorFrontLeft.openSymbol, ClusterPart.doorFrontRight.openSymbol)
        XCTAssertEqual(ClusterPart.doorFrontLeft.openSymbol, ClusterPart.doorRearLeft.openSymbol)
        XCTAssertEqual(ClusterPart.doorFrontRight.openSymbol, ClusterPart.doorRearRight.openSymbol)
    }

    // MARK: 좌표 표

    func testEveryAnchorIsInsideThePhoto() {
        for photo in ClusterPhoto.allCases {
            let spots = ClusterPart.allCases.map { photo.spot($0) } + [photo.lockSpot]
            for spot in spots {
                for point in [spot.car, spot.icon] {
                    XCTAssertTrue((0...1).contains(point.x) && (0...1).contains(point.y), "\(photo) \(point)")
                }
            }
        }
    }

    func testTopPhotoPutsIconsOnTheirPartsWithoutLines() {
        XCTAssertFalse(ClusterPhoto.top.drawsLines)
        for part in ClusterPart.allCases { XCTAssertEqual(ClusterPhoto.top.spot(part).icon, ClusterPhoto.top.spot(part).car) }
        let top = ClusterPhoto.top
        // 앞이 위, 왼쪽이 운전석.
        XCTAssertLessThan(top.spot(.doorFrontLeft).car.x, 0.5)
        XCTAssertGreaterThan(top.spot(.doorFrontRight).car.x, 0.5)
        XCTAssertLessThan(top.spot(.doorRearLeft).car.x, 0.5)
        XCTAssertGreaterThan(top.spot(.doorRearRight).car.x, 0.5)
        XCTAssertLessThan(top.spot(.doorFrontLeft).car.y, top.spot(.doorRearLeft).car.y)
        XCTAssertLessThan(top.spot(.doorFrontRight).car.y, top.spot(.doorRearRight).car.y)
        XCTAssertLessThan(top.spot(.frunk).car.y, top.spot(.doorFrontLeft).car.y)
        XCTAssertGreaterThan(top.spot(.trunk).car.y, top.spot(.doorRearLeft).car.y)
        XCTAssertGreaterThan(top.spot(.chargePort).car.y, top.spot(.doorRearLeft).car.y)
        XCTAssertLessThan(top.spot(.chargePort).car.x, 0.5)
    }

    func testSidePhotoHasFrontAtLowerLeftAndRearAtUpperRight() {
        let side = ClusterPhoto.side
        XCTAssertTrue(side.drawsLines)
        XCTAssertLessThan(side.spot(.frunk).car.x, side.spot(.trunk).car.x)
        XCTAssertGreaterThan(side.spot(.frunk).car.y, side.spot(.trunk).car.y)
        // 말풍선 글자는 위 왼쪽(전면), 위 오른쪽(트렁크). 선은 아래로 곧게 내려간다.
        XCTAssertLessThan(side.spot(.frunk).icon.x, 0.5)
        XCTAssertGreaterThan(side.spot(.trunk).icon.x, 0.5)
        XCTAssertEqual(side.spot(.frunk).icon.x, side.spot(.frunk).car.x, accuracy: 0.0001)
        XCTAssertEqual(side.spot(.trunk).icon.x, side.spot(.trunk).car.x, accuracy: 0.0001)
        XCTAssertLessThan(side.spot(.frunk).icon.y, side.spot(.frunk).car.y)
        XCTAssertLessThan(side.spot(.trunk).icon.y, side.spot(.trunk).car.y)
        // 충전구 아이콘은 차 뒤 옆(오른쪽)에 있고 짧은 가로선으로 이어진다.
        XCTAssertGreaterThan(side.spot(.chargePort).icon.x, side.spot(.chargePort).car.x)
        XCTAssertEqual(side.spot(.chargePort).icon.y, side.spot(.chargePort).car.y, accuracy: 0.06)
    }

    func testSidePhotoDoorsFollowTheNearAndFarSides() {
        let side = ClusterPhoto.side
        // 보이는 쪽은 운전석(왼쪽). 앞문이 뒷문보다 앞(왼쪽).
        XCTAssertLessThan(side.spot(.doorFrontLeft).car.x, side.spot(.doorRearLeft).car.x)
        XCTAssertLessThan(side.spot(.doorFrontRight).car.x, side.spot(.doorRearRight).car.x)
        // 먼 쪽(오른쪽) 문은 가까운 쪽(왼쪽) 문보다 사진 위쪽에 있다.
        XCTAssertLessThan(side.spot(.doorFrontRight).car.y, side.spot(.doorFrontLeft).car.y)
        XCTAssertLessThan(side.spot(.doorRearRight).car.y, side.spot(.doorRearLeft).car.y)
    }

    func testSideLockSitsCenteredAboveTheRoofLine() {
        let lock = ClusterPhoto.side.lockSpot
        XCTAssertEqual(lock.icon.x, 0.5, accuracy: 0.0001)
        XCTAssertEqual(lock.car.x, 0.5, accuracy: 0.0001)
        XCTAssertLessThan(lock.icon.y, lock.car.y)
    }
}
