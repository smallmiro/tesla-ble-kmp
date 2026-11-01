# Build 18 link health, third-party interference and broadcast timing (2026-11-01)

Author: `protocol-analyst`. Skill: `analyze-monitor-logs`. Repository state: `main` at `39af70f`.
This is an analysis report. Nothing here changes the library, the sample or the manual. `library-developer` decides what
enters Part B.

**Summary.** Reads of `listKeys()` failed 47 of 53 times in one session because each failure landed in a narrow time window
tied to a third-party BLE client's roughly 1 Hz polling (0 of 164 requests sent before the window stalled, 45 of 92 sent inside
it). After a link loss in the background the reconnect loop freezes until something wakes the app. With a person present, the idle
`Status` broadcast repeats 60.0 s after the previous broadcast, which sets the expected upper bound for the SFR-022 dimming. Several
Part B statements need correcting, including the charging reading of field 8.

No `mark` lines exist and no scenario was described, so nothing below is **Confirmed**. Status means: **Likely** =
consistent and repeated but uncontrolled; **Unknown** = otherwise. Statuses already in Part B are not raised.

## Data

| Item | Value |
|---|---|
| Sessions analysed | `monitor-20261101-173304.jsonl` (S1), `monitor-20261101-200304.jsonl` (S2) |
| Trivial | `monitor-20261101-160314.jsonl` (build 16, 2 lines: `connecting` only); `monitor-20261101-163736.jsonl` (build 17, 3 lines: one scan timeout after 30.08 s, the nominal value) |
| Compared where a finding needed it | `monitor-20261031-101803` (build 13), `monitor-20261101-143001`, `-144027`, `-144147` (build 14) |
| Phone | iPhone 16 Pro (`iPhone17,1`), iOS 27.0.1 |
| App | Monitor app `0.5.0(18)` |
| Car | The car of Part B B5 (Model Y); firmware not recorded; VIN not quoted |
| Clock | Device clock, KST (+09:00) |
| Scenario | None given. Everything about what the driver did is inferred from the frames |

## Inventory

| | S1 | S2 |
|---|---|---|
| Span | 17:33:04 to 20:02:38 (2 h 29 m 34 s) | 20:03:04 to 20:23:25 (20 m 20 s) |
| Lines | 2258 | 700 |
| `link` / `observe` / `query` / `event` | 22 / 2044 / 159 / 32 | 13 / 504 / 111 / 71 |
| Events | 31 `Status`, 1 `Unrecognized` | 63 `Status`, 6 `Unrecognized`, 2 `AuthenticationRequest` |
| `unknownFields` | `1.8` ×31, `48` ×1 | `1.8` ×63, `53` ×4, `48` ×1, `44` ×1 |
| `vehicleStatus` / `keySummary` | 53 / 53 Success | 37 / 37 Success |
| `listKeys` | **6 Success, 47 Uncertain** | 37 Success |
| `Failure` on any query | 0 | 0 |
| `[drop:no-handler]` | 1699 (1690 Infotainment, 9 VCSEC) | 15 (all VCSEC, 83 B) |
| `[drop:to-domain]` | 32 (equals events) | 71 (equals events) |
| `[drop:missing-source]` / `[drop:unparseable]` | 0 / 0 | 1 / 0 |

`[drop:to-domain]` lines equal event lines in both sessions, so no broadcast was lost between the dispatcher and the event
stream.

Connections:

| Connected | Duration | Connect took | First `Status` broadcast | Ended by |
|---|---|---|---|---|
| 18:41:32.531 | 807.6 s | 0.11 s | +21.8 s | Link lost, gear `DRIVE` |
| 19:31:43.193 | 1018.1 s | 0.17 s | +4.7 s | Link lost, gear `DRIVE` |
| 20:03:05.051 | 1034.8 s | 0.15 s | +43.1 s | Link lost 21 s after locking |
| 20:21:09.681 | 92.2 s | 13.58 s | +39.0 s | Link lost 14 s after locking |

## Findings

### F1. `listKeys` stalls in a time window tied to another client's polling — Likely (mechanism Unknown)

**Claim.** While another BLE client polls Infotainment at about 1 Hz, a VCSEC request sent 0.55 to 0.85 s after that client's
previous reply often gets no reply at all. `listKeys` needs eight requests in about 0.8 s, so it nearly always runs into the
window.

