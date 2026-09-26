# 04. Go 공개 API 레퍼런스

`github.com/teslamotors/vehicle-command/pkg/...` 아래 임포트 가능한 패키지의 공개 API를 실제 시그니처 그대로 정리한다. `internal/` 패키지는 임포트할 수 없으므로 맨 끝에 개요만 둔다.

관련 파일 (저장소 루트 기준):
- `pkg/protocol/{error.go,key.go,domains.go,receiver.go}`
- `pkg/connector/connector.go`, `pkg/connector/ble/{ble.go,device_*.go}`, `pkg/connector/inet/inet.go`
- `pkg/vehicle/{vehicle.go,vcsec.go,actions.go,charge.go,climate.go,infotainment.go,security.go,state.go}`
- `pkg/cache/cache.go`, `pkg/cli/{config.go,keyring.go,config_linux.go}`, `pkg/account/account.go`
- `pkg/proxy/{proxy.go,command.go}`, `pkg/sign/sign.go`

관련 문서: [01-architecture.md](01-architecture.md), [05-command-catalog.md](05-command-catalog.md), [08-errors.md](08-errors.md), [09-recipes.md](09-recipes.md)

---

## 계층 요약

```
pkg/account (OAuth, Fleet API)  ──┐
pkg/cli (플래그/환경/키링)        ──┤
                                  ▼
pkg/vehicle.Vehicle  ← 명령 메서드 (Lock, ClimateOn, GetState ...)
        │  uses internal/dispatcher (세션, 서명, 라우팅)
        ▼
pkg/connector.Connector  ← pkg/connector/ble.Connection | pkg/connector/inet.Connection | 사용자 정의
```

`pkg/cache` 는 `Vehicle` 과 `internal/dispatcher` 사이의 세션 캐시, `pkg/protocol` 은 오류/키/도메인 공용 타입, `pkg/proxy` 는 `Vehicle` 위에 얹은 REST 서버, `pkg/sign` 은 JWS 서명이다.

---

## pkg/protocol

### 오류 인터페이스와 헬퍼 (`pkg/protocol/error.go`)

```go
type Error interface {
    error
    MayHaveSucceeded() bool // 차량이 명령을 받았을 수도 있음 (예: 응답 대기 중 타임아웃)
    Temporary() bool        // 일시적 조건 (예: 웨이크업 중 Busy)
}

type CommandError struct {
    Err               error
    PossibleSuccess   bool
    PossibleTemporary bool
}
func NewError(message string, mayHaveSucceeded bool, temporary bool) error // *CommandError 반환
func (e *CommandError) Error() string
func (e *CommandError) Unwrap() error
func (e *CommandError) MayHaveSucceeded() bool
func (e *CommandError) Temporary() bool

func MayHaveSucceeded(err error) bool // errors.As(err, *Error) && MayHaveSucceeded()
func Temporary(err error) bool        // errors.As(err, *Error) && Temporary()
func ShouldRetry(err error) bool      // err != nil && Error 구현 && !MayHaveSucceeded() && Temporary()
func IsNominalError(err error) bool   // errors.As(err, **NominalError)
```

`ShouldRetry` 가 `Vehicle.Send`, `Vehicle.StartSession`, `Dispatcher.Send` 의 재시도 루프 기준이다. `MayHaveSucceeded()==true` 인 오류는 절대 재시도하지 않는다 (중복 실행 방지).

```go
// 키체인(화이트리스트) 조작 실패. 코드는 vcsec.WhitelistOperationInformation_E.
type KeychainError struct{ Code vcsec.WhitelistOperationInformation_E }
// Error(): "keychain operation failed: <Code>", MayHaveSucceeded()=false, Temporary()=false

// 차량이 인증까지 마쳤지만 실행하지 못한 애플리케이션 계층 오류. Details 를 Unwrap 한다.
type NominalError struct{ Details error }
// MayHaveSucceeded()/Temporary() 는 Details 에 위임

// VCSEC 애플리케이션 오류 (errors.proto NominalError). 항상 non-temporary, non-success.
type NominalVCSECError struct{ Details *verror.NominalError }
// Error(): "vcsec could not execute command: <GenericError_E 이름>"

// 프로토콜 계층 오류 (RoutableMessage.signedMessageStatus.signed_message_fault).
type RoutableMessageError struct{ Code universal.MessageFault_E }
// Error(): MessageFault_E 이름 (예: "MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES")
// MayHaveSucceeded(): Code == ERROR_NONE || Code == ERROR_RESPONSE_MTU_EXCEEDED
// Temporary(): Code ∈ {BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL,
//                      INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG}

func GetError(u *universal.RoutableMessage) error
```

`GetError` 규칙 (순서대로):
1. `signedMessageStatus.signed_message_fault != NONE` → `UNKNOWN_KEY_ID` 면 `ErrKeyNotPaired`, 그 외 `&RoutableMessageError{Code}`.
2. `session_info` 페이로드가 있으면 파싱 → 파싱 실패 `ErrBadResponse`; `status == KEY_NOT_ON_WHITELIST` → `ErrKeyNotPaired`; 그 외 비-OK → `ErrUnknown`.
3. `signedMessageStatus.operation_status`: `OK` → nil, `WAIT` → `ErrBusy`, `ERROR` → nil (fault 없이 ERROR 만 오면 nil), 그 외 값 → `ErrUnknown`.

### 센티널 오류

| 이름 | 타입 | 메시지 / 의미 | MayHaveSucceeded / Temporary |
|---|---|---|---|
| `ErrBusy` | `*CommandError` | "vehicle busy or finishing wake-up" | false / **true** |
| `ErrUnknown` | `*CommandError` | "vehicle responded with an unrecognized status code" | false / false |
| `ErrNotConnected` | `*CommandError` | "vehicle not connected" (dispatcher 미시작, inet inbox 닫힘) | false / false |
| `ErrNoSession` | `*CommandError` | "cannot send authenticated command before establishing a vehicle session" | false / false |
| `ErrRequiresKey` | `*CommandError` | "no private key available" (개인 키 없이 핸드셰이크 시도) | false / false |
| `ErrInvalidPublicKey` | `authentication.ErrInvalidPublicKey` (재수출) | P-256 비압축 공개 키가 아님 | 해당 없음 |
| `ErrKeyNotPaired` | `*CommandError` | "vehicle rejected request: your public key has not been paired with the vehicle" | false / false |
| `ErrUnpexpectedPublicKey` | `errors.New` | "remote public key changed unexpectedly" (철자 오류 그대로 공개 API) | 해당 없음 |
| `ErrBadResponse` | `errors.New` | "invalid response" | 해당 없음 |
| `ErrProtocolNotSupported` | `errors.New` | "vehicle does not support protocol -- use REST API" (Fleet API 422) | 해당 없음 |
| `ErrRequiresBLE` | `errors.New` | "command can only be sent over BLE" (`SendAddKeyRequest*` 를 inet 로) | 해당 없음 |
| `ErrRequiresEncryption` | `errors.New` | "command should not be sent in plaintext or encrypted with an unauthenticated public key" (`SetPINToDrive` 를 BLE 로) | 해당 없음 |
| `ErrNoDecryptionContext` | `errors.New` | "could not decrypt vehicle response without a session" | 해당 없음 |
| `ErrReplayedResponse` | `errors.New` | "received vehicle response with duplicate counter" | 해당 없음 |

