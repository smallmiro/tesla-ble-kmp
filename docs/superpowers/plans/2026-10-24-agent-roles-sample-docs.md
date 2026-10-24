# Agent Roles and Sample Docs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 샘플 앱의 제약·스펙을 `samples/` 아래 지침과 두 벌 문서로 분리하고, 역할별 에이전트 5개와 스킬 4개를 만들어 라이브러리 개발·샘플 개발·프로토콜 분석의 경계를 명확히 한다.

**Architecture:** 루트 `AGENTS.md`가 계속 정본이다. `samples/AGENTS.md`(+ `android/`, `ios/`)는 차이만 적고, 각 폴더의 `CLAUDE.md`는 `@AGENTS.md`로 불러온다. 샘플 문서는 `samples/documents/md/`(영어, 에이전트용)와 `samples/documents/html/{ko,en,ja}/`(사람용) 두 벌이며, 기존 매뉴얼 검사 스크립트 3종이 이 문서 세트도 검사한다. 역할·모델·Codex 협의 규칙은 루트 `AGENTS.md` 새 절에 한 번만 적고 `.claude/agents/*.md`는 그 절을 가리킨다.

**Tech Stack:** Markdown, 정적 HTML(기존 매뉴얼 마크업과 `assets/`), bash + `python3 -I`(검사 스크립트), Claude Code 에이전트/스킬 frontmatter.

**Spec:** 이 대화에서 사용자가 승인한 구성(2026-10-24). 요약은 아래 Global Constraints. 관련 기능 설계: `docs/superpowers/specs/2026-10-24-vehicle-events-monitor-design.md`(이 PR에 함께 커밋하되 구현은 별도 계획).

## Global Constraints

- 경로는 `docs/PATHS.md` 키로만 참조한다(AGENTS 규칙 10). 새 키: `SAMPLES_DOCS_DIR`=`samples/documents/md/`, `SAMPLES_DOCS_HTML_DIR`=`samples/documents/html/`, `SAMPLES_DOCS_INDEX`=`{{SAMPLES_DOCS_DIR}}README.md`, `SAMPLE_LOGS_DIR`=`samples/logs/`(git-ignored), `ANALYSIS_DIR`=`docs/analysis/`. 키 추가는 `PATHS.md`만 고치는 `docs:` 커밋.
- 언어: `samples/documents/md/`는 영어만. `samples/documents/html/`은 `ko/`·`en/`·`ja/` 같은 페이지·같은 마크업. 식별자·코드·경로는 원문 그대로(규칙 12 확장).
- 에이전트(이름 / model / effort): `library-developer` opus/max, `protocol-analyst` opus/xhigh, `android-developer` sonnet/high, `ios-developer` sonnet/high, `docs-translator` sonnet/low.
- 스킬: `library-api-request`, `lib-docs-sync`, `sample-from-lib-docs`, `analyze-monitor-logs`.
- Codex 협의(모든 에이전트): 다른 의견·상세 토론이 필요하면 `codex mcp-server`의 MCP 도구(`codex`, `codex-reply`)를 쓴다. 질문에 브랜치·HEAD를 적고 Codex가 브랜치를 바꾸지 않게 한다. 사용량 제한이면 협의를 건너뛰고 산출물에 `Codex 협의 생략: 사용량 제한(<시각>), 질문: …` 메모만 남기고 계속한다.
- `protocol-analyst`는 보고서만 쓴다(`{{ANALYSIS_DIR}}`). 라이브러리 사용 문서 반영은 `library-developer`.
- 샘플 HTML의 `assets/`는 `documents/html/assets/`와 바이트 단위로 같아야 한다(게이트가 검사). 폴더 밖 링크 금지.
- `.mcp.json`은 건드리지 않는다(로컬 `idea` 서버 포함). 설정 방법만 문서화.
- 커밋 접두어 `docs:`/`chore:`, 본문 끝에 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. Rebase merge.

## Review Focus

