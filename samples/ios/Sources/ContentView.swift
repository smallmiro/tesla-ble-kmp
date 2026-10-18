import SwiftUI
import UIKit

struct ContentView: View {
    @StateObject private var model = SampleModel()
    @State private var copied = false

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
                .disabled(model.busy)
                Section("BLE") {
                    Button("스캔") { Task { await model.scan() } }
                    ForEach(model.advertisements, id: \.self) { Text($0).font(.footnote) }
                    Button("연결") { Task { await model.connect() } }
                    Button("VehicleStatus") { Task { await model.vehicleStatus() } }
                    Button("키 목록") { Task { await model.listKeys() } }
                    Button("연결 끊기", role: .destructive) { Task { await model.disconnect() } }
                }
                .disabled(model.busy)
                Section("결과") {
                    Button(copied ? "복사됨 ✓" : "결과 전체 복사") {
                        UIPasteboard.general.string = model.report()
                        copied = true
                        Task {
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            copied = false
                        }
                    }
                    Button("결과 지우기", role: .destructive) { model.clearOutput() }
                    ForEach(Array(model.output.enumerated()), id: \.offset) {
                        Text($0.element).font(.footnote.monospaced()).textSelection(.enabled)
                    }
                }
                Section("진단") {
                    NavigationLink("BLE 진단 (스파이크)") { DiagnosticsView() }
                }
                Section("앱 정보") {
                    LabeledContent("번들 ID", value: AppInfo.bundleID)
                    LabeledContent("버전", value: AppInfo.version)
                    LabeledContent("빌드", value: AppInfo.build)
                }
                .font(.footnote.monospaced())
            }
            .navigationTitle("Teslable Sample")
        }
    }
}

/// 실행 중인 앱의 번들 정보(실차 확인 기록에 어떤 빌드인지 남기기 위해 화면에 표시).
enum AppInfo {
    static let bundleID = Bundle.main.bundleIdentifier ?? "?"
    static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    static let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
}
