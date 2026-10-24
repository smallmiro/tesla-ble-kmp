# Sample Requirements (SFR)

Sample requirements use the prefix `SFR-` so they never collide with the library's `FR-`/`NFR-` IDs in
`docs/prd/PRD.md`. They were moved out of the PRD on 2026-10-24: the PRD now covers the library only and points here.

| ID | Requirement | Platform | Status | Origin |
|---|---|---|---|---|
| SFR-001 | Android sample (Compose) covering the library flow: VIN input, scan, connect, pairing with progress events, key list and removal, lock / unlock / trunk / frunk, `vehicleStatus()`, wake-up, uncertain-result display with re-query, debug log view. Infotainment items (climate on/off, charge start/stop, at least one `getState`) are added when the library ships them (M5) | Android | VCSEC flow done (M4); debug log view not done (`DebugLogger` writes to Logcat only; nothing is shown on screen); Infotainment items wait for M5 | PRD FR-110 |
| SFR-002 | iOS sample (SwiftUI) with the same flow as SFR-001 through the SKIE Swift API, plus the observation mode (collects `observe` log lines, mark button, copy-all) | iOS | VCSEC flow and observation mode done (M4); the observation mode is now part of the Log tab of the monitor app (SFR-016); Infotainment items wait for M5 | PRD FR-111 |
| SFR-010 to SFR-017 | Monitor app: tabs, saved VIN and auto-connect, background reconnect, screen kept on, live state from `Vehicle.events`, periodic queries, JSON Lines log with marks, pull script. Details in `04-monitor-app.md` | Android, iOS | Built on both. Android and iOS: unit tests pass, real-device check pending (iOS background reconnect unconfirmed until the plan Task 2 spike). Pull script: Android and iOS done | Design 2026-10-24 |
| SFR-018 | Key & Control tab: four steps (key, connect, enroll, control) with done / to do / blocked hints and a button grid of the available VCSEC commands; each command ensures a VCSEC session first, is sent once, and is followed by `vehicleStatus()` (the re-query after `Uncertain`). Details in `05-key-control-tab.md` | Android, iOS | Built on both (Android `0.3.0`, iOS `0.5.0 (12)`); unit tests pass, real-device check pending | Request 2026-10-24 |

## Rules for this table

- A new sample requirement gets the next free `SFR-` number. The monitoring app planned in
  `docs/superpowers/specs/2026-10-24-vehicle-events-monitor-design.md` starts at `SFR-010`.
- A requirement that needs a library API that is not "available" in the library manual is written here with the status
  "waits for library" and the API request is raised with the `library-api-request` skill (`02-constraints.md`).
- Status words: "planned", "in progress", "done", "waits for library", "waits for M5".
