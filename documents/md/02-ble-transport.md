# 02. BLE 전송 계층

`pkg/connector/ble`가 구현하는 BLE 전송 계층의 정밀 사양: GATT 식별자, 광고 이름, 스캔/연결 절차, MTU와 프레이밍, 수신 재조립, 어댑터 생명주기, 플랫폼별 차이, 다른 BLE 스택으로 교체할 때의 체크리스트.

관련 파일: `pkg/connector/ble/ble.go`, `pkg/connector/ble/device_darwin.go`, `pkg/connector/ble/device_linux.go`, `pkg/connector/ble/device_windows.go`, `pkg/connector/ble/doc.go`, `pkg/connector/connector.go`, `examples/ble/main.go`, `pkg/cli/config.go` (`ConnectLocal`), `pkg/protocol/protocol.md` ("BLE" 절)

---

## 1. GATT 식별자

| 항목 | UUID | 사용 |
|---|---|---|
| Service | `00000211-b2d1-43f0-9b88-960cebf8b91e` (`vehicleServiceUUID`) | `client.DiscoverServices([]ble.UUID{vehicleServiceUUID})` |
| To-vehicle characteristic (TX) | `00000212-b2d1-43f0-9b88-960cebf8b91e` (`toVehicleUUID`) | `client.WriteCharacteristic(txChar, chunk, false)` — `noRsp=false`, 즉 **write with response** |
| From-vehicle characteristic (RX) | `00000213-b2d1-43f0-9b88-960cebf8b91e` (`fromVehicleUUID`) | `client.Subscribe(rxChar, true, conn.rx)` — `ind=true`, 즉 **indication** 구독 |

연결 후 두 특성 모두에 `client.DiscoverDescriptors(nil, characteristic)`를 호출한다 (CCCD 확보; 실패 시 재시도 대상 오류).

---

## 2. 광고 Local Name

공식 (`protocol.md`, `ble.go` `VehicleLocalName`):

```
LocalName = "S" + lowercase_hex(SHA1(VIN)[0:8]) + "C"      // 총 18자
```

```go
func VehicleLocalName(vin string) string {
    vinBytes := []byte(vin)
    digest := sha1.Sum(vinBytes)
    return fmt.Sprintf("S%02xC", digest[:8])
}
```

예시: VIN `5YJS0000000000000` → `S1a87a5a75f3df858C`.

셸에서 검증:

```bash
printf '%s' '5YJS0000000000000' | shasum -a 1 | cut -c1-16     # → 1a87a5a75f3df858
# Linux: printf '%s' "$VIN" | sha1sum | cut -c1-16
```

`printf '%s'`를 써서 개행이 해시에 섞이지 않게 한다. 스캔 필터는 **Local Name 완전 일치**만 사용하며 Service UUID 광고 필터는 쓰지 않는다.

---

## 3. 스캔

### 3.1 공개 API

```go
type ScanResult struct {
    Address     string   // a.Addr().String()
    LocalName   string
    RSSI        int16
    Connectable bool
}

func ScanVehicleBeacon(ctx context.Context, vin string) (*ScanResult, error)   // mu 잠금, initAdapter(nil), localName 일치 첫 광고 반환
```

### 3.2 내부 동작 (`scanVehicleBeacon`)

1. `ctx2, cancel := context.WithCancel(ctx)`; `ch := make(chan ble.Advertisement, 1)`.
2. 콜백 `fn(a)`: `a.LocalName() != localName`이면 무시. 일치하면 `ch <- a` 후 `cancel()`. 이미 다른 goroutine이 넣었으면(`ctx2.Done()`) 그냥 반환.
3. `device.Scan(ctx2, false /*allowDup*/, fn)`.
   - **macOS 특이점**: `device.Scan`은 ctx가 취소될 때까지 반환하지 않으므로 **항상** 오류를 반환한다. 코드는 `!errors.Is(err, context.Canceled)`일 때만 오류로 취급한다.
4. `select { case a := <-ch: return advertisementToScanResult(a); case <-ctx.Done(): return ctx.Err() }`. 부모 `ctx`가 취소된 경우는 여기서 잡힌다.

### 3.3 Linux 스캔 파라미터 (`device_linux.go`)

```go
var scanParams = cmd.LESetScanParameters{
    LEScanType:           1,    // Active scanning
    LEScanInterval:       0x10, // 10ms
    LEScanWindow:         0x10, // 10ms
    OwnAddressType:       0,    // Static
    ScanningFilterPolicy: 2,    // Basic filtered
}
```

