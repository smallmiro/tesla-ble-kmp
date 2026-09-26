# 08. 오류 모델, 재시도 정책, 문제 해결

SDK가 반환하는 오류의 타입 체계, 프로토콜/애플리케이션 계층 오류 코드 전체 표, 각 재시도 루프의 정책, 세션 desync 자동 복구, 트러블슈팅 매트릭스와 디버깅 절차.

관련 파일 (저장소 루트 기준)

- `pkg/protocol/error.go` — `Error` 인터페이스, sentinel 오류, `CommandError`, `RoutableMessageError`, `NominalError`, `KeychainError`, `GetError`, `ShouldRetry`
- `pkg/protocol/protobuf/universal_message.proto` — `MessageFault_E`, `OperationStatus_E`
- `pkg/protocol/protobuf/errors.proto` — `GenericError_E`, `NominalError`
- `pkg/protocol/protobuf/vcsec.proto` — `WhitelistOperation_information_E`, `SignedMessage_information_E`, `CommandStatus`
- `pkg/protocol/protobuf/signatures.proto` — `Session_Info_Status`
- `pkg/connector/inet/inet.go` — `HTTPError`, `ErrVehicleNotAwake`, HTTP 상태 코드 매핑
- `pkg/connector/ble/ble.go`, `device_linux.go` — BLE sentinel, `IsAdapterError`
- `pkg/vehicle/vehicle.go`, `vcsec.go`, `infotainment.go` — 재시도 루프, 응답 해석
- `internal/dispatcher/dispatcher.go`, `session.go` — 세션 갱신, 응답 라우팅, 리플레이 드롭
- `internal/authentication/signer.go` — `UpdateSessionInfo` (카운터/epoch 갱신 규칙)
- `internal/log/logger.go` — 로그 레벨
- `pkg/proxy/proxy.go` — HTTP 상태 코드 변환 (`httpStatusCode`)

---

## 1. 오류 타입 체계

### 1.1 `protocol.Error` 인터페이스

```go
// pkg/protocol/error.go
type Error interface {
	error
	MayHaveSucceeded() bool // 차량이 명령을 받아 실행했을 가능성이 있는가 (응답 미수신 등)
	Temporary() bool        // 일시적 조건인가 (재시도로 해결될 수 있는가)
}
```

두 불리언의 조합이 재시도 여부를 결정한다. **`MayHaveSucceeded()==true` 이면 절대 재시도하지 않는다** (같은 명령이 두 번 실행될 수 있으므로).

### 1.2 구현체

| 타입 | 필드 | `MayHaveSucceeded` | `Temporary` | `Error()` 문자열 | 생성 위치 |
|---|---|---|---|---|---|
| `*protocol.CommandError` | `Err error`, `PossibleSuccess bool`, `PossibleTemporary bool` | `PossibleSuccess` | `PossibleTemporary` | `Err.Error()` (Unwrap 지원) | `protocol.NewError(msg, mayHaveSucceeded, temporary)` 및 각 계층에서 직접 생성 |
| `*protocol.RoutableMessageError` | `Code universal.MessageFault_E` | `Code == MESSAGEFAULT_ERROR_NONE` 또는 `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED` | `Code`가 아래 `retriableErrors` 8개 중 하나 | `MessageFault_E_name[Code]` (예: `MESSAGEFAULT_ERROR_BUSY`), 미등록 값이면 `unrecognized error code N` | `protocol.GetError` |
| `*protocol.NominalError` | `Details error` | `MayHaveSucceeded(Details)` | `Temporary(Details)` | `Details.Error()` (Unwrap 지원) | 차량이 인증은 통과했으나 실행 거부한 경우. Infotainment: `infotainment.go getCarServerResponse`; VCSEC: `vcsec.go unmarshalVCSECResponse`; 프록시 파라미터 오류: `proxy/command.go` |
| `*protocol.NominalVCSECError` | `Details *errors.NominalError` (protobuf) | `false` | `false` | `vcsec could not execute command: <Details.String()>` (GenericError != NONE일 때) / `...: GENERICERROR_NONE` | `vcsec.go unmarshalVCSECResponse`; 항상 `NominalError{Details: &NominalVCSECError{...}}` 로 래핑됨 |
| `*protocol.KeychainError` | `Code vcsec.WhitelistOperationInformation_E` | `false` | `false` | `keychain operation failed: <Code>` | `vcsec.go unmarshalVCSECResponse`, `isWhitelistOperationComplete` |
| `*inet.HTTPError` | `Code int`, `Message string` | 4xx → `false`; 그 외 `Code != 503` | `Code ∈ {503, 504, 408, 421}` | `Message`가 비어 있으면 `http.StatusText(Code)` | `inet.SendFleetAPICommand` (200/422/503/408 특수 처리 외의 모든 상태), `proxy/command.go` (400 invalid_command, 413) |
| `authentication.Error` (`internal`) | `Code MessageFault_E`, `Info string` | (구현 안 함) | (구현 안 함) | `IncorrectEpoch: <info>` 형식 (CamelCase 코드) | `internal/authentication`; 외부에는 `protocol.ErrInvalidPublicKey` 로만 노출 |

`retriableErrors` (`pkg/protocol/error.go`):

```go
var retriableErrors = []universal.MessageFault_E{
	MESSAGEFAULT_ERROR_BUSY,                     // 1
	MESSAGEFAULT_ERROR_TIMEOUT,                  // 2
	MESSAGEFAULT_ERROR_INVALID_SIGNATURE,        // 5
	MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, // 6
	MESSAGEFAULT_ERROR_INTERNAL,                 // 11
	MESSAGEFAULT_ERROR_INCORRECT_EPOCH,          // 15
	MESSAGEFAULT_ERROR_TIME_EXPIRED,             // 17
	MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG,    // 20
}
```

### 1.3 헬퍼 함수 의미론

| 함수 | 동작 |
|---|---|
| `protocol.MayHaveSucceeded(err) bool` | `errors.As(err, &protocol.Error)` 성공 && `MayHaveSucceeded()`. 래핑된 오류도 추적. |
| `protocol.Temporary(err) bool` | `errors.As` 성공 && `Temporary()`. |
| `protocol.ShouldRetry(err) bool` | `err == nil` → `false`. `protocol.Error`가 아니면 `false`. **`MayHaveSucceeded()` 가 `true`면 `Temporary()`와 무관하게 `false`**. 그 외 `Temporary()`. |
| `protocol.IsNominalError(err) bool` | `errors.As(err, &*NominalError)`. 프록시가 "차량이 거부" (HTTP 200 + `{"response":{"result":false,"reason":...}}`) 와 "서버/전송 오류"를 구분하는 데 사용. |
| `protocol.GetError(u *universal.RoutableMessage) error` | 아래 1.4 |

### 1.4 `GetError` 변환 규칙 (프로토콜 계층 → Go 오류)

순서대로 평가한다. `pkg/protocol/error.go`.

