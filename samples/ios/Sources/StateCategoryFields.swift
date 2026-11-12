import Foundation

// 상태 진단(SFR-023)의 나머지 9개 범주를 화면에 보일 줄로 바꾸는 순수 규칙. 라이브러리(Kotlin) 타입이 없다.
// 값은 `VehicleMapping.swift` 가 `VehicleData` 에서 이 파일의 `...Values` 로 옮기고, 여기서 한국어 라벨과 단위가 붙는다.
// 정본: `documents/md/14-lib-reading-state.md` A8.1(범주), A8.3(없는 값은 0 이 아니다, 결과를 로그에 남기지 않는다), A8.4(위치).
//
// 규칙
// - 차가 보내지 않은 값은 `—`. 0 으로 바꾸지 않는다.
// - 단위는 proto 가 말했거나 이름이 분명히 말하는 것만 붙인다(이름에 celsius·mph·`*_ms`·`*_sec`·`minutes_*`, 주석에 bar·meters,
//   이름상 백분율인 `battery_level`·`usable_battery_level`·`charge_limit_soc`·`*_perc`·`*_percent_open` 의 %).
//   그 밖은 `(단위 미확인)` 이다: 주행 가능 거리, 충전 전력·전압·전류, 설정 온도, 방향 등(`14-lib-reading-state.md` A8.5: 실차에서 확인된 것이 없다).
// - oneof 메시지(`Void` 멤버들)에서 멤버가 하나도 없이 온 것은 값 없음(`—`)으로 읽는다. 중첩 메시지의 일반 값(일정 항목의 `enabled`)은
//   없음과 false 를 가를 수 없다(A8.3).
// - 로그로 가는 것은 `presentKeys`(차가 보낸 필드의 이름)뿐이다. 값, 좌표, 위치 이름, 일정 이름은 어디에도 남기지 않는다.

// MARK: - 공통 형식

enum StateFormat {
    static let absent = StateDiagnostics.absent
    static let unitUnknown = StateDiagnostics.unitUnknown

    static func int(_ n: Int?, suffix: String = "") -> String {
        n.map { "\($0)\(suffix)" } ?? absent
    }

    static func decimal(_ f: Float?, digits: Int, suffix: String = "") -> String {
        f.map { String(format: "%.\(digits)f", Double($0)) + suffix } ?? absent
    }

    static func yesNo(_ b: Bool?, yes: String, no: String) -> String {
        b.map { $0 ? yes : no } ?? absent
    }

    /// 에폭 초를 `2023-11-14 22:13:20 (1700000000)` 로. 읽기 쉬운 시각과 원래 값을 같이 보인다.
    static func time(_ epochSeconds: Int64?, zone: TimeZone = .current) -> String {
        guard let epochSeconds else { return absent }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return "\(formatter.string(from: Date(timeIntervalSince1970: TimeInterval(epochSeconds)))) (\(epochSeconds))"
    }

    /// oneof 의 멤버 이름(`Charging`)에 한국어를 붙여 `충전 중 (Charging)`. 모르는 이름은 그대로 보인다(새 펌웨어 값).
    static func named(_ name: String?, _ table: [String: String]) -> String {
        guard let name else { return absent }
        return table[name].map { "\($0) (\(name))" } ?? name
    }

    /// 단위를 모르는 정수: `11 (단위 미확인)`.
    static func rawInt(_ n: Int?) -> String { n.map { "\($0) (\(unitUnknown))" } ?? absent }

    static func rawDecimal(_ f: Float?, digits: Int) -> String {
        f.map { String(format: "%.\(digits)f", Double($0)) + " (\(unitUnknown))" } ?? absent
    }
}

// MARK: - 값 묶음 (차가 보내지 않았으면 nil)

struct LocationValues: Sendable, Equatable {
    var latitude: Float?, longitude: Float?
    var nativeLatitude: Float?, nativeLongitude: Float?
    /// `GCJ` 또는 `WGS`.
    var nativeType: String?
    var correctedLatitude: Float?, correctedLongitude: Float?
    var geoLatitude: Float?, geoLongitude: Float?
    /// 추정 좌표와 원시 GPS 좌표 사이의 거리(미터, proto 주석).
    var estimatedToRawDistance: Float?
    var estimatedGpsValid: Bool?
    var heading: Int?
    var gpsAsOf: Int64?
    var locationName: String?
    var homelinkNearby: Bool?
    var timestamp: Int64?
}

