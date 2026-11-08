# Monitor App (SFR-010 to SFR-017)

Status: **Android implemented** (build `0.3.0 (3)`) and **iOS implemented** (build `0.5.0 (25)`); unit tests and builds
pass, **real-device checks pending** for both (iOS background reconnect is unconfirmed until the plan Task 2 spike).
Issue #91. The library APIs it needs are available: `Vehicle.events`
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
| SFR-010 | Tab layout: **Live**, **Query**, **Log**, **Control** (the existing single screen moves into Control), then **Key & Control** (SFR-018, `05-key-control-tab.md`) |
| SFR-011 | The VIN is entered once and saved (Android `SharedPreferences`, iOS `UserDefaults`), with a button to clear it. On app start, if a VIN is saved, the app scans and connects automatically. A **Connect** button always tries immediately |
| SFR-012 | When `connection` becomes `Disconnected`, the app calls `Vehicle.disconnect()` and reconnects with back-off (2 s doubling to at most 30 s), also in the background. **Disconnect** stops automatic reconnection until Connect is pressed |
| SFR-013 | While the app is visible, the screen does not turn off (Android `FLAG_KEEP_SCREEN_ON`, iOS `isIdleTimerDisabled` tied to the active scene phase) |
| SFR-014 | Live tab: latest `VehicleEvent.Status` (on iOS also a successful `vehicleStatus()`) drawn as lock, gear, presence, sleep, four doors, frunk, trunk, charge port; next to each item the time it last **changed** (a repeated equal value is not a change). Recent events list including authentication requests. Connection banner (connected / reconnecting, attempt n / disconnected with reason). A note that the trunk may not produce a broadcast. On iOS the Live tab is drawn as a cluster (SFR-022) and these items sit in a detail sheet; there the status also comes from a successful `vehicleStatus()` of the poll (Android: broadcasts only), so a trunk change shows after the next poll |
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

## Key nickname (SFR-019)

The vehicle has **no key-name field**: the VCSEC `KeyMetadata` message carries only `keyFormFactor`, so the car shows
default names and `listKeys()` returns no name (`14-lib-reading-state.md` §A4). To tell keys apart, the Control tab lets
the user give each key a **nickname that exists only on this phone**. It is never sent to the vehicle, never shown on the
car's screen, and never written to the log files or the result list (the result list gets only a line with the slot).

| Rule | Detail |
|---|---|
| Where | Control tab key list (after **Key list**). Each row shows the nickname above the usual label (`slot N formFactor role`, "this device") and a **Name** / **Edit name** button; a text box opens with a note that the name stays on the phone |
| Key | The key id: `WhitelistEntry.keyIdSha1Hex` (SHA-1 of the public key, hex; `14-lib-reading-state.md` §`WhitelistSummary` and `WhitelistEntry`, `13-lib-pairing.md`). **Not the slot**, because slots can change. A row whose entry has no key id (`keyIdSha1Hex` is `null` or empty) cannot have a nickname; its button is off |
| Input | Trimmed (spaces and line breaks at both ends). At most **32 characters** (counted as characters, so one emoji is one); the text box cuts longer input (leading spaces are not counted). Empty or whitespace-only input **removes** the nickname |
| Storage | `UserDefaults` (iOS) / `SharedPreferences` (Android, planned), one entry mapping key id (lower case) → nickname. It is an ordinary app preference, so uninstalling the app removes it. Nicknames of keys later deleted from the car stay until overwritten or cleared; the same key enrolled again gets its old name back |
| Not shown | The public key in full, the VIN. Only the nickname and the usual row label are on screen; the delete confirmation also shows the nickname |

The rules are a small pure type (`KeyNickname`: trim, length check, clamp; `KeyNicknameStore`: set / get / remove over an
injected `UserDefaults`) that is unit tested without a vehicle (`Tests/KeyNicknameTests.swift`). The screen is checked
with the real-device checklist in `samples/ios/README.md` (SFR-019).

Status: iOS built (`0.5.0 (25)`), unit tests pass, real-device check pending. Android: planned, same spec.

## State diagnostics (SFR-023)

The Query tab has a manual area where a person calls `getState()` and reads what the car returns. It exists to collect real-car
facts that `14-lib-reading-state.md` A8.5 lists as unconfirmed (which fields a category carries, the unit of `speed`, whether a
reply is too large, what a sleeping car does). It is not a product feature: there is no periodic `getState`, no automatic retry
and no button for `Location`.

