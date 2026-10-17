# 01. Architecture

Package layers of the `vehicle-command` repository, responsibilities of each layer, the full path of a single command, the concurrency model, cache structure, and build/CI/test setup.

Related files: `pkg/connector/connector.go`, `internal/dispatcher/dispatcher.go`, `internal/dispatcher/session.go`, `internal/dispatcher/receiver.go`, `internal/authentication/*.go`, `pkg/vehicle/vehicle.go`, `pkg/vehicle/vcsec.go`, `pkg/vehicle/infotainment.go`, `pkg/cache/cache.go`, `go.mod`, `Makefile`, `Dockerfile`, `.github/workflows/build.yml`

---

## 1. Directory Tree

```
vehicle-command/
├── README.md                     Installation, proxy setup, key distribution procedure (authoritative user docs)
├── SECURITY.md                   Vulnerability reporting: https://www.tesla.com/legal/security
├── PULL_REQUEST_TEMPLATE.md
├── LICENSE
├── go.mod / go.sum               Module github.com/teslamotors/vehicle-command, go 1.23
├── Makefile                      build/test/linters/format/install/proto-gen/doc-images/set-version
├── check-all.sh                  build+test+vet+gofmt check+shellcheck
├── .golangci.yml                 Linter config (00-agent-guide.md section 2.3)
├── Dockerfile                    golang:1.23.0 build → distroless nonroot, ENTRYPOINT tesla-http-proxy
├── docker-compose.yml            Proxy example (env-based config, ./config volume)
├── .dockerignore / .gitignore
├── .github/
│   ├── ISSUE_TEMPLATE/{config.yml,issue.md}
│   └── workflows/{build.yml,publish.yml}   CI: format diff check, golangci-lint v1.61.0, make test
├── doc/
│   ├── authorization.puml/.png   OAuth token acquisition flow (PlantUML)
│   └── request_diagram.puml/.png Command flow through the proxy
├── cmd/                          Binaries (install with go install ./cmd/...)
│   ├── tesla-keygen/{main.go,doc.go}          Key create/delete/export/migrate, keyring storage
│   ├── tesla-control/{main.go,commands.go,commands_test.go,doc.go,README.md,.gitignore}
│   │                                            CLI (BLE/internet), full command table in commands.go
│   ├── tesla-auth-token/{main.go,doc.go,README.md}   Stores OAuth token in keyring
│   ├── tesla-http-proxy/{main.go,server.go,main_test.go,doc.go}
│   │                                            REST → signed command proxy (server.go is a self-signed certificate helper)
│   └── tesla-jws/{main.go,README.md}          Schnorr/P256 JWS sign/verify (for fleet telemetry config)
├── examples/
│   ├── ble/{main.go,doc.go}      BLE scan → connect → session → Unlock → ClimateOn
│   └── unlock/{unlock.go,doc.go} Internet (account) → Unlock
├── pkg/                          Public API
│   ├── protocol/
│   │   ├── doc.go, domains.go    Domain aliases/constants (DomainNone/VCSEC/Infotainment)
│   │   ├── error.go              Error interface, CommandError, RoutableMessageError, NominalError, GetError, ShouldRetry
│   │   ├── key.go                Re-exports ECDHPrivateKey/Session, Load/SavePrivateKey, LoadPublicKey, PublicKeyBytesFromHex
│   │   ├── receiver.go           Receiver interface
│   │   ├── protocol.md           Original protocol specification (test vectors)
│   │   ├── *_test.go, test/*.pem Key loading test data
│   │   └── protobuf/             *.proto + generated code (carserver/, vcsec/, signatures/, universalmessage/, keys/, errors/, managedcharging/)
│   ├── connector/
│   │   ├── connector.go, doc.go  Connector / FleetAPIConnector interfaces, AuthMethod, BufferSize, MaxResponseLength
│   │   ├── ble/{ble.go,doc.go,device_darwin.go,device_linux.go,device_windows.go}   BLE implementation (go-ble/ble)
│   │   └── inet/{inet.go,doc.go,inet_test.go}                                        Fleet API HTTPS implementation
│   ├── vehicle/
│   │   ├── vehicle.go            Vehicle type, NewVehicle, Connect, StartSession, Send, SendMessage, Wakeup, cache integration
│   │   ├── vcsec.go              VCSEC response parsing, readUntil, getVCSECResult, RKE/closure/whitelist helpers
│   │   ├── infotainment.go       CarServer Action execution, Ping, media, software update, SeatPosition
│   │   ├── security.go           Lock/key management/PIN/valet/sentry/guest/parental controls/Homelink
│   │   ├── actions.go            Trunk/frunk/tonneau/horn/lights/sunroof/windows/charge port
│   │   ├── charge.go             Charge limit/current/start·stop/schedule/scheduled departure/low power/accessory power
│   │   ├── climate.go            Climate/seat heater·cooler/steering wheel heater/preconditioning/COP/climate keeper
│   │   ├── state.go              BodyControllerState (VCSEC), GetState (Infotainment, StateCategory)
│   │   └── *_test.go             Retry/timeout tests based on a mock connector
│   ├── cache/{cache.go,doc.go,cache_test.go,example_test.go}   SessionCache (LRU, JSON import/export)
│   ├── cli/{config.go,keyring.go,config_darwin.go,config_linux.go,config_windows.go,config_test.go}
│   │                             Flags/environment variables/keyring/connection helpers
│   ├── account/{account.go,doc.go,version.txt,account_test.go}   OAuth token → Fleet API host, GetVehicle, Get/Post, UpdateKey
│   ├── proxy/{proxy.go,command.go,doc.go,*_test.go}   HTTP proxy handler, endpoint → Vehicle method mapping
│   └── sign/{sign.go,doc.go}     SignMessageForVehicle / SignMessageForFleet (JWS)
└── internal/                     Not importable from outside the module
    ├── authentication/           Cryptographic protocol implementation
    │   ├── ecdh.go               ECDHPrivateKey interface, SharedKeySizeBytes
    │   ├── crypto.go             Session interface, constants (labels, epochIDLength, windowSize), epochStartTime
    │   ├── native.go             NativeSession (AES-GCM/HMAC), NativeECDHKey, NewECDHPrivateKey, LoadExternalECDHKey, UnmarshalECDHPrivateKey
    │   ├── metadata.go           TLV metadata hash builder
    │   ├── peer.go               Peer (shared state), extractMetadata, hmacTag, RequestID, responseMetadata
    │   ├── signer.go             Signer (client side): Encrypt, AuthorizeHMAC, Decrypt, session info import/export/update
    │   ├── verifier.go           Verifier (vehicle-side simulation, test/reference implementation)
    │   ├── window.go             SlidingWindow anti-replay
    │   ├── dispatcher.go         authentication.Dispatcher (helper creating Signers for multiple vehicles with the same key)
    │   ├── jwt.go                Tesla.SS256 JWT signing method, SignMessage
    │   ├── error.go              authentication.Error (MessageFault code + description)
    │   ├── *_test.go, protocol_doc_test.go   Verifies protocol.md examples
    │   └── test_data/            PEM fixtures
    ├── dispatcher/
    │   ├── dispatcher.go         Dispatcher: session management, Send, listen/process, cache import/export
    │   ├── session.go            session, CacheEntry, authorize/decrypt/processHello
    │   ├── receiver.go           receiverKey, receiver (response channel + SlidingWindow)
    │   └── dispatcher_test.go
    ├── schnorr/{schnorr.go,sign.go,*_test.go}   RFC 8235 Schnorr/P256 + RFC 6979 deterministic nonce
    └── log/logger.go             Global level logger (stderr, RFC3339 timestamps)
```

