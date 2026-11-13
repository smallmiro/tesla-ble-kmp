# Build 27 while driving: no lost request with the other app off, a cached Infotainment counter one step behind, link losses after parking (2026-11-14)

Author: `protocol-analyst`. Skill: `analyze-monitor-logs`. Repository state: branch `feat/collect-monitor-logs-skill` at
`9ad0451` (no library, sample or manual change since `6a5658f` that affects this analysis).
Builds on `{{ANALYSIS_DIR}}2026-11-13-build27-all-categories.md` ("the 11-13 report", F10 to F19, H8 to H16, N5 to N9),
`{{ANALYSIS_DIR}}2026-11-07-118-awake-car-capture.md` ("the 11-07 report"), `{{ANALYSIS_DIR}}2026-11-05-118-sleeping-car-getstate.md`
and `{{ANALYSIS_DIR}}2026-11-01-build18-link-health.md` ("the build 18 report"). Numbering continues from the 11-13 report:
findings F20 to F26, hardening H17 to H24, next scenarios N10 to N14.
This is an analysis report. Nothing here changes the library, the sample or the manual. `library-developer` decides what
enters `{{LIB_DOCS_DIR}}14-lib-reading-state.md`, `16-lib-errors.md`, `17-lib-platform-notes.md`, `19-lib-architecture.md`,
ADR-0015 and the PRD; `ios-developer` decides sample changes. §Library hardening gives, per manual item, the final wording
recommended after combining the 11-13 and 11-14 evidence.

**Summary.** Two captures in a moving car, about 12 minutes connected in `DRIVE` or `REVERSE`, with the user's other BLE app
turned off (user confirmation). **No own request went unanswered**: 715 of 715 (673 VCSEC, 42 Infotainment), no timeout,
no late reply, no `Uncertain`. The only frames for another client were 7 encrypted, empty-body VCSEC replies (83 B) to
other routing addresses, the form of the car's reply to another key's signed command, which also appear in the earlier
zero-loss captures; none of our requests near them failed. This is an uncontrolled "client off" block and fits the 11-13
F12 association; F12 stays Likely (F20). The first `LOCATION` of the second process took 1495 ms because the car refused
the cached Infotainment session with `INVALID_TOKEN_OR_COUNTER` plus session info, and the library resynchronized and
resent. The cache is written when `startSession` succeeds, which the sample calls before every Infotainment request, so it
holds the counter from before the last request; the user force-quit the previous process without `disconnect()`, so its
last counter was never stored and the new process reused one the car had seen. Mechanism Likely, controlled test N10
(F21). `getState`
while driving answered every time: 37 of 37 `Success`, 262 to 590 ms apart from that resync, including 22 `LOCATION` taps
0.9 to 3 s apart, with a field set that never changed within a category; no Infotainment `WAIT`, no fault, no sign of rate
limiting (F22). VCSEC round trips were 91 ms at the median with two isolated replies of about 2 s; three `WAIT`s at the
15th, 16th and 16th reply of the connect bursts (F23). Both link losses came after parking, not while driving: the first
18 s after `PARK`, with the user (by their account) staying next to the car until about 08:11, so distance is unlikely but
the cause is Unknown; the second a walk-away, 20 s after the walk-away lock (F24). After the first loss the reconnect loop
froze for 90 s while iOS suspended the app (11-13 F18), this time with the car in reach. In the background the poll became
intermittent, its late rounds clustered with incoming frames (F25). In the broadcasts, field 8 sub-field 1 dropped to 1 in
gear three times for 2 to 7 s, which B3.1 had never seen, and its changes coincide with those 83 B replies to other
addresses more often than a uniform-time baseline predicts across ten recordings; the user carried two keys, the Tesla
phone key on this iPhone and the enrolled Apple Watch (F26). §Library hardening states, per manual item, the final wording
after combining 11-13 and 11-14.

## Data

| Item | Value |
|---|---|
| Sessions | `monitor-20261114-071723.jsonl` (281 lines, one connection 07:17:23.358 to the end of the file at 07:22:06.956, no `disconnected` line) and `monitor-20261114-080221.jsonl` (747 lines, connections 08:02:22.125 to 08:09:35.098 and 08:11:08.176 to 08:11:37.030), in `{{SAMPLE_LOGS_DIR}}ios/2026-11-14/` (git-ignored) |
| Phone | iPhone 16 Pro (`iPhone17,1`), iOS 27.2.0 |
| App | Monitor app `0.5.0(27)`, same build as the 11-13 report |
| Car | Assumed to be the Model Y of Part B B5 (same phone, same seven-key whitelist, `entries=7 slots=[0..6]`); firmware unknown (user, 2026-11-14) and model not recorded; VIN not quoted (no VIN-shaped string in either file) |
| Clock | Device clock, KST (+09:00) |
| Scenario | No `mark` line; the rest is inferred, except for these **user confirmations (2026-11-14, through the coordinator)**: (U1) the user's other BLE app was turned off for both captures; (U2) at 08:09:21, when the app went to the background, the user said that something auto-connected ("자동연결이라 연결되었었을 꺼에요", "it was auto-connect, so it would have been connected"; which app or connection is not stated and not inferred here), and they stayed next to the car until about 08:11; (U3) the user carried both the iPhone and the Apple Watch, and both act as keys (the Tesla phone key on this iPhone and the enrolled watch); (U4) between 07:19:04 and 07:19:17 they pressed the read button and the Infotainment buttons in Key & Control; (U5) they force-quit the monitor app between 07:22 and 08:02; (U6) the firmware version is unknown |
| Poll interval | 10 s (10.01 to 10.65 s from a `listKeys` result to the next `vehicleStatus` start in the foreground, 52 of 52; 31.2, 48.0 and 60.3 s in the background, F25) |

Limits of the log, as before: no `TX:` lines; values never reach the log (FR-112); the Key & Control tab's Infotainment
controls and its automatic `listKeys` write no line; no RSSI after the connect; the `getState` line does not record the
button mode (11-13 H11). Log value check: `grep '"kind":"query"' … | grep -E '[0-9]+\.[0-9]{4,}'` gives 0 lines in both
files, and no coordinate-shaped number appears anywhere. This report names fields and counts only; it quotes no
coordinate, speed, odometer or other value.

## Inventory

| Item | `071723` | `080221` |
|---|---|---|
| Span | 07:17:23.2 to 07:22:06.956 | 08:02:21.8 to 08:12:13.467 |
| Connections | 1: `connected scanHitMs=51 rssi=-49 openAttempt=1`, 0.13 s after `connecting attempt=1`; 4 m 43.6 s until the file ends | 2: `scanHitMs=193 rssi=-49`, 0.33 s after `connecting` (7 m 13.0 s); `scanHitMs=79 rssi=-57`, 0.21 s after a woken `connecting attempt=2` (28.9 s) |
| Car state | `DRIVE`, `INTERNAL_LOCKED`, `PRESENT`, `AWAKE` throughout | `DRIVE` from 08:02:22, five `REVERSE`/`DRIVE` shifts 08:07:52 to 08:08:29, `PARK` and `UNLOCKED` 08:09:17.173; on the second connection `PARK`, `UNLOCKED`, `NOT_PRESENT`, then walk-away `LOCKED` 08:11:16.907 |
| App lines | Foreground 07:17:23.390 to 07:19:24.737; background from 07:19:25.498 (phone locked 07:19:25.968); phone unlocked 07:22:05.738 without the app coming to the front | Foreground 08:02:21.895 to 08:09:20.895; background 08:09:21.863 (no phone-lock line); phone unlocked and app in front 08:11:07.966 to 08:11:47.811 (phone locked 08:11:47.672); background again from 08:12:04.612 |
| Own requests (highest `attempt`) | 201: 193 VCSEC, 8 Infotainment | 464 (430 VCSEC, 34 Infotainment) and 50 (VCSEC) |
| Own replies | 201, one per attempt; 1 VCSEC `WAIT` | 464 and 50, one per attempt; 2 VCSEC `WAIT`, 1 Infotainment `ERROR/INVALID_TOKEN_OR_COUNTER session_info=96B` (F21) |
| Never answered | **0**. No `to=address(16B, closed)` line, no request open at either link loss | **0** |
| `query` lines | `vehicleStatus`, `keySummary`, `listKeys` 16 each, all `Success`; `getState` 4 `Success` (`MEDIA`, `MEDIA_DETAIL`, `LOCATION` x2) | 41 each `Success`, plus one cycle of 3 `Failure "vehicle not connected"` at 08:09:35.112 to .134 (F24); `getState` 33 `Success` (`LOCATION` x22, `DRIVE` x6, `TIRE_PRESSURE` x2, `PARENTAL_CONTROLS`, `MEDIA`, `MEDIA_DETAIL`) |
| Infotainment replies without a `query` line | 4, all `gcm-ok`: `encrypted(186B)` 07:19:04.922, `encrypted(249B)` 07:19:09.451, `encrypted(2B)` 07:19:15.752 and 07:19:17.673, each 0.15 to 0.24 s after a VCSEC `vehicleStatus` reply. This is the Key & Control Infotainment controls' order (`InfotainmentRunner.run`: status read, `startSession`, one call), and the user confirmed pressing the read button and the Infotainment buttons there in this window (U4). By size most likely the climate read, the charge read (11-13: `CLIMATE` 168 B, `CHARGE` 250 B) and two commands whose 2 B reply is the size of an empty-success `Response{actionStatus{}}`. Which buttons exactly is not logged (H11) | none |
| Extra VCSEC traffic | 3 automatic `listKeys` of the Key & Control tab (8 requests each), 4 status reads of the Infotainment controls | 4 automatic `listKeys`, 3 more `vehicleStatus` reads |
| Events | 6 `Status`, all `unknownFields=[1.8]`; `[drop:to-domain]` 6 | 20 `Status`, all `unknownFields=[1.8]`; `[drop:to-domain]` 20 |
| Other-client frames | 5, all `[drop:no-handler] 83B … to=address(16B, other) request_uuid=16B payload=encrypted(0B)` (F20) | 2, same form |
| Not seen | `Unrecognized`, fields 53, 48, 44, `AuthenticationRequest`, `[drop:missing-source]`, `[drop:unparseable]`, `RESPONSE_MTU_EXCEEDED`, any Infotainment frame for another client | same |

