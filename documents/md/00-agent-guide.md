# 00. Agent Work Guide

Rules, invariants, and quick reference cards that an AI agent modifying the `vehicle-command/` repository or implementing a client for this protocol must read **first**.

Related files: `README.md`, `Makefile`, `check-all.sh`, `.golangci.yml`, `go.mod`, `pkg/protocol/protocol.md`, `SECURITY.md`

---

## 1. Scope and Baseline

| Item | Value |
|---|---|
| Repository location (relative to this document) | `../../vehicle-command/` |
| Go module | `github.com/teslamotors/vehicle-command` |
| Baseline commit | `a4b43c1` (Fix wrapped protocol error classification), 16 commits after tag `v0.4.1` (`v0.4.1-16-ga4b43c1`) |
| `pkg/account/version.txt` | `0.4.1` (included in User-Agent as `tesla-sdk/0.4.1`) |
| Go version | `go 1.23` (`go.mod`), CI uses 1.23.0, Docker build image `golang:1.23.0` |
| Supported OS | macOS, Linux. Windows can build without BLE (`pkg/connector/ble/device_windows.go` always returns `not supported on Windows`) |
| Other files in this manual | See the task table in [README.md](README.md) |

This repository is **v0.x**, so API stability is not guaranteed (last paragraph of `README.md`). When changing a public API, update its callers (`cmd/*`, `examples/*`, `pkg/proxy`, `pkg/cli`) as well.

---

## 2. Work Rules

### 2.1 What to Read Before Modifying

| Target | Read first (repository) | This manual |
|---|---|---|
| `pkg/connector/ble/*` | `pkg/connector/connector.go` (interface contract), `examples/ble/main.go` | [02-ble-transport.md](02-ble-transport.md) |
| `internal/dispatcher/*` | `internal/authentication/signer.go`, `pkg/protocol/receiver.go`, `pkg/cache/cache.go` | [01-architecture.md](01-architecture.md), [03-protocol.md](03-protocol.md) |
| `internal/authentication/*` | `pkg/protocol/protocol.md` **in full**, `pkg/protocol/protobuf/signatures.proto`, `internal/authentication/protocol_doc_test.go` | [03-protocol.md](03-protocol.md) |
| `pkg/vehicle/*` (adding commands) | `pkg/protocol/protobuf/car_server.proto` or `vcsec.proto`, `cmd/tesla-control/commands.go`, `pkg/proxy/command.go` | [05-command-catalog.md](05-command-catalog.md), [09-recipes.md](09-recipes.md) |
| `pkg/proxy/*`, `cmd/tesla-http-proxy/*` | `pkg/proxy/proxy_test.go` (behavior contract is specified by tests) | [07-http-proxy.md](07-http-proxy.md) |
| `pkg/cli/*` | `cmd/tesla-control/main.go`, `cmd/tesla-keygen/main.go` (call order) | [06-cli-tools.md](06-cli-tools.md) |
| `*.proto` | `proto-gen` target in `Makefile` | [03-protocol.md](03-protocol.md) |

### 2.2 Build, Test, Lint

```bash
cd vehicle-command
go build ./...          # compile all packages/binaries
go test ./...           # unit tests (make test includes -cover)
go vet ./...
./check-all.sh          # build + test + vet + check gofmt makes no changes + shellcheck (if present)
make test               # go install ./cmd/... then go test -cover ./... && go vet ./...
make linters            # golangci-lint run -v --exclude-use-default=false --timeout 30s (CI uses v1.61.0)
make format             # set-version then go fmt ./...
make proto-gen          # protoc --go_out ... pkg/protocol/protobuf/*.proto (requires protoc + protoc-gen-go)
make doc-images         # docker plantuml: doc/*.puml → png
```

Notes:

- `make format` and `make build` run the `set-version` target first. This target strips the `v` from the output of `git describe --tags --abbrev=0` and **overwrites** `pkg/account/version.txt`. In forks/shallow clones without tags, failure is ignored (`if TAG=...; then`). If a `version.txt` change appears in the diff, confirm whether it is intended.
- CI (`.github/workflows/build.yml`) runs `make format` and then passes only if `git diff --exit-code` shows no formatting changes. Always apply `gofmt` before committing.
- **`go` and `protoc` are not installed on this development machine** (`which go` and `which protoc` both fail). After code changes, build/test verification must be performed in the user's environment or in Docker (`docker build .`). If you could not verify, say so in your report.
- When linting on Alpine, `LINTER_FLAGS += --build-tags=musl` is added automatically.

