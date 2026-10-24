import Foundation

/// 키·제어 탭 화면 상태(SFR-018). 연결과 모니터 키는 `MonitorEngine` 이 가진 것을 쓰고, 입력 변화는 `MonitorModel` 이 알려 준다.
/// 라이브러리 호출은 `BleClient`(액터)가 하고, 순서 규칙은 `KeyControlRunner`(순수)가 정한다.
/// 이 탭의 호출은 `exclusive` 로 한 번에 하나만 돈다(`05-key-control-tab.md` §Concurrency).
@MainActor
final class KeyControlModel: ObservableObject {
    struct ResultLine: Identifiable, Equatable {
        enum Tone { case info, ok, warn, fail }

        let id = UUID()
        let at: Date
        let title: String
        let text: String
        let tone: Tone
    }

    @Published private(set) var inputs = KeyControlInputs()
    /// 3단계 진행 줄(키 등록 요청과 확인).
    @Published private(set) var progress: [String] = []
    /// 새 것이 앞(위)에 온다. VIN 은 넣지 않는다.
    @Published private(set) var results: [ResultLine] = []
    @Published private(set) var status: StatusCard?
    /// 확인 대화상자를 기다리는 버튼(프렁크 열기, 원격 주행 허용).
    @Published var pendingConfirm: ControlButton?

    var steps: KeyControlSteps { KeyControlSteps(inputs) }

    private let engine: MonitorEngine
    private let client: BleClient
    /// 연결이 바뀔 때마다 올라간다. 늦게 끝난 호출이 새 연결의 상태를 덮지 않게 한다.
    private var generation = 0
    /// 새 연결에서 아직 등록 확인(자동)을 하지 않았다.
    private var needsCheck = false

    private static let maxResults = 100
    private static let autoRetrySeconds = 5

    init(engine: MonitorEngine) {
        self.engine = engine
        client = BleClient(engine: engine)
    }

    // MARK: - 입력 변화 (MonitorModel 이 부른다)

    func connectionChanged(connected: Bool, hasKey: Bool) {
        let keyed = connected && hasKey
        guard connected != inputs.connected || keyed != inputs.hasKey else { return }
        generation += 1
        inputs.connected = connected
        inputs.hasKey = keyed
        if connected {
            needsCheck = true
            checkIfNeeded()
        } else {
            needsCheck = false
            inputs.enrollment = .unknown // 연결이 끊기면 등록 상태를 다시 모른다
        }
    }

    func keyChanged(_ state: KeyState) {
        let ready = state == .ready
        let locked = state == .locked
        guard ready != inputs.keyReady || locked != inputs.keyLocked else { return }
        let wasReady = inputs.keyReady
        inputs.keyReady = ready
        inputs.keyLocked = locked
        if wasReady, !ready {
            generation += 1
            inputs.enrollment = .unknown // 키가 바뀌었다
        }
        checkIfNeeded()
    }

    func vinChanged(saved: Bool) {
        guard saved != inputs.vinSaved else { return }
        inputs.vinSaved = saved
    }

    /// 탭이 화면에 나타날 때. 다른 탭에서 키가 등록되거나 지워졌을 수 있어 등록 상태를 다시 확인한다.
    func tabAppeared() {
        needsCheck = true
        checkIfNeeded()
    }

    /// Control 탭이 이 기기의 키 등록을 바꿨을 수 있다(`SampleModel.onEnrollmentChange`).
    func enrollmentChangedElsewhere(_ change: SampleModel.EnrollmentChange) {
        switch change {
        case .enrolled:
            guard inputs.connected else { return }
            inputs.enrollment = .enrolled
        case .ownKeyRemoved:
            inputs.enrollment = .unknown
            needsCheck = true
            checkIfNeeded()
        }
    }

    // MARK: - 한 번에 하나

    private func exclusive(_ body: @MainActor () async -> ()) async {
        guard !inputs.busy else { return }
        inputs.busy = true
        await body()
        inputs.busy = false
        checkIfNeeded() // 바쁜 사이에 놓친 자동 확인
    }

    /// 키가 준비되고 키를 가진 연결이 있다.
    private var canCheck: Bool { inputs.keyReady && inputs.connected && inputs.hasKey }

    private func checkIfNeeded() {
        guard needsCheck, canCheck, !inputs.busy, inputs.enrollment != .checking else { return }
        Task { await autoCheck() }
    }

    /// 자동 확인 한 번. 결과를 모르면(`unknown`) 연결 직후 모니터의 첫 조회와 겹쳤을 수 있어 5초 뒤에 한 번만 더 한다.
    private func autoCheck(isRetry: Bool = false) async {
        let gen = generation
        var ran = false
        await exclusive { [self] in
            // 기다리는 사이에 조건이 바뀌었을 수 있다. 여기서(바쁨을 잡은 뒤) 한 번만 소비한다.
            guard canCheck, isRetry ? inputs.enrollment == .unknown : needsCheck else { return }
            needsCheck = false
            ran = true
            await runCheck(automatic: true)
        }
        guard ran, !isRetry, gen == generation, inputs.enrollment == .unknown else { return }
        try? await Task.sleep(for: .seconds(Self.autoRetrySeconds))
        guard gen == generation else { return }
        await autoCheck(isRetry: true)
    }