`pbwire.py` was used on all 26 event frames (field 8, F26). Replies to our own requests carry no raw bytes in the log.

### Timeline of the key moments

| Time | What |
|---|---|
| 07:17:23.358 | Connected; poll and Key & Control check form a 27-reply burst; `WAIT` on attempt 15 at 07:17:24.811 (F23) |
| 07:17:23.581 | First 83 B reply to another address, 0.22 s after our connect |
| 07:17:40 to 07:18:35 | Taps `MEDIA`, `MEDIA_DETAIL`, `LOCATION` x2, all `Success` (cached Infotainment session accepted, no fault) |
| 07:18:23.943 | One VCSEC reply 2.07 s after the previous one inside a poll's `listKeys` (F23) |
| 07:18:28.804 / 33.392 | Field 8 `{1,1,1}` in `DRIVE`, back to `{2,1,1}` 4.59 s later; 83 B reply to another address 3.48 s after that (F26) |
| 07:19:04.9 to 17.7 | Two Infotainment reads and two Infotainment commands without log lines, pressed in Key & Control (U4, Inventory) |
| 07:19:25.498 | App to the background, phone locked 0.47 s later; polls from now on intermittent, late ones starting with incoming frames (F25) |
| 07:22:05.7 | 83 B reply to another address and phone unlock in the same 15 ms; one poll; the file ends at 07:22:06.956 with no `disconnected` line: the user force-quit the app later, before 08:02, without a disconnect (U5, F21) |
| 08:02:21.794 | New process (`start reason=launch`), connected 0.33 s later |
| 08:02:30.878 / 32.109 | `LOCATION` tap: attempt 29 `INVALID_TOKEN_OR_COUNTER` with `session_info=96B`, attempt 30 `gcm-ok`, `durationMs=1495` (F21) |
| 08:02:55 to 08:05:02 | 21 more `LOCATION` taps (0.9 to 3 s apart in bursts), `TIRE_PRESSURE`, `PARENTAL_CONTROLS`, 6 `DRIVE` taps, all `Success` (F22) |
| 08:04:04.480 | `keySummary` answered after 2.01 s (F23) |
| 08:04:34.148 to 36.939 | 83 B reply to another address, then field 8 `{1,1,1}` 0.60 s later, back to `{2,1,1}` 2.19 s after that (F26) |
| 08:07:52 to 08:08:46 | Five gear changes (parking manoeuvre); field 8 `{1,1,1}` in `REVERSE` 08:08:39.371 to 46.121; 83 B reply to another address 3.51 s after |
| 08:09:17.173 | `PARK` and `UNLOCKED` in one frame (twice, 57 ms apart) |
| 08:09:21.863 | App to the background without a phone lock; the user says something auto-connected then and that they stayed next to the car until about 08:11 (U2) |
| 08:09:24.229 | Last poll: `PARK`, `UNLOCKED`, `PRESENT`; its last reply 08:09:25.078 |
| 08:09:35.098 | `disconnected` "bluetooth connection lost"; 3 `Failure "vehicle not connected"` 14 to 36 ms later (F24) |
| 08:09:37.210 → 08:11:07.873 | Scan `attempt=1`, its 30 s timeout logged after 90.66 s, 61 ms before the app came forward; the car was in reach (U2) (F24) |
| 08:11:07.966 / 08:11:08.176 | Phone unlocked, `start reason=protected-data effect=woke`, connected after a 79 ms scan hit at -57 dBm; `UNLOCKED`, `NOT_PRESENT` |
| 08:11:16.907 | Walk-away `LOCKED` |
| 08:11:31.6 to 33.98 | Last `listKeys`: reply gaps 0.15 to 0.60 s instead of about 0.09 s |
| 08:11:37.030 | `disconnected` "bluetooth connection lost", app in the foreground; phone locked 10.6 s later |
| 08:12:09.208 / 13.467 | Scan timeout after 30.10 s (process running), backoff 4 s, `connecting attempt=2`; file ends |

## Findings

### F20. No own request went unanswered with the other BLE app off; the 7 `other` frames are encrypted empty-body VCSEC replies to other routing addresses — a fact of this run; as evidence for 11-13 F12 an uncontrolled "client off" block, F12 stays Likely

**What the log shows.**
- 715 own requests on three connections, attempts 1 to 201, 1 to 464 and 1 to 50, each with exactly one reply line (a
  `WAIT` or fault reply counts as the reply to its attempt; the resend is a new attempt). No `closed` line. No request was
  open when either link dropped: the last poll before each loss had finished (08:09:25.080, 08:11:33.978).
- The three `Failure "vehicle not connected"` at 08:09:35 were never written (F24).
- **The 7 frames for another client** are all `[drop:no-handler] 83B from=domain(DOMAIN_VEHICLE_SECURITY) to=address(16B, other)
  request_uuid=16B payload=encrypted(0B)`: a reply with `AES_GCM_Response_data`, an empty body and a 16-byte request UUID,
  addressed to a routing address that is not ours. We cannot verify its tag (not our session), so "signed" describes the
  form only. It is the form of the vehicle's "done" for a signed VCSEC command (B3.5 first row; the same 83 B form as our own
  `lock`/`unlock` successes in the 11-07 report), so another key's command is the plain reading; the log does not identify
  the key. There was **no Infotainment frame for another client, no `session_info`, and no 16 to 20 B encrypted VCSEC
  reply with an empty request UUID**, the three markers of the polling client in the 11-13 report (F12) and of the build 18
  client (F1).
- Times: 07:17:23.581, 07:17:54.088, 07:18:36.871, 07:19:27.901, 07:22:05.728, 08:04:34.148, 08:08:49.629. Gaps 30.5, 42.8,
  51.0, 157.8 s, then 255.5 s in the second file: B3.5's "about every 1 to 2 minutes", less often in the second capture.
  Three fall within 4 s of a field 8 sub-field 1 change (F26), two within 2 s of this phone's lock or unlock (1.93 s after
  `protectedData unavailable`, 10 ms before `available`), one 0.22 s after our connect, one 0.12 s before the first
  broadcast of the session.
- **Nothing of ours failed near them.** Within ±3 s of each: 07:17:23.581 sits in the connect burst, own reply gaps 88 to
  122 ms (the `WAIT` 1.23 s later is F23); 07:18:36.871 came 1.4 s after a poll and a `LOCATION` tap had completed with
  normal gaps; 08:08:49.629 was followed 0.27 s later by a poll and a `MEDIA_DETAIL` tap with reply gaps of 87 to 182 ms;
  07:22:05.728 by a poll answered in 153, 95 and 984 ms; the other three had no own request within 3 s.

**Who the other client was.** Not in the log. **User confirmation (U3): the user carried this iPhone, with the Tesla phone
key on it, and the enrolled Apple Watch (whitelist slot 4, `APPLE_WATCH`), and both act as keys.** With the other app off
(U1), these two keys are the candidates the user knows of. The whitelist also has other `IOS_DEVICE` keys, and nothing
says no other key was nearby, so this is a strong candidate set, not an identification. Which of the two sent each command
cannot be told: the frames carry only a 16-byte routing address, which the log does not compare between frames. The two
frames at this phone's lock and unlock fit the Tesla app on this iPhone. Across the builds 23 to 27 logs, though, only 8 of
34 such frames came within 5 s of a lock or unlock, and getting in or out of the car often goes with both. So the
attribution stays open. That the Tesla app ran on the same iPhone also bears on the build 18 hypothesis that apps on one
iPhone share its link to the car, which would explain why these replies reach our link. Unknown, not tested here.

