# 07. HTTP 프록시 (tesla-http-proxy / pkg/proxy)

REST 요청을 받아 종단 간 인증 차량 명령으로 변환해 Fleet API 로 보내는 프록시의 라우팅, 응답 규약, 상태 코드, 배포, 확장 방법을 코드 기준으로 정리한다. 이 프록시는 **인터넷 경로 전용**이며 BLE 를 사용하지 않는다.

관련 파일 (저장소 루트 기준):
- `cmd/tesla-http-proxy/main.go` — 실행 파일 (TLS, 플래그, 환경 변수)
- `cmd/tesla-http-proxy/server.go` — 자체 서명 인증서 생성 헬퍼 (`NewServer`, 현재 main 에서 사용하지 않음)
- `pkg/proxy/proxy.go` — `Proxy`, `ServeHTTP`, 라우팅, 포워딩, 텔레메트리 서명
- `pkg/proxy/command.go` — 엔드포인트 → `vehicle.Vehicle` 메서드 매핑
- `pkg/proxy/{proxy_test.go,command_test.go,status_test.go}`, `cmd/tesla-http-proxy/main_test.go`
- `Dockerfile`, `docker-compose.yml`, `.dockerignore`

관련 문서: [05-command-catalog.md](05-command-catalog.md) (엔드포인트 표), [04-go-api-reference.md](04-go-api-reference.md), [08-errors.md](08-errors.md)

---

## 실행

```bash
tesla-http-proxy -tls-key config/tls-key.pem -cert config/tls-cert.pem -key-file config/fleet-key.pem -port 4443
```

| 플래그 | 환경 변수 | 기본값 | 의미 |
|---|---|---|---|
| `-cert FILE` | `TESLA_HTTP_PROXY_TLS_CERT` | "" | TLS 인증서 체인 (서버 + 중간 CA + 루트 CA 연결) |
| `-tls-key FILE` | `TESLA_HTTP_PROXY_TLS_KEY` | "" | TLS 서버 개인 키 |
| `-host HOST` | `TESLA_HTTP_PROXY_HOST` | `localhost` | 바인드 호스트. `localhost` 가 아니면 경고문 출력 |
| `-port N` | `TESLA_HTTP_PROXY_PORT` | 443 | 포트. 환경 변수가 정수가 아니면 "invalid port" |
| `-timeout D` | `TESLA_HTTP_PROXY_TIMEOUT` | `10s` (`proxy.DefaultTimeout`) | 명령/포워딩 ctx 타임아웃. `time.ParseDuration` 형식 |
| `-verbose` | `TESLA_VERBOSE` | false | debug 로그 |
| `-key-name` / `-key-file` 등 | `TESLA_KEY_NAME` / `TESLA_KEY_FILE`, 키링 변수 | | 명령 인증 개인 키 (`cli.FlagPrivateKey`). `-session-cache` 플래그도 등록되지만 프록시는 자체 메모리 캐시를 쓴다 |

환경 변수는 플래그가 기본값일 때만 적용된다(`readFromEnvironment`). 순서: 플래그 파싱 → `readFromEnvironment()` → `config.ReadFromEnvironment()`.

시작 시 검사:
1. `config.PrivateKey()` 실패 → 종료 1.
2. **TLS 키 재사용 검사**: `protocol.LoadPublicKey(tls-key)` 가 성공하고(즉 P-256 키) 그 공개 키가 명령 키의 `PublicBytes()` 와 같으면 "It is unsafe to use the same private key for TLS and command authentication." 출력 후 종료. P-256 이 아니면(예: P-384, RSA) 검사 통과.
3. `proxy.New(ctx, skey, cacheSize=10000)`, `p.Timeout = -timeout`.
4. `http.ListenAndServeTLS(addr, cert, key, p)`. 반환 시 "Server stopped" 로그.

### TLS

