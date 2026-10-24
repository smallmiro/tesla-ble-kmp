import Foundation
import Teslable

// Kotlin 타입(`VehicleEvent`, `VehicleStatus_`)을 화면과 로그가 쓰는 Swift 값으로 옮기는 곳. 매핑은 여기 한 곳에서만 한다.
// 값은 Kotlin enum 이름(`LOCKED`, `PARK` …)이라 Android 앱의 로그와 같은 글자가 된다.

extension KotlinByteArray {
    /// Kotlin `ByteArray` 의 복사본. `VehicleEvent.raw` 는 읽을 때마다 새 복사본이 오므로 한 번만 읽는다.
    func toBytes() -> [UInt8] {
        (0..<Int(size)).map { UInt8(bitPattern: get(index: Int32($0))) }
    }
}

extension StatusSnapshot {
    init(_ s: VehicleStatus_) {
        let c = s.closures
        self.init(
            lock: s.lockState.name, gear: s.gear.name, presence: s.userPresence.name, sleep: s.sleepStatus.name,
            frontDriverDoor: c.frontDriverDoor.name, frontPassengerDoor: c.frontPassengerDoor.name,
            rearDriverDoor: c.rearDriverDoor.name, rearPassengerDoor: c.rearPassengerDoor.name,
            frunk: c.frontTrunk.name, trunk: c.rearTrunk.name, chargePort: c.chargePort.name
        )
    }
}

extension MonitorEvent {
    init(_ event: any VehicleEvent) {
        switch onEnum(of: event) {
        case .status(let s):
            self.init(kind: .status(StatusSnapshot(s.status)), raw: s.raw.toBytes(), unknownFields: s.unknownFields)
        case .authenticationRequest(let a):
            self.init(
                kind: .authenticationRequest(level: a.requestedLevel.name, reasons: a.reasons.map(\.name)),
                raw: a.raw.toBytes(), unknownFields: a.unknownFields
            )
        case .unrecognized(let u):
            self.init(kind: .unrecognized, raw: u.raw.toBytes(), unknownFields: u.unknownFields)
        }
    }
}

/// `Vehicle` 은 Kotlin 객체라 `Sendable` 이 아니다. 연결 루프와 Task 사이로 넘길 때 이 상자에 담는다(접근은 라이브러리가 스레드 안전).
struct VehicleBox: @unchecked Sendable, Equatable {
    let vehicle: Vehicle

    static func == (lhs: VehicleBox, rhs: VehicleBox) -> Bool { lhs.vehicle === rhs.vehicle }
}
