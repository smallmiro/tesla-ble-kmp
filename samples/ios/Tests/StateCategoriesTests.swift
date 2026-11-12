import XCTest
@testable import TeslableSample

/// SFR-023 의 나머지 9개 범주(Charge, Climate, Location, Closures, TirePressure, SoftwareUpdate, 두 일정, ParentalControls)의 순수 규칙:
/// 없는 값은 `—`(0 이 아님), 단위는 proto 가 말한 것만, 좌표 고르기(`14-lib-reading-state.md` A8.4), 로그에는 필드 이름만.
final class StateCategoriesTests: XCTestCase {
    private func value(_ fields: [StateField], _ label: String, file: StaticString = #filePath, line: UInt = #line) -> String? {
        let hit = fields.first { $0.label == label }
        XCTAssertNotNil(hit, "no row \(label)", file: file, line: line)
        return hit?.value
    }

    // MARK: 범주 목록

    func testEveryStateCategoryHasAButtonInTheDocumentedOrder() {
        XCTAssertEqual(DiagnosticCategory.allCases.count, 12)
        XCTAssertEqual(
            DiagnosticCategory.allCases.map(\.logName),
            ["DRIVE", "MEDIA", "MEDIA_DETAIL", "CHARGE", "CLIMATE", "LOCATION", "CLOSURES", "TIRE_PRESSURE",
             "SOFTWARE_UPDATE", "CHARGE_SCHEDULE", "PRECONDITIONING_SCHEDULE", "PARENTAL_CONTROLS"]
        )
        XCTAssertEqual(Set(DiagnosticCategory.allCases.map(\.title)).count, 12, "titles are unique")
    }

    // MARK: 공통 형식

    func testFormatKeepsAbsentAsDashAndZeroAsZero() {
        XCTAssertEqual(StateFormat.int(nil, suffix: "%"), "—")
        XCTAssertEqual(StateFormat.int(0, suffix: "%"), "0%", "0 stays 0")
        XCTAssertEqual(StateFormat.decimal(nil, digits: 2, suffix: " bar"), "—")
        XCTAssertEqual(StateFormat.decimal(0, digits: 2, suffix: " bar"), "0.00 bar")
        XCTAssertEqual(StateFormat.decimal(2.849, digits: 2, suffix: " bar"), "2.85 bar")
        XCTAssertEqual(StateFormat.yesNo(nil, yes: "예", no: "아니오"), "—")
        XCTAssertEqual(StateFormat.yesNo(false, yes: "예", no: "아니오"), "아니오", "false is not absent")
        XCTAssertEqual(StateFormat.yesNo(true, yes: "예", no: "아니오"), "예")
    }

    func testTimeShowsReadableTimeAndTheRawSeconds() {
        let utc = TimeZone(identifier: "UTC")!
        XCTAssertEqual(StateFormat.time(nil, zone: utc), "—")
        XCTAssertEqual(StateFormat.time(1_700_000_000, zone: utc), "2023-11-14 22:13:20 (1700000000)")
        XCTAssertEqual(StateFormat.time(0, zone: utc), "1970-01-01 00:00:00 (0)", "0 is a value, not absent")
    }

    func testNamedValueTranslatesKnownNamesAndKeepsUnknownOnes() {
        XCTAssertEqual(StateFormat.named(nil, ["A": "가"]), "—")
        XCTAssertEqual(StateFormat.named("A", ["A": "가"]), "가 (A)")
        XCTAssertEqual(StateFormat.named("NEW", ["A": "가"]), "NEW", "a name we do not know is shown as is")
    }

    // MARK: Location

    private func location(_ edit: (inout LocationValues) -> Void = { _ in }) -> LocationValues {
        var v = LocationValues()
        edit(&v)
        return v
    }

    func testLocationPrefersNativeCoordinatesWithTheirType() {
        let pick = LocationRule.pick(location {
            $0.nativeLatitude = 37.5; $0.nativeLongitude = 127.0; $0.nativeType = "WGS"
            $0.latitude = 1; $0.longitude = 2; $0.correctedLatitude = 3; $0.correctedLongitude = 4
        })
        XCTAssertEqual(pick, PickedCoordinate(latitude: 37.5, longitude: 127.0, source: "native_latitude/native_longitude", system: "WGS", reason: nil))
    }

