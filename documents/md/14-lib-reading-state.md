# Reading Vehicle State

Scope: everything the library can read from the vehicle over BLE, in two parts.

- **Part A, on demand:** data you request. The four VCSEC queries on `Vehicle` (`vehicleStatus()`, `keySummary()`,
  `listKeys()`, `sessionInfo()`), with wire format, field tables and enum tables. None of the four needs a key, a session
  or a wake-up: VCSEC answers over BLE while Infotainment is asleep. A8 adds the Infotainment query `getState(category)`,
  which does need an enrolled key, an Infotainment session and an awake vehicle.
- **Part B, broadcast:** data the vehicle pushes without a request. What each message is, how often it came, and what is
  confirmed on a real car. The library delivers the plain VCSEC broadcasts through `Vehicle.events` (`Flow<VehicleEvent>`,
  FR-035, B2); they still never reach a pending request, and the observation log still shows them.

**`getState()` is available** (A8), ahead of the rest of M5. It is checked with FakeVehicle tests, not yet on a real
vehicle (A8.5, #118). Go original: VCSEC queries in `05-command-catalog.md` §B and `03-protocol.md` §11–12; `getState` in
`05-command-catalog.md` §I and `03-protocol.md` §10, §13; transport in `02-ble-transport.md`. Control payloads (lock, trunk,
wake) are in `15-lib-commands.md`.

Status labels used in Part B: **Confirmed** = seen on a real vehicle (results note, see Provenance);
**Likely** = consistent and repeated in real-car logs, but not reproduced in a controlled scenario;
**Schema-only** = defined in the proto files, never seen by us; **Unknown** = seen on the wire, meaning not known.

## Calls

| Call | Returns | Go original | FR |
|---|---|---|---|
| `vehicleStatus(timeout = null)` | `VehicleResult<VehicleStatus>` | `BodyControllerState` (`InformationRequest{GET_STATUS}`) | FR-031 |
| `keySummary(timeout = null)` | `VehicleResult<WhitelistSummary>` | `KeySummary` (`GET_WHITELIST_INFO`) | FR-032 |
| `listKeys(timeout = null)` | `VehicleResult<List<WhitelistEntry>>` | `KeySummary` + `KeyInfoBySlot` per occupied slot | FR-025 |
| `sessionInfo(publicKey, domain, timeout = null)` | `VehicleResult<SessionInfoSummary>` | `SessionInfo` (`session_info_request`) | FR-024 |
| `getState(category, timeout = null)` | `VehicleResult<VehicleData>` | `GetState` (`getVehicleData`) | FR-033 |

- `timeout = null` uses `TeslaBleConfig.commandTimeout` (5 s). For `listKeys` the timeout covers all requests together.
- VCSEC requests are serialized per connection: a query waits behind any VCSEC request already in flight, and that
  wait counts against its timeout. `getState` goes to Infotainment and does not wait in that queue.
- These calls are queries. An `Uncertain` result means no data was confirmed, not that a command may have run, so it is
  safe to ask again. Do not do that in a tight loop (`16-lib-errors.md` §`getState` results).
- While another BLE client is active, a query can get no reply at all, `listKeys` most often (`16-lib-errors.md`
  §Queries while another BLE client is active).

# Part A: On-demand data (you request it)

## A1. How a request travels

| Item | Value |
|---|---|
| GATT service | `00000211-b2d1-43f0-9b88-960cebf8b91e` |
| Write characteristic (phone to vehicle) | `00000212-b2d1-43f0-9b88-960cebf8b91e` |
| Notify/indicate characteristic (vehicle to phone) | `00000213-b2d1-43f0-9b88-960cebf8b91e` |
| Framing | 2-byte big-endian length prefix, then the serialized `RoutableMessage`. Maximum message 1024 bytes (`Framer.MAX_MESSAGE_SIZE`); a larger one fails with `InvalidArgument` |
| Block length for writes | `min(ATT MTU, 1024) - 3`; 20 when the MTU exchange fails |

Every frame, both directions, is a `UniversalMessage.RoutableMessage` (`universal_message.proto`):

| Field | Number | Type | Meaning |
|---|---|---|---|
| `to_destination` | 6 | `Destination` | `oneof`: `domain` (1, enum `Domain`) or `routing_address` (2, bytes) |
| `from_destination` | 7 | `Destination` | Same shape. Requests carry our routing address; vehicle replies carry the vehicle domain |
| `protobuf_message_as_bytes` | 10 | bytes | `oneof payload`: serialized domain message (`UnsignedMessage` / `ToVCSECMessage` to the vehicle, `FromVCSECMessage` back) |
| `session_info_request` | 14 | `SessionInfoRequest` | `oneof payload`: `public_key` (1), `challenge` (2) |
| `session_info` | 15 | bytes | `oneof payload`: serialized `Signatures.SessionInfo` |
| `signature_data` | 13 | `Signatures.SignatureData` | Present on authenticated (AES-GCM) commands and replies |
| `signedMessageStatus` | 12 | `MessageStatus` | `operation_status` (1), `signed_message_fault` (2, `MessageFault_E`) |
| `request_uuid` | 50 | bytes | Echo of the request `uuid` (16 B). Used to match Infotainment replies |
| `uuid` | 51 | bytes | Request id, 16 random bytes |
| `flags` | 52 | uint32 | Bit mask of `Flags` (`FLAG_USER_COMMAND` 0, `FLAG_ENCRYPT_RESPONSE` 1; 2 to 4 are Teslemetry-only) |

`Domain` values: `DOMAIN_BROADCAST` 0, `DOMAIN_VEHICLE_SECURITY` 2 (VCSEC), `DOMAIN_INFOTAINMENT` 3. The schema also lists
4, 5, 7, 8 (Teslemetry-only); the library does not use them. Field 53 `message_frame` in the Teslemetry schema is
unconfirmed and unused.

Routing rules for VCSEC requests (`Dispatcher.kt`, `VcsecCommands.kt`):

- `to_destination.domain = DOMAIN_VEHICLE_SECURITY`, `from_destination.routing_address` = **a fresh random 16-byte address per
  request**. The reply comes back with `to_destination.routing_address` = that address, and the dispatcher matches
  it by address (plus the vehicle domain). `request_uuid` is **not** used to match VCSEC replies (the dispatcher uses an
  empty uuid in the match key for `DOMAIN_VEHICLE_SECURITY`). The `uuid` (51) is still filled with 16 random bytes.
- A reply addressed to any other routing address is not ours; it is dropped as `no-handler` (see B3).
- Queries are **unauthenticated**: `AuthMethod.NONE`, no `signature_data`, and `protobuf_message_as_bytes` is the plain
  `UnsignedMessage`. Commands (`15-lib-commands.md`) are authenticated: the same envelope with `signature_data` and an
  AES-GCM protected payload.
- For `sessionInfo` the payload is `session_info_request` (14) with `to_destination.domain` = the requested domain; the
  reply carries `session_info` (15).

## A2. `vehicleStatus()`

```kotlin
suspend fun vehicleStatus(timeout: Duration? = null): VehicleResult<VehicleStatus>
```

| Item | Value |
|---|---|
| Auth | None (works with an unenrolled key, no session) |
| Domain | `DOMAIN_VEHICLE_SECURITY` |
| Request payload | `UnsignedMessage{InformationRequest{GET_STATUS}}` = hex `0a00` (field 1, length 0; `informationRequestType` 0 is the proto3 default and is not encoded) |
| Terminal rule | First message of the reply (`VcsecResponses.FIRST_MESSAGE`) |
| Reply | `FromVCSECMessage.vehicleStatus` (field 1) |
| Missing field | `Failure(BadResponse("missing vehicleStatus"))` |
| Errors | `FromVCSECMessage.nominalError` (46) becomes `Failure(VcsecRejected(...))`; a lost reply is `Uncertain(Timeout(afterSend = true))` |

`VCSEC.VehicleStatus` (`vcsec.proto`):

| # | Field | Type | Meaning | In public `VehicleStatus` |
|---|---|---|---|---|
| 1 | `closureStatuses` | `ClosureStatuses` | Per-opening state; **only sent while something is open** (see below) | `closures`; absent becomes all `CLOSED` |
| 2 | `vehicleLockState` | `VehicleLockState_E` | Lock state | `lockState` |
| 3 | `vehicleSleepStatus` | `VehicleSleepStatus_E` | Infotainment sleep state | `sleepStatus` |
| 4 | `userPresence` | `UserPresence_E` | Person in or at the vehicle (also `PRESENT` while standing outside with a door open, B3.1) | `userPresence` |
| 5 | `detailedClosureStatus` | `DetailedClosureStatus` | `tonneauPercentOpen` (1, uint32), Cybertruck | `tonneauPercentOpen` (`null` if absent) |
| 6 | `uiDesire` | `UIDesire_E` (TESLEMETRY-EXT) | In schema only | **Not surfaced** (planned M5) |
| 7 | `gear` | `Gear_E` (TESLEMETRY-EXT; confirmed in broadcasts; Likely in query replies) | Current gear | `gear`; absent or outside the schema becomes `UNKNOWN` |
| 8 | (unknown, not in schema) | message | Seen on a real car: `{1: 1\|2, 2: 1\|2, 3: 1\|2}`; meaning unknown (B3.1) | Not surfaced |

`ClosureStatuses` has eight `ClosureState_E` fields, numbered 1 to 8: `frontDriverDoor`, `frontPassengerDoor`,
`rearDriverDoor`, `rearPassengerDoor`, `rearTrunk`, `frontTrunk`, `chargePort`, `tonneau`.

Mapping rules (`VcsecInfoMapping`): a value outside the schema (found in `unknownFields`) maps to `UNKNOWN` instead of the
zero value. proto3 cannot tell "missing" from the zero value for enums, so a missing `vehicleLockState` reads as
`UNLOCKED`; the library keeps that Go behaviour for the query. `gear` has no Go counterpart (the official Go `vcsec.proto`
ends at field 5); its zero value is `GEAR_UNKNOWN`, so a missing `gear` reads as `UNKNOWN`. Gear was confirmed in
broadcasts (B3.1). Query replies also fill it (Likely, B5): 144 of 144 carried `gear`; `NEUTRAL` not seen. The real-car
results show that the vehicle omits `closureStatuses` when every field is `CLOSED` (the zero value) and sends it while
any field is not (front and rear driver doors, rear trunk, charge port seen), so "absent = all `CLOSED`" matches what we
saw. The one exception is the trunk operated with the interior button, which sent no broadcast at all; this query
should still report it (not yet checked on a real car).

## A3. `keySummary()`

```kotlin
suspend fun keySummary(timeout: Duration? = null): VehicleResult<WhitelistSummary>
```

| Item | Value |
|---|---|
| Auth / domain | None / `DOMAIN_VEHICLE_SECURITY` |
| Request payload | `UnsignedMessage{InformationRequest{GET_WHITELIST_INFO}}` = hex `0a020805` (`informationRequestType` 5) |
| Terminal rule | First message |
| Reply | `FromVCSECMessage.whitelistInfo` (field 16); missing becomes `BadResponse("missing whitelistInfo")` |

`WhitelistInfo`:

| # | Field | Type | Meaning | Public model |
|---|---|---|---|---|
| 1 | `numberOfEntries` | uint32 | Enrolled keys | `WhitelistSummary.numberOfEntries` |
| 2 | `whitelistEntries` | repeated `KeyIdentifier{publicKeySHA1 = 1}` | Key ids | Not surfaced |
| 3 | `slotMask` | uint32 | Bit *i* set = slot *i* occupied | `slotMask`, `occupiedSlots` |

## A4. `listKeys()`

```kotlin
suspend fun listKeys(timeout: Duration? = null): VehicleResult<List<WhitelistEntry>>
```

| Item | Value |
|---|---|
| Auth / domain | None / `DOMAIN_VEHICLE_SECURITY` |
| Requests | One `keySummary` request, then one `GET_WHITELIST_ENTRY_INFO` per occupied slot, sequentially |
| Slot request payload | `UnsignedMessage{InformationRequest{GET_WHITELIST_ENTRY_INFO, slot = N}}` = hex `0a04080620NN`, for example slot 0 `0a0408062000`, slot 2 `0a0408062002` (`informationRequestType` 6, `slot` field 4). `NN` is a varint; for slots of 128 or more it takes two bytes and the `InformationRequest` length byte becomes 5 (`0a05080620` followed by the two varint bytes) |
| Terminal rule | First message of each reply |
| Reply | `FromVCSECMessage.whitelistEntryInfo` (field 17); missing becomes `BadResponse("missing whitelistEntryInfo")` |
| Budget | One `timeout` for all requests; the first `Failure` or `Uncertain` is returned; no time left gives `Timeout(afterSend = false)` |

`WhitelistEntryInfo`:

| # | Field | Type | Meaning | Public model |
|---|---|---|---|---|
| 1 | `keyId` | `KeyIdentifier{publicKeySHA1 = 1}` | SHA-1 of the public key | `keyIdSha1Hex` (`null` if empty) |
| 2 | `publicKey` | `PublicKey{PublicKeyRaw = 1}` | 65-byte key | `publicKey` (`null` if missing or malformed) |
| 4 | `metadataForKey` | `KeyMetadata{keyFormFactor = 1}` | Form factor | `formFactor` |
| 6 | `slot` | uint32 | Slot | `slot` is the **requested** slot, not this field (same as Go `list-keys`) |
| 7 | `keyRole` | `Keys.Role` | Role | `role` |

`keyInfoBySlot(slot)` exists on the internal query class but is not on `Vehicle`.

## A5. `sessionInfo(publicKey, domain)`

```kotlin
suspend fun sessionInfo(publicKey: PublicKeyBytes, domain: VehicleDomain, timeout: Duration? = null): VehicleResult<SessionInfoSummary>
```

| Item | Value |
|---|---|
| Auth | None. Any 65-byte public key can be asked about, including keys other than the connection's own |
| Domain | `domain` = `VCSEC` (`DOMAIN_VEHICLE_SECURITY`) or `INFOTAINMENT` (`DOMAIN_INFOTAINMENT`) |
| Request | `RoutableMessage{to_destination{domain}, from_destination{routing_address = 16 bytes}, session_info_request{public_key}, uuid}`; the address is fresh per request for `VCSEC` and the connection's own fixed address for `INFOTAINMENT`; the optional `challenge` (2) is not sent |
| Terminal rule | The first reply; sent once, never repeated |
| Reply | `RoutableMessage.session_info` (15) = serialized `Signatures.SessionInfo` |
| Errors | `signedMessageStatus` fault `UNKNOWN_KEY_ID`, or `SessionInfo.status = KEY_NOT_ON_WHITELIST`, becomes `Failure(KeyNotPaired)`; `status = INVALID_HANDLE` or an unknown `status` value becomes `Failure(UnknownResponse)`; missing `session_info` or an undecodable one becomes `Failure(BadResponse)`; no reply in time is `Uncertain(Timeout(afterSend = true))` |
| Side effect | Asking with the connection's own key also builds that domain's session, so `sessions` shows `Ready(fromCache = false)` |

`Signatures.SessionInfo` (`signatures.proto`):

| # | Field | Type | Public `SessionInfoSummary` |
|---|---|---|---|
| 1 | `counter` | uint32 | `counter` |
| 2 | `publicKey` | bytes | `vehiclePublicKey` (`null` if not a valid 65-byte key) |
| 3 | `epoch` | bytes | Not surfaced |
| 4 | `clock_time` | fixed32 | `clockTime` (seconds) |
| 5 | `status` | `Session_Info_Status` | Checked before the payload is used: `OK` 0 passes; `KEY_NOT_ON_WHITELIST` 1 gives `KeyNotPaired`; `INVALID_HANDLE` 2 (Teslemetry-only) or any unknown value gives `UnknownResponse` |
| 6 | `handle` | uint32 | Not surfaced (Teslemetry-only) |

Use it to confirm enrollment after pairing: `sessionInfo(publicKey, INFOTAINMENT)` (see `13-lib-pairing.md`).

## A6. Enum value tables

All values come from `vcsec.proto` and `universal_message.proto`. `Public` is the library model value; a value the
library does not know maps to `UNKNOWN`.

`VehicleLockState_E` (`vehicleLockState`, field 2):

| Value | Name | Public `LockState` |
|---|---|---|
| 0 | `VEHICLELOCKSTATE_UNLOCKED` | `UNLOCKED` |
| 1 | `VEHICLELOCKSTATE_LOCKED` | `LOCKED` |
| 2 | `VEHICLELOCKSTATE_INTERNAL_LOCKED` | `INTERNAL_LOCKED` |
| 3 | `VEHICLELOCKSTATE_SELECTIVE_UNLOCKED` | `SELECTIVE_UNLOCKED` |

`VehicleSleepStatus_E` (field 3): 0 `VEHICLE_SLEEP_STATUS_UNKNOWN` (`UNKNOWN`), 1 `..._AWAKE` (`AWAKE`), 2 `..._ASLEEP` (`ASLEEP`).

`UserPresence_E` (field 4): 0 `VEHICLE_USER_PRESENCE_UNKNOWN` (`UNKNOWN`), 1 `..._NOT_PRESENT` (`NOT_PRESENT`), 2 `..._PRESENT` (`PRESENT`).

`ClosureState_E` (each `ClosureStatuses` field):

| Value | Name | Public `ClosureState` |
|---|---|---|
| 0 | `CLOSURESTATE_CLOSED` | `CLOSED` |
| 1 | `CLOSURESTATE_OPEN` | `OPEN` |
| 2 | `CLOSURESTATE_AJAR` | `AJAR` |
| 3 | `CLOSURESTATE_UNKNOWN` | `UNKNOWN` |
| 4 | `CLOSURESTATE_FAILED_UNLATCH` | `FAILED_UNLATCH` |
| 5 | `CLOSURESTATE_OPENING` | `OPENING` |
| 6 | `CLOSURESTATE_CLOSING` | `CLOSING` |

`Gear_E` (`VehicleStatus.gear`, field 7, TESLEMETRY-EXT, **Confirmed on a real car in broadcasts**: each of P, R, N, D produced a
broadcast with the matching value; `GEAR_UNKNOWN` is the zero value and is never encoded, but frames with no gear field do
occur and read as `UNKNOWN`, B2.4):

| Value | Name | Public `Gear` |
|---|---|---|
| 0 | `GEAR_UNKNOWN` | `UNKNOWN` |
| 1 | `GEAR_PARK` | `PARK` |
| 2 | `GEAR_DRIVE` | `DRIVE` |
| 3 | `GEAR_REVERSE` | `REVERSE` |
| 4 | `GEAR_NEUTRAL` | `NEUTRAL` |

`UIDesire_E` (`VehicleStatus.uiDesire`, field 6, TESLEMETRY-EXT, **Schema-only**): 0 `UI_DESIRE_NONE`, 1 `UI_DESIRE_HAS_DATA`. Meaning on a real car is not established.

`Keys.Role` (`keyRole`) and the public `KeyRole`:

| Value | Wire name | Public `KeyRole` |
|---|---|---|
| 0 | `ROLE_NONE` | `NONE` |
| 1 | `ROLE_SERVICE` | `SERVICE` |
| 2 | `ROLE_OWNER` | `OWNER` |
| 3 | `ROLE_DRIVER` | `DRIVER` |
| 4 | `ROLE_FM` | `FLEET_MANAGER` |
| 5 | `ROLE_VEHICLE_MONITOR` | `VEHICLE_MONITOR` |
| 6 | `ROLE_CHARGING_MANAGER` | `CHARGING_MANAGER` |
| 7 | `ROLE_SERVICE_TECH` (Teslemetry, unconfirmed) | `UNKNOWN` |
| 8 | `ROLE_GUEST` | `GUEST` |
| 9, 10, 11 | `ROLE_RIDER`, `ROLE_PREDELIVERY`, `ROLE_INFOTAINMENT` (Teslemetry, unconfirmed) | `UNKNOWN` |

`KeyFormFactor` (`KeyMetadata.keyFormFactor`): the public model has `UNKNOWN`, `NFC_CARD`, `IOS_DEVICE`, `ANDROID_DEVICE`,
`CLOUD_KEY` only. Wire values 0 `UNKNOWN`, 1 `NFC_CARD`, 6 `IOS_DEVICE`, 7 `ANDROID_DEVICE`, 9 `CLOUD_KEY` map to those.
**The Teslemetry snapshot values map to `UNKNOWN`:** 2 `3_BUTTON_BLE_CAR_KEYFOB`, 3 `BLE_DEVICE`, 4 `NFC_DEVICE`,
5 `BLE_AND_NFC_DEVICE`, 8 `3_BUTTON_BLE_CAR_KEYFOB_P60`, 10 `3_BUTTON_GEN2_CAR_KEYFOB_P60`, 11 `5_BUTTON_GEN2_CAR_KEYFOB_P60`,
12 `..._P60_V2`, 13 `..._P60_V3`, 14 `NFC_CARD_P71`, 15 `NFC_CARD_METAL_CARD`, 16 `NFC_CARD_PLASTIC_CARD`, 17 `APPLE_WATCH`,
18 `NFC_CARD_WD_PLASTIC_CARD`, 19 `HARMONY_OS_NEXT_DEVICE` (all prefixed `KEY_FORM_FACTOR_`).

## A7. Other request types in the schema

`InformationRequestType` also defines `GET_TOKEN` 1, `GET_COUNTER` 2, `GET_EPHEMERAL_PUBLIC_KEY` 3, `GET_SESSION_DATA` 4,
`GET_PERSONALIZATION_INFO` 7, `GET_KEYSTATUS_INFO` 8, `GET_ACTIVE_KEY` 9, `GET_CAPABILITIES` 16 (Teslemetry-only). The
library sends none of them and has no API for them.

`getState(category)` is not an `InformationRequest`: it goes to Infotainment (next section).

## A8. `getState(category)` (Infotainment)

Available (FR-033, #115, #116), ahead of the rest of M5. It is checked with FakeVehicle tests; **nothing in this section
is confirmed on a real vehicle yet** (A8.5).

```kotlin
suspend fun getState(category: StateCategory, timeout: Duration? = null): VehicleResult<VehicleData>
```

| Item | Value |
|---|---|
| Auth | AES-GCM over the Infotainment session, with `flags` = 2 (`FLAG_ENCRYPT_RESPONSE`). Needs an enrolled key |
| Domain | `DOMAIN_INFOTAINMENT`; the reply is matched by `request_uuid` |
| Request payload | `CarServer.Action{vehicleAction{getVehicleData{selector}}}` with exactly one selector (A8.1). `CHARGE` is hex `12040a021200` |
| Requests | One category per request. There is no combined query |
| Terminal rule | The first reply |
| Reply | `CarServer.Response.vehicleData`, returned unchanged as the Wire generated type `com.tesla.generated.carserver.vehicle.VehicleData` |
| Timeout | `timeout = null` uses `commandTimeout` (5 s) |
| Retries | As for commands (`16-lib-errors.md` §Retries and timeouts): temporary errors are retried inside `timeout` with a new counter, nonce and `expires_at`; `Uncertain` is never resent |
| Results | `16-lib-errors.md` §`getState` results |

### A8.1 Categories

`StateCategory` (`dev.mirolab.teslable.model`) has the 12 Go constants in the Go order. All 12 reply messages have a
`timestamp` field. Full field lists: the official `pkg/protocol/protobuf/vehicle.proto` of `vehicle-command`.

| `StateCategory` | Swift | Selector (`GetVehicleData` field) | `VehicleData` field (number) | Reply message | Official fields, for example |
|---|---|---|---|---|---|
| `CHARGE` | `.charge` | `getChargeState` (2) | `charge_state` (3) | `ChargeState` | `battery_level`, `charging_state`, `charge_limit_soc`, `charger_power`, `minutes_to_full_charge`, `charge_port_door_open` |
| `CLIMATE` | `.climate` | `getClimateState` (3) | `climate_state` (4) | `ClimateState` | `inside_temp_celsius`, `outside_temp_celsius`, `is_climate_on`, `driver_temp_setting`, `seat_heater_left` |
| `DRIVE` | `.drive` | `getDriveState` (4) | `drive_state` (5) | `DriveState` | `shift_state`, `speed`, `speed_float`, `power`, `odometer_in_hundredths_of_a_mile`, `active_route_destination`, `active_route_minutes_to_arrival` |
| `LOCATION` | `.location` | `getLocationState` (7) | `location_state` (8) | `LocationState` | A8.4 |
| `CLOSURES` | `.closures` | `getClosuresState` (8) | `closures_state` (9) | `ClosuresState` | `door_open_driver_front`, `window_open_driver_front`, `locked`, `sun_roof_state`, `sentry_mode_state`, `is_user_present` |
| `CHARGE_SCHEDULE` | `.chargeSchedule` | `getChargeScheduleState` (10) | `charge_schedule_state` (15) | `ChargeScheduleState` | `charge_schedules`, `charge_schedule_window`, `next_schedule` |
| `PRECONDITIONING_SCHEDULE` | `.preconditioningSchedule` | `getPreconditioningScheduleState` (11) | `preconditioning_schedule_state` (16) | `PreconditioningScheduleState` | `precondition_schedules`, `preconditioning_schedule_window`, `next_schedule` |
| `TIRE_PRESSURE` | `.tirePressure` | `getTirePressureState` (14) | `tire_pressure_state` (19) | `TirePressureState` | `tpms_pressure_fl` (also `fr`, `rl`, `rr`), `tpms_hard_warning_fl`, `tpms_soft_warning_fl`, `tpms_last_seen_pressure_time_fl` |
| `MEDIA` | `.media` | `getMediaState` (15) | `media_state` (20) | `MediaState` | `now_playing_title`, `now_playing_artist`, `audio_volume`, `media_playback_status` |
| `MEDIA_DETAIL` | `.mediaDetail` | `getMediaDetailState` (16) | `media_detail_state` (21) | `MediaDetailState` | `now_playing_duration`, `now_playing_elapsed`, `now_playing_album` |
| `SOFTWARE_UPDATE` | `.softwareUpdate` | `getSoftwareUpdateState` (17) | `software_update_state` (23) | `SoftwareUpdateState` | `status`, `version`, `download_perc`, `install_perc`, `scheduled_time_ms` |
| `PARENTAL_CONTROLS` | `.parentalControls` | `getParentalControlsState` (19) | `parental_controls_state` (24) | `ParentalControlsState` | `parental_controls_active`, `parental_controls_pin_set`, `parental_controls_settings` |

**Only fields of the official proto are supported.** `VehicleData` is generated from the Teslemetry snapshot, so it also
has `TESLEMETRY-EXT` fields, for example `gui_settings` and `vehicle_config`, and `native_latitude_d` on `LocationState`.
The library never requests them, and a value that arrives in one has no guaranteed meaning. Do not build on them. In this
repository's `domain/src/commonMain/proto/vehicle.proto` they carry a `TESLEMETRY-EXT` comment (ADR-0002, amendment
2026-11-01).

### A8.2 Call order

`getState` **never starts a session and never wakes the vehicle**.

| Situation | What `getState` does | What the app does |
|---|---|---|
| No `INFOTAINMENT` session | Sends nothing, returns `Failure(NoSession)` | `startSession(setOf(VehicleDomain.INFOTAINMENT))` first |
| A session restored from the cache that the vehicle no longer accepts | Returns `Failure(NoSession)`; `sessions` then shows `None` for `INFOTAINMENT` | `startSession(setOf(VehicleDomain.INFOTAINMENT))`, then ask again |
| Infotainment asleep | Sends one request, gets no reply, returns `Uncertain(Timeout(afterSend = true))` | Wake it first (below) |

```
Awake:    connect → startSession(setOf(INFOTAINMENT)) → getState(category)
Asleep:   connect → vehicleStatus() shows ASLEEP → startSession(setOf(VCSEC)) → wakeUp()
            → vehicleStatus() shows AWAKE → startSession(setOf(INFOTAINMENT)) → getState(category)
```

- Decide with `vehicleStatus()`: it needs no session and never wakes the vehicle.
- On a sleeping vehicle, handshake with VCSEC alone. Go `tesla-control` does this for a BLE wake because Infotainment is
  presumably asleep (`cmd/tesla-control/commands.go:152-158`), and the Go recipes warn that a handshake with both domains
  can then take a long time (`09-recipes.md`).
- `wakeUp()` returning `Success` means VCSEC accepted the command, not that Infotainment is up. Wait for `AWAKE`, then
  start the Infotainment session (`09-recipes.md`). The vehicle can still answer busy right after waking
  (`08-errors.md` §3.1); the library retries that inside `timeout`, so a first query after a wake may need a longer
  `timeout`.
- **A cached session says nothing about sleep.** `startSession` succeeds without a request and `sessions` shows
  `Ready(fromCache = true)` even while Infotainment sleeps; the first query can then time out (`09-recipes.md`). Read
  `sleepStatus` instead.
- Waking is the app's decision. The library never does it for you.

```kotlin
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

// vehicle was connected with an enrolled key (12-lib-getting-started.md). True when getState can be called.
suspend fun prepareInfotainment(vehicle: Vehicle): Boolean {
    val status = vehicle.vehicleStatus()                                  // no session needed, never wakes the car
    if (status is VehicleResult.Success && status.value.sleepStatus == SleepStatus.ASLEEP) {
        if (vehicle.startSession(setOf(VehicleDomain.VCSEC)) !is VehicleResult.Success) return false
        if (vehicle.wakeUp() is VehicleResult.Failure) return false       // Uncertain: do not resend, the loop reads the state
        for (attempt in 1..30) {                                          // about 30 s; the real wake time is not confirmed (#118)
            delay(1.seconds)
            val now = vehicle.vehicleStatus()
            if (now is VehicleResult.Success && now.value.sleepStatus == SleepStatus.AWAKE) break
        }
    }
    return vehicle.startSession(setOf(VehicleDomain.INFOTAINMENT)) is VehicleResult.Success
}
```

```swift
func prepareInfotainment(vehicle: Vehicle) async throws -> Bool {
    let status = try await vehicle.vehicleStatus(timeout: nil)                 // no session needed, never wakes the car
    if case .success(let s) = onEnum(of: status), (s.value as? VehicleStatus_)?.sleepStatus == .asleep {
        let vcsec = try await vehicle.startSession(domains: [.vcsec], timeout: nil)
        guard case .success = onEnum(of: vcsec) else { return false }
        let woke = try await vehicle.wakeUp(timeout: nil)
        if case .failure = onEnum(of: woke) { return false }                   // uncertain: do not resend, the loop reads the state
        for _ in 1...30 {                                                      // about 30 s; the real wake time is not confirmed (#118)
            try await Task.sleep(nanoseconds: 1_000_000_000)
            let now = try await vehicle.vehicleStatus(timeout: nil)
            if case .success(let n) = onEnum(of: now), (n.value as? VehicleStatus_)?.sleepStatus == .awake { break }
        }
    }
    let session = try await vehicle.startSession(domains: [.infotainment], timeout: nil)
    if case .success = onEnum(of: session) { return true }
    return false
}
```

The 30 s comes from the Go BLE example (`examples/ble/main.go:95`) and is not a measurement. Instead of polling, the app
can watch `sleepStatus` in `vehicle.events` (`VehicleEvent.Status`, B2).

### A8.3 Reading the reply

- **Absent is not 0.** Every scalar and message field directly on the 12 reply messages is nullable: a field the vehicle
  did not send is `null`, and `speed = 0` stays 0. Never replace `null` with 0. Repeated fields (`charge_schedules`,
  `precondition_schedules`) are empty lists when absent. Inside nested messages such as `ChargeSchedule`, fields are
  plain proto3 values: absent reads as 0, empty or false.
- **Check every field you use.** A reply with no `vehicleData` is `Success(VehicleData())`, the same as an empty
  `vehicleData`, and the two cannot be told apart. Go returns `(nil, nil)` here; the library keeps a non-null result
  because the app checks each field anyway. Another kind of reply, such as `Ping`, gives the same empty result.
- **The reply is not checked against `category`** (same as Go). Read the field for the category you asked for; a different
  field that arrives is passed through.
- **`Success` is not proof of a new measurement.** Compare `timestamp` with the previous reply: if it did not move, do not
  present the values as fresh. If it is `null`, you cannot tell. `timestamp` is a Wire `Instant`: `getEpochSecond()`
  works in common Kotlin and in Swift.
- Speed, power and odometer exist only in `DRIVE`, and that reply can exceed the vehicle's size limit
  (`16-lib-errors.md` §Response size limit). Real-time speed is not guaranteed, and there is no other source for it.
- Wire keeps proto names, so fields are `drive_state`, `speed_float`, `charge_state`, unlike the camelCase hand-written
  models. Generated types are in `com.tesla.generated.*`.
- **Do not log a result**, `toString()` included. A `VehicleData` can hold the location and the destination. The library
  does not log it, and neither should the app (FR-112).

```kotlin
import com.tesla.generated.universalmessage.MessageFault_E
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.*
import kotlin.time.Duration.Companion.seconds

suspend fun readSpeed(vehicle: Vehicle): Int? {
    when (val result = vehicle.getState(StateCategory.DRIVE)) {
        is VehicleResult.Success ->
            return result.value.drive_state?.speed               // null = the vehicle did not send it; 0 = it sent 0
        is VehicleResult.Uncertain -> {
            val e = result.error
            if (e is VehicleError.ProtocolFault && e.fault == MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED) {
                return null                                      // reply too large
            }
            if (e is VehicleError.Timeout) return null           // no reply
            return null
        }
        is VehicleResult.Failure -> {
            if (result.error == VehicleError.NoSession) return null
            return null
        }
    }
}

// With an explicit timeout, and reading the reply's timestamp:
suspend fun readBattery(vehicle: Vehicle, lastSeconds: Long?) {
    val result = vehicle.getState(StateCategory.CHARGE, timeout = 8.seconds)
    if (result !is VehicleResult.Success) return
    val charge = result.value.charge_state ?: return             // no charge data in this reply
    val level: Int? = charge.battery_level
    val seconds: Long? = charge.timestamp?.getEpochSecond()
    val fresh = seconds != null && seconds != lastSeconds
    render(level, fresh)
}
```

Swift passes every argument. `s.value` needs a cast and numbers bridge as `KotlinInt?`: `17-lib-platform-notes.md`
§Swift: generated types. This form passes a Swift typecheck against the framework; it has not run in a Swift program.

```swift
func readSpeed(vehicle: Vehicle) async throws -> Int32? {
    let result = try await vehicle.getState(category: .drive, timeout: nil)
    switch onEnum(of: result) {
    case .success(let s):
        // VehicleResult's generic is erased in Swift: s.value is AnyObject?, so cast it.
        guard let data = s.value as? VehicleData else { return nil }
        // Nullable numerics bridge as KotlinInt? / KotlinFloat?, not Int32? / Float?.
        let speed: KotlinInt? = data.drive_state?.speed
        let speedFloat: KotlinFloat? = data.drive_state?.speed_float
        _ = speedFloat?.floatValue
        return speed?.int32Value // nil = the vehicle did not send it; 0 = it sent 0
    case .uncertain(let u):
        switch onEnum(of: u.error) {
        case .protocolFault(let p) where p.fault == .messagefaultErrorResponseMtuExceeded:
            return nil // reply too large
        case .timeout(let t):
            _ = t.afterSend
            return nil // no reply
        default:
            return nil
        }
    case .failure(let f):
        if case .noSession = onEnum(of: f.error) { return nil }
        return nil
    }
}

// With an explicit timeout, and reading the reply's timestamp:
func readBattery(vehicle: Vehicle, lastSeconds: Int64?) async throws {
    let result = try await vehicle.getState(category: .charge, timeout: Timeouts.shared.seconds(seconds: 8))
    guard case .success(let s) = onEnum(of: result), let data = s.value as? VehicleData else { return }
    guard let charge = data.charge_state else { return }   // no charge data in this reply
    let level: KotlinInt? = charge.battery_level
    let seconds: Int64? = charge.timestamp?.getEpochSecond()
    let fresh = seconds != nil && seconds != lastSeconds
    render(level?.int32Value, fresh)
}
```

### A8.4 Location: which coordinates to use

`LOCATION` can return several coordinate pairs. The rule is the `LocationState` comment in the official `vehicle.proto`,
which Go `GetState` points to (`state.go:66-67`):

| Use | Fields | When |
|---|---|---|
| First choice | `native_latitude`, `native_longitude` | Normally. They use WGS (international) or GCJ (China) depending on the vehicle's region; `native_type` says which |
| Fallback, WGS client | `latitude`, `longitude` | The native fields are not provided |
| Fallback, GCJ client | `corrected_latitude`, `corrected_longitude` | The native fields are not provided. The proto marks these "only for China cars" |
| Exception | `geo_latitude`, `geo_longitude` | Raw WGS GPS, usually less accurate. Use them when `estimated_to_raw_distance` (meters between the estimated and raw coordinates) shows a large gap, which mostly happens after the vehicle was transported or towed. The proto gives no threshold |

`native_latitude_d`, `geo_latitude_d` and the other double-precision variants are `TESLEMETRY-EXT`: do not use them.

### A8.5 Not confirmed on a real vehicle (#118)

- The whole of `getState`: so far it has run only against FakeVehicle.
- Which fields each category really carries, and how often they update.
- The unit of `speed` and `speed_float`. Do not assume km/h or mph.
- What `timestamp` marks: the measurement or the reply. Until that is known, the check in A8.3 is the best available.
- How long a wake takes, and what a sleeping Infotainment does with a request. The tests model no reply. If a real
  vehicle answers busy instead, the library retries until `timeout`, and the result can be
  `Failure(Timeout(afterSend = false))` (`16-lib-errors.md` §`getState` results).
- How the size limit behaves per firmware, and how the fault arrives on our own `getState`. In replies to another client it
  came in plaintext `signedMessageStatus` (Likely, `16-lib-errors.md` §Response size limit).

## `VehicleStatus` (public model)

| Field | Type | Notes |
|---|---|---|
| `lockState` | `LockState` | `UNLOCKED`, `LOCKED`, `INTERNAL_LOCKED`, `SELECTIVE_UNLOCKED`, `UNKNOWN` |
| `closures` | `Closures` | All eight are `CLOSED` if the vehicle did not send closure statuses (it omits them when everything is closed, like the Go getters) |
| `sleepStatus` | `SleepStatus` | `UNKNOWN`, `AWAKE`, `ASLEEP` (Infotainment) |
| `userPresence` | `UserPresence` | `UNKNOWN`, `NOT_PRESENT`, `PRESENT` |
| `tonneauPercentOpen` | `Int?` | Cybertruck only; `null` if not reported |
| `gear` | `Gear` | `UNKNOWN`, `PARK`, `DRIVE`, `REVERSE`, `NEUTRAL`. `UNKNOWN` if not sent (proto3 cannot tell that from `GEAR_UNKNOWN`) or outside the schema. Last constructor parameter. TESLEMETRY-EXT field 7, no Go counterpart (FR-036). Confirmed in broadcasts; Likely in query replies |

`Closures` has eight `ClosureState` fields: `frontDriverDoor`, `frontPassengerDoor`, `rearDriverDoor`,
`rearPassengerDoor`, `rearTrunk`, `frontTrunk`, `chargePort`, `tonneau`. `ClosureState` values: `CLOSED`, `OPEN`, `AJAR`,
`UNKNOWN`, `FAILED_UNLATCH`, `OPENING`, `CLOSING`. Every enum maps values the library does not know to `UNKNOWN`
(newer firmware) instead of failing. `uiDesire` is not in this model yet (planned M5).

## `WhitelistSummary` and `WhitelistEntry`

| Type | Member | Meaning |
|---|---|---|
| `WhitelistSummary` | `numberOfEntries: Int` | Number of enrolled keys |
| | `slotMask: UInt` | Bit *i* set = slot *i* occupied |
| | `occupiedSlots: List<Int>` | Occupied slot numbers, ascending |
| `WhitelistEntry` | `slot: Int` | Slot number |
| | `publicKey: PublicKeyBytes?` | 65-byte key; `null` if missing or malformed |
| | `keyIdSha1Hex: String?` | SHA-1 of the public key in hex; `null` if not sent |
| | `role: KeyRole` | See `13-lib-pairing.md` |
| | `formFactor: KeyFormFactor` | See `13-lib-pairing.md` |

`SessionInfoSummary`: `counter: UInt`, `clockTime: UInt`, `vehiclePublicKey: PublicKeyBytes?`.

## Connection and session state

These are `StateFlow`s on `Vehicle`, not vehicle queries:

| Member | Type | Values |
|---|---|---|
| `connection` | `StateFlow<TransportState>` | `Connected`, `Disconnected(reason: TransportError?)` |
| `sessions` | `StateFlow<Map<VehicleDomain, SessionStatus>>` | `None`, `Handshaking`, `Ready(fromCache)` per `VCSEC` / `INFOTAINMENT` |
| `hasKey` | `Boolean` | Connected with a private key |
| `vin` | `Vin` | `toString()` is masked for logs; `value` is the raw VIN, never log it |

`events` (`Flow<VehicleEvent>`, not a `StateFlow`) carries the broadcasts of the connection: B2.

## Wake behaviour

- `vehicleStatus()`, `keySummary()` and `listKeys()` never wake the vehicle. Use `sleepStatus` to show whether
  Infotainment is asleep.
- `getState(category)` never wakes the vehicle either, and it needs an authenticated Infotainment session and an awake
  vehicle (A8.2). On a sleeping vehicle it ends with `Uncertain(Timeout(afterSend = true))`.
- `wakeUp()` (FR-042, M4) uses only the VCSEC session. Call order with `getState`: A8.2.
- After an `Uncertain` VCSEC command result (M4), re-read with `vehicleStatus()` instead of resending.

# Part B: Broadcast data (the vehicle pushes it)

## B1. What a broadcast is on the wire

A broadcast arrives on the same `00000213-...` subscription as replies. It is a `RoutableMessage` with:

| Field | Value |
|---|---|
| `from_destination.domain` | `DOMAIN_VEHICLE_SECURITY` (2) |
| `to_destination.domain` | `DOMAIN_BROADCAST` (0), no routing address |
| `request_uuid` | empty |
| `signature_data` | absent (plaintext, unauthenticated) |
| `protobuf_message_as_bytes` | serialized `FromVCSECMessage` |

`FromVCSECMessage` (`vcsec.proto`; fields 6 to 10 reserved):

| # | Field | Type | Notes |
|---|---|---|---|
| 1 | `vehicleStatus` | `VehicleStatus` | Same message as the query reply (A2) |
| 3 | `authenticationRequest` | `AuthenticationRequest` | TESLEMETRY-EXT |
| 4 | `commandStatus` | `CommandStatus` | Replies to commands |
| 16 | `whitelistInfo` | `WhitelistInfo` | Query reply |
| 17 | `whitelistEntryInfo` | `WhitelistEntryInfo` | Query reply |
| 46 | `nominalError` | `Errors.NominalError` | Error reply |
| 44, 48, 53 | unknown | | Seen on a real car, not in any schema (B3) |

## B2. What the library does with broadcasts: `Vehicle.events`

Routing is unchanged and matches the Go original: a broadcast has no routing address, so the dispatcher's `route()`
returns the drop reason `TO_DOMAIN`. Broadcasts never reach a pending request and cannot be mixed up with replies, which
are matched by our routing address. The observation log still writes its `[drop:to-domain]` line (DEBUG, tag `observe`,
`18-lib-troubleshooting.md` §Observation log).

In addition (FR-035; the Go original drops these frames and emits nothing), every frame that passes the gate below is
delivered through `Vehicle.events`. Meaning of each message: B3. Kotlin and Swift examples: `12-lib-getting-started.md`
§Broadcast events.

```kotlin
val events: Flow<VehicleEvent>                    // member of Vehicle

sealed interface VehicleEvent {                   // dev.mirolab.teslable.model
    val raw: ByteArray                            // the whole received frame; a new copy on every access; not VIN-masked
    val unknownFields: List<String>               // paths of fields the schema does not know, for example "53", "1.8"
    class Status(val status: VehicleStatus, …) : VehicleEvent
    class AuthenticationRequest(val requestedLevel: AuthenticationLevel, val reasons: List<AuthenticationReason>, …) : VehicleEvent
    class Unrecognized(…) : VehicleEvent
}
```

### B2.1 Gate

A frame becomes events only if all of these hold. It is the observation log's detailed-decode gate without its last
condition: the payload does not have to decode (an undecodable one gives `Unrecognized`).

| Field | Required value |
|---|---|
| `from_destination` | `domain = DOMAIN_VEHICLE_SECURITY` |
| `to_destination` | `domain = DOMAIN_BROADCAST` (no routing address) |
| `request_uuid` | empty |
| `signature_data` | absent |
| `session_info` | absent |

Everything else is not an event and stays an observation log line only: replies to other clients (`no-handler`),
signed or encrypted frames, frames that do not parse as `RoutableMessage` (`unparseable`), frames without a source
(`missing-source`, which includes unwrapped frames, B3.2). The library never decrypts a broadcast, and nothing it decrypted
ever reaches an event (FR-112).

### B2.2 Event types

| Event | When | Fields |
|---|---|---|
| `VehicleEvent.Status` | The payload has `vehicleStatus` (field 1) | `status: VehicleStatus`, built with the same rules as `vehicleStatus()` (A2): absent `closureStatuses` = all `CLOSED`, values outside the schema = `UNKNOWN`, `gear` included |
| `VehicleEvent.AuthenticationRequest` | The payload has `authenticationRequest` (field 3) | `requestedLevel: AuthenticationLevel`, `reasons: List<AuthenticationReason>` (B3.2). The 20-byte token is not a field; it is in `raw` |
| `VehicleEvent.Unrecognized` | Neither field 1 nor field 3: only unknown fields (44, 48, 53 on the real car), another `FromVCSECMessage` field, no payload, or a payload that does not decode as `FromVCSECMessage` (then `unknownFields` is empty) | none beyond `raw` and `unknownFields` |

- One frame can give two events. With both fields 1 and 3, `Status` comes first, then `AuthenticationRequest`; both have
  the same `raw` and the same `unknownFields`. The schema puts the two fields in one `oneof`, so a plain Wire decode keeps
  only the last one; the library reads them separately and loses neither.
- `raw` is the whole received `RoutableMessage` frame (without the 2-byte BLE length prefix), exactly as the vehicle sent
  it in plaintext. Every access returns a new copy, so read it once. It is **not VIN-masked**: mask it before you store or
  share it. Like the observation log's detailed decode, this is a narrow exception to "no plaintext payloads" that does
  not touch FR-112: only frames the vehicle originally sent as plaintext, never anything the library decrypted.
- `equals` and `hashCode` compare `raw` by content plus the other fields; `toString()` shows `raw` by length only.

### B2.3 `unknownFields` paths

Each entry is a dot path from `FromVCSECMessage` to a field the schema does not know:

| Path | Meaning |
|---|---|
| `"n"` | Top-level field n, for example `"53"`, `"48"`; in arrival order |
| `"1.n"` | Field n inside `vehicleStatus`, for example `"1.8"` |
| `"1.1.n"`, `"1.5.n"` | Inside `closureStatuses`, inside `detailedClosureStatus` |
| `"3.n"` | Inside `authenticationRequest` |
| `"3.2.n"` | Inside its token message |

- An enum value outside the schema also appears under its field number (the model reads it as `UNKNOWN`); a new gear
  value gives `"1.7"`.
- Inside a sub-message its own fields come first, then those of its nested messages. A number that occurs several times
  is listed several times. The inside of other messages (`commandStatus` and so on) is not inspected.
- A non-empty list means part of the frame was not understood; the bytes are in `raw`.

### B2.4 Rules for apps

- **A repeat is not a change.** With nothing changing, the vehicle resends `vehicleStatus` 60.0 s after the previous
  `Status` (Likely; measured only on an awake vehicle with a person present, B3.1), and several frames can come within one
  second around a change, some of them identical. A frame can also differ only in field 8, which the model does not show,
  so it reads as an unchanged `VehicleStatus`. The library does not filter duplicates; compare with the previous value
  yourself.
- **A quiet minute is normal.** `Status` frames can be 60 s apart, so up to 60 s without one says nothing about the link.
  Judge the link from `connection`, not from the time since the last event.
- **Trunk:** opening and closing the trunk by hand from outside produced broadcasts (`rearTrunk = OPEN`, then
  `closureStatuses` absent). Opening or closing it with the interior button produced **no** broadcast, so an app that
  tracks the trunk from `events` keeps seeing it `CLOSED`. When the trunk state matters, confirm it with
  `vehicleStatus()`.
- **Waking vehicle:** the first frames of a sleeping vehicle can carry `UNKNOWN` closures and `gear = UNKNOWN`; they
  become real values within about 0.5 s (B3.1). Do not treat those `UNKNOWN` values as a change. The lock state
  reads `UNLOCKED` in them too, and since `UNLOCKED` is never encoded you cannot tell whether the vehicle was still
  locked; confirm with `vehicleStatus()` if it matters.
- **`gear = UNKNOWN` on an awake vehicle (Likely):** twice a parked, awake vehicle with a person present sent a 24-byte
  frame with no gear field, and a normal `PARK` frame followed within 0.1 s. Do not treat that `UNKNOWN` as a change.
- **Subscription:** each collection subscribes when it starts. There is no replay: a broadcast that arrived earlier is
  not delivered. Several collectors each get every event.
- **Completion:** the flow completes normally (no exception) when `connection` becomes `Disconnected`, whatever the
  reason (`null` or a `TransportError`), or when `disconnect()` is called, whichever comes first. A collection started
  after that completes at once. A frame still being handed over at that moment can be lost. Decide about reconnecting
  from `connection`, not from the end of the flow, and still call `disconnect()` after a link loss. A new connection is a
  new `Vehicle` with its own `events`.
- **Slow collector:** the library holds up to 64 frames for collectors that fall behind. When that buffer is full, new
  frames are dropped for every collector of the connection, counted per connection, and logged at `WARN` on the first
  drop and every 100th: `Dropping plain broadcast because the broadcast buffer is full (dropped N since connect)`. This is
  not an error (`16-lib-errors.md`). Keep the collector light and hand slow work to another coroutine.
- No key and no session are needed: `connect(vin, key = null)` is enough. The real-car observation used an unenrolled key.

## B3. Message catalogue

| Message | Status | In `Vehicle.events` | Where |
|---|---|---|---|
| `vehicleStatus` (field 1) | Confirmed | `VehicleEvent.Status` | B3.1 |
| `authenticationRequest` (field 3) | Confirmed (field names unverified) | `VehicleEvent.AuthenticationRequest` | B3.2 |
| Field 53 | Unknown | `"53"` in `unknownFields`; alone it is a `VehicleEvent.Unrecognized` | B3.3 |
| Field 48 | Unknown | `"48"` in `unknownFields`; alone it is a `VehicleEvent.Unrecognized` | B3.4 |
| Field 44 | Unknown | `"44"` in `unknownFields`; alone it is a `VehicleEvent.Unrecognized` | B3.6 |
| Replies for other clients | Confirmed for the signed VCSEC reply, Likely for the others (not a broadcast) | Not an event (fails the gate) | B3.5 |

### B3.1 `vehicleStatus` broadcast

Same fields as A2. What the real-car observation showed (iPhone, Model Y; 1st round 71 lines, 2nd round per scenario,
3rd round one 40 s scenario of 25 lines starting from a sleeping vehicle):

| Field | Status | Observed |
|---|---|---|
| `vehicleLockState` (2) | Confirmed | `UNLOCKED` right after connect; `INTERNAL_LOCKED` repeated while seated with doors closed; `LOCKED` after getting out; `UNLOCKED` to `LOCKED` when walking away with the connection still up (link lost about 30 s later). Around driving, no scenario given (Likely, B5): `INTERNAL_LOCKED` became `UNLOCKED` in the same frame as `PARK` (3 of 3). It is not simply "in gear": after a shift to `DRIVE` it took 16.9 to 130.4 s to appear (3 cases), it did not appear in one 44 s stretch of `REVERSE` and `DRIVE`, and it ended once in `DRIVE` just as a door opened |
| `vehicleSleepStatus` (3) | Confirmed | Rounds 1 and 2: `AWAKE` in all lines. Round 3: the first two frames were `ASLEEP` when the exterior handle of the sleeping vehicle was pulled, then `AWAKE` about 0.1 s later |
| `userPresence` (4) | Confirmed | `PRESENT` while seated, `NOT_PRESENT` after getting out. Also `PRESENT` while a person stood outside with a door open (about 2.6 s after the rear door opened; about 0.1 s after the driver door opened from outside), `NOT_PRESENT` again about 0.2 s after the rear door closed. So `PRESENT` does not mean "seated" |
| `gear` (7) | **Confirmed** | Each P/R/N/D change produced exactly one broadcast whose `gear` matched the operation order (`GEAR_REVERSE`, `GEAR_NEUTRAL`, `GEAR_DRIVE`, `GEAR_PARK`, twice in a row), about 10 s apart as operated. Works without an Infotainment session and with an unenrolled key |
| `closureStatuses` (1) | Confirmed for front driver door, rear driver door, rear trunk and charge port; Likely for front passenger door, rear passenger door and front trunk | **Present only while some field is not `CLOSED`**, absent when all are `CLOSED` (frame sizes match proto3, which never encodes a zero value). Driver door opened with the interior handle (round 2) or the exterior handle (round 3): `frontDriverDoor = OPEN`; absent again after closing. Rear driver door opened from outside: `rearDriverDoor = OPEN`. Rear trunk opened and closed by hand from outside: `rearTrunk = OPEN`, then absent. Charge port: `chargePort = OPEN` when opened (round 2) and when the latch was released by holding a door handle with a charger plugged in (round 3); absent again after unplugging and the port door closing. With the charger plugged in and latched, `chargePort` read `CLOSED`, so `CLOSED` does not mean "no cable". Trunk operated by the interior button: **no broadcast at all**. Waking vehicle: the first frames had `UNKNOWN` (value 3) for the four doors and both trunks; `frontTrunk` stayed `UNKNOWN` for one more frame. With no scenario given (Likely, B5): `frontPassengerDoor`, `rearPassengerDoor` and `frontTrunk` each read `OPEN` and were absent again later; how they were opened was not recorded. Not tested: trunk by key card, phone key or app |
| `uiDesire` (6) | Schema-only | Meaning not established |
| `detailedClosureStatus` (5) | Schema-only | Cybertruck tonneau; no data (Model Y) |
| field 8 (unknown) | Unknown | `{1: 1\|2, 2: 1\|2, 3: 1\|2}`, in all 176 `Status` frames of the later recordings (B5). Meaning unknown. **It is not a charging state:** sub-field 2 was 1 in all 75 frames in `DRIVE`, `REVERSE` or `NEUTRAL`, where no cable can be in use, so the charge port changes of rounds 2 and 3 (B4) do not define it. Sub-field 1 changes at irregular gaps, once 7 times in about 8 s. Of the 111 frames with sub-field 1 = 2, 108 were `PRESENT`, yet 17 frames with sub-field 1 = 1 were `PRESENT` too, so presence does not define it either. Sub-field 3 is not always 1: it was 2 in 7 frames, parked with nobody present, flipping every 8 to 16 s. A change of field 8 alone produces a broadcast (29 of 32 changes). Not related to lights or turn signals |

Field 8 values in the later recordings (176 frames, 32 value changes; the 2 frames with no gear field are in no column):

| `{1, 2, 3}` | `DRIVE`, `REVERSE`, `NEUTRAL` | `PARK`, `PRESENT` | `PARK`, `NOT_PRESENT` |
|---|---:|---:|---:|
| 2, 1, 1 | 75 | 28 | 2 |
| 1, 1, 1 | 0 | 16 | 33 |
| 1, 2, 1 | 0 | 1 | 8 |
| 2, 2, 1 | 0 | 3 | 1 |
| 1, 1, 2 | 0 | 0 | 7 |

Triggers seen: gear change, lock change (interior lock while seated, walk-away lock), door open/close via interior and
exterior handles (front and rear driver doors), trunk open/close by hand from outside, charge port open, latch release
and close, charging start, user presence change, waking up, a change of field 8 alone (Likely).

Frequency: a frame comes at each change, and it is also repeated with no change.

- **Repeat (Likely):** on an awake vehicle with a person present and nothing changing, the next `Status` came 60.0 s after
  the previous one: 47 gaps of 59.9 to 60.15 s (44 in `DRIVE`, 3 in `PARK`). No gap between two `Status` frames on one
  connection was longer (167 gaps, later recordings, B5).
- **What restarts the wait (Likely):** a frame with a change. 6 of the 47 gaps started at one. Frames other than `Status`
  did not restart it (3 gaps, with field 53, `whitelistInfo` or `commandStatus` in between).
- **Counterexample:** one gap between identical frames was 55.7 s, unexplained.
- **Not measured:** a sleeping vehicle, and an awake vehicle with nobody present. With `NOT_PRESENT` something kept changing
  (the largest of 42 gaps was 25.3 s), so no repeat was seen in that state.
- **Earlier rounds:** round 1 reported every 1 to 25 s while awake (26 B `UNLOCKED`, 28 B otherwise). Those frames are not
  available to check. Field 8 changes may explain it: in the later recordings 37 of the 38 gaps of 5 to 59 s ended at a frame
  that changed something. Round 2: sometimes several within 1 s. Round 3: nine frames within 0.5 s while waking, then one
  every 0.1 to 8 s while things changed.

Do not treat a repeat as a change.

Library rule: a missing `closureStatuses` in a `vehicleStatus` means "all closed" (A2), confirmed for both driver doors,
the rear trunk and the charge port. It is wrong only for a trunk operated with the interior button, which sends no
broadcast. `vehicleLockState` `UNLOCKED` is the zero value and is never encoded, so in a broadcast it cannot be told
apart from a missing lock state (seen while the vehicle was still `ASLEEP`).

### B3.2 `authenticationRequest` (field 3)

Status: **Confirmed** to occur; names of the sub-fields are unverified (Teslemetry schema, TESLEMETRY-EXT).

`AuthenticationRequest`:

| # | Field | Type | Meaning |
|---|---|---|---|
| 2 | `sessionInfo` (name unverified) | `AuthenticationRequestToken{token = 1}` | `token`: 20 bytes |
| 3 | `requestedLevel` (name unverified) | `AuthenticationLevel_E` | 0 `NONE`, 1 `UNLOCK`, 2 `DRIVE` (`AUTHENTICATION_LEVEL_` prefix) |
| 4 | `reasonsForAuth` (name unverified) | repeated `AuthenticationReason_E` | Why the vehicle asks (table below) |

`AuthenticationReason_E`: 0 `NOT_DOCUMENTED`, 1 `IDENTIFICATION`, 2 `POWER_ON_VEHICLE_REQUEST`, 3 `GTW_REQUEST`,
4 `UI_UNLOCK_PASSIVE_AUTH`, 5 `PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL`, 6 `PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL`,
7 `PASSIVE_UNLOCK_AUTOPRESENT_DOOR`, 8 `ENTERED_HIGHER_AUTH_ZONE`, 9 `WALK_UP_UNLOCK`, 10 `IMMOBILIZER`
(prefix `AUTHENTICATIONREASON_`). The related `AuthenticationResponse` (`UnsignedMessage` field 3, sent by a phone key) and
`AuthenticationRejection_E` exist in the schema; the library does not send them.

Public model (`VehicleEvent.AuthenticationRequest`, B2.2): `requestedLevel: AuthenticationLevel` is `NONE`, `UNLOCK`,
`DRIVE`, or `UNKNOWN` for a value outside the schema; proto3 cannot tell an absent level from `NONE`. `reasons:
List<AuthenticationReason>` has one entry per value sent, named as above without the prefix (`NOT_DOCUMENTED` …
`IMMOBILIZER`); a value outside the schema is `UNKNOWN` and is listed after the known ones (its original position is
lost; the exact order is in `raw`).

Observed: `requestedLevel = DRIVE`, `reasons = [PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL]` when the interior handle was pulled;
`ENTERED_HIGHER_AUTH_ZONE` was added when the charge port was opened and charging started. It came together with a
`vehicleStatus` (and field 53) in the same instant. In the later recordings (B5) all 6 wrapped frames had
`requestedLevel = DRIVE`, `reasons = [ENTERED_HIGHER_AUTH_ZONE]`, and one unwrapped frame had `IDENTIFICATION` (below).
Other reasons were not observed. In round 3 (exterior handles on a sleeping, then awake vehicle; charge port latch
release; trunk by hand) **no** `authenticationRequest` and no field 53 arrived; `PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL` was
never seen.

**Wrapping:** this vehicle normally sends it inside a `RoutableMessage` (broadcast form above). Unwrapped frames do occur
(Likely): 2 in the 12 connections of the later recordings (B5), both without `from_destination` and read as an unwrapped
`FromVCSECMessage`:

- 31 B, `authenticationRequest{requestedLevel = DRIVE, reasons = [IDENTIFICATION], token = 20 B}`, as a slow connect
  completed (B3.6).
- 12 B, `commandStatus{OPERATIONSTATUS_OK}`.

That is far from the "about every 1.1 s" reported elsewhere (Teslemetry PR #179). These frames fail the gate (B2.1), so
`Vehicle.events` does not deliver them and an app cannot see this `AuthenticationRequest`. Only the observation log shows
them, as `[drop:missing-source]` with `reinterpreted=` (`18-lib-troubleshooting.md`). `drop:unparseable`: 0 lines.

### B3.3 Field 53 (unknown)

Status: **Unknown**. `FromVCSECMessage` field 53: one message with repeated sub-field 1, each entry
`{1: small int, 2: 35 bytes}`.

- Size by entries: 54 bytes for 1 entry (ids 3, 4 and 7 seen), 95 bytes for 2 entries (ids 3 and 6), 205 to 220 bytes for
  5 entries (ids 1, 3, 4, 6, 7). The later recordings (B5) have 11 frames, four of them with 5 entries, all 220 bytes and
  all in the order 1, 3, 6, 7, 4, which is the field 48 value byte for byte (B3.4).
- **The ids are not key slots (Likely).** They overlap with slot numbers, but `keySummary` reported only slots 0 to 6 in all
  37 polls of a session in which id 7 appeared.
- **The 35-byte blob:** the first 33 bytes were identical in all 28 entries of those 11 frames, on two dates and while the
  number of enrolled keys changed. Only the last 2 bytes vary, per entry, even for the same id (28 values in 28 entries).
- **When it comes:** rounds 1 and 2 saw it with authentication events. In the later recordings only 1 of the 11 frames came
  within 3 s of an `authenticationRequest`. Three of the four 5-entry frames came within 0.7 s of field 8 sub-field 1
  changing from 2 to 1, and the fourth 0.4 s after a slow connect.
- The earlier guess of a per-key authentication challenge is weaker now: at most 16 bits differ between entries. Its
  meaning is unknown.

### B3.4 Field 48 (unknown)

Status: **Unknown**. `FromVCSECMessage` field 48: `{1: 5 bytes}`, in a 20-byte frame. All 3 frames of the later recordings
(B5) carry `01 03 06 07 04`, the id order of the 5-entry field 53 frames (B3.3).

Rate: round 1 saw it about every 5.0 s while the vehicle was awake and a person was present (gaps exist), and round 2 did
not see it. The later recordings have 3 frames in about 68 minutes connected, with no visible trigger. That does not fit a
steady heartbeat, the earlier guess. The condition is unknown.

### B3.5 Replies addressed to other clients

Not broadcasts. Replies to another BLE client reach our link too: `to_destination.routing_address` is that client's 16-byte
address, and the dispatcher drops them as `no-handler` (owner `other`). They fail the gate (B2.1), so they are never events.
Counts are from the later recordings (B5).

| Reply | Domain | Frame | Status | Seen |
|---|---|---|---|---|
| Signed, empty body, 16-byte `request_uuid` | VCSEC | 83 B | Confirmed | About every 1 to 2 minutes and right after a door event; 41 frames |
| Other VCSEC replies: encrypted with empty `request_uuid`, plaintext `whitelistInfo` and `whitelistEntryInfo`, `session_info` | VCSEC | 80 to 177 B | Likely | 178 frames |
| Encrypted reply, 16-byte `request_uuid` | Infotainment | 150 to 453 B; ciphertext up to 350 B | Likely | 1685 frames |
| `session_info` | Infotainment | 179 and 180 B | Likely | 66 frames |
| `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED` in `signedMessageStatus`, no payload | Infotainment | 106 B | Likely | 132 frames (`16-lib-errors.md` §Response size limit) |

- The Infotainment frames came at about one per second while that client was active (median gap 0.94 s). They appeared on 7
  of the 12 connections. One 17-minute connection, mostly in `DRIVE`, carried none, so driving alone does not produce them.
- The logs do not identify the client.
- Queries can stall while this traffic runs (`16-lib-errors.md` §Queries while another BLE client is active).

### B3.6 Field 44 (unknown)

Status: **Unknown** for the meaning, **Likely** for the link with slow connects. `FromVCSECMessage` field 44: a varint, value
2 in all 4 frames seen, in a 13-byte frame. Alone it is a `VehicleEvent.Unrecognized` with `"44"` in `unknownFields`.

All 12 connections of the later recordings (B5) split cleanly by how long `connect` took:

| Connections | `connecting` to `connected` | With a field 44 frame | With another client's Infotainment frames |
|---|---:|---:|---:|
| 8 fast | 0.10 to 0.21 s | 0 of 8 | 7 of 8 |
| 4 slow | 1.52 to 13.58 s | 4 of 4, 1.1 to 3.4 s after `connected` | 0 of 4 |

- The shortest fast connection lasted 26.7 s, so a frame at that delay would have been seen.
- It is not simply "vehicle locked": one fast connect to a locked vehicle with nobody present had no field 44.
- Why a connect is slow or fast, and what the value means, are unknown.

## B4. What is not available by broadcast

| Item | Result |
|---|---|
| Turn signals (left, right), hazards, headlights, high beams | **No message at all** when operated, and no BLE schema field for them (`vcsec.proto`, `vehicle.proto`, `car_server.proto`). The only known source is Tesla Fleet Telemetry (`LightsTurnSignal` 240, `LightsHazardsActive` 239, `LightsHighBeams` 241), an internet server path outside this library's no-server scope |
| Trunk opened with the interior button | No broadcast. Opened and closed by hand from outside: broadcast (B3.1). Key card, phone key and app not tested |
| Sleeping vehicle | Only its first frames while waking (round 3, `ASLEEP` for about 0.1 s). A vehicle that stays asleep was not observed |
| Charging cable | Round 2 (charge port open, charger connected, charging start): `chargePort = OPEN`, field 8 sub-field 2 changed 2 to 1, `authenticationRequest` with `ENTERED_HIGHER_AUTH_ZONE`, field 53 with one entry. Round 3 (latch release, unplug, port door closes): `chargePort` `CLOSED` while plugged in, `OPEN` after the latch release, absent after the port door closed; field 8 sub-field 2 changed 1 to 2. No separate broadcast for the cable itself was identified, and field 8 is not one (B3.1) |
| Another phone key approaching | Not recorded |

## B5. Observation method and environment

- iPhone 16 Pro, iOS 27, Model Y (firmware not recorded). Sample `dev.mirolab.TeslableSampleDevice` builds 7 and 8, key
  `SECURE_ENCLAVE`. The key does not need to be enrolled to observe.
- 1st round 2026-10-18 (build 7, one continuous recording, 71 lines). 2nd round 2026-10-24 (build 8, detailed unknown-field
  decode, one recording per scenario). 3rd round 2026-10-24 (build 8, one 40 s recording of 25 lines: sleeping and locked
  vehicle, rear driver door opened from outside, charge port latch released while holding the handle, charger unplugged,
  rear door closed, charge port door closed, trunk opened and closed by hand from outside, driver door opened and closed
  from outside).
- Later recordings: six recordings of the Monitor app on 2026-10-31 and 2026-11-01 (builds 13, 14 and 18), 12 connections,
  about 68 minutes connected, on the phone and vehicle above. No scenario was given, so what a person did is inferred from
  the frames and these findings stay Likely or Unknown.
- Reproduce: turn on the sample's observation mode, perform the scenario, then press the "show" button (and "copy whole
  result"). Details and log syntax: `18-lib-troubleshooting.md`.

## B6. Provenance

- Official `vehicle-command` at `a4b43c1` (Apache-2.0): routing, framing, VCSEC queries.
- Teslemetry `tesla-protocol` at `e6f7afb1` (Apache-2.0): proto snapshot with TESLEMETRY-EXT fields (`gear`, `uiDesire`,
  `authenticationRequest`, extra enum values); ADR-0002 (revised 2026-10-18).
- Teslemetry `python-tesla-fleet-api` PRs #178 and #179: facts only.
- Our own captures: the real-car results note (`docs/handoff/2026-10-18-m4-push-observation-results.md`) and the log
  analysis (`docs/analysis/2026-11-01-build18-link-health.md`), rechecked against the raw logs for this page.
- AGPL repositories were not used.
