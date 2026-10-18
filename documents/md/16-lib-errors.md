# Error Handling

Scope: M1 error model, M2 retries and timeouts, M3 transport and key store errors, M4 `InsufficientPrivileges`, `UnknownVcsecError` and per-command re-query for VCSEC (Infotainment is added with M5). Go original: `08-errors.md` (MessageFault meanings and remedies in §3.1).

## Three outcomes (`VehicleResult<T>`)

| Outcome | Meaning | What the app does |
|---|---|---|
| `Success(value)` | The vehicle confirmed | Use the value |
| `Uncertain(error)` | The vehicle may have executed it but it could not be confirmed (`error.mayHaveSucceeded == true`) | Do not resend. Query state again (`vehicleStatus()` for VCSEC; `getState()` for Infotainment once M5 lands) |
| `Failure(error)` | Not executed | Branch on `error` |

Swift: `switch onEnum(of: result) { case .success(let s): … case .uncertain(let u): … case .failure(let f): … }`.
Full example: `12-lib-getting-started.md`.

Caller cancellation is not converted to a value: `CancellationException` propagates.

## `VehicleError` hierarchy

Every error has `message` (English), `mayHaveSucceeded` and `temporary`. `shouldRetry()` is
`!mayHaveSucceeded && temporary` (Go `ShouldRetry`). The library already retries with this rule (M2), so apps do not
write retry loops.

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
| `KeyStoreError.*` | Key store (M3) | false | false |
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

Call `Vehicle.disconnect()` even after the link dropped so session keys and the scope are released.

## Retries and timeouts (M2)

The library retries with the same rules as Go `vehicle-command`.

| Situation | Library behaviour |
|---|---|
| `error.shouldRetry()`: `BUSY`, `TIMEOUT`, `INVALID_SIGNATURE`, `INVALID_TOKEN_OR_COUNTER`, `INTERNAL`, `INCORRECT_EPOCH`, `TIME_EXPIRED`, `TIME_TO_LIVE_TOO_LONG`, `Busy` (VCSEC `WAIT`), temporary transport errors | After 1 s (BLE `retryInterval`) it **authorizes again with a new counter, nonce and expires_at** and resends. Session info attached to the error response is applied first |
| `mayHaveSucceeded` errors (timeout with no response, `RESPONSE_MTU_EXCEEDED`, unparsable VCSEC or Infotainment response) | **Never resent automatically** (prevents running a toggle twice). Returned as `Uncertain`; query state again |
| Timeout (`timeout`, default 5 s) before sending or while waiting to retry | `Failure(Timeout(afterSend = false))` |
| Timeout while waiting for the response | `Uncertain(Timeout(afterSend = true))` |
| Handshake timeout (`handshakeTimeout`, default 20 s) | `Failure(Timeout(afterSend = false))`; no session, no side effect |
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
