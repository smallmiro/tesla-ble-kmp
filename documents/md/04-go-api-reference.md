# 04. Go Public API Reference

This document lists the public API of the importable packages under `github.com/teslamotors/vehicle-command/pkg/...` with their exact signatures. `internal/` packages cannot be imported, so only an overview of them is given at the end.

Related files (relative to the repository root):
- `pkg/protocol/{error.go,key.go,domains.go,receiver.go}`
- `pkg/connector/connector.go`, `pkg/connector/ble/{ble.go,device_*.go}`, `pkg/connector/inet/inet.go`
- `pkg/vehicle/{vehicle.go,vcsec.go,actions.go,charge.go,climate.go,infotainment.go,security.go,state.go}`
- `pkg/cache/cache.go`, `pkg/cli/{config.go,keyring.go,config_linux.go}`, `pkg/account/account.go`
- `pkg/proxy/{proxy.go,command.go}`, `pkg/sign/sign.go`

Related documents: [01-architecture.md](01-architecture.md), [05-command-catalog.md](05-command-catalog.md), [08-errors.md](08-errors.md), [09-recipes.md](09-recipes.md)

---

## Layer summary

```
pkg/account (OAuth, Fleet API)  ──┐
pkg/cli (flags/env/keyring)      ──┤
                                  ▼
pkg/vehicle.Vehicle  ← command methods (Lock, ClimateOn, GetState ...)
        │  uses internal/dispatcher (sessions, signing, routing)
        ▼
pkg/connector.Connector  ← pkg/connector/ble.Connection | pkg/connector/inet.Connection | user-defined
```

`pkg/cache` is the session cache between `Vehicle` and `internal/dispatcher`, `pkg/protocol` holds shared error/key/domain types, `pkg/proxy` is a REST server built on top of `Vehicle`, and `pkg/sign` is JWS signing.

---

## pkg/protocol

### Error interface and helpers (`pkg/protocol/error.go`)

```go
type Error interface {
    error
    MayHaveSucceeded() bool // the vehicle may have received the command (e.g., timeout while waiting for a response)
    Temporary() bool        // transient condition (e.g., Busy during wake-up)
}

type CommandError struct {
    Err               error
    PossibleSuccess   bool
    PossibleTemporary bool
}
func NewError(message string, mayHaveSucceeded bool, temporary bool) error // returns *CommandError
func (e *CommandError) Error() string
func (e *CommandError) Unwrap() error
func (e *CommandError) MayHaveSucceeded() bool
func (e *CommandError) Temporary() bool

func MayHaveSucceeded(err error) bool // errors.As(err, *Error) && MayHaveSucceeded()
func Temporary(err error) bool        // errors.As(err, *Error) && Temporary()
func ShouldRetry(err error) bool      // err != nil && implements Error && !MayHaveSucceeded() && Temporary()
func IsNominalError(err error) bool   // errors.As(err, **NominalError)
```

`ShouldRetry` is the criterion for the retry loops in `Vehicle.Send`, `Vehicle.StartSession`, and `Dispatcher.Send`. An error with `MayHaveSucceeded()==true` is never retried (prevents duplicate execution).

```go
// Keychain (whitelist) operation failure. Code is vcsec.WhitelistOperationInformation_E.
type KeychainError struct{ Code vcsec.WhitelistOperationInformation_E }
// Error(): "keychain operation failed: <Code>", MayHaveSucceeded()=false, Temporary()=false

// Application-layer error: the vehicle authenticated the request but could not execute it. Unwraps to Details.
type NominalError struct{ Details error }
// MayHaveSucceeded()/Temporary() delegate to Details

// VCSEC application error (errors.proto NominalError). Always non-temporary, non-success.
type NominalVCSECError struct{ Details *verror.NominalError }
// Error(): "vcsec could not execute command: <GenericError_E name>"

// Protocol-layer error (RoutableMessage.signedMessageStatus.signed_message_fault).
type RoutableMessageError struct{ Code universal.MessageFault_E }
// Error(): MessageFault_E name (e.g., "MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES")
// MayHaveSucceeded(): Code == ERROR_NONE || Code == ERROR_RESPONSE_MTU_EXCEEDED
// Temporary(): Code ∈ {BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL,
//                      INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG}

func GetError(u *universal.RoutableMessage) error
```

`GetError` rules (in order):
1. `signedMessageStatus.signed_message_fault != NONE` → `ErrKeyNotPaired` if `UNKNOWN_KEY_ID`, otherwise `&RoutableMessageError{Code}`.
2. If a `session_info` payload is present, parse it → parse failure `ErrBadResponse`; `status == KEY_NOT_ON_WHITELIST` → `ErrKeyNotPaired`; any other non-OK → `ErrUnknown`.
3. `signedMessageStatus.operation_status`: `OK` → nil, `WAIT` → `ErrBusy`, `ERROR` → nil (ERROR without a fault yields nil), any other value → `ErrUnknown`.

### Sentinel errors

