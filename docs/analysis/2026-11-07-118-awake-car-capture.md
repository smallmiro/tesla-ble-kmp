# #118 awake-car capture: own replies, cached-session resync, a misclassified timeout (2026-11-07)

Author: `protocol-analyst`. Skill: `analyze-monitor-logs`. Repository state: `main` at `70ec920`.
Plan: `docs/superpowers/plans/2026-11-01-118-state-diagnostics.md` Task 3. Policy: `docs/adr/0015-reply-authentication-policy.md`.
Builds on `docs/analysis/2026-11-05-118-sleeping-car-getstate.md` (uncommitted; "the 11-05 report" below).
This is an analysis report. Nothing here changes the library, the sample or the manual. `library-developer` decides what
enters `14-lib-reading-state.md`, `16-lib-errors.md`, `18-lib-troubleshooting.md`, ADR-0015, ADR-0010 and the PRD.

**Summary.** For the first time the car was awake and our own `getState` was answered: 39 taps (20 `DRIVE`, 11 `MEDIA`,
8 `MEDIA_DETAIL`), 39 `Success`, every data reply `encrypted(...)` with `auth=gcm-ok`, no plaintext data, no
`gcm-failed`, `gcm-replay` or `gcm-no-session`, no size fault (largest reply 436 B). Four signed VCSEC commands (three unlocks, the second
user-confirmed, and one lock inferred from the broadcasts; most likely from the Control tab; commands are not logged)
returned empty successes, all `encrypted(0B) auth=gcm-ok`. No `wakeUp()` was sent; the car woke after the unlocks.
The first request on a cached session drew a plaintext fault with fresh session info in both domains (Infotainment
`INVALID_SIGNATURE`, VCSEC `INCORRECT_EPOCH`), and the library resent with a new attempt after applying the session
info (applied: Likely, strong): both ended in `Success` about 1.1 to 1.3 s later. That path is the designed recovery of `protocol.md` but is not described in A8.2 or
S6, and a literal reading of ADR-0015's "untagged fault: no automatic resend" would turn it into `Uncertain` after 5 s.
One VCSEC read in the background ended `Failure(Timeout(afterSend = false))` ("before sending") after 9.65 s, and the
vehicle's reply to that very request arrived 9 ms later as `closed attempt=160`: the request had reached the car. The
same path on a command would report "not executed" for a command the car may have executed; Go cannot produce this
result because its BLE write ignores the deadline. Seven more VCSEC `WAIT`s, all on unsigned reads and all at the 16th
reply of a dense burst, again had no same-request final reply. T4's real question (a `WAIT` on a signed command) is still
**미관찰**. No mark was written and the firmware is still not recorded.

## Data

| Item | Value |
|---|---|
| Sessions analysed | `monitor-20261106-212142.jsonl` (file A, 1299 lines) and `monitor-20261107-102920.jsonl` (file B, 297 lines), both in `samples/logs/ios/2026-11-07/` (git-ignored) |
| Context only | `monitor-20261106-194508.jsonl` (build **23**, 17 lines, 19:45 to 21:20 on 11-06, three scan timeouts, no connection) |
| Phone | iPhone 16 Pro (`iPhone17,1`), iOS 27.2.0 |
| App | Monitor app `0.5.0(24)` in files A and B |
| Car | Assumed to be the Model Y of Part B B5 (same phone, same seven-key whitelist); firmware and model **not recorded**; VIN not quoted |
| Clock | Device clock, KST (+09:00) |
| Scenario | No `mark` line in any file. What a person did is inferred from the frames: a drive from about 09:12 to a stop at 09:23 (11-07), a walk-away, a return to a sleeping car at 10:18, a second drive, a stop at 10:34 and a walk-away. Who tapped while the car was in `DRIVE` is not in the log (the README asks for the passenger) |
| User-confirmed (after the first draft) | (1) Navigation was on from about 09:15 during the first drive. (2) At 10:18:56 the user pressed **unlock** again (attempt 22), not wake. Nothing is known about navigation during the second drive (10:19 onwards) |

Limits of the log, as in the 11-05 report: no `TX:` lines, so a request shows only through the `attempt=` of its reply;
values never reach the log (FR-112); Control and Key & Control commands write no `query` line, only the reply and the
broadcasts they cause.

## Inventory

| | C1 (file A) | C2 (file A) | C3 (file B) |
|---|---|---|---|
| Span | 09:12:47.182 to 09:23:45.772 (658.6 s) | 10:18:45.962 to 10:29:19.678 (633.7 s, file ends) | 10:29:21.038 to 10:35:09.932 (348.9 s) |
| Start | Backoff timer 4 s after the phone was unlocked (09:12:42, after an overnight wait), scan hit 96 ms, RSSI -42 dBm | Connect button, scan hit 24 ms, -71 dBm | App launch, scan hit 30 ms, -53 dBm |
| End | `bluetooth connection lost` | App process ended (no `disconnected` line); a new process started 1.24 s later | `bluetooth connection lost` |
| Car | `AWAKE` throughout; `DRIVE` until 09:22:42, `PARK` from 09:23:06 | `ASLEEP` 10:18:46 to 10:18:54, `AWAKE` from 10:18:58; `DRIVE` from 10:19:16 | `AWAKE`; `DRIVE` until 10:34:16, `PARK` from 10:34:22 |
| Own requests (`attempt` max) | 269 | 561 | 172 |
| Requests with a reply line | 269 | 561 | 171 (+1 late `closed`) |
| `getState` taps | 33 (all `Success`) | 6 (all `Success`) | 0 |
| `[reply:infotainment]` | 34: 33 `gcm-ok`, 1 fault | 6 `gcm-ok` | 0 |
| Signed VCSEC replies | 0 | 5: 1 fault, 4 `encrypted(0B) gcm-ok` | 0 |
| VCSEC `WAIT` | 1 | 4 | 2 |
| `vehicleStatus` / `keySummary` / `listKeys` | 72 / 72 / 72 Success over C1 and C2 | (with C1) | 16 / 16 / 14 Success, 1 `listKeys` `Failure` |
| Broadcast events | 64 over C1 and C2: 60 `Status`, 2 `AuthenticationRequest`, 2 `Unrecognized` (field 53, field 48) | (with C1) | 18: 17 `Status`, 1 `Unrecognized` (field 53) |
| Other-client frames | 83 B VCSEC `other`: 16 over C1 and C2; **no Infotainment `other` frame** | (with C1) | 83 B VCSEC `other`: 3 |

