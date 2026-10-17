# Getting Started with tesla-ble-kmp

Scope: M3 (scan, connect, key list, vehicle status). Key enrollment arrives in M4, so at this stage an app can only run
the unauthenticated queries. Installation: `11-lib-overview.md` §Installation. Permissions: `17-lib-platform-notes.md`.
Errors: `16-lib-errors.md`.

## Flow

```
TeslaBle.create → keys.getOrCreate() → scan(vin).first() → connect(vin, key, advertisement)
  → vehicleStatus() / keySummary() / listKeys() → vehicle.disconnect() → ble.close()
```

Invariants:

- Every call that talks to the vehicle or the key store returns `VehicleResult<T>` (`Success` / `Uncertain` / `Failure`).
  Failures are values, not exceptions. Only caller cancellation propagates (`CancellationException`).
- Create **one** `TeslaBle` per app process and call `close()` (suspend) when done. `close()` disconnects every vehicle
  still connected.
- One `Vehicle` per connection. Always call `vehicle.disconnect()`, even after the link dropped
  (`connection` is `Disconnected`). It saves the session cache, wipes session keys and frees the coroutine scope.
  It is idempotent. A disconnected `Vehicle` cannot be reused; connect again.
- `key = null` is allowed: the unauthenticated queries work, `startSession()` fails with `RequiresKey`.
- `TeslaBleConfig.default(logger = DebugLogger())` is for development builds only.

## Kotlin (Android)

```kotlin
import dev.mirolab.teslable.*
import dev.mirolab.teslable.model.*
import dev.mirolab.teslable.port.*
import kotlinx.coroutines.flow.first

suspend fun demo(context: android.content.Context, vinText: String) {
    val vin = Vin.parseOrNull(vinText) ?: return              // 17-character format check
    val ble = TeslaBle.create(
        PlatformContext(context),
        TeslaBleConfig.default(logger = DebugLogger()),         // development logger, not for release builds
    )
    try {
        val key = when (val r = ble.keys.getOrCreate()) {       // default alias, hardware first
            is VehicleResult.Success -> r.value
            is VehicleResult.Uncertain -> return
            is VehicleResult.Failure -> return                  // r.error: KeyStoreError
        }

        // Take the first advertisement. The caller sets the time limit (for example withTimeoutOrNull).
        val event = ble.scan(vin).first()
        val target = (event as? ScanEvent.Found)?.advertisement ?: return   // on Failed, see event.error

        val vehicle = when (val r = ble.connect(vin, key, target)) {
            is VehicleResult.Success -> r.value
            is VehicleResult.Uncertain -> return
            is VehicleResult.Failure -> return                  // r.error: TransportError and others
        }
        try {
            println(vehicle.vehicleStatus())                    // show it in the UI instead of println
            println(vehicle.listKeys())
        } finally {
            vehicle.disconnect()                                // frees the slot; call it even after the link dropped
        }
    } finally {
        ble.close()                                             // suspend; disconnects any vehicle still connected
    }
}
```

The app must request `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` at runtime before these calls
(`17-lib-platform-notes.md` §Android).

## Swift (iOS)

SKIE turns `suspend` into `async throws`, `Flow` into `AsyncSequence`, and sealed types into enums reachable with
`onEnum(of:)`. The shape below matches `samples/ios/Sources/SampleModel.swift`.

```swift
import Teslable

let ble = TeslaBle.companion.create(
    platform: PlatformContext(),
    config: TeslaBleConfig.companion.default(logger: DebugLogger())
)

func run(vinText: String) async {
    guard let vin = Vin.companion.parseOrNull(value: vinText) else { return }   // bad format

    // 1) Key
    var key: VehicleKey?
    do {
        let result = try await ble.keys.getOrCreate(alias: VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS, policy: .preferHardware)
        switch onEnum(of: result) {
        case .success(let s): key = s.value as? VehicleKey
        case .uncertain(let u): print("uncertain: \(u.error.message)")
        case .failure(let f): print("failure: \(f.error.message)")
        }
    } catch { print("error: \(error)") }

    // 2) Scan (stop at the first advertisement; set a time limit by cancelling the Task)
    for await event in ble.scan(vin: vin) {
        switch onEnum(of: event) {
        case .found(let f): print("\(f.advertisement.localName.value) rssi=\(f.advertisement.rssi)")
        case .failed(let e): print("scan failed: \(e.error.message)")
        }
        break
    }

    // 3) Connect and query
    do {
        let connected = try await ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
        switch onEnum(of: connected) {
        case .success(let s):
            guard let vehicle = s.value as? Vehicle else { return }
            let status = try await vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: status) {
            case .success(let v): print("status: \(String(describing: v.value))")
            case .uncertain(let u): print("uncertain: \(u.error.message)")   // query again, do not resend
            case .failure(let f): print("failure: \(f.error.message)")
            }
            try await vehicle.disconnect()
        case .uncertain(let u): print("uncertain: \(u.error.message)")
        case .failure(let f): print("failure: \(f.error.message)")
        }
    } catch { print("error: \(error)") }
}
```

With `target: nil`, `connect` scans by itself. `Info.plist` needs `NSBluetoothAlwaysUsageDescription`
(`17-lib-platform-notes.md` §iOS).

## Next steps

- Key enrollment: M4 (`13-lib-pairing.md`). Until then `connect(vin, key = null)` is enough for `vehicleStatus()`,
  `keySummary()` and `listKeys()`.
- Observe the link with `vehicle.connection` (`StateFlow<TransportState>`) and sessions with `vehicle.sessions`
  (`StateFlow<Map<VehicleDomain, SessionStatus>>`). Call `disconnect()` yourself when `Disconnected` arrives.
