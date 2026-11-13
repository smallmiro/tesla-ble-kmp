# Build 27 first run of all 12 `getState` categories: field sets, request loss tied to another client, wake by a stale VCSEC session (2026-11-13)

Author: `protocol-analyst`. Skill: `analyze-monitor-logs`. Repository state: `main` at `7a63472`.
Builds on `{{ANALYSIS_DIR}}2026-11-07-118-awake-car-capture.md` ("the 11-07 report", F1 to F9, H1 to H7, N1 to N4),
`{{ANALYSIS_DIR}}2026-11-05-118-sleeping-car-getstate.md` ("the 11-05 report") and
`{{ANALYSIS_DIR}}2026-11-01-build18-link-health.md` ("the build 18 report"). Numbering continues from the 11-07 report:
findings F10 to F19, hardening H8 to H16, next scenarios N5 to N9.
This is an analysis report. Nothing here changes the library, the sample or the manual. `library-developer` decides what
enters `{{LIB_DOCS_DIR}}14-lib-reading-state.md`, `16-lib-errors.md`, `18-lib-troubleshooting.md`, ADR-0010, ADR-0015 and
the PRD; `ios-developer` decides sample changes.

**Summary.** Build 27 queried the nine new `getState` categories on a real car for the first time. Eight answered on the
first try: `CHARGE`, `CLIMATE`, `LOCATION`, `TIRE_PRESSURE`, `SOFTWARE_UPDATE`, `CHARGE_SCHEDULE`,
`PRECONDITIONING_SCHEDULE`, `PARENTAL_CONTROLS`. Every reply was `encrypted(...)` with `auth=gcm-ok`, no plaintext, no fault,
the largest 353 B (F10). Which fields arrived is known only for the fields the sample maps (`present=[...]`); size arithmetic
shows `CHARGE` and `CLIMATE` carry much more than the sample sees, and `LOCATION` carries more than every official
`LocationState` field could fill (F11). `CLOSURES` was never answered: its one request got no reply. The losses of this run
(15 of 134 own requests, none answered late) are not explained by sleep, `WAIT`, burst position or our own `getState`: from
17:49:30 another client polled the car every 2.1 s, and 9 of the 10 interruptions of its Infotainment stream on our link
contain at least one of our lost requests, while none of its 17 normal intervals contains one. This extends build 18 F1 as
an association with overlapping requests; where the frames die is Unknown (F12). The `listKeys` `Uncertain` results fit
this pattern (F14); `CLOSURES` fits it too, but with one trial its attribution stays Unknown (F13). The first
`TIRE_PRESSURE` was an experiment-button tap on a sleeping car inside a 14 s link-wide silence (F13). The three
`NotSent reason=sleepReadFailed:Timeout(afterSend=true)` are correctly classified; the car had already reported `AWAKE`, and
their `vehicleStatus` reads were lost in an 18 s stall that the other client's stream shows too (F15).
A signed VCSEC command, most likely `wakeUp`, met a stale cached session (`INCORRECT_EPOCH` with session info), was resent
and succeeded 1.11 s later; the car reported `AWAKE` 0.15 s after that, but gear stayed `UNKNOWN` and Infotainment was first
seen answering 5.6 s later (F16). `WAIT` came at the 17th and 15th reply of a burst, not the 16th (F17). The hours-long
scan attempts are app suspension, ended by a phone unlock (F18). No `mark` line, the other client was not closed as the
runbook asks, and the firmware is still not recorded.

## Data

