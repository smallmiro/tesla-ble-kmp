# ADR-0010: 타임아웃 파라미터와 취소 전파

| 항목 | 내용 |
|---|---|
| 상태 | 제안 (2026-09-26) — SDD 승인 시 채택 |
| 결정 번호 | D29 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
Go는 `ctx`의 deadline을 `expires_at` 수명과 응답 대기 시한에 모두 쓴다. Kotlin 코루틴은 남은 시간을 조회할 수 없고, 외부 `withTimeout`의 `CancellationException`을 값으로 바꾸면 취소 의미가 깨진다. PRD NFR-012 초안은 "타임아웃은 호출자가 withTimeout으로 제어"라고 했다.

## 결정
명령마다 `timeout: Duration? = null`(기본 `TeslaBleConfig.commandTimeout` 5초)을 받아 라이브러리가 `withTimeoutOrNull`을 건다. 전송 전에 만료되면 `Failure(Timeout(afterSend=false))`, 전송 후면 `Uncertain(Timeout(afterSend=true))`. `expires_at` 수명은 별도 `commandLifetime`(기본 5초). 외부 취소는 `CancellationException`으로 전파하고 `PendingRequest`를 `finally`에서 해제한다. PRD NFR-012를 이에 맞춰 갱신한다.

## 결과
- Go의 `-command-timeout 5s`와 같은 기본 동작.
- 앱이 더 긴 작업(깨우기 후 명령)을 하려면 `timeout`을 늘리면 된다.

## 대안
- 호출자 `withTimeout`만: 전송 후 타임아웃을 `Uncertain`으로 표현할 수 없다. 기각.
