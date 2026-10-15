import Foundation
import Teslable

/// 라이브러리 호출만 담당한다. Kotlin 객체는 이 액터 안에만 두고 화면에는 `String`만 돌려준다.
/// 액터는 suspend 중 재진입할 수 있으므로 화면(`SampleModel.busy`)이 동작을 한 번에 하나만 허용한다.
/// SKIE: suspend → async, Flow → AsyncSequence, sealed → onEnum.
actor BleClient {
    // Kotlin 객체는 Sendable이 아니라 SKIE async 호출로 보낼 수 없다. 액터가 접근을 직렬화하고 화면이 겹침을 막으므로
    // `nonisolated(unsafe)`로 표시한다(문자열만 액터 밖으로 나간다).
    nonisolated(unsafe) private let ble = TeslaBle.companion.create(
        platform: PlatformContext(),
        config: TeslaBleConfig.companion.default(logger: DebugLogger())
    )
    nonisolated(unsafe) private var key: VehicleKey?
    nonisolated(unsafe) private var vehicle: Vehicle?

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

    func connect(vinText: String) async -> String {
        guard let vin = Vin.companion.parseOrNull(value: vinText) else { return "VIN 형식 오류" }
        await disconnect() // 이미 연결돼 있으면 먼저 끊는다
        do {
            let result = try await ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
            switch onEnum(of: result) {
            case .success(let s):
                guard let v = s.value as? Vehicle else { return "실패: 예상하지 못한 결과 형식" }
                vehicle = v
                return "연결됨 (키: \(key == nil ? "없음" : "있음"))"
            case .uncertain(let u): return "불확실: \(u.error.message)"
            case .failure(let f): return "실패: \(f.error.message)"
            }
        } catch { return "오류: \(error)" }
    }

    func vehicleStatus() async -> String { await run("VehicleStatus") { try await $0.vehicleStatus(timeout: nil) } }
    func listKeys() async -> String { await run("키 목록") { try await $0.listKeys(timeout: nil) } }

    func disconnect() async {
        try? await vehicle?.disconnect()
        vehicle = nil
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

    /// 라이브러리 호출이 진행 중이면 true — 모든 버튼을 막아 호출이 겹치지 않게 한다.
    @Published var busy = false

    private let client = BleClient()

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
        }
    }

    func connect() async {
        await exclusive { [self] in
            connection = "연결 중…"
            let result = await client.connect(vinText: vinText)
            connection = result
        }
    }

    func vehicleStatus() async {
        await exclusive { [self] in
            let line = await client.vehicleStatus()
            output.append(line)
        }
    }

    func listKeys() async {
        await exclusive { [self] in
            let line = await client.listKeys()
            output.append(line)
        }
    }

    func disconnect() async {
        await exclusive { [self] in
            await client.disconnect()
            connection = "연결 안 됨"
        }
    }
}
