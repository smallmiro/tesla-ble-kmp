# #118 sleeping-car `getState`: two experiment runs (2026-11-05)

Author: `protocol-analyst`. Skill: `analyze-monitor-logs`. Repository state: `main` at `b68301c`.
Plan: `docs/superpowers/plans/2026-11-01-118-state-diagnostics.md` Task 3. Policy: `docs/adr/0015-reply-authentication-policy.md`.
This is an analysis report. Nothing here changes the library, the sample or the manual. `library-developer` decides what
enters `14-lib-reading-state.md` (A8.5, Part B), ADR-0015 and the PRD.

**Summary.** The car stayed `ASLEEP` in both runs, so only S1 (asleep case) and S10/T5 were exercised: 6 experiment taps,
6 `Uncertain(Timeout(afterSend = true))` after 5.02 to 5.11 s. Each tap whose attempt number can be seen (4 of 6) registered
exactly one request, the `getState` itself: no Infotainment handshake and no protocol retry. The Infotainment session was
therefore already `Ready` without a handshake on that connection, which in this code means restored from the session cache
(Likely; the session state is not logged). No Infotainment frame addressed to us arrived during any 5 s budget or for the rest of either connection. The one
Infotainment frame in these two logs, a `WAIT` at 06:30:34.624, is addressed to a routing address that is not ours and is not a
late answer to our requests. No wake was seen: every `vehicleStatus` read 9 to 47 s after a tap still said `ASLEEP`, and no
`Status` broadcast came. The lost `keySummary` and the two link losses have no demonstrated connection to the taps. Nothing
about an **awake** car was collected, so the ADR-0015 questions (our `auth=` on a real reply, `WAIT` versus final reply on a
signed command) and the size and session mismatch rows stay **미관찰**. That the silence was caused by sleep, and not by a
cached session the car no longer accepts, needs an awake control with the same session (N1 step 6).

## Data

