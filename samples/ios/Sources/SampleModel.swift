import Foundation
import Teslable

/// 라이브러리 호출만 담당한다. Kotlin 객체는 이 액터 안에만 두고 화면에는 `String`만 돌려준다.
/// 액터는 suspend 중 재진입할 수 있으므로 화면(`SampleModel.busy`)이 동작을 한 번에 하나만 허용한다.
/// SKIE: suspend → async, Flow → AsyncSequence, sealed → onEnum.
actor BleClient {
    // Kotlin 객체는 Sendable이 아니라 SKIE async 호출로 보낼 수 없다. 액터가 접근을 직렬화하고 화면이 겹침을 막으므로
    // `nonisolated(unsafe)`로 표시한다(문자열만 액터 밖으로 나간다).
    nonisolated(unsafe) private let ble: TeslaBle
    /// 관찰 로그(태그 `observe`)를 화면으로 내보내는 로거(M4 Task 1).
    nonisolated let observer = ObservingLogger()
    nonisolated(unsafe) private var key: VehicleKey?
    nonisolated(unsafe) private var vehicle: Vehicle?
    /// `Vehicle.connection` 관찰. 연결 해제·재연결 때 취소한다.
    private var linkWatch: Task<(), Never>?

    init() {
        ble = TeslaBle.companion.create(
            platform: PlatformContext(),
            config: TeslaBleConfig.companion.default(logger: observer)
        )
    }

    nonisolated var observations: AsyncStream<String> { observer.lines }

    func createKey() async -> String {
        do {
            let result = try await ble.keys.getOrCreate(alias: VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS, policy: .preferHardware)
            switch onEnum(of: result) {
            case .success(let s):
                guard let k = s.value as? VehicleKey else { return "실패: 예상하지 못한 결과 형식" }
                key = k
                return "\(k.protection.name) \(String(describing: k.publicKey))"
            case .uncertain(let u): return "불확실: \(u.error.message)"
            case .failure(let f): return "실패: \(f.error.message)"
            }
        } catch { return "오류: \(error)" }
    }

    func scan(vinText: String) async -> String {
        guard let vin = Vin.companion.parseOrNull(value: vinText) else { return "VIN 형식 오류" }
        let scanning = Task { await self.firstAdvertisement(vin) }
        let timer = Task {
            try? await Task.sleep(for: .seconds(30))
            scanning.cancel()
        }
        let line = await scanning.value
        timer.cancel()
        return line
    }

    private func firstAdvertisement(_ vin: Vin) async -> String {
        for await event in ble.scan(vin: vin) {
            switch onEnum(of: event) {
            case .found(let f):
                let ad = f.advertisement
                let connectable = ad.isConnectable.map { String($0.boolValue) } ?? "unknown"
                return "\(ad.localName.value) rssi=\(ad.rssi) connectable=\(connectable)" // 첫 광고에서 멈춘다
            case .failed(let e):
                return "스캔 실패: \(e.error.message)"
            }
        }
        return Task.isCancelled ? "스캔 시간 초과 (30초)" : "스캔 종료(광고 없음)"
    }

    /// `onLinkLoss`는 차량이 링크를 끊었을 때(원인이 있는 Disconnected) 표시할 문자열만 받는다.
    func connect(vinText: String, onLinkLoss: @escaping @Sendable (String) async -> ()) async -> String {
        guard let vin = Vin.companion.parseOrNull(value: vinText) else { return "VIN 형식 오류" }
        await disconnect() // 이미 연결돼 있으면 먼저 끊는다
        do {
            let result = try await ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
            switch onEnum(of: result) {
            case .success(let s):
                guard let v = s.value as? Vehicle else { return "실패: 예상하지 못한 결과 형식" }
                vehicle = v
                watchLink(v, onLinkLoss)
                return "연결됨 (키: \(key == nil ? "없음" : "있음"))"
            case .uncertain(let u): return "불확실: \(u.error.message)"
            case .failure(let f): return "실패: \(f.error.message)"
            }
        } catch { return "오류: \(error)" }
    }

    func vehicleStatus() async -> String { await run("VehicleStatus") { try await $0.vehicleStatus(timeout: nil) } }

    /// 목록 한 줄(화면용). 삭제는 슬롯으로 고른다 — 공개키는 액터 안(`keyEntries`)에만 둔다.
    struct KeyRow: Identifiable, Sendable {
        let slot: Int
        let label: String
        let isOwn: Bool
        let removable: Bool
        var id: Int { slot }
    }

    nonisolated(unsafe) private var keyEntries: [Int: WhitelistEntry] = [:]

    /// 키 목록 조회. 결과 한 줄과 항목별 행을 돌려준다(행마다 "삭제" 버튼).
    func listKeys() async -> (line: String, rows: [KeyRow]) {
        guard let vehicle else { return ("키 목록: 먼저 연결하세요", []) }
        do {
            let result = try await vehicle.listKeys(timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let entries = s.value as? [WhitelistEntry] else { return ("키 목록: 예상하지 못한 결과 형식", []) }
                keyEntries = Dictionary(uniqueKeysWithValues: entries.map { (Int($0.slot), $0) })
                let rows = entries.map { e in
                    let own = key.map { k in e.publicKey.map { $0.isEqual(k.publicKey) } ?? false } ?? false
                    return KeyRow(
                        slot: Int(e.slot),
                        label: "슬롯 \(e.slot) \(e.formFactor.name) \(e.role.name)\(own ? " (이 기기)" : "")",
                        isOwn: own,
                        removable: e.publicKey != nil
                    )
                }
                return ("키 목록: \(rows.count)개", rows)
            case .uncertain(let u): return ("키 목록 불확실: \(u.error.message)", [])
            case .failure(let f): return ("키 목록 실패: \(f.error.message)", [])
            }
        } catch { return ("키 목록 오류: \(error)", []) }
    }

    func startSession(vcsecOnly: Bool) async -> String {
        let domains: Set<VehicleDomain> = vcsecOnly ? [VehicleDomain.vcsec] : VehicleDomain.companion.ALL
        let label = vcsecOnly ? "세션(VCSEC만)" : "세션(전체)"
        let started = Date()
        let line = await run(label) { try await $0.startSession(domains: domains, timeout: nil) }
        return "\(line) [\(String(format: "%.1f", Date().timeIntervalSince(started)))초] \(sessionsText())"
    }

    /// 도메인별 세션 상태 한 줄.
    func sessionsText() -> String {
        guard let vehicle else { return "세션: 연결 안 됨" }
        guard let map = vehicle.sessions.value as? [VehicleDomain: SessionStatus] else { return "세션: 형식 오류" }
        let parts = map.keys.sorted { $0.name < $1.name }.map { d -> String in
            let text: String
            switch onEnum(of: map[d]!) {
            case .none: text = "None"
            case .handshaking: text = "Handshaking"
            case .ready(let r): text = r.fromCache ? "Ready(fromCache)" : "Ready"
            }
            return "\(d.name)=\(text)"
        }
        return "세션: " + (parts.isEmpty ? "없음" : parts.joined(separator: ", "))
    }

    /// 키 등록 요청. 이벤트마다 한국어 한 줄을 `onEvent`로 보내고, 끝이 `Accepted`/`TimedOut`이면 등록 확인을 잇는다.
    func pair(onEvent: @escaping @Sendable (String) async -> ()) async {
        guard let vehicle else { await onEvent("키 등록: 먼저 연결하세요"); return }
        guard let key else { await onEvent("키 등록: 먼저 키를 만드세요"); return }
        var confirm = false
        for await event in vehicle.addKeyRequest(
            publicKey: key.publicKey, role: .owner, formFactor: KeyFormFactors_iosKt.platformKeyFormFactor(), timeout: nil
        ) {
            switch onEnum(of: event) {
            case .sent: await onEvent("요청 보냄 — 중앙 콘솔에 키카드를 대고 화면에서 승인하세요")
            case .waitingForTap: await onEvent("카드 태그 대기 중")
            case .accepted: await onEvent("차량이 승인함 — Infotainment 동기화 확인 중"); confirm = true
            case .rejected(let r): await onEvent("거부: \(r.error.message)")
            case .unknown(let u): await onEvent("모르는 응답 \(hex(u.raw))")
            case .failed(let f): await onEvent("실패: \(f.error.message)")
            case .timedOut: await onEvent("시간 초과 — 등록 여부를 확인합니다"); confirm = true
            }
        }
        if confirm { await onEvent(await confirmEnrollment()) }
    }

    private func hex(_ bytes: KotlinByteArray) -> String {
        (0..<bytes.size).map { String(format: "%02x", UInt8(bitPattern: bytes.get(index: $0))) }.joined()
    }

    /// 3초 간격, 최대 2분 동안 Infotainment `sessionInfo`로 등록을 확인한다.
    func confirmEnrollment() async -> String {
        guard let vehicle else { return "등록 확인: 먼저 연결하세요" }
        guard let key else { return "등록 확인: 먼저 키를 만드세요" }
        var last = "등록 확인: 시도하지 못함"
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline {
            do {
                let result = try await vehicle.sessionInfo(
                    publicKey: key.publicKey, domain: .infotainment, timeout: Timeouts.shared.seconds(seconds: 5)
                )
                switch onEnum(of: result) {
                case .success: return "등록 확인됨(Infotainment)"
                case .failure(let f):
                    if f.error is VehicleErrorKeyNotPaired { last = "아직 등록되지 않음(KeyNotPaired) — 2분까지 반복" }
                    else { last = "등록 확인 실패: \(f.error.message)" }
                case .uncertain(let u): last = "등록 확인 불확실: \(u.error.message)"
                }
            } catch { last = "등록 확인 오류: \(error)" }
            if Task.isCancelled { break }
            try? await Task.sleep(for: .seconds(3))
        }
        return "\(last) (2분 안에 확인되지 않음)"
    }

    /// 제어 명령. 결과가 `Uncertain`이면 다시 보내지 않고 곧바로 `vehicleStatus()`로 재조회한다(FR-102).
    func control(_ title: String, _ call: (Vehicle) async throws -> VehicleResult) async -> String {
        guard let vehicle else { return "\(title): 먼저 연결하세요" }
        let started = Date()
        let line: String
        do {
            let result = try await call(vehicle)
            switch onEnum(of: result) {
            case .success: line = "\(title): 성공"
            case .failure(let f): line = "\(title) 실패: \(f.error.message)"
            case .uncertain(let u):
                let status = await statusText()
                line = "\(title) 불확실: \(u.error.message) → 재조회: \(status)"
            }
        } catch { line = "\(title) 오류: \(error)" }
        return "\(line) [\(String(format: "%.1f", Date().timeIntervalSince(started)))초]"
    }

    private func statusText() async -> String {
        guard let vehicle else { return "연결 안 됨" }
        do {
            let result = try await vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let st = s.value as? VehicleStatus_ else { return "형식 오류" }
                return "잠금 \(st.lockState.name), 트렁크 \(st.closures.rearTrunk.name), 프렁크 \(st.closures.frontTrunk.name), 수면 \(st.sleepStatus.name)"
            case .uncertain(let u): return "불확실: \(u.error.message)"
            case .failure(let f): return "실패: \(f.error.message)"
            }
        } catch { return "오류: \(error)" }
    }

    func lock() async -> String { await control("잠금") { try await $0.lock(timeout: nil) } }
    func unlock() async -> String { await control("해제") { try await $0.unlock(timeout: nil) } }
    func openTrunk() async -> String { await control("트렁크 열기") { try await $0.openTrunk(timeout: nil) } }
    func closeTrunk() async -> String { await control("트렁크 닫기") { try await $0.closeTrunk(timeout: nil) } }
    func openFrunk() async -> String { await control("프렁크 열기") { try await $0.openFrunk(timeout: nil) } }
    func wakeUp() async -> String { await control("깨우기") { try await $0.wakeUp(timeout: nil) } }

    /// 슬롯으로 고른 마지막 목록 항목의 공개키로 키를 삭제한다.
    func removeKey(slot: Int) async -> String {
        guard let pk = keyEntries[slot]?.publicKey else { return "키 삭제: 목록에서 고른 항목을 찾지 못함(목록을 다시 조회하세요)" }
        return await control("키 삭제(슬롯 \(slot))") { try await $0.removeKey(publicKey: pk, timeout: nil) }
    }

    private func watchLink(_ v: Vehicle, _ onLinkLoss: @escaping @Sendable (String) async -> ()) {
        nonisolated(unsafe) let connection = v.connection
        linkWatch = Task {
            for await state in connection {
                if Task.isCancelled { return }
                if case .disconnected(let d) = onEnum(of: state), d.reason != nil {
                    await onLinkLoss("연결 끊김 (링크 손실)")
                    return
                }
            }
        }
    }

    func disconnect() async {
        linkWatch?.cancel()
        linkWatch = nil
        try? await vehicle?.disconnect()
        vehicle = nil
        keyEntries = [:]
    }

    private func run(_ title: String, _ call: (Vehicle) async throws -> VehicleResult) async -> String {
        guard let vehicle else { return "\(title): 먼저 연결하세요" }
        do {
            let result = try await call(vehicle)
            switch onEnum(of: result) {
            case .success(let s): return "\(title): \(String(describing: s.value))"
            case .uncertain(let u): return "\(title) 불확실: \(u.error.message)"
            case .failure(let f): return "\(title) 실패: \(f.error.message)"
            }
        } catch { return "\(title) 오류: \(error)" }
    }
}