TLS 를 끄는 옵션은 **의도적으로 없다** (`main.go` 주석: DIY 사용자가 차량을 인터넷에 노출하는 사고 방지). 비-TLS 가 꼭 필요하면 `pkg/proxy` 를 직접 임포트해 `http.ListenAndServe` 로 감싼다(아래 "라이브러리로 사용").

개발용 자체 서명 인증서 (README 와 동일, P-384 이라 재사용 검사도 통과):

```bash
mkdir -p config
openssl req -x509 -nodes -newkey ec \
    -pkeyopt ec_paramgen_curve:secp384r1 \
    -pkeyopt ec_param_enc:named_curve \
    -subj '/CN=localhost' \
    -keyout config/tls-key.pem -out config/tls-cert.pem -sha256 -days 3650 \
    -addext "extendedKeyUsage = serverAuth" \
    -addext "keyUsage = digitalSignature, keyCertSign, keyAgreement"
```

클라이언트는 `curl --cacert config/tls-cert.pem` 으로 신뢰.

### Docker

`Dockerfile`: 1단계 `golang:1.23.0` 에서 `go mod download` → `go build -o ./build ./...`; 2단계 `gcr.io/distroless/base-debian12:nonroot` 에 `/usr/local/bin` 으로 복사. `ENTRYPOINT ["tesla-http-proxy"]` (인자는 그대로 플래그로 전달).

```bash
docker pull tesla/vehicle-command:latest
docker run --security-opt=no-new-privileges:true -v ./config:/config -p 127.0.0.1:4443:4443 \
  tesla/vehicle-command:latest -tls-key /config/tls-key.pem -cert /config/tls-cert.pem \
  -key-file /config/fleet-key.pem -host 0.0.0.0 -port 4443
```

`docker-compose.yml` 은 환경 변수만으로 설정한다:

| 변수 | 값 |
|---|---|
| `TESLA_HTTP_PROXY_TLS_CERT` | `/config/tls-cert.pem` |
| `TESLA_HTTP_PROXY_TLS_KEY` | `/config/tls-key.pem` |
| `TESLA_HTTP_PROXY_HOST` | `0.0.0.0` (컨테이너 안이므로 필수; 포트 매핑으로 노출 범위 제어) |
| `TESLA_HTTP_PROXY_PORT` | `4443` |
| `TESLA_HTTP_PROXY_TIMEOUT` | `10s` |
| `TESLA_KEY_FILE` | `/config/fleet-key.pem` |
| `TESLA_VERBOSE` | `true` |

볼륨 `./config:/config`, `security_opt: no-new-privileges:true`. distroless 이미지에는 셸이 없다.

---

## 요청 처리 (`Proxy.ServeHTTP`)

```
req.Body = MaxBytesReader(1 MiB)                       # 초과 시 읽는 쪽에서 413
if path == "/health":  GET → 200 "OK" (text) / 그 외 → 405
acct = getAccount(req)                                  # "Authorization: Bearer <jwt>" 없으면 403
acct.Host = domainForSubject[acct.Subject] 가 있으면 그 값  # 이전 421 리다이렉트 결과 재사용
if path 가 "/api/1/vehicles/" 로 시작:
    seg = split(path, "/")
    if len(seg)==7 && seg[5]=="command":                # /api/1/vehicles/{VIN}/command/{cmd}
        VIN 길이 != 17 → 404 "expected 17-character VIN in path (do not user Fleet API ID)"
        VIN 이 unsupported 로 표시됨 → forwardRequest (+ Host 변경 시 domainForSubject 갱신)
        else handleVehicleCommand; 반환값이 ErrCommandUseRESTAPI 면 forwardRequest
        return
    if len(seg)==5 && seg[4]=="fleet_telemetry_config": # /api/1/vehicles/fleet_telemetry_config
        handleFleetTelemetryConfig; return
forwardRequest(acct, w, req)                            # 그 외 모든 /api/1/* (vehicle_data, wake_up 등)
```

`getAccount` 는 요청마다 `account.New(token, "tesla-http-proxy/1.1.0")` 를 호출한다. 토큰의 `aud`/`ou_code` 로 지역 Fleet API 호스트를 정한다(기본 `fleet-api.prd.na.vn.cloud.tesla.com`, 규칙은 [04-go-api-reference.md](04-go-api-reference.md) `pkg/account`).

