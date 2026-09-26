import SwiftUI
import Teslable

struct ContentView: View {
    @State private var vin = "5YJS0000000000000"

    // SKIE는 프레임워크 이름("Teslable")과의 충돌을 피하려고 Kotlin object `Teslable`을
    // Swift에서 `Teslable_`로 노출한다(sdk/build/skie/.../apinotes/Teslable.apinotes 참고).
    // Kotlin `Vin` 생성자의 `require` 실패는 Kotlin 예외이며 Swift로 전파되지 않고
    // 프로세스가 종료된다(Kotlin/Native 규칙). M3에서 파사드가 `VehicleResult`를 돌려주므로
    // 임시 코드에서는 호출 전에 입력을 검증한다: 17자가 아니면 호출하지 않는다.
    private var localName: String {
        guard vin.count == 17 else { return "VIN은 17자여야 합니다" }
        return Teslable_.shared.localNameFor(vin: vin)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Teslable \(Teslable_.shared.VERSION)").font(.title2)
            TextField("VIN", text: $vin).textFieldStyle(.roundedBorder)
            Text("BLE local name: \(localName)")
        }
        .padding(24)
    }
}
