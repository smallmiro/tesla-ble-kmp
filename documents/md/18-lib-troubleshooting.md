# Troubleshooting

Scope: M3–M4 (scan, connect, key store, read-only queries, pairing, VCSEC commands, observation log), broadcast events (`Vehicle.events`, FR-035) and key accessibility on a locked iPhone (FR-037). Infotainment command problems are added with M5.
Go original troubleshooting: `08-errors.md`, `02-ble-transport.md`.

## Symptom → cause → action

| Symptom | Likely cause | Action |
|---|---|---|
| `ScanEvent.Failed(PermissionDenied)` or `Failure(PermissionDenied)` | Bluetooth permission missing or denied | Android: request `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` at runtime. iOS: add `NSBluetoothAlwaysUsageDescription`; if denied, send the user to Settings |
| `ScanEvent.Failed(BluetoothOff)` | Bluetooth is off | Ask the user to turn it on |
| `scan(vin)` emits nothing, `connect` ends with `ScanTimeout` | Out of range, wrong VIN, or iOS simulator (no Bluetooth) | Move closer, check the VIN (the filter is an exact match on the advertised name `S<hash of VIN>C`), use a real device |
| `Failure(MaxConnectionsExceeded)`, advertisement has `isConnectable == false` | The vehicle already has 3 BLE connections (phone keys, other apps) | Disconnect another device; always call `disconnect()` in your own app |
| `ConnectFailed` repeats | Link unstable or the vehicle is busy | The library already retries inside `connectTimeout` (30 s); try again later |
| `connection` turns `Disconnected` | Link lost (range, vehicle side) | Call `vehicle.disconnect()`, then `connect` again |
| `startSession()` → `RequiresKey` | Connected with `key = null` | Connect with a `VehicleKey` |
| `startSession()` → `KeyNotPaired` | The public key is not enrolled on the vehicle | Enroll it with `addKeyRequest` (`13-lib-pairing.md`). Check with `listKeys()` or `sessionInfo` |
| `KeyStoreError.PlatformFailure("stored key is corrupted")` | Android backup restored the key file without its wrapping key, or storage damage | `delete` then `getOrCreate`, then enroll the new key. Exclude `files/teslable/keys/` from backup (`17-lib-platform-notes.md`) |
| `KeyStoreError.HardwareUnavailable` | `REQUIRE_HARDWARE` on a device without StrongBox / TEE / Secure Enclave | Use `PREFER_HARDWARE` |
| `KeyStoreError.Locked` | iOS: the phone is locked and the key is `WHEN_UNLOCKED`, or the phone was not unlocked since restart | Never delete the key. Call again after unlock. For use while locked, create an `AFTER_FIRST_UNLOCK` key under a new alias and enroll it (`13-lib-pairing.md` §Key accessibility) |
| `startSession()` → `Timeout` only while the iPhone is locked; `WARN` `Session info error: MESSAGEFAULT_ERROR_BAD_PARAMETER: invalid public key` or `invalid cache: …: invalid public key` | The `WHEN_UNLOCKED` key object was read before the phone locked; its handshake ECDH fails (known limit, ADR-0014; expected iOS behaviour, not yet confirmed on a device) | Read the key from the key store right before connecting (you then get `Locked`), or use an `AFTER_FIRST_UNLOCK` key |
| `WARN` `Keychain did not report the protection class of key <alias>; reporting WHEN_UNLOCKED` (tag `keystore`) | The Keychain returned no protection class for the stored key (not expected) | The key reports `WHEN_UNLOCKED`. Report it with the iOS version |
| First command after restoring a cached session fails with `TIME_EXPIRED` and then succeeds | Device slept, monotonic clock lagged | Nothing; the library resyncs from the attached session info and retries |
| `addKeyRequest` ends with `TimedOut` | The card was not tapped, or the on-screen confirmation was not given in time (default 60 s), or the reply was not recognized | Ask the user to tap the key card on the center console and confirm on the screen, then check `sessionInfo(publicKey, INFOTAINMENT)`. It may still have been enrolled |
| `addKeyRequest` emits `Rejected(KeychainRejected(WHITELIST_FULL))` or another `KeychainRejected` code | The vehicle refused: key list full, no permission to add, … | Remove an unused key on the vehicle's touchscreen or with `removeKey`; see `16-lib-errors.md` |
| `addKeyRequest` emits `Unknown(raw)` | A reply format not seen before (real-vehicle behaviour is not verified yet, ADR-0009) | Report `raw` as hex (no VIN) and what the car did |
| `addKeyRequest` emits `Failed(NotConnected)` | The vehicle was disconnected or the link dropped | `disconnect()`, `connect` again, repeat |
| `lock()` (or any VCSEC command) → `NoSession` | No VCSEC session yet, or the cached session is stale | Call `startSession(setOf(VehicleDomain.VCSEC))` first. If `sessions` showed `Ready(fromCache = true)` it changes to `None` after this failure (the cache belonged to another key or vehicle); `startSession` fixes it |
| `removeKey` / `addKey` → `InsufficientPrivileges` | The connection's key may not manage keys (for example a Driver key) | Use an Owner key |
| `Uncertain(Timeout(afterSend = true))` | Response lost after sending | Query state again; do not resend |
| `vehicle.events` completed | The link dropped or `disconnect()` was called (a collection started after that ends at once) | Look at `connection`; call `disconnect()`; connect again for a new `Vehicle` and new `events` |
| `vehicle.events` delivers nothing | Collection started after the broadcasts (there is no replay), the vehicle sent none (it pushes only while awake), or the frames fail the gate (`14-lib-reading-state.md` B2.1) | Make a change on the car (open a door, shift gear). Compare with the observation log: a plain broadcast has a `[drop:to-domain]` line with `from=domain(DOMAIN_VEHICLE_SECURITY) to=domain(DOMAIN_BROADCAST)` |
| `WARN` `Dropping plain broadcast because the broadcast buffer is full (dropped N since connect)` | A collector of `vehicle.events` fell more than 64 frames behind | Keep the collector light; move slow work to another coroutine (`16-lib-errors.md`) |
| `UnknownFault`, `UnknownKeychainCode`, enum values read as `UNKNOWN` | Newer vehicle firmware | Check for a library update |
| Keychain test fails with -25291 on the simulator | Plain Kotlin/Native test binaries cannot use the Keychain | Verify Keychain behaviour in the sample app |

