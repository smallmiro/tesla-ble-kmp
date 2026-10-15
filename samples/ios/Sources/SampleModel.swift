import Foundation
import Teslable

/// 라이브러리 호출만 담당한다. Kotlin 객체는 Swift 6 `Sendable`이 아니므로 어떤 액터에도 묶지 않고
/// (비격리 async 안에서만 다루고) 화면에는 `String`만 돌려준다. 호출은 화면에서 한 번에 하나씩만 일어난다.
/// SKIE: suspend → async, Flow → AsyncSequence, sealed → onEnum.
final class BleClient: @unchecked Sendable {
    private let ble = TeslaBle.companion.create(
        platform: PlatformContext(),
        config: TeslaBleConfig.companion.default(logger: DebugLogger())
    )
    private var key: VehicleKey?
    private var vehicle: Vehicle?

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
        return "스캔 종료(광고 없음)"
    }

    func connect(vinText: String) async -> String {
        guard let vin = Vin.companion.parseOrNull(value: vinText) else { return "VIN 형식 오류" }
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

    private let client = BleClient()

    func createKey() async { keyInfo = await client.createKey() }

    func scan() async {
        advertisements = []
        advertisements.append(await client.scan(vinText: vinText))
    }

    func connect() async {
        connection = "연결 중…"
        connection = await client.connect(vinText: vinText)
    }

    func vehicleStatus() async { output.append(await client.vehicleStatus()) }
    func listKeys() async { output.append(await client.listKeys()) }

    func disconnect() async {
        await client.disconnect()
        connection = "연결 안 됨"
    }
}
