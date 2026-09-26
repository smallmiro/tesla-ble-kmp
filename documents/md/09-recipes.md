# 09. 코드 레시피

SDK 공개 API만 사용하는 실전 Go 코드 예제 15개. 시그니처는 저장소 코드에서 직접 확인한 것이며, 각 레시피는 목적 / 전제 / 코드 / 실행 / 주의 순으로 구성된다. 이 머신에는 `go`가 없어 컴파일 검증은 하지 않았다; 사용 전 `go build ./...` 로 확인할 것.

관련 파일 (저장소 루트 기준)

- `examples/ble/main.go`, `examples/unlock/unlock.go` — 공식 예제 (R1, R8의 원형)
- `pkg/cache/example_test.go` — 세션 캐시 예제 (R2의 원형)
- `pkg/cli/config.go` — CLI 뼈대 (R9)
- `pkg/connector/connector.go`, `pkg/connector/ble/ble.go`, `pkg/connector/inet/inet.go` — Connector 구현 참고 (R10)
- `pkg/vehicle/*.go` — 명령 메서드 패턴 (R11)
- `cmd/tesla-control/commands.go`, `pkg/proxy/command.go` — 명령 등록 (R11)
- `cmd/tesla-http-proxy/main.go`, `pkg/proxy/proxy.go` — 프록시 임베드 (R13)
- `pkg/sign/sign.go`, `cmd/tesla-jws/main.go` — JWS 서명 (R14)
- `pkg/vehicle/vehicle_test.go`, `vcsec_test.go`, `internal/dispatcher/dispatcher_test.go`, `pkg/proxy/proxy_test.go`, `internal/authentication/protocol_doc_test.go` — 테스트 패턴 (R15)

공통 규칙

- **defer 순서**: `defer conn.Close()` 를 먼저 등록하고 `defer car.Disconnect()` 를 나중에 등록한다 (LIFO → `Disconnect`가 먼저 실행됨). `Vehicle.Disconnect`는 내부에서 `conn.Close()`도 호출하며, `Connector.Close`는 멱등이어야 하므로 두 번 호출해도 안전하다.
- **ctx 타임아웃 권장값**: 연결(스캔+GATT+핸드셰이크) 20s (`tesla-control -connect-timeout` 기본), 명령 5s (`-command-timeout` 기본). 웨이크업이 필요하면 30s.
- **타입**: `protocol.ECDHPrivateKey`는 `vehicle.NewVehicle`의 `authentication.ECDHPrivateKey` 파라미터에 그대로 전달 가능하다 (동일 메서드 집합의 인터페이스).
- import 별칭: `universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"`, `carserver "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/carserver"`.

---

## R1. BLE로 잠금/해제 최소 프로그램

목적: 개인 키로 BLE 연결 → 핸드셰이크 → `Unlock` → 오류 분류 출력.
전제: 공개 키가 차량에 등록됨 (R4), 개인 키 PEM 파일.

```go
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/teslamotors/vehicle-command/pkg/connector/ble"
	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/vehicle"
)

func main() {
	var vin, keyFile string
	flag.StringVar(&vin, "vin", os.Getenv("TESLA_VIN"), "VIN")
	flag.StringVar(&keyFile, "key", "private.pem", "PEM private key (P-256)")
	flag.Parse()
	if vin == "" {
		fmt.Fprintln(os.Stderr, "VIN required")
		os.Exit(1)
	}

	privateKey, err := protocol.LoadPrivateKey(keyFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "load key: %s\n", err)
		os.Exit(1)
	}

	// Linux에서 어댑터를 고르려면 ble.InitAdapterWithID("hci0"); 기본 어댑터면 생략 가능.
	if err := ble.InitAdapterWithID(""); err != nil {
		if ble.IsAdapterError(err) {
			fmt.Fprintln(os.Stderr, ble.AdapterErrorHelpMessage(err))
		} else {
			fmt.Fprintf(os.Stderr, "adapter: %s\n", err)
		}
		os.Exit(1)
	}

	connectCtx, cancelConnect := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancelConnect()

	conn, err := ble.NewConnection(connectCtx, vin) // 스캔 + Dial + 서비스 탐색 + 구독 + MTU
	if err != nil {
		fmt.Fprintf(os.Stderr, "connect: %s\n", err)
		os.Exit(1)
	}
	defer conn.Close()

	car, err := vehicle.NewVehicle(conn, privateKey, nil)
	if err != nil {
		fmt.Fprintf(os.Stderr, "vehicle: %s\n", err)
		os.Exit(1)
	}
	if err := car.Connect(connectCtx); err != nil { // 디스패처 goroutine 시작
		fmt.Fprintf(os.Stderr, "dispatcher: %s\n", err)
		os.Exit(1)
	}
	defer car.Disconnect()

	// nil → VCSEC + INFOTAINMENT 둘 다 핸드셰이크. Infotainment가 자고 있으면 여기서 오래 걸릴 수 있다.
	if err := car.StartSession(connectCtx, nil); err != nil {
		fmt.Fprintf(os.Stderr, "handshake: %s\n", err)
		os.Exit(1)
	}

	cmdCtx, cancelCmd := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancelCmd()

	if err := car.Unlock(cmdCtx); err != nil {
		switch {
		case protocol.MayHaveSucceeded(err):
			fmt.Fprintf(os.Stderr, "unlock sent but unconfirmed: %s\n", err) // 재전송 금지
		case errors.Is(err, protocol.ErrKeyNotPaired):
			fmt.Fprintln(os.Stderr, "public key not paired; run add-key-request")
		case protocol.IsNominalError(err):
			fmt.Fprintf(os.Stderr, "vehicle refused: %s\n", err) // 예: closures open
		default:
			fmt.Fprintf(os.Stderr, "unlock failed: %s\n", err)
		}
		os.Exit(1)
	}
	fmt.Println("unlocked")
}
```

