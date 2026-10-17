# 08. Error Model, Retry Policy, Troubleshooting

The SDK's error type hierarchy, the full table of protocol/application-layer error codes, the policy of each retry loop, automatic session desync recovery, a troubleshooting matrix, and debugging procedures.

Related files (relative to repository root)

- `pkg/protocol/error.go` — `Error` interface, sentinel errors, `CommandError`, `RoutableMessageError`, `NominalError`, `KeychainError`, `GetError`, `ShouldRetry`
- `pkg/protocol/protobuf/universal_message.proto` — `MessageFault_E`, `OperationStatus_E`
- `pkg/protocol/protobuf/errors.proto` — `GenericError_E`, `NominalError`
- `pkg/protocol/protobuf/vcsec.proto` — `WhitelistOperation_information_E`, `SignedMessage_information_E`, `CommandStatus`
- `pkg/protocol/protobuf/signatures.proto` — `Session_Info_Status`
- `pkg/connector/inet/inet.go` — `HTTPError`, `ErrVehicleNotAwake`, HTTP status code mapping
- `pkg/connector/ble/ble.go`, `device_linux.go` — BLE sentinels, `IsAdapterError`
- `pkg/vehicle/vehicle.go`, `vcsec.go`, `infotainment.go` — retry loops, response interpretation
- `internal/dispatcher/dispatcher.go`, `session.go` — session updates, response routing, replay drops
- `internal/authentication/signer.go` — `UpdateSessionInfo` (counter/epoch update rules)
- `internal/log/logger.go` — log levels
- `pkg/proxy/proxy.go` — HTTP status code conversion (`httpStatusCode`)

---

## 1. Error type hierarchy

### 1.1 `protocol.Error` interface

```go
// pkg/protocol/error.go
type Error interface {
	error
	MayHaveSucceeded() bool // could the vehicle have received and executed the command (e.g. no response received)
	Temporary() bool        // is this a transient condition (could a retry resolve it)
}
```

The combination of the two booleans decides whether to retry. **If `MayHaveSucceeded()==true`, never retry** (the same command could be executed twice).

### 1.2 Implementations

| Type | Fields | `MayHaveSucceeded` | `Temporary` | `Error()` string | Created at |
|---|---|---|---|---|---|
| `*protocol.CommandError` | `Err error`, `PossibleSuccess bool`, `PossibleTemporary bool` | `PossibleSuccess` | `PossibleTemporary` | `Err.Error()` (supports Unwrap) | `protocol.NewError(msg, mayHaveSucceeded, temporary)` and constructed directly in each layer |
| `*protocol.RoutableMessageError` | `Code universal.MessageFault_E` | `Code == MESSAGEFAULT_ERROR_NONE` or `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED` | `Code` is one of the 8 `retriableErrors` below | `MessageFault_E_name[Code]` (e.g. `MESSAGEFAULT_ERROR_BUSY`); for unregistered values `unrecognized error code N` | `protocol.GetError` |
| `*protocol.NominalError` | `Details error` | `MayHaveSucceeded(Details)` | `Temporary(Details)` | `Details.Error()` (supports Unwrap) | Vehicle passed authentication but refused execution. Infotainment: `infotainment.go getCarServerResponse`; VCSEC: `vcsec.go unmarshalVCSECResponse`; proxy parameter errors: `proxy/command.go` |
| `*protocol.NominalVCSECError` | `Details *errors.NominalError` (protobuf) | `false` | `false` | `vcsec could not execute command: <Details.String()>` (when GenericError != NONE) / `...: GENERICERROR_NONE` | `vcsec.go unmarshalVCSECResponse`; always wrapped as `NominalError{Details: &NominalVCSECError{...}}` |
| `*protocol.KeychainError` | `Code vcsec.WhitelistOperationInformation_E` | `false` | `false` | `keychain operation failed: <Code>` | `vcsec.go unmarshalVCSECResponse`, `isWhitelistOperationComplete` |
| `*inet.HTTPError` | `Code int`, `Message string` | 4xx → `false`; otherwise `Code != 503` | `Code ∈ {503, 504, 408, 421}` | `http.StatusText(Code)` if `Message` is empty | `inet.SendFleetAPICommand` (all statuses other than the specially handled 200/422/503/408), `proxy/command.go` (400 invalid_command, 413) |
| `authentication.Error` (`internal`) | `Code MessageFault_E`, `Info string` | (not implemented) | (not implemented) | Format `IncorrectEpoch: <info>` (CamelCase code) | `internal/authentication`; exposed externally only as `protocol.ErrInvalidPublicKey` |

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

### 1.3 Helper function semantics

| Function | Behavior |
|---|---|
| `protocol.MayHaveSucceeded(err) bool` | `errors.As(err, &protocol.Error)` succeeds && `MayHaveSucceeded()`. Follows wrapped errors too. |
| `protocol.Temporary(err) bool` | `errors.As` succeeds && `Temporary()`. |
| `protocol.ShouldRetry(err) bool` | `err == nil` → `false`. Not a `protocol.Error` → `false`. **If `MayHaveSucceeded()` is `true`, `false` regardless of `Temporary()`**. Otherwise `Temporary()`. |
| `protocol.IsNominalError(err) bool` | `errors.As(err, &*NominalError)`. Used by the proxy to distinguish "vehicle refused" (HTTP 200 + `{"response":{"result":false,"reason":...}}`) from "server/transport error". |
| `protocol.GetError(u *universal.RoutableMessage) error` | See 1.4 below |

### 1.4 `GetError` conversion rules (protocol layer → Go error)

Evaluated in order. `pkg/protocol/error.go`.

1. `u.signedMessageStatus.signed_message_fault != MESSAGEFAULT_ERROR_NONE`
   - `MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID` → `protocol.ErrKeyNotPaired` (replaced with a friendlier message)
   - Otherwise → `&RoutableMessageError{Code: fault}`
2. If a `u.session_info` payload is present, parse it as `signatures.SessionInfo`
   - Parse failure → `ErrBadResponse`
   - `status == SESSION_INFO_STATUS_OK` → continue
   - `status == SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST` → `ErrKeyNotPaired`
   - Otherwise → `ErrUnknown`
