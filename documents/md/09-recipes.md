# 09. Code Recipes

15 practical Go code examples that use only the SDK's public API. Signatures were checked directly against the repository code. Each recipe is structured as Purpose / Prerequisites / Code / Run / Caveats. `go` is not installed on this machine, so compilation was not verified; confirm with `go build ./...` before use.

Related files (relative to the repository root)

- `examples/ble/main.go`, `examples/unlock/unlock.go` — official examples (basis for R1, R8)
- `pkg/cache/example_test.go` — session cache example (basis for R2)
- `pkg/cli/config.go` — CLI skeleton (R9)
- `pkg/connector/connector.go`, `pkg/connector/ble/ble.go`, `pkg/connector/inet/inet.go` — Connector implementation references (R10)
- `pkg/vehicle/*.go` — command method patterns (R11)
- `cmd/tesla-control/commands.go`, `pkg/proxy/command.go` — command registration (R11)
- `cmd/tesla-http-proxy/main.go`, `pkg/proxy/proxy.go` — proxy embedding (R13)
- `pkg/sign/sign.go`, `cmd/tesla-jws/main.go` — JWS signing (R14)
- `pkg/vehicle/vehicle_test.go`, `vcsec_test.go`, `internal/dispatcher/dispatcher_test.go`, `pkg/proxy/proxy_test.go`, `internal/authentication/protocol_doc_test.go` — test patterns (R15)

Common rules

- **defer order**: register `defer conn.Close()` first and `defer car.Disconnect()` afterwards (LIFO → `Disconnect` runs first). `Vehicle.Disconnect` also calls `conn.Close()` internally, and `Connector.Close` must be idempotent, so calling it twice is safe.
- **Recommended ctx timeouts**: connection (scan+GATT+handshake) 20s (`tesla-control -connect-timeout` default), command 5s (`-command-timeout` default). 30s if a wake-up is needed.
- **Types**: a `protocol.ECDHPrivateKey` can be passed as-is to the `authentication.ECDHPrivateKey` parameter of `vehicle.NewVehicle` (interfaces with the same method set).
- Import aliases: `universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"`, `carserver "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/carserver"`.

---

## R1. Minimal BLE lock/unlock program

Purpose: BLE connection with a private key → handshake → `Unlock` → print the classified error.
Prerequisites: public key enrolled on the vehicle (R4), private key PEM file.

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

	// On Linux, select an adapter with ble.InitAdapterWithID("hci0"); can be omitted for the default adapter.
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

	conn, err := ble.NewConnection(connectCtx, vin) // scan + Dial + service discovery + subscribe + MTU
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
	if err := car.Connect(connectCtx); err != nil { // starts the dispatcher goroutine
		fmt.Fprintf(os.Stderr, "dispatcher: %s\n", err)
		os.Exit(1)
	}
	defer car.Disconnect()

	// nil → handshake with both VCSEC + INFOTAINMENT. If Infotainment is asleep, this can take a long time.
	if err := car.StartSession(connectCtx, nil); err != nil {
		fmt.Fprintf(os.Stderr, "handshake: %s\n", err)
		os.Exit(1)
	}

	cmdCtx, cancelCmd := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancelCmd()

	if err := car.Unlock(cmdCtx); err != nil {
		switch {
		case protocol.MayHaveSucceeded(err):
			fmt.Fprintf(os.Stderr, "unlock sent but unconfirmed: %s\n", err) // do not resend
		case errors.Is(err, protocol.ErrKeyNotPaired):
			fmt.Fprintln(os.Stderr, "public key not paired; run add-key-request")
		case protocol.IsNominalError(err):
			fmt.Fprintf(os.Stderr, "vehicle refused: %s\n", err) // e.g. closures open
		default:
			fmt.Fprintf(os.Stderr, "unlock failed: %s\n", err)
		}
		os.Exit(1)
	}
	fmt.Println("unlocked")
}
```

Run: `go run . -vin $TESLA_VIN -key private.pem`
Caveat: `ble.NewConnection` retries scan failures/Dial failures indefinitely until ctx expires. `Connectable=false` (`ErrMaxConnectionsExceeded`) returns immediately.

---

## R2. Using the session cache

Purpose: skip the handshake on re-runs. Same pattern as `pkg/cache/example_test.go`.

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
			// Delete a corrupted cache and create a new one.
			_ = os.Remove(path)
		}
		return cache.New(5) // up to 5 vehicles (0 = unlimited)
	}
	return c
}

// ... after preparing conn, privateKey:
sessions := loadCache(cachePath)
car, err := vehicle.NewVehicle(conn, privateKey, sessions) // if the cache has an entry for the VIN, it is loaded immediately as a ready session
// ...
if err := car.StartSession(ctx, nil); err != nil { /* returns immediately on a cache hit */ }
defer func() {
	if err := car.UpdateCachedSessions(sessions); err == nil {
		// ExportToFile creates the file with 0644. The cache holds session counters/epochs, so narrow the permissions.
		if err := sessions.ExportToFile(cachePath); err == nil {
			_ = os.Chmod(cachePath, 0o600)
		}
	}
}()
```

