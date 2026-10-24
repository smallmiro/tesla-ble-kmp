import XCTest
@testable import TeslableSample

final class LiveStateTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_792_803_600)

    private func status(lock: String = "LOCKED", gear: String = "PARK", driverDoor: String = "CLOSED") -> MonitorEvent {
        MonitorEvent(kind: .status(StatusSnapshot(lock: lock, gear: gear, frontDriverDoor: driverDoor)), raw: [1], unknownFields: [])
    }

    func testFirstStatusFillsEveryItemWithoutClaimingAChange() {
        let s = LiveReducer.reduce(LiveState(), status(), now: t0)

        XCTAssertEqual(s.items.count, LiveItemKey.allCases.count)
        XCTAssertEqual(s.items[.lock]?.value, "LOCKED")
        XCTAssertEqual(s.items[.gear]?.value, "PARK")
        XCTAssertTrue(s.items.values.allSatisfy { $0.since == t0 && !$0.changed })
        XCTAssertEqual(s.lastStatusAt, t0)
        XCTAssertTrue(s.recent.isEmpty)
    }

    func testEqualRepeatIsNotAChange() {
        let first = LiveReducer.reduce(LiveState(), status(), now: t0)

        let second = LiveReducer.reduce(first, status(), now: t0.addingTimeInterval(5))

        XCTAssertEqual(first.items, second.items)
        XCTAssertEqual(first.recent, second.recent)
        XCTAssertEqual(second.lastStatusAt, t0.addingTimeInterval(5))
    }

    func testOnlyTheChangedItemGetsANewTime() {
        let first = LiveReducer.reduce(LiveState(), status(), now: t0)

        let second = LiveReducer.reduce(first, status(gear: "DRIVE"), now: t0.addingTimeInterval(9))

        let gear = second.items[.gear]
        XCTAssertEqual(gear?.value, "DRIVE")
        XCTAssertEqual(gear?.since, t0.addingTimeInterval(9))
        XCTAssertEqual(gear?.changed, true)
        XCTAssertEqual(second.items[.lock]?.since, t0)
        XCTAssertEqual(second.recent.count, 1)
        XCTAssertTrue(second.recent[0].text.contains("PARK") && second.recent[0].text.contains("DRIVE"))
    }

    func testChangeBackAndForthIsEachAChange() {
        var s = LiveReducer.reduce(LiveState(), status(), now: t0)
        s = LiveReducer.reduce(s, status(driverDoor: "OPEN"), now: t0.addingTimeInterval(1))
        s = LiveReducer.reduce(s, status(driverDoor: "CLOSED"), now: t0.addingTimeInterval(2))

        XCTAssertEqual(s.items[.driverDoor]?.since, t0.addingTimeInterval(2))
        XCTAssertEqual(s.recent.count, 2)
    }

    func testAuthenticationRequestAndUnrecognizedAreListed() {
        var s = LiveReducer.reduce(LiveState(), MonitorEvent(kind: .authenticationRequest(level: "DRIVE", reasons: []), raw: [2], unknownFields: []), now: t0)
        s = LiveReducer.reduce(s, MonitorEvent(kind: .unrecognized, raw: [3], unknownFields: ["53"]), now: t0.addingTimeInterval(1))

        XCTAssertNil(s.lastStatusAt)
        XCTAssertTrue(s.items.isEmpty)
        XCTAssertEqual(s.recent.count, 2)
        XCTAssertTrue(s.recent.last?.text.contains("DRIVE") ?? false)
        XCTAssertTrue(s.recent.first?.text.contains("53") ?? false)
    }

    func testRecentListIsCapped() {
        var s = LiveState()
        for i in 0..<(LiveReducer.maxRecent + 20) {
            s = LiveReducer.reduce(s, MonitorEvent(kind: .unrecognized, raw: [1], unknownFields: ["\(i)"]), now: t0.addingTimeInterval(Double(i)))
        }
        XCTAssertEqual(s.recent.count, LiveReducer.maxRecent)
        XCTAssertFalse(s.recent.contains { $0.text.hasSuffix("[0]") })
    }
}
