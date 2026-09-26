# 01. 아키텍처

`vehicle-command` 저장소의 패키지 계층, 각 계층의 책임, 명령 하나가 지나가는 전체 경로, 동시성 모델, 캐시 구조, 빌드/CI/테스트 구성.

관련 파일: `pkg/connector/connector.go`, `internal/dispatcher/dispatcher.go`, `internal/dispatcher/session.go`, `internal/dispatcher/receiver.go`, `internal/authentication/*.go`, `pkg/vehicle/vehicle.go`, `pkg/vehicle/vcsec.go`, `pkg/vehicle/infotainment.go`, `pkg/cache/cache.go`, `go.mod`, `Makefile`, `Dockerfile`, `.github/workflows/build.yml`

---

## 1. 디렉터리 트리

```
vehicle-command/
├── README.md                     설치, 프록시 설정, 키 배포 절차 (권위 있는 사용자 문서)
├── SECURITY.md                   취약점 신고: https://www.tesla.com/legal/security
├── PULL_REQUEST_TEMPLATE.md
├── LICENSE
├── go.mod / go.sum               모듈 github.com/teslamotors/vehicle-command, go 1.23
├── Makefile                      build/test/linters/format/install/proto-gen/doc-images/set-version
├── check-all.sh                  build+test+vet+gofmt 검사+shellcheck
├── .golangci.yml                 린터 설정 (00-agent-guide.md 2.3절)
├── Dockerfile                    golang:1.23.0 빌드 → distroless nonroot, ENTRYPOINT tesla-http-proxy
├── docker-compose.yml            프록시 예시 (env 기반 설정, ./config 볼륨)
├── .dockerignore / .gitignore
├── .github/
│   ├── ISSUE_TEMPLATE/{config.yml,issue.md}
│   └── workflows/{build.yml,publish.yml}   CI: format diff 검사, golangci-lint v1.61.0, make test
├── doc/
│   ├── authorization.puml/.png   OAuth 토큰 획득 흐름 (PlantUML)
│   └── request_diagram.puml/.png 프록시를 통한 명령 흐름
├── cmd/                          바이너리 (go install ./cmd/... 로 설치)
│   ├── tesla-keygen/{main.go,doc.go}          키 생성/삭제/내보내기/이관, 키링 저장
│   ├── tesla-control/{main.go,commands.go,commands_test.go,doc.go,README.md,.gitignore}
│   │                                            CLI (BLE/인터넷), commands.go에 전체 명령 표
│   ├── tesla-auth-token/{main.go,doc.go,README.md}   OAuth 토큰을 키링에 저장
│   ├── tesla-http-proxy/{main.go,server.go,main_test.go,doc.go}
│   │                                            REST → 서명 명령 프록시 (server.go는 자체 서명 인증서 헬퍼)
│   └── tesla-jws/{main.go,README.md}          Schnorr/P256 JWS 서명/검증 (fleet telemetry 설정용)
├── examples/
│   ├── ble/{main.go,doc.go}      BLE 스캔 → 연결 → 세션 → Unlock → ClimateOn
│   └── unlock/{unlock.go,doc.go} 인터넷(account) → Unlock
├── pkg/                          공개 API
│   ├── protocol/
│   │   ├── doc.go, domains.go    Domain 별칭/상수 (DomainNone/VCSEC/Infotainment)
│   │   ├── error.go              Error 인터페이스, CommandError, RoutableMessageError, NominalError, GetError, ShouldRetry
│   │   ├── key.go                ECDHPrivateKey/Session 재수출, Load/SavePrivateKey, LoadPublicKey, PublicKeyBytesFromHex
│   │   ├── receiver.go           Receiver 인터페이스
│   │   ├── protocol.md           프로토콜 사양 원문 (테스트 벡터)
│   │   ├── *_test.go, test/*.pem 키 로딩 테스트 자료
│   │   └── protobuf/             *.proto + 생성 코드 (carserver/, vcsec/, signatures/, universalmessage/, keys/, errors/, managedcharging/)
│   ├── connector/
│   │   ├── connector.go, doc.go  Connector / FleetAPIConnector 인터페이스, AuthMethod, BufferSize, MaxResponseLength
│   │   ├── ble/{ble.go,doc.go,device_darwin.go,device_linux.go,device_windows.go}   BLE 구현 (go-ble/ble)
│   │   └── inet/{inet.go,doc.go,inet_test.go}                                        Fleet API HTTPS 구현
│   ├── vehicle/
│   │   ├── vehicle.go            Vehicle 타입, NewVehicle, Connect, StartSession, Send, SendMessage, Wakeup, 캐시 연동
│   │   ├── vcsec.go              VCSEC 응답 파싱, readUntil, getVCSECResult, RKE/closure/whitelist 헬퍼
│   │   ├── infotainment.go       CarServer Action 실행, Ping, 미디어, 소프트웨어 업데이트, SeatPosition
│   │   ├── security.go           잠금/키 관리/PIN/발렛/센트리/게스트/패렌탈 컨트롤/Homelink
│   │   ├── actions.go            트렁크/프렁크/토노/경적/라이트/선루프/창문/충전 포트
│   │   ├── charge.go             충전 제한/전류/시작·정지/스케줄/출발 예약/저전력/액세서리 전원
│   │   ├── climate.go            공조/시트 히터·쿨러/스티어링 휠 히터/프리컨디셔닝/COP/클라이밋 키퍼
│   │   ├── state.go              BodyControllerState(VCSEC), GetState(Infotainment, StateCategory)
│   │   └── *_test.go             mock connector 기반 재시도/타임아웃 테스트
│   ├── cache/{cache.go,doc.go,cache_test.go,example_test.go}   SessionCache (LRU, JSON import/export)
│   ├── cli/{config.go,keyring.go,config_darwin.go,config_linux.go,config_windows.go,config_test.go}
│   │                             플래그/환경 변수/키링/연결 헬퍼
│   ├── account/{account.go,doc.go,version.txt,account_test.go}   OAuth 토큰 → Fleet API 호스트, GetVehicle, Get/Post, UpdateKey
│   ├── proxy/{proxy.go,command.go,doc.go,*_test.go}   HTTP 프록시 핸들러, 엔드포인트 → Vehicle 메서드 매핑
│   └── sign/{sign.go,doc.go}     SignMessageForVehicle / SignMessageForFleet (JWS)
└── internal/                     모듈 외부 임포트 불가
    ├── authentication/           암호 프로토콜 구현
    │   ├── ecdh.go               ECDHPrivateKey 인터페이스, SharedKeySizeBytes
    │   ├── crypto.go             Session 인터페이스, 상수(labels, epochIDLength, windowSize), epochStartTime
    │   ├── native.go             NativeSession(AES-GCM/HMAC), NativeECDHKey, NewECDHPrivateKey, LoadExternalECDHKey, UnmarshalECDHPrivateKey
    │   ├── metadata.go           TLV 메타데이터 해시 빌더
    │   ├── peer.go               Peer(공통 상태), extractMetadata, hmacTag, RequestID, responseMetadata
    │   ├── signer.go             Signer(클라이언트 측): Encrypt, AuthorizeHMAC, Decrypt, 세션 정보 import/export/update
    │   ├── verifier.go           Verifier(차량 측 시뮬레이션, 테스트/참조 구현)
    │   ├── window.go             SlidingWindow 안티리플레이
    │   ├── dispatcher.go         authentication.Dispatcher (여러 차량에 같은 키로 Signer 생성 헬퍼)
    │   ├── jwt.go                Tesla.SS256 JWT 서명 메서드, SignMessage
    │   ├── error.go              authentication.Error (MessageFault 코드 + 설명)
    │   ├── *_test.go, protocol_doc_test.go   protocol.md 예시 검증
    │   └── test_data/            PEM 픽스처
    ├── dispatcher/
    │   ├── dispatcher.go         Dispatcher: 세션 관리, Send, listen/process, 캐시 import/export
    │   ├── session.go            session, CacheEntry, authorize/decrypt/processHello
    │   ├── receiver.go           receiverKey, receiver (응답 채널 + SlidingWindow)
    │   └── dispatcher_test.go
    ├── schnorr/{schnorr.go,sign.go,*_test.go}   RFC 8235 Schnorr/P256 + RFC 6979 결정적 nonce
    └── log/logger.go             전역 레벨 로거 (stderr, RFC3339 타임스탬프)
```

