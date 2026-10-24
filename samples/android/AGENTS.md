# samples/android/AGENTS.md — Android 샘플 지침

> [`samples/AGENTS.md`](../AGENTS.md) 와 루트 `AGENTS.md` 를 따릅니다. 여기는 Android에만 해당하는 내용입니다.

## 모듈과 구조
- Gradle 모듈 `:samples:android`, 패키지·applicationId `dev.mirolab.teslable.sample`, `minSdk` 31, Jetpack Compose(Material 3).
- 의존성은 `project(":sdk")` 와 Compose·코루틴뿐입니다. 라이브러리 내부 모듈 의존을 추가하지 않습니다(Gradle 경계, 루트 절대 규칙 4).
- 현재 화면과 상태 구조: `{{SAMPLES_DOCS_DIR}}03-current-apps.md` §Android.

## 플랫폼 규칙
- 권한: `BLUETOOTH_SCAN`(`neverForLocation`), `BLUETOOTH_CONNECT` 를 선언하고 **런타임에 요청**합니다. 없으면 라이브러리가 `TransportError.PermissionDenied` 를 돌려줍니다(`17-lib-platform-notes.md`).
- `INTERNET` 권한을 추가하지 않습니다(PRD NFR-014, 게이트가 병합된 매니페스트를 검사).
- `android:allowBackup="false"` 를 유지합니다.
- `PlatformContext(context)` 에는 application context를 넘깁니다.

## 빌드와 테스트
| 목적 | 명령 |
|---|---|
| 빌드 | `./gradlew :samples:android:assembleDebug` |
| 단위 테스트 | `./gradlew :samples:android:testDebugUnitTest` (소스 세트 `src/test/` 는 첫 테스트와 함께 만듭니다) |
| 전체 게이트 | `tools/ci/verify.sh` |

- 실기기 확인은 체크리스트로 하고 결과에 VIN·공개키 전문을 적지 않습니다.