Caveats

- The cache is bound to the private key. If used with a different key, the first command fails with `INVALID_SIGNATURE` and recovers automatically via the session_info sent by the vehicle (no extra cost).
- Sessions loaded in `NewVehicle` have `ready=true`, so `StartSession` does not send a handshake. `StartSession` succeeds even if Infotainment is asleep, and the first Infotainment command may fail with `ErrBusy`/timeout.
- `UpdateCachedSessions` must run **before** `Disconnect` on the defer stack (`tesla-control`: `defer config.UpdateCachedSessions(car)` after `defer car.Disconnect()`).
- If multiple processes write the same cache file concurrently, the counters collide (`INVALID_TOKEN_OR_COUNTER`).

---

## R3. VCSEC only (without waking Infotainment)

Purpose: query lock/door/sleep state and run lock commands without an Infotainment handshake.

```go
import (
	"github.com/teslamotors/vehicle-command/pkg/protocol"
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/vcsec"
	"google.golang.org/protobuf/encoding/protojson"
)

// Restrict the handshake target to VCSEC
if err := car.StartSession(ctx, []universal.Domain{protocol.DomainVCSEC}); err != nil {
	return err
}

// Unauthenticated information request: works without a private key/session (InformationRequest GET_STATUS)
status, err := car.BodyControllerState(ctx) // *vcsec.VehicleStatus
if err != nil {
	return err
}
fmt.Println(protojson.Format(status))
if status.GetVehicleSleepStatus() == vcsec.VehicleSleepStatus_E_VEHICLE_SLEEP_STATUS_ASLEEP {
	fmt.Println("infotainment asleep; VCSEC-only commands still work")
}
if status.GetVehicleLockState() != vcsec.VehicleLockState_E_VEHICLELOCKSTATE_LOCKED {
	if err := car.Lock(ctx); err != nil { // RKE_ACTION_LOCK, authorized with the VCSEC session
		return err
	}
}
```

Caveats

- Calling an Infotainment command (`ClimateOn`, etc.) with only a VCSEC session open returns `protocol.ErrNoSession`.
- Over BLE, `car.Wakeup(ctx)` sends `RKE_ACTION_WAKE_VEHICLE` to VCSEC (VCSEC session required). To use Infotainment after waking, additionally call `car.StartSession(ctx, []universal.Domain{protocol.DomainInfotainment})`.
- VCSEC domain commands: `Lock`, `Unlock`, `RemoteDrive`, `AutoSecureVehicle`, `Wakeup` (BLE), `OpenTrunk`/`CloseTrunk`/`ActuateTrunk`, `OpenFrunk`, `Open/Close/StopTonneau`, `AddKey*`, `RemoveKey`, `KeySummary`, `KeyInfoBySlot`, `BodyControllerState`.

---

## R4. BLE key pairing program

Purpose: request public key enrollment on the vehicle without a private key → NFC tap → confirm enrollment.
Prerequisites: the user is inside the vehicle and has an NFC card. `SendAddKeyRequestWithRole` sends `vcsec.ToVCSECMessage{SignedMessage{SIGNATURE_TYPE_PRESENT_KEY}}` **directly** via `conn.Send`, not as a `RoutableMessage`.

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
	car, err := vehicle.NewVehicle(conn, nil, nil) // no private key
	if err != nil {
		return err
	}
	if err := car.Connect(ctx); err != nil { // the dispatcher is needed to receive responses
		return err
	}
	defer car.Disconnect()

	err = car.SendAddKeyRequestWithRole(ctx, publicKey,
		keys.Role_ROLE_OWNER,                            // owner|driver|fm|vehicle_monitor|charging_manager
		vcsec.KeyFormFactor_KEY_FORM_FACTOR_CLOUD_KEY)   // nfc_card|ios_device|android_device|cloud_key
	if err != nil {
		return err // ErrRequiresBLE: when conn is inet; ErrInvalidPublicKey: not P-256
	}
	fmt.Println("Tap your NFC card on the center console, then confirm on the screen.")

	// Confirm enrollment: SessionInfo succeeds once Infotainment knows this key.
	// (Syncing to Infotainment can take tens of seconds after VCSEC enrollment)
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