---

## 2. 계층 다이어그램

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 애플리케이션: cmd/tesla-control, cmd/tesla-http-proxy, examples/*, 사용자 코드 │
└───────────────┬───────────────────────────────┬──────────────────────────┘
                │                               │
      ┌─────────▼─────────┐            ┌────────▼────────┐
      │ pkg/cli (Config)  │            │ pkg/proxy       │  REST → Vehicle 메서드
      │ 플래그/env/키링    │            │ VIN 락, 캐시     │
      └─────────┬─────────┘            └────────┬────────┘
                │                               │
      ┌─────────▼───────────────────────────────▼────────┐
      │ pkg/vehicle.Vehicle                              │  명령 API (Lock, ClimateOn, GetState ...)
      │  - VCSEC: vcsec.UnsignedMessage → readUntil      │
      │  - Infotainment: carserver.Action → Response     │
      │  - 재시도 루프 (protocol.ShouldRetry)             │
      └─────────┬────────────────────────────────────────┘
                │ sender 인터페이스
      ┌─────────▼────────────────────────────────────────┐
      │ internal/dispatcher.Dispatcher                    │  RoutableMessage 계층
      │  - 도메인별 session (핸드셰이크, 캐시)              │
      │  - receiverKey ↔ receiver 매칭                     │
      │  - listen goroutine: Unmarshal → process           │
      │  - 세션 정보 갱신, 응답 복호화, 안티리플레이         │
      └─────────┬───────────────────────┬─────────────────┘
                │                       │ authentication.Signer
                │              ┌────────▼─────────────────────┐
                │              │ internal/authentication       │  암호 계층
                │              │  ECDH → K, metadata TLV,      │
                │              │  AES-GCM / HMAC, SlidingWindow │
                │              └──────────────────────────────┘
      ┌─────────▼────────────────────────────────────────┐
      │ pkg/connector.Connector ([]byte 데이터그램)        │  전송 계층
      │  ble.Connection (GATT, 2바이트 길이 프레이밍)       │
      │  inet.Connection (POST signed_command, JSON/base64)│
      └──────────────────────────────────────────────────┘
```

`pkg/account`는 OAuth 토큰에서 Fleet API 호스트를 결정하고 `inet.Connection`을 만들어 `vehicle.NewVehicle`에 넘긴다. `pkg/cache`는 `dispatcher.CacheEntry` 목록을 VIN별로 보관/직렬화한다. `pkg/sign`과 `internal/schnorr`는 명령 경로와 무관한 JWS 서명(fleet telemetry 설정)용이다.

---

## 3. 계층별 책임

### 3.1 `pkg/connector` (전송)

| 메서드 | 계약 |
|---|---|
| `Receive() <-chan []byte` | 차량이 보낸 데이터그램(RoutableMessage 직렬화 바이트). 스레드 안전. |
| `Send(ctx, []byte) error` | 데이터그램 전송. 오류가 `protocol.Error`를 구현하면 `MayHaveSucceeded`로 수신 여부 판단 가능. |
| `VIN() string` | 17자 VIN. 메타데이터 `TAG_PERSONALIZATION`에 그대로 쓰인다. |
| `Close()` | 멱등. 이후 동작은 정의되지 않음. |
| `PreferredAuthMethod()` | `AuthMethodGCM`(BLE) / `AuthMethodHMAC`(inet). `Vehicle.authMethod`의 초기값. |
| `RetryInterval()` | 재전송 간격 (둘 다 1s). |
| `AllowedLatency()` | 세션 정보 갱신 허용 지연 (BLE 4s, inet 10s). |

`FleetAPIConnector`는 여기에 `SendFleetAPICommand(ctx, endpoint, cmd)`와 `Wakeup(ctx)`를 더한다. `inet.Connection`만 구현한다. `vehicle.Wakeup`, `vehicle.SetPINToDrive`, `vehicle.SendAddKeyRequest`는 타입 단언으로 이 인터페이스 여부를 검사해 동작을 바꾼다.

### 3.2 `internal/dispatcher` (라우팅/세션)

- `Dispatcher.New(conn, privateKey)`: 16바이트 랜덤 `address`(Infotainment용 고정 routing address) 생성. `privateKey`는 nil 허용(비인증 연결).
- `Start(ctx)`: `listen` goroutine 시작, `ready` 신호를 ctx 만료 전에 받아야 함. `Stop()`: `terminate` 채널 닫고 `done` 대기.
- `StartSessions(ctx, domains)`: 도메인별 `StartSession`을 goroutine으로 병렬 실행. `domains == nil`이면 `[VCSEC, INFOTAINMENT]`. 하나라도 실패하면 첫 오류 반환(단, `context.Canceled`는 무시하고 다음 결과를 본다).
- `StartSession(ctx, domain)`: 세션이 캐시에서 로드되어 `s.ctx != nil`이면 즉시 반환(`Session for %s loaded from cache`). 아니면 `tryStartSession` 루프: `RequestSessionInfo` 전송 → `readySignal`/응답/`RetryInterval` 타임아웃 중 먼저 오는 것 처리. 타임아웃이면 재전송.
- `Send(ctx, msg, auth)`: 아래 4절 참조.
- `Cache()`/`LoadCache(entries)`: 세션 export/import. `LoadCache`는 `ready=true`, `readySignal` 닫힌 상태의 세션을 만든다.
- `SetMaxLatency(d)`: `d > 0`일 때만 갱신.

### 3.3 `internal/authentication` (암호)

| 타입/함수 | 역할 |
|---|---|
| `ECDHPrivateKey` (인터페이스) | `Exchange(remotePub) (Session, error)`, `PublicBytes() []byte`, `SchnorrSignature(msg)`. HSM 구현 가능하도록 인터페이스. |
| `Session` (인터페이스) | `SessionInfoHMAC`, `Encrypt`, `Decrypt`, `LocalPublicBytes`, `NewHMAC(label)`. |
| `NativeECDHKey{*ecdsa.PrivateKey}` | 순수 Go 구현. `Exchange`: `elliptic.P256().ScalarMult` → x좌표 32바이트 → `sha1[:16]` → AES-GCM. |
| `NativeSession` | `gcm cipher.AEAD`, `key []byte`(K), `localPublic`. `subkey(label) = HMAC-SHA256(K, label)`. |
| `Peer` | `domain`, `verifierName`(=VIN 바이트), `counter`, `epoch[16]`, `timeZero`, `session`. `timestamp()` = `time.Since(timeZero)` 초. |
| `Signer` | 클라이언트. `NewSigner`, `NewAuthenticatedSigner`(태그 검증), `ImportSessionInfo`, `ExportSessionInfo`, `UpdateSessionInfo`, `UpdateSignedSessionInfo`, `Encrypt`, `AuthorizeHMAC`, `Decrypt`. |
| `Verifier` | 차량 측 참조 구현. 테스트와 `dispatcher_test.go`의 가짜 차량에 쓰인다. 실제 클라이언트 경로에서는 사용되지 않는다. |
| `metadata` | 태그 순서 강제 TLV 해시 빌더. `Checksum(msg)`는 `0xFF || msg`를 추가 후 Sum. |
| `SlidingWindow` | 첫 `Update`는 무조건 true(기준 카운터 설정), 이후 `updateSlidingWindow`(윈도우 32). |
| `RequestID(msg)` | 요청 해시 계산. |
| `SignMessage` / `SigningMethodSchnorrP256` | JWT `alg = "Tesla.SS256"`. `iss`는 base64(PublicBytes), `aud`는 인자. |
| `Error{Code, Info}` | `MessageFault_E` 기반 오류. 문자열은 `IncorrectEpoch: ...` 형태(`errCodeString`). |
| `Dispatcher{ECDHPrivateKey}` | `Connect`/`ConnectAuthenticated` 헬퍼. `internal/dispatcher`와 이름만 같고 다른 것. |

### 3.4 `pkg/vehicle` (명령 API)

- `Vehicle{Flags, dispatcher sender, vin, conn, authMethod, keyAvailable}`.
- Infotainment 명령: `executeCarServerAction` → `getCarServerResponse` → `proto.Marshal(carserver.Action{ActionMsg: action})` → `v.Send(ctx, DomainInfotainment, payload, v.authMethod)` → `carserver.Response` 파싱 → `actionStatus.result == ERROR`면 `NominalError`.
- VCSEC 명령: `getVCSECResult(ctx, payload, auth, done)` → `getReceiver` → `readUntil(recv, done)` → `unmarshalVCSECResponse`. `done`은 명령 종류별 종료 판정 함수 (`isWhitelistOperationComplete`, `commandStatus == nil`, 정보 요청은 첫 응답).
- `SessionInfo(ctx, pub, domain)`: 임의 공개 키로 핸드셰이크 요청을 보내고 응답 `SessionInfo`를 반환 (키 등록 확인용).
- `SendMessage(ctx, msg)`: 인증 없이 RoutableMessage를 그대로 전송(프록시/카드리스 페어링 용도).

### 3.5 나머지

| 패키지 | 책임 |
|---|---|
| `pkg/cache` | `SessionCache{MaxEntries, Vehicles map[vin][]CacheEntry}`. LRU 근사(가장 오래된 `CreatedAt` 제거). JSON `Import/Export(File)`. |
| `pkg/cli` | `Config`: `Flag` 마스크(VIN/OAuth/PrivateKey/BLE), `RegisterCommandLineFlags`, `ReadFromEnvironment`, `LoadCredentials`, `Connect`/`ConnectRemote`/`ConnectLocal`, 키링 저장/로드/삭제, `DomainList` 플래그 타입. |
| `pkg/account` | `Account{UserAgent, Host, Subject}`. `New(token, ua)`는 JWT payload의 `aud`/`ou_code`에서 `fleet-api.*.tesla.com|tesla.cn|teslamotors.com` 호스트 결정. `GetVehicle`은 `inet.NewConnection` + `vehicle.NewVehicle`. |
| `pkg/proxy` | `Proxy.ServeHTTP`: `/health`, `/api/1/vehicles/<VIN>/command/<cmd>` (7세그먼트), `/api/1/vehicles/fleet_telemetry_config`, 나머지는 Fleet API로 포워딩. [07-http-proxy.md](07-http-proxy.md). |
| `pkg/sign` | `SignMessageForVehicle(key, vin, app, claims)` aud=`com.tesla.vehicle.<vin>.<app>`, `SignMessageForFleet(key, app, claims)` aud=`com.tesla.fleet.<app>`. |
| `internal/schnorr` | `Sign(*ecdh.PrivateKey, msg)`, `Verify(pubBytes, msg, sig)`. SHA-256, 결정적 nonce. |
| `internal/log` | `SetLevel(LevelNone/Error/Warning/Info/Debug)`, stderr 출력 `2006-01-02T15:04:05Z07:00 [debug] ...`. |

---

## 4. 명령 하나의 전체 경로 (BLE, `car.Lock(ctx)`)

```
pkg/vehicle/security.go      Lock(ctx)
pkg/vehicle/vcsec.go         └ executeRKEAction(ctx, RKE_ACTION_LOCK)
                                ├ payload = proto.Marshal(vcsec.UnsignedMessage{RKEAction: LOCK})
                                └ getVCSECResult(ctx, payload, v.authMethod /*GCM*/, done)
                                   loop:
                                   ├ getReceiver(ctx, DOMAIN_VEHICLE_SECURITY, payload, auth)