| Name | Type | Message / meaning | MayHaveSucceeded / Temporary |
|---|---|---|---|
| `ErrBusy` | `*CommandError` | "vehicle busy or finishing wake-up" | false / **true** |
| `ErrUnknown` | `*CommandError` | "vehicle responded with an unrecognized status code" | false / false |
| `ErrNotConnected` | `*CommandError` | "vehicle not connected" (dispatcher not started, inet inbox closed) | false / false |
| `ErrNoSession` | `*CommandError` | "cannot send authenticated command before establishing a vehicle session" | false / false |
| `ErrRequiresKey` | `*CommandError` | "no private key available" (handshake attempted without a private key) | false / false |
| `ErrInvalidPublicKey` | `authentication.ErrInvalidPublicKey` (re-exported) | not an uncompressed P-256 public key | N/A |
| `ErrKeyNotPaired` | `*CommandError` | "vehicle rejected request: your public key has not been paired with the vehicle" | false / false |
| `ErrUnpexpectedPublicKey` | `errors.New` | "remote public key changed unexpectedly" (the misspelling is part of the public API) | N/A |
| `ErrBadResponse` | `errors.New` | "invalid response" | N/A |
| `ErrProtocolNotSupported` | `errors.New` | "vehicle does not support protocol -- use REST API" (Fleet API 422) | N/A |
| `ErrRequiresBLE` | `errors.New` | "command can only be sent over BLE" (`SendAddKeyRequest*` over inet) | N/A |
| `ErrRequiresEncryption` | `errors.New` | "command should not be sent in plaintext or encrypted with an unauthenticated public key" (`SetPINToDrive` over BLE) | N/A |
| `ErrNoDecryptionContext` | `errors.New` | "could not decrypt vehicle response without a session" | N/A |
| `ErrReplayedResponse` | `errors.New` | "received vehicle response with duplicate counter" | N/A |

The `errors.New` errors do not implement `protocol.Error`, so `ShouldRetry` returns false for them.

### Domains (`pkg/protocol/domains.go`)

```go
type Domain = universal.Domain
const (
    DomainNone         = universal.Domain_DOMAIN_BROADCAST        // 0
    DomainVCSEC        = universal.Domain_DOMAIN_VEHICLE_SECURITY // 2
    DomainInfotainment = universal.Domain_DOMAIN_INFOTAINMENT     // 3
)
```

### Keys (`pkg/protocol/key.go`)

```go
type ECDHPrivateKey authentication.ECDHPrivateKey // interface: Exchange(remotePublicBytes []byte) (Session, error); PublicBytes() []byte; SchnorrSignature(message []byte) ([]byte, error)
type Session = authentication.Session             // alias for HSM/TEE implementations. See the TEE requirements in the source comments.

func LoadPrivateKey(filename string) (ECDHPrivateKey, error)
func SavePrivateKey(skey ECDHPrivateKey, filename string) error
func LoadPublicKey(filename string) (*ecdh.PublicKey, error)
func PublicKeyBytesFromHex(h string) (*ecdh.PublicKey, error)
func UnmarshalECDHPrivateKey(keyBytes []byte) ECDHPrivateKey
```

- `LoadPrivateKey`: PEM only. `EC PRIVATE KEY` (SEC1) or PKCS8 (`PRIVATE KEY`, unencrypted). If the curve is not P-256, returns an error wrapping `ErrInvalidPrivateKey`. The returned implementation is `*authentication.NativeECDHKey`.
- `SavePrivateKey`: only `*authentication.NativeECDHKey` is supported ("key is not exportable"). Writes `EC PRIVATE KEY` PEM with file mode `0600`.
- `LoadPublicKey` supported formats: PKIX PEM (`PUBLIC KEY`), PKCS8 PEM (`PRIVATE KEY`, public key extracted from the private key), SEC1 PEM (`EC PRIVATE KEY`), 65-byte binary uncompressed point (`0x04||X||Y`), 130-byte (131 with trailing newline) hex string. Not P-256 → `ErrInvalidPublicKey`.
- `PublicKeyBytesFromHex`: hex → `ecdh.P256().NewPublicKey`.
- `UnmarshalECDHPrivateKey`: 32-byte scalar → key. If the length is not 32 or `D >= N`, **returns nil (not an error)**. This is the format stored in the keyring.

### Receiver (`pkg/protocol/receiver.go`)

```go
type Receiver interface {
    Recv() <-chan *universal.RoutableMessage // usually one response; VCSEC may send several
    Close()                                  // must be called to release the dispatcher handler
}
```

---

## pkg/connector

```go
type AuthMethod int32
const (
    AuthMethodNone AuthMethod = iota // handshake, unauthenticated information requests
    AuthMethodGCM                    // AES-GCM encryption+authentication (BLE default)
    AuthMethodHMAC                   // HMAC-SHA256 plaintext authentication (Fleet API default)
)
const BufferSize = 5              // receive channel buffer (5 for both inet and ble)
const MaxResponseLength = 100000  // maximum response bytes a Connector must support

type Connector interface {
    Receive() <-chan []byte                         // vehicle→client datagrams. Thread-safe.
    Send(ctx context.Context, buffer []byte) error  // client→vehicle. Thread-safe. If the error is a protocol.Error, MayHaveSucceeded can be checked.
    VIN() string
    Close()                                         // idempotent. Behavior afterwards is undefined.
    PreferredAuthMethod() AuthMethod                // must be AuthMethodHMAC for HTTP-based connectors
    RetryInterval() time.Duration                   // recommended retransmission interval
    AllowedLatency() time.Duration                  // allowed latency for receiving session info (session info discarded if exceeded)
}

type FleetAPIConnector interface {
    Connector
    SendFleetAPICommand(ctx context.Context, endpoint string, command interface{}) ([]byte, error)
    Wakeup(ctx context.Context) error
}
```

`Vehicle.Wakeup`, `Vehicle.SetPINToDrive`, and `Vehicle.SendAddKeyRequestWithRole` determine the transport type with the type assertion `conn.(connector.FleetAPIConnector)`. A user-defined HTTP-based Connector must also implement this interface for correct behavior.

---

## pkg/connector/ble

