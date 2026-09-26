# 06. CLI 도구 레퍼런스

`cmd/` 아래 5개 도구(`tesla-keygen`, `tesla-control`, `tesla-auth-token`, `tesla-jws`, `tesla-http-proxy`)와 `examples/` 2개 프로그램의 플래그, 환경 변수, 키링 동작, 실행 흐름을 코드 기준으로 정리한다.

관련 파일 (저장소 루트 기준):
- `pkg/cli/config.go`, `pkg/cli/keyring.go`, `pkg/cli/config_{linux,darwin,windows}.go` — 공통 설정/키링
- `cmd/tesla-keygen/main.go`, `cmd/tesla-control/{main.go,commands.go}`, `cmd/tesla-auth-token/main.go`, `cmd/tesla-jws/main.go`, `cmd/tesla-http-proxy/main.go`
- `examples/ble/main.go`, `examples/unlock/unlock.go`
- `README.md`, `cmd/tesla-control/README.md`, `cmd/tesla-jws/README.md`

관련 문서: [04-go-api-reference.md](04-go-api-reference.md) (`pkg/cli` API), [05-command-catalog.md](05-command-catalog.md), [07-http-proxy.md](07-http-proxy.md), [08-errors.md](08-errors.md)

---

## 빌드 / 설치

```bash
cd vehicle-command
go get ./...          # 의존성
go build ./...        # 컴파일 확인
go install ./cmd/...  # $GOBIN 에 5개 바이너리 설치 (make install 과 동일)
```

- Go 1.23 (`go.mod`, CI 는 1.23.0). macOS/Linux 지원. Windows 는 BLE 제외 빌드는 되지만 `tesla-control` BLE 는 동작하지 않음(`device_windows.go` 가 항상 오류).
- Linux BLE 는 `CAP_NET_ADMIN` 필요: `sudo setcap 'cap_net_admin=eip' "$(which tesla-control)"` (어댑터 오류 시 `AdapterErrorHelpMessage` 가 이 안내를 출력).
- Docker 이미지 `tesla/vehicle-command` 의 ENTRYPOINT 는 `tesla-http-proxy`; `--entrypoint tesla-control` 로 교체 가능. 컨테이너에서는 BLE 를 쓸 수 없다고 가정한다.

---

## 공통 설정 (`pkg/cli.Config`)

### 우선순위

**명령행 플래그 > 환경 변수 > 내장 기본값.** `ReadFromEnvironment()` 는 이미 채워진 필드를 덮어쓰지 않으며, 모든 도구가 `flag.Parse()` 다음에 호출한다.

### 플래그 표

`Config.Flags` 마스크에 따라 `RegisterCommandLineFlags()` 가 등록한다. 각 도구의 마스크는 도구별 절 참조.

| 플래그 | 타입 | 기본값 | 대응 환경 변수 | 등록 조건 | 의미 |
|---|---|---|---|---|---|
| `-vin` | string | "" | `TESLA_VIN` | `FlagVIN` | 17자 VIN |
| `-key-name` | string | "" | `TESLA_KEY_NAME` | `FlagPrivateKey` | 키링 항목 이름 |
| `-key-file` | string | "" | `TESLA_KEY_FILE` | `FlagPrivateKey` | PEM 개인 키 파일 (`EC PRIVATE KEY` 또는 PKCS8) |
| `-session-cache` | string | "" → `TESLA_CACHE_FILE` → `$HOME/.tesla-cache.json` | `TESLA_CACHE_FILE` | `FlagPrivateKey` | 세션 캐시 JSON |
| `-disable-session-cache` | bool | false | (없음) | `FlagPrivateKey` | 캐시 미사용 |
| `-domain` | 반복 가능 (`DomainList`) | 없음 = 전체 | (없음) | `FlagPrivateKey` | `vcsec` / `infotainment` (대소문자 무관) |
| `-token-name` | string | "" | `TESLA_TOKEN_NAME` | `FlagOAuth` | 키링 토큰 항목 이름 |
| `-token-file` | string | "" | `TESLA_TOKEN_FILE` | `FlagOAuth` | OAuth 토큰 파일 |
| `-keyring-type` | string (`backendType`) | 미설정 → OS 기본 | `TESLA_KEYRING_TYPE` | `FlagOAuth` 또는 `FlagPrivateKey` | `keyring.AvailableBackends()` 중 하나. `-h` 에서 목록 확인 |
| `-keyring-file-dir` | string | `~/.tesla_keys` | `TESLA_KEYRING_PATH` (플래그 미등록 시에만 적용) | 위와 동일 | 파일 백엔드 디렉터리 |
| `-keyring-debug` | bool | false | `TESLA_KEYRING_DEBUG` (존재만 검사) | 위와 동일 | 키링 디버그 로그 |
| `-bt-adapter` | string | "" (=hci0) | (없음) | Linux + `FlagBLE` | `hciN`, N=0..15 |