pkg/vehicle/vehicle.go         │   └ RoutableMessage{ToDestination: Domain(2), Payload: bytes, Flags: v.Flags(2)}
internal/dispatcher/dispatcher.go  └ Dispatcher.Send(ctx, msg, AuthMethodGCM)
                                       ├ listening 확인 (아니면 ErrNotConnected)
                                       ├ key.domain=VCSEC → addr = 16바이트 랜덤, key.uuid = 0 (VCSEC은 uuid 매칭 안 함)
                                       ├ msg.Uuid = 16바이트 랜덤, msg.FromDestination = RoutingAddress(addr)
                                       ├ session 조회 (없거나 !ready → ErrNoSession)
internal/dispatcher/session.go         ├ session.authorize(ctx, msg, GCM)
                                       │   ├ lifetime = ctx deadline까지 or defaultExpiration 5s
                                       │   ├ <-readySignal 대기
internal/authentication/signer.go      │   └ Signer.Encrypt(msg, lifetime)
                                       │       ├ counter++ (0xFFFFFFFF면 오류)
                                       │       ├ gcmData{Epoch, Counter, ExpiresAt = now+lifetime - timeZero}
internal/authentication/peer.go        │       ├ extractMetadata: SIGNATURE_TYPE(5), DOMAIN, PERSONALIZATION(VIN), EPOCH, EXPIRES_AT, COUNTER, FLAGS(≠0일 때)
internal/authentication/metadata.go    │       ├ aad = SHA256(TLV... || 0xFF)
internal/authentication/native.go      │       ├ nonce(12B 랜덤), ciphertext, tag = AES-GCM(K).Seal
                                       │       └ msg.Payload = ciphertext; msg.SignatureData = {signer_identity.public_key, AES_GCM_Personalized_data}
                                       ├ resp = createHandler(key, RequestID(msg))   // requestSentAt = now, SlidingWindow 초기화
                                       ├ encoded = proto.Marshal(msg)
