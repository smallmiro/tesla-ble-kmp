# 00. 에이전트 작업 가이드

`vehicle-command/` 저장소를 수정하거나 이 프로토콜의 클라이언트를 구현하는 AI 에이전트가 **가장 먼저** 읽어야 하는 규칙, 불변 조건, 빠른 참조 카드.

관련 파일: `README.md`, `Makefile`, `check-all.sh`, `.golangci.yml`, `go.mod`, `pkg/protocol/protocol.md`, `SECURITY.md`

---

## 1. 범위와 기준

| 항목 | 값 |
|---|---|
| 저장소 위치 (이 문서 기준) | `../../vehicle-command/` |
| Go 모듈 | `github.com/teslamotors/vehicle-command` |
| 기준 커밋 | `a4b43c1` (Fix wrapped protocol error classification), 태그 `v0.4.1` 이후 16 커밋 (`v0.4.1-16-ga4b43c1`) |
| `pkg/account/version.txt` | `0.4.1` (User-Agent에 `tesla-sdk/0.4.1`로 포함) |
| Go 버전 | `go 1.23` (`go.mod`), CI는 1.23.0, Docker 빌드 이미지 `golang:1.23.0` |
| 지원 OS | macOS, Linux. Windows는 BLE 제외 빌드 가능 (`pkg/connector/ble/device_windows.go`는 항상 `not supported on Windows` 반환) |
| 이 매뉴얼의 나머지 파일 | [README.md](README.md)의 작업별 표 참고 |

이 저장소는 **v0.x** 이므로 API 안정성이 보장되지 않는다 (`README.md` 마지막 문단). 공개 API를 바꿀 때는 호출처(`cmd/*`, `examples/*`, `pkg/proxy`, `pkg/cli`)를 함께 수정한다.

---

## 2. 작업 규칙

### 2.1 수정 전에 읽을 것

| 수정 대상 | 먼저 읽을 파일 (저장소) | 이 매뉴얼 |
|---|---|---|
| `pkg/connector/ble/*` | `pkg/connector/connector.go` (인터페이스 계약), `examples/ble/main.go` | [02-ble-transport.md](02-ble-transport.md) |
| `internal/dispatcher/*` | `internal/authentication/signer.go`, `pkg/protocol/receiver.go`, `pkg/cache/cache.go` | [01-architecture.md](01-architecture.md), [03-protocol.md](03-protocol.md) |
| `internal/authentication/*` | `pkg/protocol/protocol.md` **전체**, `pkg/protocol/protobuf/signatures.proto`, `internal/authentication/protocol_doc_test.go` | [03-protocol.md](03-protocol.md) |
| `pkg/vehicle/*` (명령 추가) | `pkg/protocol/protobuf/car_server.proto` 또는 `vcsec.proto`, `cmd/tesla-control/commands.go`, `pkg/proxy/command.go` | [05-command-catalog.md](05-command-catalog.md), [09-recipes.md](09-recipes.md) |
| `pkg/proxy/*`, `cmd/tesla-http-proxy/*` | `pkg/proxy/proxy_test.go` (동작 계약이 테스트로 명시됨) | [07-http-proxy.md](07-http-proxy.md) |
| `pkg/cli/*` | `cmd/tesla-control/main.go`, `cmd/tesla-keygen/main.go` (호출 순서) | [06-cli-tools.md](06-cli-tools.md) |
| `*.proto` | `Makefile`의 `proto-gen` 타깃 | [03-protocol.md](03-protocol.md) |

### 2.2 빌드, 테스트, 린트

```bash
cd vehicle-command
go build ./...          # 모든 패키지/바이너리 컴파일
go test ./...           # 단위 테스트 (make test 는 -cover 포함)
go vet ./...
./check-all.sh          # build + test + vet + gofmt 변경 없음 확인 + shellcheck(있을 때)
make test               # go install ./cmd/... 후 go test -cover ./... && go vet ./...
make linters            # golangci-lint run -v --exclude-use-default=false --timeout 30s (CI는 v1.61.0)
make format             # set-version 후 go fmt ./...
make proto-gen          # protoc --go_out ... pkg/protocol/protobuf/*.proto (protoc + protoc-gen-go 필요)
make doc-images         # docker plantuml 로 doc/*.puml → png
```

주의:

- `make format`과 `make build`는 `set-version` 타깃을 먼저 실행한다. 이 타깃은 `git describe --tags --abbrev=0` 결과에서 `v`를 떼어 `pkg/account/version.txt`에 **덮어쓴다**. 태그가 없는 fork/얕은 클론에서는 실패해도 무시(`if TAG=...; then`)된다. `version.txt` 변경이 diff에 나타나면 의도한 것인지 확인한다.
- CI(`.github/workflows/build.yml`)는 `make format` 후 `git diff --exit-code`로 포맷 변경이 없어야 통과한다. 커밋 전에 `gofmt`를 반드시 적용한다.
- **이 개발 머신에는 `go`와 `protoc`가 설치되어 있지 않다** (`which go`, `which protoc` 모두 실패). 코드 변경 후 빌드/테스트 검증은 사용자 환경 또는 Docker(`docker build .`)에서 수행해야 한다. 검증하지 못했으면 보고서에 그렇게 적는다.
- Alpine에서 린트할 때 `LINTER_FLAGS += --build-tags=musl`이 자동 추가된다.

### 2.3 코드 스타일 (`.golangci.yml`)

활성 린터: `errcheck`, `gofmt`, `gosimple`, `govet`, `misspell`, `revive`, `unused`.

revive 활성 규칙: `blank-imports`, `context-as-argument`, `context-keys-type`, `dot-imports`(ginkgo/gomega만 허용), `empty-block`, `error-naming`, `error-return`, `error-strings`, `errorf`, `increment-decrement`, `indent-error-flow`, `range`, `receiver-naming`, `redefines-builtin-id`, `superfluous-else`, `time-naming`, `unexported-return`, `unreachable-code`, `unused-parameter`, `var-declaration`, `var-naming`. (`exported`는 주석 처리, `package-comments`는 disabled.)

제외되는 errcheck 경고: `w.Write`, `fmt.Fprintf`, `fmt.Fprintln`, `file.Close`, `os.Setenv`의 반환값 미확인. `elliptic.*` deprecated 경고도 제외 (Go 1.21 툴체인 호환 유지 목적, `internal/authentication/native.go`).

관례:

- `context.Context`는 첫 인자, 이름은 `ctx`.
- 오류 변수는 `Err...` 접두사, 오류 문자열은 소문자 시작, 마침표 없음.
- 미사용 파라미터는 `_`로 (예: `func (c *Connection) Send(_ context.Context, buffer []byte)`).
- 사용자 대상 로그는 `internal/log` 패키지 (`log.Debug/Info/Warning/Error`)를 쓴다. `fmt.Println`은 CLI 출력 전용.
- 파일 상단 주석으로 파일 역할을 설명하는 관례가 있다 (`// File implements commands related to vehicle charging.`).

### 2.4 protobuf 재생성

`pkg/protocol/protobuf/*.proto`를 바꾸면 `make proto-gen`으로 `*.pb.go`를 재생성한다. 옵션은 `--go_opt=module=github.com/teslamotors/vehicle-command/pkg/protocol/protobuf`이며 생성 파일은 각 `go_package` 하위 디렉터리(`carserver/`, `vcsec/`, `signatures/`, `universalmessage/`, `keys/`, `errors/`, `managedcharging/`)에 놓인다. `*.pb.go`를 손으로 편집하지 않는다.

### 2.5 문서 갱신 규칙

코드를 바꾸면 이 디렉터리(`documents/md/`)의 대응 파일과 `documents/html/`의 대응 페이지를 같은 변경에서 갱신한다. 대응 관계:

| 코드 | md | html |
|---|---|---|
| `pkg/connector/ble/*` | `02-ble-transport.md` | `03-ble-transport.html` |
| `internal/authentication/*`, `internal/dispatcher/*`, `pkg/protocol/protocol.md`, `*.proto` | `03-protocol.md`, `01-architecture.md` | `04-protocol.html`, `01-overview.html` |
| `pkg/vehicle/*`, `cmd/tesla-control/commands.go`, `pkg/proxy/command.go` | `05-command-catalog.md`, `04-go-api-reference.md` | `07-command-reference.html`, `05-go-sdk.html` |
| `cmd/*`, `pkg/cli/*` | `06-cli-tools.md` | `06-cli-tools.html` |
| `pkg/proxy/*`, `cmd/tesla-http-proxy/*` | `07-http-proxy.md` | `08-http-proxy.html` |
| `pkg/protocol/error.go`, `universal_message.proto`의 `MessageFault_E` | `08-errors.md` | `09-errors-troubleshooting.html` |