`errors.New` 계열은 `protocol.Error` 를 구현하지 않으므로 `ShouldRetry` 는 false.

### 도메인 (`pkg/protocol/domains.go`)

```go
type Domain = universal.Domain
const (
    DomainNone         = universal.Domain_DOMAIN_BROADCAST        // 0
    DomainVCSEC        = universal.Domain_DOMAIN_VEHICLE_SECURITY // 2
    DomainInfotainment = universal.Domain_DOMAIN_INFOTAINMENT     // 3
)
```

### 키 (`pkg/protocol/key.go`)

```go
type ECDHPrivateKey authentication.ECDHPrivateKey // 인터페이스: Exchange(remotePublicBytes []byte) (Session, error); PublicBytes() []byte; SchnorrSignature(message []byte) ([]byte, error)
type Session = authentication.Session             // HSM/TEE 구현용 별칭. 주석의 TEE 요구사항 참조.

func LoadPrivateKey(filename string) (ECDHPrivateKey, error)
func SavePrivateKey(skey ECDHPrivateKey, filename string) error
func LoadPublicKey(filename string) (*ecdh.PublicKey, error)
func PublicKeyBytesFromHex(h string) (*ecdh.PublicKey, error)
func UnmarshalECDHPrivateKey(keyBytes []byte) ECDHPrivateKey
```

- `LoadPrivateKey`: PEM 만. `EC PRIVATE KEY`(SEC1) 또는 PKCS8(`PRIVATE KEY`, 비암호화). 곡선이 P-256 이 아니면 `ErrInvalidPrivateKey` 래핑 오류. 반환 구현체는 `*authentication.NativeECDHKey`.
- `SavePrivateKey`: `*authentication.NativeECDHKey` 만 가능("key is not exportable"). `EC PRIVATE KEY` PEM, 파일 모드 `0600`.
- `LoadPublicKey` 지원 형식: PKIX PEM(`PUBLIC KEY`), PKCS8 PEM(`PRIVATE KEY`, 개인 키에서 공개 키 추출), SEC1 PEM(`EC PRIVATE KEY`), 65바이트 바이너리 비압축 점(`0x04||X||Y`), 130(+개행 131)바이트 hex 문자열. P-256 이 아니면 `ErrInvalidPublicKey`.
- `PublicKeyBytesFromHex`: hex → `ecdh.P256().NewPublicKey`.
- `UnmarshalECDHPrivateKey`: 32바이트 스칼라 → 키. 길이가 32가 아니거나 `D >= N` 이면 **nil 반환(오류 아님)**. 키링에 저장된 형식이 이것이다.

### Receiver (`pkg/protocol/receiver.go`)

```go
type Receiver interface {
    Recv() <-chan *universal.RoutableMessage // 보통 응답 1개, VCSEC 는 여러 개 가능
    Close()                                  // 반드시 호출해 dispatcher 핸들러 해제
}
```

---

## pkg/connector

```go
type AuthMethod int32
const (
    AuthMethodNone AuthMethod = iota // 핸드셰이크, 비인증 정보 요청
    AuthMethodGCM                    // AES-GCM 암호화+인증 (BLE 기본)
    AuthMethodHMAC                   // HMAC-SHA256 평문 인증 (Fleet API 기본)
)
const BufferSize = 5              // 수신 채널 버퍼 (inet, ble 모두 5)
const MaxResponseLength = 100000  // Connector 가 지원해야 하는 최대 응답 바이트

type Connector interface {
    Receive() <-chan []byte                         // 차량→클라 데이터그램. 스레드 안전.
    Send(ctx context.Context, buffer []byte) error  // 클라→차량. 스레드 안전. 오류가 protocol.Error 면 MayHaveSucceeded 로 판단 가능.
    VIN() string
    Close()                                         // 멱등. 이후 동작 미정의.
    PreferredAuthMethod() AuthMethod                // HTTP 기반은 반드시 AuthMethodHMAC
    RetryInterval() time.Duration                   // 재전송 간격 권장값
    AllowedLatency() time.Duration                  // 세션 정보 수신 허용 지연 (초과 시 세션 정보 폐기)
}

type FleetAPIConnector interface {
    Connector
    SendFleetAPICommand(ctx context.Context, endpoint string, command interface{}) ([]byte, error)
    Wakeup(ctx context.Context) error
}
```

`Vehicle.Wakeup`, `Vehicle.SetPINToDrive`, `Vehicle.SendAddKeyRequestWithRole` 는 `conn.(connector.FleetAPIConnector)` 타입 단언으로 전송 종류를 판별한다. 사용자 정의 Connector 가 HTTP 기반이면 이 인터페이스도 구현해야 동작이 맞다.

---

## pkg/connector/ble

```go
var ErrAdapterInvalidID       = protocol.NewError("the bluetooth adapter ID is invalid", false, false)
var ErrMaxConnectionsExceeded = protocol.NewError("the vehicle is already connected to the maximum number of BLE devices", false, false)

type Connection struct { /* 비공개 */ }
func (c *Connection) PreferredAuthMethod() connector.AuthMethod // AuthMethodGCM
func (c *Connection) RetryInterval() time.Duration               // 1s
func (c *Connection) AllowedLatency() time.Duration              // 4s (maxLatency)
func (c *Connection) Receive() <-chan []byte                     // 버퍼 5
func (c *Connection) Send(_ context.Context, buffer []byte) error // 2바이트 BE 길이 + 페이로드, blockLength 단위 write-with-response. ctx 무시.
func (c *Connection) VIN() string
func (c *Connection) Close()                                     // ClearSubscriptions + CancelConnection

func VehicleLocalName(vin string) string // "S" + hex(sha1(vin)[:8]) + "C"

func InitAdapterWithID(id string) error  // Linux: "hciN"(0..15). Darwin: 빈 문자열만 허용, 아니면 ErrAdapterInvalidID. Windows: 항상 오류.
func CloseAdapter() error                // 전역 device.Stop() 후 nil 로. 기존 연결/스캔은 끊지 않음.

type ScanResult struct {
    Address     string
    LocalName   string
    RSSI        int16
    Connectable bool
}
func ScanVehicleBeacon(ctx context.Context, vin string) (*ScanResult, error)
func NewConnection(ctx context.Context, vin string) (*Connection, error)                                  // = NewConnectionFromScanResult(ctx, vin, nil)
func NewConnectionFromScanResult(ctx context.Context, vin string, target *ScanResult) (*Connection, error)

func IsAdapterError(err error) bool             // Linux: 메시지에 "operation not permitted" 포함. Darwin/Windows: 항상 false.
func AdapterErrorHelpMessage(err error) string  // Linux: setcap 안내문. 그 외: err.Error()
```

