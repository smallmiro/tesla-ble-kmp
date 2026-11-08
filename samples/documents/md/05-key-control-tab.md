# Key & Control Tab (SFR-018)

A fifth tab in the monitor app (`04-monitor-app.md`) for the everyday flow of a first-time user: **get a key, enroll it
in the vehicle, then control the vehicle with buttons**. The Control tab stays as the diagnostic screen (every call
separately); this tab hides the order of calls behind four steps and a button grid.

Library pages this tab follows: `13-lib-pairing.md` (§Checking whether a key is enrolled, §Enrolling a key),
`15-lib-commands.md` (§Implemented: VCSEC; §Implemented: Infotainment climate and charging for SFR-025), `16-lib-errors.md`
(§Per-command re-query, §`getState` results), `14-lib-reading-state.md` (`vehicleStatus()`, A8 `getState`),
`17-lib-platform-notes.md` (Swift arguments).

## Requirement

| ID | Requirement |
|---|---|
| SFR-018 | Tab **Key & Control** after Control. Four steps, each showing done / to do / blocked with a one-line hint: (1) **Key**: create or load the monitor key; (2) **Connect**: uses the saved VIN and the monitor's connection; (3) **Enroll**: request enrollment (NFC card on the console), progress lines, automatic confirmation; (4) **Control**: a button grid of the available VCSEC commands. Every command first makes sure a VCSEC session exists, then sends the command once; `Uncertain` is never resent and is followed by a `vehicleStatus()` re-query. A status card shows lock, trunk, frunk and sleep from the last `vehicleStatus()` |
| SFR-025 | Infotainment section in step 4 (after the VCSEC grid): **climate on / off**, **charge start / stop**, and **read climate / read charge** (`getState(CLIMATE)` / `getState(CHARGE)`, also the re-query after `Uncertain`). Same enabling rule as step 4. One tap is: `vehicleStatus()` (only `AWAKE` goes on), `startSession(setOf(INFOTAINMENT))`, then **one** call. `wakeUp()` is never called and nothing is resent. `Uncertain` is shown as it is and the category is marked "unconfirmed" until a read or a later success settles it. A result that arrives after the connection changed is dropped. The log keeps only the command, the outcome, the duration and the error type. Details in §Infotainment controls (SFR-025) below |

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
  with a ready key (retried once after about 5 s when it ends `unknown`), each time the tab becomes visible, and on the
  **Check enrollment** button. On iOS every one of these checks, manual ones included, first waits for the monitor's first query
  round after a connect to end, so the two `listKeys()` do not run at the same time and share one time budget on a slow connect.
  While it waits the tab is `busy`, so its other buttons are refused for up to about 15 s (three queries of up to 5 s each).
  The check can still end `unknown` and be retried, for example when another `listKeys()` runs at the same time: the Control
  tab's manual **List keys** button does not wait and can still overlap a poll or a check. An automatic check never
  writes a "connect first" line.
- Becomes `enrolled` when the pairing confirmation (`sessionInfo(publicKey, INFOTAINMENT)` polling, every 3 s for up to
  2 min, `13-lib-pairing.md` §Enrolling a key) succeeds. As in the manual's `waitUntilEnrolled`, polling continues on
  `KeyNotPaired` and `Uncertain` and stops at once on any other failure (for example `NotConnected` after the link drops).
- Is re-checked when the Control tab confirms an enrollment or removes this device's key.
- Becomes `notEnrolled` when `startSession` before a command, or the command itself (the session was already `Ready`, so
  `startSession` sent nothing), fails with `KeyNotPaired`.
- Goes back to `unknown` when the connection is lost or the key changes.

## Enrollment progress popup (SFR-021)

Pressing **Request enrollment** (step 3) opens a blocking sheet at once and keeps it until the flow ends. It shows the
existing flow (`addKeyRequest`, then the confirmation of `13-lib-pairing.md` §Enrolling a key) as three rows and one
message. iOS built; Android: planned, same spec. The Control tab's **Request enrollment** keeps its result lines and has
no popup.

