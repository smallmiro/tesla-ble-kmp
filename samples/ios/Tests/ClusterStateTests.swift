import XCTest
@testable import TeslableSample

final class ClusterStateTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_792_803_600)

    private func live(_ snapshot: StatusSnapshot) -> LiveState {
        LiveReducer.reduce(LiveState(), MonitorEvent(kind: .status(snapshot), raw: [1], unknownFields: []), now: t0)
    }

    private func cluster(_ state: LiveState, link: LinkState<Int> = .connected(1), now: Date? = nil) -> ClusterState {
        ClusterState(live: state, link: link, now: now ?? t0)
    }

    func testSpeedStaysEmptyBecauseNoDocumentedApiReportsIt() {
        XCTAssertEqual(cluster(live(StatusSnapshot(gear: "DRIVE"))).speedText, "—")
    }

    func testGearMapsTheFourKnownValuesAndNothingElse() {
        XCTAssertEqual(ClusterGear(raw: "PARK"), .park)
        XCTAssertEqual(ClusterGear(raw: "REVERSE"), .reverse)
        XCTAssertEqual(ClusterGear(raw: "NEUTRAL"), .neutral)
        XCTAssertEqual(ClusterGear(raw: "DRIVE"), .drive)
        XCTAssertNil(ClusterGear(raw: "UNKNOWN"))
        XCTAssertNil(ClusterGear(raw: nil))
    }

    func testOnlyClosedCountsAsClosedAndUnknownIsNeverGuessed() {
        XCTAssertEqual(ClusterClosure(raw: "CLOSED"), .closed)
        for raw in ["OPEN", "AJAR", "OPENING", "CLOSING", "FAILED_UNLATCH"] {
            XCTAssertEqual(ClusterClosure(raw: raw), .open, raw)
        }
        XCTAssertEqual(ClusterClosure(raw: "UNKNOWN"), .unknown)
        XCTAssertEqual(ClusterClosure(raw: nil), .unknown)
    }

    func testLockPresenceAndSleepTexts() {
        XCTAssertEqual(ClusterText.lock("LOCKED"), "잠김")
        XCTAssertEqual(ClusterText.lock("INTERNAL_LOCKED"), "내부 잠금")
        XCTAssertEqual(ClusterText.lock("UNLOCKED"), "잠금 해제됨")
        XCTAssertEqual(ClusterText.lock("SELECTIVE_UNLOCKED"), "일부 해제됨")
        XCTAssertEqual(ClusterText.lock("UNKNOWN"), "—")
        XCTAssertEqual(ClusterText.lock(nil), "—")
        XCTAssertEqual(ClusterText.presence("PRESENT"), "사용자 있음")
        XCTAssertEqual(ClusterText.presence("NOT_PRESENT"), "사용자 없음")
        XCTAssertEqual(ClusterText.presence("UNKNOWN"), "—")
        XCTAssertEqual(ClusterText.sleep("AWAKE"), "깨어 있음")
        XCTAssertEqual(ClusterText.sleep("ASLEEP"), "잠듦")
        XCTAssertEqual(ClusterText.sleep(nil), "—")
    }

    func testEveryPartReadsItsOwnLiveItem() {
        let state = live(StatusSnapshot(
            frontDriverDoor: "OPEN", frontPassengerDoor: "CLOSED", rearDriverDoor: "CLOSED", rearPassengerDoor: "CLOSED",
            frunk: "OPEN", trunk: "CLOSED", chargePort: "UNKNOWN"
        ))
        let c = cluster(state)

        XCTAssertEqual(c.closure(.doorFrontLeft), .open)
        XCTAssertEqual(c.closure(.doorFrontRight), .closed)
        XCTAssertEqual(c.closure(.frunk), .open)
        XCTAssertEqual(c.closure(.chargePort), .unknown)
        XCTAssertEqual(c.closureSummary, "운전석 문, 프렁크 열림")
    }

    func testSummaryClaimsAllClosedOnlyWhenEveryPartIsKnown() {
        let allClosed = StatusSnapshot(
            frontDriverDoor: "CLOSED", frontPassengerDoor: "CLOSED", rearDriverDoor: "CLOSED", rearPassengerDoor: "CLOSED",
            frunk: "CLOSED", trunk: "CLOSED", chargePort: "CLOSED"
        )
        XCTAssertEqual(cluster(live(allClosed)).closureSummary, "방송 기준 모두 닫힘")

        var partial = allClosed
        partial.chargePort = "UNKNOWN"
        XCTAssertEqual(cluster(live(partial)).closureSummary, "—")
        XCTAssertEqual(cluster(LiveState()).closureSummary, "—")
    }

    func testDoorHingesSwingOutwardOnEachSide() {
        XCTAssertGreaterThan(ClusterPart.doorFrontLeft.openAngle, 0)
        XCTAssertGreaterThan(ClusterPart.doorRearLeft.openAngle, 0)
        XCTAssertLessThan(ClusterPart.doorFrontRight.openAngle, 0)
        XCTAssertLessThan(ClusterPart.doorRearRight.openAngle, 0)
        XCTAssertEqual(ClusterPart.frunk.openAngle, 0)
    }

    func testConnectedShowsTheAgeOfTheLastBroadcast() {
        let c = cluster(live(StatusSnapshot()), now: t0.addingTimeInterval(3))

        XCTAssertFalse(c.isStale)
        XCTAssertEqual(c.headline, "연결됨")
        XCTAssertEqual(c.freshness, "3초 전")
    }

    func testConnectedBeforeAnyBroadcastSaysSoAndDimsTheValues() {
        let c = cluster(LiveState())

        XCTAssertTrue(c.isStale)
        XCTAssertEqual(c.freshness, "받은 방송 없음")
    }

    func testAReconnectStaysDimUntilThatConnectionHearsABroadcast() {
        let reconnected = LiveReducer.linkStarted(live(StatusSnapshot(frontDriverDoor: "OPEN")))
        let c = cluster(reconnected)

        XCTAssertTrue(c.isStale)
        XCTAssertEqual(c.freshness, "마지막 수신 \(Formatting.clockText(t0))")

        let heard = LiveReducer.reduce(reconnected, MonitorEvent(kind: .status(StatusSnapshot()), raw: [1], unknownFields: []), now: t0)
        XCTAssertFalse(cluster(heard).isStale)
    }

    func testAnyOtherLinkStateDimsTheValuesAndNamesTheLastReceivedTime() {
        let c = cluster(live(StatusSnapshot()), link: .stopped)

        XCTAssertTrue(c.isStale)
        XCTAssertEqual(c.headline, LinkText.of(LinkState<Int>.stopped))
        XCTAssertEqual(c.freshness, "마지막 수신 \(Formatting.clockText(t0))")
        XCTAssertNil(cluster(LiveState(), link: .idle).freshness)
    }
}