    func testLocationFallsBackToPlainWhenNativeIsMissing() {
        let pick = LocationRule.pick(location { $0.latitude = 37.5; $0.longitude = 127.0; $0.correctedLatitude = 3; $0.correctedLongitude = 4 })
        XCTAssertEqual(pick?.source, "latitude/longitude")
        XCTAssertEqual(pick?.system, "WGS")
    }

    func testLocationUsesCorrectedFirstForAGcjCarWithoutNativeFields() {
        let pick = LocationRule.pick(location {
            $0.nativeType = "GCJ"; $0.latitude = 1; $0.longitude = 2; $0.correctedLatitude = 31.5; $0.correctedLongitude = 121.5
        })
        XCTAssertEqual(pick?.source, "corrected_latitude/corrected_longitude")
        XCTAssertEqual(pick?.system, "GCJ")
        XCTAssertEqual(pick?.latitude, 31.5)
    }

    func testLocationFallsBackToCorrectedWhenPlainIsMissingToo() {
        let pick = LocationRule.pick(location { $0.correctedLatitude = 31.5; $0.correctedLongitude = 121.5 })
        XCTAssertEqual(pick?.source, "corrected_latitude/corrected_longitude")
    }

    func testLocationUsesRawGpsWhenEstimatedAndRawFarApart() {
        let far = LocationRule.largeGapMeters
        let pick = LocationRule.pick(location {
            $0.nativeLatitude = 37.5; $0.nativeLongitude = 127.0; $0.nativeType = "WGS"
            $0.geoLatitude = 35.1; $0.geoLongitude = 129.0; $0.estimatedToRawDistance = far
        })
        XCTAssertEqual(pick?.source, "geo_latitude/geo_longitude")
        XCTAssertEqual(pick?.system, "WGS")
        XCTAssertNotNil(pick?.reason, "the screen says why the raw GPS was chosen")
    }

    func testLocationKeepsNativeWhenTheGapIsSmallOrUnknown() {
        let small = LocationRule.pick(location {
            $0.nativeLatitude = 37.5; $0.nativeLongitude = 127.0
            $0.geoLatitude = 35.1; $0.geoLongitude = 129.0; $0.estimatedToRawDistance = LocationRule.largeGapMeters - 1
        })
        XCTAssertEqual(small?.source, "native_latitude/native_longitude")
        let unknown = LocationRule.pick(location {
            $0.nativeLatitude = 37.5; $0.nativeLongitude = 127.0; $0.geoLatitude = 35.1; $0.geoLongitude = 129.0
        })
        XCTAssertEqual(unknown?.source, "native_latitude/native_longitude", "no distance: no reason to leave native")
    }

    func testLocationNeedsBothHalvesOfAPair() {
        XCTAssertNil(LocationRule.pick(location { $0.nativeLatitude = 37.5 }), "latitude alone is not a coordinate")
        XCTAssertNil(LocationRule.pick(location { $0.geoLatitude = 1; $0.geoLongitude = 2 }), "geo alone, with no large gap, is not chosen")
        XCTAssertNil(LocationRule.pick(LocationValues()))
        XCTAssertEqual(LocationRule.pick(location { $0.latitude = 0; $0.longitude = 0 })?.latitude, 0, "0, 0 is a value")
    }