---

## 2. Layer Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ Applications: cmd/tesla-control, cmd/tesla-http-proxy, examples/*, user code │
└───────────────┬───────────────────────────────┬──────────────────────────────┘
                │                               │
      ┌─────────▼─────────┐            ┌────────▼────────┐
      │ pkg/cli (Config)  │            │ pkg/proxy       │  REST → Vehicle methods
      │ flags/env/keyring │            │ VIN lock, cache │
      └─────────┬─────────┘            └────────┬────────┘
                │                               │
      ┌─────────▼───────────────────────────────▼─────────────┐
      │ pkg/vehicle.Vehicle                                   │  Command API (Lock, ClimateOn, GetState ...)
      │  - VCSEC: vcsec.UnsignedMessage → readUntil           │
      │  - Infotainment: carserver.Action → Response          │
      │  - Retry loop (protocol.ShouldRetry)                  │
      └─────────┬─────────────────────────────────────────────┘
                │ sender interface
      ┌─────────▼─────────────────────────────────────────────┐
      │ internal/dispatcher.Dispatcher                        │  RoutableMessage layer
      │  - Per-domain session (handshake, cache)              │
      │  - receiverKey ↔ receiver matching                    │
      │  - listen goroutine: Unmarshal → process              │
      │  - Session info update, response decrypt, anti-replay │
      └─────────┬───────────────────────┬─────────────────────┘
                │                       │ authentication.Signer
                │              ┌────────▼───────────────────────┐
                │              │ internal/authentication        │  Crypto layer
                │              │  ECDH → K, metadata TLV,       │
                │              │  AES-GCM / HMAC, SlidingWindow │
                │              └────────────────────────────────┘
      ┌─────────▼─────────────────────────────────────────────┐
      │ pkg/connector.Connector ([]byte datagrams)            │  Transport layer
      │  ble.Connection (GATT, 2-byte length framing)         │
      │  inet.Connection (POST signed_command, JSON/base64)   │
      └───────────────────────────────────────────────────────┘