Before C1: scan timeouts from 21:21 (11-06) to 09:12 (11-07); the backoff timers fired hours late (app suspended).
Between C1 and C2: no advertisement from 09:23:47 to 10:18:42 (one 55-minute timer, suspension again).
After C3: one weak scan hit at 10:35:55.727 (`after=5559ms rssi=-98`), `connect failed: vehicle found but the connection
did not complete before the deadline` at 10:36:30, then no advertisement.

`pbwire.py` was not needed: there are no raw reply bytes in these logs, and the broadcast frames are already decoded in
the observe lines.

### Timeline of the key moments

| Time | File:line | What |
|---|---|---|
| 09:12:47.264 | A:23 | First `vehicleStatus`: `INTERNAL_LOCKED`, `AWAKE`, `PRESENT`, `GEAR_DRIVE` |
| 09:12:48.856 | A:41 | VCSEC `WAIT` on attempt 16 (16th reply of the connect burst) |
| 09:12:56.906 | A:48 | First `DRIVE` tap starts (`startedAt`) |
| 09:12:57.229 | A:46 | `[reply:infotainment] 183B … status=OPERATIONSTATUS_ERROR/MESSAGEFAULT_ERROR_INVALID_SIGNATURE session_info=94B payload=none attempt=21 auth=none` |
| 09:12:58.460 | A:47-48 | `[reply:infotainment] 385B … payload=encrypted(282B) attempt=22 auth=gcm-ok`; `getState(DRIVE) Success durationMs=1563` |
| 09:13:08.8 to 09:13:15.8 | A:57-62 | Three taps with no `vehicleStatus` before them (attempts 27, 28, 29): the experiment button (F6) |
| 09:23:06 to 09:23:18.355 | A:412-437 | `PARK` and `UNLOCKED`, driver door open and closed, `NOT_PRESENT`, taps `DRIVE` `MEDIA` `MEDIA_DETAIL` at 09:23:15.8 to 18.6, walk-away `LOCKED` |
| 09:23:45.772 | A:462 | C1 lost, 27.4 s after the walk-away lock |
| 10:18:46.059 | A:474-475 | C2 first `vehicleStatus`: `LOCKED`, `ASLEEP`, `GEAR_UNKNOWN` |
| 10:18:52.751 | A:496 | `[reply:vcsec] 181B … ERROR/MESSAGEFAULT_ERROR_INCORRECT_EPOCH session_info=92B payload=none attempt=20 auth=none` |
| 10:18:53.833 to 10:18:54.008 | A:497-505 | attempt 21 `encrypted(0B) auth=gcm-ok`, then four broadcasts `UNLOCKED` and still `ASLEEP` (closures `UNKNOWN` in the first) |
| 10:18:56.111 / 58.419 / 10:19:00.311 | A:506, 510, 518 | attempts 22, 23, 24 `encrypted(0B) auth=gcm-ok`; 22 is the second unlock (user-confirmed) and draws no broadcast (already unlocked); 23 is followed by `LOCKED` (and the first `AWAKE`) in 27 ms, 24 by `UNLOCKED` in 27 ms |
| 10:20:55.244 | A:597-599 | First C2 tap: `vehicleStatus` (65), `getState` (66) `426B encrypted(323B) gcm-ok`, no fault |
| 10:29:19.678 / 10:29:20.921 | A:1299, B:2 | File A ends with `background`; file B starts with `start reason=launch` |
| 10:30:20.872 | B:117 | App to background (stays there until 10:35:55) |
| 10:34:31.256 to 10:34:40.918 | B:231-235 | `listKeys`: attempt 159 answered at 31.336, then `Failure` "timed out before sending command" at 40.909 and `[drop:no-handler] 108B … to=address(16B, closed) … whitelistEntryInfo{slot=0 …} attempt=160` at 40.918 (F4) |
| 10:34:58.264 / 10:35:09.932 | B:266, 273 | Walk-away `LOCKED`; C3 lost 11.7 s later |

## Findings

### F1. Our own replies on an awake car: every data reply verified, no plaintext — Confirmed (bounded observation)

**Claim.** On this car (firmware unrecorded), every reply that delivered data or success to one of our authenticated
requests carried `AES_GCM_Response_data` that verified and passed the replay check.

| Domain | Reply | Count | `auth=` |
|---|---|---:|---|
| Infotainment | `getState` data, `request_uuid=16B`, `payload=encrypted(NB)` | 39 | `gcm-ok` 39 |
| Infotainment | Fault `ERROR/INVALID_SIGNATURE` with `session_info=94B`, `payload=none` (F2) | 1 | `none` |
| Infotainment | Handshake (`session_info` without a fault), `WAIT` | 0 | – |
| VCSEC | Signed command, empty success `encrypted(0B)` | 4 | `gcm-ok` 4 |
| VCSEC | Fault `ERROR/INCORRECT_EPOCH` with `session_info=92B`, `payload=none` (F2) | 1 | `none` |
| VCSEC | `WAIT`, 47 B, `request_uuid=16B`, on unsigned reads (F5) | 7 | `none` |
| VCSEC | Unsigned read replies (`vehicleStatus`, `whitelistInfo`, `whitelistEntryInfo`) | 949 | `none` |
| Either | `gcm-failed`, `gcm-replay`, `gcm-no-session` | 0 | – |

