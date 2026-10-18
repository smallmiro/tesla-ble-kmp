import XCTest
@testable import TeslableSample

final class ObservationMarkTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_000)

    func testLineHasClockElapsedAndMarkTag() throws {
        let line = try XCTUnwrap(ObservationMark.line(memo: "문 열기 직전", now: start.addingTimeInterval(12.34), since: start))
        XCTAssertTrue(line.hasSuffix("+12.3s] [mark] 문 열기 직전"), line)
        XCTAssertTrue(line.hasPrefix("["), line)
    }

    func testEmptyOrBlankMemoIsIgnored() {
        XCTAssertNil(ObservationMark.line(memo: "", now: start, since: start))
        XCTAssertNil(ObservationMark.line(memo: " \n\t ", now: start, since: start))
    }

    func testControlCharactersBecomeSpaces() throws {
        let line = try XCTUnwrap(ObservationMark.line(memo: "a\nb\tc\u{07}d", now: start, since: start))
        XCTAssertTrue(line.hasSuffix("[mark] a b c d"), line)
        XCTAssertFalse(line.contains("\n"))
    }

    func testMemoIsLimitedTo80Characters() throws {
        let line = try XCTUnwrap(ObservationMark.line(memo: String(repeating: "i", count: 200), now: start, since: start))
        XCTAssertTrue(line.hasSuffix("[mark] " + String(repeating: "i", count: 80)), line)
    }

    func testVinPatternIsMasked() throws {
        let line = try XCTUnwrap(ObservationMark.line(memo: "vin 5YJ30123456789ABC 확인", now: start, since: start))
        XCTAssertFalse(line.contains("5YJ30123456789ABC"), line)
        XCTAssertTrue(line.contains("5YJ**********9ABC"), line)
    }
}