실행: `go run . -vin $TESLA_VIN -key private.pem`
주의: `ble.NewConnection`은 스캔 실패/Dial 실패를 ctx 만료까지 무한 재시도한다. `Connectable=false`(`ErrMaxConnectionsExceeded`)는 즉시 반환.

---

## R2. 세션 캐시 사용

목적: 재실행 시 핸드셰이크 생략. `pkg/cache/example_test.go` 와 동일 패턴.

```go
import (
	"errors"
	"io/fs"
	"os"

	"github.com/teslamotors/vehicle-command/pkg/cache"
)

func loadCache(path string) *cache.SessionCache {
	c, err := cache.ImportFromFile(path)
	if err != nil {
		if !errors.Is(err, fs.ErrNotExist) {
			// 손상된 캐시는 지우고 새로 만든다.
			_ = os.Remove(path)
		}
		return cache.New(5) // 최대 5대 (0이면 무제한)
	}
	return c
}

// ... conn, privateKey 준비 후:
sessions := loadCache(cachePath)
car, err := vehicle.NewVehicle(conn, privateKey, sessions) // 캐시에 VIN 항목이 있으면 즉시 ready 세션으로 로드
// ...
if err := car.StartSession(ctx, nil); err != nil { /* 캐시 히트면 즉시 반환 */ }
defer func() {
	if err := car.UpdateCachedSessions(sessions); err == nil {
		// ExportToFile은 0644로 생성한다. 캐시는 세션 카운터/epoch를 담으므로 권한을 좁힌다.
		if err := sessions.ExportToFile(cachePath); err == nil {
			_ = os.Chmod(cachePath, 0o600)
		}
	}
}()
```

주의

- 캐시는 개인 키에 묶인다. 다른 키로 쓰면 첫 명령이 `INVALID_SIGNATURE`로 실패하고 차량이 보낸 session_info로 자동 복구된다 (추가 비용 없음).
- `NewVehicle`에서 로드된 세션은 `ready=true`이므로 `StartSession`이 핸드셰이크를 보내지 않는다. Infotainment가 잠들어 있어도 `StartSession`은 성공하고, 첫 Infotainment 명령이 `ErrBusy`/타임아웃으로 실패할 수 있다.
- `UpdateCachedSessions`는 defer 스택에서 `Disconnect`보다 **먼저** 실행되어야 한다 (`tesla-control`: `defer car.Disconnect()` 다음에 `defer config.UpdateCachedSessions(car)`).
- 동일 캐시 파일을 여러 프로세스가 동시에 쓰면 카운터가 충돌한다 (`INVALID_TOKEN_OR_COUNTER`).

---

## R3. VCSEC만 사용 (Infotainment를 깨우지 않음)

목적: 잠금/도어/수면 상태 조회와 잠금 명령을 Infotainment 핸드셰이크 없이 수행.

```go
import (
	"github.com/teslamotors/vehicle-command/pkg/protocol"
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/vcsec"
	"google.golang.org/protobuf/encoding/protojson"
)

// 핸드셰이크 대상을 VCSEC로 제한
if err := car.StartSession(ctx, []universal.Domain{protocol.DomainVCSEC}); err != nil {
	return err
}

// 비인증 정보 요청: 개인 키/세션 없이도 동작 (InformationRequest GET_STATUS)
status, err := car.BodyControllerState(ctx) // *vcsec.VehicleStatus
if err != nil {
	return err
}
fmt.Println(protojson.Format(status))
if status.GetVehicleSleepStatus() == vcsec.VehicleSleepStatus_E_VEHICLE_SLEEP_STATUS_ASLEEP {
	fmt.Println("infotainment asleep; VCSEC-only commands still work")
}
if status.GetVehicleLockState() != vcsec.VehicleLockState_E_VEHICLELOCKSTATE_LOCKED {
	if err := car.Lock(ctx); err != nil { // RKE_ACTION_LOCK, VCSEC 세션으로 인가
		return err
	}
}
```

주의

- VCSEC 세션만 연 상태에서 Infotainment 명령(`ClimateOn` 등)을 부르면 `protocol.ErrNoSession`.
- `car.Wakeup(ctx)`는 BLE에서 `RKE_ACTION_WAKE_VEHICLE`을 VCSEC로 보낸다 (VCSEC 세션 필요). 깨운 뒤 Infotainment를 쓰려면 `car.StartSession(ctx, []universal.Domain{protocol.DomainInfotainment})`를 추가로 호출한다.
- VCSEC 도메인 명령: `Lock`, `Unlock`, `RemoteDrive`, `AutoSecureVehicle`, `Wakeup`(BLE), `OpenTrunk`/`CloseTrunk`/`ActuateTrunk`, `OpenFrunk`, `Open/Close/StopTonneau`, `AddKey*`, `RemoveKey`, `KeySummary`, `KeyInfoBySlot`, `BodyControllerState`.