```go
var ErrAdapterInvalidID       = protocol.NewError("the bluetooth adapter ID is invalid", false, false)
var ErrMaxConnectionsExceeded = protocol.NewError("the vehicle is already connected to the maximum number of BLE devices", false, false)

type Connection struct { /* private */ }
func (c *Connection) PreferredAuthMethod() connector.AuthMethod // AuthMethodGCM
func (c *Connection) RetryInterval() time.Duration               // 1s
func (c *Connection) AllowedLatency() time.Duration              // 4s (maxLatency)
func (c *Connection) Receive() <-chan []byte                     // buffer 5
func (c *Connection) Send(_ context.Context, buffer []byte) error // 2-byte BE length + payload, write-with-response in blockLength chunks. ctx ignored.
func (c *Connection) VIN() string
func (c *Connection) Close()                                     // ClearSubscriptions + CancelConnection

func VehicleLocalName(vin string) string // "S" + hex(sha1(vin)[:8]) + "C"

func InitAdapterWithID(id string) error  // Linux: "hciN"(0..15). Darwin: only the empty string is allowed, otherwise ErrAdapterInvalidID. Windows: always an error.
func CloseAdapter() error                // calls global device.Stop() then sets it to nil. Does not drop existing connections/scans.

type ScanResult struct {
    Address     string
    LocalName   string
    RSSI        int16
    Connectable bool
}
func ScanVehicleBeacon(ctx context.Context, vin string) (*ScanResult, error)
func NewConnection(ctx context.Context, vin string) (*Connection, error)                                  // = NewConnectionFromScanResult(ctx, vin, nil)
func NewConnectionFromScanResult(ctx context.Context, vin string, target *ScanResult) (*Connection, error)

func IsAdapterError(err error) bool             // Linux: message contains "operation not permitted". Darwin/Windows: always false.
func AdapterErrorHelpMessage(err error) string  // Linux: setcap instructions. Otherwise: err.Error()
```

Notes:
- The adapter is a package-global singleton (`device`, `mu`). On Linux, calling `newDevice()` more than once fails, so it is reused.
- `NewConnectionFromScanResult` retries until `ctx` ends (for errors with retry=true). Returns immediately on `IsAdapterError` or `retry=false` (LocalName mismatch, `!Connectable` → `ErrMaxConnectionsExceeded`).
- Scan matching is an exact match `LocalName == VehicleLocalName(vin)`.
- MTU: on `ExchangeMTU(ble.MaxMTU)` success `blockLength = min(txMtu, 1024) - 3`, on failure `ble.DefaultMTU - 3`.
- Receive: the buffer is reset if the gap between chunks is 1 second (`rxTimeout`) or more. If the length > 1024 the buffer is discarded. If the inbox is full the message is dropped.

---

## pkg/connector/inet

```go
var MaxLatency = 10 * time.Second // default AllowedLatency (global variable, mutable)
var ErrVehicleNotAwake = protocol.NewError("vehicle unavailable: vehicle is offline or asleep", false, false)

func ReadWithContext(ctx context.Context, r io.Reader, p []byte) ([]byte, error) // reads until p is full or EOF, checking ctx cancellation

type HTTPError struct {
    Code    int
    Message string
}
func (e *HTTPError) Error() string          // http.StatusText(Code) if Message is empty
func (e *HTTPError) MayHaveSucceeded() bool // 4xx → false; 503 → false; otherwise (other 5xx etc.) → true
func (e *HTTPError) Temporary() bool        // 503, 504, 408, 421

func SendFleetAPICommand(ctx context.Context, client *http.Client, userAgent, authHeader string, url string, command interface{}) ([]byte, error)
func ValidTeslaDomainSuffix(domain string) bool // .tesla.com | .tesla.cn | .teslamotors.com

type Connection struct {
    UserAgent string // public field
    /* private: vin, client, serverURL, inbox, authHeader, lock, lastPoke */
}
func NewConnection(vin string, authHeader, serverURL, userAgent string) *Connection // serverURL is the hostname only (without "https://")
func (c *Connection) SendFleetAPICommand(ctx context.Context, endpoint string, command interface{}) ([]byte, error)
func (c *Connection) PreferredAuthMethod() connector.AuthMethod // AuthMethodHMAC
func (c *Connection) AllowedLatency() time.Duration              // MaxLatency
func (c *Connection) RetryInterval() time.Duration               // 1s
func (c *Connection) Receive() <-chan []byte
func (c *Connection) Close()                                     // inbox close + nil (idempotent)
func (c *Connection) VIN() string
func (c *Connection) Wakeup(ctx context.Context) error           // repeats POST api/1/vehicles/<vin>/wake_up every 10 s until state=="online" (retries only Temporary errors)
func (c *Connection) Send(ctx context.Context, buffer []byte) error // POST api/1/vehicles/<vin>/signed_command {"routable_message": base64}; response {"response": base64} goes to inbox
```

`SendFleetAPICommand` (function) rules:
- If `command` is `[]byte` it is used as the body as-is, otherwise `json.Marshal`.
- Headers: `User-Agent`, `Content-type: application/json`, `Authorization`, `Accept: */*`.
- The response body is read up to `connector.MaxResponseLength+1`; if exceeded, `NewError("response exceeds maximum length", true, true)`.
- Status codes: 200 → body; **422 → `protocol.ErrProtocolNotSupported`**; 503 → `ErrVehicleNotAwake`; 408 + "vehicle is offline" in body → `ErrVehicleNotAwake`; otherwise → `&HTTPError{Code, Message: body}`.
- The method `(*Connection).SendFleetAPICommand` extracts `use base URL: https://<host>` from the message of a 421 `HTTPError` with a regex and, if `ValidTeslaDomainSuffix`, updates `serverURL` (that request itself still returns an error; the new host is used from the next request).

