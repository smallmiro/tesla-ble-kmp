# ADR-0010: 타임아웃 파라미터와 취소 전파

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-26, SDD v1.0 승인). **개정** 2026-11-07: "전송" = 첫 BLE 쓰기 호출(아래 H1 절) |
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

## M3 재검토 (2026-10-16)

`expires_at`을 남은 timeout으로 줄이지 않고 `commandLifetime`으로 유지한다 — 설계 구체화 16. 재시도 대기 중 수명이 0에 가까워지면 `TIME_EXPIRED`로 재시도만 늘어난다. Go도 `defaultExpiration`을 고정으로 쓴다. 결정(위 "결정")은 그대로다.

## 2026-11-07 개정: "전송"은 첫 BLE 쓰기 호출이다 (H1)

### 맥락
실차 로그(2026-11-07 10:34:40.909, iOS 앱 백그라운드, 분석 보고서 `{{ANALYSIS_DIR}}2026-11-07-118-awake-car-capture.md` F4·H1,
작성 시점에는 `docs/118-capture-reports` 브랜치)에서 VCSEC
`listKeys` 요청 하나가 9.65초 뒤 `Failure(Timeout(afterSend = false))`("timed out before sending")로 끝났고, 9 ms 뒤 같은 요청에 대한
차량의 응답이 닫힌 요청의 응답(`closed attempt=160`)으로 도착했다. `afterSend`는 `dispatcher.send`가, 곧 `transport.send`가 끝난 뒤에야
true가 됐기 때문이다. `BleTransport`의 쓰기(write-with-response)는 취소되므로, 쓰는 도중에 걸린 기한이 "보내기 전"으로 보고됐다. Go는 BLE
쓰기에서 `ctx`를 무시하고(`pkg/connector/ble/ble.go:108-127`) 전송 뒤의 기한은 응답 대기에서 `PossibleSuccess: true`가 된다
(`pkg/vehicle/vcsec.go:78`, `pkg/vehicle/vehicle.go:211-212`). 원래 결정의 "전송 전/후"는 진행 중인 쓰기가 어느 쪽인지 정하지 않았다.
명령에서는 실행됐을 수 있는 것을 "실행 안 됨"으로 알려 토글 명령(`openTrunk` 등)을 두 번 보내게 할 수 있다.

### 결정 (사용자 승인 H1)
- **"전송" = 요청의 첫 BLE 쓰기를 부른 시점**이다. 쓰기 확인(완료)이 아니다. 그 뒤의 기한은 쓰기가 끝나지 않았어도
  `Uncertain(Timeout(afterSend = true))`다.
- 표시는 전송 계층이 알린다: `Transport.send(message, onWriteInvoked)`(`@InternalTeslableApi`, 기본 구현은 들어오자마자 알리고
  `send(message)`). `BleTransport`는 전송 잠금(`sendMutex`)·연결 검사·프레이밍·TX 로그 뒤, 첫 `link.write` 바로 앞에서 알린다(한 청크
  프레임 포함, 사이에 중단점 없음). 그 직전에 이미 취소됐으면 쓰지도 알리지도 않는다(`ensureActive`).
- **전송 재시도는 표시를 지우지 않는다.** 실패한 write-with-response도 차량에 닿았을 수 있다(ATT 응답 유실, 링크 끊김).
- `afterSend = false`는 쓰기를 부르지 않았을 때만 남는다: VCSEC 락 대기, 전송 잠금 대기, 쓰기 전 검사·실패. 재시도할 응답을 받은 뒤
  다음 시도를 기다리는 동안도 `false`다(차량이 실행하지 않았다고 답했다, Go `vehicle.go:250-252`, `vcsec.go:98-100`).
- 적용: `VcsecCommands`, `SendWithRetry`, `KeyManagement.sessionInfo`(같은 헬퍼, 같은 정의). 핸드셰이크(`VehicleSession.startSession`)는
  그대로 항상 `false`다(부작용 없음, 사용자 승인 답 b).

### Go와 다른 점 (원본 존중 규칙 5의 기록)
- 쓰는 도중의 기한은 Go에 없는 경우다(Go의 BLE 쓰기는 끊기지 않는다). `VcsecCommands`·`SendWithRetry`에서 결과(`PossibleSuccess`)는
  Go와 같다. `sessionInfo`는 다르다: Go `Vehicle.SessionInfo`는 기한에 그냥 `ctx.Err()`를 돌려주지만(`pkg/vehicle/vehicle.go:139-140`),
  이 포트는 M3부터 응답 대기 중 기한을 `Uncertain`으로 보았고 이제 쓰는 도중의 기한도 그렇다.