주석: 차량 모델/상태에 따라 광고 주기는 20ms 또는 150ms.

---

## 4. 연결 절차 (`tryToConnect`)

`NewConnection(ctx, vin)` = `NewConnectionFromScanResult(ctx, vin, nil)`. 단계별:

| # | 단계 | 실패 시 `(conn, retry, err)` |
|---|---|---|
| 1 | `mu.Lock()`; `initAdapter(nil)` | `(nil, false, err)` — 어댑터 오류는 재시도 안 함 |
| 2 | `target == nil`이면 `scanVehicleBeacon(ctx, localName)` | `(nil, true, "ble: failed to scan for <vin>: ...")` |
| 3 | `target.LocalName != localName` 검사 | `(nil, false, "ble: beacon with unexpected local name: '...'")` |
| 4 | `!target.Connectable` 검사 | `(nil, false, ErrMaxConnectionsExceeded)` |
| 5 | `device.Dial(ctx, ble.NewAddr(target.Address))` | `(nil, true, "ble: failed to dial for ...")` |
| 6 | `client.DiscoverServices([vehicleServiceUUID])`; 0개면 실패 | `(nil, true, ...)` |
| 7 | `client.DiscoverCharacteristics([toVehicleUUID, fromVehicleUUID], services[0])` | `(nil, true, ...)` |
| 8 | 각 특성에 `DiscoverDescriptors`; txChar/rxChar 배정 | `(nil, true, "ble: couldn't fetch descriptors")` / `"ble: failed to find required characteristics"` |
| 9 | `client.Subscribe(rxChar, true, conn.rx)` | `(nil, true, "ble: failed to subscribe to RX")` |
| 10 | `client.ExchangeMTU(ble.MaxMTU)` → `blockLength` 계산 (5절) | 실패해도 계속 (경고 로그, 기본 MTU 사용) |
| 11 | `log.Info("Connected to vehicle BLE")`; `(conn, false, nil)` | |

`Connection` 초기값: `inbox: make(chan []byte, 5)`.

### 4.1 재시도 루프 (`NewConnectionFromScanResult`)

```go
for {
    conn, retry, err := tryToConnect(ctx, vin, target)
    if err == nil { return conn, nil }
    if !retry || IsAdapterError(err) { return nil, err }
    log.Warning("BLE connection attempt failed: %s", err)
    if err := ctx.Err(); err != nil {
        if lastError != nil { return nil, lastError }   // ctx 만료 시 마지막 실제 오류를 우선 반환
        return nil, err
    }
    lastError = err
}
```

재시도 사이에 명시적 sleep은 없다. 스캔 단계가 자연스러운 대기 역할을 한다. `target`을 넘겼는데 dial이 계속 실패하면 바쁜 루프가 될 수 있으므로 ctx에 반드시 타임아웃을 건다 (예제는 30s, `tesla-control`은 `-connect-timeout` 기본 20s).

---

## 5. MTU와 블록 길이

```go
const (
    maxBLEMTUSize     = ble.MaxMTU   // 클라이언트가 수락하는 최대 MTU (go-ble 상수)
    maxBLEMessageSize = 1024
)
txMtu, err := client.ExchangeMTU(maxBLEMTUSize)
if err != nil {
    conn.blockLength = ble.DefaultMTU - 3          // 실패 시 폴백 (DefaultMTU=23 → 20바이트)
} else {
    conn.blockLength = min(txMtu, maxBLEMessageSize) - 3   // 3바이트 = ATT Write 헤더 (opcode 1 + handle 2)
}
```

`blockLength`는 한 번의 `WriteCharacteristic` 호출에 넣는 최대 바이트 수다. 프레이밍 헤더(2바이트)를 포함한 전체 버퍼를 이 크기로 잘라 보낸다.

---

## 6. 프레이밍

### 6.1 바이트 배치

```
offset 0 : uint8  len >> 8        (big-endian 상위 바이트)
offset 1 : uint8  len & 0xFF
offset 2 : payload[0 .. len-1]     RoutableMessage protobuf 직렬화 바이트
```

`len`은 payload 길이만 (헤더 2바이트 제외). 최대 유효 길이는 수신 측 기준 `maxBLEMessageSize = 1024`.

### 6.2 송신 분할 (`Connection.Send`)