---

## pkg/vehicle

### Core (`pkg/vehicle/vehicle.go`)

```go
var DefaultFlags = uint32(1 << universal.Flags_FLAG_ENCRYPT_RESPONSE) // = 2
var ErrNoFleetAPIConnection = errors.New("not connected to Fleet API")  // not referenced in current code
var ErrVehicleStateUnknown  = errors.New("could not determine vehicle state") // not referenced in current code

type Vehicle struct {
    Flags uint32 // request RoutableMessage.flags. Default DefaultFlags. If 0, response encryption is not requested.
    /* private: dispatcher, vin, conn, authMethod, keyAvailable */
}

func NewVehicle(conn connector.Connector, privateKey authentication.ECDHPrivateKey, sessionCache *cache.SessionCache) (*Vehicle, error)
func (v *Vehicle) SetMaxLatency(latency time.Duration) // ignored if <= 0
func (v *Vehicle) VIN() string
func (v *Vehicle) PrivateKeyAvailable() bool
func (v *Vehicle) Connect(ctx context.Context) error   // starts the dispatcher receive goroutine. Error if ctx expires.
func (v *Vehicle) SessionInfo(ctx context.Context, publicKey *ecdh.PublicKey, domain universal.Domain) (*signatures.SessionInfo, error) // one unauthenticated handshake request with an arbitrary public key. Used to check whether a key is enrolled.
func (v *Vehicle) StartSession(ctx context.Context, domains []universal.Domain) error // nil means VCSEC+Infotainment concurrently. ShouldRetry errors are retried at RetryInterval.
func (v *Vehicle) Disconnect()                          // dispatcher.Stop() then conn.Close(). conn.Close() may be deferred separately, but Disconnect comes first.
func (v *Vehicle) SendMessage(ctx context.Context, message *universal.RoutableMessage) (protocol.Receiver, error) // sends as-is with AuthMethodNone. For relaying messages signed by another party, e.g., cardless pairing.
func (v *Vehicle) Send(ctx context.Context, domain universal.Domain, payload []byte, auth connector.AuthMethod) ([]byte, error) // low-level. Retries until a terminal result. Returns the response's protobuf_message_as_bytes.
func (v *Vehicle) Wakeup(ctx context.Context) error     // REST wake_up if FleetAPIConnector, otherwise VCSEC RKE_ACTION_WAKE_VEHICLE
func (v *Vehicle) UpdateCachedSessions(c *cache.SessionCache) error
func (v *Vehicle) LoadCachedSessions(c *cache.SessionCache) error // errors.New("VIN not in cache") if the VIN is absent
```

- `NewVehicle`: both `privateKey` and `sessionCache` may be nil. If `sessionCache` has an entry for the VIN, it is loaded immediately (handshake skipped). `authMethod = conn.PreferredAuthMethod()`.
- ctx expiry in `Send` → `&CommandError{Err: ctx.Err(), PossibleSuccess: true, PossibleTemporary: true}` (not retryable, may have succeeded).
- Sending an authenticated command before `StartSession` → `ErrNoSession`.

### VCSEC domain commands (`pkg/vehicle/vcsec.go`, `security.go`, `state.go`, part of `actions.go`)

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
// (private wakeupRKE: RKE_ACTION_WAKE_VEHICLE, used by Wakeup)

// ClosureMoveRequest
func (v *Vehicle) ActuateTrunk(ctx context.Context) error // rearTrunk=MOVE
func (v *Vehicle) OpenTrunk(ctx context.Context) error    // rearTrunk=MOVE (same payload as ActuateTrunk)
func (v *Vehicle) CloseTrunk(ctx context.Context) error   // rearTrunk=CLOSE (some models)
func (v *Vehicle) OpenFrunk(ctx context.Context) error    // frontTrunk=MOVE (no remote close)
func (v *Vehicle) OpenTonneau(ctx context.Context) error  // tonneau=OPEN (Cybertruck)
func (v *Vehicle) CloseTonneau(ctx context.Context) error // tonneau=CLOSE
func (v *Vehicle) StopTonneau(ctx context.Context) error  // tonneau=STOP

// Keychain (WhitelistOperation). The public key must be ecdh.P256(), otherwise ErrInvalidPublicKey.
func (v *Vehicle) AddKey(ctx context.Context, publicKey *ecdh.PublicKey, isOwner bool, formFactor vcsec.KeyFormFactor) error // isOwner→ROLE_OWNER, otherwise ROLE_DRIVER
func (v *Vehicle) AddKeyWithRole(ctx context.Context, publicKey *ecdh.PublicKey, role keys.Role, formFactor vcsec.KeyFormFactor) error // addKeyToWhitelistAndAddPermissions + metadataForKey
func (v *Vehicle) RemoveKey(ctx context.Context, publicKey *ecdh.PublicKey) error // removePublicKeyFromWhitelist
func (v *Vehicle) SendAddKeyRequest(ctx context.Context, publicKey *ecdh.PublicKey, isOwner bool, formFactor vcsec.KeyFormFactor) error
func (v *Vehicle) SendAddKeyRequestWithRole(ctx context.Context, publicKey *ecdh.PublicKey, role keys.Role, formFactor vcsec.KeyFormFactor) error
// ↑ BLE only (ErrRequiresBLE if FleetAPIConnector). Sends vcsec.ToVCSECMessage{SignedMessage{SIGNATURE_TYPE_PRESENT_KEY}} directly via conn.Send without a RoutableMessage. Returns nil immediately after sending; approval is not guaranteed.