/// 샘플 화면 상태.
@MainActor
final class SampleModel: ObservableObject {
    @Published var vinText = ""
    @Published var keyInfo = "키 없음"
    @Published var advertisements: [String] = []
    @Published var connection = "연결 안 됨"
    @Published var output: [String] = []
    /// 마지막 키 목록 조회 결과(항목마다 "삭제" 버튼).
    @Published var keyRows: [BleClient.KeyRow] = []
    /// 삭제 확인 대기 중인 항목.
    @Published var pendingRemoval: BleClient.KeyRow?

    /// 라이브러리 호출이 진행 중이면 true — 모든 버튼을 막아 호출이 겹치지 않게 한다.
    @Published var busy = false

    /// 관찰 모드(요청 없이 수신만 기록). 켜져 있는 동안은 차량을 깨우는 요청 버튼을 막는다.
    /// 샘플에는 주기적 자동 조회가 없다 — 관찰이 차량을 깨우지 않도록 앞으로도 넣지 않는다.
    @Published var observing = false {
        didSet { observingChanged() }
    }
    @Published var observations: [String] = []
    private var observeStart: Date?
    private static let maxObservations = 500

    private let client = BleClient()

    init() {
        let stream = client.observations
        Task { [weak self] in
            for await line in stream { await self?.observed(line) }
        }
    }

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    private func observingChanged() {
        if observing {
            observeStart = Date()
            log("관찰 시작")
        } else {
            observeStart = nil
            log("관찰 종료: \(observations.count)줄")
        }
    }