- 쓰기 실패 뒤의 재전송: Go의 BLE 연결은 실패한 쓰기를 다시 시도하지 않는다. `ble.go:121`은 go-ble의 오류를 그대로 돌려주고,
  `protocol.ShouldRetry`는 `protocol.Error`가 아닌 오류에 false라(`pkg/protocol/error.go:125-138`) `Dispatcher.Send`가 그 오류로 바로
  끝난다(`dispatcher.go:449-452`, "may have succeeded" 아님). 전송 재시도 대기 중 기한을 `PossibleSuccess: false`로 돌려주는
  `dispatcher.go:454-457`은 BLE 쓰기 오류에는 닿지 않는다. 이 포트는 원래부터 `WriteFailed`를 일시 오류로 보고(SDD §6) 같은 바이트를 다시
  쓴다. 이번 개정으로 그 재전송 중의 기한은, 앞서 쓰기를 부른 적이 있으면 `Uncertain`이다(쓰기 실패가 차량이 받지 않았다는 증거가 아니다).
  같은 바이트의 재전송은 서명 명령이면 같은 counter라 차량의 재전송 검사가 거부한다(Go 참조 verifier `internal/authentication/verifier.go:217-220`,
  `INVALID_TOKEN_OR_COUNTER`). 실패로 보인 첫 쓰기가 실제로 실행됐다면 보통은 그 응답이 먼저 온다(같은 요청 키라 재전송 대기 중에도
  받아 둔다) — 결과는 `Success`다. 그러나 첫 응답이 오지 않으면, 중복본에 대한 그 fault가 재시도 대상이라(FR-101) 라이브러리가 새
  counter로 다시 인가해 보내고 **두 번 실행될 수 있다**(NFR-007과 충돌). 이전부터 있던 위험이고(Go는 실패한 BLE 쓰기를 끝내므로 없다)
  이번 범위에서 바꾸지 않는다. 아래 "남은 일"의 사용자 결정 항목이다.
- 쓰기를 부른 뒤 재시도하지 않는 전송 오류로 끝나면(예: 링크가 끊겨 재전송이 `Disconnected`) 기한이 아니라 그 오류의 `Failure`다 —
  "실행 안 됨"으로 읽힌다. Go도 쓰기 오류를 "may have succeeded"로 보지 않는다(같은 동작). 아래 "남은 일"에 둔다.
- 알린 뒤 아무것도 나가지 않는 경우가 남는다: 알림과 Kable 자체의 취소 검사 사이에 취소가 걸리거나, 플랫폼 호출 전에 Kable이 거부할 때
  (연결 없음 등). 둘 다 "보냈을 수 있음"으로 남는다(안전한 쪽의 오차).

### 결과
- 쓰기 완료가 늦는 상황(iOS 백그라운드 등)에서 `Failure(Timeout)`이 줄고 `Uncertain(Timeout)`이 는다. 앱의 규칙(Uncertain이면 재조회,
  재전송 금지)은 그대로다.
- 공개 API: `:domain` 덤프에 `Transport.send(message, onWriteInvoked)` 하나가 늘었다(`@InternalTeslableApi`, Swift 헤더에 프로토콜
  메서드 하나). 앱은 `Transport`를 주입할 수 없다(`TeslaBle` 생성자 internal). `:sdk`·`:application`·`:adapter-*` 덤프는 그대로다.
  **이 예외는 사용자 확인 대상이다.**
- 남은 일(이번 범위 밖):
  - **사용자 결정:** 쓰기를 부른 뒤의 `WriteFailed`를 지금처럼 일시 오류로 두고 같은 바이트를 다시 쓸지, Go처럼 끝낼지, "실행됐을 수
    있음"(`Uncertain`)으로 돌려줄지. 지금 방식은 위 "Go와 다른 점"의 두 번 실행 위험이 있다.
  - 여러 청크 프레임의 첫 청크 쓰기 도중 취소되면 `written == 0`이라 전송을 닫지 않는다(`BleTransport.writeAll`). 첫 청크가 차량에
    닿았다면 차량 재조립 버퍼에 반쪽 프레임이 남을 수 있다.
  - 쓰기를 부른 뒤 재시도하지 않는 전송 오류(예: 재전송 때 `Disconnected`)는 그 오류의 `Failure`로 끝난다(위). "실행됐을 수 있음"으로
    올릴지는 따로 정한다.
  - iOS 샘플의 진단 문구(`StateDiagnostics`)는 `Uncertain(Timeout)`의 원인으로 수면·응답 유실·링크 끊김만 든다. 백그라운드에서 쓰기
    완료가 늦은 경우를 더할지는 샘플 작업(`ios-developer`)이다.

### 대안
- **Dispatcher 수준 전송 잠금 + `transport.send` 직전 표시(포트 변경 없음).** `BleTransport`에 잠금 말고는 쓰기 전 중단점이 없고
  `Dispatcher`만 보낸다는 암묵적 가정에 기댄다(주된 기각 이유). 또 "쓰기 전의 재시도 가능한 전송 실패"를 표현하지 못해 Go
  `TestVehicleSendTimeout`의 포팅이 `Uncertain`으로 바뀐다 — 실제 `BleTransport`에는 그런 실패가 없으므로 시험 표현력의 문제다. 기각.
  사용자가 API 예외를 거부하면 다시 검토하되, 그때도 "첫 쓰기 직전" 경계를 완화하는 승인이 필요하다.
- **공개 `CoroutineContext` 요소로 전달.** API 비용은 같고 더 암묵적이다. 기각.
- Codex 협의(gpt-6.1-sol, high, `fix/aftersend-on-first-write`@`70ec920`): 포트 훅, 표시의 생애(응답 처리를 마친 뒤에만 지움, `finally`
  에서 지우지 않음), Go와의 차이, `sessionInfo` 포함, `ensureActive`에 합의. Codex는 포트 추가를 `struct`가 아닌 `feat` 커밋으로 둘 것과,
  덤프 갱신은 "공개 API 불변"을 만족하지 않으므로 명시적 승인이 필요하다고 지적했다(반영).