---

## 3. 절대 어기면 안 되는 불변 조건

각 항목의 근거 파일을 함께 적는다. 이 조건을 깨는 변경은 보안 취약점이거나 차량과의 상호 운용성 파괴다.

### 3.1 프로토콜/암호

1. **안티리플레이 카운터는 같은 epoch 안에서 단조 증가**해야 한다. `Signer.Encrypt`/`AuthorizeHMAC`는 매 호출 `s.counter++` 하며, `0xFFFFFFFF`에서 `counter rollover` 오류를 낸다. 세션 정보 갱신 시 카운터는 **epoch가 바뀌지 않는 한 절대 롤백하지 않는다** (`Signer.UpdateSessionInfo`: `if s.counter < info.Counter { s.counter = info.Counter }`). 근거: `internal/authentication/signer.go`, `pkg/protocol/protocol.md` "Recovering from synchronization errors".
2. **VCSEC는 카운터 순서대로 도착해야 하며 동시 요청을 보내면 안 된다.** Infotainment는 슬라이딩 윈도우로 순서 뒤바뀜을 허용하지만 VCSEC는 그렇지 않다. 프록시는 이 이유로 VIN별 뮤텍스(`Proxy.lockVIN`)로 명령을 직렬화한다. 근거: `pkg/protocol/protocol.md` "Metadata", `pkg/proxy/proxy.go` `handleVehicleCommand` 주석.
3. **응답 카운터 재사용 검사**: 복호화된 응답의 `AES_GCM_Response_data.counter`는 요청별 `SlidingWindow`(윈도우 32)로 검사하고 중복이면 `protocol.ErrReplayedResponse`로 폐기한다. 근거: `internal/dispatcher/session.go` `decrypt`, `internal/authentication/window.go`.
4. **세션 정보 HMAC 태그는 상수 시간 비교** (`hmac.Equal`)로 검증한다. 태그가 없는 세션 정보는 폐기한다 (`Discarding unauthenticated session info`). 근거: `internal/authentication/signer.go` `NewAuthenticatedSigner`, `UpdateSignedSessionInfo`; `internal/dispatcher/dispatcher.go` `checkForSessionUpdate`.
5. **세션 정보는 해당 요청 전송 후 `AllowedLatency()` 안에 도착해야** 갱신에 쓴다. BLE 4s, inet 10s. 초과하면 폐기 (`receiver.expired`). 근거: `internal/dispatcher/dispatcher.go` `checkForSessionUpdate`, `pkg/connector/ble/ble.go` `maxLatency`, `pkg/connector/inet/inet.go` `MaxLatency`.
6. **세션 정보 갱신 조건**: 요청 UUID(challenge)가 최근 것이고, 태그가 맞고, 같은 epoch에서 시계가 뒤로 가지 않아야 갱신한다 (`s.setTime <= info.ClockTime` 또는 epoch 변경 시에만 갱신). 근거: `Signer.UpdateSessionInfo`.
7. **`FLAG_ENCRYPT_RESPONSE`는 기본으로 켠다.** `vehicle.DefaultFlags = 1 << Flags_FLAG_ENCRYPT_RESPONSE` (= 2). 플래그가 0이 아니면 요청 메타데이터에 `TAG_FLAGS`가 포함되고, 응답 메타데이터에는 **항상** 포함된다. 근거: `pkg/vehicle/vehicle.go`, `internal/authentication/peer.go` `extractMetadata`/`responseMetadata`.
8. **BLE는 AES-GCM, Fleet API(inet)는 HMAC-SHA256.** `ble.Connection.PreferredAuthMethod()`는 `AuthMethodGCM`, `inet.Connection`은 `AuthMethodHMAC`. Fleet API는 AES-GCM 명령을 차단한다(OAuth scope 검사 불가). 근거: `pkg/connector/ble/ble.go`, `pkg/connector/inet/inet.go`, `protocol.md` "Authentication methods".
9. **메타데이터 TLV는 태그 오름차순, 각 값 ≤255바이트, `0xFF`로 종료.** `metadata.Add`는 순서가 틀리면 `errOutOfOrderMetadata`, 길이 초과면 `ErrMetadataFieldTooLong`. `nil` 값은 건너뛴다. 근거: `internal/authentication/metadata.go`.
10. **요청 해시(request hash) 규칙**: `[SignatureType 1바이트] || tag`. VCSEC 대상 HMAC 요청은 tag를 16바이트로 절단. 근거: `internal/authentication/peer.go` `RequestID`.
11. **테스트 키(`protocol.md`의 `vehicle.key`, `client.key`)를 실제 차량에 등록하지 않는다.** 개인 키가 공개되어 있으므로 등록하면 무단 접근이 가능하다. 테스트 벡터 검증 용도로만 쓴다.
12. **만료 시간 상한**: `expires_at`은 `epochLength = 2^30 초`를 넘을 수 없다 (`out of bounds expiration time`). 명령 기본 수명은 `defaultExpiration = 5s`이며 ctx에 deadline이 있으면 그 시각까지. 근거: `internal/authentication/peer.go`, `internal/dispatcher/session.go`.

