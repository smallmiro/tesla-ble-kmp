# Commands

Scope: on `main` (M4) the session calls are `startSession()` and `disconnect()`, and the 12 VCSEC control commands are
implemented. **Infotainment control (M5) is not implemented yet**; its table below is copied from `docs/prd/PRD.md` §5
and names may change when the milestone lands. Go original for every command:
`05-command-catalog.md`.

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
- Retried automatically at 1 s intervals while `error.shouldRetry()`; never resent when `mayHaveSucceeded`.
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

`Uncertain` (timeout after sending, unreadable reply) means the vehicle may have acted. **Do not resend**; read
`vehicleStatus()` to see the lock state and closures (`16-lib-errors.md` §Per-command re-query). This matters most for
the toggles `openTrunk()` / `actuateTrunk()`.

`sessions` follows the real session state: after a command ends with `NoSession`, or when a session event arrives
(refreshed session info, a stale cache entry), the library re-checks the domain and `sessions` shows `Ready` or `None`
accordingly. A session restored from the cache that the vehicle no longer accepts (for example the cache was stored for
another vehicle's key) shows `None` after the first command fails with `NoSession`; `startSession` recovers it.

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