### 2.3 Code Style (`.golangci.yml`)

Enabled linters: `errcheck`, `gofmt`, `gosimple`, `govet`, `misspell`, `revive`, `unused`.

Enabled revive rules: `blank-imports`, `context-as-argument`, `context-keys-type`, `dot-imports` (only ginkgo/gomega allowed), `empty-block`, `error-naming`, `error-return`, `error-strings`, `errorf`, `increment-decrement`, `indent-error-flow`, `range`, `receiver-naming`, `redefines-builtin-id`, `superfluous-else`, `time-naming`, `unexported-return`, `unreachable-code`, `unused-parameter`, `var-declaration`, `var-naming`. (`exported` is commented out, `package-comments` is disabled.)

Excluded errcheck warnings: unchecked return values of `w.Write`, `fmt.Fprintf`, `fmt.Fprintln`, `file.Close`, `os.Setenv`. `elliptic.*` deprecation warnings are also excluded (to keep Go 1.21 toolchain compatibility, `internal/authentication/native.go`).

Conventions:

- `context.Context` is the first argument, named `ctx`.
- Error variables use the `Err...` prefix; error strings start lowercase and have no trailing period.
- Unused parameters are `_` (e.g. `func (c *Connection) Send(_ context.Context, buffer []byte)`).
- User-facing logs use the `internal/log` package (`log.Debug/Info/Warning/Error`). `fmt.Println` is for CLI output only.
- By convention, a comment at the top of a file describes the file's role (`// File implements commands related to vehicle charging.`).

### 2.4 Regenerating protobuf

When you change `pkg/protocol/protobuf/*.proto`, regenerate `*.pb.go` with `make proto-gen`. The option is `--go_opt=module=github.com/teslamotors/vehicle-command/pkg/protocol/protobuf`, and generated files are placed in subdirectories per `go_package` (`carserver/`, `vcsec/`, `signatures/`, `universalmessage/`, `keys/`, `errors/`, `managedcharging/`). Do not edit `*.pb.go` by hand.

### 2.5 Documentation Update Rules

When you change code, update the corresponding file in this directory (`documents/md/`) and the corresponding page under `documents/html/` in the same change. HTML pages live under `documents/html/{ko,en,ja}/` (same file names in each language directory), and all three languages must be updated in the same change. `documents/html/index.html` is the English home mirrored from `en/index.html`; after editing `en/index.html`, run `tools/ci/sync-manual-root-index.sh`. Files in `documents/md/` must be written in English (AGENTS.md absolute rule 12). Mapping:

| Code | md | html |
|---|---|---|
| `pkg/connector/ble/*` | `02-ble-transport.md` | `03-ble-transport.html` |
| `internal/authentication/*`, `internal/dispatcher/*`, `pkg/protocol/protocol.md`, `*.proto` | `03-protocol.md`, `01-architecture.md` | `04-protocol.html`, `01-overview.html` |
| `pkg/vehicle/*`, `cmd/tesla-control/commands.go`, `pkg/proxy/command.go` | `05-command-catalog.md`, `04-go-api-reference.md` | `07-command-reference.html`, `05-go-sdk.html` |
| `cmd/*`, `pkg/cli/*` | `06-cli-tools.md` | `06-cli-tools.html` |
| `pkg/proxy/*`, `cmd/tesla-http-proxy/*` | `07-http-proxy.md` | `08-http-proxy.html` |
| `pkg/protocol/error.go`, `MessageFault_E` in `universal_message.proto` | `08-errors.md` | `09-errors-troubleshooting.html` |

---

## 3. Invariants That Must Never Be Violated

Each item lists its source files. A change that breaks these conditions is either a security vulnerability or a break in interoperability with the vehicle.

### 3.1 Protocol/Cryptography