### 3.2 키와 배포

13. **TLS 키와 명령 인증 키를 같은 키로 쓰지 않는다.** `cmd/tesla-http-proxy/main.go`는 두 공개 키가 같으면 `It is unsafe to use the same private key for TLS and command authentication.`을 출력하고 종료한다. 이 검사를 제거하지 않는다.
14. **프록시에 TLS 비활성 옵션(`--insecure` 등)을 추가하지 않는다.** 설계 결정이며 `cmd/tesla-http-proxy/main.go` 주석과 `README.md`에 명시되어 있다. TLS 없는 변형이 필요하면 `pkg/proxy`를 직접 사용하는 별도 바이너리를 만든다.
15. **`localhost` 이외 인터페이스에 바인드하면 경고**(`nonLocalhostWarning`)를 출력한다. 클라이언트 인증 없이 외부에 노출하지 않는다.
16. **키링 항목 이름** 규칙: 서비스 `com.tesla.auth`, 개인 키 `vehicleCommandKey.<TESLA_KEY_NAME>`, OAuth 토큰 `oauthtoken.<TESLA_TOKEN_NAME>`, 파일 백엔드 기본 디렉터리 `~/.tesla_keys`. 개인 키는 32바이트 스칼라(`D.FillBytes`)로 저장된다. 근거: `pkg/cli/keyring.go`.
17. **세션 캐시 파일은 개인 키에 종속**되며 접근 제어가 필요하다 (`pkg/cache/doc.go`). 다른 키로 캐시를 쓰면 인증 실패 후 자동 재핸드셰이크된다.

### 3.3 Go API 계약

18. **`internal/` 패키지는 모듈 밖에서 임포트할 수 없다.** 외부에 노출해야 하는 타입은 `pkg/protocol/key.go`처럼 타입 별칭/래퍼로 재수출한다 (`protocol.ECDHPrivateKey`, `protocol.Session`). `pkg/cache`가 `internal/dispatcher.CacheEntry`를 노출하는 것은 예외이며, 클라이언트는 `vehicle.UpdateCachedSessions`/`LoadCachedSessions`를 쓰라고 안내한다.
19. **`connector.Connector.Close()`는 멱등**이어야 하고, `Receive()`/`Send()`는 스레드 안전해야 한다. `inet.Connection.Close`는 inbox를 닫고 `nil`로 만들어 이후 `Send`가 `ErrNotConnected`를 반환하게 한다 (`TestSendAfterClose`). 근거: `pkg/connector/connector.go`.
20. **`Vehicle.Disconnect()`를 `Connector.Close()`보다 먼저 호출**한다. `Disconnect`는 dispatcher goroutine을 멈춘 뒤 `conn.Close()`를 호출하므로 둘 다 `defer`해도 되지만 순서(`defer conn.Close()` 먼저 등록, `defer car.Disconnect()` 나중 등록 → Disconnect가 먼저 실행)를 지킨다. 근거: `pkg/vehicle/vehicle.go` `Disconnect` 주석.
21. **`ECDHPrivateKey`를 HSM/TEE로 구현할 때** TEE는 (a) 공유 비밀(ECDH 결과)을 내보내는 API, (b) ECDH 파생 키를 쓰는 AES 인터페이스, (c) 호스트가 nonce를 제공하는 AES-GCM 암호화 인터페이스를 **노출하면 안 된다**. `Session` 객체에는 파생 비밀을 넣지 말고 핸들만 넣는다. 근거: `pkg/protocol/key.go` `Session` 주석, `internal/authentication/ecdh.go` 상단 주석 (그래서 `crypto/ecdh`를 쓰지 않는다).
22. **`protocol.Error` 인터페이스**(`MayHaveSucceeded()`, `Temporary()`)를 구현하는 오류만 재시도 판단에 쓰인다. `ShouldRetry`는 `MayHaveSucceeded`면 절대 재시도하지 않고, `Temporary`면 재시도한다. 새 오류 타입을 추가하면 이 두 메서드를 신중히 정의한다. 근거: `pkg/protocol/error.go`.
23. **inbox 버퍼가 가득 차면 응답을 드롭**한다 (BLE `flush`: `default: return false`, inet `Send`: `dropped response because inbox is full`, dispatcher `process`: `response handler queue is full`). 버퍼 크기는 `connector.BufferSize = 5`, `receiverBufferSize = 10`. 블로킹으로 바꾸면 데드락 위험이 있다.

