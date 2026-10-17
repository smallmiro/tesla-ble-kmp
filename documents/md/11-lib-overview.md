# tesla-ble-kmp Library Manual (Entry Point)

Files `11-lib-*.md` to `19-lib-*.md` document **this repository's library**, tesla-ble-kmp: a Kotlin Multiplatform
(Android + iOS) port of the BLE path of `vehicle-command`. Files `00`–`10` document the Go original and are the
reference for porting. The human-readable version of these pages is `../html/<lang>/11-lib-overview.html` to
`19-lib-architecture.html`.

These pages describe the code on `main`. They are maintained by agents: every PR that changes library behaviour,
public API or milestone status updates the affected `1x-lib-*.md` page **and** the matching HTML page in `ko/`, `en/`
and `ja/` in the same PR (AGENTS.md §3 rule 11 and §7).

## Status (M3)

| Area | State | Page |
|---|---|---|
| Protocol core, dispatcher, sessions, retry, session cache (M0–M2) | Done | `16-lib-errors.md`, `19-lib-architecture.md` |
| BLE scan and connect (Kable), key store, session cache storage, facade `TeslaBle` / `Vehicle` (M3) | Done in code; real-vehicle check in progress | `12-lib-getting-started.md`, `17-lib-platform-notes.md` |
| Read-only queries without a key: `vehicleStatus()`, `keySummary()`, `listKeys()` (M3) | Done | `14-lib-reading-state.md` |
| Key enrollment (add-key-request), VCSEC control (lock, unlock, trunk…) (M4) | **Not implemented** | `13-lib-pairing.md`, `15-lib-commands.md` |
| Infotainment control and `getState()` (M5) | **Not implemented** | `14-lib-reading-state.md`, `15-lib-commands.md` |
| Packaging: GitHub Packages + Swift Package Manager (M6) | **Not implemented** | this page, "Installation" |

Do not document or call an API that is listed as not implemented. Planned names come from `docs/prd/PRD.md` and may
change when the milestone lands.

## Platforms and packaging

- Android 12 (API 31) or later, iOS 16 or later. Foreground use only.
- JVM target exists for tests and experiments (in-memory key store and session cache).
- Package root `dev.mirolab.teslable`. Public types live in `dev.mirolab.teslable` (facade),
  `dev.mirolab.teslable.model` (values and results) and `dev.mirolab.teslable.port` (key store, scan, logger).
- iOS framework name `Teslable` (exported by `:sdk`, Swift interop through SKIE).

## Installation

Not published yet (M6 adds GitHub Packages + SPM). Build from source:

| Target | Command |
|---|---|
| Android / JVM | `./gradlew :sdk:assemble` |
| iOS simulator framework | `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` |
| Sample apps | `samples/android` (Gradle module), `samples/ios` (`xcodegen generate`, see `samples/ios/README.md`) |

## Reference files by task

| Task | Files to read |
|---|---|
| Write app code that scans, connects and reads state | `12-lib-getting-started.md`, `14-lib-reading-state.md`, `16-lib-errors.md` |
| Handle keys (create, look up, delete, check enrollment) | `13-lib-pairing.md`, `17-lib-platform-notes.md` §Key storage |
| Know which commands exist and which are planned | `15-lib-commands.md` |
| Map a `VehicleError` to app behaviour, understand retries | `16-lib-errors.md` |
| Android permissions, iOS `Info.plist`, backup rules, session cache location | `17-lib-platform-notes.md` |
| Diagnose scan, connect, pairing or session problems | `18-lib-troubleshooting.md` |
| Change library internals (modules, dispatcher, cache) | `19-lib-architecture.md`, then `docs/sdd/SDD.md` |

## File list

| File | Contents |
|---|---|
| `11-lib-overview.md` | This page: status, platforms, installation, reading order |
| `12-lib-getting-started.md` | End-to-end flow and full Kotlin / Swift examples |
| `13-lib-pairing.md` | `VehicleKeyStore`, `KeyPolicy`, `KeyProtection`, public key bytes, enrollment check, planned add-key-request |
| `14-lib-reading-state.md` | `vehicleStatus()`, `keySummary()`, `listKeys()`, result types, wake behaviour, planned `getState()` |
| `15-lib-commands.md` | Implemented session API and the planned control API table (method ↔ FR ↔ domain ↔ milestone) |
| `16-lib-errors.md` | `VehicleResult`, `VehicleError` hierarchy, transport and key store errors, retry and timeouts |
| `17-lib-platform-notes.md` | Android / iOS requirements, key storage, session cache, clocks, connection slots |
| `18-lib-troubleshooting.md` | Symptom → cause → action table, debug logging |
| `19-lib-architecture.md` | Contributor summary: modules, command path, concurrency, adapters |
