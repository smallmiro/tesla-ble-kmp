# 07. HTTP Proxy (tesla-http-proxy / pkg/proxy)

Documents, based on the code, the routing, response conventions, status codes, deployment, and extension of the proxy that receives REST requests, converts them into end-to-end authenticated vehicle commands, and sends them to the Fleet API. This proxy is **internet-path only** and does not use BLE.

Related files (relative to the repository root):
- `cmd/tesla-http-proxy/main.go` — executable (TLS, flags, environment variables)
- `cmd/tesla-http-proxy/server.go` — self-signed certificate generation helper (`NewServer`, currently unused by main)
- `pkg/proxy/proxy.go` — `Proxy`, `ServeHTTP`, routing, forwarding, telemetry signing
- `pkg/proxy/command.go` — endpoint → `vehicle.Vehicle` method mapping
- `pkg/proxy/{proxy_test.go,command_test.go,status_test.go}`, `cmd/tesla-http-proxy/main_test.go`
- `Dockerfile`, `docker-compose.yml`, `.dockerignore`

Related documents: [05-command-catalog.md](05-command-catalog.md) (endpoint table), [04-go-api-reference.md](04-go-api-reference.md), [08-errors.md](08-errors.md)

---

## Running

```bash
tesla-http-proxy -tls-key config/tls-key.pem -cert config/tls-cert.pem -key-file config/fleet-key.pem -port 4443
```

| Flag | Environment variable | Default | Meaning |
|---|---|---|---|
| `-cert FILE` | `TESLA_HTTP_PROXY_TLS_CERT` | "" | TLS certificate chain (server + intermediate CA + root CA concatenated) |
| `-tls-key FILE` | `TESLA_HTTP_PROXY_TLS_KEY` | "" | TLS server private key |
| `-host HOST` | `TESLA_HTTP_PROXY_HOST` | `localhost` | Bind host. Prints a warning if not `localhost` |
| `-port N` | `TESLA_HTTP_PROXY_PORT` | 443 | Port. "invalid port" if the environment variable is not an integer |
| `-timeout D` | `TESLA_HTTP_PROXY_TIMEOUT` | `10s` (`proxy.DefaultTimeout`) | Command/forwarding ctx timeout. `time.ParseDuration` format |
| `-verbose` | `TESLA_VERBOSE` | false | debug logging |
| `-key-name` / `-key-file` etc. | `TESLA_KEY_NAME` / `TESLA_KEY_FILE`, keyring variables | | Private key for command authentication (`cli.FlagPrivateKey`). The `-session-cache` flag is also registered, but the proxy uses its own in-memory cache |

Environment variables apply only when the flag is at its default (`readFromEnvironment`). Order: flag parsing → `readFromEnvironment()` → `config.ReadFromEnvironment()`.

Startup checks:
1. `config.PrivateKey()` fails → exit 1.
2. **TLS key reuse check**: if `protocol.LoadPublicKey(tls-key)` succeeds (i.e. a P-256 key) and its public key equals the command key's `PublicBytes()`, prints "It is unsafe to use the same private key for TLS and command authentication." and exits. If not P-256 (e.g. P-384, RSA), the check passes.
3. `proxy.New(ctx, skey, cacheSize=10000)`, `p.Timeout = -timeout`.
4. `http.ListenAndServeTLS(addr, cert, key, p)`. On return, logs "Server stopped".

### TLS

There is **intentionally no** option to disable TLS (`main.go` comment: prevents DIY users from accidentally exposing vehicles to the internet). If non-TLS is truly required, import `pkg/proxy` directly and wrap it with `http.ListenAndServe` (see "Using as a Library" below).

Self-signed certificate for development (same as README; P-384, so it also passes the reuse check):

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

Clients trust it with `curl --cacert config/tls-cert.pem`.

### Docker

`Dockerfile`: stage 1 on `golang:1.23.0` runs `go mod download` → `go build -o ./build ./...`; stage 2 copies into `/usr/local/bin` on `gcr.io/distroless/base-debian12:nonroot`. `ENTRYPOINT ["tesla-http-proxy"]` (arguments are passed through as flags).

