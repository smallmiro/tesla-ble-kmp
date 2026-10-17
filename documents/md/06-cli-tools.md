# 06. CLI Tool Reference

Documents, based on the code, the flags, environment variables, keyring behavior, and execution flow of the 5 tools under `cmd/` (`tesla-keygen`, `tesla-control`, `tesla-auth-token`, `tesla-jws`, `tesla-http-proxy`) and the 2 programs under `examples/`.

Related files (relative to the repository root):
- `pkg/cli/config.go`, `pkg/cli/keyring.go`, `pkg/cli/config_{linux,darwin,windows}.go` — shared config/keyring
- `cmd/tesla-keygen/main.go`, `cmd/tesla-control/{main.go,commands.go}`, `cmd/tesla-auth-token/main.go`, `cmd/tesla-jws/main.go`, `cmd/tesla-http-proxy/main.go`
- `examples/ble/main.go`, `examples/unlock/unlock.go`
- `README.md`, `cmd/tesla-control/README.md`, `cmd/tesla-jws/README.md`

Related documents: [04-go-api-reference.md](04-go-api-reference.md) (`pkg/cli` API), [05-command-catalog.md](05-command-catalog.md), [07-http-proxy.md](07-http-proxy.md), [08-errors.md](08-errors.md)

---

## Build / Install

```bash
cd vehicle-command
go get ./...          # dependencies
go build ./...        # verify compilation
go install ./cmd/...  # install the 5 binaries into $GOBIN (same as make install)
```

- Go 1.23 (`go.mod`; CI uses 1.23.0). Supports macOS/Linux. Windows builds without BLE, but `tesla-control` BLE does not work (`device_windows.go` always returns an error).
- Linux BLE requires `CAP_NET_ADMIN`: `sudo setcap 'cap_net_admin=eip' "$(which tesla-control)"` (on adapter errors, `AdapterErrorHelpMessage` prints this hint).
- The ENTRYPOINT of the Docker image `tesla/vehicle-command` is `tesla-http-proxy`; it can be replaced with `--entrypoint tesla-control`. Assume BLE is unavailable inside containers.

---

## Shared Configuration (`pkg/cli.Config`)

### Precedence

**Command-line flags > environment variables > built-in defaults.** `ReadFromEnvironment()` does not overwrite fields that are already set, and every tool calls it after `flag.Parse()`.

### Flag Table

Registered by `RegisterCommandLineFlags()` according to the `Config.Flags` mask. See each tool's section for its mask.

| Flag | Type | Default | Environment variable | Registered when | Meaning |
|---|---|---|---|---|---|
| `-vin` | string | "" | `TESLA_VIN` | `FlagVIN` | 17-character VIN |
| `-key-name` | string | "" | `TESLA_KEY_NAME` | `FlagPrivateKey` | Keyring entry name |
| `-key-file` | string | "" | `TESLA_KEY_FILE` | `FlagPrivateKey` | PEM private key file (`EC PRIVATE KEY` or PKCS8) |
| `-session-cache` | string | "" → `TESLA_CACHE_FILE` → `$HOME/.tesla-cache.json` | `TESLA_CACHE_FILE` | `FlagPrivateKey` | Session cache JSON |
| `-disable-session-cache` | bool | false | (none) | `FlagPrivateKey` | Disable cache |
| `-domain` | repeatable (`DomainList`) | none = all | (none) | `FlagPrivateKey` | `vcsec` / `infotainment` (case-insensitive) |
| `-token-name` | string | "" | `TESLA_TOKEN_NAME` | `FlagOAuth` | Keyring token entry name |
| `-token-file` | string | "" | `TESLA_TOKEN_FILE` | `FlagOAuth` | OAuth token file |
| `-keyring-type` | string (`backendType`) | unset → OS default | `TESLA_KEYRING_TYPE` | `FlagOAuth` or `FlagPrivateKey` | One of `keyring.AvailableBackends()`. See the list in `-h` |
| `-keyring-file-dir` | string | `~/.tesla_keys` | `TESLA_KEYRING_PATH` (applied only when the flag is not registered) | Same as above | File backend directory |
| `-keyring-debug` | bool | false | `TESLA_KEYRING_DEBUG` (presence only) | Same as above | Keyring debug log |
| `-bt-adapter` | string | "" (=hci0) | (none) | Linux + `FlagBLE` | `hciN`, N=0..15 |

### All Environment Variables