struct WheelValues: Sendable, Equatable {
    var pressure: Float?
    var lastSeen: Int64?
    var hard: Bool?
    var soft: Bool?
}

struct TireValues: Sendable, Equatable {
    var fl = WheelValues(), fr = WheelValues(), rl = WheelValues(), rr = WheelValues()
    var rcpFront: Float?, rcpRear: Float?
    var timestamp: Int64?
}

struct ClimateValues: Sendable, Equatable {
    var inside: Float?, outside: Float?
    var driverSet: Float?, passengerSet: Float?
    var climateOn: Bool?, autoConditioningOn: Bool?, preconditioning: Bool?
    var batteryHeater: Bool?
    var fanStatus: Int?
    var frontDefroster: Bool?, rearDefroster: Bool?
    var seatHeaterLeft: Int?, seatHeaterRight: Int?
    var keeperMode: String?
    var timestamp: Int64?
}

struct ChargeValues: Sendable, Equatable {
    var batteryLevel: Int?, usableBatteryLevel: Int?
    var batteryRange: Float?, estBatteryRange: Float?, idealBatteryRange: Float?
    var chargingState: String?
    var chargerPower: Int?, chargerVoltage: Int?, chargerActualCurrent: Int?, chargerPilotCurrent: Int?
    var minutesToFullCharge: Int?
    var chargeLimitSoc: Int?
    var chargePortDoorOpen: Bool?
    var chargePortLatch: String?
    var timestamp: Int64?
}

struct ClosuresValues: Sendable, Equatable {
    var doorDriverFront: Bool?, doorDriverRear: Bool?, doorPassengerFront: Bool?, doorPassengerRear: Bool?
    var trunkFront: Bool?, trunkRear: Bool?
    var windowDriverFront: Bool?, windowDriverRear: Bool?, windowPassengerFront: Bool?, windowPassengerRear: Bool?
    var sunRoof: String?
    var sunRoofPercent: Int?
    var locked: Bool?
    var sentry: String?
    var sentryAvailable: Bool?
    var userPresent: Bool?
    var timestamp: Int64?
}

struct SoftwareUpdateValues: Sendable, Equatable {
    var status: String?
    var version: String?
    var downloadPerc: Int?, installPerc: Int?
    /// 예약 시각, 밀리초.
    var scheduledTimeMs: Int64?
    var expectedDurationSec: Int?
    var timestamp: Int64?
}

/// 충전 일정과 예열 일정의 요약. 일정의 이름과 위치는 읽지 않는다.
struct ScheduleValues: Sendable, Equatable {
    var count = 0
    var enabledCount = 0
    var oneTimeCount = 0
    var hasWindow = false
    var nextSchedule: Bool?
    var maxCount: Int?
    /// 충전 일정에만 있다(`charge_buffer`).
    var buffer: Int?
    var showComplete: Bool?
    var timestamp: Int64?
}

struct ParentalValues: Sendable, Equatable {
    var active: Bool?, pinSet: Bool?
    var hasSettings = false
    var speedLimitEnabled: Bool?
    var maxLimitMph: Float?, minLimitMph: Float?, currentLimitMph: Float?
    var chillAcceleration: Bool?, requireSafetySettings: Bool?
    var curfewEnabled: Bool?, curfewStart: Int?, curfewEnd: Int?
    var timestamp: Int64?
}

// MARK: - 위치: 어떤 좌표를 쓰나

/// 고른 좌표. `source` 는 어느 필드 쌍인지(이름), `system` 은 좌표계(`WGS`, `GCJ`, 모르면 nil).
struct PickedCoordinate: Equatable, Sendable {
    let latitude: Float
    let longitude: Float
    let source: String
    let system: String?
    /// 첫째 선택이 아닌 이유(있으면 화면에 보인다).
    let reason: String?
}

