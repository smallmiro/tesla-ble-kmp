import Foundation

/// 키·제어 탭(`KeyControlModel`)이 라이브러리와 모니터 엔진에 하는 일의 전부. 화면 상태 논리를 차량 없이 시험하도록 가른 이음매다
/// (시험은 가짜 구현을 끼운다). 앱은 `LiveKeyControlBackend` 를 쓴다.
protocol KeyControlBackend: Sendable {
    func ensureKey() async -> KeyEnsureResult
    func disconnect() async
    /// 연결 루프를 바로 시작한다. VIN 이 없거나 형식이 틀리거나 키를 바꾸는 중이면 `false`.
    func connectNow() -> Bool
    func replaceKey() async -> KeyReplaceOutcome
    func checkEnrollment() async -> EnrollmentCheckOutcome
    func pair(onEvent: @escaping @Sendable (EnrollmentUpdate) async -> ()) async -> Bool
    func ensureVcsecSession() async -> SessionOutcome
    func send(_ button: ControlButton) async -> CommandOutcome
    func statusCard() async -> RequeryOutcome
}

/// `ensureKey()` 의 결과(Kotlin 키 객체 없이).
enum KeyEnsureResult: Sendable, Equatable {
    case ready
    case locked
    case failed(String)
}

/// 앱이 쓰는 구현: 모니터 엔진과 `BleClient`.
struct LiveKeyControlBackend: KeyControlBackend {
    let engine: MonitorEngine
    let client: BleClient

    init(engine: MonitorEngine) {
        self.engine = engine
        client = BleClient(engine: engine)
    }

    func ensureKey() async -> KeyEnsureResult {
        switch await engine.ensureKey() {
        case .ready: return .ready
        case .locked: return .locked
        case .failed(let why): return .failed(why)
        }
    }

    func disconnect() async { await engine.disconnect() }
    func connectNow() -> Bool { engine.connectNow() }
    func replaceKey() async -> KeyReplaceOutcome { await engine.replaceKey() }
    /// 연결 직후에는 모니터의 첫 조회(`listKeys()` 포함)가 끝난 뒤에 확인한다. 두 `listKeys()` 가 겹치면 느린 연결에서 5초 예산을 같이 써 버린다(#128, 빌드 18 분석 F11).
    func checkEnrollment() async -> EnrollmentCheckOutcome {
        await engine.waitForInitialQueries()
        return await client.checkEnrollment()
    }
    func pair(onEvent: @escaping @Sendable (EnrollmentUpdate) async -> ()) async -> Bool { await client.pair(onEvent: onEvent) }
    func ensureVcsecSession() async -> SessionOutcome { await client.ensureVcsecSession() }
    func send(_ button: ControlButton) async -> CommandOutcome { await client.send(button) }
    func statusCard() async -> RequeryOutcome { await client.statusCard() }
}