3. `u.signedMessageStatus.operation_status`
   - `OPERATIONSTATUS_OK` → `nil`
   - `OPERATIONSTATUS_WAIT` → `ErrBusy`
   - `OPERATIONSTATUS_ERROR` → **`nil`** (the case body is empty, so it falls out of the switch to `return nil`; an ERROR with fault NONE is treated as success at the protocol layer and left to application-layer parsing)
   - Undefined value → `ErrUnknown`

Note: `GetError` only looks at the `RoutableMessage`. Application errors inside the payload (`FromVCSECMessage`, `CarServer.Response`) are interpreted separately in §3.

---

## 2. Full table of sentinel errors

MHS = `MayHaveSucceeded`, T = `Temporary`. `-` denotes a plain `error` that does not implement `protocol.Error` (→ `ShouldRetry` always false).

### 2.1 `pkg/protocol/error.go`

| Name | Message | MHS | T | Raised at | Remedy |
|---|---|---|---|---|---|
| `ErrBusy` | `vehicle busy or finishing wake-up` | false | true | `GetError` (operation_status WAIT), `vcsec.go` (VCSEC `OPERATIONSTATUS_WAIT`) | Retried automatically. Give the ctx a sufficient timeout (20–30s including wake-up) |
| `ErrUnknown` | `vehicle responded with an unrecognized status code` | false | false | `GetError`, `vcsec.go` (ERROR + signedMessageStatus nil) | Firmware is newer than the SDK. Update the SDK |
| `ErrNotConnected` | `vehicle not connected` | false | false | `dispatcher.Send` (before Start / after Stop), `inet.Send` (after Close) | Call `car.Connect(ctx)` first; do not reuse after `Disconnect` |
| `ErrNoSession` | `cannot send authenticated command before establishing a vehicle session` | false | false | `dispatcher.Send` (auth != None but no session / session not ready for that domain) | Call `car.StartSession(ctx, domains)`; if only a subset of domains was opened, only those domains are usable |
| `ErrRequiresKey` | `no private key available` | false | false | `dispatcher.RequestSessionInfo` (privateKey nil) | Pass a private key to `NewVehicle` |
| `ErrInvalidPublicKey` | `BadParameter: invalid public key` | - | - | `AddKeyWithRole`/`RemoveKey`/`SendAddKeyRequestWithRole` (not P-256), `LoadPublicKey`, ECDH | Verify it is a 65-byte uncompressed P-256 public key |
| `ErrKeyNotPaired` | `vehicle rejected request: your public key has not been paired with the vehicle` | false | false | `GetError` | Pair the key (`add-key-request` + NFC). Proxy returns HTTP 412 |
| `ErrUnpexpectedPublicKey` (typo in original) | `remote public key changed unexpectedly` | - | - | Declared only (no return site in current code) | - |
| `ErrBadResponse` | `invalid response` | - | - | `GetError` (SessionInfo parse failure), `Vehicle.SessionInfo` (no session_info), `vcsec.go` (`%w` wrapped, MHS=true) | Vehicle/firmware incompatibility or transport corruption. Inspect RX hex with `-debug` |
| `ErrProtocolNotSupported` | `vehicle does not support protocol -- use REST API` | - | - | `inet.SendFleetAPICommand` (HTTP 422) | Pre-2021 Model S/X. Use Fleet API REST commands. The proxy marks the VIN unsupported and forwards to REST |
| `ErrRequiresBLE` | `command can only be sent over BLE` | - | - | `SendAddKeyRequestWithRole` (conn is a `FleetAPIConnector`) | Run with `-ble` |
| `ErrRequiresEncryption` | `command should not be sent in plaintext or encrypted with an unauthenticated public key` | - | - | `SetPINToDrive` (when conn is not a `FleetAPIConnector`) | PIN setup only via the Fleet API path |
| `ErrNoDecryptionContext` | `could not decrypt vehicle response without a session` | - | - | `dispatcher.decrypt` (encrypted response but no domain session) | Only logged; message is dropped. Check session cache/key |
| `ErrReplayedResponse` | `received vehicle response with duplicate counter` | - | - | `session.decrypt` (sliding window failure) | Normal (retransmission duplicate). Message is dropped |

### 2.2 Other packages