// Information requests (InformationRequest, AuthMethodNone)
func (v *Vehicle) KeySummary(ctx context.Context) (*vcsec.WhitelistInfo, error)                 // GET_WHITELIST_INFO
func (v *Vehicle) KeyInfoBySlot(ctx context.Context, slot uint32) (*vcsec.WhitelistEntryInfo, error) // GET_WHITELIST_ENTRY_INFO + slot
func (v *Vehicle) BodyControllerState(ctx context.Context) (*vcsec.VehicleStatus, error)      // GET_STATUS. Works over BLE even while Infotainment is asleep.
```

VCSEC response handling rules (`unmarshalVCSECResponse` + `readUntil`):
- No payload → empty `FromVCSECMessage` (treated as success).
- `nominalError` → `&NominalError{Details: &NominalVCSECError{...}}`.
- `commandStatus.operationStatus == WAIT` → `ErrBusy` (retry).
- `ERROR` + `whitelistOperationStatus.whitelistOperationInformation != NONE` → `&KeychainError{Code}`; `ERROR` + `signedMessageStatus == nil` → `ErrUnknown`.
- For RKE/Closure, the message with `commandStatus == nil` is terminal; for whitelist operations, the message with `whitelistOperationStatus` populated is terminal; for information requests, the first message is terminal.

### Infotainment domain commands

All of them send `carserver.Action{VehicleAction{...}}` via `Send(ctx, DomainInfotainment, payload, v.authMethod)` and check `carserver.Response.actionStatus`. If `result == OPERATIONSTATUS_ERROR`, returns `&NominalError{Details: NewError("car could not execute command: "+plain_text, false, false)}` ("unspecified error" if there is no text).

`pkg/vehicle/actions.go`:
```go
func (v *Vehicle) HonkHorn(ctx context.Context) error                                 // vehicleControlHonkHornAction
func (v *Vehicle) FlashLights(ctx context.Context) error                              // vehicleControlFlashLightsAction
func (v *Vehicle) ChangeSunroofState(ctx context.Context, sunroofLevel int32) error   // vehicleControlSunroofOpenCloseAction.absolute_level (no CLI/proxy)
func (v *Vehicle) CloseWindows(ctx context.Context) error                             // vehicleControlWindowAction.close
func (v *Vehicle) VentWindows(ctx context.Context) error                              // vehicleControlWindowAction.vent
func (v *Vehicle) ChargePortClose(ctx context.Context) error                          // chargePortDoorClose (same as CloseChargePort in charge.go)
func (v *Vehicle) ChargePortOpen(ctx context.Context) error                           // chargePortDoorOpen  (same as OpenChargePort in charge.go)
```

`pkg/vehicle/charge.go`:
```go
type ChargingPolicy int
const (
    ChargingPolicyOff ChargingPolicy = iota
    ChargingPolicyAllDays
    ChargingPolicyWeekdays
)
type ChargeSchedule = carserver.ChargeSchedule             // fields: Id, Name, DaysOfWeek, StartEnabled, StartTime, EndEnabled, EndTime, OneTime, Enabled, Latitude, Longitude
type PreconditionSchedule = carserver.PreconditionSchedule // fields: Id, Name, DaysOfWeek, PreconditionTime, OneTime, Enabled, Latitude, Longitude

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
func (v *Vehicle) ScheduleDeparture(ctx context.Context, departAt, offPeakEndTime time.Duration, preconditioning, offpeak ChargingPolicy) error // departAt ∉ [0,24h] → "invalid departure time"; converted to minutes
func (v *Vehicle) ScheduleCharging(ctx context.Context, enabled bool, timeAfterMidnight time.Duration) error // scheduledChargingAction{enabled, charging_time(minutes)}
func (v *Vehicle) ClearScheduledDeparture(ctx context.Context) error // scheduledDepartureAction{enabled:false}
func (v *Vehicle) SetLowPowerMode(ctx context.Context, enable bool) error            // low_power_mode_enforced error if low power is enforced
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

func (v *Vehicle) SetSeatCooler(ctx context.Context, level Level, seat SeatPosition) error // SeatFrontLeft/Right only; proto level = level+1
func (v *Vehicle) ClimateOn(ctx context.Context) error   // hvacAutoAction.power_on=true
func (v *Vehicle) ClimateOff(ctx context.Context) error  // hvacAutoAction.power_on=false
func (v *Vehicle) AutoSeatAndClimate(ctx context.Context, positions []SeatPosition, enabled bool) error // maps only SeatUnknown/FrontLeft/FrontRight, others ignored
func (v *Vehicle) ChangeClimateTemp(ctx context.Context, driverCelsius float32, passengerCelsius float32) error // hvacTemperatureAdjustmentAction + level.TEMP_MAX
func (v *Vehicle) SetSeatHeater(ctx context.Context, levels map[SeatPosition]Level) error // converted to a per-seat Void oneof; unknown values become UNKNOWN
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
    SeatSecondRowLeftBack           // 4  (seatback, some Model S)
    SeatSecondRowCenter             // 5
    SeatSecondRowRight              // 6
    SeatSecondRowRightBack          // 7
    SeatThirdRowLeft                // 8
    SeatThirdRowRight               // 9
)