주의:
- 어댑터는 패키지 전역 싱글턴(`device`, `mu`). Linux 에서 `newDevice()` 를 여러 번 호출하면 실패하므로 재사용한다.
- `NewConnectionFromScanResult` 는 `ctx` 가 끝날 때까지 재시도(retry=true 인 오류). `IsAdapterError` 이거나 `retry=false`(LocalName 불일치, `!Connectable` → `ErrMaxConnectionsExceeded`) 면 즉시 반환.
- 스캔 매칭은 `LocalName == VehicleLocalName(vin)` 정확 일치.
- MTU: `ExchangeMTU(ble.MaxMTU)` 성공 시 `blockLength = min(txMtu, 1024) - 3`, 실패 시 `ble.DefaultMTU - 3`.
- 수신: 청크 간 1초(`rxTimeout`) 이상 비면 버퍼 리셋. 길이 > 1024 면 버퍼 폐기. inbox 가 가득 차면 메시지 드롭.

---

## pkg/connector/inet

```go
var MaxLatency = 10 * time.Second // AllowedLatency 기본값 (전역 변수, 변경 가능)
var ErrVehicleNotAwake = protocol.NewError("vehicle unavailable: vehicle is offline or asleep", false, false)

func ReadWithContext(ctx context.Context, r io.Reader, p []byte) ([]byte, error) // p 가 찰 때까지 또는 EOF 까지 읽음, ctx 취소 확인

type HTTPError struct {
    Code    int
    Message string
}
func (e *HTTPError) Error() string          // Message 가 비면 http.StatusText(Code)
func (e *HTTPError) MayHaveSucceeded() bool // 4xx → false; 503 → false; 그 외(5xx 등) → true
func (e *HTTPError) Temporary() bool        // 503, 504, 408, 421

func SendFleetAPICommand(ctx context.Context, client *http.Client, userAgent, authHeader string, url string, command interface{}) ([]byte, error)
func ValidTeslaDomainSuffix(domain string) bool // .tesla.com | .tesla.cn | .teslamotors.com

type Connection struct {
    UserAgent string // 공개 필드
    /* 비공개: vin, client, serverURL, inbox, authHeader, lock, lastPoke */
}
func NewConnection(vin string, authHeader, serverURL, userAgent string) *Connection // serverURL 은 호스트명만 ("https://" 없이)
func (c *Connection) SendFleetAPICommand(ctx context.Context, endpoint string, command interface{}) ([]byte, error)
func (c *Connection) PreferredAuthMethod() connector.AuthMethod // AuthMethodHMAC
func (c *Connection) AllowedLatency() time.Duration              // MaxLatency
func (c *Connection) RetryInterval() time.Duration               // 1s
func (c *Connection) Receive() <-chan []byte
func (c *Connection) Close()                                     // inbox close + nil (멱등)
func (c *Connection) VIN() string
func (c *Connection) Wakeup(ctx context.Context) error           // POST api/1/vehicles/<vin>/wake_up 를 state=="online" 까지 10초 간격 반복 (Temporary 오류만 재시도)
func (c *Connection) Send(ctx context.Context, buffer []byte) error // POST api/1/vehicles/<vin>/signed_command {"routable_message": base64}; 응답 {"response": base64} 를 inbox 로
```

`SendFleetAPICommand` (함수) 규칙:
- `command` 가 `[]byte` 면 그대로 body, 아니면 `json.Marshal`.
- 헤더: `User-Agent`, `Content-type: application/json`, `Authorization`, `Accept: */*`.
- 응답 본문은 `connector.MaxResponseLength+1` 까지 읽고 초과 시 `NewError("response exceeds maximum length", true, true)`.
- 상태 코드: 200 → body; **422 → `protocol.ErrProtocolNotSupported`**; 503 → `ErrVehicleNotAwake`; 408 + body 에 "vehicle is offline" → `ErrVehicleNotAwake`; 그 외 → `&HTTPError{Code, Message: body}`.
- 메서드 `(*Connection).SendFleetAPICommand` 는 421 `HTTPError` 의 메시지에서 `use base URL: https://<host>` 를 정규식으로 추출해 `ValidTeslaDomainSuffix` 면 `serverURL` 을 갱신한다 (해당 요청 자체는 오류로 반환, 다음 요청부터 새 호스트).

---

## pkg/vehicle

### 핵심 (`pkg/vehicle/vehicle.go`)

```go
var DefaultFlags = uint32(1 << universal.Flags_FLAG_ENCRYPT_RESPONSE) // = 2
var ErrNoFleetAPIConnection = errors.New("not connected to Fleet API")  // 현재 코드에서 참조되지 않음
var ErrVehicleStateUnknown  = errors.New("could not determine vehicle state") // 현재 코드에서 참조되지 않음

type Vehicle struct {
    Flags uint32 // 요청 RoutableMessage.flags. 기본 DefaultFlags. 0 으로 두면 응답 암호화 요청 안 함.
    /* 비공개: dispatcher, vin, conn, authMethod, keyAvailable */
}

func NewVehicle(conn connector.Connector, privateKey authentication.ECDHPrivateKey, sessionCache *cache.SessionCache) (*Vehicle, error)
func (v *Vehicle) SetMaxLatency(latency time.Duration) // 0 이하면 무시
func (v *Vehicle) VIN() string
func (v *Vehicle) PrivateKeyAvailable() bool
func (v *Vehicle) Connect(ctx context.Context) error   // dispatcher 수신 고루틴 시작. ctx 만료 시 오류.
func (v *Vehicle) SessionInfo(ctx context.Context, publicKey *ecdh.PublicKey, domain universal.Domain) (*signatures.SessionInfo, error) // 임의 공개 키로 비인증 핸드셰이크 요청 1회. 키 등록 여부 확인 용도.
func (v *Vehicle) StartSession(ctx context.Context, domains []universal.Domain) error // nil 이면 VCSEC+Infotainment 동시. ShouldRetry 오류는 RetryInterval 간격으로 재시도.
func (v *Vehicle) Disconnect()                          // dispatcher.Stop() 후 conn.Close(). conn.Close() 를 별도로 defer 해도 되지만 Disconnect 가 먼저.
func (v *Vehicle) SendMessage(ctx context.Context, message *universal.RoutableMessage) (protocol.Receiver, error) // AuthMethodNone 으로 그대로 전송. 카드리스 페어링 등 타 주체가 서명한 메시지 중계용.
func (v *Vehicle) Send(ctx context.Context, domain universal.Domain, payload []byte, auth connector.AuthMethod) ([]byte, error) // 저수준. 종단 결과까지 재시도. 응답의 protobuf_message_as_bytes 반환.
func (v *Vehicle) Wakeup(ctx context.Context) error     // FleetAPIConnector 면 REST wake_up, 아니면 VCSEC RKE_ACTION_WAKE_VEHICLE
func (v *Vehicle) UpdateCachedSessions(c *cache.SessionCache) error
func (v *Vehicle) LoadCachedSessions(c *cache.SessionCache) error // VIN 없으면 errors.New("VIN not in cache")
```