| Name | Package | Message | MHS | T | Raised at | Remedy |
|---|---|---|---|---|---|---|
| `ErrAdapterInvalidID` | `connector/ble` | `the bluetooth adapter ID is invalid` | false | false | `InitAdapterWithID` (Linux: not `hci0`–`hci15`; macOS: specifying an ID is not possible at all) | Linux only: `-bt-adapter hciN` |
| `ErrMaxConnectionsExceeded` | `connector/ble` | `the vehicle is already connected to the maximum number of BLE devices` | false | false | `tryToConnect` (advertisement `Connectable == false`) | VCSEC allows at most 3 BLE connections (including key fobs/phone keys). Disconnect other devices |
| `ErrVehicleNotAwake` | `connector/inet` | `vehicle unavailable: vehicle is offline or asleep` | false | false | `SendFleetAPICommand` (HTTP 503, or 408 + `vehicle is offline` in body) | Retry after `car.Wakeup(ctx)`. Proxy returns HTTP 408 |
| `ErrNoFleetAPIConnection` | `vehicle` | `not connected to Fleet API` | - | - | Declared only | - |
| `ErrVehicleStateUnknown` | `vehicle` | `could not determine vehicle state` | - | - | Declared only | - |
| `ErrInvalidPIN` | `vehicle` | `PIN codes must be four digits` | - | - | `SetValetMode(on)`, `ParentalControlsActivate/Deactivate` | 4-digit numeric string |
| `ErrNoKeySpecified` | `cli` | `private key location not provided` | - | - | `Config.PrivateKey`, `SavePrivateKey` | `-key-name`/`-key-file` or `TESLA_KEY_NAME`/`TESLA_KEY_FILE` |
| `ErrNoAvailableTransports` | `cli` | `no available transports (configuration must permit BLE and/or OAuth)` | - | - | `Config.Connect` | Include `FlagBLE|FlagVIN` or `FlagOAuth` in `Flags` |
| `ErrKeyNotFound` | `cli` (= `keyring.ErrKeyNotFound`) | (keyring library message) | - | - | No entry in the keyring | `tesla-keygen create` / `tesla-auth-token` |
| `ErrCommandNotImplemented` | `proxy` | `command not implemented` | - | - | `ExtractCommandAction` (`remote_boombox`) | - |
| `ErrCommandUseRESTAPI` | `proxy` | `command requires using the REST API` | - | - | `ExtractCommandAction` (`navigation_request`, `set_managed_*`) | Proxy forwards to REST automatically |
| `ErrInvalidPrivateKey` | `internal/authentication` | `invalid private key` (`%w` wrapped: `: expected PEM encoding`, `: only elliptic curve keys supported`, `: only NIST-P256 keys supported`) | - | - | `LoadExternalECDHKey` (= `protocol.LoadPrivateKey`) | PEM `EC PRIVATE KEY` or PKCS8 `PRIVATE KEY`, P-256 |
| `ErrMetadataFieldTooLong` | `internal/authentication` | `metadata fields can't be more than 255 bytes long` | - | - | Metadata such as VIN > 255B | - |
| `ErrCommandLineArgs` | `cmd/tesla-control` | `invalid command line arguments` | - | - | Wrong argument count/value → prints per-command Usage | - |
| `ErrRequiresOAuth` / `ErrRequiresVIN` / `ErrRequiresPrivateKey` / `ErrUnknownCommand` | `cmd/tesla-control` | `command requires a FleetAPI OAuth token` / `command requires a VIN` / `command requires a private key` / `unrecognized command` | - | - | `checkReadiness` | See chapter 6 |

### 2.3 `CommandError`s created inline (identified by message)

| Message | MHS | T | Location | Meaning |
|---|---|---|---|---|
| `<ctx.Err()>` (`context deadline exceeded`, etc.) | true | true | `Vehicle.trySend`, `vcsec.readUntil` | Timed out waiting for a response after sending the command. **The vehicle may have executed it** → `ShouldRetry` false |
| `<ctx.Err()>` | false | true | `dispatcher.Send` transport retry loop | Timed out with the send itself failing. Not executed |
| `cannot send message without a destination domain` | false | false | `dispatcher.Send` | `ToDestination.domain` is `DOMAIN_BROADCAST(0)` |
| `payload missing from vehicle response` | true | false | `vcsec.unmarshalVCSECResponse` | Payload oneof is not bytes |
| `invalid response: <proto err>` | true | false | `vcsec.unmarshalVCSECResponse` | `FromVCSECMessage` parse failure |
| `unable to parse vehicle response: <err>` | true | false | `infotainment.getCarServerResponse` | `CarServer.Response` parse failure |
| `car could not execute command: <plain_text or "unspecified error">` | false | false | `infotainment.getCarServerResponse` (wrapped in NominalError) | Infotainment application refusal |
| `<http err>` | false | true | `inet.SendFleetAPICommand` (request creation / `client.Do` failure) | Network error |
| `<read err>` | true | false | `inet.SendFleetAPICommand` (body read failure) | Server may have processed it |
| `response exceeds maximum length` | true | true | `inet.SendFleetAPICommand` (> `connector.MaxResponseLength`=100000), `proxy.forwardRequest` (> 10000000) | - |
| `unable to parse server response: <err>` | true | false | `inet.Send` | Not `{"response": ...}` JSON |
| `dropped response because inbox is full` | true | false | `inet.Send` | inbox(5) full. Consecutive sends without consuming responses |
| `max retry exhausted` | false | false | `proxy.forwardRequest` | More than 2 retries on 421 |

---

## 3. Protocol/application-layer error codes

### 3.1 `UniversalMessage.MessageFault_E` (0–28)

The "Retry" column is based on `RoutableMessageError.Temporary()` (membership in retriableErrors). The MHS column is `MayHaveSucceeded()`.