| Phase | When | What the sheet says | Buttons |
|---|---|---|---|
| Sending | the button was pressed | "sending the request" | Cancel |
| Waiting for the card | `Sent` / `WaitingForTap` | request sent: put the key card on the centre console and approve on the car's screen | Cancel |
| Checking | `Accepted`, or the request ended with `TimedOut` | `sessionInfo(publicKey, INFOTAINMENT)` every 3 s: attempt number, elapsed and remaining time out of 2:00 and a progress bar (refreshed at each attempt, so up to 3 s old). After `TimedOut` the text begins with the request timed out and the check is running, then keeps the progress line | Cancel |
| Done | the confirmation succeeded | the key is enrolled, the step 4 buttons work | Close |
| Not confirmed | no confirmation within 2 minutes | not confirmed in 2 minutes; check that the approval was given on the car, then press **Check enrollment** | Close |
| Failed | `Rejected`, `Failed`, a `Failure` of the check other than `KeyNotPaired` (for example `NotConnected`), or the request could not start (no connection or key) | "failed: " and the message | Close |
| Cancelled | the user pressed Cancel | see below | Close |

- **Blocking.** The sheet cannot be swiped away while the flow runs; only **Cancel** (and **Close** once it has ended).
- **Cancel really stops the waiting.** It cancels the task of the flow: the request collection and the 3-second check
  loop end (a `sessionInfo` call already in flight ends within its 5 s timeout). After pressing it the sheet says it is
  stopping, then shows the cancelled state. The step 3 state is not changed by cancelling.
- **What cancelling does not undo.** The request already sent to the car cannot be recalled. The car may still show its
  approval prompt, and a key card tap afterwards can still enroll the key. The cancelled sheet says so and points to
  **Check enrollment**.
- **Dismissed when the app has moved on.** Any change of the connection (link loss, reconnect) or of the key closes the
  sheet at once, stops the flow, and adds a result line ("enrollment waiting was stopped"); updates that arrive afterwards
  are dropped. A finished sheet (done, not confirmed, failed, cancelled) also goes away in that case.
- Step and state logic is pure: `EnrollmentPopup` (phases, rows, texts, button rules, remaining time),
  `EnrollmentUpdate` (what the flow reports), `EnrollmentWaiter.wait(onAttempt:)`; tests in
  `Tests/EnrollmentPopupTests.swift`. The sheet in `Views/KeyControlTab.swift` only draws it.

## Delete and recreate the key (SFR-020)

Step 1 has a second button, **Delete and recreate the key**, that replaces the monitor key `teslable-monitor` with a new one
(for example after the key was removed from the car for good, or the stored key is corrupted). iOS built; Android: planned,
same spec. Library: `13-lib-pairing.md` §Deleting or replacing a key locally and §Key accessibility,
`17-lib-platform-notes.md` §Session cache.

