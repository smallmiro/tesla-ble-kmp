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

// MARK: - getState (SFR-023)

extension StateFault {
    /// 결과의 오류를 종류만 남기는 값으로 옮긴다(`14-lib-reading-state.md` A8.3, `16-lib-errors.md` §`getState` results).
    /// 문서에 적힌 세 가지(응답 크기 초과, 시간 초과, 세션 없음)만 가리고 나머지는 오류 타입 이름만 남긴다. `message` 는 옮기지 않는다.
    init(_ error: VehicleError) {
        switch onEnum(of: error) {
        case .protocolFault(let p) where p.fault == .messagefaultErrorResponseMtuExceeded:
            self = .responseTooLarge
        case .timeout(let t):
            self = .noReply(afterSend: t.afterSend)
        case .noSession:
            self = .noSession
        default:
            var name = String(describing: type(of: error))
            for prefix in ["Teslable.", "Teslable", "VehicleError"] where name.hasPrefix(prefix) { name.removeFirst(prefix.count) }
            self = .other(name.isEmpty ? "VehicleError" : name)
        }
    }
}

extension VehicleError {
    /// 명령 결과 줄에 쓰는 문장: 라이브러리 `message` 에, `Timeout` 이면 `afterSend` 에 맞는 안내를 붙인다
    /// (`16-lib-errors.md` `Timeout(afterSend)` 행: 참이면 보냈을 수 있음(`Uncertain`), 거짓이면 실행되지 않음(`Failure`)).
    var commandResultText: String {
        switch onEnum(of: self) {
        case .timeout(let t): return TimeoutNote.line(message: message, timeoutAfterSend: t.afterSend)
        default: return message
        }
    }
}

extension DiagnosticCategory {
    var stateCategory: StateCategory {
        switch self {
        case .drive: return .drive
        case .media: return .media
        case .mediaDetail: return .mediaDetail
        }
    }
}

/// `VehicleData` 에서 화면에 보일 이름 붙은 필드만 꺼낸다. 위치와 경로 필드는 `DriveState` 에 있어도 읽지 않는다.
/// `getState` 의 값은 여기서 `StateField` 로만 나가고, 로그 쪽(`StateLogEntry`)으로는 흐르지 않는다(FR-112).
enum StateFields {
    static func extract(_ category: DiagnosticCategory, from data: VehicleData) -> [StateField] {
        switch category {
        case .drive:
            let d = data.drive_state
            return StateDiagnostics.fields(drive: DriveValues(
                speed: d?.speed.map { Int($0.int32Value) },
                speedFloat: d?.speed_float.map { $0.floatValue },
                shift: d?.shift_state.flatMap(shiftName),
                power: d?.power.map { Int($0.int32Value) },
                odometer: d?.odometer_in_hundredths_of_a_mile.map { Int($0.int32Value) },
                timestamp: d?.timestamp.map { $0.getEpochSecond() }
            ))
        case .media:
            let m = data.media_state
            return StateDiagnostics.fields(media: MediaValues(
                playback: m?.media_playback_status.map { $0.name },
                volume: m?.audio_volume.map { $0.floatValue },
                title: m?.now_playing_title,
                timestamp: m?.timestamp.map { $0.getEpochSecond() }
            ))
        case .mediaDetail:
            let m = data.media_detail_state
            return StateDiagnostics.fields(detail: MediaDetailValues(
                duration: m?.now_playing_duration.map { Int($0.int32Value) },
                elapsed: m?.now_playing_elapsed.map { Int($0.int32Value) },
                timestamp: m?.timestamp.map { $0.getEpochSecond() }
            ))
        }
    }

    /// `ShiftState` 는 Wire 의 oneof 메시지라 enum 이 아니다: 값이 있는 멤버(`P`, `R`, `N`, `D`, `SNA`)가 곧 기어다.
    private static func shiftName(_ s: ShiftState) -> String? {
        if s.P != nil { return "P" }
        if s.R != nil { return "R" }
        if s.N != nil { return "N" }
        if s.D != nil { return "D" }
        if s.SNA != nil { return "SNA" }
        return nil
    }
}