| Value | Name (`MESSAGEFAULT_ERROR_` omitted) | Meaning (translated proto comment) | Retry | MHS | Client remedy |
|---|---|---|---|---|---|
| 0 | `NONE` | Success | - | true | - |
| 1 | `BUSY` | Required vehicle subsystem is busy. Try again | O | false | Retried automatically. Common right after wake-up |
| 2 | `TIMEOUT` | Vehicle subsystem did not respond. Try again | O | false | Retried automatically |
| 3 | `UNKNOWN_KEY_ID` | Vehicle does not recognize the key that authorized the command. Check key pairing | X | false | `GetError` replaces it with `ErrKeyNotPaired`. Pairing required |
| 4 | `INACTIVE_KEY` | The key that authorized the command is inactive | X | false | Check key status on the vehicle Locks screen; re-pair |
| 5 | `INVALID_SIGNATURE` | Signature/MAC mismatch. Update session with the enclosed session info and retry | O | false | Automatic: update with the session_info enclosed in the response, then resend. If persistent, delete the cache |
| 6 | `INVALID_TOKEN_OR_COUNTER` | Anti-replay counter already used. Update session and retry | O | false | Automatic update. Recurs if multiple processes use the same key+cache concurrently → separate cache per process or serialize per VIN |
| 7 | `INSUFFICIENT_PRIVILEGES` | User lacks permission to execute the command, due to role or vehicle state | X | false | Check key role (`list-keys`). Fleet Manager cannot send BLE commands (2023.38+). Check vehicle state (e.g. driving) |
| 8 | `INVALID_DOMAINS` | Malformed command or unrecognized domain. Client error or old firmware | X | false | Check `to_destination.domain`; update firmware |
| 9 | `INVALID_COMMAND` | Unrecognized command. Client error or unsupported firmware | X | false | Check that the firmware supports the Action |
| 10 | `DECODING` | Failed to parse command. Client error | X | false | Check payload protobuf encoding (`protoc --decode`) |
| 11 | `INTERNAL` | Vehicle internal error. Try again. Common when boot is not complete | O | false | Retried automatically |
| 12 | `WRONG_PERSONALIZATION` | Command signed for a different VIN | X | false | Exact 17-character VIN in `TAG_PERSONALIZATION` |
| 13 | `BAD_PARAMETER` | Malformed command or deprecated parameter | X | false | Check argument ranges |
| 14 | `KEYCHAIN_IS_FULL` | Keychain is full. A key must be removed before adding | X | false | Clean up via vehicle UI or `remove-key` |
| 15 | `INCORRECT_EPOCH` | Session ID (epoch) mismatch. Update session and retry | O | false | Automatic update (common after Infotainment reboot) |
| 16 | `IV_INCORRECT_LENGTH` | Wrong IV length (12 bytes for AES-GCM). Client programming error | X | false | 12-byte nonce |
| 17 | `TIME_EXPIRED` | Command expired. Check clock sync with the enclosed session info and retry | O | false | Automatic update. If persistent, check `AllowedLatency`/clock |
| 18 | `NOT_PROVISIONED_WITH_IDENTITY` | Vehicle has no VIN provisioned. Service required | X | false | Tesla Service |
| 19 | `COULD_NOT_HASH_METADATA` | Vehicle internal error | X | false | - |
| 20 | `TIME_TO_LIVE_TOO_LONG` | Expiration time too far in the future (security measure) | O | false | The ctx deadline becomes the `expires_at` lifetime. Keep the command ctx short (default 5s) |
| 21 | `REMOTE_ACCESS_DISABLED` | Owner disabled Mobile access | X | false | Vehicle UI: Controls > Safety > Allow Mobile Access |
| 22 | `REMOTE_SERVICE_ACCESS_DISABLED` | Authorized by a Service key but remote service commands are not allowed | X | false | - |
| 23 | `COMMAND_REQUIRES_ACCOUNT_CREDENTIALS` | A command requiring Tesla account credentials was sent over a channel without them. Resend via Fleet API | X | false | Send over the internet path, not BLE |
| 24 | `REQUEST_MTU_EXCEEDED` | Request fields exceed the MTU | X | false | Reduce payload size |
| 25 | `RESPONSE_MTU_EXCEEDED` | Request was received but the response exceeds the MTU | X | **true** | The command was executed. For state queries, use a smaller category |
| 26 | `REPEATED_COUNTER` | (no proto comment) Repeated counter | X | false | Update session and resend (manually) |
| 27 | `INVALID_KEY_HANDLE` | (no proto comment) Invalid key handle | X | false | Use `signer_identity.public_key` (the SDK does not use handles) |
| 28 | `REQUIRES_RESPONSE_ENCRYPTION` | (no proto comment) Response encryption required | X | false | Set `FLAG_ENCRYPT_RESPONSE` (bit 1 → value 2) in `flags`. SDK default `vehicle.DefaultFlags` |

### 3.2 `UniversalMessage.OperationStatus_E` (RoutableMessage layer)

| Value | Name | `GetError` result |
|---|---|---|
| 0 | `OPERATIONSTATUS_OK` | `nil` |
| 1 | `OPERATIONSTATUS_WAIT` | `ErrBusy` (retry) |
| 2 | `OPERATIONSTATUS_ERROR` | `nil` if fault is NONE (§1.4) |

### 3.3 `Signatures.Session_Info_Status`

| Value | Name | Result |
|---|---|---|
| 0 | `SESSION_INFO_STATUS_OK` | Normal |
| 1 | `SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST` | `ErrKeyNotPaired` |

### 3.4 `Errors.GenericError_E` (VCSEC `nominalError`)

| Value | Name | Meaning |
|---|---|---|
| 0 | `GENERICERROR_NONE` | None |
| 1 | `GENERICERROR_UNKNOWN` | Unknown |
| 2 | `GENERICERROR_CLOSURES_OPEN` | Door/trunk open (cannot lock, etc.) |
| 3 | `GENERICERROR_ALREADY_ON` | Already on |
| 4 | `GENERICERROR_DISABLED_FOR_USER_COMMAND` | Disabled for user commands |
| 5 | `GENERICERROR_VEHICLE_NOT_IN_PARK` | Vehicle not in Park |
| 6 | `GENERICERROR_UNAUTHORIZED` | Unauthorized |
| 7 | `GENERICERROR_NOT_ALLOWED_OVER_TRANSPORT` | Not allowed over this transport |

In Go this is returned as `*protocol.NominalError{Details: &protocol.NominalVCSECError{Details: <NominalError proto>}}`. To check:

```go
var vErr *protocol.NominalVCSECError
if errors.As(err, &vErr) {
	code := vErr.Details.GetGenericError() // verror.GenericError_E
}
```

### 3.5 VCSEC `OperationStatus_E` + `CommandStatus` interpretation rules (`vcsec.go unmarshalVCSECResponse`)

1. `protocol.GetError(message)` != nil → return it as is.
2. No payload (`Payload == nil`) → empty `FromVCSECMessage` (treated as success).
3. `FromVCSECMessage` parse failure → `CommandError{wraps ErrBadResponse, MHS=true}`.
4. `nominalError` present → `NominalError{NominalVCSECError}`.
5. `commandStatus.operationStatus`:
   - `OPERATIONSTATUS_OK` → pass
   - `OPERATIONSTATUS_WAIT` → `ErrBusy` (Temporary → resend the whole command)
   - `OPERATIONSTATUS_ERROR`:
     - `whitelistOperationStatus.whitelistOperationInformation != NONE` → `KeychainError{Code}`
     - `signedMessageStatus == nil` → `ErrUnknown`
     - Otherwise → pass (legacy `signedMessageStatus` is ignored)
6. Termination check (`isTerminalTest`): for RKE/Closure, the message with `commandStatus == nil` is terminal; for whitelist operations, the message containing `whitelistOperationStatus` is terminal (`NONE` means success, otherwise `KeychainError`); for information requests, the first message is terminal.