| Variable | Read by | Notes |
|---|---|---|
| `TESLA_VIN` | `cli.Config` | |
| `TESLA_KEY_NAME`, `TESLA_KEY_FILE` | `cli.Config` | When both `-key-name`/`-key-file` are empty, **both variables are read together**. If both are set, `PrivateKey()` tries the file first and falls back to the keyring on failure (nil). |
| `TESLA_TOKEN_NAME`, `TESLA_TOKEN_FILE` | `cli.Config` | Read together when both are empty. `token()` tries the file first (only a file-not-found error falls back to the keyring; other read errors are returned). |
| `TESLA_CACHE_FILE` | `cli.Config` | If unset, `$HOME/.tesla-cache.json`. If `$HOME` is also unset, caching is disabled. |
| `TESLA_KEYRING_TYPE` | `cli.Config` | Invalid values are silently ignored (default kept) |
| `TESLA_KEYRING_PASSWORD` | `cli.Config.getPassword` | Password for the file/keychain backend. When set, the prompt is skipped |
| `TESLA_KEYRING_PATH` | `cli.Config` | Only when `Backend.FileDir` is empty |
| `TESLA_KEYRING_DEBUG` | `cli.Config` | If present, Debug=true |
| `TESLA_VERBOSE` | `tesla-control`, `tesla-http-proxy` | Any value other than `"false"`, `"0"` enables debug logging |
| `TESLA_HTTP_PROXY_TLS_CERT`, `TESLA_HTTP_PROXY_TLS_KEY`, `TESLA_HTTP_PROXY_HOST`, `TESLA_HTTP_PROXY_PORT`, `TESLA_HTTP_PROXY_TIMEOUT` | `tesla-http-proxy` | [07-http-proxy.md](07-http-proxy.md) |

Recommended initial setup:

```bash
export TESLA_KEY_NAME=$(whoami)
export TESLA_TOKEN_NAME=$(whoami)      # only when using the internet path
export TESLA_VIN=5YJ30123456789ABC
export TESLA_CACHE_FILE=~/.tesla-cache.json
```

### Keyring (`pkg/cli/keyring.go`)

| Item | Value |
|---|---|
| Library | `github.com/99designs/keyring` v1.2.2 |
| `ServiceName` | `com.tesla.auth` |
| Private key entry key | `vehicleCommandKey.<KeyringKeyName>` |
| Token entry key | `oauthtoken.<KeyringTokenName>` |
| Private key storage format | P-256 scalar `D` as 32-byte big-endian (`FillBytes`). Only `*authentication.NativeECDHKey` can be stored ("key is not exportable"). If the length is not 32, "invalid private key". |
| Load | `protocol.UnmarshalECDHPrivateKey(item.Data)`; if nil, "invalid private key" |
| Backend selection | `-keyring-type` / `TESLA_KEYRING_TYPE` → `Backend.AllowedBackends = [type]`. If unset, the keyring library's OS default order (macOS: login keychain, `KeychainTrustApplication: true`; Linux: secret-service/kwallet/keyctl(`KeyCtlScope: "user"`)/pass/file etc., in order of availability) |
| File backend | `-keyring-file-dir` (default `~/.tesla_keys`); password from `TESLA_KEYRING_PASSWORD` or a prompt |
| Password prompt (`getPassword`) | Uses `c.password` if set. Otherwise prompts on stdout if stdout is a terminal, else on stderr if stderr is a terminal; if neither, "no terminal output available for password prompt". `term.ReadPassword(stdin)`. Cached after first entry. |
| Debug | `-keyring-debug` / `TESLA_KEYRING_DEBUG` → `Config.Debug`; `tesla-keygen` uses this value for `log.SetLevel(LevelDebug)` |

Why `LoadCredentials()` is called before `Connect()`: so that the keyring password prompt does not wait on the user inside the connection timeout (ctx).

---

## tesla-keygen

`cli.NewConfig(cli.FlagOAuth | cli.FlagPrivateKey)` — token flags are also registered but not used.

```
usage: tesla-keygen [OPTION...] create|delete|export|migrate
```

| Option | Meaning |
|---|---|
| `-f` | Overwrite the existing key on `create` |
| `-output FILE` | Write the public key to a file instead of stdout |
| Shared flags | `-key-name`, `-key-file`, `-keyring-type`, `-keyring-file-dir`, `-keyring-debug`, `-session-cache`, `-disable-session-cache`, `-domain`, `-token-name`, `-token-file` |

Subcommand behavior:

