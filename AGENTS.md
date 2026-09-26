# AGENTS.md — tesla-ble-kmp

> **모든 코딩 에이전트(Claude Code, Codex CLI, GitHub Copilot CLI) 공통 지침의 정본**입니다.
> 도구별 추가 지침은 각 도구 파일에만 둡니다. Claude Code 전용 지침은 `CLAUDE.md` 에 있습니다.
> 이 파일과 도구별 파일이 충돌하면 **이 파일이 우선**합니다.
> 경로는 모두 [`docs/PATHS.md`](docs/PATHS.md) 의 `{{키}}` 로 참조합니다.

## 1. 프로젝트 한 줄 요약
Tesla 공식 Go SDK `vehicle-command` 의 **BLE 경로**를 **Kotlin Multiplatform(Android + iOS)** 으로 직접 포팅해,
**서버 없이** 스마트폰에서 테슬라 차량과 통신(키 등록, 조회, 제어)하는 라이브러리를 만듭니다.

## 2. 먼저 읽을 문서 (순서대로)
| 순서 | 키 | 내용 | 언제 |
|---|---|---|---|
| 1 | `{{PATHS_FILE}}` | 모든 경로의 정본 | 항상 |
| 2 | `{{MANUAL_DIR}}` | **개발 매뉴얼.** 실제 `vehicle-command` 기준으로 정리된 포팅 가이드 (사용자 작성). **포팅 작업 시 반드시 먼저 참고** | 포팅·설계·구현 전 항상 |
| 3 | `{{HANDOFF_FILE}}` | 착수 메타 프롬프트: 결정 사항, 범위, 프로토콜 사실, 마일스톤 | 착수할 때, 방향이 헷갈릴 때 |
| 4 | `{{WORKFLOW_FILE}}` | 개발 표준 전문: TDD, Tidy First, 아키텍처, 브랜치, CI, DoD | 코드를 쓰기 전 |
| 5 | `{{PRD_FILE}}` | 요구사항 (FR-xxx / NFR-xxx) | 기능 작업 전 |
| 6 | `{{SDD_FILE}}` + `{{ADR_DIR}}` | 설계와 결정 기록 | 구조를 바꾸기 전 |
| 7 | `{{PLANS_DIR}}` | 현재 구현 계획 | 작업을 고를 때 |
| 8 | `{{HANDOFF_DIR}}` 최신 파일 | 직전 세션 인계 노트 | 세션을 시작할 때 |
| 9 | `{{LIB_DOCS_INDEX}}` | 라이브러리 사용 문서 (산출물) | 공개 API를 바꿀 때 |

PRD나 SDD가 아직 없으면 `{{HANDOFF_FILE}}` 의 워크플로를 따릅니다(Phase 0부터).

## 3. 절대 규칙 (예외 없음)
1. **TDD**: 실패하는 테스트를 먼저 작성합니다. 테스트 없이 동작을 바꾸지 않습니다.
2. **Tidy First**: 구조 변경과 동작 변경을 **한 커밋에 섞지 않습니다.** 구조 변경을 먼저 커밋합니다.
3. **main은 항상 Green**: 로컬에서 `./gradlew check` 가 통과한 것만 푸시합니다.
4. **아키텍처 경계는 Gradle이 강제**합니다. 금지된 모듈 의존성을 추가해 우회하지 않습니다. 의존성 변경은 ADR 대상입니다.
5. **원본 존중**: `{{REF_REPO_DIR}}` 는 읽기 전용입니다. 수정하거나 커밋하지 않습니다. 동작이 애매하면 **원본 Go 코드가 정답 기준**입니다.
5-1. **개발 매뉴얼 사용**: 포팅, 설계, 구현은 `{{MANUAL_DIR}}` 의 개발 매뉴얼을 **1차 가이드로 사용**합니다. 작업 계획과 커밋에 참고한 매뉴얼 문서를 표기합니다.
   - 매뉴얼과 원본 Go 코드가 다르면 **원본 코드 기준으로 구현**하고, 불일치 내용(매뉴얼 위치, 원본 파일과 줄)을 사용자에게 보고합니다.
   - 매뉴얼은 **사용자 승인 없이 수정하지 않습니다.**
