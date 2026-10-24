# Monitor App (SFR-010 to SFR-017)

Status: **Android implemented** (build `0.2.0 (2)`; unit tests and build pass, **real-device
check pending**). **iOS planned** (plan Task 10). Issue #91. The library APIs it needs are available: `Vehicle.events`
(`12-lib-getting-started.md` §Broadcast events, `14-lib-reading-state.md` B2) and the key accessibility `KeyAccessibility`
(`13-lib-pairing.md` §Key accessibility, FR-037). Library design:
`docs/superpowers/specs/2026-10-24-vehicle-events-monitor-design.md`. Plan:
`docs/superpowers/plans/2026-10-24-vehicle-events-monitor.md`.

## Purpose

A phone left running in or near the car connects on its own, stays connected (reconnecting in the background when the
link drops), shows the vehicle state live, polls everything the library can query, and records every broadcast with its
raw bytes so that unknown protocol fields can be analysed later (`protocol-analyst`, `analyze-monitor-logs` skill).

## Requirements

| ID | Requirement |
|---|---|
| SFR-010 | Tab layout: **Live**, **Query**, **Log**, **Control** (the existing single screen moves into Control) |
| SFR-011 | The VIN is entered once and saved (Android `SharedPreferences`, iOS `UserDefaults`), with a button to clear it. On app start, if a VIN is saved, the app scans and connects automatically. A **Connect** button always tries immediately |
| SFR-012 | When `connection` becomes `Disconnected`, the app calls `Vehicle.disconnect()` and reconnects with back-off (2 s doubling to at most 30 s), also in the background. **Disconnect** stops automatic reconnection until Connect is pressed |
| SFR-013 | While the app is visible, the screen does not turn off (Android `FLAG_KEEP_SCREEN_ON`, iOS `isIdleTimerDisabled` tied to the active scene phase) |
| SFR-014 | Live tab: latest `VehicleEvent.Status` drawn as lock, gear, presence, sleep, four doors, frunk, trunk, charge port; next to each item the time it last **changed** (a repeated equal value is not a change). Recent events list including authentication requests. Connection banner (connected / reconnecting, attempt n / disconnected with reason). A note that the trunk may not produce a broadcast |
| SFR-015 | Query tab: polls `vehicleStatus()`, `keySummary()`, `listKeys()` every 30 s (changeable on screen) and shows `sessions`. Each result shows `Success` / `Uncertain` / `Failure` and its time. A "query now" button |
| SFR-016 | Log tab and log files: every line in the format below is appended to a JSON Lines file; a memo field with a **Mark** button writes a `mark` line; the tab shows line counts, the unrecognized-event count, the last lines and the file location, and can start a new file |
| SFR-017 | `tools/samples/pull-monitor-logs.sh android|ios` copies the log folder from a USB-connected phone to `samples/logs/<platform>/<date>/` (git-ignored) |

## Key on a locked phone

The monitor uses its own key alias `teslable-monitor`, created with
`keys.getOrCreate("teslable-monitor", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK)` so that the
handshake works while the phone is locked (iOS; Android keys already report `AFTER_FIRST_UNLOCK`, not yet confirmed on a
device). Accessibility is fixed when a key is created: a phone that already has a key under that alias whose
`key.accessibility` is `WHEN_UNLOCKED` is told to delete it, create a new one and enroll it on the car again. The Control
tab shows the key's accessibility. The app reads the key when it starts and keeps that handle; on
`KeyStoreError.Locked` (the phone was not unlocked since restart, or a `WHEN_UNLOCKED` key while locked) it waits, reads
it again after unlock and never deletes the key (`13-lib-pairing.md` §Key accessibility, `16-lib-errors.md`).

## Background