```

`pkg/account` determines the Fleet API host from the OAuth token, creates an `inet.Connection`, and passes it to `vehicle.NewVehicle`. `pkg/cache` stores/serializes lists of `dispatcher.CacheEntry` per VIN. `pkg/sign` and `internal/schnorr` are for JWS signing (fleet telemetry config) and are unrelated to the command path.

---

## 3. Responsibilities by Layer

### 3.1 `pkg/connector` (Transport)

| Method | Contract |
|---|---|
| `Receive() <-chan []byte` | Datagrams sent by the vehicle (serialized RoutableMessage bytes). Thread-safe. |
| `Send(ctx, []byte) error` | Sends a datagram. If the error implements `protocol.Error`, `MayHaveSucceeded` indicates whether it may have been received. |
| `VIN() string` | 17-character VIN. Used as-is in metadata `TAG_PERSONALIZATION`. |
| `Close()` | Idempotent. Subsequent behavior is undefined. |
| `PreferredAuthMethod()` | `AuthMethodGCM` (BLE) / `AuthMethodHMAC` (inet). Initial value of `Vehicle.authMethod`. |
| `RetryInterval()` | Retransmission interval (1s for both). |
| `AllowedLatency()` | Allowed latency for session info updates (BLE 4s, inet 10s). |

`FleetAPIConnector` adds `SendFleetAPICommand(ctx, endpoint, cmd)` and `Wakeup(ctx)`. Only `inet.Connection` implements it. `vehicle.Wakeup`, `vehicle.SetPINToDrive`, and `vehicle.SendAddKeyRequest` use a type assertion to check for this interface and change behavior accordingly.

### 3.2 `internal/dispatcher` (Routing/Sessions)

- `Dispatcher.New(conn, privateKey)`: generates a 16-byte random `address` (fixed routing address for Infotainment). `privateKey` may be nil (unauthenticated connection).
- `Start(ctx)`: starts the `listen` goroutine; the `ready` signal must be received before ctx expires. `Stop()`: closes the `terminate` channel and waits on `done`.
- `StartSessions(ctx, domains)`: runs `StartSession` per domain in parallel goroutines. If `domains == nil`, `[VCSEC, INFOTAINMENT]`. If any fails, returns the first error (but `context.Canceled` is ignored and the next result is examined).
- `StartSession(ctx, domain)`: if the session was loaded from cache and `s.ctx != nil`, returns immediately (`Session for %s loaded from cache`). Otherwise runs the `tryStartSession` loop: send `RequestSessionInfo` → handle whichever comes first of `readySignal`/response/`RetryInterval` timeout. On timeout, retransmit.
- `Send(ctx, msg, auth)`: see section 4 below.
- `Cache()`/`LoadCache(entries)`: session export/import. `LoadCache` creates sessions with `ready=true` and `readySignal` closed.
- `SetMaxLatency(d)`: updates only when `d > 0`.

### 3.3 `internal/authentication` (Cryptography)

| Type/function | Role |
|---|---|
| `ECDHPrivateKey` (interface) | `Exchange(remotePub) (Session, error)`, `PublicBytes() []byte`, `SchnorrSignature(msg)`. An interface so that HSM implementations are possible. |
| `Session` (interface) | `SessionInfoHMAC`, `Encrypt`, `Decrypt`, `LocalPublicBytes`, `NewHMAC(label)`. |
| `NativeECDHKey{*ecdsa.PrivateKey}` | Pure Go implementation. `Exchange`: `elliptic.P256().ScalarMult` → 32-byte x-coordinate → `sha1[:16]` → AES-GCM. |
| `NativeSession` | `gcm cipher.AEAD`, `key []byte` (K), `localPublic`. `subkey(label) = HMAC-SHA256(K, label)`. |
| `Peer` | `domain`, `verifierName` (= VIN bytes), `counter`, `epoch[16]`, `timeZero`, `session`. `timestamp()` = `time.Since(timeZero)` in seconds. |
| `Signer` | Client. `NewSigner`, `NewAuthenticatedSigner` (tag verification), `ImportSessionInfo`, `ExportSessionInfo`, `UpdateSessionInfo`, `UpdateSignedSessionInfo`, `Encrypt`, `AuthorizeHMAC`, `Decrypt`. |
| `Verifier` | Vehicle-side reference implementation. Used in tests and the fake vehicle in `dispatcher_test.go`. Not used in the real client path. |
| `metadata` | TLV hash builder enforcing tag order. `Checksum(msg)` appends `0xFF || msg` then calls Sum. |
| `SlidingWindow` | The first `Update` is always true (sets the baseline counter); afterwards `updateSlidingWindow` (window 32). |
| `RequestID(msg)` | Computes the request hash. |
| `SignMessage` / `SigningMethodSchnorrP256` | JWT `alg = "Tesla.SS256"`. `iss` is base64(PublicBytes), `aud` is an argument. |
| `Error{Code, Info}` | Error based on `MessageFault_E`. String form is `IncorrectEpoch: ...` (`errCodeString`). |
| `Dispatcher{ECDHPrivateKey}` | `Connect`/`ConnectAuthenticated` helpers. Shares only the name with `internal/dispatcher`; it is a different thing. |

### 3.4 `pkg/vehicle` (Command API)

- `Vehicle{Flags, dispatcher sender, vin, conn, authMethod, keyAvailable}`.
- Infotainment commands: `executeCarServerAction` → `getCarServerResponse` → `proto.Marshal(carserver.Action{ActionMsg: action})` → `v.Send(ctx, DomainInfotainment, payload, v.authMethod)` → parse `carserver.Response` → `NominalError` if `actionStatus.result == ERROR`.
- VCSEC commands: `getVCSECResult(ctx, payload, auth, done)` → `getReceiver` → `readUntil(recv, done)` → `unmarshalVCSECResponse`. `done` is a per-command termination function (`isWhitelistOperationComplete`, `commandStatus == nil`, first response for information requests).
- `SessionInfo(ctx, pub, domain)`: sends a handshake request with an arbitrary public key and returns the response `SessionInfo` (for confirming key enrollment).
- `SendMessage(ctx, msg)`: sends a RoutableMessage as-is without authentication (for the proxy/cardless pairing).

### 3.5 Others

| Package | Responsibility |
|---|---|
| `pkg/cache` | `SessionCache{MaxEntries, Vehicles map[vin][]CacheEntry}`. Approximate LRU (removes the oldest `CreatedAt`). JSON `Import/Export(File)`. |
| `pkg/cli` | `Config`: `Flag` mask (VIN/OAuth/PrivateKey/BLE), `RegisterCommandLineFlags`, `ReadFromEnvironment`, `LoadCredentials`, `Connect`/`ConnectRemote`/`ConnectLocal`, keyring save/load/delete, `DomainList` flag type. |
| `pkg/account` | `Account{UserAgent, Host, Subject}`. `New(token, ua)` determines the `fleet-api.*.tesla.com|tesla.cn|teslamotors.com` host from `aud`/`ou_code` in the JWT payload. `GetVehicle` is `inet.NewConnection` + `vehicle.NewVehicle`. |
| `pkg/proxy` | `Proxy.ServeHTTP`: `/health`, `/api/1/vehicles/<VIN>/command/<cmd>` (7 segments), `/api/1/vehicles/fleet_telemetry_config`; everything else is forwarded to Fleet API. [07-http-proxy.md](07-http-proxy.md). |
| `pkg/sign` | `SignMessageForVehicle(key, vin, app, claims)` aud=`com.tesla.vehicle.<vin>.<app>`, `SignMessageForFleet(key, app, claims)` aud=`com.tesla.fleet.<app>`. |
| `internal/schnorr` | `Sign(*ecdh.PrivateKey, msg)`, `Verify(pubBytes, msg, sig)`. SHA-256, deterministic nonce. |
| `internal/log` | `SetLevel(LevelNone/Error/Warning/Info/Debug)`, stderr output `2006-01-02T15:04:05Z07:00 [debug] ...`. |

---

## 4. Full Path of a Single Command (BLE, `car.Lock(ctx)`)

```
pkg/vehicle/security.go      Lock(ctx)
pkg/vehicle/vcsec.go         └ executeRKEAction(ctx, RKE_ACTION_LOCK)
                                ├ payload = proto.Marshal(vcsec.UnsignedMessage{RKEAction: LOCK})
                                └ getVCSECResult(ctx, payload, v.authMethod /*GCM*/, done)
                                   loop:
                                   ├ getReceiver(ctx, DOMAIN_VEHICLE_SECURITY, payload, auth)