1. `u.signedMessageStatus.signed_message_fault != MESSAGEFAULT_ERROR_NONE`
   - `MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID` → `protocol.ErrKeyNotPaired` (더 친절한 메시지로 치환)
   - 그 외 → `&RoutableMessageError{Code: fault}`
2. `u.session_info` 페이로드가 있으면 `signatures.SessionInfo`로 파싱
   - 파싱 실패 → `ErrBadResponse`
   - `status == SESSION_INFO_STATUS_OK` → 계속
   - `status == SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST` → `ErrKeyNotPaired`
   - 그 외 → `ErrUnknown`
3. `u.signedMessageStatus.operation_status`
   - `OPERATIONSTATUS_OK` → `nil`
   - `OPERATIONSTATUS_WAIT` → `ErrBusy`
   - `OPERATIONSTATUS_ERROR` → **`nil`** (case 본문이 비어 있어 switch를 빠져나가 `return nil`; fault가 NONE인 ERROR는 프로토콜 계층에서는 성공으로 보고 애플리케이션 계층 파싱에 맡김)
   - 미정의 값 → `ErrUnknown`

주의: `GetError`는 `RoutableMessage` 만 본다. 페이로드(`FromVCSECMessage`, `CarServer.Response`) 안의 애플리케이션 오류는 §3에서 별도로 해석한다.

---

## 2. Sentinel 오류 전체 표

MHS = `MayHaveSucceeded`, T = `Temporary`. `-` 는 `protocol.Error`를 구현하지 않는 일반 `error` (→ `ShouldRetry` 항상 false).

### 2.1 `pkg/protocol/error.go`

| 이름 | 메시지 | MHS | T | 발생 위치 | 대처 |
|---|---|---|---|---|---|
| `ErrBusy` | `vehicle busy or finishing wake-up` | false | true | `GetError` (operation_status WAIT), `vcsec.go` (VCSEC `OPERATIONSTATUS_WAIT`) | 자동 재시도됨. ctx 타임아웃을 충분히 (웨이크업 포함 20~30s) |
| `ErrUnknown` | `vehicle responded with an unrecognized status code` | false | false | `GetError`, `vcsec.go` (ERROR + signedMessageStatus nil) | 펌웨어가 SDK보다 신규. SDK 업데이트 |
| `ErrNotConnected` | `vehicle not connected` | false | false | `dispatcher.Send` (Start 전/Stop 후), `inet.Send` (Close 후) | `car.Connect(ctx)` 먼저 호출; `Disconnect` 후 재사용 금지 |
| `ErrNoSession` | `cannot send authenticated command before establishing a vehicle session` | false | false | `dispatcher.Send` (auth != None 인데 해당 도메인 세션 없음/미준비) | `car.StartSession(ctx, domains)` 호출; 도메인 부분 집합만 열었으면 그 도메인만 사용 가능 |
| `ErrRequiresKey` | `no private key available` | false | false | `dispatcher.RequestSessionInfo` (privateKey nil) | `NewVehicle`에 개인 키 전달 |
| `ErrInvalidPublicKey` | `BadParameter: invalid public key` | - | - | `AddKeyWithRole`/`RemoveKey`/`SendAddKeyRequestWithRole` (P-256 아님), `LoadPublicKey`, ECDH | 65바이트 uncompressed P-256 공개 키인지 확인 |
| `ErrKeyNotPaired` | `vehicle rejected request: your public key has not been paired with the vehicle` | false | false | `GetError` | 키 페어링 (`add-key-request` + NFC). 프록시는 HTTP 412 |
| `ErrUnpexpectedPublicKey` (원문 오타) | `remote public key changed unexpectedly` | - | - | 선언만 존재 (현재 코드에서 반환처 없음) | - |
| `ErrBadResponse` | `invalid response` | - | - | `GetError` (SessionInfo 파싱 실패), `Vehicle.SessionInfo` (session_info 없음), `vcsec.go` (`%w` 래핑, MHS=true) | 차량/펌웨어 비호환 또는 전송 손상. `-debug`로 RX hex 확인 |
| `ErrProtocolNotSupported` | `vehicle does not support protocol -- use REST API` | - | - | `inet.SendFleetAPICommand` (HTTP 422) | 2021년 이전 Model S/X. Fleet API REST 명령 사용. 프록시는 VIN을 unsupported로 표시 후 REST 포워딩 |
| `ErrRequiresBLE` | `command can only be sent over BLE` | - | - | `SendAddKeyRequestWithRole` (conn이 `FleetAPIConnector`) | `-ble` 로 실행 |
| `ErrRequiresEncryption` | `command should not be sent in plaintext or encrypted with an unauthenticated public key` | - | - | `SetPINToDrive` (conn이 `FleetAPIConnector`가 아닐 때) | PIN 설정은 Fleet API 경로로만 |
| `ErrNoDecryptionContext` | `could not decrypt vehicle response without a session` | - | - | `dispatcher.decrypt` (암호화 응답인데 도메인 세션 없음) | 로그로만 남고 메시지 드롭. 세션 캐시/키 확인 |
| `ErrReplayedResponse` | `received vehicle response with duplicate counter` | - | - | `session.decrypt` (슬라이딩 윈도우 실패) | 정상 (재전송 중복). 메시지 드롭됨 |

### 2.2 다른 패키지