---

## R4. BLE 키 페어링 프로그램

목적: 개인 키 없이 차량에 공개 키 등록 요청 → NFC 탭 → 등록 확인.
전제: 사용자가 차량 안에 있고 NFC 카드 소지. `SendAddKeyRequestWithRole`은 `RoutableMessage`가 아니라 `vcsec.ToVCSECMessage{SignedMessage{SIGNATURE_TYPE_PRESENT_KEY}}`를 `conn.Send`로 **직접** 전송한다.

```go
import (
	"crypto/ecdh"
	"errors"
	"time"

	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/keys"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/vcsec"
	"github.com/teslamotors/vehicle-command/pkg/vehicle"
)

func pair(ctx context.Context, conn *ble.Connection, publicKey *ecdh.PublicKey) error {
	car, err := vehicle.NewVehicle(conn, nil, nil) // 개인 키 없음
	if err != nil {
		return err
	}
	if err := car.Connect(ctx); err != nil { // 응답 수신을 위해 디스패처는 필요
		return err
	}
	defer car.Disconnect()

	err = car.SendAddKeyRequestWithRole(ctx, publicKey,
		keys.Role_ROLE_OWNER,                            // owner|driver|fm|vehicle_monitor|charging_manager
		vcsec.KeyFormFactor_KEY_FORM_FACTOR_CLOUD_KEY)   // nfc_card|ios_device|android_device|cloud_key
	if err != nil {
		return err // ErrRequiresBLE: conn이 inet일 때; ErrInvalidPublicKey: P-256 아님
	}
	fmt.Println("Tap your NFC card on the center console, then confirm on the screen.")

	// 등록 확인: Infotainment가 이 키를 알면 SessionInfo가 성공한다.
	// (VCSEC 등록 직후 Infotainment 동기화까지 수십 초 걸릴 수 있음)
	deadline := time.Now().Add(2 * time.Minute)
	for time.Now().Before(deadline) {
		pollCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		_, err := car.SessionInfo(pollCtx, publicKey, protocol.DomainInfotainment)
		cancel()
		if err == nil {
			fmt.Println("key enrolled")
			return nil
		}
		if !errors.Is(err, protocol.ErrKeyNotPaired) && !errors.Is(err, context.DeadlineExceeded) {
			return err
		}
		time.Sleep(3 * time.Second)
	}
	return errors.New("timed out waiting for enrollment")
}
```

호출: `publicKey, _ := protocol.LoadPublicKey("public_key.pem")` (PEM 공개 키, 개인 키 PEM, 65바이트 바이너리, 130자 hex 모두 허용).
CLI 동등 명령: `tesla-control -ble -vin $VIN add-key-request public_key.pem owner cloud_key` (인증 불필요, `-ble` 필수).
주의: 요청 자체에 대한 응답(`OPERATIONSTATUS_WAIT` 등)은 이 함수가 읽지 않는다. `SessionInfo` 폴링이 유일한 확인 수단이다. Infotainment가 잠들어 있으면 `SessionInfo`가 타임아웃될 수 있으므로 VCSEC 도메인으로 먼저 확인해도 된다.

---

## R5. 등록된 키 목록 조회

`cmd/tesla-control/commands.go` `list-keys` 로직. 인증 불필요.

```go
summary, err := car.KeySummary(ctx) // *vcsec.WhitelistInfo
if err != nil {
	return err
}
slot := uint32(0)
for mask := summary.GetSlotMask(); mask > 0; mask >>= 1 {
	if mask&1 == 1 {
		details, err := car.KeyInfoBySlot(ctx, slot) // *vcsec.WhitelistEntryInfo
		if err != nil {
			if errors.Is(err, context.DeadlineExceeded) {
				return err
			}
			fmt.Fprintf(os.Stderr, "slot %d: %s\n", slot, err)
		} else if details != nil {
			fmt.Printf("%02x\t%s\t%s\n",
				details.GetPublicKey().GetPublicKeyRaw(), // 65바이트 uncompressed
				details.GetKeyRole(),                      // keys.Role
				details.GetMetadataForKey().GetKeyFormFactor())
		}
	}
	slot++
}
```

주의: 슬롯마다 VCSEC 왕복이 발생한다. VCSEC는 동시 요청을 피해야 하므로 순차 호출을 유지한다.

---

## R6. 상태 조회 (Infotainment)

```go
import (
	"github.com/teslamotors/vehicle-command/pkg/vehicle"
	"google.golang.org/protobuf/encoding/protojson"
)

categories := []vehicle.StateCategory{
	vehicle.StateCategoryCharge, vehicle.StateCategoryClimate, vehicle.StateCategoryDrive,
	vehicle.StateCategoryLocation, vehicle.StateCategoryClosures, vehicle.StateCategoryChargeSchedule,
	vehicle.StateCategoryPreconditioningSchedule, vehicle.StateCategoryTirePressure,
	vehicle.StateCategoryMedia, vehicle.StateCategoryMediaDetail, vehicle.StateCategorySoftwareUpdate,
	vehicle.StateCategoryParentalControls,
}
for _, c := range categories {
	data, err := car.GetState(ctx, c) // *carserver.VehicleData
	if err != nil {
		fmt.Fprintf(os.Stderr, "category %d: %s\n", c, err)
		continue
	}
	fmt.Println(protojson.Format(data))
}
```

