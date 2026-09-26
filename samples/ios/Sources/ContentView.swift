import SwiftUI
import Teslable

struct ContentView: View {
    @State private var vin = "5YJS0000000000000"

    // SKIE는 프레임워크 이름("Teslable")과의 충돌을 피하려고 Kotlin object `Teslable`을
    // Swift에서 `Teslable_`로 노출한다(sdk/build/skie/.../apinotes/Teslable.apinotes 참고).
    // Kotlin `Vin` 생성자의 `require` 실패는 Kotlin 예외이며 Swift로 전파되지 않고
    // 프로세스가 종료된다(Kotlin/Native 규칙). M3에서 파사드가 `VehicleResult`를 돌려주므로
    // 임시 코드에서는 호출 전에 `Vin`과 같은 규칙으로 입력을 검증하고, 통과하지 못하면 호출하지 않는다.
    private var localName: String {
        guard Self.isValidVin(vin) else { return "VIN은 I·O·Q를 제외한 영문자와 숫자 17자여야 합니다" }
        return Teslable_.shared.localNameFor(vin: vin)
    }

    private static let vinCharacters = Set("ABCDEFGHJKLMNPRSTUVWXYZ0123456789")

    // Kotlin `Vin`과 같은 규칙: 대문자로 바꾼 뒤 정확히 17자, 각 문자가 [A-HJ-NPR-Z0-9].
    private static func isValidVin(_ input: String) -> Bool {
        let upper = input.uppercased()
        return upper.count == 17 && upper.allSatisfy { vinCharacters.contains($0) }
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