1. **The anti-replay counter must increase monotonically within the same epoch.** `Signer.Encrypt`/`AuthorizeHMAC` do `s.counter++` on every call and return a `counter rollover` error at `0xFFFFFFFF`. When updating session info, the counter is **never rolled back unless the epoch changes** (`Signer.UpdateSessionInfo`: `if s.counter < info.Counter { s.counter = info.Counter }`). Source: `internal/authentication/signer.go`, `pkg/protocol/protocol.md` "Recovering from synchronization errors".
2. **VCSEC requires messages to arrive in counter order; do not send concurrent requests.** Infotainment tolerates reordering via a sliding window, but VCSEC does not. For this reason the proxy serializes commands with a per-VIN mutex (`Proxy.lockVIN`). Source: `pkg/protocol/protocol.md` "Metadata", comment on `handleVehicleCommand` in `pkg/proxy/proxy.go`.
3. **Response counter reuse check**: the `AES_GCM_Response_data.counter` of a decrypted response is checked against a per-request `SlidingWindow` (window 32); duplicates are discarded with `protocol.ErrReplayedResponse`. Source: `decrypt` in `internal/dispatcher/session.go`, `internal/authentication/window.go`.
4. **Session info HMAC tags are verified with constant-time comparison** (`hmac.Equal`). Session info without a tag is discarded (`Discarding unauthenticated session info`). Source: `NewAuthenticatedSigner`, `UpdateSignedSessionInfo` in `internal/authentication/signer.go`; `checkForSessionUpdate` in `internal/dispatcher/dispatcher.go`.
5. **Session info is used for an update only if it arrives within `AllowedLatency()` after the corresponding request was sent.** BLE 4s, inet 10s. If exceeded, it is discarded (`receiver.expired`). Source: `checkForSessionUpdate` in `internal/dispatcher/dispatcher.go`, `maxLatency` in `pkg/connector/ble/ble.go`, `MaxLatency` in `pkg/connector/inet/inet.go`.
6. **Session info update conditions**: update only if the request UUID (challenge) is recent, the tag matches, and the clock does not go backwards within the same epoch (update only when `s.setTime <= info.ClockTime` or the epoch changed). Source: `Signer.UpdateSessionInfo`.
7. **`FLAG_ENCRYPT_RESPONSE` is on by default.** `vehicle.DefaultFlags = 1 << Flags_FLAG_ENCRYPT_RESPONSE` (= 2). If flags are non-zero, `TAG_FLAGS` is included in request metadata, and it is **always** included in response metadata. Source: `pkg/vehicle/vehicle.go`, `extractMetadata`/`responseMetadata` in `internal/authentication/peer.go`.
8. **BLE uses AES-GCM; Fleet API (inet) uses HMAC-SHA256.** `ble.Connection.PreferredAuthMethod()` is `AuthMethodGCM`; `inet.Connection` is `AuthMethodHMAC`. Fleet API blocks AES-GCM commands (it cannot check OAuth scopes). Source: `pkg/connector/ble/ble.go`, `pkg/connector/inet/inet.go`, `protocol.md` "Authentication methods".
9. **Metadata TLV is in ascending tag order, each value ≤255 bytes, terminated by `0xFF`.** `metadata.Add` returns `errOutOfOrderMetadata` if out of order and `ErrMetadataFieldTooLong` if too long. `nil` values are skipped. Source: `internal/authentication/metadata.go`.
10. **Request hash rule**: `[SignatureType 1 byte] || tag`. For HMAC requests to VCSEC, the tag is truncated to 16 bytes. Source: `RequestID` in `internal/authentication/peer.go`.
11. **Do not enroll the test keys (`vehicle.key`, `client.key` in `protocol.md`) on a real vehicle.** Their private keys are public, so enrolling them enables unauthorized access. Use them only to verify test vectors.
12. **Expiration upper bound**: `expires_at` cannot exceed `epochLength = 2^30 seconds` (`out of bounds expiration time`). The default command lifetime is `defaultExpiration = 5s`, or until the ctx deadline if one is set. Source: `internal/authentication/peer.go`, `internal/dispatcher/session.go`.

### 3.2 Keys and Deployment