```go
func (c *Connection) Send(_ context.Context, buffer []byte) error {
    c.lock.Lock(); defer c.lock.Unlock()
    var out []byte
    log.Debug("TX: %02x", buffer)
    out = append(out, uint8(len(buffer)>>8), uint8(len(buffer)))
    out = append(out, buffer...)
    blockLength := c.blockLength
    for len(out) > 0 {
        if blockLength > len(out) { blockLength = len(out) }
        if err := c.client.WriteCharacteristic(c.txChar, out[:blockLength], false); err != nil {
            return err
        }
        out = out[blockLength:]
    }
    return nil
}
```

- ctx는 무시된다 (`_ context.Context`). 쓰기 타임아웃은 go-ble의 dialer/listener 타임아웃(Linux 20s)에 의존.
- 하나의 논리 메시지가 여러 ATT write로 나뉘어도 차량은 길이 헤더로 재조립한다. 다른 메시지의 청크가 섞이지 않도록 `c.lock`으로 직렬화한다.
- 반환 오류는 `protocol.Error`를 구현하지 않으므로 `Dispatcher.Send`의 `ShouldRetry`는 false → 즉시 실패로 전파된다.

### 6.3 수신 재조립 (`rx` + `flush`)

```go
func (c *Connection) rx(p []byte) {
    if time.Since(c.lastRx) > rxTimeout {   // rxTimeout = 1s: 청크 간 간격이 1초를 넘으면 이전 조각을 버린다
        c.inputBuffer = []byte{}
    }
    c.lastRx = time.Now()
    c.inputBuffer = append(c.inputBuffer, p...)
    for c.flush() {}                        // 버퍼에 완성된 메시지가 여러 개면 모두 꺼낸다
}

func (c *Connection) flush() bool {
    if len(c.inputBuffer) >= 2 {
        msgLength := 256*int(c.inputBuffer[0]) + int(c.inputBuffer[1])
        if msgLength > maxBLEMessageSize {   // > 1024 → 버퍼 전체 폐기 (동기화 복구)
            c.inputBuffer = []byte{}
            return false
        }
        if len(c.inputBuffer) >= 2+msgLength {
            buffer := c.inputBuffer[2 : 2+msgLength]
            log.Debug("RX: %02x", buffer)
            c.inputBuffer = c.inputBuffer[2+msgLength:]
            select {
            case c.inbox <- buffer:          // inbox cap 5
            default:
                return false                 // 가득 차면 이 메시지는 드롭되고 루프 종료 (inputBuffer는 이미 소비됨)
            }
            return true
        }
    }
    return false
}
```

주의: `buffer`는 `inputBuffer`의 서브슬라이스다. 이후 `append`로 `inputBuffer`가 재할당되지 않는 한 같은 배킹 배열을 공유한다. dispatcher는 즉시 `proto.Unmarshal`하므로 실무상 문제는 없지만, 수신 데이터를 오래 보관하는 코드를 추가하면 복사해야 한다.

---

## 7. `Connection` 메서드 (connector.Connector 구현)

| 메서드 | 반환/동작 |
|---|---|
| `PreferredAuthMethod()` | `connector.AuthMethodGCM` |
| `RetryInterval()` | `time.Second` |
| `AllowedLatency()` | `maxLatency = 4 * time.Second` |
| `Receive()` | `c.inbox` |
| `Send(ctx, buf)` | 6.2절 |
| `VIN()` | 생성 시 받은 VIN |
| `Close()` | `client.ClearSubscriptions()`, `client.CancelConnection()` (오류 무시). 어댑터는 닫지 않는다. |

`Connection`은 `FleetAPIConnector`가 아니므로 `Wakeup`은 VCSEC RKE 경로, `SetPINToDrive`는 `ErrRequiresEncryption`, `SendAddKeyRequest`는 허용된다.

---

## 8. 어댑터 생명주기

```go
var (
    device ble.Device   // 프로세스 전역, 재사용
    mu     sync.Mutex
)
func InitAdapterWithID(id string) error   // mu 잠금 후 initAdapter(&id). 기본 어댑터면 호출 불필요. Linux 전용 id ("hciX")
func CloseAdapter() error                 // device.Stop() 후 device = nil. 기존 연결/스캔은 끊지 않음 (별도로 Close 필요)
func initAdapter(id *string) error        // device != nil 이면 재사용 ("Reusing existing BLE device"), 아니면 newAdapter(id)
```