    func testLocationFieldsShowTheChosenCoordinateAndTheRest() {
        let fields = StateCategoryFields.fields(location: location {
            $0.nativeLatitude = 37.123456; $0.nativeLongitude = 127.654321; $0.nativeType = "WGS"
            $0.geoLatitude = 37.1; $0.geoLongitude = 127.6; $0.estimatedToRawDistance = 12.5; $0.estimatedGpsValid = true
            $0.heading = 270; $0.gpsAsOf = 1_700_000_000; $0.locationName = "서울"
            $0.timestamp = 1_700_000_100
        })
        XCTAssertEqual(value(fields, "위도·경도"), "37.123455, 127.654320", "six decimals of the Float the vehicle sent")
        XCTAssertEqual(value(fields, "좌표 출처"), "native_latitude/native_longitude (WGS)")
        XCTAssertEqual(value(fields, "추정↔원시 GPS 거리"), "12.5 m", "the proto states meters")
        XCTAssertEqual(value(fields, "추정 GPS 유효"), "예")
        XCTAssertEqual(value(fields, "방향 (heading)"), "270 (단위 미확인)")
        XCTAssertEqual(value(fields, "GPS 기준 시각 (gps_as_of)"), "1700000000 (단위 미확인)")
        XCTAssertEqual(value(fields, "위치 이름"), "서울")
    }

    func testLocationAbsentFieldsAreDashes() {
        let fields = StateCategoryFields.fields(location: LocationValues())
        for f in fields { XCTAssertEqual(f.value, "—", f.label) }
        XCTAssertTrue(fields.allSatisfy { $0.presentKeys.isEmpty })
    }

    // MARK: Tire pressure

    func testTireFieldsShowBarPerWheelWarningsAndRecommendedPressure() {
        let utc = TimeZone(identifier: "UTC")!
        var v = TireValues()
        v.fl = WheelValues(pressure: 2.85, lastSeen: 1_700_000_000, hard: false, soft: false)
        v.fr = WheelValues(pressure: 0, lastSeen: nil, hard: nil, soft: true)
        v.rcpFront = 2.9
        v.rcpRear = 3.1
        let fields = StateCategoryFields.fields(tire: v, zone: utc)
        XCTAssertEqual(value(fields, "앞 왼쪽 압력"), "2.85 bar")
        XCTAssertEqual(value(fields, "앞 왼쪽 마지막 수신"), "2023-11-14 22:13:20 (1700000000)")
        XCTAssertEqual(value(fields, "앞 왼쪽 경고"), "하드 없음 · 소프트 없음")
        XCTAssertEqual(value(fields, "앞 오른쪽 압력"), "0.00 bar", "0 is a value")
        XCTAssertEqual(value(fields, "앞 오른쪽 마지막 수신"), "—")
        XCTAssertEqual(value(fields, "앞 오른쪽 경고"), "하드 — · 소프트 경고")
        XCTAssertEqual(value(fields, "뒤 왼쪽 압력"), "—")
        XCTAssertEqual(value(fields, "권장 압력 앞 (rcp)"), "2.90 bar")
        XCTAssertEqual(value(fields, "권장 압력 뒤 (rcp)"), "3.10 bar")
    }

    func testTireWarningSummaryNamesTheWheelsAndKindsOfWarning() {
        var v = TireValues()
        XCTAssertEqual(TireWarnings.summary(v), "—", "nothing sent")
        v.fl = WheelValues(pressure: nil, lastSeen: nil, hard: false, soft: false)
        v.fr = WheelValues(pressure: nil, lastSeen: nil, hard: false, soft: false)
        XCTAssertEqual(TireWarnings.summary(v), "없음 (일부 바퀴는 값 없음)")
        v.rl = WheelValues(pressure: nil, lastSeen: nil, hard: false, soft: false)
        v.rr = WheelValues(pressure: nil, lastSeen: nil, hard: false, soft: false)
        XCTAssertEqual(TireWarnings.summary(v), "없음")
        v.rl = WheelValues(pressure: nil, lastSeen: nil, hard: true, soft: true)
        v.fr = WheelValues(pressure: nil, lastSeen: nil, hard: false, soft: true)
        XCTAssertEqual(TireWarnings.summary(v), "앞 오른쪽(소프트), 뒤 왼쪽(하드+소프트)")
    }

    func testTirePresentKeysAreFieldNamesOnly() {
        var v = TireValues()
        v.fl = WheelValues(pressure: 2.85, lastSeen: nil, hard: false, soft: nil)
        v.rcpRear = 3
        let keys = StateCategoryFields.presentKeys(StateCategoryFields.fields(tire: v))
        XCTAssertEqual(keys, ["tpms_pressure_fl", "tpms_hard_warning_fl", "tpms_rcp_rear_value"])
    }

