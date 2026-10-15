import SwiftUI
import Teslable

struct ContentView: View {
    @State private var vin = "5YJS0000000000000"

    // Kotlin `Vin` 생성자의 `require` 실패는 Swift로 전파되지 않고 프로세스를 종료시키므로 `parseOrNull`을 쓴다.
    private var parsed: String {
        Vin.companion.parseOrNull(value: vin)?.description() ?? "VIN은 I·O·Q를 제외한 영문자와 숫자 17자여야 합니다"
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("Teslable").font(.title2)
                TextField("VIN", text: $vin).textFieldStyle(.roundedBorder)
                Text("VIN: \(parsed)")
                NavigationLink("BLE 진단 (스파이크)") { DiagnosticsView() }
            }
            .padding(24)
        }
    }
}