```bash
docker pull tesla/vehicle-command:latest
docker run --security-opt=no-new-privileges:true -v ./config:/config -p 127.0.0.1:4443:4443 \
  tesla/vehicle-command:latest -tls-key /config/tls-key.pem -cert /config/tls-cert.pem \
  -key-file /config/fleet-key.pem -host 0.0.0.0 -port 4443
```

`docker-compose.yml` configures everything via environment variables only:

| Variable | Value |
|---|---|
| `TESLA_HTTP_PROXY_TLS_CERT` | `/config/tls-cert.pem` |
| `TESLA_HTTP_PROXY_TLS_KEY` | `/config/tls-key.pem` |
| `TESLA_HTTP_PROXY_HOST` | `0.0.0.0` (required inside a container; port mapping controls exposure) |
| `TESLA_HTTP_PROXY_PORT` | `4443` |
| `TESLA_HTTP_PROXY_TIMEOUT` | `10s` |
| `TESLA_KEY_FILE` | `/config/fleet-key.pem` |
| `TESLA_VERBOSE` | `true` |

Volume `./config:/config`, `security_opt: no-new-privileges:true`. The distroless image has no shell.

---

## Request Handling (`Proxy.ServeHTTP`)

```
req.Body = MaxBytesReader(1 MiB)                       # 413 on the reading side if exceeded
if path == "/health":  GET → 200 "OK" (text) / otherwise → 405
acct = getAccount(req)                                  # 403 if no "Authorization: Bearer <jwt>"
acct.Host = domainForSubject[acct.Subject] if present  # reuse result of a previous 421 redirect
if path starts with "/api/1/vehicles/":
    seg = split(path, "/")
    if len(seg)==7 && seg[5]=="command":                # /api/1/vehicles/{VIN}/command/{cmd}
        VIN length != 17 → 404 "expected 17-character VIN in path (do not user Fleet API ID)"
        VIN marked unsupported → forwardRequest (+ update domainForSubject if Host changed)
        else handleVehicleCommand; if it returns ErrCommandUseRESTAPI, forwardRequest
        return
    if len(seg)==5 && seg[4]=="fleet_telemetry_config": # /api/1/vehicles/fleet_telemetry_config
        handleFleetTelemetryConfig; return
forwardRequest(acct, w, req)                            # all other /api/1/* (vehicle_data, wake_up, etc.)
```

`getAccount` calls `account.New(token, "tesla-http-proxy/1.1.0")` on every request. The token's `aud`/`ou_code` determines the regional Fleet API host (default `fleet-api.prd.na.vn.cloud.tesla.com`; rules in [04-go-api-reference.md](04-go-api-reference.md) `pkg/account`).

### handleVehicleCommand Flow

```
ctx = WithTimeout(p.Timeout)
lockVIN(ctx, vin)                          # failure (ctx expired) → 503
  ↓ defer unlockVIN
loadVehicleAndCommandFromRequest
  ├ method != POST                         → 405 {"error":"Method Not Allowed"}
  ├ extractCommandAction(req, command)     # read body, JSON → RequestParameters, restore body
  │   ├ over 1 MiB                          → 413 (HTTPError, body is the err string)
  │   ├ read failure                        → 400 "could not read request body"
  │   ├ JSON parse failure                  → 400 "error occurred while parsing request parameters"
  │   ├ ErrCommandUseRESTAPI               → returned → ServeHTTP calls forwardRequest
  │   ├ ErrCommandNotImplemented           → 400 {"error":"command not implemented"}
  │   ├ unknown command                     → 400 {"response":null,"error":"invalid_command","error_description":""}
  │   └ parameter NominalError              → 400 {"response":{"result":false,"reason":"missing volume param"},...}
  └ fetchVehicle(acct, vin)                → failure/nil → 500
car.Connect(ctx)                           → failure → httpStatusCode(err)
car.StartSession(ctx)  (both domains)
  ├ ErrProtocolNotSupported (Fleet API 422) → markUnsupportedVIN(vin); forwardRequest (this VIN is always forwarded afterwards)
  └ other                                  → httpStatusCode(err)
defer car.UpdateCachedSessions(p.sessions)
car.Execute(fn)
  ├ ErrCommandUseRESTAPI                   → returned → forwardRequest
  ├ protocol.IsNominalError(err)           → 200 {"response":{"result":false,"reason":"<err>"},"error":"","error_description":""}
  ├ other                                  → httpStatusCode(err) (if inet.HTTPError, its code and body as-is)
  └ nil                                    → 200 {"response":{"result":true,"reason":""}}
```

