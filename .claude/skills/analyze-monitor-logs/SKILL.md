---
name: analyze-monitor-logs
description: Use when analysing monitor logs pulled from the sample apps (JSON Lines with broadcast events and raw bytes, observe lines, query results, link events, marks) to explain unknown Tesla BLE protocol fields, find error or disconnect patterns, or propose library hardening. Produces an evidence-backed analysis report.
---

# Analyze monitor logs

Owner: `protocol-analyst`. Output: a report in `{{ANALYSIS_DIR}}`. Never edits code or the library manual.

## Steps

1. **Get the logs.** Pull from a connected phone into `{{SAMPLE_LOGS_DIR}}` (git-ignored) with the pull script described
   in the monitor app spec (`{{SAMPLES_DOCS_DIR}}`). Never copy logs anywhere that is committed.
2. **Inventory** with a short script (stdlib Python, `python3 -I`): sessions and time span, count per line kind
   (`event`, `observe`, `query`, `link`, `mark`), count per event type, `unrecognized` count, disconnects and reconnect
   attempts, gaps in reception. Read whole files only after this summary tells you where to look.
3. **Decode raw bytes** with `tools/analysis/pbwire.py` (`python3 -I tools/analysis/pbwire.py <hex>`). Compare the field
   tree with the schema (`domain` module protos) and with `14-lib-reading-state.md` Part B.
4. **Line up in time.** Put marks, link events, query results and decoded fields on one timeline. For each unknown field,
   list every value change and what happened within a few seconds before it.
5. **Hypotheses.** For each one write: claim, supporting observations (count, timestamps), counterexamples, conditions not
   yet checked, and the scenario that would confirm or refute it.
6. **Status.** Confirmed: reproduced in a controlled scenario more than once. Likely: consistent but uncontrolled.
   Unknown: otherwise. Do not raise a status already in Part B without new evidence.
7. **Report** `{{ANALYSIS_DIR}}YYYY-MM-DD-<topic>.md`:
   `## Data` (sessions, phone, OS, car, firmware if known, app build) · `## Inventory` · `## Findings` (one block per
   hypothesis with status) · `## Library hardening` (errors, timeouts, disconnect patterns) · `## Next scenarios` ·
   `## Hand-offs` (to `library-developer`, `library-api-request`, sample developers) · `## Codex` (consultation summary
   or the skip note).
8. **Hand off.** `library-developer` decides what goes into the manual.

## Data rules

- Quote VINs only masked (first 3 and last 4 characters). Shorten hex in reports to what supports the claim.
- Key-like values (16, 32, 33, 64, 65 bytes) are quoted by length or SHA-1 prefix only.
- No network, no AGPL repositories.