13. **Do not use the same key for TLS and command authentication.** If the two public keys are equal, `cmd/tesla-http-proxy/main.go` prints `It is unsafe to use the same private key for TLS and command authentication.` and exits. Do not remove this check.
14. **Do not add a TLS-disabling option (`--insecure`, etc.) to the proxy.** This is a design decision stated in comments in `cmd/tesla-http-proxy/main.go` and in `README.md`. If you need a variant without TLS, build a separate binary that uses `pkg/proxy` directly.
15. **Binding to an interface other than `localhost` prints a warning** (`nonLocalhostWarning`). Do not expose it externally without client authentication.
16. **Keyring entry naming** rules: service `com.tesla.auth`, private key `vehicleCommandKey.<TESLA_KEY_NAME>`, OAuth token `oauthtoken.<TESLA_TOKEN_NAME>`, default file-backend directory `~/.tesla_keys`. The private key is stored as a 32-byte scalar (`D.FillBytes`). Source: `pkg/cli/keyring.go`.
17. **The session cache file is bound to the private key** and requires access control (`pkg/cache/doc.go`). If the cache is used with a different key, authentication fails and a new handshake happens automatically.

### 3.3 Go API Contracts

18. **`internal/` packages cannot be imported from outside the module.** Types that must be exposed are re-exported as type aliases/wrappers, as in `pkg/protocol/key.go` (`protocol.ECDHPrivateKey`, `protocol.Session`). `pkg/cache` exposing `internal/dispatcher.CacheEntry` is an exception; clients are directed to use `vehicle.UpdateCachedSessions`/`LoadCachedSessions`.
19. **`connector.Connector.Close()` must be idempotent**, and `Receive()`/`Send()` must be thread-safe. `inet.Connection.Close` closes the inbox and sets it to `nil` so that subsequent `Send` returns `ErrNotConnected` (`TestSendAfterClose`). Source: `pkg/connector/connector.go`.
20. **Call `Vehicle.Disconnect()` before `Connector.Close()`.** `Disconnect` stops the dispatcher goroutine and then calls `conn.Close()`, so deferring both is fine, but keep the order (register `defer conn.Close()` first, `defer car.Disconnect()` later → Disconnect runs first). Source: comment on `Disconnect` in `pkg/vehicle/vehicle.go`.
21. **When implementing `ECDHPrivateKey` with an HSM/TEE**, the TEE **must not expose** (a) an API that exports the shared secret (ECDH result), (b) an AES interface using the ECDH-derived key, or (c) an AES-GCM encryption interface where the host supplies the nonce. Do not put derived secrets in the `Session` object; put only handles there. Source: comment on `Session` in `pkg/protocol/key.go`, top-of-file comment in `internal/authentication/ecdh.go` (which is why `crypto/ecdh` is not used).
22. **Only errors implementing the `protocol.Error` interface** (`MayHaveSucceeded()`, `Temporary()`) are used in retry decisions. `ShouldRetry` never retries if `MayHaveSucceeded`, and retries if `Temporary`. When adding a new error type, define these two methods carefully. Source: `pkg/protocol/error.go`.
23. **Responses are dropped when the inbox buffer is full** (BLE `flush`: `default: return false`, inet `Send`: `dropped response because inbox is full`, dispatcher `process`: `response handler queue is full`). Buffer sizes are `connector.BufferSize = 5`, `receiverBufferSize = 10`. Changing this to blocking risks deadlock.

---

## 4. Quick Reference Cards

### 4.1 BLE

| Item | Value | Source |
|---|---|---|
| Service UUID | `00000211-b2d1-43f0-9b88-960cebf8b91e` | `pkg/connector/ble/ble.go` |
| Write (to vehicle) characteristic | `00000212-b2d1-43f0-9b88-960cebf8b91e` — write **with response** | 〃 |
| Read/notify (from vehicle) characteristic | `00000213-b2d1-43f0-9b88-960cebf8b91e` — subscribe **indication** | 〃 |
| Advertisement Local Name | `"S" + hex(SHA1(VIN)[:8]) + "C"` (16 lowercase hex chars) — e.g. VIN `5YJS0000000000000` → `S1a87a5a75f3df858C` | `VehicleLocalName`, `protocol.md` |
| Framing | `[len_hi][len_lo][payload...]`, length is 2-byte big-endian, payload is a RoutableMessage protobuf | `Connection.Send`/`flush` |
| Maximum message | `maxBLEMessageSize = 1024` bytes (on receive, buffer is discarded if exceeded) | 〃 |
| Block length | `min(negotiatedMTU, 1024) - 3`; `ble.DefaultMTU - 3` if MTU exchange fails | `tryToConnect` |
| Timeout between received chunks | `rxTimeout = 1s` (input buffer reset if exceeded) | 〃 |
| Allowed latency for clock sync | `maxLatency = 4s` | 〃 |
| Retry interval | `RetryInterval() = 1s` | 〃 |
| inbox buffer | 5 (`make(chan []byte, 5)`) | 〃 |
| Linux adapter ID | `hciX` (0 ≤ X ≤ 15), dial/listen timeout 20s | `device_linux.go` |
| Concurrent VCSEC BLE connections | Up to 3 (shared with key fobs/phone keys) | `protocol.md` |