| Rule | Detail |
|---|---|
| Enabled | No other call runs (this tab's, or the Control tab's pairing and commands: otherwise the result says "busy, press again later" and nothing changes), **and** either the key is ready and not locked, or the stored key is **corrupted** (next rows). Any other key error (not read yet, another `keys.get` failure) keeps it off. A connection is **not** needed |
| Confirmation | Always, before anything changes. It says: the old key is removed from this phone **permanently** and cannot be restored; the car **still lists the old public key** until it is removed on the car (its screen, or `removeKey` from another enrolled key); the new key is not enrolled, so it must be **enrolled again with the key card** (step 3); the connection is dropped and made again |
| Locked | The key store is read first (`keys.get(alias)`). `KeyStoreError.Locked` changes nothing, not even the connection, and the result says to unlock the phone (`13-lib-pairing.md`: never delete or re-create on `Locked`). A `Locked` during the delete restores the old key and reconnects; a `Locked` during the create counts as a failed create (no retry) |
| Corrupted key | `KeyStoreError.PlatformFailure` whose `cause` is **exactly** `stored key is corrupted` (an exact match, never a substring: the library puts "key store failure: " in front of it in `message`, and a false positive would delete a healthy enrolled key after the confirmation) (`13-lib-pairing.md` §Deleting or replacing a key locally, `16-lib-errors.md`, `17-lib-platform-notes.md` §Key storage) is the one documented damage case. The key state becomes `corrupted`, step 1 says so, the button is on, and the read gate accepts it (the key cannot be read, that is the point): delete, then create. The confirmation then also says the damaged old key **cannot be recovered**. The library never replaces a corrupted key by itself; the app only does it after this confirmation |
| Other tabs and racing connects | An engine-level gate (`KeyOperationGate`) covers every tab. Replace is refused (`busy`) while a Control-tab call runs; Control-tab calls are refused while the key is replaced; `connectNow`, the automatic connect, the unlock retry and `ensureKey()` do nothing during the replacement, so a connect pressed on Live or Control cannot take the old handle. **The user's last press wins** (SFR-012): a Connect pressed during the replacement is remembered and honoured when the replacement ends with a usable key (success, or the old key unchanged), also when no connection was running; a Disconnect or Clear VIN pressed meanwhile cancels the reconnect, so a stop is never undone. If the replacement ends without a usable key state (failed create, `lost`, `unverified`, `unknownLocked`), nothing connects and the result says so. The key handle cache has a generation: a read that finishes after the handle was dropped is not cached, so the deleted key never stays cached. After the disconnect the replacer checks that no connection remains; if one does, nothing is deleted. `replaced` is therefore never reported with an old-key connection |
| Order (`KeyReplacer`, pure) | read the key store → disconnect (only if the monitor connection was running: connecting, connected or waiting to retry; a connection the user stopped stays stopped) → drop the key handle → `keys.delete(alias)` → `keys.getOrCreate(alias, PREFER_HARDWARE, AFTER_FIRST_UNLOCK)`, the same settings as step 1 → reconnect with the new key (if the link was running or Connect was pressed meanwhile, and no Disconnect after that) |
| Why the connection goes first | The live `Vehicle`, its sessions and the session cache belong to the old key (the cache is keyed by the SHA-1 of the public key). After a key change the next `connect` discards the old cache and handshakes again, so the old connection is not kept |
| Failed delete | The new key is **not** created. The old key is read again (`keys.get`, never `getOrCreate`) and the result follows what was read: the key is there, so the old key is unchanged and the connection is made again if it was running; the key is gone anyway (`lost`), so nothing is created and there is no reconnect (a connect would create a key silently); the state cannot be read (`unverified`), so there is no reconnect either and the result says to press step 1 "Reload"; the key store is locked after the failed delete, so nobody knows whether the key was deleted (`unknownLocked`): no reconnect either (after the unlock a connect would create a key silently), and the result says the state is unknown until the phone is unlocked and step 1 "Reload" is pressed |
| Failed create | The old key is gone and there is no new key. No silent retry and no reconnect; the result says to press step 1 |
| After success | Step 3 goes back to "not enrolled" (a new key is not on the car; the check after reconnecting confirms it). The status card and the enrollment progress lines are cleared, because they came from the old key's connection. The Control tab key list and key info are cleared |
| Nickname | A key nickname (SFR-019) belongs to the old key id and stays with it; it is **not** moved to the new key |

Implementation (iOS): `KeyReplacement.swift` (`KeyReplacer`, `KeyReplaceOutcome`, the confirmation text),
`MonitorEngine.replaceKey()`, `KeyControlModel.replaceKey()`, `KeyOperationGate` and `KeyHandleCache`
(`KeyReplacement.swift`), `Tests/KeyReplacementTests.swift`, `Tests/KeyOperationGateTests.swift` (gate, handle cache and the
engine branches that end before any library call). The tab state is tested with a fake `KeyControlBackend`
(`KeyControlBackend.swift`, `Tests/KeyControlModelTests.swift`): popup lifecycle, cancel, dismissal, dropped late updates,
replacement results. What needs a real car or phone (the Kotlin flow actually ending on cancel, a real corrupted key,
the racing connects) is on the device checklist.

## Running a command

`KeyControlRunner.run(ensureSession, command, requery)` (pure, unit tested with fakes):

1. `ensureSession` = `startSession(setOf(VCSEC))`. A domain already `Ready` succeeds without a request
   (`15-lib-commands.md`). On `Failure` (or `Uncertain`) the command is **not sent** and nothing is re-queried; the result line says why, and `KeyNotPaired`
   sets enrollment to `notEnrolled`. `KeyStoreError.Locked` says to unlock the phone.
2. `command` is called exactly once.
3. `Success` → "succeeded"; `Failure` → "failed: <message>"; `Uncertain` → "uncertain: <message>", never resent. When the error is `Timeout`, the message is followed by a sentence from `16-lib-errors.md` (`Timeout(afterSend)` row, `TimeoutNote`): `afterSend = true` says it may have been sent and the car may have run it, so read the state again, plus a note that BLE writes can be slow in the background; `afterSend = false` says it was not executed (no write had started, the wait for a retry ran out, or the handshake timed out). The re-query of step 4 follows either way.
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
a result line says why. Model X and Cybertruck buttons sit in a collapsed "Other models" section. The Infotainment climate and charging buttons
are a separate section below the grid (§Infotainment controls (SFR-025)); the other Infotainment commands (horn, lights,
windows, media, ...) wait for the rest of M5 and are not shown.

