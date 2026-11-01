# Error Handling

Scope: M1 error model, M2 retries and timeouts, M3 transport and key store errors, M4 `InsufficientPrivileges`, `UnknownVcsecError` and per-command re-query for VCSEC (Infotainment commands are added with M5), broadcast events (`Vehicle.events`, FR-035), `KeyStoreError.Locked` on a locked iPhone from the key store and the handshake (FR-037), `getState` results, the response size limit and reply authenticity (FR-033). Go original: `08-errors.md` (MessageFault meanings and remedies in §3.1).

## Three outcomes (`VehicleResult<T>`)

| Outcome | Meaning | What the app does |
|---|---|---|
| `Success(value)` | The vehicle confirmed | Use the value |
| `Uncertain(error)` | A command: the vehicle may have executed it but it could not be confirmed (`error.mayHaveSucceeded == true`). A query: no data was confirmed; nothing was executed (§`getState` results) | Command: do not resend, query state again (`vehicleStatus()` for VCSEC; `getState()` for Infotainment state). Query: keep the last value and ask again later |
| `Failure(error)` | Not executed | Branch on `error` |

Swift: `switch onEnum(of: result) { case .success(let s): … case .uncertain(let u): … case .failure(let f): … }`.
Full example: `12-lib-getting-started.md`.

Caller cancellation is not converted to a value: `CancellationException` propagates.

## `VehicleError` hierarchy

Every error has `message` (English), `mayHaveSucceeded` and `temporary`. `shouldRetry()` is
`!mayHaveSucceeded && temporary` (Go `ShouldRetry`). The library already retries with this rule (M2), so apps do not
write retry loops. One exception: `KeyStoreError.Locked` is `temporary` but the library never retries it, because only
the user can unlock the phone; the app calls again after the unlock.

| Type | When | temporary | mayHaveSucceeded |
|---|---|---|---|
| `ProtocolFault(fault)` | The vehicle sent a `MessageFault` code (except `UNKNOWN_KEY_ID` → `KeyNotPaired`, `INSUFFICIENT_PRIVILEGES` → `InsufficientPrivileges`) | true only for BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL, INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG | true only for NONE, RESPONSE_MTU_EXCEEDED |
| `KeyNotPaired` | `UNKNOWN_KEY_ID`, or session_info `KEY_NOT_ON_WHITELIST` | false | false |
| `InsufficientPrivileges` | `INSUFFICIENT_PRIVILEGES` (fault 7): the key's role or the vehicle state does not allow the command (Driver managing keys, Vehicle Monitor sending control commands, driving, valet) | false | false |
| `Busy` | `operation_status WAIT` | true | false |
| `UnknownFault(rawCode)` | A `MessageFault` code that is not in this library's proto schema (newer firmware). `message` is `unrecognized error code <rawCode>`; check for a library update | false | false |
| `UnknownKeychainCode(rawCode)` | A `whitelistOperationInformation` code that is not in this library's proto schema. `message` is `keychain operation failed: unrecognized code <rawCode>` | false | false |
| `UnknownVcsecError(rawCode)` | A VCSEC `nominalError` code that is not in this library's proto schema. `message` is `vcsec could not execute command: unrecognized error code <rawCode>` | false | false |
| `UnknownResponse` | Unrecognized `session_info.status` or `operation_status` value (no code). Includes session_info status 2 `SESSION_INFO_STATUS_INVALID_HANDLE`, which exists only in the Teslemetry schema (the Go code treats it as unknown) | false | false |
| `NotConnected`, `NoSession`, `RequiresKey` | Call order error | false | false |
| `BadResponse(detail)` | Response could not be parsed | false | true for VCSEC and Infotainment responses |
| `KeychainRejected(code)` | Key add or remove rejected | false | false |
| `VcsecRejected(error)` | VCSEC `nominalError` | false | false |
| `InfotainmentRejected(reason)` | `actionStatus ERROR` | false | false |
| `TransportError.*` | Scan, connect, write (M3) | true only for `ScanTimeout`, `ConnectFailed`, `WriteFailed` | false |
| `KeyStoreError.*` | Key store (M3; `Locked` FR-037, also from `startSession()`) | true only for `Locked` | false |
| `Timeout(afterSend)` | Command timed out | true | `afterSend` |
| `InvalidArgument(detail)` | Bad call argument | false | false |