1. 샘플 문서 세트 디렉터리가 없거나 비었을 때 검사 스크립트가 조용히 통과하면 안 된다 → Task 1 Step 1의 "누락 시 실패" 케이스.
2. 샘플 `assets/`가 매뉴얼 `assets/`와 어긋나면 실패해야 한다(테마 수정이 한쪽에만 들어가는 경우) → Task 1 Step 1.
3. 기존 `documents/` 검사 결과가 바뀌면 안 된다(회귀) → Task 1 Step 4에서 기존 메시지 확인.
4. Claude Code는 `AGENTS.md`를 직접 읽지 않는다. 하위 폴더 `CLAUDE.md`가 없으면 샘플 규칙이 로드되지 않는다 → Task 3 Step 2 확인.
5. 에이전트 정의가 루트 규칙을 복사해 어긋나기 시작하는 것 → Task 4 리뷰 체크(각 정의는 루트 절을 링크, 규칙 본문 복사 금지).

---

### Task 1: 경로 키와 샘플 문서 골격 + 게이트 확장

**Files:**
- Modify: `docs/PATHS.md` (키 5개), `.gitignore` (`samples/logs/`)
- Create: `samples/documents/md/README.md`, `samples/documents/html/{ko,en,ja}/index.html`, `samples/documents/html/index.html`, `samples/documents/html/assets/{style.css,theme.js}`(복사)
- Modify: `tools/ci/check-manual-i18n.sh`, `tools/ci/highlight-manual-code.sh`, `tools/ci/sync-manual-root-index.sh`

**Interfaces:**
- Produces: 문서 세트 목록 `DOC_SETS = [("documents/md", "documents/html"), ("samples/documents/md", "samples/documents/html")]`을 세 스크립트가 같은 이름으로 가진다. 스크립트 인자 규약(첫 인자 = 저장소 루트, highlight는 `--check` 선행)은 그대로.

- [ ] **Step 1: 실패하는 검사 케이스 준비** — 스크래치에 저장소 사본(`git worktree`가 아니라 `cp -R documents samples tools`)을 만들고 세 가지를 확인할 명령을 적는다: (a) `samples/documents/md/README.md`에 한글 한 줄 → `check-manual-i18n.sh <copy>` 실패, 메시지에 `samples/documents/md/README.md:` 포함; (b) `samples/documents/html/assets/style.css` 끝에 한 줄 추가 → 실패, 메시지 `samples/documents/html/assets: differs from documents/html/assets`; (c) `samples/documents/html/ko` 삭제 → 실패, `directory missing`.
- [ ] **Step 2: 지금 스크립트로 돌려 세 케이스가 통과(=검사 안 함)하는 것을 확인** — Expected: 세 경우 모두 `manual i18n OK` (아직 샘플 세트를 모름).
- [ ] **Step 3: `PATHS.md` 키 5개 추가 → `docs:` 커밋(이 파일만).** 그 다음 `.gitignore`에 `samples/logs/` 추가.
- [ ] **Step 4: 골격 작성** — `README.md`(영어, 목차: 01 requirements, 02 constraints, 03 current apps; 페이지는 Task 2에서 채움), HTML `index.html` 3언어(매뉴얼 `en/index.html`의 헤더·테마 전환·언어 메뉴 마크업 재사용, 사이드바는 샘플 문서 4페이지, 브랜드 문구 "tesla-ble-kmp Sample Apps"), `assets/` 복사, 루트 `index.html`은 sync 스크립트로 생성.
- [ ] **Step 5: 스크립트 확장** — 세 스크립트를 `DOC_SETS` 반복으로 바꾼다. 기존 검사 항목은 세트마다 그대로 적용. 추가: 샘플 세트 `assets/` 각 파일이 `documents/html/assets/` 같은 이름 파일과 바이트 동일. 출력 마지막 줄은 세트별 한 줄 `manual i18n OK (<set>): …`.
- [ ] **Step 6: Step 1 세 케이스 재실행** — Expected: 모두 FAIL(지정 메시지). 원본 트리에서 `tools/ci/check-manual-i18n.sh` → 두 세트 모두 OK, 기존 `documents` 페이지 수가 바뀌지 않았는지 확인.
- [ ] **Step 7: 커밋** `chore: check sample docs in the manual i18n gate` (스크립트) / `docs: add sample docs skeleton (md + html ko/en/ja)` (골격) — 두 커밋으로 분리.