주의: BLE 용도. 인터넷에서는 Fleet API `vehicle_data`가 효율적이다. 응답이 크면 `RESPONSE_MTU_EXCEEDED`(MHS=true). `StateCategoryLocation`은 여러 (lat, lon) 필드를 반환할 수 있다 (`carserver.LocationState` 주석 참조).

---

## R7. 충전 스케줄 추가

`vehicle.ChargeSchedule = carserver.ChargeSchedule` (`pkg/protocol/protobuf/common.proto`).

```go
import "time"

// DaysOfWeek 비트마스크: Sun=1 Mon=2 Tue=4 Wed=8 Thu=16 Fri=32 Sat=64, all=127, weekdays=62
const weekdays = 2 | 4 | 8 | 16 | 32

schedule := &vehicle.ChargeSchedule{
	Id:           uint64(time.Now().Unix()), // 새 스케줄: epoch 초. 기존 ID를 주면 수정
	Name:         "night",
	DaysOfWeek:   weekdays,
	StartEnabled: true,
	StartTime:    22 * 60, // 자정 이후 분
	EndEnabled:   true,
	EndTime:      6 * 60,  // 다음날 06:00 허용
	OneTime:      false,
	Enabled:      true,
	Latitude:     37.5665,
	Longitude:    126.9780,
}
if err := car.AddChargeSchedule(ctx, schedule); err != nil {
	return err
}
fmt.Println(schedule.Id)

// 삭제
_ = car.RemoveChargeSchedule(ctx, schedule.Id)
_ = car.BatchRemoveChargeSchedules(ctx, true /*home*/, false /*work*/, false /*other*/)

// 프리컨디션 스케줄도 동일 패턴: vehicle.PreconditionSchedule{Id, DaysOfWeek, PreconditionTime, OneTime, Enabled, Latitude, Longitude}
```

CLI 동등: `tesla-control charging-schedule-add weekdays 22:00-6:00 37.5665 126.9780`.

---

## R8. 인터넷 경로 (Fleet API)

`examples/unlock/unlock.go` + 웨이크업.

```go
import (
	"github.com/teslamotors/vehicle-command/pkg/account"
	"github.com/teslamotors/vehicle-command/pkg/connector/inet"
)

token, _ := os.ReadFile(tokenFile) // OAuth access token (JWT). 서버 도메인은 토큰의 aud/ou_code에서 결정
acct, err := account.New(string(token), "example-app/1.0.0") // userAgent "" 이면 빌드 정보로 생성
if err != nil {
	return err
}
car, err := acct.GetVehicle(ctx, vin, privateKey, sessions) // inet.Connection 생성 (아직 네트워크 호출 없음)
if err != nil {
	return err
}
if err := car.Connect(ctx); err != nil {
	return err
}
defer car.Disconnect()

if err := car.StartSession(ctx, nil); err != nil {
	if errors.Is(err, inet.ErrVehicleNotAwake) { // 503 또는 408 "vehicle is offline"
		if err := car.Wakeup(ctx); err != nil { // POST wake_up, 10s 간격 폴링, state=="online" 까지
			return err
		}
		if err := car.StartSession(ctx, nil); err != nil {
			return err
		}
	} else if errors.Is(err, protocol.ErrProtocolNotSupported) { // 422: 2021 이전 S/X
		_, err := acct.SendVehicleFleetAPICommand(ctx, vin, "command/door_unlock", map[string]any{})
		return err
	} else {
		return err
	}
}
return car.Unlock(ctx) // HMAC-SHA256 인증, 평문 전송 (inet.PreferredAuthMethod == AuthMethodHMAC)
```

주의

- `Wakeup`에 30~60s ctx를 준다.
- 인터넷 경로에서는 `SendAddKeyRequestWithRole`이 `ErrRequiresBLE`. `SetPINToDrive`는 반대로 인터넷 경로에서만 허용.
- 421 응답을 받으면 `inet.Connection`이 서버 도메인을 자동 갱신한다.

---

## R9. `pkg/cli` 기반 커스텀 CLI 뼈대

`cmd/tesla-control/main.go` 축약. 플래그/환경 변수/키링/캐시/전송 선택을 모두 위임한다.