### 3.6 Infotainment `CarServer.ActionStatus` interpretation (`infotainment.go getCarServerResponse`)

- `Response.actionStatus.result == OPERATIONSTATUS_ERROR` → `NominalError{Details: NewError("car could not execute command: " + result_reason.plain_text, false, false)}`. If `plain_text` is empty, `unspecified error`.
- Note: `CarServer.OperationStatus_E` has `OPERATIONSTATUS_OK=0`, `OPERATIONSTATUS_ERROR=1`, which differ in value from `UniversalMessage.OperationStatus_E` (OK=0, WAIT=1, ERROR=2) and `VCSEC.OperationStatus_E` (OK=0, WAIT=1, ERROR=2). In Go the types differ so mixing them is a compile error, but when porting to another language do not mix up the numeric values.
- Otherwise → success. Returns `Response.vehicleData`, etc.
- Special case: in `SetValetMode(off)`, a message ending in `already off` is treated as `nil`.

### 3.7 `VCSEC.WhitelistOperation_information_E` (0–28) — `KeychainError.Code`

| Value | Name (`WHITELISTOPERATION_INFORMATION_` omitted) | Cause / remedy |
|---|---|---|
| 0 | `NONE` | Success |
| 1 | `UNDOCUMENTED_ERROR` | Undocumented error |
| 2 | `NO_PERMISSION_TO_REMOVE_ONESELF` | Cannot remove own key |
| 3 | `KEYFOB_SLOTS_FULL` | Key fob slots full |
| 4 | `WHITELIST_FULL` | Whitelist full → remove a key and retry |
| 5 | `NO_PERMISSION_TO_ADD` | No permission to add (e.g. `add-key` with a Driver key) → use an Owner key |
| 6 | `INVALID_PUBLIC_KEY` | Malformed public key |
| 7 | `NO_PERMISSION_TO_REMOVE` | No permission to remove |
| 8 | `NO_PERMISSION_TO_CHANGE_PERMISSIONS` | Cannot change permissions |
| 9 | `ATTEMPTING_TO_ELEVATE_OTHER_ABOVE_ONESELF` | Attempt to grant a role higher than one's own |
| 10 | `ATTEMPTING_TO_DEMOTE_SUPERIOR_TO_ONESELF` | Attempt to demote a superior key |
| 11 | `ATTEMPTING_TO_REMOVE_OWN_PERMISSIONS` | Attempt to remove own permissions |
| 12 | `PUBLIC_KEY_NOT_ON_WHITELIST` | Key to remove does not exist |
| 13 | `ATTEMPTING_TO_ADD_KEY_THAT_IS_ALREADY_ON_THE_WHITELIST` | Key already registered (harmless; to update, remove then add) |
| 14 | `NOT_ALLOWED_TO_ADD_UNLESS_ON_READER` | Attempt to add without a card on the NFC reader |
| 15 | `FM_MODIFYING_OUTSIDE_OF_F_MODE` | Fleet Manager modifying outside F mode |
| 16 | `FM_ATTEMPTING_TO_ADD_PERMANENT_KEY` | FM attempting to add a permanent key |
| 17 | `FM_ATTEMPTING_TO_REMOVE_PERMANENT_KEY` | FM attempting to remove a permanent key |
| 18 | `KEYCHAIN_WHILE_FS_FULL` | Vehicle filesystem full |
| 19 | `ATTEMPTING_TO_ADD_KEY_WITHOUT_ROLE` | Add without a role (`ROLE_NONE`) |
| 20 | `ATTEMPTING_TO_ADD_KEY_WITH_SERVICE_ROLE` | Attempt to grant the Service role |
| 21 | `NON_SERVICE_KEY_ATTEMPTING_TO_ADD_SERVICE_TECH` | Non-service key adding a service technician |
| 22 | `SERVICE_KEY_ATTEMPTING_TO_ADD_SERVICE_TECH_OUTSIDE_SERVICE_MODE` | Outside service mode |
| 23 | `COULD_NOT_START_LOCAL_ENTITY_AUTH` | Failed to start the NFC approval procedure |
| 24 | `LOCAL_ENTITY_AUTH_FAILED_UI_DENIED` | User denied on screen |
| 25 | `LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_TAP` | Timed out waiting for NFC tap → request again and tap immediately |
| 26 | `LOCAL_ENTITY_AUTH_FAILED_TIMED_OUT_WAITING_FOR_UI_ACK` | Timed out waiting for on-screen confirmation |
| 27 | `LOCAL_ENTITY_AUTH_FAILED_VALET_MODE` | Not allowed in Valet mode |
| 28 | `LOCAL_ENTITY_AUTH_FAILED_CANCELLED` | Cancelled |

### 3.8 `VCSEC.SignedMessage_information_E` (0–19, legacy)

`CommandStatus.signedMessageStatus.signedMessageInformation`. The SDK does not interpret this value (superseded by protocol-layer faults). Values: 0 `NONE`, 1 `FAULT_UNKNOWN`, 2 `FAULT_NOT_ON_WHITELIST`, 3 `FAULT_IV_SMALLER_THAN_EXPECTED`, 4 `FAULT_INVALID_TOKEN`, 5 `FAULT_TOKEN_AND_COUNTER_INVALID`, 6 `FAULT_AES_DECRYPT_AUTH`, 7 `FAULT_ECDSA_INPUT`, 8 `FAULT_ECDSA_SIGNATURE`, 9 `FAULT_LOCAL_ENTITY_START`, 10 `FAULT_LOCAL_ENTITY_RESULT`, 11 `FAULT_COULD_NOT_RETRIEVE_KEY`, 12 `FAULT_COULD_NOT_RETRIEVE_TOKEN`, 13 `FAULT_SIGNATURE_TOO_SHORT`, 14 `FAULT_TOKEN_IS_INCORRECT_LENGTH`, 15 `FAULT_INCORRECT_EPOCH`, 16 `FAULT_IV_INCORRECT_LENGTH`, 17 `FAULT_TIME_EXPIRED`, 18 `FAULT_NOT_PROVISIONED_WITH_IDENTITY`, 19 `FAULT_COULD_NOT_HASH_METADATA`. Prefix `SIGNEDMESSAGE_INFORMATION_`.