func (v *Vehicle) Ping(ctx context.Context) error // ping{ping_id:1}. nil means online + key recognized. RoutableMessageError means online but rejected.
func (v *Vehicle) VolumeUp(ctx context.Context) error   // mediaUpdateVolume.volume_delta=+1
func (v *Vehicle) VolumeDown(ctx context.Context) error // volume_delta=-1
func (v *Vehicle) SetVolume(ctx context.Context, volume float32) error // outside [0,10] → "invalid volume (should be in [0, 10])"; volume_absolute_float
func (v *Vehicle) MediaNextTrack(ctx context.Context) error
func (v *Vehicle) MediaPreviousTrack(ctx context.Context) error
func (v *Vehicle) MediaNextFavorite(ctx context.Context) error
func (v *Vehicle) MediaPreviousFavorite(ctx context.Context) error
func (v *Vehicle) ToggleMediaPlayback(ctx context.Context) error // mediaPlayAction
func (v *Vehicle) ScheduleSoftwareUpdate(ctx context.Context, delay time.Duration) error // offset_sec
func (v *Vehicle) CancelSoftwareUpdate(ctx context.Context) error
func (v *Vehicle) GetNearbyCharging(ctx context.Context) error // getNearbyChargingSites{include_meta_data:true, radius:200, count:10}. Response data is discarded (returns only error). No CLI/proxy.
func (v *Vehicle) SetVehicleName(ctx context.Context, name string) error
```

`pkg/vehicle/security.go`:
```go
func IsValidPIN(pin string) bool // exactly 4 digits
var ErrInvalidPIN = errors.New("PIN codes must be four digits")

func (v *Vehicle) EnableValetMode(ctx context.Context, pin string) error  // checks IsValidPIN
func (v *Vehicle) DisableValetMode(ctx context.Context) error             // nil if the error message ends with "already off"
func (v *Vehicle) SetValetMode(ctx context.Context, on bool, valetPassword string) error // Deprecated
func (v *Vehicle) ResetValetPin(ctx context.Context) error
func (v *Vehicle) ResetPIN(ctx context.Context) error                     // Deprecated → ClearPINToDrive. vehicleControlResetPinToDriveAction
func (v *Vehicle) ClearPINToDrive(ctx context.Context) error              // vehicleControlResetPinToDriveAdminAction
func (v *Vehicle) SetPINToDrive(ctx context.Context, enabled bool, pin string) error // Fleet API only: ErrRequiresEncryption if not FleetAPIConnector
func (v *Vehicle) ActivateSpeedLimit(ctx context.Context, speedLimitPin string) error
func (v *Vehicle) DeactivateSpeedLimit(ctx context.Context, speedLimitPin string) error
func (v *Vehicle) ClearSpeedLimitPINAdminAction(ctx context.Context) error
func (v *Vehicle) SpeedLimitSetLimitMPH(ctx context.Context, speedLimitMPH float64) error
func (v *Vehicle) ClearSpeedLimitPIN(ctx context.Context, speedLimitPin string) error
func (v *Vehicle) SetSentryMode(ctx context.Context, state bool) error
func (v *Vehicle) SetGuestMode(ctx context.Context, enabled bool) error   // guestModeAction (VehicleState_GuestMode)
func (v *Vehicle) TriggerHomelink(ctx context.Context, latitude float32, longitude float32) error
func (v *Vehicle) EraseGuestData(ctx context.Context) error               // eraseUserDataAction (effective only in Guest Mode)

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
func (v *Vehicle) ParentalControlsEnableSetting(ctx context.Context, setting ParentalControlsSetting, enable bool) error // parental_controls_active error if active
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
func (v *Vehicle) GetState(ctx context.Context, category StateCategory) (*carserver.VehicleData, error) // unknown category → "unrecognized vehicle data category". Intended for BLE; over the internet, Fleet API vehicle_data is recommended.
```

---

## pkg/cache

```go
type SessionCache struct {
    MaxEntries int
    Vehicles   map[string][]dispatcher.CacheEntry `json:"vehicles"` // dispatcher.CacheEntry{CreatedAt time.Time `json:"created_at"`; Domain int `json:"domain"`; SessionInfo []byte `json:"data"`}
    /* lock sync.Mutex */
}
func New(maxEntries int) *SessionCache // 0 means unlimited
func Import(r io.Reader) (*SessionCache, error)
func ImportFromFile(filename string) (*SessionCache, error)
func (c *SessionCache) Export(w io.Writer) error
func (c *SessionCache) ExportToFile(filename string) error // O_RDWR|O_CREATE, 0644. Does not truncate an existing file (if shorter, trailing garbage may remain).
func (c *SessionCache) Update(vin string, sessions []dispatcher.CacheEntry) error // prefer Vehicle.UpdateCachedSessions
func (c *SessionCache) GetEntry(vin string) ([]dispatcher.CacheEntry, bool)      // for dispatcher internal use
```

Eviction: after `Update`, if `MaxEntries > 0 && len > MaxEntries`, the most recent `CreatedAt` of each VIN is compared and the single oldest VIN is deleted. The "use" time is the time `Dispatcher.Cache()` is called (`time.Now()`). The cache is bound to a specific private key; if used with a different key, the first command fails and the vehicle sends new session info, which recovers it. Exported files require access control.

---

## pkg/cli

### Constants and types (`pkg/cli/config.go`, `keyring.go`)

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
    FlagPrivateKey Flag = 4 // required to send vehicle commands
    FlagBLE        Flag = 8 // requires FlagVIN
    FlagAll        Flag = FlagVIN | FlagOAuth | FlagPrivateKey | FlagBLE
)
var (
    ErrNoKeySpecified        = errors.New("private key location not provided")
    ErrNoAvailableTransports = errors.New("no available transports (configuration must permit BLE and/or OAuth)")
    ErrKeyNotFound           = keyring.ErrKeyNotFound
)
var DomainsByName = map[string]protocol.Domain{"VCSEC": DomainVCSEC, "INFOTAINMENT": DomainInfotainment}
var DomainNames   = map[protocol.Domain]string{...reverse mapping...}
type DomainList []protocol.Domain // flag.Value. Set uppercases then looks up DomainsByName; on failure "unknown domain '<v>'"

// keyring.go (private constants, for understanding behavior)
// keyringServiceName = "com.tesla.auth"; keyringKeyService = "vehicleCommandKey"; keyringTokenService = "oauthtoken"; keyringDirectory = "~/.tesla_keys"

type Config struct {
    Flags            Flag
    KeyringKeyName   string
    KeyringTokenName string
    VIN              string
    BtAdapterID      string // Linux only
    TokenFilename    string
    KeyFilename      string
    CacheFilename    string
    DisableCache     bool
    Backend          keyring.Config
    BackendType      backendType // flag.Value; String() is AllowedBackends[0] or keyring.InvalidBackend
    Debug            bool
    Domains          DomainList
    /* private: password, sessions, acct, skey, oauthToken */
}
```

