# samples/ios/AGENTS.md — iOS 샘플 지침

> [`samples/AGENTS.md`](../AGENTS.md) 와 루트 `AGENTS.md` 를 따릅니다. 여기는 iOS에만 해당하는 내용입니다.

## 프로젝트와 구조
- SwiftUI 앱, XcodeGen 프로젝트(`project.yml`). 스킴 `TeslableSample`(시뮬레이터), `TeslableSampleDevice`(실기기).
- `TeslableSample.xcodeproj` 와 `Sources/Info.plist` 는 `xcodegen generate` 생성물이라 **커밋하지 않습니다.** `Info.plist` 항목은 `project.yml` 의 `info:` 블록에서 고칩니다.
- `Local.xcconfig`(팀 ID)는 커밋하지 않습니다. 예시는 `Local.xcconfig.example`.
- 라이브러리는 `Teslable` 프레임워크(`:sdk`)로 들어옵니다. 현재 화면과 파일 구조: `{{SAMPLES_DOCS_DIR}}03-current-apps.md` §iOS.

## Swift 표면 규칙 (`17-lib-platform-notes.md`)
- SKIE 변환: `suspend` → `async throws`, `Flow` → `AsyncSequence`, sealed → `onEnum(of:)` 로 `switch`.
- Kotlin 기본 인자는 Swift 오버로드가 되지 않습니다. **모든 인자를 넘깁니다**(예: `vehicleStatus(timeout: nil)`).
- companion 멤버는 `.companion` 또는 `.shared` 로 접근합니다(예: `TeslaBle.companion.create`).
- 라이브러리 로그는 `TeslaLogger` 구현으로 받습니다. 화면·복사본에는 `observe` 줄만 내보내고 `TX:`/`RX:` 헥사는 통합 로그에만 둡니다.

## 빌드와 테스트
| 목적 | 명령 |
|---|---|
| 시뮬레이터 프레임워크 | `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` |
| 실기기 프레임워크 | `./gradlew :sdk:linkDebugFrameworkIosArm64` |
| 프로젝트 생성 | `cd samples/ios && xcodegen generate` |
| 빌드 + Swift 단위 테스트 | `xcodebuild -project TeslableSample.xcodeproj -scheme TeslableSample -destination 'platform=iOS Simulator,name=<기기>' CODE_SIGNING_ALLOWED=NO test` |
| 전체 게이트 | `tools/ci/verify.sh` |

- 시뮬레이터에는 블루투스가 없습니다. 스캔·연결·키체인 왕복은 실기기에서만 확인합니다.
- 실차 확인 절차(runbook)는 `samples/ios/README.md` 에 빌드 번호와 함께 둡니다.