    // MARK: - 결과 줄

    private func add(_ title: String, _ text: String, _ tone: ResultLine.Tone) {
        results.insert(ResultLine(at: Date(), title: title, text: text, tone: tone), at: 0)
        if results.count > Self.maxResults { results.removeLast(results.count - Self.maxResults) }
    }

    private func addProgress(_ line: String) {
        progress.append("[\(Formatting.clockText(Date()))] \(line)")
    }

    func clearResults() { results.removeAll() }

    // MARK: - 단계 동작

    /// 1단계: 키를 만들거나 불러온다.
    func prepareKey() async {
        await exclusive { [self] in
            // 키 상태 입력은 엔진의 `.key` 갱신이 `MonitorModel.apply` 로 알려 준다.
            switch await engine.ensureKey() {
            case .ready:
                add("키", "준비됨", .ok)
            case .locked:
                add("키", "잠겨 있습니다. 폰을 잠금 해제한 뒤 다시 누르세요(키는 지우지 않음)", .warn)
            case .failed(let why):
                add("키", "실패: \(why)", .fail)
            }
        }
    }

    /// 2단계: 모니터의 연결 루프를 바로 시작한다. 상태는 위 배너에 나온다.
    func connect() async {
        await exclusive { [self] in
            if engine.connectNow() {
                add("연결", "시작함 (상태는 위 배너)", .info)
            } else {
                add("연결", "저장된 VIN이 없거나 형식이 틀립니다", .fail)
            }
        }
    }

    /// 3단계: 등록 상태 확인 버튼.
    func checkEnrollment() async {
        await exclusive { [self] in
            needsCheck = false
            await runCheck(automatic: false)
        }
    }

    /// `exclusive` 안에서만 부른다. `automatic` 이면 연결이 사라진 경우에 줄을 남기지 않는다.
    private func runCheck(automatic: Bool) async {
        let gen = generation
        inputs.enrollment = .checking
        let outcome = await client.checkEnrollment()
        guard gen == generation else { return } // 그 사이 연결이 바뀜: 상태는 이미 처음으로 돌아갔다
        let report = outcome.report(automatic: automatic)
        inputs.enrollment = report.enrollment
        if let line = report.line { add("등록 확인", line, report.tone) }
    }

    /// 3단계: 키 등록 요청. 이벤트 줄은 `progress` 에, 끝나면 등록 확인까지 한다(자동 확인, 3초 간격 최대 2분).
    func enroll() async {
        await exclusive { [self] in
            progress = []
            let gen = generation
            let enrolled = await client.pair { [weak self] line in await self?.addProgress(line) }
            if enrolled, gen == generation { inputs.enrollment = .enrolled }
            add("키 등록", enrolled ? "등록 확인됨" : "등록이 확인되지 않았습니다(진행 줄 참고)", enrolled ? .ok : .warn)
        }
    }

    // MARK: - 4단계 제어

    /// 버튼을 눌렀을 때. 확인이 필요한 버튼은 대화상자를 먼저 띄운다.
    func press(_ button: ControlButton) {
        if button.confirmation != nil {
            pendingConfirm = button
        } else {
            Task { await run(button) }
        }
    }

    /// 확인 대화상자에서 "보내기"를 눌렀을 때. 그 사이 조건이 바뀌었을 수 있어 4단계 규칙을 다시 따지고, 못 보내면 이유를 남긴다.
    func runConfirmed(_ button: ControlButton) async {
        if let why = KeyControlSteps.controlBlockReason(inputs) {
            add(button.title, "보내지 않음: \(why)", .warn)
            return
        }
        await run(button)
    }

    /// 세션 보장 → 명령(한 번) → 재조회(`KeyControlRunner`).
    func run(_ button: ControlButton) async {
        await exclusive { [self] in
            let gen = generation
            let client = client
            let report = await KeyControlRunner.run(
                ensureSession: { await client.ensureVcsecSession() },
                command: { await client.send(button) },
                requery: { await client.statusCard() }
            )
            if let card = report.status { status = card }
            if gen == generation, let enrollment = report.enrollment { inputs.enrollment = enrollment }
            let tone: ResultLine.Tone
            switch report.kind {
            case .success: tone = .ok
            case .uncertain, .notSent: tone = .warn
            case .failure: tone = .fail
            }
            add(button.title, report.line, tone)
        }
    }

    /// 상태 카드의 "상태 새로고침". `vehicleStatus()` 는 키 없이도 되고 차량을 깨우지 않는다.
    func refreshStatus() async {
        await exclusive { [self] in
            let result = await client.statusCard()
            if let card = result.card { status = card }
            add("상태 새로고침", result.text, result.card == nil ? .warn : .ok)
        }
    }
}