---

## 4. 빠른 참조 카드

### 4.1 BLE

| 항목 | 값 | 근거 |
|---|---|---|
| Service UUID | `00000211-b2d1-43f0-9b88-960cebf8b91e` | `pkg/connector/ble/ble.go` |
| Write (to vehicle) characteristic | `00000212-b2d1-43f0-9b88-960cebf8b91e` — write **with response** | 〃 |
| Read/notify (from vehicle) characteristic | `00000213-b2d1-43f0-9b88-960cebf8b91e` — subscribe **indication** | 〃 |
| Advertisement Local Name | `"S" + hex(SHA1(VIN)[:8]) + "C"` (소문자 hex 16자) — 예: VIN `5YJS0000000000000` → `S1a87a5a75f3df858C` | `VehicleLocalName`, `protocol.md` |
| 프레이밍 | `[len_hi][len_lo][payload...]`, 길이는 big-endian 2바이트, payload는 RoutableMessage protobuf | `Connection.Send`/`flush` |
| 최대 메시지 | `maxBLEMessageSize = 1024` 바이트 (수신 시 초과면 버퍼 폐기) | 〃 |
| 블록 길이 | `min(negotiatedMTU, 1024) - 3`; MTU 교환 실패 시 `ble.DefaultMTU - 3` | `tryToConnect` |
| 수신 청크 간 타임아웃 | `rxTimeout = 1s` (초과 시 입력 버퍼 리셋) | 〃 |
| 시계 동기 허용 지연 | `maxLatency = 4s` | 〃 |
| 재시도 간격 | `RetryInterval() = 1s` | 〃 |
| inbox 버퍼 | 5 (`make(chan []byte, 5)`) | 〃 |
| Linux 어댑터 ID | `hciX` (0 ≤ X ≤ 15), 다이얼/리슨 타임아웃 20s | `device_linux.go` |
| VCSEC 동시 BLE 연결 | 최대 3 (키포브/폰키와 공유) | `protocol.md` |

### 4.2 프로토콜 상수