**Evidence.** Measured on S1, 52 polls (the first poll is left out: it ran before the third-party traffic and had two
`listKeys` overlapping). The send time of a request is taken as the arrival of the previous reply, so `vehicleStatus` is not
in the table.

| Send time after the other client's previous frame | Answered | Never answered |
|---|---:|---:|
| Under 0.55 s | 164 | **0** |
| 0.55 to 0.65 s | 38 | 8 |
| 0.65 to 0.75 s | 6 | **34** |
| 0.75 to 0.85 s | 3 | 3 |
| 0.85 to 1.15 s | 5 | 0 |
| 1.15 s or more | 2 | 2 |

- The stall step varies (slot 0 ×20, slot 4 ×11, slot 3 ×8, first summary ×3, slot 1 ×2, slot 5 ×2, slot 2 ×1), so it is the
  time that matters, not the request.
- The other client's stream has a strict period (median 0.94 s). After a stall its next frame came a median 2.19 s later
  instead of about 0.28 s, so **both** streams lose a message.
- Two lulls where the other client slowed to about one frame per 2.1 s (19:33:47 to 19:34:07, 19:43:52 to 19:45:30) gave
  success on all 4 polls inside them (19:34:05, 19:44:03, 19:44:35, 19:45:06), in 0.78 to 0.84 s. The sequence finished before the
  next window. Of the other 2 successes, 18:41:32 predates the traffic and 19:43:30 was rescued by a `WAIT` retry (F11).
- S2 had no third-party Infotainment frames: 37 of 37 Success, median 0.81 s.
- All 47 outcomes are `Timeout(afterSend = true)` ("timed out waiting for vehicle response"). The status is set after
  `dispatcher.send` returned, and writes are with response, so the GATT write had completed: **the phone did send**. No late
  reply appeared in the 5 s budget (0 whitelist frames among no-handler lines). A larger budget would not have helped.
- Reproduced in earlier recordings: 14 non-success `listKeys` at 10-31 and 14:30 to 14:41, all in recordings with third-party
  frames. Last reply phase: 0.6 to 0.8 s in 6, 1.7 to 2.7 s in 5 (matches the slower cadence), other in 3.

**Why `vehicleStatus` and `keySummary` did not fail (106 of 106).** They sit at the start of a cycle. 51 of 52 `vehicleStatus`
replies arrived under 0.65 s after another-client frame, and `keySummary` was sent right after that. The window is reached a few
steps later, inside `listKeys`. That the poll timer tends to fire early in the other client's cycle is **not explained**: it
may be iOS timer coalescing on the notification wake-up. A single command sent into the window should behave the same way, but
no command was sent during the traffic, so this is an extrapolation.

**Counterexamples and limits.**
- 2 requests sent at 0.51 and 0.57 s were answered late (2.04 s, 1.29 s) rather than lost.
- 18:49:12 and 18:54:34 show total silence on all traffic for 17 s and 26 s; these are not a timing collision (F4).
- `Failure` "timed out before sending command" at 10-31 11:33:25 had no third-party traffic. It was not a stall: two overlapping
  `listKeys` on a slow new connection used up the 5 s at about 0.3 s per reply.

**Not checked.** Where the message dies. The window is compatible with a single in-flight slot in the vehicle's BLE routing or
with a shared receive buffer, but these logs cannot separate them. `TX:` lines are not in the log, so request times are
inferred.

**Confirm or refute.** Next scenario N1 (same poll with the other client on, then off).

### F2. Another client's Infotainment replies reach our link — Likely

**Claim.** B3.5 describes only 83-byte VCSEC replies for other clients. Infotainment replies arrive too, at up to 1 frame/s.

**Evidence.** 1690 frames in S1 (`from=domain(DOMAIN_INFOTAINMENT)`, `to=address(16B, other)`, `request_uuid=16B`): 1497
`encrypted`, 132 `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED`, 61 `session_info=97B`. Span 18:41:46 to 19:48:40. Also 90, 71, 26 and 6
frames in the four earlier recordings. Largest frame 453 B, largest encrypted payload 350 B. All 132 size errors fall in
18:43:41 to 18:54:31, in bursts, followed by `session_info` replies; none occur in the second connection.

