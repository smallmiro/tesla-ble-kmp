import Foundation
import Teslable

// `getState` 의 나머지 9개 범주: `VehicleData` 의 메시지를 `StateCategoryFields.swift` 의 `...Values` 로 옮긴다(SFR-023).
// 숫자와 불리언은 `KotlinInt?`, `KotlinBoolean?` 등으로 온다. 차가 보내지 않았으면 nil 이고 0 이나 false 로 바꾸지 않는다
// (`14-lib-reading-state.md` A8.3, 17 §Swift: generated types). 차량 없이는 시험하지 못하는 이음매라 값 규칙은 `StateCategoryFields` 에 있고,
// 여기는 필드를 옮기는 일만 한다.
//
// 읽지 않는 것: `DriveState` 의 위치·경로, 일정(`ChargeSchedule`, `PreconditionSchedule`)의 이름·위치. 일정은 개수와 켜짐 여부만 센다.
// `LOCATION` 의 좌표와 위치 이름은 읽지만 화면에만 가고 로그에는 필드 이름만 간다.
extension StateFields {
    private static func int(_ n: KotlinInt?) -> Int? { n.map { Int($0.int32Value) } }
    private static func long(_ n: KotlinLong?) -> Int64? { n.map { $0.int64Value } }
    private static func float(_ n: KotlinFloat?) -> Float? { n.map { $0.floatValue } }
    private static func bool(_ n: KotlinBoolean?) -> Bool? { n.map { $0.boolValue } }
    private static func seconds(_ t: Wire_runtimeInstant?) -> Int64? { t.map { $0.getEpochSecond() } }

    /// oneof 메시지(`Void` 멤버들)에서 값이 있는 멤버의 이름. 멤버가 하나도 없으면 nil.
    private static func member(_ pairs: [(String, AnyObject?)]) -> String? {
        pairs.first { $0.1 != nil }?.0
    }

    static func chargeValues(_ c: ChargeState?) -> ChargeValues {
        guard let c else { return ChargeValues() }
        return ChargeValues(
            batteryLevel: int(c.battery_level), usableBatteryLevel: int(c.usable_battery_level),
            batteryRange: float(c.battery_range), estBatteryRange: float(c.est_battery_range), idealBatteryRange: float(c.ideal_battery_range),
            chargingState: c.charging_state.flatMap { s in
                member([
                    ("Unknown", s.Unknown), ("Disconnected", s.Disconnected), ("NoPower", s.NoPower), ("Starting", s.Starting),
                    ("Charging", s.Charging), ("Complete", s.Complete), ("Stopped", s.Stopped), ("Calibrating", s.Calibrating),
                ])
            },
            chargerPower: int(c.charger_power), chargerVoltage: int(c.charger_voltage),
            chargerActualCurrent: int(c.charger_actual_current), chargerPilotCurrent: int(c.charger_pilot_current),
            minutesToFullCharge: int(c.minutes_to_full_charge), chargeLimitSoc: int(c.charge_limit_soc),
            chargePortDoorOpen: bool(c.charge_port_door_open),
            chargePortLatch: c.charge_port_latch.flatMap { l in
                member([("SNA", l.SNA), ("Disengaged", l.Disengaged), ("Engaged", l.Engaged), ("Blocking", l.Blocking)])
            },
            timestamp: seconds(c.timestamp)
        )
    }

    static func climateValues(_ c: ClimateState?) -> ClimateValues {
        guard let c else { return ClimateValues() }
        return ClimateValues(
            inside: float(c.inside_temp_celsius), outside: float(c.outside_temp_celsius),
            driverSet: float(c.driver_temp_setting), passengerSet: float(c.passenger_temp_setting),
            climateOn: bool(c.is_climate_on), autoConditioningOn: bool(c.is_auto_conditioning_on), preconditioning: bool(c.is_preconditioning),
            batteryHeater: bool(c.battery_heater), fanStatus: int(c.fan_status),
            frontDefroster: bool(c.is_front_defroster_on), rearDefroster: bool(c.is_rear_defroster_on),
            seatHeaterLeft: int(c.seat_heater_left), seatHeaterRight: int(c.seat_heater_right),
            keeperMode: c.climate_keeper_mode.flatMap { k in
                member([("Unknown", k.Unknown), ("Off", k.Off), ("On", k.On), ("Dog", k.Dog), ("Party", k.Party)])
            },
            timestamp: seconds(c.timestamp)
        )
    }

    static func locationValues(_ l: LocationState?) -> LocationValues {
        guard let l else { return LocationValues() }
        return LocationValues(
            latitude: float(l.latitude), longitude: float(l.longitude),
            nativeLatitude: float(l.native_latitude), nativeLongitude: float(l.native_longitude),
            nativeType: l.native_type.flatMap { member([("GCJ", $0.GCJ), ("WGS", $0.WGS)]) },
            correctedLatitude: float(l.corrected_latitude), correctedLongitude: float(l.corrected_longitude),
            geoLatitude: float(l.geo_latitude), geoLongitude: float(l.geo_longitude),
            estimatedToRawDistance: float(l.estimated_to_raw_distance), estimatedGpsValid: bool(l.estimated_gps_valid),
            heading: int(l.heading), gpsAsOf: long(l.gps_as_of), locationName: l.location_name,
            homelinkNearby: bool(l.homelink_nearby), timestamp: seconds(l.timestamp)
        )
    }

