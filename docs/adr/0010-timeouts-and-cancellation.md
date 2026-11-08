# ADR-0010: 타임아웃 파라미터와 취소 전파

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-26, SDD v1.0 승인). **개정** 2026-11-07: "전송" = 첫 BLE 쓰기 호출(아래 H1 절). **개정** 2026-11-08: 쓰기 실패 뒤 새 사본 금지(아래 ③ 절) |
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
  이번 범위에서 바꾸지 않는다. 아래 "남은 일"의 사용자 결정 항목이다(2026-11-08 개정으로 해결).
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
  - ~~**사용자 결정:** 쓰기를 부른 뒤의 `WriteFailed`를 지금처럼 일시 오류로 두고 같은 바이트를 다시 쓸지, Go처럼 끝낼지, "실행됐을 수
    있음"(`Uncertain`)으로 돌려줄지. 지금 방식은 위 "Go와 다른 점"의 두 번 실행 위험이 있다.~~ → 2026-11-08 사용자 결정 ③으로 해결(아래
    "2026-11-08 개정" 절).
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

## 2026-11-08 개정: 쓰기 실패 뒤에는 새 사본을 인가해 보내지 않는다 (사용자 결정 ③)

### 맥락
위 2026-11-07 절의 "남은 일"에 둔 위험이다. 전송 루프(`Dispatcher.transmit`)는 일시 쓰기 오류(`WriteFailed`) 뒤 **같은 바이트**를 다시 쓴다
(Go와 다른 점, SDD §6). 실패로 보인 첫 쓰기가 실제로는 차량에 닿아 실행됐고 그 응답이 유실되면, 다시 보낸 사본은 같은 counter라 차량의
재전송 검사가 거부한다(Go 참조 verifier `internal/authentication/verifier.go:217-220`, `INVALID_TOKEN_OR_COUNTER` + 서명된 세션정보). 이
fault는 재시도 대상(FR-101)이어서 `retryWhileRetriable`이 **새 counter로 다시 인가한 사본**을 보냈고 명령이 두 번 실행될 수 있었다(NFR-007
충돌, `openTrunk` 같은 토글은 원래대로 돌아간다). 시험으로 재현했다: 수정 전 결과는 두 번 실행된 `Success`였다. Go에는 이 경로가 없다:
BLE 연결이 실패한 쓰기를 그 오류로 끝내고(`pkg/connector/ble/ble.go:121`), 그 오류는 `protocol.ShouldRetry`가 false라
(`pkg/protocol/error.go:125-138`) `Dispatcher.Send`도 `Vehicle.Send`·`getVCSECResult`도 다시 보내지 않는다.

### 결정 (사용자 승인 ③, 2026-11-08)
결정 ③은 위 2026-11-07 절 "남은 일"의 셋째 선택지("실행됐을 수 있음"으로 돌려줌)를 사용자가 구체화한 것이다: 같은 바이트의 재전송은
그대로 두고, 그 뒤 새 사본을 인가하게 될 거부가 오면 다시 보내지 않고 `Uncertain`으로 끝낸다(쓰기 실패 자체를 곧바로 `Uncertain`으로
돌려주는 것이 아니다).
- **표시.** `Dispatcher.transmit`는 한 번의 `Transport.send`가 쓰기를 부른 뒤(`onWriteInvoked`) 재시도할 오류로 실패하면, 재시도 대기 전에
  그 요청(`PendingRequest`, 서명한 사본 하나)에 `hadFailedWrite`를 세운다. 요청마다 새로 시작하고 내리지 않는다. 쓰기를 부르기 전의 실패는
  세우지 않는다. 쓰기 시점을 모르는 전송(`Transport.send(message, onWriteInvoked)` 기본 구현은 들어오자마자 알린다)은 재시도할 오류로 끝난 실패가
  모두 표시가 된다(안전한 쪽).
