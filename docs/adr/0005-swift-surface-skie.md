# ADR-0005: Swift 표면: SKIE

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-26, SDD v1.0 승인) |
| 결정 번호 | D25 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
Kotlin/Native의 `suspend`는 Swift에서 completion handler, `Flow`는 불투명 객체, `sealed class`는 클래스 계층으로 노출되어 iOS 개발자 경험이 나쁘다.

## 결정
Touchlab SKIE 0.10.15(Apache-2.0)를 `:sdk`에만 적용한다. `suspend` → `async throws`(양방향 취소), `Flow`/`StateFlow` → `AsyncSequence`, `sealed`/`enum` → Swift `enum`, 기본 인자 오버로드 생성. SKIE는 Kotlin 버전마다 릴리스되므로 Kotlin 업그레이드는 SKIE 지원 목록을 먼저 확인한다.

## 결과
- iOS 샘플 앱이 `for await event in vehicle.addKeyRequest()`처럼 자연스럽게 쓴다.
- 프레임워크 빌드 시간이 늘고 Gradle 캐시 관련 알려진 이슈가 있다.

## 대안
- SKIE 없음: 샘플과 문서에 콜백 브리지 코드가 필요. 기각(D12).
- 수기 Swift 래퍼: 공개 API마다 두 번 작성. 기각.