Invocation: `publicKey, _ := protocol.LoadPublicKey("public_key.pem")` (accepts PEM public key, private key PEM, 65-byte binary, and 130-char hex).
Equivalent CLI command: `tesla-control -ble -vin $VIN add-key-request public_key.pem owner cloud_key` (no authentication needed, `-ble` required).
Caveat: this function does not read the response to the request itself (`OPERATIONSTATUS_WAIT`, etc.). Polling `SessionInfo` is the only means of confirmation. If Infotainment is asleep, `SessionInfo` may time out, so checking with the VCSEC domain first is also fine.

---

## R5. Listing enrolled keys

The `list-keys` logic in `cmd/tesla-control/commands.go`. No authentication needed.

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
				details.GetPublicKey().GetPublicKeyRaw(), // 65 bytes uncompressed
				details.GetKeyRole(),                      // keys.Role
				details.GetMetadataForKey().GetKeyFormFactor())
		}
	}
	slot++
}
```

Caveat: each slot costs one VCSEC round trip. VCSEC should not receive concurrent requests, so keep the calls sequential.

---

## R6. State query (Infotainment)

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

Caveat: intended for BLE. Over the internet, the Fleet API `vehicle_data` is more efficient. Large responses yield `RESPONSE_MTU_EXCEEDED` (MHS=true). `StateCategoryLocation` can return multiple (lat, lon) fields (see the `carserver.LocationState` comments).

---

## R7. Adding a charge schedule

`vehicle.ChargeSchedule = carserver.ChargeSchedule` (`pkg/protocol/protobuf/common.proto`).

```go
import "time"

// DaysOfWeek bitmask: Sun=1 Mon=2 Tue=4 Wed=8 Thu=16 Fri=32 Sat=64, all=127, weekdays=62
const weekdays = 2 | 4 | 8 | 16 | 32

schedule := &vehicle.ChargeSchedule{
	Id:           uint64(time.Now().Unix()), // new schedule: epoch seconds. Passing an existing ID modifies it
	Name:         "night",
	DaysOfWeek:   weekdays,
	StartEnabled: true,
	StartTime:    22 * 60, // minutes after midnight
	EndEnabled:   true,
	EndTime:      6 * 60,  // next-day 06:00 allowed
	OneTime:      false,
	Enabled:      true,
	Latitude:     37.5665,
	Longitude:    126.9780,
}
if err := car.AddChargeSchedule(ctx, schedule); err != nil {
	return err
}
fmt.Println(schedule.Id)

// Removal
_ = car.RemoveChargeSchedule(ctx, schedule.Id)
_ = car.BatchRemoveChargeSchedules(ctx, true /*home*/, false /*work*/, false /*other*/)

// Precondition schedules follow the same pattern: vehicle.PreconditionSchedule{Id, DaysOfWeek, PreconditionTime, OneTime, Enabled, Latitude, Longitude}
```

CLI equivalent: `tesla-control charging-schedule-add weekdays 22:00-6:00 37.5665 126.9780`.

---

## R8. Internet path (Fleet API)

`examples/unlock/unlock.go` + wake-up.

```go
import (
	"github.com/teslamotors/vehicle-command/pkg/account"
	"github.com/teslamotors/vehicle-command/pkg/connector/inet"
)

token, _ := os.ReadFile(tokenFile) // OAuth access token (JWT). The server domain is determined from the token's aud/ou_code
acct, err := account.New(string(token), "example-app/1.0.0") // userAgent "" → generated from build info
if err != nil {
	return err
}
car, err := acct.GetVehicle(ctx, vin, privateKey, sessions) // creates an inet.Connection (no network call yet)
if err != nil {
	return err
}
if err := car.Connect(ctx); err != nil {
	return err
}
defer car.Disconnect()