| 이름 | 패키지 | 메시지 | MHS | T | 발생 위치 | 대처 |
|---|---|---|---|---|---|---|
| `ErrAdapterInvalidID` | `connector/ble` | `the bluetooth adapter ID is invalid` | false | false | `InitAdapterWithID` (Linux: `hci0`~`hci15` 아님; macOS: ID 지정 자체 불가) | Linux만 `-bt-adapter hciN` |
| `ErrMaxConnectionsExceeded` | `connector/ble` | `the vehicle is already connected to the maximum number of BLE devices` | false | false | `tryToConnect` (광고 `Connectable == false`) | VCSEC는 최대 3개 BLE 연결(키포브/폰키 포함). 다른 기기 끊기 |
| `ErrVehicleNotAwake` | `connector/inet` | `vehicle unavailable: vehicle is offline or asleep` | false | false | `SendFleetAPICommand` (HTTP 503, 또는 408 + body에 `vehicle is offline`) | `car.Wakeup(ctx)` 후 재시도. 프록시는 HTTP 408 |
| `ErrNoFleetAPIConnection` | `vehicle` | `not connected to Fleet API` | - | - | 선언만 존재 | - |
| `ErrVehicleStateUnknown` | `vehicle` | `could not determine vehicle state` | - | - | 선언만 존재 | - |
| `ErrInvalidPIN` | `vehicle` | `PIN codes must be four digits` | - | - | `SetValetMode(on)`, `ParentalControlsActivate/Deactivate` | 4자리 숫자 문자열 |
| `ErrNoKeySpecified` | `cli` | `private key location not provided` | - | - | `Config.PrivateKey`, `SavePrivateKey` | `-key-name`/`-key-file` 또는 `TESLA_KEY_NAME`/`TESLA_KEY_FILE` |
| `ErrNoAvailableTransports` | `cli` | `no available transports (configuration must permit BLE and/or OAuth)` | - | - | `Config.Connect` | `Flags`에 `FlagBLE|FlagVIN` 또는 `FlagOAuth` 포함 |
| `ErrKeyNotFound` | `cli` (= `keyring.ErrKeyNotFound`) | (keyring 라이브러리 메시지) | - | - | 키링에 항목 없음 | `tesla-keygen create` / `tesla-auth-token` |
| `ErrCommandNotImplemented` | `proxy` | `command not implemented` | - | - | `ExtractCommandAction` (`remote_boombox`) | - |
| `ErrCommandUseRESTAPI` | `proxy` | `command requires using the REST API` | - | - | `ExtractCommandAction` (`navigation_request`, `set_managed_*`) | 프록시가 자동으로 REST 포워딩 |
| `ErrInvalidPrivateKey` | `internal/authentication` | `invalid private key` (`%w` 래핑: `: expected PEM encoding`, `: only elliptic curve keys supported`, `: only NIST-P256 keys supported`) | - | - | `LoadExternalECDHKey` (= `protocol.LoadPrivateKey`) | PEM `EC PRIVATE KEY` 또는 PKCS8 `PRIVATE KEY`, P-256 |
| `ErrMetadataFieldTooLong` | `internal/authentication` | `metadata fields can't be more than 255 bytes long` | - | - | VIN 등 메타데이터 > 255B | - |
| `ErrCommandLineArgs` | `cmd/tesla-control` | `invalid command line arguments` | - | - | 인자 개수/값 오류 → 명령별 Usage 출력 | - |
| `ErrRequiresOAuth` / `ErrRequiresVIN` / `ErrRequiresPrivateKey` / `ErrUnknownCommand` | `cmd/tesla-control` | `command requires a FleetAPI OAuth token` / `command requires a VIN` / `command requires a private key` / `unrecognized command` | - | - | `checkReadiness` | 6장 참조 |

### 2.3 인라인으로 생성되는 `CommandError` (메시지로 식별)

| 메시지 | MHS | T | 위치 | 의미 |
|---|---|---|---|---|
| `<ctx.Err()>` (`context deadline exceeded` 등) | true | true | `Vehicle.trySend`, `vcsec.readUntil` | 명령 전송 후 응답 대기 중 타임아웃. **차량이 실행했을 수 있음** → `ShouldRetry` false |
| `<ctx.Err()>` | false | true | `dispatcher.Send` 전송 재시도 루프 | 전송 자체가 실패한 채 타임아웃. 실행 안 됨 |
| `cannot send message without a destination domain` | false | false | `dispatcher.Send` | `ToDestination.domain`이 `DOMAIN_BROADCAST(0)` |
| `payload missing from vehicle response` | true | false | `vcsec.unmarshalVCSECResponse` | 페이로드 oneof가 bytes가 아님 |
| `invalid response: <proto err>` | true | false | `vcsec.unmarshalVCSECResponse` | `FromVCSECMessage` 파싱 실패 |
| `unable to parse vehicle response: <err>` | true | false | `infotainment.getCarServerResponse` | `CarServer.Response` 파싱 실패 |
| `car could not execute command: <plain_text or "unspecified error">` | false | false | `infotainment.getCarServerResponse` (NominalError 로 래핑) | Infotainment 애플리케이션 거부 |
| `<http err>` | false | true | `inet.SendFleetAPICommand` (요청 생성/`client.Do` 실패) | 네트워크 오류 |
| `<read err>` | true | false | `inet.SendFleetAPICommand` (본문 읽기 실패) | 서버가 처리했을 수 있음 |
| `response exceeds maximum length` | true | true | `inet.SendFleetAPICommand` (> `connector.MaxResponseLength`=100000), `proxy.forwardRequest` (> 10000000) | - |
| `unable to parse server response: <err>` | true | false | `inet.Send` | `{"response": ...}` JSON 아님 |
| `dropped response because inbox is full` | true | false | `inet.Send` | inbox(5) 가득 참. 응답을 소비하지 않은 채 연속 전송 |
| `max retry exhausted` | false | false | `proxy.forwardRequest` | 421 재시도 2회 초과 |

---

## 3. 프로토콜/애플리케이션 계층 오류 코드

### 3.1 `UniversalMessage.MessageFault_E` (0~28)

"재시도" 열은 `RoutableMessageError.Temporary()` 기준 (retriableErrors 포함 여부). MHS 열은 `MayHaveSucceeded()`.