### Methods

```go
func NewConfig(flags Flag) (*Config, error) // Backend: ServiceName "com.tesla.auth", KeychainTrustApplication true, KeyCtlScope "user", password function getPassword. Always err == nil.
func (c *Config) RegisterCommandLineFlags()
func (c *Config) LoadCredentials() error   // calls token() if FlagOAuth and PrivateKey() if FlagPrivateKey ahead of time (keeps keyring password prompts outside timeouts)
func (c *Config) ReadFromEnvironment()     // fills only empty fields from environment variables. Call after flag.Parse().
func (c *Config) UpdateCachedSessions(v *vehicle.Vehicle) // no-op if CacheFilename is empty or sessions is nil
func (c *Config) PrivateKey() (skey protocol.ECDHPrivateKey, err error) // cached. File first, then keyring. If no location is specified, (nil, ErrNoKeySpecified). loadCache() is also performed here.
func (c *Config) Connect(ctx context.Context) (acct *account.Account, car *vehicle.Vehicle, err error)
func (c *Config) Account() (*account.Account, error)                     // account.New(token, "")
func (c *Config) SavePrivateKey(skey protocol.ECDHPrivateKey) error       // keyring first, else file, ErrNoKeySpecified if neither
func (c *Config) ConnectRemote(ctx context.Context, skey protocol.ECDHPrivateKey) (acct *account.Account, car *vehicle.Vehicle, err error) // creates car only if FlagVIN && VIN != ""
func (c *Config) ConnectLocal(ctx context.Context, skey protocol.ECDHPrivateKey) (car *vehicle.Vehicle, err error) // ble.InitAdapterWithID(BtAdapterID) → ble.NewConnection → vehicle.NewVehicle(conn, skey, c.sessions)
func (c *Config) LoadTokenFromKeyring() (string, error)  // key "oauthtoken.<KeyringTokenName>"
func (c *Config) SaveTokenToKeyring(token string) error
func (c *Config) LoadKeyFromKeyring() (protocol.ECDHPrivateKey, error) // key "vehicleCommandKey.<KeyringKeyName>", 32-byte scalar
func (c *Config) DeletePrivateKey() error                // deletes only the keyring entry
```

Flags registered by `RegisterCommandLineFlags`:

| Flag | Condition | Default | Description |
|---|---|---|---|
| `-vin` | FlagVIN | "" | Defaults to $TESLA_VIN |
| `-session-cache` | FlagPrivateKey | "" | session cache file |
| `-disable-session-cache` | FlagPrivateKey | false | |
| `-key-name` | FlagPrivateKey | "" | keyring name |
| `-key-file` | FlagPrivateKey | "" | PEM file |
| `-domain` | FlagPrivateKey | (none) | repeatable. vcsec / infotainment |
| `-token-name` | FlagOAuth | "" | |
| `-token-file` | FlagOAuth | "" | |
| `-keyring-type` | FlagOAuth or FlagPrivateKey | (unset) | one of `keyring.AvailableBackends()` |
| `-keyring-file-dir` | FlagOAuth or FlagPrivateKey | `~/.tesla_keys` | file keyring directory |
| `-keyring-debug` | FlagOAuth or FlagPrivateKey | false | |
| `-bt-adapter` | Linux and FlagBLE | "" | hciN |

`Connect` decision rules:
1. If VIN, KeyringTokenName, and TokenFilename are all empty, error "must provide VIN and/or OAuth token".
2. If `PrivateKey()` returns an error other than `ErrNoKeySpecified`, return it.
3. If `FlagOAuth` and a token location is set, `ConnectRemote`; otherwise if `FlagBLE|FlagVIN`, `ConnectLocal`; otherwise `ErrNoAvailableTransports`.
4. If car exists, `car.Connect(ctx)`; if skey exists, `car.StartSession(ctx, c.Domains)`.

---

## pkg/account