## Debug logging

- Pass a logger through `TeslaBleConfig(logger = …)` or `TeslaBleConfig.default(logger = …)`. The default is
  `TeslaLogger.NoOp`.
- `DebugLogger()` writes to the platform log: Android Logcat, iOS unified log (`NSLog`), JVM `java.util.logging`. Use it
  in development builds only.
- Custom logger: implement `fun interface TeslaLogger { fun log(level: LogLevel, tag: String, message: () -> String) }`.
  `message` is evaluated lazily. A logger that throws does not stop the library.
- Levels: `DEBUG` (TX/RX hex), `INFO` (session and connection events), `WARN` (drop or discard reasons), `ERROR`
  (unrecoverable situations).
- The library logs VINs masked (`Vin.toString()`) and never logs private keys, session keys, nonces or plaintext at any
  level. Do not add `vin.value` to your own logs either, and mask VINs before sharing logs.

To report a problem, use the iOS sample's "copy whole result" button log together with `DebugLogger`. There is no probe or diagnostics entry point.

## Observation log and `vehicle.events`

Both show the vehicle's broadcasts, for different readers:

| | Observation log | `vehicle.events` |
|---|---|---|
| What it is | One `DEBUG` text line per dropped message or VCSEC reply, tag `observe` | Public `Flow<VehicleEvent>` on `Vehicle` (FR-035) |
| Which frames | Every dropped frame (replies to other clients, unparseable frames, broadcasts) and every VCSEC reply | Only frames that pass the plain broadcast gate (`14-lib-reading-state.md` B2.1) |
| Content | A summary for people; values of unknown fields only in the detailed decode | Typed model (`Status`, `AuthenticationRequest`, `Unrecognized`), `unknownFields` paths and the whole frame in `raw` |
| VIN | Masked by the library | **Not masked** in `raw`: the app masks it before storing or sharing |
| Needs | A logger that keeps `DEBUG` (development builds) | Nothing; it works in release builds |
| Use it for | Exploring the protocol, reporting problems | App features (live state) and collecting raw frames for analysis |

