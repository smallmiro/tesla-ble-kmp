import Foundation
import Teslable

/// 라이브러리 호출만 담당한다. Kotlin 객체는 이 액터 안에만 두고 화면에는 `String`만 돌려준다.
/// 액터는 suspend 중 재진입할 수 있으므로 화면(`SampleModel.busy`)이 동작을 한 번에 하나만 허용한다.
/// SKIE: suspend → async, Flow → AsyncSequence, sealed → onEnum.
actor BleClient {
    // Kotlin 객체는 Sendable이 아니라 SKIE async 호출로 보낼 수 없다. 액터가 접근을 직렬화하고 화면이 겹침을 막으므로
    // `nonisolated(unsafe)`로 표시한다(문자열만 액터 밖으로 나간다).
    nonisolated(unsafe) private let ble: TeslaBle
    /// 연결·키·로그는 모니터 엔진이 가진 것을 쓴다(`TeslaBle`은 프로세스당 하나, 차의 연결은 3개뿐).
    private let engine: MonitorEngine

    init(engine: MonitorEngine) {
        self.engine = engine
        ble = engine.ble
    }

    // 연결 루프가 `Vehicle` 을 바꾸므로 동작을 시작할 때마다 `refresh()` 로 현재 것을 가져온다(Kotlin 객체라 `nonisolated(unsafe)`).
    nonisolated(unsafe) private var vehicle: Vehicle?
    nonisolated(unsafe) private var key: VehicleKey?

    private func refresh() {
        vehicle = engine.currentVehicle()
        key = engine.currentKey()
    }

    /// 모니터 키(`teslable-monitor`)를 읽거나 만든다. 결과 문장은 화면용이다.
    func createKey() async -> String {
        switch await engine.ensureKey() {
        case .ready(let k): return "\(k.alias) \(k.protection.name) \(k.accessibility.name) \(String(describing: k.publicKey))"
        case .locked: return "키 잠김: 폰을 잠금 해제한 뒤 다시 누르세요(키는 지우지 않음)"
        case .failed(let why): return "실패: \(why)"
        }
    }

    func scan() async -> String {
        guard let vin = engine.parsedVin() else { return "저장된 VIN이 없습니다(Live 탭에서 저장)" }
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

    func vehicleStatus() async -> String {
        refresh()
        return await run("VehicleStatus") { try await $0.vehicleStatus(timeout: nil) }
    }

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
        refresh()
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
        refresh()
        let domains: Set<VehicleDomain> = vcsecOnly ? [VehicleDomain.vcsec] : VehicleDomain.companion.ALL
        let label = vcsecOnly ? "세션(VCSEC만)" : "세션(전체)"
        let started = Date()
        let line = await run(label) { try await $0.startSession(domains: domains, timeout: nil) }
        return "\(line) [\(String(format: "%.1f", Date().timeIntervalSince(started)))초] \(sessionsText())"
    }

    /// 도메인별 세션 상태 한 줄.
    func sessionsText() -> String {
        refresh()
        guard let vehicle else { return "세션: 연결 안 됨" }
        return engine.sessionsText(vehicle)
    }

    /// 키 등록 요청. 이벤트마다 한국어 한 줄을 `onEvent`로 보내고, 끝이 `Accepted`/`TimedOut`이면 등록 확인을 잇는다.
    /// 등록 확인까지 끝나 등록이 확인되면 `true`(키·제어 탭이 등록 상태를 바꾸는 데 쓴다).
    @discardableResult
    func pair(onEvent: @escaping @Sendable (String) async -> ()) async -> Bool {
        refresh()
        guard let vehicle else { await onEvent("키 등록: 먼저 연결하세요"); return false }
        guard let key else { await onEvent("키 등록: 먼저 키를 만드세요"); return false }
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
        guard confirm else { return false }
        let confirmed = await confirmEnrollment()
        await onEvent(confirmed.line)
        return confirmed.enrolled
    }

    private func hex(_ bytes: KotlinByteArray) -> String {
        (0..<bytes.size).map { String(format: "%02x", UInt8(bitPattern: bytes.get(index: $0))) }.joined()
    }

    /// 3초 간격, 최대 2분 동안 Infotainment `sessionInfo`로 등록을 확인한다(`13-lib-pairing.md` 의 `waitUntilEnrolled`).
    /// `KeyNotPaired`·`Uncertain` 이면 계속 묻고, 그 밖의 `Failure`(예: 링크가 끊긴 `NotConnected`)면 바로 멈춘다.
    /// 등록이 확인됐는지와 화면용 한 줄을 돌려준다.
    func confirmEnrollment() async -> (enrolled: Bool, line: String) {
        refresh()
        guard let vehicle else { return (false, "등록 확인: 먼저 연결하세요") }
        guard let key else { return (false, "등록 확인: 먼저 키를 만드세요") }
        return await EnrollmentWaiter.wait(
            probe: {
                do {
                    let result = try await vehicle.sessionInfo(
                        publicKey: key.publicKey, domain: .infotainment, timeout: Timeouts.shared.seconds(seconds: 5)
                    )
                    switch onEnum(of: result) {
                    case .success: return .enrolled
                    case .failure(let f): return f.error is VehicleErrorKeyNotPaired ? .notYet : .failed(f.error.message)
                    case .uncertain(let u): return .uncertain(u.error.message)
                    }
                } catch { return .uncertain("\(error)") }
            },
            sleep: { try await Task.sleep(for: .seconds($0)) },
            now: { Date().timeIntervalSince1970 }
        )
    }

    /// 제어 명령. 결과가 `Uncertain`이면 다시 보내지 않고 곧바로 `vehicleStatus()`로 재조회한다(FR-102).
    func control(_ title: String, _ call: (Vehicle) async throws -> VehicleResult) async -> String {
        refresh()
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

    /// `vehicleStatus()` 한 번의 결과. 값은 `StatusSnapshot` 으로 한 번만 옮기고, 못 읽으면 이유 한 줄을 돌려준다.
    private enum StatusRead {
        case snapshot(StatusSnapshot)
        case unavailable(String)
    }

    /// 재조회가 쓰는 공통 읽기(`statusText()` 와 `statusCard()` 가 함께 쓴다). `refresh()` 는 부르는 쪽이 이미 했다.
    private func readStatus() async -> StatusRead {
        guard let vehicle else { return .unavailable("연결 안 됨") }
        do {
            let result = try await vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let st = s.value as? VehicleStatus_ else { return .unavailable("형식 오류") }
                return .snapshot(StatusSnapshot(st))
            case .uncertain(let u): return .unavailable("불확실: \(u.error.message)")
            case .failure(let f): return .unavailable("실패: \(f.error.message)")
            }
        } catch { return .unavailable("오류: \(error)") }
    }

    private func statusText() async -> String {
        switch await readStatus() {
        case .snapshot(let s): return "잠금 \(s.lock), 트렁크 \(s.trunk), 프렁크 \(s.frunk), 수면 \(s.sleep)"
        case .unavailable(let why): return why
        }
    }

    func lock() async -> String { await control("잠금") { try await $0.lock(timeout: nil) } }
    func unlock() async -> String { await control("해제") { try await $0.unlock(timeout: nil) } }
    func openTrunk() async -> String { await control("트렁크 열기") { try await $0.openTrunk(timeout: nil) } }
    func closeTrunk() async -> String { await control("트렁크 닫기") { try await $0.closeTrunk(timeout: nil) } }
    func openFrunk() async -> String { await control("프렁크 열기") { try await $0.openFrunk(timeout: nil) } }
    func wakeUp() async -> String { await control("깨우기") { try await $0.wakeUp(timeout: nil) } }

    /// 슬롯으로 고른 마지막 목록 항목의 공개키로 키를 삭제한다.
    func removeKey(slot: Int) async -> String {
        refresh()
        guard let pk = keyEntries[slot]?.publicKey else { return "키 삭제: 목록에서 고른 항목을 찾지 못함(목록을 다시 조회하세요)" }
        return await control("키 삭제(슬롯 \(slot))") { try await $0.removeKey(publicKey: pk, timeout: nil) }
    }

    /// 연결이 바뀌면 지난 목록의 공개키를 버린다.
    func resetEntries() { keyEntries = [:] }

    // MARK: - 키·제어 탭 (SFR-018, `samples/documents/md/05-key-control-tab.md`)

    /// 차량의 키 목록에 이 기기의 공개키가 있는지 `listKeys()` 로 확인한다(`13-lib-pairing.md` §Checking whether a key is enrolled).
    /// `Uncertain`/`Failure`/오류는 `.unknown`(나중에 다시 확인), 연결이나 키가 이미 없으면 `.noLink`.
    func checkEnrollment() async -> EnrollmentCheckOutcome {
        refresh()
        guard let vehicle, let key else { return .noLink }
        do {
            let result = try await vehicle.listKeys(timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let entries = s.value as? [WhitelistEntry] else { return .unknown("예상하지 못한 결과 형식") }
                let own = entries.contains { e in e.publicKey.map { $0.isEqual(key.publicKey) } ?? false }
                return own ? .enrolled : .notEnrolled
            case .uncertain(let u): return .unknown("불확실: \(u.error.message)")
            case .failure(let f): return .unknown("실패: \(f.error.message)")
            }
        } catch { return .unknown("오류: \(error)") }
    }

    /// 명령 앞의 세션 보장: `startSession(setOf(VCSEC))`. 이미 `Ready` 인 도메인은 요청 없이 성공한다(`15-lib-commands.md`).
    func ensureVcsecSession() async -> SessionOutcome {
        refresh()
        guard let vehicle else { return .failed("연결되어 있지 않습니다") }
        let domains: Set<VehicleDomain> = [VehicleDomain.vcsec]
        do {
            let result = try await vehicle.startSession(domains: domains, timeout: nil)
            switch onEnum(of: result) {
            case .success: return .ready
            case .failure(let f):
                if f.error is VehicleErrorKeyNotPaired { return .notEnrolled }
                if f.error is VehicleErrorKeyStoreErrorLocked { return .locked }
                return .failed(f.error.message)
            case .uncertain(let u): return .failed("불확실: \(u.error.message)")
            }
        } catch { return .failed("\(error)") }
    }

    /// 버튼에 맞는 VCSEC 명령을 한 번 보낸다. 다시 보내지 않는다(재조회는 `KeyControlRunner` 가 한다).
    func send(_ button: ControlButton) async -> CommandOutcome {
        refresh()
        guard let vehicle else { return .failure("연결되어 있지 않습니다") }
        do {
            let result: VehicleResult
            switch button {
            case .lock: result = try await vehicle.lock(timeout: nil)
            case .unlock: result = try await vehicle.unlock(timeout: nil)
            case .wakeUp: result = try await vehicle.wakeUp(timeout: nil)
            case .actuateTrunk: result = try await vehicle.actuateTrunk(timeout: nil)
            case .closeTrunk: result = try await vehicle.closeTrunk(timeout: nil)
            case .openFrunk: result = try await vehicle.openFrunk(timeout: nil)
            case .remoteDrive: result = try await vehicle.remoteDrive(timeout: nil)
            case .autoSecure: result = try await vehicle.autoSecureVehicle(timeout: nil)
            case .openTonneau: result = try await vehicle.openTonneau(timeout: nil)
            case .closeTonneau: result = try await vehicle.closeTonneau(timeout: nil)
            case .stopTonneau: result = try await vehicle.stopTonneau(timeout: nil)
            }
            switch onEnum(of: result) {
            case .success: return .success
            case .failure(let f): return .failure(f.error.message, keyNotPaired: f.error is VehicleErrorKeyNotPaired)
            case .uncertain(let u): return .uncertain(u.error.message)
            }
        } catch { return .failure("\(error)") }
    }

    /// `vehicleStatus()` 재조회. 성공하면 상태 카드 값을, 아니면 이유만 돌려준다(화면의 이전 값은 그대로 둔다).
    func statusCard() async -> RequeryOutcome {
        refresh()
        switch await readStatus() {
        case .snapshot(let s):
            let card = StatusCard(s, at: Date())
            return RequeryOutcome(text: "잠금 \(card.lockText), 트렁크 \(card.trunkText), 프렁크 \(card.frunkText), 수면 \(card.sleepText)", card: card)
        case .unavailable(let why):
            return RequeryOutcome(text: why, card: nil)
        }
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

/// Control 탭 화면 상태. 연결과 모니터 키는 `MonitorEngine` 이 가진 것을 쓴다(`MonitorModel` 이 만든다).
@MainActor
final class SampleModel: ObservableObject {
    @Published var keyInfo = "키 없음"
    @Published var advertisements: [String] = []
    @Published var output: [String] = []
    /// 마지막 키 목록 조회 결과(항목마다 "삭제" 버튼).
    @Published var keyRows: [BleClient.KeyRow] = []
    /// 삭제 확인 대기 중인 항목.
    @Published var pendingRemoval: BleClient.KeyRow?

    /// 라이브러리 호출이 진행 중이면 true — 모든 버튼을 막아 호출이 겹치지 않게 한다.
    @Published var busy = false

    private let engine: MonitorEngine
    private let client: BleClient

    /// 이 탭의 동작이 이 기기의 키 등록 상태를 바꿨을 수 있을 때 키·제어 탭에 알린다(`MonitorModel` 이 연결한다).
    enum EnrollmentChange: Equatable {
        /// 키 등록 요청 뒤 등록 확인이 성공했다.
        case enrolled
        /// 이 기기의 키를 지우려 했다(결과와 상관없이 다시 확인해야 한다).
        case ownKeyRemoved
    }

    var onEnrollmentChange: ((EnrollmentChange) -> ())?

    init(engine: MonitorEngine) {
        self.engine = engine
        client = BleClient(engine: engine)
    }

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    /// 결과 칸에 시각과 함께 한 줄을 남긴다(복사해서 실차 확인 기록으로 쓴다).
    private func log(_ line: String) {
        output.append("[\(Self.clock.string(from: Date()))] \(line)")
    }

    /// 실차 확인 기록용 전체 텍스트: 앱 정보, 현재 상태, 결과 기록, 로그 마지막 줄. VIN은 넣지 않는다(로그 줄은 이미 가려져 있다).
    func report() -> String {
        let stamp = ISO8601DateFormatter.string(from: Date(), timeZone: .current, formatOptions: [.withInternetDateTime])
        var lines = [
            "Teslable Sample 기록 \(stamp)",
            "앱: \(AppInfo.bundleID) / \(AppInfo.version) / 빌드 \(AppInfo.build)",
            "연결: \(LinkText.of(engine.link))",
            "키: \(keyInfo)",
        ]
        lines += advertisements.map { "광고: \($0)" }
        lines.append("--- 결과 (\(output.count)줄) ---")
        lines += output
        let last = engine.log.stats.lastLines
        lines.append("--- 로그 마지막 줄 (\(last.count)줄) ---")
        lines += last
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
            let line = await client.scan()
            advertisements = [line]
            log("스캔: \(line)")
        }
    }

    /// 모니터의 연결 루프를 바로 시작한다(기다리던 재연결은 건너뛴다). 상태는 배너와 Live 탭에 나온다.
    func connect() async {
        await exclusive { [self] in
            if engine.connectNow() {
                log("연결: 시작함 (상태는 위 배너)")
            } else {
                log("연결: 저장된 VIN이 없거나 형식이 틀립니다")
            }
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
            let enrolled = await client.pair { [weak self] line in await self?.log(line) }
            if enrolled { onEnrollmentChange?(.enrolled) }
        }
    }

    func confirmEnrollment() async {
        await exclusive { [self] in log(await client.confirmEnrollment().line) }
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
            if row.isOwn { onEnrollmentChange?(.ownKeyRemoved) }
        }
    }

    /// 자동 재연결도 멈춘다(SFR-012). 다시 연결 버튼을 누를 때까지 재연결하지 않는다.
    func disconnect() async {
        await exclusive { [self] in
            await engine.disconnect()
            await client.resetEntries()
            keyRows = []
            log("연결 끊기: 자동 재연결 중지")
        }
    }
}
