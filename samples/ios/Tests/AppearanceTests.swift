import XCTest

/// SFR-024: 앱은 폰의 라이트/다크 설정과 상관없이 항상 다크 모드다.
/// 시스템 설정이 바뀌어도 모든 창(탭, 시트, 알림)이 어둡게 그려지도록 `Info.plist` 의 `UIUserInterfaceStyle` 을 `Dark` 로 고정한다.
/// 화면이 실제로 어둡게 보이는지는 실기기 확인표(README)에서 본다.
final class AppearanceTests: XCTestCase {
    func testAppForcesDarkInterfaceStyle() {
        let style = Bundle.main.object(forInfoDictionaryKey: "UIUserInterfaceStyle") as? String
        XCTAssertEqual(style, "Dark")
    }
}
