# iOS 샘플

1. `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64`
2. `cd samples/ios && xcodegen generate`
3. `open TeslableSample.xcodeproj` 또는
   `xcodebuild -project TeslableSample.xcodeproj -scheme TeslableSample -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`

`.xcodeproj`는 생성물이라 커밋하지 않는다(`.gitignore`).