| Item | Value |
|---|---|
| Main session | `monitor-20261113-101448.jsonl` in `{{SAMPLE_LOGS_DIR}}ios/2026-11-13/` (git-ignored), 277 lines, one connection 17:48:54.861 to 17:51:07.869 (133.0 s) |
| Context only | `monitor-20261112-100824.jsonl` (build 25, 11 lines), `monitor-20261112-212752.jsonl` (build 25, 7 lines), `monitor-20261113-000244.jsonl` (build 26, 12 lines): app and link lines only, no connection (Inventory) |
| Phone | iPhone 16 Pro (`iPhone17,1`), iOS 27.2.0 |
| App | Monitor app `0.5.0(27)` (PR #157: 12 categories, `present=[field names]` on `Success`) |
| Car | Assumed to be the Model Y of Part B B5 (same phone, same seven-key whitelist, `entries=7 slots=[0..6]`); firmware and model **not recorded**; VIN not quoted (no VIN string in the log) |
| Clock | Device clock, KST (+09:00) |
| Scenario | No `mark` line in any file. The README "추가 카테고리 확인" asks for a mark before each tap and for other BLE apps and the Tesla app to be closed; another client was active from 17:49:30. What a person did is inferred |
| Poll interval | About 10 s (the gap from one cycle's `listKeys` result to the next `vehicleStatus` start was 10.06 to 10.62 s, 7 of 8; once 6.20 s, a "지금 조회" press or a poll-setting change). The default is 30 s |

Limits of the log, as before: no `TX:` lines, so send times are inferred from the previous own reply in a chain or from the
query's `startedAt`; values never reach the log (FR-112); commands from Key & Control and Control write no line, only their
replies and the broadcasts they cause; the `getState` line does not say whether the normal or the experiment button was used
(11-07 report H4, still open).

## Inventory

### Short files (builds 25 and 26)

| File | Span | What happened |
|---|---|---|
| `100824` (build 25) | 11-12 10:08:24.3 to 10:08:42.9 | Launch, `connecting attempt=1`, app to background at 10:08:31.5, foreground 10:08:41.5, background 10:08:42.9. No scan result before the file ends |
| `212752` (build 25) | 11-12 21:27:53.0 to 21:27:55.0 | Launch, `connecting attempt=1`, background 2.0 s later. No scan result |
| `000244` (build 26) | 11-13 00:02:44.9 to 00:03:58.7 | Scan timeout after 30.02 s in the foreground (nominal); phone locked 00:03:25.9, background; second scan timeout logged 35.5 s after its start, backoff 4 s fired after 6.26 s, `connecting attempt=3` at 00:03:58.694, then nothing |

None connected. In all three the last line is a scan that never reported a result, and the next file starts with a new
process (`start reason=launch`), so each process was suspended and later ended without running again. The 00:02 file shows
the process kept running for about 33 s after going to the background before it stopped. Consistent with build 18 F3; nothing
new.

### Main session

| Item | Value |
|---|---|
| Link lines | 5 scan attempts before the connection (F18), `connected scanHitMs=104 rssi=-82 openAttempt=1` at 17:48:54.861 (0.70 s after `connecting attempt=5`), `disconnected` "bluetooth connection lost" at 17:51:07.869, `connecting attempt=1` at 17:51:09.929, file ends 17:51:27.8 |
| App lines | Foreground from 17:48:54.285 to 17:51:01.954 (phone locked 17:51:01.775) |
| Own requests (`attempt` max) | 134: 124 VCSEC, 10 Infotainment (133 is the highest number in a reply line; 134 is the last `listKeys` slot request, which got none) |
| Own replies | 119: 111 `[reply:vcsec]` (107 unsigned read replies, 2 `WAIT`, 1 `INCORRECT_EPOCH` fault, 1 `encrypted(0B) gcm-ok`), 8 `[reply:infotainment]` (all `gcm-ok`) |
| Never answered | 15: VCSEC 25, 27, 56, 57, 58, 59, 66, 85, 108, 118, 120, 125, 134; Infotainment 26, 74. No late reply seen: no `closed` line, and no plaintext VCSEC reply among the `other` drops |
| `query` lines | `vehicleStatus` 9/9 `Success`; `keySummary` 9/9 `Success`; `listKeys` 3 `Success`, 6 `Uncertain` ("timed out waiting for vehicle response"); `getState` 13: 8 `Success`, 2 `Uncertain` `Timeout(afterSend=true)`, 3 `NotSent reason=sleepReadFailed:Timeout(afterSend=true)` |
| Events | 19: 17 `Status` (all with `unknownFields=[1.8]`), 2 `AuthenticationRequest`; no `Unrecognized`, no field 53, 48 or 44. `[drop:to-domain]` 19, equal to the events |
| Other-client frames (`to=address(16B, other)`) | 44: Infotainment 28 (26 `encrypted(263 to 267B)` in 366 to 370 B frames, 2 `session_info=97B`), VCSEC 16 (12 `encrypted(16 to 20B)` with empty `request_uuid`, 2 signed `encrypted(0B)` 83 B, 1 `session_info=92B`). First at 17:49:30.676. None before |
| Other drops | `[drop:missing-source]`, `[drop:unparseable]`: 0 |
| Log value check | `grep '"kind":"query"' … | grep -E '[0-9]+\.[0-9]{4,}'`: 0 lines (README check) |

`pbwire.py` was used for the 19 event frames (field 8 sub-fields, F19). Replies to our own requests carry no raw bytes in the
log.

### Timeline of the key moments

| Time | Line | What |
|---|---|---|
| 17:48:54.085 | 20 | Overdue scan timeout of attempt 4 (started 10:59:44.857), logged as the app resumed; 73 ms later `start reason=protected-data effect=woke` (23) |
| 17:48:54.861 to 56.205 | 27-40 | Connected; first poll: `ASLEEP`, `LOCKED`, `NOT_PRESENT`, `GEAR_UNKNOWN`; `listKeys` `Success` |
| 17:48:56.443 to 58.246 | 41-49 | Key & Control automatic `listKeys` (attempts 11 to 19); `WAIT` on attempt 17 (47) |
| 17:49:06.829 to 07.305 | 50-56 | Poll 2: attempts 20 to 24 answered, still `ASLEEP` |
| 17:49:07.305 to 21.225 | 56 → 59 | **No frame of any kind for 13.9 s.** Attempt 25 (slot 2) unanswered, `listKeys` `Uncertain` at 12.011 (57); `getState(TIRE_PRESSURE)` started 10.092, `Uncertain` at 15.115 (58); attempts 26 and 27 unanswered |
| 17:49:21.225 to 22.066 | 59-66 | Attempts 28 to 35: a `listKeys` with no poll before it (Key & Control retry, F13) |
| 17:49:22.786 | 75 | `WAIT` on attempt 42 (15th reply of the burst) |
| 17:49:23.988 / 25.096 | 77 / 78 | Attempt 44 `ERROR/INCORRECT_EPOCH session_info=92B auth=none`; attempt 45 `encrypted(0B) auth=gcm-ok` (F16) |
| 17:49:25.160 to 25.274 | 79-86 | Broadcasts: `ASLEEP` with closures `UNKNOWN` twice, then `AWAKE` (25.245), all closed (25.274); gear absent |
| 17:49:30.556 to 31.667 | 94-100 | `AuthenticationRequest` (`DRIVE`, `ENTERED_HIGHER_AUTH_ZONE`); other client's Infotainment `session_info`, its 83 B signed VCSEC success, first `GEAR_PARK` broadcast (30.764), its first Infotainment data reply (31.667) |
| 17:49:32.415 to 51.644 | 108 → 118 | **Own requests 56 to 59 unanswered for 19.2 s; the other client's stream is silent from 31.667 to 50.237**; two `Status` broadcasts still arrive (34.065, 34.517). `listKeys` `Uncertain` (113); taps `DRIVE`, `MEDIA`, `MEDIA_DETAIL` end `NotSent` (114, 115, 117) |
| 17:49:52.235 to 17:50:14.631 | 133-172 | Taps `CHARGE`, `CLIMATE`, `LOCATION` `Success`; `CLOSURES` `Uncertain` (144); `TIRE_PRESSURE`, `SOFTWARE_UPDATE`, `CHARGE_SCHEDULE`, `PRECONDITIONING_SCHEDULE`, `PARENTAL_CONTROLS` `Success` |
| 17:50:24.374 to 31.336 | 194-211 | `UNLOCKED` (not by us), front passenger door open and closed, `PRESENT`, then `NOT_PRESENT` |
| 17:50:51.346 | 245 | Walk-away `LOCKED` |
| 17:51:01.517 / 01.775 | 264 / 266 | Second `AuthenticationRequest`; phone locked, app to background (269) |
| 17:51:07.869 | 272 | Link lost, 6.1 s after the phone locked and 16.5 s after the walk-away lock |

## Findings

### F10. Eight new categories answered on the first request, all verified — Likely (one reply per category); field sets observed once

**Claim.** On this car (firmware unrecorded), `CHARGE`, `CLIMATE`, `LOCATION`, `TIRE_PRESSURE`, `SOFTWARE_UPDATE`,
`CHARGE_SCHEDULE`, `PRECONDITIONING_SCHEDULE` and `PARENTAL_CONTROLS` each answered a normal-button `getState` with an
`AES_GCM_Response_data` reply that verified (`auth=gcm-ok`). The fields the sample maps arrived as listed below.

| Category | Attempt | Start → result | `durationMs` | Frame / ciphertext | Sleep-read reply → data reply | `present` (sample-mapped fields the car sent) | Mapped but absent | Official fields the sample does not map |
|---|---:|---|---:|---|---:|---|---|---:|
| `CHARGE` | 68 | 17:49:52.235 → 57.072 | 4837 (F14) | 353 / 250 B | 213 ms | 13 of 15: `battery_level`, `usable_battery_level`, `battery_range`, `ideal_battery_range`, `charging_state`, `charger_power`, `charger_voltage`, `charger_actual_current`, `charger_pilot_current`, `charge_limit_soc`, `charge_port_door_open`, `charge_port_latch`, `timestamp` | `est_battery_range`, `minutes_to_full_charge` | 60 of 75 |
| `CLIMATE` | 70 | 17:49:57.688 → 57.979 | 291 | 271 / 168 B | 181 ms | 15 of 15: `inside_temp_celsius`, `outside_temp_celsius`, `driver_temp_setting`, `passenger_temp_setting`, `is_climate_on`, `is_auto_conditioning_on`, `is_preconditioning`, `battery_heater`, `fan_status`, `is_front_defroster_on`, `is_rear_defroster_on`, `seat_heater_left`, `seat_heater_right`, `climate_keeper_mode`, `timestamp` | none | 31 of 46 |
| `LOCATION` | 72 | 17:49:58.974 → 59.270 | 296 | 268 / 165 B | 211 ms | 11 of 16: `native_latitude`, `native_longitude`, `latitude`, `longitude`, `native_type`, `geo_latitude`, `geo_longitude`, `estimated_to_raw_distance`, `estimated_gps_valid`, `heading`, `timestamp` | `corrected_latitude`, `corrected_longitude`, `gps_as_of`, `location_name`, `homelink_nearby` | 4 of 20 (`native_location_supported`, `geo_heading`, `geo_elevation`, `geo_accuracy`) |
| `TIRE_PRESSURE` | 76 | 17:50:06.057 → 06.291 | 233 | 233 / 130 B | 152 ms | 19 of 19: `tpms_pressure_*`, `tpms_last_seen_pressure_time_*`, `tpms_hard_warning_*`, `tpms_soft_warning_*` (fl, fr, rl, rr), `tpms_rcp_front_value`, `tpms_rcp_rear_value`, `timestamp` | none | 0 of 19 |
| `SOFTWARE_UPDATE` | 84 | 17:50:07.374 → 07.757 | 383 | 151 / 49 B | 240 ms | 5 of 7: `status`, `version`, `install_perc`, `expected_duration_sec`, `timestamp` | `download_perc`, `scheduled_time_ms` | 1 of 8 (`warning_time_remaining_ms`) |
| `CHARGE_SCHEDULE` | 87 | 17:50:08.889 → 12.380 | 3492 (F14) | 134 / 32 B | 152 ms | 5 of 7: `next_schedule`, `max_num_charge_schedules`, `charge_buffer`, `show_schedule_complete_state`, `timestamp` | `charge_schedules` (no entry), `charge_schedule_window` | 0 of 7 |
| `PRECONDITIONING_SCHEDULE` | 89 | 17:50:13.238 → 13.579 | 340 | 130 / 28 B | 182 ms | 3 of 5: `next_schedule`, `max_num_precondition_schedules`, `timestamp` | `precondition_schedules` (no entry), `preconditioning_schedule_window` | 0 of 5 |
| `PARENTAL_CONTROLS` | 91 | 17:50:14.343 → 14.631 | 288 | 166 / 64 B | 213 ms | 13 of 13: `parental_controls_active`, `parental_controls_pin_set`, `parental_controls_settings` and its nine fields (`speed_limit_enabled`, `current_limit_mph`, `max_limit_mph`, `min_limit_mph`, `chill_acceleration_enabled`, `require_safety_settings_enabled`, `curfew_enabled`, `curfew_start_time`, `curfew_end_time`), `timestamp` | none | 0 of 13 |

Field counts are top-level fields of the official `{{REF_REPO_DIR}}pkg/protocol/protobuf/vehicle.proto` at
`{{REF_REPO_COMMIT}}` (`ParentalControlsSettings` counted with its parent). The sample's mapping is
`{{SAMPLES_DIR}}ios/Sources/VehicleMappingCategories.swift` and `StateCategoryFields.swift`.

- **How to read `present`.** A name is listed when the generated property was non-null, so for a `oneof optional_*` scalar
  the car encoded it (a oneof member is encoded even at its default value). A wrapper such as `charging_state`,
  `charge_port_latch`, `climate_keeper_mode`, `native_type` or `status` is listed only when one known member was set
  (`VehicleMappingCategories.swift`, `member(...)`), so these five wrappers carried a member this schema knows. A repeated
  list (`charge_schedules`, `precondition_schedules`) is listed only when non-empty, so "absent" there means the car sent no
  entry. Fields the sample does not map are invisible here, whatever the car sent (F11).
- **Every new category sent `timestamp`** (8 of 8). A8.1 says all 12 messages have one; what it marks is still open (A8.5).
- **A8.4 inputs are present on this car**: the first-choice pair `native_latitude`/`native_longitude` and `native_type`,
  the WGS fallback pair and the raw `geo_*` pair with `estimated_to_raw_distance` all arrived; `corrected_*` did not (the
  proto marks them as for China cars). Which pair the sample picked, and the values, are on the screen only.
- `install_perc` came without `download_perc`; `version` was present (a oneof string is encoded even when empty; F11 says the
  reply has room for a non-empty one). What `version` names (installed or offered firmware) is not established.
- All eight data replies were the first reply to their request: no fault, no handshake (the Infotainment session restored
  from the cache at connect was accepted, a third counter-case to the 11-07 F2 path), no `WAIT`, no resend, no
  `RESPONSE_MTU_EXCEEDED`. The largest, `CHARGE`, is 353 B, below the 436 B `DRIVE` of the 11-07 report.
- Latency from the tap's sleep-read reply to the data reply: 152 to 240 ms (median 197), in the range of the 11-07 taps.

**Status reasoning.** Each tap is the controlled action, but each category ran once, on one car, with unrecorded firmware.
The project rule needs more than one controlled repetition for Confirmed, so the per-category claim is Likely. The field
lists are facts about these eight replies; as "what this category carries" they are Likely, and they say nothing about
fields the sample does not map, about values (FR-112), units or update rates. `CLOSURES`, `DRIVE`, `MEDIA` and
`MEDIA_DETAIL` gave no new field data in this run (the last three are covered by the 11-07 report F1, without field names).

**Proposed status per category for `{{LIB_DOCS_DIR}}14-lib-reading-state.md` A8.5** (the request asked for "Part B"; the
`getState` material lives in Part A, A8; the Part B items of this run are in F19):

| Category | Real-car status after this run | Evidence |
|---|---|---|
| `DRIVE`, `MEDIA`, `MEDIA_DETAIL` | Answered and verified (unchanged) | 11-07 report F1 (39 taps). This run: 3 `NotSent`, no request sent (F15) |
| `CHARGE`, `CLIMATE`, `LOCATION`, `TIRE_PRESSURE`, `SOFTWARE_UPDATE`, `CHARGE_SCHEDULE`, `PRECONDITIONING_SCHEDULE`, `PARENTAL_CONTROLS` | **Answered and verified once each** (Likely); sample-mapped field sets as in the table | F10 |
| `CLOSURES` | **Not yet answered** (Unknown). One request, lost while another client was active (F13); nothing shows the car refuses it | F13 |
| Field coverage (A8.5 item 2) | Partly observed: sample-mapped fields only, one reply each | F10, F11 |
| `speed` unit, `timestamp` meaning | Not observed (values not logged, no screen notes) | – |

### F11. Size arithmetic: what `present` cannot see — Unknown (inference)

AES-GCM does not change the length, so the logged `encrypted(NB)` is the length of the plaintext `CarServer.Response`.
Estimating the encoded size of the present fields (tag bytes from the field numbers; a `Timestamp` as 6 to 12 B; small
varints 1 to 2 B, `charge_buffer` up to 10 B) plus the `Response`/`VehicleData` wrapping (5 to 9 B) gives:

| Category | Present fields plus wrapping | Ciphertext | Not accounted for | Reading |
|---|---:|---:|---:|---|
| `TIRE_PRESSURE` | 95 to 129 B | 130 B | 1 to 35 B | The official message has exactly these 19 fields; little or nothing else |
| `SOFTWARE_UPDATE` | 27 to 39 B (with an empty `version`) | 49 B | 10 to 22 B | Room for a non-empty `version`, `warning_time_remaining_ms`, or both |
| `CHARGE_SCHEDULE` | 21 to 40 B | 32 B | up to 11 B | Consistent with no schedule entry, as `present` says |
| `PRECONDITIONING_SCHEDULE` | 18 to 28 B | 28 B | 0 to 10 B | Same |
| `PARENTAL_CONTROLS` | 46 to 59 B | 64 B | 5 to 18 B | Small remainder (larger varints, or the three `TESLEMETRY-EXT` booleans of `ParentalControlsSettings`); not identifiable |
| `CLIMATE` | 69 to 79 B | 168 B | 89 to 99 B | Additional unmapped encoded content (the official message has 31 more fields) |
| `CHARGE` | 58 to 72 B | 250 B | 178 to 192 B | Additional unmapped encoded content (60 more official fields) |
| `LOCATION` | 64 to 75 B (85 to 96 B if all four unmapped official fields were also sent) | 165 B | **at least 69 to 80 B** | More than the mapped fields and the remaining official `LocationState` fields readily explain |

- For `LOCATION` the mapped-but-absent fields (`corrected_*`, `gps_as_of`, `location_name`, `homelink_nearby`) are known to
  be absent, so with canonical encoding the ordinary official fields leave 69 B or more unexplained. The `domain` module
  `vehicle.proto` (Teslemetry snapshot) adds seven `TESLEMETRY-EXT` fields to `LocationState` (`supercharger_trt_id`, six
  `*_d` double-precision coordinates, `geo_horizontal_accuracy`), 69 to 73 B together (more if the int32 is negative). They are
  a size-compatible candidate, not an identification: the ciphertext length covers the whole `Response` (`actionStatus`, the
  `vehicleData` and category wrappers, any other fields at those levels, unknown fields), so the surplus need not lie in
  `LocationState`, and not all candidates need be present.
- For the small categories "largely consistent with the mapped field set" is the most the arithmetic says; it does not prove
  completeness.
- Nothing here changes A8.1's rule ("only fields of the official proto are supported"). It does say the `LOCATION` reply very
  likely carries coordinates in more forms than the official message lists, which strengthens A8.3's "do not log a result".

**Status.** Unknown: one reply each, and arithmetic on encrypted lengths cannot name fields. A debug-only field-number
census (H13) would settle it without logging values.

### F12. Own requests lost while another client was active fall inside interruptions of that client's reply stream — Likely (association, build 18 F1 extended); failure location and mechanism Unknown

**What the other client did** (all frames addressed to `other`, first at 17:49:30.676, right after the
`AuthenticationRequest` of 17:49:30.556): an Infotainment handshake and a signed VCSEC command, then Infotainment data
replies of 366 to 370 B every 2.04 to 2.22 s when undisturbed (for example 17:50:13.369 to 30.198, nine frames on a
2.10 s grid), and VCSEC `encrypted(16 to 20B)` replies with an empty `request_uuid` 0.78 to 0.87 s after some of its
Infotainment replies. A second VCSEC handshake at 17:49:56.719 and a second Infotainment handshake at 17:51:00.048. Who it is
is not in the log. **User confirmation (2026-11-13):** another app was running during the capture (the user's other BLE app,
as in build 18). Whether it ran on the same phone was not stated, so mechanism (b) below stays a candidate, not a finding.

**Evidence: the other client's Infotainment stream as the clock.** Its 27 intervals between consecutive Infotainment frames
on our link (17:49:30.676 to 17:51:06.113) are 2.04 to 2.22 s when undisturbed. Ten are longer than 3 s:

| Interval of the other stream | Length | Own unanswered request inside | Its estimated send | Phase after the interval's first frame |
|---|---:|---|---|---:|
| 17:49:31.667 → 17:49:50.237 | 18.57 s | 56 (VCSEC, poll 4 slot 3); then 57 to 59 (F15) | 17:49:32.415 | 0.75 s |
| 17:49:50.237 → 17:49:55.817 | 5.58 s | 66 (VCSEC, poll 5 slot 3) | 17:49:52.157 | 1.92 s |
| 17:49:58.369 → 17:50:03.798 | 5.43 s | 74 (Infotainment, `CLOSURES`) | 17:50:00.198 | 1.83 s |
| 17:50:05.898 → 17:50:11.329 | 5.43 s | 85 (VCSEC, poll 6 slot 3) | 17:50:07.638 | 1.74 s |
| 17:50:30.198 → 17:50:36.675 | 6.48 s | 108 (VCSEC, Key & Control slot 5) | 17:50:32.026 | 1.83 s |
| 17:50:36.675 → 17:50:40.098 | 3.42 s | 118 (VCSEC, poll 8 slot 6) | 17:50:37.397 | 0.72 s |
| 17:50:40.098 → 17:50:44.628 | 4.53 s | 120 (VCSEC, Key & Control slot 0) | 17:50:41.836 | 1.74 s |
| 17:50:46.610 → 17:50:51.108 | 4.50 s | 125 (VCSEC, Key & Control slot 3) | 17:50:48.437 | 1.83 s |
| 17:50:53.206 → 17:50:56.599 | 3.39 s | 134 (VCSEC, poll 9 slot 5) | 17:50:53.925 | 0.72 s |
| 17:50:56.599 → 17:51:00.048 | 3.45 s | none; the interval ends with that client's new Infotainment handshake | – | – |

- **9 of the 10 intervals longer than 3 s contain the estimated send of at least one own unanswered request (the 18.57 s one
  contains 56 to 59, the others exactly one); none of the 17 intervals of 2.67 s or less does**, although many own requests
  were answered inside them (for example attempts 86 to 101 between 17:50:12 and 17:50:24). Every own request lost after
  17:49:30 is in the table. This is a descriptive association, not a per-request rate comparison: a longer interval gives
  more room for an own request, and the intervals are consecutive, not independent trials (Codex).
- Send times are the arrival of the previous own reply in the request's chain (for 85, the VCSEC reply 83; the interleaved
  Infotainment reply 84 belongs to another chain). The phases, 0.72 to 0.75 s and 1.74 to 1.92 s, are about when that client
  would send its VCSEC read (its VCSEC replies come 0.78 to 0.87 s after some of its Infotainment replies) or its next
  Infotainment request (about 1.9 s), which suggests overlapping requests. The phase alone does not predict a loss: 65
  (1.80 s), 97 (0.75 s) and 98 (0.84 s) were answered (Codex), and several steady cycles of that client have no VCSEC frame
  at all, so the VCSEC cadence is not strict.
- Both domains are involved on our side: eight of the nine are VCSEC reads that fell in interruptions of the other client's
  Infotainment stream, one is an Infotainment `getState`. If the two events share a cause, it is something the domains share,
  such as the BLE link or the vehicle's receive path, rather than one domain being busy.
- **What the log cannot show.** The direction (our request disturbing theirs, or both disturbed by a third thing). Whether
  the other client missed its replies: a frame missing on our link may have reached that client on its own connection. The
  other client's request times. Our own send times (no `TX:` lines). The interval after an interruption varies (3.4 to
  18.6 s), so no timeout of that client can be read from it.