**Proto schema and error codes (2026-10-18, ADR-0002 revision).** "In the proto schema" means the pinned
Teslemetry/tesla-protocol snapshot (`19-lib-architecture.md`), a superset of the official protos. Codes it defines are no
longer reported as `Unknown*` with a raw code but as the named type, with the same `temporary = false` and
`mayHaveSucceeded = false`. The names of these codes come from the schema and are not confirmed on a real vehicle:

| Code | Was | Now |
|---|---|---|
| `MessageFault` 29 `MESSAGEFAULT_ERROR_COMMAND_REQUIRES_PHYSICAL_PROXIMITY` | `UnknownFault(29)` | `ProtocolFault(fault)`, not retried |
| `whitelistOperationInformation` 29–32 (rider and predelivery key rules) | `UnknownKeychainCode(n)` | `KeychainRejected(code)` |
| `GenericError` 8 `GENERICERROR_KEY_NOT_FOUND`, 9 `GENERICERROR_NOT_SUPPORTED` | `UnknownVcsecError(n)` | `VcsecRejected(error)` |
| session_info status 2 `SESSION_INFO_STATUS_INVALID_HANDLE` | `UnknownResponse` | `UnknownResponse` (the official Go code does not know it either) |

Match on the error type, not on `message`: `message` now carries the code name instead of the number.

## Transport and key store errors (M3)

The library already retries connecting at 1 s intervals inside the `connect` timeout. The last column says what the app
should do.

