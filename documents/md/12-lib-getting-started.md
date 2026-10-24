# Getting Started with tesla-ble-kmp

Scope: M3 (scan, connect, key list, vehicle status), M4 (pairing, VCSEC commands: see "Pair, then lock") and broadcast
events (`Vehicle.events`, FR-035: see "Broadcast events"). The main examples below use only the unauthenticated queries.
Installation: `11-lib-overview.md` §Installation. Permissions: `17-lib-platform-notes.md`. Errors: `16-lib-errors.md`.

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

To pass a time limit from Swift, use the boxed helper and the seconds-based config factory (every argument is required;
Swift gets no default-argument overloads):

```swift
let status = try await vehicle.vehicleStatus(timeout: Timeouts.shared.seconds(seconds: 10))
let config = TeslaBleConfig.companion.withSeconds(logger: DebugLogger(), commandTimeoutSeconds: 10,
    handshakeTimeoutSeconds: 20, connectTimeoutSeconds: 30, pairingTimeoutSeconds: 90)
```

## Pair, then lock

First time with a new phone key: enroll it with the key card, wait until the car lists it, then start the VCSEC
session and send a command. Details and event handling: `13-lib-pairing.md`; commands: `15-lib-commands.md`.

```kotlin
// vehicle was connected with `key` (see above)
vehicle.addKeyRequest(key.publicKey).collect { event -> render(event) }      // user taps the key card, confirms on screen
if (waitUntilEnrolled(vehicle, key)) {                                       // polls sessionInfo(…, INFOTAINMENT) (13-lib-pairing.md)
    vehicle.startSession(setOf(VehicleDomain.VCSEC))
    val result = vehicle.lock()                                              // VehicleResult<Unit>; Uncertain: vehicleStatus(), do not resend
}
```

```swift
for await event in vehicle.addKeyRequest(publicKey: key.publicKey, role: .owner, formFactor: .iosDevice, timeout: nil) {
    render(event)                                                            // user taps the key card, confirms on screen
}
// poll vehicle.sessionInfo(publicKey:domain:timeout:) until success (13-lib-pairing.md), then:
_ = try await vehicle.startSession(domains: [.vcsec], timeout: nil)
let result = try await vehicle.lock(timeout: nil)
```

## Broadcast events

The vehicle pushes plaintext state broadcasts (lock, closures, gear, presence, sleep) and authentication requests
without being asked. `vehicle.events` (`Flow<VehicleEvent>`) delivers them; no key and no session are needed. Rules
(gate, event types, `unknownFields`, buffer): `14-lib-reading-state.md` B2.

- Collect in its own coroutine (Swift: its own `Task`). The flow **completes by itself** when the link drops or
  `disconnect()` is called; a collection started after that ends at once. Then look at `connection` and call
  `disconnect()`. A new connection gives a new `Vehicle` with new `events`.
- Only broadcasts that arrive while you collect are delivered (no replay). **A repeat is not a change**: the vehicle
  resends the same status every few seconds, so compare with the last value.
- The trunk opened or closed by hand from outside is broadcast, but with the interior button it is not; when the trunk
  state matters, read it with `vehicleStatus()`.
- `raw` is the whole received frame, **not VIN-masked**, and every access returns a new copy: read it once and mask the
  VIN before you store or share it.

```kotlin
// vehicle was connected as above (key = null is enough)
scope.launch {
    vehicle.events.collect { event ->
        when (event) {
            is VehicleEvent.Status -> render(event.status)                        // a repeat is not a change
            is VehicleEvent.AuthenticationRequest -> note(event.requestedLevel, event.reasons)
            is VehicleEvent.Unrecognized -> keep(event.raw, event.unknownFields)  // mask the VIN in raw before storing it
        }
    }
    // completed: the link dropped or disconnect() was called. Check vehicle.connection, then call vehicle.disconnect().
}
```

Swift: SKIE flows do not throw, so `for await` is enough. Write `AuthenticationLevel.none` with the type name: where the
value is optional (for example a saved last level), a bare `.none` means `nil`. In Swift the public `VehicleStatus` model
is named `VehicleStatus_` (the generated VCSEC message has the plain name), so `s.status` is a `VehicleStatus_`.

```swift
// in its own Task; the loop ends by itself when the link drops or disconnect() is called
for await event in vehicle.events {
    switch onEnum(of: event) {
    case .status(let s):
        render(s.status)                                         // a repeat is not a change
    case .authenticationRequest(let a):
        if a.requestedLevel != AuthenticationLevel.none {
            print("authentication request: \(a.requestedLevel), reasons \(a.reasons)")
        }
    case .unrecognized(let u):
        let raw = u.raw                                          // every access copies the bytes: read it once
        keep(raw, u.unknownFields)                               // mask the VIN in raw before storing it
    }
}
// the loop ended: check vehicle.connection, then call vehicle.disconnect()
```

## Next steps

- Key enrollment and the pairing events: `13-lib-pairing.md`. `connect(vin, key = null)` is still enough for
  `vehicleStatus()`, `keySummary()` and `listKeys()`.
- Observe the link with `vehicle.connection` (`StateFlow<TransportState>`) and sessions with `vehicle.sessions`
  (`StateFlow<Map<VehicleDomain, SessionStatus>>`). Call `disconnect()` yourself when `Disconnected` arrives.
- Live state from broadcasts: `vehicle.events` ("Broadcast events" above, `14-lib-reading-state.md` B2).