/// `14-lib-reading-state.md` A8.4: native 가 첫째, 없으면 WGS 는 latitude/longitude, GCJ 는 corrected, 추정↔원시 거리가 크면 geo.
enum LocationRule {
    /// "크다"의 기준. proto 는 기준을 주지 않는다(A8.4). 100 m 는 이 샘플이 정한 값이고 실차로 조정한다.
    static let largeGapMeters: Float = 100

    static func pick(_ v: LocationValues) -> PickedCoordinate? {
        if let lat = v.geoLatitude, let lon = v.geoLongitude, let gap = v.estimatedToRawDistance, gap >= largeGapMeters {
            let text = StateFormat.decimal(gap, digits: 0)
            return PickedCoordinate(
                latitude: lat, longitude: lon, source: "geo_latitude/geo_longitude", system: "WGS",
                reason: "추정↔원시 거리 \(text) m 가 \(Int(largeGapMeters)) m 이상이라 원시 GPS를 씀(샘플 기준값)"
            )
        }
        if let lat = v.nativeLatitude, let lon = v.nativeLongitude {
            return PickedCoordinate(latitude: lat, longitude: lon, source: "native_latitude/native_longitude", system: v.nativeType, reason: nil)
        }
        let plain: PickedCoordinate? = zip2(v.latitude, v.longitude).map {
            PickedCoordinate(latitude: $0, longitude: $1, source: "latitude/longitude", system: "WGS", reason: nil)
        }
        let corrected: PickedCoordinate? = zip2(v.correctedLatitude, v.correctedLongitude).map {
            PickedCoordinate(latitude: $0, longitude: $1, source: "corrected_latitude/corrected_longitude", system: "GCJ", reason: nil)
        }
        // native 가 없으면 차의 지역(native_type)으로 어느 클라이언트인지 가른다: GCJ 차는 corrected 가 먼저.
        // 이것은 샘플의 추정이다. A8.4 는 "클라이언트의 좌표계"를 말하는데 샘플에는 그 설정이 없고, native 가 없으면 native_type 도 없을 가능성이 크다.
        return v.nativeType == "GCJ" ? (corrected ?? plain) : (plain ?? corrected)
    }

    private static func zip2(_ a: Float?, _ b: Float?) -> (Float, Float)? {
        guard let a, let b else { return nil }
        return (a, b)
    }
}

// MARK: - 타이어 경고

enum TireWarnings {
    private static var wheels: [(String, KeyPath<TireValues, WheelValues> & Sendable)] {
        [("앞 왼쪽", \.fl), ("앞 오른쪽", \.fr), ("뒤 왼쪽", \.rl), ("뒤 오른쪽", \.rr)]
    }

    /// 경고가 있는 바퀴와 종류. 경고가 없고 값이 다 있으면 `없음`, 일부만 있으면 그 사실을 덧붙이고, 하나도 없으면 `—`.
    static func summary(_ v: TireValues) -> String {
        var warned: [String] = []
        var known = 0
        for (name, path) in wheels {
            let w = v[keyPath: path]
            if w.hard != nil || w.soft != nil { known += 1 }
            var kinds: [String] = []
            if w.hard == true { kinds.append("하드") }
            if w.soft == true { kinds.append("소프트") }
            if !kinds.isEmpty { warned.append("\(name)(\(kinds.joined(separator: "+")))") }
        }
        if !warned.isEmpty { return warned.joined(separator: ", ") }
        if known == 0 { return StateFormat.absent }
        return known == wheels.count ? "없음" : "없음 (일부 바퀴는 값 없음)"
    }

    static func wheelText(hard: Bool?, soft: Bool?) -> String {
        if hard == nil && soft == nil { return StateFormat.absent }
        let h = StateFormat.yesNo(hard, yes: "경고", no: "없음")
        let s = StateFormat.yesNo(soft, yes: "경고", no: "없음")
        return "하드 \(h) · 소프트 \(s)"
    }
}

// MARK: - 줄 만들기

enum StateCategoryFields {
    private static let absent = StateFormat.absent