### 환경 변수 전체

| 변수 | 읽는 곳 | 비고 |
|---|---|---|
| `TESLA_VIN` | `cli.Config` | |
| `TESLA_KEY_NAME`, `TESLA_KEY_FILE` | `cli.Config` | `-key-name`/`-key-file` 둘 다 비었을 때 **두 변수를 동시에** 읽는다. 둘 다 설정되면 `PrivateKey()` 는 파일 먼저, 실패(nil) 시 키링. |
| `TESLA_TOKEN_NAME`, `TESLA_TOKEN_FILE` | `cli.Config` | 둘 다 비었을 때 동시에 읽음. `token()` 은 파일 먼저(파일 없음 오류만 키링으로 폴백, 다른 읽기 오류는 반환). |
| `TESLA_CACHE_FILE` | `cli.Config` | 없으면 `$HOME/.tesla-cache.json`. `$HOME` 도 없으면 캐시 비활성. |
| `TESLA_KEYRING_TYPE` | `cli.Config` | 잘못된 값이면 조용히 무시(기본 유지) |
| `TESLA_KEYRING_PASSWORD` | `cli.Config.getPassword` | 파일/keychain 백엔드 비밀번호. 설정 시 프롬프트 생략 |
| `TESLA_KEYRING_PATH` | `cli.Config` | `Backend.FileDir` 이 비어 있을 때만 |
| `TESLA_KEYRING_DEBUG` | `cli.Config` | 존재하면 Debug=true |
| `TESLA_VERBOSE` | `tesla-control`, `tesla-http-proxy` | `"false"`, `"0"` 외의 값이면 debug 로그 |
| `TESLA_HTTP_PROXY_TLS_CERT`, `TESLA_HTTP_PROXY_TLS_KEY`, `TESLA_HTTP_PROXY_HOST`, `TESLA_HTTP_PROXY_PORT`, `TESLA_HTTP_PROXY_TIMEOUT` | `tesla-http-proxy` | [07-http-proxy.md](07-http-proxy.md) |

권장 초기 설정:

```bash
export TESLA_KEY_NAME=$(whoami)
export TESLA_TOKEN_NAME=$(whoami)      # 인터넷 경로를 쓸 때만
export TESLA_VIN=5YJ30123456789ABC
export TESLA_CACHE_FILE=~/.tesla-cache.json
```

### 키링 (`pkg/cli/keyring.go`)

| 항목 | 값 |
|---|---|
| 라이브러리 | `github.com/99designs/keyring` v1.2.2 |
| `ServiceName` | `com.tesla.auth` |
| 개인 키 항목 키 | `vehicleCommandKey.<KeyringKeyName>` |
| 토큰 항목 키 | `oauthtoken.<KeyringTokenName>` |
| 개인 키 저장 형식 | P-256 스칼라 `D` 를 32바이트 big-endian 으로 (`FillBytes`). `*authentication.NativeECDHKey` 만 저장 가능("key is not exportable"). 길이가 32가 아니면 "invalid private key". |
| 로드 | `protocol.UnmarshalECDHPrivateKey(item.Data)`; nil 이면 "invalid private key" |
| 백엔드 선택 | `-keyring-type` / `TESLA_KEYRING_TYPE` → `Backend.AllowedBackends = [type]`. 미설정이면 keyring 라이브러리의 OS 기본 순서 (macOS: 로그인 keychain, `KeychainTrustApplication: true`; Linux: secret-service/kwallet/keyctl(`KeyCtlScope: "user"`)/pass/file 등 가용 순) |
| 파일 백엔드 | `-keyring-file-dir` (기본 `~/.tesla_keys`), 비밀번호는 `TESLA_KEYRING_PASSWORD` 또는 프롬프트 |
| 비밀번호 프롬프트 (`getPassword`) | `c.password` 가 있으면 사용. 없으면 stdout 이 터미널이면 stdout, 아니면 stderr 가 터미널이면 stderr 에 프롬프트, 둘 다 아니면 "no terminal output available for password prompt". `term.ReadPassword(stdin)`. 한 번 입력하면 캐시. |
| Debug | `-keyring-debug` / `TESLA_KEYRING_DEBUG` → `Config.Debug`; `tesla-keygen` 은 이 값으로 `log.SetLevel(LevelDebug)` |