`httpStatusCode(err)`:

| Condition (`errors.Is`) | Code | Reason |
|---|---|---|
| `inet.ErrVehicleNotAwake` | 408 Request Timeout | Same code the Fleet API uses for offline/asleep vehicles |
| `protocol.ErrKeyNotPaired` | 412 Precondition Failed | OAuth passed, but the vehicle key pairing precondition failed (not 401) |
| Otherwise | 500 | |

`writeJSONError(w, code, err)`:
- If `err` is `*inet.HTTPError`, **the code is overwritten with `httpErr.Code` and the body is `err.Error()` as-is** (the JSON body from the Fleet API is passed through; Content-Type is always `application/json`).
- `err == nil` → `{"error": http.StatusText(code)}`.
- `IsNominalError(err)` → `{"response":{"result":false,"reason":"<err>"},"error":"","error_description":""}` (the `Response` struct has no `omitempty`).
- Otherwise → `{"response":null,"error":"<err>","error_description":""}`.
- Body ends with `\n`. If not 200, `log.Error("Returning error ...")`.

### Status Code Summary

| Code | Situation |
|---|---|
| 200 | Command success `{"response":{"result":true,"reason":""}}` / rejected by vehicle (NominalError) `{"response":{"result":false,"reason":"..."}}` / forwarded upstream 200 |
| 400 | Invalid JSON, missing/mistyped parameter (NominalError format), unknown command (`invalid_command`), `remote_boombox`, invalid `window_control` command value, `forwardRequest` request creation failure/RemoteAddr parse failure |
| 403 | No `Authorization: Bearer` header |
| 404 | VIN is not 17 characters (do not use the Fleet API numeric ID) |
| 405 | Non-GET on `/health`, non-POST on command endpoints |
| 408 | Vehicle offline/asleep (`ErrVehicleNotAwake`, Fleet API 503 or 408+"vehicle is offline") |
| 412 | Public key not enrolled on the vehicle (`ErrKeyNotPaired`, `UNKNOWN_KEY_ID` or `KEY_NOT_ON_WHITELIST`) |
| 413 | Request body over 1 MiB |
| 421 | If upstream 421 has no `Alt-Svc` header, passed through as-is; if `Alt-Svc` exists but has no `h2=https://` entry, `{"error":"Misdirected Request"}` |
| 500 | Other errors, `fetchVehicle` failure, telemetry signing failure, JSON serialization failure |
| 502 | Upstream HTTP error (non-timeout), response body read failure, response over 10 MB, 421 retries exhausted ("max retry exhausted") |
| 503 | ctx expired while waiting in `lockVIN` (burst of concurrent commands to the same VIN) |
| 504 | Upstream timeout (`url.Error.Timeout()`), ctx expired while waiting for a 421 retry |
| Other | Code returned by the Fleet API as-is (`inet.HTTPError`, e.g. 401, 429) |

Note 1: `/command/wake_up` goes through `handleVehicleCommand`, so `Connect`+`StartSession` run before `Execute`. If the vehicle is asleep, the handshake step yields `ErrVehicleNotAwake` → **408**, so it cannot wake the vehicle. Wake an asleep vehicle via the forwarding path `POST /api/1/vehicles/{VIN}/wake_up`, then send the command.

Note 2: If the Fleet API returns **422** during the session handshake (`StartSession`), `ErrProtocolNotSupported` → that VIN is remembered as `unsupported` and the original request is forwarded to the Fleet API (older vehicles that do not support the protocol). This persists until the process restarts.

