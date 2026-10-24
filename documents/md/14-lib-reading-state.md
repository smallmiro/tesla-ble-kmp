# Reading Vehicle State

Scope: everything the library can read from the vehicle over BLE, in two parts.

- **Part A, on demand:** data you request. The four VCSEC queries on `Vehicle` (`vehicleStatus()`, `keySummary()`,
  `listKeys()`, `sessionInfo()`), with wire format, field tables and enum tables. None needs a key, a session or a
  wake-up: VCSEC answers over BLE while Infotainment is asleep.
- **Part B, broadcast:** data the vehicle pushes without a request. What each message is, how often it came, and what is
  confirmed on a real car. The library delivers the plain VCSEC broadcasts through `Vehicle.events` (`Flow<VehicleEvent>`,
  FR-035, B2); they still never reach a pending request, and the observation log still shows them.

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
| 7 | `gear` | `Gear_E` (TESLEMETRY-EXT; confirmed in broadcasts only, not yet checked in query replies) | Current gear | `gear`; absent or outside the schema becomes `UNKNOWN` |
| 8 | (unknown, not in schema) | message | Seen on a real car: `{1: 1\|2, 2: 1\|2, 3: 1}`; meaning unknown | Not surfaced |

`ClosureStatuses` has eight `ClosureState_E` fields, numbered 1 to 8: `frontDriverDoor`, `frontPassengerDoor`,
`rearDriverDoor`, `rearPassengerDoor`, `rearTrunk`, `frontTrunk`, `chargePort`, `tonneau`.

Mapping rules (`VcsecInfoMapping`): a value outside the schema (found in `unknownFields`) maps to `UNKNOWN` instead of the
zero value. proto3 cannot tell "missing" from the zero value for enums, so a missing `vehicleLockState` reads as
`UNLOCKED`; the library keeps that Go behaviour for the query. `gear` has no Go counterpart (the official Go `vcsec.proto`
ends at field 5); its zero value is `GEAR_UNKNOWN`, so a missing `gear` reads as `UNKNOWN`. Gear was confirmed in
broadcasts (B3.1); whether the vehicle fills it in a query reply has not been checked on a real car. The real-car
results show that the vehicle omits `closureStatuses` when every field is `CLOSED` (the zero value) and sends it while
any field is not (front and rear driver doors, rear trunk, charge port seen), so "absent = all `CLOSED`" matches what we
saw. The one exception is the trunk operated with the interior button, which sent no broadcast at all; the query
still reports it.

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
broadcast with the matching value; `GEAR_UNKNOWN` was not seen):

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

`getState(category)` (Infotainment) is planned for M5 and has no API; see Wake behaviour.

## `VehicleStatus` (public model)

| Field | Type | Notes |
|---|---|---|
| `lockState` | `LockState` | `UNLOCKED`, `LOCKED`, `INTERNAL_LOCKED`, `SELECTIVE_UNLOCKED`, `UNKNOWN` |
| `closures` | `Closures` | All eight are `CLOSED` if the vehicle did not send closure statuses (it omits them when everything is closed, like the Go getters) |
| `sleepStatus` | `SleepStatus` | `UNKNOWN`, `AWAKE`, `ASLEEP` (Infotainment) |
| `userPresence` | `UserPresence` | `UNKNOWN`, `NOT_PRESENT`, `PRESENT` |
| `tonneauPercentOpen` | `Int?` | Cybertruck only; `null` if not reported |
| `gear` | `Gear` | `UNKNOWN`, `PARK`, `DRIVE`, `REVERSE`, `NEUTRAL`. `UNKNOWN` if not sent (proto3 cannot tell that from `GEAR_UNKNOWN`) or outside the schema. Last constructor parameter. TESLEMETRY-EXT field 7, no Go counterpart (FR-036). Confirmed in broadcasts; not yet checked in query replies |

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
(`missing-source`). The library never decrypts a broadcast, and nothing it decrypted ever reaches an event (FR-112).

### B2.2 Event types

