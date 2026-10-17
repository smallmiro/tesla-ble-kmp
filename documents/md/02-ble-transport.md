# 02. BLE Transport Layer

Precise specification of the BLE transport layer implemented by `pkg/connector/ble`: GATT identifiers, advertised name, scan/connect procedure, MTU and framing, receive reassembly, adapter lifecycle, per-platform differences, and a checklist for swapping in a different BLE stack.

Related files: `pkg/connector/ble/ble.go`, `pkg/connector/ble/device_darwin.go`, `pkg/connector/ble/device_linux.go`, `pkg/connector/ble/device_windows.go`, `pkg/connector/ble/doc.go`, `pkg/connector/connector.go`, `examples/ble/main.go`, `pkg/cli/config.go` (`ConnectLocal`), `pkg/protocol/protocol.md` ("BLE" section)

---

## 1. GATT Identifiers

| Item | UUID | Usage |
|---|---|---|
| Service | `00000211-b2d1-43f0-9b88-960cebf8b91e` (`vehicleServiceUUID`) | `client.DiscoverServices([]ble.UUID{vehicleServiceUUID})` |
| To-vehicle characteristic (TX) | `00000212-b2d1-43f0-9b88-960cebf8b91e` (`toVehicleUUID`) | `client.WriteCharacteristic(txChar, chunk, false)` — `noRsp=false`, i.e. **write with response** |
| From-vehicle characteristic (RX) | `00000213-b2d1-43f0-9b88-960cebf8b91e` (`fromVehicleUUID`) | `client.Subscribe(rxChar, true, conn.rx)` — `ind=true`, i.e. **indication** subscription |

After connecting, `client.DiscoverDescriptors(nil, characteristic)` is called on both characteristics (to obtain the CCCD; failure is a retryable error).

---

## 2. Advertised Local Name

Formula (`protocol.md`, `ble.go` `VehicleLocalName`):

```
LocalName = "S" + lowercase_hex(SHA1(VIN)[0:8]) + "C"      // 18 characters total
```

```go
func VehicleLocalName(vin string) string {
    vinBytes := []byte(vin)
    digest := sha1.Sum(vinBytes)
    return fmt.Sprintf("S%02xC", digest[:8])
}
```

Example: VIN `5YJS0000000000000` → `S1a87a5a75f3df858C`.

Verify in a shell:

```bash
printf '%s' '5YJS0000000000000' | shasum -a 1 | cut -c1-16     # → 1a87a5a75f3df858
# Linux: printf '%s' "$VIN" | sha1sum | cut -c1-16
```

Use `printf '%s'` so that no newline gets into the hash. The scan filter uses only an **exact Local Name match**; no Service UUID advertisement filter is used.

---

## 3. Scanning

### 3.1 Public API

```go
type ScanResult struct {
    Address     string   // a.Addr().String()
    LocalName   string
    RSSI        int16
    Connectable bool
}

func ScanVehicleBeacon(ctx context.Context, vin string) (*ScanResult, error)   // locks mu, initAdapter(nil), returns first advertisement matching localName
```

### 3.2 Internal Behavior (`scanVehicleBeacon`)

1. `ctx2, cancel := context.WithCancel(ctx)`; `ch := make(chan ble.Advertisement, 1)`.
2. Callback `fn(a)`: ignore if `a.LocalName() != localName`. On match, `ch <- a` then `cancel()`. If another goroutine already sent one (`ctx2.Done()`), just return.
3. `device.Scan(ctx2, false /*allowDup*/, fn)`.
   - **macOS quirk**: `device.Scan` does not return until ctx is cancelled, so it **always** returns an error. The code treats it as an error only when `!errors.Is(err, context.Canceled)`.
4. `select { case a := <-ch: return advertisementToScanResult(a); case <-ctx.Done(): return ctx.Err() }`. Cancellation of the parent `ctx` is caught here.

### 3.3 Linux Scan Parameters (`device_linux.go`)

```go
var scanParams = cmd.LESetScanParameters{
    LEScanType:           1,    // Active scanning
    LEScanInterval:       0x10, // 10ms
    LEScanWindow:         0x10, // 10ms
    OwnAddressType:       0,    // Static
    ScanningFilterPolicy: 2,    // Basic filtered
}
```

Comment: depending on vehicle model/state, the advertising interval is 20ms or 150ms.

---

## 4. Connection Procedure (`tryToConnect`)

`NewConnection(ctx, vin)` = `NewConnectionFromScanResult(ctx, vin, nil)`. Step by step:

