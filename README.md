# tesla-ble-kmp

**English** | [한국어](README.ko.md)

Talk to a Tesla over Bluetooth LE from an iPhone or Android app, with no server: a Kotlin Multiplatform port of the BLE
path of Tesla's Go SDK [`teslamotors/vehicle-command`](https://github.com/teslamotors/vehicle-command).

License: [Apache-2.0](LICENSE)

> Unofficial community project. Not affiliated with, endorsed by or supported by Tesla, Inc.

## Features

- Scan for a vehicle by VIN and connect over BLE. No Fleet API, no OAuth, no server, no telemetry: the library makes
  no network calls.
- Phone key in secure hardware (iOS Secure Enclave, Android StrongBox or TEE), with a software fallback for simulators
  and emulators. Enroll it on the car with the key card.
- Read state: `vehicleStatus()` without a key, and `getState()` for 12 Infotainment categories (battery, climate,
  drive, location…).
- Commands: lock, unlock, trunk and the other VCSEC commands, and 51 Infotainment commands (climate, charging, windows,
  media, software update…).
- Live broadcasts from the car (`vehicle.events`), and a session cache so the next launch skips the handshake.
- Every call returns a value (`Success`, `Uncertain` or `Failure`), so an app can tell "the car may have done it"
  from "it did not happen".

## Status