- Linux에서 `newDevice()`를 여러 번 부르면 실패하므로 전역 재사용이 필수 (코드 주석).
- `ScanVehicleBeacon`과 `tryToConnect`는 모두 `initAdapter(nil)`을 호출하므로 `InitAdapterWithID`는 **비기본 어댑터를 쓸 때만, 그리고 다른 BLE 호출보다 먼저** 부른다. `pkg/cli.Config.ConnectLocal`은 `ble.InitAdapterWithID(c.BtAdapterID)`를 항상 먼저 호출한다 (빈 문자열이면 기본).
- `CloseAdapter` 후 다시 `initAdapter`하면 새 어댑터가 만들어진다.

---

## 9. 오류

| 오류 | 정의 | 의미 / `ShouldRetry` |
|---|---|---|
| `ErrAdapterInvalidID` | `protocol.NewError("the bluetooth adapter ID is invalid", false, false)` | Linux: `hci` 접두사 아님, 숫자 아님, 0–15 범위 밖. Darwin: ID를 지정하면 무조건 이 오류 (경고 로그 `Darwin does not support specifying a Bluetooth adapter ID`). 재시도 안 함 |
| `ErrMaxConnectionsExceeded` | `protocol.NewError("the vehicle is already connected to the maximum number of BLE devices", false, false)` | 광고가 `Connectable=false`. VCSEC 동시 연결(최대 3) 초과. 재시도 안 함 (다른 기기 연결 해제 필요) |
| `"ble: failed to scan for <vin>: <err>"` | `fmt.Errorf` | 스캔 실패/ctx 만료. `retry=true` |
| `"ble: beacon with unexpected local name: '<name>'"` | | 넘겨준 `ScanResult`가 다른 차량. `retry=false` |
| `"ble: failed to dial for <vin> (<localName>): <err>"` | | `retry=true` |
| `"ble: failed to enumerate device services"`, `"ble: failed to discover service"`, `"ble: failed to discover service characteristics"`, `"ble: couldn't fetch descriptors"`, `"ble: failed to find required characteristics"`, `"ble: failed to subscribe to RX"` | | 모두 `retry=true` |
| `"ble: failed to enable device: <err>"` | `initAdapter` | 어댑터 생성 실패. Linux에서 `operation not permitted` 포함 시 `IsAdapterError`=true |
| `"ble: failed to stop device: <err>"` | `CloseAdapter` | |

플랫폼 헬퍼:

| 함수 | darwin | linux | windows |
|---|---|---|---|
| `IsAdapterError(err)` | 항상 `false` (TODO) | `strings.Contains(err.Error(), "operation not permitted")` | 항상 `false` |
| `AdapterErrorHelpMessage(err)` | `err.Error()` | `"Failed to initialize BLE adapter: \n\t<err>\nTry again after granting this application CAP_NET_ADMIN or running with root:\n\n\tsudo setcap 'cap_net_admin=eip' \"$(which <argv0>)\""` | `err.Error()` |
| `newAdapter(id)` | `darwin.NewDevice()`; id 지정 시 `ErrAdapterInvalidID` | `linux.NewDeviceWithName("vehicle-command", OptDialerTimeout(20s), OptListenerTimeout(20s), OptScanParams(scanParams)[, OptDeviceID(n)])` | `errors.New("not supported on Windows")` |

`tesla-control`과 `examples/ble`는 연결 오류 시 `ble.IsAdapterError(err)`면 `AdapterErrorHelpMessage`를 출력한다.

---

## 10. 플랫폼 요약

| 항목 | macOS (`device_darwin.go`) | Linux (`device_linux.go`) | Windows (`device_windows.go`) |
|---|---|---|---|
| 백엔드 | go-ble `darwin` (CoreBluetooth, cgo via tinygo-org/cbgo v0.0.4) | go-ble `linux` (raw HCI 소켓) | 없음 |
| 어댑터 선택 | 불가 | `hci0`–`hci15` (`-bt-adapter`, `Config.BtAdapterID`) | — |
| 권한 | 앱에 Bluetooth 권한 (터미널 앱 최초 실행 시 시스템 프롬프트) | `CAP_NET_ADMIN` 또는 root. go-ble가 HCIDEVDOWN을 호출하므로 bluetoothd와 충돌 가능 | — |
| `device.Scan` 반환 | ctx 취소 전까지 블록, 항상 오류 | 정상 | — |
| 타임아웃 | go-ble 기본 | dial/listen 20s (`bleTimeout`) | — |
| `tesla-control` | 지원 | 지원 | 빌드는 되나 BLE 불가 (README: "does not run on Windows") |