- **The two long silences are separate observations.** The 13.9 s link-wide silence (17:49:07.305 to 21.225, car asleep, no
  other client visible) and the 18.6 s stall that began with 56 (broadcasts still arrived at 34.065 and 34.517) may have
  causes other than the isolated losses; only the second overlaps the other client's activity.
- Comparison with build 18 F1: there the other client polled Infotainment about once per 0.94 s and losses clustered 0.55 to
  0.85 s after its previous frame, and its stream also lost a frame after each stall. With a 2.1 s client the loss phases are
  different, again near where that client's own requests would be. A single fixed window, as `16-lib-errors.md` §Queries
  while another BLE client is active now states it (0.55 to 0.85 s), does not carry over to another client's cadence; the
  old window is better read as "around that client's requests" than as its exact transmit time.

**Not the cause** (checked): sleep (the car was `AWAKE` from 17:49:25.245 for every loss in this table); `WAIT` (no `WAIT`
after 17:49:22.786, and no Infotainment `WAIT` ever seen); burst position (losses at burst positions 2 to 11); our own
`getState` (poll cycles 8 and 9 lost a slot with no `getState` anywhere near); our own interleaving on one domain (74 had the
Infotainment channel to itself).

**Mechanism candidates, all Unknown, none testable here.** (a) The vehicle handles one inbound request at a time across
clients and drops both when two overlap. (b) If the other client shares our iPhone's link (the user's other app, or the
Tesla app as phone key on the same phone), both apps write to the same characteristic over one connection. Our library
serialises only its own frames; if the other app's request is multi-chunk (`blockLength` = MTU - 3) and one of our
single-chunk frames lands between its chunks, the vehicle's length-prefixed reassembly can absorb our frame into the other
one and misalign what follows, and a long two-sided stall like 17:49:32 to 17:49:51 could be a misaligned buffer until some
reset. Unverified premises: that the other app's requests are multi-chunk (its large replies do not show this), that writes
interleaved, and how the vehicle resets a malformed buffer. Build 18 F2 found other-client frames almost only on fast
connects (7 of 8), which would fit a shared link; this run's 0.70 s connect with the other client appearing 36 s later
neither supports nor excludes it. (c) A vehicle-side busy period after a phone-key authentication: plausible for the first
stall (it began 1.9 s after the `AuthenticationRequest`), weaker as the explanation of the later isolated losses. Shared write
handling elsewhere, routing capacity, or our own receive path remain alternatives. N6 discriminates between (a) and (b)
without settling them; request chunk counts and write times (H11) would be the strongest discriminator.