| Platform | Status |
|---|---|
| iOS | **Available** through Swift Package Manager, version `1.0.0` (iOS 16 or later) |
| Android | **Coming soon.** Development is complete, but testing on real Android devices is not finished yet. Please wait for the Maven Central release. Until then you can [build from source](documents/md/12-lib-getting-started.md#android-and-jvm-build-from-source) |

Some features pass the fake-vehicle tests but are not confirmed on a real car yet, notably the Infotainment commands
and `getState()`: the checklist is issue [#118](https://github.com/smallmiro/tesla-ble-kmp/issues/118). The state of
every area is in the "Status" table of [`documents/md/11-lib-overview.md`](documents/md/11-lib-overview.md).

## Installation (iOS)

In Xcode choose **File > Add Package Dependencies…**, enter

```
https://github.com/smallmiro/tesla-ble-kmp
```

pick the dependency rule **Up to Next Major Version** from `1.0.0`, and add the `Teslable` product to your app target.

In a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/smallmiro/tesla-ble-kmp", from: "1.0.0"),
],
targets: [
    .target(name: "MyApp", dependencies: [.product(name: "Teslable", package: "tesla-ble-kmp")]),
]
```

Requirements:

- iOS 16 or later.
- `NSBluetoothAlwaysUsageDescription` in `Info.plist`. The first scan shows the Bluetooth permission prompt.
- A static framework, with an arm64 slice for devices and an arm64 slice for the simulator. Use the simulator on an
  Apple Silicon Mac; for a simulator build of every architecture (for example Release) set
  `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64`.
- The simulator has no Bluetooth: scanning and connecting need a real iPhone. On the simulator the key store works in a
  signed app (ad-hoc signing is enough).

CocoaPods is not supported (the CocoaPods trunk is read-only from 2026-12-02).

More: [Installation](documents/md/11-lib-overview.md#installation) and
[platform notes](documents/md/17-lib-platform-notes.md#ios).

## Quick start (Swift)

Swift gets no default arguments from Kotlin, so every argument is passed, including `timeout: nil` and
`options: ConnectOptions(timeout: nil)`. SKIE turns `suspend` functions into `async throws`, `Flow` into
`AsyncSequence`, and sealed results into enums for `switch onEnum(of:)`. This is the example from
[Getting started](documents/md/12-lib-getting-started.md#swift-ios):

```swift
import Teslable

let ble = TeslaBle.companion.create(
    platform: PlatformContext(),
    config: TeslaBleConfig.companion.default(logger: DebugLogger())
)

func run(vinText: String) async {
    guard let vin = Vin.companion.parseOrNull(value: vinText) else { return }   // bad format

    // 1) Key
    var key: VehicleKey?
    do {
        let result = try await ble.keys.getOrCreate(alias: VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS, policy: .preferHardware)
        switch onEnum(of: result) {
        case .success(let s): key = s.value as? VehicleKey
        case .uncertain(let u): print("uncertain: \(u.error.message)")
        case .failure(let f): print("failure: \(f.error.message)")
        }
    } catch { print("error: \(error)") }

    // 2) Scan (stop at the first advertisement; set a time limit by cancelling the Task)
    for await event in ble.scan(vin: vin) {
        switch onEnum(of: event) {
        case .found(let f): print("\(f.advertisement.localName.value) rssi=\(f.advertisement.rssi)")
        case .failed(let e): print("scan failed: \(e.error.message)")
        }
        break
    }

    // 3) Connect and query
    do {
        let connected = try await ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
        switch onEnum(of: connected) {
        case .success(let s):
            guard let vehicle = s.value as? Vehicle else { return }
            let status = try await vehicle.vehicleStatus(timeout: nil)
            switch onEnum(of: status) {
            case .success(let v): print("status: \(String(describing: v.value))")
            case .uncertain(let u): print("uncertain: \(u.error.message)")   // query again, do not resend
            case .failure(let f): print("failure: \(f.error.message)")
            }
            try await vehicle.disconnect()
        case .uncertain(let u): print("uncertain: \(u.error.message)")
        case .failure(let f): print("failure: \(f.error.message)")
        }
    } catch { print("error: \(error)") }
}
```

`DebugLogger()` is for development builds only. Next: pairing a new key (`documents/md/13-lib-pairing.md`), commands
(`documents/md/15-lib-commands.md`) and error handling (`documents/md/16-lib-errors.md`).

## Android

**Coming soon.** Development is complete, but testing on real Android devices is not finished yet, so there is no
Android package yet. Please wait for the Maven Central release. Until then, build the library from source inside this
repository's Gradle build: [Getting started, "Android and JVM: build from source"](documents/md/12-lib-getting-started.md#android-and-jvm-build-from-source).

## Documentation

| Document | For |
|---|---|
| [`documents/md/11-lib-overview.md`](documents/md/11-lib-overview.md) | Library manual, entry point (Markdown, English): status, installation, which page to read |
| [`documents/html/en/11-lib-overview.html`](documents/html/en/11-lib-overview.html) ([한국어](documents/html/ko/11-lib-overview.html), [日本語](documents/html/ja/11-lib-overview.html)) | The same manual for people, as static HTML pages: open them in a browser from a clone |
| [`documents/html/en/11-lib-user-guide.html`](documents/html/en/11-lib-user-guide.html) ([한국어](documents/html/ko/11-lib-user-guide.html), [日本語](documents/html/ja/11-lib-user-guide.html)), [`documents/md/11-lib-user-guide.md`](documents/md/11-lib-user-guide.md) | The complete user guide in one offline page: setup, every method with parameters and examples, result types, all `getState` schemas |
| [`documents/md/12-lib-getting-started.md`](documents/md/12-lib-getting-started.md) | Adding the library, the whole flow in Kotlin and Swift |
| [`tools/release/README.md`](tools/release/README.md) | How a version is released (maintainers) |

## Contributing

Contributions follow [`AGENTS.md`](AGENTS.md) (written in Korean; it is the rule book for people and coding agents):

- Write the failing test first. Keep structural changes and behaviour changes in separate commits (`struct:` before
  `feat:`). `main` is always green.
- Run [`tools/ci/verify.sh`](tools/ci/verify.sh) on a Mac before you push or merge: it is the local gate and replaces CI
  (ADR-0012). Fill in the [pull request template](.github/pull_request_template.md); a pull request needs a passing
  `verify.sh`, an AI code review and updated library docs, and is merged with a rebase merge (no squash).
- The source of truth for protocol behaviour is `vehicle-command` at commit `a4b43c1` and its `pkg/protocol/protocol.md`.
- Do not read or copy code from AGPL-licensed implementations (for example `yoziru/tesla-ble`).
- Every path is defined once in [`docs/PATHS.md`](docs/PATHS.md); documents refer to paths by `{{KEY}}`.

Modules: `:domain` (protocol model and ports, pure Kotlin), `:application` (dispatcher, sessions), `:adapter-ble`
(Kable), `:adapter-crypto` (Android Keystore, iOS Security framework), `:adapter-storage` (session cache), `:sdk`
(public facade; Swift through SKIE), `:testing` (test fixtures). Sample apps: `samples/android` (Compose) and
`samples/ios` (SwiftUI, XcodeGen). Package root `dev.mirolab.teslable`.

| Document | Contents |
|---|---|
| [`HANDOFF.md`](HANDOFF.md) | Project brief: decisions, scope, protocol facts, milestones |
| [`docs/prd/PRD.md`](docs/prd/PRD.md) | Requirements (FR/NFR, release plan) |
| [`docs/sdd/SDD.md`](docs/sdd/SDD.md), [`docs/adr/`](docs/adr/) | Design and architecture decision records |
| [`docs/workflow.md`](docs/workflow.md) | Development standard: TDD, Tidy First, branches, verification gate, definition of done |
| [`documents/md/README.md`](documents/md/README.md) | Development manual for agents: the Go `vehicle-command` reference (`00`–`10`) and this library's manual (`11`–`19`) |

The planning and design documents in `docs/` are written in Korean; the library manual has English, Korean and
Japanese editions.

## License

[Apache License 2.0](LICENSE). This project ports code from Tesla's `vehicle-command` (Apache-2.0); see
[`NOTICE`](NOTICE) for the attribution.