### 4.2 Protocol Constants

| Item | Value |
|---|---|
| `Domain` | `DOMAIN_BROADCAST=0`, `DOMAIN_VEHICLE_SECURITY=2`, `DOMAIN_INFOTAINMENT=3` |
| `Flags` (bit index) | `FLAG_USER_COMMAND=0`, `FLAG_ENCRYPT_RESPONSE=1` → `vehicle.DefaultFlags = 2` |
| `SignatureType` | `AES_GCM=0`, `AES_GCM_PERSONALIZED=5`, `HMAC=6`, `HMAC_PERSONALIZED=8`, `AES_GCM_RESPONSE=9` |
| `Tag` | `SIGNATURE_TYPE=0`, `DOMAIN=1`, `PERSONALIZATION=2`, `EPOCH=3`, `EXPIRES_AT=4`, `COUNTER=5`, `CHALLENGE=6`, `FLAGS=7`, `REQUEST_HASH=8`, `FAULT=9`, `END=255` |
| `Keys.Role` | `NONE=0`, `SERVICE=1`, `OWNER=2`, `DRIVER=3`, `FM=4`, `VEHICLE_MONITOR=5`, `CHARGING_MANAGER=6`, `GUEST=8` (no 7) |
| `VCSEC.KeyFormFactor` | `UNKNOWN=0`, `NFC_CARD=1`, `IOS_DEVICE=6`, `ANDROID_DEVICE=7`, `CLOUD_KEY=9` |
| `VCSEC.RKEAction_E` | `UNLOCK=0`, `LOCK=1`, `REMOTE_DRIVE=20`, `AUTO_SECURE_VEHICLE=29`, `WAKE_VEHICLE=30` |
| `VCSEC.SignatureType` | `NONE=0`, `PRESENT_KEY=2` (add-key-request envelope) |
| Shared key | `K = SHA1(BIG_ENDIAN(Sx,32))[:16]` (128-bit AES-GCM), `SharedKeySizeBytes = 16` |
| KDF labels | `"session info"`, `"authenticated command"` (HMAC-SHA256(K, label)) |
| epoch ID length | 16 bytes; `epochLength = 2^30 s`; sliding window 32 |
| UUID/routing address length | 16 bytes (`uuidLength`, `addressLength`, `challengeLength`) |
| Default command lifetime | `defaultExpiration = 5s` (`internal/dispatcher/session.go`) |
| dispatcher receiver buffer | `receiverBufferSize = 10` |
| Maximum response length (connector) | `connector.MaxResponseLength = 100000` |
| Fleet API default domain | `fleet-api.prd.na.vn.cloud.tesla.com` (re-determined from token `aud`/`ou_code`) |
| Proxy | `DefaultTimeout = 10s`, `MaxAttempts = 2`, `MaxResponseLength = 10000000`, `maxRequestBodyBytes = 1 MiB`, `cacheSize = 10000`, default port 443, User-Agent `tesla-http-proxy/1.1.0` |
| Fleet API endpoints | `POST api/1/vehicles/<VIN>/signed_command` body `{"routable_message": <base64>}` → `{"response": <base64>}`; `POST api/1/vehicles/<VIN>/wake_up` |

### 4.3 Environment Variables