- Reply sizes (frame / ciphertext): `DRIVE` 359 to 436 B / 256 to 333 B (20); `MEDIA` 151 to 192 B / 49 to 90 B (11);
  `MEDIA_DETAIL` 145 to 187 B / 43 to 85 B (8). The largest is below the 453 B previously observed maximum in another
  client's traffic, an observed value and not a known limit (`16-lib-errors.md` §Response size limit). No
  `RESPONSE_MTU_EXCEEDED`, including 6 `DRIVE` replies with navigation active (434 to 436 B, user-confirmed route, see
  the T2 row).
- Latency from the tap's `vehicleStatus` reply (or `startedAt` for the experiment taps) to the `getState` reply: `DRIVE`
  212 to 305 ms (median 273), excluding the resync tap (1444 ms); `MEDIA` 151 to 300 ms; `MEDIA_DETAIL` 152 to 239 ms.
- One registered Infotainment request per tap, except the first tap of C1 (two, F2). No handshake on either connection.
- Every routing address was ours (`open-request`); no `self`.

**Status reasoning.** T1 and T2 are the controlled action (a person taps; the library sends), repeated 39 times over two
connections and two drives. The claim is limited to what was measured: three categories, one car, unrecorded firmware,
replies to our own requests. It is the evidence ADR-0015 waited for: under the FR-105 rule ("success and data need a
verified `AES_GCM_Response_data`") none of the 43 legitimate data or success replies would have been rejected. The two
faults are the only untagged replies to signed requests, and F2 shows why they matter.

**Not checked.** Other categories (`CHARGE`, `CLIMATE`, …), firmware before 2024.38, another car. `auth=gcm-ok` does not
cover the outer `operation_status` (ADR-0015).

### F2. A cached session the car no longer accepts is recovered by fault plus session info, then a resend — observed once per domain; Likely

**Claim.** When the first authenticated request on a restored session is not accepted, this car answers promptly with a
plaintext fault (`OPERATIONSTATUS_ERROR`) and fresh `session_info`. The library applies the session info and
resends with a new attempt, and the resend succeeds. The caller sees `Success`, about 1.1 to 1.3 s late.

**Evidence.**
- Infotainment, C1. The app process started at 21:21:42 on 11-06 and C1 is its first connection, so the Infotainment
  session came from the cache (`DefaultVehicle` restores at connect). The tap's `startSession` sent nothing (no
  handshake line). Attempt 21, the `getState` itself, drew `INVALID_SIGNATURE` with `session_info=94B`, logged 213 ms
  after the tap's `vehicleStatus` reply (A:45 → A:46; the send time is not logged, so this is not a send-to-fault
  latency). Attempt 22 came back `encrypted(282B) auth=gcm-ok` 1231 ms later (A:47), which includes the 1 s
  `retryInterval`. `durationMs=1563` (A:48). `INVALID_SIGNATURE` does not say which part of the session was out of
  step (epoch, counter or clock), so this is called a synchronization recovery here; only the VCSEC fault names the
  epoch.
- VCSEC, C2. No VCSEC handshake line appears; attempt 20, a command, drew `INCORRECT_EPOCH` with `session_info=92B`
  (A:496) while the car was `ASLEEP`. Attempts 20 to 24 are consecutive with no `vehicleStatus` between them; the Key &
  Control tab re-reads `vehicleStatus` after every command (`KeyControl.swift:404`, `SampleModel.swift:214-217`) and the
  Control tab does not, so these were most likely Control-tab buttons. Attempt 21, 1082 ms later, was `encrypted(0B) auth=gcm-ok`
  (A:497), and the next broadcast said `UNLOCKED` (A:498-499). The command was therefore an unlock, executed once.
  The user's next press, a second unlock at 10:18:56 (attempt 22, user-confirmed), went through on the refreshed
  session with no further fault (`gcm-ok`, A:506), as did the lock and unlock after it (attempts 23, 24). A repeated
  unlock on an unlocked car returned an empty `Success` and drew no broadcast: one data point for the repeat-safe
  discussion below.
- That the session info was applied is Likely (strong), not logged: the resend, and in VCSEC the three later commands,
  were signed under a session that the car then accepted (`gcm-ok`), and a discarded session info (missing tag, too late, `Dispatcher.kt:740-777`) would have
  left the old session in place. A failed HMAC on an unconfirmed restore would have invalidated the session
  (`Dispatcher.kt:786-797`) and the resend would have been refused as `NoSession`. The "Updated session info" line is
  `INFO`, which the monitor does not keep; `auth=none` on the fault line says nothing about the session-info HMAC
  (`18-lib-troubleshooting.md` §Reply lines).
- This is the recovery that `vehicle-command/pkg/protocol/protocol.md` §Recovering from synchronization errors describes
  ("the vehicle may include up-to-date session state in an error message … if the infotainment system reboots").
  `16-lib-errors.md` §Retries already says "Session info attached to the error response is applied first".
- Counter-case in the same capture: C2's first tap (A:597-599, attempt 66) used the session stored after C1 and was
  accepted with no fault, although the car had been `ASLEEP` at 10:18:46. So a sleep period alone did not invalidate the
  Infotainment session here; what made the C1 cache unacceptable is unknown.