**Status.** Likely for the association (9 of 10 long intervals against 0 of 17 normal ones, and the same shape in build 18),
uncontrolled. Where the frames are lost, whether the other client also missed replies, and the mechanism: Unknown.
Agreed wording with Codex: "unanswered requests are repeatedly associated with interruptions in the other-address reply
stream, near phases consistent with overlapping requests; contention of some kind on a shared transport or vehicle routing
path is Likely; which kind, the failure location and whether the other client also missed replies are Unknown."
Confirmation: N6.

### F13. Why `CLOSURES` and the first `TIRE_PRESSURE` timed out — `CLOSURES`: observed during an interruption of the other client's stream, attribution Unknown (consistent with F12); first `TIRE_PRESSURE`: sleep or link silence, Unknown which

**`CLOSURES` (line 144, `Uncertain` `Timeout(afterSend=true)`, `durationMs=5109`).**
- Its sleep read (attempt 73) was answered in 92 ms at 17:50:00.198 (`AWAKE`, `PARK`); the session was ready; `getState`
  (attempt 74) was sent at about 00.20 and drew nothing, no late reply. The deadline at 05.215 matches 5 s plus the sleep read.
- Nothing of ours competed: the poll had ended at 17:49:56.739 and the next began at 17:50:06.947; 74 was the 8th own
  request of its burst; there was no `WAIT` (the burst rule of F5 concerns VCSEC, F17).
- It is row 74 of F12: sent 1.83 s after the other client's Infotainment reply of 17:49:58.369; that client's next frame came
  5.43 s later instead of 2.1 s.
- Alternative not excluded: the car does not answer `CLOSURES` at all, or answers it only in some state. One trial cannot
  separate this from a loss; nothing in the protocol or in Go suggests a category is silently ignored (an unsupported
  request would be expected to draw a fault). A size fault would have arrived as a reply, so the size limit is not the cause.
