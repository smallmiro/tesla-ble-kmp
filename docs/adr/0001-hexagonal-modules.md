# ADR-0001: 헥사고날 모듈 경계와 Gradle 강제

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-26, SDD v1.0 승인) |
| 결정 번호 | D21 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
HANDOFF §6.1은 기능 단위(protocol/dispatcher/transport/vehicle/keystore)로, AGENTS.md §5와 `{{WORKFLOW_FILE}}` §4는 헥사고날(:domain/:application/:adapter-*/:sdk)로 모듈을 제안했다. 도메인을 BLE·키스토어·OS 없이 테스트하려면 포트 경계가 필요하고, 경계는 리뷰가 아니라 빌드가 지켜야 한다.

## 결정
`:domain`(순수 Kotlin + Wire 생성 코드 + 포트), `:application`(Dispatcher, 명령 유스케이스), `:adapter-ble`, `:adapter-crypto`, `:adapter-storage`, `:sdk`(파사드·조립·SKIE), `:testing`(FakeVehicle 등 테스트 전용)의 7모듈. `build-logic/` 컨벤션 플러그인이 모듈 종류별 허용 의존을 선언하고 그 밖의 `project()` 의존이나 금지 라이브러리를 구성 단계에서 실패시킨다. `:domain`에는 detekt `ForbiddenImport`로 `android.*`, `platform.*`, `com.juul.*` 임포트를 막는다.

## 결과
- HANDOFF §6.1의 이름은 파일·패키지 수준으로 남는다(`protocol/`, `dispatcher/`, `transport/`, `vehicle/`, `keystore/`).
- 새 의존성 추가 PR은 `architecture` 라벨과 ADR이 필요하다.
- 모듈이 7개라 초기 Gradle 설정 비용이 있다(M0에서 흡수).

## 대안
- 단일 모듈 + 패키지 규약: 경계를 빌드가 못 지킨다. 기각.
- HANDOFF §6.1 5모듈: 어댑터와 유스케이스가 한 모듈에 섞여 iOS 전용 코드가 도메인 테스트를 오염시킨다. 기각.