`LoadCredentials()` 를 `Connect()` 전에 호출하는 이유: 키링 비밀번호 프롬프트가 연결 타임아웃(ctx) 안에서 사용자를 기다리지 않게 하기 위해서다.

---

## tesla-keygen

`cli.NewConfig(cli.FlagOAuth | cli.FlagPrivateKey)` — 토큰 플래그도 등록되지만 사용하지 않는다.

```
usage: tesla-keygen [OPTION...] create|delete|export|migrate
```

| 옵션 | 의미 |
|---|---|
| `-f` | `create` 시 기존 키를 덮어씀 |
| `-output FILE` | 공개 키를 stdout 대신 파일에 기록 |
| 공통 플래그 | `-key-name`, `-key-file`, `-keyring-type`, `-keyring-file-dir`, `-keyring-debug`, `-session-cache`, `-disable-session-cache`, `-domain`, `-token-name`, `-token-file` |

서브커맨드 동작:

| 서브커맨드 | 동작 | 출력 | 종료 코드 |
|---|---|---|---|
| `create` | `-f` 없으면 먼저 `config.PrivateKey()` 시도 → 성공하면 **기존 키의 공개 키만 출력하고 종료(덮어쓰지 않음)**. 실패(없음)하거나 `-f` 면 `authentication.NewECDHPrivateKey(rand.Reader)` 로 새 P-256 키 생성 → `config.SavePrivateKey` (키링 이름이 있으면 키링, 아니면 `-key-file` 에 `EC PRIVATE KEY` PEM 0600) → 공개 키 출력 | PKIX PEM `-----BEGIN PUBLIC KEY-----` | 0 성공 / 1 실패 |
| `delete` | `config.DeletePrivateKey()` — 키링 항목 `vehicleCommandKey.<name>` 삭제. 파일 키는 삭제하지 않음 | 없음 | 0 / 1 |
| `export` | `config.PrivateKey()` 로 로드 후 **개인 키**를 `EC PRIVATE KEY` PEM 으로 stdout 출력 | SEC1 PEM | 0 / 1 |
| `migrate` | `-key-file` 과 `-key-name` 둘 다 필수. 파일에서 로드 → `KeyFilename=""` 로 지운 뒤 `SavePrivateKey` (키링으로) → 공개 키 출력. 원본 파일은 남음 | PKIX PEM | 0 / 1 |

인자 수가 1이 아니거나 알 수 없는 서브커맨드면 usage 출력 후 1. "Failed to parse key. The keyring may be corrupted. Run with -f to generate new key." 는 키링 데이터가 깨졌을 때.

```bash
export TESLA_KEY_NAME=$(whoami)
tesla-keygen create > public_key.pem          # 키링에 생성
tesla-keygen -key-file private.pem create      # 파일에 생성 (키링 이름 없을 때)
tesla-keygen -key-file private.pem -key-name $(whoami) migrate   # 파일 → 키링
tesla-keygen export > backup.pem               # 개인 키 백업 (민감)
tesla-keygen delete
```

OpenSSL 로 만든 키도 호환: `openssl ecparam -genkey -name prime256v1 -noout > private.pem && openssl ec -in private.pem -pubout > public.pem`.

---

## tesla-control

`cli.NewConfig(cli.FlagAll)` 로 시작하지만, 명령 이름이 주어지면 `configureFlags` 가 `Flags` 를 명령 요구에 맞게 다시 계산한다.

```
Usage: tesla-control [OPTION...] COMMAND [ARG...]
```

### 고유 플래그