### 3.9 Proxy HTTP status codes (`pkg/proxy/proxy.go httpStatusCode`, `writeJSONError`)

| Condition | HTTP | Body |
|---|---|---|
| `inet.ErrVehicleNotAwake` | 408 | `{"response":null,"error":"vehicle unavailable: ...","error_description":""}` |
| `protocol.ErrKeyNotPaired` | 412 | Same format as above |
| `*inet.HTTPError` | `httpErr.Code` | `err.Error()` verbatim |
| `protocol.IsNominalError(err)` | 200 | `{"response":{"result":false,"reason":"<err>"},"error":"","error_description":""}` |
| Success | 200 | `{"response":{"result":true,"reason":""}}` |
| Otherwise | 500 | `{"response":null,"error":"<err>","error_description":""}` |
| No OAuth header | 403 | |
| VIN not 17 characters | 404 | |
| ctx expired while waiting for VIN lock | 503 | |
| Not POST | 405 | |
| Body > 1 MiB | 413 | |

---

## 4. Retry loop locations and policies

`RetryInterval()`: `ble.Connection` 1s, `inet.Connection` 1s. `AllowedLatency()`: BLE 4s, inet 10s (changed via `Vehicle.SetMaxLatency`).

| Loop | File:function | Retry condition | Wait | Exit |
|---|---|---|---|---|
| Command send + response | `pkg/vehicle/vehicle.go` `Vehicle.Send` → `trySend` | `protocol.ShouldRetry(err)` | `RetryInterval()` | Success / non-retriable error / `ctx.Done()` → `ctx.Err()` (not wrapped). ctx expiry while awaiting a response is turned into `CommandError{MHS=true}` by `trySend`, so it is not retried |
| VCSEC command | `pkg/vehicle/vcsec.go` `getVCSECResult` → `getReceiver` + `readUntil` | `ShouldRetry(err)` (including `ErrBusy`) | `RetryInterval()` | Same as above. `readUntil` returns on the first message for which `done()` is true |
| Handshake (Vehicle) | `Vehicle.StartSession` → `dispatcher.StartSessions` | `ShouldRetry(err)` | `RetryInterval()` | - |
| Handshake (domain) | `internal/dispatcher/dispatcher.go` `StartSession` → `tryStartSession` | `RetryInterval()` elapses with no response → resend request | `RetryInterval()` | `readySignal` received / error response (`GetError`) / `ctx.Done()` |
| Parallel handshake | `dispatcher.StartSessions` | One goroutine per domain (VCSEC+INFOTAINMENT if nil) | - | Returns the first non-`Canceled` error, cancels the aggregate ctx |
| Transport layer | `dispatcher.Send` | `conn.Send` error is `ShouldRetry` | `conn.RetryInterval()` | `ctx.Done()` → `CommandError{ctx.Err(), MHS=false, T=true}` |
| Authorization wait | `internal/dispatcher/session.go` `session.authorize` | On `Encrypt`/`AuthorizeHMAC` failure, clears err to nil and loops (retries immediately since readySignal is already closed) | None | `ctx.Done()` |
| Wake-up | `pkg/connector/inet/inet.go` `Connection.Wakeup` | `protocol.Temporary(err)` or `state != "online"` | **10s** | `state == "online"` / non-temporary error / ctx |
| BLE connect | `pkg/connector/ble/ble.go` `NewConnectionFromScanResult` → `tryToConnect` | `retry == true` && `!IsAdapterError(err)`: scan failure, `Dial` failure, service/characteristic/descriptor discovery failure, subscribe failure | None (immediate) | `retry == false`: adapter initialization failure, local name mismatch, `Connectable == false`; `ctx.Err() != nil` → returns the last error |
| Proxy forwarding | `pkg/proxy/proxy.go` `forwardRequest` | Only HTTP 421 + `Alt-Svc: h2=https://<host>` | 1s | `MaxAttempts` = 2 → `max retry exhausted` (502) |
| Proxy command | `proxy.handleVehicleCommand` | No retry. `ErrProtocolNotSupported` → mark VIN unsupported then forward to REST; `ErrCommandUseRESTAPI` → forward to REST | - | `p.Timeout` (default 10s) |
| CLI | `cmd/tesla-control/main.go` | No retry | - | `-connect-timeout` 20s, `-command-timeout` 5s |

**Note**: The retry loop re-authorizes the original payload (counter increment, new nonce). `SendMessage` only retries transport errors for which resending the same message without changing the counter is safe.

---

## 5. Automatic session desync recovery

The vehicle may enclose the latest `session_info` + `signature_data.session_info_tag` in responses to authentication errors (`INVALID_SIGNATURE`, `INVALID_TOKEN_OR_COUNTER`, `INCORRECT_EPOCH`, `TIME_EXPIRED`, etc.). Because `dispatcher.process` performs the following on every received message before passing it to the handler, the next retry of `Vehicle.Send` is authorized with the updated session.

Flow (`internal/dispatcher/dispatcher.go checkForSessionUpdate` → `session.processHello` → `authentication.Signer`):

1. `message.session_info == nil` → do nothing.
2. Discard conditions (log only, do not update session; the message itself is still delivered):
   - `d.privateKey == nil` → `Discarding session info because client does not have a private key`
   - `handler.expired(maxLatency)` (more than `AllowedLatency` since the request was sent) → `Discarding session info because it was received more than %s after request`
   - `signature_data.session_info_tag.tag == nil` → `Discarding unauthenticated session info`
   - Domain session not registered → `Dropping session from unregistered domain`
   - HMAC mismatch / parse failure / public key mismatch → `Session info error: ...`