| 값 | 이름 (`MESSAGEFAULT_ERROR_` 생략) | 의미 (proto 주석 번역) | 재시도 | MHS | 클라이언트 대처 |
|---|---|---|---|---|---|
| 0 | `NONE` | 성공 | - | true | - |
| 1 | `BUSY` | 필요한 차량 서브시스템이 바쁨. 다시 시도 | O | false | 자동 재시도. 웨이크업 직후 흔함 |
| 2 | `TIMEOUT` | 차량 서브시스템이 응답하지 않음. 다시 시도 | O | false | 자동 재시도 |
| 3 | `UNKNOWN_KEY_ID` | 명령을 인가한 키를 차량이 모름. 키 페어링 확인 | X | false | `GetError`가 `ErrKeyNotPaired`로 치환. 페어링 필요 |
| 4 | `INACTIVE_KEY` | 명령을 인가한 키가 비활성화됨 | X | false | 차량 Locks 화면에서 키 상태 확인, 재페어링 |
| 5 | `INVALID_SIGNATURE` | 서명/MAC 불일치. 동봉된 session info로 세션 갱신 후 재시도 | O | false | 자동: 응답에 동봉된 session_info로 갱신 후 재전송. 지속되면 캐시 삭제 |
| 6 | `INVALID_TOKEN_OR_COUNTER` | 안티리플레이 카운터가 이미 사용됨. 세션 갱신 후 재시도 | O | false | 자동 갱신. 여러 프로세스가 같은 키+캐시를 동시에 쓰면 반복됨 → 프로세스당 캐시 분리 또는 VIN 직렬화 |
| 7 | `INSUFFICIENT_PRIVILEGES` | 사용자가 명령 실행 권한 없음. 역할 또는 차량 상태 때문 | X | false | 키 역할 확인 (`list-keys`). Fleet Manager는 BLE 명령 불가(2023.38+). 차량 상태(주행 중 등) 확인 |
| 8 | `INVALID_DOMAINS` | 명령 형식 오류 또는 미인식 도메인. 클라이언트 오류 또는 구 펌웨어 | X | false | `to_destination.domain` 확인, 펌웨어 업데이트 |
| 9 | `INVALID_COMMAND` | 미인식 명령. 클라이언트 오류 또는 미지원 펌웨어 | X | false | 펌웨어가 해당 Action을 지원하는지 확인 |
| 10 | `DECODING` | 명령 파싱 실패. 클라이언트 오류 | X | false | 페이로드 protobuf 인코딩 확인 (`protoc --decode`) |
| 11 | `INTERNAL` | 차량 내부 오류. 다시 시도. 부팅 미완료 시 흔함 | O | false | 자동 재시도 |
| 12 | `WRONG_PERSONALIZATION` | 다른 VIN으로 서명된 명령 | X | false | `TAG_PERSONALIZATION`에 정확한 17자 VIN |
| 13 | `BAD_PARAMETER` | 명령 형식 오류 또는 deprecated 파라미터 | X | false | 인자 범위 확인 |
| 14 | `KEYCHAIN_IS_FULL` | 키체인이 가득 참. 키를 지워야 추가 가능 | X | false | 차량 UI 또는 `remove-key`로 정리 |
| 15 | `INCORRECT_EPOCH` | 세션 ID(epoch) 불일치. 세션 갱신 후 재시도 | O | false | 자동 갱신 (Infotainment 재부팅 후 흔함) |
| 16 | `IV_INCORRECT_LENGTH` | IV 길이 오류 (AES-GCM은 12바이트). 클라이언트 프로그래밍 오류 | X | false | nonce 12바이트 |
| 17 | `TIME_EXPIRED` | 명령 만료. 동봉 session info로 시계 동기화 확인 후 재시도 | O | false | 자동 갱신. 지속되면 `AllowedLatency`/시계 확인 |
| 18 | `NOT_PROVISIONED_WITH_IDENTITY` | 차량에 VIN이 프로비저닝되지 않음. 서비스 필요 | X | false | Tesla 서비스 |
| 19 | `COULD_NOT_HASH_METADATA` | 차량 내부 오류 | X | false | - |
| 20 | `TIME_TO_LIVE_TOO_LONG` | 만료 시각이 너무 먼 미래 (보안 조치) | O | false | ctx deadline이 곧 `expires_at` lifetime. 명령 ctx를 짧게 (기본 5s) |
| 21 | `REMOTE_ACCESS_DISABLED` | 차주가 Mobile access 비활성화 | X | false | 차량 UI: Controls > Safety > Allow Mobile Access |
| 22 | `REMOTE_SERVICE_ACCESS_DISABLED` | Service 키 인가이나 원격 서비스 명령 미허용 | X | false | - |
| 23 | `COMMAND_REQUIRES_ACCOUNT_CREDENTIALS` | Tesla 계정 증명이 필요한 명령을 그 증명이 없는 채널로 보냄. Fleet API로 재전송 | X | false | BLE가 아니라 인터넷 경로로 전송 |
| 24 | `REQUEST_MTU_EXCEEDED` | 요청 필드가 MTU 초과 | X | false | 페이로드 크기 축소 |
| 25 | `RESPONSE_MTU_EXCEEDED` | 요청은 수신됐으나 응답이 MTU 초과 | X | **true** | 명령은 실행됨. 상태 조회라면 더 작은 카테고리로 |
| 26 | `REPEATED_COUNTER` | (proto 주석 없음) 카운터 반복 | X | false | 세션 갱신 후 재전송 (수동) |
| 27 | `INVALID_KEY_HANDLE` | (proto 주석 없음) 키 핸들 무효 | X | false | `signer_identity.public_key` 사용 (SDK는 handle 미사용) |
| 28 | `REQUIRES_RESPONSE_ENCRYPTION` | (proto 주석 없음) 응답 암호화 필요 | X | false | `flags`에 `FLAG_ENCRYPT_RESPONSE`(bit 1 → 값 2) 설정. SDK 기본값 `vehicle.DefaultFlags` |

### 3.2 `UniversalMessage.OperationStatus_E` (RoutableMessage 계층)

| 값 | 이름 | `GetError` 결과 |
|---|---|---|
| 0 | `OPERATIONSTATUS_OK` | `nil` |
| 1 | `OPERATIONSTATUS_WAIT` | `ErrBusy` (재시도) |
| 2 | `OPERATIONSTATUS_ERROR` | fault가 NONE이면 `nil` (§1.4) |

### 3.3 `Signatures.Session_Info_Status`

| 값 | 이름 | 결과 |
|---|---|---|
| 0 | `SESSION_INFO_STATUS_OK` | 정상 |
| 1 | `SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST` | `ErrKeyNotPaired` |

### 3.4 `Errors.GenericError_E` (VCSEC `nominalError`)

| 값 | 이름 | 의미 |
|---|---|---|
| 0 | `GENERICERROR_NONE` | 없음 |
| 1 | `GENERICERROR_UNKNOWN` | 알 수 없음 |
| 2 | `GENERICERROR_CLOSURES_OPEN` | 도어/트렁크 열림 (잠금 등 불가) |
| 3 | `GENERICERROR_ALREADY_ON` | 이미 켜짐 |
| 4 | `GENERICERROR_DISABLED_FOR_USER_COMMAND` | 사용자 명령에 대해 비활성화 |
| 5 | `GENERICERROR_VEHICLE_NOT_IN_PARK` | 주차 상태 아님 |
| 6 | `GENERICERROR_UNAUTHORIZED` | 권한 없음 |
| 7 | `GENERICERROR_NOT_ALLOWED_OVER_TRANSPORT` | 이 전송 경로로는 불가 |

Go에서는 `*protocol.NominalError{Details: &protocol.NominalVCSECError{Details: <NominalError proto>}}` 로 반환된다. 검사:

```go
var vErr *protocol.NominalVCSECError
if errors.As(err, &vErr) {
	code := vErr.Details.GetGenericError() // verror.GenericError_E
}
```

### 3.5 VCSEC `OperationStatus_E` + `CommandStatus` 해석 규칙 (`vcsec.go unmarshalVCSECResponse`)

1. `protocol.GetError(message)` != nil → 그대로 반환.
2. 페이로드가 없으면(`Payload == nil`) → 빈 `FromVCSECMessage` (성공으로 간주).
3. `FromVCSECMessage` 파싱 실패 → `CommandError{ErrBadResponse 래핑, MHS=true}`.
4. `nominalError` 있음 → `NominalError{NominalVCSECError}`.
5. `commandStatus.operationStatus`:
   - `OPERATIONSTATUS_OK` → 통과
   - `OPERATIONSTATUS_WAIT` → `ErrBusy` (Temporary → 명령 전체 재전송)
   - `OPERATIONSTATUS_ERROR`:
     - `whitelistOperationStatus.whitelistOperationInformation != NONE` → `KeychainError{Code}`
     - `signedMessageStatus == nil` → `ErrUnknown`
     - 그 외 → 통과 (레거시 `signedMessageStatus`는 무시)
