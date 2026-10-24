# Reading Vehicle State

Scope: everything the library can read from the vehicle over BLE, in two parts.

- **Part A, on demand:** data you request. The four VCSEC queries on `Vehicle` (`vehicleStatus()`, `keySummary()`,
  `listKeys()`, `sessionInfo()`), with wire format, field tables and enum tables. None needs a key, a session or a
  wake-up: VCSEC answers over BLE while Infotainment is asleep.
- **Part B, broadcast:** data the vehicle pushes without a request. What each message is, how often it came, and what is
  confirmed on a real car. The library drops these messages today (the observation log is the only way to see them);
  a public `vehicle.events` API is planned for M5 and **does not exist yet**.

**`getState()` (Infotainment, M5) is not implemented.** Go original: `05-command-catalog.md` §B, `03-protocol.md` §11–12,
`02-ble-transport.md`. Control payloads (lock, trunk, wake) are in `15-lib-commands.md`.

Status labels used in Part B: **Confirmed** = seen on a real vehicle (results note, see Provenance);
**Schema-only** = defined in the proto files, never seen by us; **Unknown** = seen on the wire, meaning not known.

## Calls

| Call | Returns | Go original | FR |
|---|---|---|---|
| `vehicleStatus(timeout = null)` | `VehicleResult<VehicleStatus>` | `BodyControllerState` (`InformationRequest{GET_STATUS}`) | FR-031 |
| `keySummary(timeout = null)` | `VehicleResult<WhitelistSummary>` | `KeySummary` (`GET_WHITELIST_INFO`) | FR-032 |
| `listKeys(timeout = null)` | `VehicleResult<List<WhitelistEntry>>` | `KeySummary` + `KeyInfoBySlot` per occupied slot | FR-025 |
| `sessionInfo(publicKey, domain, timeout = null)` | `VehicleResult<SessionInfoSummary>` | `SessionInfo` (`session_info_request`) | FR-024 |

- `timeout = null` uses `TeslaBleConfig.commandTimeout` (5 s). For `listKeys` the timeout covers all requests together.
- VCSEC requests are serialized per connection: a query waits behind any VCSEC request already in flight, and that
  wait counts against its timeout.
- These calls are queries, so an `Uncertain` result is safe to ask again.

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
| 4 | `userPresence` | `UserPresence_E` | Person in the vehicle | `userPresence` |
| 5 | `detailedClosureStatus` | `DetailedClosureStatus` | `tonneauPercentOpen` (1, uint32), Cybertruck | `tonneauPercentOpen` (`null` if absent) |
| 6 | `uiDesire` | `UIDesire_E` (TESLEMETRY-EXT) | In schema only | **Not surfaced** (planned M5) |
| 7 | `gear` | `Gear_E` (TESLEMETRY-EXT, confirmed on a real car) | Current gear | **Not surfaced** (planned M5) |
| 8 | (unknown, not in schema) | message | Seen on a real car: `{1: 1\|2, 2: 1\|2, 3: 1}`; meaning unknown | Not surfaced |

`ClosureStatuses` has eight `ClosureState_E` fields, numbered 1 to 8: `frontDriverDoor`, `frontPassengerDoor`,
`rearDriverDoor`, `rearPassengerDoor`, `rearTrunk`, `frontTrunk`, `chargePort`, `tonneau`.

Mapping rules (`VcsecInfoMapping`): a value outside the schema (found in `unknownFields`) maps to `UNKNOWN` instead of the
zero value. proto3 cannot tell "missing" from the zero value for enums, so a missing `vehicleLockState` reads as
`UNLOCKED`; the library keeps that Go behaviour for the query. The real-car results show that the vehicle omits
`closureStatuses` when everything is closed and sends it while something is open (driver door, charge port seen), so
"absent = all `CLOSED`" matches what we saw for doors and the charge port. Trunk opened by the interior button was not
visible in broadcasts, so read the trunk with this query.

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
| Slot request payload | `UnsignedMessage{InformationRequest{GET_WHITELIST_ENTRY_INFO, slot = N}}` = hex `0a04080620NN`, for example slot 0 `0a0408062000`, slot 2 `0a0408062002` (`informationRequestType` 6, `slot` field 4). `NN` is a varint; slots of 128 or more need two bytes |
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
| Errors | `signedMessageStatus` fault `UNKNOWN_KEY_ID`, or `SessionInfo.status = KEY_NOT_ON_WHITELIST`, becomes `Failure(KeyNotPaired)`; missing `session_info` or an undecodable one becomes `Failure(BadResponse)`; no reply in time is `Uncertain(Timeout(afterSend = true))` |
| Side effect | Asking with the connection's own key also builds that domain's session, so `sessions` shows `Ready(fromCache = false)` |

`Signatures.SessionInfo` (`signatures.proto`):

| # | Field | Type | Public `SessionInfoSummary` |
|---|---|---|---|
| 1 | `counter` | uint32 | `counter` |
| 2 | `publicKey` | bytes | `vehiclePublicKey` (`null` if not a valid 65-byte key) |
| 3 | `epoch` | bytes | Not surfaced |
| 4 | `clock_time` | fixed32 | `clockTime` (seconds) |
| 5 | `status` | `Session_Info_Status` | Not checked: `OK` 0, `KEY_NOT_ON_WHITELIST` 1, `INVALID_HANDLE` 2 (Teslemetry-only) |
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

