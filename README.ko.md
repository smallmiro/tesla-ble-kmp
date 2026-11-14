# tesla-ble-kmp

[English](README.md) | **한국어**

서버 없이 iPhone이나 Android 앱에서 Bluetooth LE로 테슬라와 통신합니다. Tesla 공식 Go SDK
[`teslamotors/vehicle-command`](https://github.com/teslamotors/vehicle-command)의 BLE 경로를 Kotlin Multiplatform으로 포팅한 라이브러리입니다.

라이선스: [Apache-2.0](LICENSE)

> 비공식 커뮤니티 프로젝트입니다. Tesla, Inc.와 제휴하지 않았으며, Tesla의 보증이나 지원을 받지 않습니다.

## 기능

- VIN으로 차량을 스캔하고 BLE로 연결합니다. Fleet API, OAuth, 서버, 텔레메트리가 없습니다. 라이브러리는 네트워크 호출을 하지 않습니다.
- 휴대폰 키를 보안 하드웨어(iOS Secure Enclave, Android StrongBox 또는 TEE)에 보관하며, 시뮬레이터와 에뮬레이터를 위한 소프트웨어 대체 경로가 있습니다. 키 카드로 차량에 등록합니다.
- 상태 읽기: 키 없이 쓰는 `vehicleStatus()`와, 인포테인먼트 12개 범주(배터리, 공조, 주행, 위치…)를 읽는 `getState()`.
- 명령: 잠금, 잠금 해제, 트렁크 등 VCSEC 명령과 인포테인먼트 명령 51개(공조, 충전, 창문, 미디어, 소프트웨어 업데이트…).
- 차량의 실시간 브로드캐스트(`vehicle.events`)와, 다음 실행에서 핸드셰이크를 건너뛰게 하는 세션 캐시.
- 모든 호출이 값(`Success`, `Uncertain`, `Failure`)을 돌려주므로, 앱은 "차량이 실행했을 수 있음"과 "실행되지 않음"을 구분할 수 있습니다.

## 상태

| 플랫폼 | 상태 |
|---|---|
| iOS | Swift Package Manager로 **사용 가능**, 버전 `1.0.0`(iOS 16 이상) |
| Android | **곧 제공 예정.** 개발은 끝났지만 실제 Android 기기 테스트가 아직 끝나지 않았습니다. Maven Central 릴리스를 기다려 주세요. 그때까지는 [소스에서 빌드](documents/md/12-lib-getting-started.md#android-and-jvm-build-from-source)할 수 있습니다 |

일부 기능은 가짜 차량(fake-vehicle) 테스트는 통과했지만 아직 실제 차량에서 확인하지 못했습니다. 특히 인포테인먼트 명령과 `getState()`가 그렇습니다. 체크리스트는 이슈 [#118](https://github.com/smallmiro/tesla-ble-kmp/issues/118)에 있습니다. 영역별 상태는 [`documents/md/11-lib-overview.md`](documents/md/11-lib-overview.md)의 "Status" 표에 있습니다.

## 설치 (iOS)

Xcode에서 **File > Add Package Dependencies…**를 선택하고

```
https://github.com/smallmiro/tesla-ble-kmp
```

를 입력한 뒤, 의존성 규칙으로 `1.0.0`부터 **Up to Next Major Version**을 고르고 앱 타깃에 `Teslable` 프로덕트를 추가합니다.

`Package.swift`에서는 다음과 같이 씁니다.

```swift
dependencies: [
    .package(url: "https://github.com/smallmiro/tesla-ble-kmp", from: "1.0.0"),
],
targets: [
    .target(name: "MyApp", dependencies: [.product(name: "Teslable", package: "tesla-ble-kmp")]),
]
```

요구사항:

- iOS 16 이상.
- `Info.plist`의 `NSBluetoothAlwaysUsageDescription`. 첫 스캔에서 블루투스 권한 요청 창이 표시됩니다.
- 정적 프레임워크이며, 기기용 arm64 슬라이스와 시뮬레이터용 arm64 슬라이스가 있습니다. 시뮬레이터는 Apple Silicon Mac에서 쓰세요. 모든 아키텍처를 빌드하는 시뮬레이터 빌드(예: Release)에는 `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64`를 설정합니다.
- 시뮬레이터에는 블루투스가 없으므로 스캔과 연결에는 실제 iPhone이 필요합니다. 시뮬레이터에서 키 저장소는 서명된 앱에서 동작합니다(ad-hoc 서명이면 충분).

CocoaPods는 지원하지 않습니다(CocoaPods trunk는 2026-12-02부터 읽기 전용입니다).

더 보기: [설치](documents/md/11-lib-overview.md#installation),
[플랫폼 노트](documents/md/17-lib-platform-notes.md#ios).

## 빠른 시작 (Swift)

Swift에서는 Kotlin의 기본 인자가 생기지 않으므로 `timeout: nil`과 `options: ConnectOptions(timeout: nil)`을 포함해 모든 인자를 넘깁니다. SKIE가 `suspend` 함수를 `async throws`로, `Flow`를 `AsyncSequence`로, sealed 결과를 `switch onEnum(of:)`용 enum으로 바꿔 줍니다. 아래는 [시작하기](documents/md/12-lib-getting-started.md#swift-ios)의 예제입니다.

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

`DebugLogger()`는 개발 빌드 전용입니다. 다음으로: 새 키 페어링(`documents/md/13-lib-pairing.md`), 명령(`documents/md/15-lib-commands.md`), 오류 처리(`documents/md/16-lib-errors.md`).

## Android

**곧 제공 예정.** 개발은 끝났지만 실제 Android 기기 테스트가 아직 끝나지 않아 Android 패키지는 아직 없습니다. Maven Central 릴리스를 기다려 주세요. 그때까지는 이 저장소의 Gradle 빌드 안에서 소스로 라이브러리를 빌드하세요: [시작하기, "Android and JVM: build from source"](documents/md/12-lib-getting-started.md#android-and-jvm-build-from-source).

## 문서

| 문서 | 용도 |
|---|---|
| [`documents/md/11-lib-overview.md`](documents/md/11-lib-overview.md) | 라이브러리 매뉴얼 진입점(Markdown, 영어): 상태, 설치, 어떤 페이지를 읽을지 |
| [`documents/html/ko/11-lib-overview.html`](documents/html/ko/11-lib-overview.html) ([English](documents/html/en/11-lib-overview.html), [日本語](documents/html/ja/11-lib-overview.html)) | 같은 매뉴얼의 사람용 정적 HTML 페이지: 클론한 저장소에서 브라우저로 여세요 |
| [`documents/html/ko/11-lib-user-guide.html`](documents/html/ko/11-lib-user-guide.html) ([English](documents/html/en/11-lib-user-guide.html), [日本語](documents/html/ja/11-lib-user-guide.html)), [`documents/md/11-lib-user-guide.md`](documents/md/11-lib-user-guide.md) | 오프라인 한 페이지로 된 전체 사용자 가이드: 설정, 매개변수와 예제가 있는 모든 메서드, 결과 타입, 모든 `getState` 스키마 |
| [`documents/md/12-lib-getting-started.md`](documents/md/12-lib-getting-started.md) | 라이브러리 추가, Kotlin과 Swift로 보는 전체 흐름 |
| [`tools/release/README.md`](tools/release/README.md) | 버전을 릴리스하는 방법(관리자용) |

## 기여

기여는 [`AGENTS.md`](AGENTS.md)를 따릅니다(한국어로 작성되어 있으며, 사람과 코딩 에이전트를 위한 규칙집입니다).

- 실패하는 테스트를 먼저 작성합니다. 구조 변경과 동작 변경은 별도 커밋으로 둡니다(`struct:`를 `feat:`보다 먼저). `main`은 항상 Green입니다.
- 푸시하거나 병합하기 전에 Mac에서 [`tools/ci/verify.sh`](tools/ci/verify.sh)를 실행하세요. 이것이 로컬 게이트이며 CI를 대신합니다(ADR-0012). [풀 리퀘스트 템플릿](.github/pull_request_template.md)을 채우세요. 풀 리퀘스트에는 통과한 `verify.sh`, AI 코드 리뷰, 갱신된 라이브러리 문서가 필요하며, rebase merge로 병합합니다(squash 금지).
- 프로토콜 동작의 기준은 커밋 `a4b43c1`의 `vehicle-command`와 그 `pkg/protocol/protocol.md`입니다.
- AGPL 라이선스 구현(예: `yoziru/tesla-ble`)의 코드는 읽거나 복사하지 마세요.
- 모든 경로는 [`docs/PATHS.md`](docs/PATHS.md)에 한 번만 정의하며, 문서는 경로를 `{{KEY}}`로 참조합니다.

모듈: `:domain`(프로토콜 모델과 포트, 순수 Kotlin), `:application`(디스패처, 세션), `:adapter-ble`
(Kable), `:adapter-crypto`(Android Keystore, iOS Security 프레임워크), `:adapter-storage`(세션 캐시), `:sdk`
(공개 파사드, Swift는 SKIE), `:testing`(테스트 픽스처). 샘플 앱: `samples/android`(Compose)와
`samples/ios`(SwiftUI, XcodeGen). 패키지 루트는 `dev.mirolab.teslable`입니다.

| 문서 | 내용 |
|---|---|
| [`HANDOFF.md`](HANDOFF.md) | 프로젝트 개요: 결정, 범위, 프로토콜 사실, 마일스톤 |
| [`docs/prd/PRD.md`](docs/prd/PRD.md) | 요구사항(FR/NFR, 릴리스 계획) |
| [`docs/sdd/SDD.md`](docs/sdd/SDD.md), [`docs/adr/`](docs/adr/) | 설계와 아키텍처 결정 기록 |
| [`docs/workflow.md`](docs/workflow.md) | 개발 표준: TDD, Tidy First, 브랜치, 검증 게이트, 완료 정의 |
| [`documents/md/README.md`](documents/md/README.md) | 에이전트용 개발 매뉴얼: Go `vehicle-command` 참고 문서(`00`–`10`)와 이 라이브러리의 매뉴얼(`11`–`19`) |

`docs/`의 기획·설계 문서는 한국어로 작성되어 있으며, 라이브러리 매뉴얼은 영어, 한국어, 일본어판이 있습니다.

## 라이선스

[Apache License 2.0](LICENSE). 이 프로젝트는 Tesla의 `vehicle-command`(Apache-2.0)의 코드를 포팅했습니다. 저작자 표기는 [`NOTICE`](NOTICE)를 참고하세요.