| Subcommand | Behavior | Output | Exit code |
|---|---|---|---|
| `create` | Without `-f`, first tries `config.PrivateKey()` → on success **prints only the existing key's public key and exits (does not overwrite)**. On failure (no key) or with `-f`, generates a new P-256 key via `authentication.NewECDHPrivateKey(rand.Reader)` → `config.SavePrivateKey` (keyring if a keyring name is set, otherwise `EC PRIVATE KEY` PEM 0600 to `-key-file`) → prints the public key | PKIX PEM `-----BEGIN PUBLIC KEY-----` | 0 success / 1 failure |
| `delete` | `config.DeletePrivateKey()` — deletes keyring entry `vehicleCommandKey.<name>`. Does not delete file keys | None | 0 / 1 |
| `export` | Loads via `config.PrivateKey()` and prints the **private key** to stdout as `EC PRIVATE KEY` PEM | SEC1 PEM | 0 / 1 |
| `migrate` | Both `-key-file` and `-key-name` required. Loads from file → clears `KeyFilename=""` then `SavePrivateKey` (to keyring) → prints the public key. The original file is kept | PKIX PEM | 0 / 1 |

If the argument count is not 1 or the subcommand is unknown, prints usage and exits 1. "Failed to parse key. The keyring may be corrupted. Run with -f to generate new key." occurs when keyring data is corrupted.

```bash
export TESLA_KEY_NAME=$(whoami)
tesla-keygen create > public_key.pem          # create in keyring
tesla-keygen -key-file private.pem create      # create in file (when no keyring name)
tesla-keygen -key-file private.pem -key-name $(whoami) migrate   # file → keyring
tesla-keygen export > backup.pem               # back up private key (sensitive)
tesla-keygen delete
```

Keys created with OpenSSL are also compatible: `openssl ecparam -genkey -name prime256v1 -noout > private.pem && openssl ec -in private.pem -pubout > public.pem`.

---

## tesla-control

Starts with `cli.NewConfig(cli.FlagAll)`, but when a command name is given, `configureFlags` recomputes `Flags` to match the command's requirements.

```
Usage: tesla-control [OPTION...] COMMAND [ARG...]
```

### Tool-Specific Flags

| Flag | Default | Meaning |
|---|---|---|
| `-debug` | false | `log.SetLevel(LevelDebug)`. Includes BLE TX/RX hex dumps. Can also be set via `TESLA_VERBOSE`. |
| `-ble` | false | Force BLE even if OAuth environment variables are set |
| `-command-timeout` | `5s` | ctx timeout for each command |
| `-connect-timeout` | `20s` | ctx timeout for connect + handshake |
| Shared flags | All of `FlagAll` (`-vin`, `-key-*`, `-token-*`, `-session-cache`, `-disable-session-cache`, `-domain`, `-keyring-*`, Linux `-bt-adapter`) |

### Execution Flow (`cmd/tesla-control/main.go`)

1. `NewConfig(FlagAll)`, register flags, `flag.Parse()`.
2. If `-debug` is absent, check `TESLA_VERBOSE`.
3. `config.ReadFromEnvironment()` (reads all environment variables per FlagAll).
4. If arguments are present:
   - `help` → full usage (note exit code 1: the initial `status` value 1 is returned unchanged); `help COMMAND` → that command's usage (exit 0).
   - Validate requirements via `configureFlags(config, COMMAND, forceBLE)`. On failure prints "Missing required flag: ...", exit 1.
5. `config.LoadCredentials()` — handles the keyring prompt.
6. `config.Connect(ctx(connect-timeout))` — if the error is `ble.IsAdapterError`, prints `AdapterErrorHelpMessage`.
7. If `car != nil`, `defer car.Disconnect()`, `defer config.UpdateCachedSessions(car)` (saves handshake results to the cache file).
8. If arguments are present, `runCommand` once; otherwise `runInteractiveShell`.

### configureFlags Rules

```
c.Flags = FlagBLE
if info.domain != DomainNone { c.Domains = [info.domain] }          // body-controller-state → VCSEC
bleWake = forceBLE && COMMAND == "wake"
if bleWake || info.requiresAuth { c.Flags |= FlagPrivateKey | FlagVIN }
if bleWake { c.Domains = [VCSEC] }                                    // Infotainment is asleep, so handshake only VCSEC
if !info.requiresFleetAPI { c.Flags |= FlagVIN }
if forceBLE { if info.requiresFleetAPI → ErrRequiresOAuth } else { c.Flags |= FlagOAuth }
checkReadiness(COMMAND, havePrivateKey, haveOAuth, haveVIN)
```

`checkReadiness` (used both for the pre-check and the check right before execution):

