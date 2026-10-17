# Manual for Agents (Entry Point)

This directory is a Markdown manual written to be read as context by AI coding agents (Claude Code, Codex, etc.).
The human-readable HTML version is at `../html/index.html`. It has two parts:

| Files | Part | Maintained by |
|---|---|---|
| `00`–`10` | **vehicle-command reference** (the Go original, the porting reference) | The user. Do not change without approval |
| `11-lib-*`–`19-lib-*` | **tesla-ble-kmp library manual** (this repository's Kotlin Multiplatform library) | Agents. Updated in every PR that changes library behaviour, public API or status; entry point `11-lib-overview.md` |

The rest of this page covers the vehicle-command reference. For the library, start at `11-lib-overview.md`.

Baseline: `github.com/teslamotors/vehicle-command` commit `a4b43c1` (after tag `v0.4.1`), Go 1.23.
The repository root is `../../vehicle-command/` relative to this document. All file paths below are relative to the repository root.

## Reading Order

1. `00-agent-guide.md` — read this first. Work rules, protocol invariants that must never be violated, and quick reference cards.
2. Depending on the task, read only the additional files you need from the table below. You do not need to read all of them.

## Reference Files by Task

| Task | Files to read |
|---|---|
| Understand the repository structure, decide which package to change | `01-architecture.md` |
| Modify BLE connect/scan/framing, replace with another BLE stack, debug connection failures | `02-ble-transport.md`, `08-errors.md` |
| Modify handshake, signing/encryption, counters, session cache logic | `03-protocol.md` (includes test vectors), `01-architecture.md` |
| Write a new application with the SDK (Go) | `04-go-api-reference.md`, `09-recipes.md`, `05-command-catalog.md` |
| Add a new vehicle command (Go method + CLI + proxy) | `05-command-catalog.md`, `04-go-api-reference.md`, "Adding a new command" in `09-recipes.md` |
| Change CLI tool options/environment variables | `06-cli-tools.md` |
| Modify the HTTP proxy, add endpoints, deploy | `07-http-proxy.md`, `05-command-catalog.md` |
| Error handling, retry policy, troubleshooting | `08-errors.md` |
| Implement a BLE client in another language (Python, Swift, Kotlin, Rust, etc.) | `10-porting-guide.md`, `02-ble-transport.md`, `03-protocol.md` |
| Use or change the tesla-ble-kmp library | `11-lib-overview.md`, then the page it names for the task |

## File List

| File | Contents |
|---|---|
| `00-agent-guide.md` | Work rules, invariants, quick reference cards (UUIDs, constants, environment variables, one-line command summaries) |
| `01-architecture.md` | Package layers (`connector` → `dispatcher` → `vehicle`), data flow, concurrency model, build/test |
| `02-ble-transport.md` | GATT UUIDs, advertisement name computation, 2-byte length framing, MTU/block splitting, scan/connect/retry, platform differences |
| `03-protocol.md` | RoutableMessage, domains, handshake, ECDH/KDF, metadata TLV, HMAC/AES-GCM, response decryption, session recovery, test vectors |
| `04-go-api-reference.md` | Public API of `pkg/protocol`, `pkg/connector`, `pkg/connector/ble`, `pkg/connector/inet`, `pkg/vehicle`, `pkg/cache`, `pkg/cli`, `pkg/account`, `pkg/proxy`, `pkg/sign` |
| `05-command-catalog.md` | Full command table: CLI name ↔ Go method ↔ proxy endpoint ↔ domain ↔ auth requirement ↔ arguments |
| `06-cli-tools.md` | Options and environment variables of tesla-keygen, tesla-control, tesla-auth-token, tesla-jws, tesla-http-proxy; keyring |
| `07-http-proxy.md` | Routing rules, response format, status codes, TLS, Docker, how to extend `pkg/proxy` |
| `08-errors.md` | `protocol.Error` interface, full MessageFault table, VCSEC/Infotainment application errors, retry policy |
| `09-recipes.md` | Code examples: BLE connection, session cache, key pairing, state queries, custom Connector, adding a new command, writing tests |
| `10-porting-guide.md` | Step-by-step checklist and verification methods for implementing a BLE client in another language |
| `11-lib-overview.md` … `19-lib-architecture.md` | tesla-ble-kmp library manual: overview, getting started, pairing, reading state, commands, errors, platform notes, troubleshooting, architecture |

## Original Repository Documents (Authoritative Sources)

- `README.md` — installation, proxy, key distribution
- `pkg/protocol/protocol.md` — original protocol specification. If it conflicts with `03-protocol.md` in this manual, the original takes precedence.
- `pkg/protocol/protobuf/*.proto` — message definitions. Final source for field numbers/enum values.
- `cmd/tesla-control/commands.go` — final source for the CLI command table.
- `pkg/proxy/command.go` — final source for the proxy endpoint table.