**Bearing on 11-13 F12 (Question 1).**

| Capture | Polling client's Infotainment frames on our link | Own requests | Unanswered |
|---|---:|---:|---:|
| Build 18 S1 (parked) | 1690, about one per 0.94 s | 53 polls | 47 `listKeys` `Uncertain` |
| 11-13 (parked, awake) | 28, one per 2.1 s | 134 | 15 (12 while that client was active; 3 earlier in a link-wide silence, car asleep) |
| Build 18 S2 | 0 | 37 polls | 0 |
| 11-07 C1, C2 (driving and parked) | 0 | 830 | 0 |
| **11-14 (driving, then parking; app off by user confirmation)** | **0** | **715** | **0** |

- Supports the association: three captures without the polling client have no lost request at all, two captures with it
  have many, and today's run is the first where the user states the app was off. It also removes two alternative
  explanations for 11-13: a moving car (busier Infotainment) did not lose requests, and neither did the same 10 s poll (ten
  VCSEC requests per cycle) with Key & Control automatic checks (seven today) and 37 taps.
- It is not a controlled test. The two sides differ in day, car state (driving versus parked, 11-13 began with a sleeping
  car), place and radio environment, and the "off" side is a recollection. Under the project rule F12 stays **Likely**; N6
  (11-13) with on and off blocks on the same day and place is still what confirms it.
- **The 83 B replies to other addresses are not the polling client.** They came in every capture, including the zero-loss
  ones (11-07 C1 and C2, today), so their presence predicts nothing about losses. The manual should not tell apps to treat
  any `other` frame as "another client is active" (H9).

### F21. The first `LOCATION` took 1495 ms because the cached Infotainment session was one counter behind — mechanism Likely (code path checked, one observation); controlled test N10