pkg/connector/ble/ble.go               └ loop: conn.Send(ctx, encoded)
                                           ├ out = [len>>8, len&0xff] || encoded
                                           └ blockLength 단위로 WriteCharacteristic(txChar, chunk, false)
                                           (오류가 ShouldRetry면 RetryInterval 후 재시도)
--- 차량 ---
pkg/connector/ble/ble.go     Subscribe 콜백 rx(p): inputBuffer append → flush() → inbox <- payload
internal/dispatcher/dispatcher.go  listen(): <-conn.Receive() → proto.Unmarshal → process(msg)
                                       ├ key.domain = FromDestination.Domain (VCSEC이면 uuid 무시)
                                       ├ ToDestination.RoutingAddress(16B) → key.address
                                       ├ handlers[key] 조회 (없으면 드롭 로그)
                                       ├ checkForSessionUpdate(msg, handler)   // session_info 있으면 processHello (태그/지연 검사)
                                       ├ decrypt(msg, handler)                 // AES_GCM_Response_data 있으면 Signer.Decrypt + SlidingWindow
                                       └ handler.ch <- msg (가득 차면 드롭)
pkg/vehicle/vcsec.go         readUntil(ctx, recv, done)
                                ├ unmarshalVCSECResponse(reply)
                                │   ├ protocol.GetError(reply)  // signed_message_fault, session_info status, operation_status
                                │   ├ payload nil → 빈 FromVCSECMessage (성공)
                                │   ├ nominalError → NominalError{NominalVCSECError}
                                │   └ commandStatus: WAIT→ErrBusy, ERROR+whitelist code→KeychainError
                                └ done(fromVCSEC): RKE는 commandStatus == nil 이면 종료
                             recv.Close() → handlers에서 제거
                             err가 ShouldRetry면 RetryInterval 후 loop 재시도, 아니면 반환