- When one frame carries both `vehicleStatus` and `authenticationRequest`, the observe line shows only the one that
  comes last on the wire: the schema puts both in one `oneof`, and a Wire decode keeps only the last member. `events`
  reads the two fields separately and delivers both (`Status`, then `AuthenticationRequest`).
- Do not parse observe lines in app code: their format is for development and may change. Use `events`.

## Observation log (development)

The dispatcher writes one summary line for every message it drops and for every VCSEC reply. It is for development, to
see what the vehicle pushes without being asked. It changes no behaviour: broadcasts are still dropped from reply
matching, and the ones that pass the plain broadcast gate are also delivered through `vehicle.events` (previous section).
What each pushed message means (confirmed on a real car or not) is in `14-lib-reading-state.md` Part B.

- Tag `observe`, level `DEBUG` (a logger that discards `DEBUG` never evaluates the line). Visible through `DebugLogger()`
  or any custom `TeslaLogger`. The iOS sample's observation mode collects only these lines.
- Line shape: `[<point>] <N>B from=<dest> to=<dest> request_uuid=<empty|16B> [status=<op>/<fault>] [session_info_request]
  [session_info=<N>B] payload=<summary>`. `<N>B` is the received frame size.

| Part | Values |
|---|---|
| `[drop:unparseable]` | Frame did not decode as `RoutableMessage`. Line is `[drop:unparseable] <N>B reinterpreted=<summary or undecodable>` |
| `[drop:missing-source]` | No `from_destination`. The line ends with `reinterpreted=<summary>`: the same bytes read as an unwrapped `FromVCSECMessage` |
| `[drop:missing-destination]` | No `to_destination` (not seen from the real vehicle) |
| `[drop:to-domain]` | Addressed to a domain, not a routing address (the vehicle's broadcasts, `DOMAIN_BROADCAST`) |
| `[drop:malformed]` | Bad request UUID length or bad routing address length |
| `[drop:no-handler]` | Valid routing address with no open request |
| `[drop:handler-closed]`, `[drop:queue-full]` | The matching request closed meanwhile, or its response queue was full |
| `[reply:vcsec]` | A VCSEC reply routed to an open request |
| `from=`, `to=` | `domain(DOMAIN_X)`, `address(16B, self\|open-request\|other)` (`to=` only), `none`, `empty` |
| `payload=` | `FromVCSECMessage{…}`, `Response{fields=[…]}`, `encrypted(NB)`, `NB`, `none` |
| `unknownFields=[n]` | Field numbers present on the wire but unknown to the generated proto |
| `uiDesire=`, `gear=` | `vehicleStatus` fields 6 and 7, named by the Teslemetry proto schema (`UI_DESIRE_*`, `GEAR_UNKNOWN\|PARK\|DRIVE\|REVERSE\|NEUTRAL`). `gear` is confirmed on a real car (see `14-lib-reading-state.md` Part B); `uiDesire` meaning is not. Always printed (proto3 enum: absent reads as the zero value) |
| `authenticationRequest{requestedLevel=…, reasons=[…], token=NB}` | `FromVCSECMessage` field 3, named by the Teslemetry proto schema (field names unverified). The token is shown by length only |
| `absent=[…]` | Message fields of `vehicleStatus` that were not sent (`closureStatuses`, `detailedClosureStatus`) |
| `key=sha1:xxxxxxxx` | Public key: first 4 bytes of its SHA-1 only |

Detail form (exploration only): `unknownFields=[48:len(8){1:varint=3(0x3), 2:len(4)"ABCD"}, 9]`. Entries are in arrival
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

Limits and rules:

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
- `DebugLogger` also prints the BLE `TX:`/`RX:` raw hex at `DEBUG`. Do not share such platform logs.
