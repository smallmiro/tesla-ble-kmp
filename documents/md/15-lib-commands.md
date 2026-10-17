# Commands

Scope: on `main` (M3) the only session-level calls are `startSession()` and `disconnect()`. **No control command is
implemented yet.** VCSEC control arrives in M4, Infotainment control in M5. The planned table below is copied from
`docs/prd/PRD.md` §5; names may change when the milestone lands. Go original for every command:
`05-command-catalog.md`.

## Implemented

| Call | Returns | Notes |
|---|---|---|
| `startSession(domains = VehicleDomain.ALL, timeout = null)` | `VehicleResult<Unit>` | Handshake per domain, in parallel; one failure cancels the others (FR-012). Domains already `Ready` from the session cache succeed without a request. Needs a key (`RequiresKey`) that is enrolled (`KeyNotPaired`). `timeout = null` uses `handshakeTimeout` (20 s) |
| `disconnect()` | `Unit` | Saves the session cache, wipes session keys, closes the link. Idempotent (FR-005) |

`sessions` reports `Handshaking` while `startSession` runs, then `Ready(fromCache = false)` or back to `None` on failure,
cancellation or disconnect.

## Rules every control command will follow

These are already implemented in the dispatcher (M2) and apply as soon as a command is exposed:

- Authenticated with AES-GCM over the domain session; `expires_at` lifetime is `commandLifetime` (5 s).
- Retried automatically at 1 s intervals while `error.shouldRetry()`; never resent when `mayHaveSucceeded`.
- VCSEC commands are serialized per connection; Infotainment commands may run in parallel.
- An `Uncertain` result means "query state, do not resend" (`16-lib-errors.md`).

## Planned: VCSEC (M4)

| FR | Method | Priority |
|---|---|---|
| FR-040 | `lock()` | P0 |
| FR-041 | `unlock()` | P0 |
| FR-042 | `wakeUp()` (VCSEC session only) | P0 |
| FR-043 | `remoteDrive()` | P1 |
| FR-044 | `autoSecureVehicle()` (Model X) | P1 |
| FR-045 | `openTrunk()` / `actuateTrunk()`, `closeTrunk()` | P0 |
| FR-046 | `openFrunk()` | P0 |
| FR-047 | `openTonneau()`, `closeTonneau()`, `stopTonneau()` (Cybertruck) | P1 |
| FR-022–027 | Key enrollment and removal | see `13-lib-pairing.md` |

## Planned: Infotainment (M5)

| FR | Methods | Priority |
|---|---|---|
| FR-034 | `ping()` | P1 |
| FR-050 | `ventWindows()`, `closeWindows()` | P1 |
| FR-051 | `setSunroof(level)` | P1 |
| FR-052 | `openChargePort()`, `closeChargePort()` | P1 |
| FR-053 | `honkHorn()`, `flashLights()` | P1 |
| FR-060, FR-062 | `climateOn()`, `climateOff()`, `setClimateTemp(driverC, passengerC)` | P0 |
| FR-061, FR-063–069 | Seat heater / cooler, steering wheel heater, auto seat climate, preconditioning max, climate keeper, bioweapon defense, cabin overheat protection | P1 |
| FR-070–072 | `chargeStart()`, `chargeStop()`, `setChargeLimit(percent)`, `setChargingAmps(amps)` | P0 |
| FR-073–083 | Standard / max range, scheduled charging and departure, low power, keep accessory power, charge and precondition schedules | P1 |
| FR-084, FR-085 | `setSentryMode(on)`, valet mode | P1 |
| FR-090–092 | Volume, media controls, software update schedule / cancel | P1 |
| FR-033 | `getState(category)` | see `14-lib-reading-state.md` |

Deferred to v1.1 (P2): FR-054 `triggerHomelink`, FR-086 `clearPinToDrive`, FR-087 speed limit, FR-088 parental
controls, FR-089 guest mode, FR-093 `setVehicleName`, FR-094 `getNearbyCharging`. `SetPINToDrive` is not possible over
BLE and is excluded.