### handleVehicleCommand 흐름

```
ctx = WithTimeout(p.Timeout)
lockVIN(ctx, vin)                          # 실패(ctx 만료) → 503
  ↓ defer unlockVIN
loadVehicleAndCommandFromRequest
  ├ method != POST                         → 405 {"error":"Method Not Allowed"}
  ├ extractCommandAction(req, command)     # body 읽고 JSON → RequestParameters, body 복원
  │   ├ 1 MiB 초과                          → 413 (HTTPError, 본문은 err 문자열)
  │   ├ 읽기 실패                           → 400 "could not read request body"
  │   ├ JSON 파싱 실패                      → 400 "error occurred while parsing request parameters"
  │   ├ ErrCommandUseRESTAPI               → 반환 → ServeHTTP 가 forwardRequest
  │   ├ ErrCommandNotImplemented           → 400 {"error":"command not implemented"}
  │   ├ 알 수 없는 명령                     → 400 {"response":null,"error":"invalid_command","error_description":""}
  │   └ 파라미터 NominalError               → 400 {"response":{"result":false,"reason":"missing volume param"},...}
  └ fetchVehicle(acct, vin)                → 실패/nil → 500
car.Connect(ctx)                           → 실패 → httpStatusCode(err)
car.StartSession(ctx)  (양 도메인)
  ├ ErrProtocolNotSupported (Fleet API 422) → markUnsupportedVIN(vin); forwardRequest (이후 이 VIN 은 항상 포워딩)
  └ 기타                                   → httpStatusCode(err)
defer car.UpdateCachedSessions(p.sessions)
car.Execute(fn)
  ├ ErrCommandUseRESTAPI                   → 반환 → forwardRequest
  ├ protocol.IsNominalError(err)           → 200 {"response":{"result":false,"reason":"<err>"},"error":"","error_description":""}
  ├ 기타                                   → httpStatusCode(err) (inet.HTTPError 면 그 코드와 본문 그대로)
  └ nil                                    → 200 {"response":{"result":true,"reason":""}}
```

`httpStatusCode(err)`:

| 조건 (`errors.Is`) | 코드 | 이유 |
|---|---|---|
| `inet.ErrVehicleNotAwake` | 408 Request Timeout | Fleet API 가 오프라인/수면 차량에 쓰는 코드와 동일 |
| `protocol.ErrKeyNotPaired` | 412 Precondition Failed | OAuth 는 통과했지만 차량 키 페어링 전제조건 실패 (401 아님) |
| 그 외 | 500 | |

`writeJSONError(w, code, err)`:
- `err` 가 `*inet.HTTPError` 면 **코드를 `httpErr.Code` 로 덮어쓰고 본문은 `err.Error()` 그대로** (Fleet API 가 준 JSON 본문이 그대로 전달됨; Content-Type 은 항상 `application/json`).
- `err == nil` → `{"error": http.StatusText(code)}`.
- `IsNominalError(err)` → `{"response":{"result":false,"reason":"<err>"},"error":"","error_description":""}` (`Response` 구조체에 `omitempty` 없음).
- 그 외 → `{"response":null,"error":"<err>","error_description":""}`.
- 본문 끝에 `\n`. 200 이 아니면 `log.Error("Returning error ...")`.

### 상태 코드 요약