- `NewVehicle`: `privateKey`, `sessionCache` 모두 nil 허용. `sessionCache` 에 VIN 항목이 있으면 즉시 로드(핸드셰이크 생략). `authMethod = conn.PreferredAuthMethod()`.
- `Send` 의 ctx 만료 → `&CommandError{Err: ctx.Err(), PossibleSuccess: true, PossibleTemporary: true}` (재시도 불가, 성공 가능).
- 인증 명령을 `StartSession` 전에 보내면 `ErrNoSession`.

### VCSEC 도메인 명령 (`pkg/vehicle/vcsec.go`, `security.go`, `state.go`, `actions.go` 일부)

```go
type Closure string
const (
    ClosureTrunk   Closure = "trunk"
    ClosureFrunk   Closure = "frunk"
    ClosureTonneau Closure = "tonneau"
)

// RKEAction (vcsec.UnsignedMessage.RKEAction)
func (v *Vehicle) Lock(ctx context.Context) error              // RKE_ACTION_LOCK
func (v *Vehicle) Unlock(ctx context.Context) error            // RKE_ACTION_UNLOCK
func (v *Vehicle) RemoteDrive(ctx context.Context) error       // RKE_ACTION_REMOTE_DRIVE
func (v *Vehicle) AutoSecureVehicle(ctx context.Context) error // RKE_ACTION_AUTO_SECURE_VEHICLE (Model X)
// (비공개 wakeupRKE: RKE_ACTION_WAKE_VEHICLE, Wakeup 이 사용)

// ClosureMoveRequest
func (v *Vehicle) ActuateTrunk(ctx context.Context) error // rearTrunk=MOVE
func (v *Vehicle) OpenTrunk(ctx context.Context) error    // rearTrunk=MOVE (ActuateTrunk 와 동일 페이로드)
func (v *Vehicle) CloseTrunk(ctx context.Context) error   // rearTrunk=CLOSE (일부 차종)
func (v *Vehicle) OpenFrunk(ctx context.Context) error    // frontTrunk=MOVE (원격 닫기 없음)
func (v *Vehicle) OpenTonneau(ctx context.Context) error  // tonneau=OPEN (Cybertruck)
func (v *Vehicle) CloseTonneau(ctx context.Context) error // tonneau=CLOSE
func (v *Vehicle) StopTonneau(ctx context.Context) error  // tonneau=STOP

// 키체인 (WhitelistOperation). 공개 키는 반드시 ecdh.P256() 아니면 ErrInvalidPublicKey.
func (v *Vehicle) AddKey(ctx context.Context, publicKey *ecdh.PublicKey, isOwner bool, formFactor vcsec.KeyFormFactor) error // isOwner→ROLE_OWNER, 아니면 ROLE_DRIVER
func (v *Vehicle) AddKeyWithRole(ctx context.Context, publicKey *ecdh.PublicKey, role keys.Role, formFactor vcsec.KeyFormFactor) error // addKeyToWhitelistAndAddPermissions + metadataForKey
func (v *Vehicle) RemoveKey(ctx context.Context, publicKey *ecdh.PublicKey) error // removePublicKeyFromWhitelist
func (v *Vehicle) SendAddKeyRequest(ctx context.Context, publicKey *ecdh.PublicKey, isOwner bool, formFactor vcsec.KeyFormFactor) error
func (v *Vehicle) SendAddKeyRequestWithRole(ctx context.Context, publicKey *ecdh.PublicKey, role keys.Role, formFactor vcsec.KeyFormFactor) error
// ↑ BLE 전용(FleetAPIConnector 면 ErrRequiresBLE). vcsec.ToVCSECMessage{SignedMessage{SIGNATURE_TYPE_PRESENT_KEY}} 를 RoutableMessage 없이 conn.Send 로 직접 전송. 전송 즉시 nil 반환; 승인 여부는 보장 안 함.

// 정보 요청 (InformationRequest, AuthMethodNone)
func (v *Vehicle) KeySummary(ctx context.Context) (*vcsec.WhitelistInfo, error)                 // GET_WHITELIST_INFO
func (v *Vehicle) KeyInfoBySlot(ctx context.Context, slot uint32) (*vcsec.WhitelistEntryInfo, error) // GET_WHITELIST_ENTRY_INFO + slot
func (v *Vehicle) BodyControllerState(ctx context.Context) (*vcsec.VehicleStatus, error)      // GET_STATUS. Infotainment 수면 중에도 BLE 로 동작.
```

VCSEC 응답 처리 규칙 (`unmarshalVCSECResponse` + `readUntil`):
- 페이로드 없음 → 빈 `FromVCSECMessage` (성공으로 간주).
- `nominalError` → `&NominalError{Details: &NominalVCSECError{...}}`.
- `commandStatus.operationStatus == WAIT` → `ErrBusy` (재시도).
- `ERROR` + `whitelistOperationStatus.whitelistOperationInformation != NONE` → `&KeychainError{Code}`; `ERROR` + `signedMessageStatus == nil` → `ErrUnknown`.
- RKE/Closure 는 `commandStatus == nil` 인 메시지가 종단, 화이트리스트 연산은 `whitelistOperationStatus` 가 채워진 메시지가 종단, 정보 요청은 첫 메시지가 종단.

### Infotainment 도메인 명령

모두 `carserver.Action{VehicleAction{...}}` 을 `Send(ctx, DomainInfotainment, payload, v.authMethod)` 로 보내고 `carserver.Response.actionStatus` 를 검사한다. `result == OPERATIONSTATUS_ERROR` 면 `&NominalError{Details: NewError("car could not execute command: "+plain_text, false, false)}` (텍스트 없으면 "unspecified error").

`pkg/vehicle/actions.go`:
```go
func (v *Vehicle) HonkHorn(ctx context.Context) error                                 // vehicleControlHonkHornAction
func (v *Vehicle) FlashLights(ctx context.Context) error                              // vehicleControlFlashLightsAction
func (v *Vehicle) ChangeSunroofState(ctx context.Context, sunroofLevel int32) error   // vehicleControlSunroofOpenCloseAction.absolute_level (CLI/프록시 없음)
func (v *Vehicle) CloseWindows(ctx context.Context) error                             // vehicleControlWindowAction.close
func (v *Vehicle) VentWindows(ctx context.Context) error                              // vehicleControlWindowAction.vent
func (v *Vehicle) ChargePortClose(ctx context.Context) error                          // chargePortDoorClose (charge.go 의 CloseChargePort 와 동일)
func (v *Vehicle) ChargePortOpen(ctx context.Context) error                           // chargePortDoorOpen  (charge.go 의 OpenChargePort 와 동일)
```

