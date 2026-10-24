# Current Sample Apps

State on `main` as of 2026-10-24 (M4). Both apps are single-screen tools; every button runs one library call and
appends a result line.

## Android (`samples/android`)

- Module `:samples:android`, package and application id `dev.mirolab.teslable.sample`, `minSdk` 31.
- Files: `MainActivity.kt` (Compose screen, removal confirmation dialog), `SampleState.kt` (state holder; every action
  runs through `exclusive { }` so only one library call runs at a time).

| Group | Buttons (UI label) | Library calls |
|---|---|---|
| Key | Create / load key | `keys.getOrCreate()` |
| BLE | Scan, Connect, VehicleStatus, Key list (with Delete per row), Disconnect | `scan(vin)`, `connect(vin, key)`, `vehicleStatus()`, `listKeys()`, `removeKey()`, `disconnect()` |
| Pairing | Request key enrollment, Confirm enrollment | `addKeyRequest()` (progress events); confirmation polls `sessionInfo(publicKey, INFOTAINMENT)` every 3 s for up to 2 min until it stops returning `KeyNotPaired` |
| Session | Handshake VCSEC only, Handshake all, Show session state | `startSession(domains)`, `sessions` |
| Control | Lock, Unlock, Open trunk, Close trunk, Open frunk, Wake up | VCSEC commands; `Uncertain` is followed by a `vehicleStatus()` re-query |

## iOS (`samples/ios`)

- XcodeGen project (`project.yml`), schemes `TeslableSample` (simulator) and `TeslableSampleDevice` (device).
- Files: `ContentView.swift` (screen), `SampleModel.swift` (library calls and result text), `ObservingLogger.swift`
  (forwards library logs to `DebugLogger` and streams `observe` lines), `ObservationMark.swift` (mark line format),
  `Tests/` (Swift unit tests for timeouts and marks).

| Section | Contents | Library calls |
|---|---|---|
| Vehicle | VIN input | `Vin.parseOrNull` |
| Key | Create / load key, key protection and fingerprint | `keys.getOrCreate()` |
| Observation | Observation mode switch, memo + Mark button, clear | Custom `TeslaLogger` that keeps `observe` lines (`18-lib-troubleshooting.md` §Observation log) |
| BLE | Scan, Connect, VehicleStatus, Key list (Delete per row), Disconnect | same as Android |
| Pairing | Request key enrollment, Confirm enrollment | same as Android |
| Session | Handshake VCSEC only / all, Show session state | same as Android |
| Control | Lock, Unlock, Open / close trunk, Open frunk, Wake up | same as Android |
| Result | Copy all results (app info, connection, key, timed results, observation lines; no VIN), Clear | – |
| App info | Version, build | – |

While observation mode is on, VehicleStatus, Key list and control buttons are disabled so the app sends nothing that
could wake the vehicle.

## Real-vehicle runbooks

The step-by-step real-vehicle checks (scan and connect, push observation rounds 1 and 2, M4 pairing and VCSEC control)
are kept in `samples/ios/README.md` with the build number each round used. Results are written to
`docs/handoff/<date>-*.md`.