```go
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/teslamotors/vehicle-command/pkg/cli"
	"github.com/teslamotors/vehicle-command/pkg/connector/ble"
)

func main() {
	status := 1
	defer func() { os.Exit(status) }()

	config, err := cli.NewConfig(cli.FlagAll) // FlagVIN|FlagOAuth|FlagPrivateKey|FlagBLE
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return
	}
	var forceBLE bool
	flag.BoolVar(&forceBLE, "ble", false, "Force BLE")
	config.RegisterCommandLineFlags() // -vin -key-name -key-file -session-cache -disable-session-cache -domain -token-name -token-file -keyring-type -keyring-file-dir -keyring-debug (-bt-adapter: Linux)
	flag.Parse()
	config.ReadFromEnvironment() // TESLA_VIN TESLA_KEY_NAME TESLA_KEY_FILE TESLA_TOKEN_NAME TESLA_TOKEN_FILE TESLA_CACHE_FILE TESLA_KEYRING_*; flag.Parse 이후에 호출해야 플래그가 우선

	if forceBLE {
		config.Flags = cli.FlagBLE | cli.FlagVIN | cli.FlagPrivateKey // OAuth 제외 → Connect가 BLE 선택
	}

	if err := config.LoadCredentials(); err != nil { // 키링 비밀번호 프롬프트를 타임아웃 밖에서 처리
		fmt.Fprintln(os.Stderr, err)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	acct, car, err := config.Connect(ctx) // 토큰 있으면 inet, 아니면 BLE. car.Connect + StartSession(config.Domains) 까지 수행
	if err != nil {
		if ble.IsAdapterError(err) {
			fmt.Fprintln(os.Stderr, ble.AdapterErrorHelpMessage(err))
		} else {
			fmt.Fprintln(os.Stderr, err)
		}
		return
	}
	_ = acct
	if car != nil {
		defer car.Disconnect()
		defer config.UpdateCachedSessions(car) // Disconnect보다 먼저 실행됨
	}

	cmdCtx, cancelCmd := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancelCmd()
	if err := car.FlashLights(cmdCtx); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return
	}
	status = 0
}
```

주의: `config.Connect`는 `VIN`이 비어 있고 토큰도 없으면 `must provide VIN and/or OAuth token`. 개인 키가 없으면 `StartSession`을 건너뛴 비인증 연결을 반환한다 (`add-key-request`, `list-keys` 등만 가능).

---

## R10. 커스텀 Connector 구현 스켈레톤

`connector.Connector` 7개 메서드. `ble.Connection`을 템플릿으로 한다.

```go
package myconn

import (
	"context"
	"sync"
	"time"

	"github.com/teslamotors/vehicle-command/pkg/connector"
)

type Connection struct {
	vin   string
	inbox chan []byte // 디스패처가 Receive()로 읽음. 닫히면 디스패처 goroutine 종료
	mu    sync.Mutex  // Send 직렬화
	once  sync.Once   // Close 멱등
	// ... 실제 전송 핸들
}

func New(vin string) *Connection {
	return &Connection{vin: vin, inbox: make(chan []byte, connector.BufferSize)} // BufferSize = 5
}

func (c *Connection) Receive() <-chan []byte { return c.inbox }

// buffer는 완성된 RoutableMessage 바이트. 전송 계층 프레이밍(BLE: 2바이트 길이 프리픽스)은 여기서 붙인다.
// 반환 오류가 protocol.Error를 구현하면 디스패처가 ShouldRetry로 재전송을 결정한다.
func (c *Connection) Send(ctx context.Context, buffer []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	// ... 전송. 응답은 별도 goroutine에서 c.deliver(msg)
	return nil
}

// 수신 goroutine에서 호출. 가득 차면 드롭 (블로킹 금지: 디스패처를 막으면 안 됨)
func (c *Connection) deliver(msg []byte) {
	select {
	case c.inbox <- msg:
	default:
	}
}

func (c *Connection) VIN() string { return c.vin }

func (c *Connection) Close() {
	c.once.Do(func() {
		// 전송 핸들 정리. inbox를 닫으면 dispatcher.listen이 종료된다 (inet은 닫고, ble은 닫지 않음; 둘 다 허용).
	})
}

// 차량과 직접 통신(BLE, 로컬 릴레이)이면 GCM (암호화). 중간자가 내용을 검사해야 하면(Fleet API) HMAC.
func (c *Connection) PreferredAuthMethod() connector.AuthMethod { return connector.AuthMethodGCM }

// 재전송/재시도 대기. BLE·inet 모두 1s.
func (c *Connection) RetryInterval() time.Duration { return time.Second }

// 요청→session_info 응답 최대 허용 지연. 초과하면 시계 동기화 정보를 폐기. BLE 4s, inet 10s.
func (c *Connection) AllowedLatency() time.Duration { return 4 * time.Second }
```

사용: `car, err := vehicle.NewVehicle(myconn.New(vin), privateKey, sessions)`.
Fleet API 기능(웨이크업, REST 명령)을 지원하려면 `connector.FleetAPIConnector`(추가 메서드 `SendFleetAPICommand(ctx, endpoint string, command interface{}) ([]byte, error)`, `Wakeup(ctx) error`)도 구현한다. `Vehicle.Wakeup`과 `SendAddKeyRequestWithRole`/`SetPINToDrive`는 이 인터페이스 단언으로 경로를 분기한다.

주의

- `Receive`, `Send`는 스레드 안전해야 한다. 디스패처는 `Send`를 여러 goroutine에서 동시에 호출할 수 있다 (`StartSessions`가 도메인별 병렬 핸드셰이크).
- `MaxResponseLength` = 100000 바이트까지 수용해야 한다.
- 수신 메시지는 RoutableMessage 하나당 하나의 `[]byte`여야 한다 (BLE는 2바이트 길이 프레이밍으로 재조립).

---

## R11. 새 차량 명령 추가 절차