### Task 2: 샘플 문서 본문 01~03 + PRD 이관

**Files:**
- Create: `samples/documents/md/01-requirements.md`, `02-constraints.md`, `03-current-apps.md`; 같은 이름 `.html` × `ko/en/ja`
- Modify: `docs/prd/PRD.md` §5.12 (FR-110, FR-111 행 → "샘플 요구사항은 `{{SAMPLES_DOCS_DIR}}01-requirements.md` SFR-001~002로 이관" 한 줄), `samples/ios/README.md`(빌드 절차만 남기고 실차 확인 절차는 `03-current-apps`로 이동, 링크)

**Interfaces:**
- Produces: 샘플 요구사항 ID `SFR-001`(Android 샘플 = 옛 FR-110), `SFR-002`(iOS 샘플 = 옛 FR-111). 이후 모니터 앱은 `SFR-010`부터(다음 계획).

- [ ] **Step 1: MD 3쪽 작성(영어)** — 01: SFR 표(ID, 요구, 상태, 출처 FR). 02: 제약 — `:sdk` 공개 API 중 "available"만(AGENTS 규칙 14), 금지 목록(`:application`/`:adapter-*`/`@InternalTeslableApi`/planned API), Swift는 모든 인자 전달, 권한·`Info.plist`·백업 제외, VIN·키·`TX:`/`RX:` 로그 규칙, 빌드 명령(`./gradlew :samples:android:assembleDebug` 실제 태스크명은 `./gradlew tasks`로 확인해 적기, iOS xcodegen 절차), 테스트 방식(iOS `Tests/`, Android 단위), API 부족 시 `library-api-request` 스킬. 03: 현재 두 샘플의 화면·기능 표와 `samples/ios/README.md`에서 옮긴 실차 확인 절차(영어로 번역).
- [ ] **Step 2: HTML en 작성** — MD와 같은 사실, 개념·표 위주. 코드 블록은 `highlight-manual-code.sh`로 칠한다.
- [ ] **Step 3: ko/ja 번역** — `docs-translator` 역할로 같은 마크업 번역(서브에이전트 가능).
- [ ] **Step 4: PRD·README 수정**
- [ ] **Step 5: 검사** — `tools/ci/check-manual-i18n.sh` Expected: 두 세트 OK, 샘플 세트 4페이지.
- [ ] **Step 6: 커밋** `docs: sample requirements, constraints and current apps (md + html ko/en/ja)`, `docs: move sample requirements out of the PRD`.

### Task 3: 샘플 지침 파일

**Files:**
- Create: `samples/AGENTS.md`, `samples/CLAUDE.md`, `samples/android/AGENTS.md`, `samples/android/CLAUDE.md`, `samples/ios/AGENTS.md`, `samples/ios/CLAUDE.md`

- [ ] **Step 1: 작성** — `samples/AGENTS.md`: "루트 `AGENTS.md`를 따른다. 여기는 차이만" + 기준 문서(`{{SAMPLES_DOCS_INDEX}}`, `{{LIB_DOCS_INDEX}}`), 하지 않는 것(Go 원본·매뉴얼 00~10 열람 불필요, 라이브러리 코드 수정 금지), TDD 범위(샘플 로직은 테스트, UI는 수동 확인 체크리스트), 문서 갱신 의무(샘플 동작이 바뀌면 샘플 문서 두 벌). `android/AGENTS.md`: Compose, 권한, Gradle 모듈, 빌드·테스트 명령. `ios/AGENTS.md`: xcodegen, `Local.xcconfig`(커밋 금지), SKIE Swift 규칙(모든 인자), 시뮬레이터 BLE 없음. 각 `CLAUDE.md`: 첫 줄 `@AGENTS.md`, 이어서 사용할 에이전트(`android-developer`/`ios-developer`)와 스킬.
- [ ] **Step 2: 확인** — 세 `CLAUDE.md`가 모두 `@AGENTS.md`로 시작하는지 `head -1`. 루트 규칙 본문을 복사한 문단이 없는지 리뷰.
- [ ] **Step 3: 커밋** `docs: sample agent instructions (samples/, android/, ios/)`.