```

Infotainment(`car.ClimateOn`)는 `executeCarServerAction` → `v.Send(ctx, DOMAIN_INFOTAINMENT, ...)` → `trySend`(단일 응답, `recv.Recv()` 한 번) → `carserver.Response` 파싱으로 갈라지며, dispatcher 이하 경로는 같다. 차이: `key.address = d.address`(고정), `key.uuid = msg.Uuid`, 응답의 `request_uuid`로 매칭.

### 4.1 receiverKey 매칭 규칙

| 도메인 | `from_destination.routing_address` | `uuid` / `request_uuid` | 이유 |
|---|---|---|---|
| VCSEC (2) | 요청마다 새 16바이트 랜덤 | 요청에는 넣지만 응답 매칭에 사용 안 함 (`key.uuid` = 0) | VCSEC 메모리 제약으로 `request_uuid` 미회신 |
| INFOTAINMENT (3) | dispatcher 생성 시 만든 고정 `d.address` | 요청 uuid 16바이트 = 응답 `request_uuid` | 여러 요청을 동시에 구분 |

`process`는 `request_uuid` 길이가 0 또는 16이 아니면 드롭, `to_destination`이 `Domain`이면(차량→다른 도메인) 드롭, `routing_address` 길이가 16이 아니면 드롭.

---

## 5. 동시성 모델

| 락/채널 | 위치 | 보호 대상 | 주의 |
|---|---|---|---|
| `ble.mu` (전역 `sync.Mutex`) | `pkg/connector/ble/ble.go` | 전역 `device` 생성/재사용, 스캔, `tryToConnect` 전체 | 연결 시도 동안 잡고 있음 → 동시에 두 차량 연결 시도는 직렬화됨 |
| `Connection.lock` | 〃 | `Send` (블록 쓰기 순서) | `rx` 콜백은 락 없음 (go-ble가 단일 goroutine에서 호출한다고 가정) |
| `Dispatcher.doneLock` | `internal/dispatcher/dispatcher.go` | `terminate`/`done` 채널 | `Stop`은 `<-d.done`으로 listen 종료 대기 |
| `Dispatcher.sessionLock` | 〃 | `sessions` 맵 | `checkForSessionUpdate`/`decrypt`는 sessionLock 잡은 채 `session.lock`을 잡음 (순서: sessionLock → session.lock) |
| `Dispatcher.handlerLock` | 〃 | `handlers` 맵 | `process`는 짧게만 잡고 채널 send는 락 밖 |
| `Dispatcher.latencyLock` | 〃 | `maxLatency` | |
| `session.lock` | `session.go` | `ctx`(Signer), `ready` | 장시간 작업 중 보유 금지 (주석). `authorize`는 `readySignal` 수신 후에만 잡음 |
| `session.readySignal` (chan, cap 1) | 〃 | 세션 준비 알림 | `processHello` 성공 시 `close`; `LoadCache`도 `close`. 닫힌 채널이므로 여러 goroutine이 반복 수신 가능 |
| `receiver.ch` (cap 10) | `receiver.go` | 응답 큐 | 가득 차면 드롭 |
| `inet.Connection.lock` | `pkg/connector/inet/inet.go` | `inbox`, `lastPoke` | `Close` 후 `inbox = nil` |
| `Proxy.vinLock` (`sync.Map` of chan) | `pkg/proxy/proxy.go` | VIN별 명령 직렬화 | 소유자가 `Delete` 후 `close`로 대기자 깨움 |

goroutine:

- `Dispatcher.listen` 1개 (Connect 시). 모든 수신 처리와 세션 갱신, 복호화가 이 goroutine에서 순차 실행된다. 여기서 블로킹하면 모든 응답이 멈춘다.
- `StartSessions`: 도메인 수만큼 goroutine, `results` 채널로 수집.
- BLE 스캔 콜백: go-ble 내부 goroutine → `scanVehicleBeacon`의 `ch`(cap 1)로 전달, 첫 매치에서 `cancel()`.

---

## 6. 세션 캐시 데이터 구조

`internal/dispatcher/session.go`:

```go
type CacheEntry struct {
    CreatedAt   time.Time `json:"created_at"`  // Cache() 호출 시각. 로드 시 timeZero = created_at - clock_time 로 시계 복원
    Domain      int       `json:"domain"`      // 2 또는 3
    SessionInfo []byte    `json:"data"`        // signatures.SessionInfo protobuf (counter, publicKey, epoch, clock_time)
}
```

`pkg/cache/cache.go` JSON (`MaxEntries`는 Go 필드 이름 그대로 직렬화됨):

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

- `Export`는 `SessionInfo.ClockTime`을 `s.timestamp()`(현재 추정 차량 시계)로 채운다. 따라서 `created_at`과 짝을 이뤄야 시계가 맞는다.
- `LoadCache`는 `authentication.ImportSessionInfo(privateKey, VIN, data, created_at)`로 Signer를 만들고 `ready=true`로 표시한다. 개인 키가 다르면 `Exchange`는 성공하지만 이후 명령이 `INVALID_SIGNATURE` 등으로 실패하고 차량이 보내는 세션 정보로 재동기화된다.
- `SessionCache.Update(vin, entries)`는 `MaxEntries > 0`이고 초과 시 "가장 최근 `CreatedAt`이 가장 오래된 VIN"을 제거한다.
- `pkg/cli`는 `TESLA_CACHE_FILE` 또는 `~/.tesla-cache.json`을 `cache.New(0)`(무제한)으로 관리, 프록시는 `cache.New(10000)`.

---

## 7. 재시도 정책 위치

| 위치 | 재시도 조건 | 간격 | 종료 |
|---|---|---|---|
| `Dispatcher.Send` (전송 자체) | `conn.Send` 오류가 `ShouldRetry` | `conn.RetryInterval()` 1s | ctx 만료 → `CommandError{ctx.Err(), PossibleSuccess:false, PossibleTemporary:true}` |
| `Dispatcher.tryStartSession` | 응답/ready 없이 `RetryInterval` 경과 | 1s | ctx 만료 |
| `Vehicle.StartSession` | `StartSessions` 오류가 `ShouldRetry` | `RetryInterval` | ctx 만료 |
| `Vehicle.Send` (Infotainment) | `trySend` 오류가 `ShouldRetry` | `RetryInterval` | ctx 만료 → `ctx.Err()` |
| `Vehicle.getVCSECResult` (VCSEC) | `readUntil` 오류가 `ShouldRetry` (예: `ErrBusy`) | `RetryInterval` | ctx 만료 |
| `inet.Connection.Wakeup` | `protocol.Temporary(err)` 또는 `state != "online"` | **10s** | ctx 만료 |
| `ble.NewConnectionFromScanResult` | `tryToConnect`가 `retry=true` 이고 `!IsAdapterError` | 즉시 (스캔이 자체 대기) | ctx 만료 → 마지막 오류 반환 |
| `Proxy.forwardRequest` | HTTP 421 + `Alt-Svc: h2=https://...` | 1s | `MaxAttempts = 2` |