### forwardRequest (Generic Forwarding)

1. New request `http.NewRequestWithContext(ctx(p.Timeout), req.Method, req.URL, req.Body)`, headers copied.
2. Remove per-hop headers: `Proxy-Connection`, `Keep-Alive`, `Transfer-Encoding`, `Te`, `Upgrade` (on both request and response).
3. `X-Forwarded-For`: if no existing value, add the client IP; if present, append and flatten into one.
4. `URL.Scheme = "https"`, `URL.Host = acct.Host`. The body is read into memory and kept for retries.
5. The response body is read up to `MaxResponseLength(10,000,000)+1`; 502 if exceeded.
6. **421 Misdirected Request + `Alt-Svc: h2=https://<host>`** → `acct.Host = <host>`, store `domainForSubject[sub] = host`, retry after 1 second. On failure of attempt `MaxAttempts = 2`, 502 "max retry exhausted". (`inet.Connection` separately updates from the `use base URL:` phrase in the body.)
7. On success, the upstream status code, headers, and body are passed through as-is.

### fleet_telemetry_config

`POST /api/1/vehicles/fleet_telemetry_config` with body `{"vins":[...], "config":{...}}`:
1. Read body (1 MiB limit → 413, otherwise → 400); JSON parse failure → 400.
2. If `config.aud`/`config.iss` are present, log a warning and overwrite.
3. `sign.SignMessageForFleet(p.commandKey, "TelemetryClient", config)` → JWS. Failure → 500.
4. Replace the body with `{"vins":[...], "token":"<jws>"}`, change the URL to `/api/1/vehicles/fleet_telemetry_config_jws`, and call `forwardRequest`.

VIN/config validation is delegated to Tesla's servers.

---

## Internal State and Concurrency

| Field | Type | Role |
|---|---|---|
| `commandKey` | `protocol.ECDHPrivateKey` | Single key used to sign all vehicle commands (Fleet key). Its public key must be enrolled on each vehicle |
| `sessions` | `*cache.SessionCache` (`cache.New(10000)`) | Per-VIN domain sessions. **In-memory only**, lost on restart. Loaded per command via `GetVehicle(..., p.sessions)` and `UpdateCachedSessions` at the end |
| `vinLock` | `sync.Map[vin]chan bool` | Per-VIN mutex. Commands for the same VIN are serialized (VCSEC requires counter ordering). The owner wakes waiters with `close(ch)` after `Delete`; map size is bounded by the number of concurrent commands |
| `unsupported` | `sync.Map[vin]bool` | VINs that received 422. Always forwarded afterwards |
| `domainForSubject` | `sync.Map[sub]host` | 421 redirect result per OAuth `sub` |
| `client` | `httpDoer` (`*http.Client{}`) | For forwarding. Replaced in tests |
| `fetchVehicle` | `func(ctx, acct, vin) (vehicleSession, error)` | Default `acct.GetVehicle(ctx, vin, commandKey, sessions)`. Replaced in tests |

A new `inet.Connection` + `vehicle.Vehicle` is created and `Disconnect`ed for each command. Thanks to the session cache, the handshake is skipped from the second command on (while the counter/epoch remain valid).

---

## Security Considerations

- If `-host` is not `localhost`, a warning is shown at startup: if unauthenticated clients send excessive traffic to Tesla's servers through the proxy, the IP may be blocked. The proxy itself has no client authentication, and the Bearer token is **passed through to the Fleet API as-is** (Tesla validates the token).
- A single command key signs for all VINs, so protecting the key file/keyring is critical. Reusing the TLS key as the command key is prohibited (checked at startup).
- Request body capped at 1 MiB, response at 10 MB. Because `X-Forwarded-For` is added, the client IP is exposed upstream.
- To add client authentication, wrap `Proxy` in an `http.Handler` (the approach recommended in the `main.go` comment):

