@AGENTS.md

# CLAUDE.md — Claude Code 전용 추가 지침

> 공통 규칙은 위에서 불러온 `AGENTS.md` 에 있습니다. 이 파일에는 **Claude Code + Superpowers 전용 내용만** 둡니다.
> (GitHub Copilot CLI도 이 파일을 읽습니다. Copilot과 다른 도구는 이 파일의 Superpowers 절을 무시하고 `AGENTS.md` 를 따릅니다.)

## 1. Superpowers 스킬 매핑
| 공통 흐름 단계 (AGENTS.md §4) | 사용할 스킬 | 산출 위치 |
|---|---|---|
| 세션 시작 | `using-superpowers` → `{{HANDOFF_DIR}}` 최신 노트 읽기 | – |
| PRD | `brainstorming`: 질문은 **한 번에 하나씩** | `{{PRD_FILE}}` |
| SDD | `brainstorming` (설계 단계) | `{{SDD_FILE}}`, 원본은 `{{SPECS_DIR}}`, 결정은 `{{ADR_DIR}}` |
| 계획 | `writing-plans` | `{{PLANS_DIR}}` |
| 작업 공간 | `using-git-worktrees`: 작업 단위마다 짧게 쓰고 바로 병합 | – |
| 구현 | `subagent-driven-development` + `test-driven-development` | 코드 |
| 디버깅 | `systematic-debugging`: 가설을 세우기 전에 원본 Go 코드와 대조 | – |
| 리뷰 (머지 조건) | `requesting-code-review` → 지적 사항은 `receiving-code-review` 로 처리 | PR 본문에 요약 첨부 |
| 마무리 | `verification-before-completion`(있다면) → `finishing-a-development-branch` | – |

- **스킬의 기본 저장 경로 대신 `{{PATHS_FILE}}` 의 키를 사용합니다.**
- 스킬 지시와 `AGENTS.md` 가 충돌하면 `AGENTS.md` 를 따르고, 충돌이 있었다는 사실을 사용자에게 알립니다.

## 2. 승인 게이트 운영
- PRD, SDD, 계획을 마치면 **요약 + 결정이 필요한 항목**만 보여주고 멈춥니다. 전문을 다시 붙여넣지 않습니다.
- 사용자가 "진행"이라고 명시하기 전에는 다음 Phase로 넘어가지 않습니다.

## 3. 서브에이전트 사용 원칙
- 구현 서브에이전트에는 **작업 1개 + 관련 FR ID + 참고할 `{{MANUAL_DIR}}` 매뉴얼 문서 + 대응하는 Go 원본 경로 + 먼저 작성할 실패 테스트**만 전달합니다.
- 리뷰 서브에이전트는 구현을 보지 않은 새 컨텍스트로 띄우고, `{{WORKFLOW_FILE}}` §9 DoD를 체크리스트로 줍니다.
- 원본 저장소 탐색(파일이 많을 때)은 읽기 전용 탐색 에이전트에 맡기고, 결론만 받습니다.

## 4. 컨텍스트 관리
- 마일스톤이 끝났거나, 주제가 바뀌었거나, 컨텍스트가 길어졌으면 `{{WORKFLOW_FILE}}` §10 형식으로 `{{HANDOFF_DIR}}` 에 인계 노트를 쓰고 `/clear` 를 제안합니다.