`ShouldRetry(err)`: `err`가 `protocol.Error`이고 `!MayHaveSucceeded() && Temporary()`일 때만 true. `RoutableMessageError.Temporary()`가 true인 코드: `BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL, INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG`. `MayHaveSucceeded()`가 true인 코드: `ERROR_NONE`, `RESPONSE_MTU_EXCEEDED`. 자세한 표는 [08-errors.md](08-errors.md).

---

## 8. 의존성 (`go.mod`)

| 모듈 | 버전 | 용도 |
|---|---|---|
| `github.com/go-ble/ble` | `v0.0.0-20240122180141-8c5522f54333` | BLE (darwin: CoreBluetooth via cbgo, linux: HCI 소켓) |
| `github.com/JuulLabs-OSS/cbgo` → replace `github.com/tinygo-org/cbgo v0.0.4` | | macOS CoreBluetooth 바인딩 (cgo) |
| `github.com/99designs/keyring` | `v1.2.2` | OS 키링 (macOS keychain, Linux secret-service/kwallet/pass/file, Windows wincred) |
| `github.com/cronokirby/saferith` | `v0.33.0` | 상수 시간 big-int (schnorr) |
| `github.com/golang-jwt/jwt/v5` | `v5.2.2` | JWS/JWT |
| `github.com/google/shlex` | `v0.0.0-20191202100458-e7afc7fbc510` | `tesla-control` 대화형 셸 파싱 |
| `golang.org/x/term` | `v0.5.0` | 키링 비밀번호 프롬프트 |
| `google.golang.org/protobuf` | `v1.34.2` | protobuf 런타임 |