### Task 4: 에이전트 정의 5개

**Files:**
- Create: `.claude/agents/library-developer.md`, `protocol-analyst.md`, `android-developer.md`, `ios-developer.md`, `docs-translator.md`

**Interfaces:**
- Produces: frontmatter `name`, `description`(언제 쓰는지 한 문장), `model`, `effort` — Global Constraints 표 그대로. 본문 공통 구조: `## Role` / `## Read first`(PATHS 키) / `## Does` / `## Does not` / `## Hands off to` / `## Codex` (루트 `AGENTS.md` 역할 절 링크 한 줄).

- [ ] **Step 1: 작성** — 본문은 영어(에이전트용). 규칙 본문을 복사하지 않고 루트 절·PATHS 키를 가리킨다. `library-developer`: Go 원본과 매뉴얼 00~10이 1차 기준, TDD·Tidy First, 사용 문서 MD 갱신, 분석 보고서를 문서에 반영, 어려운 디버깅만 max가 기본임을 명시. `protocol-analyst`: `analyze-monitor-logs` 스킬, 등급(Confirmed/Likely/Unknown) 규칙, 보고서만 씀. 샘플 두 개: `sample-from-lib-docs`, `library-api-request`. `docs-translator`: en → ko/ja 같은 마크업, 내용 변경 금지, 끝나면 `check-manual-i18n.sh`.
- [ ] **Step 2: 확인** — `grep -H '^model:\|^effort:' .claude/agents/*.md` 출력이 Global Constraints 표와 같음.
- [ ] **Step 3: 커밋** `docs: role agents (library, protocol analyst, android, ios, translator)`.

### Task 5: 스킬 4개 + wire 디코더

**Files:**
- Create: `.claude/skills/library-api-request/SKILL.md`, `.claude/skills/lib-docs-sync/SKILL.md`, `.claude/skills/sample-from-lib-docs/SKILL.md`, `.claude/skills/analyze-monitor-logs/SKILL.md`
- Create: `tools/analysis/pbwire.py`, `tools/analysis/test_pbwire.py`

**Interfaces:**
- Produces: `pbwire.decode(data: bytes, depth: int = 0, max_depth: int = 3) -> list[dict]` — 스키마 없는 protobuf wire 디코딩. 항목 `{"field": int, "wire": "varint"|"fixed32"|"fixed64"|"len", "value": int|str|list}`; LEN은 하위 메시지로 완전히 풀리면 list, 아니면 출력 가능 ASCII면 `"text:…"`, 아니면 `"hex:…"`. 18번 문서 Observation log 상세 형식과 같은 판정. CLI: `python3 -I tools/analysis/pbwire.py <hex>` → JSON.