**Relevance beyond this log.** It is real-car evidence for the size limit in `16-lib-errors.md` §Response size limit (#116): a
live client hits `RESPONSE_MTU_EXCEEDED`, and successful replies stayed at or under 350 B of ciphertext. The category asked for
is unknown because the payload is encrypted.

**Counterexample.** One connection on the same phone, in the same gear (20:03:05, `DRIVE`), carried none, so the client is not
tied to driving.

**Not checked.** Who the client is. It appears only on fast connects (7 of 8) and never on slow ones (0 of 4), see F8. It may be
another device or another app on this iPhone. See question 1.

### F3. After a link loss in the background the reconnect loop freezes — Likely

**Claim.** `scan` by name makes no progress and the app is suspended, so a lost link stays lost until something wakes the app.
This is the plan Task 2 spike question, and `17-lib-platform-notes.md` §iOS background use still calls it unconfirmed.

**Evidence.** The scan timeout is 30 s. It fired on time when the app was running (30.02 s at 20:20:21, 30.08 s at 16:37:36).
Otherwise the timer fired late, which only happens when the process did not run:

| `connecting` at | Scan "timed out" after | Then |
|---|---:|---|
| 17:33:04 | 3041 s (50 m 41 s) | Backoff |
| 18:23:47 | 179 s | Backoff |
| 18:26:54 | 48 s | Backoff |
| 18:27:51 | 32 s | Backoff |
| 18:28:53 | 759 s (12 m 39 s) | `backoff` delayMs 30000, then `connecting` **47 ms later** |
| 18:55:02 (after link loss) | 2197 s (36 m 37 s) | Connected 4.4 s after the timer fired |
| 19:48:43 (after link loss) | 835 s (13 m 55 s) | New process at 20:03:04 |

- Backoff timers also stretched: 4 s took 8.3 s and 9.0 s, 16 s took 29.4 s.
- At 18:41:32 a 30 s rest ended after 47 ms. In `ConnectionLoop` only `start()` can do that, and its callers are the Connect
  buttons and `protectedDataBecameAvailable` (phone unlock). So a person or an unlock triggered it, not a background Bluetooth
  wake.
- After each wake the car was found at once (0.11 to 0.17 s), and at 19:31:43 and 20:03:05 it was still in `DRIVE`. If the phone
  stayed in the car, the car was reachable the whole time and only the loop was missing (question 3).
- While a link is up the app keeps running: 53 polls at 30 to 36 s spacing over 1826 s connected in S1.

**Counterexample.** None of the 7 late timers shows a background reconnect. But the screen state is not in the log, so "in the
background" is inferred. The freeze itself is not inferred: a 30 s timer firing 36 minutes late is direct.

**Not checked.** Whether the vehicle advertises the service UUID, which decides whether a service filter is even possible. Go
filters by local name only (`ble.go:222`).

**Confirm or refute.** N2.

### F4. Four link losses: two after walking away, two in `DRIVE` — Likely for the first two, Unknown for the others

| Lost at | Last frame before | Context | Reading |
|---|---:|---|---|
| 20:20:19.837 | 9.0 s | `LOCKED` 21 s earlier, `NOT_PRESENT` | Walk-away, as in B3.1 |
| 20:22:41.871 | 11.0 s | `LOCKED` 14 s earlier, `NOT_PRESENT` | Walk-away, as in B3.1 |
| 18:55:00.134 | **25.9 s** | `DRIVE`, `INTERNAL_LOCKED`, third-party client in its size-error phase | Unknown |
| 19:48:41.250 | 0.9 s | `DRIVE`, `INTERNAL_LOCKED`, abrupt | Unknown |

- 18:49:12 had a 17 s silence on **all** traffic that recovered without a disconnect. 18:54:34 had the same silence and ended in
  the 18:55:00 loss. Both came during a burst of `RESPONSE_MTU_EXCEEDED`. Recovering after 17 s suggests the application layer
  stopped sending rather than radio loss, but only if the link's supervision timeout is shorter than that, and it is not logged.
  **Unproven.**
- Both `DRIVE` losses happened with the third-party client active, and S2's 17 minutes without it had none. Two events are too few
  to read anything into.
- Reconnect after the walk-aways: 49.8 s, by which time the phone was back in range. After 20:22:41 there was no reconnect in the
  remaining 44 s (one attempt ended with `connect failed: NotConnectedException: Disconnect detected`).

### F5. Idle `Status` broadcast repeats 60.0 s after the previous broadcast — Likely

**Claim.** While awake, with a person present and nothing changing, the car resends `vehicleStatus` 60.0 s after the last broadcast.
Any change restarts the wait. Part B B3.1 says "every 1 to 25 s".

**Evidence.** 47 gaps of 59.9 to 60.1 s, all with `PRESENT` (44 in `DRIVE`, 3 in Park); measured period 60.008 s over 660 s and 900 s
stretches. No gap above 60.145 s in 167 consecutive `Status` gaps across 6 recordings. Restart: 20:07:12.902, then a lock change at
20:07:23.312, then +60.0 s from that change. Same after the gear changes of 20:12:34 and 20:14:35. The phase does not carry across a
disconnect: 18:54:11 to 19:31:47 is not a whole number of periods, so the timer restarted while we were not listening.

**Counterexamples.** Bursts of 2 to 5 frames within 1 s at changes, some identical. The "1 to 25 s" of round 1 may come from field 8
changes, which trigger a broadcast by themselves (F7). Round 1 raw frames are not available to check.

**Not checked.** A sleeping car, and an awake car with nobody present: the largest of 42 `Status` gaps with `NOT_PRESENT` is 25.3 s, so
that state never stayed unchanged long enough to show a 60 s repeat.

### F6. SFR-022 dimming: a broadcast arrives, but the wait is long — Likely

**Answer to focus 3.** All 4 connections of build 18 got a `Status` broadcast. The dim lasted 4.7 to 43.1 s. The logic works as
specified, but it waits for something the app already has.

| Connection | First `vehicleStatus()` Success | First event of any type | First `Status` (cluster brightens) |
|---|---:|---:|---:|
| 18:41:32 | 0.125 s | 19.9 s (field 48) | 21.8 s |
| 19:31:43 | 0.096 s | 4.7 s | 4.7 s |
| 20:03:05 | 0.105 s | 43.1 s | 43.1 s |
| 20:21:09 | 0.657 s | 0.4 s (field 53) | 39.0 s |

- With earlier recordings, 12 connections: 9 got a `Status` (median 25.1 s, maximum 49.8 s). **3 of 12 ended before any `Status`**
  (after 3.9, 26.7, 32.4 s), so the screen stayed dim for the whole connection. By F5 the expected bound is about 60 s while awake
  with a person present. It has not been measured for an empty car, so the wait there could be longer.
- First `vehicleStatus()` Success: 12 of 12, median 0.105 s, maximum 1.78 s. It is the same data: `VehicleEvent.Status` is built
  with the same rules as `vehicleStatus()` (B2.2).
- Only `Status` sets `heardOnThisLink`, so at 20:21:09 a broadcast at +0.4 s proved the link was live but the screen stayed dim for
  39 s while the query had already returned current values.

**Recommendation (for `ios-developer`, needs an SFR-022 change).** Keep the rule "not current until heard on this link", and count a
successful `vehicleStatus()` on this link as heard, feeding it through the same reducer. It would cut the dim to under 2 s in 12 of
12 connections and needs no new library API. Dim on `Disconnected` stays as it is.

### F7. Field 8: the charging reading is refuted, and sub-field 3 is not always 1 — Unknown

**Part B text.** "Sub-field 2 … may be a charging-cable or charging state (1 = in use, 2 = idle)" and "Sub-field 3 was always 1".

**Evidence.** 176 frames in 6 recordings, 32 value changes:

| `{1, 2, 3}` | D / R / N | Park, present | Park, not present |
|---|---:|---:|---:|
| 2, 1, 1 | 75 | 28 | 2 |
| 1, 1, 1 | 0 | 16 | 33 |
| 1, 2, 1 | 0 | 1 | 8 |
| 2, 2, 1 | 0 | 3 | 1 |
| 1, 1, 2 | 0 | 0 | 7 |

The 2 frames with `gear = UNKNOWN` are left out of the table.

- **Refutes "1 = charging in use":** sub-field 2 is 1 in all 75 frames in D, R or N. A car cannot be plugged in while driving.
- **Corrects "always 1":** sub-field 3 was 2 in 7 frames at 10-31 11:29 to 11:31, flipping every 8 to 16 s, parked and not present.
- **New hypothesis (position-like):** sub-field 1 = 2 goes with presence (108 of 111 frames, 97%). At 20:18:56 to 20:19:03 a person
  returns: `ENTERED_HIGHER_AUTH_ZONE`, then sub-field 2 → 2 after 0.9 s, unlock, door opens, then sub-field 1 → 2 after 0.7 s, then
  sub-field 2 → 1 at the shift. At 20:22:11 to 20:22:30, around an open trunk, it flips repeatedly while the person is outside. The
  charge-port changes of rounds 2 and 3 fit a person standing at the car too.
- A field 8 change alone triggers a broadcast (20:22:17, :20, :23, :30).

**Counterexamples to the new hypothesis.** Sub-field 1 toggled 7 times in 8 s at 14:41:30 with `PRESENT` and Park. 17 frames have
sub-field 1 = 1 while present. A second person or key could explain these; the logs cannot tell. Status stays **Unknown**.

**Confirm or refute.** N5.

### F8. Field 44 and two kinds of connect — Likely for the correlation, Unknown for the meaning

**Claim.** New top-level field: `FromVCSECMessage{44: 2}`, 13-byte frame, an `Unrecognized` event. It is not in Part B.

**Evidence.** All 12 connections split cleanly:

| | Connect took | First query | Field 44 frame | Third-party Infotainment frames |
|---|---:|---:|---:|---:|
| 8 fast connections | 0.10 to 0.21 s | 0.07 to 0.13 s | **0 of 8** | 7 of 8 |
| 4 slow connections | 1.52 to 13.58 s | 0.66 to 1.78 s | **4 of 4** | 0 of 4 |

Value 2 in all 4 frames, 1.1 to 3.4 s after `connected` (10-31 11:32:04, 11:32:14, 11:33:20; S2 20:21:13). The one unwrapped
`authenticationRequest` (F10) was also on a slow connect. Replies also ran slower for the first seconds of a slow connection (0.15 to
0.48 s apart, against about 0.09 s on a fast one).

**Hypothesis.** A slow connect creates a new link and the car reacts to it. A fast connect joins a link the iPhone already holds (for
example a phone key), so the car sees nothing new. **Unconfirmed**; the timing above is the only thing pointing at it.

**Counterexample checked.** Field 44 is not just "car locked": 14:41:47 was locked, not present, fast, and had no field 44.

**Confirm or refute.** N4.

### F9. Fields 48 and 53: structure and identifiers — Unknown

**Field 48.** 3 frames in 6 recordings, all 20 B with `{1: 01 03 06 07 04}` (18:41:52, 20:19:32, 14:31:29). This is a far lower rate than
the "about every 5.0 s" of round 1. No trigger is visible.

**Field 53.** 11 frames. All 4 five-entry frames list ids in the order **1, 3, 6, 7, 4**, which is byte for byte the field 48 value.

- **Identifiers are not slots.** `keySummary` reports slots 0 to 6 in 90 of 90 polls of S1 and S2, yet id 7 appears. Part B's "overlap
  with enrolled key slot numbers" needs a qualifier. The ids seen on 10-31 (8 keys: 3, 4, 6, 7) are a subset of today's set (7 keys).
- **The 35-byte blob.** The first 33 bytes are the same in all 11 frames over 3 days and across the key change (SHA-1 prefix
  `a3072d90`). Only the last 2 bytes vary, per entry and per frame, even for the same id. It is not a masking artefact (no `0x2a`
  bytes). This weakens "per-key authentication challenge": a challenge with at most 16 bits of variation is unlikely. A 16-bit tag is
  one alternative, but it is a guess.
- Three of the four five-entry frames fall within 0.7 s of a sub-field 1 change from 2 to 1 (20:18:27, 20:19:53, 14:41:30); the fourth is
  0.4 s after a slow connect (20:21:10). Single-entry frames show no such link.

### F10. Unwrapped frames do occur on this car — Likely

**Part B text (B3.2).** "No unwrapped frame was seen on this vehicle (`drop:missing-source` and `drop:unparseable`: 0 lines)."

**Evidence.**
- 20:21:09.680, 1 ms before `connected` on a slow connect: 31 B, reinterpreted as `authenticationRequest{requestedLevel=DRIVE,
  reasons=[IDENTIFICATION], token=20B}`. `IDENTIFICATION` is a reason Part B has not seen.
- 14:30:17.371: 12 B, reinterpreted as `commandStatus{OPERATIONSTATUS_OK}`.

**Consequence.** These frames fail the gate (B2.1), so `Vehicle.events` does not deliver them. That is documented behaviour, but an
app cannot see this `AuthenticationRequest`. The rate is 2 frames in 12 connections, far from the "every 1.1 s" reported elsewhere.

### F11. Explicit busy and epoch replies recovered as designed — Likely

- `OPERATIONSTATUS_WAIT` (47 B): 5 occurrences. All 5 were followed by the expected reply 1.08 to 1.19 s later, which matches
  `VehicleError.Busy` plus the 1 s `retryInterval`. Three came after 15 or 16 replies in a burst from two overlapping `listKeys`. One
  (19:43:31) came in third-party traffic and rescued that poll: it is the only stall-like case that recovered.
- `INCORRECT_EPOCH` with `session_info=92B` at 20:21:47.551, retried, signed reply at 20:21:48.628, then `lock=UNLOCKED` broadcast 62 ms
  later and a re-read 59 ms after that. This looks like an unlock from the Key & Control tab (question 5). The automatic retry worked.

### F12. Smaller additions for Part B — Likely

- **`gear = UNKNOWN` on an awake car:** 2 frames of 24 B with no gear field (14:30:59, 20:19:02), the second 28 ms before a normal `PARK`
  frame. B2.4 mentions this only for a waking car.
- **Rear passenger door:** `rearPassengerDoor = OPEN` 20:21:53 to 20:22:09 (`Not tested` in B3.1; question 5).
- **Lock around driving:** `INTERNAL_LOCKED` → `UNLOCKED` in the same frame as `PARK` (2 of 2). After shifting to `DRIVE` it took 16.9 s
  and 130.4 s to reach `INTERNAL_LOCKED`, and it did not happen at all in the 44 s of 20:19:03 to 20:19:47. So `INTERNAL_LOCKED` is not
  simply "in gear".

## Library hardening

| # | Item | Evidence | Proposal | Priority |
|---|---|---|---|---|
| H1 | A lost read-only query waits out the whole budget | F1: 47 of 53 in S1, 0 late replies; resend after `WAIT` worked 5 of 5 | For unsigned `InformationRequest`s only, use an attempt timeout of about 1 s and resend within the budget. **This goes beyond Go** (`getVCSECResult` resends only on `ShouldRetry`, `vcsec.go:85`), so it needs a decision (ADR). Reads are safe to resend (`16-lib-errors.md`). Raising the budget alone would not help. Do not extend it to signed commands | High |
| H2 | Reconnect cannot run in the iOS background | F3: gaps of 37 and 14 minutes | Needs an API or design request: a pending connect to the known peripheral that iOS can complete in the background, a service filter if the car advertises one, state restoration. Only with evidence from N2 | High |
| H3 | Documentation does not warn about other clients | F1, F2 | `16-lib-errors.md`: `Uncertain(Timeout)` on queries while another client is active; re-query. `14` B3.5: Infotainment frames | Medium |
| H4 | Unwrapped `authenticationRequest` is invisible to apps | F10 | Decide whether to keep it out of `events` (the gate), and document it. Low value at 2 frames in 12 connections | Low |
| H5 | `connect` ran 2.63 s over its 30 s budget | 20:22:44 to 20:23:16 | Probably `open()` cleanup after the timeout (`DISCONNECT_TIMEOUT`). Check whether the overrun is bounded. No harm seen | Low |

**Not a problem.** No loss between dispatcher and `events`; no `Failure` on any query; backoff numbers follow SFR-012; `drop:unparseable`
0. Inbox overflow cannot be judged: those `WARN` and `ERROR` lines are not written to this log.

## Next scenarios

Use Log tab marks at every step. Please state the phone's screen state in each mark.

| # | Purpose | Do | Wait | Mark |
|---|---|---|---|---|
| N1 | F1, F2 | Parked, app in foreground, other client **on**, then **off** | 5 minutes each (10 polls) | `client on`, `client off` |
| N2 | F3 | Phone locked. Walk away until the link drops, come back, do not touch the phone. Repeat with the app in foreground | 3 minutes away, 5 minutes back | `away`, `back`, `touched phone` |
| N3 | F5 | Seated in Park, no operation. Then locked, standing near the car | 5 minutes each | `idle seated`, `idle outside` |
| N4 | F8 | Force-quit the Tesla app (and any other BLE client), turn Bluetooth off and on, connect. Repeat with the Tesla app running | 2 minutes each, 3 rounds | `cold`, `warm` |
| N5 | F7 | One phone only, watch off. Stand 20 s each at the driver door, passenger door, rear, front, then seated. Separately, charger plugged in with nobody near | 20 s per spot, 5 minutes for the charger | One mark per spot |
| N6 | F6 | After the SFR-022 change, repeat C2 and C7 of the runbook | One reconnect each | `reconnect` |

## Hand-offs

**`library-developer`**
- Decide H1 (ADR needed, it departs from Go) and H3 to H5.
- Part B proposals, status unchanged unless noted:

| Where | Change | Finding |
|---|---|---|
| B3.1 Frequency, B2.4 | Add the 60 s repeat, restarted by any change | F5 |
| B3.1 field 8 | Remove the charging reading; sub-field 3 can be 2; a change alone triggers a broadcast; stays Unknown | F7 |
| B3.2 Wrapping | Replace "no unwrapped frame"; add `IDENTIFICATION` as observed | F10 |
| B3.3 | Ids are not slots; 33 constant bytes; soften "challenge" | F9 |
| B3.4 | Add content; correct the rate | F9 |
| New B3.x | Field 44; list it in B1 | F8 |
| B3.5 | Infotainment replies, session info and size errors also arrive | F2 |
| B2.4, B3.1 | `gear = UNKNOWN` on an awake car; rear passenger door; lock notes | F12 |
| `17-lib-platform-notes.md` | Background reconnect evidence, after N2 | F3 |
| `16-lib-errors.md` §Response size limit | Real-car size-error evidence | F2 |

**`library-api-request`**: H2, once N2 confirms it.

**`ios-developer`**
- SFR-022: count a successful `vehicleStatus()` on this link as heard (F6).
- Log additions: scene phase and protected-data lines; a marker for why `start()` fired; request start time on `query` lines, so that
  `vehicleStatus` can enter the F1 table; scan hit time and RSSI in `connect`.
- Stop the two overlapping `listKeys` right after connect (F11; the 10-31 11:33:25 `Failure`).
- Spec `04-monitor-app.md` and `samples/ios/README.md` check 9: this report is input for the background check.

**Android**: no action. The same scenarios apply when the Android monitor reaches a real car.

## User answers (2026-11-01)

The user answered after the analysis, and said that the expected readings are all correct. Questions 1 to 3 were answered directly.
Questions 4 and 5 are covered only by that general statement. Question 6 is still open.

- **Question 1.** The other client was the user's own other app. Read here as running on this iPhone, which the user's wording implies
  but does not state. This resolves "Who the client is" in F2.
- **Question 2.** The monitor app was in the background because that other app was in use. This turns "in the background" in F3 from an
  inference into a reported fact.
- **Question 3.** The phone was inside the moving car at 18:55:00 and 19:48:41. So F4's two `DRIVE` losses were **not** caused by
  distance, and in F3 the car was in reach while the reconnect loop was frozen.

**Status.** Unchanged. These are uncontrolled recollections, so F1 to F4 stay as listed until N1 and N2 run. They do remove the need for
N1 to identify the client, and N1 can now be run by simply using the user's app.

**New hypothesis, Unknown.** If both apps run on one iPhone they share one BLE link to the car, which iOS manages, so iOS would deliver
the other app's replies to the monitor as well. That would explain F2 (its frames reach our link), and it would fit F8 (fast connects,
because iOS already holds the link, and slow ones without it). It does not by itself explain F1's stall window. To check: run N1 and N4 with
the other app on and off, and note whether the fast connect and the third-party frames appear and disappear together.

## Questions for the user

Questions 1 to 5 are kept as asked; see the answers above. Only question 6 is open.

1. **Which other BLE app or device was active** 18:41 to 19:48 today, and at 10-31 11:26 and 14:30 to 14:41? For example a second phone,
   a dashboard device, or an app on this iPhone. It stopped somewhere between 19:48 and 20:03. (F1, F2, F8)
2. Was the phone locked or was the app in the background during those periods? What did you do at 18:41:32 and 19:31:38? (F3)
3. At 18:55:00 and 19:48:41 was the phone inside the moving car, and did you stop or park between then and the reconnect? (F3, F4)
4. Did you restart or force-quit the app at about 20:03? (F3)
5. At 20:21:47 did you press Unlock in the app? At 20:21:53 did someone open the **rear right** door, and at 20:22:12 did you open the
   trunk, and how? (F11, F12)
6. At 10-31 11:29 to 11:31, what was happening near the parked car: other people, other keys? (F7)

## Codex

No consultation was needed. No hypothesis here is contested between two readings on which a decision depends: F1's correlation stands on
its own numbers, and its mechanism is left Unknown until N1 runs. H1 is a design decision for `library-developer`, who can consult Codex
when writing the ADR.