`pkg/vehicle/charge.go`:
```go
type ChargingPolicy int
const (
    ChargingPolicyOff ChargingPolicy = iota
    ChargingPolicyAllDays
    ChargingPolicyWeekdays
)
type ChargeSchedule = carserver.ChargeSchedule             // 필드: Id, Name, DaysOfWeek, StartEnabled, StartTime, EndEnabled, EndTime, OneTime, Enabled, Latitude, Longitude
type PreconditionSchedule = carserver.PreconditionSchedule // 필드: Id, Name, DaysOfWeek, PreconditionTime, OneTime, Enabled, Latitude, Longitude

func (v *Vehicle) AddChargeSchedule(ctx context.Context, schedule *ChargeSchedule) error
func (v *Vehicle) RemoveChargeSchedule(ctx context.Context, id uint64) error
func (v *Vehicle) BatchRemoveChargeSchedules(ctx context.Context, home, work, other bool) error
func (v *Vehicle) AddPreconditionSchedule(ctx context.Context, schedule *PreconditionSchedule) error
func (v *Vehicle) RemovePreconditionSchedule(ctx context.Context, id uint64) error
func (v *Vehicle) BatchRemovePreconditionSchedules(ctx context.Context, home, work, other bool) error
func (v *Vehicle) ChangeChargeLimit(ctx context.Context, chargeLimitPercent int32) error // chargingSetLimitAction.percent
func (v *Vehicle) ChargeStart(ctx context.Context) error         // chargingStartStopAction.start
func (v *Vehicle) ChargeStop(ctx context.Context) error          // chargingStartStopAction.stop
func (v *Vehicle) ChargeMaxRange(ctx context.Context) error      // chargingStartStopAction.start_max_range
func (v *Vehicle) ChargeStandardRange(ctx context.Context) error // chargingStartStopAction.start_standard
func (v *Vehicle) SetChargingAmps(ctx context.Context, amps int32) error // setChargingAmpsAction.charging_amps
func (v *Vehicle) OpenChargePort(ctx context.Context) error
func (v *Vehicle) CloseChargePort(ctx context.Context) error
func (v *Vehicle) ScheduleDeparture(ctx context.Context, departAt, offPeakEndTime time.Duration, preconditioning, offpeak ChargingPolicy) error // departAt ∉ [0,24h] → "invalid departure time"; 분 단위로 변환
func (v *Vehicle) ScheduleCharging(ctx context.Context, enabled bool, timeAfterMidnight time.Duration) error // scheduledChargingAction{enabled, charging_time(분)}
func (v *Vehicle) ClearScheduledDeparture(ctx context.Context) error // scheduledDepartureAction{enabled:false}
func (v *Vehicle) SetLowPowerMode(ctx context.Context, enable bool) error            // 강제 저전력 상태면 low_power_mode_enforced 오류
func (v *Vehicle) SetKeepAccessoryPowerMode(ctx context.Context, enable bool) error
```

`pkg/vehicle/climate.go`:
```go
type Level int
const (
    LevelOff Level = iota // 0
    LevelLow              // 1
    LevelMed              // 2
    LevelHigh             // 3
)
type ClimateKeeperMode = carserver.HvacClimateKeeperAction_ClimateKeeperAction_E
const (
    ClimateKeeperModeOff  = carserver.HvacClimateKeeperAction_ClimateKeeperAction_Off  // 0
    ClimateKeeperModeOn   = carserver.HvacClimateKeeperAction_ClimateKeeperAction_On   // 1
    ClimateKeeperModeDog  = carserver.HvacClimateKeeperAction_ClimateKeeperAction_Dog  // 2
    ClimateKeeperModeCamp = carserver.HvacClimateKeeperAction_ClimateKeeperAction_Camp // 3
)

func (v *Vehicle) SetSeatCooler(ctx context.Context, level Level, seat SeatPosition) error // SeatFrontLeft/Right 만; 프로토 레벨 = level+1
func (v *Vehicle) ClimateOn(ctx context.Context) error   // hvacAutoAction.power_on=true
func (v *Vehicle) ClimateOff(ctx context.Context) error  // hvacAutoAction.power_on=false
func (v *Vehicle) AutoSeatAndClimate(ctx context.Context, positions []SeatPosition, enabled bool) error // SeatUnknown/FrontLeft/FrontRight 만 매핑, 나머지 무시
func (v *Vehicle) ChangeClimateTemp(ctx context.Context, driverCelsius float32, passengerCelsius float32) error // hvacTemperatureAdjustmentAction + level.TEMP_MAX
func (v *Vehicle) SetSeatHeater(ctx context.Context, levels map[SeatPosition]Level) error // 좌석별 Void oneof 로 변환, 알 수 없는 값은 UNKNOWN
func (v *Vehicle) SetSteeringWheelHeater(ctx context.Context, enabled bool) error
func (v *Vehicle) SetPreconditioningMax(ctx context.Context, enabled bool, manualOverride bool) error
func (v *Vehicle) SetBioweaponDefenseMode(ctx context.Context, enabled bool, manualOverride bool) error
func (v *Vehicle) SetCabinOverheatProtection(ctx context.Context, enabled bool, fanOnly bool) error
func (v *Vehicle) SetCabinOverheatProtectionTemperature(ctx context.Context, level Level) error // setCopTempAction.cop_activation_temp = ClimateState_CopActivationTemp(level)
func (v *Vehicle) SetClimateKeeperMode(ctx context.Context, mode ClimateKeeperMode, override bool) error
```

`pkg/vehicle/infotainment.go`:
```go
type SeatPosition int64
const (
    SeatUnknown SeatPosition = iota // 0
    SeatFrontLeft                   // 1
    SeatFrontRight                  // 2
    SeatSecondRowLeft               // 3
    SeatSecondRowLeftBack           // 4  (등받이, 일부 Model S)
    SeatSecondRowCenter             // 5
    SeatSecondRowRight              // 6
    SeatSecondRowRightBack          // 7
    SeatThirdRowLeft                // 8
    SeatThirdRowRight               // 9
)

func (v *Vehicle) Ping(ctx context.Context) error // ping{ping_id:1}. nil 이면 온라인+키 인식. RoutableMessageError 면 온라인이지만 거부.
func (v *Vehicle) VolumeUp(ctx context.Context) error   // mediaUpdateVolume.volume_delta=+1
func (v *Vehicle) VolumeDown(ctx context.Context) error // volume_delta=-1
func (v *Vehicle) SetVolume(ctx context.Context, volume float32) error // [0,10] 아니면 "invalid volume (should be in [0, 10])"; volume_absolute_float
func (v *Vehicle) MediaNextTrack(ctx context.Context) error
func (v *Vehicle) MediaPreviousTrack(ctx context.Context) error
func (v *Vehicle) MediaNextFavorite(ctx context.Context) error
func (v *Vehicle) MediaPreviousFavorite(ctx context.Context) error
func (v *Vehicle) ToggleMediaPlayback(ctx context.Context) error // mediaPlayAction
func (v *Vehicle) ScheduleSoftwareUpdate(ctx context.Context, delay time.Duration) error // offset_sec
func (v *Vehicle) CancelSoftwareUpdate(ctx context.Context) error
func (v *Vehicle) GetNearbyCharging(ctx context.Context) error // getNearbyChargingSites{include_meta_data:true, radius:200, count:10}. 응답 데이터는 버림(error 만 반환). CLI/프록시 없음.
func (v *Vehicle) SetVehicleName(ctx context.Context, name string) error
```