**Where the manual is incomplete.** `14-lib-reading-state.md` A8.2 and `18-lib-troubleshooting.md` (rows "`lock()` →
`NoSession`", "`getState()` → `NoSession`") describe a cached session the car no longer accepts only as
`Failure(NoSession)`. In the code that outcome needs a session-info HMAC failure (vehicle key changed); a stale epoch or
counter with the same key takes the path above. README S6 lists only `Success` or `Failure` plus "Infotainment 세션이
없어…". The third outcome should be written down: `Success` after one extra request and about 1.1 to 1.3 s, visible as
an `auth=none` fault line with `session_info=` followed by a `gcm-ok` reply with the next attempt number.

**Implication for ADR-0015 (decision for `library-developer` and the user).** ADR-0015 keeps an untagged fault as
unverified diagnostics and "by default" does not resend it, and it applies session info separately without
authenticating the fault. Implemented literally, both recoveries here (one `getState`, one `unlock`) would have waited
the full 5 s and ended `Uncertain` (carrying the unverified fault, ADR-0015 §결정), and the next call would have
succeeded.

A narrow exception "fault in {`INVALID_SIGNATURE`, `INCORRECT_EPOCH`, …} plus session info whose HMAC verifies against
this request's UUID" is **not** safe for every command. The session-info HMAC covers the VIN, the challenge (request
UUID) and the session info (`Session.kt:45`), not the fault, the request's contents or whether it already ran. The Go
reference verifier answers a corrupted copy of a request with `INVALID_SIGNATURE` plus freshly signed session info for the
same UUID, and a replayed one with `INVALID_TOKEN_OR_COUNTER` plus signed session info
(`internal/authentication/verifier.go:52-60`, `:217-220`, `:237-240`). So a relay can let the original command run, drop
its success, send a damaged copy with the same UUID, and obtain a genuinely signed fault that makes the client re-sign
and send again: a second execution, without knowing any key (Codex's counterexample). The same path exists today, as in
Go.

Options for the FR-105 plan: (a) apply verified session info always, but reissue automatically only for operations
audited as safe to repeat, never for toggles or motion (`openTrunk`, `actuateTrunk`, `closeTrunk`, `openFrunk`,
`remoteDrive`, `autoSecureVehicle`, tonneau). Candidates: an enumerated list of Infotainment reads (`getState`
categories), `wakeUp` (a repeat only wakes again), and `lock` / `unlock` only if their target-state meaning is accepted:
a resend can undo an opposite action someone else took in between, so retries stay within the original deadline and stop
when a newer local command supersedes them. Unsigned VCSEC reads are not signed and need no session recovery. (b) Keep
the ADR rule for all and accept one `Uncertain` per stale cache. Either choice should cite these two observations.

**Status reasoning.** Observed once in each domain, in an uncontrolled run; the mechanism matches the code and
`protocol.md`. Likely. A controlled repeat is cheap (N1 step 2).

### F3. The 11-05 silence: the stale-session alternative is weakened — Likely (unchanged)

The 11-05 report (F1) kept "sleeping Infotainment explains the silence" at Likely because a cached session the car no
longer accepts was not excluded. Now:

- On this car an awake Infotainment answered a not-accepted cached session promptly with a fault and session info (F2),
  not with silence. That is one case; it does not show that every out-of-step session draws a fault.
- No log on hand shows an Infotainment handshake or session update between the 11-05 evening connection and C1. The
  cache is rewritten after a session update and at disconnect (`VehicleSession.handleEvent`, `storeOnceForDisconnect`),
  and the 11-05 connections had no update, so C1 probably used the same session as the six sleeping-car taps of 11-05.
  Not provable: session contents are not logged, and logs that were not pulled may exist.
- If that cache was already unacceptable on 11-05, an awake car would have faulted, and the asleep car said nothing for
  5 s, six times. If it was still acceptable, an awake car would have answered with data. Either way silence needed
  something else, and sleep is the one condition the 11-05 runs had (all nine `vehicleStatus` reads `ASLEEP`).

Remaining alternatives: a lost frame before the dispatcher, a cache that changed between 11-05 and 11-07 through an
unlogged connection, a kind of session mismatch that an awake car answers with silence (not seen). Wording agreed with
Codex: the awake observation weakens a generic stale-session explanation; sleep remains the leading explanation for the
six silent requests; the same-session awake control (11-05 report N1 step 6) is still missing. Status stays **Likely**.
`library-developer` may cite it in A8.5 as "Likely".

### F4. `Failure(Timeout(afterSend = false))` on a request the vehicle received and answered — Likely (strong); a deviation from Go

**What happened (file B, app in the background since 10:30:20.872, B:117).**

| Time | Line | Event |
|---|---|---|
| 10:34:30.689 | B:227-228 | Phone unlocked (`protectedData available`); the app stays in the background |
| 10:34:30.713 → 31.148 | B:229-230 | Poll `vehicleStatus`, 435 ms (file A: median 98 ms, 90 % under 125 ms) |
| 10:34:31.256 | B:232-234 | `listKeys` starts; its `keySummary` (attempt 159) is answered at 31.336 |
| 10:34:40.909 | B:234 | `listKeys` `Failure`, summary "timed out before sending command", 9.65 s after the start |
| 10:34:40.918 | B:235 | `[drop:no-handler] 108B … to=address(16B, closed) request_uuid=empty payload=FromVCSECMessage{whitelistEntryInfo{slot=0, …}} attempt=160` |

- "timed out before sending command" is `VehicleError.Timeout(afterSend = false)` (`VehicleError.kt:259`), a `Failure`
  with `mayHaveSucceeded = false`.
- Attempt 160 is the slot 0 request of this `listKeys`. It was registered (it has an attempt number and a closed-history
  entry) and its reply came back, so the vehicle received it.
- In the code `afterSend` becomes true only after `dispatcher.send` returns, that is after `transport.send` completed
  (`VcsecCommands.kt:75-76`, `withAttemptTimeout` in `Results.kt:64-71`). `Dispatcher.transmit` closes a request whose
  send did not return (`Dispatcher.kt:538-560`), which is why the late reply read `closed`. `KableGattLink.write` uses
  write with response (`KableGattLink.kt:55-57`), and `BleTransport.writeAll` is cancellable; it closes the transport only
  when a multi-chunk frame is cut (`BleTransport.kt:109-125`). One 108 B reply means the request fitted one chunk, and
  the link stayed up (the next poll at 10:34:55.8 was answered).
- The 5 s budget fired at 9.65 s. Timers in a suspended iOS app fire late (build 18 report F3), so the app was most
  likely suspended between 10:34:31.3 and 10:34:40.9 and woke when the reply arrived.
- Two readings fit and lead to the same result: the write completion had not been delivered when the overdue deadline
  ran, or it had been delivered but its continuation lost the race with the deadline on resume. In both, bytes handed to
  CoreBluetooth reached the car, and the library said "not sent".

**Go.** `pkg/connector/ble/ble.go:108` is `Send(_ context.Context, …)`: the context is ignored, so a write in progress
cannot be cut by the deadline. After the send, the VCSEC reply wait returns `PossibleSuccess: true` on `ctx.Done()`
(`pkg/vehicle/vcsec.go:78`), as does `Vehicle.trySend` for Infotainment (`pkg/vehicle/vehicle.go:211-212`). Go's "not
sent" outcome comes only from the deadline before the send or during the retry wait (`vehicle.go:250-252`). The port's
cancellable write lets the deadline land inside the write and report it as "before sending". ADR-0010 and SDD D29 define
the split as "before sending / after sending" and do not say where a write in progress belongs; the observed case shows
it belongs to "after". The same split is in `SendWithRetry` (`SendWithRetry.kt:76`), so both domains are affected. This
is a port deviation under AGENTS rule 5.

**Why it matters.** `16-lib-errors.md` defines `Failure` as "not executed" and `Timeout` as `temporary`. On a VCSEC
command the same path would tell the app the command did not run when the car may have run it; an app that then sends
`openTrunk` or `actuateTrunk` again toggles twice. On a query the harm is a lost result only.

**Status.** Likely (strong): one observed misclassified outcome, supported by the code; controlled reproduction pending
(N4). It is not labelled Confirmed because the project rule needs a controlled scenario reproduced more than once. The code admits no other
way for a registered, delivered request to end `afterSend = false` (the pre-send check in `VehicleInfoQueries.kt:72`
returns before registering). The exact mechanism (an outstanding OS write, a delayed completion callback, or delayed
processing on resume) is Unknown; the background state and the 9.65 s are observed, the suspension is inferred. That it
would happen to a command is a code reading, not an observation. Hardening H1.

### F5. VCSEC `WAIT` on dense bursts: 7 more, all at the 16th reply; still no same-request final reply — Likely (11-05 report F5 reinforced)

| Connection | Burst start | Burst length | First 15 replies took | `WAIT` at position (attempt) | Resend answered after | In history until about |
|---|---|---:|---:|---|---:|---|
| C1 | 09:12:47.264 (connect poll + Key & Control `listKeys`) | 19 | 1.50 s | 16 (16) | 1.08 s | 09:13:59.6 |
| C2 | 10:18:46.059 (same) | 19 | 1.38 s | 16 (16) | 1.08 s | 10:20:23.4 |
| C2 | 10:21:05.603 (polls back to back) | 31 | 1.62 s | 16 (86) | 1.08 s | 10:21:25.5 |
| C2 | 10:21:24.982 (same) | 21 | 1.56 s | 16 (129) | 1.08 s | 10:22:03.0 |
| C2 | 10:25:44.841 (same) | 21 | 1.47 s | 16 (376) | 1.08 s | 10:26:22.1 |
| C3 | 10:29:21.143 (connect burst) | 27 | 1.50 s | 16 (16) | 1.11 s | 10:29:51.3 |
| C3 | 10:30:02.801 (polls back to back) | 19 | 1.32 s | 16 (73) | 1.08 s | 10:30:41.4 |

- Bursts are own replies split at gaps over 1.5 s, as in the 11-05 report (Codex reproduced the same 7 of 8 with gaps
  over 1 s). 7 of the 8 bursts that reached 16 own replies had a `WAIT`, always the 16th. The one without (10:20:54.293,
  16 replies) took 3.36 s for its first 15, because a `getState` tap sat inside it. The 112 shorter bursts had none.
  Together with the 11-05 report: 18 own `WAIT`s, 16 of them at position 16. This is a correlation with dense bursts,
  not an established rate limit.
- Back-to-back poll cycles most likely come from presses of the Query tab's "지금 조회" button (`QueryTab.swift:25`; not
  logged); the connect bursts from the poll plus the Key & Control tab's automatic `listKeys` (11-05 report H4).
