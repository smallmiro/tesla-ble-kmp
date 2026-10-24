# tesla-ble-kmp Sample Apps (Entry Point)

These pages are the specification of the sample apps under `samples/`: `samples/android` (Jetpack Compose) and
`samples/ios` (SwiftUI, XcodeGen). The samples exist to **verify, monitor and explore the library** on a real vehicle.
They are not products.

- Agent instructions for sample work: `samples/AGENTS.md` (and `samples/android/AGENTS.md`, `samples/ios/AGENTS.md`).
- The samples are built **only** from the library usage manual: `documents/md/11-lib-overview.md` to
  `19-lib-architecture.md` (AGENTS.md §3 rule 14). These pages say *what* the samples do; the library manual says *how*
  the library is called.
- The human-readable version of these pages is `../html/<lang>/index.html` (`ko`, `en`, `ja`).

## Reading order

| Order | File | When |
|---|---|---|
| 1 | `02-constraints.md` | Before any sample change |
| 2 | `01-requirements.md` | When picking or checking a sample requirement (SFR) |
| 3 | `03-current-apps.md` | When changing an existing screen or flow |

## File list

| File | Contents |
|---|---|
| `README.md` | This page |
| `01-requirements.md` | Sample requirements `SFR-xxx`, status, origin |
| `02-constraints.md` | Rules every sample change follows: allowed API, platform setup, logs and secrets, build and test |
| `03-current-apps.md` | What the two apps do today, screen by screen, and where the real-vehicle runbooks are |

## Keeping these pages current

A PR that changes sample behaviour updates the affected page here **and** the matching HTML page in `ko/`, `en/` and
`ja/` in the same PR. This folder is English only; the HTML pages carry the same facts in three languages
(AGENTS.md §3 rule 12).