1. protobuf에 필드가 없으면 `pkg/protocol/protobuf/*.proto` 수정 후 `make proto-gen` (`protoc` + `protoc-gen-go` 필요).
2. `pkg/vehicle/<카테고리>.go`에 메서드 추가.
3. `cmd/tesla-control/commands.go`의 `commands` 맵에 항목 추가.
4. `pkg/proxy/command.go` `ExtractCommandAction`에 `case` 추가 (Fleet API 엔드포인트 이름과 파라미터 이름을 맞춘다).
5. 테스트: `pkg/vehicle/*_test.go`, `pkg/proxy/command_test.go`.
6. `make format && make linters && make test` (또는 `./check-all.sh`).

### 템플릿 A: Infotainment (CarServer Action)

```go
// pkg/vehicle/climate.go 등
func (v *Vehicle) SetExampleFeature(ctx context.Context, on bool) error {
	return v.executeCarServerAction(ctx,
		&carserver.Action_VehicleAction{
			VehicleAction: &carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_ExampleFeatureAction{ // oneof wrapper (생성 코드)
					ExampleFeatureAction: &carserver.ExampleFeatureAction{On: on},
				},
			},
		})
}
```

`executeCarServerAction` → `getCarServerResponse`: `carserver.Action{ActionMsg: action}` 마샬 → `v.Send(ctx, universal.Domain_DOMAIN_INFOTAINMENT, payload, v.authMethod)` → `carserver.Response` 언마샬 → `actionStatus.result == ERROR` 면 `NominalError`. 응답 데이터가 필요하면 `getCarServerResponse`를 직접 호출한다 (`GetState` 참고).

### 템플릿 B: VCSEC (UnsignedMessage)

```go
// pkg/vehicle/vcsec.go
func (v *Vehicle) ExampleRKE(ctx context.Context) error {
	return v.executeRKEAction(ctx, vcsec.RKEAction_E_RKE_ACTION_UNLOCK) // 기존 헬퍼 재사용
}

// 새 서브메시지 타입이면 getVCSECResult를 직접 사용
func (v *Vehicle) ExampleVCSEC(ctx context.Context) error {
	payload := vcsec.UnsignedMessage{
		SubMessage: &vcsec.UnsignedMessage_ClosureMoveRequest{
			ClosureMoveRequest: &vcsec.ClosureMoveRequest{ChargePort: vcsec.ClosureMoveType_E_CLOSURE_MOVE_TYPE_OPEN},
		},
	}
	encoded, err := proto.Marshal(&payload)
	if err != nil {
		return err
	}
	// 종료 판정: RKE/Closure 계열은 commandStatus가 없는 메시지가 최종 응답
	done := func(m *vcsec.FromVCSECMessage) (bool, error) { return m.GetCommandStatus() == nil, nil }
	_, err = v.getVCSECResult(ctx, encoded, v.authMethod, done)
	return err
}
```

whitelist 계열은 `executeWhitelistOperation` (종료 판정 `isWhitelistOperationComplete`), 정보 조회는 `getVCSECInfo(ctx, requestType, slot)` 을 재사용한다.

### CLI 항목

```go
"example-feature": {
	help:             "Set example feature to STATE ('on' or 'off')",
	requiresAuth:     true,  // 개인 키 필요
	requiresFleetAPI: false, // OAuth 토큰 필요 여부
	// domain: protocol.DomainVCSEC, // VCSEC 전용이면 핸드셰이크 범위를 제한
	args: []Argument{{name: "STATE", help: "'on' or 'off'"}},
	handler: func(ctx context.Context, _ *account.Account, car *vehicle.Vehicle, args map[string]string) error {
		switch args["STATE"] {
		case "on":
			return car.SetExampleFeature(ctx, true)
		case "off":
			return car.SetExampleFeature(ctx, false)
		}
		return fmt.Errorf("%w: STATE must be 'on' or 'off'", ErrCommandLineArgs)
	},
},
```

### 프록시 case

```go
case "example_feature":
	on, err := params.getBool("on", true) // required=true → 없으면 NominalError "missing on param"
	if err != nil {
		return nil, err
	}
	return func(v *vehicle.Vehicle) error { return v.SetExampleFeature(ctx, on) }, nil
```

`params.getNumber`는 JSON 숫자를 `float64`로만 받는다 (정수 문자열 거부).

---

## R12. 저수준 `Send` / `SendMessage`

```go
import (
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
	"github.com/teslamotors/vehicle-command/pkg/connector"
	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/vehicle"
	"google.golang.org/protobuf/proto"
)

// (a) 페이로드만 주면 RoutableMessage 조립·인가·재시도까지 처리. 응답 페이로드 바이트 반환.
respBytes, err := car.Send(ctx, protocol.DomainInfotainment, actionBytes, connector.AuthMethodGCM)
// BLE에서는 AuthMethodGCM, inet에서는 AuthMethodHMAC (car의 conn.PreferredAuthMethod()와 맞출 것)
var resp carserver.Response
_ = proto.Unmarshal(respBytes, &resp)

// (b) RoutableMessage를 직접 만들어 보내고 Receiver로 여러 응답을 읽음 (인가 없음 = AuthMethodNone)
msg := &universal.RoutableMessage{
	ToDestination: &universal.Destination{
		SubDestination: &universal.Destination_Domain{Domain: protocol.DomainVCSEC}, // BROADCAST면 오류
	},
	Payload: &universal.RoutableMessage_ProtobufMessageAsBytes{ProtobufMessageAsBytes: unsignedBytes},
	Flags:   vehicle.DefaultFlags, // 1 << FLAG_ENCRYPT_RESPONSE
}
recv, err := car.SendMessage(ctx, msg) // Uuid, FromDestination(routing_address)는 디스패처가 채움
if err != nil {
	return err
}
defer recv.Close() // 반드시 호출: 핸들러 맵에서 제거
for {
	select {
	case reply := <-recv.Recv():
		if err := protocol.GetError(reply); err != nil {
			return err
		}
		// reply.GetProtobufMessageAsBytes() 파싱. VCSEC는 최대 3개 응답이 올 수 있음 (종료 규칙은 08-errors §3.5)
	case <-ctx.Done():
		return ctx.Err()
	}
}
```