- **보호 대상 요청(명령)에서는 새 사본이 없다.** 표시된 요청의 응답이 다음 오류면 결과로 받지 않는다(`awaitOutcome`). WARN 한 줄(태그
  `Dispatcher`, `possible duplicate`)을 남기고 **같은 요청**을 계속 읽는다.
  - 새 시도를 시작할 오류 전부(`shouldRetry()`이고 `KeyStoreError.Locked`가 아님): `BUSY`, `TIMEOUT`, `INVALID_SIGNATURE`,
    `INVALID_TOKEN_OR_COUNTER`, `INTERNAL`, `INCORRECT_EPOCH`, `TIME_EXPIRED`, `TIME_TO_LIVE_TOO_LONG`, `Busy`(`WAIT`, VCSEC
    `commandStatus WAIT` 포함).
  - 메시지에 대한 프로토콜 계층의 판정 전부: `ProtocolFault`(재시도 대상이 아닌 `REPEATED_COUNTER` 등 모든 코드. 단 `mayHaveSucceeded`인
    `RESPONSE_MTU_EXCEEDED`는 이미 `Uncertain`이라 결과를 정한다), `KeyNotPaired`
    (`UNKNOWN_KEY_ID`, `KEY_NOT_ON_WHITELIST`), `InsufficientPrivileges`, `UnknownFault`. 중복 사본에 대한 답이거나 첫 사본이 바꾼 상태에 대한
    답일 수 있다(예: 자기 키를 지운 `removeKey`의 재전송 사본은 `UNKNOWN_KEY_ID`). 그래서 "실행 안 됨"(`Failure`)으로 단정하지 않는다.
  - 첫 사본의 응답이 늦게 오면(같은 routing address·uuid·request hash라 같은 요청으로 배달되고 복호화된다) 그것이 결과다. 끝내 오지 않으면
    원래의 시간 제한이 `Uncertain(Timeout(afterSend = true))`로 끝낸다 — 응답 유실과 같은 결과다(`afterSend`는 그동안 true, 시간 제한·
    `expires_at` 수명을 다시 시작하지 않는다). 새 오류 타입은 없다.
  - **결과를 정하는 응답**: 성공, `mayHaveSucceeded` 오류(이미 `Uncertain`: `RESPONSE_MTU_EXCEEDED`, 해석할 수 없는 응답), 페이로드 안의
    애플리케이션 결과(`VcsecRejected`·`UnknownVcsecError`, `KeychainRejected`·`UnknownKeychainCode`, `InfotainmentRejected`). 애플리케이션
    결과는 그 사본이 counter 검사를 통과했다는 뜻이고 같은 counter의 사본은 하나만 통과하므로(verifier는 `Verify`에서 서명 검사 뒤,
    명령 실행 전에 counter를 소비한다, `verifier.go:202-220`) 다른 사본은 실행되지 않았다. 이 논거는 진짜 응답과 Go 참조 verifier의 검사 순서를 전제한다.
  - VCSEC의 옛 `commandStatus{ERROR, signedMessageStatus}`(counter 거부가 페이로드 안에 오는 형태)는 Go처럼 오류가 아니고 RKE·화이트리스트
    종료 판정이 계속 읽으므로 결과가 되지 않는다(시험으로 고정). 임의의 종료 판정까지 보장하지는 않는다.
- **면제: 반복해도 안전한 조회만.** 차량 상태를 바꾸지 않는 요청은 Go처럼 재시도할 오류에 새 사본(새 요청)을 보낸다: VCSEC 정보 요청
  (`VehicleInfoQueries`: `vehicleStatus`, `keySummary`, `keyInfoBySlot`, `listKeys`)과 Infotainment `getState`. 내부 진입점
  (`VcsecCommands.query`, `SendWithRetry.query`)으로만 면제하고 기본은 보호다 — `AuthMethod.NONE`으로 추론하지 않는다(인증 방식은 동작의
  의미가 아니고, 공개 `VcsecCommands.execute`는 임의의 페이로드를 받는다). 면제는 기존 재시도 자격만 유지한다: 응답 유실·
  `mayHaveSucceeded` 오류를 다시 보내지는 않는다.
- **표시가 없는 경로는 그대로다.** 캐시 세션을 차량이 받지 않아 fault와 세션정보가 오는 회복(분석 보고서
  `{{ANALYSIS_DIR}}2026-11-07-118-awake-car-capture.md` F2, `FakeVehicleScenarioTest` 시나리오 3)은 쓰기 실패가 없으므로 지금처럼 세션정보를
  반영하고 다시 인가해 보낸다(명령도 조회도). 표시된 요청에서도 응답에 동봉된 세션정보는 그대로 반영한다(디스패처가 전달 전에 처리, 결과
  판정과 무관).

### Go와 다른 점 (원본 존중 규칙 5의 기록)
- Go는 실패한 BLE 쓰기에서 명령을 그 오류로 끝낸다("may have succeeded" 아님). 이 포트는 같은 바이트의 재전송은 유지하되, 쓰기를 부른 뒤
  실패한 **보호 대상 요청**에서는 새로 인가한 사본의 재시도를 막는다. 감사한 조회는 기존 재시도 자격을 유지한다.
- Go에서 `REPEATED_COUNTER`·`UNKNOWN_KEY_ID`·`INSUFFICIENT_PRIVILEGES` 같은 재시도 대상이 아닌 fault는 곧바로 오류다. 이 포트는 쓰기 실패
  뒤의 보호 대상 요청에서만 이것들을 결과로 받지 않고 시간 제한까지 기다려 `Uncertain(Timeout(afterSend = true))`가 될 수 있다. 첫 사본이
  닿지 않았고 차량이 다시 보낸 사본을 정말로 거부한 경우(예: Vehicle Monitor 키의 명령)에도 앱은 즉시 `Failure` 대신 시간 제한 뒤
  `Uncertain`을 받는다(드문 경로의 비용, 다음 호출이 진짜 이유를 돌려준다).

### 결과
- 이 드문 경로(쓰기 실패 + 첫 응답 유실)에서 명령은 한 번만 실행되고 결과는 `Uncertain(Timeout(afterSend = true))`다. 앱의 규칙(Uncertain이면
  재조회, 재전송 금지)은 그대로다. 그동안 VCSEC 직렬화 락을 쥔다(응답 유실과 같다).