| 플래그 | 기본값 | 의미 |
|---|---|---|
| `-debug` | false | `log.SetLevel(LevelDebug)`. BLE TX/RX hex 덤프 포함. `TESLA_VERBOSE` 로도 설정 가능. |
| `-ble` | false | OAuth 환경 변수가 있어도 BLE 강제 |
| `-command-timeout` | `5s` | 각 명령의 ctx 타임아웃 |
| `-connect-timeout` | `20s` | 연결+핸드셰이크 ctx 타임아웃 |
| 공통 플래그 | `FlagAll` 전부 (`-vin`, `-key-*`, `-token-*`, `-session-cache`, `-disable-session-cache`, `-domain`, `-keyring-*`, Linux `-bt-adapter`) |

### 실행 흐름 (`cmd/tesla-control/main.go`)

1. `NewConfig(FlagAll)`, 플래그 등록, `flag.Parse()`.
2. `-debug` 가 없으면 `TESLA_VERBOSE` 확인.
3. `config.ReadFromEnvironment()` (FlagAll 기준으로 모든 환경 변수 읽음).
4. 인자가 있으면:
   - `help` → 전체 usage(종료 코드 1 주의: `status` 초기값 1 그대로 반환), `help COMMAND` → 해당 명령 usage (종료 0).
   - `configureFlags(config, COMMAND, forceBLE)` 로 요구사항 검증. 실패 시 "Missing required flag: ..." 출력, 종료 1.
5. `config.LoadCredentials()` — 키링 프롬프트 처리.
6. `config.Connect(ctx(connect-timeout))` — 오류가 `ble.IsAdapterError` 면 `AdapterErrorHelpMessage` 출력.
7. `car != nil` 이면 `defer car.Disconnect()`, `defer config.UpdateCachedSessions(car)` (핸드셰이크 결과를 캐시 파일에 저장).
8. 인자가 있으면 `runCommand` 1회, 없으면 `runInteractiveShell`.

### configureFlags 규칙

```
c.Flags = FlagBLE
if info.domain != DomainNone { c.Domains = [info.domain] }          // body-controller-state → VCSEC
bleWake = forceBLE && COMMAND == "wake"
if bleWake || info.requiresAuth { c.Flags |= FlagPrivateKey | FlagVIN }
if bleWake { c.Domains = [VCSEC] }                                    // Infotainment 가 자고 있으므로 VCSEC 만 핸드셰이크
if !info.requiresFleetAPI { c.Flags |= FlagVIN }
if forceBLE { if info.requiresFleetAPI → ErrRequiresOAuth } else { c.Flags |= FlagOAuth }
checkReadiness(COMMAND, havePrivateKey, haveOAuth, haveVIN)
```

`checkReadiness` (사전 검사와 실행 직전 검사 모두 사용):

| `requiresFleetAPI` | `requiresAuth` | 필요한 것 | 부족 시 오류 |
|---|---|---|---|
| true | (무관) | OAuth 토큰 (`-token-name`/`-token-file`/환경) | `ErrRequiresOAuth` "command requires a FleetAPI OAuth token" |
| false | (무관) | VIN | `ErrRequiresVIN` "command requires a VIN" |
| (무관) | true | 개인 키 (`-key-name`/`-key-file`/환경) | `ErrRequiresPrivateKey` "command requires a private key" |

실행 직전에는 실제 상태로 다시 검사: `havePrivateKey = car != nil && car.PrivateKeyAvailable()`, `haveOAuth = acct != nil`, `haveVIN = car != nil`.

전송 경로 결정 (`Config.Connect`): `FlagOAuth` 가 켜져 있고(=`-ble` 없음) 토큰 위치가 있으면 **인터넷**, 아니면 `FlagBLE|FlagVIN` 이면 **BLE**. 즉 `TESLA_TOKEN_NAME` 이 export 되어 있으면 `-ble` 를 붙여야 BLE 로 간다.

### 명령 실행 (`execute`)

- 인자 수: `len(args)-1` 이 `len(info.args)` 이상, `len(info.args)+len(info.optional)` 이하가 아니면 "Invalid number of command line arguments" + usage, `ErrCommandLineArgs`.
- 위치 인자를 `args[i].name` 키의 map 으로 만들어 `handler(ctx, acct, car, keywords)` 호출.
- 핸들러 오류가 `ErrCommandLineArgs` 를 감싸면 해당 명령 usage 출력.

`runCommand` 의 오류 메시지 3종 (stderr):

