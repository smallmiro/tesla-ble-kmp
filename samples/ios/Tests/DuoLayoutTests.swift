import XCTest
@testable import TeslableSample

/// SFR-024: 넓은 화면(접는 폰의 안쪽 화면 등)에서 두 영역으로 나누는 규칙과, 폰 탭 ↔ 왼쪽 영역 항목의 대응.
/// 크기 클래스와 쓸 수 있는 너비만 본다.
final class DuoLayoutTests: XCTestCase {
    // MARK: 모드

    func testCompactWidthIsAlwaysPhone() {
        XCTAssertEqual(DuoLayout.mode(isRegularWidth: false, width: 390), .phone)
        XCTAssertEqual(DuoLayout.mode(isRegularWidth: false, width: 1_200), .phone)
    }

    func testRegularWidthThatFitsBothPanesIsTwoPane() {
        XCTAssertEqual(DuoLayout.mode(isRegularWidth: true, width: DuoLayout.twoPaneMinWidth), .twoPane)
        XCTAssertEqual(DuoLayout.mode(isRegularWidth: true, width: 1_024), .twoPane)
    }

    func testRegularWidthThatIsTooNarrowStaysPhone() {
        XCTAssertEqual(DuoLayout.mode(isRegularWidth: true, width: DuoLayout.twoPaneMinWidth - 1), .phone)
    }

    func testTwoPaneMinimumLeavesRoomForBothPanes() {
        XCTAssertGreaterThanOrEqual(DuoLayout.twoPaneMinWidth, DuoLayout.leftPaneMinWidth + DuoLayout.rightPaneMinWidth)
    }

    // MARK: 왼쪽 너비

    func testLeftPaneIsFortyPercentWithinLimits() {
        XCTAssertEqual(DuoLayout.leftPaneWidth(total: 1_000), 400)
        XCTAssertEqual(DuoLayout.leftPaneWidth(total: DuoLayout.twoPaneMinWidth), DuoLayout.leftPaneMinWidth)
        XCTAssertEqual(DuoLayout.leftPaneWidth(total: 3_000), DuoLayout.leftPaneMaxWidth)
    }

    func testRightPaneKeepsItsMinimumAtTheSmallestTwoPaneWidth() {
        let total = DuoLayout.twoPaneMinWidth
        XCTAssertGreaterThanOrEqual(total - DuoLayout.leftPaneWidth(total: total), DuoLayout.rightPaneMinWidth)
    }

    // MARK: 선택 유지

    func testStartsOnLiveWithMenuOpen() {
        let nav = AppNavigation()
        XCTAssertEqual(nav.tab, .live)
        XCTAssertNil(nav.pane)
    }

    func testPhoneTabChoiceIsTheLeftPaneSectionAfterUnfolding() {
        var nav = AppNavigation()
        nav.tab = .keyControl
        XCTAssertEqual(nav.pane, .keyControl)
        nav.tab = .log
        XCTAssertEqual(nav.pane, .log)
    }

    func testLeftPaneSectionIsThePhoneTabAfterFolding() {
        var nav = AppNavigation()
        nav.pane = .query
        XCTAssertEqual(nav.tab, .query)
        nav.pane = .control
        XCTAssertEqual(nav.tab, .control)
    }

    func testLiveDetailFoldsToTheLiveTabButIsRemembered() {
        var nav = AppNavigation()
        nav.pane = .liveDetail
        XCTAssertEqual(nav.tab, .live)
        XCTAssertEqual(nav.pane, .liveDetail) // 폰 TabView 가 같은 값을 다시 써도 지워지지 않는다
        nav.tab = .live
        XCTAssertEqual(nav.pane, .liveDetail)
    }

    func testChoosingLiveFromAnotherTabShowsTheMenu() {
        var nav = AppNavigation()
        nav.pane = .log
        nav.tab = .live
        XCTAssertNil(nav.pane)
    }

    func testEveryTabRoundTrips() {
        for tab in AppTab.allCases {
            var nav = AppNavigation()
            nav.tab = tab
            XCTAssertEqual(nav.tab, tab)
        }
    }
}