if err := car.StartSession(ctx, nil); err != nil {
	if errors.Is(err, inet.ErrVehicleNotAwake) { // 503 or 408 "vehicle is offline"
		if err := car.Wakeup(ctx); err != nil { // POST wake_up, polls every 10s until state=="online"
			return err
		}
		if err := car.StartSession(ctx, nil); err != nil {
			return err
		}
	} else if errors.Is(err, protocol.ErrProtocolNotSupported) { // 422: pre-2021 S/X
		_, err := acct.SendVehicleFleetAPICommand(ctx, vin, "command/door_unlock", map[string]any{})
		return err
	} else {
		return err
	}
}
return car.Unlock(ctx) // HMAC-SHA256 authentication, plaintext transmission (inet.PreferredAuthMethod == AuthMethodHMAC)
```

Caveats

- Give `Wakeup` a 30–60s ctx.
- On the internet path, `SendAddKeyRequestWithRole` returns `ErrRequiresBLE`. `SetPINToDrive`, conversely, is allowed only on the internet path.
- On a 421 response, `inet.Connection` updates the server domain automatically.

---

## R9. Custom CLI skeleton based on `pkg/cli`

Abridged `cmd/tesla-control/main.go`. Delegates flags/environment variables/keyring/cache/transport selection entirely.

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
	config.ReadFromEnvironment() // TESLA_VIN TESLA_KEY_NAME TESLA_KEY_FILE TESLA_TOKEN_NAME TESLA_TOKEN_FILE TESLA_CACHE_FILE TESLA_KEYRING_*; must be called after flag.Parse so flags take precedence

	if forceBLE {
		config.Flags = cli.FlagBLE | cli.FlagVIN | cli.FlagPrivateKey // OAuth excluded → Connect picks BLE
	}

	if err := config.LoadCredentials(); err != nil { // handle the keyring password prompt outside the timeout
		fmt.Fprintln(os.Stderr, err)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	acct, car, err := config.Connect(ctx) // inet if a token exists, otherwise BLE. Performs car.Connect + StartSession(config.Domains)
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
		defer config.UpdateCachedSessions(car) // runs before Disconnect
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

Caveat: `config.Connect` returns `must provide VIN and/or OAuth token` when `VIN` is empty and there is no token. Without a private key it returns an unauthenticated connection that skips `StartSession` (only `add-key-request`, `list-keys`, etc. are possible).

---

## R10. Custom Connector implementation skeleton

The 7 methods of `connector.Connector`. Uses `ble.Connection` as the template.

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
	inbox chan []byte // read by the dispatcher via Receive(). When closed, the dispatcher goroutine exits
	mu    sync.Mutex  // serializes Send
	once  sync.Once   // idempotent Close
	// ... actual transport handle
}

func New(vin string) *Connection {
	return &Connection{vin: vin, inbox: make(chan []byte, connector.BufferSize)} // BufferSize = 5
}

func (c *Connection) Receive() <-chan []byte { return c.inbox }

// buffer is the complete RoutableMessage bytes. Transport-layer framing (BLE: 2-byte length prefix) is added here.
// If the returned error implements protocol.Error, the dispatcher decides on resending via ShouldRetry.
func (c *Connection) Send(ctx context.Context, buffer []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	// ... transmit. Responses go through c.deliver(msg) from a separate goroutine
	return nil
}

// Called from the receive goroutine. Drops when full (no blocking: must not stall the dispatcher)
func (c *Connection) deliver(msg []byte) {
	select {
	case c.inbox <- msg:
	default:
	}
}

func (c *Connection) VIN() string { return c.vin }

func (c *Connection) Close() {
	c.once.Do(func() {
		// Clean up the transport handle. Closing inbox terminates dispatcher.listen (inet closes it, ble does not; both are allowed).
	})
}

// GCM (encrypted) for direct communication with the vehicle (BLE, local relay). HMAC if an intermediary must inspect the content (Fleet API).
func (c *Connection) PreferredAuthMethod() connector.AuthMethod { return connector.AuthMethodGCM }

// Resend/retry wait. 1s for both BLE and inet.
func (c *Connection) RetryInterval() time.Duration { return time.Second }

// Maximum allowed latency from request to session_info response. If exceeded, clock sync info is discarded. BLE 4s, inet 10s.
func (c *Connection) AllowedLatency() time.Duration { return 4 * time.Second }
```

