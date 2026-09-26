# ADR-0006: 결과 모델: sealed VehicleResult

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-26, SDD v1.0 승인) |
| 결정 번호 | D20 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
PRD FR-100은 성공 / 실패 / 결과 불확실 세 가지를 구분하라고 요구한다. Go는 `error` 값과 `MayHaveSucceeded()` 인터페이스로 구분한다. Kotlin에서 불확실을 예외로 던지면 호출자가 놓치기 쉽다.

## 결정
모든 명령·조회는 `VehicleResult<T>`(`Success(value)`, `Uncertain(error)`, `Failure(error)`)를 반환한다. `mayHaveSucceeded`인 오류는 `Uncertain`, 나머지는 `Failure`. 예외는 `CancellationException`과 프로그래밍 오류(`IllegalStateException`: 연결 전 호출 등)에만 쓴다. `VehicleError`는 `sealed`이며 `mayHaveSucceeded`, `temporary`를 노출한다.

## 결과
- 호출자는 `when`/`switch`로 세 갈래를 반드시 다룬다. SKIE가 Swift `enum`으로 변환한다.
- `runCatching` 스타일 헬퍼는 제공하지 않는다(YAGNI).

## 대안
- 예외 기반: 코드가 짧지만 불확실 결과를 놓치기 쉽다. 기각.
- 둘 다 제공: API 표면 두 배. 기각.
