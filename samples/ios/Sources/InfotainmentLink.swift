import Foundation
import Teslable

/// 한 연결의 `Vehicle` 에 하는 Infotainment 호출 넷(SFR-025): `vehicleStatus`, `startSession(INFOTAINMENT)`, 명령, `getState`.
/// 누르기 한 번이 시작할 때 한 번 얻고, 그 한 번의 호출은 모두 이 연결에 한다(중간에 연결이 바뀌어도 새 연결로 보내지 않는다).
/// 모두 `timeout: nil`(문서의 기본값). `wakeUp()` 은 여기에 없다. 결과는 오류 종류 이름과 이름 붙은 필드로만 돌려주고 `VehicleData` 를 들고 다니지 않는다.
protocol InfotainmentLink: Sendable {
    /// `vehicleStatus(timeout: nil)`: 수면 확인(세션도 키도 필요 없고 차를 깨우지 않는다).
    func readStatus() async -> InfoStatusRead
    /// `startSession(setOf(INFOTAINMENT))`. 이미 `Ready` 이면 요청 없이 성공한다.
    func ensureSession() async -> InfoSessionResult
    /// 명령 한 번(`climateOn` / `climateOff` / `chargeStart` / `chargeStop`). 읽기 버튼은 `read` 를 쓴다.
    func send(_ cmd: InfoCommand) async -> InfoCallResult<InfoNoValue>
    /// `getState(category, timeout: nil)` 한 번.
    func read(_ category: InfoCategory) async -> InfoCallResult<InfoValues>
}

final class LiveInfotainmentLink: InfotainmentLink, @unchecked Sendable {
    private let box: VehicleBox

    init(box: VehicleBox) { self.box = box }

    func readStatus() async -> InfoStatusRead {
        do {
            let result = try await box.vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let status = s.value as? VehicleStatus_ else { return .failed(errorType: "UnexpectedResultType") }
                return .success(StatusCard(StatusSnapshot(status), at: Date()))
            case .uncertain(let u): return .failed(errorType: u.error.infotainmentTypeName)
            case .failure(let f): return .failed(errorType: f.error.infotainmentTypeName)
            }
        } catch {
            return .failed(errorType: "Exception")
        }
    }

    /// `15-lib-commands.md` §Call order: 명령 앞에 `startSession(setOf(INFOTAINMENT))`.
    func ensureSession() async -> InfoSessionResult {
        let domains: Set<VehicleDomain> = [VehicleDomain.infotainment]
        do {
            let result = try await box.vehicle.startSession(domains: domains, timeout: nil)
            switch onEnum(of: result) {
            case .success: return .ready
            case .uncertain(let u): return .failed(errorType: u.error.infotainmentTypeName)
            case .failure(let f): return .failed(errorType: f.error.infotainmentTypeName)
            }
        } catch {
            return .failed(errorType: "Exception")
        }
    }

    func send(_ cmd: InfoCommand) async -> InfoCallResult<InfoNoValue> {
        do {
            let result: VehicleResult
            switch cmd {
            case .climateOn: result = try await box.vehicle.climateOn(timeout: nil)
            case .climateOff: result = try await box.vehicle.climateOff(timeout: nil)
            case .chargeStart: result = try await box.vehicle.chargeStart(timeout: nil)
            case .chargeStop: result = try await box.vehicle.chargeStop(timeout: nil)
            case .readClimate, .readCharge: return .failure(errorType: "ReadIsNotACommand") // 읽기 버튼은 `read` 로 보낸다
            }
            switch onEnum(of: result) {
            case .success: return .success(InfoNoValue())
            case .uncertain(let u): return .uncertain(errorType: u.error.infotainmentTypeName)
            case .failure(let f): return .failure(errorType: f.error.infotainmentTypeName)
            }
        } catch {
            return .failure(errorType: "Exception")
        }
    }

    /// `14-lib-reading-state.md` A8.3 의 Swift 형태: 값은 `s.value as? VehicleData` 로 꺼낸다.
    func read(_ category: InfoCategory) async -> InfoCallResult<InfoValues> {
        do {
            let result = try await box.vehicle.getState(category: category.stateCategory, timeout: nil)
            switch onEnum(of: result) {
            case .success(let s):
                guard let data = s.value as? VehicleData else { return .failure(errorType: "UnexpectedResultType") }
                return .success(InfoFields.extract(category, from: data))
            case .uncertain(let u): return .uncertain(errorType: u.error.infotainmentTypeName)
            case .failure(let f): return .failure(errorType: f.error.infotainmentTypeName)
            }
        } catch {
            return .failure(errorType: "Exception")
        }
    }
}
