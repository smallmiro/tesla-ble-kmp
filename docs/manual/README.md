# tesla-ble-kmp 사용 문서

앱 개발자를 위한 라이브러리 사용 문서입니다. 모든 링크는 이 파일 기준 상대경로입니다.

| 문서 | 내용 | 상태 |
|---|---|---|
| [getting-started.md](getting-started.md) | 설치, 권한 설정, 첫 페어링 | M3 |
| [pairing.md](pairing.md) | 키 생성·보관, add-key-request, 역할, 키 삭제 | M4 |
| [reading-state.md](reading-state.md) | 조회 API, 깨우기 정책 | M3, M5 |
| [commands.md](commands.md) | 제어 API 전체 표 | M4, M5 |
| [errors.md](errors.md) | 성공/실패/불확실, 에러 코드, 재시도 | M2, M5 |
| [platform-notes.md](platform-notes.md) | iOS/Android 제약, 백그라운드, 세션 캐시 위치 | M3 |
| [troubleshooting.md](troubleshooting.md) | 슬롯 초과, 세션 오류, 디버그 로그 | M5 |
| [architecture.md](architecture.md) | 기여자용 설계 요약 | M2, M6 |

## 지원 플랫폼

- Android 12 (API 31) 이상, iOS 16 이상
- 패키지 루트 `io.github.smallmiro.teslable`, 배포는 GitHub Packages + Swift Package Manager (M6)

## 설치

배포 전(M6에서 GitHub Packages + SPM). 지금은 소스에서 빌드: `./gradlew :sdk:assemble` (Android/JVM) 또는 `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64` (iOS 시뮬레이터 프레임워크).

## 현재 상태 (M0)

라이브러리 골격과 프로토콜 코어(TLV, 세션 키, AES-GCM, 프레이밍)가 있으며 `protocol.md` 테스트 벡터를 JVM과 iOS 시뮬레이터에서 통과합니다. BLE 연결과 명령 API는 M2~M5에서 추가됩니다.