Usage: `car, err := vehicle.NewVehicle(myconn.New(vin), privateKey, sessions)`.
To support Fleet API features (wake-up, REST commands), also implement `connector.FleetAPIConnector` (additional methods `SendFleetAPICommand(ctx, endpoint string, command interface{}) ([]byte, error)`, `Wakeup(ctx) error`). `Vehicle.Wakeup` and `SendAddKeyRequestWithRole`/`SetPINToDrive` branch their path on a type assertion to this interface.

Caveats

- `Receive` and `Send` must be thread-safe. The dispatcher can call `Send` concurrently from multiple goroutines (`StartSessions` runs per-domain handshakes in parallel).
- Must accept up to `MaxResponseLength` = 100000 bytes.
- Received messages must be one `[]byte` per RoutableMessage (BLE reassembles using 2-byte length framing).

---

## R11. Procedure for adding a new vehicle command

1. If the protobuf lacks the field, edit `pkg/protocol/protobuf/*.proto` and then run `make proto-gen` (requires `protoc` + `protoc-gen-go`).
2. Add a method to `pkg/vehicle/<category>.go`.
3. Add an entry to the `commands` map in `cmd/tesla-control/commands.go`.
4. Add a `case` to `ExtractCommandAction` in `pkg/proxy/command.go` (match the Fleet API endpoint name and parameter names).
5. Tests: `pkg/vehicle/*_test.go`, `pkg/proxy/command_test.go`.
6. `make format && make linters && make test` (or `./check-all.sh`).

### Template A: Infotainment (CarServer Action)

```go
// pkg/vehicle/climate.go, etc.
func (v *Vehicle) SetExampleFeature(ctx context.Context, on bool) error {
	return v.executeCarServerAction(ctx,
		&carserver.Action_VehicleAction{
			VehicleAction: &carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_ExampleFeatureAction{ // oneof wrapper (generated code)
					ExampleFeatureAction: &carserver.ExampleFeatureAction{On: on},
				},
			},
		})
}
```

`executeCarServerAction` → `getCarServerResponse`: marshal `carserver.Action{ActionMsg: action}` → `v.Send(ctx, universal.Domain_DOMAIN_INFOTAINMENT, payload, v.authMethod)` → unmarshal `carserver.Response` → `NominalError` if `actionStatus.result == ERROR`. If you need response data, call `getCarServerResponse` directly (see `GetState`).

### Template B: VCSEC (UnsignedMessage)

```go
// pkg/vehicle/vcsec.go
func (v *Vehicle) ExampleRKE(ctx context.Context) error {
	return v.executeRKEAction(ctx, vcsec.RKEAction_E_RKE_ACTION_UNLOCK) // reuse the existing helper
}

// For a new sub-message type, use getVCSECResult directly
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
	// Completion check: for RKE/Closure types, a message without commandStatus is the final response
	done := func(m *vcsec.FromVCSECMessage) (bool, error) { return m.GetCommandStatus() == nil, nil }
	_, err = v.getVCSECResult(ctx, encoded, v.authMethod, done)
	return err
}
```

For whitelist operations reuse `executeWhitelistOperation` (completion check `isWhitelistOperationComplete`); for information queries reuse `getVCSECInfo(ctx, requestType, slot)`.

### CLI entry

```go
"example-feature": {
	help:             "Set example feature to STATE ('on' or 'off')",
	requiresAuth:     true,  // private key required
	requiresFleetAPI: false, // whether an OAuth token is required
	// domain: protocol.DomainVCSEC, // restrict the handshake scope if VCSEC-only
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

### Proxy case

```go
case "example_feature":
	on, err := params.getBool("on", true) // required=true → NominalError "missing on param" if absent
	if err != nil {
		return nil, err
	}
	return func(v *vehicle.Vehicle) error { return v.SetExampleFeature(ctx, on) }, nil
```

`params.getNumber` accepts JSON numbers only as `float64` (integer strings are rejected).

---

## R12. Low-level `Send` / `SendMessage`

```go
import (
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
	"github.com/teslamotors/vehicle-command/pkg/connector"
	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/vehicle"
	"google.golang.org/protobuf/proto"
)

// (a) Given just the payload, handles RoutableMessage assembly, authorization, and retries. Returns the response payload bytes.
respBytes, err := car.Send(ctx, protocol.DomainInfotainment, actionBytes, connector.AuthMethodGCM)
// AuthMethodGCM over BLE, AuthMethodHMAC over inet (match car's conn.PreferredAuthMethod())
var resp carserver.Response
_ = proto.Unmarshal(respBytes, &resp)