- [ ] **Step 1: 실패 테스트** — `test_pbwire.py`(unittest): `decode(bytes.fromhex("0801"))[0] == {"field":1,"wire":"varint","value":1}`; `decode(bytes.fromhex("12020801"))[0]["value"] == [{"field":1,"wire":"varint","value":1}]`; `decode(b"\x1a\x03abc")[0]["value"] == "text:abc"`; 잘린 입력 `decode(bytes.fromhex("0a05ff"))`는 예외 없이 `[{"field":1,"wire":"len","value":"invalid"}]`.
- [ ] **Step 2: 실패 확인** — `python3 -I -m unittest tools/analysis/test_pbwire.py` Expected: ImportError/FAIL.
- [ ] **Step 3: 구현**(표준 라이브러리만).
- [ ] **Step 4: 통과 확인** — 같은 명령 Expected: OK.
- [ ] **Step 5: 스킬 작성** — frontmatter `name`, `description`(트리거 문장). `library-api-request`: 요청서 형식(필요한 동작, 샘플에서의 장면, 문서 위치, 제안 API, 대안 없음 근거) → 계획/이슈로 올림, 샘플에서 우회 금지. `lib-docs-sync`: MD → HTML en → ko/ja(`docs-translator`) → `highlight-manual-code.sh` → `check-manual-i18n.sh` → PR 체크. `sample-from-lib-docs`: `{{LIB_DOCS_INDEX}}` "Reference files by task" 표로 페이지 선택 → available API만 → 예제 코드 대조 → 어긋나면 문서를 먼저 고치거나 요청. `analyze-monitor-logs`: 가져오기(`{{SAMPLE_LOGS_DIR}}`) → 목록 → `pbwire.py` 디코딩 → mark·link·query와 시각 대조 → 가설(근거·반례·미확인 조건) → 등급(Confirmed는 통제 시나리오 반복 확인만) → 보고서 `{{ANALYSIS_DIR}}YYYY-MM-DD-<topic>.md` → 넘기기. 원본 로그·실제 VIN 커밋 금지.
- [ ] **Step 6: 게이트 연결** — `tools/ci/verify.sh`에 `python3 -I -m unittest discover -s tools/analysis -p 'test_*.py'` 단계 추가.
- [ ] **Step 7: 커밋** `chore: schema-less protobuf wire decoder for log analysis` / `docs: role skills (api request, docs sync, samples from docs, log analysis)`.

### Task 6: 루트 지침 갱신

**Files:**
- Modify: `AGENTS.md` (§2 읽을 문서 표에 `{{SAMPLES_DOCS_INDEX}}`, 규칙 12에 샘플 문서 세트, 규칙 14에 샘플 문서, 새 절 "역할 분담과 Codex 협의"), `CLAUDE.md` (§1 스킬 매핑에 에이전트·새 스킬, §3 서브에이전트 원칙: 구현은 역할 에이전트로, 어려운 디버깅만 effort 상향), `docs/workflow.md` §9 DoD 문서 항목에 "샘플 동작이 바뀌면 샘플 문서 두 벌"

- [ ] **Step 1: `AGENTS.md` 새 절** — 역할 표(에이전트, 담당 경로, 1차 기준 문서, 산출물, 넘기는 곳, model/effort), 넘김 흐름(샘플 → `library-api-request` → 계획 → `library-developer`; 분석 → 보고서 → `library-developer`가 문서 반영; 다음 관찰 시나리오 → 샘플 개발자), Codex 협의 규칙(Global Constraints 문구 그대로), `.mcp.json`에 `codex` 서버(`codex mcp-server`) 등록과 세션 시작 시 승인 안내.
- [ ] **Step 2: `CLAUDE.md`, `workflow.md` 수정**
- [ ] **Step 3: 확인** — `grep -n '{{' AGENTS.md CLAUDE.md` 새 키가 `PATHS.md`에 모두 있는지.
- [ ] **Step 4: 커밋** `docs: role split and Codex consultation rules in AGENTS/CLAUDE`.

### Task 7: 기능 설계 커밋, 검증, 리뷰, PR

- [ ] **Step 1:** `docs/superpowers/specs/2026-10-24-vehicle-events-monitor-design.md`와 이 계획을 `docs:` 커밋.
- [ ] **Step 2:** `tools/ci/verify.sh` Expected: 전체 Green.
- [ ] **Step 3:** 새 컨텍스트 리뷰 서브에이전트(`requesting-code-review`) — DoD §9 + Review Focus 5개 + "루트 규칙 복사 여부".
- [ ] **Step 4:** 지적 처리(`receiving-code-review`), 재검증.
- [ ] **Step 5:** push, PR 생성(본문: 요약, 리뷰 요약, "라이브러리 사용 문서 영향 없음: 라이브러리 동작·API 변경 없음", `Closes` 이슈), rebase merge.