`pkg/vehicle/security.go`:
```go
func IsValidPIN(pin string) bool // 정확히 4자리 숫자
var ErrInvalidPIN = errors.New("PIN codes must be four digits")

func (v *Vehicle) EnableValetMode(ctx context.Context, pin string) error  // IsValidPIN 검사
func (v *Vehicle) DisableValetMode(ctx context.Context) error             // 오류 메시지가 "already off" 로 끝나면 nil
func (v *Vehicle) SetValetMode(ctx context.Context, on bool, valetPassword string) error // Deprecated
func (v *Vehicle) ResetValetPin(ctx context.Context) error
func (v *Vehicle) ResetPIN(ctx context.Context) error                     // Deprecated → ClearPINToDrive. vehicleControlResetPinToDriveAction
func (v *Vehicle) ClearPINToDrive(ctx context.Context) error              // vehicleControlResetPinToDriveAdminAction
func (v *Vehicle) SetPINToDrive(ctx context.Context, enabled bool, pin string) error // Fleet API 전용: FleetAPIConnector 아니면 ErrRequiresEncryption
func (v *Vehicle) ActivateSpeedLimit(ctx context.Context, speedLimitPin string) error
func (v *Vehicle) DeactivateSpeedLimit(ctx context.Context, speedLimitPin string) error
func (v *Vehicle) ClearSpeedLimitPINAdminAction(ctx context.Context) error
func (v *Vehicle) SpeedLimitSetLimitMPH(ctx context.Context, speedLimitMPH float64) error
func (v *Vehicle) ClearSpeedLimitPIN(ctx context.Context, speedLimitPin string) error
func (v *Vehicle) SetSentryMode(ctx context.Context, state bool) error
func (v *Vehicle) SetGuestMode(ctx context.Context, enabled bool) error   // guestModeAction (VehicleState_GuestMode)
func (v *Vehicle) TriggerHomelink(ctx context.Context, latitude float32, longitude float32) error
func (v *Vehicle) EraseGuestData(ctx context.Context) error               // eraseUserDataAction (Guest Mode 중에만 효과)

type ParentalControlsSetting = carserver.ParentalControlsEnableSettingsAction_ParentalControlsSetting_E
const (
    ParentalControlsSettingSpeedLimit     = carserver.ParentalControlsEnableSettingsAction_SpeedLimit
    ParentalControlsSettingAcceleration   = carserver.ParentalControlsEnableSettingsAction_Acceleration
    ParentalControlsSettingSafetyFeatures = carserver.ParentalControlsEnableSettingsAction_SafetyFeatures
    ParentalControlsSettingCurfew         = carserver.ParentalControlsEnableSettingsAction_Curfew
    ParentalControlsSettingBrowserBlocked = carserver.ParentalControlsEnableSettingsAction_BrowserBlocked
    ParentalControlsSettingTheaterBlocked = carserver.ParentalControlsEnableSettingsAction_TheaterBlocked
    ParentalControlsSettingArcadeBlocked  = carserver.ParentalControlsEnableSettingsAction_ArcadeBlocked
)
func (v *Vehicle) ParentalControlsActivate(ctx context.Context, pin string) error   // IsValidPIN
func (v *Vehicle) ParentalControlsDeactivate(ctx context.Context, pin string) error // IsValidPIN
func (v *Vehicle) ParentalControlsEnableSetting(ctx context.Context, setting ParentalControlsSetting, enable bool) error // 활성 상태면 parental_controls_active 오류
func (v *Vehicle) ParentalControlsSetSpeedLimit(ctx context.Context, limitMPH float64) error
func (v *Vehicle) ParentalControlsClearPIN(ctx context.Context) error // parentalControlsClearPinAdminAction
```

`pkg/vehicle/state.go`:
```go
type StateCategory int32
const (
    StateCategoryCharge StateCategory = iota   // 0  GetChargeState
    StateCategoryClimate                       // 1  GetClimateState
    StateCategoryDrive                         // 2  GetDriveState
    StateCategoryLocation                      // 3  GetLocationState
    StateCategoryClosures                      // 4  GetClosuresState
    StateCategoryChargeSchedule                // 5  GetChargeScheduleState
    StateCategoryPreconditioningSchedule       // 6  GetPreconditioningScheduleState
    StateCategoryTirePressure                  // 7  GetTirePressureState
    StateCategoryMedia                         // 8  GetMediaState
    StateCategoryMediaDetail                   // 9  GetMediaDetailState
    StateCategorySoftwareUpdate                // 10 GetSoftwareUpdateState
    StateCategoryParentalControls              // 11 GetParentalControlsState
)
func (v *Vehicle) GetState(ctx context.Context, category StateCategory) (*carserver.VehicleData, error) // 알 수 없는 카테고리 → "unrecognized vehicle data category". BLE 용도; 인터넷은 Fleet API vehicle_data 권장.
```

---

## pkg/cache

```go
type SessionCache struct {
    MaxEntries int
    Vehicles   map[string][]dispatcher.CacheEntry `json:"vehicles"` // dispatcher.CacheEntry{CreatedAt time.Time `json:"created_at"`; Domain int `json:"domain"`; SessionInfo []byte `json:"data"`}
    /* lock sync.Mutex */
}
func New(maxEntries int) *SessionCache // 0 이면 무제한
func Import(r io.Reader) (*SessionCache, error)
func ImportFromFile(filename string) (*SessionCache, error)
func (c *SessionCache) Export(w io.Writer) error
func (c *SessionCache) ExportToFile(filename string) error // O_RDWR|O_CREATE, 0644. 기존 파일을 truncate 하지 않음(짧아지면 뒤에 찌꺼기 남을 수 있음).
func (c *SessionCache) Update(vin string, sessions []dispatcher.CacheEntry) error // Vehicle.UpdateCachedSessions 사용 권장
func (c *SessionCache) GetEntry(vin string) ([]dispatcher.CacheEntry, bool)      // dispatcher 내부용
```

퇴거(eviction): `Update` 후 `MaxEntries > 0 && len > MaxEntries` 이면 각 VIN 의 가장 최근 `CreatedAt` 을 비교해 가장 오래된 VIN 하나를 삭제. "사용" 시각은 `Dispatcher.Cache()` 호출 시각(`time.Now()`)이다. 캐시는 특정 개인 키에 묶인다; 다른 키로 쓰면 첫 명령이 실패하고 차량이 새 세션 정보를 보내 복구된다. 내보낸 파일은 접근 제어 필요.

---

## pkg/cli

### 상수와 타입 (`pkg/cli/config.go`, `keyring.go`)

