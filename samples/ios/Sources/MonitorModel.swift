import Combine
import Foundation
import SwiftUI
import Teslable
import UIKit

/// 앱 수명 동안 하나만 있는 모니터 객체(SFR-010). `MonitorEngine` 이 라이브러리·연결 루프·로그를 가지고, 이 객체는 화면이 읽는 값만 모아 둔다.
/// Control 탭의 상태(`SampleModel`)와 키·제어 탭의 상태(`KeyControlModel`)도 여기서 만든다(탭이 바뀌어도 사라지지 않게).
@MainActor
final class MonitorModel: ObservableObject {
    let engine: MonitorEngine
    let control: SampleModel
    /// 키·제어 탭(SFR-018)의 상태. 연결·키·VIN 의 변화는 `apply` 가 알려 준다.
    let keyControl: KeyControlModel
    /// Query 탭의 상태 진단(SFR-023). 연결·등록·수면 상태의 변화는 `apply` 와 `keyControl` 이 알려 준다.
    let diagnostics: StateDiagnosticsModel

    @Published var link: LinkState<VehicleBox> = .idle
    @Published var live = LiveState()
    @Published var queries: [String: QueryResult] = [:]
    @Published var sessions = "세션: 연결 안 됨"
    @Published var keyStatus = KeyStatus()
    @Published var savedVin: String?
    /// 엔진이 저장된 VIN 을 알려 주었는지. 그 전의 `savedVin == nil` 은 "VIN 없음"이 아니라 "아직 모름"이다.
    @Published var vinKnown = false
    @Published var pollSeconds = MonitorEngine.defaultPollSeconds
    @Published var logStats = LogStats()

    private var started = false
    private var unlockObserver: NSObjectProtocol?
    private var lockObserver: NSObjectProtocol?
    private var enrollmentSubscription: AnyCancellable?

    /// 단위 테스트가 앱을 띄울 때는 자동 연결과 키 읽기를 하지 않는다.
    private static var runningTests: Bool { ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil }

    init() {
        let engine = MonitorEngine()
        self.engine = engine
        let control = SampleModel(engine: engine)
        let keyControl = KeyControlModel(engine: engine)
        self.control = control
        self.keyControl = keyControl
        // 다른 탭의 동작(Control 탭의 호출, 키·제어 탭의 호출)이 도는 동안은 진단 조회를 시작하지 않는다.
        let diagnostics = StateDiagnosticsModel(engine: engine, otherOperationRunning: { [weak control, weak keyControl] in
            (control?.busy ?? false) || (keyControl?.inputs.busy ?? false)
        })
        self.diagnostics = diagnostics
        control.onEnrollmentChange = { [weak keyControl] change in keyControl?.enrollmentChangedElsewhere(change) }
        keyControl.onKeyReplaced = { [weak control] in control?.keyReplaced() }
        // 등록 상태는 키·제어 탭이 확인한다. 진단 버튼은 등록이 확인된 키가 있을 때만 켠다.
        enrollmentSubscription = keyControl.$inputs.sink { diagnostics.enrollmentChanged($0.enrollment == .enrolled) }
        let updates = engine.updates
        Task { [weak self] in
            for await update in updates { self?.apply(update) }
        }
    }

    /// 화면이 처음 나타날 때 한 번. 저장된 VIN 이 있으면 스캔하고 연결한다(SFR-011).
    func start() {
        guard !started else { return }
        started = true
        engine.publishAll()
        if Self.runningTests { return }
        // 잠금 해제로 키 저장소가 열리면 기다리던 재연결을 바로 시도한다(`13-lib-pairing.md` §Key accessibility).
        let engine = engine
        unlockObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main
        ) { _ in engine.protectedDataBecameAvailable() }
        // 잠기는 때도 남긴다: 잠금 중에 재연결이 멈추는지 읽으려면 잠금 시각이 필요하다.
        lockObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil, queue: .main
        ) { _ in engine.noteProtectedData(available: false) }
        engine.autoConnectIfSaved()
    }

    private func apply(_ update: MonitorUpdate) {
        switch update {
        case .link(let state):
            link = state
            var hasKey = false
            if case .connected(let box) = state { hasKey = box.vehicle.hasKey }
            keyControl.connectionChanged(connected: isConnected, hasKey: hasKey)
            diagnostics.connectionChanged(connected: isConnected, hasKey: hasKey)
        case .live(let state): live = state
        case .queries(let results): queries = results
        case .sessions(let text): sessions = text
        case .key(let status):
            keyStatus = status
            keyControl.keyChanged(status.state)
        case .vin(let vin):
            savedVin = vin
            if !vinKnown { vinKnown = true }
            keyControl.vinChanged(saved: vin != nil)
        case .pollSeconds(let seconds): pollSeconds = seconds
        case .log(let stats): logStats = stats
        case .sleep(let name): diagnostics.sleepQueried(name)
        }
    }

    /// 앱 화면 상태가 바뀌었다(`ContentView` 가 부른다). 시험 중에는 기록하지 않는다.
    func scenePhaseChanged(_ phase: String) {
        if Self.runningTests { return }
        engine.noteScenePhase(phase)
    }

    // MARK: - 사용자 동작

    /// VIN 을 저장한다. 형식이 틀리면 `false`.
    func saveVin(_ text: String) -> Bool { engine.saveVin(text) }

    /// 연결 버튼. 기다리지 않고 바로 시도한다. VIN 이 없으면 `false`.
    func connect() -> Bool { engine.connectNow() }

    func disconnect() async { await engine.disconnect() }

    func clearVin() async { await engine.clearVin() }

    func setPollSeconds(_ seconds: Int) { engine.setPollSeconds(seconds) }

    func queryNow() { engine.requestQuery() }

    func mark(_ memo: String) { engine.mark(memo) }

    func newLogFile() { engine.newLogFile() }

    /// 화면에는 가린 VIN 만 보인다(스크린샷으로 새지 않게).
    var maskedVin: String? { savedVin.map(VinMask.maskText) }

    var isConnected: Bool { if case .connected = link { return true } else { return false } }

    var isStopped: Bool { link == .stopped }
}