- **No late final reply.** None of the 7 attempts reappeared as `closed` while they stayed in the 32-entry history (about 18 to
  96 s). The detector works on real traffic: F4's `closed attempt=160` is the first real-car `closed` line. For
  unsigned VCSEC reads the count is now 0 of 11.
- No signed request met a `WAIT` (5 VCSEC, 40 Infotainment). The ADR-0015 question stays open for signed commands.

### F6. Three taps were the experiment button on an awake car — Likely

A:57-62: taps at 09:13:08.840, 09:13:12.522 and 09:13:15.560 (`DRIVE`, `MEDIA`, `MEDIA_DETAIL`) registered attempts 27,
28 and 29 back to back, with no `vehicleStatus` before them. Every other tap has one. Only the experiment path skips the
sleep read (`StateDiagnostics.swift:447`), and the experiment button is enabled while awake (`experimentEnabled`,
`StateDiagnostics.swift:254-256`). All three ended `Success` in 186 to 241 ms. They do not count for S10 or T5 (the car was
awake). The log line does not say which button was used (H4).

### F7. Sleep and wake around C2 — observations, Unknown as rules

- The ten `ASLEEP` lines of file A are five frames, each logged twice (observe line plus `query` or `event`): the C2
  connect read (A:474-475) and four broadcasts right after the unlock (A:498-505). One sleep episode, seen only at its
  end. The car fell asleep somewhere between 09:23:45 and 10:18:46, unobserved.
- **No `wakeUp()` in C2.** Attempt 22 was a second unlock (user-confirmed), 23 and 24 were followed within 27 ms by
  `LOCKED` and `UNLOCKED`. So all five signed requests of C2 (20 to 24) were lock-state commands, and the car woke
  without the wake command.
- `ASLEEP` to `AWAKE` took at most 4.4 s after the last `ASLEEP` frame (10:18:54.008 → 10:18:58.446) and at most 4.6 s
  after our first unlock was confirmed (10:18:53.833). The first `AWAKE` value rides on the `LOCKED` broadcast of attempt
  23; the second unlock (10:18:56.111) changed no state and drew no broadcast, so the transition may have happened
  earlier inside that window. Other stimuli in the window: an `AuthenticationRequest` (`ENTERED_HIGHER_AUTH_ZONE`) at
  10:18:56.829 and another client's signed reply at 10:18:56.918 (a phone key nearby, Likely). Reading: an unlock of a
  sleeping car, with a phone key approaching, woke it within about 4.6 s (one observation, Unknown as a rule). It is not a
  `wakeUp()` timing, so the A8.5 item "how long a wake takes" (after `wakeUp()`) stays open.
