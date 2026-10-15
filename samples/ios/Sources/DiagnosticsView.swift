import SwiftUI
import Teslable

/// M3 Task 1 스파이크 화면(ADR-0003). Task 14에서 지운다.
struct DiagnosticsView: View {
    @State private var vin = ""
    @State private var forceIndication = false
    @State private var lines: [String] = []
    @State private var running = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("VIN", text: $vin).textFieldStyle(.roundedBorder).textInputAutocapitalization(.characters)
            Toggle("CCCD indication 강제 (Android 전용, iOS는 실패 로그가 정상)", isOn: $forceIndication)
            Button(running ? "실행 중…" : "Probe") { Task { await run() } }.disabled(running)
            ScrollView {
                Text(lines.joined(separator: "\n")).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
            }
        }
        .padding(16)
    }

    @MainActor
    private func run() async {
        running = true
        lines = []
        do {
            try await TeslableDiagnostics.shared.probe(vin: vin.uppercased(), forceIndication: forceIndication) { @Sendable line in
                Task { @MainActor in lines.append(line) }
            }
        } catch {
            lines.append("swift error: \(error)")
        }
        running = false
    }
}
