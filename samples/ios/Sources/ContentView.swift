import SwiftUI

struct ContentView: View {
    @StateObject private var model = SampleModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("차량") {
                    TextField("VIN", text: $model.vinText).textInputAutocapitalization(.characters)
                    Text(model.connection)
                }
                Section("키") {
                    Button("키 만들기/불러오기") { Task { await model.createKey() } }
                    Text(model.keyInfo).font(.footnote.monospaced())
                }
                Section("BLE") {
                    Button("스캔") { Task { await model.scan() } }
                    ForEach(model.advertisements, id: \.self) { Text($0).font(.footnote) }
                    Button("연결") { Task { await model.connect() } }
                    Button("VehicleStatus") { Task { await model.vehicleStatus() } }
                    Button("키 목록") { Task { await model.listKeys() } }
                    Button("연결 끊기", role: .destructive) { Task { await model.disconnect() } }
                }
                Section("결과") {
                    ForEach(Array(model.output.enumerated()), id: \.offset) { Text($0.element).font(.footnote.monospaced()) }
                }
                Section("진단") {
                    NavigationLink("BLE 진단 (스파이크)") { DiagnosticsView() }
                }
            }
            .navigationTitle("Teslable Sample")
        }
    }
}
