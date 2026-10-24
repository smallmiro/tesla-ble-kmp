# Key & Control Tab (SFR-018)

A fifth tab in the monitor app (`04-monitor-app.md`) for the everyday flow of a first-time user: **get a key, enroll it
in the vehicle, then control the vehicle with buttons**. The Control tab stays as the diagnostic screen (every call
separately); this tab hides the order of calls behind four steps and a button grid.

Library pages this tab follows: `13-lib-pairing.md` (§Checking whether a key is enrolled, §Enrolling a key),
`15-lib-commands.md` (§Implemented: VCSEC), `16-lib-errors.md` (§Per-command re-query), `14-lib-reading-state.md`
(`vehicleStatus()`), `17-lib-platform-notes.md` (Swift arguments).

## Requirement

| ID | Requirement |
|---|---|
| SFR-018 | Tab **Key & Control** after Control. Four steps, each showing done / to do / blocked with a one-line hint: (1) **Key**: create or load the monitor key; (2) **Connect**: uses the saved VIN and the monitor's connection; (3) **Enroll**: request enrollment (NFC card on the console), progress lines, automatic confirmation; (4) **Control**: a button grid of the available VCSEC commands. Every command first makes sure a VCSEC session exists, then sends the command once; `Uncertain` is never resent and is followed by a `vehicleStatus()` re-query. A status card shows lock, trunk, frunk and sleep from the last `vehicleStatus()` |

## Steps and enabling rules

The rules are a pure function (`KeyControlSteps`) so they are unit tested without a vehicle.

