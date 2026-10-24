---
name: sample-from-lib-docs
description: Use when writing or changing sample app code (samples/android, samples/ios) that calls the tesla-ble-kmp library. Picks the library manual pages to follow, restricts code to the documented available :sdk API, and checks the sample against the manual's examples.
---

# Sample from library docs

The samples are written the way a first-time library user would write them: from the manual only (AGENTS.md rule 14).

## Steps

1. **Pick the pages.** Open `{{LIB_DOCS_INDEX}}`, find your task in "Reference files by task", and read those pages
   (connect and read: `12`, `14`; pairing: `13`; commands: `15`; errors: `16`; platform setup: `17`; logs: `18`).
   Also read `{{SAMPLES_DOCS_DIR}}02-constraints.md` and the app's spec page.
2. **List the calls** you need, each with its manual page and section. For each, check the status column says
   available (not planned, not internal).
3. **Write a failing test** for any logic that runs without a car (state reduction, parsing, masking, timers).
4. **Write the code** following the manual's call order, result handling (`Success` / `Uncertain` / `Failure`; after
   `Uncertain`, re-query with `vehicleStatus()`), timeouts and platform setup. Swift: pass every argument.
5. **Compare with the manual's examples.** If your working code and the manual example disagree, find out which is
   right; fix the manual in the same PR (`lib-docs-sync`) or raise `library-api-request`.
6. **Real-device checklist.** Write the steps and expected results for what tests cannot reach (screens, car behaviour,
   background) into the PR or the app's runbook.
7. **Update the sample docs** (`lib-docs-sync`, sample row) and list the manual pages and sections used in the PR body.

## Stop and raise a request when

- The behaviour you need is not in the manual, or the library does something the manual does not say.
- The only way forward would use `:application` / `:adapter-*`, `@InternalTeslableApi`, planned API or log parsing.