`Gear_E` (`VehicleStatus.gear`, field 7, TESLEMETRY-EXT, **Confirmed on a real car**: each of P, R, N, D produced a
broadcast with the matching value; `GEAR_UNKNOWN` was not seen):

| Value | Name |
|---|---|
| 0 | `GEAR_UNKNOWN` |
| 1 | `GEAR_PARK` |
| 2 | `GEAR_DRIVE` |
| 3 | `GEAR_REVERSE` |
| 4 | `GEAR_NEUTRAL` |

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

`getState(category)` (Infotainment) is planned for M5 and has no API; see Wake behaviour.

## `VehicleStatus` (public model)

| Field | Type | Notes |
|---|---|---|
| `lockState` | `LockState` | `UNLOCKED`, `LOCKED`, `INTERNAL_LOCKED`, `SELECTIVE_UNLOCKED`, `UNKNOWN` |
| `closures` | `Closures` | All eight are `CLOSED` if the vehicle did not send closure statuses (it omits them when everything is closed, like the Go getters) |
| `sleepStatus` | `SleepStatus` | `UNKNOWN`, `AWAKE`, `ASLEEP` (Infotainment) |
| `userPresence` | `UserPresence` | `UNKNOWN`, `NOT_PRESENT`, `PRESENT` |
| `tonneauPercentOpen` | `Int?` | Cybertruck only; `null` if not reported |

`Closures` has eight `ClosureState` fields: `frontDriverDoor`, `frontPassengerDoor`, `rearDriverDoor`,
`rearPassengerDoor`, `rearTrunk`, `frontTrunk`, `chargePort`, `tonneau`. `ClosureState` values: `CLOSED`, `OPEN`, `AJAR`,
`UNKNOWN`, `FAILED_UNLATCH`, `OPENING`, `CLOSING`. Every enum maps values the library does not know to `UNKNOWN`
(newer firmware) instead of failing. `gear` and `uiDesire` are not in this model yet (planned M5).

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

## Wake behaviour

- `vehicleStatus()`, `keySummary()` and `listKeys()` never wake the vehicle. Use `sleepStatus` to show whether
  Infotainment is asleep.
- Planned (M5, not implemented): `getState(category)` for the 12 Infotainment categories (charge, climate, drive,
  location, closures, charge schedule, preconditioning schedule, tire pressure, media, media detail, software update,
  parental controls) returning Wire generated types (FR-033). It needs an authenticated Infotainment session and an
  awake vehicle. `wakeUp()` (FR-042, M4) uses only the VCSEC session.
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
| 48, 53 | unknown | | Seen on a real car, not in any schema (B3) |

## B2. What the library does with broadcasts today

- The dispatcher **drops** every broadcast: it has no routing address, so `route()` returns the drop reason `TO_DOMAIN`
  (same as the Go original). Broadcasts therefore never reach a pending request and cannot be mixed up with replies,
  which are matched by our routing address.
- The only way to see them is the observation log: DEBUG, tag `observe`, line head `[drop:to-domain]`. Format and field
  names: `18-lib-troubleshooting.md` §Observation log. Meaning of each message: B3 below.
- **A public `vehicle.events` API is planned for M5 and is not implemented.** There is no public callback, flow or
  model type for broadcasts today. Until then read lock, closures, sleep and presence with `vehicleStatus()`.

## B3. Message catalogue

| Message | Status | Where |
|---|---|---|
| `vehicleStatus` (field 1) | Confirmed | B3.1 |
| `authenticationRequest` (field 3) | Confirmed (field names unverified) | B3.2 |
| Field 53 | Unknown | B3.3 |
| Field 48 | Unknown | B3.4 |
| Replies for other clients | Confirmed (not a broadcast) | B3.5 |

### B3.1 `vehicleStatus` broadcast

Same fields as A2. What the real-car observation showed (iPhone, Model Y; 1st round 71 lines, 2nd round per scenario):

| Field | Status | Observed |
|---|---|---|
| `vehicleLockState` (2) | Confirmed | `UNLOCKED` right after connect; `INTERNAL_LOCKED` repeated while seated with doors closed; `LOCKED` after getting out; `UNLOCKED` to `LOCKED` when walking away with the connection still up (link lost about 30 s later) |
| `vehicleSleepStatus` (3) | Confirmed | `AWAKE` in all lines. A sleeping vehicle was **not** observed |
| `userPresence` (4) | Confirmed | `PRESENT` while seated, `NOT_PRESENT` after getting out |
| `gear` (7) | **Confirmed** | Each P/R/N/D change produced exactly one broadcast whose `gear` matched the operation order (`GEAR_REVERSE`, `GEAR_NEUTRAL`, `GEAR_DRIVE`, `GEAR_PARK`, twice in a row), about 10 s apart as operated. Works without an Infotainment session and with an unenrolled key |
| `closureStatuses` (1) | Confirmed for driver door and charge port | **Present only while something is open**, absent when all closed. Driver door opened with the interior handle: `frontDriverDoor = OPEN`; about 6 s after closing it the field is absent again. Charge port opened: `chargePort = OPEN`. Trunk operated by the interior button: **no broadcast at all**; other trunk methods (by hand outside, key card, phone key, app) are not tested |
| `uiDesire` (6) | Schema-only | Meaning not established |
| `detailedClosureStatus` (5) | Schema-only | Cybertruck tonneau; no data (Model Y) |
| field 8 (unknown) | Unknown | `{1: 1\|2, 2: 1\|2, 3: 1}`. Sub-field 1 alternates 1 and 2 periodically, sub-field 2 changed 2 to 1 when the charge port opened and charging started, sub-field 3 was always 1. Meaning unknown. Not related to lights or turn signals |