| Event | When | Fields |
|---|---|---|
| `VehicleEvent.Status` | The payload has `vehicleStatus` (field 1) | `status: VehicleStatus`, built with the same rules as `vehicleStatus()` (A2): absent `closureStatuses` = all `CLOSED`, values outside the schema = `UNKNOWN`, `gear` included |
| `VehicleEvent.AuthenticationRequest` | The payload has `authenticationRequest` (field 3) | `requestedLevel: AuthenticationLevel`, `reasons: List<AuthenticationReason>` (B3.2). The 20-byte token is not a field; it is in `raw` |
| `VehicleEvent.Unrecognized` | Neither field 1 nor field 3: only unknown fields (48, 53 on the real car), another `FromVCSECMessage` field, no payload, or a payload that does not decode as `FromVCSECMessage` (then `unknownFields` is empty) | none beyond `raw` and `unknownFields` |

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

- **A repeat is not a change.** The vehicle resends `vehicleStatus` every 1 to 25 s without a change, sometimes several
  times within one second (B3.1). The library does not filter duplicates; compare with the previous value yourself.
- **Trunk:** opening and closing the trunk by hand from outside produced broadcasts (`rearTrunk = OPEN`, then
  `closureStatuses` absent). Opening or closing it with the interior button produced **no** broadcast, so a trunk moved
  that way stays `CLOSED` in `events` until some other broadcast arrives. When the trunk state matters, confirm it with
  `vehicleStatus()`.
- **Waking vehicle:** the first frames of a sleeping vehicle can carry `UNKNOWN` closures and `gear = UNKNOWN`; they
  become real values within about 0.5 s (B3.1). Do not treat those `UNKNOWN` values as a change.
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
| Replies for other clients | Confirmed (not a broadcast) | Not an event (fails the gate) | B3.5 |

### B3.1 `vehicleStatus` broadcast

Same fields as A2. What the real-car observation showed (iPhone, Model Y; 1st round 71 lines, 2nd round per scenario,
3rd round one 40 s scenario of 25 lines starting from a sleeping vehicle):

| Field | Status | Observed |
|---|---|---|
| `vehicleLockState` (2) | Confirmed | `UNLOCKED` right after connect; `INTERNAL_LOCKED` repeated while seated with doors closed; `LOCKED` after getting out; `UNLOCKED` to `LOCKED` when walking away with the connection still up (link lost about 30 s later) |
| `vehicleSleepStatus` (3) | Confirmed | Rounds 1 and 2: `AWAKE` in all lines. Round 3: the first two frames were `ASLEEP` when the exterior handle of the sleeping vehicle was pulled, then `AWAKE` about 0.1 s later |
| `userPresence` (4) | Confirmed | `PRESENT` while seated, `NOT_PRESENT` after getting out. Also `PRESENT` while a person stood outside with a door open (about 2.6 s after the rear door opened; about 0.1 s after the driver door opened from outside), `NOT_PRESENT` again about 0.2 s after the rear door closed. So `PRESENT` does not mean "seated" |
| `gear` (7) | **Confirmed** | Each P/R/N/D change produced exactly one broadcast whose `gear` matched the operation order (`GEAR_REVERSE`, `GEAR_NEUTRAL`, `GEAR_DRIVE`, `GEAR_PARK`, twice in a row), about 10 s apart as operated. Works without an Infotainment session and with an unenrolled key |
| `closureStatuses` (1) | Confirmed for front driver door, rear driver door, rear trunk and charge port | **Present only while some field is not `CLOSED`**, absent when all are `CLOSED` (frame sizes match proto3, which never encodes a zero value). Driver door opened with the interior handle (round 2) or the exterior handle (round 3): `frontDriverDoor = OPEN`; absent again after closing. Rear driver door opened from outside: `rearDriverDoor = OPEN`. Rear trunk opened and closed by hand from outside: `rearTrunk = OPEN`, then absent. Charge port: `chargePort = OPEN` when opened (round 2) and when the latch was released by holding a door handle with a charger plugged in (round 3); absent again after unplugging and the port door closing. With the charger plugged in and latched, `chargePort` read `CLOSED`, so `CLOSED` does not mean "no cable". Trunk operated by the interior button: **no broadcast at all**. Waking vehicle: the first frames had `UNKNOWN` (value 3) for the four doors and both trunks; `frontTrunk` stayed `UNKNOWN` for one more frame. Not tested: passenger doors, front trunk, trunk by key card, phone key or app |
| `uiDesire` (6) | Schema-only | Meaning not established |
| `detailedClosureStatus` (5) | Schema-only | Cybertruck tonneau; no data (Model Y) |
| field 8 (unknown) | Unknown | `{1: 1\|2, 2: 1\|2, 3: 1}`. Sub-field 1 alternates 1 and 2 periodically. Sub-field 2 changed 2 to 1 when the charge port opened and charging started (round 2), was 1 while a charger was plugged in and changed 1 to 2 when the charge port closed after unplugging (round 3); it may be a charging-cable or charging state (1 = in use, 2 = idle), **unconfirmed** (the change came with the port door closing, more than 5 s after the cable was pulled out). Sub-field 3 was always 1. Not related to lights or turn signals |