| 조건 | 메시지 |
|---|---|
| `protocol.MayHaveSucceeded(err)` | `Couldn't verify success: <err>` — 명령이 실행됐을 수도 있음 (타임아웃 등). 재시도 전 상태 확인 필요 |
| `errors.Is(err, protocol.ErrNoSession)` | `You must provide a private key with -key-name or -key-file to execute this command` |
| 그 외 | `Failed to execute command: <err>` |

종료 코드: 단일 명령 모드는 성공 0 / 실패 1. 연결 실패, 자격 증명 실패, 플래그 부족도 1.

### 대화형 셸

인자 없이 실행하면 `> ` 프롬프트. 한 줄을 `github.com/google/shlex` 로 분리해 `runCommand` 호출(연결/세션은 재사용). `exit` 로 종료(코드 0). 빈 줄 무시. 개별 명령 실패는 최종 종료 코드에 영향 없음. stdin 읽기 오류만 1.

### 사용 예시

```bash
# BLE 페어링 (차 안에서, NFC 카드 준비)
tesla-control -ble add-key-request public_key.pem owner cloud_key
# 등록 확인 (Infotainment 까지 동기화됐는지)
tesla-control -ble session-info public_key.pem infotainment
tesla-control -ble list-keys

# 기본 명령
tesla-control -ble lock
tesla-control -ble -debug unlock
tesla-control -ble climate-set-temp 22C
tesla-control -ble seat-heater front-left high
tesla-control -ble charging-set-limit 80
tesla-control -ble body-controller-state         # 키 없이도 가능, Infotainment 수면 중 OK
tesla-control -ble state charge                  # 키 필요
tesla-control -ble -domain vcsec lock            # Infotainment 깨우지 않음

# 스케줄
tesla-control -ble charging-schedule-add weekdays 22:00-6:00 37.4 -122.1        # ID 출력
tesla-control -ble charging-schedule-add all -6:00 37.4 -122.1 once
tesla-control -ble charging-schedule-remove id 1700000000
tesla-control -ble precondition-schedule-add mon,wed,fri 07:30 37.4 -122.1

# 인터넷 (TESLA_TOKEN_NAME 설정 후)
tesla-control lock
tesla-control wake
tesla-control product-info
tesla-control get api/1/vehicles/$TESLA_VIN/vehicle_data
echo '{"name":"Dave"}' | tesla-control post api/1/users/keys
tesla-control rename-key public_key.pem "Home Server"

# Linux 어댑터 지정, 타임아웃 조정
tesla-control -ble -bt-adapter hci1 -connect-timeout 40s -command-timeout 10s honk

# 대화형
tesla-control -ble
> lock
> state climate
> exit
```

---

## tesla-auth-token

`cli.NewConfig(cli.FlagOAuth)`. 이 도구는 토큰을 **발급하지 않는다**. Fleet API 문서 절차로 얻은 access token 을 키링에 저장만 한다.

```
usage: tesla-auth-token [-token-name token_name] [file]
```

- `-token-name` (기본 `$TESLA_TOKEN_NAME`) 필수. 없으면 오류 후 종료 1.
- 인자 0개: stdin 전체를 토큰으로. 1개: 파일 내용. 2개 이상: "Too many command-line arguments".
- `config.SaveTokenToKeyring(token)` → 키 `oauthtoken.<name>`. 앞뒤 공백은 저장 시 제거하지 않지만 `account.New` 가 `TrimSpace` 한다.
- `-keyring-type` 등 공통 키링 플래그는 등록되지 않는다(`RegisterCommandLineFlags` 미호출). 환경 변수 `TESLA_KEYRING_TYPE`, `TESLA_KEYRING_PASSWORD`, `TESLA_KEYRING_PATH`, `TESLA_KEYRING_DEBUG` 는 `ReadFromEnvironment` 로 적용된다.

```bash
export TESLA_TOKEN_NAME=$(whoami)
echo -n "$ACCESS_TOKEN" | tesla-auth-token
tesla-auth-token token.txt
```

---

## tesla-jws

`cli.NewConfig(cli.FlagPrivateKey | cli.FlagVIN)`. Fleet Telemetry 설정 등에 쓰는 JWS(JSON Web Signature) 생성/검증.

```
usage: tesla-jws [OPTION...] sign APP [JSON_FILE]
       tesla-jws verify [JWS_FILE]
```