Triggers seen: gear change, lock change (interior lock while seated, walk-away lock), door open/close via interior
handle, charge port open and charging start, user presence change.

Frequency: also repeated with no change. 1st round: every 1 to 25 s while awake (26 B UNLOCKED, 28 B otherwise). 2nd
round: sometimes several within 1 s. Do not treat a repeat as a change.

Library rule: a missing `closureStatuses` in a `vehicleStatus` means "all closed" for doors and the charge port (A2), but
not for the trunk when it is operated with the interior button.

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

Observed: `requestedLevel = DRIVE`, `reasons = [PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL]` when the interior handle was pulled;
`ENTERED_HIGHER_AUTH_ZONE` was added when the charge port was opened and charging started. It came together with a
`vehicleStatus` (and field 53) in the same instant. Other reasons were not observed.

**Wrapping:** this vehicle sent it inside a `RoutableMessage` (broadcast form above), unlike reports elsewhere
(Teslemetry PR #179) of an unwrapped `FromVCSECMessage{authenticationRequest}` about every 1.1 s. No unwrapped frame
was seen on this vehicle (`drop:missing-source` and `drop:unparseable`: 0 lines).

### B3.3 Field 53 (unknown)

Status: **Unknown**. `FromVCSECMessage` field 53: repeated `{1: small int, 2: 35 bytes}`.

- 54 bytes total (1 entry, id 4) right after connect and at the charge port event; 205 to 220 bytes (5 entries, ids
  1, 3, 4, 6, 7 in some order) at door and lock events. The 35-byte blobs of one message differ only in the last
  2 bytes.
- It arrives with authentication events. The ids 1, 3, 4, 6, 7 overlap with enrolled key slot numbers (7 keys seen in M3),
  so it is probably a per-key authentication challenge. **Unconfirmed.**

### B3.4 Field 48 (unknown)

Status: **Unknown**. `FromVCSECMessage` field 48, 20 bytes, about every 5.0 s while the vehicle is awake and a person is
present (gaps exist). Seen only in the 1st round; it did not appear in the 2nd. Likely a heartbeat; the condition is
unknown.

### B3.5 Replies addressed to other clients

Not broadcasts. A signed 83-byte reply (`to_destination.routing_address` = another client's 16-byte address, 16-byte
`request_uuid`, empty body) answers another phone's VCSEC request. It reaches our link too, about every 1 to 2 minutes and
right after a door event. The dispatcher drops it as `no-handler` (owner `other`).

## B4. What is not available by broadcast

| Item | Result |
|---|---|
| Turn signals (left, right), hazards, headlights, high beams | **No message at all** when operated, and no BLE schema field for them (`vcsec.proto`, `vehicle.proto`, `car_server.proto`). The only known source is Tesla Fleet Telemetry (`LightsTurnSignal` 240, `LightsHazardsActive` 239, `LightsHighBeams` 241), an internet server path outside this library's no-server scope |
| Trunk opened with the interior button | No broadcast (other methods untested) |
| Sleeping vehicle | Not observed |
| Charging cable, other phone keys approaching | Not recorded |

## B5. Observation method and environment

- iPhone 16 Pro, iOS 27, Model Y (firmware not recorded). Sample `dev.mirolab.TeslableSampleDevice` builds 7 and 8, key
  `SECURE_ENCLAVE`. The key does not need to be enrolled to observe.
- 1st round 2026-10-18 (build 7, one continuous recording, 71 lines). 2nd round 2026-10-24 (build 8, detailed unknown-field
  decode, one recording per scenario).
- Reproduce: turn on the sample's observation mode, perform the scenario, then press the "show" button (and "copy whole
  result"). Details and log syntax: `18-lib-troubleshooting.md`.

## B6. Provenance

- Official `vehicle-command` at `a4b43c1` (Apache-2.0): routing, framing, VCSEC queries.
- Teslemetry `tesla-protocol` at `e6f7afb1` (Apache-2.0): proto snapshot with TESLEMETRY-EXT fields (`gear`, `uiDesire`,
  `authenticationRequest`, extra enum values); ADR-0002 (revised 2026-10-18).
- Teslemetry `python-tesla-fleet-api` PRs #178 and #179: facts only.
- Our own captures: the real-car results note (`docs/handoff/2026-10-18-m4-push-observation-results.md`).
- AGPL repositories were not used.
