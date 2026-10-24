---
name: lib-docs-sync
description: Use before merging any PR that changes library behaviour, public API or milestone status, or sample app behaviour. Updates the agent Markdown page and the human HTML pages in Korean, English and Japanese together and runs the docs gate.
---

# Docs sync (MD + HTML ko/en/ja)

AGENTS.md rules 12 and 13: a PR that changes what the library or a sample does updates both doc sets in the same PR.

| What changed | Markdown (English, agents) | HTML (ko/en/ja, people) |
|---|---|---|
| Library behaviour, public API, milestone status | `{{LIB_DOCS_DIR}}` `11-lib-*.md`–`19-lib-*.md` | `{{LIB_DOCS_HTML_DIR}}<lang>/11-lib-*.html`–`19-lib-*.html` |
| Sample behaviour, sample requirements | `{{SAMPLES_DOCS_DIR}}` | `{{SAMPLES_DOCS_HTML_DIR}}<lang>/` |

## Steps

1. **List the facts that changed** (signatures, invariants, statuses, examples). Find every page that states them:
   `grep -rn '<name>' documents/md samples/documents/md`.
2. **Markdown first.** English only, exact signatures and invariants. Move "planned" items to "available" and update the
   status table in `11-lib-overview.md` when a feature lands. Code examples must compile against the real API.
3. **HTML English page** with the same facts, explained for people (concepts, steps, tables). Reuse the existing page
   markup; do not invent new CSS classes.
4. **Korean and Japanese** with identical markup: hand the English page to `docs-translator`.
5. **Regenerate and check:**
   - `tools/ci/highlight-manual-code.sh` (code block colours)
   - `tools/ci/sync-manual-root-index.sh` (only if an `en/index.html` changed)
   - `tools/ci/check-manual-i18n.sh` → must print `manual i18n OK` for both doc sets
6. **PR body:** list the pages changed, or write "문서 영향 없음" with the reason.

## Do not

- Edit the Go reference pages (`{{MANUAL_DIR}}` `00`–`10`, `{{MANUAL_HTML_DIR}}` `01`–`09`) without the user's approval.
- Put Korean text in any Markdown page, or Hangul in `en/`/`ja/` pages (except the "한국어" switcher label).
