---
name: collect-monitor-logs
description: Use when the user says they collected real-car logs with the sample monitor app ("로그 수집해가세요", "로그 가져가세요", "로그 확인하세요") or asks to pull logs from the phone. Pulls only the new monitor logs from the iPhone (USB or Wi-Fi) or Android phone into the git-ignored logs folder, prints a privacy-safe summary, and hands the analysis to protocol-analyst.
---

# Collect monitor logs

Pull the logs the user just recorded, show what is in them, then hand them to `protocol-analyst`.
Logs are real-car data: they stay in `{{SAMPLE_LOGS_DIR}}` (git-ignored) and are never committed (AGENTS.md rule 8).

## Steps

1. **Pull only what is new.**
   - iOS: `tools/samples/pull-monitor-logs.sh ios --new`
     - The phone must be unlocked and visible to `xcrun devicectl` (USB, or Wi-Fi after "Connect via network" in Xcode).
     - With more than one iPhone, the script lists them; pass `--device <id>`. Do not hardcode a device id in files.
     - It prints one pulled path per line. New files go to today's `{{SAMPLE_LOGS_DIR}}ios/<yyyy-MM-dd>/`. A file that grew on
       the phone is overwritten where it already was.
     - `no new logs on the phone` means there is nothing to analyse; tell the user and stop.
     - A VIN warning means a 17-character string with an upper-case letter is in a log. Check it before anything else.
   - Android: `tools/samples/pull-monitor-logs.sh android` copies the whole folder into today's folder.
2. **Summarise** the pulled files: `python3 -I tools/analysis/monitor_summary.py <paths>`.
   - The output has the app build, time span, outcome counts per call, non-success lines, marks, connects and disconnects,
     and the count of frames addressed to another client.
   - It never prints `getState` summaries. Do not add location values, coordinates or key material to anything you
     write yourself either (`14-lib-reading-state` A8.3/A8.4).
3. **Tell the user (in Korean)** in a short table, one row per file: build, time span, success/total, what failed and
   when, disconnects, other-client frames. Point out anything unusual compared with the latest report in `{{ANALYSIS_DIR}}`.
4. **Ask only what the log cannot show**, at most two questions. Ask only when it changes the analysis:
   - what they did when there are no `mark` lines
   - whether their other BLE app or the Tesla app was running
5. **Hand off to `protocol-analyst`** in the background, using the `analyze-monitor-logs` skill. Brief it with:
   - the file paths and the summary numbers
   - what the user said they did
   - the report path `{{ANALYSIS_DIR}}YYYY-MM-DD-<topic>.md`
   - the latest report to continue numbering from (F/H/N)
   - the specific questions to answer
   - the rules: report only, no commits, no location values, `{{KEY}}` paths
   Forward any user answers that arrive later with SendMessage.
6. **When the report is back:**
   - Summarise it for the user in Korean, with its questions for them.
   - Commit the report on a `docs/` branch and run `tools/ci/verify.sh`.
   - Open a PR, resolve the review threads, and rebase-merge.
   - Manual changes the report proposes go to `library-developer`.

## Do not

- Copy logs anywhere that is committed, or paste raw log lines with location values into chat, PRs or reports.
- Re-pull the whole folder on iOS (duplicates files across date folders); use `--new`.
- Analyse in the main thread beyond the summary. The analysis belongs to `protocol-analyst`.
