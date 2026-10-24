---
name: library-api-request
description: Use when sample or analysis work finds that the tesla-ble-kmp library is missing an API, the library usage manual does not describe a needed behaviour, or the library behaves differently from the manual. Produces a library request instead of a workaround in the sample.
---

# Library API request

The samples must not work around the library (AGENTS.md rule 14). A gap is recorded as a request and planned as library
work for `library-developer`.

## Steps

1. **Confirm the gap.** Re-read the relevant `11-lib-*`–`19-lib-*` page (find it with the "Reference files by task"
   table in `{{LIB_DOCS_INDEX}}`). Check that the API is not already available under another name and is not only
   "planned".
2. **Write the request** in the current plan (`{{PLANS_DIR}}`) or, if there is no plan for it yet, as a GitHub issue
   labelled `enhancement` (bug: `bug`). Use this shape:

   ```markdown
   ## Library request: <short name>
   - Need: <behaviour the sample or analysis needs, one sentence>
   - Scene: <when it happens in the app or on the car; the SFR-xxx it blocks>
   - Manual today: <page and section, what it says or that it is silent>
   - Observed (if the library differs from the manual): <steps, result, masked log lines>
   - Proposed API: <signature sketch, Swift call shape>  (a proposal; library-developer decides)
   - Why no workaround: <which rule a sample-side workaround would break>
   - Raised by: <agent>, Refs: <SFR-xxx / FR-xxx / issue>
   ```
3. **Mark the sample requirement** in `{{SAMPLES_DOCS_DIR}}01-requirements.md` as "waits for library" with a link to the
   request (and the HTML pages in ko/en/ja).
4. **Stop the sample part** that depends on it. Continue with sample work that does not.

## Do not

- Call internal types, `@InternalTeslableApi` or planned API "just for now".
- Parse library log text to get data the API does not give.
- Change library code from a sample task.