**Observation.** Attempt 29 (the tap's `getState`, 180 ms after its status read) drew `[reply:infotainment] 185B …
status=OPERATIONSTATUS_ERROR/MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER session_info=96B payload=none attempt=29 auth=none` at
08:02:30.878. Attempt 30 came back `encrypted(172B) auth=gcm-ok` 1.231 s later (the 1 s `retryInterval` plus a normal round
trip), `durationMs=1495`. Neither file has an Infotainment handshake, so both processes used the session restored from the
cache at connect.

**Why the counter was behind** (conditional on the fault being genuine: it is untagged, but the session info it carried
was applied and the resend under it was accepted).
1. The cache is written when a handshake completes, when session info is updated, on `disconnect()`, and, in
   `VehicleSession.startSession`, after **every** successful `startSession`, even when the domain was already `Ready` and
   nothing was sent (`VehicleSession.kt:101-134`; Codex found this). The export includes the signer's counter (Go
   `{{REF_REPO_DIR}}internal/authentication/signer.go:86-94`, ported as is).
2. The sample calls `startSession(setOf(INFOTAINMENT))` before every `getState` tap and every Key & Control Infotainment
   control (`StateDiagnostics.swift:450`, `InfotainmentControl.swift:134`, `KeyControlModel.swift:356`). So each store
   captures the counter **before** that tap's request, and the request then uses the next value.
3. File 1 sent 8 Infotainment requests on the restored session, the last at about 07:19:17.5 (an Infotainment command,
   attempt 141; pressed by the user, U4). The process then ended without `disconnect()`: no `disconnected` line, the next
   file starts with `start reason=launch` 40 minutes later, and **the user confirmed force-quitting the monitor app in
   between (U5)**. So the cache kept the counter from just before attempt 141.
4. The new process restored that counter, and its first request reused the value attempt 141 had used. In the Go
   reference verifier exactly this case gives `INVALID_TOKEN_OR_COUNTER`: the GCM tag verifies but the counter was seen
   before (`{{REF_REPO_DIR}}internal/authentication/verifier.go:215-220`, `window.go:17-20`); a wrong epoch gives `INCORRECT_EPOCH` and a
   failed tag `INVALID_SIGNATURE`. The car's firmware is not the Go verifier, but the fault name matches.
5. The library then did what `16-lib-errors.md` §Retries describes: applied the session info and resent with a new counter.

**Consistent with earlier captures.** The 11-13 connection ended with a link loss, after which the sample calls
`disconnect()` (`MonitorEngine.swift:197`), which stores the final counter; this morning's first process restored it and was
accepted (attempt 39, `gcm-ok`). In the 11-07 report the C2 cache, written after a link loss and `disconnect()`, was accepted
too. The 11-07 C1 refusal was `INVALID_SIGNATURE`, a different fault, still unexplained.

**Alternatives.** The car could have refused the counter for another reason (an Infotainment restart that kept the epoch, a
firmware rule); a store could have failed (library log levels other than the `observe` lines are not in the monitor log); a
restore could have produced a wrong counter; another user of the same key would also advance the car's counter (none is
known: the key lives in this app). The fault name and the code path make the stale counter the simplest reading, not the
only one. How the process ended is no longer an alternative: the user force-quit it (U5), which removes the crash and
system-kill readings; for this mechanism they would have been the same anyway.

**Consequences.**
- An app process that ends without `disconnect()` after at least one authenticated request since the last store (force
  quit, iOS ending a suspended app, a crash) leaves a stale counter. With the store-before-request pattern above, that is
  every such exit after any authenticated request. The first authenticated request of the next process in that domain then
  costs, in this observation, one extra request and about 1.2 s (VCSEC commands use the same store path). The result is
  still `Success`; nothing runs twice, because the refused copy was not executed.
- **ADR-0015 / FR-105 (11-07 H2, 11-13 H16):** this is the fourth untagged fault-with-session-info recovery observed and the
  first `INVALID_TOKEN_OR_COUNTER`. Under a strict "no resend on an untagged fault" rule, each exit that leaves a stale
  counter would turn the next process's first command or `getState` into `Uncertain`. Because the library's own store timing
  causes it, it will be common on iOS, where the system ends suspended apps without notice.

**Status.** Likely (unchanged by the user's answers): the fault, the store timing and the process end (force quit, U5) line
up with the code, once, conditional on the fault being genuine and the restore being correct. Whether every store
succeeded: Unknown. The user's force quit makes this observation the "A" arm of N10 done once without marks; Confirmed
needs N10's controlled A/B pairs.

### F22. `getState` while driving: every request answered, field sets stable within a category, no rate limiting seen — Likely (one car, uncontrolled)

| Category | Taps | `durationMs` min / median / max | Status read → data reply | Ciphertext | `present` (sample-mapped fields the car sent) | Change against earlier runs |
|---|---:|---|---|---|---|---|
| `LOCATION` | 24 | 262 / 318 / 590 over 23 taps, plus the 1495 ms resync (F21) | 181 to 394 ms | 171 or 172 B (24 of 24) | 12 of 16, the same 12 in all 24: `native_latitude`, `native_longitude`, `latitude`, `longitude`, `native_type`, `geo_latitude`, `geo_longitude`, `estimated_to_raw_distance`, `estimated_gps_valid`, `heading`, `gps_as_of`, `timestamp` | **`gps_as_of` now present** (absent parked on 11-13, 165 B then). Still absent: `corrected_latitude`, `corrected_longitude`, `location_name`, `homelink_nearby` |
| `DRIVE` | 6 | 286 / 368 / 454 | 212 to 301 ms | 308 or 310 B | 6 of 6: `speed`, `speed_float`, `shift_state`, `power`, `odometer_in_hundredths_of_a_mile`, `timestamp` | First field names for `DRIVE` (the 11-07 taps predate `present=`). Sizes in the range of the 11-07 drives (383 to 436 B frames; today 411 to 413 B); route state unknown |
| `MEDIA` | 2 | 290, 292 | 184, 211 ms | 72, 67 B | 4 of 4: `media_playback_status`, `audio_volume`, `now_playing_title`, `timestamp` | First field names |
| `MEDIA_DETAIL` | 2 | 318, 462 | 212, 297 ms | 76, 64 B | 1 of 3: `timestamp` (no `now_playing_duration`, `now_playing_elapsed`) | First field names |
| `TIRE_PRESSURE` | 2 | 263, 312 | 181, 182 ms | 73, 74 B | 15 of 19: pressures, hard and soft warnings (fl, fr, rl, rr), `tpms_rcp_front_value`, `tpms_rcp_rear_value`, `timestamp` | **The four `tpms_last_seen_pressure_time_*` are absent** (all 19 parked on 11-13, 130 B). The 56 to 57 B difference matches four `Timestamp` messages of about 14 B (seconds and nanoseconds) |
| `PARENTAL_CONTROLS` | 1 | 271 | 181 ms | 65 B | 13 of 13 | Same set as 11-13 (64 B) |

- **Repeated polling (Question 3).** 22 `LOCATION` taps in three bursts (08:02:55 to 08:03:26 at 2.3 to 3.3 s, 08:03:46, and
  08:04:49 to 08:05:02 at 0.9 to 2.3 s), 6 `DRIVE` taps 2.2 to 6.5 s apart. Every reply was the first reply to its request,
  `gcm-ok`, with the same field set within the category; ciphertext varied by 1 B only (171/172, 308/310 B), consistent
  with a varint changing length; which field is not identifiable. Latency did not grow along a burst (last of the 9-tap
  burst 372 ms, first 369 ms). No Infotainment `WAIT` (still none ever seen), no `BUSY` or other fault, no size fault. So no
  sign of rate limiting at one request per 0.9 s, the fastest this run reached; a limit above that rate is not excluded.
- **`gps_as_of` appearing**: the 6 to 7 B growth fits one `uint64` field with a two-byte tag (field 104) and a 4 to 5 byte
  value. One reading is that the car fills it only while it has a current GPS fix (driving here, parked on 11-13); not
  established, and what it marks is not known.
- **`tpms_last_seen_pressure_time_*` disappearing** while driving and present when parked suggests the car sends the
  "last seen" times only when the sensors are not reporting live (parked, sensors idle). Unknown, two states once each.
- `MEDIA_DETAIL` without duration and elapsed fits nothing playing with a known length; `MEDIA` carried a title. Values are
  not in the log.
- Latency is in line with earlier runs: status read to data reply median 238 ms here (11-07 `DRIVE` while driving: median
  273 ms; 11-13 parked: median 197 ms). The two longer taps (`LOCATION` 590 ms at 08:04:51.849, `DRIVE` 454 ms at
  08:04:28.778) overlapped a poll; the 462 ms `MEDIA_DETAIL` too.
- **Privacy.** `DRIVE` carries 250 B or more beyond the six mapped fields (unmapped official route fields, which the 11-07
  report tied to a 40 B step with navigation on, and possibly `TESLEMETRY-EXT` fields). With `LOCATION` (11-13 F11) this
  makes two categories whose unmapped content may include location data; A8.3's "do not log a result" covers both.

**Status.** Likely for "answered while driving at this rate, with these field sets": one car, two drives, uncontrolled. The
field-set differences between parked and driving (`gps_as_of`, `tpms_last_seen_*`) are observations, Unknown as rules.

### F23. VCSEC timings: no head-of-line stalls without losses, two isolated 2 s replies, `WAIT` at the 15th and 16th reply — refines 11-13 F14 and F17; Likely

**Round trips (Question 2).** Without transmit lines a round trip is inferred from a sequential chain: the gap between
consecutive replies inside one `listKeys` call (summary → slot 0, slot k−1 → slot k, consecutive attempts; polls and Key &
Control checks), 443 samples: median 91 ms, 90th percentile 122 ms, 99th 359 ms. Outliers: **2071 ms** at 07:18:23.943
(slot 6 of a poll, `DRIVE`, nothing else on the link), and the `keySummary` of 08:04:04.480 took **2009 ms** (`DRIVE`).
Both were answered; nothing else is unusual around them. The 0.15 to 0.60 s gaps at 08:11:31 to 33 are the walk-away of
F24. `vehicleStatus` 72 to 172 ms (median 94),
`keySummary` 79 to 213 ms apart from the 2 s one, `listKeys` 718 to 1111 ms (median 813) apart from 2913 ms (the 2 s reply)
and 2613 ms (walk-away).

**Head-of-line wait (11-13 F14).** Without a lost request the shared VCSEC lock costs little: a tap that overlapped a poll
took 454 to 590 ms instead of about 300 ms, and the poll's `keySummary` behind such a tap took 213 ms. The 1.2 to 4.6 s waits
of 11-13 were waits behind lost requests.

**`WAIT` (11-13 F17).**

| Burst | Replies | Before the `WAIT` | `WAIT` at reply (attempt) | Resend answered after |
|---|---:|---|---|---:|
| 07:17:23.429, connect: poll, then two Key & Control `listKeys` | 27 | 14 replies in 1.29 s | 15 (15) | 1.081 s |
| 08:02:22.205, connect | 27 | 15 replies in 1.50 s | 16 (16) | 1.091 s |
| 08:11:08.264, reconnect | 27 | 15 replies in 1.50 s | 16 (16) | 1.081 s |

- An 18-reply burst at 07:18:57.810 (poll `listKeys`, a 1.05 s pause, then a Key & Control `listKeys`) had **no** `WAIT`; four
  12-reply bursts had none. All reports together: 23 own `WAIT`s, at reply 16 (18 times), 17 (twice), 15 (twice) and 6
  (once). A pause of about 1 s letting an 18-reply burst through favours a refilling allowance (rate) over a fixed count,
  as 11-13 F17 suggested.
- The burst with the `WAIT` at 15 is the only one with an 83 B reply to another address inside it (07:17:23.581). If the
  allowance were per vehicle rather than per client, that exchange would use one place; one case, not testable here.
- No same-request final reply after a `WAIT` (no `closed` line): unsigned VCSEC reads are now 0 of 16. No signed command met
  a `WAIT`; the ADR-0015 question for commands stays open.
- All three `WAIT`s are in the connect burst where the poll and the Key & Control automatic `listKeys` overlap (11-13 H12).

**For H8 (Question 6).** A silence-resend timer should not fire on these isolated slow replies: 2 of 673 VCSEC requests
took about 2 s and were answered. An experimental threshold of about 2.5 s after transport submission keeps them out; with
the 5 s budget that allows at most one useful resend, and less when a mutex wait or earlier `listKeys` steps have used part
of the shared deadline. It is a starting value for N6/N11, not an established minimum. Today's data cannot show any benefit
of H8, because nothing was lost.

### F24. Both link losses came after parking: the first cause Unknown (the user stayed next to the car, so distance is unlikely), the second a walk-away (Likely); the reconnect froze while the app was suspended with the car in reach (11-13 F18 again)

**First loss, 08:09:35.098 (Question 4).**

| Item | Value |
|---|---|
| Car | `PARK` and `UNLOCKED` since 08:09:17.173 (same frame, B3.1); last poll reply 08:09:24.229 still `PRESENT` |
| Doors | No door broadcast between `PARK` and the last received frame (08:09:25.078). A door opening produces one (B3.1), so nobody had opened a door by then |
| App | Background since 08:09:21.863, 13.2 s before the loss, with no phone-lock line (a missing `protectedData` line does not prove the phone stayed unlocked; which app came forward is not logged). User (U2): "it was auto-connect, so it would have been connected" ("자동연결이라 연결되었었을 꺼에요"). What auto-connected is not stated, and the log does not show it: no frame for another client arrived between 08:08:49.629 and the loss |
| Link | Idle from 08:09:25.078: the poll had ended and nothing else was pending. The last round trips were normal (`listKeys` 770 ms) |
| RSSI | Not logged after a connect. At the connects: -49 dBm inside the car (07:17, 08:02), -57 dBm at 08:11:08 with nobody inside |
| Other app | Off (user confirmation); no `other` frame since 08:08:49.629 |
| After | The car stayed `UNLOCKED` until 08:11:16.907. At 08:11:08 this phone connected to it in 79 ms at -57 dBm |
| Person | User (U2): stayed next to the car until about 08:11, carrying this iPhone and the watch (U3) |

- **Distance is unlikely, by the user's account.** The log alone could not show it: up to 10 s before the loss nobody had
  opened a door, and the late walk-away lock (1 m 42 s after the loss) did not prove the phone stayed near. **The user
  states they stayed next to the car until about 08:11 (U2)**, which fits the late walk-away lock (both keys stayed near,
  U3) and the -57 dBm connect at 08:11:08. So the link dropped with the phone a few metres from the car at most. This is a
  recollection, not a measurement (no RSSI is logged after a connect).
- What else could end a link 18 s after parking is open: a vehicle-side change at `PARK` or at the driver leaving, body
  shadowing as the phone went into a pocket, something on the phone side (the user mentions an auto-connect at that moment,
  U2; what it was is not known), or another app or key on the same iPhone. The log cannot separate them; the link was idle
  for the last 10 s, so the supervision timeout hid the exact moment.
- **Not while driving.** About 11.6 minutes connected in `DRIVE` or `REVERSE` today without the other app had no loss. With
  build 18 S2 (17 minutes, no client, no loss) and the 11-07 drives, the two build 18 `DRIVE` losses remain the only ones in
  gear, both with the other app running. That is a negative observation; it does not show that the other app caused those
  losses. The earlier note that link losses in `DRIVE` are not distance is unaffected; this loss was after parking.
- **The three `Failure "vehicle not connected"`** at 08:09:35.112 to .134: the poll timer fired 10.03 s after the previous
  `listKeys`, which is on schedule (foreground range 10.01 to 10.65 s), 14 ms after the library reported the loss and 44 ms
  before the connection loop's `backoff` transition cancelled the poll task (`MonitorEngine.swift:540-560`, `:616-628`). The
  library refused all three before any write (no attempt number was consumed). Whether the process had been running or was
  resumed by the disconnection is not visible. A sample race, harmless (H21).
- **Reconnect froze (11-13 F18, build 18 F3).** `connecting attempt=1` at 08:09:37.210; its 30 s scan timeout was logged at
  08:11:07.873, after 90.66 s, 61 ms before `scenePhase inactive` and 93 ms before `protectedData available`. The process did
  not run in between. Then the unlock woke the loop (`effect=woke`) and it connected in 0.21 s. **By the user's account the
  car was in reach for the whole 90 s (U2)**: the first case of a car known to be in reach that the suspended app did not
  reconnect to (#124 L2, #126). Still uncontrolled and based on recollection.

**Second loss, 08:11:37.030: walk-away.** `NOT_PRESENT` from the reconnect, walk-away `LOCKED` at 08:11:16.907, loss 20.1 s
later with the app in the foreground (phone locked 10.6 s after the loss). In the last `listKeys`, 3 s before the loss, the
gaps between replies grew from about 0.09 s to 0.15, 0.12, 0.45, 0.24, 0.48, 0.21, 0.36 and 0.60 s, as expected for a fading
link with link-layer retransmissions. The connection lasted 28.9 s. Then a normal 30.10 s scan timeout (the process
running) and a backoff. With the build 18 report F4 (21 s, 14 s), the 11-07 report F8 (27.4 s, 11.7 s) and the 11-13
report F18 (16.5 s): **six walk-aways, link lost 11.7 to 27.4 s after the walk-away lock** (H19).

**Status.** Second loss: Likely walk-away. First loss: cause Unknown; distance unlikely by the user's account (U2), which
moves "not distance" from a reading to a user-reported condition, not to a measurement. Freeze while suspended: Likely
(third time; the first with the car in reach by the user's account).

### F25. In the background the poll became intermittent; its late rounds started with incoming frames — Likely

| Poll start | Late by | What arrived in the same instant |
|---|---:|---|
| 07:19:32.120 | on time (10.4 s after the previous) | (app 6.6 s into the background, still running) |
| 07:20:33.454 | 50.4 s | `Status` repeat, line at 07:20:33.464 (60.04 s after the previous one) |
| 07:20:44.516 | on time | (process still running after that wake) |
| 07:21:33.472 | 38.0 s | `Status` repeat, line at 07:21:33.478 (60.01 s) |
| 07:22:05.723 | 21.2 s | 83 B reply to another address, line at 07:22:05.728; phone unlock 07:22:05.738 |

- Each late poll started 5 to 15 ms **before** the line of the frame that arrived with it, which fits the frame's
  notification resuming the process, the overdue timer firing first and the frame's processing finishing a few ms later.
  That is how `bluetooth-central` background mode is expected to behave: no timers while suspended, a short run on each BLE
  event. At 07:22:05 the phone unlock came in the same 15 ms, so that wake source is not unique. Two rounds ran on time
  (07:19:32.120, 6.6 s into the background, and 07:20:44.516, 10 s after a wake), so the process sometimes kept running
  for a while.
- The link itself stayed up in the background for 2 m 41 s with the phone locked, and every frame was delivered (6 events,
  6 `[drop:to-domain]`).
- So in the background the sample's 10 s poll becomes intermittent, and while driving with a person present the car's 60 s
  `Status` repeat (B3.1) appears to give it recurring chances to run. A parked car with nothing changing may send no
  repeats in some states (B3.1 "Not measured"), so a backgrounded app may poll rarely or not at all then.
- This is input for `17-lib-platform-notes.md` §iOS background use (still "unconfirmed") and for #126 (H20).

**Status.** Likely for intermittent background execution associated with incoming traffic. Which frame resumed the process
in each case: Unknown.

### F26 (Part B). Broadcasts while driving: field 8 sub-field 1 drops to 1 in gear; its changes coincide with 83 B replies to other addresses — B3.1 correction Likely; association Likely (descriptive); meaning Unknown

**Status frames (Question 5).** 26 frames, all `vehicleStatus` with `unknownFields=[1.8]`, `uiDesire` none, no closures (all
closed), `AWAKE` throughout.

| Item | Observed | Against B3.1 / 11-13 F19 |
|---|---|---|
| Lock while driving | `INTERNAL_LOCKED` in all 20 in-gear frames | As B3.1 |
| `PARK` | `INTERNAL_LOCKED` → `UNLOCKED` in the same frame as `PARK` (08:09:17.173, sent twice 57 ms apart) | Now 6 of 6 (B3.1 3, 11-07 F9 2 more) |
| Gear | Five `REVERSE`/`DRIVE` changes, one frame each, in the order operated by inference | As B3.1 |
| Presence | `PRESENT` in all in-gear frames; `NOT_PRESENT` after the reconnect | As B3.1 |
| Repeat | 7 gaps of 60.00 to 60.04 s ending at an identical frame in `DRIVE` (two of them counted from a field-8 change frame: 60.033 s and 60.000 s); every shorter gap ended at a change | As B3.1 (now 54 gaps of 59.9 to 60.15 s, 51 in `DRIVE`) |
| Frame size | 28 B with `INTERNAL_LOCKED` or `LOCKED`, 26 B with `UNLOCKED` (lock field not encoded) | As B3.1 |

**Field 8** (`{1, 2, 3}` = sub-fields 1, 2, 3, decoded from `raw` with `pbwire.py`):

| Time | State | Field 8 | Change |
|---|---|---|---|
| 07:17:54.214 | `DRIVE`, `PRESENT` | 2, 1, 1 | first frame of the connection |
| 07:18:28.804 | same | **1, 1, 1** | field 8 only |
| 07:18:33.392 | same | 2, 1, 1 | field 8 only, 4.59 s later |
| 07:19:33 to 07:21:33 | same | 2, 1, 1 | three 60 s repeats |
| 08:02:44.200, 08:03:44.228 | `DRIVE`, `PRESENT` | 2, 1, 1 | first frame, repeat |
| 08:04:34.749 | same | **1, 1, 1** | field 8 only, 0.60 s after an 83 B reply to another address |
| 08:04:36.939 | same | 2, 1, 1 | field 8 only, 2.19 s later; then three 60 s repeats |
| 08:07:52 to 08:08:29 | `REVERSE` / `DRIVE` | 2, 1, 1 | five gear changes |
| 08:08:39.371 | `REVERSE` | **1, 1, 1** | field 8 only |
| 08:08:46.121 | `REVERSE` | 2, 1, 1 | field 8 only, 6.75 s later; 83 B reply to another address 3.51 s after |
| 08:09:17.173 | `PARK`, `UNLOCKED`, `PRESENT` | 2, 1, 1 | lock, gear |
| 08:11:10.399 | `PARK`, `UNLOCKED`, `NOT_PRESENT` | 2, 2, 1 | first frame of the reconnect |
| 08:11:11.896 | same | 1, 2, 1 | field 8 only |
| 08:11:15.689 | same | 1, 1, 1 | field 8 only |
| 08:11:16.907 | `LOCKED`, `NOT_PRESENT` | 1, 1, 1 | walk-away lock, 5.0 s after sub-field 1 went to 1 |

Raw example, 07:18:28.804 (28 B, no identifiers): `…52120a1010021801200238024206080110011801`, where `38 02` is
`gear = DRIVE` and `42 06 08 01 10 01 18 01` is field 8 = `{1: 1, 2: 1, 3: 1}`.

- **Correction to B3.1.** "Sub-field 2 was 1 in all 75 frames in `DRIVE`, `REVERSE` or `NEUTRAL`" still holds (20 of 20 more).
  But sub-field 1 is not always 2 in gear: **3 of 20 in-gear frames had sub-field 1 = 1** (17 `{2,1,1}`, 3 `{1,1,1}`;
  B3.1's table: 0 of 75), each for 2.2 to 6.8 s before returning to 2, while the phone was inside the moving car (-49 dBm at
  the connect). Combinations seen today are all in B3.1's table; nothing new for 11-13 F19's `{2, 1, 2}`.
- **The 83 B replies to other addresses line up with sub-field 1 changes.** Today 3 of the 7 came within 4 s of a sub-field
  1 change. On independent data, the eight earlier recordings with decoded broadcasts (10-31 to 11-13, 5828 s connected, of
  which one 523 s connection ended with `stopped`; 44 sub-field 1 changes, 62 such frames): **12 of 62 frames lie within
  ±4 s of a change, where the time covered by those windows (3.3 % of the connected time) predicts 2.0** (2.2 without the
  `stopped` connection); within ±2 s, 8 against 1.2. Conversely 13 of the 44 changes have such a frame within ±4 s, in both
  directions (2 → 1: 7 of 24, 1 → 2: 6 of 20). Most frames (50 of 62) and most changes are not paired, so this is a partial
  association, not a one-to-one link. The uniform-time baseline is descriptive only: both kinds of event cluster around
  vehicle activity and authentication, and a stronger test would shift times within a connection and vehicle state
  (Codex). It fits the 11-07 F9 and 11-05 F6 observation that field 53 frames are followed by such a reply within 0.65 to
  0.78 s.
- **Two walk-away locks were preceded by sub-field 1 going to 1**: 5.0 s before today, 20.0 s before on 11-13 (17:50:31.336 →
  17:50:51.347). On 11-13 sub-field 1 went to 2 1.26 s before someone unlocked the car.
- **Reading.** Together with build 18 F7 (108 of the 111 frames with sub-field 1 = 2 were `PRESENT`), the simplest candidate is
  that sub-field 1 reflects whether the car currently localises an authenticated key nearby or inside, and that the 83 B
  replies are that key's own signed exchanges. Counterexamples: 17 `PRESENT` frames with sub-field 1 = 1 (build 18), 6 of 8
  frames with sub-field 1 = 2 `NOT_PRESENT` on 11-13, and the flapping while driving with the phone inside.
- **User confirmation (U3): two keys were in the car today**, the Tesla phone key on this iPhone and the enrolled Apple
  Watch. That makes the candidate concrete: either key's localisation could flap while driving (a watch on a moving wrist,
  or a phone key whose own BLE link reconnects), and either could have sent the commands behind the 83 B replies. It is
  consistent with the reading and tests nothing: both keys were present the whole time, so presence cannot be compared with
  absence, and which key is behind each change or reply is not in the log. #124 L5 / N12 (watch off, Tesla app quit,
  positions) is the test.

**Status.** The in-gear correction to B3.1: Likely (3 frames on two drives, uncontrolled). The association with the 83 B
replies to other addresses: Likely as a descriptive association (it held on independent recordings; uncontrolled; the
baseline ignores clustering). What sub-field 1 means: Unknown. The user's answer (U3) narrows the candidate keys but
changes no status.

## Scenario rows

| Row | Status | What was seen (no marks; inferred) |
|---|---|---|
| Conditions | Partly met | Other BLE app off (U1); Tesla phone key on this iPhone and the Apple Watch key present (U3). No marks; firmware unknown (U6) |
| #118 T2 driving | **Observed** | 37 taps, 0 failures, field names per category (F22). Speed unit still needs screen notes |
| A8.5 field coverage | Extended | `DRIVE`, `MEDIA`, `MEDIA_DETAIL` field names for the first time; `LOCATION` and `TIRE_PRESSURE` differ between parked and driving (F22) |
| S6 cached session | Observed twice | 07:17 accepted; 08:02 refused with `INVALID_TOKEN_OR_COUNTER` and recovered (F21) |
| T4 `WAIT` on a signed command | **미관찰** | No signed VCSEC command today; Infotainment commands without `WAIT` |
| #124 L1 | Uncontrolled "off" block | 715 of 715 answered (F20); no "on" block |
| #124 L2 | Uncontrolled, once | Loop frozen 90.66 s while suspended, with the user next to the car the whole time (U2, F24) |
| #124 L3 | Partly | 60 s repeats in `DRIVE` (F26); parked idle not covered |
| #124 L4 to L6 | Not run | Connects were fast (0.13 to 0.33 s) with no field 44 and no polling client, unlike build 18's "fast connect with the other client" pattern |

## Library hardening

### Earlier items after combining 11-13 and 11-14 (final recommended wording)

| # | Evidence change | Final recommendation (wording for `library-developer`) | Owner, priority |
|---|---|---|---|
| H8 bounded read resend (#125) | **Gains a constraint, no new benefit.** F23: two isolated 2.0 to 2.1 s VCSEC replies, answered; F20: no loss without the polling client, so the benefit is limited to apps that share the car with such a client | Keep the 11-13 design constraints unchanged (audited repeat-safe VCSEC reads only, one resend, keep the per-connection serialization and the caller's budget, timer from transport submission not from the mutex wait, jitter, `afterSend = true` kept, racing replies to the first copy handled, never for commands; ADR needed). Add: **an experimental silence threshold of about 2.5 s after submission**, not lower (inferred VCSEC round trips: median 91 ms, 99th percentile 359 ms, 443 samples; 2 of 673 requests took about 2 s and were answered); with the 5 s default budget that leaves at most one useful resend. Decide only after N11 has measured losses with a client on; today's run gives nothing to measure | `library-developer`, Medium (decision first) |
| H9 manual section "Queries while another BLE client is active" | **Gains.** F20: a user-confirmed "off" capture with 0 of 715 lost; other keys' 83 B replies present in every zero-loss capture | Replace `16-lib-errors.md` §Queries while another BLE client is active with the text below (§H9 text). Keep it Likely | `library-developer` (MD), Medium |
| H10 head-of-line wait | **Gains the normal case.** F23: without a lost request the wait is 0.15 to 0.3 s; 11-13 F14: behind a lost request 1.2 to 4.6 s | Add to `16-lib-errors.md` §Retries and timeouts: "VCSEC requests of one connection go out one at a time, and a call's `timeout` includes the time it waits for earlier VCSEC requests of the same connection (FR-049). Normally that wait is short: on a real car a `getState` whose status read met a poll took 0.45 to 0.59 s instead of about 0.3 s. Behind a request that gets no reply it lasts until that request's deadline: the next calls waited 1.2 to 4.6 s, and a tap's status read had about 2.7 s left for its own reply. So a call can spend most of its `timeout` without any problem on its own request, and `Uncertain(Timeout(afterSend = true))` can come after a short time on the wire." | `library-developer` (MD), Low |
| H11 log lines | **Gains.** Today: two Infotainment reads and two Infotainment commands, seven Key & Control `listKeys` and their status reads left no line; RSSI never logged after the connect | Unchanged from 11-13 (command lines with `source`, `mode=normal|experiment` on `getState`, a `query` line for the automatic `listKeys`, library send-side lines). Add: the Key & Control Infotainment controls (`call`, result, `durationMs`, `startedAt`, no values); a `link` line field for RSSI if H22 lands | `ios-developer`, `library-developer`, Medium |
| H12 sample load | **Gains.** All three `WAIT`s today were in the connect burst where the poll and the Key & Control automatic `listKeys` overlap (27 replies in 3.6 s) | Unchanged: run `listKeys` only when the slot mask changes or on demand, let the Key & Control check reuse the poll's last `listKeys`, reuse a fresh sleep state for taps (with freshness limit and connection generation) | `ios-developer`, Medium |
| H13 field census | **Gains a little.** F22: `DRIVE` carries 250 B or more beyond six mapped fields | Unchanged; include `DRIVE` (route fields would be location data, so numbers only) | `library-developer` with `ios-developer`, Low |
| H14 A8.2 wake guidance and B2.4 | **No new evidence** (the car was awake throughout today) | Apply the 11-13 wording unchanged in A8.2: "In one capture, a successful authenticated VCSEC command reply, inferred to be `wakeUp`, preceded an `AWAKE` broadcast by about 0.15 s; the first Infotainment reply seen came about 5.6 s after the command reply. An `AWAKE` indication does not establish Infotainment readiness." Keep the advice of a longer `timeout` for the first query after a wake. B2.4: "they become real values within about 0.5 s" → "closures became real values within 0.2 s; gear stayed `UNKNOWN` for 5.6 s once"; "The lock state reads `UNLOCKED` in them too" → "The lock state can read `UNLOCKED` in them too" | `library-developer` (MD), Medium |
| H15 sleep-read sentence | **No new evidence** (no `sleepReadFailed` today) | Unchanged: for `sleepReadFailed`, name a lost reply (another BLE client, the link) before sleep | `ios-developer`, Low |
| H16 ADR-0015 / FR-105 | **Gains.** F21: a fourth untagged fault-with-session-info recovery, the first `INVALID_TOKEN_OR_COUNTER`, most likely caused by the library's own cache timing, so it recurs after each unclean exit that leaves a stale counter | Add F21 to the FR-105 plan's evidence. The plan must say what a strict rule does to the first request after an unclean exit, or fix H17 first so this case disappears | `library-developer`, as 11-07 H2 |

**§H9 text** (for `16-lib-errors.md` §Queries while another BLE client is active, replacing the current body; tables and
the app-action table kept as indicated):

> Status: **Likely**, from real-car logs of one vehicle (`14-lib-reading-state.md` B3.5, B5). It was not reproduced in a
> controlled scenario, and the mechanism is unknown.
>
> While another BLE client polled the vehicle's Infotainment domain, its replies reached our link (B3.5) and some of our
> requests got no reply at all. In captures without that traffic, none were lost:
>
> | Capture | That client's Infotainment frames on our link | Own requests | Unanswered |
> |---|---:|---:|---:|
> | Parked, client polling about once per second | 1690 | 53 polls | 47 `listKeys()` `Uncertain(Timeout(afterSend = true))`; `vehicleStatus()` and `keySummary()` 106 of 106 `Success` |
> | Parked, client polling every 2.1 s | 28 | 134 | 12 while that client was active (11 VCSEC, 1 Infotainment) |
> | Without such a client, three captures (parked and driving) | 0 | 37 polls, 830, 715 | 0 |
>
> - The lost requests fall near the other client's own requests, so the timing depends on its polling rate. With a client
>   polling about once per second, 45 of 47 lost requests were sent 0.55 to 0.85 s after its latest frame. With a client
>   polling every 2.1 s, 9 of the 10 interruptions longer than 3 s in its reply stream contained one of our lost requests and
>   none of its 17 normal intervals did. Send times are inferred from the previous reply (no transmit lines).
> - Every kind of request can be lost: `listKeys()` most (eight requests in one 5 s budget), but also `vehicleStatus()` and
>   `keySummary()`, the status read before a `getState`, and an Infotainment `getState` (1 of 9 sent during that traffic).
> - A lost VCSEC request also delays the next VCSEC calls of the connection until its deadline (§Retries and timeouts).
> - No late reply arrived for any lost request, so a longer `timeout` is not expected to help. The link stayed up.
> - Encrypted, empty-body VCSEC replies of 83 B to another routing address (B3.5 first row, the form of a reply to another
>   key's command) are a different thing: they arrive about every 1 to 2 minutes in every capture, including those without
>   any loss. Do not treat them as a sign of this traffic.
> - No command was sent during this traffic, so whether a command stalls the same way is unknown.
> - Requests can be lost for other reasons too, for example in a silence of the whole link while the vehicle slept (3
>   requests in one capture, before any other client appeared). This section describes only the pattern tied to another
>   client.

The app-action table that follows stays as it is.

### New items

| # | Item | Evidence | Proposal | Owner, priority |
|---|---|---|---|---|
| H17 | The session cache holds the counter from before the last authenticated request | F21: `startSession` stores even for a `Ready` domain and runs before the request; an unclean exit (here a user force quit, U5) leaves the cache one or more counters behind; the next first request draws `INVALID_TOKEN_OR_COUNTER` and costs about 1.2 s | Options, decision for `library-developer`, in the order agreed with Codex: (d) **authenticated resync**: before the first authenticated request on a session restored by a new process, request fresh session info (verify the challenge-bound HMAC and apply it before sending); one round trip per process and domain instead of about 1.2 s when stale, keeps session reuse but gives up the cache's zero-round-trip start; simplest; (b) **durable reserve-ahead**: persist `counter + R` before using counters up to it, per key, domain and epoch, never wrap, handle exhaustion explicitly (the vehicle's window accepts forward jumps, Go `window.go`, but a large jump can invalidate older outstanding requests, and reaching `0xFFFFFFFF` does not make the vehicle rotate its epoch; the signer just stops); no extra round trip, more machinery; choose it first if the extra round trip is unacceptable; (a) store after verified authenticated replies, coalesced: narrows the window only; (c) document only. Under exclusive use of the key both (d) and (b) prevent reuse; neither handles another signer using the same key. **Keep** the store in `startSession` for a `Ready` domain unless another trigger replaces it: it is today's only checkpoint between handshake and disconnect, and removing it alone would widen the window. Any of (a), (b), (d) changes behaviour against Go (whose CLI stores at exit) and deserves a short ADR. Whatever is chosen, document in `19-lib-architecture.md` §Session cache and `17-lib-platform-notes.md` §Session cache: "If the app process ends without `disconnect()` (force quit, the system ending a suspended app, a crash), the first authenticated request of the next process may be refused with `INVALID_TOKEN_OR_COUNTER` and fresh session info; the library applies it and resends, which adds about 1.2 s. The result is still `Success`; the refused copy did not run." Add a troubleshooting row in `18-lib-troubleshooting.md` beside the `TIME_EXPIRED` row | `library-developer`, Medium (fix), High for the documentation sentence |
| H18 | B3.1 field 8 and the repeat | F26 | B3.1 field 8 row: replace "sub-field 2 was 1 in all 75 frames in `DRIVE`, `REVERSE` or `NEUTRAL`" by "… in all 95 frames …" and add: "Sub-field 1 is usually 2 in gear, but it dropped to 1 for 2 to 7 s in 3 of 20 in-gear frames on two drives with the phone inside the car (2026-11-14)". Add today's 26 frames to the table: `DRIVE`/`REVERSE`/`NEUTRAL` `{2,1,1}` 75 → 92 and `{1,1,1}` 0 → 3; `PARK`, `PRESENT` `{2,1,1}` 28 → 30; `PARK`, `NOT_PRESENT` `{2,2,1}` 1 → 2, `{1,2,1}` 8 → 9, `{1,1,1}` 33 → 35 (the 17 frames of 11-13 F19 can be added the same way). Add: "Changes of sub-field 1 often come within a few seconds of an encrypted, empty-body 83 B VCSEC reply to another routing address (B3.5 first row): 15 of 69 such replies were within 4 s of a change, about six times what a uniform-time baseline predicts (Likely as a descriptive association, uncontrolled). Meaning still unknown; that it reflects the car localising an authenticated key is a candidate." Repeat bullet: "47 gaps of 59.9 to 60.15 s (44 in `DRIVE`, 3 in `PARK`)" → "54 gaps … (51 in `DRIVE`, 3 in `PARK`)" | `library-developer` (MD), Low |
| H19 | B3.1 / B2.4 lock and walk-away link loss | F24, F26 | B3.1 lock row: "`INTERNAL_LOCKED` became `UNLOCKED` in the same frame as `PARK` (6 of 6)". Walk-away: "`UNLOCKED` to `LOCKED` when walking away with the connection still up; the link was lost 11.7 to 27.4 s after that lock in six walk-aways" instead of "about 30 s later" | `library-developer` (MD), Low |
| H20 | iOS background behaviour seen on a real iPhone | F24, F25, 11-13 F18, build 18 F3 | `17-lib-platform-notes.md` §iOS background use by the sample: replace the "unconfirmed" bullet with observations (Likely, sample only, one iPhone): "A link that stays up keeps working in the background with the phone locked: every broadcast was delivered. iOS suspends the app between BLE events, so app timers (a poll) run intermittently; late polls started together with incoming frames, and while driving the vehicle's 60 s status repeat gave recurring chances to run. After a link loss in the background the reconnect scan by device name made no progress until the app was brought to the front or the phone unlocked (three captures, up to 6 h 49 m; once for 90 s with the user standing next to the car). Reconnecting in the background is therefore not supported yet (#126)." | `library-developer` (MD), Medium; #126 owner for the fix |
| H21 | Sample polls once after the loss notification | F24: 3 `Failure "vehicle not connected"` between `disconnected` and `backoff` | Cancel the poll task when `watchDisconnect` sees `Disconnected` (or check `connection` before each poll call), so the log does not show failures that are not query results. Harmless today | `ios-developer`, Low |
| H22 | No signal strength while connected | F24: "RSSI before the loss" could not be answered | `library-api-request`: an optional `Vehicle` call to read the connected link's RSSI (both platforms offer one), no values beyond dBm, for the observation log; the sample would log it every poll. Not needed for apps; analysis only | `library-api-request` → `library-developer`, Low |
| H23 | Connect scan observation is now observed on a real car | 11-13 and 11-14: four iOS connects with `scanHitMs`, `rssi`, `openAttempt` and matching `[connect:scan-hit]` lines (104 ms / -82 dBm; 51 / -49; 193 / -49; 79 / -57; `openAttempt=1` each) | `11-lib-overview.md` status row for FR-038: "real-vehicle check pending" → "checked on a real iPhone (four connects, 2026-11-13/14); Android not checked" | `library-developer` (MD), Low |
| H24 | A8.5 still says `getState` ran only against FakeVehicle | 11-07 F1, 11-13 F10, F22 | Replace the first two bullets of `14-lib-reading-state.md` A8.5 with: "`getState` answered and verified on one real car (iOS, firmware not recorded) for 11 of 12 categories: `DRIVE`, `MEDIA`, `MEDIA_DETAIL` while driving and parked; `LOCATION`, `TIRE_PRESSURE`, `PARENTAL_CONTROLS` parked and driving; `CHARGE`, `CLIMATE`, `SOFTWARE_UPDATE`, `CHARGE_SCHEDULE`, `PRECONDITIONING_SCHEDULE` parked, once each. `CLOSURES` has not been answered yet (one request, lost while another client was active). No reply was larger than 436 B and none drew a size fault." and "Which fields arrive can depend on the vehicle state: in one parked capture `LOCATION` came without `gps_as_of` and `TIRE_PRESSURE` with the four `tpms_last_seen_pressure_time_*`; in one driving capture, the reverse. Only fields the sample maps were checked." Keep the bullets on `speed` units, `timestamp`, wake and size limits; add "Repeated requests: 22 `LOCATION` requests 0.9 to 3 s apart were all answered with the same field set; no rate limit was seen at that rate." | `library-developer` (MD), Medium |

No library crash or protocol violation. The library behaved as documented: every reply was matched, the refused cached
session was recovered with one resend, `WAIT` was resent after about 1.08 s, the three calls after the link loss failed
before any write, and no request was written twice.

## Next scenarios

**N10. Stale cache counter (F21, H17).** Car parked and awake, other apps off, app in the foreground.
1. Mark `cache A`. Tap `Drive` three times. Force-quit the monitor app (swipe up) while still connected. Relaunch, wait for
   the connection, mark `relaunch A`, tap `Drive` once. Expected under F21: `INVALID_TOKEN_OR_COUNTER` with `session_info`,
   then `gcm-ok`, about 1.2 to 1.5 s.
2. Mark `cache B`. Tap `Drive` three times, press the app's disconnect (or walk away until the link drops and the loop calls
   `disconnect()`), then force-quit. Relaunch, mark `relaunch B`, tap `Drive` once. Expected: no fault, about 0.3 s.
3. Repeat both on another day. Two A/B pairs confirm or refute the mechanism. Today's run, with the user's force quit
   (U5), was an unmarked "A" once; it does not count toward the controlled pairs.

**N11. #124 L1 with on and off blocks on the same day (11-13 N6, still open).** Today supplies an "off" block only. Parked,
car awake, poll only; 5-minute blocks `client off`, `client on (same phone)`, `client off`, `client on (second phone)`, each
with a mark and the other app's polling setting written down.

**N12. Field 8 and other keys (F26, #124 L5).** Both keys the user carries (U3) matter here. Apple Watch off (or out of
range) and the Tesla app force-quit for one block, both on for another, and if possible one block with only the watch and
one with only the Tesla app; in each, stand at the driver door, the passenger door, the rear and the front for 20 s and sit in the
seat for 20 s, with a mark per position. Count sub-field 1 changes and 83 B `other` frames per block. If sub-field 1 stops
flapping and the 83 B frames disappear with the other keys away, the key-localisation reading gains.

**N13. Link loss after parking (F24).** After a drive: mark `park`, stay seated 60 s with the monitor in the foreground; mark
`app switch`, open another app for 60 s while seated; mark `door open`, step out and stand at the open door 60 s; mark
`door closed`, stand next to the car 60 s; then walk away. If the link drops before walking away, note in which block, and
which other apps were in front. If something auto-connects on the phone when you park (U2), add a mark `auto-connect`
with what connected, without naming places.

**N14. #124 L2 (background reconnect).** As in the README, unchanged from 11-13 N8; today's first loss, with the user next
to the car for the whole 90 s (U2), is an uncontrolled case of it.

## Hand-offs

- **`library-developer`**
  - Manual items with final wording: H9 (§H9 text), H10, H14 (A8.2, B2.4), H18 (B3.1 field 8, repeat), H19 (B3.1 lock and
    walk-away), H20 (`17-lib-platform-notes.md` background), H23 (`11-lib-overview.md` FR-038), H24 (A8.5), and the H17
    documentation sentence (`19-lib-architecture.md`, `17-lib-platform-notes.md`, `18-lib-troubleshooting.md`).
  - Decisions: H17 (store timing; ADR), H8 with the 2.5 s threshold (#125; ADR), H16 / FR-105 with F21 as evidence.
  - No mismatch between the manual and Go found in this run. One library detail differs from Go by design and matters
    here: the store in `startSession` for a `Ready` domain (Go has no equivalent; its CLI stores at exit).
- **`library-api-request`**: H22 (connected RSSI read), optional.
- **`ios-developer`**: H11 additions (Key & Control Infotainment controls lines), H12, H15, H21; README rows for N10, N12,
  N13.
- **`android-developer`**: none.
- **User**: answers received 2026-11-14 and folded in as U2 to U6 (§Data). Still open, optional: what the "auto-connect"
  at 08:09:21 was (U2), if you remember; the firmware version when you can read it from the car screen (U6); for the next
  runs, marks as in N10 to N13.

## Codex

Consulted through the Codex discussion MCP (`gpt-6.1-sol`, effort high), two rounds, on branch
`feat/collect-monitor-logs-skill` at `9ad0451` (Codex confirmed branch and HEAD; read-only, no files edited). Codex reread
both logs, `VehicleSession.kt`, `SessionCacheSync.kt`, the sample's `startSession` call sites, the Go signer, verifier and
window code, the older recordings, and recomputed the counts.

| Point | Claude's position | Codex's view | Outcome |
|---|---|---|---|
| F20 | Zero loss; 7 frames are other keys' signed replies; uncontrolled "off" block supporting F12 | Counts reproduced (201 + 464 + 50, contiguous, one reply each). Say "no observed unanswered request"; the 83 B frames are replies to other routing addresses whose tags we cannot verify, and the log does not identify a key. F12 association Likely, mechanism Unknown | Adopted |
| F21 mechanism | Cache stored only at handshake, session-info update, disconnect; the counter came from the 11-13 store | **Correction:** `startSession` stores on every success, also for a `Ready` domain, and the sample calls it before each request, so file 1 checkpointed during its own run; the 11-13 attribution was unsupported. The central hypothesis survives (the cache lags the last request). Add alternatives (persistence failure, restore defect, another signer with the same key), "conditional on a genuine fault", "only exits that leave a stale counter", 1.2 s as an expectation | Adopted; F21 rewritten around the store-before-request pattern |
| H17 options | Reserve-ahead first, then resync, coalesced store, document; drop the `Ready` store | (d) authenticated resync first for simplicity, then (b) reserve-ahead (first if the round trip is unacceptable), (a), (c). "Only reserve-ahead prevents reuse" is too strong: resync does too under exclusive key use. Keep the `Ready` store unless replaced, since it is today's only checkpoint. Reserve-ahead needs atomic persistence, per key/domain/epoch allocation, no wrap, explicit exhaustion; reaching `0xFFFFFFFF` does not rotate the epoch | Adopted (H17 order and caveats) |
| F26 | 24 in-gear frames, 21/3; 12 of 62 vs 2.0 expected over about 5830 s | **Correction:** 20 in-gear frames (17/3). Codex counted 5305 s; the difference is one 523 s connection ending with `stopped`, which Claude counts (expectation 2.0, or 2.2 without it). The uniform-time null is descriptive only; both event kinds cluster; a within-connection, state-stratified shift test would be stronger. Association Likely (descriptive), meaning Unknown; enrolled form factors do not show which devices were present | Adopted; counts corrected (repeats 7, not 8, found while rechecking) |
| F24 | First loss with the key holder probably still at the car | Delayed locking does not establish proximity (exit time, which key, lock conditions unknown); a missing `protectedData` line does not prove the phone was unlocked; first loss Unknown, second Likely walk-away; driving exposure is a negative observation, not proof that the other app caused earlier losses | Adopted |
| F23 / H8 | 2.5 s threshold minimum; one resend | Call the latency "inferred sequential-chain timing" and state the sample selection; 2.5 s is an experimental threshold, not a minimum; "at most one useful resend", less after mutex waits; note the 1495 ms is outside the 262 to 590 ms range | Adopted |
| F25 | Polls ran only when a frame resumed the process | "Only" is false: rounds at 07:19:32.120 and 07:20:44.516 ran on time; at 07:22:05 the phone unlock coincides, so the wake source is not unique. Say intermittent, clustered around incoming traffic | Adopted |

Agreed: every finding and status above. No disagreement remains. Open points both sides flagged for the user: how the
07:22 process ended (force quit or left in the background) and which keys and apps were present (Tesla app, Apple Watch).

**User answers received after the discussion (2026-11-14):** force quit (U5), both keys carried (U3), next to the car until
about 08:11 with an unspecified auto-connect at 08:09:21 (U2), Key & Control buttons pressed (U4), firmware unknown (U6).
They confirm conditions, not mechanisms. Status changes: none for F20, F21, F22, F23, F25, F26; for F24 the first loss stays
Unknown but distance is now unlikely by the user's account, and the background freeze is now a case with the car in reach.
Not re-discussed with Codex: no status moved across a level.