6. **서버 없음**: Fleet API, 외부 네트워크 호출, 텔레메트리를 추가하지 않습니다.
7. **암호**: 알고리즘을 직접 구현하지 않습니다. 원시 연산은 플랫폼이 제공하는 것만 쓰고, HMAC은 상수 시간으로 비교합니다.
8. **비밀**: 개인키, 세션키, 실제 VIN을 로그, 테스트 픽스처, 커밋에 남기지 않습니다. 픽스처는 VIN을 마스킹한 것만 씁니다.
9. **라이선스**: AGPL 저장소(예: `yoziru/tesla-ble`)는 열람하지 않습니다. 포팅한 파일 헤더에 원본 경로와 커밋을 표기합니다.
10. **경로 하드코딩 금지**: `{{PATHS_FILE}}` 의 키로만 참조합니다.
11. **문서 동기화**: 요구사항, 설계, 공개 API가 바뀌면 PRD, SDD, 매뉴얼을 **같은 PR에서** 갱신합니다.

## 4. 작업 흐름 (도구 공통)
```
PRD ─🛑승인→ SDD ─🛑승인→ 계획 ─🛑승인→ [작업 단위 반복: 브랜치 → Red → Green → Refactor → 커밋 → AI 리뷰 → PR(CI Green) → main]
                                                                                        └→ 마일스톤 종료: 매뉴얼 갱신 + 인계 노트
```
- 🛑는 **사용자 승인 게이트**입니다. 승인 없이 다음 단계로 넘어가지 않습니다.
- 한 번에 **계획의 작업 하나만** 진행합니다. 계획에 없는 일은 먼저 계획을 고친 뒤에 합니다.
- 세부 절차는 `{{WORKFLOW_FILE}}` 에 있습니다.

## 5. 모듈과 의존 방향 (요약, 확정은 SDD)
```
:sdk (조립 / 공개 파사드)
 ├─ :application  ──▶ :domain
 ├─ :adapter-ble  ──▶ :domain      (Kable)
 ├─ :adapter-crypto ─▶ :domain     (Android Keystore / iOS Security.framework)
 └─ :adapter-storage ▶ :domain     (세션 캐시)
:domain = 순수 Kotlin (프로토콜 모델, Session, Metadata, SlidingWindow, 포트 인터페이스). 외부 의존은 Wire 런타임만.
samples/* ──▶ :sdk 만
```

## 6. 명령어 (M0 이후 유효, SDD에서 확정)
| 목적 | 명령 |
|---|---|
| 전체 검사 (푸시 전 필수) | `./gradlew check` |
| 공통 테스트 (JVM) | `./gradlew jvmTest` |
| iOS 시뮬레이터 테스트 (macOS) | `./gradlew iosSimulatorArm64Test` |
| Android 단위 테스트 | `./gradlew testAndroidHostTest` |
| 정적 분석 | `./gradlew lintKotlin detekt` |
| 금지 토큰 스캔 | `./gradlew forbiddenTokens` |
| 공개 API 호환성 | `./gradlew apiCheck` (변경을 의도했다면 `apiDump` 후 커밋) |
| protobuf 생성 | `./gradlew :domain:generateCommonMainProtos` (Wire 태스크 이름은 `./gradlew :domain:tasks --all \| grep -i proto`로 확인) |
| iOS 프레임워크 | `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` |

## 7. 커밋과 PR
- 접두어: `struct:` (구조) · `feat:` (기능) · `fix:` (결함) · `test:` (테스트만) · `docs:` · `chore:` (의존성·빌드)
- 본문에 추적 ID를 적습니다: `Refs: FR-012` 또는 `Refs: NFR-003`, `ADR-0004`
- PR 머지 조건: **CI Green + AI 코드 리뷰 통과**(리뷰 요약을 PR 본문에 첨부) + PR 템플릿 체크리스트 완료
- 머지 방식: **Rebase merge.** Squash는 금지합니다. `struct` 커밋과 `feat` 커밋이 따로 보존되어야 하기 때문입니다.

## 8. 막혔을 때
1. `{{MANUAL_DIR}}` 개발 매뉴얼 → 원본 Go 코드 → `vehicle-command/pkg/protocol/protocol.md` 순으로 해당 동작을 찾습니다.
2. 그래도 모호하면 추측으로 구현하지 않습니다. 질문 목록을 만들어 사용자에게 묻습니다.
3. 컨텍스트가 길어졌거나 주제가 바뀌면 `{{HANDOFF_DIR}}` 에 인계 노트를 쓰고 새 세션을 제안합니다(`{{WORKFLOW_FILE}}` §10).