| 항목 | 값 |
|---|---|
| `Domain` | `DOMAIN_BROADCAST=0`, `DOMAIN_VEHICLE_SECURITY=2`, `DOMAIN_INFOTAINMENT=3` |
| `Flags` (비트 인덱스) | `FLAG_USER_COMMAND=0`, `FLAG_ENCRYPT_RESPONSE=1` → `vehicle.DefaultFlags = 2` |
| `SignatureType` | `AES_GCM=0`, `AES_GCM_PERSONALIZED=5`, `HMAC=6`, `HMAC_PERSONALIZED=8`, `AES_GCM_RESPONSE=9` |
| `Tag` | `SIGNATURE_TYPE=0`, `DOMAIN=1`, `PERSONALIZATION=2`, `EPOCH=3`, `EXPIRES_AT=4`, `COUNTER=5`, `CHALLENGE=6`, `FLAGS=7`, `REQUEST_HASH=8`, `FAULT=9`, `END=255` |
| `Keys.Role` | `NONE=0`, `SERVICE=1`, `OWNER=2`, `DRIVER=3`, `FM=4`, `VEHICLE_MONITOR=5`, `CHARGING_MANAGER=6`, `GUEST=8` (7 없음) |
| `VCSEC.KeyFormFactor` | `UNKNOWN=0`, `NFC_CARD=1`, `IOS_DEVICE=6`, `ANDROID_DEVICE=7`, `CLOUD_KEY=9` |
| `VCSEC.RKEAction_E` | `UNLOCK=0`, `LOCK=1`, `REMOTE_DRIVE=20`, `AUTO_SECURE_VEHICLE=29`, `WAKE_VEHICLE=30` |
| `VCSEC.SignatureType` | `NONE=0`, `PRESENT_KEY=2` (add-key-request 봉투) |
| 공유 키 | `K = SHA1(BIG_ENDIAN(Sx,32))[:16]` (128비트 AES-GCM), `SharedKeySizeBytes = 16` |
| KDF 라벨 | `"session info"`, `"authenticated command"` (HMAC-SHA256(K, label)) |
| epoch ID 길이 | 16바이트; `epochLength = 2^30 s`; 슬라이딩 윈도우 32 |
| UUID/routing address 길이 | 16바이트 (`uuidLength`, `addressLength`, `challengeLength`) |
| 명령 기본 수명 | `defaultExpiration = 5s` (`internal/dispatcher/session.go`) |
| dispatcher receiver 버퍼 | `receiverBufferSize = 10` |
| 최대 응답 길이 (connector) | `connector.MaxResponseLength = 100000` |
| Fleet API 기본 도메인 | `fleet-api.prd.na.vn.cloud.tesla.com` (토큰 `aud`/`ou_code`로 재결정) |
| 프록시 | `DefaultTimeout = 10s`, `MaxAttempts = 2`, `MaxResponseLength = 10000000`, `maxRequestBodyBytes = 1 MiB`, `cacheSize = 10000`, 기본 포트 443, User-Agent `tesla-http-proxy/1.1.0` |
| Fleet API 엔드포인트 | `POST api/1/vehicles/<VIN>/signed_command` body `{"routable_message": <base64>}` → `{"response": <base64>}`; `POST api/1/vehicles/<VIN>/wake_up` |

### 4.3 환경 변수

| 변수 | 사용처 | 의미 |
|---|---|---|
| `TESLA_KEY_NAME` | `pkg/cli` | 키링의 개인 키 이름 |
| `TESLA_KEY_FILE` | `pkg/cli` | 개인 키 PEM 파일 경로 (키링 대신) |
| `TESLA_TOKEN_NAME` | `pkg/cli` | 키링의 OAuth 토큰 이름 |
| `TESLA_TOKEN_FILE` | `pkg/cli` | OAuth 토큰 파일 경로 |
| `TESLA_VIN` | `pkg/cli` | VIN (17자) |
| `TESLA_CACHE_FILE` | `pkg/cli` | 세션 캐시 파일 (기본 `~/.tesla-cache.json`) |
| `TESLA_KEYRING_TYPE` | `pkg/cli` | 99designs/keyring 백엔드 (`tesla-keygen -h`로 목록 확인) |
| `TESLA_KEYRING_PASSWORD` | `pkg/cli` | 파일 백엔드 비밀번호 (프롬프트 생략) |
| `TESLA_KEYRING_PATH` | `pkg/cli` | 파일 백엔드 디렉터리 |
| `TESLA_KEYRING_DEBUG` | `pkg/cli` | 존재하면 키링 디버그 로그 |
| `TESLA_VERBOSE` | `tesla-control`, `tesla-http-proxy` | `false`/`0` 이외면 디버그 로그 |
| `TESLA_HTTP_PROXY_TLS_CERT` / `_TLS_KEY` / `_HOST` / `_PORT` / `_TIMEOUT` | `tesla-http-proxy` | 프록시 TLS/바인드/타임아웃 |

`ReadFromEnvironment()`는 **이미 채워진 값은 덮어쓰지 않으므로** 반드시 `flag.Parse()` 뒤에 호출한다.

### 4.4 CLI 한 줄 예시

