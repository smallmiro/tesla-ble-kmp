import SwiftUI
import UIKit

struct ContentView: View {
    @StateObject private var model = SampleModel()
    @State private var copied = false
    @State private var copyReset: Task<(), Never>?

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
                Section("관찰 (요청 없이 수신만 기록)") {
                    Toggle("관찰 모드", isOn: $model.observing)
                    Text("받은 메시지 \(model.observations.count)줄")
                    Button("관찰 기록 지우기", role: .destructive) { model.clearObservations() }
                    ForEach(Array(model.observations.suffix(200).enumerated()), id: \.offset) {
                        Text($0.element).font(.caption2.monospaced()).textSelection(.enabled)
                    }
                }
                Section("BLE") {
                    Button("스캔") { Task { await model.scan() } }
                    ForEach(model.advertisements, id: \.self) { Text($0).font(.footnote) }
                    Button("연결") { Task { await model.connect() } }
                    Button("VehicleStatus") { Task { await model.vehicleStatus() } }
                        .disabled(model.observing)
                    Button("키 목록") { Task { await model.listKeys() } }
                        .disabled(model.observing)
                    ForEach(model.keyRows) { row in
                        HStack {
                            Text(row.label).font(.footnote.monospaced())
                            Spacer()
                            Button("삭제", role: .destructive) { model.pendingRemoval = row }
                                .disabled(!row.removable || model.observing)
                                .buttonStyle(.borderless)
                        }
                    }
                    Button("연결 끊기", role: .destructive) { Task { await model.disconnect() } }
                }
                .disabled(model.busy)
                Section("페어링") {
                    Button("키 등록 요청") { Task { await model.pair() } }
                    Button("등록 확인") { Task { await model.confirmEnrollment() } }
                }
                .disabled(model.busy || model.observing)
                Section("세션") {
                    Button("핸드셰이크: VCSEC만") { Task { await model.startSession(vcsecOnly: true) } }
                    Button("핸드셰이크: 전체") { Task { await model.startSession(vcsecOnly: false) } }
                    Button("세션 상태 보기") { Task { await model.showSessions() } }
                }
                .disabled(model.busy || model.observing)
                Section("제어") {
                    Button("잠금") { Task { await model.lock() } }
                    Button("해제") { Task { await model.unlock() } }
                    Button("트렁크 열기") { Task { await model.openTrunk() } }
                    Button("트렁크 닫기") { Task { await model.closeTrunk() } }
                    Button("프렁크 열기") { Task { await model.openFrunk() } }
                    Button("깨우기") { Task { await model.wakeUp() } }
                }
                .disabled(model.busy || model.observing)
                Section("결과") {
                    Button(copied ? "복사됨 ✓" : "결과 전체 복사") {
                        UIPasteboard.general.string = model.report()
                        copied = true
                        copyReset?.cancel()
                        copyReset = Task {
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            if !Task.isCancelled { copied = false }
                        }
                    }
                    Button("결과 지우기", role: .destructive) { model.clearOutput() }
                    ForEach(Array(model.output.enumerated()), id: \.offset) {
                        Text($0.element).font(.footnote.monospaced()).textSelection(.enabled)
                    }
                }
                Section("앱 정보") {
                    LabeledContent("번들 ID", value: AppInfo.bundleID)
                    LabeledContent("버전", value: AppInfo.version)
                    LabeledContent("빌드", value: AppInfo.build)
                }
                .font(.footnote.monospaced())
            }
            .navigationTitle("Teslable Sample")
            .confirmationDialog(
                "키를 삭제할까요?",
                isPresented: Binding(get: { model.pendingRemoval != nil }, set: { if !$0 { model.pendingRemoval = nil } }),
                titleVisibility: .visible,
                presenting: model.pendingRemoval
            ) { row in
                Button(row.isOwn ? "이 기기의 키를 지웁니다" : "삭제", role: .destructive) {
                    Task { await model.removeKey(row) }
                }
                Button("취소", role: .cancel) {}
            } message: { row in
                Text(row.isOwn ? "이 기기의 키를 지웁니다. 지우면 이 기기로는 더 이상 차량을 제어할 수 없습니다.\n\(row.label)" : row.label)
            }
        }
    }
}

/// 실행 중인 앱의 번들 정보(실차 확인 기록에 어떤 빌드인지 남기기 위해 화면에 표시).
enum AppInfo {
    static let bundleID = Bundle.main.bundleIdentifier ?? "?"
    static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    static let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
}
