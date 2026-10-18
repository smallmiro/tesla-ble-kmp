# Troubleshooting

Scope: M3–M4 (scan, connect, key store, read-only queries, observation log). Pairing-time and command-time problems are added with M4/M5.
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
| `startSession()` → `KeyNotPaired` | The public key is not enrolled on the vehicle | Enroll it (M4). Check with `listKeys()` (`13-lib-pairing.md`) |
| `KeyStoreError.PlatformFailure("stored key is corrupted")` | Android backup restored the key file without its wrapping key, or storage damage | `delete` then `getOrCreate`, then enroll the new key. Exclude `files/teslable/keys/` from backup (`17-lib-platform-notes.md`) |
| `KeyStoreError.HardwareUnavailable` | `REQUIRE_HARDWARE` on a device without StrongBox / TEE / Secure Enclave | Use `PREFER_HARDWARE` |
| First command after restoring a cached session fails with `TIME_EXPIRED` and then succeeds | Device slept, monotonic clock lagged | Nothing; the library resyncs from the attached session info and retries |
| `Uncertain(Timeout(afterSend = true))` | Response lost after sending | Query state again; do not resend |
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

## Observation log (development)

The dispatcher writes one summary line for every message it drops and for every VCSEC reply. It is for development, to
see what the vehicle pushes without being asked. It changes no behaviour: the library still drops these messages
(`vehicle.events` is planned for M5).

- Tag `observe`, level `DEBUG` (a logger that discards `DEBUG` never evaluates the line). Visible through `DebugLogger()`
  or any custom `TeslaLogger`. The iOS sample's observation mode collects only these lines.
- Line shape: `[<point>] <N>B from=<dest> to=<dest> request_uuid=<empty|16B> [status=<op>/<fault>] [session_info_request]
  [session_info=<N>B] payload=<summary>`. `<N>B` is the received frame size.

| Part | Values |
|---|---|
| `[drop:unparseable]` | Frame did not decode as `RoutableMessage`. Line is `[drop:unparseable] <N>B reinterpreted=<summary or undecodable>` |
| `[drop:missing-source]` | No `from_destination`. The line ends with `reinterpreted=<summary>`: the same bytes read as an unwrapped `FromVCSECMessage` |
| `[drop:missing-destination]` | No `to_destination` (typical vehicle push) |
| `[drop:to-domain]` | Addressed to a domain, not a routing address |
| `[drop:malformed]` | Bad request UUID length or bad routing address length |
| `[drop:no-handler]` | Valid routing address with no open request |
| `[drop:handler-closed]`, `[drop:queue-full]` | The matching request closed meanwhile, or its response queue was full |
| `[reply:vcsec]` | A VCSEC reply routed to an open request |
| `from=`, `to=` | `domain(DOMAIN_X)`, `address(16B, self\|open-request\|other)` (`to=` only), `none`, `empty` |
| `payload=` | `FromVCSECMessage{…}`, `Response{fields=[…]}`, `encrypted(NB)`, `NB`, `none` |
| `unknownFields=[n]` | Field numbers present on the wire but unknown to the generated proto |
| `absent=[…]` | Message fields of `vehicleStatus` that were not sent (`closureStatuses`, `detailedClosureStatus`) |
| `key=sha1:xxxxxxxx` | Public key: first 4 bytes of its SHA-1 only |

Limits and rules:

- proto3 enums cannot tell "missing" from the zero value: an absent lock state reads `VEHICLELOCKSTATE_UNLOCKED`
  (sleep and presence read `UNKNOWN`). Only message fields can be reported as `absent`.
- Ciphertext and `session_info` are logged by length only; public keys by SHA-1 prefix only; the VIN and plaintext
  payloads of encrypted messages never appear. Keep the rule when you add lines.
- The BLE transport counts inbox overflows per connection. Each dropped message logs
  `Dropping received message because inbox is full (dropped N since connect)` at `ERROR`; when the link ends, one line
  `BLE inbox overflowed N time(s) during this connection` is logged at `WARN` (only if N > 0).
- `DebugLogger` also prints the BLE `TX:`/`RX:` raw hex at `DEBUG`. Do not share such platform logs.