6. 종료 판정(`isTerminalTest`): RKE/Closure는 `commandStatus == nil`인 메시지가 종료; whitelist 작업은 `whitelistOperationStatus`가 있는 메시지가 종료 (`NONE`이면 성공, 아니면 `KeychainError`); 정보 요청은 첫 메시지가 종료.

### 3.6 Infotainment `CarServer.ActionStatus` 해석 (`infotainment.go getCarServerResponse`)

- `Response.actionStatus.result == OPERATIONSTATUS_ERROR` → `NominalError{Details: NewError("car could not execute command: " + result_reason.plain_text, false, false)}`. `plain_text` 비어 있으면 `unspecified error`.
- 주의: `CarServer.OperationStatus_E`는 `OPERATIONSTATUS_OK=0`, `OPERATIONSTATUS_ERROR=1` 로, `UniversalMessage.OperationStatus_E`(OK=0, WAIT=1, ERROR=2) 및 `VCSEC.OperationStatus_E`(OK=0, WAIT=1, ERROR=2)와 값이 다르다. Go 타입이 달라 혼용은 컴파일 오류지만, 다른 언어로 포팅할 때 숫자 값을 섞지 말 것.
- 그 외 → 성공. `Response.vehicleData` 등 반환.
- 특수 처리: `SetValetMode(off)`에서 메시지가 `already off`로 끝나면 `nil`로 취급.

### 3.7 `VCSEC.WhitelistOperation_information_E` (0~28) — `KeychainError.Code`

| 값 | 이름 (`WHITELISTOPERATION_INFORMATION_` 생략) | 원인 / 대처 |
|---|---|---|
| 0 | `NONE` | 성공 |
| 1 | `UNDOCUMENTED_ERROR` | 미문서화 오류 |
| 2 | `NO_PERMISSION_TO_REMOVE_ONESELF` | 자기 키 삭제 불가 |
| 3 | `KEYFOB_SLOTS_FULL` | 키포브 슬롯 가득 참 |
| 4 | `WHITELIST_FULL` | 화이트리스트 가득 참 → 키 삭제 후 재시도 |
| 5 | `NO_PERMISSION_TO_ADD` | 추가 권한 없음 (Driver 키로 `add-key` 등) → Owner 키 사용 |
| 6 | `INVALID_PUBLIC_KEY` | 공개 키 형식 오류 |
| 7 | `NO_PERMISSION_TO_REMOVE` | 삭제 권한 없음 |
| 8 | `NO_PERMISSION_TO_CHANGE_PERMISSIONS` | 권한 변경 불가 |
| 9 | `ATTEMPTING_TO_ELEVATE_OTHER_ABOVE_ONESELF` | 자신보다 높은 역할 부여 시도 |
| 10 | `ATTEMPTING_TO_DEMOTE_SUPERIOR_TO_ONESELF` | 상위 키 강등 시도 |
| 11 | `ATTEMPTING_TO_REMOVE_OWN_PERMISSIONS` | 자기 권한 제거 시도 |
| 12 | `PUBLIC_KEY_NOT_ON_WHITELIST` | 삭제 대상 키가 없음 |
| 13 | `ATTEMPTING_TO_ADD_KEY_THAT_IS_ALREADY_ON_THE_WHITELIST` | 이미 등록된 키 (무해; 갱신하려면 삭제 후 추가) |
| 14 | `NOT_ALLOWED_TO_ADD_UNLESS_ON_READER` | NFC 리더에 카드 없이 추가 시도 |
| 15 | `FM_MODIFYING_OUTSIDE_OF_F_MODE` | Fleet Manager가 F 모드 밖에서 수정 |
| 16 | `FM_ATTEMPTING_TO_ADD_PERMANENT_KEY` | FM이 영구 키 추가 시도 |
| 17 | `FM_ATTEMPTING_TO_REMOVE_PERMANENT_KEY` | FM이 영구 키 삭제 시도 |
| 18 | `KEYCHAIN_WHILE_FS_FULL` | 차량 파일시스템 가득 참 |
| 19 | `ATTEMPTING_TO_ADD_KEY_WITHOUT_ROLE` | 역할 없이 추가 (`ROLE_NONE`) |
| 20 | `ATTEMPTING_TO_ADD_KEY_WITH_SERVICE_ROLE` | Service 역할 부여 시도 |
| 21 | `NON_SERVICE_KEY_ATTEMPTING_TO_ADD_SERVICE_TECH` | 비서비스 키가 서비스 기술자 추가 |
| 22 | `SERVICE_KEY_ATTEMPTING_TO_ADD_SERVICE_TECH_OUTSIDE_SERVICE_MODE` | 서비스 모드 밖 |
| 23 | `COULD_NOT_START_LOCAL_ENTITY_AUTH` | NFC 승인 절차 시작 실패 |
| 24 | `LOCAL_ENTITY_AUTH_FAILED_UI_DENIED` | 사용자가 화면에서 거부 |
| 25 | `LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_TAP` | NFC 탭 대기 타임아웃 → 다시 요청 후 즉시 탭 |
| 26 | `LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_UI_ACK` | 화면 확인 대기 타임아웃 |
| 27 | `LOCAL_ENTITY_AUTH_FAILED_VALET_MODE` | 발렛 모드에서는 불가 |
| 28 | `LOCAL_ENTITY_AUTH_FAILED_CANCELLED` | 취소됨 |

### 3.8 `VCSEC.SignedMessage_information_E` (0~19, 레거시)

`CommandStatus.signedMessageStatus.signedMessageInformation`. SDK는 이 값을 해석하지 않는다(프로토콜 계층 fault로 대체됨). 값: 0 `NONE`, 1 `FAULT_UNKNOWN`, 2 `FAULT_NOT_ON_WHITELIST`, 3 `FAULT_IV_SMALLER_THAN_EXPECTED`, 4 `FAULT_INVALID_TOKEN`, 5 `FAULT_TOKEN_AND_COUNTER_INVALID`, 6 `FAULT_AES_DECRYPT_AUTH`, 7 `FAULT_ECDSA_INPUT`, 8 `FAULT_ECDSA_SIGNATURE`, 9 `FAULT_LOCAL_ENTITY_START`, 10 `FAULT_LOCAL_ENTITY_RESULT`, 11 `FAULT_COULD_NOT_RETRIEVE_KEY`, 12 `FAULT_COULD_NOT_RETRIEVE_TOKEN`, 13 `FAULT_SIGNATURE_TOO_SHORT`, 14 `FAULT_TOKEN_IS_INCORRECT_LENGTH`, 15 `FAULT_INCORRECT_EPOCH`, 16 `FAULT_IV_INCORRECT_LENGTH`, 17 `FAULT_TIME_EXPIRED`, 18 `FAULT_NOT_PROVISIONED_WITH_IDENTITY`, 19 `FAULT_COULD_NOT_HASH_METADATA`. 접두사 `SIGNEDMESSAGE_INFORMATION_`.

### 3.9 프록시 HTTP 상태 코드 (`pkg/proxy/proxy.go httpStatusCode`, `writeJSONError`)