pkg/vehicle/vehicle.go         │   └ RoutableMessage{ToDestination: Domain(2), Payload: bytes, Flags: v.Flags(2)}
internal/dispatcher/dispatcher.go  └ Dispatcher.Send(ctx, msg, AuthMethodGCM)
                                       ├ check listening (otherwise ErrNotConnected)
                                       ├ key.domain=VCSEC → addr = 16-byte random, key.uuid = 0 (VCSEC does not match on uuid)
                                       ├ msg.Uuid = 16-byte random, msg.FromDestination = RoutingAddress(addr)
                                       ├ look up session (missing or !ready → ErrNoSession)
internal/dispatcher/session.go         ├ session.authorize(ctx, msg, GCM)
                                       │   ├ lifetime = until ctx deadline or defaultExpiration 5s
                                       │   ├ wait on <-readySignal
internal/authentication/signer.go      │   └ Signer.Encrypt(msg, lifetime)
                                       │       ├ counter++ (error at 0xFFFFFFFF)
                                       │       ├ gcmData{Epoch, Counter, ExpiresAt = now+lifetime - timeZero}
internal/authentication/peer.go        │       ├ extractMetadata: SIGNATURE_TYPE(5), DOMAIN, PERSONALIZATION(VIN), EPOCH, EXPIRES_AT, COUNTER, FLAGS(when ≠0)
internal/authentication/metadata.go    │       ├ aad = SHA256(TLV... || 0xFF)
internal/authentication/native.go      │       ├ nonce(12B random), ciphertext, tag = AES-GCM(K).Seal
                                       │       └ msg.Payload = ciphertext; msg.SignatureData = {signer_identity.public_key, AES_GCM_Personalized_data}
                                       ├ resp = createHandler(key, RequestID(msg))   // requestSentAt = now, initialize SlidingWindow
                                       ├ encoded = proto.Marshal(msg)
