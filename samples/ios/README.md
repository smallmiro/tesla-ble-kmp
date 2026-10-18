# iOS 샘플

1. `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64`
2. `cd samples/ios && xcodegen generate`
3. `open TeslableSample.xcodeproj` 또는
   `xcodebuild -project TeslableSample.xcodeproj -scheme TeslableSample -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`

`.xcodeproj`는 생성물이라 커밋하지 않는다(`.gitignore`).
`Sources/Info.plist`도 `xcodegen generate`가 `project.yml`로부터 매번 새로 생성하므로 커밋하지 않는다.

## 실기기 실행

1. `./gradlew :sdk:linkDebugFrameworkIosArm64`
2. `cp Local.xcconfig.example Local.xcconfig` 후 `DEVELOPMENT_TEAM`에 팀 ID 입력(커밋 금지)
3. `xcodegen generate`
4. Xcode에서 `TeslableSampleDevice` 스킴을 iPhone에 실행
5. 차량 근처에서 VIN 입력 후 스파이크 확인이 필요하면 "BLE 진단 (스파이크)" 화면에서 Probe, 로그 전체를 복사해 전달(로그에는 VIN 원문이 없다 — 로컬 이름만 남는다)
6. 샘플 화면으로 확인(체크리스트, 결과에 VIN·공개키 전문은 적지 않는다). 이 폰의 키는 아직 차량에 등록되어 있지 않으므로 인증이 필요한 동작은 대상이 아니다.
   - 기록은 "결과" 칸의 **결과 전체 복사** 버튼으로 앱 정보·연결 상태·키·시각별 결과를 한 번에 클립보드로 복사해 전달한다(VIN은 포함되지 않는다). **결과 지우기**로 다음 확인을 새로 시작한다.
   1. 키 만들기: `secureEnclave` + 공개키 지문, 앱을 다시 실행해도 같은 지문
   2. 스캔(차량 근처): `S…C` 광고, rssi, connectable=true
   3. 연결: "연결됨" (실패하면 오류 문구)
   4. VehicleStatus: 잠금·문 상태가 실제와 같음(문 하나 열고 다시)
   5. 키 목록: 차량 화면(잠금 → 키)의 키 수와 같음, 역할·형태 표시
   6. 연결 끊기 후 다시 연결: 둘 다 성공
   7. 블루투스를 끄고 스캔: `bluetooth is powered off`
   8. 설정에서 블루투스 권한을 거부하고 스캔: `bluetooth permission not granted` (크래시 없음)
   9. (선택) 명령 중 차량에서 멀어짐: 불확실(시간 초과) 뒤 `vehicle not connected`
