# ADR-0011: 골든 픽스처를 Kotlin 상수로 저장

| 항목 | 내용 |
|---|---|
| 상태 | 제안 (2026-09-26) — SDD 승인 시 채택 |
| 결정 번호 | D30 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
`{{PATHS_FILE}}` 초안은 `FIXTURES_DIR = testing/src/commonTest/resources/fixtures/`였다. Kotlin/Native 테스트 바이너리는 commonTest 리소스를 표준 방식으로 로드하지 못한다.

## 결정
테스트 벡터와 골든 TX/RX 헥사는 `:testing` commonMain의 Kotlin `object` 상수로 둔다(`{{FIXTURES_DIR}}` = `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/fixtures/`). 캡처 로그에서 상수 파일을 만드는 스크립트(`tools/fixtures/import-debug-log.py`)를 M3에서 추가한다. VIN은 테스트 VIN으로 치환한 것만 커밋한다.

## 결과
- JVM과 iOS 시뮬레이터에서 같은 코드로 픽스처를 읽는다.
- 픽스처 변경이 코드 변경으로 보여 리뷰에서 드러난다.

## 대안
- 리소스 파일 + 플랫폼별 로더: expect/actual 추가, 이득 없음. 기각.