Other controls: a status card (lock, trunk, frunk, sleep, time of the last read) with a **Refresh status** button
(`vehicleStatus()`), and a result list (newest first, time stamped, no VIN). The status card belongs to one connection: it is
cleared when the connection changes, and a re-query that ends after the connection changed does not update it.

**Callouts and actions (SFR-024, iOS).** On a wide screen (iPhone Duo inner display) the right pane shows the lock icon centred above the car photo, **Frunk open** (small "Open" over bold "Front") top left, **Trunk open/close** ("Open/Close" over bold "Trunk") top right, and a bottom row with **Wake up** and **Close trunk** (`QuickControls`). They call the same `KeyControlModel.press`, so every rule on this page applies unchanged: step 4 must be on (otherwise they are dimmed and the same orange hint shows once), **Frunk open** asks first, a session is ensured, the command is sent once, `vehicleStatus()` follows, and `Uncertain` is never resent. The right pane shows the confirmation dialog while this tab is open in the left pane (`ownsCommandDialog: false` here), so there is only one dialog. The latest result line appears under the car.

## Infotainment controls (SFR-025)

A section at the bottom of step 4, under the VCSEC grid and the "Other models" section, titled **Infotainment (climate,
charging)**. It is the first use of the library's Infotainment commands (`15-lib-commands.md` §Implemented: Infotainment
climate and charging, M5; **not confirmed on a real vehicle yet**, #118) and of `getState` (`14-lib-reading-state.md` A8).
Android built (`0.4.0 (4)`); iOS built (`0.5.0 (26)`). Both apps use the same Korean UI labels, one per row below (given by meaning); the exact strings are in `InfoCommand` and `InfoCategory` of the Android `InfotainmentControl.kt`, and the iOS `InfotainmentControl.swift` has the same strings (both unit tests pin them).

| Group | Button (meaning of the Korean UI label) | Call | If `Uncertain`, read with |
|---|---|---|---|
| Climate | Climate on | `climateOn()` | `getState(CLIMATE)` |
| Climate | Climate off | `climateOff()` | `getState(CLIMATE)` |
| Climate | Read climate | `getState(CLIMATE)` | – |
| Charging | Charge start | `chargeStart()` | `getState(CHARGE)` |
| Charging | Charge stop | `chargeStop()` | `getState(CHARGE)` |
| Charging | Read charge | `getState(CHARGE)` | – |

No confirmation dialog (nothing here is irreversible). The groups are titled Climate and Charging.

### Enabling

The six buttons use **exactly the step 4 rule**: key ready, connected with a key (`Vehicle.hasKey`), enrollment =
`enrolled`, and no library call of this tab running. There is no separate rule and no cached "session is ready" input:
the INFOTAINMENT session is started by the tap (below), so the user never has to start a session first. A tap whose
conditions no longer hold when it runs (for example after a confirmation or a connection change) sends nothing and the
result line says why (`KeyControlSteps.commandBlockedReason`, the same text as for the VCSEC buttons).

### Order of one tap (command)

Follows `15-lib-commands.md` §Call order and `14-lib-reading-state.md` A8.2 (the same order as SFR-023):