pkg/connector/ble/ble.go               └ loop: conn.Send(ctx, encoded)
                                           ├ out = [len>>8, len&0xff] || encoded
                                           └ WriteCharacteristic(txChar, chunk, false) per blockLength
                                           (if the error is ShouldRetry, retry after RetryInterval)
--- vehicle ---
pkg/connector/ble/ble.go     Subscribe callback rx(p): inputBuffer append → flush() → inbox <- payload
internal/dispatcher/dispatcher.go  listen(): <-conn.Receive() → proto.Unmarshal → process(msg)
                                       ├ key.domain = FromDestination.Domain (uuid ignored for VCSEC)
                                       ├ ToDestination.RoutingAddress(16B) → key.address
                                       ├ look up handlers[key] (log and drop if missing)
                                       ├ checkForSessionUpdate(msg, handler)   // processHello if session_info is present (tag/latency checks)
                                       ├ decrypt(msg, handler)                 // Signer.Decrypt + SlidingWindow if AES_GCM_Response_data is present
                                       └ handler.ch <- msg (dropped if full)
pkg/vehicle/vcsec.go         readUntil(ctx, recv, done)
                                ├ unmarshalVCSECResponse(reply)
                                │   ├ protocol.GetError(reply)  // signed_message_fault, session_info status, operation_status
                                │   ├ payload nil → empty FromVCSECMessage (success)
                                │   ├ nominalError → NominalError{NominalVCSECError}
                                │   └ commandStatus: WAIT→ErrBusy, ERROR+whitelist code→KeychainError
                                └ done(fromVCSEC): for RKE, finishes when commandStatus == nil
                             recv.Close() → removed from handlers
                             if err is ShouldRetry, retry loop after RetryInterval; otherwise return
