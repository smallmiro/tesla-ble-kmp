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
    var commandResultText: String { resultText(read: false) }

    /// 조회(`vehicleStatus()`, `startSession()` 등 차를 바꾸지 않는 호출)의 결과 줄. `Timeout` 안내가 "실행했을 수 있음" 이 아니다.
    var readResultText: String { resultText(read: true) }

    private func resultText(read: Bool) -> String {
        switch onEnum(of: self) {
        case .timeout(let t): return TimeoutNote.line(message: message, timeoutAfterSend: t.afterSend, read: read)
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

// MARK: - Infotainment 공조·충전 (SFR-025)

extension VehicleError {
    /// 결과 줄과 `InfoOutcome.errorType` 에 가는 유일한 오류 정보: **종류 이름**(`message` 는 쓰지 않는다, FR-112).
    /// `ResponseTooLarge`, `Timeout(afterSend=…)`, 그 밖에는 클래스 이름(`NoSession`, `NotConnected`, `Locked`, `InfotainmentRejected` …).
    var infotainmentTypeName: String {
        switch onEnum(of: self) {
        case .protocolFault(let p):
            return p.fault == .messagefaultErrorResponseMtuExceeded ? InfotainmentErrors.responseTooLarge : "ProtocolFault"
        case .timeout(let t): return InfotainmentErrors.timeout(afterSend: t.afterSend)
        case .keyNotPaired: return InfotainmentErrors.keyNotPaired
        case .keyStoreError(let k):
            switch onEnum(of: k) {
            case .locked: return InfotainmentErrors.locked
            case .hardwareUnavailable: return "HardwareUnavailable"
            case .keyNotFound: return "KeyNotFound"
            case .platformFailure: return "PlatformFailure"
            }
        case .transportError(let t):
            switch onEnum(of: t) {
            case .bluetoothOff: return "BluetoothOff"
            case .connectFailed: return "ConnectFailed"
            case .disconnected: return "Disconnected"
            case .maxConnectionsExceeded: return "MaxConnectionsExceeded"
            case .permissionDenied: return "PermissionDenied"
            case .scanTimeout: return "ScanTimeout"
            case .writeFailed: return "WriteFailed"
            }
        case .noSession: return "NoSession"
        case .notConnected: return "NotConnected"
        case .infotainmentRejected: return "InfotainmentRejected"
        case .badResponse: return "BadResponse"
        case .busy: return "Busy"
        case .insufficientPrivileges: return "InsufficientPrivileges"
        case .invalidArgument: return "InvalidArgument"
        case .keychainRejected: return "KeychainRejected"
        case .requiresKey: return "RequiresKey"
        case .unknownFault: return "UnknownFault"
        case .unknownKeychainCode: return "UnknownKeychainCode"
        case .unknownResponse: return "UnknownResponse"
        case .unknownVcsecError: return "UnknownVcsecError"
        case .vcsecRejected: return "VcsecRejected"
        }
    }
}

extension InfoCategory {
    var stateCategory: StateCategory {
        switch self {
        case .climate: return .climate
        case .charge: return .charge
        }
    }
}

/// `VehicleData` 에서 이름 붙은 필드만 꺼낸다(`14-lib-reading-state.md` A8.3 의 Swift 형태: 숫자는 `KotlinInt?` 등으로 온다).
/// `charge_state.charging_state` 는 읽지 않는다(`05-key-control-tab.md` §Readout 의 알려진 공백).
enum InfoFields {
    static func extract(_ category: InfoCategory, from data: VehicleData) -> InfoValues {
        switch category {
        case .climate:
            let c = data.climate_state
            return .climate(InfoClimateValues(
                isClimateOn: c?.is_climate_on?.boolValue,
                insideTempCelsius: c?.inside_temp_celsius?.floatValue,
                driverTempSetting: c?.driver_temp_setting?.floatValue,
                timestamp: c?.timestamp?.getEpochSecond()
            ))
        case .charge:
            let c = data.charge_state
            return .charge(InfoChargeValues(
                batteryLevel: c?.battery_level.map { Int($0.int32Value) },
                chargeLimitSoc: c?.charge_limit_soc.map { Int($0.int32Value) },
                chargerPower: c?.charger_power.map { Int($0.int32Value) },
                chargePortDoorOpen: c?.charge_port_door_open?.boolValue,
                timestamp: c?.timestamp?.getEpochSecond()
            ))
        }
    }
}
