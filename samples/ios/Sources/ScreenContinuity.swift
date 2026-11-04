import SwiftUI

/// 화면이 다시 만들어져도(접거나 펼쳐 배치가 바뀔 때, SFR-024) 이어져야 하는 화면 상태: 열린 시트와 입력 중인 글.
/// 폰 TabView 와 두 영역 배치는 서로 다른 뷰 계층이라 뷰의 `@State` 는 배치가 바뀔 때 사라진다. 그래서 ContentView 가 하나 들고 있다.
/// 동작은 그대로이고 상태를 두는 곳만 다르다. 차량이나 라이브러리 상태는 여기 두지 않는다(그건 모델이 가진다).
@MainActor
final class ScreenContinuity: ObservableObject {
    /// Live: VIN과 연결 시트, 항목별 시각 시트.
    @Published var showsSettings = false
    @Published var showsDetail = false
    /// Log: 메모 입력 글.
    @Published var memo = ""

    /// 마지막으로 받은 `vinKnown`. 두 영역 배치는 탭을 바꿀 때마다 오른쪽 영역을 다시 만들고 다시 구독해 같은 값을 또 받는다.
    private var lastVinKnown = false

    /// SFR-022: VIN이 알려지지 않음 → 알려짐으로 바뀌었고 저장된 VIN이 없을 때만 설정 시트를 스스로 연다.
    /// 같은 값을 다시 받으면(뷰가 다시 만들어짐) 열지 않는다.
    func vinKnownChanged(_ known: Bool, savedVin: String?) {
        defer { lastVinKnown = known }
        if known && !lastVinKnown && savedVin == nil { showsSettings = true }
    }
}