| 코드 | 상황 |
|---|---|
| 200 | 명령 성공 `{"response":{"result":true,"reason":""}}` / 차량이 거부(NominalError) `{"response":{"result":false,"reason":"..."}}` / 포워딩된 업스트림 200 |
| 400 | 잘못된 JSON, 파라미터 누락/타입 오류(NominalError 형식), 알 수 없는 명령(`invalid_command`), `remote_boombox`, `window_control` command 값 오류, `forwardRequest` 요청 생성 실패/RemoteAddr 파싱 실패 |
| 403 | `Authorization: Bearer` 헤더 없음 |
| 404 | VIN 이 17자가 아님 (Fleet API 숫자 ID 사용 금지) |
| 405 | `/health` 에 GET 이외, 명령 엔드포인트에 POST 이외 |
| 408 | 차량 오프라인/수면 (`ErrVehicleNotAwake`, Fleet API 503 또는 408+"vehicle is offline") |
| 412 | 공개 키가 차량에 등록되지 않음 (`ErrKeyNotPaired`, `UNKNOWN_KEY_ID` 또는 `KEY_NOT_ON_WHITELIST`) |
| 413 | 요청 본문 1 MiB 초과 |
| 421 | 업스트림 421 에 `Alt-Svc` 헤더가 없으면 그대로 전달; `Alt-Svc` 는 있으나 `h2=https://` 항목이 없으면 `{"error":"Misdirected Request"}` |
| 500 | 기타 오류, `fetchVehicle` 실패, 텔레메트리 서명 실패, JSON 직렬화 실패 |
| 502 | 업스트림 HTTP 오류(비-타임아웃), 응답 본문 읽기 실패, 응답 10 MB 초과, 421 재시도 소진("max retry exhausted") |
| 503 | `lockVIN` 대기 중 ctx 만료 (같은 VIN 에 동시 명령 폭주) |
| 504 | 업스트림 타임아웃(`url.Error.Timeout()`), 421 재시도 대기 중 ctx 만료 |
| 그 외 | Fleet API 가 반환한 코드 그대로 (`inet.HTTPError`, 예: 401, 429) |

주의 1: `/command/wake_up` 은 `handleVehicleCommand` 를 타므로 `Execute` 전에 `Connect`+`StartSession` 을 먼저 수행한다. 차량이 잠들어 있으면 핸드셰이크 단계에서 `ErrVehicleNotAwake` → **408** 이 되어 깨우지 못한다. 수면 차량은 포워딩 경로 `POST /api/1/vehicles/{VIN}/wake_up` 으로 깨운 뒤 명령을 보낸다.

주의 2: 세션 핸드셰이크(`StartSession`) 중 Fleet API 가 **422** 를 주면 `ErrProtocolNotSupported` → 그 VIN 을 `unsupported` 로 기억하고 원 요청을 Fleet API 로 포워딩한다(프로토콜 미지원 구형 차량). 프로세스 재시작 전까지 유지된다.

### forwardRequest (범용 포워딩)

1. 새 요청 `http.NewRequestWithContext(ctx(p.Timeout), req.Method, req.URL, req.Body)`, 헤더 복제.
2. per-hop 헤더 제거: `Proxy-Connection`, `Keep-Alive`, `Transfer-Encoding`, `Te`, `Upgrade` (요청·응답 모두).
3. `X-Forwarded-For`: 기존 값이 없으면 클라이언트 IP 추가, 있으면 뒤에 붙이고 하나로 평탄화.
4. `URL.Scheme = "https"`, `URL.Host = acct.Host`. 본문은 메모리에 읽어 재시도용으로 보관.
5. 응답 본문은 `MaxResponseLength(10,000,000)+1` 까지 읽고 초과 시 502.
6. **421 Misdirected Request + `Alt-Svc: h2=https://<host>`** → `acct.Host = <host>`, `domainForSubject[sub] = host` 저장, 1초 후 재시도. `MaxAttempts = 2` 번째 실패 시 502 "max retry exhausted". (`inet.Connection` 은 별도로 본문의 `use base URL:` 문구로 갱신한다.)
7. 성공 시 업스트림 상태 코드·헤더·본문을 그대로 전달.

### fleet_telemetry_config