| # | Step | On failure `(conn, retry, err)` |
|---|---|---|
| 1 | `mu.Lock()`; `initAdapter(nil)` | `(nil, false, err)` — adapter errors are not retried |
| 2 | If `target == nil`, `scanVehicleBeacon(ctx, localName)` | `(nil, true, "ble: failed to scan for <vin>: ...")` |
| 3 | Check `target.LocalName != localName` | `(nil, false, "ble: beacon with unexpected local name: '...'")` |
| 4 | Check `!target.Connectable` | `(nil, false, ErrMaxConnectionsExceeded)` |
| 5 | `device.Dial(ctx, ble.NewAddr(target.Address))` | `(nil, true, "ble: failed to dial for ...")` |
| 6 | `client.DiscoverServices([vehicleServiceUUID])`; fails if 0 found | `(nil, true, ...)` |
| 7 | `client.DiscoverCharacteristics([toVehicleUUID, fromVehicleUUID], services[0])` | `(nil, true, ...)` |
| 8 | `DiscoverDescriptors` on each characteristic; assign txChar/rxChar | `(nil, true, "ble: couldn't fetch descriptors")` / `"ble: failed to find required characteristics"` |
| 9 | `client.Subscribe(rxChar, true, conn.rx)` | `(nil, true, "ble: failed to subscribe to RX")` |
| 10 | `client.ExchangeMTU(ble.MaxMTU)` → compute `blockLength` (section 5) | Continues on failure (warning log, default MTU used) |
| 11 | `log.Info("Connected to vehicle BLE")`; `(conn, false, nil)` | |

`Connection` initial value: `inbox: make(chan []byte, 5)`.

### 4.1 Retry Loop (`NewConnectionFromScanResult`)

```go
for {
    conn, retry, err := tryToConnect(ctx, vin, target)
    if err == nil { return conn, nil }
    if !retry || IsAdapterError(err) { return nil, err }
    log.Warning("BLE connection attempt failed: %s", err)
    if err := ctx.Err(); err != nil {
        if lastError != nil { return nil, lastError }   // when ctx expires, prefer returning the last real error
        return nil, err
    }
    lastError = err
}
```

There is no explicit sleep between retries. The scan step acts as a natural wait. If `target` is passed and dial keeps failing, this can become a busy loop, so always put a timeout on ctx (the example uses 30s; `tesla-control` `-connect-timeout` defaults to 20s).

---

## 5. MTU and Block Length

```go
const (
    maxBLEMTUSize     = ble.MaxMTU   // max MTU the client accepts (go-ble constant)
    maxBLEMessageSize = 1024
)
txMtu, err := client.ExchangeMTU(maxBLEMTUSize)
if err != nil {
    conn.blockLength = ble.DefaultMTU - 3          // fallback on failure (DefaultMTU=23 → 20 bytes)
} else {
    conn.blockLength = min(txMtu, maxBLEMessageSize) - 3   // 3 bytes = ATT Write header (opcode 1 + handle 2)
}
```

`blockLength` is the maximum number of bytes passed to a single `WriteCharacteristic` call. The entire buffer, including the framing header (2 bytes), is split into chunks of this size.

---

## 6. Framing

### 6.1 Byte Layout

```
offset 0 : uint8  len >> 8        (big-endian high byte)
offset 1 : uint8  len & 0xFF
offset 2 : payload[0 .. len-1]     serialized RoutableMessage protobuf bytes
```

`len` is the payload length only (excluding the 2-byte header). The maximum valid length, as enforced by the receiver, is `maxBLEMessageSize = 1024`.

### 6.2 Send Chunking (`Connection.Send`)

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

- ctx is ignored (`_ context.Context`). Write timeout relies on go-ble's dialer/listener timeout (20s on Linux).
- Even when one logical message is split across multiple ATT writes, the vehicle reassembles it using the length header. Sends are serialized with `c.lock` so chunks of different messages do not interleave.
- The returned error does not implement `protocol.Error`, so `ShouldRetry` in `Dispatcher.Send` is false → it propagates as an immediate failure.

### 6.3 Receive Reassembly (`rx` + `flush`)