| 조건 | HTTP | 본문 |
|---|---|---|
| `inet.ErrVehicleNotAwake` | 408 | `{"response":null,"error":"vehicle unavailable: ...","error_description":""}` |
| `protocol.ErrKeyNotPaired` | 412 | 위 형식 |
| `*inet.HTTPError` | `httpErr.Code` | `err.Error()` 원문 |
| `protocol.IsNominalError(err)` | 200 | `{"response":{"result":false,"reason":"<err>"},"error":"","error_description":""}` |
| 성공 | 200 | `{"response":{"result":true,"reason":""}}` |
| 그 외 | 500 | `{"response":null,"error":"<err>","error_description":""}` |
| OAuth 헤더 없음 | 403 | |
| VIN 17자 아님 | 404 | |
| VIN 락 대기 중 ctx 만료 | 503 | |
| POST 아님 | 405 | |
| 본문 > 1 MiB | 413 | |

---

## 4. 재시도 루프 위치와 정책

`RetryInterval()`: `ble.Connection` 1s, `inet.Connection` 1s. `AllowedLatency()`: BLE 4s, inet 10s (`Vehicle.SetMaxLatency`로 변경).

| 루프 | 파일:함수 | 재시도 조건 | 대기 | 종료 |
|---|---|---|---|---|
| 명령 전송+응답 | `pkg/vehicle/vehicle.go` `Vehicle.Send` → `trySend` | `protocol.ShouldRetry(err)` | `RetryInterval()` | 성공 / 비재시도 오류 / `ctx.Done()` → `ctx.Err()` (래핑 없음). 응답 대기 중 ctx 만료는 `trySend`가 `CommandError{MHS=true}`로 만들어 재시도 안 함 |
| VCSEC 명령 | `pkg/vehicle/vcsec.go` `getVCSECResult` → `getReceiver` + `readUntil` | `ShouldRetry(err)` (`ErrBusy` 포함) | `RetryInterval()` | 위와 동일. `readUntil`은 `done()` 이 true인 첫 메시지에서 반환 |
| 핸드셰이크 (Vehicle) | `Vehicle.StartSession` → `dispatcher.StartSessions` | `ShouldRetry(err)` | `RetryInterval()` | - |
| 핸드셰이크 (도메인) | `internal/dispatcher/dispatcher.go` `StartSession` → `tryStartSession` | 응답 없이 `RetryInterval()` 경과 → 요청 재전송 | `RetryInterval()` | `readySignal` 수신 / 오류 응답 (`GetError`) / `ctx.Done()` |
| 병렬 핸드셰이크 | `dispatcher.StartSessions` | 도메인마다 goroutine (nil이면 VCSEC+INFOTAINMENT) | - | 첫 non-`Canceled` 오류 반환, aggregate ctx 취소 |
| 전송 계층 | `dispatcher.Send` | `conn.Send` 오류가 `ShouldRetry` | `conn.RetryInterval()` | `ctx.Done()` → `CommandError{ctx.Err(), MHS=false, T=true}` |
| 인가 대기 | `internal/dispatcher/session.go` `session.authorize` | `Encrypt`/`AuthorizeHMAC` 실패 시 err를 nil로 지우고 루프 (readySignal이 이미 닫혀 있으므로 즉시 재시도) | 없음 | `ctx.Done()` |
| 웨이크업 | `pkg/connector/inet/inet.go` `Connection.Wakeup` | `protocol.Temporary(err)` 또는 `state != "online"` | **10s** | `state == "online"` / 비일시 오류 / ctx |
| BLE 연결 | `pkg/connector/ble/ble.go` `NewConnectionFromScanResult` → `tryToConnect` | `retry == true` && `!IsAdapterError(err)`: 스캔 실패, `Dial` 실패, 서비스/특성/디스크립터 탐색 실패, 구독 실패 | 없음 (즉시) | `retry == false`: 어댑터 초기화 실패, 로컬 이름 불일치, `Connectable == false`; `ctx.Err() != nil` → 마지막 오류 반환 |
| 프록시 포워딩 | `pkg/proxy/proxy.go` `forwardRequest` | HTTP 421 + `Alt-Svc: h2=https://<host>` 만 | 1s | `MaxAttempts` = 2 → `max retry exhausted` (502) |
| 프록시 명령 | `proxy.handleVehicleCommand` | 재시도 없음. `ErrProtocolNotSupported` → VIN을 unsupported 표시 후 REST 포워딩; `ErrCommandUseRESTAPI` → REST 포워딩 | - | `p.Timeout` (기본 10s) |
| CLI | `cmd/tesla-control/main.go` | 재시도 없음 | - | `-connect-timeout` 20s, `-command-timeout` 5s |

**주의**: 재시도 루프는 원본 페이로드를 다시 인가한다(카운터 증가, 새 nonce). `SendMessage`는 같은 메시지를 카운터 변경 없이 재전송해도 안전한 전송 오류만 재시도한다.

---

## 5. 세션 desync 자동 복구

차량은 인증 오류(`INVALID_SIGNATURE`, `INVALID_TOKEN_OR_COUNTER`, `INCORRECT_EPOCH`, `TIME_EXPIRED` 등) 응답에 최신 `session_info` + `signature_data.session_info_tag`를 동봉할 수 있다. `dispatcher.process`가 모든 수신 메시지에 대해 아래를 수행한 뒤 핸들러로 전달하므로, `Vehicle.Send`의 다음 재시도는 갱신된 세션으로 인가된다.

흐름 (`internal/dispatcher/dispatcher.go checkForSessionUpdate` → `session.processHello` → `authentication.Signer`):

1. `message.session_info == nil` → 아무것도 안 함.
2. 폐기 조건 (로그만 남기고 세션 갱신 안 함; 메시지 자체는 계속 전달):
   - `d.privateKey == nil` → `Discarding session info because client does not have a private key`
   - `handler.expired(maxLatency)` (요청 전송 후 `AllowedLatency` 초과) → `Discarding session info because it was received more than %s after request`
   - `signature_data.session_info_tag.tag == nil` → `Discarding unauthenticated session info`
   - 도메인 세션 미등록 → `Dropping session from unregistered domain`
   - HMAC 불일치 / 파싱 실패 / 공개 키 불일치 → `Session info error: ...`
3. `processHello(challenge=request_uuid, info, tag)`:
   - 세션 최초 → `NewAuthenticatedSigner` (K 유도, `SESSION_INFO_KEY` HMAC 검증)
   - 기존 세션 → `UpdateSignedSessionInfo` → HMAC 검증 → `UpdateSessionInfo`:
     - `info.publicKey != 기존 vehicle 공개 키` → 오류 (`UnknownKeyId: public key in SessionInfo doesn't match ...`)
     - `epoch 변경` **또는** `setTime <= info.clock_time` 일 때만 갱신: `counter = max(counter, info.counter)` (Go 구현은 epoch가 바뀌어도 카운터를 내리지 않음), `epoch`, `setTime`, `timeZero = now - clock_time`
     - 그 외(과거 시각의 session info) → 무시