    static func closuresValues(_ c: ClosuresState?) -> ClosuresValues {
        guard let c else { return ClosuresValues() }
        return ClosuresValues(
            doorDriverFront: bool(c.door_open_driver_front), doorDriverRear: bool(c.door_open_driver_rear),
            doorPassengerFront: bool(c.door_open_passenger_front), doorPassengerRear: bool(c.door_open_passenger_rear),
            trunkFront: bool(c.door_open_trunk_front), trunkRear: bool(c.door_open_trunk_rear),
            windowDriverFront: bool(c.window_open_driver_front), windowDriverRear: bool(c.window_open_driver_rear),
            windowPassengerFront: bool(c.window_open_passenger_front), windowPassengerRear: bool(c.window_open_passenger_rear),
            sunRoof: c.sun_roof_state.flatMap { s in
                member([
                    ("Unknown", s.Unknown), ("Calibrating", s.Calibrating), ("Closed", s.Closed),
                    ("Open", s.Open), ("Moving", s.Moving), ("Vent", s.Vent),
                ])
            },
            sunRoofPercent: int(c.sun_roof_percent_open), locked: bool(c.locked),
            sentry: c.sentry_mode_state.flatMap { s in
                member([("Off", s.Off), ("Idle", s.Idle), ("Armed", s.Armed), ("Aware", s.Aware), ("Panic", s.Panic), ("Quiet", s.Quiet)])
            },
            sentryAvailable: bool(c.sentry_mode_available), userPresent: bool(c.is_user_present),
            timestamp: seconds(c.timestamp)
        )
    }

    static func tireValues(_ t: TirePressureState?) -> TireValues {
        guard let t else { return TireValues() }
        return TireValues(
            fl: WheelValues(
                pressure: float(t.tpms_pressure_fl), lastSeen: seconds(t.tpms_last_seen_pressure_time_fl),
                hard: bool(t.tpms_hard_warning_fl), soft: bool(t.tpms_soft_warning_fl)
            ),
            fr: WheelValues(
                pressure: float(t.tpms_pressure_fr), lastSeen: seconds(t.tpms_last_seen_pressure_time_fr),
                hard: bool(t.tpms_hard_warning_fr), soft: bool(t.tpms_soft_warning_fr)
            ),
            rl: WheelValues(
                pressure: float(t.tpms_pressure_rl), lastSeen: seconds(t.tpms_last_seen_pressure_time_rl),
                hard: bool(t.tpms_hard_warning_rl), soft: bool(t.tpms_soft_warning_rl)
            ),
            rr: WheelValues(
                pressure: float(t.tpms_pressure_rr), lastSeen: seconds(t.tpms_last_seen_pressure_time_rr),
                hard: bool(t.tpms_hard_warning_rr), soft: bool(t.tpms_soft_warning_rr)
            ),
            rcpFront: float(t.tpms_rcp_front_value), rcpRear: float(t.tpms_rcp_rear_value), timestamp: seconds(t.timestamp)
        )
    }

    static func updateValues(_ u: SoftwareUpdateState?) -> SoftwareUpdateValues {
        guard let u else { return SoftwareUpdateValues() }
        return SoftwareUpdateValues(
            status: u.status.flatMap { s in
                member([
                    ("Unknown", s.Unknown), ("Installing", s.Installing), ("Scheduled", s.Scheduled), ("Available", s.Available),
                    ("DownloadingWifiWait", s.DownloadingWifiWait), ("Downloading", s.Downloading),
                ])
            },
            version: u.version, downloadPerc: int(u.download_perc), installPerc: int(u.install_perc),
            scheduledTimeMs: long(u.scheduled_time_ms), expectedDurationSec: int(u.expected_duration_sec),
            timestamp: seconds(u.timestamp)
        )
    }

    static func chargeScheduleValues(_ c: ChargeScheduleState?) -> ScheduleValues {
        guard let c else { return ScheduleValues() }
        return ScheduleValues(
            count: c.charge_schedules.count,
            enabledCount: c.charge_schedules.filter { $0.enabled }.count,
            oneTimeCount: c.charge_schedules.filter { $0.one_time }.count,
            hasWindow: c.charge_schedule_window != nil, nextSchedule: bool(c.next_schedule),
            maxCount: int(c.max_num_charge_schedules), buffer: int(c.charge_buffer),
            showComplete: bool(c.show_schedule_complete_state), timestamp: seconds(c.timestamp)
        )
    }

    static func preconditioningValues(_ p: PreconditioningScheduleState?) -> ScheduleValues {
        guard let p else { return ScheduleValues() }
        return ScheduleValues(
            count: p.precondition_schedules.count,
            enabledCount: p.precondition_schedules.filter { $0.enabled }.count,
            oneTimeCount: p.precondition_schedules.filter { $0.one_time }.count,
            hasWindow: p.preconditioning_schedule_window != nil, nextSchedule: bool(p.next_schedule),
            maxCount: int(p.max_num_precondition_schedules), timestamp: seconds(p.timestamp)
        )
    }

    static func parentalValues(_ p: ParentalControlsState?) -> ParentalValues {
        guard let p else { return ParentalValues() }
        let s = p.parental_controls_settings
        return ParentalValues(
            active: bool(p.parental_controls_active), pinSet: bool(p.parental_controls_pin_set), hasSettings: s != nil,
            speedLimitEnabled: bool(s?.speed_limit_enabled),
            maxLimitMph: float(s?.max_limit_mph), minLimitMph: float(s?.min_limit_mph), currentLimitMph: float(s?.current_limit_mph),
            chillAcceleration: bool(s?.chill_acceleration_enabled), requireSafetySettings: bool(s?.require_safety_settings_enabled),
            curfewEnabled: bool(s?.curfew_enabled), curfewStart: int(s?.curfew_start_time), curfewEnd: int(s?.curfew_end_time),
            timestamp: seconds(p.timestamp)
        )
    }
}