1. `vehicleStatus()` with the default timeout. Only `sleepStatus == AWAKE` goes on. `ASLEEP`, `UNKNOWN`, or a failed or
   `Uncertain` read ends the tap with **NotSent**: no session is started, nothing is sent, and **`wakeUp()` is never
   called** (waking is the user's decision: the **Wake up** button of the Doors group, then wait for `AWAKE`, then tap again).
   A successful read refreshes the status card.
2. `startSession(setOf(INFOTAINMENT))` with the default timeout. A domain already `Ready` succeeds without a request.
   `Failure` or `Uncertain` ends the tap with **NotSent**. `KeyNotPaired` also sets enrollment to `notEnrolled`;
   `KeyStoreError.Locked` says to unlock the phone.
3. **One** command call with the default timeout (`null`). Never a second one, whatever the result.
4. Nothing else: **no automatic `getState`**, no re-query chain. The user reads the state with the read buttons.

A read tap is the same with `getState(category)` as step 3.

### Results

| Result | Meaning | What the app shows |
|---|---|---|
| `Success` | The car accepted the command | "succeeded". The state is **not** read automatically: the new state is shown only after a read tap |
| `Uncertain(error)` | The car may have run it (no reply in time because Infotainment sleeps or the reply was lost, a failed write the library repeated, an unreadable reply) | "uncertain" plus the error type, and the sentence: the car may have run it, the app does **not** resend, press **Read climate** / **Read charge**. The category is marked **unconfirmed** (below) |
| `Failure(InfotainmentRejected)` | The car refused | "failed" plus the type, and "the car refused it". The car's own text (`reason`) is **not** shown or logged (`15-lib-commands.md` §Call order) |
| `Failure(NoSession)`, `Failure(NotConnected)`, other `Failure` | Call order or link | "failed" plus the error type (`16-lib-errors.md`). `KeyNotPaired` also sets enrollment to `notEnrolled` |
| NotSent | The app stopped before the command (asleep, sleep unknown, status read failed, session failed) | "not sent" plus the reason; for asleep, a pointer to the Wake up button |

For a read tap: `Success` shows the readout; `Uncertain` and `Failure` are shown as the library returned them
(`16-lib-errors.md` §`getState` results), the last readout stays with its age, and there is no automatic retry.

- **Never resend.** The app never calls a command a second time by itself, not after `Uncertain`, not after a read. The
  buttons stay enabled while a category is unconfirmed (the user decides), but the line says to read first.
- **Unconfirmed marker.** After an `Uncertain` command the section shows "climate result unconfirmed" /
  "charge result unconfirmed" with the matching read button next to it. The marker for a category is cleared when a
  `getState` of that category returns `Success`, when a later command of that category returns `Success`, or when the
  connection changes. An `Uncertain` read keeps it. Other failures of a command do not set or clear it.

### Readout

Values are shown on screen only (FR-112): the last successful read per category, its time and age, and the reply's
`timestamp`. Only these named fields are read, `—` for absent and `0` stays `0` (`14-lib-reading-state.md` A8.3):

| Category | Fields |
|---|---|
| Climate | `is_climate_on`, `inside_temp_celsius`, `driver_temp_setting`, `timestamp` |
| Charge | `battery_level`, `charge_limit_soc`, `charger_power`, `charge_port_door_open`, `timestamp` |

If the `timestamp` of a read equals the one of the previous read of that category, the readout says "timestamp did not
change: not necessarily a new measurement" (A8.3). Known gap: `15-lib-commands.md` §Re-query names `charge_state.charging_state`
as the field to look at after charge start/stop, but the manual does not say how a Kotlin or Swift app reads that oneof
wrapper, so the readout leaves it out and uses `charger_power` as the visible sign of charging until the manual
describes it (library request, #118).

### Connection change and late results

The readouts, the unconfirmed markers and the result of a tap belong to the **connection** (the `Vehicle` instance) that
produced them. When the connection changes (link loss, reconnect, key change) the readouts and markers are cleared. A
result that arrives **after** the connection changed is dropped: no readout, no marker, no enrollment or status-card
update. One result line records that, naming the command only: "result dropped because the connection changed; the car may
have run it, read the state after reconnecting".

### Log and result lines

One result line per finished tap and nothing else (no `observe` line, no file log): `<button label>: <result>`, then the
error type in parentheses (unless `Success`) and the duration, for example `<climate on label>: <uncertain word> (Timeout(afterSend=true)) 5012 ms`,
followed for `Uncertain` and NotSent by one fixed sentence. **No value, no field name, no vehicle text, no
`error.message`** goes into the line (FR-112, `14-lib-reading-state.md` A8.3 "Do not log a result"). Error types are:
`ResponseTooLarge` (`ProtocolFault(RESPONSE_MTU_EXCEEDED)`), `Timeout(afterSend=true|false)`, `NoSession`, otherwise the
class name of the error (for example `InfotainmentRejected`, `NotConnected`, `Locked`). The duration is the whole tap, from the
first library call.

### Concurrency

Every call runs through the tab's own `exclusive { }` (one at a time, all buttons off while busy). A second tap while
one runs does nothing and logs "busy". Infotainment calls are not serialized by the library, so this guard is the only one.

Android: `InfotainmentControl.kt` (pure: `InfoCommand`, `InfotainmentRunner`, `InfotainmentErrors`, `InfoUiState`
reducer, readout mapping), `Tests/InfotainmentControlTest.kt`; the state in `KeyControlState`, the screen in
`ui/KeyControlTab.kt`. iOS: `InfotainmentControl.swift` (pure, the same rules and sentences), `InfotainmentLink.swift` (the four library calls of one tap on one connection's `Vehicle`), `KeyControlModel.pressInfo` / `runInfo` (state, results, the connection generation that drops late results), the section in `Views/KeyControlTab.swift`; tests with a fake `KeyControlBackend` and a fake `InfotainmentLink` as for SFR-021. The iOS shows the climate and charge readout with a `TimelineView`, so the age counts up without touching the model.

## Concurrency

All library calls of this tab run through the tab's own `exclusive { }` (one at a time, buttons disabled while busy). On
Android they run in the view model's scope, so switching tabs or rotating the phone does not cancel them. The
Control tab has its own guard; the library serializes VCSEC commands per connection, so a press on both tabs at once
cannot interleave on the wire.

## Implementation

| | Android | iOS |
|---|---|---|
| Pure logic + tests | `KeyControl.kt` (`KeyControlSteps`, `KeyControlRunner`, `Enrollment`), `src/test/.../KeyControlTest.kt` | `KeyControl.swift`, `Tests/KeyControlTests.swift`; `KeyReplacement.swift`, `Tests/KeyReplacementTests.swift` (SFR-020); `EnrollmentProgress.swift`, `Tests/EnrollmentPopupTests.swift` (SFR-021) |
| State | `KeyControlState.kt` (made in `MainViewModel`) | `KeyControlModel.swift` (made in `MonitorModel`) |
| Infotainment (SFR-025) | `InfotainmentControl.kt`, `src/test/.../InfotainmentControlTest.kt` | `InfotainmentControl.swift`, `InfotainmentLink.swift`, `Tests/InfotainmentControlTests.swift`, `Tests/InfotainmentModelTests.swift` |
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
13. Corrupted key (only if you can reproduce it, for example an Android restore without its wrapping key; hard on iOS):
    step 1 says the key is damaged and the button is on; the dialog adds that the old key cannot be recovered;
    after confirming, a new key exists. A key error that is not `stored key is corrupted` leaves the button off.
14. Racing taps: start **Request enrollment** on the Control tab and press the replace button: the result says busy.
    Press replace and, while it runs, **Connect** on Live: no connection starts until the new key exists; afterwards the
    app reconnects with the new key (the Connect press was remembered), also if nothing was running before.
    Press **Disconnect** on Live while it runs: after the replacement the app stays disconnected.

SFR-021 (enrollment progress popup; iOS runbook in `samples/ios/README.md`):

15. Press **Request enrollment**: the sheet opens at once, then says the request was sent and to put the key card on the
    centre console and approve on the car's screen.
16. Tap the card and approve: the sheet moves to checking with attempt number, elapsed and remaining time; then "done" and
    **Close**; step 3 is done and the step 4 buttons work.
17. Do not approve for 2 minutes: "not confirmed in 2 minutes" with the advice; **Close**; **Check enrollment** works.
18. Press **Cancel** while waiting: the sheet says it is stopping, then cancelled with the note that the request cannot be
    recalled; no further `sessionInfo` requests (Log tab). Tap the card afterwards, then **Check enrollment**: it may show
    `enrolled`.
19. Turn Bluetooth off or walk away while the sheet is open: the sheet closes at once and a result line says the waiting
    was stopped.
20. While the flow runs, swipe the sheet down: it stays.

SFR-025 (Infotainment climate and charging; everything unconfirmed on a real car, #118; write what the car did, not values):

21. Awake car, enrolled key: the six Infotainment buttons are on; with Bluetooth off, or before step 3, they are off.
22. **Climate on**: result "succeeded"; **Read climate** shows `is_climate_on`; the car's own screen agrees. **Climate off**
    and read again.
23. **Charge start** / **Charge stop** with a cable connected: result, then **Read charge** (`charger_power`, `battery_level`);
    note what the car showed. Without a cable: the result type the car returns.
24. Let the car fall asleep, tap **Climate on**: "not sent, asleep", no wake, no session (Log tab shows no Infotainment
    traffic). Press **Wake up**, wait for the status card to say awake, tap again.
25. Force an `Uncertain` (for example walk to the edge of range right after the tap): the line says uncertain with the error
    type, the "unconfirmed" marker shows, no second command is sent, **Read climate** clears it when it succeeds.
26. Drop the link while a command runs: the late result is dropped (one "dropped" line), the readouts are cleared.
27. Copy the result lines: they contain command names, outcomes, durations and error types only.