4. 성공 시 `ready = true`, `readySignal` close → 대기 중인 `authorize` 해제.

세션 캐시(`pkg/cache`)에서 로드한 세션은 `LoadCache`에서 즉시 `ready`로 표시되므로 핸드셰이크 없이 첫 명령을 보내고, 실패하면 위 흐름으로 복구된다. 캐시가 다른 개인 키로 만들어졌으면 첫 명령이 `INVALID_SIGNATURE`로 실패하고 정상 복구된다.

## 6. 응답 리플레이와 드롭

- `AES_GCM_Response_data`가 있는 응답은 `session.decrypt` → `handler.antireplay.Update(counter)` (`authentication.SlidingWindow`, 윈도우 32). 실패 → `ErrReplayedResponse` → `Dropping duplicate vehicle response` 로그, 핸들러에 전달 안 됨.
- 핸들러 채널(`receiverBufferSize` = 10) 가득 참 → `Dropping response to command because response handler queue is full`.
- 등록되지 않은 `(routing_address, request_uuid, domain)` → `Dropping message without registered handler`. VCSEC는 `request_uuid`를 채우지 않으므로 키에서 uuid를 제외하고 요청마다 랜덤 `routing_address`로 구분한다.
- `to_destination`이 도메인이거나 주소 길이 ≠ 16 → 드롭.

## 7. "Couldn't verify success" 의 의미

`tesla-control`은 `protocol.MayHaveSucceeded(err)` 이면 `Couldn't verify success: <err>` 를 출력한다. 명령이 전송됐고 차량이 실행했을 수 있으나 확인 응답을 받지 못했다는 뜻이다. 사용자에게는 "명령이 적용됐는지 차량 상태를 확인하라"고 안내하고, **자동 재전송하지 않는다** (잠금/트렁크처럼 토글성 명령이 두 번 실행될 수 있음). 상태 확인은 `body-controller-state`(VCSEC) 또는 `state <category>`(Infotainment).

---

## 8. 트러블슈팅 매트릭스

| 증상 | 가능한 원인 | 확인 | 조치 |
|---|---|---|---|
| `ble: failed to scan for <VIN>: context deadline exceeded` | 차량 근처 아님, 블루투스 꺼짐, VIN 오타(로컬 이름 불일치), 차량이 깊은 수면(광고 간격 ↑) | `examples/ble -scan-only -vin <VIN>`; 다른 스캐너로 `S<16hex>C` 이름 확인 (`ble.VehicleLocalName`) | 20~30s 스캔 허용, 차량 접근, VIN 재확인 |
| `the vehicle is already connected to the maximum number of BLE devices` | 광고 `Connectable=false`: 키포브/폰키 포함 3개 연결 초과 | `ScanVehicleBeacon` 결과 `Connectable` | 다른 폰/앱 BLE 끊기, 잠시 후 재시도 |
| Linux: `Failed to initialize BLE adapter: ... operation not permitted` | `CAP_NET_ADMIN` 없음 (go-ble가 HCIDEVDOWN 호출) | `ble.IsAdapterError` | `sudo setcap 'cap_net_admin=eip' "$(which tesla-control)"` 또는 root |
| Linux: `the bluetooth adapter ID is invalid` | `-bt-adapter` 값이 `hci0`~`hci15` 형식 아님 | `hciconfig` | `-bt-adapter hci0` |
| macOS: 스캔이 아무것도 못 찾음 | 터미널 앱에 Bluetooth 권한 없음 | 시스템 설정 > 개인정보 보호 > Bluetooth | 터미널/앱 권한 허용 |
| `vehicle rejected request: your public key has not been paired with the vehicle` | 키 미등록, 다른 키 사용, Infotainment에 아직 동기화 안 됨(페어링 직후) | `tesla-control -ble list-keys`; `session-info public_key.pem vcsec` / `infotainment` | `add-key-request` + NFC 탭; 페어링 직후면 수십 초 대기 후 `session-info ... infotainment` 재확인 |
| `MESSAGEFAULT_ERROR_INVALID_SIGNATURE` / `INCORRECT_EPOCH` / `INVALID_TOKEN_OR_COUNTER` 가 반복 | 세션 캐시 손상, 같은 키+캐시를 여러 프로세스가 동시 사용, 시계 오차 | `-debug`로 `Updated session info for ...` 로그 확인 | 캐시 파일(`$TESLA_CACHE_FILE`, 기본 `~/.tesla-cache.json`) 삭제; 프로세스 간 VIN 직렬화; `-disable-session-cache`로 재현 |
| `MESSAGEFAULT_ERROR_TIME_EXPIRED` / `TIME_TO_LIVE_TOO_LONG` | ctx deadline이 곧 `expires_at`. 너무 길거나(TTL) 로컬 시계와 도메인 시계 오프셋 오류 | `-debug` 응답에 session_info 동봉 여부 | 명령 ctx 5~10s; 세션 갱신 후 자동 재시도됨. 지속 시 캐시 삭제 |
| `MESSAGEFAULT_ERROR_BUSY` / `INTERNAL` | 차량 부팅/웨이크업 중 | 잠시 후 재시도 | 자동 재시도됨. `-command-timeout` 늘리기 |
| `vehicle busy or finishing wake-up` 로 결국 타임아웃 | Infotainment 잠듦, BLE로 `wake` 안 함 | `body-controller-state`의 `vehicleSleepStatus` | `tesla-control -ble wake` 후 재시도 (VCSEC 세션만 열림) |
| `MESSAGEFAULT_ERROR_REMOTE_ACCESS_DISABLED` | 차량에서 Mobile access 꺼짐 | 차량 화면 Controls > Safety | Allow Mobile Access 켜기 |
| `MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES` | 키 역할 부족 (Driver가 키 관리, Vehicle Monitor가 제어 명령), Fleet Manager가 BLE 사용, 차량 상태(주행 중, 발렛) | `list-keys`로 역할 확인 | 적절한 역할 키로 재페어링 |
| `MESSAGEFAULT_ERROR_COMMAND_REQUIRES_ACCOUNT_CREDENTIALS` | 계정 증명이 필요한 명령을 BLE로 전송 | - | 인터넷 경로(OAuth 토큰)로 전송 |
| `MESSAGEFAULT_ERROR_KEYCHAIN_IS_FULL` / `keychain operation failed: ..._WHITELIST_FULL` | 키체인 가득 참 | `list-keys` | 차량 UI 또는 `remove-key`로 삭제 |
| `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED` | 응답 크기 초과 (주로 상태 조회) | - | 명령은 실행됨(MHS=true). 더 작은 상태 카테고리 사용 |
| `vehicle does not support protocol -- use REST API` (HTTP 422) | 2021년 이전 Model S/X | - | Fleet API REST 명령; 프록시는 자동 포워딩 |
| `vehicle unavailable: vehicle is offline or asleep` (503/408) | 인터넷 경로, 차량 수면 | - | `car.Wakeup(ctx)` (10s 간격 폴링) 후 재시도 |
| `could not load key: ...` / `could not load token: ...` | 키링 항목 이름 불일치, 키링 타입 불일치 | `-keyring-debug` 또는 `TESLA_KEYRING_DEBUG=1`; `tesla-keygen -h`의 `-keyring-type` 목록 | `TESLA_KEY_NAME`/`TESLA_TOKEN_NAME` 일치시키기; `TESLA_KEYRING_TYPE` 지정; 파일 키링이면 `TESLA_KEYRING_PASSWORD` |
| `failed to load session cache: ...` | 캐시 JSON 손상 | `cat ~/.tesla-cache.json` | 파일 삭제 (재생성됨) |
| `keychain operation failed: ..._TIMED_OUT_WAITING_FOR_TAP` | NFC 탭 지연 | - | `add-key-request` 직후 즉시 카드 탭, 화면 Confirm |
| `command can only be sent over BLE` | `add-key-request`를 OAuth 설정된 환경에서 `-ble` 없이 실행 | - | `-ble` 추가 |
| `command should not be sent in plaintext or encrypted with an unauthenticated public key` | `SetPINToDrive`를 BLE로 | - | Fleet API 경로 사용 |
| Infotainment 명령만 실패, `lock`/`unlock`은 성공 | Infotainment 잠듦 또는 부팅 중 | `body-controller-state` → `vehicleSleepStatus` | `wake` 후 재시도; VCSEC 전용 명령이면 `-domain vcsec`로 핸드셰이크 범위 제한 |
| `dropped response because inbox is full` | 응답을 읽지 않고 연속 전송 (커스텀 코드) | - | `Receiver`를 반드시 소비/`Close` |