```bash
export TESLA_KEY_NAME=$(whoami) TESLA_VIN=<VIN> TESLA_CACHE_FILE=~/.tesla-cache.json
tesla-keygen create > public_key.pem                                  # 키 생성 (있으면 공개 키만 출력, -f 로 덮어쓰기)
tesla-control -ble add-key-request public_key.pem owner cloud_key     # 차 안에서 NFC 카드로 승인
tesla-control -ble lock                                               # BLE 명령
tesla-control -ble -debug list-keys                                   # TX/RX hex 덤프
tesla-control -ble -domain vcsec body-controller-state                # Infotainment 안 깨우고 VCSEC만
tesla-control -ble state charge                                       # Infotainment 상태 JSON
tesla-control -ble session-info public_key.pem infotainment           # 키 등록 확인
tesla-control lock                                                    # OAuth 토큰이 있으면 인터넷 경로
tesla-auth-token -token-name $(whoami) token.txt                      # 토큰을 키링에 저장
tesla-jws -fleet sign TelemetryClient telemetry_config.json > cfg.jws
tesla-http-proxy -tls-key config/tls-key.pem -cert config/tls-cert.pem -key-file config/fleet-key.pem -port 4443
go run ./examples/ble -vin <VIN> -key private.pem [-debug] [-bt-adapter hci0]   # 예제
go run ./examples/ble -vin <VIN> -scan-only
```

---

## 5. 흔한 함정

| 함정 | 사실 | 근거 |
|---|---|---|
| `StartSession(ctx, nil)`이 Infotainment를 깨운다 | `domains == nil`이면 VCSEC와 INFOTAINMENT 두 도메인에 병렬로 핸드셰이크한다. 잠든 차에 VCSEC 전용 명령만 보내려면 `[]universal.Domain{protocol.DomainVCSEC}` 또는 CLI `-domain vcsec`. `tesla-control`은 `-ble wake`일 때 자동으로 VCSEC만 지정한다. | `internal/dispatcher/dispatcher.go` `StartSessions`, `cmd/tesla-control/commands.go` `configureFlags` |
| `SendAddKeyRequest`는 세션이 필요 없다 | `vcsec.ToVCSECMessage{SignedMessage{SignatureType: PRESENT_KEY}}` 봉투를 `v.conn.Send`로 **직접** 보낸다. dispatcher/RoutableMessage를 거치지 않으며 응답을 기다리지 않는다. inet 커넥터면 `ErrRequiresBLE`. 승인 여부는 `SessionInfo(ctx, pub, DomainInfotainment)`로 확인. | `pkg/vehicle/security.go` |
| `Wakeup`의 경로가 커넥터에 따라 다르다 | `FleetAPIConnector`면 `api/1/vehicles/<VIN>/wake_up`을 10초 간격으로 폴링(`state == "online"`), 아니면 VCSEC `RKE_ACTION_WAKE_VEHICLE`. | `pkg/vehicle/vehicle.go`, `pkg/connector/inet/inet.go` |
| `SetPINToDrive`는 BLE에서 실패 | `FleetAPIConnector`가 아니면 `protocol.ErrRequiresEncryption`. (PIN 평문 노출 방지 정책.) | `pkg/vehicle/security.go` |
| `AddKey`(인증됨)와 `AddKeyRequest`(NFC 승인)는 다르다 | `AddKeyWithRole`은 이미 등록된 Owner 키로 서명해 화이트리스트에 추가. `SendAddKeyRequestWithRole`은 미등록 키를 NFC 카드 탭으로 부트스트랩. | `pkg/vehicle/security.go` |
| Fleet Manager 키는 BLE 명령 불가 | 2023.38+ 차량에서 FM 역할은 BLE 명령과 타 사용자 키 추가/삭제가 막힌다. | `protocol.md` "Roles" |
| VCSEC 응답에는 `request_uuid`가 없다 | 그래서 `receiverKey`는 VCSEC일 때 랜덤 `routing_address`만으로 매칭하고 uuid는 비운다. Infotainment는 고정 address + uuid. | `internal/dispatcher/dispatcher.go` `Send`/`process` |
| VCSEC는 요청 하나에 최대 3개 응답 | 종료 판정은 명령 종류별 `isTerminalTest`. `OPERATIONSTATUS_WAIT`는 `ErrBusy`(재시도), `OPERATIONSTATUS_ERROR`는 레거시용이라 whitelist 코드가 없으면 `ErrUnknown` 또는 무시. | `pkg/vehicle/vcsec.go` |
| Infotainment 애플리케이션 오류 | `Response.actionStatus.result == OPERATIONSTATUS_ERROR`면 `protocol.NominalError{"car could not execute command: <plain_text>"}`. 프록시는 이를 HTTP 200 + `{"response":{"result":false,"reason":...}}`로 변환. | `pkg/vehicle/infotainment.go`, `pkg/proxy/proxy.go` |
| 세션 캐시는 키에 종속 | 다른 개인 키로 캐시를 로드하면 첫 명령이 실패하고 차량이 새 세션 정보를 주어 복구된다. 캐시 히트 시 핸드셰이크를 생략하므로 `Session for X loaded from cache` 로그가 찍힌다. | `pkg/cache/doc.go`, `dispatcher.StartSession` |
| macOS `device.Scan`은 항상 오류 반환 | ctx가 취소될 때까지 종료되지 않으므로 코드는 `context.Canceled`를 정상 경로로 취급한다. | `pkg/connector/ble/ble.go` `scanVehicleBeacon` |
| Linux BLE 권한 | `operation not permitted`면 `sudo setcap 'cap_net_admin=eip' "$(which <bin>)"` 또는 root. `IsAdapterError`는 Linux에서만 이 문자열을 검사한다. | `device_linux.go` |
| 프록시는 VIN만 받는다 | URL의 4번째 세그먼트가 17자가 아니면 404 (`do not user Fleet API ID`). Owner API ID 사용 불가. | `pkg/proxy/proxy.go` `ServeHTTP` |
| `flag.Parse` → `ReadFromEnvironment` → `LoadCredentials` → `Connect` 순서 | 순서가 바뀌면 환경 변수가 플래그를 덮거나 키링 프롬프트가 타임아웃을 소모한다. | `pkg/cli/config.go` 패키지 주석 |
| `LoadPublicKey`는 개인 키 파일도 받는다 | PKIX PEM, PKCS8 PEM, SEC1 PEM, 65바이트 바이너리, 130(+개행) hex 모두 허용. CLI의 `PUBLIC_KEY` 인자에 개인 키 파일을 넘겨도 된다. | `pkg/protocol/key.go` |
| `Ping`의 문서 주석은 반대로 적혀 있다 | 코드상 `Ping`이 `nil`을 반환하면 온라인이고 키 인식됨. 주석의 "non-nil error ... online"은 오타. | `pkg/vehicle/infotainment.go` |
| 세션 정보에 개인 키가 없으면 폐기 | `NewVehicle(conn, nil, nil)`로 만든 비인증 차량은 핸드셰이크 자체를 못 한다 (`ErrRequiresKey`). `list-keys`, `body-controller-state`, `session-info`, `add-key-request`, `wake`(인터넷)만 키 없이 가능. | `dispatcher.RequestSessionInfo`, `commands.go` `requiresAuth` |