주의: `SendMessage`는 인가된 메시지(예: 다른 엔티티가 서명한 메시지)를 그대로 전달하는 용도다. 세션 인가가 필요한 명령은 `Send`를 사용한다. `Vehicle.Flags`를 바꾸면 이후 `Send`의 `flags`가 바뀐다.

---

## R13. HTTP 프록시 임베드 + 인증 미들웨어

`cmd/tesla-http-proxy/main.go`는 `pkg/proxy`의 얇은 래퍼다. TLS를 끄거나 클라이언트 인증을 추가하려면 직접 구성한다.

```go
package main

import (
	"context"
	"net/http"
	"time"

	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/proxy"
)

func main() {
	skey, err := protocol.LoadPrivateKey("config/fleet-key.pem") // 명령 인증 키 (TLS 키와 달라야 함)
	if err != nil {
		panic(err)
	}
	p, err := proxy.New(context.Background(), skey, 10000) // cacheSize: 세션 캐시 최대 VIN 수
	if err != nil {
		panic(err)
	}
	p.Timeout = 10 * time.Second // proxy.DefaultTimeout

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "expected" { // 자체 클라이언트 인증
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		p.ServeHTTP(w, r) // /health, /api/1/vehicles/{vin}/command/{cmd}, /api/1/vehicles/fleet_telemetry_config, 그 외 REST 포워딩
	})
	// 공식 바이너리와 동일하게 TLS 사용을 권장. 전문가용 non-TLS: http.ListenAndServe(addr, handler)
	panic(http.ListenAndServeTLS("localhost:4443", "config/tls-cert.pem", "config/tls-key.pem", handler))
}
```

주의: 클라이언트는 `Authorization: Bearer <OAuth token>`을 매 요청에 보내야 한다 (프록시는 토큰을 저장하지 않음). 프록시는 VIN별 뮤텍스로 명령을 직렬화하고, 요청 본문을 1 MiB로 제한한다.

---

## R14. Fleet telemetry 설정 서명 (JWS)

CLI:

```bash
# fleet 전체 대상 (aud = "com.tesla.fleet.TelemetryClient")
tesla-jws -fleet sign TelemetryClient telemetry_config.json > signed-config.jws
# 특정 차량 대상 (aud = "com.tesla.vehicle.<VIN>.TelemetryClient")
tesla-jws -vin $VIN sign TelemetryClient telemetry_config.json
# 서명 검증 (발급자 신뢰 여부는 검사하지 않음)
tesla-jws verify signed-config.jws
```

라이브러리:

```go
import (
	"github.com/golang-jwt/jwt/v5"
	"github.com/teslamotors/vehicle-command/pkg/sign"
)

claims := jwt.MapClaims{"fields": map[string]any{"VehicleSpeed": map[string]any{"interval_seconds": 10}}, "exp": 1900000000}
token, err := sign.SignMessageForFleet(skey, "TelemetryClient", claims) // "aud","iss" 는 덮어써짐. iss = base64(공개 키)
// 또는 sign.SignMessageForVehicle(skey, vin, "TelemetryClient", claims)
```

알고리즘 헤더는 `Tesla.SS256` (Schnorr/P-256, 표준 JWS 알고리즘 아님). 결과를 Fleet API `/api/1/vehicles/fleet_telemetry_config_jws`에 POST 하거나, 프록시의 `/api/1/vehicles/fleet_telemetry_config`에 `{"vins":[...],"config":{...}}`를 POST 하면 프록시가 서명 후 전달한다.

---

## R15. 테스트 작성 가이드

기존 테스트 하네스를 재사용한다. 모두 in-package 테스트(unexported 접근)이므로 새 테스트도 같은 패키지에 둔다.

### `pkg/vehicle` — `testSender` (dispatcher 대체)

`pkg/vehicle/vehicle_test.go`의 `newTestVehicle()`은 `sender` 인터페이스를 구현한 `testSender`를 주입한다.

```go
// pkg/vehicle/example_test.go (package vehicle)
func TestExampleFeatureNominalError(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	v, dispatch := newTestVehicle()
	if err := v.Connect(ctx); err != nil {
		t.Fatal(err)
	}
	defer v.Disconnect()

	// Infotainment 거부 응답 고정
	rsp := carserver.Response{ActionStatus: &carserver.ActionStatus{
		Result:       carserver.OperationStatus_E_OPERATIONSTATUS_ERROR,
		ResultReason: &carserver.ResultReason{Reason: &carserver.ResultReason_PlainText{PlainText: "not in park"}},
	}}
	payload, _ := proto.Marshal(&rsp)
	dispatch.fixedResponse = &universal.RoutableMessage{
		Payload: &universal.RoutableMessage_ProtobufMessageAsBytes{ProtobufMessageAsBytes: payload},
	}

	err := v.SetExampleFeature(ctx, true)
	if !protocol.IsNominalError(err) {
		t.Fatalf("expected NominalError, got %v", err)
	}
}
```

