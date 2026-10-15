# iOS 샘플

1. `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64`
2. `cd samples/ios && xcodegen generate`
3. `open TeslableSample.xcodeproj` 또는
   `xcodebuild -project TeslableSample.xcodeproj -scheme TeslableSample -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`

`.xcodeproj`는 생성물이라 커밋하지 않는다(`.gitignore`).
`Sources/Info.plist`도 `xcodegen generate`가 `project.yml`로부터 매번 새로 생성하므로 커밋하지 않는다.

## 실기기 실행 (ADR-0003 스파이크)

1. `./gradlew :sdk:linkDebugFrameworkIosArm64`
2. `cp Local.xcconfig.example Local.xcconfig` 후 `DEVELOPMENT_TEAM`에 팀 ID 입력(커밋 금지)
3. `xcodegen generate`
4. Xcode에서 `TeslableSampleDevice` 스킴을 iPhone에 실행
5. 차량 근처에서 VIN 입력 후 Probe, 로그 전체를 복사해 전달(로그에는 VIN 원문이 없다 — 로컬 이름만 남는다)