| `requiresFleetAPI` | `requiresAuth` | Required | Error if missing |
|---|---|---|---|
| true | (any) | OAuth token (`-token-name`/`-token-file`/environment) | `ErrRequiresOAuth` "command requires a FleetAPI OAuth token" |
| false | (any) | VIN | `ErrRequiresVIN` "command requires a VIN" |
| (any) | true | Private key (`-key-name`/`-key-file`/environment) | `ErrRequiresPrivateKey` "command requires a private key" |

Right before execution, it re-checks with actual state: `havePrivateKey = car != nil && car.PrivateKeyAvailable()`, `haveOAuth = acct != nil`, `haveVIN = car != nil`.

Transport selection (`Config.Connect`): if `FlagOAuth` is set (= no `-ble`) and a token location exists, **internet**; otherwise, if `FlagBLE|FlagVIN`, **BLE**. That is, if `TESLA_TOKEN_NAME` is exported, you must add `-ble` to use BLE.

### Command Execution (`execute`)

- Argument count: if `len(args)-1` is not between `len(info.args)` and `len(info.args)+len(info.optional)` inclusive, "Invalid number of command line arguments" + usage, `ErrCommandLineArgs`.
- Builds a map of positional arguments keyed by `args[i].name` and calls `handler(ctx, acct, car, keywords)`.
- If the handler error wraps `ErrCommandLineArgs`, prints that command's usage.

The 3 error messages of `runCommand` (stderr):

| Condition | Message |
|---|---|
| `protocol.MayHaveSucceeded(err)` | `Couldn't verify success: <err>` — the command may have executed (timeout, etc.). Check state before retrying |
| `errors.Is(err, protocol.ErrNoSession)` | `You must provide a private key with -key-name or -key-file to execute this command` |
| Otherwise | `Failed to execute command: <err>` |

Exit codes: single-command mode is 0 on success / 1 on failure. Connection failure, credential failure, and missing flags are also 1.

### Interactive Shell

Running without arguments shows a `> ` prompt. Each line is split with `github.com/google/shlex` and passed to `runCommand` (connection/session are reused). `exit` quits (code 0). Empty lines are ignored. Individual command failures do not affect the final exit code. Only a stdin read error yields 1.

### Usage Examples

```bash
# BLE pairing (inside the car, with NFC card ready)
tesla-control -ble add-key-request public_key.pem owner cloud_key
# Verify enrollment (whether it has synced to Infotainment)
tesla-control -ble session-info public_key.pem infotainment
tesla-control -ble list-keys

# Basic commands
tesla-control -ble lock
tesla-control -ble -debug unlock
tesla-control -ble climate-set-temp 22C
tesla-control -ble seat-heater front-left high
tesla-control -ble charging-set-limit 80
tesla-control -ble body-controller-state         # works without a key, OK while Infotainment is asleep
tesla-control -ble state charge                  # key required
tesla-control -ble -domain vcsec lock            # does not wake Infotainment

# Schedules
tesla-control -ble charging-schedule-add weekdays 22:00-6:00 37.4 -122.1        # prints ID
tesla-control -ble charging-schedule-add all -6:00 37.4 -122.1 once
tesla-control -ble charging-schedule-remove id 1700000000
tesla-control -ble precondition-schedule-add mon,wed,fri 07:30 37.4 -122.1

# Internet (after setting TESLA_TOKEN_NAME)
tesla-control lock
tesla-control wake
tesla-control product-info
tesla-control get api/1/vehicles/$TESLA_VIN/vehicle_data
echo '{"name":"Dave"}' | tesla-control post api/1/users/keys
tesla-control rename-key public_key.pem "Home Server"

# Linux adapter selection, timeout tuning
tesla-control -ble -bt-adapter hci1 -connect-timeout 40s -command-timeout 10s honk

# Interactive
tesla-control -ble
> lock
> state climate
> exit
```

---

## tesla-auth-token

`cli.NewConfig(cli.FlagOAuth)`. This tool does **not issue** tokens. It only stores an access token, obtained through the Fleet API documentation procedure, into the keyring.

```
usage: tesla-auth-token [-token-name token_name] [file]
```

- `-token-name` (default `$TESLA_TOKEN_NAME`) is required. If missing, error and exit 1.
- 0 arguments: all of stdin is the token. 1: file contents. 2 or more: "Too many command-line arguments".
- `config.SaveTokenToKeyring(token)` → key `oauthtoken.<name>`. Leading/trailing whitespace is not stripped on save, but `account.New` applies `TrimSpace`.
- Shared keyring flags such as `-keyring-type` are not registered (`RegisterCommandLineFlags` is not called). Environment variables `TESLA_KEYRING_TYPE`, `TESLA_KEYRING_PASSWORD`, `TESLA_KEYRING_PATH`, `TESLA_KEYRING_DEBUG` are applied via `ReadFromEnvironment`.