```go
type Account struct {
    UserAgent  string // buildUserAgent(app)
    Host       string // Fleet API host (updated by the proxy on 421)
    Subject    string // OAuth "sub"
    /* private: authHeader, client */
}
func New(oauthToken, userAgent string) (*Account, error)
func (a *Account) GetVehicle(_ context.Context, vin string, privateKey authentication.ECDHPrivateKey, sessions *cache.SessionCache) (*vehicle.Vehicle, error) // inet.NewConnection(vin, authHeader, Host, UserAgent) → vehicle.NewVehicle. ctx unused.
func (a *Account) Get(ctx context.Context, endpoint string) ([]byte, error)  // GET https://<Host>/<endpoint>. Error if not 200. Body limited to MaxResponseLength.
func (a *Account) Post(ctx context.Context, endpoint string, data []byte) ([]byte, error) // POST via inet.SendFleetAPICommand (data is used as the body as-is)
func (a *Account) SendVehicleFleetAPICommand(ctx context.Context, vin, endpoint string, command interface{}) ([]byte, error) // api/1/vehicles/<vin>/<endpoint>
func (a *Account) UpdateKey(ctx context.Context, publicKey *ecdh.PublicKey, name string) error // POST api/1/users/keys {public_key(hex), kind:"mobile_device", model:"3rd Party Application", name, tag:UserAgent}
```

`New` rules:
- Splits the token on `.` into 3 parts, decodes the second part with `base64.RawStdEncoding`, and parses only `aud`, `ou_code`, `sub`. On failure, "client provided malformed OAuth token".
- Host selection: `remappedDomains` (for development, empty) → from `aud`, an entry that does not start with `https://auth.tesla.`, is `[A-Za-z0-9-.]+` after stripping `https://`, satisfies `ValidTeslaDomainSuffix`, and starts with `fleet-api.`. A candidate containing `.<ou_code lowercased>.` is adopted immediately. If none, `defaultDomain = "fleet-api.prd.na.vn.cloud.tesla.com"`.
- `authHeader = "Bearer " + strings.TrimSpace(token)`.

`buildUserAgent(app)`: `"<app> tesla-sdk/<pkg/account/version.txt>"`. If `app` is empty, the last element of the module path from `debug.ReadBuildInfo()` + version (or 8 characters of vcs.revision).

---

## pkg/proxy

```go
const (
    DefaultTimeout    = 10 * time.Second
    MaxResponseLength = 10000000 // forwardRequest response limit (10 MB)
    MaxAttempts       = 2        // number of 421 Alt-Svc retries
)
// private: maxRequestBodyBytes = 1<<20, vinLength = 17, proxyProtocolVersion = "tesla-http-proxy/1.1.0", h2Prefix = "h2=https://"

var ErrCommandNotImplemented = errors.New("command not implemented")          // remote_boombox
var ErrCommandUseRESTAPI     = errors.New("command requires using the REST API") // navigation_request, set_managed_* → falls back to forwardRequest

type Proxy struct {
    Timeout time.Duration // context timeout for commands/forwarding
    /* private: commandKey, sessions, vinLock, unsupported, domainForSubject, client, fetchVehicle */
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

The (private) parameter accessors of `ExtractCommandAction` strictly check JSON types: if not a string/boolean/number (float64), `&NominalError{"invalid <key> param"}`; if a required key is missing, `&NominalError{"missing <key> param"}`. Details in [07-http-proxy.md](07-http-proxy.md); endpoint table in [05-command-catalog.md](05-command-catalog.md).

---

## pkg/sign

```go
func SignMessageForVehicle(privateKey authentication.ECDHPrivateKey, vin, app string, message jwt.MapClaims) (string, error) // aud = "com.tesla.vehicle.<vin>.<app>"
func SignMessageForFleet(privateKey authentication.ECDHPrivateKey, app string, message jwt.MapClaims) (string, error)       // aud = "com.tesla.fleet.<app>"
```

Both delegate to `internal/authentication.SignMessage` and overwrite the `aud` and `iss` claims. The algorithm is Schnorr/P-256 (`alg` header `authentication.TeslaSchnorrSHA256`), and `iss` is the base64-encoded public key. The proxy's `fleet_telemetry_config` uses `SignMessageForFleet(key, "TelemetryClient", config)`.

---

## internal package overview (not importable)

| Package | Role | Types exposed through the public API |
|---|---|---|
| `internal/authentication` | ECDH key agreement, metadata TLV, HMAC/AES-GCM signing (`Signer`) and verification (`Verifier`), session info import/export, sliding-window anti-replay, Schnorr JWT | `ECDHPrivateKey`(→`protocol.ECDHPrivateKey`), `Session`(→`protocol.Session`), `NativeECDHKey` (type assertion when saving keys), `ErrInvalidPublicKey` |
| `internal/dispatcher` | RoutableMessage send/receive on top of `Connector`, per-domain `session`, request-response matching (`receiverKey{address,uuid,domain}`), automatic session refresh, response decryption | `CacheEntry` (value type of `cache.SessionCache.Vehicles`) |
| `internal/log` | Global leveled logger (stderr). `SetLevel(LevelDebug)` enables TX/RX hex dumps | None (used directly by CLI tools) |
| `internal/schnorr` | P-256 Schnorr signatures (for JWS) | None |

---

## Verification checklist

- [ ] If you made a change that alters a signature in this document, you cross-checked it against the actual code with e.g. `grep -nE '^func \(v \*Vehicle\)' pkg/vehicle/*.go`.
- [ ] If you added a new sentinel error, you deliberately chose whether it implements `protocol.Error` (`NewError` vs `errors.New`) and its effect on `ShouldRetry` is correct.
- [ ] If you implemented a new `Connector`, it satisfies `PreferredAuthMethod`, `AllowedLatency`, `RetryInterval`, idempotent `Close`, and thread-safe `Send/Receive`, and if HTTP-based it also implements `FleetAPIConnector`.
- [ ] If you added a command method to `Vehicle`, you followed the "Checklist of Files to Modify When Adding a New Command" in [05-command-catalog.md](05-command-catalog.md).
- [ ] `go build ./... && go vet ./... && go test ./...` passes (`check-all.sh` or `make test`).