    /// 관찰 중일 때만 `[HH:mm:ss +12.3s] <line>`으로 쌓는다(최대 500줄, 넘치면 앞에서 지운다).
    private func observed(_ line: String) {
        guard observing, let start = observeStart else { return }
        append("\(ObservationMark.prefix(now: Date(), since: start)) \(line)")
    }

    private func append(_ line: String) {
        observations.append(line)
        if observations.count > Self.maxObservations {
            observations.removeFirst(observations.count - Self.maxObservations)
        }
    }

    /// 사람이 남기는 메모 줄. 관찰 모드에서만, 빈 메모는 무시한다. 차량 요청은 보내지 않는다.
    /// 성공하면 true(화면이 입력칸을 비운다).
    @discardableResult
    func mark(_ memo: String) -> Bool {
        guard observing, let start = observeStart,
              let line = ObservationMark.line(memo: memo, now: Date(), since: start) else { return false }
        append(line)
        return true
    }

    func clearObservations() {
        observations.removeAll()
    }

    /// 결과 칸에 시각과 함께 한 줄을 남긴다(복사해서 실차 확인 기록으로 쓴다).
    private func log(_ line: String) {
        output.append("[\(Self.clock.string(from: Date()))] \(line)")
    }

    /// 실차 확인 기록용 전체 텍스트: 앱 정보, 현재 상태, 결과 기록. VIN은 넣지 않는다.
    func report() -> String {
        let stamp = ISO8601DateFormatter.string(from: Date(), timeZone: .current, formatOptions: [.withInternetDateTime])
        var lines = [
            "Teslable Sample 기록 \(stamp)",
            "앱: \(AppInfo.bundleID) / \(AppInfo.version) / 빌드 \(AppInfo.build)",
            "연결: \(connection)",
            "키: \(keyInfo)",
        ]
        lines += advertisements.map { "광고: \($0)" }
        lines.append("--- 결과 (\(output.count)줄) ---")
        lines += output
        lines.append("--- 관찰 (\(observations.count)줄) ---")
        lines += observations
        return lines.joined(separator: "\n")
    }