// (b) Build and send a RoutableMessage directly and read multiple responses via the Receiver (no authorization = AuthMethodNone)
msg := &universal.RoutableMessage{
	ToDestination: &universal.Destination{
		SubDestination: &universal.Destination_Domain{Domain: protocol.DomainVCSEC}, // error if BROADCAST
	},
	Payload: &universal.RoutableMessage_ProtobufMessageAsBytes{ProtobufMessageAsBytes: unsignedBytes},
	Flags:   vehicle.DefaultFlags, // 1 << FLAG_ENCRYPT_RESPONSE
}
recv, err := car.SendMessage(ctx, msg) // Uuid, FromDestination(routing_address) are filled in by the dispatcher
if err != nil {
	return err
}
defer recv.Close() // must be called: removes it from the handler map
for {
	select {
	case reply := <-recv.Recv():
		if err := protocol.GetError(reply); err != nil {
			return err
		}
		// Parse reply.GetProtobufMessageAsBytes(). VCSEC can send up to 3 responses (termination rules in 08-errors §3.5)
	case <-ctx.Done():
		return ctx.Err()
	}
}
```

Caveat: `SendMessage` is for forwarding already-authorized messages (e.g. messages signed by another entity) as-is. Commands that need session authorization should use `Send`. Changing `Vehicle.Flags` changes the `flags` of subsequent `Send` calls.

---

## R13. Embedding the HTTP proxy + authentication middleware

`cmd/tesla-http-proxy/main.go` is a thin wrapper around `pkg/proxy`. To disable TLS or add client authentication, build it yourself.

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
	skey, err := protocol.LoadPrivateKey("config/fleet-key.pem") // command authentication key (must differ from the TLS key)
	if err != nil {
		panic(err)
	}
	p, err := proxy.New(context.Background(), skey, 10000) // cacheSize: max number of VINs in the session cache
	if err != nil {
		panic(err)
	}
	p.Timeout = 10 * time.Second // proxy.DefaultTimeout

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "expected" { // custom client authentication
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		p.ServeHTTP(w, r) // /health, /api/1/vehicles/{vin}/command/{cmd}, /api/1/vehicles/fleet_telemetry_config, other REST is forwarded
	})
	// Using TLS, as the official binary does, is recommended. Non-TLS for experts: http.ListenAndServe(addr, handler)
	panic(http.ListenAndServeTLS("localhost:4443", "config/tls-cert.pem", "config/tls-key.pem", handler))
}
```

Caveat: clients must send `Authorization: Bearer <OAuth token>` on every request (the proxy does not store tokens). The proxy serializes commands with a per-VIN mutex and limits request bodies to 1 MiB.

---

## R14. Signing the fleet telemetry config (JWS)

CLI:

```bash
# Fleet-wide target (aud = "com.tesla.fleet.TelemetryClient")
tesla-jws -fleet sign TelemetryClient telemetry_config.json > signed-config.jws
# Specific vehicle target (aud = "com.tesla.vehicle.<VIN>.TelemetryClient")
tesla-jws -vin $VIN sign TelemetryClient telemetry_config.json
# Verify the signature (does not check whether the issuer is trusted)
tesla-jws verify signed-config.jws
```

Library:

```go
import (
	"github.com/golang-jwt/jwt/v5"
	"github.com/teslamotors/vehicle-command/pkg/sign"
)

claims := jwt.MapClaims{"fields": map[string]any{"VehicleSpeed": map[string]any{"interval_seconds": 10}}, "exp": 1900000000}
token, err := sign.SignMessageForFleet(skey, "TelemetryClient", claims) // "aud","iss" are overwritten. iss = base64(public key)
// or sign.SignMessageForVehicle(skey, vin, "TelemetryClient", claims)
```

The algorithm header is `Tesla.SS256` (Schnorr/P-256, not a standard JWS algorithm). POST the result to the Fleet API `/api/1/vehicles/fleet_telemetry_config_jws`, or POST `{"vins":[...],"config":{...}}` to the proxy's `/api/1/vehicles/fleet_telemetry_config` and the proxy signs and forwards it.

---

## R15. Test writing guide

Reuse the existing test harnesses. All are in-package tests (access to unexported identifiers), so place new tests in the same package.

### `pkg/vehicle` — `testSender` (replaces the dispatcher)

