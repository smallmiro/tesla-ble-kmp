# Reading Vehicle State

Scope: the three VCSEC queries on `Vehicle` (M3). None needs a key, a session or a wake-up: VCSEC answers over BLE
while Infotainment is asleep. **`getState()` (Infotainment, M5) is not implemented.** Go original:
`05-command-catalog.md` §B, `03-protocol.md` §12.

## Calls

| Call | Returns | Go original | FR |
|---|---|---|---|
| `vehicleStatus(timeout = null)` | `VehicleResult<VehicleStatus>` | `BodyControllerState` (`InformationRequest{GET_STATUS}`) | FR-031 |
| `keySummary(timeout = null)` | `VehicleResult<WhitelistSummary>` | `KeySummary` (`GET_WHITELIST_INFO`) | FR-032 |
| `listKeys(timeout = null)` | `VehicleResult<List<WhitelistEntry>>` | `KeySummary` + `KeyInfoBySlot` per occupied slot | FR-025 |

- `timeout = null` uses `TeslaBleConfig.commandTimeout` (5 s). For `listKeys` the timeout covers all requests together.
- VCSEC requests are serialized per connection: a query waits behind any VCSEC request already in flight, and that
  wait counts against its timeout.
- These calls are queries, so an `Uncertain` result is safe to ask again.

## `VehicleStatus`

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
(newer firmware) instead of failing.

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
  awake vehicle. The planned `wakeUp()` (FR-042, M4) uses only the VCSEC session.
- After an `Uncertain` VCSEC command result (M4), re-read with `vehicleStatus()` instead of resending.