---

## 11. VCSEC 측 제약 (프로토콜 문서)

- VCSEC는 **최대 3개**의 동시 BLE 연결만 안정적으로 유지한다. 키포브와 폰키가 이 슬롯을 공유한다. 초과 시 광고가 non-connectable → `ErrMaxConnectionsExceeded`.
- VCSEC는 메모리 제약으로 응답에 `request_uuid`를 채우지 않는다 → dispatcher는 요청마다 랜덤 `routing_address`로 구분한다 ([01-architecture.md](01-architecture.md#41-receiverkey-매칭-규칙)).
- VCSEC에 **동시 요청을 보내지 말 것**. 카운터 순서가 어긋나면 거부된다.
- VCSEC는 Infotainment가 잠들어 있어도 BLE로 응답한다. `body-controller-state`, `list-keys`, `wake`, `lock/unlock`, 트렁크 등은 Infotainment 세션 없이 가능 (`-domain vcsec`).
- 요청 하나에 최대 3개 응답이 올 수 있다 (`OPERATIONSTATUS_WAIT` 등). 종료 판정은 [03-protocol.md](03-protocol.md#11-vcsec-응답-종료-규칙).

---

## 12. 시퀀스 다이어그램

```
Client (ble.Connection)                    Vehicle (VCSEC BLE peripheral)
  |                                            |
  |-- LE Scan (active, LocalName == S..C) ---->|   광고 20ms/150ms 주기
  |<-- ADV_IND LocalName=S1a87...C, connectable|
  |-- Dial(addr) ----------------------------->|
  |<-- connected -------------------------------|
  |-- DiscoverServices [0211] ---------------->|
  |<-- service handle --------------------------|
  |-- DiscoverCharacteristics [0212,0213] ----->|
  |<-- tx=0212 (write), rx=0213 (indicate) -----|
  |-- DiscoverDescriptors (CCCD) -------------->|
  |-- Subscribe rx (indication) -------------->|
  |-- ExchangeMTU(MaxMTU) --------------------->|
  |<-- MTU=n ----------------------------------|   blockLength = min(n,1024)-3
  |                                            |
  |== RoutableMessage 송신 ======================|
  |-- Write tx: [len_hi len_lo pb[0:bl-2]] ---->|   write with response
  |<-- ATT Write Response ----------------------|
  |-- Write tx: [pb[bl-2:2bl-2]] -------------->|   (blockLength 단위 반복)
  |<-- ATT Write Response ----------------------|
  |                                            |
  |== RoutableMessage 수신 ======================|
  |<-- Indication rx: [len_hi len_lo pb...] ----|   rx(): inputBuffer append, flush()
  |-- Confirmation ---------------------------->|
  |<-- Indication rx: [pb 나머지] ---------------|   1초 안에 와야 함 (rxTimeout)
  |-- Confirmation ---------------------------->|   완성되면 inbox <- payload
  |                                            |
  |-- ClearSubscriptions, CancelConnection ---->|   Close()
```

---

## 13. 다른 BLE 스택으로 교체할 때 체크리스트

`go-ble/ble`를 다른 라이브러리(예: tinygo bluetooth, bluez D-Bus, 플랫폼 네이티브)로 바꾸거나 다른 언어로 포팅할 때 아래를 모두 만족해야 `Dispatcher`가 그대로 동작한다.

1. **Local Name 완전 일치 필터**로 스캔한다. 대소문자 구분(hex는 소문자).
2. `Connectable` 플래그를 확인해 non-connectable이면 `ErrMaxConnectionsExceeded`에 해당하는 오류를 낸다.
3. Service `0211` 아래 `0212`(write), `0213`(indicate)를 찾는다. `0213`은 **indication**(notification이 아님)으로 구독한다.
4. MTU를 협상하고 `blockLength = min(MTU, 1024) - 3`. 협상 실패 시 20.
5. 송신: `[len>>8, len&0xff] || payload`를 `blockLength`로 잘라 **write with response**로 순차 전송. 한 연결에서 동시 송신은 뮤텍스로 직렬화.
6. 수신: 청크를 이어 붙이고 길이 헤더로 메시지를 잘라낸다. 청크 간 1초 초과 시 버퍼 리셋, 길이 > 1024면 버퍼 폐기. 완성된 payload를 상위 계층 큐(cap 5)에 넣고 가득 차면 드롭.
7. `Receive()`/`Send()`는 스레드 안전, `Close()`는 멱등.
8. `PreferredAuthMethod() = GCM`, `RetryInterval() = 1s`, `AllowedLatency() = 4s`, `VIN()` 반환.
9. 어댑터 객체는 프로세스에서 하나만 만들어 재사용한다 (특히 Linux).
10. 스캔/연결 ctx 타임아웃을 상위에서 강제한다. 연결 재시도는 `(retry, IsAdapterError)` 규칙을 따른다.
11. 디버그 로그 `TX: <hex>` / `RX: <hex>`를 유지하면 `protoc --decode`로 바로 분석할 수 있다 ([03-protocol.md](03-protocol.md#15-protoc로-디코딩)).
12. 기존 테스트에는 BLE 통합 테스트가 없다. `pkg/vehicle/vehicle_mock_test.go`의 mock connector 패턴으로 프레이밍 단위 테스트를 추가하는 것을 권장.

---

## 14. 관련 CLI와 예제

| 명령 | 동작 |
|---|---|
| `go run ./examples/ble -vin <VIN> -scan-only` | `ScanVehicleBeacon`만 실행, 찾으면 `Found vehicle` 후 종료(exit 0), Ctrl-C면 130 |
| `go run ./examples/ble -vin <VIN> -key private.pem [-debug] [-bt-adapter hci0]` | 스캔 → `NewConnectionFromScanResult` → `NewVehicle(conn, key, nil)` → `Connect` → `StartSession(ctx, nil)` → `Unlock` → `ClimateOn`. 전체 30s 타임아웃 |
| `tesla-control -ble [-debug] [-bt-adapter hciX] [-connect-timeout 20s] [-command-timeout 5s] [-domain vcsec] <cmd>` | `cli.Config.ConnectLocal` 경로. `-ble`는 OAuth 환경 변수가 있어도 BLE 강제 |
| `tesla-control -ble -debug list-keys` | stderr에 `2023-12-13T14:41:13-08:00 [debug] TX: 3202...` / `RX: ...` hex 덤프 |
| `-debug` / `TESLA_VERBOSE=1` | `log.SetLevel(log.LevelDebug)` — BLE 단계별 로그(`Dialing to`, `Discovering services`, `MTU size: n`, `Reusing existing BLE device`) 출력 |

`examples/ble/main.go`는 `ble.ScanVehicleBeacon`으로 얻은 `*ScanResult`를 `NewConnectionFromScanResult`에 넘겨 스캔을 한 번만 한다. `pkg/cli`는 `ble.NewConnection`(내부 스캔)을 쓴다.

---

## 검증 체크리스트

`pkg/connector/ble/*`를 수정한 뒤:

- [ ] UUID 3개, Local Name 공식, 길이 헤더(big-endian 2바이트), `maxBLEMessageSize=1024`, `blockLength` 계산식이 `protocol.md` "BLE" 절과 일치하는가?
- [ ] `WriteCharacteristic(..., false)`(with response)와 `Subscribe(..., true, ...)`(indication)를 유지했는가?
- [ ] `Send`가 `c.lock`으로 직렬화되고, `rx`/`flush`가 `rxTimeout`, 길이 초과 폐기, inbox 드롭 규칙을 유지하는가?
- [ ] `tryToConnect`의 `(conn, retry, err)` 표(4절)와 `NewConnectionFromScanResult`의 `IsAdapterError` 단락 조건이 유지되는가?
- [ ] 전역 `device`/`mu` 재사용을 깨지 않았는가 (Linux에서 `newDevice` 중복 호출 금지)?
- [ ] macOS `device.Scan`의 `context.Canceled` 정상 경로 처리를 유지했는가?
- [ ] `device_darwin.go`/`device_linux.go`/`device_windows.go` 세 파일의 함수 시그니처(`IsAdapterError`, `AdapterErrorHelpMessage`, `newAdapter`)가 동일한가 (빌드 태그별 컴파일)?
- [ ] `examples/ble`, `pkg/cli.ConnectLocal`, `cmd/tesla-control`이 여전히 컴파일되는가?
- [ ] 실제 차량으로 `tesla-control -ble -debug body-controller-state`(Infotainment 미기상)와 `state charge`(Infotainment 기상)를 확인했는가? 못 했으면 보고서에 명시.
- [ ] 이 문서와 `../html/03-ble-transport.html`, `00-agent-guide.md` 4.1절 카드를 갱신했는가?