| Error | Meaning | temporary | App action |
|---|---|---|---|
| `TransportError.ScanTimeout` | No advertisement found in time | true | Check the vehicle is in range, try again |
| `TransportError.MaxConnectionsExceeded` | The vehicle already has 3 BLE connections | false | Disconnect another device, try again |
| `TransportError.ConnectFailed(cause)` | Connect, service discovery or subscription failed | true | Retry after a moment |
| `TransportError.Disconnected` | BLE link dropped | false | `Vehicle.disconnect()`, then a new `connect` |
| `TransportError.BluetoothOff` | Bluetooth is off | false | Ask the user to turn it on |
| `TransportError.PermissionDenied` | No Bluetooth permission | false | Request permission or open Settings |
| `TransportError.WriteFailed(cause)` | Write failed | true | Retried by the library; reconnect if it persists |
| `KeyStoreError.HardwareUnavailable` | `REQUIRE_HARDWARE` but no hardware key | false | Use `PREFER_HARDWARE` or check the device |
| `KeyStoreError.KeyNotFound` | No key under that alias | false | Create one with `getOrCreate` |
| `KeyStoreError.PlatformFailure(cause)` | Platform key store error. `stored key is corrupted` means the stored key is damaged | false | Corrupted keys are not replaced automatically: `delete` then `getOrCreate`, and **enroll the new key again** (`17-lib-platform-notes.md` §Key storage) |
| `KeyStoreError.Locked` | iOS: the phone is locked and the key is `WHEN_UNLOCKED`, or the phone was not unlocked since restart (`errSecInteractionNotAllowed`, -25308). Returned by `getOrCreate`, `get`, `delete`; creation does not fall back to a software key. Also returned by `startSession()` when the lock blocks a Secure Enclave key object read before the phone locked (issue #96) | true | **Do not delete or re-create the key.** Call again after the phone is unlocked. For use while locked, create a key with `KeyAccessibility.AFTER_FIRST_UNLOCK` under a new alias and enroll it (`13-lib-pairing.md` §Key accessibility). The library retries neither key store calls nor the handshake |

Call `Vehicle.disconnect()` even after the link dropped so session keys and the scope are released.

## Broadcast events (`Vehicle.events`)

`events` has no error values and never throws. It completes normally when the link drops or `disconnect()` is called
(`14-lib-reading-state.md` B2); the reason for a link loss is in `connection` (`Disconnected(reason)`).

| Situation | What happens | App action |
|---|---|---|
| A collector falls more than 64 frames behind | New frames are dropped for every collector of that connection and counted per connection. `WARN` (tag `Dispatcher`) on the first drop and every 100th: `Dropping plain broadcast because the broadcast buffer is full (dropped N since connect)`. **Not an error:** no `VehicleError`, and the flow keeps running | Keep the collector light and hand slow work to another coroutine. The vehicle repeats its status every few seconds, so the latest state comes again; or read `vehicleStatus()` |
| A broadcast the library cannot decode | `VehicleEvent.Unrecognized` with an empty `unknownFields`; the bytes are in `raw` | Keep `raw` (VIN-masked) for analysis |

## Retries and timeouts (M2)

The library retries with the same rules as Go `vehicle-command`.

| Situation | Library behaviour |
|---|---|
| `error.shouldRetry()`: `BUSY`, `TIMEOUT`, `INVALID_SIGNATURE`, `INVALID_TOKEN_OR_COUNTER`, `INTERNAL`, `INCORRECT_EPOCH`, `TIME_EXPIRED`, `TIME_TO_LIVE_TOO_LONG`, `Busy` (VCSEC `WAIT`), temporary transport errors | After 1 s (BLE `retryInterval`) it **authorizes again with a new counter, nonce and expires_at** and resends. Session info attached to the error response is applied first |
| `mayHaveSucceeded` errors (timeout with no response, `RESPONSE_MTU_EXCEEDED`, unparsable VCSEC or Infotainment response) | **Never resent automatically** (prevents running a toggle twice). Returned as `Uncertain`; query state again |
| Timeout (`timeout`, default 5 s) before sending or while waiting to retry | `Failure(Timeout(afterSend = false))` |
| Timeout while waiting for the response | `Uncertain(Timeout(afterSend = true))` |
| Handshake timeout (`handshakeTimeout`, default 20 s) | `Failure(Timeout(afterSend = false))`; no session, no side effect |
| Handshake blocked by the phone lock (iOS, FR-037) | `Failure(KeyStoreError.Locked)` right after the vehicle's reply, **not retried** although `temporary`; no session. A cached session the lock kept from being restored stays in the cache |
| VCSEC commands | One at a time per connection. Time spent waiting for the previous one counts against `timeout` |
| Caller coroutine cancelled | `CancellationException` propagates (not turned into a value) |

Defaults come from `TeslaBleConfig`: `commandTimeout` 5 s, `commandLifetime` 5 s, `handshakeTimeout` 20 s,
`connectTimeout` 30 s, `pairingTimeout` 60 s (`addKeyRequest`).

## Per-command re-query (FR-102)

After `Uncertain`, never resend. Read the state the command changes:

| Command | Re-query with | What to look at |
|---|---|---|
| `lock`, `unlock`, `wakeUp`, `remoteDrive`, `autoSecureVehicle`, `openTrunk`, `actuateTrunk`, `closeTrunk`, `openFrunk`, `openTonneau`, `closeTonneau`, `stopTonneau` | `vehicleStatus()` | Lock state, closure states, sleep status. `openTrunk` / `actuateTrunk` toggle: compare the closure state before deciding to send again |
| `addKey`, `removeKey` | `listKeys()` | Whether the public key is in the whitelist |
| `addKeyRequest` (`TimedOut`, or a `Flow` that ended without a terminal reply) | `sessionInfo(publicKey, VehicleDomain.INFOTAINMENT)` | `Success` = enrolled; `Failure(KeyNotPaired)` = not (yet) |

Infotainment commands (M5) will be added to this table.

## `getState` results (FR-033, #115, #116)

A query changes nothing on the vehicle. So **`Uncertain` on a read means no data was confirmed**, not that a command may
have run. `mayHaveSucceeded` is still true, because reads and commands share the error types. There is nothing to check
afterwards: keep the last value and ask again later. Asking again is safe, but not in a tight loop: the library sets no
interval, so it is the app's choice.

| Result | Meaning | App action |
|---|---|---|
| `Success(data)` | The vehicle answered | Check every field you use for `null`. `data` can be empty, and a success is not proof of a new measurement: compare `timestamp` with the previous reply (`14-lib-reading-state.md` A8.3) |
| `Uncertain(ProtocolFault(RESPONSE_MTU_EXCEEDED))` | The vehicle received the request but the reply was too large. The vehicle is reachable | Keep the last value with its age; see the next section |
| `Uncertain(Timeout(afterSend = true))` | Nothing came back within `timeout`: Infotainment is asleep, the reply was lost, or the link dropped while waiting | Read `vehicleStatus()`. `ASLEEP`: the app may wake the vehicle (`14-lib-reading-state.md` A8.2). Otherwise keep the last value with its age. Check `connection` too |
| `Uncertain(BadResponse(detail))` | A reply arrived but could not be parsed | Keep the last value. Report `detail` if it repeats |
| `Failure(Timeout(afterSend = false))` | Time ran out before sending or while waiting to retry, for example when the vehicle keeps answering busy while it wakes | Ask again later, with a longer `timeout` right after a wake |
| `Failure(NoSession)` | No `INFOTAINMENT` session (nothing was sent), or the vehicle did not accept the cached one | `startSession(setOf(VehicleDomain.INFOTAINMENT))`, then ask again once |
| `Failure(InfotainmentRejected(reason))` | The vehicle refused (`actionStatus ERROR`). This wins over any data in the reply | Show a generic message. `reason` is the vehicle's own text; do not log it |
| `Failure(NotConnected)`, `TransportError.*` | The link is gone or the write failed | `disconnect()`, then a new `connect` (§Transport and key store errors) |

Any other error means what the `VehicleError` table above says. The UI tells the two main `Uncertain` cases apart by
error type: a size fault means the vehicle answered, a timeout means nothing came back. Neither is a reason to clear the
session.

## Response size limit on `getState` (FR-033, #116)

The vehicle returns no data when its reply exceeds a size limit. The largest reply seen on real vehicles is about
453 bytes; this is an observed value, not a specified one. `getState()` returns
`Uncertain(ProtocolFault(RESPONSE_MTU_EXCEEDED))`, and the client cannot make the reply smaller.

- It is most likely on `Drive`: a long destination name raises the rate, with reports of failures lasting tens of
  seconds, and of one Model S/X build (`2026.27.6.15`, South Korea) failing almost always even with no route.
- The library does not resend the request. It does not clear the session, reconnect or wake the vehicle because of this
  fault; session info attached to the reply is still applied as usual. The fault does not mean the vehicle is gone or
  asleep.
- Apps keep the last value with its age, show "unavailable" when it is too old, and do not retry in a tight loop.
- Real-time speed is not guaranteed. Behaviour per firmware is not confirmed on a real vehicle (#118).

## Reply authenticity

Like the Go original, the library accepts a plaintext reply, because firmware before 2024.38 does not encrypt
responses (`vehicle-command/pkg/protocol/protocol.md:751`). A plaintext reply carries no authentication tag, so a party on
the BLE link could forge read data. **Do not base safety decisions on `getState`.** The same goes for `vehicleStatus()`
and `vehicle.events`, which are unauthenticated plaintext by design (`14-lib-reading-state.md` A1, B1). Command replies
are not exempt: the plaintext path has no domain check, so a forged plaintext VCSEC reply can make a command such as
`lock()` return `Success` even if the vehicle never ran it. Planned (not implemented yet, ADR-0015): authenticated commands
will need a verified encrypted reply to succeed, and firmware before 2024.38 will no longer be supported.