`newTestVehicle()` in `pkg/vehicle/vehicle_test.go` injects a `testSender` that implements the `sender` interface.

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

	// Fix an Infotainment rejection response
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

`testSender` control points: `fixedResponse` (same response for every Recv), `EnqueueError(err)` (queue of errors returned by the next Send), `SendError` (always returned), `ConnectionErrors` (returned in order by StartSessions), `EnqueueResponse(t, msg)`. `RetryInterval` is 1ms, so retry tests are fast. VCSEC error verification helpers: `checkNominalError`, `checkWhitelistOperationStatus`, `testPublicKey()` in `vcsec_test.go`.

### `internal/dispatcher` — `dummyConnector` (vehicle simulator)

`newDummyConnector(t)` in `dispatcher_test.go` generates per-domain vehicle keys and performs real handshake/signature verification with `authentication.Verifier`. If you changed session cache, retry, or replay logic, add tests modeled on `TestCache`, `TestRetrySend`, `TestUnsolicitedSessionInfo`, `TestCorruptedSessionInfo`, `TestDiscardUnauthenticatedSessionInfo`.

### `pkg/proxy` — HTTP/vehicle mocks

`proxy_test.go`: replaces the Tesla server with `p.client = roundTripFunc(func(*http.Request) (*http.Response, error) {...})` and the vehicle with `p.fetchVehicle = func(...) (vehicleSession, error) { return &mockVehicle{...}, nil }`. `mockVehicle` injects failures at each stage via `connectErr`, `sessionErr`, `executeErr`. For endpoint parsing tests, add rows to the table in `command_test.go` (`proxy.ExtractCommandAction(ctx, command, params)`).

### Protocol vectors

`internal/authentication/protocol_doc_test.go` pins the AES-GCM example from `pkg/protocol/protocol.md` (metadata `000105...ff`, K `1b2fce19...`, nonce `dbf79447...`, tag `c228e0ff...`). If you change metadata serialization or the KDF, update this test and the document together. Test keys: `internal/authentication/test_data/*.pem`, `pkg/protocol/test/*.pem`.

### Running

```bash
go test ./pkg/vehicle/ -run 'TestVehicleRetryFail|TestNominalVSCECError' -v
go test -cover ./...      # same as make test (+ go vet)
./check-all.sh            # build + test + vet + gofmt check (+ shellcheck)
```

---

## Verification checklist

- [ ] Do all examples compile with `go build ./...` (Go was not available in the environment where this document was written)?
- [ ] Do the signatures `vehicle.NewVehicle(conn, privateKey, sessionCache)`, `StartSession(ctx, domains)`, `SessionInfo(ctx, *ecdh.PublicKey, domain)`, `SendAddKeyRequestWithRole(ctx, pk, keys.Role, vcsec.KeyFormFactor)`, `GetState(ctx, StateCategory)`, `Send(ctx, domain, payload, auth)`, `SendMessage(ctx, *RoutableMessage)` match `pkg/vehicle/*.go`?
- [ ] Do `cache.New(int)`, `ImportFromFile`, `ExportToFile`, `Vehicle.UpdateCachedSessions(*cache.SessionCache)` match `pkg/cache/cache.go` and `pkg/vehicle/vehicle.go`?
- [ ] Do the `ChargeSchedule` field names match the code generated from `pkg/protocol/protobuf/common.proto` (`DaysOfWeek`, `StartEnabled`, `StartTime`, `EndEnabled`, `EndTime`, `OneTime`, `Enabled`, `Latitude`, `Longitude`, `Id`, `Name`)?
- [ ] Does R4 accurately describe the behavior of sending a `ToVCSECMessage` directly rather than a `RoutableMessage` (`pkg/vehicle/security.go SendAddKeyRequestWithRole`)?
- [ ] Do the 7 `connector.Connector` methods in R10 (`Receive`, `Send`, `VIN`, `Close`, `PreferredAuthMethod`, `RetryInterval`, `AllowedLatency`) match `pkg/connector/connector.go`?
- [ ] Does the R11 order (proto → vehicle → commands.go → proxy/command.go → tests → `make format/linters/test`) match `Makefile` and `.github/workflows/build.yml`?
- [ ] Do the R15 harness names (`newTestVehicle`, `testSender.fixedResponse/EnqueueError/SendError/ConnectionErrors`, `newDummyConnector`, `mockVehicle`, `roundTripFunc`) match the actual test files?