| Item | Value |
|---|---|
| Sessions analysed | `monitor-20261105-061447.jsonl` (morning, M), `monitor-20261105-200728.jsonl` (evening, E), both in `samples/logs/ios/2026-11-05/` (git-ignored) |
| Context only | `monitor-20261104-222530.jsonl` (build 22, 29 lines, scan timeouts only, no connection), `monitor-20261105-060452.jsonl` (build 22, 13 lines, same) |
| Phone | iPhone 16 Pro (`iPhone17,1`), iOS 27.2.0 |
| App | Monitor app `0.5.0(23)` (has the #138 `[reply:*]` lines with `attempt=` and `auth=`) |
| Car | Assumed to be the car of Part B B5 (Model Y), same phone; firmware and model **not recorded** in this run; VIN not quoted |
| Clock | Device clock, KST (+09:00) |
| Scenario | User report: car `ASLEEP` in both runs, normal buttons off, only **send to a sleeping car (experiment)** used. M: `MEDIA`, `MEDIA_DETAIL`, `DRIVE`. E: `DRIVE`, `MEDIA`, `MEDIA_DETAIL`. No `mark` lines in either file |

What the log can and cannot show (`samples/documents/md/04-monitor-app.md` §Log format, `18-lib-troubleshooting.md`
§Observation log):

- BLE `TX:`/`RX:` lines are never written. There is no send line. A request becomes visible only through its attempt
  number, which appears on the `[reply:*]` line of its own reply or, by its absence, as a gap between later numbers.
- `attempt=` is one counter per connection for both domains. It is incremented when the dispatcher registers a request,
  before the transport write (`Dispatcher.register`, `Dispatcher.kt:565-574`). An Infotainment handshake registers a new
  request every `retryInterval` (1 s) until it gets an answer or `handshakeTimeout` (20 s) ends (`HandshakeFlow.kt:38-90`).
- `afterSend = true` is set after the write returned. Writes are with response, so the GATT write completed (build 18
  report F1). It does not show that the vehicle's application layer processed the request.
- A transport-level resend after a retriable write error reuses the same attempt number (`Dispatcher.sendWith` →
  `transmit`). So a single attempt number proves one **registered** request, not exactly one radio transmission.
- The experiment path skips `vehicleStatus()` entirely (`StateDiagnostics.swift` `execute()`, `if !ignoringSleep`), so
  there is no sleep read at tap time; before and after states come from the monitor's own poll.
- Values never reach the log (FR-112); the `getState` query lines hold `call`, `result`, `summary`, `startedAt` only.

## Inventory

| | M | E |
|---|---|---|
| Span | 06:14:47.612 to 06:35:34.961 | 20:07:28.827 to 20:30:04.326 |
| Lines | 155 | 94 |
| `app` / `link` / `observe` / `query` / `event` | 27 / 13 / 89 / 21 / 4 | 31 / 7 / 42 / 12 / 1 |
| Connections | 1: 06:29:12.320 to 06:31:18.694 (126.4 s), RSSI -64 dBm, scan hit 85 ms | 1: 20:07:28.898 to 20:09:12.121 (103.2 s), RSSI **-92 dBm**, scan hit 28 ms |
| Before the connection | 3 scan timeouts 06:14:47 to 06:29:11 (no advertisement) | none |
| After the loss | no advertisement 06:31:20 to 06:35:33, then one more attempt, file ends | no advertisement 20:09:14 to 20:30:02, then one more attempt, file ends |
| `vehicleStatus` | 6 Success, **all `sleep=ASLEEP`** | 3 Success, **all `sleep=ASLEEP`** |
| `keySummary` | 5 Success, **1 Uncertain** (line 103) | 3 Success |
| `listKeys` | 6 Success | 3 Success |
| `getState` (experiment) | 3 Uncertain `Timeout(afterSend=true)` (lines 96 to 98) | 3 Uncertain `Timeout(afterSend=true)` (lines 64, 80, 81) |
| `[reply:vcsec]` | 78 (3 of them `WAIT`) | 39 (1 `WAIT`) |
| `[reply:infotainment]` | **0** | **0** |
| `[drop:no-handler]` | 6: 5 x 83 B VCSEC `other`, 1 x 47 B Infotainment `WAIT` `other` (line 120) | 1: 83 B VCSEC `other` |
| `to=address(16B, self)` or `closed` | **0** | **0** |
| `[drop:to-domain]` = events | 4 = 4 `Unrecognized` (field 53, 54 B) | 1 = 1 `Unrecognized` (field 53, 220 B) |
| `Status` broadcasts | 0 | 0 |
| Highest attempt seen | 82 | 40 |

Attempt numbers missing from `[reply:*]` lines: M 41, 52, 53, 55; E 30 (and anything after 40, which no later reply can
reveal). No raw reply bytes exist in these logs (observe lines are summaries), so `pbwire.py` had nothing to decode beyond
the field 53 broadcasts, which the observe lines already show decoded.

### Timeline around the taps

| Time | Line | What |
|---|---|---|
| M 06:29:23.344 | 64 | `vehicleStatus` `ASLEEP` (attempt 30) |
| M 06:29:25.535 | 75 | attempt 40, end of a poll cycle (`listKeys` Success, line 76) |
| M 06:29:53.634 | 96 | **MEDIA** tap starts (`startedAt`) |
| M 06:29:55.896 | 83-84 | poll `vehicleStatus` `ASLEEP` (attempt 42) while MEDIA waits; poll 42 to 51 all answered |
| M 06:29:58.675 | 96 | MEDIA `Uncertain`, 5041 ms |
| M 06:30:06.432 / 06:30:11.459 | 97 | **MEDIA_DETAIL** start / `Uncertain`, 5027 ms |
| M 06:30:13.712 / 06:30:18.739 | 98 | **DRIVE** start / `Uncertain`, 5027 ms |
| M 06:30:27.730 | 101-102 | `vehicleStatus` `ASLEEP` (attempt 54) |
| M 06:30:27.730 to 06:30:32.733 | 103 | `keySummary` attempt 55: no reply, `Uncertain`; no frame of any kind in these 5 s |
| M 06:30:32.823 to 06:30:35.437 | 104-122 | two interleaved `listKeys` (attempts 56 to 72); `WAIT` on attempt 71 (line 119) |
| M 06:30:34.624 | 120 | `[drop:no-handler] 47B from=domain(DOMAIN_INFOTAINMENT) to=address(16B, other) … OPERATIONSTATUS_WAIT` |
| M 06:30:35.957 | 123 | phone locks (`protectedData unavailable`), app to background at 06:30:36.082 |
| M 06:31:05.626 | 131-132 | `vehicleStatus` `ASLEEP` (attempt 73), poll 73 to 82 all answered |
| M 06:31:18.694 | 147 | link lost, "bluetooth connection lost" |
| E 20:08:07.435 | 45 | `vehicleStatus` `ASLEEP` (attempt 20) |
| E 20:08:28.037 / 20:08:33.143 | 64 | **DRIVE** start / `Uncertain`, 5107 ms |
| E 20:08:50.273 | 67 | `vehicleStatus` `ASLEEP` (attempt 31), poll 31 to 40 all answered |
| E 20:08:55.424 / 20:09:00.451 | 80 | **MEDIA** start / `Uncertain`, 5027 ms |
| E 20:09:02.408 / 20:09:07.430 | 81 | **MEDIA_DETAIL** start / `Uncertain`, 5022 ms |
| E 20:09:11.335 | 82 | app `inactive` |
| E 20:09:12.121 | 83 | link lost, "bluetooth connection lost" |

## Findings

### F1. What went on the wire for each tap: one registered `getState`, no handshake, no retry, no reply — Confirmed (bounded observation); cause Likely

**Claim (Confirmed, bounded).** On this car (firmware unrecorded), 6 of 6 experiment taps after `ASLEEP` reads ended
`Uncertain(Timeout(afterSend = true))` after 5.022 to 5.107 s, with no own Infotainment reply and no `self` or `closed`
frame. Every tap whose attempts can be counted registered one `getState` request, no handshake request and no protocol
retry.

**Claim (Likely).** The silence is because Infotainment was asleep. The alternative, a cached session that the car no
longer accepts, is not excluded: an awake car would be expected to answer such a session with a fault and session info,
but that has not been seen for Infotainment on this car. An awake control with the same cached session settles it (N1
step 6).

**Claim (Likely, strong).** The session was `Ready(fromCache = true)`. It follows from the code and from an attempt trace
that is complete from attempt 1 of each connection, but the session state itself is not logged.

**Denominators.**

| | M | E | Total |
|---|---:|---:|---:|
| User taps (query lines) | 3 | 3 | 6 |
| Results | 3 `Uncertain(Timeout(afterSend=true))` | 3 `Uncertain(Timeout(afterSend=true))` | 6 |
| Taps whose attempts can be counted | 3 (41; 52 and 53) | 1 (30) | 4 |
| Registered requests per counted tap | 1, 1, 1 | 1 | 4 requests for 4 taps |
| Handshake requests | 0 | 0 | 0 |
| Retries (extra attempt numbers) | 0 | 0 in the counted tap | 0 |
| Infotainment frames to our address (`[reply:infotainment]`, `self`, `closed`) | 0 | 0 | 0 |
| Duration | 5041, 5027, 5027 ms | 5107, 5027, 5022 ms | 5022 to 5107 ms |

**Evidence.**
- M: the gap between attempt 40 (06:29:25.535, line 75) and 42 (06:29:55.896, line 83) holds only 41, and MEDIA is the one
  tap in it. The gap 51 (06:29:56.792, line 94) to 54 (06:30:27.729, line 101) holds 52 and 53, and MEDIA_DETAIL and DRIVE
  are the two taps in it. Attempt 55 is the poll's `keySummary` (F4).
- E: the gap 29 (20:08:08.275, line 56) to 31 (20:08:50.272, line 66) holds only 30, the DRIVE tap. MEDIA and MEDIA_DETAIL
  come after the last reply line (attempt 40, line 78), so their numbers are not visible.
- A handshake is excluded for all 6 taps, also the 2 uncounted ones. Answered, it would leave a `[reply:infotainment] …
  session_info=…` line (none). Unanswered, it would register a new request every 1 s and end the tap as **NotSent**
  (`sessionFailed`) after 20 s (`HandshakeFlow.kt:38-90`, `MonitorEngine.swift` `ensureInfotainmentSession`). Every tap
  ended `Uncertain` from `getState` in about 5 s.
- The session was therefore `Ready` before the first tap of each connection. On these connections every attempt before
  the first tap (M 1 to 40, E 1 to 29) has a VCSEC reply line, so no Infotainment handshake ran earlier on the connection,
  and no Infotainment frame addressed to us (session info included) arrived at any time. The only remaining source of `Ready` is the
  restore at connect, `Ready(fromCache = true)` (`DefaultVehicle.kt:57`). When and on which build that cached session was
  made is not in these logs. Whether the car would still accept it is unknown (no reply means no verdict): **미관찰**.
- The 5.02 to 5.11 s durations fit the 5 s `commandTimeout` plus app overhead. On their own they would not exclude a quick,
  answered handshake; the missing `session_info` line does.
- The `getState` path did not interfere with VCSEC: the M poll at 06:29:55.797 ran while MEDIA was waiting and got 10 of
  10 replies (lines 83 to 95).

**Counterexamples.** None in these logs.

**Not checked.**
- Radio-level transmission count: one attempt number allows transport resends after retriable write errors (see Data).
  Nothing suggests one, but the log cannot exclude it.
- Firmware, model: not recorded.
- Whether an Infotainment reply was lost before the dispatcher (BLE inbox overflow is logged at `ERROR`, which the monitor
  does not keep). With at most one poll of traffic in those seconds this is unlikely, not excluded.
- The tap count is taken from the query lines. A second press while a query runs does nothing (S7) and leaves no line.

**Status reasoning.** S10 is a controlled scenario and it was reproduced in two separate runs (6 taps, 4 with counted
attempts), so the bounded observation meets the Confirmed rule. It is limited to what was measured: an `ASLEEP` car, a
`Ready` (cached) session, the default 5 s, one car with unrecorded firmware. It says nothing about a longer budget, an
awake Infotainment, or another firmware. The causal reading stays Likely until the awake control exists. The observation
matches the prediction already in `14-lib-reading-state.md` A8.2 ("Infotainment asleep: sends one request, gets no
reply") and `16-lib-errors.md` §`getState` results. For the A8.5 item "what a sleeping Infotainment does with a request":
on this car no answer came within 5 s and no busy answer reached us (no `WAIT` to our address, no retry), so the
`Failure(Timeout(afterSend = false))` busy path of A8.5 was not seen.

### F2. The 06:30:34 Infotainment `WAIT` is not ours — established (not addressed to us); another client Likely; identity Unknown

**Claim.** The frame at M line 120 is not addressed to any of our three `getState` requests. It most likely answers another
client's Infotainment request.

**Evidence.**
- Addressing. All our Infotainment requests on a connection use the one per-connection routing address
  (`Dispatcher.kt:124`, `:411`). A frame to that address reads `closed` when its key (address plus `request_uuid`) is in
  the closed history and `self` otherwise, **also after the request has left the history** (`ownerOf`,
  `observeNoHandler`, `Dispatcher.kt:189-218`). A `request_uuid` or domain mismatch cannot change that. `other` therefore
  means the routing address is not ours (and not that of an open VCSEC request). The `request_uuid` value is not logged
  (`16B` only) and is not needed.
- Only abnormal paths could make a reply to us read `other`: an address of an earlier dispatcher, a vehicle reply sent to
  the wrong address, or corrupted traffic. The M connection is the only connection of that run, no earlier log on hand
  contains a `getState` call, and the Key & Control tab's `listKeys` share the same attempt counter (attempts 56 to 72
  interleaved), so there is one dispatcher per connection. Nothing supports these paths.
- History, as a cross-check only: attempts 41, 52 and 53 closed at 06:29:58.675, 06:30:11.459 and 06:30:18.739 and were
  still among the 32 most recent closed requests at 06:30:34.624 (31 entries from 41 to 71), with no disconnect in
  between. A late answer would have read `closed attempt=41|52|53`.
- Timing. 15.9 s after the last of our requests closed and 36 s after the first; the vehicle does not use our timeout, so
  timing alone would not exclude it, which is why the addressing argument carries the weight.
- Company. 272 ms after our own VCSEC request got a `WAIT` (attempt 71, line 119, during an overlapping-`listKeys` burst,
  F5). Other-client VCSEC replies (83 B, signed, `other`) followed at 06:30:38.802 and 06:30:41.561 (lines 127, 128); these
  match B3.5 "Replies addressed to other clients".
- It is the first Infotainment-domain `WAIT` in the 33 distinct monitor logs on hand. The other 11 `WAIT` frames (7 earlier,
  4 in this run) are all VCSEC replies to our own requests (F5).

**Who sent the request.** The logs do not identify the client. Possibilities: the user's other BLE app on this iPhone
(memory note: it polls Infotainment at about 1 Hz while driving; here there is a single frame, not a stream), the official
Tesla app acting as phone key on this iPhone (iOS shares one LE link between apps, so its traffic reaches our
characteristic), or another device near the car. The phone was unlocked and the app in the foreground until 06:30:35.957.

**Side reading (Unknown).** An Infotainment `WAIT` to another client while the car stayed `ASLEEP` (06:31:05.626) suggests
that some request paths on a sleeping car do get a busy answer. Ours did not. What that client asked, and whether it
then woke the car, is not visible.

**Counterexample.** None. Besides the abnormal paths above, it could be ours only if the address classification were
wrong; the rule is unit tested (`foreignAddressStaysOther`, `waitThenLateReplyShareTheAttempt` in
`DispatcherObservationTest`) and is consistent with every other `other` line in the logs.

### F3. `getState` did not wake the car within the observed windows — Likely

**Claim.** None of the six taps woke the car within 9 to 47 s.

**Evidence.**

| Tap | Last `vehicleStatus` before | First `vehicleStatus` after the result |
|---|---|---|
| M MEDIA (06:29:53.6 to 58.7) | 06:29:23.344 `ASLEEP` (30 s before); also 06:29:55.896 `ASLEEP` during the wait | 06:30:27.730 `ASLEEP` (+29 s) |
| M MEDIA_DETAIL (06:30:06.4 to 11.5) | 06:29:55.896 `ASLEEP` | 06:30:27.730 `ASLEEP` (+16 s) |
| M DRIVE (06:30:13.7 to 18.7) | 06:29:55.896 `ASLEEP` | 06:30:27.730 `ASLEEP` (+9 s), 06:31:05.626 `ASLEEP` (+47 s) |
| E DRIVE (20:08:28.0 to 33.1) | 20:08:07.435 `ASLEEP` (21 s before) | 20:08:50.273 `ASLEEP` (+17 s) |
| E MEDIA (20:08:55.4 to 09:00.5) | 20:08:50.273 `ASLEEP` (5 s before) | none: link lost 20:09:12.121 |
| E MEDIA_DETAIL (20:09:02.4 to 07.4) | 20:08:50.273 `ASLEEP` | none |

- No `Status` broadcast arrived in either connection. B4 notes that a waking car pushes frames; none came in the 60 s
  between the last M tap and the M link loss.
- `vehicleStatus` reads VCSEC's view of Infotainment sleep, so "`ASLEEP` 9 to 47 s later" is the observable, not the
  car's screen.

**Counterexamples.** None.

**Not checked.** 2 of 6 taps have no after-read. A slow wake (over 47 s) is not excluded. The user did not record
whether the screen lit. The windows are short and the experiment path takes no sleep reading itself, so this stays
Likely even though S10 ran twice.

### F4. The `keySummary` timeout and the two link losses: no demonstrated connection to the taps — Likely; link-loss causes Unknown

**`keySummary` (M line 103).** Attempt 55 was registered at 06:30:27.730, right after the `vehicleStatus` reply of attempt
54 (116 ms round trip), and no frame of any kind was logged for the next 5.0 s. The message is "timed out waiting for vehicle
response" (`Timeout(afterSend = true)`). No late reply followed: no `closed attempt=55` and no plaintext `whitelistInfo`
drop. Then two `listKeys` ran interleaved (attempts 56 to 72, each request twice): the poll's own and the Key & Control
tab's automatic enrollment check, which re-runs when that tab appears (`KeyControlModel.tabAppeared`, `checkEnrollment`).
- The taps held nothing open: their three requests had closed 9 to 29 s earlier, and during the MEDIA wait the
  poll got 10 of 10 replies (F1).
- It is the ordinary lost-reply pattern of the build 18 report F1 and `16-lib-errors.md` §Queries while another BLE
  client is active: one request sent, nothing back, no late reply. Another client was active around then (F2: its `WAIT`
  at 06:30:34.6 and its 83 B replies at 06:30:38.8 and :41.6). Too few frames to test F1's timing window.
- Overall VCSEC loss in these runs: 1 of 118 requests (M 79 VCSEC requests, 78 answered; E 39 of 39).

**Morning link loss, 06:31:18.694 (line 147).** 60 s after the last tap closed, 42.7 s after the phone locked (line 123),
5.0 s after the last frame (field 53 broadcast, line 145). The poll at 06:31:05 got 10 of 10. Afterwards the reconnect
scan reported no advertisement after 252 s, a 30 s timer that fired late, so the app was suspended (build 18 F3); that shows
suspension, not that the car was out of range the whole time. The file ends without a new connection. The shape resembles
the walk-away losses of build 18 F4 (loss some tens of seconds after the person stops using the car or phone), but nothing
in the log shows the person moving. Cause Unknown.

**Evening link loss, 20:09:12.121 (line 83).** 4.7 s after the last tap closed and 0.8 s after the app went `inactive`
(line 82). The connection opened at -92 dBm, near the edge of a usable link (the morning link opened at -64 dBm), and the
reconnect timer then fired after 1248 s (again suspension). A weak link that ends when the user moves or puts the phone away
is the simplest reading, but a 4.7 s gap after a tap cannot be separated from the tap with one event. Cause Unknown.
Counter-indication for a tap effect: the morning link survived 60 s and a full poll after three taps.

### F5 (side, not a #118 question). VCSEC `WAIT` correlates with dense request bursts — Likely

**Claim.** On this car VCSEC returns `OPERATIONSTATUS_WAIT` (47 B, `request_uuid` 16 B, no payload, `auth=none`) mostly
inside dense bursts of our requests. This is consistent with throttling or queue pressure. The threshold, the time window
and whether other clients share the capacity are unknown.

**Evidence.** The 9 distinct monitor logs that contain own `[reply:vcsec]` lines (identical copies counted once; builds 13
to 23), bursts split at gaps over 1.5 s between own replies:
- 11 own `WAIT` replies, at burst position 16 (9 times), 17 (once) and 6 (once).
- 14 bursts reached 16 own replies. 10 of them had a `WAIT` (9 at the 16th reply, 1 at the 17th). In 9 of those 10 the
  first 15 replies took 1.47 to 1.83 s; the 17th-position case took 3.2 s on a slow connect. The 4 bursts without a
  `WAIT` were slower: their first 15 replies took 2.7 to 5.2 s.
- 181 bursts stayed under 16 replies; 1 had a `WAIT` (the 6th, during heavy third-party Infotainment traffic, build 18 F1).
- In this run: M attempts 16, 35, 71 and E attempt 16, each the 16th reply of its burst. The bursts were: the connect-time
  poll of 10 requests followed by the Key & Control automatic `listKeys` of 9 (M 06:29:12, E 20:07:29), two poll cycles
  back to back (M 06:29:22), and two interleaved `listKeys` (M 06:30:32). Single polls of 10 requests never got one.
- The resend (new attempt, new VCSEC address) got the data 1.08 to 1.26 s later in all 4 cases here. That delay includes
  our configured 1 s `retryInterval` plus the reply latency; it is not a measured vehicle cooldown.
- Reply times stand in for request times, which are not logged.

**Relevance to ADR-0015.** In the 4 new cases the closed history was available. No final reply to the `WAIT`ed attempt
arrived (no `closed` line, and no plaintext whitelist or status frame among the drops of either file), so for these
**unsigned VCSEC reads** a `WAIT` was not followed by a same-request final reply. The 4 late-reply windows are short
(about 1.1 s before the resend's reply, then until the entry left the 32-entry history or the link dropped). Because the
library resends after about 1 s, these logs also cannot show what the vehicle would do if we kept waiting on the original
request without resending (H6). This does not answer the ADR-0015 question for signed commands (lock, unlock), where
execution and a delayed final reply matter.

**Hardening note.** The sample's connect-time pattern (poll plus automatic enrollment `listKeys`, back to back) makes a
dense burst; both connections here got a `WAIT` in it, costing about 1.1 s each time. See Library hardening H4.

### F6 (side). Field 53 broadcasts precede another client's signed VCSEC reply — Likely (correlation), meaning Unknown

- Here: 4 of 5 field 53 frames were followed by an 83 B signed VCSEC reply to `other` within 0.54 to 0.69 s (M lines 77→79,
  80→82, 129→133; E 32→34). The fifth (M line 145) was 5 s before the link loss.
- All 33 distinct monitor logs on hand (copies with identical content counted once): 14 of 16 field 53 frames were
  followed by such a reply within 0.06 to 0.96 s (median about 0.63 s). Background: 47 such frames in 3848 s connected (0.012 per second), so a frame within 1 s of
  a random instant has a chance of about 1.2 %. That describes the association; it is not a significance estimate, because
  this traffic clusters and varies by connection and vehicle state. A common cause (for example a phone key's
  authentication triggering both) is not excluded.
- New entry ids for 1-entry frames: M has 54 B frames with ids 4, 7, 6 and 1 (B3.3 lists 3, 4, 7). The E frame is 220 B with
  5 entries in the order 1, 3, 6, 7, 4, as in B3.3.
- Reading: field 53 may be tied to another key holder's authenticated VCSEC exchange (a phone key?). Not tested. B3.3
  stays Unknown for the meaning.

## Scenario rows

| Row | Status in these runs | What was seen |
|---|---|---|
| S1 (asleep case) | Observed (user report, not in the log) | Normal buttons off while `ASLEEP` (user). The log confirms `ASLEEP` in all 9 `vehicleStatus` reads. "Not connected", "no key", `UNKNOWN`: 미관찰 |
| S5 (two kinds of `Uncertain`) | Half | Timeout kind 6 times. Size kind (`RESPONSE_MTU_EXCEEDED` on our `getState`): **미관찰** |
| S10 / T5 | Observed twice (planned once) | 6 taps, all `Uncertain(Timeout(afterSend=true))`, 5.02 to 5.11 s, no handshake (6 of 6), no protocol retry (4 counted), no reply to us, still `ASLEEP` 9 to 47 s later (4 taps), no after-read for 2 taps |
| S11 (no values in log) | Observed for the `Uncertain` path | The 6 query lines hold only `call`, `result`, `summary` (`durationMs`, `error`), `startedAt`. The `Success` path: **미관찰** |
| S2 to S4, S6 to S9, S12 | Not run | 미관찰 |
| T1 to T4 (awake car) | Not run | 미관찰 |

ADR-0015 / FR-104 / A8.5 items:

| Item | Status |
|---|---|
| `auth=` of our own Infotainment reply (`gcm-ok` expected) | **미관찰** (no Infotainment reply to us at all) |
| Plaintext (`auth=none`) data on our own `getState` | **미관찰** |
| Reply size per category, `RESPONSE_MTU_EXCEEDED` on our request | **미관찰** |
| Cached session that the car no longer accepts (`Failure(NoSession)`) | **미관찰** (a cached session was used, but an asleep car gives no verdict) |
| Infotainment handshake on a sleeping car (does it answer, wake, or time out) | **미관찰** (no handshake ran, F1) |
| `WAIT` followed by a same-attempt final reply, signed command (T4) | **미관찰**. Unsigned VCSEC reads: 0 of 4 had one (F5) |
| Sleeping Infotainment answers a `getState` with busy | Not seen: 0 of 6 (F1). Another client got one Infotainment `WAIT` (F2) |
| `getState` wakes the car | Not seen within 9 to 47 s (F3) |
| FR-104 "do not infer asleep from silence" | Consistent. Silence coincided with `ASLEEP` 6 of 6, but the same connection also lost a reply on an answering VCSEC (F4), so silence alone still does not identify sleep |
| `speed` unit, field coverage, `timestamp` meaning | **미관찰** |

What this means for the plan's Step 4 (FR-105 start). The collection that ADR-0015 waits for (our `getState` reply with
`auth=`, and `WAIT` versus final reply on a signed command) has **not** happened. Nothing here argues for changing ADR-0015
or FR-104. The `WAIT` decision stays open.

## Library hardening

| # | Item | Evidence | Proposal | Owner, priority |
|---|---|---|---|---|
| H1 | No send-side observation line | F1 had to infer requests from attempt-number gaps; 2 of 6 taps could not be counted, and a transport resend under one attempt is invisible | Metadata-only observe lines that keep three events apart: request registered (`attempt=<N>`, domain, `kind=<request\|session-info>`), transport write invoked (`resend=<k>` for a resend under the same attempt), and write result. No payload, no hex (FR-112). They count software sends, not over-the-air delivery. They would give T4 the `WAIT`-to-final-reply link directly | `library-developer`, Medium |
| H2 | Session origin not in the sample log | F1 deduces `Ready(fromCache = true)` | The sample can log `vehicle.sessions[INFOTAINMENT]` (public API, `14-lib-reading-state.md` §Connection and session state) in the `getState` query line summary, for example `session=ready-cache\|ready-fresh\|started` | `ios-developer`, Medium |
| H3 | Experiment path takes no sleep reading | F3 relies on the poll; 2 of 6 taps have no after-read | Experiment tap: call `vehicleStatus()` before (logged, result ignored) and once 15 to 20 s after the result, and write both sleep values into the summary. The plan's Step 4 wording "같은 순서로" suggests the before-read was intended; `04-monitor-app.md` says "skips only the sleep check" and the code skips the call | `ios-developer` (spec and code), Low |
| H4 | Back-to-back sample queries draw a VCSEC `WAIT` | F5: both connect-time bursts here, plus two other overlaps (double poll, poll `listKeys` with the tab's `listKeys`) | Let the Key & Control automatic check reuse the poll's `listKeys` result, or start it after a short pause, and avoid starting a second poll cycle right after the first. Saves about 1.1 s per occurrence; behaviour already correct | `ios-developer`, Low |
| H5 | Build 18 H1 still applies | F4: one more lost unsigned read with no late reply | No new proposal; extra data point for the existing one | `library-developer`, as before |
| H6 | The `WAIT` question cannot be answered while the library resends after about 1 s | F5: the resend's reply arrives before any late final reply could be told apart; ADR-0015 needs "does a `WAIT`ed request get its own final reply" | A debug-only collection mode in which a `WAIT` does not close and resend the request: keep the original pending request, with its authentication and replay checks, until `timeout`, then `Uncertain`. A verified final reply answers the bounded question; none means "none within the timeout", nothing more. Off by default, never in release; for N3 and T4 only. This is close to the Codex proposal recorded in ADR-0015 and needs that decision anyway | `library-developer` (decision), Medium |

No library defect was found. The library behaved as `14-lib-reading-state.md` A8.2 and `16-lib-errors.md` describe:
one request, no automatic retry after `Uncertain`, no wake.

## Next scenarios

N1 is what #118 still needs. Run it on build 23 or later, ideally with H1 to H3.

**N1. T1 to T4 on an awake car, without the app calling `wakeUp()`.**
1. Before connecting, force-quit the other BLE app and the official Tesla app, and keep other phone keys away from the car
   where practical. If the Tesla app has to stay, write that in a mark. (A force-quit app should not be relaunched for
   Bluetooth events; not verified here.)
2. Wake the car physically: key card on the pillar, open the driver door, sit in, close the door, stay in Park and press the
   brake until the centre screen is on. These are wake stimuli, not proof. Do not use the app's Wake button or the Tesla
   app. If another method was used, mark it.
3. First marks: firmware and model from the car screen (Controls > Software), for example `fw 2026.xx.x Model Y` (no VIN,
   under 80 characters), app build, wake method, other-app state. Then `T1 start` and so on before each block, and the
   result after each trial.
4. Keep the monitor in the foreground and the phone unlocked for the whole run (the morning link ended 43 s after a phone
   lock). Let the connect-time poll and the automatic enrollment check finish, then require a **new** successful
   `vehicleStatus` line with `sleep=AWAKE` in the log before the first tap. Use the **normal** buttons, never the experiment
   button: they read `vehicleStatus` again on every tap.
5. One connection per block. If `link` shows `disconnected`, mark it and restart the block; compare attempt numbers only
   within one connection.
6. **Awake control for F1.** Best done on the same connection as a sleeping-car tap: start with the car `ASLEEP`, connect,
   make one experiment tap (N2), then wake the car physically (step 2) **without disconnecting**, wait for `sleep=AWAKE`,
   and make one normal Drive tap. Same connection, same session, only the sleep state changed. If that is not possible, the
   first `getState` tap after a new connection is a weaker control; then check that every attempt before it has a VCSEC
   reply (no Infotainment handshake in between). Write down its result: a `Success` with `[reply:infotainment] …
   auth=gcm-ok` shows the cached session was still good, which leaves sleep as the cause of F1's silence. A `Failure`
   "Infotainment session missing" (S6) shows the cache was stale; the next tap then starts a session, and the handshake
   appears as `[reply:infotainment] … session_info=…` with `auth=none`. Either way the session mismatch row is observed
   rather than provoked.
7. T1: parked, Drive, Media, MediaDetail 5 times each, one at a time, waiting for each result, about 2 to 3 s apart. T3:
   parked, same source playing, a long title, Media and MediaDetail 5 times each; note the source and the rough title
   length. T2: the passenger operates, 10 Drive reads without navigation and 10 with a route, the cluster speed and its
   unit written per read; no coordinates.
8. T4: parked, 5 alternating lock/unlock pairs from Key & Control or Control, one completed operation at a time, at least
   5 s apart, then watch 10 s more. No trunk or other toggles. Expect `[reply:vcsec] … encrypted(0B) … auth=gcm-ok` (empty
   success). If a `WAIT` arrives on attempt N, look for `closed … attempt=N`; a success on N+1 is the resend's reply. Keep
   fewer than 32 requests between a `WAIT` and the end of observation so attempt N stays in the history. A `closed` match
   shows correspondence, not authenticity. T4 cannot guarantee a `WAIT`.
9. Before leaving: wait for two more polls (about 70 s) so the end state is in the log, then pull the logs.

**N2. S10 again with after-reads (the first half of N1 step 6).** Car `ASLEEP`, phone unlocked, app in the foreground, one
experiment tap, then stay for 2 polls (about 70 s) and look at the car screen; mark whether it lit. This removes the two
missing after-reads of F3 (H3 would do it in the app). Then continue with N1 on the same connection.

**N3. `WAIT` on a signed command (needs the user's approval, and H6).** T4 may never meet a `WAIT` by chance. F5 suggests
dense bursts get one. A lock or unlock sent at the end of a dense burst (for example, a debug action that sends about 15
`vehicleStatus` reads back to back and then `lock()`) would likely provoke a `WAIT` on a signed, idempotent command. With
H6 the library would then keep waiting on the same request, and the log would show whether the vehicle sends a final reply
to it. The 16th position is an experimental placement, not an established trigger: count the competing connect-time and
poll traffic, and treat a run with no `WAIT` as inconclusive. This is a deliberate provocation, unlike the README's "do not
create events", so the user decides. Never with `openTrunk` or other toggles.

## Hand-offs

- **`library-developer`**
  - A8.5: the sleeping-Infotainment item can cite F1 (this car, firmware unrecorded: no reply within 5 s, no busy answer,
    cached session). Whether to mark it confirmed, and with what scope, is yours. The handshake on a sleeping car and the
    wake time remain open.
  - `14-lib-reading-state.md` B3.5: an Infotainment `WAIT` to another client (F2, 1 frame). B3.3: new 1-entry ids 1 and 6,
    and the field 53 → 83 B reply correlation (F6).
  - ADR-0015: no change; the `WAIT` decision stays open. F5 adds "unsigned VCSEC reads: 0 of 4 `WAIT`s had a same-request
    final reply" as context only.
  - FR-104: consistent with these logs; no change suggested.
  - Plan Task 3 Step 4: FR-105 should not start on this data; the `auth=` and `WAIT` collection has not happened.
  - H1 (send-side observe lines) and H6 (debug-only no-resend-on-`WAIT` collection mode) for decision.
- **`library-api-request`**: none. The public API was sufficient. H1 is an internal diagnostic line and H6 a debug
  setting; if H6 needs a public switch, that becomes a request.
- **`ios-developer`**: H2, H3, H4; the N1/N2 runbook text in `samples/ios/README.md` (same-connection asleep-then-awake
  control, physical wake, marks, other apps force-quit, phone kept unlocked); the wording mismatch on what the experiment
  tap does before `startSession` (plan Step 4 "같은 순서로" vs `04-monitor-app.md` "skips only the sleep check" vs code: no
  `vehicleStatus()` call); N3 debug action only after the user approves.
- **`android-developer`**: none.
- **User**: firmware and model were not recorded; add them as the first mark of the next run. Decide on N3.

## Codex

Consulted through the Codex discussion MCP (`gpt-6.1-sol`, effort high), two rounds, on `main` at `b68301c` (Codex checked
branch and HEAD; read-only). Codex read the dispatcher, handshake and sample code and both logs itself.

| Point | Claude's position | Codex's view | Outcome |
|---|---|---|---|
| F1 denominator | One request per counted tap, no handshake | Agrees on registration and no handshake; but `transmit()` can resend under one attempt, so "exactly one transmission" is not shown; the evening tail is weaker | Adopted: "one registered request, no observed protocol retry" |
| F1 status | Confirmed for the narrow claim | Confirm the bounded observation; keep "caused by sleep" Likely until an awake control with the same session, because an unusable cached session could also explain silence | Adopted, with N1 step 6 as the control |
| Cache provenance | Follows from the code and the complete trace | Strongly inferred, not logged: Likely | Adopted: Likely (strong) |
| F2 | `other` plus history arithmetic | Simpler: our Infotainment address stays `self` even after eviction; only abnormal paths (earlier dispatcher, misaddressed or corrupted frame) could yield `other` | Adopted; abnormal paths checked and found unsupported |
| F3 | Likely | Agrees; sparse reads can miss a transient wake; frames lost before the dispatcher cannot be excluded | Adopted |
| F4 | Morning walk-away Likely | Prefer "no demonstrated connection"; both causes Unknown; stretched timers show suspension, not radio absence | Adopted |
| F5 | "Rate limit about 15 per 1.5 s" | Over-specific; count all comparable bursts; recovery time includes our retry delay; corpus count was off (11 own `WAIT`s, not 12) | Adopted: recounted on deduplicated logs, reworded as correlation with dense bursts |
| F6 | 14 of 16 correlation | Establish the background rate first; the rate is not a significance estimate | Adopted: background 0.012/s added with that caveat |
| Next run | Awake-car protocol | Physical wake (key card), force-quit both apps, fresh `sleep=AWAKE` line, same-connection control preferred, T4 alternating pairs with 10 s watch, under 32 closures; the current auto-resend prevents answering the `WAIT` question, so a separate collection mode is needed; request 16 is only an experimental placement | Adopted as N1, N2, N3, H6 |

Agreed: every finding and status above. No disagreement remains.