| 항목 | 값 |
|---|---|
| `-fleet` | 플릿 전체용 서명 (`SignMessageForFleet`). 없으면 `-vin` 필수 (`SignMessageForVehicle`) |
| `APP` | audience 의 마지막 요소. Fleet Telemetry 는 `TelemetryClient` |
| `JSON_FILE` | 클레임 JSON. 생략 시 stdin. `aud`, `iss` 는 덮어씀 |
| audience | 차량: `com.tesla.vehicle.<VIN>.<APP>` / 플릿: `com.tesla.fleet.<APP>` |
| `alg` 헤더 | `authentication.TeslaSchnorrSHA256` (Schnorr/P-256, 표준 JWS 알고리즘 아님) |
| `iss` | base64(std) 인코딩된 클라이언트 공개 키 (65바이트 비압축) |
| `verify` | `iss` 를 공개 키로 파싱해 서명만 검증. **발급자 신뢰/aud 검증은 하지 않음.** 성공 시 클레임 JSON 출력 |

```bash
tesla-jws -fleet sign TelemetryClient telemetry_config.json > signed-config.jws
tesla-jws -vin $TESLA_VIN sign MyApp claims.json
tesla-jws verify signed-config.jws
```

출력 JWS 는 bearer 토큰이 아니므로 민감 정보가 아니다. 프록시의 `POST /api/1/vehicles/fleet_telemetry_config` 가 같은 서명을 자동으로 수행한다([07-http-proxy.md](07-http-proxy.md)).

---

## tesla-http-proxy (요약)

`cli.NewConfig(cli.FlagPrivateKey)`. 고유 플래그: `-cert`, `-tls-key`, `-host`(기본 `localhost`), `-port`(기본 443), `-timeout`(기본 10s), `-verbose`. 환경 변수 `TESLA_HTTP_PROXY_*`, `TESLA_VERBOSE`, 개인 키는 `TESLA_KEY_NAME`/`TESLA_KEY_FILE`. 상세는 [07-http-proxy.md](07-http-proxy.md).

---

## examples/

### `examples/ble/main.go` (BLE 로 unlock + climate-on)

| 플래그 | 의미 |
|---|---|
| `-vin VIN` | 필수 |
| `-key FILE` | PEM 개인 키. 없으면 핸드셰이크 실패 |
| `-scan-only` | 광고 스캔만 하고 종료 (Ctrl-C 시 종료 코드 130) |
| `-debug` | TX/RX 덤프 |
| `-bt-adapter ID` | Linux 전용 |

흐름: `ble.InitAdapterWithID` → `ble.ScanVehicleBeacon` → `ble.NewConnectionFromScanResult` → `vehicle.NewVehicle(conn, key, nil)` → `Connect` → `StartSession(ctx, nil)` → `Unlock` → `ClimateOn`. 전체 30초 ctx.

### `examples/unlock/unlock.go` (인터넷으로 unlock)

| 플래그 | 기본값 |
|---|---|
| `-key FILE` | `private.key` |
| `-vin VIN` | 필수 |
| `-token FILE` | OAuth 토큰 파일 |

흐름: `account.New(token, "example-unlock/1.0.0")` → `GetVehicle` → `Connect` → `StartSession` → `Unlock`. `protocol.MayHaveSucceeded(err)` 처리 예시 포함.

---

## 검증 체크리스트

- [ ] 플래그를 추가/변경했다면 `RegisterCommandLineFlags` 의 등록 조건(`Flags` 마스크)과 `ReadFromEnvironment` 의 환경 변수 대응, 그리고 이 문서의 표를 함께 갱신했다.
- [ ] 새 환경 변수는 `pkg/cli/config.go` 의 `Env*` 상수로 정의하고 `README.md` "Configuration" 절에도 추가했다.
- [ ] `tesla-control` 에 명령을 추가했다면 `requiresAuth`/`requiresFleetAPI`/`domain` 이 `configureFlags` 규칙과 맞물려 올바른 전송 경로/핸드셰이크 도메인을 만든다 (`-ble` 유무 두 경우 모두 확인).
- [ ] `cmd/tesla-control/commands_test.go`, `pkg/cli/config_test.go`, `cmd/tesla-http-proxy/main_test.go` 통과.
- [ ] 키링 저장 형식(32바이트 스칼라)이나 항목 키 접두사(`vehicleCommandKey.`, `oauthtoken.`)를 바꾸면 기존 사용자의 키가 읽히지 않는다 — 마이그레이션 경로 없이 바꾸지 않는다.
