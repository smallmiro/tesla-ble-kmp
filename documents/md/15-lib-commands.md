# Commands

Scope: on `main` the session calls are `startSession()` and `disconnect()`, the 12 VCSEC control commands (M4) and the
51 Infotainment commands of M5 (climate, charging and power, body, security, media, software update, `ping()`) are
implemented. The Infotainment query `getState(category)` is in `14-lib-reading-state.md` A8. Go original for every
command: `05-command-catalog.md`.

## Implemented

| Call | Returns | Notes |
|---|---|---|
| `startSession(domains = VehicleDomain.ALL, timeout = null)` | `VehicleResult<Unit>` | Handshake per domain, in parallel; one failure cancels the others (FR-012). Domains already `Ready` from the session cache succeed without a request. Needs a key (`RequiresKey`) that is enrolled (`KeyNotPaired`). A key the phone lock blocks (iOS `WHEN_UNLOCKED` Secure Enclave key read before the lock) fails right after the first reply with `KeyStoreError.Locked`, not retried (FR-037, `13-lib-pairing.md` §Key accessibility). `timeout = null` uses `handshakeTimeout` (20 s) |
| `disconnect()` | `Unit` | Saves the session cache, wipes session keys, closes the link. Idempotent (FR-005) |

`sessions` reports `Handshaking` while `startSession` runs, then `Ready(fromCache = false)` or back to `None` on failure,
cancellation or disconnect.

## Rules every control command follows

These are implemented in the dispatcher (M2) and apply to every command:

- Authenticated with AES-GCM over the domain session; `expires_at` lifetime is `commandLifetime` (5 s).
- No command starts a session or wakes the vehicle. Without a session for its domain it returns `Failure(NoSession)` and
  sends nothing.
- Retried automatically at 1 s intervals while `error.shouldRetry()`; never resent when `mayHaveSucceeded`.
- After a failed write that the library repeats with the same bytes, a command is never authorized again: a fault for
  the repeated copy (a retriable one, or any protocol-layer rejection such as `KeyNotPaired`) is not taken as the result,
  and the call returns `Uncertain(Timeout(afterSend = true))` unless a reply that settles it arrives within `timeout`:
  the first copy's late reply, or the repeated copy's success when the first never reached the vehicle
  (`16-lib-errors.md` §Retries and timeouts).
- VCSEC commands are serialized per connection; Infotainment commands may run in parallel.
- An `Uncertain` result means "query state, do not resend" (`16-lib-errors.md`).

## Implemented: VCSEC (M4)

All VCSEC commands are authenticated with AES-GCM over the VCSEC session. Without a VCSEC session they return
`Failure(NoSession)`: the app runs `startSession(setOf(VehicleDomain.VCSEC))` first. Success is a reply without
`commandStatus`. `timeout = null` uses `commandTimeout` (5 s) for every method.

| Method | FR | Payload (`UnsignedMessage`, hex) | Notes |
|---|---|---|---|
| `lock()` | FR-040 | `1001` | `RKEAction` LOCK |
| `unlock()` | FR-041 | `1000` | `RKEAction` UNLOCK |
| `wakeUp()` | FR-042 | `101e` | `RKEAction` WAKE_VEHICLE. Needs the VCSEC session only (Go `wakeupRKE`) |
| `remoteDrive()` | FR-043 | `1014` | `RKEAction` REMOTE_DRIVE |
| `autoSecureVehicle()` | FR-044 | `101d` | `RKEAction` AUTO_SECURE_VEHICLE (Model X) |
| `openTrunk()` | FR-045 | `22022801` | Same payload as `actuateTrunk()`: a **toggle** (`rearTrunk = MOVE`) |
| `actuateTrunk()` | FR-045 | `22022801` | Toggle |
| `closeTrunk()` | FR-045 | `22022804` | `rearTrunk = CLOSE`. Powered trunks only |
| `openFrunk()` | FR-046 | `22023001` | `frontTrunk = MOVE`. There is no remote close |
| `openTonneau()` | FR-047 | `22024003` | Cybertruck. `tonneau = OPEN` |
| `closeTonneau()` | FR-047 | `22024004` | Cybertruck. `tonneau = CLOSE` |
| `stopTonneau()` | FR-047 | `22024002` | Cybertruck. `tonneau = STOP` |
| Key enrollment and removal | FR-022–027 | | See `13-lib-pairing.md` |

