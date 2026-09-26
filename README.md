# tesla-ble-kmp

Tesla 공식 Go SDK [`teslamotors/vehicle-command`](https://github.com/teslamotors/vehicle-command)의 **BLE 경로**를
**Kotlin Multiplatform(Android + iOS)** 으로 포팅하는 라이브러리입니다.
서버, Fleet API, OAuth 없이 스마트폰이 차량과 BLE로 직접 통신합니다(키 등록, 상태 조회, 제어).

> 비공식 커뮤니티 프로젝트입니다. Tesla, Inc.와 관련이 없습니다.
> Unofficial community port of Tesla's `vehicle-command` BLE protocol to Kotlin Multiplatform. Not affiliated with Tesla, Inc.

## 현재 상태

| 단계 | 상태 |
|---|---|
| Phase 0 준비 | 완료 (2026-09-26) |
| Phase 1 PRD | 승인 (2026-09-26) |
| Phase 2 SDD / Phase 3 계획 | 완료 (2026-09-26) |
| 구현 (M0~M6) | M0 완료 (2026-09-26) · M1 진행 예정 |

M0 골격이 `main`에 있습니다 — 라이브러리 모듈 7개 + 샘플 앱 2개, 프로토콜 코어(TLV, 세션 키, AES-GCM, 프레이밍)가 `protocol.md` 테스트 벡터를 JVM과 iOS 시뮬레이터에서 통과합니다. 원본 PoC는 참고용으로 [`reference/poc/`](reference/poc/)에 남아 있습니다. BLE 연결과 명령 API는 M2~M5에서 추가됩니다.

## 문서

모든 경로의 정본은 [`docs/PATHS.md`](docs/PATHS.md)입니다. 문서와 지침은 경로를 `{{키}}`로 참조합니다.

| 문서 | 내용 |
|---|---|
| [`HANDOFF.md`](HANDOFF.md) | 착수 메타 프롬프트: 결정 사항, 범위, 프로토콜 핵심 사실, 마일스톤 |
| [`docs/prd/PRD.md`](docs/prd/PRD.md) | 제품 요구사항 (FR/NFR, 우선순위, 릴리스 계획) |
| [`docs/sdd/SDD.md`](docs/sdd/SDD.md) · [`docs/adr/`](docs/adr/) | 설계 문서 · 아키텍처 결정 기록 |
| [`docs/superpowers/plans/`](docs/superpowers/plans/) | 로드맵 · 마일스톤 구현 계획 |
| [`AGENTS.md`](AGENTS.md) · [`CLAUDE.md`](CLAUDE.md) | 코딩 에이전트 공통 지침 / Claude Code 전용 지침 |
| [`docs/workflow.md`](docs/workflow.md) | 개발 표준: TDD, Tidy First, 헥사고날 아키텍처, 브랜치·CI·DoD |
| [`documents/md/`](documents/md/README.md) · [`documents/html/`](documents/html/index.html) | `vehicle-command` 개발 매뉴얼 (에이전트용 MD / 사람용 HTML, 한국어, 읽기 전용) |
| `docs/handoff/` | 세션 인계 노트 |
| [`docs/manual/README.md`](docs/manual/README.md) | 라이브러리 사용 문서 (앱 개발자용) |

## 구성

- `:domain` 순수 Kotlin 프로토콜 모델 (RoutableMessage, Session, Metadata TLV, SlidingWindow, 포트)
- `:application` Dispatcher, Vehicle 유스케이스
- `:adapter-ble` Kable · `:adapter-crypto` Android Keystore / iOS Security.framework · `:adapter-storage` 세션 캐시
- `:sdk` 공개 파사드 (`suspend` + `Flow`, iOS는 SKIE로 Swift async/await)
- `:testing` 테스트 픽스처 (protocol.md 벡터, FakeVehicle 등, 테스트 소스셋 전용)
- `samples/android` (Compose), `samples/ios` (SwiftUI, XcodeGen)

패키지 루트: `io.github.smallmiro.teslable`. 최소 지원: Android API 31, iOS 16.
빌드·검증 명령은 [`AGENTS.md`](AGENTS.md) §6 참조.

## 배포 (예정, M6)

- Android/KMP: GitHub Packages Maven 레지스트리 `https://maven.pkg.github.com/smallmiro/tesla-ble-kmp` (받을 때 `read:packages` 권한의 GitHub 토큰 필요)
- iOS: Swift Package Manager (`Package.swift` + GitHub Release XCFramework)

## 기여

- 규칙은 [`AGENTS.md`](AGENTS.md)를 따릅니다. 실패 테스트 먼저, 구조 변경과 동작 변경 분리, `main`은 항상 Green.
- PR은 [템플릿](.github/pull_request_template.md)을 채우고, CI Green + AI 코드 리뷰 통과 후 rebase merge 합니다.
- 동작의 정답 기준은 `vehicle-command` 커밋 `a4b43c1`과 `pkg/protocol/protocol.md`입니다.
- AGPL 라이선스 구현체(예: `yoziru/tesla-ble`)의 코드는 열람하거나 복사하지 않습니다.

## 라이선스

[Apache License 2.0](LICENSE). 원본 저작권 고지는 [`NOTICE`](NOTICE)를 참조하세요.