| | Android | iOS |
|---|---|---|
| Owner of the connection | A foreground service (`foregroundServiceType="connectedDevice"`) owns `TeslaBle`, the reconnect loop, the event subscription and the log writer. Notification shows the link state and a Stop action | One monitor object for the app's lifetime |
| Declarations | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` (asked at runtime). Still no `INTERNET` | `UIBackgroundModes: [bluetooth-central]` |
| Reconnect while in background | Same loop as foreground | Depends on the spike result (plan Task 2); until then the goal is to keep receiving while the link is up |

## Log format

One JSON object per line. Common fields: `ts` (ISO-8601 with offset, device clock), `kind`. The first line of every file
is a `session` line with `platform`, `app` (version and build), `os`, `phone` model.

```json
{"ts":"2026-10-24T10:00:00.123+09:00","kind":"session","platform":"ios","app":"0.2.0(9)","os":"27.0.1","phone":"iPhone"}
{"ts":"…","kind":"event","type":"Status","summary":"lock=LOCKED gear=PARK presence=NOT_PRESENT","unknownFields":["1.8"],"unrecognized":true,"raw":"<hex>"}
{"ts":"…","kind":"observe","line":"[drop:no-handler] 83B from=domain(DOMAIN_VEHICLE_SECURITY) …"}
{"ts":"…","kind":"query","call":"vehicleStatus","result":"Success","summary":"…"}
{"ts":"…","kind":"link","state":"backoff","reason":"…","attempt":3,"delayMs":8000}
{"ts":"…","kind":"mark","memo":"before: open driver door"}
```

- `type`: `Status`, `AuthenticationRequest`, `Unrecognized`. `unrecognized` is true when `unknownFields` is not empty or
  the type is `Unrecognized`.
- `raw`: the event's raw frame as lowercase hex, after VIN masking.
- `observe`: library lines with tag `observe` (already VIN-masked by the library). BLE `TX:`/`RX:` lines are never written.
- `link.state`: `connecting`, `connected`, `disconnected`, `backoff`, `stopped`.

Field notes (both apps write the same names and order):

- `event.summary` starts with `lock=<LockState> gear=<Gear> presence=<UserPresence>`, then `sleep=…`, `doors=<FD>/<FP>/<RD>/<RP>`,
  `frunk=…`, `trunk=…`, `chargePort=…` for `Status`; `level=… reasons=[…]` for `AuthenticationRequest`; `unknownFields=[…]` for
  `Unrecognized`.
- `link`: `reason`, `attempt` and `delayMs` are left out when they do not apply. `connecting.attempt` counts the tries since the
  start or since the link dropped. `backoff.attempt` is the number of consecutive failures (a dropped link counts as the
  first) and `delayMs` is the wait before the next try, so the delays read 2000, 4000, 8000, 16000, 30000, 30000. A
  `disconnected` line is written when the link drops (with the library's reason when it gave one), followed by a `backoff` line.
  A connection that stayed up for 10 seconds or more restarts the wait at 2 seconds.
- `query.summary` for `vehicleStatus` uses the same form as `event.summary` of a `Status`. It has no public keys or VINs.

Rules:

- **VIN masking** before writing: any ASCII run of **17 or more** characters from `[A-HJ-NPR-Z0-9]` in the raw bytes or in
  text is replaced with the same length, keeping the first 3 and last 4 characters **of the run** (the other characters
  become `*`). A VIN with such characters right before or after it is masked as part of one longer run.
- Mark memo: at most 80 characters, empty memo ignored, VIN-shaped text masked.
- Files: `monitor-<yyyyMMdd-HHmmss>.jsonl`; a new file at 20 MB; oldest files deleted when the folder exceeds 200 MB.
- Writing runs on its own queue and never blocks event collection.
- The writer sits behind a small `LogSink` interface (file implementation only; a cloud sink may be added later with an ADR).
- Location: Android `getExternalFilesDir("logs")` (= `Android/data/dev.mirolab.teslable.sample/files/logs/`, readable by
  `adb pull`); iOS `Documents/logs/` with `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace` (also visible in
  Finder and the Files app).

## Android implementation

Files in `samples/android/src/main/kotlin/dev/mirolab/teslable/sample/`:

| File | Role |
|---|---|
| `MonitorService.kt` | Foreground service (`foregroundServiceType="connectedDevice"`). Shows the notification (link state, **Stop**) and keeps the engine alive. Started while the screen is visible, after `BLUETOOTH_CONNECT` was granted. `START_NOT_STICKY` |
| `MonitorEngine.kt` | One per process: `TeslaBle`, `ConnectionLoop`, the `Vehicle.events` subscription, the 30 s query poll, the saved VIN, the monitor key, the log writer. `observe` lines come through a `TeslaLogger` that forwards everything else to `DebugLogger` |
| `ConnectionLoop.kt` | State machine `Idle`, `Connecting(attempt)`, `Connected`, `Backoff(attempt, delay, reason)`, `Stopped`; knows nothing about the library (unit-tested with virtual time) |
| `LogLine.kt`, `LogWriter.kt`, `VinMask.kt` | Log line format, file writer behind the `LogSink` interface, VIN masking |
| `LiveState.kt` | Reduces events into the Live tab state (last-changed time per item) |
| `ui/` | `LiveTab`, `QueryTab`, `LogTab`, `ControlTab` (the former single screen), shared helpers |

Library calls used (manual page and section):

| Call | Where | Manual |
|---|---|---|
| `TeslaBle.create`, `connect(vin, key)`, `vehicle.disconnect()` after every link loss | `MonitorEngine` | 12 §Flow, 17 §Connection slots and dropped links |
| `vehicle.connection` (wait for `Disconnected`) | `MonitorEngine.awaitLoss` | 12 §Next steps, 14 §Connection and session state |
| `vehicle.events` collected in its own coroutine; `raw` read once and masked before it is stored | `MonitorEngine.collectEvents` | 12 §Broadcast events, 14 B2, B2.4 |
| `vehicleStatus()`, `keySummary()`, `listKeys()`, `sessions` every 30 s; `Uncertain` and `Failure` shown as they are | `MonitorEngine.runQueries` | 14 Calls, 16 §Three outcomes |
| `keys.getOrCreate("teslable-monitor", PREFER_HARDWARE, AFTER_FIRST_UNLOCK)`; `Locked` waits and retries, never deletes | `MonitorEngine.ensureKey` | 13 §Key accessibility, 16 §Transport and key store errors |
| `TeslaLogger` (tag `observe`) | `ObserveLogger` in `MonitorEngine.kt` | 18 §Observation log |
| `Vin.parseOrNull`, `scan`, `addKeyRequest`, `sessionInfo`, `startSession`, VCSEC commands, `removeKey` | Control tab (`SampleState`) | 12, 13, 15, 16 |

How it behaves, where the design left room:

- **One connection.** The Control tab uses the monitor's connection and key (the car allows 3 BLE connections). Its Connect
  and Disconnect buttons start and stop the monitor's loop. A new
  connection waits until the previous vehicle has been disconnected.
- **One engine per process.** It is created on first use and kept for the process (one `TeslaBle` per process,
  `12-lib-getting-started.md`); the service keeps the process in the foreground. The notification's **Stop** disconnects,
  stops automatic reconnection and takes the service down. Opening the app again in the same process does **not** connect
  by itself: press Connect. A new process connects by itself when a VIN is saved.
- **Key errors.** `KeyStoreError.Locked` fails the attempt, so the loop waits and reads the key again (the key is never
  deleted). Any other key error connects without a key: broadcasts and the three queries need none (`14-lib-reading-state.md` B2.4).
  The Control tab shows the key's `protection` and `accessibility` and a warning if the key is `WHEN_UNLOCKED`.
- **Live tab.** The recent-events list holds changes, authentication requests and unrecognized broadcasts (at most 100);
  repeated equal status broadcasts only move "last broadcast". Every event, repeat or not, goes into the log file. The first
  status shows "first seen" times because nothing earlier is known. The saved VIN is shown masked.
- **Query tab.** Changing the interval queries once at once and then counts again. The queries do not wake the car.
- **Log files.** A size is counted in decimal MB (20 000 000 and 200 000 000 bytes). Pruning keeps the folder at or below the
  limit, never deleting the file being written. If two files would start in the same second the second one is named one second
  later. Each line is flushed as it is written. The writer has a queue of 4096 lines; a full queue drops the new line and counts it
  (Log tab) instead of blocking. A write error (disk full, folder gone) drops that line (counted) and the next line opens a file
  again; it never crashes the app.
- **Pulling.** `adb pull` puts the files in `samples/logs/android/<date>/logs/`.
- **Not covered by the gate.** `lintKotlin` and `detekt` run only on library modules, not on `samples/`.

## Real-vehicle checklist (both apps)

1. Fresh install, enter VIN, create the monitor key, enroll it; restart the app → connects on its own.
2. Live tab: open and close the driver door, shift P/R/N/D, lock from outside → each change appears with its time.
3. Walk away until the link drops, come back → reconnects without touching the phone (screen on, then screen locked).
4. Locked phone for 30 minutes near the car → still connected or reconnected; log keeps growing.
5. Disconnect button → no reconnect attempts for 2 minutes.
6. Pull the logs on the Mac with the script; the first line is a `session` line; no unmasked VIN (`grep -E '[A-HJ-NPR-Z0-9]{17}'` finds nothing).
