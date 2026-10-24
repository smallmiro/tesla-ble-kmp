# Monitor App (SFR-010 to SFR-017)

Status: **planned** (issue #91). The library features it needs (`Vehicle.events`, key
accessibility on iOS) are not available yet; this page describes the target. Library design:
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

The monitor uses its own key alias `teslable-monitor`, created with `KeyAccessibility.AFTER_FIRST_UNLOCK` so that the
handshake works while the phone is locked (iOS; Android keys already work when locked). Accessibility is fixed when a key
is created: a phone that already has a key under that alias with `WHEN_UNLOCKED` is told to delete it, create a new one
and enroll it on the car again. The Control tab shows the key's accessibility.

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

Rules:

- **VIN masking** before writing: any ASCII run matching `[A-HJ-NPR-Z0-9]{17}` in the raw bytes or in text is replaced
  with the same length, keeping the first 3 and last 4 characters (the other characters become `*`).
- Mark memo: at most 80 characters, empty memo ignored, VIN-shaped text masked.
- Files: `monitor-<yyyyMMdd-HHmmss>.jsonl`; a new file at 20 MB; oldest files deleted when the folder exceeds 200 MB.
- Writing runs on its own queue and never blocks event collection.
- The writer sits behind a small `LogSink` interface (file implementation only; a cloud sink may be added later with an ADR).
- Location: Android `getExternalFilesDir("logs")` (= `Android/data/dev.mirolab.teslable.sample/files/logs/`, readable by
  `adb pull`); iOS `Documents/logs/` with `UIFileSharingEnabled` and `LSSupportsOpeningDocumentsInPlace` (also visible in
  Finder and the Files app).

## Real-vehicle checklist (both apps)

1. Fresh install, enter VIN, create the monitor key, enroll it; restart the app → connects on its own.
2. Live tab: open and close the driver door, shift P/R/N/D, lock from outside → each change appears with its time.
3. Walk away until the link drops, come back → reconnects without touching the phone (screen on, then screen locked).
4. Locked phone for 30 minutes near the car → still connected or reconnected; log keeps growing.
5. Disconnect button → no reconnect attempts for 2 minutes.
6. Pull the logs on the Mac with the script; the first line is a `session` line; no unmasked VIN (`grep -E '[A-HJ-NPR-Z0-9]{17}'` finds nothing).