`POST /api/1/vehicles/fleet_telemetry_config` 본문 `{"vins":[...], "config":{...}}`:
1. 본문 읽기(1 MiB 제한 → 413, 그 외 → 400), JSON 파싱 실패 → 400.
2. `config.aud`/`config.iss` 가 있으면 경고 로그 후 덮어씀.
3. `sign.SignMessageForFleet(p.commandKey, "TelemetryClient", config)` → JWS. 실패 → 500.
4. 본문을 `{"vins":[...], "token":"<jws>"}` 로 바꾸고 URL 을 `/api/1/vehicles/fleet_telemetry_config_jws` 로 변경해 `forwardRequest`.

VIN/설정 유효성 검증은 Tesla 서버에 위임한다.

---

## 내부 상태와 동시성

| 필드 | 타입 | 역할 |
|---|---|---|
| `commandKey` | `protocol.ECDHPrivateKey` | 모든 차량 명령 서명에 쓰는 단일 키 (Fleet 키). 공개 키가 각 차량에 등록되어 있어야 함 |
| `sessions` | `*cache.SessionCache` (`cache.New(10000)`) | VIN 별 도메인 세션. **메모리 전용**, 재시작 시 소실. 명령마다 `GetVehicle(..., p.sessions)` 로 로드하고 끝에 `UpdateCachedSessions` |
| `vinLock` | `sync.Map[vin]chan bool` | VIN 별 뮤텍스. 같은 VIN 의 명령은 직렬화(VCSEC 는 카운터 순서 필수). 소유자가 `Delete` 후 `close(ch)` 로 대기자 깨움; 맵 크기는 동시 명령 수로 제한 |
| `unsupported` | `sync.Map[vin]bool` | 422 를 받은 VIN. 이후 항상 포워딩 |
| `domainForSubject` | `sync.Map[sub]host` | OAuth `sub` 별 421 리다이렉트 결과 |
| `client` | `httpDoer` (`*http.Client{}`) | 포워딩용. 테스트에서 교체 |
| `fetchVehicle` | `func(ctx, acct, vin) (vehicleSession, error)` | 기본 `acct.GetVehicle(ctx, vin, commandKey, sessions)`. 테스트에서 교체 |

명령마다 새 `inet.Connection` + `vehicle.Vehicle` 을 만들고 `Disconnect` 한다. 세션 캐시 덕분에 두 번째 명령부터는 핸드셰이크가 생략된다(카운터/epoch 가 유효한 동안).

---

## 보안 고려사항

- `-host` 가 `localhost` 가 아니면 시작 시 경고: 인증 없는 클라이언트가 프록시를 통해 Tesla 서버로 과도한 트래픽을 보내면 IP 가 차단될 수 있다. 프록시 자체에는 클라이언트 인증이 없고, Bearer 토큰은 **그대로 Fleet API 로 전달**된다(토큰 검증은 Tesla 가 함).
- 명령 키 하나로 모든 VIN 을 서명하므로 키 파일/키링 보호가 핵심. TLS 키와 명령 키 재사용 금지(시작 시 검사).
- 요청 본문 1 MiB, 응답 10 MB 상한. `X-Forwarded-For` 를 붙이므로 업스트림에 클라이언트 IP 가 노출된다.
- 클라이언트 인증을 추가하려면 `Proxy` 를 `http.Handler` 로 감싼다 (`main.go` 주석의 권장 방식):

```go
type authed struct{ next http.Handler }

func (a authed) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    if r.Header.Get("X-Client-Token") != os.Getenv("MY_CLIENT_TOKEN") { // 예시: 실제로는 mTLS 등 사용
        http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
        return
    }
    a.next.ServeHTTP(w, r)
}

// main: http.ListenAndServeTLS(addr, cert, key, authed{next: p})
```

---

## curl 예시