| Rule | Detail |
|---|---|
| Where | Query tab, under the periodic-query section: three buttons **Drive**, **Media**, **MediaDetail**, the last result, and a separate **send to a sleeping car (experiment)** section |
| Enabling | A connection made with a key (`Vehicle.hasKey`), a key the Key & Control tab has confirmed as enrolled, and `sleepStatus == AWAKE` from the latest successful `vehicleStatus()` (the monitor's poll, or the re-read of the last tap). `ASLEEP`, `UNKNOWN` and "not read yet" keep the buttons off, and the reason is shown under them. Buttons are also off while a query runs |
| Order of one tap | (1) `vehicleStatus(timeout: null)`: anything but `AWAKE` ends the tap with **NotSent**. (2) `startSession(setOf(INFOTAINMENT), timeout: null)`: the library sends nothing when the session is already `Ready` (`14-lib-reading-state.md` A8.2: `getState` never starts a session). (3) one `getState(category, timeout: null)`. The default timeout is used (`16-lib-errors.md` §Retries and timeouts) |
| Results | `Success`, `Uncertain` and `Failure` as the library returns them, plus **NotSent** when the app stops before sending (asleep, sleep unknown, `vehicleStatus()` failed, session failed). Each has its own sentence: a size fault says the car answered, a timeout says nothing came back, `NoSession` says nothing was sent. A `Timeout` sentence follows `16-lib-errors.md` (`Timeout(afterSend)` row): `afterSend = true` says it may have been sent (`Uncertain`, the first BLE write had started), that the value was not confirmed and that a query changes nothing so it can be asked again, and notes that BLE writes can be slow in the background; `afterSend = false` says it was not executed (no write had started, or the car had asked for a retry and the wait ran out; `Failure`). No automatic retry. After `Failure(NoSession)` (a cached session the car no longer accepts, A8.2) the next tap starts a new session; the sample does not do it by itself |
| Waking | The screen **never calls `wakeUp()`** and says "does not call `wakeUp()`", not "does not wake the car": what the Infotainment handshake does to a sleeping car is unconfirmed |
| Sleeping-car experiment | A button with its own category choice and a confirmation dialog that says the car may wake. After the confirmation it skips **only the sleep check**; it still needs a connection and an enrolled key, runs the same session step and sends one `getState`. It never calls `wakeUp()`. Intended for one recorded try |
| Overlap | The tab refuses to start while the key is being replaced or while another tab (Control, Key & Control) runs a call, and while a query runs the key cannot be replaced (`KeyOperationGate`). A second tap while a query runs does nothing |
| Connection change | The result and the sleep state belong to the connection that read them. When the connection changes the result is cleared, the sleep state goes back to `UNKNOWN`, and a result that arrives late from the old connection is dropped (not shown, not logged) |

Fields shown. Only these named fields are read; `DriveState` also has route and destination fields and the app never reads
them, and `Location` has no button. Absent is `—` and `0` stays `0` (`14-lib-reading-state.md` A8.3).

| Category | Fields |
|---|---|
| Drive | `speed` and `speed_float` as the raw number followed by "(unit unconfirmed)", never converted to km/h or mph (A8.5); `shift_state` (the member of the `ShiftState` oneof that is set: `P`, `R`, `N`, `D`, `SNA`); `power`; `odometer_in_hundredths_of_a_mile` (raw); `timestamp` (epoch seconds, to tell a fresh reading from a repeated one, A8.3) |
| Media | `media_playback_status`, `audio_volume`, `now_playing_title` (the track title is on screen only), `timestamp` |
| MediaDetail | `now_playing_duration`, `now_playing_elapsed`, `timestamp`. The title is in `MediaState`, not here |

Log. One `query` line per finished tap, nothing else: `call` is `getState(DRIVE)`, `getState(MEDIA)` or `getState(MEDIA_DETAIL)`,
`result` is `Success`, `Uncertain`, `Failure` or `NotSent`, `summary` is `durationMs=<n>` plus `error=<type>` or `reason=<why>`, and
`startedAt` is the time the first library call of the tap started. **No value, no track title, no field name** reaches the log (FR-112, `14-lib-reading-state.md`
A8.3 "Do not log a result"): the log line is built from a small value (`StateLogEntry`) that has no place for them, and the sample's `record` helper (whose summary
goes to the log) is not used for `getState`. Error types are the library's type names (`ResponseTooLarge`, `Timeout(afterSend=true)`, `NoSession`, or the class name), never `message` or the vehicle's own text.

Library calls and manual pages: `vehicleStatus(timeout: nil)` (14 A2), `startSession(domains:timeout:)` (14 A8.2, 15), `getState(category:timeout:)` and
`s.value as? VehicleData`, `KotlinInt?` numbers, `getEpochSecond()` (14 A8.3, 17 §Swift: generated types), result handling and the size fault (16 §`getState` results,
§Response size limit on `getState`). The pure rules (`StateDiagnostics`) and the screen state (`StateDiagnosticsModel`, with a fake link) are unit tested
without a vehicle (`Tests/StateDiagnosticsTests.swift`); the real-device checklist is in `samples/ios/README.md` (SFR-023).

Status: iOS built (`0.5.0 (25)`), unit tests pass, real-device check pending. Android: planned, same spec.

## Wide and foldable screens (SFR-024)

The app follows the large-screen layout of the official Tesla app on the foldable iPhone Duo (app 4.62): on the **inner
display** it shows two panes, a navigation menu on the left and a large, always visible vehicle on the right with
quick actions along the bottom; the **outer display** keeps the normal phone layout. The sample does the same with its
own screens. It uses only the size class and the usable width, so it also applies to an iPad or a split window. It
never looks at the device model, `UIScreen`, the orientation or the hinge angle, and uses no iOS 27.1 fold API.