---

## 검증 체크리스트

이 문서 또는 3절의 불변 조건과 관련된 코드를 바꾼 뒤:

- [ ] `go build ./... && go test ./... && go vet ./...` (또는 `./check-all.sh`)가 사용자 환경에서 통과했는가? 못 돌렸으면 보고서에 명시했는가?
- [ ] `gofmt` 변경이 없는가 (`make format && git diff --exit-code`)?
- [ ] `internal/authentication/protocol_doc_test.go`(`TestProtocolDocAESGCMExample`)와 `signer_test.go`/`verifier_test.go`/`window_test.go`가 그대로 통과하는가? 암호 관련 변경이면 테스트 벡터(`03-protocol.md`)와 대조했는가?
- [ ] 카운터 롤백, 태그 비교(`hmac.Equal`), `AllowedLatency`, `FLAG_ENCRYPT_RESPONSE` 기본값 중 어느 것도 약화되지 않았는가?
- [ ] 새 공개 API/상수/환경 변수를 추가했다면 4절 카드와 `04-go-api-reference.md`, `06-cli-tools.md`, 그리고 `../html/`의 대응 페이지에 반영했는가?
- [ ] `pkg/account/version.txt`가 의도치 않게 바뀌지 않았는가?
- [ ] `*.pb.go`를 손으로 고치지 않았는가? `.proto`를 바꿨다면 `make proto-gen`을 돌렸는가?
- [ ] `vehicle-command/` 밖(이 `documents/` 트리)만 문서 변경으로 건드렸는가?