- A sleeping car answered VCSEC normally: the `ASLEEP` read, the 19-reply burst with one `WAIT`, the `INCORRECT_EPOCH`
  fault and the unlock.
- First broadcasts after the unlock had `UNKNOWN` for the doors and trunks and `frontTrunk` stayed `UNKNOWN` one frame
  longer, as B3.1 describes for a waking car.
- The Infotainment session from C1 survived this sleep (F2 counter-case).

### F8. Link losses and the restart — Likely

- C1 and C3 ended 27.4 s and 11.7 s after the walk-away lock, with `NOT_PRESENT` before it, the phone locked or the app in
  the background. The same shape as the build 18 report F4 and B3.1 ("link lost about 30 s later"). After C3 the only scan
  hit was -98 dBm and the open did not complete. Walking away is the simplest reading; distance is consistent here because
  the person left the car (unlike the memory note about driving with the other app).
- C2 ended because the app process ended: no `disconnected` line, `background` at 10:29:19.678, a new process with
  `start reason=launch` at 10:29:20.921, connected 117 ms later. A user force-quit and relaunch is the likely reading (a
  crash followed by a manual launch within 1.2 s is unlikely). It could have been the first half of S6, but no tap followed
  in file B.
- No request was lost on C1 or C2 (830 of 830 answered), and no other client's Infotainment traffic appeared: a
  "without traffic" data point for `16-lib-errors.md` §Queries while another BLE client is active.

### F9 (side, for Part B). Small additions — Likely

- B3.1 lock state around driving: `INTERNAL_LOCKED` → `UNLOCKED` in the same frame as `PARK` twice more (A:412, B:222;
  now 5 of 5). `UNLOCKED` → `INTERNAL_LOCKED` came 6.4 s after a shift to `DRIVE` (A:536 → A:551), outside B3.1's 16.9 to
  130.4 s range. Walk-away `LOCKED` 5.3 s and 10.1 s after `NOT_PRESENT`.