| Variable | Used by | Meaning |
|---|---|---|
| `TESLA_KEY_NAME` | `pkg/cli` | Private key name in the keyring |
| `TESLA_KEY_FILE` | `pkg/cli` | Private key PEM file path (instead of keyring) |
| `TESLA_TOKEN_NAME` | `pkg/cli` | OAuth token name in the keyring |
| `TESLA_TOKEN_FILE` | `pkg/cli` | OAuth token file path |
| `TESLA_VIN` | `pkg/cli` | VIN (17 characters) |
| `TESLA_CACHE_FILE` | `pkg/cli` | Session cache file (default `~/.tesla-cache.json`) |
| `TESLA_KEYRING_TYPE` | `pkg/cli` | 99designs/keyring backend (list with `tesla-keygen -h`) |
| `TESLA_KEYRING_PASSWORD` | `pkg/cli` | File backend password (skips prompt) |
| `TESLA_KEYRING_PATH` | `pkg/cli` | File backend directory |
| `TESLA_KEYRING_DEBUG` | `pkg/cli` | If present, keyring debug logs |
| `TESLA_VERBOSE` | `tesla-control`, `tesla-http-proxy` | Debug logs if anything other than `false`/`0` |
| `TESLA_HTTP_PROXY_TLS_CERT` / `_TLS_KEY` / `_HOST` / `_PORT` / `_TIMEOUT` | `tesla-http-proxy` | Proxy TLS/bind/timeout |

`ReadFromEnvironment()` **does not overwrite values that are already set**, so always call it after `flag.Parse()`.

### 4.4 One-Line CLI Examples

```bash
export TESLA_KEY_NAME=$(whoami) TESLA_VIN=<VIN> TESLA_CACHE_FILE=~/.tesla-cache.json
tesla-keygen create > public_key.pem                                  # generate key (if it exists, prints only the public key; -f to overwrite)
tesla-control -ble add-key-request public_key.pem owner cloud_key     # approve with NFC card inside the car
tesla-control -ble lock                                               # BLE command
tesla-control -ble -debug list-keys                                   # TX/RX hex dump
tesla-control -ble -domain vcsec body-controller-state                # VCSEC only, without waking Infotainment
tesla-control -ble state charge                                       # Infotainment state JSON
tesla-control -ble session-info public_key.pem infotainment           # confirm key enrollment
tesla-control lock                                                    # internet path if an OAuth token is present
tesla-auth-token -token-name $(whoami) token.txt                      # store token in keyring
tesla-jws -fleet sign TelemetryClient telemetry_config.json > cfg.jws
tesla-http-proxy -tls-key config/tls-key.pem -cert config/tls-cert.pem -key-file config/fleet-key.pem -port 4443
go run ./examples/ble -vin <VIN> -key private.pem [-debug] [-bt-adapter hci0]   # example
go run ./examples/ble -vin <VIN> -scan-only
```

---

## 5. Common Pitfalls