Triggers seen: gear change, lock change (interior lock while seated, walk-away lock), door open/close via interior and
exterior handles (front and rear driver doors), trunk open/close by hand from outside, charge port open, latch release
and close, charging start, user presence change, waking up.

Frequency: also repeated with no change. 1st round: every 1 to 25 s while awake (26 B UNLOCKED, 28 B otherwise). 2nd
round: sometimes several within 1 s. 3rd round: nine frames within 0.5 s while waking, then one every 0.1 to 8 s while
things changed. Do not treat a repeat as a change.

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
`vehicleStatus` (and field 53) in the same instant. Other reasons were not observed. In round 3 (exterior handles on a
sleeping, then awake vehicle; charge port latch release; trunk by hand) **no** `authenticationRequest` and no field 53
arrived; `PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL` was never seen.

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
| Trunk opened with the interior button | No broadcast. Opened and closed by hand from outside: broadcast (B3.1). Key card, phone key and app not tested |
| Sleeping vehicle | Only its first frames while waking (round 3, `ASLEEP` for about 0.1 s). A vehicle that stays asleep was not observed |
| Charging cable | Round 2 (charge port open, charger connected, charging start): `chargePort = OPEN`, field 8 sub-field 2 changed 2 to 1, `authenticationRequest` with `ENTERED_HIGHER_AUTH_ZONE`, field 53 with one entry. Round 3 (latch release, unplug, port door closes): `chargePort` `CLOSED` while plugged in, `OPEN` after the latch release, absent after the port door closed; field 8 sub-field 2 changed 1 to 2. No separate broadcast for the cable itself was identified |
| Another phone key approaching | Not recorded |

## B5. Observation method and environment

- iPhone 16 Pro, iOS 27, Model Y (firmware not recorded). Sample `dev.mirolab.TeslableSampleDevice` builds 7 and 8, key
  `SECURE_ENCLAVE`. The key does not need to be enrolled to observe.
- 1st round 2026-10-18 (build 7, one continuous recording, 71 lines). 2nd round 2026-10-24 (build 8, detailed unknown-field
  decode, one recording per scenario). 3rd round 2026-10-24 (build 8, one 40 s recording of 25 lines: sleeping and locked
  vehicle, rear driver door opened from outside, charge port latch released while holding the handle, charger unplugged,
  rear door closed, charge port door closed, trunk opened and closed by hand from outside, driver door opened and closed
  from outside).
- Reproduce: turn on the sample's observation mode, perform the scenario, then press the "show" button (and "copy whole
  result"). Details and log syntax: `18-lib-troubleshooting.md`.

## B6. Provenance

- Official `vehicle-command` at `a4b43c1` (Apache-2.0): routing, framing, VCSEC queries.
- Teslemetry `tesla-protocol` at `e6f7afb1` (Apache-2.0): proto snapshot with TESLEMETRY-EXT fields (`gear`, `uiDesire`,
  `authenticationRequest`, extra enum values); ADR-0002 (revised 2026-10-18).
- Teslemetry `python-tesla-fleet-api` PRs #178 and #179: facts only.
- Our own captures: the real-car results note (`docs/handoff/2026-10-18-m4-push-observation-results.md`).
- AGPL repositories were not used.
