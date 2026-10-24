# Current Sample Apps

State as of 2026-10-24 (M4). Both apps are the monitor app (`04-monitor-app.md`); the former single screen of each is its
Control tab. The Key & Control tab (`05-key-control-tab.md`, SFR-018) is the short path: get a key, enroll it, press
control buttons.

## Android (`samples/android`)

The monitor app (`04-monitor-app.md`, SFR-010 to SFR-018): five tabs and a foreground service. Build `0.3.0 (3)`; unit
tests pass, real-device check pending.

- Module `:samples:android`, package and application id `dev.mirolab.teslable.sample`, `minSdk` 31.
- Files: see `04-monitor-app.md` §Android implementation. `MainActivity.kt` (tabs, permissions, `FLAG_KEEP_SCREEN_ON` while
  visible), `MonitorService.kt` and `MonitorEngine.kt` (service, connection, events, queries, logs), `SampleState.kt` (Control
  tab state; every action runs through `exclusive { }` so only one library call runs at a time), `ui/*Tab.kt`.
- Tests: `src/test/` (`VinMaskTest`, `LogWriterTest`, `ConnectionLoopTest`, `LiveStateTest`), run with
  `./gradlew :samples:android:testDebugUnitTest`.

| Tab | Contents |
|---|---|
| Live | Connection banner, VIN save and clear, Connect and Disconnect, the latest broadcast status per item with its last-changed time, recent events |
| Query | `vehicleStatus()`, `keySummary()`, `listKeys()` every 30 s (changeable), results with outcome and time, sessions, "query now" |
| Log | Line counts, unrecognized count, last lines, file path, memo and Mark, new file |
| Control | The former single screen (below). Uses the monitor's connection and key |
| Key & Control | Four steps (key, connect, enroll, control) and a button grid of the VCSEC commands, status card, results (`05-key-control-tab.md`) |

Control tab (the former single screen):

| Group | Buttons (UI label) | Library calls |
|---|---|---|
| Key | Create / load key (`teslable-monitor`, shows protection and accessibility) | `keys.getOrCreate(alias, policy, accessibility)` |
| BLE | Scan, Connect, VehicleStatus, Key list (with Delete per row), Disconnect | `scan(vin)`, the monitor's connection, `vehicleStatus()`, `listKeys()`, `removeKey()`, `disconnect()` |
| Pairing | Request key enrollment, Confirm enrollment | `addKeyRequest()` (progress events); confirmation polls `sessionInfo(publicKey, INFOTAINMENT)` every 3 s for up to 2 min until it stops returning `KeyNotPaired` |
| Session | Handshake VCSEC only, Handshake all, Show session state | `startSession(domains)`, `sessions` |
| Control | Lock, Unlock, Open trunk, Close trunk, Open frunk, Wake up | VCSEC commands; `Uncertain` is followed by a `vehicleStatus()` re-query |

## iOS (`samples/ios`)

The monitor app (`04-monitor-app.md`, SFR-010 to SFR-018): five tabs, one monitor object for the app's lifetime. Build
`0.5.0 (12)`; unit tests pass, real-device check pending.

- XcodeGen project (`project.yml`), schemes `TeslableSample` (simulator) and `TeslableSampleDevice` (device).
  `Info.plist` gets `UIBackgroundModes: [bluetooth-central]`, `UIFileSharingEnabled` and
  `LSSupportsOpeningDocumentsInPlace` from `project.yml`.
- Files: see `04-monitor-app.md` §iOS implementation. `MonitorModel.swift` and `MonitorEngine.swift` (connection, events,
  queries, logs), `ConnectionLoop.swift`, `LogLine.swift`, `LogWriter.swift`, `VinMask.swift`, `LiveState.swift`,
  `VehicleMapping.swift`, `SampleModel.swift` (Control tab state; every action runs through `exclusive { }` so only one
  library call runs at a time), `ContentView.swift`, `Views/*Tab.swift`.
- Tests: `Tests/` (`VinMaskTests`, `LogWriterTests`, `ConnectionLoopTests`, `LiveStateTests`, `TimeoutsTests`), run with
  `xcodebuild ... test` (`02-constraints.md` §4).

| Tab | Contents |
|---|---|
| Live | Connection banner, VIN save and clear, Connect and Disconnect, the latest broadcast status per item with its last-changed time, recent events |
| Query | `vehicleStatus()`, `keySummary()`, `listKeys()` every 30 s (changeable), results with outcome and time, sessions, "query now" |
| Log | Line counts, unrecognized count, last lines and copy, file path, memo and Mark, new file |
| Control | The former single screen (below). Uses the monitor's connection and key |
| Key & Control | Four steps (key, connect, enroll, control) and a button grid of the VCSEC commands, status card, results (`05-key-control-tab.md`) |

Control tab (the former single screen; same groups as Android):

| Group | Buttons (UI label) | Library calls |
|---|---|---|
| Key | Create / load key (`teslable-monitor`, shows protection and accessibility) | `keys.getOrCreate(alias:policy:accessibility:)` |
| BLE | Scan, Connect, VehicleStatus, Key list (Delete per row), Disconnect | `scan(vin:)`, the monitor's connection, `vehicleStatus()`, `listKeys()`, `removeKey()`, `disconnect()` |
| Pairing | Request key enrollment, Confirm enrollment | `addKeyRequest()` (progress events); confirmation polls `sessionInfo(publicKey, INFOTAINMENT)` every 3 s for up to 2 min |
| Session | Handshake VCSEC only, Handshake all, Show session state | `startSession(domains:)`, `sessions` |
| Control | Lock, Unlock, Open / close trunk, Open frunk, Wake up | VCSEC commands; `Uncertain` is followed by a `vehicleStatus()` re-query |
| Result | Copy all results (app info, link, key, timed results, last log lines; no VIN), Clear | – |

The old observation mode (switch that blocked requests, memo + Mark, copy) is merged into the Log tab: `observe` lines are
always written to the log file and Mark writes a `mark` line.

## Real-vehicle runbooks

The step-by-step real-vehicle checks (scan and connect, push observation rounds 1 and 2, M4 pairing and VCSEC control, the
monitor app checklist) are kept in `samples/ios/README.md` with the build number each round used. Results are written to
`docs/handoff/<date>-*.md`.