    // MARK: Climate

    func testClimateShowsCelsiusOnlyWhereTheFieldNameSaysSo() {
        var v = ClimateValues()
        v.inside = 21.5; v.outside = -3; v.driverSet = 22; v.passengerSet = 0
        v.climateOn = false; v.batteryHeater = true
        let fields = StateCategoryFields.fields(climate: v)
        XCTAssertEqual(value(fields, "실내 온도"), "21.5 °C")
        XCTAssertEqual(value(fields, "실외 온도"), "-3.0 °C")
        XCTAssertEqual(value(fields, "설정 온도 (운전석)"), "22.0 (단위 미확인)", "the proto does not say the unit of the set temperature")
        XCTAssertEqual(value(fields, "설정 온도 (조수석)"), "0.0 (단위 미확인)", "0 is a value")
        XCTAssertEqual(value(fields, "공조 켜짐"), "아니오")
        XCTAssertEqual(value(fields, "배터리 히터"), "켜짐")
        XCTAssertEqual(value(fields, "시트 히터 (왼쪽)"), "—")
    }

    func testSeatHeaterLevelsUseTheDocumentedNames() {
        XCTAssertEqual(StateCategoryFields.seatLevel(0), "꺼짐")
        XCTAssertEqual(StateCategoryFields.seatLevel(1), "낮음")
        XCTAssertEqual(StateCategoryFields.seatLevel(2), "중간")
        XCTAssertEqual(StateCategoryFields.seatLevel(3), "높음")
        XCTAssertEqual(StateCategoryFields.seatLevel(9), "9", "a level we do not know is shown as is")
        XCTAssertEqual(StateCategoryFields.seatLevel(nil), "—")
    }

    // MARK: Charge