`testSender` 제어점: `fixedResponse` (모든 Recv에 동일 응답), `EnqueueError(err)` (다음 Send가 반환할 오류 큐), `SendError` (항상 반환), `ConnectionErrors` (StartSessions가 순서대로 반환), `EnqueueResponse(t, msg)`. `RetryInterval`은 1ms라 재시도 테스트가 빠르다. VCSEC 오류 검증 헬퍼: `vcsec_test.go`의 `checkNominalError`, `checkWhitelistOperationStatus`, `testPublicKey()`.

### `internal/dispatcher` — `dummyConnector` (차량 시뮬레이터)

`dispatcher_test.go`의 `newDummyConnector(t)`는 도메인별 차량 키를 생성하고 `authentication.Verifier`로 실제 핸드셰이크/서명 검증을 수행한다. 세션 캐시·재시도·리플레이 로직을 바꿨다면 `TestCache`, `TestRetrySend`, `TestUnsolicitedSessionInfo`, `TestCorruptedSessionInfo`, `TestDiscardUnauthenticatedSessionInfo`를 기준으로 테스트를 추가한다.

### `pkg/proxy` — HTTP/차량 목

`proxy_test.go`: `p.client = roundTripFunc(func(*http.Request) (*http.Response, error) {...})`로 Tesla 서버를, `p.fetchVehicle = func(...) (vehicleSession, error) { return &mockVehicle{...}, nil }`로 차량을 대체한다. `mockVehicle`은 `connectErr`, `sessionErr`, `executeErr`로 각 단계 실패를 주입한다. 엔드포인트 파싱 테스트는 `command_test.go`의 테이블(`proxy.ExtractCommandAction(ctx, command, params)`)에 행을 추가한다.

### 프로토콜 벡터

`internal/authentication/protocol_doc_test.go`는 `pkg/protocol/protocol.md`의 AES-GCM 예제(메타데이터 `000105...ff`, K `1b2fce19...`, nonce `dbf79447...`, 태그 `c228e0ff...`)를 고정한다. 메타데이터 직렬화나 KDF를 바꾸면 이 테스트와 문서를 함께 갱신한다. 테스트 키: `internal/authentication/test_data/*.pem`, `pkg/protocol/test/*.pem`.

### 실행

```bash
go test ./pkg/vehicle/ -run 'TestVehicleRetryFail|TestNominalVSCECError' -v
go test -cover ./...      # make test 와 동일 (+ go vet)
./check-all.sh            # build + test + vet + gofmt 검사 (+ shellcheck)
```

---

## 검증 체크리스트

- [ ] 모든 예제가 `go build ./...` 로 컴파일되는가 (이 문서 작성 환경에는 Go가 없었음).
- [ ] `vehicle.NewVehicle(conn, privateKey, sessionCache)`, `StartSession(ctx, domains)`, `SessionInfo(ctx, *ecdh.PublicKey, domain)`, `SendAddKeyRequestWithRole(ctx, pk, keys.Role, vcsec.KeyFormFactor)`, `GetState(ctx, StateCategory)`, `Send(ctx, domain, payload, auth)`, `SendMessage(ctx, *RoutableMessage)` 시그니처가 `pkg/vehicle/*.go`와 일치하는가.
- [ ] `cache.New(int)`, `ImportFromFile`, `ExportToFile`, `Vehicle.UpdateCachedSessions(*cache.SessionCache)` 가 `pkg/cache/cache.go`, `pkg/vehicle/vehicle.go`와 일치하는가.
- [ ] `ChargeSchedule` 필드명이 `pkg/protocol/protobuf/common.proto` 생성 코드와 일치하는가 (`DaysOfWeek`, `StartEnabled`, `StartTime`, `EndEnabled`, `EndTime`, `OneTime`, `Enabled`, `Latitude`, `Longitude`, `Id`, `Name`).
- [ ] R4가 `RoutableMessage`가 아닌 `ToVCSECMessage`를 직접 보내는 동작(`pkg/vehicle/security.go SendAddKeyRequestWithRole`)을 정확히 설명하는가.
- [ ] R10의 `connector.Connector` 메서드 7개(`Receive`, `Send`, `VIN`, `Close`, `PreferredAuthMethod`, `RetryInterval`, `AllowedLatency`)가 `pkg/connector/connector.go`와 일치하는가.
- [ ] R11 순서(proto → vehicle → commands.go → proxy/command.go → 테스트 → `make format/linters/test`)가 `Makefile`, `.github/workflows/build.yml`과 일치하는가.
- [ ] R15의 하네스 이름(`newTestVehicle`, `testSender.fixedResponse/EnqueueError/SendError/ConnectionErrors`, `newDummyConnector`, `mockVehicle`, `roundTripFunc`)이 실제 테스트 파일과 일치하는가.