```

Infotainment (`car.ClimateOn`) branches as `executeCarServerAction` → `v.Send(ctx, DOMAIN_INFOTAINMENT, ...)` → `trySend` (single response, `recv.Recv()` once) → `carserver.Response` parsing; the path from the dispatcher down is the same. Differences: `key.address = d.address` (fixed), `key.uuid = msg.Uuid`, matched by the response's `request_uuid`.

### 4.1 receiverKey Matching Rules

| Domain | `from_destination.routing_address` | `uuid` / `request_uuid` | Reason |
|---|---|---|---|
| VCSEC (2) | New 16-byte random per request | Included in the request but not used for response matching (`key.uuid` = 0) | VCSEC does not echo `request_uuid` due to memory constraints |
| INFOTAINMENT (3) | Fixed `d.address` created when the dispatcher is created | Request uuid 16 bytes = response `request_uuid` | Distinguishes multiple concurrent requests |

`process` drops a message if the `request_uuid` length is neither 0 nor 16, if `to_destination` is a `Domain` (vehicle → another domain), or if the `routing_address` length is not 16.

---

## 5. Concurrency Model

| Lock/channel | Location | Protects | Notes |
|---|---|---|---|
| `ble.mu` (global `sync.Mutex`) | `pkg/connector/ble/ble.go` | Global `device` creation/reuse, scanning, all of `tryToConnect` | Held during the connection attempt → concurrent connection attempts to two vehicles are serialized |
| `Connection.lock` | 〃 | `Send` (block write order) | The `rx` callback takes no lock (assumes go-ble calls it from a single goroutine) |
| `Dispatcher.doneLock` | `internal/dispatcher/dispatcher.go` | `terminate`/`done` channels | `Stop` waits for listen to end via `<-d.done` |
| `Dispatcher.sessionLock` | 〃 | `sessions` map | `checkForSessionUpdate`/`decrypt` take `session.lock` while holding sessionLock (order: sessionLock → session.lock) |
| `Dispatcher.handlerLock` | 〃 | `handlers` map | `process` holds it only briefly; the channel send is outside the lock |
| `Dispatcher.latencyLock` | 〃 | `maxLatency` | |
| `session.lock` | `session.go` | `ctx` (Signer), `ready` | Do not hold during long operations (per comment). `authorize` takes it only after receiving `readySignal` |
| `session.readySignal` (chan, cap 1) | 〃 | Session-ready notification | `close`d on `processHello` success; `LoadCache` also `close`s it. Since it is a closed channel, multiple goroutines can receive from it repeatedly |
| `receiver.ch` (cap 10) | `receiver.go` | Response queue | Dropped when full |
| `inet.Connection.lock` | `pkg/connector/inet/inet.go` | `inbox`, `lastPoke` | `inbox = nil` after `Close` |
| `Proxy.vinLock` (`sync.Map` of chan) | `pkg/proxy/proxy.go` | Per-VIN command serialization | The owner wakes waiters by `Delete` then `close` |

Goroutines:

- One `Dispatcher.listen` (on Connect). All receive processing, session updates, and decryption run sequentially in this goroutine. Blocking here stalls all responses.
- `StartSessions`: one goroutine per domain, collected via the `results` channel.
- BLE scan callback: go-ble internal goroutine → delivered to `ch` (cap 1) in `scanVehicleBeacon`, `cancel()` on the first match.

---

## 6. Session Cache Data Structure

`internal/dispatcher/session.go`:

```go
type CacheEntry struct {
    CreatedAt   time.Time `json:"created_at"`  // Time Cache() was called. On load, the clock is restored as timeZero = created_at - clock_time
    Domain      int       `json:"domain"`      // 2 or 3
    SessionInfo []byte    `json:"data"`        // signatures.SessionInfo protobuf (counter, publicKey, epoch, clock_time)
}
```

`pkg/cache/cache.go` JSON (`MaxEntries` is serialized with its Go field name as-is):

```json
{
  "MaxEntries": 0,
  "vehicles": {
    "5YJ30123456789ABC": [
      {"created_at": "2026-09-26T07:46:00.000000+09:00", "domain": 2, "data": "CAYSQQTH...(base64)"},
      {"created_at": "2026-09-26T07:46:00.000000+09:00", "domain": 3, "data": "..."}
    ]
  }
}
```

- `Export` fills `SessionInfo.ClockTime` with `s.timestamp()` (current estimated vehicle clock). It must therefore be paired with `created_at` for the clock to be correct.
- `LoadCache` creates a Signer with `authentication.ImportSessionInfo(privateKey, VIN, data, created_at)` and marks it `ready=true`. If the private key differs, `Exchange` succeeds, but subsequent commands fail with `INVALID_SIGNATURE` etc., and resynchronization happens with the session info the vehicle sends.
- `SessionCache.Update(vin, entries)`, when `MaxEntries > 0` and it is exceeded, removes "the VIN whose most recent `CreatedAt` is the oldest".
- `pkg/cli` manages `TESLA_CACHE_FILE` or `~/.tesla-cache.json` with `cache.New(0)` (unlimited); the proxy uses `cache.New(10000)`.

---

## 7. Where Retry Policies Live

| Location | Retry condition | Interval | Termination |
|---|---|---|---|
| `Dispatcher.Send` (transmission itself) | `conn.Send` error is `ShouldRetry` | `conn.RetryInterval()` 1s | ctx expiry → `CommandError{ctx.Err(), PossibleSuccess:false, PossibleTemporary:true}` |
| `Dispatcher.tryStartSession` | `RetryInterval` elapsed without response/ready | 1s | ctx expiry |
| `Vehicle.StartSession` | `StartSessions` error is `ShouldRetry` | `RetryInterval` | ctx expiry |
| `Vehicle.Send` (Infotainment) | `trySend` error is `ShouldRetry` | `RetryInterval` | ctx expiry → `ctx.Err()` |
| `Vehicle.getVCSECResult` (VCSEC) | `readUntil` error is `ShouldRetry` (e.g. `ErrBusy`) | `RetryInterval` | ctx expiry |
| `inet.Connection.Wakeup` | `protocol.Temporary(err)` or `state != "online"` | **10s** | ctx expiry |
| `ble.NewConnectionFromScanResult` | `tryToConnect` returns `retry=true` and `!IsAdapterError` | Immediate (the scan waits on its own) | ctx expiry → returns the last error |
| `Proxy.forwardRequest` | HTTP 421 + `Alt-Svc: h2=https://...` | 1s | `MaxAttempts = 2` |