```go
type authed struct{ next http.Handler }

func (a authed) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    if r.Header.Get("X-Client-Token") != os.Getenv("MY_CLIENT_TOKEN") { // example: use mTLS etc. in practice
        http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
        return
    }
    a.next.ServeHTTP(w, r)
}

// main: http.ListenAndServeTLS(addr, cert, key, authed{next: p})
```

---

## curl Examples

```bash
export TESLA_AUTH_TOKEN=<access-token>
export VIN=5YJ30123456789ABC
CA=config/tls-cert.pem
BASE=https://localhost:4443

curl --cacert $CA $BASE/health                                              # OK

# End-to-end authenticated commands (signed by the proxy)
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{}' $BASE/api/1/vehicles/$VIN/command/flash_lights
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{"percent":80}' $BASE/api/1/vehicles/$VIN/command/set_charge_limit
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{"seat_position":0,"level":3}' $BASE/api/1/vehicles/$VIN/command/remote_seat_heater_request
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     --data '{"days_of_week":"Mon,Tues,Wed","lat":37.4,"lon":-122.1,"start_time":1320,"start_enabled":true,"end_enabled":false,"enabled":true}' \
     $BASE/api/1/vehicles/$VIN/command/add_charge_schedule

# Forwarded endpoints (not signed by the proxy)
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" $BASE/api/1/vehicles/$VIN/vehicle_data | jq .
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -X POST $BASE/api/1/vehicles/$VIN/wake_up   # wake asleep vehicles via this path

# Sign + submit telemetry config
curl --cacert $CA -H "Authorization: Bearer $TESLA_AUTH_TOKEN" -H 'Content-Type: application/json' \
     -X POST --data-binary @telemetry.json $BASE/api/1/vehicles/fleet_telemetry_config
```

Example responses:

```json
{"response":{"result":true,"reason":""}}
{"response":{"result":false,"reason":"car could not execute command: already_set"},"error":"","error_description":""}
{"response":null,"error":"vehicle unavailable: vehicle is offline or asleep","error_description":""}
```

---

## Using as a Library (`pkg/proxy`)

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
// On an internal network where a reverse proxy handles TLS termination:
http.ListenAndServe("127.0.0.1:8080", p)
```

`proxy.ExtractCommandAction(ctx, command, params)` converts an endpoint name and JSON parameters into a `func(*vehicle.Vehicle) error` without HTTP, so it can be reused in other server frameworks or message queues.

---

## Tests

| File | Contents |
|---|---|
| `pkg/proxy/proxy_test.go` | About 40 cases with fake `httpDoer`/`fetchVehicle`: routing, forwarding, 421 retry, VIN serialization, 422 fallback, telemetry signing, etc. (`TestServeHTTP*`, `TestForwardRequest*`, `TestVehicleCommand*`, `TestHandleFleetTelemetryConfig*`) |
| `pkg/proxy/command_test.go` | `TestExtractCommandAction` — per-endpoint parameter parsing |
| `pkg/proxy/status_test.go` | `TestHTTPStatusCode` (408/412/500 mapping, including wrapped errors), `TestWriteJSONErrorStatus` |
| `cmd/tesla-http-proxy/main_test.go` | `TestParseConfig` — environment variable/flag precedence |

```bash
go test ./pkg/proxy/... ./cmd/tesla-http-proxy/...
```

---

## Verification Checklist

- [ ] If an endpoint was added, the `switch` in `pkg/proxy/command.go`, `command_test.go`, and [05-command-catalog.md](05-command-catalog.md) were updated together.
- [ ] New error → HTTP code mappings are added to `httpStatusCode` with `errors.Is`, with cases (including wrapped errors) in `status_test.go`.
- [ ] Changing the response JSON format (`Response` struct, `carResponse`) breaks Fleet API client compatibility — do not change it.
- [ ] If `forwardRequest` was modified, `TestForwardRequest*` confirms that per-hop header removal, XFF, 421 retry, and body caps are preserved.
- [ ] The `-host` default `localhost`, mandatory TLS, and the TLS key reuse check were not removed.
- [ ] `go test ./pkg/proxy/... ./cmd/tesla-http-proxy/...` passes.
