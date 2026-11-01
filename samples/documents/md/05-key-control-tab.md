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

## Delete and recreate the key (SFR-020)

Step 1 has a second button, **Delete and recreate the key**, that replaces the monitor key `teslable-monitor` with a new one
(for example after the key was removed from the car for good, or the key store is damaged). iOS built; Android: planned,
same spec. Library: `13-lib-pairing.md` §Deleting or replacing a key locally and §Key accessibility,
`17-lib-platform-notes.md` §Session cache.

| Rule | Detail |
|---|---|
| Enabled | The key is ready, the key is not locked, and no other call of this tab runs. A connection is **not** needed |
| Confirmation | Always, before anything changes. It says: the old key is removed from this phone **permanently** and cannot be restored; the car **still lists the old public key** until it is removed on the car (its screen, or `removeKey` from another enrolled key); the new key is not enrolled, so it must be **enrolled again with the key card** (step 3); the connection is dropped and made again |
| Locked | The key store is read first (`keys.get(alias)`). `KeyStoreError.Locked` changes nothing, not even the connection, and the result says to unlock the phone (`13-lib-pairing.md`: never delete or re-create on `Locked`). A `Locked` during the delete restores the old key and reconnects; a `Locked` during the create counts as a failed create (no retry) |
| Order (`KeyReplacer`, pure) | read the key store → disconnect (only if the monitor connection was running: connecting, connected or waiting to retry; a connection the user stopped stays stopped) → drop the key handle → `keys.delete(alias)` → `keys.getOrCreate(alias, PREFER_HARDWARE, AFTER_FIRST_UNLOCK)`, the same settings as step 1 → reconnect with the new key |
| Why the connection goes first | The live `Vehicle`, its sessions and the session cache belong to the old key (the cache is keyed by the SHA-1 of the public key). After a key change the next `connect` discards the old cache and handshakes again, so the old connection is not kept |
| Failed delete | The new key is **not** created. The old key is read again (`keys.get`, never `getOrCreate`), so the screen shows what is really there; the connection is made again if it was running; the result says the old key is unchanged |
| Failed create | The old key is gone and there is no new key. No silent retry and no reconnect; the result says to press step 1 |
| After success | Step 3 goes back to "not enrolled" (a new key is not on the car; the check after reconnecting confirms it). The status card and the enrollment progress lines are cleared, because they came from the old key's connection. The Control tab key list and key info are cleared |
| Nickname | A key nickname (SFR-019) belongs to the old key id and stays with it; it is **not** moved to the new key |

Implementation (iOS): `KeyReplacement.swift` (`KeyReplacer`, `KeyReplaceOutcome`, the confirmation text),
`MonitorEngine.replaceKey()`, `KeyControlModel.replaceKey()`, `Tests/KeyReplacementTests.swift`.

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
| Pure logic + tests | `KeyControl.kt` (`KeyControlSteps`, `KeyControlRunner`, `Enrollment`), `src/test/.../KeyControlTest.kt` | `KeyControl.swift`, `Tests/KeyControlTests.swift`; `KeyReplacement.swift`, `Tests/KeyReplacementTests.swift` (SFR-020) |
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

SFR-020 (delete and recreate the key; iOS runbook in `samples/ios/README.md`):

8. Press **Delete and recreate the key**: the dialog states the four points (permanent removal from this phone, the car
   still lists the old public key, enroll again with the key card, the connection is dropped). Cancel changes nothing.
9. Confirm while connected and enrolled: the connection drops and comes back, step 1 stays done, step 3 shows "not
   enrolled" after the check, the status card shows nothing from the old connection, the Control tab key list is empty.
   **Key list** then shows the old public key on the car and no row marked "this device".
10. Enroll the new key (step 3). A nickname given to the old key id does not appear on the new key.
11. After a restart, before the first unlock: the button is off, or the result says the key store is locked and nothing
    changed (no key deleted, connection unchanged).
12. With the VIN saved but disconnected (Disconnect pressed): a new key is created and no connection is started.