| Pitfall | Fact | Source |
|---|---|---|
| `StartSession(ctx, nil)` wakes Infotainment | If `domains == nil`, it handshakes with both VCSEC and INFOTAINMENT domains in parallel. To send only VCSEC commands to a sleeping car, use `[]universal.Domain{protocol.DomainVCSEC}` or CLI `-domain vcsec`. `tesla-control` automatically selects only VCSEC for `-ble wake`. | `StartSessions` in `internal/dispatcher/dispatcher.go`, `configureFlags` in `cmd/tesla-control/commands.go` |
| `SendAddKeyRequest` needs no session | It sends a `vcsec.ToVCSECMessage{SignedMessage{SignatureType: PRESENT_KEY}}` envelope **directly** via `v.conn.Send`. It does not go through the dispatcher/RoutableMessage and does not wait for a response. With the inet connector, `ErrRequiresBLE`. Check approval with `SessionInfo(ctx, pub, DomainInfotainment)`. | `pkg/vehicle/security.go` |
| The `Wakeup` path depends on the connector | For a `FleetAPIConnector`, polls `api/1/vehicles/<VIN>/wake_up` every 10 seconds (`state == "online"`); otherwise VCSEC `RKE_ACTION_WAKE_VEHICLE`. | `pkg/vehicle/vehicle.go`, `pkg/connector/inet/inet.go` |
| `SetPINToDrive` fails over BLE | If not a `FleetAPIConnector`, `protocol.ErrRequiresEncryption`. (Policy to prevent plaintext PIN exposure.) | `pkg/vehicle/security.go` |
| `AddKey` (authenticated) and `AddKeyRequest` (NFC approval) differ | `AddKeyWithRole` signs with an already-enrolled Owner key to add to the whitelist. `SendAddKeyRequestWithRole` bootstraps an unenrolled key via an NFC card tap. | `pkg/vehicle/security.go` |
| Fleet Manager keys cannot send BLE commands | On 2023.38+ vehicles, the FM role is blocked from BLE commands and from adding/removing other users' keys. | `protocol.md` "Roles" |
| VCSEC responses have no `request_uuid` | Therefore, for VCSEC, `receiverKey` matches only on the random `routing_address` and leaves the uuid empty. Infotainment uses a fixed address + uuid. | `Send`/`process` in `internal/dispatcher/dispatcher.go` |
| VCSEC sends up to 3 responses per request | Termination is decided by the per-command `isTerminalTest`. `OPERATIONSTATUS_WAIT` is `ErrBusy` (retry); `OPERATIONSTATUS_ERROR` is legacy, so without a whitelist code it is `ErrUnknown` or ignored. | `pkg/vehicle/vcsec.go` |
| Infotainment application errors | If `Response.actionStatus.result == OPERATIONSTATUS_ERROR`, `protocol.NominalError{"car could not execute command: <plain_text>"}`. The proxy converts this to HTTP 200 + `{"response":{"result":false,"reason":...}}`. | `pkg/vehicle/infotainment.go`, `pkg/proxy/proxy.go` |
| Session cache is bound to the key | If the cache is loaded with a different private key, the first command fails and recovers when the vehicle provides new session info. On a cache hit the handshake is skipped, so the log `Session for X loaded from cache` appears. | `pkg/cache/doc.go`, `dispatcher.StartSession` |
| macOS `device.Scan` always returns an error | It does not finish until ctx is canceled, so the code treats `context.Canceled` as the normal path. | `scanVehicleBeacon` in `pkg/connector/ble/ble.go` |
| Linux BLE permissions | On `operation not permitted`, use `sudo setcap 'cap_net_admin=eip' "$(which <bin>)"` or root. `IsAdapterError` checks this string only on Linux. | `device_linux.go` |
| The proxy accepts only VINs | If the 4th URL segment is not 17 characters, 404 (`do not user Fleet API ID`). Owner API IDs cannot be used. | `ServeHTTP` in `pkg/proxy/proxy.go` |
| Order: `flag.Parse` → `ReadFromEnvironment` → `LoadCredentials` → `Connect` | If the order changes, environment variables override flags or the keyring prompt consumes the timeout. | Package comment in `pkg/cli/config.go` |
| `LoadPublicKey` also accepts private key files | Accepts PKIX PEM, PKCS8 PEM, SEC1 PEM, 65-byte binary, and 130 (+newline) hex. You may pass a private key file to the CLI's `PUBLIC_KEY` argument. | `pkg/protocol/key.go` |
| The doc comment of `Ping` is inverted | In code, if `Ping` returns `nil`, the vehicle is online and recognizes the key. The comment's "non-nil error ... online" is a typo. | `pkg/vehicle/infotainment.go` |
| Session info is discarded without a private key | An unauthenticated vehicle created with `NewVehicle(conn, nil, nil)` cannot handshake at all (`ErrRequiresKey`). Only `list-keys`, `body-controller-state`, `session-info`, `add-key-request`, `wake` (internet) work without a key. | `dispatcher.RequestSessionInfo`, `requiresAuth` in `commands.go` |

---

## Verification Checklist

After changing this document or code related to the invariants in section 3:

- [ ] Did `go build ./... && go test ./... && go vet ./...` (or `./check-all.sh`) pass in the user's environment? If you could not run it, did you state so in the report?
- [ ] Are there no `gofmt` changes (`make format && git diff --exit-code`)?
- [ ] Do `internal/authentication/protocol_doc_test.go` (`TestProtocolDocAESGCMExample`) and `signer_test.go`/`verifier_test.go`/`window_test.go` still pass unchanged? For crypto-related changes, did you check against the test vectors (`03-protocol.md`)?
- [ ] Was none of counter rollback protection, tag comparison (`hmac.Equal`), `AllowedLatency`, or the `FLAG_ENCRYPT_RESPONSE` default weakened?
- [ ] If you added a new public API/constant/environment variable, did you reflect it in the section 4 cards, `04-go-api-reference.md`, `06-cli-tools.md`, and the corresponding pages under `../html/`?
- [ ] Did `pkg/account/version.txt` remain unchanged unless intended?
- [ ] Did you avoid hand-editing `*.pb.go`? If you changed a `.proto`, did you run `make proto-gen`?
- [ ] Did documentation changes touch only files outside `vehicle-command/` (this `documents/` tree)?