```go
func (c *Connection) rx(p []byte) {
    if time.Since(c.lastRx) > rxTimeout {   // rxTimeout = 1s: if the gap between chunks exceeds 1s, discard previous fragments
        c.inputBuffer = []byte{}
    }
    c.lastRx = time.Now()
    c.inputBuffer = append(c.inputBuffer, p...)
    for c.flush() {}                        // if the buffer holds several complete messages, extract all of them
}

func (c *Connection) flush() bool {
    if len(c.inputBuffer) >= 2 {
        msgLength := 256*int(c.inputBuffer[0]) + int(c.inputBuffer[1])
        if msgLength > maxBLEMessageSize {   // > 1024 → discard entire buffer (resync)
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
                return false                 // when full, this message is dropped and the loop ends (inputBuffer already consumed)
            }
            return true
        }
    }
    return false
}
```

Note: `buffer` is a subslice of `inputBuffer`. It shares the same backing array unless a later `append` reallocates `inputBuffer`. The dispatcher calls `proto.Unmarshal` immediately, so this is not a problem in practice, but any code added that retains received data for a long time must copy it.

---

## 7. `Connection` Methods (connector.Connector implementation)

| Method | Return / behavior |
|---|---|
| `PreferredAuthMethod()` | `connector.AuthMethodGCM` |
| `RetryInterval()` | `time.Second` |
| `AllowedLatency()` | `maxLatency = 4 * time.Second` |
| `Receive()` | `c.inbox` |
| `Send(ctx, buf)` | Section 6.2 |
| `VIN()` | VIN received at construction |
| `Close()` | `client.ClearSubscriptions()`, `client.CancelConnection()` (errors ignored). Does not close the adapter. |

`Connection` is not a `FleetAPIConnector`, so `Wakeup` takes the VCSEC RKE path, `SetPINToDrive` returns `ErrRequiresEncryption`, and `SendAddKeyRequest` is allowed.

---

## 8. Adapter Lifecycle

```go
var (
    device ble.Device   // process-global, reused
    mu     sync.Mutex
)
func InitAdapterWithID(id string) error   // locks mu then initAdapter(&id). Not needed for the default adapter. Linux-only id ("hciX")
func CloseAdapter() error                 // device.Stop() then device = nil. Does not tear down existing connections/scans (Close them separately)
func initAdapter(id *string) error        // reuse if device != nil ("Reusing existing BLE device"), otherwise newAdapter(id)
```

- On Linux, calling `newDevice()` more than once fails, so global reuse is mandatory (code comment).
- Both `ScanVehicleBeacon` and `tryToConnect` call `initAdapter(nil)`, so call `InitAdapterWithID` **only when using a non-default adapter, and before any other BLE call**. `pkg/cli.Config.ConnectLocal` always calls `ble.InitAdapterWithID(c.BtAdapterID)` first (empty string means default).
- Calling `initAdapter` again after `CloseAdapter` creates a new adapter.

---

## 9. Errors

| Error | Definition | Meaning / `ShouldRetry` |
|---|---|---|
| `ErrAdapterInvalidID` | `protocol.NewError("the bluetooth adapter ID is invalid", false, false)` | Linux: no `hci` prefix, not a number, or outside 0–15. Darwin: always this error if any ID is specified (warning log `Darwin does not support specifying a Bluetooth adapter ID`). Not retried |
| `ErrMaxConnectionsExceeded` | `protocol.NewError("the vehicle is already connected to the maximum number of BLE devices", false, false)` | Advertisement has `Connectable=false`. VCSEC concurrent connection limit (max 3) exceeded. Not retried (another device must disconnect) |
| `"ble: failed to scan for <vin>: <err>"` | `fmt.Errorf` | Scan failure / ctx expired. `retry=true` |
| `"ble: beacon with unexpected local name: '<name>'"` | | The supplied `ScanResult` belongs to a different vehicle. `retry=false` |
| `"ble: failed to dial for <vin> (<localName>): <err>"` | | `retry=true` |
| `"ble: failed to enumerate device services"`, `"ble: failed to discover service"`, `"ble: failed to discover service characteristics"`, `"ble: couldn't fetch descriptors"`, `"ble: failed to find required characteristics"`, `"ble: failed to subscribe to RX"` | | All `retry=true` |
| `"ble: failed to enable device: <err>"` | `initAdapter` | Adapter creation failed. On Linux, `IsAdapterError`=true if it contains `operation not permitted` |
| `"ble: failed to stop device: <err>"` | `CloseAdapter` | |

Platform helpers:

| Function | darwin | linux | windows |
|---|---|---|---|
| `IsAdapterError(err)` | Always `false` (TODO) | `strings.Contains(err.Error(), "operation not permitted")` | Always `false` |
| `AdapterErrorHelpMessage(err)` | `err.Error()` | `"Failed to initialize BLE adapter: \n\t<err>\nTry again after granting this application CAP_NET_ADMIN or running with root:\n\n\tsudo setcap 'cap_net_admin=eip' \"$(which <argv0>)\""` | `err.Error()` |
| `newAdapter(id)` | `darwin.NewDevice()`; `ErrAdapterInvalidID` if id is specified | `linux.NewDeviceWithName("vehicle-command", OptDialerTimeout(20s), OptListenerTimeout(20s), OptScanParams(scanParams)[, OptDeviceID(n)])` | `errors.New("not supported on Windows")` |

On connection errors, `tesla-control` and `examples/ble` print `AdapterErrorHelpMessage` if `ble.IsAdapterError(err)`.

---

## 10. Platform Summary

| Item | macOS (`device_darwin.go`) | Linux (`device_linux.go`) | Windows (`device_windows.go`) |
|---|---|---|---|
| Backend | go-ble `darwin` (CoreBluetooth, cgo via tinygo-org/cbgo v0.0.4) | go-ble `linux` (raw HCI socket) | None |
| Adapter selection | Not possible | `hci0`–`hci15` (`-bt-adapter`, `Config.BtAdapterID`) | — |
| Permissions | Bluetooth permission for the app (system prompt on first run of the terminal app) | `CAP_NET_ADMIN` or root. go-ble calls HCIDEVDOWN, so it may conflict with bluetoothd | — |
| `device.Scan` return | Blocks until ctx is cancelled, always returns an error | Normal | — |
| Timeouts | go-ble defaults | dial/listen 20s (`bleTimeout`) | — |
| `tesla-control` | Supported | Supported | Builds, but no BLE (README: "does not run on Windows") |

---

## 11. VCSEC-side Constraints (protocol document)