---

## 9. 빌드 산출물, Docker, CI

- `go install ./cmd/...` → `tesla-keygen`, `tesla-control`, `tesla-auth-token`, `tesla-http-proxy`, `tesla-jws` (GOBIN). `go build ./...`는 `examples/ble`, `examples/unlock`도 컴파일한다.
- `Dockerfile`: 2단계. `golang:1.23.0`에서 `go build -o ./build ./...` → `gcr.io/distroless/base-debian12:nonroot`의 `/usr/local/bin`에 복사, `ENTRYPOINT ["tesla-http-proxy"]`. 다른 도구는 `docker run --entrypoint tesla-control ...`. distroless라 BLE(cgo/HCI)는 컨테이너에서 실질적으로 사용 불가.
- `docker-compose.yml`: 이미지 `tesla/vehicle-command:latest`, 포트 4443, env로 TLS 경로/호스트/포트/타임아웃/`TESLA_KEY_FILE`/`TESLA_VERBOSE`, `./config:/config` 볼륨, `no-new-privileges:true`.
- CI `build.yml` (push/PR to main): checkout → setup-go 1.23.0 → `make format && git diff --exit-code` → golangci-lint v1.61.0 → `make linters` → `make test`. `publish.yml`은 Docker Hub 배포(내용 미확인, 수정 시 직접 읽을 것).