- So: the timeout during an interruption of the other client's traffic is observed; its attribution to the F12 pattern is
  plausible but Unknown (one trial; that client's pause could be independent). `CLOSURES` itself stays Unknown until it is
  answered (N5 asks for three taps without the other client). Codex proposed this weaker wording; adopted.

**First `TIRE_PRESSURE` (line 58, 17:49:10.092 → 15.115, `durationMs=5022`).**
- It must have been the experiment button: the last poll had said `ASLEEP` (line 51), and `buttonsEnabled` requires `AWAKE`
  (`StateDiagnostics.swift:294`); the experiment path skips the sleep read (`:497`). The line does not record the mode (H11).
- The car was asleep (`ASLEEP` at 17:49:06.915 and 17:49:22.155) and the whole link was silent from 17:49:07.305 to
  17:49:21.225: our VCSEC requests 25 (slot 2) and 27 got nothing either, and no broadcast or other-client frame arrived.
  No other client was visible yet.
- Attempt 26 is this `getState` (the session was ready: no handshake line; the Infotainment path does not wait for the VCSEC
  lock). Attempt 27 is most likely the Key & Control automatic check started by a tab switch about 1 s after the tap: it
  waited for the VCSEC lock until 25's deadline (12.004), got no reply, and its one retry 5 s after its own timeout is the
  `listKeys` of attempts 28 to 35 at 17:49:21.1 (`KeyControlModel.swift:105`, `:142`, `autoRetrySeconds = 5`). Likely.
- Explanations overlap and cannot be separated: sleeping Infotainment (11-05 report F1, 6 of 6 silent), the link-wide
  silence, and (weakly) a cached session the car would not accept; the later `CHARGE` tap on the same session succeeded,
  which weakens the last one without settling this event. This tap neither adds to nor weakens the 11-05 finding.

### F14. The `listKeys` `Uncertain` pattern and the VCSEC head-of-line wait — loss: Likely (F12); head-of-line: established by the code, 3 of 3 directly checkable timings match (2 more fit)

| Poll | `listKeys` start | Result | Lost request |
|---:|---|---|---|
| 1 | 17:48:55.125 | `Success` (1081 ms) | – |
| 2 | 17:49:07.004 | `Uncertain` | 25 (slot 2), link-wide silence, car asleep (F13) |
| 3 | 17:49:22.274 | `Success` (3332 ms, one `WAIT` resend and our command interleaved) | – |
| 4 | 17:49:32.019 | `Uncertain` | 56 (slot 3), start of the 19 s stall (F12) |
| 5 | 17:49:51.735 | `Uncertain` | 66 (slot 3) |
| 6 | 17:50:07.126 | `Uncertain` | 85 (slot 3) |
| 7 | 17:50:22.363 | `Success` (842 ms) | – |
| 8 | 17:50:36.736 | `Uncertain` | 118 (slot 6) |
| 9 | 17:50:53.265 | `Uncertain` | 134 (slot 5) |

The Key & Control checks lost 27 (summary), 108 (slot 5), 120 (slot 0) and 125 (slot 3) the same way; their results are not
logged.

- **Not collision with our own `getState`**: polls 8 and 9 had no `getState`; in poll 6 the interleaved `SOFTWARE_UPDATE`
  `getState` (84) was answered while slot 3 (85) was lost.
- **Not an inherent failure of the 10 s poll**: losses fall at different slots and phases of the poll, and polls 1, 3 and 7
  succeeded. The period still matters indirectly: ten VCSEC requests every 10 s, plus Key & Control checks, raise the exposure
  and decide where the bursts land against the other client's cadence. "Every second poll" is 6 of 9 here, not a rhythm.
- **Not a VCSEC reply that arrived late**: no `closed` line for any of the 13 lost VCSEC requests, and no plaintext VCSEC reply
  among the `other` drops either (all 16 other-address VCSEC frames are encrypted, `session_info` or 83 B signed), which is where
  a late reply would land once its request had left the 32-entry closed history. 11-05 report F4 and build 18 F1 found the
  same.
- `listKeys` is the most exposed because it sends eight requests back to back in one 5 s budget
  (`VehicleInfoQueries.kt:59`); one lost request uses the rest of the budget.

**Head-of-line wait.** VCSEC calls of one connection share one mutex, and the timeout includes the wait for it
(`VcsecCommands.kt:86-87`). A call made while a lost request holds the mutex waits until that request's deadline:

| Call | Started | Waited behind | Lock released | Reply | Call duration |
|---|---|---|---|---|---:|
| Poll 5 `vehicleStatus` (60) | 17:49:47.628 | 59 (`MEDIA_DETAIL` sleep read) | 51.549 | 51.644 | 4018 ms |
| `CHARGE` tap sleep read (67) | 17:49:52.235 | 66 (poll 5 slot 3) | 56.739 | 56.833 | (tap 4837 ms) |
| `CHARGE_SCHEDULE` tap sleep read (86) | 17:50:08.889 | 85 (poll 6 slot 3) | 12.134 | 12.225 | (tap 3492 ms) |
| Poll 8 `vehicleStatus` (109) | 17:50:33.627 | 108 (Key & Control slot 5) | about 36.46 (inferred) | 36.555 | 2930 ms |
| Poll 9 `vehicleStatus` (126) | 17:50:51.946 | 125 (Key & Control slot 3) | about 53.05 (inferred) | 53.117 | 1200 ms |

For the first three the blocking call's result line gives the deadline, and each reply came 91 to 95 ms after it, the normal
round trip (unblocked poll `vehicleStatus` calls took 80 to 102 ms). For the last two the Key & Control check's start is not logged, so
its deadline is inferred from its first reply minus one round trip; the replies fit at about 70 to 95 ms after it. So the
long durations are waiting, not slow replies. The `DRIVE` tap's sleep read (57) also waited about 2.3 s behind 56 and had
only about 2.7 s left for its own reply. This is designed behaviour (FR-049, SDD §5) and correct; its cost is that
one lost VCSEC request can delay every VCSEC call for up to 5 s, including a tap's sleep read and, in principle, a command.

### F15. `NotSent reason=sleepReadFailed:Timeout(afterSend=true)` (17:49:34 to 17:49:51): classification correct; the car had reported `AWAKE` 9 to 21 s earlier — log and code agree

- VCSEC had reported `AWAKE` before all three taps: broadcast 17:49:25.245, poll reply 17:49:31.903 (`AWAKE`, `PARK`),
  broadcasts 17:49:34.065 and 34.517. The normal buttons were enabled because the last poll said `AWAKE`. `AWAKE` does not
  prove every subsystem had finished waking (F16), but the requests that failed were VCSEC `vehicleStatus` reads, which this
  car answered while asleep earlier in the same run (lines 28, 50, 67). Better wording than "while the car was waking": these
  prerequisite VCSEC reads timed out during the shared stall of F12 (17:49:32.4 to 51.6) despite recent `AWAKE`
  observations, and no Infotainment request was sent.
- Attempts: `DRIVE` (start 34.754) used attempt 57 after waiting about 2.3 s for the VCSEC lock behind 56; `MEDIA` (40.645)
  used 58 and `MEDIA_DETAIL` (46.545) used 59, each with the lock free. All three consumed an attempt number, which the
  dispatcher assigns at registration just before the write (`Dispatcher.kt:428`), so each write was invoked.
- **ADR-0010 (2026-11-07 amendment, #149)**: "sent" means the first BLE write was invoked; a deadline after that is
  `Uncertain(Timeout(afterSend = true))`. That is what the library returned for each `vehicleStatus`. A deadline during the lock
  wait would have been `Timeout(afterSend = false)` with no attempt consumed; none occurred.
- **#150** (no re-signed copy after a failed write) does not apply: these are repeat-safe unsigned reads, and no write failed.
- **Sample rule** (`{{SAMPLES_DOCS_DIR}}04-monitor-app.md` §State diagnostics: "NotSent when the app stops before sending
  (… `vehicleStatus()` failed …)"): the tap stopped before `startSession` and `getState`, so `NotSent` is right, and the reason
  carries the sleep read's error type correctly (`StateDiagnostics.swift:504`). FR-104 holds: the sample did not infer
  `ASLEEP` from the silence.
- Minor wording point (H15): the `afterSend=true` sentence names sleep first among the possible causes ("수면 중이거나, 응답이
  사라졌거나, 링크가 끊겼을 수 있습니다"). For these three the car was awake and the reply was lost. VCSEC answered a sleeping car
  in this run too (lines 28, 50, 67), but it also went silent while asleep (F13), so the sentence is not wrong, only ordered
  unhelpfully for a sleep read.

### F16. Wake by a signed VCSEC command on a stale cached session: resync, `AWAKE` in 0.15 s, Infotainment by 5.6 s — sequence Likely; as timing rules Unknown

- Attempt 44 drew `OPERATIONSTATUS_ERROR/MESSAGEFAULT_ERROR_INCORRECT_EPOCH session_info=92B payload=none auth=none` at
  17:49:23.988 (line 77). Attempt 45 came back `encrypted(0B) auth=gcm-ok` 1.108 s later (line 78), which includes the 1 s
  retry interval. No VCSEC handshake line precedes it, so the session came from the cache at connect. This is the 11-07 F2
  path again: the third observation overall (Infotainment once, VCSEC twice), and the second time a cached VCSEC session used on a car that
  had been asleep was refused with `INCORRECT_EPOCH` (11-07 C2 was the first). The cached Infotainment session of the same
  connection was accepted (F10), as in 11-07 C2.
- Attempt 47 (17:49:25.424) is a `vehicleStatus` inside the poll's `listKeys`, which fits the Key & Control tab's re-read after
  every command (11-07 report F2), so the command most likely came from Key & Control. Its lock state did not change
  (`LOCKED` before and after) and the car woke right after, so it was most likely `깨우기` (`wakeUp`). Not excluded: a `lock` of
  a locked car, another command, or an independent wake stimulus at the same moment. Commands are not logged (H11), so the
  user's recollection would settle it.
- Times below are from the receipt of reply 45 (the API's own return time is not logged). After it: two `ASLEEP` frames
  with all closures `UNKNOWN` at +64 and +120 ms, `AWAKE` at **+149 ms**
  (17:49:25.245, `frontTrunk` still `UNKNOWN` for that one frame, as B3.1 describes), all closed at +178 ms. Gear stayed absent
  (`UNKNOWN`) in the broadcasts at 25.274 and 27.168 and in the reply at 25.424; the first `GEAR_PARK` came at 17:49:30.764,
  **+5.67 s**. The other client's Infotainment handshake was answered at 17:49:30.676, **+5.58 s**, the first sign in the log
  that Infotainment answered anyone (our first Infotainment request after the wake came at 17:49:56.9, delayed by F12, not by
  the wake).
- Reading: `AWAKE` in `vehicleStatus` came almost at once and well before Infotainment was seen answering. An `AWAKE`
  indication does not establish Infotainment readiness. A8.2 says "wait for `AWAKE`, then start the Infotainment session";
  this observation neither shows that a `getState` right after `AWAKE` would have failed nor that it would have worked. The
  5.58 s bounds the time to the first Infotainment answer seen from above; the other client's request time is unknown and
  nobody probed earlier, so Infotainment may have been ready sooner. Gear leaving `UNKNOWN` coincided with that answer, once.
- Compared with the 11-07 F7 wake (an unlock with a phone key nearby, `AWAKE` within 4.6 s): this one was faster to `AWAKE`;
  neither measures Infotainment readiness directly.

**Status.** The fault, resync and resend sequence is observed (Likely as a behaviour, with 11-07 F2). The command identity
is Likely. The timings are one observation each: Unknown as rules. N7 measures them.

### F17. VCSEC `WAIT` at the 17th and 15th reply of a burst — Likely (11-05 F5 and 11-07 F5 refined: the position varies)

| Burst start | Replies in burst | First 15 replies took | `WAIT` at position (attempt) | Resend answered after |
|---|---:|---:|---|---:|
| 17:48:54.944 (connect poll plus Key & Control check) | 19 | 1.95 s (first 16: 2.04 s) | 17 (17) | 1.080 s |
| 17:49:21.225 (Key & Control retry plus poll plus our command, after 13.9 s of silence) | 22 | 1.56 s (first 14: 1.47 s) | 15 (42) | 1.109 s |

- Bursts split at gaps over 1.5 s between own replies (1.0 s gives the same positions). Both bursts that reached 15 own
  replies had a `WAIT`; the twelve shorter bursts had none.
- All reports together: 20 own `WAIT`s, at positions 16 (16 times), 17 (twice), 15 (once) and 6 (once). The slower burst got
  one more answered request and the faster one fewer, which fits a rate (requests per time) or a refilling allowance better
  than a fixed count; with two cases this is a direction, not a rule. Whether the unanswered 25 and 27, sent 9 to 14 s before the
  second burst, counted against it is unknown.
- No same-request final reply after either `WAIT` (no `closed` line): unsigned VCSEC reads are now 0 of 13. No signed command
  met a `WAIT`; the ADR-0015 question for commands stays open (11-07 report N3, H7).
- Neither `WAIT` relates to the `getState` timeouts (both Infotainment, F13).

### F18. Hours-long scan attempts are app suspension, ended by a phone unlock — Likely (build 18 F3 again); ties to #124 L2 and #126

| Attempt | `connecting` | Result logged | Elapsed | App state |
|---:|---|---|---:|---|
| 1 | 10:14:48.508 | Scan timeout 10:15:18.524 | 30.02 s | Foreground (nominal) |
| 2 | 10:15:20.641 | Scan timeout **10:59:06.546** | **43 m 46 s** | Background from 10:15:31.794; the line came 22 ms before `scenePhase inactive` and 38 ms before `protectedData available` |
| 3 | 10:59:06.585 (`start reason=protected-data effect=woke`) | Scan timeout 10:59:36.598 | 30.01 s | Foreground (nominal) |
| 4 | 10:59:44.857 (backoff 8 s fired after 8.26 s, 6.6 s into the background) | Scan timeout **17:48:54.085** | **6 h 49 m 9 s** | Background; the line came 54 ms before `scenePhase inactive` and 73 ms before `protectedData available` |
| 5 | 17:48:54.159 (`start reason=protected-data effect=woke`) | `connected` 17:48:54.861, scan hit 104 ms, -82 dBm | 0.70 s | Foreground |

- A 30 s timer that fires 43 minutes or 7 hours late, in the same few milliseconds as the process resuming, means the process
  did not run in between. The loop logic is not at fault: when the app ran, the timeouts were 30.01 to 30.02 s, and the
  unlock path woke a pending backoff at once (`effect=woke`) and connected in 0.70 s.
- Attempts 1 and 3 ran 30 s in the foreground without an advertisement, so the car was out of range (or not advertising) at
  10:15 and 10:59. When it came within range before 17:48 is unknown, so this run cannot say how long suspension kept the
  phone from a reachable car. The short files of builds 25 and 26 show the same: scans that never report because the process
  stops.
- Sample scans are by name, which iOS does not deliver to a suspended app; this is the open question of build 18 F3 and the
  #124 scenario L2 (`{{SAMPLES_DIR}}ios/README.md` §링크 안정화 시나리오), input for #126. L2 was not run (no marks).
- The connection ended 6.1 s after the phone locked and 16.5 s after a walk-away lock (`NOT_PRESENT` from 17:50:30.946),
  last frame 1.8 s before; the connection had opened at -82 dBm. Walking away is the simplest reading, as in 11-07 F8.

### F19 (Part B). Field 8 and `AuthenticationRequest` — Unknown; two corrections to B2.4 — Likely

**Field 8 (`unknownFields=[1.8]` in all 17 `Status` events and in every `vehicleStatus` reply).** Decoded from `raw`
(`{1, 2, 3}` = sub-fields 1, 2, 3):

| Time | Line | State | Field 8 | Change |
|---|---|---|---|---|
| 17:49:25.160, 25.216 | 80, 82 | `ASLEEP`, gear absent, closures `UNKNOWN` | 1, 1, 1 | – |
| 17:49:25.245, 25.274 | 84, 86 | `AWAKE`, gear absent | 1, 1, 1 | – |
| 17:49:27.168 | 93 | same | **1, 1, 2** | Field 8 only |
| 17:49:30.765 | 99 | `PARK`, `NOT_PRESENT` | 1, 1, 2 | gear |
| 17:49:34.067 | 110 | same | **2, 1, 2** | Field 8 only (new combination) |
| 17:49:34.517 | 112 | same | 2, 1, 1 | Field 8 only |
| 17:49:52.515 | 127 | same | 1, 1, 1 | Field 8 only |
| 17:50:23.118 | 190 | same | 2, 1, 1 | Field 8 only, 1.26 s before `UNLOCKED` |
| 17:50:24.375 to 30.946 | 195-208 | `UNLOCKED`, door open and closed, `PRESENT` then `NOT_PRESENT` | 2, 1, 1 | – |
| 17:50:31.336 | 211 | `UNLOCKED`, `NOT_PRESENT` | 1, 1, 1 | Field 8 only, 0.39 s after `NOT_PRESENT` |
| 17:50:51.347 | 246 | `LOCKED` | 1, 1, 1 | lock |

Raw example, line 110 (28 B, no identifiers): `…52120a1010011801200138014206080210011802`, where `4206 08 02 10 01 18 02` is
field 8 = `{1: 2, 2: 1, 3: 2}`.

- Six more frames that changed only in field 8 (B3.1: "a change of field 8 alone produces a broadcast").
- `{2, 1, 2}` is a combination B3.1's table does not have, and 6 of the 8 frames with sub-field 1 = 2 here were `NOT_PRESENT`
  (B3.1: 108 of 111 were `PRESENT`). Presence does not define sub-field 1, as B3.1 already says; this weakens the association.
- Sub-field 1 was 2 from 17:49:34.067 to 52.515, which spans the stall of F12 (32.4 to 51.6) closely, and from 17:50:23.118 to
  31.336, around a door opening by someone (with no stall). Two windows; Unknown.
- Sub-field 3 was 2 from 27.168 to 34.517 (7.3 s), parked with nobody present, as B3.1 describes.

**`AuthenticationRequest`.** Two wrapped 41 B frames, both `requestedLevel = DRIVE`, `reasons = [ENTERED_HIGHER_AUTH_ZONE]`
(packed, one value 8), token 20 B (not quoted; different in the two frames), both while `LOCKED` and `NOT_PRESENT`.
- 17:49:30.556: followed by the other client's Infotainment `session_info` (+0.12 s) and its signed 83 B VCSEC success
  (+0.18 s). Then the start of that client's polling.
- 17:51:01.517: followed by the other client's signed 83 B VCSEC success (+0.39 s); its Infotainment `session_info` came 1.47 s
  before.
- With 11-07 F7 (+0.09 s), 3 of 3 `AuthenticationRequest`s in the last two captures were followed within 0.4 s by another
  client's signed VCSEC reply. A phone key answering the vehicle is the obvious reading; who it is, Unknown. B4's "another phone
  key approaching: not recorded" stays open (no mark).

**Corrections for B2.4 "Waking vehicle"** (Likely, one wake each, with B3.1 round 3 and 11-07 F7):
- "they become real values within about 0.5 s": the closures did (within 0.18 s), but **gear stayed absent for 5.6 s** after
  `AWAKE` (F16).
- "The lock state reads `UNLOCKED` in them too": here the waking frames carried `LOCKED` (field 2 = 1 encoded), on a car that
  stayed locked. The statement should be "can read `UNLOCKED`".

## Scenario rows

README §추가 카테고리 확인 (build 27) and the #118 rows still open:

| Row | Status | What was seen (no marks; inferred) |
|---|---|---|
| Conditions (N1 rules) | **Not met** | Another client active from 17:49:30; experiment button used on a sleeping car; wake from Key & Control (the runbook says the car is woken by hand); no marks |
| 1 `cat CHARGE` | `Success` once, 353 B | Fields in F10. Values, range unit and screen comparison: not in the log |
| 2 `cat CLIMATE` | `Success` once, 271 B | F10 |
| 3 `cat LOCATION` | `Success` once, 268 B | F10, F11. No coordinate or name in any line (checked) |
| 4 `cat CLOSURES` | **`Uncertain` once**, no retry | F13 |
| 5 `cat TIRE` | `Uncertain` once (experiment, asleep), then `Success` once, 233 B | F10, F13 |
| 6 `cat SWUPDATE` | `Success` once, 151 B | F10. The screen's `version` row would record the firmware question if noted |
| 7 `cat CHGSCHED` | `Success` once, 134 B, no schedule entry | F10 |
| 8 `cat PRECOND` | `Success` once, 130 B, no schedule entry | F10 |
| 9 `cat PARENTAL` | `Success` once, 166 B | F10 |
| Reply size per category | Observed | 130 to 353 B, no `RESPONSE_MTU_EXCEEDED` |
| Log check (values) | Pass | 0 coordinate-shaped numbers in `query` lines; only `present=[…]` names |
| S5 two `Uncertain` kinds | Timeout kind only | 2 `getState` `Uncertain(Timeout(afterSend=true))`; size kind not seen |
| S6 cached session | Observed again | Infotainment cache accepted at first use; VCSEC cache refused with resync (F16) |
| T4 `WAIT` on a signed command | **미관찰** | F17 |
| T5 / S10 asleep | Experiment tap once, asleep, Uncertain | F13 (not separable from link silence) |
| A8.5 wake time | One observation | `AWAKE` +0.15 s, gear and first Infotainment answer seen by +5.6 s after the reply to the (Likely) `wakeUp` (F16) |
| #124 L1 to L6 | Not run | F12 is an uncontrolled L1-like observation; F18 an uncontrolled L2-like one |

## Library hardening

| # | Item | Evidence | Proposal | Owner, priority |
|---|---|---|---|---|
| H8 | A lost repeat-safe read consumes its whole budget, and with the VCSEC mutex delays every later VCSEC call | F12, F14: 15 lost requests, no late reply seen (bounded: no `closed` line, no plaintext VCSEC reply among `other` drops); of the 12 lost VCSEC requests that had a successor, the next own request was answered 8 times, the other 4 were inside the two long silences | Input for #125, as a bounded read recovery to be tested, not an established policy. Constraints (with Codex): only audited repeat-safe VCSEC reads at first (`vehicleStatus`, `keySummary`, `keyInfoBySlot` inside `listKeys`); keep the per-connection VCSEC serialization and the caller's end-to-end budget; **one** silence resend with a new request and routing address, after a silence much longer than the round trip (observed 80 to 120 ms for VCSEC), counted from transport submission and not from the mutex wait, with modest jitter so it does not land on the same unfavourable phase; keep `Timeout(afterSend = true)` while any written copy is unanswered; handle a reply to the first copy racing the resend and close every pending registration. Never for commands (ADR-0010 2026-11-08). Fresh routing addresses keep replies correctly associated, but they do not authenticate unsigned reads; that trust limit is separate. Extra traffic may add contention, and this run cannot show that a resend after about 1 s would succeed. Deviates from Go (rule 5): ADR needed. Extending it to `getState` should wait for data from the VCSEC part | `library-developer`, Medium (decision first) |
| H9 | The manual's "another BLE client" window is cadence-specific | F12 | `16-lib-errors.md` §Queries while another BLE client is active: replace the fixed 0.55 to 0.85 s window with "near the other client's own requests; the losses fall inside interruptions of that client's reply stream (here 9 of its 10 long intervals, none of its 17 normal ones)"; add that `getState` and `vehicleStatus` are lost the same way (1 of 9 own Infotainment requests during the traffic; 3 sleep reads in an 18 s stall visible in both streams); keep Likely and say the other client's cadence decides the timing | `library-developer` (MD), Medium |
| H10 | Head-of-line wait is not documented | F14: 1.2 to 4.6 s waits | `16-lib-errors.md` §Retries and timeouts (or `14-lib-reading-state.md` A1): a VCSEC call can spend most of its `timeout` waiting for an earlier VCSEC request of the same connection; `Timeout(afterSend = true)` can therefore come after a short own wait | `library-developer` (MD), Low |
| H11 | The log still cannot say which button or command ran (11-07 H4), nor when a request was written | F13 (experiment inferred), F16 (wake inferred), Key & Control `listKeys` results invisible; all send times inferred | One line per Control and Key & Control command (`call`, `result`, error type, `durationMs`, `startedAt`, no values); `mode=normal|experiment` on the `getState` line; a `query` line for the Key & Control automatic `listKeys` with a `source` field. Library side (11-05 H1, 11-07 H5): send-side observe lines with attempt, registration, write invoked, write completed and retry index, so that F12 phases and an H8 threshold can be measured rather than inferred | `ios-developer` (sample lines), `library-developer` (send-side lines), Medium |
| H12 | The sample generates most of the exposure | F14: 10 VCSEC requests every 10 s plus Key & Control checks; taps waited behind lost slot requests | Run `listKeys` only when `keySummary`'s slot mask changes or on demand; let the Key & Control check reuse the poll's last `listKeys` (11-05 H4); for the tap's sleep check use a `sleepStatus` from `events` or the poll that is at most a few seconds old (A8.2 allows `events`) and read `vehicleStatus` only when none is fresh. That changes the documented per-tap check, so specify the freshness limit and the connection generation, drop the value on disconnect, and keep the rule that a cached `AWAKE` does not guarantee Infotainment readiness (F16) | `ios-developer` (with `library-developer` if A8.2 or the sample spec changes), Medium |
| H13 | Field coverage cannot be measured without logging values | F11 | A debug-only census that logs the field numbers present in a `VehicleData` (numbers only, nested message numbers as paths, no values, never for `LOCATION` sub-values), off by default; or a sample-side list of all generated properties that are non-null, names only. Decide whether names of `TESLEMETRY-EXT` fields may appear | `library-developer` with `ios-developer`, Low |
| H14 | A8.2 wake guidance waits only for `AWAKE` | F16: `AWAKE` 0.15 s after the command's reply, first Infotainment answer seen 5.58 s after it, gear `UNKNOWN` until 5.67 s | A8.2 (wording agreed with Codex): "In one capture, a successful authenticated VCSEC command reply, inferred to be `wakeUp`, preceded an `AWAKE` broadcast by about 0.15 s; the first Infotainment reply seen came about 5.6 s after the command reply. An `AWAKE` indication does not establish Infotainment readiness." Keep the existing advice of a longer `timeout` for the first query after a wake; a gear other than `UNKNOWN` may serve as a hint (Unknown as a rule). B2.4 corrections (F19) | `library-developer` (MD), Medium |
| H15 | Sleep-read failure sentence | F15 | For `sleepReadFailed`, list a lost reply (another BLE client, link) before sleep | `ios-developer`, Low |
| H16 | ADR-0015 / FR-105 evidence (11-07 H2) | F16: third untagged-fault-with-session-info recovery, a VCSEC command (most likely `wakeUp`, a candidate repeat-safe operation) | Add to the FR-105 plan's evidence; no new proposal | `library-developer`, as 11-07 H2 |

No library crash or protocol violation. The library behaved as documented: verified replies were delivered, the stale VCSEC
session was recovered, `WAIT` was resent after about 1.1 s, a lost request ended `Uncertain(Timeout(afterSend = true))` after a
write, no request ended `afterSend = false` after a write (the F4 defect fixed by #149 did not recur), and no `getState` was
resent after a result.

## Next scenarios

**N5. Repeat the category run cleanly (README §추가 카테고리 확인, N1 rules).**
1. First a mark with firmware and model from the car screen, the app build, and the state of other apps. Write the
   `SoftwareUpdate` screen's `version` row next to the car screen's firmware.
2. Before starting, make sure no other client is active: no Tesla app in use, the user's other BLE app force-quit. Check the
   Log tab: no `to=address(16B, other)` line for a minute. If such lines keep coming, write a mark `other client on` and say
   which apps are installed and which phones are nearby.
3. Wake the car by hand (open a door from inside or use the screen), wait for `AWAKE` and a gear, then tap each of the nine
   categories once with a mark before each. **Tap `Closures` three times**, 10 s apart.
4. Set the poll interval back to 30 s for this run, so polls do not crowd the taps.

**N6. #124 L1 with a split (discriminates F12 candidates (a) and (b)).** Parked, car awake, app in the foreground, poll only
(no taps), same poll interval in every block. Blocks of 5 minutes, each with a mark: `client on (same phone)` with the other app
on this iPhone, `client off`, `client on (second phone)` with the same app on a second phone at the same distance; then repeat
the three blocks in a different order. Write down the other app's polling setting. Count `listKeys` `Uncertain` and lost
requests per block.

| Result | Reading |
|---|---|
| Losses mainly with the same-phone app | Favours a shared-link or app interaction, framing included |
| Losses with either phone | Favours a shared vehicle capacity, other mechanisms still possible |
| Losses with the app off as well | That app is not necessary |

Replies to a client on the second phone may not reach our monitor, so record that client's view too if it has a log. With
H11's send-side lines, check the chunk count of each request: if all relevant requests are single-chunk, the interleaving
candidate (b) is excluded.

**N7. Wake timing (A8.5, F16, H14).** Car asleep, other clients off. Mark `wake`, press `깨우기` once in Key & Control (needs H11
to log it). Then tap normal `Drive` at about +2, +4, +6, +8 and +10 s after the `AWAKE` broadcast, each with a mark. Repeat on
another day. Do not use the experiment button.

**N8. #124 L2 (background reconnect, F18).** As in the README: phone locked, walk away until the link drops, return, wait 5
minutes without touching the phone; then the same with the app in the foreground.

**N9. Stale VCSEC cache (F16, 11-07 N1 step 2).** After the car has slept, connect and send one `lock` from Key & Control with a
mark. Expected: `INCORRECT_EPOCH` with session info, then `gcm-ok` about 1.1 s later. A third VCSEC case would make the
"VCSEC epoch changes across a sleep" reading testable (now 2 of 2, both uncontrolled).

## Hand-offs

- **`library-developer`**
  - `14-lib-reading-state.md` A8 header and A8.5: eight categories answered and verified once (F10 table, scope: one car,
    firmware unrecorded, sample-mapped fields only); `CLOSURES` not yet answered; coverage partly observed. Optionally list the
    observed field names per category as "seen on one car".
  - A8.2 and B2.4: H14 and the F19 corrections. B3.1 field 8: the new `{2, 1, 2}` combination and the weaker presence
    association (F19).
  - `16-lib-errors.md`: H9 (another-client section), H10 (head-of-line wait).
  - #125 decision with H8 (Go deviation; ADR). ADR-0015 / FR-105 evidence: H16.
  - No mismatch between the manual and Go was found in this run.
- **`library-api-request`**: none. The public API was enough.
- **`ios-developer`**: H11 (command lines, `mode`, Key & Control `listKeys` line), H12 (sample load), H15 (sentence); README
  text for N5 to N9 (marks first, clean-client check, three `Closures` taps, poll at 30 s for the run).
- **`android-developer`**: none.
- **User**: (1) Was the other BLE client the Tesla app (phone key), your other BLE app, or another phone? It appeared at
  17:49:30 with an authentication request and polled about every 2 s. (2) Did you press `깨우기` in Key & Control at about
  17:49:23? (3) Did you switch to the Key & Control tab right after the first `TirePressure` tap at 17:49:10? (4) The screen
  values for `SoftwareUpdate` (`version`) and the car's firmware, if you noted them. (5) Next time, a mark before each tap.

## Codex

Consulted through the Codex discussion MCP (`gpt-6.1-sol`, effort high), two rounds, on `main` at `7a63472` (Codex confirmed
branch and HEAD; read-only). Codex reread the log, the dispatcher, `VcsecCommands`, `VehicleInfoQueries`, the sample sources,
the protos and the Go code, and recomputed the timings.

| Point | Claude's position | Codex's view | Outcome |
|---|---|---|---|
| F12 claim | "Mutual loss": both clients lose a message, 9 of 9 | Timing alignment reproduced; but the log only shows gaps on our link, not that the other client missed anything; phase is not a strict window (65, 97, 98 answered); a missing "VCSEC 0.8 s later" frame is weak evidence (its cadence is irregular); no timeout can be read from the recovery gaps; keep the stalls separate | Adopted: association wording, Likely; failure location and mechanism Unknown |
| F12 metric | Interval table, "exactly one" loss per long interval | Fair as a descriptive association; say "at least one" (the 18.57 s interval holds 56 to 59); not a per-request rate; intervals not independent; direction unknown | Adopted |
| F12 mechanism (b) | Shared-link frame interleaving as a candidate | Plausible enough to list as Unknown; premises unverified (multi-chunk requests, interleaving, buffer reset); N6 discriminates but does not settle; repeat and reorder blocks; chunk counts are the best discriminator | Adopted (F12, N6, H11) |
| F13 `CLOSURES` | Likely due to F12 | One trial cannot separate a category-specific silence; "Unknown, consistent with F12" | Adopted |
| F13 first `TIRE_PRESSURE` | Sleep or link silence | Agrees; add an unusable cached session as a weak alternative, weakened by the later success | Adopted |
| F14 | Not caused by our `getState` or the poll period; head-of-line wait | Agrees on head-of-line (60, 67, 86 checked directly; 109 fits with an inferred deadline); "not the poll period" too categorical | Adopted: "not an inherent failure of the 10 s poll" |
| F15 | Correct classification; car awake, not waking | Agrees on the classification; `AWAKE` does not prove every subsystem was awake | Adopted: "despite recent `AWAKE` observations" |
| F16 | Likely `wakeUp`; `AWAKE` +0.15 s, Infotainment +5.58 s | Agrees with the sequence; command identity reasonable, not proven; measure from reply receipt; 5.58 s is not a readiness duration | Adopted, including the A8.2 wording in H14 |
| F11 | `LOCATION` surplus matches the `TESLEMETRY-EXT` extras | Arithmetic sound under canonical encoding; the surplus can sit anywhere in the `Response`; say "additional unmapped encoded content" for `CHARGE` and `CLIMATE` | Adopted; status Unknown |
| H8 | Silence resend of repeat-safe reads "would rescue most" | Worth proposing as bounded read recovery, rescue rate unproven; "never late" bounded by the 32-entry history; successor count 8 of 12; constraints (one resend, VCSEC serialization kept, timer from submission, jitter, `afterSend = true` kept, racing replies handled); routing addresses solve association, not authenticity | Adopted in full; the bounded no-late-reply check (no plaintext VCSEC among `other` drops) added by Claude and confirmed by Codex |
| H12 | Reuse a fresh sleep state for taps | Justified; specify freshness and connection generation, invalidate on disconnect, cached `AWAKE` is not Infotainment readiness | Adopted |

User answer received after the discussion: the other client was the user's other app (see F12). This confirms the
identity of the client, not the mechanism; statuses are unchanged.

Agreed: every finding and status above. No disagreement remains. Open questions both sides flagged for the user: whether the
other app was on the same phone during this capture, and whether attempts 44 and 45 were the `깨우기` button.