```bash
export TESLA_TOKEN_NAME=$(whoami)
echo -n "$ACCESS_TOKEN" | tesla-auth-token
tesla-auth-token token.txt
```

---

## tesla-jws

`cli.NewConfig(cli.FlagPrivateKey | cli.FlagVIN)`. Creates/verifies JWS (JSON Web Signature) used for Fleet Telemetry configuration and similar.

```
usage: tesla-jws [OPTION...] sign APP [JSON_FILE]
       tesla-jws verify [JWS_FILE]
```

| Item | Value |
|---|---|
| `-fleet` | Fleet-wide signature (`SignMessageForFleet`). Without it, `-vin` is required (`SignMessageForVehicle`) |
| `APP` | Last element of the audience. For Fleet Telemetry, `TelemetryClient` |
| `JSON_FILE` | Claims JSON. stdin if omitted. `aud`, `iss` are overwritten |
| audience | Vehicle: `com.tesla.vehicle.<VIN>.<APP>` / fleet: `com.tesla.fleet.<APP>` |
| `alg` header | `authentication.TeslaSchnorrSHA256` (Schnorr/P-256, not a standard JWS algorithm) |
| `iss` | Client public key (65-byte uncompressed), base64 (std) encoded |
| `verify` | Parses `iss` as the public key and verifies only the signature. **Does not verify issuer trust or aud.** On success prints the claims JSON |

```bash
tesla-jws -fleet sign TelemetryClient telemetry_config.json > signed-config.jws
tesla-jws -vin $TESLA_VIN sign MyApp claims.json
tesla-jws verify signed-config.jws
```

The output JWS is not a bearer token, so it is not sensitive. The proxy's `POST /api/1/vehicles/fleet_telemetry_config` performs the same signing automatically ([07-http-proxy.md](07-http-proxy.md)).

---

## tesla-http-proxy (Summary)

`cli.NewConfig(cli.FlagPrivateKey)`. Tool-specific flags: `-cert`, `-tls-key`, `-host` (default `localhost`), `-port` (default 443), `-timeout` (default 10s), `-verbose`. Environment variables `TESLA_HTTP_PROXY_*`, `TESLA_VERBOSE`; private key via `TESLA_KEY_NAME`/`TESLA_KEY_FILE`. Details in [07-http-proxy.md](07-http-proxy.md).

---

## examples/

### `examples/ble/main.go` (unlock + climate-on over BLE)

| Flag | Meaning |
|---|---|
| `-vin VIN` | Required |
| `-key FILE` | PEM private key. Without it, the handshake fails |
| `-scan-only` | Only scan advertisements and exit (exit code 130 on Ctrl-C) |
| `-debug` | TX/RX dump |
| `-bt-adapter ID` | Linux only |

Flow: `ble.InitAdapterWithID` → `ble.ScanVehicleBeacon` → `ble.NewConnectionFromScanResult` → `vehicle.NewVehicle(conn, key, nil)` → `Connect` → `StartSession(ctx, nil)` → `Unlock` → `ClimateOn`. 30-second ctx overall.

### `examples/unlock/unlock.go` (unlock over the internet)

| Flag | Default |
|---|---|
| `-key FILE` | `private.key` |
| `-vin VIN` | Required |
| `-token FILE` | OAuth token file |

Flow: `account.New(token, "example-unlock/1.0.0")` → `GetVehicle` → `Connect` → `StartSession` → `Unlock`. Includes an example of handling `protocol.MayHaveSucceeded(err)`.

---

## Verification Checklist

- [ ] If flags were added/changed, the registration condition (`Flags` mask) in `RegisterCommandLineFlags`, the environment variable mapping in `ReadFromEnvironment`, and the tables in this document were updated together.
- [ ] New environment variables are defined as `Env*` constants in `pkg/cli/config.go` and also added to the "Configuration" section of `README.md`.
- [ ] If a command was added to `tesla-control`, its `requiresAuth`/`requiresFleetAPI`/`domain` interact with the `configureFlags` rules to produce the correct transport/handshake domain (check both with and without `-ble`).
- [ ] `cmd/tesla-control/commands_test.go`, `pkg/cli/config_test.go`, `cmd/tesla-http-proxy/main_test.go` pass.
- [ ] Changing the keyring storage format (32-byte scalar) or the entry key prefixes (`vehicleCommandKey.`, `oauthtoken.`) makes existing users' keys unreadable — do not change them without a migration path.
