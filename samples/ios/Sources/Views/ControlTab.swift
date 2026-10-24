import SwiftUI
import UIKit

/// Control 탭: 예전 단일 화면(SFR-010). 연결과 모니터 키(`teslable-monitor`)는 모니터가 가진 것을 쓴다.
/// 모니터 키를 차량에 등록하려면 연결한 뒤 "키 등록 요청"을 누른다.
struct ControlTab: View {
    @ObservedObject var monitor: MonitorModel
    @ObservedObject var model: SampleModel
    @State private var copied = false
    @State private var copyReset: Task<(), Never>?

    var body: some View {
        Form {
            Section { LinkBanner(monitor: monitor).listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
            Section("키") {
                Button("키 만들기/불러오기 (teslable-monitor)") { Task { await model.createKey() } }
                Text(monitor.keyStatus.text).font(.footnote.monospaced())
                if let warning = monitor.keyStatus.warning {
                    Text(warning).font(.footnote).foregroundStyle(.red)
                }
                Text(model.keyInfo).font(.footnote.monospaced())
            }
            .disabled(model.busy)
            Section("BLE") {
                Text("VIN: \(monitor.maskedVin ?? "저장 안 됨 (Live 탭에서 저장)")").font(.footnote.monospaced())
                Button("스캔") { Task { await model.scan() } }
                ForEach(model.advertisements, id: \.self) { Text($0).font(.footnote) }
                Button("연결") { Task { await model.connect() } }
                Button("VehicleStatus") { Task { await model.vehicleStatus() } }
                Button("키 목록") { Task { await model.listKeys() } }
                ForEach(model.keyRows) { row in
                    HStack {
                        Text(row.label).font(.footnote.monospaced())
                        Spacer()
                        Button("삭제", role: .destructive) { model.pendingRemoval = row }
                            .disabled(!row.removable)
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
            .disabled(model.busy)
            Section("세션") {
                Button("핸드셰이크: VCSEC만") { Task { await model.startSession(vcsecOnly: true) } }
                Button("핸드셰이크: 전체") { Task { await model.startSession(vcsecOnly: false) } }
                Button("세션 상태 보기") { Task { await model.showSessions() } }
            }
            .disabled(model.busy)
            Section("제어") {
                Button("잠금") { Task { await model.lock() } }
                Button("해제") { Task { await model.unlock() } }
                Button("트렁크 열기") { Task { await model.openTrunk() } }
                Button("트렁크 닫기") { Task { await model.closeTrunk() } }
                Button("프렁크 열기") { Task { await model.openFrunk() } }
                Button("깨우기") { Task { await model.wakeUp() } }
            }
            .disabled(model.busy)
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

/// 실행 중인 앱의 번들 정보(실차 확인 기록에 어떤 빌드인지 남기기 위해 화면에 표시).
enum AppInfo {
    static let bundleID = Bundle.main.bundleIdentifier ?? "?"
    static let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    static let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
}