---

## 9. 디버깅 절차

### 9.1 로그

- `internal/log` 레벨: `LevelNone`=0 (기본값, **아무것도 출력 안 함**), `LevelError`=1, `LevelWarning`=2, `LevelInfo`=3, `LevelDebug`=4. 출력은 stderr, 형식 `2023-12-13T14:41:13-08:00 [debug] TX: 3202...`. 레이블: `[debug]`, `[info ]`, `[warn ]`, `[error]`.
- 켜는 방법: `tesla-control -debug` 또는 `TESLA_VERBOSE=1` (`false`/`0` 이외 값), `tesla-http-proxy -verbose` 또는 `TESLA_VERBOSE`, `examples/ble -debug`. 모두 `LevelDebug`로 설정한다 (중간 레벨 선택 불가).
- `internal/log`는 모듈 외부에서 import할 수 없다. 외부 모듈에서 SDK 로그를 켜려면 저장소를 vendoring/replace 하거나 `tesla-control -debug`로 재현한다.
- 키링 디버그: `-keyring-debug` 또는 `TESLA_KEYRING_DEBUG` (존재 여부만 검사).

### 9.2 TX/RX hex 디코딩 (`pkg/protocol/protocol.md` "Decoding messages")

`pkg/protocol/` 디렉터리에서 실행 (`protoc` 필요):

```bash
# RoutableMessage
echo 320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805 \
    | xxd -r -p \
    | protoc --decode=UniversalMessage.RoutableMessage -I protobuf protobuf/*.proto

# VCSEC 페이로드 (protobuf_message_as_bytes 가 평문일 때)
printf "\n\002\010\005" | protoc --decode=VCSEC.UnsignedMessage -I protobuf protobuf/*.proto
# 응답: --decode=VCSEC.FromVCSECMessage
# Infotainment: --decode=CarServer.Action / --decode=CarServer.Response
# session_info 필드: --decode=Signatures.SessionInfo
```

BLE `-debug`의 `TX:`/`RX:` hex는 2바이트 길이 프리픽스가 제거된 RoutableMessage 본문이다 (`ble.Connection.Send`는 프리픽스 붙이기 전에, `flush`는 떼어낸 뒤 로그). AES-GCM으로 암호화된 페이로드/응답은 protoc로 내부를 볼 수 없다. 평문으로 보려면 `PreferredAuthMethod`를 HMAC으로 바꾼 커스텀 Connector를 쓰거나 인터넷 경로(HMAC)를 사용한다.

### 9.3 비인증 진단 명령 (개인 키 없이 동작)

| 명령 | 용도 |
|---|---|
| `tesla-control -ble -vin $VIN list-keys` | 등록된 공개 키/역할/폼팩터 |
| `tesla-control -ble -vin $VIN session-info public_key.pem vcsec` | VCSEC가 이 키를 아는지, epoch/counter/clock_time |
| `tesla-control -ble -vin $VIN session-info public_key.pem infotainment` | Infotainment 동기화 여부 (페어링 직후 확인용) |
| `tesla-control -ble -vin $VIN body-controller-state` | 잠금/도어/트렁크/수면/탑승 상태 (Infotainment 수면 중에도) |
| `tesla-control -ble -vin $VIN wake` | (BLE에서는 인증 필요) VCSEC를 통해 Infotainment 깨우기 |

### 9.4 재현 순서

1. `-disable-session-cache -debug` 로 실행해 캐시 영향 배제.
2. `session-info` 로 핸드셰이크 단계 분리.
3. `body-controller-state` 로 VCSEC 경로 확인 → `ping` 으로 Infotainment 경로 확인.
4. 실패 응답의 `signedMessageStatus`를 §3.1 표와 대조.

---

## 검증 체크리스트

- [ ] `retriableErrors` 8개가 `pkg/protocol/error.go`와 일치하는가 (`pkg/vehicle/vehicle_test.go TestVehicleRetryFail`도 같은 목록).
- [ ] `RoutableMessageError.MayHaveSucceeded`가 `NONE`, `RESPONSE_MTU_EXCEEDED`에서만 true인가.
- [ ] `GetError`의 `OPERATIONSTATUS_ERROR` 분기가 `nil`을 반환하는 현재 동작을 바꾸지 않았는가 (바꾸면 VCSEC 응답 해석이 달라짐).
- [ ] 새 sentinel을 추가했다면 `NewError(msg, mayHaveSucceeded, temporary)`의 두 불리언을 §1.1 기준으로 정했는가. 응답 미수신 계열은 반드시 `mayHaveSucceeded=true`.
- [ ] `MessageFault_E`에 새 값이 추가되면 §3.1 표와 `retriableErrors` 포함 여부를 검토했는가.
- [ ] `WhitelistOperation_information_E` 값이 `vcsec.proto`와 일치하는가 (0~28).
- [ ] 프록시 상태 코드 변경 시 `pkg/proxy/status_test.go`, `proxy_test.go TestWriteJSONError` 갱신.
- [ ] `go test ./pkg/protocol/ ./pkg/vehicle/ ./internal/dispatcher/ ./pkg/proxy/` 통과.