    func clearOutput() {
        output.removeAll()
    }

    private func exclusive(_ body: @MainActor () async -> ()) async {
        guard !busy else { return }
        busy = true
        await body()
        busy = false
    }

    func createKey() async {
        await exclusive { [self] in
            let info = await client.createKey()
            keyInfo = info
        }
    }

    func scan() async {
        await exclusive { [self] in
            let line = await client.scan(vinText: vinText)
            advertisements = [line]
            log("스캔: \(line)")
        }
    }

    func connect() async {
        await exclusive { [self] in
            connection = "연결 중…"
            let result = await client.connect(vinText: vinText) { [weak self] text in await self?.linkLost(text) }
            connection = result
            log("연결: \(result)")
        }
    }

    /// 연결된 상태일 때만 반영한다(사용자가 이미 끊었다면 덮어쓰지 않는다).
    private func linkLost(_ text: String) {
        if connection.hasPrefix("연결됨") {
            connection = text
            log("연결: \(text)")
        }
    }

    func vehicleStatus() async {
        await exclusive { [self] in
            let line = await client.vehicleStatus()
            log(line)
        }
    }

    func listKeys() async {
        await exclusive { [self] in
            let result = await client.listKeys()
            keyRows = result.rows
            log(result.line)
        }
    }

    func startSession(vcsecOnly: Bool) async {
        await exclusive { [self] in log(await client.startSession(vcsecOnly: vcsecOnly)) }
    }