`ShouldRetry(err)`: true only when `err` is a `protocol.Error` and `!MayHaveSucceeded() && Temporary()`. Codes for which `RoutableMessageError.Temporary()` is true: `BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL, INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG`. Codes for which `MayHaveSucceeded()` is true: `ERROR_NONE`, `RESPONSE_MTU_EXCEEDED`. Full table in [08-errors.md](08-errors.md).

---

## 8. Dependencies (`go.mod`)

| Module | Version | Purpose |
|---|---|---|
| `github.com/go-ble/ble` | `v0.0.0-20240122180141-8c5522f54333` | BLE (darwin: CoreBluetooth via cbgo, linux: HCI socket) |
| `github.com/JuulLabs-OSS/cbgo` → replace `github.com/tinygo-org/cbgo v0.0.4` | | macOS CoreBluetooth bindings (cgo) |
| `github.com/99designs/keyring` | `v1.2.2` | OS keyring (macOS keychain, Linux secret-service/kwallet/pass/file, Windows wincred) |
| `github.com/cronokirby/saferith` | `v0.33.0` | Constant-time big-int (schnorr) |
| `github.com/golang-jwt/jwt/v5` | `v5.2.2` | JWS/JWT |
| `github.com/google/shlex` | `v0.0.0-20191202100458-e7afc7fbc510` | `tesla-control` interactive shell parsing |
| `golang.org/x/term` | `v0.5.0` | Keyring password prompt |
| `google.golang.org/protobuf` | `v1.34.2` | protobuf runtime |

---

## 9. Build Artifacts, Docker, CI

- `go install ./cmd/...` → `tesla-keygen`, `tesla-control`, `tesla-auth-token`, `tesla-http-proxy`, `tesla-jws` (GOBIN). `go build ./...` also compiles `examples/ble` and `examples/unlock`.
- `Dockerfile`: two stages. `go build -o ./build ./...` in `golang:1.23.0` → copied to `/usr/local/bin` in `gcr.io/distroless/base-debian12:nonroot`, `ENTRYPOINT ["tesla-http-proxy"]`. Other tools via `docker run --entrypoint tesla-control ...`. Because it is distroless, BLE (cgo/HCI) is effectively unusable in the container.
- `docker-compose.yml`: image `tesla/vehicle-command:latest`, port 4443, TLS paths/host/port/timeout/`TESLA_KEY_FILE`/`TESLA_VERBOSE` via env, `./config:/config` volume, `no-new-privileges:true`.
- CI `build.yml` (push/PR to main): checkout → setup-go 1.23.0 → `make format && git diff --exit-code` → golangci-lint v1.61.0 → `make linters` → `make test`. `publish.yml` deploys to Docker Hub (contents not verified; read it directly before modifying).