| Rule | Detail |
|---|---|
| Choice | `DuoLayout.mode(isRegularWidth:width:)` (pure, `Tests/DuoLayoutTests.swift`): **two panes** only when the horizontal size class is `regular` **and** the usable width (safe areas excluded) is at least **540 pt**; otherwise the **phone layout**. The width is measured by `ContentView` and re-evaluated whenever it changes (fold, unfold, rotation, split view). The Xcode 27.2 `iPhone Duo` simulator profile has an outer display of 466 x 678 pt and an inner display of 669 x 951 pt (both 3x) |
| Phone layout | Unchanged: the five tabs Live, Query, Log, Control, Key & Control (SFR-010, SFR-018). Used on the outer display and on ordinary iPhones |
| Left pane | Width 40% of the usable width, between 260 and 520 pt. It follows the large-screen column of the official Tesla app with only data the app has. **Header:** a large title (the nickname of this phone's key if one is set in the Control tab key list, else "Tesla"; the VIN only masked and small) and one status line from gear, lock and sleep (`HomeHeader`: "parked" for P and "driving" for R, N and D (shown in Korean), then lock and sleep when known). **Two filled rounded cards** with a chevron: **Live detail** (time of the last event) and **Key & Control** (hint of the first unfinished step, or "enrolled"). **Plain rows** without a card: **Lock**, **Doors and trunk**, **Sleep**, **Presence** and **Drive** (gear and the speed of the last manual Drive query), each with a grey subtitle from the cluster state or `—` (`HomeStatus`); a tap opens Live detail. Then the plain rows **Query** (outcome and time of the last `vehicleStatus()`), **Log** (lines written) and **Control** (connection), which open their pages. The open page is a filled card without chevron and opens in place under the menu (the menu area shrinks and scrolls); the pages are the same views as the phone tabs (no copies), and Key & Control leaves the command confirmation to the right pane (`ownsCommandDialog: false`). **Footer:** the app build and, only if the last manual Drive query returned it, the odometer (raw, in 1/100 mile, with its age). Nothing is invented: no battery, climate, location, camera or model name. |
| Right pane | The header with the link state and the settings button (the gear stays clear of the right dock), the gear row P R N D, and the **speed**: the raw number of the **last successful manual Drive query of this connection** with "unit unconfirmed" and its age (for example 12 seconds ago); before any such query `—` and a hint to use the Query tab. The app still never calls `getState()` by itself and never calls `wakeUp()` for this (SFR-023). Below: the three-quarter car photo with its callouts (lock, frunk, trunk, charge port), the hint and the latest result line, and the action row. Tapping the car or the closure line opens the detail sheet; the gear button opens the VIN and connection sheet. |
| Callouts and actions | **Callouts:** a lock icon centred above the photo with a thin vertical line down to the roof (`LockCallout`: closed padlock when locked and a tap unlocks, open padlock when unlocked and a tap locks, off when the state is unknown), top left a two-line label, small grey "Open" over bold "Frunk" (shown in Korean), with a thin vertical line down to the hood (asks first), top right "Open/Close" over bold "Trunk" with a line down to the rear; beside the rear a **charge-port bolt** with a short horizontal line, red when open and grey otherwise, not tappable; an open frunk or trunk also gets a red icon where its line meets the car, and each open door a small red door icon with a short line at that door's position. **Action row** along the bottom, plain icon and label: **Wake up** and **Close trunk** (`QuickControls.actionRow`; Auto secure is Model X only, which Key & Control also keeps under "other models", so it is not here; Remote drive is never one tap). Every button is an existing Key & Control button sent through the **same `KeyControlModel.press`**: on only when step 4 is on (dimmed otherwise, with the same orange hint once), a VCSEC session is ensured, the command is sent once, `vehicleStatus()` follows, `Uncertain` is never resent (5. Key & Control Tab), and the enrollment state is checked again when the two-pane layout starts. No new command: no horn, flash or climate. |
| Car photos | `ClusterPhoto.photo(for:)` picks the photo from the same `DuoLayout.Mode`: the **phone layout** (ordinary iPhones, the Duo outer display) uses the top-down `ClusterCarTop` (front at the top), the **two-pane layout** (the Duo inner display, iPad) uses the front-left three-quarter `ClusterCarSide` (front lower left, rear upper right). Open parts are shown **with red icons only** (`OpenMarker` from `ClusterState.openMarkers(on:)`, in part order): four doors, frunk, trunk, charge port. A closed part has no icon, and neither has a part whose state is unknown (the summary text covers it). No door animation, no part overlays. Icon positions are one normalized table per photo (`ClusterPhoto.spot(_:)`, x and y from 0 to 1 from the top left, with unit tests for bounds and order); replacing a photo means editing the table, not the views. The phone photo has no callouts or lines, a tap opens the detail sheet, and the stale dimming is kept. The photos are JPEG in `Assets.xcassets` (900 x 1350 and 1350 x 900, about 100 and 150 KB). Their black background sits on a dark rounded panel that merges with the black screen (the app is always dark), and the callout text uses light colours on that panel. Doors on the far side of the car are not visible in the three-quarter photo, so their icons point at the far edge of the glass roof |
| Tab bar | The five tabs stay as the native `TabView`, shared by both layouts. On the Duo inner display the system draws it as a vertical rail along the right edge, under the status and sensor dock (seen on the iOS 27.2 simulator); the sample uses no iOS 27.1 API and no custom rail. Choosing a tab sets the same `AppNavigation`: Live is the first screen above; Query, Log, Control and Key & Control open that page in the left pane while the car stays; the rail highlight and the left menu follow the same selection. In two-pane mode only the selected tab draws the two panes (the other tabs are empty), so switching tabs rebuilds the panes; the open sheets and the memo are kept by `ScreenContinuity`. |
| Safe areas | Content stays inside the safe area; only the background of the right pane runs to the screen edge. The inner display of the Duo has its status and sensor dock on the **right** edge, so the right inset is larger than the left: nothing is placed under it |
| Theme | The app is **always in dark mode**, whatever the phone's light or dark setting: every tab, both panes, sheets and alerts. It is set once with `UIUserInterfaceStyle = Dark` in the generated `Info.plist` (`project.yml`, both targets), so no screen needs its own colour scheme; a unit test pins the key. The system colours (`systemBackground` and the like) therefore resolve to their dark values. Android is not changed |
| Staying put | One value, `AppNavigation`, is the selection of both layouts: the phone tab and the left page map to each other (`tab` / `pane`, tested), so a fold or unfold keeps the selected section. The page "Live detail" is remembered but shows the Live tab on the phone layout. `ScreenContinuity`, held by `ContentView`, keeps the open Live sheets and the Log memo text across the change, because the two layouts are different view trees. The key operation sheets and dialogs are driven by `KeyControlModel`, which lives in the monitor. Not kept: scroll positions, text focus, the Query interval draft (it is reset to the current interval whenever the page appears, as when switching tabs today) and the "other models" disclosure |
| Not handled | A partly folded posture gets no special handling; the check is that nothing important sits under the fold. Hinge reserved regions, `ArrangementView` and `onHingeChange` (iOS 27.1) are not used |

The decision and the mapping are pure and unit tested without a vehicle (`Tests/DuoLayoutTests.swift`). The screens are
checked with the real-device checklist in `samples/ios/README.md` (SFR-024). The simulator cannot be folded without the
Simulator app, so the two-pane layout was also looked at on an iPad simulator; the posture change on the Duo itself
is a real-device check.

Status: iOS built (`0.5.0 (25)`), unit tests pass, real-device check pending. Android: not applicable today (no foldable target); a large-screen layout would be a separate requirement.

## Background

| | Android | iOS |
|---|---|---|
| Owner of the connection | A foreground service (`foregroundServiceType="connectedDevice"`) owns `TeslaBle`, the reconnect loop, the event subscription and the log writer. Notification shows the link state and a Stop action | One monitor object for the app's lifetime |
| Declarations | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` (asked at runtime). Still no `INTERNET` | `UIBackgroundModes: [bluetooth-central]`, `UIFileSharingEnabled`, `LSSupportsOpeningDocumentsInPlace` (all in `project.yml` `info:`) |
| Reconnect while in background | Same loop as foreground | Same loop as foreground. **Whether iOS lets the app scan and reconnect in the background is unconfirmed** until the spike (plan Task 2) runs on the car; until then the goal is to keep receiving while the link is up (`bluetooth-central` keeps a live link delivering data) |

## Log format

One JSON object per line. Common fields: `ts` (ISO-8601 with offset, device clock), `kind`. The first line of every file
is a `session` line with `platform`, `app` (version and build), `os`, `phone` model.

```json
{"ts":"2026-10-24T10:00:00.123+09:00","kind":"session","platform":"ios","app":"0.4.0(11)","os":"27.0.1","phone":"iPhone"}
{"ts":"…","kind":"event","type":"Status","summary":"lock=LOCKED gear=PARK presence=NOT_PRESENT","unknownFields":["1.8"],"unrecognized":true,"raw":"<hex>"}
{"ts":"…","kind":"observe","line":"[drop:no-handler] 83B from=domain(DOMAIN_VEHICLE_SECURITY) …"}
{"ts":"…","kind":"query","call":"vehicleStatus","result":"Success","summary":"…","startedAt":"…"}
{"ts":"…","kind":"query","call":"getState(DRIVE)","result":"Uncertain","summary":"durationMs=5012 error=Timeout(afterSend=true)","startedAt":"…"}
{"ts":"…","kind":"link","state":"backoff","reason":"…","attempt":3,"delayMs":8000}
{"ts":"…","kind":"mark","memo":"before: open driver door"}
{"ts":"…","kind":"app","event":"scenePhase","phase":"background"}
{"ts":"…","kind":"app","event":"protectedData","state":"unavailable"}
{"ts":"…","kind":"app","event":"start","reason":"connect-button","effect":"woke"}
```

- `type`: `Status`, `AuthenticationRequest`, `Unrecognized`. `unrecognized` is true when `unknownFields` is not empty or
  the type is `Unrecognized`.
- `raw`: the event's raw frame as lowercase hex, after VIN masking.
- `observe`: library lines with tag `observe` (already VIN-masked by the library). BLE `TX:`/`RX:` lines are never written.
- `link.state`: `connecting`, `connected`, `disconnected`, `backoff`, `stopped`.
- `app` (iOS only so far, see "Additions since build 18" below): app and device state around the link, told apart by `event`.

Field notes (both apps write the same names and order, except the iOS-only additions below):

- `event.summary` starts with `lock=<LockState> gear=<Gear> presence=<UserPresence>`, then `sleep=…`, `doors=<FD>/<FP>/<RD>/<RP>`,
  `frunk=…`, `trunk=…`, `chargePort=…` for `Status`; `level=… reasons=[…]` for `AuthenticationRequest`; `unknownFields=[…]` for
  `Unrecognized`.
- `link`: `reason`, `attempt` and `delayMs` are left out when they do not apply. `connecting.attempt` counts the tries since the
  start or since the link dropped. `backoff.attempt` is the number of consecutive failures (a dropped link counts as the
  first) and `delayMs` is the wait before the next try, so the delays read 2000, 4000, 8000, 16000, 30000, 30000. A
  `disconnected` line is written when the link drops (with the library's reason when it gave one), followed by a `backoff` line.
  A connection that stayed up for 10 seconds or more restarts the wait at 2 seconds.
- `query.summary` for `vehicleStatus` uses the same form as `event.summary` of a `Status`. It has no public keys or VINs.
- `query` lines with `call` `getState(<CATEGORY>)` (iOS only, SFR-023, §State diagnostics) come from a manual tap, not from the poll. `result` is
  `Success`, `Uncertain`, `Failure` or `NotSent`; `summary` is `durationMs=<n>` and, unless `Success`, `error=<type>` or `reason=<why>`. They never carry values.

Additions since build 18 (iOS only; #128, from the build 18 link-health analysis). New fields are added at the end of a line and
new lines use a new `kind`, so a parser that ignores unknown keys and kinds keeps working. Android does not write them yet; the
Android app has a gap here until it is brought level.

- `query.startedAt`: the time the request was sent (same format as `ts`). `ts` stays the time the result arrived, so
  `ts - startedAt` is how long the call took. Written for `vehicleStatus`, `keySummary` and `listKeys`.
- `app` / `event: "scenePhase"`: `phase` is `active`, `inactive` or `background` (SwiftUI scene phase). The first `scenePhase` line of a run gives the
  phase at start; the `session` line and a `start` line (auto connect) can come before it.
- `app` / `event: "protectedData"`: `state` is `available` (the phone was unlocked; `protectedDataDidBecomeAvailable`) or
  `unavailable` (`protectedDataWillBecomeUnavailable`, the phone is about to lock).
- `app` / `event: "start"`: written each time the connection loop's `start()` is called, **before** the `connecting` line it may
  cause. `reason`: `launch` (auto connect at app start), `connect-button`, `protected-data` (unlock woke a waiting retry),
  `key-replaced` (reconnect after "delete and recreate the key"). `effect`: `started` (a new loop began), `woke` (it was waiting in
  `backoff` and was told to try at once) or `ignored` (already connecting or connected, nothing happened). A `connecting` line
  with no `start` line of `effect` `started` or `woke` right before it came from the backoff timer (an `ignored` line causes no attempt).
- `link` / `state: "connected"`: `scanHitMs`, `rssi` and `openAttempt` (#134), taken from `Vehicle.scanObservation`
  (`12-lib-getting-started.md` §Connect scan observation). `scanHitMs` is the time from the start of `connect` until the library
  received the advertisement the link opened through (it includes earlier attempts and the waits between them); `rssi` is the
  signal strength in dBm and is left out when the platform gave no value; `openAttempt` is which open attempt made the link
  (above 1 means earlier opens failed, not that the car was scanned again). All three are left out when `connect` used a
  remembered target (no scan ran). `scanHitMs` is a monotonic-clock reading, so for a wall-clock time use the `ts` of the
  `[connect:scan-hit]` `observe` line. A failed `connect` returns no `Vehicle`, so its scan hits are only in the `observe`
  lines (`18-lib-troubleshooting.md`). The monitor does not scan before `connect`, so the measured connection path is unchanged.

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
| `KeyControl.kt`, `KeyControlState.kt`, `Enrollment.kt` | Key & Control tab (`05-key-control-tab.md`): pure step rules and command runner, tab state, and the pair + confirm helper shared with the Control tab |
| `ui/` | `LiveTab`, `QueryTab`, `LogTab`, `ControlTab` (the former single screen), `KeyControlTab`, shared helpers |

Library calls used (manual page and section):

| Call | Where | Manual |
|---|---|---|
| `TeslaBle.create`, `connect(vin, key)`, `vehicle.disconnect()` after every link loss | `MonitorEngine` | 12 §Flow, 17 §Connection slots and dropped links |
| `vehicle.connection` (wait for `Disconnected`) | `MonitorEngine.awaitLoss` | 12 §Next steps, 14 §Connection and session state |
| `vehicle.events` collected in its own coroutine; `raw` read once and masked before it is stored | `MonitorEngine.collectEvents` | 12 §Broadcast events, 14 B2, B2.4 |
| `vehicleStatus()`, `keySummary()`, `listKeys()`, `sessions` every 30 s; `Uncertain` and `Failure` shown as they are | `MonitorEngine.runQueries` | 14 Calls, 16 §Three outcomes |
| `keys.getOrCreate("teslable-monitor", PREFER_HARDWARE, AFTER_FIRST_UNLOCK)`; `Locked` waits and retries, never deletes | `MonitorEngine.ensureKey` | 13 §Key accessibility, 16 §Transport and key store errors |
| `TeslaLogger` (tag `observe`) | `ObserveLogger` in `MonitorEngine.kt` | 18 §Observation log |
| `Vin.parseOrNull`, `scan`, `addKeyRequest`, `sessionInfo`, `startSession`, VCSEC commands, `removeKey` | Control tab (`SampleState`), Key & Control tab (`KeyControlState`), `Enrollment.kt` | 12, 13, 15, 16 |

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
  repeated equal statuses (broadcast or query) only move "last received". Every event, repeat or not, goes into the log file. The first
  status shows "first seen" times because nothing earlier is known. The saved VIN is shown masked.
- **Query tab.** Changing the interval queries once at once and then counts again. The queries do not wake the car.
- **Log files.** A size is counted in decimal MB (20 000 000 and 200 000 000 bytes). Pruning keeps the folder at or below the
  limit, never deleting the file being written. If two files would start in the same second the second one is named one second
  later. Each line is flushed as it is written. The writer has a queue of 4096 lines; a full queue drops the new line and counts it
  (Log tab) instead of blocking. A write error (disk full, folder gone) drops that line (counted) and the next line opens a file
  again; it never crashes the app.
- **Pulling.** `adb pull` puts the files in `samples/logs/android/<date>/logs/`.
- **Style gate.** `lintKotlin` and `detekt` also run on `:samples:android` (main and test sources), so `tools/ci/verify.sh` step (a) covers it. The library-only rules (`explicitApi`, `forbiddenTokens`) do not apply to the sample; detekt layers `config/detekt/detekt-samples.yml` on `detekt.yml` (Compose function naming and length, `android.*` imports, optional injection parameters). The Swift sources in `samples/ios/` are not covered by these Kotlin tasks.

## iOS implementation

Files in `samples/ios/Sources/` (tests in `samples/ios/Tests/`, names as in the Android tests):

| File | Role |
|---|---|
| `MonitorModel.swift` | The one object for the app's lifetime (`@StateObject` in `TeslableSampleApp`). Holds what the screens read, starts the engine once (auto-connect when a VIN is saved, not under XCTest), and creates the Control tab's `SampleModel` and the Key & Control tab's `KeyControlModel` |
| `MonitorEngine.swift` | Owns `TeslaBle`, `ConnectionLoop`, the `Vehicle.events` Task, the query poll Task, the saved VIN (`UserDefaults`), the monitor key and the `LogWriter`. Sends changes to the model through one `AsyncStream` so their order is kept. `observe` lines come through `ObserveLogger`, a `TeslaLogger` that passes everything to `DebugLogger` |
| `ConnectionLoop.swift` | State machine `idle`, `connecting(n)`, `connected(V)`, `backoff(attempt:delay:reason:)`, `stopped`; knows nothing about the library; the clock and the sleeper can be replaced (tests use a manual clock). `start()` returns what it did (`started`, `woke`, `ignored`) and takes a callback that runs before it acts (the `app` / `start` log line) |
| `InitialQueryGate.swift`, `Tests/InitialQueryGateTests.swift` | A gate per connection, closed when the link becomes `connected` and opened when the monitor's first query round ends (or the connection ends). The Key & Control tab's enrollment checks (`listKeys()`) wait on it, so they never overlap the monitor's own first `listKeys()` (`05-key-control-tab.md`). The Control tab's manual **List keys** button does not wait and can still overlap a poll |
| `LogLine.swift`, `LogWriter.swift`, `VinMask.swift` | Log line format, file writer on its own serial queue behind the `LogSink` protocol, VIN masking |
| `LiveState.swift` | Reduces events into the Live tab state (last-changed time per item). A successful `vehicleStatus()` counts as heard on the current link like a broadcast; a per-connection `linkEpoch` drops results that finish after their connection ended (SFR-022) |
| `ClusterState.swift`, `Tests/ClusterStateTests.swift` | Reduces `LiveState` and the link into the cluster values of SFR-022: gear, closure of each `ClusterPart` (only `CLOSED` is closed), texts, dimming, headline. `ClusterPhoto.swift` and `Tests/ClusterPhotoTests.swift` (which photo a layout uses and the anchor table per photo) and `Views/ClusterCarViews.swift` (`ClusterCar`: photo plus red icons; `CarWithCallouts`: the two-pane callouts) draw the car from the `ClusterCarTop` and `ClusterCarSide` photos in `Assets.xcassets` |
| `VehicleMapping.swift` | The only place that turns `VehicleEvent` / `VehicleStatus_` into Swift values (`MonitorEvent`, `StatusSnapshot`); `VehicleBox` carries a `Vehicle` across tasks |
| `SampleModel.swift`, `Views/ControlTab.swift` | Control tab (the former single screen) on the monitor's connection and key |
| `KeyControl.swift`, `KeyControlModel.swift`, `Views/KeyControlTab.swift` | Key & Control tab (`05-key-control-tab.md`): pure step rules and command runner, tab state, screen; library calls go through `BleClient` |
| `KeyNickname.swift`, `Tests/KeyNicknameTests.swift` | Local key nickname (SFR-019, §Key nickname): `KeyNickname` (trim, 32-character check, input clamp) and `KeyNicknameStore` (set / get / remove over an injected `UserDefaults`), pure and unit tested; the Control tab key list (`SampleModel.swift`, `Views/ControlTab.swift`) shows and edits it |
| `KeyReplacement.swift`, `Tests/KeyReplacementTests.swift` | Delete and recreate the monitor key (SFR-020, `05-key-control-tab.md`): `KeyReplacer` (order, locked and failure rules), `KeyReplaceOutcome`, the confirmation text; pure and unit tested. `MonitorEngine.replaceKey()` runs it against `ble.keys` |
| `EnrollmentProgress.swift`, `Tests/EnrollmentPopupTests.swift` | Enrollment progress popup (SFR-021, `05-key-control-tab.md`): `EnrollmentUpdate` (what `BleClient.pair` reports) and `EnrollmentPopup` (phases, rows, texts, buttons, remaining time); pure and unit tested |
| `StateDiagnostics.swift`, `Tests/StateDiagnosticsTests.swift` | State diagnostics (SFR-023, §State diagnostics): pure rules (`StateDiagnostics`: button enabling, value texts, `StateOutcome` sentences, `StateLogEntry`), the `StateLink` / `StateDiagnosticsBackend` seam to the library, and `StateDiagnosticsModel` (one query at a time, drops late results, refuses while a key operation runs). `MonitorEngine.swift` has the live `StateLink` (`vehicleStatus`, `startSession`, `getState`); `VehicleMapping.swift` turns a `VehicleData` into the named fields; `Views/QueryTab.swift` draws it |
| `KeyControlBackend.swift`, `Tests/KeyControlModelTests.swift`, `Tests/KeyOperationGateTests.swift` | `KeyControlBackend`: everything `KeyControlModel` asks of the library and the engine (a fake in tests); `KeyOperationGate` and `KeyHandleCache` guard the key replacement across tabs (`KeyReplacement.swift`) |
| `ContentView.swift`, `Views/` | Phone layout: `TabView` Live / Query / Log / Control / Key & Control; wide layout: `Views/TwoPaneView.swift` (SFR-024); `isIdleTimerDisabled` follows `scenePhase == .active` |
| `DuoLayout.swift`, `Tests/DuoLayoutTests.swift`, `ScreenContinuity.swift`, `Tests/ScreenContinuityTests.swift`, `HomeSummary.swift`, `Tests/HomeSummaryTests.swift`, `Views/TwoPaneView.swift` | Wide and foldable screens (SFR-024, §Wide and foldable screens): pure `DuoLayout.mode` / `leftPaneWidth`, `AppNavigation` (one selection for both layouts), `QuickControls` (car callouts and action row); `ScreenContinuity` (open sheets and memo that survive a layout change, and `vinKnownChanged`: the VIN sheet opens by itself only when the VIN goes from unknown to known, so a right pane rebuilt on every tab change does not reopen it); `HomeSummary` (pure texts of the first screen: lock callout, Drive speed, header, menu, status rows, footer); `TwoPaneView` (left menu, right Tesla-style Live home) |

Library calls used (Swift form) and the manual page and section:

| Call | Where | Manual |
|---|---|---|
| `TeslaBle.companion.create(platform:config:)`, `connect(vin:key:target:options:)`, `vehicle.disconnect()` after every link loss | `MonitorEngine` | 12 §Flow, 17 §Connection slots and dropped links, §Swift |
| `vehicle.connection` (`for await`, wait for `.disconnected`) | `MonitorEngine.awaitLoss` | 12 §Next steps, 14 §Connection and session state |
| `vehicle.events` (`for await`, `onEnum(of:)`) in its own Task; `raw` read once and masked before it is stored | `MonitorEngine.collectEvents`, `MonitorEvent.init` | 12 §Broadcast events, 14 B2, B2.4 |
| `vehicleStatus(timeout: nil)`, `keySummary(timeout: nil)`, `listKeys(timeout: nil)`, `sessions` every 30 s; `Uncertain` and `Failure` shown as they are | `MonitorEngine.runQueries` | 14 Calls, 16 §Three outcomes |
| `vehicleStatus(timeout: nil)`, `startSession(domains: [.infotainment], timeout: nil)`, `getState(category:timeout:)` on a button tap, in that order, once, no retry, no `wakeUp()` | `LiveStateLink` (`MonitorEngine.swift`) | 14 A2, A8.1 to A8.3, A8.5, 16 §`getState` results, 17 §Swift: generated types |
| `keys.getOrCreate(alias:policy:accessibility:)` with `"teslable-monitor"`, `.preferHardware`, `.afterFirstUnlock`; `VehicleErrorKeyStoreErrorLocked` waits and retries, never deletes | `MonitorEngine.ensureKey` | 13 §Key accessibility, 16 §Transport and key store errors |
| `TeslaLogger` (tag `observe`) | `ObserveLogger` | 18 §Observation log |
| `Vin.companion.parseOrNull`, `scan`, `addKeyRequest`, `sessionInfo`, `startSession`, VCSEC commands, `removeKey` | Control and Key & Control tabs (`BleClient`) | 12, 13, 15, 16 |

How it behaves, where the design left room:

- **Same behaviour as Android.** The log schema, the field notes above, the back-off numbers, the 10 s stable reset, the
  VIN runs, the write-error rule and "connect waits for the previous vehicle to be released" are the same code in Swift;
  the same test names exist in `Tests/` (`VinMaskTests`, `LogWriterTests`, `ConnectionLoopTests`, `LiveStateTests`).
- **One connection.** The Control tab uses the monitor's connection and key. Its Connect and Disconnect buttons start and
  stop the monitor's loop. The VIN is entered and cleared on the Live tab; Control shows it masked.
- **Key errors.** `Locked` fails the attempt, so the loop waits and reads the key again; the app also retries at once when
  `UIApplication.protectedDataDidBecomeAvailableNotification` arrives. The key is never deleted. Any other key error
  connects without a key (broadcasts and the three queries need none). The Control tab shows `protection` and
  `accessibility` and a warning for a `WHEN_UNLOCKED` key (delete it, create a new one, enroll it again).
- **Library gaps found.** None; no library request was raised.
- **Log tab.** The old observation mode is gone: `observe` lines are always written to the log file, and its memo and Mark
  buttons write `mark` lines. "Copy last lines" and the Control tab's "Copy all results" (which now ends with the last log
  lines) replace the old copy button.
- **Log files.** Same rules as Android (decimal MB, pruning never deletes the open file, names one second apart, a queue of
  4096 lines, a write error drops and counts the line and the next line reopens a file). Lines are written to the file as they
  arrive (no buffering). Files are in `Documents/logs/` with the default file protection (readable after the first unlock).
- **Screen on.** `isIdleTimerDisabled` is true only while the scene is active; it goes back to the system default in the
  background and when inactive.
- **Background.** The same loop runs in the background as long as iOS keeps the app running. Whether iOS wakes the app to
  scan and reconnect after a link loss is **unconfirmed** (plan Task 2 spike); while iOS keeps the app suspended the loop does not run, and the
  gap between two `link` lines shows how long that was.
- **Pulling.** `tools/samples/pull-monitor-logs.sh ios [--device <id>]` copies `Documents/logs` of the device build
  (`dev.mirolab.TeslableSampleDevice`) with `xcrun devicectl device copy from` into `samples/logs/ios/<date>/`.
- **Version.** `project.yml` has `MARKETING_VERSION 0.5.0` and `CURRENT_PROJECT_VERSION 12`; raise the build number for
  every real-device round.

## Real-vehicle checklist (both apps)

1. Fresh install, enter VIN, create the monitor key, enroll it; restart the app → connects on its own.
2. Live tab: open and close the driver door, shift P/R/N/D, lock from outside → each change appears with its time (iOS: in the detail sheet).
3. Walk away until the link drops, come back → reconnects without touching the phone (screen on, then screen locked).
4. Locked phone for 30 minutes near the car → still connected or reconnected; log keeps growing.
5. Disconnect button → no reconnect attempts for 2 minutes.
6. Pull the logs on the Mac with the script; the first line is a `session` line; no unmasked VIN (`grep -E '[A-HJ-NPR-Z0-9]{17}'` finds nothing).

iOS only (the full runbook with expected results is in `samples/ios/README.md` §Monitor app: real-device checklist):

7. The monitor key reports `afterFirstUnlock` after the app is started again (Secure Enclave key, plan Task 11).
8. Handshake on a locked iPhone with the monitor key (plan Task 11); if creating a Secure Enclave key fails while the phone
   is locked, copy the exact error text.
9. Background: lock the phone, leave and come back; read the `link` lines to see whether iOS reconnected on its own (the
   spike result, plan Task 2).