---

## 10. 테스트 지도

| 파일 | 검증 대상 |
|---|---|
| `internal/authentication/protocol_doc_test.go` | `protocol.md`의 AES-GCM 예시(테스트 키, K, 메타데이터, 평문 `120452020801`)가 구현과 일치 |
| `internal/authentication/signer_test.go` | 세션 정보 갱신 규칙(잘못된 태그/challenge/카운터/epoch/공개 키 거부), 카운터 롤오버, export/import, 만료 시간 범위 |
| `internal/authentication/verifier_test.go` | GCM/HMAC 검증, 플래그, epoch 회전, 순서 뒤바뀜(윈도우), 변조 탐지, 만료, 핸들 |
| `internal/authentication/window_test.go` | `SlidingWindow` |
| `internal/authentication/metadata_test.go` | 태그 순서 강제, 255바이트 제한, 체크섬 |
| `internal/authentication/ecdh_test.go`, `native_test.go` | 키 로딩(PEM 변형), 공유 키, 0 처리, 공개 키 인코딩, 공유 비밀 패딩 |
| `internal/authentication/peer_test.go` | `RequestID` (VCSEC 17바이트 절단 포함) |
| `internal/authentication/jwt_test.go`, `internal/schnorr/*_test.go` | Tesla.SS256, Schnorr 서명/검증, 결정적 nonce |
| `internal/dispatcher/dispatcher_test.go` | 세션 없는 전송 거부, 핸드셰이크, 타임아웃, 잘못된 메시지 드롭, 비요청 세션 정보, 손상/비인증 세션 정보 폐기, 재시도, Stop, 응답 큐 비블로킹, 키 없는 핸드셰이크, 캐시 |
| `pkg/vehicle/vehicle_test.go` (+ `vehicle_mock_test.go`) | mock connector로 세션 실패, 연결 재시도/타임아웃, 전송 오류/타임아웃, 재시도 실패 |
| `pkg/vehicle/vcsec_test.go` | `NominalVCSECError`, 파싱 불가 응답, whitelist 오류 |
| `pkg/vehicle/security_test.go` | `IsValidPIN` |
| `pkg/protocol/error_test.go` | 래핑된 오류 분류(`errors.As`), 재시도 가능 코드 |
| `pkg/protocol/key_test.go`, `external_impl_test.go` | `LoadPublicKey` 형식들, 외부 `ECDHPrivateKey` 구현 가능성 |
| `pkg/cache/cache_test.go` | import/export, eviction |
| `pkg/account/account_test.go` | 토큰 파싱, 도메인 결정 |
| `pkg/connector/inet/inet_test.go` | `Close` 후 `Send` → `ErrNotConnected` |
| `pkg/cli/config_test.go` | `DomainList` 플래그 |
| `pkg/proxy/proxy_test.go`, `command_test.go`, `status_test.go` | 라우팅, 인증 헤더, VIN 검사, 포워딩(XFF, Alt-Svc 421 재시도, 재시도 소진, 본문 크기), 명령 성공/실패/폴백, VIN 직렬화, telemetry config 서명, 상태 코드 매핑 |
| `cmd/tesla-control/commands_test.go` | `MinutesAfterMidnight`, `GetDays` |
| `cmd/tesla-http-proxy/main_test.go` | 환경 변수 파싱 |

---

## 검증 체크리스트

- [ ] 새 파일/패키지를 추가했다면 1절 트리와 2절 다이어그램에 반영했는가?
- [ ] `Connector` 인터페이스를 바꿨다면 `ble`, `inet`, `pkg/vehicle/vehicle_mock_test.go`, `internal/dispatcher/dispatcher_test.go`의 구현을 모두 갱신했는가?
- [ ] dispatcher의 락 순서(`sessionLock` → `session.lock`)를 뒤집거나 `listen` goroutine에서 블로킹 호출을 추가하지 않았는가?
- [ ] `receiverKey` 매칭 규칙(VCSEC uuid 무시)을 바꾸지 않았는가? 바꿨다면 `dispatcher_test.go`의 가짜 차량도 함께 바꿨는가?
- [ ] `CacheEntry` JSON 필드 이름(`created_at`, `domain`, `data`)을 바꾸지 않았는가? 바꾸면 기존 `~/.tesla-cache.json`과 호환이 깨진다.
- [ ] 재시도 루프를 추가/수정했다면 `ShouldRetry` 규칙과 ctx 만료 시 반환 오류 타입(`MayHaveSucceeded` 여부)을 확인했는가?
- [ ] 10절 테스트가 사용자 환경에서 통과했는가?
