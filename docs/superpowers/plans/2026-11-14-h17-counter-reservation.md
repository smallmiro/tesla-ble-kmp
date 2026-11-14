# H17 세션 캐시 counter 예약 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 깨끗하지 않은 프로세스 종료(강제 종료, iOS가 멈춘 앱을 끝냄, 충돌) 뒤 다음 프로세스의 첫 인증 요청이 차량이 이미 받은 counter를 다시 써 `INVALID_TOKEN_OR_COUNTER`를 받는 일(실차 약 1.2초 추가)을 없앤다. 캐시 복원의 영(0) 왕복 시작(FR-017, NFR-011)은 유지한다.

**Spec:** `{{ANALYSIS_DIR}}2026-11-14-build27-driving.md` §F21, §Library hardening H17, §Codex. 사용자 승인 "H17 수정 진행해주세요"(2026-11-14). `{{PRD_FILE}}` FR-017, FR-018, NFR-011, D18. ADR-0007(세션 캐시), ADR-0010(전송·불확실 의미, #149/#150 보장), ADR-0015/FR-105(H16). 참고 매뉴얼: `{{MANUAL_DIR}}10-porting-guide.md` §8·§9, `01-architecture.md` §6, `00-agent-guide.md` §3.2-17. 원본: `{{REF_REPO_DIR}}internal/authentication/signer.go:86-94`, `verifier.go:215-221, 289-295`, `window.go:17-49`, `pkg/cache/doc.go`, `cmd/tesla-control/main.go:177`, `pkg/protocol/protocol.md`(캐시 701-709행, counter 547-549행, 롤백 금지 726-728행).

## Global Constraints

- 작업 하나, worktree 하나, PR 하나. 역할 `library-developer`. HTML ko/ja는 `docs-translator`.
- TDD(F21 재현 시험 먼저), Tidy First(`struct:` 먼저), 공개 API 변경 없음(바뀌면 `apiDump`).
- Go와 다른 동작이므로 ADR(0016) + ADR-0007 교차 참조. 라이브러리 사용 문서 MD + HTML ko/en/ja 같은 PR(절대 규칙 13).
- 선택지 결정은 Codex 협의(AGENTS.md §9)로 하고 양쪽 근거를 ADR과 PR 본문에 남긴다.

### Task: 캐시에 쓴 counter 예약(reserve-ahead) (H17, FR-017)

- [x] **Step 1: 결정** — (d) 인증된 재동기화 vs (b) 내구성 있는 선예약. Codex 협의, ADR-0016 초안.
- [x] **Step 2: 실패 시험** — `CounterReservationTest`: 프로세스 1이 `startSession` → 요청을 되풀이하고 `disconnect()` 없이 끝난 뒤, 같은 캐시·같은 `FakeVehicle`로 프로세스 2의 첫 요청(Infotainment, VCSEC)이 거부·재전송 없이(인증 요청 1개) 성공하고 세션정보 요청도 없다. FAIL 확인(인증 요청 2개).
- [x] **Step 3: 구현** — 필요한 구조 변경은 `struct:` 커밋으로 먼저. 예약 불변식, 보내기 전 예약 저장, 저장은 예약을 내리지 않음, 동시 전송, 실패·시간 초과·잠금 보존, 포화(되감지 않음)와 소진된 항목 복원 거부. 각 동작마다 실패 시험 먼저.
- [x] **Step 4: 문서** — PRD FR-017, SDD §7.1·의도한 차이·ADR 목록, ADR-0016 확정, ADR-0007 교차 참조. `{{LIB_DOCS_DIR}}19-lib-architecture.md` §Session cache, `17-lib-platform-notes.md` §Session cache, `18-lib-troubleshooting.md`(`TIME_EXPIRED` 행 옆), `16-lib-errors.md` §Retries and timeouts, 필요하면 `11-lib-overview.md`. HTML en 먼저, ko/ja 번역, `tools/ci/check-manual-i18n.sh`.
- [ ] **Step 5: 검증과 PR** — `apiCheck`, `tools/ci/verify.sh`(직렬 실행), 커밋 `Refs: FR-017, ADR-0016, H17`, PR(요약, Codex 협의 결과, 리뷰 요약, 문서, 게이트 결과). 구현을 보지 않은 새 리뷰 에이전트에 diff 범위와 `{{WORKFLOW_FILE}}` §9 DoD. 지적 반영. 머지하지 않는다.
- [ ] **Step 6: 실차 확인(사용자)** — 분석 보고서 N10의 A/B 쌍: 강제 종료 뒤 첫 탭에 `INVALID_TOKEN_OR_COUNTER`가 없어야 한다. 앞으로 뛴 counter를 차량이 받는지도 이것으로 확인한다.