All return `VehicleResult<Unit>`. Failures to expect: `NoSession`, `NotConnected`, `InsufficientPrivileges` (for example
a Vehicle Monitor key, or the vehicle state forbids it), `VcsecRejected(error)`, `UnknownVcsecError(rawCode)`.

`Uncertain` (timeout after the request's first BLE write was started, including after a failed write the library
repeated, unreadable reply) means the vehicle may have acted. **Do not resend**; read
`vehicleStatus()` to see the lock state and closures (`16-lib-errors.md` §Per-command re-query). This matters most for
the toggles `openTrunk()` / `actuateTrunk()`.

`sessions` follows the real session state: after a command ends with `NoSession`, or when a session event arrives
(refreshed session info, a stale cache entry), the library re-checks the domain and `sessions` shows `Ready` or `None`
accordingly. A session restored from the cache that the vehicle no longer accepts (for example the cache was stored for
another vehicle's key) shows `None` after the first command fails with `NoSession`; `startSession` recovers it.

## Implemented: Infotainment (M5)

All 51 methods (climate 12, charging and power 19, body 5, security 4, media 8, software update 2, `ping()`) are
authenticated with AES-GCM over the INFOTAINMENT session, return `VehicleResult<Unit>` and take
`timeout: Duration? = null` last (`null` uses `commandTimeout`, 5 s). The success reply's content is dropped, as in Go.
They are checked with FakeVehicle tests; **none is confirmed on a real vehicle yet** (#118).

### Call order

1. An `INFOTAINMENT` session: `startSession(setOf(VehicleDomain.INFOTAINMENT))`, or both domains. Without it a command
   returns `Failure(NoSession)` and sends nothing.
2. An awake vehicle. The library does **not** wake it: when Infotainment sleeps, the command is sent, nothing comes back,
   and the result is `Uncertain(Timeout(afterSend = true))`. Read `vehicleStatus()` first (no session, never wakes the
   vehicle); if it is `ASLEEP`, wake it as in `14-lib-reading-state.md` A8.2 (`startSession(setOf(VehicleDomain.VCSEC))`,
   `wakeUp()`, wait for `AWAKE`) and start the INFOTAINMENT session.
3. Send the command and branch on the result:

| Result | Meaning | App action |
|---|---|---|
| `Success(Unit)` | The vehicle accepted the command | To show the new state, read it with `getState` (§Re-query) |
| `Uncertain(error)` | The vehicle may have run it: no reply in time (Infotainment asleep, reply lost), a failed write the library repeated, an unreadable reply | **Never resend.** Read the state with `getState` (§Re-query) and decide from what it shows. `setLowPowerMode` and `setKeepAccessoryPowerMode` cannot be read back |
| `Failure(InvalidArgument(detail))` | A local check failed and nothing was sent (§Local checks) | Fix the argument |
| `Failure(InfotainmentRejected(reason))` | The vehicle refused (`actionStatus ERROR`) | Show a generic message. `reason` is the vehicle's own text; do not log it |
| `Failure(NoSession)`, `Failure(NotConnected)`, other errors | Call order or link | `16-lib-errors.md` |

### Local checks

Only the checks the Go original has. They run before the session check, so a bad argument returns `InvalidArgument`
even without a session, and nothing is sent:

| Method | Rule | `InvalidArgument.detail` |
|---|---|---|
| `setSeatCooler` | `seat` is `FRONT_LEFT` or `FRONT_RIGHT` | `invalid seat position for seat cooler: <SEAT>` (for example `…: SECOND_ROW_LEFT`) |
| `scheduleDeparture` | `departAtMinutes` is in `0..1440` (both ends allowed) | `invalid departure time: <n> minutes after midnight (expected 0..1440)` |
| `enableValetMode` | `pin` is exactly four ASCII digits `0`–`9` (other scripts' digits and full-width digits are rejected) | `PIN codes must be four digits` (Go `ErrInvalidPIN`; the PIN itself is never in an error or a log) |
| `setVolume` | `volume` is in `0..10` and not NaN | `invalid volume (should be in [0, 10])` (Go's text; Go lets NaN through, this library rejects it) |

Everything else is sent as given and the vehicle decides: temperatures, charge limit, amps, the other times, schedule
fields, the sunroof level, the software update delay. `autoSeatAndClimate` drops seats the Go original does not handle,
without an error (§Climate).

### Parameter types

| Type | Values | Used by |
|---|---|---|
| `SeatPosition` | `UNKNOWN`, `FRONT_LEFT`, `FRONT_RIGHT`, `SECOND_ROW_LEFT`, `SECOND_ROW_LEFT_BACK`, `SECOND_ROW_CENTER`, `SECOND_ROW_RIGHT`, `SECOND_ROW_RIGHT_BACK`, `THIRD_ROW_LEFT`, `THIRD_ROW_RIGHT` (Go `SeatPosition` order; `_BACK` is the backrest, heated on some Model S only) | `setSeatHeater` (all), `setSeatCooler` (front seats), `autoSeatAndClimate` (`UNKNOWN`, `FRONT_LEFT`, `FRONT_RIGHT`) |
| `SeatClimateLevel` | `OFF`, `LOW`, `MED`, `HIGH` (Go `Level`) | `setSeatHeater`, `setSeatCooler` |
| `ClimateKeeperMode` | `OFF`, `ON`, `DOG`, `CAMP` | `setClimateKeeperMode` |
| `CabinOverheatProtectionTemperature` | `UNSPECIFIED`, `LOW`, `MEDIUM`, `HIGH` (wire values 0–3 of `ClimateState.CopActivationTemp`) | `setCabinOverheatProtectionTemperature` |
| `ChargingPolicy` | `OFF` (that part is not sent), `ALL_DAYS`, `WEEKDAYS` | `scheduleDeparture` |
| `ChargeSchedule`, `PreconditionSchedule` | Wire generated types in `com.tesla.generated.carserver.common`, sent as given (ADR-0002, revised 2026-11-08). `id` is the uint64 in a `Long`; an id above `Long.MAX_VALUE` is a negative `Long` | `addChargeSchedule`, `addPreconditionSchedule` |

The other commands take plain values: `level: Int` (sunroof), `enabled: Boolean`, `pin: String`, `volume: Float`,
`delaySeconds: Int`.

Times are minutes after midnight in the vehicle's local time: `minutesAfterMidnight`, `departAtMinutes`,
`offPeakEndMinutes`, and the schedules' `start_time`, `end_time`, `precondition_time`. `days_of_week` is a bitmask; the
Go CLI (`cmd/tesla-control/commands.go` `dayNamesBitMask`) uses Sunday 1, Monday 2, Tuesday 4, Wednesday 8, Thursday 16,
Friday 32, Saturday 64 (every day 127, weekdays 62). The Go CLI uses the current Unix time as a new schedule's `id`.

### Climate

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `climateOn()` | FR-060 | `120452020801` | `hvacAutoAction{power_on: true}`; the protocol.md example |
| `climateOff()` | FR-060 | `12025200` | Empty `hvacAutoAction` member |
| `setClimateTemp(driverCelsius: Float, passengerCelsius: Float)` | FR-062 | `(22.0, 21.5)` → `1210720e2a021a00350000b0413d0000ac41` | Celsius, no range check. Always carries `level{TEMP_MAX}`, as in Go |
| `setSeatHeater(levels: Map<SeatPosition, SeatClimateLevel>)` | FR-061 | `{FRONT_LEFT: HIGH}` → `1209a202060a042a003a00` | One entry per seat, sent in `SeatPosition` declaration order whatever the map order; an empty map is sent |
| `setSeatCooler(level: SeatClimateLevel, seat: SeatPosition)` | FR-063 | `(LOW, FRONT_RIGHT)` → `12098a03060a0408021002` | Front seats only (§Local checks). The wire level is Go's `level + 1` |
| `setSteeringWheelHeater(enabled: Boolean)` | FR-064 | `true` → `12046a020801` | |
| `autoSeatAndClimate(seats: List<SeatPosition>, enabled: Boolean)` | FR-065 | `([FRONT_RIGHT, UNKNOWN], false)` → `12098203060a0210020a00` | Keeps `UNKNOWN`, `FRONT_LEFT`, `FRONT_RIGHT` in order with duplicates and drops the other seats; sent even when no seat is left |
| `setPreconditioningMax(enabled: Boolean, manualOverride: Boolean)` | FR-066 | `(true, true)` → `1206620408011001` | |
| `setClimateKeeperMode(mode: ClimateKeeperMode, manualOverride: Boolean)` | FR-067 | `(DOG, true)` → `1207e2020408021001` | |
| `setBioweaponDefenseMode(enabled: Boolean, manualOverride: Boolean)` | FR-068 | `(true, false)` → `12059a02020801` | |
| `setCabinOverheatProtection(enabled: Boolean, fanOnly: Boolean)` | FR-069 | `(true, true)` → `120792030408011001` | |
| `setCabinOverheatProtectionTemperature(temperature: CabinOverheatProtectionTemperature)` | FR-069 | `HIGH` → `12059204020803` | |

### Charging and power

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `chargeStart()` | FR-070 | `120432021200` | `chargingStartStopAction{start}` |
| `chargeStop()` | FR-070 | `120432022a00` | `chargingStartStopAction{stop}` |
| `setChargeLimit(percent: Int)` | FR-071 | `80` → `12042a020850` | No range check |
| `setChargingAmps(amps: Int)` | FR-072 | `32` → `1205da02020820` | No range check |
| `chargeStandardRange()` | FR-073 | `120432021a00` | `chargingStartStopAction{start_standard}` |
| `chargeMaxRange()` | FR-073 | `120432022200` | `chargingStartStopAction{start_max_range}` |
| `scheduleCharging(enabled: Boolean, minutesAfterMidnight: Int)` | FR-074 | `(true, 120)` → `1207ca020408011078` | No check of the time |
| `scheduleDeparture(departAtMinutes: Int, offPeakEndMinutes: Int, preconditioning: ChargingPolicy, offPeak: ChargingPolicy)` | FR-075 | `(480, 360, ALL_DAYS, WEEKDAYS)` → `1213d20210080110e0031a020a002202120028e802` | `enabled` is always true. `departAtMinutes` must be `0..1440`; `offPeakEndMinutes` is not checked. An `OFF` policy leaves its part out |
| `clearScheduledDeparture()` | FR-075 | `1203d20200` | `enabled = false` only |
| `setLowPowerMode(enabled: Boolean)` | FR-076 | `true` → `12059208020801` | Cannot be read back (§Re-query) |
| `setKeepAccessoryPowerMode(enabled: Boolean)` | FR-077 | `true` → `1205d208020801` | Cannot be read back (§Re-query) |
| `addChargeSchedule(schedule: ChargeSchedule)` | FR-078 | `ChargeSchedule(id = 1, enabled = true)` → `12078a060408014801` | Sent as given |
| `removeChargeSchedule(id: Long)` | FR-079 | `1_700_000_000` → `12099206060880e2cfaa06` | |
| `batchRemoveChargeSchedules(home: Boolean, work: Boolean, other: Boolean)` | FR-080 | `(true, false, true)` → `1207e2060408011801` | `other` = schedules that are neither home nor work |
| `addPreconditionSchedule(schedule: PreconditionSchedule)` | FR-081 | `PreconditionSchedule(id = 2, enabled = true)` → `12079a060408023001` | Sent as given |
| `removePreconditionSchedule(id: Long)` | FR-082 | `2` → `1205a206020802` | |
| `batchRemovePreconditionSchedules(home: Boolean, work: Boolean, other: Boolean)` | FR-083 | `(false, true, true)` → `1207da060410011801` | |
| `openChargePort()` | FR-052 | `1203f20300` | Go `OpenChargePort` (= `ChargePortOpen`) |
| `closeChargePort()` | FR-052 | `1203ea0300` | Go `CloseChargePort` (= `ChargePortClose`) |

### Body

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `ventWindows()` | FR-050 | `12059202021a00` | `vehicleControlWindowAction{vent}` |
| `closeWindows()` | FR-050 | `12059202022200` | `vehicleControlWindowAction{close}` |
| `setSunroof(level: Int)` | FR-051 | `0` → `12058202020800` | Absolute level, no check. `0` is still sent (`absolute_level` is a oneof member) |
| `honkHorn()` | FR-053 | `1203da0100` | Cannot be read back (§Re-query) |
| `flashLights()` | FR-053 | `1203d20100` | Cannot be read back (§Re-query) |

### Security

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `setSentryMode(enabled: Boolean)` | FR-084 | `true` → `1205f201020801` | |
| `enableValetMode(pin: String)` | FR-085 | `"1234"` → `120bfa01080801120431323334` | The PIN must be four ASCII digits (§Local checks). The library never logs it; the app should not either |
| `disableValetMode()` | FR-085 | `1203fa0100` | A refusal whose reason ends with `already off` (case-sensitive) is `Success`: valet mode is off already. Go checks that suffix on any error; the library on the vehicle's refusal only |
| `resetValetPin()` | FR-085 | `1203e20100` | |

### Media

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `volumeUp()` | FR-090 | `12058201020802` | `volume_delta` +1 (sint32) |
| `volumeDown()` | FR-090 | `12058201020801` | `volume_delta` −1 |
| `setVolume(volume: Float)` | FR-090 | `5.5` → `12088201051d0000b040` | `0..10`, NaN refused (§Local checks). `0` is still sent |
| `mediaNextTrack()` | FR-091 | `12039a0100` | |
| `mediaPreviousTrack()` | FR-091 | `1203a20100` | |
| `mediaNextFavorite()` | FR-091 | `12038a0100` | |
| `mediaPreviousFavorite()` | FR-091 | `1203920100` | |
| `toggleMediaPlayback()` | FR-091 | `12027a00` | A **toggle**: run twice, it ends where it started. After `Uncertain` never resend; read `media_playback_status` |

### Software update

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `scheduleSoftwareUpdate(delaySeconds: Int)` | FR-092 | `7200` → `1206ea010308a038` | Starts the update after `delaySeconds`; no check. Go takes a `time.Duration` and truncates it to seconds |
| `cancelSoftwareUpdate()` | FR-092 | `1203ca0100` | |

### Ping

| Method | FR | Example payload (`CarServer.Action`, hex) | Notes |
|---|---|---|---|
| `ping()` | FR-034 | `1205f202020801` | `ping{ping_id: 1}`, as Go. The reply's `Ping` is not inspected |

`ping()` changes nothing and reads nothing. `Success` means the vehicle knows the key and Infotainment answered, which
makes it a cheap check before a series of commands. It needs the same INFOTAINMENT session and awake vehicle as every
command; `Uncertain(Timeout(afterSend = true))` means no reply (asleep or lost). Because it changes nothing, the app may
call it again after `Uncertain`; the library never resends it by itself. To read data, use `getState`.

### Re-query

After `Uncertain`, never resend: read the state the command changes. The field names are the official proto names; which
field a command changes on a real vehicle is not confirmed yet (#118). `getState` itself can come back `Uncertain` (asleep,
reply too large): then the command's result stays unknown until a later read succeeds.

| Commands | Read with | Look at |
|---|---|---|
| `climateOn`, `climateOff` | `getState(StateCategory.CLIMATE)` | `climate_state.is_climate_on` |
| `setClimateTemp` | same | `driver_temp_setting`, `passenger_temp_setting` |
| `setSeatHeater`, `setSeatCooler` | same | `seat_heater_*`, `seat_fan_front_left` / `_right` |
| `setSteeringWheelHeater`, `autoSeatAndClimate` | same | `steering_wheel_heater`, `auto_seat_climate_left` / `_right` |
| `setClimateKeeperMode`, `setBioweaponDefenseMode` | same | `climate_keeper_mode`, `bioweapon_mode_on` (cars with a HEPA filter) |
| `setCabinOverheatProtection`, `setCabinOverheatProtectionTemperature` | same | `cabin_overheat_protection`, `cop_activation_temperature` |
| `setPreconditioningMax` | same | The climate state; no single field is identified |
| `chargeStart`, `chargeStop`, `chargeStandardRange`, `chargeMaxRange` | `getState(StateCategory.CHARGE)` | `charge_state.charging_state`, `charge_limit_soc` |
| `setChargeLimit`, `setChargingAmps` | same | `charge_limit_soc`, `charging_amps`, `charge_current_request` |
| `scheduleCharging` | same | `scheduled_charging_mode`, `scheduled_charging_start_time_minutes` |
| `scheduleDeparture`, `clearScheduledDeparture` | same | `scheduled_departure_time_minutes`, `preconditioning_enabled`, `off_peak_charging_times` |
| `openChargePort`, `closeChargePort` | same | `charge_port_door_open` |
| `addChargeSchedule`, `removeChargeSchedule`, `batchRemoveChargeSchedules` | `getState(StateCategory.CHARGE_SCHEDULE)` | `charge_schedule_state.charge_schedules` (the same `ChargeSchedule` type; look for the `id`) |
| `addPreconditionSchedule`, `removePreconditionSchedule`, `batchRemovePreconditionSchedules` | `getState(StateCategory.PRECONDITIONING_SCHEDULE)` | `preconditioning_schedule_state.precondition_schedules` |
| `ventWindows`, `closeWindows` | `getState(StateCategory.CLOSURES)` | `closures_state.window_open_driver_front` (and the other three windows) |
| `setSunroof` | same | `sun_roof_percent_open`, `sun_roof_state` |
| `setSentryMode` | same | `sentry_mode_state` |
| `enableValetMode`, `disableValetMode`, `resetValetPin` | same | `valet_mode`, `valet_pin_needed` |
| `volumeUp`, `volumeDown`, `setVolume` | `getState(StateCategory.MEDIA)` | `media_state.audio_volume` |
| `mediaNextTrack`, `mediaPreviousTrack`, `mediaNextFavorite`, `mediaPreviousFavorite` | same | `now_playing_title`, `now_playing_artist` |
| `toggleMediaPlayback` | same | `media_playback_status` (`Playing`, `Paused`, `Stopped`). **Never resend the toggle** |
| `scheduleSoftwareUpdate`, `cancelSoftwareUpdate` | `getState(StateCategory.SOFTWARE_UPDATE)` | `software_update_state.status`, `scheduled_time_ms` |
| `setLowPowerMode`, `setKeepAccessoryPowerMode`, `honkHorn`, `flashLights` | None | The official `VehicleData` has no field for these. Show the result as unknown; do not resend |
| `ping` | None | Nothing changed; call `ping()` again if needed |

Some of these fields are wrapper messages, not enums or numbers: `charging_state`, `off_peak_charging_times`,
`climate_keeper_mode`, `sun_roof_state`, `sentry_mode_state`, `status`. How to read them in Kotlin and Swift:
`14-lib-reading-state.md` A8.6.

### Kotlin example

```kotlin
import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.common.MediaPlaybackStatus
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.ChargingPolicy
import dev.mirolab.teslable.model.SeatClimateLevel
import dev.mirolab.teslable.model.SeatPosition
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult

// Before: an INFOTAINMENT session (startSession) and an awake vehicle (14-lib-reading-state.md A8.2).
suspend fun warmUp(vehicle: Vehicle): String {
    when (val result = vehicle.climateOn()) {
        is VehicleResult.Success -> Unit
        is VehicleResult.Uncertain -> {
            // The vehicle may have turned climate on. Never resend: read the state.
            val state = vehicle.getState(StateCategory.CLIMATE)
            val on = (state as? VehicleResult.Success)?.value?.climate_state?.is_climate_on
            return if (on == true) "climate on" else "unknown, read again later"
        }
        is VehicleResult.Failure -> return "not sent or refused: ${result.error.message}"
    }
    // Handle these results the same way.
    vehicle.setClimateTemp(driverCelsius = 22.0f, passengerCelsius = 21.5f)
    vehicle.setSeatHeater(mapOf(SeatPosition.FRONT_LEFT to SeatClimateLevel.HIGH, SeatPosition.FRONT_RIGHT to SeatClimateLevel.LOW))
    return "climate on"
}

suspend fun planCharging(vehicle: Vehicle, scheduleId: Long, latitude: Float, longitude: Float): String {
    // Leave at 08:00, off-peak charging ends at 06:00 (minutes after the vehicle's local midnight).
    when (val departure = vehicle.scheduleDeparture(480, 360, ChargingPolicy.ALL_DAYS, ChargingPolicy.WEEKDAYS)) {
        is VehicleResult.Success -> Unit
        is VehicleResult.Uncertain -> {
            // The vehicle may have stored it. Never resend: read the state and stop here.
            val state = vehicle.getState(StateCategory.CHARGE)
            val departAt = (state as? VehicleResult.Success)?.value?.charge_state?.scheduled_departure_time_minutes
            return if (departAt == 480) "departure set, schedule not added" else "unknown, read again later"
        }
        is VehicleResult.Failure ->
            return if (departure.error is VehicleError.InvalidArgument) {
                "departure time outside 0..1440 minutes; nothing was sent"
            } else {
                "not sent or refused: ${departure.error.message}"
            }
    }
    val schedule =
        ChargeSchedule(
            id = scheduleId, // the Go CLI uses the current Unix time
            name = "Home",
            days_of_week = 127, // every day
            start_enabled = true,
            start_time = 120, // 02:00
            enabled = true,
            latitude = latitude,
            longitude = longitude,
        )
    return when (val added = vehicle.addChargeSchedule(schedule)) {
        is VehicleResult.Success -> "departure and schedule set"
        is VehicleResult.Uncertain -> {
            // Never resend: look for the id in the charge schedules.
            val state = vehicle.getState(StateCategory.CHARGE_SCHEDULE)
            val schedules = (state as? VehicleResult.Success)?.value?.charge_schedule_state?.charge_schedules.orEmpty()
            if (schedules.any { it.id == scheduleId }) "departure and schedule set" else "schedule unknown, read again later"
        }
        is VehicleResult.Failure -> "schedule not sent or refused: ${added.error.message}"
    }
}

suspend fun togglePlayback(vehicle: Vehicle): String =
    when (val result = vehicle.toggleMediaPlayback()) {
        is VehicleResult.Success -> "toggled"
        is VehicleResult.Uncertain -> {
            // A toggle run twice ends where it started. Never resend: read the state and let the user decide.
            val state = vehicle.getState(StateCategory.MEDIA)
            when ((state as? VehicleResult.Success)?.value?.media_state?.media_playback_status) {
                MediaPlaybackStatus.Playing -> "playing"
                MediaPlaybackStatus.Paused, MediaPlaybackStatus.Stopped -> "not playing"
                null -> "unknown, read again later"
            }
        }
        is VehicleResult.Failure -> "not sent or refused: ${result.error.message}"
    }

suspend fun enterValetMode(vehicle: Vehicle, pin: String): String =
    when (val result = vehicle.enableValetMode(pin)) {
        is VehicleResult.Success -> "valet mode on"
        is VehicleResult.Uncertain -> {
            // The vehicle may have turned it on. Never resend: read the state.
            val state = vehicle.getState(StateCategory.CLOSURES)
            val on = (state as? VehicleResult.Success)?.value?.closures_state?.valet_mode
            if (on == true) "valet mode on" else "unknown, read again later"
        }
        is VehicleResult.Failure ->
            if (result.error is VehicleError.InvalidArgument) {
                "the PIN must be four digits" // the error never contains the PIN; do not log the PIN
            } else {
                "not sent or refused: ${result.error.message}"
            }
    }
```

### Swift example

Swift passes every argument, including `timeout:`; the full rules are in `17-lib-platform-notes.md` §Swift: command
arguments. This form passes a Swift typecheck against the framework; it has not run in a Swift program.

```swift
func warmUp(vehicle: Vehicle) async throws -> String {
    let result = try await vehicle.climateOn(timeout: nil)
    switch onEnum(of: result) {
    case .success:
        break
    case .uncertain:
        // The vehicle may have turned climate on. Never resend: read the state.
        let state = try await vehicle.getState(category: .climate, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           data.climate_state?.is_climate_on?.boolValue == true {
            return "climate on"
        }
        return "unknown, read again later"
    case .failure(let f):
        return "not sent or refused: \(f.error.message)"
    }
    _ = try await vehicle.setClimateTemp(driverCelsius: 22.0, passengerCelsius: 21.5, timeout: nil)
    _ = try await vehicle.setSeatHeater(levels: [.frontLeft: .high, .frontRight: .low], timeout: nil)
    return "climate on"
}

func planCharging(vehicle: Vehicle, scheduleId: Int64, latitude: Float, longitude: Float) async throws -> String {
    // Leave at 08:00, off-peak charging ends at 06:00 (minutes after the vehicle's local midnight).
    let departure = try await vehicle.scheduleDeparture(
        departAtMinutes: 480, offPeakEndMinutes: 360, preconditioning: .allDays, offPeak: .weekdays, timeout: nil)
    switch onEnum(of: departure) {
    case .success:
        break
    case .uncertain:
        // The vehicle may have stored it. Never resend: read the state and stop here.
        let state = try await vehicle.getState(category: .charge, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           data.charge_state?.scheduled_departure_time_minutes?.int32Value == 480 {
            return "departure set, schedule not added"
        }
        return "unknown, read again later"
    case .failure(let f):
        if case .invalidArgument = onEnum(of: f.error) {
            return "departure time outside 0..1440 minutes; nothing was sent"
        }
        return "not sent or refused: \(f.error.message)"
    }
    let schedule = ChargeSchedule(
        id: scheduleId, name: "Home", days_of_week: 127, start_enabled: true, start_time: 120,
        end_enabled: false, end_time: 0, one_time: false, enabled: true,
        latitude: latitude, longitude: longitude, unknownFields: OkioByteString.companion.EMPTY)
    let added = try await vehicle.addChargeSchedule(schedule: schedule, timeout: nil)
    switch onEnum(of: added) {
    case .success:
        return "departure and schedule set"
    case .uncertain:
        // Never resend: look for the id in the charge schedules.
        let state = try await vehicle.getState(category: .chargeSchedule, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           (data.charge_schedule_state?.charge_schedules ?? []).contains(where: { $0.id == scheduleId }) {
            return "departure and schedule set"
        }
        return "schedule unknown, read again later"
    case .failure(let f):
        return "schedule not sent or refused: \(f.error.message)"
    }
}

func togglePlayback(vehicle: Vehicle) async throws -> String {
    let result = try await vehicle.toggleMediaPlayback(timeout: nil)
    switch onEnum(of: result) {
    case .success:
        return "toggled"
    case .uncertain:
        // A toggle run twice ends where it started. Never resend: read the state and let the user decide.
        let state = try await vehicle.getState(category: .media, timeout: nil)
        guard case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
              let status = data.media_state?.media_playback_status else { return "unknown, read again later" }
        return status == .playing ? "playing" : "not playing"
    case .failure(let f):
        return "not sent or refused: \(f.error.message)"
    }
}

func enterValetMode(vehicle: Vehicle, pin: String) async throws -> String {
    let result = try await vehicle.enableValetMode(pin: pin, timeout: nil)
    switch onEnum(of: result) {
    case .success:
        return "valet mode on"
    case .uncertain:
        // The vehicle may have turned it on. Never resend: read the state.
        let state = try await vehicle.getState(category: .closures, timeout: nil)
        if case .success(let s) = onEnum(of: state), let data = s.value as? VehicleData,
           data.closures_state?.valet_mode?.boolValue == true {
            return "valet mode on"
        }
        return "unknown, read again later"
    case .failure(let f):
        if case .invalidArgument = onEnum(of: f.error) {
            return "the PIN must be four digits" // the error never contains the PIN; do not log the PIN
        }
        return "not sent or refused: \(f.error.message)"
    }
}
```

## Deferred to v1.1

P2 items: FR-054 `triggerHomelink`, FR-086 `clearPinToDrive`, FR-087 speed limit, FR-088 parental
controls, FR-089 guest mode, FR-093 `setVehicleName`, FR-094 `getNearbyCharging`. `SetPINToDrive` is not possible over
BLE and is excluded.
