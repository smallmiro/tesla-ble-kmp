# 에러 처리

이 문서는 `docs/manual/README.md` 기준 상대경로를 쓴다. M1 시점의 초안이며, 재시도 루프(M2)와 명령별 재조회 안내(M5)는 해당 마일스톤에서 채운다.

## 결과 세 갈래 (`VehicleResult<T>`)

| 갈래 | 뜻 | 앱이 할 일 |
|---|---|---|
| `Success(value)` | 차량이 확인 응답을 보냈다 | 결과 사용 |
| `Uncertain(error)` | 차량이 실행했을 수 있으나 확인하지 못했다(`error.mayHaveSucceeded == true`) | 재전송하지 말고 상태를 재조회한다(VCSEC → `vehicleStatus()`, Infotainment → `getState()`, M3/M5) |
| `Failure(error)` | 실행되지 않았다 | `error`로 분기 |

Swift에서는 SKIE가 `enum`으로 노출한다(`switch result { case .success(let v): … }`).

## `VehicleError` 계층

모든 오류는 `message`(영어), `mayHaveSucceeded`, `temporary`를 가진다. `shouldRetry()`는 `!mayHaveSucceeded && temporary`다(Go `ShouldRetry`). 라이브러리가 M2부터 이 규칙으로 1초 간격 재시도를 수행하므로 앱이 직접 재시도할 필요는 없다.

| 타입 | 언제 | temporary | mayHaveSucceeded |
|---|---|---|---|
| `ProtocolFault(fault)` | 차량이 `MessageFault` 코드를 보냄 | BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL, INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG만 true | NONE, RESPONSE_MTU_EXCEEDED만 true |
| `KeyNotPaired` | `UNKNOWN_KEY_ID` 또는 session_info `KEY_NOT_ON_WHITELIST` | false | false |
| `Busy` | `operation_status WAIT` | true | false |
| `UnknownFault(rawCode)` | 차량이 이 라이브러리가 모르는 `MessageFault` 코드를 보냄(더 새 펌웨어). `message`는 `"unrecognized error code <rawCode>"` — 라이브러리 업데이트를 확인한다 | false | false |
| `UnknownResponse` | 인식할 수 없는 `session_info.status`·`operation_status` 값(코드 없음) | false | false |
| `NotConnected`, `NoSession`, `RequiresKey` | 호출 순서 오류 | false | false |
| `BadResponse(detail)` | 응답 파싱 실패 | false | VCSEC 응답이면 true |
| `KeychainRejected(code)` | 키 추가·삭제 거부 | false | false |
| `VcsecRejected(error)` | VCSEC `nominalError` | false | false |
| `InfotainmentRejected(reason)` | `actionStatus ERROR` | false | false |
| `TransportError.*` | 스캔·연결·쓰기 (M3) | `ScanTimeout`, `ConnectFailed`, `WriteFailed`만 true | false |
| `KeyStoreError.*` | 키스토어 (M3) | false | false |
| `Timeout(afterSend)` | 명령 시간 초과 | true | `afterSend` |
| `InvalidArgument(detail)` | 호출 인자 오류 | false | false |

`MessageFault` 코드별 의미와 대처는 개발 매뉴얼 `documents/md/08-errors.md` §3.1을 따른다.