3. `processHello(challenge=request_uuid, info, tag)`:
   - First session → `NewAuthenticatedSigner` (derive K, verify `SESSION_INFO_KEY` HMAC)
   - Existing session → `UpdateSignedSessionInfo` → verify HMAC → `UpdateSessionInfo`:
     - `info.publicKey != existing vehicle public key` → error (`UnknownKeyId: public key in SessionInfo doesn't match ...`)
     - Update only when `epoch changed` **or** `setTime <= info.clock_time`: `counter = max(counter, info.counter)` (the Go implementation does not lower the counter even when the epoch changes), `epoch`, `setTime`, `timeZero = now - clock_time`
     - Otherwise (session info with an older timestamp) → ignored
4. On success, `ready = true` and `readySignal` is closed → releases any waiting `authorize`.

Sessions loaded from the session cache (`pkg/cache`) are marked `ready` immediately in `LoadCache`, so the first command is sent without a handshake; if it fails, it recovers via the flow above. If the cache was created with a different private key, the first command fails with `INVALID_SIGNATURE` and recovers normally.

## 6. Response replay and drops

- Responses carrying `AES_GCM_Response_data` go through `session.decrypt` → `handler.antireplay.Update(counter)` (`authentication.SlidingWindow`, window 32). Failure → `ErrReplayedResponse` → `Dropping duplicate vehicle response` log; not delivered to the handler.
- Handler channel (`receiverBufferSize` = 10) full → `Dropping response to command because response handler queue is full`.
- Unregistered `(routing_address, request_uuid, domain)` → `Dropping message without registered handler`. Because VCSEC does not populate `request_uuid`, the uuid is excluded from the key and requests are distinguished by a random per-request `routing_address`.
- `to_destination` is a domain, or address length ≠ 16 → dropped.

## 7. Meaning of "Couldn't verify success"

`tesla-control` prints `Couldn't verify success: <err>` when `protocol.MayHaveSucceeded(err)`. It means the command was sent and the vehicle may have executed it, but no acknowledgement was received. Tell the user to "check the vehicle state to see whether the command was applied", and **do not resend automatically** (toggle-like commands such as lock/trunk could be executed twice). Check state with `body-controller-state` (VCSEC) or `state <category>` (Infotainment).

---

## 8. Troubleshooting matrix

| Symptom | Possible cause | Check | Action |
|---|---|---|---|
| `ble: failed to scan for <VIN>: context deadline exceeded` | Not near the vehicle, Bluetooth off, VIN typo (local name mismatch), vehicle in deep sleep (longer advertising interval) | `examples/ble -scan-only -vin <VIN>`; check the `S<16hex>C` name with another scanner (`ble.VehicleLocalName`) | Allow 20–30s for scanning, move closer to the vehicle, re-check the VIN |
| `the vehicle is already connected to the maximum number of BLE devices` | Advertisement `Connectable=false`: more than 3 connections including key fobs/phone keys | `Connectable` in the `ScanVehicleBeacon` result | Disconnect other phones/apps from BLE, retry after a moment |
| Linux: `Failed to initialize BLE adapter: ... operation not permitted` | No `CAP_NET_ADMIN` (go-ble calls HCIDEVDOWN) | `ble.IsAdapterError` | `sudo setcap 'cap_net_admin=eip' "$(which tesla-control)"` or root |
| Linux: `the bluetooth adapter ID is invalid` | `-bt-adapter` value is not of the form `hci0`–`hci15` | `hciconfig` | `-bt-adapter hci0` |
| macOS: scan finds nothing | Terminal app lacks Bluetooth permission | System Settings > Privacy & Security > Bluetooth | Grant permission to the terminal/app |
| `vehicle rejected request: your public key has not been paired with the vehicle` | Key not registered, different key in use, not yet synced to Infotainment (right after pairing) | `tesla-control -ble list-keys`; `session-info public_key.pem vcsec` / `infotainment` | `add-key-request` + NFC tap; if just paired, wait tens of seconds and re-check `session-info ... infotainment` |
| `MESSAGEFAULT_ERROR_INVALID_SIGNATURE` / `INCORRECT_EPOCH` / `INVALID_TOKEN_OR_COUNTER` repeating | Corrupted session cache, multiple processes using the same key+cache concurrently, clock skew | Check `Updated session info for ...` logs with `-debug` | Delete the cache file (`$TESLA_CACHE_FILE`, default `~/.tesla-cache.json`); serialize per VIN across processes; reproduce with `-disable-session-cache` |
| `MESSAGEFAULT_ERROR_TIME_EXPIRED` / `TIME_TO_LIVE_TOO_LONG` | The ctx deadline becomes `expires_at`. Too long (TTL) or wrong offset between local clock and domain clock | Whether session_info is enclosed in the `-debug` response | Command ctx 5–10s; retried automatically after session update. If persistent, delete the cache |
| `MESSAGEFAULT_ERROR_BUSY` / `INTERNAL` | Vehicle booting/waking up | Retry after a moment | Retried automatically. Increase `-command-timeout` |
| Eventually times out with `vehicle busy or finishing wake-up` | Infotainment asleep, not woken via BLE `wake` | `vehicleSleepStatus` in `body-controller-state` | Retry after `tesla-control -ble wake` (only the VCSEC session is opened) |
| `MESSAGEFAULT_ERROR_REMOTE_ACCESS_DISABLED` | Mobile access turned off on the vehicle | Vehicle screen Controls > Safety | Turn on Allow Mobile Access |
| `MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES` | Insufficient key role (Driver managing keys, Vehicle Monitor sending control commands), Fleet Manager using BLE, vehicle state (driving, valet) | Check role with `list-keys` | Re-pair with a key of the appropriate role |
| `MESSAGEFAULT_ERROR_COMMAND_REQUIRES_ACCOUNT_CREDENTIALS` | A command requiring account credentials was sent over BLE | - | Send via the internet path (OAuth token) |
| `MESSAGEFAULT_ERROR_KEYCHAIN_IS_FULL` / `keychain operation failed: ..._WHITELIST_FULL` | Keychain full | `list-keys` | Remove via vehicle UI or `remove-key` |
| `MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED` | Response size exceeded (mostly state queries) | - | The command was executed (MHS=true). Use a smaller state category |
| `vehicle does not support protocol -- use REST API` (HTTP 422) | Pre-2021 Model S/X | - | Fleet API REST commands; proxy forwards automatically |
| `vehicle unavailable: vehicle is offline or asleep` (503/408) | Internet path, vehicle asleep | - | Retry after `car.Wakeup(ctx)` (polls at 10s intervals) |
| `could not load key: ...` / `could not load token: ...` | Keyring entry name mismatch, keyring type mismatch | `-keyring-debug` or `TESLA_KEYRING_DEBUG=1`; `-keyring-type` list in `tesla-keygen -h` | Match `TESLA_KEY_NAME`/`TESLA_TOKEN_NAME`; set `TESLA_KEYRING_TYPE`; for a file keyring, `TESLA_KEYRING_PASSWORD` |
| `failed to load session cache: ...` | Corrupted cache JSON | `cat ~/.tesla-cache.json` | Delete the file (it is regenerated) |
| `keychain operation failed: ..._TIMED_OUT_WAITING_FOR_TAP` | NFC tap delayed | - | Tap the card immediately after `add-key-request`, Confirm on screen |
| `command can only be sent over BLE` | `add-key-request` run without `-ble` in an environment with OAuth configured | - | Add `-ble` |
| `command should not be sent in plaintext or encrypted with an unauthenticated public key` | `SetPINToDrive` over BLE | - | Use the Fleet API path |
| Only Infotainment commands fail, `lock`/`unlock` succeed | Infotainment asleep or booting | `body-controller-state` → `vehicleSleepStatus` | Retry after `wake`; for VCSEC-only commands, limit the handshake scope with `-domain vcsec` |
| `dropped response because inbox is full` | Consecutive sends without reading responses (custom code) | - | Always consume/`Close` the `Receiver` |