```go
const (
    EnvTeslaKeyName      = "TESLA_KEY_NAME"
    EnvTeslaKeyFile      = "TESLA_KEY_FILE"
    EnvTeslaTokenName    = "TESLA_TOKEN_NAME"
    EnvTeslaTokenFile    = "TESLA_TOKEN_FILE"
    EnvTeslaVIN          = "TESLA_VIN"
    EnvTeslaCacheFile    = "TESLA_CACHE_FILE"
    EnvTeslaKeyringType  = "TESLA_KEYRING_TYPE"
    EnvTeslaKeyringPass  = "TESLA_KEYRING_PASSWORD"
    EnvTeslaKeyringPath  = "TESLA_KEYRING_PATH"
    EnvTeslaKeyringDebug = "TESLA_KEYRING_DEBUG"
)
type Flag int
const (
    FlagVIN        Flag = 1
    FlagOAuth      Flag = 2
    FlagPrivateKey Flag = 4 // 차량 명령 전송에 필요
    FlagBLE        Flag = 8 // FlagVIN 필요
    FlagAll        Flag = FlagVIN | FlagOAuth | FlagPrivateKey | FlagBLE
)
var (
    ErrNoKeySpecified        = errors.New("private key location not provided")
    ErrNoAvailableTransports = errors.New("no available transports (configuration must permit BLE and/or OAuth)")
    ErrKeyNotFound           = keyring.ErrKeyNotFound
)
var DomainsByName = map[string]protocol.Domain{"VCSEC": DomainVCSEC, "INFOTAINMENT": DomainInfotainment}
var DomainNames   = map[protocol.Domain]string{...역방향...}
type DomainList []protocol.Domain // flag.Value. Set 은 대문자 변환 후 DomainsByName 조회, 실패 시 "unknown domain '<v>'"

// keyring.go (비공개 상수, 동작 이해용)
// keyringServiceName = "com.tesla.auth"; keyringKeyService = "vehicleCommandKey"; keyringTokenService = "oauthtoken"; keyringDirectory = "~/.tesla_keys"

type Config struct {
    Flags            Flag
    KeyringKeyName   string
    KeyringTokenName string
    VIN              string
    BtAdapterID      string // Linux 전용
    TokenFilename    string
    KeyFilename      string
    CacheFilename    string
    DisableCache     bool
    Backend          keyring.Config
    BackendType      backendType // flag.Value; String() 은 AllowedBackends[0] 또는 keyring.InvalidBackend
    Debug            bool
    Domains          DomainList
    /* 비공개: password, sessions, acct, skey, oauthToken */
}
```

### 메서드

```go
func NewConfig(flags Flag) (*Config, error) // Backend: ServiceName "com.tesla.auth", KeychainTrustApplication true, KeyCtlScope "user", 비밀번호 함수 getPassword. 항상 err == nil.
func (c *Config) RegisterCommandLineFlags()
func (c *Config) LoadCredentials() error   // FlagOAuth 면 token(), FlagPrivateKey 면 PrivateKey() 를 미리 호출 (키링 비밀번호 프롬프트를 타임아웃 밖으로)
func (c *Config) ReadFromEnvironment()     // 비어 있는 필드만 환경 변수로 채움. flag.Parse() 이후 호출.
func (c *Config) UpdateCachedSessions(v *vehicle.Vehicle) // CacheFilename 비었거나 sessions nil 이면 no-op
func (c *Config) PrivateKey() (skey protocol.ECDHPrivateKey, err error) // 캐시됨. 파일 → 키링 순. 위치 미지정이면 (nil, ErrNoKeySpecified). loadCache() 도 여기서 수행.
func (c *Config) Connect(ctx context.Context) (acct *account.Account, car *vehicle.Vehicle, err error)
func (c *Config) Account() (*account.Account, error)                     // account.New(token, "")
func (c *Config) SavePrivateKey(skey protocol.ECDHPrivateKey) error       // 키링 우선, 없으면 파일, 둘 다 없으면 ErrNoKeySpecified
func (c *Config) ConnectRemote(ctx context.Context, skey protocol.ECDHPrivateKey) (acct *account.Account, car *vehicle.Vehicle, err error) // FlagVIN && VIN != "" 일 때만 car 생성
func (c *Config) ConnectLocal(ctx context.Context, skey protocol.ECDHPrivateKey) (car *vehicle.Vehicle, err error) // ble.InitAdapterWithID(BtAdapterID) → ble.NewConnection → vehicle.NewVehicle(conn, skey, c.sessions)
func (c *Config) LoadTokenFromKeyring() (string, error)  // 키 "oauthtoken.<KeyringTokenName>"
func (c *Config) SaveTokenToKeyring(token string) error
func (c *Config) LoadKeyFromKeyring() (protocol.ECDHPrivateKey, error) // 키 "vehicleCommandKey.<KeyringKeyName>", 32바이트 스칼라
func (c *Config) DeletePrivateKey() error                // 키링 항목만 삭제
```

`RegisterCommandLineFlags` 가 등록하는 플래그:

| 플래그 | 조건 | 기본값 | 설명 |
|---|---|---|---|
| `-vin` | FlagVIN | "" | Defaults to $TESLA_VIN |
| `-session-cache` | FlagPrivateKey | "" | 세션 캐시 파일 |
| `-disable-session-cache` | FlagPrivateKey | false | |
| `-key-name` | FlagPrivateKey | "" | 키링 이름 |
| `-key-file` | FlagPrivateKey | "" | PEM 파일 |
| `-domain` | FlagPrivateKey | (없음) | 반복 가능. vcsec / infotainment |
| `-token-name` | FlagOAuth | "" | |
| `-token-file` | FlagOAuth | "" | |
| `-keyring-type` | FlagOAuth 또는 FlagPrivateKey | (미설정) | `keyring.AvailableBackends()` 중 하나 |
| `-keyring-file-dir` | FlagOAuth 또는 FlagPrivateKey | `~/.tesla_keys` | 파일 키링 디렉터리 |
| `-keyring-debug` | FlagOAuth 또는 FlagPrivateKey | false | |
| `-bt-adapter` | Linux 이고 FlagBLE | "" | hciN |

`Connect` 결정 규칙:
1. VIN, KeyringTokenName, TokenFilename 이 모두 비면 오류 "must provide VIN and/or OAuth token".
2. `PrivateKey()` 오류가 `ErrNoKeySpecified` 가 아니면 반환.
3. `FlagOAuth` 이고 토큰 위치가 있으면 `ConnectRemote`; 아니면 `FlagBLE|FlagVIN` 이면 `ConnectLocal`; 아니면 `ErrNoAvailableTransports`.
4. car 가 있으면 `car.Connect(ctx)`, skey 가 있으면 `car.StartSession(ctx, c.Domains)`.

---

## pkg/account