- B3.3 and B3.4: both field 53 frames (220 B, A:421, B:243) and the one field 48 frame (A:553) were followed by an 83 B
  signed VCSEC reply to another client within 0.65, 0.78 and 0.69 s (with the 11-05 report's F6: 16 of 18 for field 53). Field 48
  had not been tied to such a reply before.

## Scenario rows

| Row | Status | What was seen (no marks; inferred) |
|---|---|---|
| T1 parked, awake | **Partly** | Parked taps: 1 `DRIVE`, 1 `MEDIA`, 1 `MEDIA_DETAIL` at 09:23:15.8 to 18.6, person already out (`NOT_PRESENT`), all `gcm-ok`, 359 / 151 / 145 B. Not 5 each with a person inside. The `auth=` and size questions of T1 are answered by all 39 taps (F1) |
| T2 driving | **Partly** | 36 taps while the latest reported gear was `DRIVE` (19 of them `DRIVE` category; gear does not prove motion), 0 failures. With navigation on from about 09:15 (user-confirmed): 10 `DRIVE` taps before 09:15:32 at 383 to 394 B, 6 taps from 09:15:32 to 09:20:29 at 434 to 436 B, all `Success`, no size fault; the step of about 40 B lines up with the route, so the route fields most likely account for it (Likely; values are not logged, and the exact start of the route versus the 394 B taps at 09:14:56 to 09:15:02 is not known). Parked at 09:23:16: 359 B (route state unknown). Second drive (10:20 to 10:23): 3 taps, 414 to 426 B, navigation unknown. Speed unit still needs screen notes; the route split needs marks next time |
| T3 long title | **Not identifiable** | 11 `MEDIA` (151 to 192 B), 8 `MEDIA_DETAIL`; title length unknown; no size fault. Navigation does not bear on T3 (the title is in `MediaState`, the route in `DriveState`); `MEDIA` sizes changed at 09:14:23 (167 → 184 B), before the route started |
| T4 lock/unlock, `WAIT` | **Partly** | 4 signed empty successes `gcm-ok` (3 unlocks, the second user-confirmed and an unlock of an already unlocked car; 1 lock inferred from its broadcast) and 1 `INCORRECT_EPOCH` fault before them; not 5 pairs; **no `WAIT` on a signed command: 미관찰** |
| T5 / S10 asleep | Not run | No tap while `ASLEEP`. The experiment button was used on an awake car (F6) |
| S1 buttons off | Not observable | UI only; the car was `ASLEEP` for 8 s at C2 start with no tap |
| S2 Drive parked | Observed once | 09:23:15.8, `Success`, values on screen only |
| S3 Media | Observed | 11 `Success`; playing or paused not recorded |
| S4 MediaDetail | Observed | 8 `Success` |
| S5 two `Uncertain` kinds | Not seen | 0 `Uncertain` on `getState` |
| S6 cached session | **Variant observed** | First tap after a cached restore: neither plain `Success` nor `Failure(NoSession)`, but `Success` after a fault and resync (F2). The force-quit and relaunch at 10:29:20 had no tap after it |
| S7, S8, S9 | Not observable / not run | A refused or second tap leaves no line; no tap overlapped a link change or another tab's command |
| S11 values not in log | **Observed for `Success`** | All 39 `getState` lines have exactly `ts`, `kind`, `call`, `result`, `summary` (`durationMs=N` only), `startedAt`; no `getState` field name (`speed`, `shift_state`, `now_playing…`, `audio_volume`, `odometer…`) appears anywhere in either file. README S11's wording "속도·기어·곡 제목·위치가 어느 줄에도 없다" is too broad: the VCSEC `gear=` of `vehicleStatus` and broadcasts is logged by design (for example A:24) |
| S12 `timestamp` | Not observable | Values are on screen only |

ADR-0015, FR-104, FR-105 and A8.5 items:

| Item | Status |
|---|---|
| `auth=` of our own Infotainment reply | **Observed: 39 of 39 data replies `gcm-ok`**, 0 plaintext (F1) |
| Empty success of a signed VCSEC command | **Observed: 4 of 4 `gcm-ok`** (F1) |
| Untagged fault on a signed request | Observed 2 (one per domain), both with session info, both followed by an automatic resend that succeeded (F2) |
| Reply size per category; `RESPONSE_MTU_EXCEEDED` on our request | Sizes observed (F1); fault **미관찰** |
| Cached session the car no longer accepts | Observed, as resync (F2), not as `Failure(NoSession)`; `Failure(NoSession)` **미관찰** |
| Infotainment handshake on a sleeping car | **미관찰** |
| `WAIT` then a same-attempt final reply, signed command (T4) | **미관찰** (no `WAIT` on a signed command). Unsigned reads: 0 of 11 had one (F5) |
| `getState` wakes the car | Not tested this time |
| How long a wake takes | After `wakeUp()`: **미관찰**. Without it: one unlock-triggered wake (phone key nearby), at most 4.6 s after the first unlock (F7, attempt 22 user-confirmed as unlock) |
| FR-104 "do not infer asleep from silence" | Consistent: 40 of 40 Infotainment requests to an awake car were answered (one with a fault); on 11-05, 6 of 6 to an asleep car were not. Silence still also comes from the link (F4: 9.65 s with no completion in the background). No change suggested |
| `speed` unit, field coverage, `timestamp` meaning | **미관찰** (values are not logged; no screen notes) |

**Plan Task 3 Step 4 (can FR-105 start?).** The collection ADR-0015 waited for has happened for the success and data
rule: our `getState` replies and our empty successes arrive verified. The rule can be planned. Two items must be settled
in that plan: the untagged-fault-with-session-info path (F2, H2), which the current ADR wording would slow down, and the
`WAIT` on a signed command, still unobserved (N3, 11-05 report H6).

## Library hardening

| # | Item | Evidence | Proposal | Owner, priority |
|---|---|---|---|---|
| H1 | A deadline inside the BLE write reports "before sending" | F4: `Failure(Timeout(afterSend = false))` and the vehicle's reply to the same request 9 ms later | Mark a request as possibly sent just before the first `link.write` of its frame is invoked (single-chunk frames included; not merely on entering `transport.send`, which also covers the send-mutex wait). A deadline after that is `Uncertain(Timeout(afterSend = true))`, as in Go (`ble.go:108`, `vcsec.go:78`, `vehicle.go:211-212`). A transport retry does not clear the mark. `afterSend = false` stays only when no write of the request was ever invoked (lock wait, mutex wait, pre-send check). Both `VcsecCommands` and `SendWithRetry`. Amend ADR-0010 and SDD D29 ("sent" = write invoked, not write confirmed). Test with a fake link whose write suspends past the deadline and whose vehicle then replies | `library-developer`, **High** (commands: toggle risk) |
| H2 | ADR-0015 versus the stale-cache recovery | F2: 2 of 2 recoveries were untagged faults with session info, then a resend | Decide in the FR-105 plan: resend after a fault with verified session info only for requests audited as safe to repeat, or keep "no resend" and accept one `Uncertain` per stale cache. Record the two observations in ADR-0015 | `library-developer` with the user, **High** (blocks FR-105 design) |
| H3 | The manual describes a not-accepted cache only as `Failure(NoSession)` | F2 | `14-lib-reading-state.md` A8.2 and `18-lib-troubleshooting.md`: add "session out of step (epoch, counter or clock) with the same key: the library applies the session info from the fault and resends; `Success` about 1.1 to 1.3 s later". `Failure(NoSession)` stays for a session-info HMAC failure (vehicle key changed). README S6 expected results likewise | `library-developer` (MD), `ios-developer` (README), Medium |
| H4 | The sample log cannot say which button or command ran | F6 (experiment taps inferred), F2 and F7 (commands inferred from broadcasts) | Write one line per Control and Key & Control command (`call`, `result`, error type, `durationMs`, `startedAt`, no values), and add the mode (`normal` or `experiment`) to the `getState` line. 11-05 report H2 (session origin) and H3 (sleep read on experiment taps) still apply | `ios-developer`, Medium |
| H5 | Send-side observe lines (11-05 report H1) | F4 needed inference to tell "write invoked" from "write completed" | Unchanged proposal, now with a concrete case: a `write-done` line would have shown when attempt 160's write completed | `library-developer`, Medium |
| H6 | Dense bursts draw a `WAIT` (11-05 report H4) | F5: 7 of 7 bursts of 16 or more | Unchanged; also rate-limit the "지금 조회" button while a cycle runs | `ios-developer`, Low |
| H7 | Debug no-resend-on-`WAIT` collection mode (11-05 report H6) | F5: still no signed-command `WAIT` | Unchanged; needed for N3 | `library-developer` (decision), Medium |

No library crash or protocol violation was seen. Apart from F4, the library behaved as documented: verified replies were
delivered, the session-info fault path recovered, `WAIT` was resent after 1 s, and `getState` was never resent after a
result.

## Next scenarios

**N1. Repeat the awake run with marks (the gap is bookkeeping, not data).**
1. First mark: firmware and model from the car screen, app build, other apps state. Then one mark before each block (`T1
   start`, `T4 start`, …) and one after it. Without marks T1 to T4 cannot be told apart from ordinary use.
2. **Stale-cache repeat (F2, H2).** Before driving off, force-quit the app, relaunch, connect, and make one normal
   `DRIVE` tap; then one `lock` and one `unlock` from Key & Control. Mark each. Expect either a plain `gcm-ok` reply or the
   fault-plus-session-info path. Repeat on another day after the car has slept overnight. This also completes S6.
3. T1 parked with a person inside, phone unlocked, app in the foreground: `DRIVE`, `MEDIA`, `MEDIA_DETAIL` 5 times each with
   the normal buttons, 2 to 3 s apart.
4. T2 (passenger operates): 10 `DRIVE` taps without a route and 10 with one, with a mark per block and the cluster speed
   written next to each tap in the notes (marks hold at most 80 characters).
5. T3: a long title playing; note the source and rough length in a mark; `MEDIA`, `MEDIA_DETAIL` 5 times each.
6. T4: parked, 5 alternating lock/unlock pairs from Key & Control, at least 5 s apart, then 10 s of watching. Avoid "지금
   조회" during T4 so that fewer than 32 requests follow a `WAIT`.
7. Before leaving: two more polls (about 70 s), then pull the logs.

**N2. T5 with the same-connection control (11-05 report N1 step 6, N2).** Still open: an `ASLEEP` car, one experiment
tap, then a physical wake without disconnecting and one normal tap.

**N3. `WAIT` on a signed command.** Unchanged from the 11-05 report (needs H7 and the user's approval).

**N4. F4 without the car.** The library can reproduce F4 in a unit test (fake link with a write that suspends past the
deadline). On the car, a passive check is enough: leave the monitor in the background with the phone locking and unlocking
for 10 minutes and count `Failure(Timeout(afterSend=false))` lines followed by a `closed` line. Do not provoke it with
commands.

## Hand-offs

- **`library-developer`**
  - H1 (timeout inside the write; ADR-0010 and D29 wording; Go deviation per AGENTS rule 5). Report the mismatch to the
    user with `ble.go:108`, `vcsec.go:78` and `vehicle.go:211-212`.
  - H2 with the FR-105 plan; ADR-0015 needs the two F2 observations and the Codex caveat that session info does not
    authenticate the fault.
  - H3: A8.2, the two `NoSession` rows of `18-lib-troubleshooting.md`; HTML in three languages through the usual flow.
  - A8.5: `getState` on a real car: `DRIVE`, `MEDIA`, `MEDIA_DETAIL` answered and verified (F1, scope: one car, firmware
    unrecorded). Sleeping-Infotainment silence: Likely with the alternative weakened (F3). Wake time: still open (F7).
  - `16-lib-errors.md` §Response size limit: largest own `DRIVE` reply 436 B, no fault in 20, including 6 with an active
    route (user-confirmed; destination length unknown). §Queries while another BLE
    client is active: a "no traffic" data point (830 of 830, F8).
  - Part B side items (F9). FR-104: no change. H5, H7 for decision.
- **`library-api-request`**: none. The public API was enough; H1 and H2 are behaviour changes.
- **`ios-developer`**: H4, H6; README S6 expected results (H3); README S11 wording (VCSEC `gear=` is logged by design,
  only `getState` values are not); N1 runbook text (marks first, stale-cache step, T4 without "지금 조회").
- **`android-developer`**: none.
- **User**: no mark and no firmware again; please start the next run with them. Who tapped while driving is not in the
  log. Answered after the first draft (user-confirmed): navigation on from about 09:15; attempt 22 at 10:18:56 was a
  second unlock. Still open: navigation during the second drive, and the destination name length.

## Codex

Consulted through the Codex discussion MCP (`gpt-6.1-sol`, effort high), two rounds, on `main` at `70ec920` (Codex
confirmed branch and HEAD; read-only, it could not write a file). Codex recounted the logs and read the dispatcher,
transport, sample and Go code itself.

| Point | Claude's position | Codex's view | Outcome |
|---|---|---|---|
| A counts | 39/39 `Success`, 39 `gcm-ok`, 4 signed `gcm-ok`, 7 `WAIT` at position 16, 7 of 8 bursts | Recount matches; state the burst definition; resend 1.080 to 1.109 s; "no late final within the windows", not "never"; command identities stay inferred | Adopted: 1.5 s split stated, 1.0 s as a robustness check |
| B mechanism | Fault, session info applied, resend, `Success`; "otherwise `NoSession`" | Sequence confirmed; "applied" is Likely (strong): a missing tag or late session info is discarded without invalidation; Infotainment `INVALID_SIGNATURE` does not prove an epoch mismatch | Adopted: "synchronization recovery", epoch only for VCSEC |
| B exception | Fault allowlist plus session-info HMAC bound to the request UUID → safe to resend | Unsafe: the reference verifier signs session info for a corrupted or replayed copy of an executed request (`verifier.go:52-60`, `:217-220`, `:237-240`), so a relay can obtain a genuine fault and cause a second execution | Adopted: no general exception; reissue only for audited repeat-safe operations |
| Repeat-safe list | Reads, `lock`, `unlock`, `wakeUp` | Reasonable with limits: enumerate Infotainment reads; `wakeUp` with bounded retries; `lock`/`unlock` only with accepted target-state semantics, retries inside the original deadline, cancelled when a newer command supersedes them (an intervening opposite action can be undone); unsigned reads need no session recovery | Adopted in F2 options |
| C (11-05 F1) | "Removes" the stale-session alternative | Too strong: one case; provenance not proven; 213 ms is reply-to-fault, not send-to-fault | Adopted: "weakens", stays Likely |
| D | Misclassification, Go deviation, hardening at `transport.send` | Agrees it is a defect and a rule 5 deviation; mechanism Unknown; set the possibly-sent state before the first `link.write` (entering `send` also covers the mutex wait); do not clear it on a transport retry; also in `SendWithRetry`; cite `vcsec.go:78` | Adopted (H1) |
| D status | Established by code and one case, not Confirmed | Likely (strong), controlled reproduction pending; no reason to override the project rule | Adopted |
| E | Likely | Likely (strong) | Kept Likely; inference only |
| F | Coverage table | T2 "latest reported gear", gear does not prove motion; 453 B is an observed maximum, not a limit; README S11 wording is false for VCSEC `gear` | Adopted |

Agreed: every finding and status above. No disagreement remains.

The user's two answers (navigation on from about 09:15; attempt 22 was a second unlock) arrived after the consultation.
They change no agreed status: they turn the T2 size step into a route-linked step (Likely), remove `wakeUp()` as a
candidate in F7, and add later accepted commands as support for "session info applied" in F2. Codex was not consulted
again on these edits.