    func showSessions() async {
        await exclusive { [self] in log(await client.sessionsText()) }
    }

    func pair() async {
        await exclusive { [self] in
            await client.pair { [weak self] line in await self?.log(line) }
        }
    }

    func confirmEnrollment() async {
        await exclusive { [self] in log(await client.confirmEnrollment()) }
    }

    func lock() async { await exclusive { [self] in log(await client.lock()) } }
    func unlock() async { await exclusive { [self] in log(await client.unlock()) } }
    func openTrunk() async { await exclusive { [self] in log(await client.openTrunk()) } }
    func closeTrunk() async { await exclusive { [self] in log(await client.closeTrunk()) } }
    func openFrunk() async { await exclusive { [self] in log(await client.openFrunk()) } }
    func wakeUp() async { await exclusive { [self] in log(await client.wakeUp()) } }

    /// 확인 뒤에 호출한다. 삭제가 끝나면 목록을 다시 조회해 화면을 맞춘다.
    func removeKey(_ row: BleClient.KeyRow) async {
        await exclusive { [self] in
            log(await client.removeKey(slot: row.slot))
            let result = await client.listKeys()
            keyRows = result.rows
            log(result.line)
        }
    }

    func disconnect() async {
        await exclusive { [self] in
            await client.disconnect()
            connection = "연결 안 됨"
            keyRows = []
            log("연결 끊기: 연결 안 됨")
        }
    }
}