Inputs: key ready (the monitor key exists and is readable), key locked (phone lock blocks the key), VIN saved,
connected with a key (`connection` is `Connected` and `vehicle.hasKey`, `14-lib-reading-state.md`; a key-store error at
connect time connects without a key; step 2 then stays "to do" and its button becomes **Disconnect and connect again**, which stops the monitor's connection and connects again with the key), enrollment (`unknown`, `checking`, `notEnrolled`, `enrolled`), busy (a library call of this tab runs).

| Step | Done when | Blocked when (hint) | Button enabled when |
|---|---|---|---|
| 1 Key | key ready | key locked (hint: unlock the phone and press again) | not busy |
| 2 Connect | connected with a key | no VIN saved (hint: save the VIN on the Live tab) | VIN saved, not connected, not busy; or connected without a key and not busy (reconnect) |
| 3 Enroll | enrollment = `enrolled` | key not ready or not connected (hint: finish steps 1 and 2 first) | key ready, connected, enrollment ≠ `enrolled`, ≠ `checking`, not busy |
| 4 Control | – | enrollment ≠ `enrolled` (hint: the buttons turn on once the key is enrolled) | key ready, connected, enrollment = `enrolled`, not busy |

Two more buttons: **Check enrollment** is enabled when the key is ready, connected, enrollment ≠ `checking` and not
busy (it stays on when `enrolled`, so a key removed on the car can be checked again); **Refresh status** is enabled when
connected and not busy (`vehicleStatus()` needs no key).

Enrollment state:

- Becomes `checking` and then `enrolled` / `notEnrolled` / back to `unknown` when the tab checks with `listKeys()`:
  the vehicle entry whose public key equals the local key (`13-lib-pairing.md` §Checking whether a key is enrolled).
  `Uncertain` or `Failure` gives `unknown`. The check runs automatically once each time the tab sees a new connection
  with a ready key (retried once after about 5 s when it ends `unknown`, because it can lose the race with the monitor's
  first queries), each time the tab becomes visible, and on the **Check enrollment** button. An automatic check never
  writes a "connect first" line.
- Becomes `enrolled` when the pairing confirmation (`sessionInfo(publicKey, INFOTAINMENT)` polling, every 3 s for up to
  2 min, `13-lib-pairing.md` §Enrolling a key) succeeds. As in the manual's `waitUntilEnrolled`, polling continues on
  `KeyNotPaired` and `Uncertain` and stops at once on any other failure (for example `NotConnected` after the link drops).
- Is re-checked when the Control tab confirms an enrollment or removes this device's key.
- Becomes `notEnrolled` when `startSession` before a command, or the command itself (the session was already `Ready`, so
  `startSession` sent nothing), fails with `KeyNotPaired`.
- Goes back to `unknown` when the connection is lost or the key changes.

## Running a command

`KeyControlRunner.run(ensureSession, command, requery)` (pure, unit tested with fakes):

1. `ensureSession` = `startSession(setOf(VCSEC))`. A domain already `Ready` succeeds without a request
   (`15-lib-commands.md`). On `Failure` (or `Uncertain`) the command is **not sent** and nothing is re-queried; the result line says why, and `KeyNotPaired`
   sets enrollment to `notEnrolled`. `KeyStoreError.Locked` says to unlock the phone.
2. `command` is called exactly once.
3. `Success` → "succeeded"; `Failure` → "failed: <message>"; `Uncertain` → "uncertain: <message>", never resent.
4. `requery` = `vehicleStatus()` always runs after the command; a failed re-query is added to the same result line (also after `Success`, to refresh the status card;
   after `Uncertain` it is the re-query the manual requires).

## Buttons

| Group | Button (meaning of the Korean UI label) | Call | Confirm first |
|---|---|---|---|
| Doors | Lock | `lock()` | no |
| Doors | Unlock | `unlock()` | no |
| Doors | Wake up | `wakeUp()` | no |
| Trunk | Trunk open / close (toggle) | `actuateTrunk()` | no |
| Trunk | Close trunk | `closeTrunk()` | no |
| Trunk | Open frunk | `openFrunk()` | yes (it cannot be closed remotely) |
| Drive | Allow remote drive | `remoteDrive()` | yes |
| Model X | Auto secure | `autoSecureVehicle()` | no |
| Cybertruck | Tonneau open / close / stop | `openTonneau()` / `closeTonneau()` / `stopTonneau()` | no |

Confirming a dialog checks the step 4 rule again; if it no longer holds, or another call is running, nothing is sent and
a result line says why. Model X and Cybertruck buttons sit in a collapsed "Other models" section. Infotainment commands (climate, charging, horn,
lights) wait for M5 and are not shown.

Other controls: a status card (lock, trunk, frunk, sleep, time of the last read) with a **Refresh status** button
(`vehicleStatus()`), and a result list (newest first, time stamped, no VIN). The status card belongs to one connection: it is
cleared when the connection changes, and a re-query that ends after the connection changed does not update it.

## Concurrency

All library calls of this tab run through the tab's own `exclusive { }` (one at a time, buttons disabled while busy). On
Android they run in the view model's scope, so switching tabs or rotating the phone does not cancel them. The
Control tab has its own guard; the library serializes VCSEC commands per connection, so a press on both tabs at once
cannot interleave on the wire.

## Implementation

| | Android | iOS |
|---|---|---|
| Pure logic + tests | `KeyControl.kt` (`KeyControlSteps`, `KeyControlRunner`, `Enrollment`), `src/test/.../KeyControlTest.kt` | `KeyControl.swift`, `Tests/KeyControlTests.swift` |
| State | `KeyControlState.kt` (made in `MainViewModel`) | `KeyControlModel.swift` (made in `MonitorModel`) |
| Screen | `ui/KeyControlTab.kt` | `Views/KeyControlTab.swift` |

Both apps use the monitor's connection and the monitor key (`teslable-monitor`): `MonitorEngine.ensureKey()`,
`currentVehicle()`, `currentKey()`, `connectNow()`.

## Real-device checklist

1. Fresh install: step 1 shows "to do"; tap → key created, step 1 done.
2. VIN saved, car nearby: step 2 done after connect; enrollment check runs (`checking` → `notEnrolled` for a new key).
3. Step 3: tap, put the key card on the console, approve on the screen → progress lines, then `enrolled`; step 4 buttons
   turn on.
4. Lock / unlock / wake: result "succeeded" and the status card follows.
5. Trunk toggle and close, frunk with the confirmation dialog.
6. Reinstall with the key still enrolled: after connect the check gives `enrolled` without pairing again.
7. Remove the key on the car's screen, press a command: the session fails with `KeyNotPaired`, step 3 becomes "to do".
