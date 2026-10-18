# Troubleshooting

Scope: M3 (scan, connect, key store, read-only queries). Pairing-time and command-time problems are added with M4/M5.
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