```bash
export TESLA_AUTH_TOKEN=<access-token>
export VIN=5YJ30123456789ABC
CA=config/tls-cert.pem
BASE=https://localhost:4443

curl --cacert $CA $BASE/health                                              # OK

# 종단 간 인증 명령 (프록시가 서명)
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{}' $BASE/api/1/vehicles/$VIN/command/flash_lights
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{"percent":80}' $BASE/api/1/vehicles/$VIN/command/set_charge_limit
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{"seat_position":0,"level":3}' $BASE/api/1/vehicles/$VIN/command/remote_seat_heater_request
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{"days_of_week":"Mon,Tues,Wed","lat":37.4,"lon":-122.1,"start_time":1320,"start_enabled":true,"end_enabled":false,"enabled":true}' \
     $BASE/api/1/vehicles/$VIN/command/add_charge_schedule

# 포워딩되는 엔드포인트 (프록시가 서명하지 않음)
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" $BASE/api/1/vehicles/$VIN/vehicle_data | jq .
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -X POST $BASE/api/1/vehicles/$VIN/wake_up   # 수면 차량은 이 경로로 깨울 것

# 텔레메트리 설정 서명+제출
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     -X POST --data-binary @telemetry.json $BASE/api/1/vehicles/fleet_telemetry_config
```

응답 예:

```json
{"response":{"result":true,"reason":""}}
{"response":{"result":false,"reason":"car could not execute command: already_set"},"error":"","error_description":""}
{"response":null,"error":"vehicle unavailable: vehicle is offline or asleep","error_description":""}
```

---

## 라이브러리로 사용 (`pkg/proxy`)

```go
import (
    "context"
    "net/http"

    "github.com/teslamotors/vehicle-command/pkg/protocol"
    "github.com/teslamotors/vehicle-command/pkg/proxy"
)

skey, err := protocol.LoadPrivateKey("fleet-key.pem")
p, err := proxy.New(context.Background(), skey, 10000)
p.Timeout = 15 * time.Second
// TLS 종료를 리버스 프록시가 담당하는 내부망이라면:
http.ListenAndServe("127.0.0.1:8080", p)
```

`proxy.ExtractCommandAction(ctx, command, params)` 는 HTTP 없이도 엔드포인트 이름과 JSON 파라미터를 `func(*vehicle.Vehicle) error` 로 바꿔 주므로, 다른 서버 프레임워크나 메시지 큐에서 재사용할 수 있다.

---

## 테스트

| 파일 | 내용 |
|---|---|
| `pkg/proxy/proxy_test.go` | `httpDoer`/`fetchVehicle` 를 가짜로 바꿔 라우팅·포워딩·421 재시도·VIN 직렬화·422 폴백·텔레메트리 서명 등 40여 케이스 (`TestServeHTTP*`, `TestForwardRequest*`, `TestVehicleCommand*`, `TestHandleFleetTelemetryConfig*`) |
| `pkg/proxy/command_test.go` | `TestExtractCommandAction` — 엔드포인트별 파라미터 파싱 |
| `pkg/proxy/status_test.go` | `TestHTTPStatusCode`(408/412/500 매핑, 래핑된 오류 포함), `TestWriteJSONErrorStatus` |
| `cmd/tesla-http-proxy/main_test.go` | `TestParseConfig` — 환경 변수/플래그 우선순위 |

```bash
go test ./pkg/proxy/... ./cmd/tesla-http-proxy/...
```

---

## 검증 체크리스트

- [ ] 엔드포인트를 추가했다면 `pkg/proxy/command.go` 의 `switch` 와 `command_test.go`, [05-command-catalog.md](05-command-catalog.md) 를 함께 갱신했다.
- [ ] 새 오류 → HTTP 코드 매핑은 `httpStatusCode` 에 `errors.Is` 로 추가하고 `status_test.go` 에 케이스(래핑된 오류 포함)를 넣었다.
- [ ] 응답 JSON 형식(`Response` 구조체, `carResponse`)을 바꾸면 Fleet API 클라이언트 호환성이 깨진다 — 바꾸지 않는다.
- [ ] `forwardRequest` 를 수정했다면 per-hop 헤더 제거, XFF, 421 재시도, 본문 상한이 유지되는지 `TestForwardRequest*` 로 확인했다.
- [ ] `-host` 기본값 `localhost`, TLS 필수, TLS 키 재사용 검사를 제거하지 않았다.
- [ ] `go test ./pkg/proxy/... ./cmd/tesla-http-proxy/...` 통과.
