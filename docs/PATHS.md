# PATHS — 경로 정본 (Single Source of Truth)

> 이 저장소의 모든 문서, 에이전트 지침, 코드 주석은 경로를 직접 쓰지 않고 **`{{키}}`** 로 참조합니다.
> 위치를 바꾸려면 **이 표의 값만** 고치세요. 다른 파일은 수정할 필요가 없습니다.
> 값이 `TBD` 인 키를 써야 하는 시점이 오면, 에이전트는 작업을 멈추고 사용자에게 확인합니다.

| 키 | 값 | 설명 |
|---|---|---|
| `PATHS_FILE` | `docs/PATHS.md` | 이 파일 |
| `AGENTS_FILE` | `AGENTS.md` | 모든 코딩 에이전트 공통 지침 (정본) |
| `CLAUDE_FILE` | `CLAUDE.md` | Claude Code 전용 추가 지침 |
| `HANDOFF_FILE` | `HANDOFF.md` | 프로젝트 착수 메타 프롬프트 |
| `WORKFLOW_FILE` | `docs/workflow.md` | 개발 표준·워크플로 전문 |
| `MANUAL_DIR` | `documents/md/` | **개발 매뉴얼 (사용자 작성, 참조용).** 실제 `vehicle-command` 저장소를 기준으로 정리한 문서. 포팅 작업의 1차 가이드. 사용자 승인 없이 수정 금지 |
| `MANUAL_INDEX` | `{{MANUAL_DIR}}README.md` | 개발 매뉴얼 목차 (진입점). 읽기 순서, 작업별 참조 파일 표, 파일 목록. 규칙·불변 조건·상수 카드는 `{{MANUAL_DIR}}00-agent-guide.md` |
| `LIB_DOCS_DIR` | `docs/manual/` | **이 라이브러리의 사용 문서 (에이전트 작성).** 공개 API, 페어링, 에러 처리 등 |
| `LIB_DOCS_INDEX` | `{{LIB_DOCS_DIR}}README.md` | 라이브러리 사용 문서 목차 |
| `PRD_FILE` | `docs/prd/PRD.md` | 제품 요구사항 문서 (FR-xxx / NFR-xxx) |
| `SDD_FILE` | `docs/sdd/SDD.md` | 소프트웨어 설계 문서 |
| `SPECS_DIR` | `docs/superpowers/specs/` | 설계 브레인스토밍 산출물 |
| `PLANS_DIR` | `docs/superpowers/plans/` | 구현 계획 |
| `ADR_DIR` | `docs/adr/` | 아키텍처 결정 기록 (`NNNN-<slug>.md`) |
| `HANDOFF_DIR` | `docs/handoff/` | 세션 인계 노트 (`YYYY-MM-DD-<slug>.md`) |
| `FIXTURES_DIR` | `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/fixtures/` | 테스트 벡터, 골든 TX/RX (VIN 마스킹본만). Kotlin 상수로 저장 (SDD D30, ADR-0011) |
| `REF_REPO_DIR` | `vehicle-command/` | 공식 Go 저장소 로컬 클론. **git-ignored, 읽기 전용** |
| `REF_REPO_URL` | `https://github.com/teslamotors/vehicle-command` | 원본 저장소 |
| `REF_REPO_COMMIT` | `a4b43c1eff0e09d77deb9f2dce97031141fe8c8a` | 포팅 기준 커밋 (2026-09-25) |
| `POC_DIR` | `reference/poc/` | 검증된 PoC 코드 |
| `LIB_NAME` | `tesla-ble-kmp` | 라이브러리 / Gradle 루트 이름 (= GitHub 저장소 이름, 2026-09-26 확정) |
| `REPO_URL` | `https://github.com/smallmiro/tesla-ble-kmp` | 공개 저장소 (Apache-2.0). 브랜치 `main` 보호 |
| `PACKAGES_MAVEN_URL` | `https://maven.pkg.github.com/smallmiro/tesla-ble-kmp` | GitHub Packages Maven 레지스트리 (M6에서 첫 배포). 소비자는 `read:packages` 토큰 필요 |
| `BASE_PACKAGE` | `io.github.smallmiro.teslable` | Kotlin 패키지 루트 (Phase 1 Q5-b에서 확정) |
| `RUNNER_DIR` | `tools/runner/` | CI self-hosted runner 구성: Docker 이미지, 호스트 supervisor, macOS runner 설치 스크립트 (ADR-0012) |

## 규칙
1. 문서와 지침에서는 `{{MANUAL_DIR}}` 처럼 **키로 참조**합니다. 링크가 꼭 필요하면 이 파일로 링크합니다.
2. 라이브러리 사용 문서의 내부 링크는 `{{LIB_DOCS_INDEX}}` 기준 **상대경로만** 사용합니다. 폴더를 통째로 옮겨도 링크가 유지됩니다.
3. `{{MANUAL_DIR}}`(개발 매뉴얼)과 `{{LIB_DOCS_DIR}}`(라이브러리 사용 문서)은 **다른 문서**입니다. 앞의 것은 읽기 전용 참조, 뒤의 것은 산출물입니다.
4. 키를 추가하거나 바꿀 때는 `docs:` 커밋으로 이 파일만 수정합니다.
