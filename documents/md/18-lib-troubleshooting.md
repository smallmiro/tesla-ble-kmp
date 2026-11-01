# Troubleshooting

Scope: M3–M4 (scan, connect, key store, read-only queries, pairing, VCSEC commands, observation log), broadcast events (`Vehicle.events`, FR-035), key accessibility on a locked iPhone (FR-037) and `getState` (FR-033). Infotainment command problems are added with M5.
Go original troubleshooting: `08-errors.md`, `02-ble-transport.md`.

## Symptom → cause → action

| Symptom | Likely cause | Action |
|---|---|---|
| `ScanEvent.Failed(PermissionDenied)` or `Failure(PermissionDenied)` | Bluetooth permission missing or denied | Android: request `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` at runtime. iOS: add `NSBluetoothAlwaysUsageDescription`; if denied, send the user to Settings |
| `ScanEvent.Failed(BluetoothOff)` | Bluetooth is off | Ask the user to turn it on |
| `scan(vin)` emits nothing, `connect` ends with `ScanTimeout` | Out of range, wrong VIN, or iOS simulator (no Bluetooth) | Move closer, check the VIN (the filter is an exact match on the advertised name `S<hash of VIN>C`), use a real device |
| `Failure(MaxConnectionsExceeded)`, advertisement has `isConnectable == false` | The vehicle already has 3 BLE connections (phone keys, other apps) | Disconnect another device; always call `disconnect()` in your own app |
| `ConnectFailed` repeats | Link unstable or the vehicle is busy | The library already retries inside `connectTimeout` (30 s); try again later |
| `connect` returns some seconds after `connectTimeout`, for example at 32.6 s with the default 30 s | Most likely a late timer: the process was probably not running when it was due (iOS suspends a background app). `connect` does not wait for a failed attempt's cleanup | Nothing to repair. See §`connect` time budget |
| `connection` turns `Disconnected` | Link lost (range, vehicle side) | Call `vehicle.disconnect()`, then `connect` again |
| `startSession()` → `RequiresKey` | Connected with `key = null` | Connect with a `VehicleKey` |
| `startSession()` → `KeyNotPaired` | The public key is not enrolled on the vehicle | Enroll it with `addKeyRequest` (`13-lib-pairing.md`). Check with `listKeys()` or `sessionInfo` |
| `KeyStoreError.PlatformFailure("stored key is corrupted")` | Android backup restored the key file without its wrapping key, or storage damage | `delete` then `getOrCreate`, then enroll the new key. Exclude `files/teslable/keys/` from backup (`17-lib-platform-notes.md`) |
| `KeyStoreError.HardwareUnavailable` | `REQUIRE_HARDWARE` on a device without StrongBox / TEE / Secure Enclave | Use `PREFER_HARDWARE` |
| `KeyStoreError.Locked` | iOS: the phone is locked and the key is `WHEN_UNLOCKED`, or the phone was not unlocked since restart | Never delete the key. Call again after unlock. For use while locked, create an `AFTER_FIRST_UNLOCK` key under a new alias and enroll it (`13-lib-pairing.md` §Key accessibility) |
| `startSession()` → `KeyStoreError.Locked` while the iPhone is locked; `WARN` (tag `Dispatcher`) `Session info not applied for <domain>: the private key is locked (device locked)` | The `WHEN_UNLOCKED` Secure Enclave key object was read before the phone locked; the lock blocks its handshake ECDH (issue #96) | Never delete the key. Call `startSession()` again after unlock with the same key object. For use while locked, create an `AFTER_FIRST_UNLOCK` key under a new alias and enroll it (`13-lib-pairing.md` §Key accessibility) |
| `connect` restores no session while the iPhone is locked; `WARN` (tag `Dispatcher`) `Cached session for <domain> not restored: the private key is locked (device locked)`, later `INFO` (tag `SessionCacheSync`) `Keeping the session cache: …` | Same cause, during the cache restore | Nothing to repair: the cache is kept and is restored on the first connection after unlock |
| `startSession()` → `Timeout` only while the iPhone is locked; `WARN` `Session info error: MESSAGEFAULT_ERROR_BAD_PARAMETER: invalid public key` or `invalid cache: …: invalid public key` | iOS reported the locked Secure Enclave ECDH with an error other than OSStatus -25308 (the library's mapping is not yet confirmed on a device) | Report the iOS version and the log lines. Meanwhile read the key with `get(alias)` right before connecting (that read returns `Locked`), or use an `AFTER_FIRST_UNLOCK` key |
| `WARN` `Keychain did not report the protection class of key <alias>; reporting WHEN_UNLOCKED` (tag `keystore`) | The Keychain returned no protection class for the stored key (not expected) | The key reports `WHEN_UNLOCKED`. Report it with the iOS version |
| First command after restoring a cached session fails with `TIME_EXPIRED` and then succeeds | Device slept, monotonic clock lagged | Nothing; the library resyncs from the attached session info and retries |
| `addKeyRequest` ends with `TimedOut` | The card was not tapped, or the on-screen confirmation was not given in time (default 60 s), or the reply was not recognized | Ask the user to tap the key card on the center console and confirm on the screen, then check `sessionInfo(publicKey, INFOTAINMENT)`. It may still have been enrolled |
| `addKeyRequest` emits `Rejected(KeychainRejected(WHITELIST_FULL))` or another `KeychainRejected` code | The vehicle refused: key list full, no permission to add, … | Remove an unused key on the vehicle's touchscreen or with `removeKey`; see `16-lib-errors.md` |
| `addKeyRequest` emits `Unknown(raw)` | A reply format not seen before (real-vehicle behaviour is not verified yet, ADR-0009) | Report `raw` as hex (no VIN) and what the car did |
| `addKeyRequest` emits `Failed(NotConnected)` | The vehicle was disconnected or the link dropped | `disconnect()`, `connect` again, repeat |
| `lock()` (or any VCSEC command) → `NoSession` | No VCSEC session yet, or the cached session is stale | Call `startSession(setOf(VehicleDomain.VCSEC))` first. If `sessions` showed `Ready(fromCache = true)` it changes to `None` after this failure (the cache belonged to another key or vehicle); `startSession` fixes it |
| `removeKey` / `addKey` → `InsufficientPrivileges` | The connection's key may not manage keys (for example a Driver key) | Use an Owner key |
| `Uncertain(Timeout(afterSend = true))` | Response lost after sending | Query state again; do not resend |
| `getState()` → `NoSession` | No Infotainment session yet, or the cached session is stale | Call `startSession(setOf(VehicleDomain.INFOTAINMENT))`, then ask again. If `sessions` showed `Ready(fromCache = true)` it changes to `None` after this failure |
| `getState()` → `Uncertain(Timeout(afterSend = true))`, while `startSession` succeeded | Infotainment is asleep (a session restored from the cache says nothing about sleep; `getState` never wakes the vehicle), or the reply was lost | Read `vehicleStatus()`. `ASLEEP`: follow the wake order in `14-lib-reading-state.md` A8.2. Otherwise keep the last value and ask again later |
| `getState()` → `Failure(Timeout(afterSend = false))` | Time ran out before sending or between retries, for example while the vehicle answers busy during a wake | Ask again later; right after a wake pass a longer `timeout` |
| `getState(DRIVE)` → `Uncertain(ProtocolFault(RESPONSE_MTU_EXCEEDED))` | The reply is over the vehicle's size limit; a long destination name makes this more likely. The vehicle is not gone or asleep | Keep the last value with its age, ask again later, not in a tight loop. There is no smaller query (`16-lib-errors.md` §Response size limit) |
| `getState()` → `Success`, but a field is `null` | The vehicle did not send that field, or the reply had no `vehicleData` | Show "no value", never 0 (`14-lib-reading-state.md` A8.3). Which fields a real vehicle sends is not confirmed yet (#118) |
| `getState()` → `Success` with the same values again | `Success` is not proof of a new measurement | Compare `timestamp` with the previous reply (`14-lib-reading-state.md` A8.3) |
| `vehicle.events` completed | The link dropped or `disconnect()` was called (a collection started after that ends at once) | Look at `connection`; call `disconnect()`; connect again for a new `Vehicle` and new `events` |
| `vehicle.events` delivers nothing | Collection started after the broadcasts (there is no replay), the vehicle sent none (it pushes only while awake), or the frames fail the gate (`14-lib-reading-state.md` B2.1) | Make a change on the car (open a door, shift gear). Compare with the observation log: a plain broadcast has a `[drop:to-domain]` line with `from=domain(DOMAIN_VEHICLE_SECURITY) to=domain(DOMAIN_BROADCAST)` |
| `[drop:missing-source]` line with `reinterpreted=FromVCSECMessage{authenticationRequest{…}}`, but no `AuthenticationRequest` in `vehicle.events` | By design: a frame without a source fails the gate | Nothing to repair. See §Frames without a source |
| `WARN` `Dropping plain broadcast because the broadcast buffer is full (dropped N since connect)` | A collector of `vehicle.events` fell more than 64 frames behind | Keep the collector light; move slow work to another coroutine (`16-lib-errors.md`) |
| `UnknownFault`, `UnknownKeychainCode`, enum values read as `UNKNOWN` | Newer vehicle firmware | Check for a library update |
| Keychain test fails with -25291 on the simulator | Plain Kotlin/Native test binaries cannot use the Keychain | Verify Keychain behaviour in the sample app |

## `connect` time budget

`connectTimeout` (default 30 s) or `ConnectOptions.timeout` covers scanning, opening the link and the retries between
attempts. When `connect` fails on it:

- It returns at once, with the error of the last failed attempt, or `ScanTimeout` if no attempt failed (Go: `ble.go:270-275`).
- It does not wait for an attempt still running. That attempt runs in the library's own scope: it is cancelled and cleans up
  after `connect` has returned, waiting at most 5 s for the platform disconnect (`DISCONNECT_TIMEOUT`). If it still opens a
  link, the library disconnects that link.
- The only wait past the budget: a transport created at the very moment the budget ran out is closed before `connect`
  returns, which also takes at most 5 s. So the library itself adds at most 5 s to a failed `connect`.
- The budget is a coroutine timer and cannot fire while the process is not running, so a suspended app can overrun by any
  amount. On a real iPhone a 30 s budget ended 36 minutes late (`docs/analysis/2026-11-01-build18-link-health.md` F3, status Likely).
  No timeout can prevent this.

Seen once on a real iPhone: `ConnectFailed` after 32.63 s, with the reason `NotConnectedException: Disconnect detected`.
The likely cause is a late timer (status **Likely**, not confirmed: the log records no process state). `ScanTimeout`
returns, where no attempt had failed, overran the same way (at 31.35, 32.40 and 33.19 s), and the app's next 4 s wait took
9.03 s. The log cannot exclude the one library wait above, which would need a link to open at the instant the budget ran
out. No harm was seen (#130).

## Frames without a source

Decision (#130): a frame without `from_destination` stays out of `vehicle.events` and remains a `[drop:missing-source]` line
in the observation log.

- It fails the gate (`14-lib-reading-state.md` B2.1), and the Go original drops it too (`dispatcher.go:248-251`). Without a
  source nothing says the frame came from VCSEC: `reinterpreted=` only shows how the same bytes read as a `FromVCSECMessage`.
- Delivering these frames would widen the public contract: `raw` is always a whole `RoutableMessage` frame, and any stray
  frame would become an event.
- The gain is small. A real car sent 2 such frames in 12 connections. The `authenticationRequest` (`DRIVE`, `IDENTIFICATION`)
  arrived 1 ms before the app saw `connected`, so nothing was collecting yet and, with no replay, a wider gate would not have
  delivered it. The other was a bare `commandStatus`, which has no event type. An app also cannot act on an
  `AuthenticationRequest`: the library does not send `AuthenticationResponse`.

Reconsider this if a vehicle sends such frames often: reports elsewhere describe one about every 1.1 s
(`14-lib-reading-state.md` B3.2).

## Debug logging

- Pass a logger through `TeslaBleConfig(logger = …)` or `TeslaBleConfig.default(logger = …)`. The default is
  `TeslaLogger.NoOp`.
- `DebugLogger()` writes to the platform log: Android Logcat, iOS unified log (`NSLog`), JVM `java.util.logging`. Use it
  in development builds only.
- Custom logger: implement `fun interface TeslaLogger { fun log(level: LogLevel, tag: String, message: () -> String) }`.
  `message` is evaluated lazily. A logger that throws does not stop the library.
- Levels: `DEBUG` (TX/RX hex), `INFO` (session and connection events), `WARN` (drop or discard reasons), `ERROR`
  (unrecoverable situations).
- The library logs VINs masked (`Vin.toString()`) and never logs private keys, session keys, nonces or decrypted plaintext
  at any level. Do not add `vin.value` to your own logs either, and mask VINs before sharing logs.
- Do not log `getState` results, `toString()` included: a `VehicleData` can hold the location and the destination, and
  `InfotainmentRejected.message` carries the vehicle's own text. The library logs neither (FR-112).

To report a problem, use the iOS sample's "copy whole result" button log together with `DebugLogger`. There is no probe or diagnostics entry point.

## Observation log and `vehicle.events`

Both show the vehicle's broadcasts, for different readers:

| | Observation log | `vehicle.events` |
|---|---|---|
| What it is | One `DEBUG` text line per dropped message or reply to an own request, tag `observe` | Public `Flow<VehicleEvent>` on `Vehicle` (FR-035) |
| Which frames | Every dropped frame (replies to other clients, unparseable frames, broadcasts) and every reply to an own request, in both domains | Only frames that pass the plain broadcast gate (`14-lib-reading-state.md` B2.1) |
| Content | A summary for people; values of unknown fields only in the detailed decode | Typed model (`Status`, `AuthenticationRequest`, `Unrecognized`), `unknownFields` paths and the whole frame in `raw` |
| VIN | Masked by the library | **Not masked** in `raw`: the app masks it before storing or sharing |
| Needs | A logger that keeps `DEBUG` (development builds) | Nothing; it works in release builds |
| Use it for | Exploring the protocol, reporting problems | App features (live state) and collecting raw frames for analysis |

- When one frame carries both `vehicleStatus` and `authenticationRequest`, the observe line shows only the one that
  comes last on the wire: the schema puts both in one `oneof`, and a Wire decode keeps only the last member. `events`
  reads the two fields separately and delivers both (`Status`, then `AuthenticationRequest`).
- Do not parse observe lines in app code: their format is for development and may change. Use `events`.

## Observation log (development)

The dispatcher writes one summary line for every message it drops and for every reply to one of its own requests, in
both domains. It is for development, to see what the vehicle pushes without being asked and how its replies line up with
requests. It changes no behaviour: broadcasts are still dropped from reply matching, and the ones that pass the plain
broadcast gate are also delivered through `vehicle.events` (previous section). What each pushed message means (confirmed
on a real car or not) is in `14-lib-reading-state.md` Part B.

- Tag `observe`, level `DEBUG` (a logger that discards `DEBUG` never evaluates the line). Visible through `DebugLogger()`
  or any custom `TeslaLogger`. The iOS sample's observation mode collects only these lines.
- Line shape: `[<point>] <N>B from=<dest> to=<dest> request_uuid=<empty|16B> [status=<op>/<fault>] [session_info_request]
  [session_info=<N>B] payload=<summary> [attempt=<N>] [auth=<value>]`. `<N>B` is the received frame size. `attempt=` and
  `auth=` close the line when present (§Reply lines).

| Part | Values |
|---|---|
| `[drop:unparseable]` | Frame did not decode as `RoutableMessage`. Line is `[drop:unparseable] <N>B reinterpreted=<summary or undecodable>` |
| `[drop:missing-source]` | No `from_destination`. The line ends with `reinterpreted=<summary>`: the same bytes read as an unwrapped `FromVCSECMessage`. Never an event (§Frames without a source) |
| `[drop:missing-destination]` | No `to_destination` (not seen from the real vehicle) |
| `[drop:to-domain]` | Addressed to a domain, not a routing address (the vehicle's broadcasts, `DOMAIN_BROADCAST`) |
| `[drop:malformed]` | Bad request UUID length or bad routing address length |
| `[drop:no-handler]` | Valid routing address with no open request. A reply whose key is in the closed-request history reads `to=address(16B, closed)` and ends with ` attempt=<N>` (§Reply lines) |
| `[drop:handler-closed]`, `[drop:queue-full]` | The matching request closed meanwhile, or its response queue was full. The `[reply:…]` line of the same frame comes first |
| `[reply:vcsec]`, `[reply:infotainment]` | A reply routed to an open request, one line per received frame (§Reply lines) |
| `from=`, `to=` | `domain(DOMAIN_X)`, `address(16B, self\|open-request\|closed\|other)` (`to=` only), `none`, `empty` |
| `payload=` | `FromVCSECMessage{…}`, `Response{fields=[…]}`, `encrypted(NB)`, `NB`, `none` |
| `unknownFields=[n]` | Field numbers present on the wire but unknown to the generated proto |
| `uiDesire=`, `gear=` | `vehicleStatus` fields 6 and 7, named by the Teslemetry proto schema (`UI_DESIRE_*`, `GEAR_UNKNOWN\|PARK\|DRIVE\|REVERSE\|NEUTRAL`). `gear` is confirmed on a real car (see `14-lib-reading-state.md` Part B); `uiDesire` meaning is not. Always printed (proto3 enum: absent reads as the zero value) |
| `authenticationRequest{requestedLevel=…, reasons=[…], token=NB}` | `FromVCSECMessage` field 3, named by the Teslemetry proto schema (field names unverified). The token is shown by length only |
| `absent=[…]` | Message fields of `vehicleStatus` that were not sent (`closureStatuses`, `detailedClosureStatus`) |
| `key=sha1:xxxxxxxx` | Public key: first 4 bytes of its SHA-1 only |

### Reply lines

A `[reply:vcsec]` or `[reply:infotainment]` line is written once per received frame, after the decrypt step, so a reply
that step dropped has its line too. It ends with ` attempt=<N> auth=<value>`. If the frame then does not reach the caller,
a `[drop:handler-closed]` or `[drop:queue-full]` line follows.

| Part | Meaning |
|---|---|
| `attempt=<N>` | Opaque number of the request on this connection, from 1. Every request takes a new one, including a handshake request and each retry, so one command can use several. Compare numbers only within one connection: a new connection starts at 1 again |
| `auth=gcm-ok` | `AES_GCM_Response_data` is present, the tag verified and the counter passed the request's replay check. Delivered |
| `auth=gcm-failed` | `AES_GCM_Response_data` is present and verification failed. Dropped |
| `auth=gcm-replay` | The tag verified, but this request already saw that counter. Dropped |
| `auth=gcm-no-session` | `AES_GCM_Response_data` is present, but there is no session to check it with (no private key, or the session is not established). Dropped |
| `auth=none` | No `AES_GCM_Response_data`, so nothing was checked. Delivered. A `session_info` reply reads this too: its HMAC tag is verified separately and this line does not report that |

- `auth=` reports the payload check, not a verdict on the reply. AES-GCM covers the payload and `signed_message_fault`.
  The outer `operation_status`, the first half of `status=`, is outside it, so `auth=gcm-ok` does not cover it (ADR-0015).
- The line is built from the frame as received, never from the decrypted message (FR-112). An encrypted reply reads
  `payload=encrypted(NB)` whatever `auth=` says, and `encrypted(NB)` alone only means the signature field is there.
- `to=address(16B, closed)` is a correspondence and nothing more. The reply's key (routing address, plus the request UUID
  for Infotainment) matches a request this connection closed, and `attempt=<N>` names it. It does not show that the reply
  is genuine or that the request succeeded: the library does not decrypt such a reply, which leaves the request's replay
  check untouched, and does not deliver it. That is why the line has no `auth=`.
- The history holds the last 32 closed requests of the connection, and the oldest falls out first. A late reply to an
  older request reads `other` for VCSEC and `self` for Infotainment, whose requests all share one address.
- The history empties when the connection ends, whether the link dropped or the app disconnected. Nothing carries over to
  a new connection, not even a request that was still open when the link dropped and closed later.

### Detail form (exploration only)

Example: `unknownFields=[48:len(8){1:varint=3(0x3), 2:len(4)"ABCD"}, 9]`. Entries are in arrival
order, repeated fields repeated. The path number and wire type are kept and no field name is invented.

| Wire type | Shown as |
|---|---|
| varint | `varint=<unsigned decimal>(0x<hex>)` |
| fixed32 / fixed64 | `fixed32=0x<8 hex digits>` / `fixed64=0x<16 hex digits>` |
| LEN | `len(N){…}` when the whole range parses as protobuf (max depth 3, 64 fields per message, 256 bytes); else `len(N)"text"` for printable ASCII (control characters escaped); else `len(N)0x<hex>` |
| over a limit | `len(N, truncated)`; corrupt unknown-field buffer: `len(N, invalid)` |
| key-like LEN | exactly 16, 32, 33, 64 or 65 bytes: `redacted(NB)`; a 65-byte value starting with `0x04`: `sha1:xxxxxxxx` |

VINs (`[A-HJ-NPR-Z0-9]{17}`) are masked in text and in raw bytes before any hex is produced (first 3 and last 4
characters kept). Masking runs on the raw bytes before they are parsed, so masked bytes can change the value or structure that is shown (nothing leaks). This output is for exploration. It does not give the meaning of a field. On the real car a missing
`closureStatuses` meant "all closed" for the driver doors, the rear trunk and the charge port, but a trunk operated by the interior button produced no broadcast at all, so a broadcast alone cannot prove the trunk is closed (see `14-lib-reading-state.md` Part B).

### Limits and rules

- proto3 enums cannot tell "missing" from the zero value: an absent lock state reads `VEHICLELOCKSTATE_UNLOCKED`
  (sleep and presence read `UNKNOWN`). Only message fields can be reported as `absent`.
- Detailed unknown-field decoding (observation log v2) is the one narrow exception to "no plaintext payloads" in logs,
  and it does not touch FR-112: it applies only to a frame the vehicle originally sent as plaintext, never to anything
  the library decrypted (`VehicleEvent.raw` hands such frames to the app: the first five conditions below, payload
  decodable or not, `14-lib-reading-state.md` B2). The gate is all of: `from=domain(DOMAIN_VEHICLE_SECURITY)`, `to=domain(DOMAIN_BROADCAST)`, empty
  `request_uuid`, no `signature_data`, no `session_info`, and a payload that decodes as `FromVCSECMessage`. For such
  frames only `FromVCSECMessage` unknown fields 48 and 53 and `VehicleStatus` unknown fields 7 and 8 are decoded (`VehicleStatus`
  7 is now the named `gear`, so it is unknown only when its value is outside the schema; that raw value is still shown,
  for example `unknownFields=[7:varint=9(0x9)]`, because that value is outside the schema); every
  other unknown field, and every other frame (replies, encrypted, addressed to a routing address, unwrapped
  reinterpretations), keeps the number-only form.
- Ciphertext and `session_info` are logged by length only; public keys by SHA-1 prefix only; the VIN and plaintext
  payloads of encrypted messages never appear. Keep the rule when you add lines.
- The BLE transport counts inbox overflows per connection. Each dropped message logs
  `Dropping received message because inbox is full (dropped N since connect)` at `ERROR`; when the link ends, one line
  `BLE inbox overflowed N time(s) during this connection` is logged at `WARN` (only if N > 0).
- `DebugLogger` also prints the BLE `TX:`/`RX:` raw hex at `DEBUG`. Do not share such platform logs. Only what the
  vehicle encrypted is ciphertext there. Everything else appears as the vehicle sent it, including VCSEC query replies,
  broadcasts and a plaintext Infotainment reply (firmware before 2024.38, `16-lib-errors.md` §Reply authenticity), which
  can hold the location.