    /// 차가 보낸 필드의 이름. 줄 순서대로, 겹치면 한 번만.
    static func presentKeys(_ fields: [StateField]) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for key in fields.flatMap(\.presentKeys) where seen.insert(key).inserted { out.append(key) }
        return out
    }

    // MARK: 줄 도우미

    /// 필드 하나에서 나온 줄. 값이 있으면(`nil` 이 아니면) 필드 이름이 `presentKeys` 로 간다.
    private static func row<T>(_ label: String, _ key: String, _ v: T?, _ format: (T?) -> String) -> StateField {
        StateField(label: label, value: format(v), presentKeys: v == nil ? [] : [key])
    }

    /// 여러 필드에서 나온 줄: `(필드 이름, 있는지)` 로 이름을 모은다.
    private static func row(_ label: String, _ value: String, _ parts: [(String, Bool)]) -> StateField {
        StateField(label: label, value: value, presentKeys: parts.filter(\.1).map(\.0))
    }

    // MARK: Charge

    static func fields(charge v: ChargeValues, zone: TimeZone = .current) -> [StateField] {
        let chargingStates = [
            "Unknown": "알 수 없음", "Disconnected": "연결 안 됨", "NoPower": "전원 없음", "Starting": "시작 중",
            "Charging": "충전 중", "Complete": "완료", "Stopped": "중지", "Calibrating": "보정 중",
        ]
        let latches = ["SNA": "알 수 없음", "Disengaged": "풀림", "Engaged": "체결됨", "Blocking": "막힘"]
        return [
            row("배터리 잔량", "battery_level", v.batteryLevel) { StateFormat.int($0, suffix: "%") },
            row("사용 가능 배터리 잔량", "usable_battery_level", v.usableBatteryLevel) { StateFormat.int($0, suffix: "%") },
            row("주행 가능 거리 (battery_range)", "battery_range", v.batteryRange) { StateFormat.rawDecimal($0, digits: 2) },
            row("주행 가능 거리 (est_battery_range)", "est_battery_range", v.estBatteryRange) { StateFormat.rawDecimal($0, digits: 2) },
            row("주행 가능 거리 (ideal_battery_range)", "ideal_battery_range", v.idealBatteryRange) { StateFormat.rawDecimal($0, digits: 2) },
            row("충전 상태", "charging_state", v.chargingState) { StateFormat.named($0, chargingStates) },
            row("충전 전력", "charger_power", v.chargerPower) { StateFormat.rawInt($0) },
            row("충전 전압", "charger_voltage", v.chargerVoltage) { StateFormat.rawInt($0) },
            row("충전 전류 (실제)", "charger_actual_current", v.chargerActualCurrent) { StateFormat.rawInt($0) },
            row("충전 전류 (파일럿)", "charger_pilot_current", v.chargerPilotCurrent) { StateFormat.rawInt($0) },
            row("완충까지", "minutes_to_full_charge", v.minutesToFullCharge) { StateFormat.int($0, suffix: "분") },
            row("충전 한도", "charge_limit_soc", v.chargeLimitSoc) { StateFormat.int($0, suffix: "%") },
            row("충전 포트 문", "charge_port_door_open", v.chargePortDoorOpen) { StateFormat.yesNo($0, yes: "열림", no: "닫힘") },
            row("충전 포트 걸쇠", "charge_port_latch", v.chargePortLatch) { StateFormat.named($0, latches) },
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
        ]
    }

    // MARK: Climate

    /// 시트 히터 단계(`SeatHeaterLevel_E`: 0 꺼짐, 1 낮음, 2 중간, 3 높음).
    static func seatLevel(_ n: Int?) -> String {
        guard let n else { return absent }
        return ["꺼짐", "낮음", "중간", "높음"].indices.contains(n) ? ["꺼짐", "낮음", "중간", "높음"][n] : String(n)
    }

    static func fields(climate v: ClimateValues, zone: TimeZone = .current) -> [StateField] {
        let onOff: (Bool?) -> String = { StateFormat.yesNo($0, yes: "켜짐", no: "꺼짐") }
        let keeper = ["Unknown": "알 수 없음", "Off": "꺼짐", "On": "켜짐", "Dog": "반려견 모드", "Party": "파티 모드"]
        return [
            row("실내 온도", "inside_temp_celsius", v.inside) { StateFormat.decimal($0, digits: 1, suffix: " °C") },
            row("실외 온도", "outside_temp_celsius", v.outside) { StateFormat.decimal($0, digits: 1, suffix: " °C") },
            row("설정 온도 (운전석)", "driver_temp_setting", v.driverSet) { StateFormat.rawDecimal($0, digits: 1) },
            row("설정 온도 (조수석)", "passenger_temp_setting", v.passengerSet) { StateFormat.rawDecimal($0, digits: 1) },
            row("공조 켜짐", "is_climate_on", v.climateOn) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
            row("자동 공조 켜짐", "is_auto_conditioning_on", v.autoConditioningOn) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
            row("예열 중", "is_preconditioning", v.preconditioning) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
            row("배터리 히터", "battery_heater", v.batteryHeater, onOff),
            row("송풍 단계", "fan_status", v.fanStatus) { StateFormat.int($0) },
            row("앞 유리 열선", "is_front_defroster_on", v.frontDefroster, onOff),
            row("뒤 유리 열선", "is_rear_defroster_on", v.rearDefroster, onOff),
            row("시트 히터 (왼쪽)", "seat_heater_left", v.seatHeaterLeft, seatLevel),
            row("시트 히터 (오른쪽)", "seat_heater_right", v.seatHeaterRight, seatLevel),
            row("클라이밋 키퍼", "climate_keeper_mode", v.keeperMode) { StateFormat.named($0, keeper) },
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
        ]
    }

    // MARK: Location

    private static func coordinateText(_ lat: Float?, _ lon: Float?) -> String {
        guard let lat, let lon else { return absent }
        return String(format: "%.6f, %.6f", Double(lat), Double(lon))
    }

    /// 좌표는 화면에만 나온다. 로그로 가는 것은 필드 이름뿐이다.
    static func fields(location v: LocationValues, zone: TimeZone = .current) -> [StateField] {
        let pick = LocationRule.pick(v)
        let coordinateKeys: [(String, Bool)] = [
            ("native_latitude", v.nativeLatitude != nil), ("native_longitude", v.nativeLongitude != nil),
            ("latitude", v.latitude != nil), ("longitude", v.longitude != nil),
            ("corrected_latitude", v.correctedLatitude != nil), ("corrected_longitude", v.correctedLongitude != nil),
            ("native_type", v.nativeType != nil),
        ]
        let sourceText: String
        if let pick {
            let system = pick.system.map { " (\($0))" } ?? " (좌표계 모름)"
            sourceText = pick.source + system + (pick.reason.map { " — \($0)" } ?? "")
        } else {
            sourceText = absent
        }
        return [
            row("위도·경도", pick.map { coordinateText($0.latitude, $0.longitude) } ?? absent, coordinateKeys),
            row("좌표 출처", sourceText, []),
            row("원시 GPS (geo_*)", coordinateText(v.geoLatitude, v.geoLongitude), [
                ("geo_latitude", v.geoLatitude != nil), ("geo_longitude", v.geoLongitude != nil),
            ]),
            row("추정↔원시 GPS 거리", "estimated_to_raw_distance", v.estimatedToRawDistance) { StateFormat.decimal($0, digits: 1, suffix: " m") },
            row("추정 GPS 유효", "estimated_gps_valid", v.estimatedGpsValid) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
            row("방향 (heading)", "heading", v.heading) { StateFormat.rawInt($0) },
            row("GPS 기준 시각 (gps_as_of)", "gps_as_of", v.gpsAsOf) { $0.map { "\($0) (\(StateFormat.unitUnknown))" } ?? absent },
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
            row("위치 이름", "location_name", v.locationName) { $0 ?? absent },
            row("홈링크 근처", "homelink_nearby", v.homelinkNearby) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
        ]
    }

    // MARK: Closures

    static func fields(closures v: ClosuresValues, zone: TimeZone = .current) -> [StateField] {
        let open: (Bool?) -> String = { StateFormat.yesNo($0, yes: "열림", no: "닫힘") }
        let sunroof = ["Unknown": "알 수 없음", "Calibrating": "보정 중", "Closed": "닫힘", "Open": "열림", "Moving": "움직이는 중", "Vent": "환기"]
        let sentry = ["Off": "꺼짐", "Idle": "대기", "Armed": "경계 중", "Aware": "감지", "Panic": "경보", "Quiet": "조용"]
        return [
            row("문 (운전석 앞)", "door_open_driver_front", v.doorDriverFront, open),
            row("문 (조수석 앞)", "door_open_passenger_front", v.doorPassengerFront, open),
            row("문 (운전석 뒤)", "door_open_driver_rear", v.doorDriverRear, open),
            row("문 (조수석 뒤)", "door_open_passenger_rear", v.doorPassengerRear, open),
            row("프렁크 (trunk_front)", "door_open_trunk_front", v.trunkFront, open),
            row("트렁크 (trunk_rear)", "door_open_trunk_rear", v.trunkRear, open),
            row("창문 (운전석 앞)", "window_open_driver_front", v.windowDriverFront, open),
            row("창문 (조수석 앞)", "window_open_passenger_front", v.windowPassengerFront, open),
            row("창문 (운전석 뒤)", "window_open_driver_rear", v.windowDriverRear, open),
            row("창문 (조수석 뒤)", "window_open_passenger_rear", v.windowPassengerRear, open),
            row("선루프 상태", "sun_roof_state", v.sunRoof) { StateFormat.named($0, sunroof) },
            row("선루프 열림 정도", "sun_roof_percent_open", v.sunRoofPercent) { StateFormat.int($0, suffix: "%") },
            row("잠금", "locked", v.locked) { StateFormat.yesNo($0, yes: "잠김", no: "잠기지 않음") },
            row("센트리 모드", "sentry_mode_state", v.sentry) { StateFormat.named($0, sentry) },
            row("센트리 모드 사용 가능", "sentry_mode_available", v.sentryAvailable) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
            row("운전자 존재", "is_user_present", v.userPresent) { StateFormat.yesNo($0, yes: "있음", no: "없음") },
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
        ]
    }

    // MARK: TirePressure

    static func fields(tire v: TireValues, zone: TimeZone = .current) -> [StateField] {
        var out = [StateField(label: "경고 요약", value: TireWarnings.summary(v))]
        let wheels: [(String, String, WheelValues)] = [
            ("앞 왼쪽", "fl", v.fl), ("앞 오른쪽", "fr", v.fr), ("뒤 왼쪽", "rl", v.rl), ("뒤 오른쪽", "rr", v.rr),
        ]
        for (name, suffix, w) in wheels {
            out.append(row("\(name) 압력", "tpms_pressure_\(suffix)", w.pressure) { StateFormat.decimal($0, digits: 2, suffix: " bar") })
            out.append(row("\(name) 마지막 수신", "tpms_last_seen_pressure_time_\(suffix)", w.lastSeen) { StateFormat.time($0, zone: zone) })
            out.append(row("\(name) 경고", TireWarnings.wheelText(hard: w.hard, soft: w.soft), [
                ("tpms_hard_warning_\(suffix)", w.hard != nil), ("tpms_soft_warning_\(suffix)", w.soft != nil),
            ]))
        }
        out.append(row("권장 압력 앞 (rcp)", "tpms_rcp_front_value", v.rcpFront) { StateFormat.decimal($0, digits: 2, suffix: " bar") })
        out.append(row("권장 압력 뒤 (rcp)", "tpms_rcp_rear_value", v.rcpRear) { StateFormat.decimal($0, digits: 2, suffix: " bar") })
        out.append(row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) })
        return out
    }

    // MARK: SoftwareUpdate

    static func fields(update v: SoftwareUpdateValues, zone: TimeZone = .current) -> [StateField] {
        let statuses = [
            "Unknown": "알 수 없음", "Installing": "설치 중", "Scheduled": "예약됨", "Available": "받을 수 있음",
            "DownloadingWifiWait": "Wi-Fi 대기 중", "Downloading": "다운로드 중",
        ]
        return [
            row("업데이트 상태", "status", v.status) { StateFormat.named($0, statuses) },
            row("버전", "version", v.version) { $0 ?? absent },
            row("다운로드", "download_perc", v.downloadPerc) { StateFormat.int($0, suffix: "%") },
            row("설치", "install_perc", v.installPerc) { StateFormat.int($0, suffix: "%") },
            row("예약 시각", "scheduled_time_ms", v.scheduledTimeMs) { StateFormat.time($0.map { $0 / 1000 }, zone: zone) },
            row("예상 소요", "expected_duration_sec", v.expectedDurationSec) { StateFormat.int($0, suffix: " 초") },
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
        ]
    }

    // MARK: Schedules

    private static func scheduleRows(_ v: ScheduleValues, listKey: String, windowKey: String, nextKey: String, maxKey: String) -> [StateField] {
        let list = StateField(
            label: "일정 수",
            value: v.count == 0 ? "0개 (없음 또는 차가 보내지 않음)" : "\(v.count)개",
            presentKeys: v.count == 0 ? [] : [listKey]
        )
        return [
            list,
            StateField(label: "활성 일정", value: v.count == 0 ? absent : "\(v.enabledCount)개"),
            StateField(label: "1회성 일정", value: v.count == 0 ? absent : "\(v.oneTimeCount)개"),
            row("다음 일정 있음 (next_schedule)", nextKey, v.nextSchedule) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
            StateField(label: "지금 적용 중인 일정 (window)", value: v.hasWindow ? "있음" : absent, presentKeys: v.hasWindow ? [windowKey] : []),
            row("최대 일정 수", maxKey, v.maxCount) { StateFormat.int($0, suffix: "개") },
        ]
    }

    static func fields(chargeSchedule v: ScheduleValues, zone: TimeZone = .current) -> [StateField] {
        scheduleRows(v, listKey: "charge_schedules", windowKey: "charge_schedule_window", nextKey: "next_schedule", maxKey: "max_num_charge_schedules") + [
            row("충전 버퍼", "charge_buffer", v.buffer) { StateFormat.rawInt($0) },
            row("일정 완료 상태 표시", "show_schedule_complete_state", v.showComplete) { StateFormat.yesNo($0, yes: "예", no: "아니오") },
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
        ]
    }

    static func fields(preconditioningSchedule v: ScheduleValues, zone: TimeZone = .current) -> [StateField] {
        scheduleRows(v, listKey: "precondition_schedules", windowKey: "preconditioning_schedule_window", nextKey: "next_schedule", maxKey: "max_num_precondition_schedules") + [
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
        ]
    }

    // MARK: ParentalControls

    static func fields(parental v: ParentalValues, zone: TimeZone = .current) -> [StateField] {
        let yn: (Bool?) -> String = { StateFormat.yesNo($0, yes: "예", no: "아니오") }
        let mph: (Float?) -> String = { StateFormat.decimal($0, digits: 0, suffix: " mph") }
        return [
            row("자녀 보호 활성", "parental_controls_active", v.active, yn),
            row("PIN 설정됨", "parental_controls_pin_set", v.pinSet, yn),
            StateField(label: "세부 설정", value: v.hasSettings ? "있음" : absent, presentKeys: v.hasSettings ? ["parental_controls_settings"] : []),
            row("속도 제한 사용", "speed_limit_enabled", v.speedLimitEnabled, yn),
            row("현재 속도 제한", "current_limit_mph", v.currentLimitMph, mph),
            row("최대 속도 제한", "max_limit_mph", v.maxLimitMph, mph),
            row("최소 속도 제한", "min_limit_mph", v.minLimitMph, mph),
            row("차분한 가속 사용", "chill_acceleration_enabled", v.chillAcceleration, yn),
            row("안전 설정 필수", "require_safety_settings_enabled", v.requireSafetySettings, yn),
            row("통금 사용", "curfew_enabled", v.curfewEnabled, yn),
            row("통금 시작", "curfew_start_time", v.curfewStart) { StateFormat.rawInt($0) },
            row("통금 종료", "curfew_end_time", v.curfewEnd) { StateFormat.rawInt($0) },
            row("응답 시각 (timestamp)", "timestamp", v.timestamp) { StateFormat.time($0, zone: zone) },
        ]
    }
}
