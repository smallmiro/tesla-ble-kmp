import SwiftUI

/// Live 탭(SFR-011, SFR-014): 연결 배너, VIN 저장·지우기, 연결·끊기, 항목별 마지막 변경 시각, 최근 이벤트.
struct LiveTab: View {
    @ObservedObject var monitor: MonitorModel
    @State private var vinText = ""
    @State private var vinError = false
    @State private var now = Date()

    var body: some View {
        Form {
            Section { LinkBanner(monitor: monitor).listRowInsets(EdgeInsets()).listRowBackground(Color.clear) }
            Section("차량") {
                if let masked = monitor.maskedVin {
                    Text("저장된 VIN: \(masked)")
                } else {
                    TextField("VIN (17자, 한 번만 입력하면 저장됩니다)", text: $vinText)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    if vinError { Text("VIN 형식이 맞지 않습니다").font(.footnote).foregroundStyle(.red) }
                    Button("VIN 저장") { vinError = !monitor.saveVin(vinText) }
                }
                Button("연결") { _ = monitor.connect() }
                    .disabled(monitor.savedVin == nil)
                Button("연결 끊기", role: .destructive) { Task { await monitor.disconnect() } }
                Button("VIN 지우기", role: .destructive) { Task { await monitor.clearVin() } }
                    .disabled(monitor.savedVin == nil)
                if monitor.isStopped {
                    Text("자동 재연결이 멈춰 있습니다. 연결 버튼을 누르면 다시 시작합니다.").font(.footnote)
                }
            }
            Section("차량 상태 (브로드캐스트)") {
                if let last = monitor.live.lastStatusAt {
                    Text("마지막 방송 \(Formatting.clockText(last)) (\(Formatting.ago(since: last, now: now)))").font(.footnote)
                } else {
                    Text("아직 받은 상태 방송이 없습니다").font(.footnote)
                }
                ForEach(LiveItemKey.allCases, id: \.self) { key in
                    LabeledContent(key.label) {
                        if let item = monitor.live.items[key] {
                            Text("\(item.value)  ·  \(item.changed ? "바뀜" : "처음 관측") \(Formatting.clockText(item.since))")
                                .font(.footnote)
                        } else {
                            Text("—")
                        }
                    }
                }
                Text("참고: 실내 버튼으로 트렁크를 열고 닫으면 방송이 오지 않을 수 있습니다. 트렁크는 Query 탭의 vehicleStatus() 로 확인하세요.")
                    .font(.footnote)
            }
            Section("최근 이벤트 (바뀐 것, 인증 요청, 알 수 없는 방송)") {
                if monitor.live.recent.isEmpty { Text("없음").font(.footnote) }
                ForEach(monitor.live.recent) { MonoSmall("\(Formatting.clockText($0.at))  \($0.text)") }
            }
        }
        .tickingNow($now)
    }
}