---

## 10. Test Map

| File | What it verifies |
|---|---|
| `internal/authentication/protocol_doc_test.go` | The AES-GCM example in `protocol.md` (test keys, K, metadata, plaintext `120452020801`) matches the implementation |
| `internal/authentication/signer_test.go` | Session info update rules (rejecting bad tag/challenge/counter/epoch/public key), counter rollover, export/import, expiration time range |
| `internal/authentication/verifier_test.go` | GCM/HMAC verification, flags, epoch rotation, reordering (window), tamper detection, expiration, handles |
| `internal/authentication/window_test.go` | `SlidingWindow` |
| `internal/authentication/metadata_test.go` | Tag order enforcement, 255-byte limit, checksum |
| `internal/authentication/ecdh_test.go`, `native_test.go` | Key loading (PEM variants), shared key, zero handling, public key encoding, shared secret padding |
| `internal/authentication/peer_test.go` | `RequestID` (including VCSEC 17-byte truncation) |
| `internal/authentication/jwt_test.go`, `internal/schnorr/*_test.go` | Tesla.SS256, Schnorr sign/verify, deterministic nonce |
| `internal/dispatcher/dispatcher_test.go` | Rejecting sends without a session, handshake, timeout, dropping bad messages, unsolicited session info, discarding corrupted/unauthenticated session info, retries, Stop, non-blocking response queue, keyless handshake, cache |
| `pkg/vehicle/vehicle_test.go` (+ `vehicle_mock_test.go`) | With a mock connector: session failure, connection retry/timeout, send error/timeout, retry failure |
| `pkg/vehicle/vcsec_test.go` | `NominalVCSECError`, unparseable responses, whitelist errors |
| `pkg/vehicle/security_test.go` | `IsValidPIN` |
| `pkg/protocol/error_test.go` | Classification of wrapped errors (`errors.As`), retryable codes |
| `pkg/protocol/key_test.go`, `external_impl_test.go` | `LoadPublicKey` formats, feasibility of external `ECDHPrivateKey` implementations |
| `pkg/cache/cache_test.go` | import/export, eviction |
| `pkg/account/account_test.go` | Token parsing, domain determination |
| `pkg/connector/inet/inet_test.go` | `Send` after `Close` → `ErrNotConnected` |
| `pkg/cli/config_test.go` | `DomainList` flag |
| `pkg/proxy/proxy_test.go`, `command_test.go`, `status_test.go` | Routing, auth headers, VIN checks, forwarding (XFF, Alt-Svc 421 retry, retry exhaustion, body size), command success/failure/fallback, VIN serialization, telemetry config signing, status code mapping |
| `cmd/tesla-control/commands_test.go` | `MinutesAfterMidnight`, `GetDays` |
| `cmd/tesla-http-proxy/main_test.go` | Environment variable parsing |

---

## Verification Checklist

- [ ] If you added a new file/package, did you reflect it in the section 1 tree and the section 2 diagram?
- [ ] If you changed the `Connector` interface, did you update all implementations in `ble`, `inet`, `pkg/vehicle/vehicle_mock_test.go`, and `internal/dispatcher/dispatcher_test.go`?
- [ ] Did you avoid reversing the dispatcher lock order (`sessionLock` → `session.lock`) or adding blocking calls in the `listen` goroutine?
- [ ] Did you leave the `receiverKey` matching rules (uuid ignored for VCSEC) unchanged? If you changed them, did you also change the fake vehicle in `dispatcher_test.go`?
- [ ] Did you leave the `CacheEntry` JSON field names (`created_at`, `domain`, `data`) unchanged? Changing them breaks compatibility with existing `~/.tesla-cache.json` files.
- [ ] If you added/modified a retry loop, did you check the `ShouldRetry` rules and the error type returned on ctx expiry (whether `MayHaveSucceeded`)?
- [ ] Did the section 10 tests pass in the user's environment?