- VCSEC reliably maintains **at most 3** concurrent BLE connections. Key fobs and phone keys share these slots. When exceeded, the advertisement becomes non-connectable → `ErrMaxConnectionsExceeded`.
- Due to memory constraints, VCSEC does not populate `request_uuid` in responses → the dispatcher distinguishes requests by a random per-request `routing_address` ([01-architecture.md](01-architecture.md#41-receiverkey-matching-rules)).
- **Do not send concurrent requests** to VCSEC. If counter ordering gets out of sequence, they are rejected.
- VCSEC responds over BLE even when Infotainment is asleep. `body-controller-state`, `list-keys`, `wake`, `lock/unlock`, trunk, etc. work without an Infotainment session (`-domain vcsec`).
- A single request may receive up to 3 responses (`OPERATIONSTATUS_WAIT`, etc.). For termination rules see [03-protocol.md](03-protocol.md#11-vcsec-response-termination-rules).

---

## 12. Sequence Diagram

```
Client (ble.Connection)                    Vehicle (VCSEC BLE peripheral)
  |                                            |
  |-- LE Scan (active, LocalName == S..C) ---->|   advertising every 20ms/150ms
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
  |== Send RoutableMessage ======================|
  |-- Write tx: [len_hi len_lo pb[0:bl-2]] ---->|   write with response
  |<-- ATT Write Response ----------------------|
  |-- Write tx: [pb[bl-2:2bl-2]] -------------->|   (repeated per blockLength)
  |<-- ATT Write Response ----------------------|
  |                                            |
  |== Receive RoutableMessage ===================|
  |<-- Indication rx: [len_hi len_lo pb...] ----|   rx(): inputBuffer append, flush()
  |-- Confirmation ---------------------------->|
  |<-- Indication rx: [pb remainder] -----------|   must arrive within 1s (rxTimeout)
  |-- Confirmation ---------------------------->|   when complete, inbox <- payload
  |                                            |
  |-- ClearSubscriptions, CancelConnection ---->|   Close()
```

---

## 13. Checklist for Replacing the BLE Stack

When replacing `go-ble/ble` with another library (e.g. tinygo bluetooth, bluez D-Bus, platform-native) or porting to another language, all of the following must hold for `Dispatcher` to work unchanged.

1. Scan with an **exact Local Name match filter**. Case-sensitive (hex is lowercase).
2. Check the `Connectable` flag; if non-connectable, raise an error equivalent to `ErrMaxConnectionsExceeded`.
3. Find `0212` (write) and `0213` (indicate) under Service `0211`. Subscribe to `0213` via **indication** (not notification).
4. Negotiate the MTU and set `blockLength = min(MTU, 1024) - 3`. Use 20 if negotiation fails.
5. Send: split `[len>>8, len&0xff] || payload` into `blockLength` chunks and send them sequentially with **write with response**. Serialize concurrent sends on one connection with a mutex.
6. Receive: concatenate chunks and cut messages using the length header. Reset the buffer if the gap between chunks exceeds 1s; discard the buffer if length > 1024. Push completed payloads into the upper-layer queue (cap 5); drop when full.
7. `Receive()`/`Send()` are thread-safe; `Close()` is idempotent.
8. `PreferredAuthMethod() = GCM`, `RetryInterval() = 1s`, `AllowedLatency() = 4s`, `VIN()` returns the VIN.
9. Create only one adapter object per process and reuse it (especially on Linux).
10. Enforce scan/connect ctx timeouts from the caller. Connection retries follow the `(retry, IsAdapterError)` rule.
11. Keeping the debug logs `TX: <hex>` / `RX: <hex>` allows direct analysis with `protoc --decode` ([03-protocol.md](03-protocol.md#15-decoding-with-protoc)).
12. The existing tests have no BLE integration tests. Adding framing unit tests using the mock connector pattern in `pkg/vehicle/vehicle_mock_test.go` is recommended.

---

## 14. Related CLI and Examples

| Command | Behavior |
|---|---|
| `go run ./examples/ble -vin <VIN> -scan-only` | Runs only `ScanVehicleBeacon`; on success prints `Found vehicle` and exits (exit 0), 130 on Ctrl-C |
| `go run ./examples/ble -vin <VIN> -key private.pem [-debug] [-bt-adapter hci0]` | Scan → `NewConnectionFromScanResult` → `NewVehicle(conn, key, nil)` → `Connect` → `StartSession(ctx, nil)` → `Unlock` → `ClimateOn`. 30s overall timeout |
| `tesla-control -ble [-debug] [-bt-adapter hciX] [-connect-timeout 20s] [-command-timeout 5s] [-domain vcsec] <cmd>` | `cli.Config.ConnectLocal` path. `-ble` forces BLE even if OAuth environment variables are set |
| `tesla-control -ble -debug list-keys` | Hex dumps on stderr: `2023-12-13T14:41:13-08:00 [debug] TX: 3202...` / `RX: ...` |
| `-debug` / `TESLA_VERBOSE=1` | `log.SetLevel(log.LevelDebug)` — prints per-step BLE logs (`Dialing to`, `Discovering services`, `MTU size: n`, `Reusing existing BLE device`) |

`examples/ble/main.go` passes the `*ScanResult` obtained from `ble.ScanVehicleBeacon` to `NewConnectionFromScanResult`, so it scans only once. `pkg/cli` uses `ble.NewConnection` (internal scan).

---

## Verification Checklist

After modifying `pkg/connector/ble/*`:

- [ ] Do the 3 UUIDs, the Local Name formula, the length header (2-byte big-endian), `maxBLEMessageSize=1024`, and the `blockLength` formula match the "BLE" section of `protocol.md`?
- [ ] Are `WriteCharacteristic(..., false)` (with response) and `Subscribe(..., true, ...)` (indication) preserved?
- [ ] Is `Send` serialized with `c.lock`, and do `rx`/`flush` preserve the `rxTimeout`, oversized-length discard, and inbox drop rules?
- [ ] Are the `(conn, retry, err)` table of `tryToConnect` (section 4) and the `IsAdapterError` short-circuit condition of `NewConnectionFromScanResult` preserved?
- [ ] Is global `device`/`mu` reuse intact (no duplicate `newDevice` calls on Linux)?
- [ ] Is the `context.Canceled` normal-path handling for macOS `device.Scan` preserved?
- [ ] Are the function signatures (`IsAdapterError`, `AdapterErrorHelpMessage`, `newAdapter`) identical across `device_darwin.go`/`device_linux.go`/`device_windows.go` (compiled per build tag)?
- [ ] Do `examples/ble`, `pkg/cli.ConnectLocal`, and `cmd/tesla-control` still compile?
- [ ] Have you verified `tesla-control -ble -debug body-controller-state` (Infotainment asleep) and `state charge` (Infotainment awake) on a real vehicle? If not, state so in the report.
- [ ] Have you updated this document, `../html/{ko,en,ja}/03-ble-transport.html` (all three languages), and the card in section 4.1 of `00-agent-guide.md`?