---

## 9. Debugging procedures

### 9.1 Logs

- `internal/log` levels: `LevelNone`=0 (default, **prints nothing**), `LevelError`=1, `LevelWarning`=2, `LevelInfo`=3, `LevelDebug`=4. Output goes to stderr, format `2023-12-13T14:41:13-08:00 [debug] TX: 3202...`. Labels: `[debug]`, `[info ]`, `[warn ]`, `[error]`.
- How to enable: `tesla-control -debug` or `TESLA_VERBOSE=1` (any value other than `false`/`0`), `tesla-http-proxy -verbose` or `TESLA_VERBOSE`, `examples/ble -debug`. All set `LevelDebug` (intermediate levels cannot be selected).
- `internal/log` cannot be imported from outside the module. To enable SDK logs from an external module, vendor/replace the repository or reproduce with `tesla-control -debug`.
- Keyring debug: `-keyring-debug` or `TESLA_KEYRING_DEBUG` (only presence is checked).

### 9.2 Decoding TX/RX hex (`pkg/protocol/protocol.md` "Decoding messages")

Run from the `pkg/protocol/` directory (`protoc` required):

```bash
# RoutableMessage
echo 320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805 \
    | xxd -r -p \
    | protoc --decode=UniversalMessage.RoutableMessage -I protobuf protobuf/*.proto

# VCSEC payload (when protobuf_message_as_bytes is plaintext)
printf "\n\002\010\005" | protoc --decode=VCSEC.UnsignedMessage -I protobuf protobuf/*.proto
# Response: --decode=VCSEC.FromVCSECMessage
# Infotainment: --decode=CarServer.Action / --decode=CarServer.Response
# session_info field: --decode=Signatures.SessionInfo
```

The `TX:`/`RX:` hex in BLE `-debug` output is the RoutableMessage body with the 2-byte length prefix removed (`ble.Connection.Send` logs before adding the prefix, `flush` logs after stripping it). The inside of AES-GCM-encrypted payloads/responses cannot be viewed with protoc. To see plaintext, use a custom Connector whose `PreferredAuthMethod` is changed to HMAC, or use the internet path (HMAC).

### 9.3 Unauthenticated diagnostic commands (work without a private key)

| Command | Purpose |
|---|---|
| `tesla-control -ble -vin $VIN list-keys` | Registered public keys/roles/form factors |
| `tesla-control -ble -vin $VIN session-info public_key.pem vcsec` | Whether VCSEC knows this key; epoch/counter/clock_time |
| `tesla-control -ble -vin $VIN session-info public_key.pem infotainment` | Whether Infotainment is synced (for checking right after pairing) |
| `tesla-control -ble -vin $VIN body-controller-state` | Lock/door/trunk/sleep/occupancy state (even while Infotainment is asleep) |
| `tesla-control -ble -vin $VIN wake` | (Requires authentication over BLE) Wake Infotainment via VCSEC |

### 9.4 Reproduction order

1. Run with `-disable-session-cache -debug` to rule out cache effects.
2. Isolate the handshake step with `session-info`.
3. Verify the VCSEC path with `body-controller-state` → verify the Infotainment path with `ping`.
4. Compare the failed response's `signedMessageStatus` against the §3.1 table.

---

## Verification checklist

- [ ] Do the 8 `retriableErrors` match `pkg/protocol/error.go` (`pkg/vehicle/vehicle_test.go TestVehicleRetryFail` uses the same list)?
- [ ] Is `RoutableMessageError.MayHaveSucceeded` true only for `NONE` and `RESPONSE_MTU_EXCEEDED`?
- [ ] Has the current behavior of `GetError`'s `OPERATIONSTATUS_ERROR` branch returning `nil` been left unchanged (changing it alters VCSEC response interpretation)?
- [ ] If a new sentinel was added, were the two booleans of `NewError(msg, mayHaveSucceeded, temporary)` chosen per §1.1? Errors in the no-response family must use `mayHaveSucceeded=true`.
- [ ] When a new value is added to `MessageFault_E`, were the §3.1 table and `retriableErrors` membership reviewed?
- [ ] Do the `WhitelistOperation_information_E` values match `vcsec.proto` (0–28)?
- [ ] When changing proxy status codes, update `pkg/proxy/status_test.go`, `proxy_test.go TestWriteJSONError`.
- [ ] `go test ./pkg/protocol/ ./pkg/vehicle/ ./internal/dispatcher/ ./pkg/proxy/` passes.
