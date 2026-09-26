# ADR-0009: add-key-request 응답 읽기 (원본과 다른 동작)

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-26, SDD v1.0 승인) |
| 결정 번호 | D28 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
Go `SendAddKeyRequestWithRole`은 `ToVCSECMessage{PRESENT_KEY}`를 `conn.Send`로 보내고 즉시 반환하며 응답을 읽지 않는다. 매뉴얼 `10-porting-guide §10`은 차량이 `FromVCSECMessage.commandStatus.operationStatus = WAIT`로 카드 태그를 기다린다고 설명한다. 앱은 "키카드를 태그하세요" 상태를 보여줘야 한다(FR-023).

## 결정
`Vehicle.addKeyRequest()`는 `Flow<PairingEvent>`를 반환한다. 전송 후 `Dispatcher`의 원시 프레임 탭(RoutableMessage 파싱 전)에서 `FromVCSECMessage`로 파싱을 시도해 `Sent`, `WaitingForTap`, `Accepted`, `Rejected(code)`, `Unknown(raw)`를 방출한다. 기본 60초 후 종료. 등록 확정은 Go와 같이 `sessionInfo(publicKey, INFOTAINMENT)`로 확인한다. `Dispatcher.process`의 드롭 규칙은 바꾸지 않는다.

## 결과
- 원본에 없는 동작이므로 실차 검증 전까지 `Unknown(raw)`로 원시 바이트를 노출하고 M4 실차 체크리스트에 항목을 둔다.
- 응답 형식이 예상과 다르면 이벤트 매핑만 수정하면 된다.

## 대안
- Go와 동일하게 즉시 반환: 앱이 진행 상태를 알 수 없다. 기각(FR-023).