    func testChargeShowsPercentMinutesAndKeepsRangeUnitUnconfirmed() {
        var v = ChargeValues()
        v.batteryLevel = 0; v.usableBatteryLevel = 78; v.batteryRange = 201.5; v.estBatteryRange = 190
        v.chargingState = "Charging"; v.chargerPower = 11; v.chargerVoltage = 230; v.chargerActualCurrent = 16
        v.minutesToFullCharge = 95; v.chargeLimitSoc = 80; v.chargePortDoorOpen = true; v.chargePortLatch = "Engaged"
        let fields = StateCategoryFields.fields(charge: v, zone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(value(fields, "배터리 잔량"), "0%", "0 stays 0")
        XCTAssertEqual(value(fields, "사용 가능 배터리 잔량"), "78%")
        XCTAssertEqual(value(fields, "주행 가능 거리 (battery_range)"), "201.50 (단위 미확인)")
        XCTAssertEqual(value(fields, "주행 가능 거리 (ideal_battery_range)"), "—")
        XCTAssertEqual(value(fields, "충전 상태"), "충전 중 (Charging)")
        XCTAssertEqual(value(fields, "충전 전력"), "11 (단위 미확인)")
        XCTAssertEqual(value(fields, "충전 전압"), "230 V")
        XCTAssertEqual(value(fields, "충전 전류 (실제)"), "16 A")
        XCTAssertEqual(value(fields, "완충까지"), "95분")
        XCTAssertEqual(value(fields, "충전 한도"), "80%")
        XCTAssertEqual(value(fields, "충전 포트 문"), "열림")
        XCTAssertEqual(value(fields, "충전 포트 걸쇠"), "체결됨 (Engaged)")
    }

    func testChargeWithNothingSentIsAllDashes() {
        let fields = StateCategoryFields.fields(charge: ChargeValues(), zone: TimeZone(identifier: "UTC")!)
        for f in fields { XCTAssertEqual(f.value, "—", f.label) }
    }

    // MARK: Closures

    func testClosuresShowDoorsWindowsSunroofLockSentryAndUser() {
        var v = ClosuresValues()
        v.doorDriverFront = true; v.doorPassengerFront = false; v.windowDriverFront = false
        v.sunRoof = "Closed"; v.sunRoofPercent = 0; v.locked = true; v.sentry = "Armed"; v.userPresent = false
        let fields = StateCategoryFields.fields(closures: v, zone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(value(fields, "문 (운전석 앞)"), "열림")
        XCTAssertEqual(value(fields, "문 (조수석 앞)"), "닫힘")
        XCTAssertEqual(value(fields, "문 (운전석 뒤)"), "—")
        XCTAssertEqual(value(fields, "창문 (운전석 앞)"), "닫힘")
        XCTAssertEqual(value(fields, "선루프 상태"), "닫힘 (Closed)")
        XCTAssertEqual(value(fields, "선루프 열림 정도"), "0%")
        XCTAssertEqual(value(fields, "잠금"), "잠김")
        XCTAssertEqual(value(fields, "센트리 모드"), "경계 중 (Armed)")
        XCTAssertEqual(value(fields, "운전자 존재"), "없음")
    }

    // MARK: Software update

    func testSoftwareUpdateShowsStatusVersionAndProgress() {
        var v = SoftwareUpdateValues()
        v.status = "Downloading"; v.version = "2026.27.6.15 abc"; v.downloadPerc = 0; v.installPerc = nil
        v.scheduledTimeMs = 1_700_000_000_000; v.expectedDurationSec = 1500
        let fields = StateCategoryFields.fields(update: v, zone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(value(fields, "업데이트 상태"), "다운로드 중 (Downloading)")
        XCTAssertEqual(value(fields, "버전"), "2026.27.6.15 abc")
        XCTAssertEqual(value(fields, "다운로드"), "0%")
        XCTAssertEqual(value(fields, "설치"), "—")
        XCTAssertEqual(value(fields, "예약 시각"), "2023-11-14 22:13:20 (1700000000)", "milliseconds are shown as seconds")
        XCTAssertEqual(value(fields, "예상 소요"), "1500 초")
    }

    // MARK: Schedules and parental controls

    func testChargeScheduleSummaryShowsCountsAndFlags() {
        var v = ScheduleValues()
        v.count = 3; v.enabledCount = 2; v.oneTimeCount = 1; v.hasWindow = true; v.nextSchedule = false; v.maxCount = 20; v.buffer = 5
        let fields = StateCategoryFields.fields(chargeSchedule: v, zone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(value(fields, "일정 수"), "3개")
        XCTAssertEqual(value(fields, "활성 일정"), "2개")
        XCTAssertEqual(value(fields, "1회성 일정"), "1개")
        XCTAssertEqual(value(fields, "다음 일정 있음 (next_schedule)"), "아니오")
        XCTAssertEqual(value(fields, "지금 적용 중인 일정 (window)"), "있음")
        XCTAssertEqual(value(fields, "최대 일정 수"), "20개")
        XCTAssertEqual(value(fields, "충전 버퍼"), "5 (단위 미확인)")
    }

    func testEmptyScheduleListSaysThatEmptyMeansNoneOrNotSent() {
        let fields = StateCategoryFields.fields(preconditioningSchedule: ScheduleValues(), zone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(value(fields, "일정 수"), "0개 (없음 또는 차가 보내지 않음)")
        XCTAssertEqual(value(fields, "활성 일정"), "—")
        XCTAssertEqual(value(fields, "다음 일정 있음 (next_schedule)"), "—")
        XCTAssertEqual(value(fields, "지금 적용 중인 일정 (window)"), "—")
        XCTAssertTrue(StateCategoryFields.presentKeys(fields).isEmpty)
    }

    func testScheduleRowsNeverCarryTheScheduleNameOrLocation() {
        var v = ScheduleValues()
        v.count = 1; v.enabledCount = 1
        let fields = StateCategoryFields.fields(chargeSchedule: v, zone: TimeZone(identifier: "UTC")!)
        for f in fields {
            XCTAssertFalse(f.label.contains("이름") || f.label.contains("위치") || f.label.contains("위도"), f.label)
        }
    }

    func testParentalControlsShowActiveFlagsAndMphWhereNamed() {
        var v = ParentalValues()
        v.active = true; v.pinSet = false; v.speedLimitEnabled = true; v.currentLimitMph = 65; v.maxLimitMph = 90
        v.curfewEnabled = false; v.curfewStart = 0
        let fields = StateCategoryFields.fields(parental: v, zone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(value(fields, "자녀 보호 활성"), "예")
        XCTAssertEqual(value(fields, "PIN 설정됨"), "아니오")
        XCTAssertEqual(value(fields, "속도 제한 사용"), "예")
        XCTAssertEqual(value(fields, "현재 속도 제한"), "65 mph")
        XCTAssertEqual(value(fields, "최대 속도 제한"), "90 mph")
        XCTAssertEqual(value(fields, "최소 속도 제한"), "—")
        XCTAssertEqual(value(fields, "통금 사용"), "아니오")
        XCTAssertEqual(value(fields, "통금 시작"), "0 (단위 미확인)", "0 is a value")
    }

    // MARK: 로그: 필드 이름만

    func testPresentKeysListNamesOfFieldsThatCameAndNothingElse() {
        var v = ChargeValues()
        v.batteryLevel = 55; v.chargerVoltage = 0
        let keys = StateCategoryFields.presentKeys(StateCategoryFields.fields(charge: v, zone: TimeZone(identifier: "UTC")!))
        XCTAssertEqual(keys, ["battery_level", "charger_voltage"], "0 is present; absent fields are not listed")
    }

    func testLogLineHoldsPresentFieldNamesButNoValueForLocation() {
        let name = "SECRET-PLACE"
        var v = LocationValues()
        v.nativeLatitude = 37.123456; v.nativeLongitude = 127.654321; v.nativeType = "WGS"
        v.locationName = name; v.heading = 271; v.gpsAsOf = 1_700_000_000
        let reading = StateReading(
            category: .location, outcome: .success, at: Date(), durationMs: 210,
            fields: StateCategoryFields.fields(location: v)
        )
        let json = LogLine.stateQuery(ts: "00:00:02.000", entry: reading.logEntry, startedAt: "00:00:01.790").toJSON()
        XCTAssertTrue(json.contains("getState(LOCATION)"))
        XCTAssertTrue(json.contains("durationMs=210"))
        XCTAssertTrue(json.contains("present=[native_latitude, native_longitude, native_type, heading, gps_as_of, location_name]"), json)
        for secret in [name, "37.12", "127.65", "271", "1700000000"] {
            XCTAssertFalse(json.contains(secret), "no value may reach the log: \(secret)")
        }
    }

    func testLogLineOfAFailedQueryHasNoPresentList() {
        let entry = StateReading(category: .charge, outcome: .uncertain(.responseTooLarge), at: Date(), durationMs: 5, fields: []).logEntry
        XCTAssertEqual(entry.summary, "durationMs=5 error=ResponseTooLarge")
    }

    func testSuccessWithNoFieldNamesSaysSo() {
        let entry = StateReading(category: .charge, outcome: .success, at: Date(), durationMs: 5, fields: StateCategoryFields.fields(charge: ChargeValues())).logEntry
        XCTAssertEqual(entry.summary, "durationMs=5 present=[]", "an empty reply is told apart from a reply with fields")
    }

    func testReadingListsPresentKeysForTheScreen() {
        var v = ClimateValues()
        v.inside = 20
        let reading = StateReading(category: .climate, outcome: .success, at: Date(), durationMs: 1, fields: StateCategoryFields.fields(climate: v))
        XCTAssertEqual(reading.presentKeys, ["inside_temp_celsius"])
    }

    func testDriveAndMediaRowsAlsoReportTheirFieldNames() {
        let drive = StateDiagnostics.fields(drive: DriveValues(speed: 0, speedFloat: nil, shift: "P", power: nil, odometer: nil, timestamp: 1))
        XCTAssertEqual(StateCategoryFields.presentKeys(drive), ["speed", "shift_state", "timestamp"])
        let media = StateDiagnostics.fields(media: MediaValues(playback: "PLAYING", volume: nil, title: "곡", timestamp: nil))
        XCTAssertEqual(StateCategoryFields.presentKeys(media), ["media_playback_status", "now_playing_title"])
    }
}
