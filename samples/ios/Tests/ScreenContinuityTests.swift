import XCTest
@testable import TeslableSample

/// SFR-022, SFR-024: VIN이 알려졌는데 저장된 VIN이 없으면 설정 시트를 스스로 연다.
/// 두 영역 배치는 탭을 바꿀 때마다 오른쪽 영역을 다시 만들고, `@Published` 는 새 구독자에게 현재 값을 바로 보낸다.
/// 그래서 같은 값을 다시 받아도 시트를 또 열면 안 된다. 값이 알려지지 않음 → 알려짐으로 바뀔 때만 연다.
@MainActor
final class ScreenContinuityTests: XCTestCase {
    func testOpensSettingsWhenVinBecomesKnownWithoutSavedVin() {
        let continuity = ScreenContinuity()
        continuity.vinKnownChanged(true, savedVin: nil)
        XCTAssertTrue(continuity.showsSettings)
    }

    func testDoesNotReopenWhenTheSameValueArrivesAgain() {
        let continuity = ScreenContinuity()
        continuity.vinKnownChanged(true, savedVin: nil)
        continuity.showsSettings = false // 사용자가 저장하지 않고 닫음
        continuity.vinKnownChanged(true, savedVin: nil) // 오른쪽 영역이 다시 만들어져 다시 구독함
        XCTAssertFalse(continuity.showsSettings)
    }

    func testOpensAgainAfterVinWasUnknownInBetween() {
        let continuity = ScreenContinuity()
        continuity.vinKnownChanged(true, savedVin: nil)
        continuity.showsSettings = false
        continuity.vinKnownChanged(false, savedVin: nil)
        continuity.vinKnownChanged(true, savedVin: nil)
        XCTAssertTrue(continuity.showsSettings)
    }

    func testDoesNotOpenWhenVinIsSaved() {
        let continuity = ScreenContinuity()
        continuity.vinKnownChanged(true, savedVin: "SAVED")
        XCTAssertFalse(continuity.showsSettings)
    }

    func testDoesNotOpenWhileVinIsUnknown() {
        let continuity = ScreenContinuity()
        continuity.vinKnownChanged(false, savedVin: nil)
        XCTAssertFalse(continuity.showsSettings)
    }
}