- 공개 API는 바뀌지 않는다: `PendingRequest.hadFailedWrite`, `VcsecCommands.query`, `SendWithRetry.query`, `awaitOutcome`은 모두 `internal`
  이고 `:sdk`·`:application`·`:domain` 덤프가 그대로다. `:testing`에 `FakeTransport.enqueueWriteErrorAfterDelivery`,
  `FakeVehicle.holdNextReplies`/`releaseHeldReplies`가 늘었다(배포하지 않는 시험 모듈).
- 남은 일·알려진 한계:
  - 쓰기를 부른 뒤 재시도하지 않는 전송 오류(예: 재전송 때 `Disconnected`)는 여전히 그 오류의 `Failure`다(2026-11-07 절 "남은 일").
    전송 루프가 요청을 넘겨주지 못하면 버퍼에 온 첫 사본의 응답도 읽지 않는다.
  - 값만 보는 판정이라 완전한 계층 분류는 아니다: 프로토콜 계층에서 온 `UnknownResponse`(모르는 `session_info.status`·`operation_status`)와
    `BadResponse(mayHaveSucceeded = false)`(해석할 수 없는 세션정보)는 애플리케이션 계층의 같은 오류와 가려낼 수 없어 결과로 둔다(드묾).
  - ADR-0015(태그 없는 fault는 결과로 단정하지 않고 시간 제한까지 기다림)를 구현하면 이 규칙은 그 일부가 된다. 태그 없는 fault 전체의 처리와
    F2 회복의 관계는 그 계획에서 정한다.

### 대안
- **(U) 그 응답에서 곧바로 `Uncertain`을 돌려준다.** 정직한 오류 타입이 없다: `Timeout(afterSend = true)`는 기다리지 않았는데 "시간 초과"라
  하고, `BadResponse(mayHaveSucceeded = true)`는 "해석할 수 없음"을 뜻하고, `Uncertain(ProtocolFault(…))`는 `Uncertain.error.mayHaveSucceeded
  == true` 불변식을 깨고, 새 `VehicleError` 하위 타입은 공개 API(sealed 계층, Swift enum) 변경이다. 늦게 온 첫 사본의 응답도 놓친다. 기각.
- **조회까지 같은 규칙.** 단순하지만 조회는 반복해도 안전하고, 인증 없는 VCSEC 조회는 밀집 요청에서 `WAIT`를 받은 뒤 같은 요청의 최종 응답이
  오지 않는 것이 관찰됐다(분석 보고서 F5) — 기다리면 시간 제한까지 `Uncertain`만 는다. 기각.
- **`AuthMethod.NONE`으로 면제 판단.** 인증 방식은 동작의 의미가 아니다. 기각.
- **재시도 대상 오류와 `REPEATED_COUNTER`만 결과에서 제외.** 자기 키 삭제 뒤 `UNKNOWN_KEY_ID`, 그사이 바뀐 권한의 `INSUFFICIENT_PRIVILEGES`가
  "실행 안 됨"으로 남는다. 키·권한 판정의 위치가 차량에서 어디인지 Go 참조로 정해지지 않는다(verifier는 이미 키를 안다). 프로토콜 계층
  판정 전부로 넓혔다.
- Codex 협의(gpt-6.1-sol, high, `fix/no-resign-after-failed-write`@`20e028d`, 브랜치·HEAD 확인, 2회):
  - 합의: (T) 같은 요청을 계속 읽고 시간 제한에서 `Uncertain`; 명시적 내부 `repeatSafe`(조회 진입점만, 기본 보호); 요청마다 내리지 않는
    표시를 재시도 대기 전에 세움; 읽기·분류·건너뛰기를 `use` 하나 안에서; 건너뛰는 동안 `afterSend` 유지(`SendWithRetry`는 분류 전에
    표시를 내리던 순서를 바꿈); 세션정보 반영 유지; `REPEATED_COUNTER`는 전역 `RETRIABLE_FAULTS`에 넣지 않고 이 판정에만; 판정을 값의
    순수 함수로 두되 `mayHaveSucceeded`를 먼저 본다; 옛 `signedMessageStatus` 형태의 회귀 시험; 결정 로그는 "possible duplicate"로(첫 사본이
    실행됐다고 단정하지 않음).
  - Codex가 처음 제기하고 반영한 점: 재시도 대상이 아닌 종료 fault를 모두 `Failure`로 두는 논거는 조건부다(차량에서 키 조회·권한 검사의
    위치를 verifier가 정하지 않음, 자기 키 삭제 뒤 `UNKNOWN_KEY_ID`가 반례) → 키·권한 판정을 포함해 프로토콜 계층 판정 전부를 결과에서 뺐다.
    기존 `enqueueWriteError`는 바이트를 차량에 넘기지 않아 재현이 안 된다 → 시험용 전송 훅을 더했다.
  - 남은 이견: 없음. Codex는 "페이로드 안의 결과면 결과를 정한다"를 완전한 분류로 부르지 말 것(진짜 응답과 참조 verifier 모델이 전제)을
    요구했고, 위 결정과 알려진 한계에 그렇게 적었다.
