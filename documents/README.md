# Tesla vehicle-command Development Manual

Manual for building applications that send end-to-end authenticated commands to Tesla vehicles over BLE
and the internet, based on the `vehicle-command/` repository (Tesla Vehicle Command SDK, Go).

Baseline: commit `a4b43c1` after tag `v0.4.1` (Go 1.23, module `github.com/teslamotors/vehicle-command`).

## Two manuals

| Directory | Audience | Format | Language | Entry point |
|---|---|---|---|---|
| `html/` | People (developers, reviewers, operators) | HTML | Korean (`ko/`), English (`en/`), Japanese (`ja/`) | Open `html/index.html` in a browser (English home); switch language from the menu at the top right |
| `md/`   | AI coding agents (Claude Code, Codex, etc.) | Markdown | English only | `md/README.md` |

Both cover the same facts but serve different purposes.

- **HTML** is written for people: concepts, diagrams, step-by-step procedures and comparison tables.
  The three language editions have the same pages and the same content. When one language changes,
  the other two change in the same PR.
- **MD** is written for agents: exact API signatures, protocol invariants, file paths and verification
  checklists, read as context when an agent modifies code or implements a new client. Have the agent read
  `documents/md/README.md` first, then only the files needed for the task.

Language rules are AGENTS.md absolute rule 12.

## Layout

```
documents/
├── README.md                      # this file
├── html/                          # for people
│   ├── index.html                 # English home: copy of en/index.html, regenerate with tools/ci/sync-manual-root-index.sh
│   ├── assets/style.css           # shared by all languages
│   └── ko/ en/ ja/                # one folder per language, identical file names
│       ├── index.html             # home / document map
│       ├── 01-overview.html       # system overview and architecture
│       ├── 02-getting-started.html# install, key generation, pairing, first command
│       ├── 03-ble-transport.html  # BLE transport (UUIDs, framing, MTU, scanning)
│       ├── 04-protocol.html       # protocol (handshake, authentication, encryption, session recovery)
│       ├── 05-go-sdk.html         # Go SDK usage
│       ├── 06-cli-tools.html      # CLI tools
│       ├── 07-command-reference.html # command reference
│       ├── 08-http-proxy.html     # HTTP proxy
│       └── 09-errors-troubleshooting.html
└── md/                            # for AI agents (English)
    ├── README.md                  # entry point: reading order and per-task file list
    ├── 00-agent-guide.md          # working rules, invariants, quick reference card
    ├── 01-architecture.md         # package layout, layers, data flow
    ├── 02-ble-transport.md        # BLE details
    ├── 03-protocol.md             # protocol details (with test vectors)
    ├── 04-go-api-reference.md     # public API per package
    ├── 05-command-catalog.md      # command catalog (CLI / Go / Proxy / domain / auth)
    ├── 06-cli-tools.md            # CLI tools and environment variables
    ├── 07-http-proxy.md           # HTTP proxy
    ├── 08-errors.md               # error codes and retry policy
    ├── 09-recipes.md              # code recipes
    └── 10-porting-guide.md        # porting checklist for other languages/platforms
```

## Upstream references

- `vehicle-command/README.md`
- `vehicle-command/pkg/protocol/protocol.md` (protocol specification)
- `vehicle-command/cmd/tesla-control/README.md`
- `vehicle-command/pkg/protocol/protobuf/*.proto`
- https://pkg.go.dev/github.com/teslamotors/vehicle-command/pkg
- https://developer.tesla.com/docs/fleet-api

## Viewing the HTML

Open `html/index.html` directly in a browser (no external resources). To serve it locally:

```bash
cd documents && python3 -m http.server 8000
# http://localhost:8000/html/
```