```go
type Account struct {
    UserAgent  string // buildUserAgent(app)
    Host       string // Fleet API 호스트 (프록시가 421 시 갱신)
    Subject    string // OAuth "sub"
    /* 비공개: authHeader, client */
}
func New(oauthToken, userAgent string) (*Account, error)
func (a *Account) GetVehicle(_ context.Context, vin string, privateKey authentication.ECDHPrivateKey, sessions *cache.SessionCache) (*vehicle.Vehicle, error) // inet.NewConnection(vin, authHeader, Host, UserAgent) → vehicle.NewVehicle. ctx 미사용.
func (a *Account) Get(ctx context.Context, endpoint string) ([]byte, error)  // GET https://<Host>/<endpoint>. 200 아니면 오류. 본문 MaxResponseLength 제한.
func (a *Account) Post(ctx context.Context, endpoint string, data []byte) ([]byte, error) // inet.SendFleetAPICommand 로 POST (data 는 그대로 body)
func (a *Account) SendVehicleFleetAPICommand(ctx context.Context, vin, endpoint string, command interface{}) ([]byte, error) // api/1/vehicles/<vin>/<endpoint>
func (a *Account) UpdateKey(ctx context.Context, publicKey *ecdh.PublicKey, name string) error // POST api/1/users/keys {public_key(hex), kind:"mobile_device", model:"3rd Party Application", name, tag:UserAgent}
```

`New` 규칙:
- 토큰을 `.` 로 3분할, 두 번째 부분을 `base64.RawStdEncoding` 디코드해 `aud`, `ou_code`, `sub` 만 파싱. 실패 시 "client provided malformed OAuth token".
- 호스트 결정: `remappedDomains`(개발용, 비어 있음) → `aud` 중 `https://auth.tesla.` 로 시작하지 않고, `https://` 제거 후 `[A-Za-z0-9-.]+` 이며 `ValidTeslaDomainSuffix` 이고 `fleet-api.` 로 시작하는 것. `.<ou_code 소문자>.` 를 포함하는 후보가 있으면 즉시 채택. 없으면 `defaultDomain = "fleet-api.prd.na.vn.cloud.tesla.com"`.
- `authHeader = "Bearer " + strings.TrimSpace(token)`.

`buildUserAgent(app)`: `"<app> tesla-sdk/<pkg/account/version.txt>"`. `app` 이 비면 `debug.ReadBuildInfo()` 의 모듈 경로 마지막 요소 + 버전(또는 vcs.revision 8자).

---

## pkg/proxy

```go
const (
    DefaultTimeout    = 10 * time.Second
    MaxResponseLength = 10000000 // forwardRequest 응답 상한 (10 MB)
    MaxAttempts       = 2        // 421 Alt-Svc 재시도 횟수
)
// 비공개: maxRequestBodyBytes = 1<<20, vinLength = 17, proxyProtocolVersion = "tesla-http-proxy/1.1.0", h2Prefix = "h2=https://"

var ErrCommandNotImplemented = errors.New("command not implemented")          // remote_boombox
var ErrCommandUseRESTAPI     = errors.New("command requires using the REST API") // navigation_request, set_managed_* → forwardRequest 로 폴백

type Proxy struct {
    Timeout time.Duration // 명령/포워딩 컨텍스트 타임아웃
    /* 비공개: commandKey, sessions, vinLock, unsupported, domainForSubject, client, fetchVehicle */
}
func New(_ context.Context, skey protocol.ECDHPrivateKey, cacheSize int) (*Proxy, error) // cache.New(cacheSize)
func (p *Proxy) ServeHTTP(w http.ResponseWriter, req *http.Request)                      // http.Handler

type Response struct {
    Response   interface{} `json:"response"`
    Error      string      `json:"error"`
    ErrDetails string      `json:"error_description"`
}
type RequestParameters map[string]interface{}
func ExtractCommandAction(ctx context.Context, command string, params RequestParameters) (func(*vehicle.Vehicle) error, error)
```

`ExtractCommandAction` 의 파라미터 접근자(비공개)는 JSON 타입을 엄격히 검사한다: 문자열/불리언/숫자(float64)가 아니면 `&NominalError{"invalid <key> param"}`, 필수 키 누락 시 `&NominalError{"missing <key> param"}`. 상세는 [07-http-proxy.md](07-http-proxy.md), 엔드포인트 표는 [05-command-catalog.md](05-command-catalog.md).

---

## pkg/sign

```go
func SignMessageForVehicle(privateKey authentication.ECDHPrivateKey, vin, app string, message jwt.MapClaims) (string, error) // aud = "com.tesla.vehicle.<vin>.<app>"
func SignMessageForFleet(privateKey authentication.ECDHPrivateKey, app string, message jwt.MapClaims) (string, error)       // aud = "com.tesla.fleet.<app>"
```

둘 다 `internal/authentication.SignMessage` 로 위임하며 `aud`, `iss` 클레임을 덮어쓴다. 알고리즘은 Schnorr/P-256 (`alg` 헤더 `authentication.TeslaSchnorrSHA256`), `iss` 는 base64 인코딩된 공개 키. 프록시의 `fleet_telemetry_config` 는 `SignMessageForFleet(key, "TelemetryClient", config)` 를 사용.

---

## internal 패키지 개요 (임포트 불가)

| 패키지 | 역할 | 공개 API 에 노출되는 타입 |
|---|---|---|
| `internal/authentication` | ECDH 키 합의, 메타데이터 TLV, HMAC/AES-GCM 서명(`Signer`)·검증(`Verifier`), 세션 정보 임포트/익스포트, 슬라이딩 윈도우 안티리플레이, Schnorr JWT | `ECDHPrivateKey`(→`protocol.ECDHPrivateKey`), `Session`(→`protocol.Session`), `NativeECDHKey`(키 저장 시 타입 단언), `ErrInvalidPublicKey` |
| `internal/dispatcher` | `Connector` 위에서 RoutableMessage 송수신, 도메인별 `session`, 요청-응답 매칭(`receiverKey{address,uuid,domain}`), 세션 자동 갱신, 응답 복호화 | `CacheEntry`(`cache.SessionCache.Vehicles` 값 타입) |
| `internal/log` | 전역 레벨 로거(stderr). `SetLevel(LevelDebug)` 로 TX/RX hex 덤프 | 없음 (CLI 도구가 직접 사용) |
| `internal/schnorr` | P-256 Schnorr 서명 (JWS 용) | 없음 |

---

## 검증 체크리스트

- [ ] 이 문서의 시그니처를 바꾸는 수정을 했다면 `grep -nE '^func \(v \*Vehicle\)' pkg/vehicle/*.go` 등으로 실제 코드와 대조했다.
- [ ] 새 센티널 오류를 추가했다면 `protocol.Error` 구현 여부(`NewError` vs `errors.New`)를 의도적으로 정했고 `ShouldRetry` 영향이 맞다.
- [ ] `Connector` 를 새로 구현했다면 `PreferredAuthMethod`, `AllowedLatency`, `RetryInterval`, 멱등 `Close`, 스레드 안전 `Send/Receive` 를 만족하고, HTTP 기반이면 `FleetAPIConnector` 도 구현했다.
- [ ] `Vehicle` 에 명령 메서드를 추가했다면 [05-command-catalog.md](05-command-catalog.md) 의 "새 명령 추가 체크리스트" 를 따랐다.
- [ ] `go build ./... && go vet ./... && go test ./...` 통과 (`check-all.sh` 또는 `make test`).
