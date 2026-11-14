# ADR-0017: iOS는 SPM으로 먼저 배포하고 Android는 나중에 Maven Central (PRD D13 개정)

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-11-14, 사용자 결정 "SPM으로", "1.0.0으로 시작", Android는 Maven Central(나중), CocoaPods 미지원. 릴리스 흐름과 검증 방법은 Claude·Codex 협의) |
| 관련 | `{{PRD_FILE}}` D13(이 ADR이 개정), §3.2, §9 M6, `{{SDD_FILE}}` §8.5, ADR-0005(SKIE), ADR-0012(로컬 검증 게이트), ADR-0013(패키지 루트 `dev.mirolab`), `{{PLANS_DIR}}2026-11-14-m6-release.md`, `{{RELEASE_TOOLS_DIR}}README.md` |

## 맥락
- PRD D13(2026-09-26)은 배포 채널을 GitHub Packages(Maven 레지스트리) + SPM으로 정했다. GitHub Packages의 Maven 레지스트리는 **공개 패키지도 내려받을 때 인증이 필요**하다. 앱 개발자마다 `read:packages` 권한의 GitHub PAT를 만들어 Gradle 설정과 CI에 넣어야 해서, 오픈소스 라이브러리를 처음 써 보는 사람에게 큰 장벽이다.
- CocoaPods trunk는 2026-12-02부터 읽기 전용이 된다(새 포드와 새 버전을 올릴 수 없다).
- Android는 개발을 마쳤지만 실기기 확인을 하지 않았다(`{{LIB_DOCS_INDEX}}` 상태 표, M3 행). iOS는 실차 확인을 시작했다(2026-10-18, M3). 그 밖의 여러 기능은 FakeVehicle 시험만 통과했고 실차 확인을 기다린다(#118).
- 릴리스용 CI가 없다(ADR-0012). `main`은 보호 브랜치라 스크립트가 `main`에 커밋을 직접 올릴 수 없다.
- SPM `binaryTarget(url:checksum:)`은 zip의 체크섬을 `Package.swift`에 적어야 하고, 내려받은 zip의 체크섬이 다르면 SPM이 설치를 거부한다. 릴리스 자산 URL은 태그 이름으로 정해지므로 태그를 만들기 전에 체크섬을 적어 둘 수 있다.
- Kotlin/Native 프레임워크를 다른 Mac의 Xcode에서 쓰려면 SKIE가 만든 Swift 코드가 library evolution(`.swiftinterface`)을 갖고 빌드한 Mac의 경로에 기대지 않아야 한다.

## 결정
1. **1단계(이번): iOS만 Swift Package Manager로 배포한다.**
   - 패키지 URL은 저장소 URL과 같다(`{{SPM_PACKAGE_URL}}`). 저장소 루트 `Package.swift`: `// swift-tools-version:5.9`, `name: "Teslable"`, `platforms: [.iOS(.v16)]`, product·target `Teslable`, `.binaryTarget(name: "Teslable", url: "{{SPM_PACKAGE_URL}}/releases/download/v<버전>/Teslable.xcframework.zip", checksum: "<sha256>")`. 이 파일은 `prepare.sh`만 쓰고 손으로 고치지 않는다.
   - XCFramework: 이름 `Teslable`, **static**, 슬라이스는 정확히 `ios-arm64` + `ios-arm64-simulator`(시뮬레이터는 arm64만, Apple Silicon Mac 전용), 각 슬라이스 `MinimumOSVersion` 16.0. SKIE `produceDistributableFramework()`(Swift library evolution, clang 모듈 breadcrumb 제거). 빌드한 Mac의 절대 경로(사용자 이름, 폴더 구조)는 바이너리에 남기지 않는다(`-Xdebug-prefix-map`으로 저장소 루트를 가짜 절대 경로 `/teslable`로, SKIE `enableRelativeSourcePathsInDebugSymbols` 끔, `.swiftsourceinfo` 제외). SKIE 사용 통계 수집과 업로드는 끈다(`{{AGENTS_FILE}}` §3 규칙 6, 텔레메트리 금지).
   - zip은 `ditto -c -k --keepParent`(압축 루트에 `Teslable.xcframework/`), 이름 `Teslable.xcframework.zip`, 체크섬은 `swift package compute-checksum`. **체크섬을 낸 zip을 그대로 올린다.** 준비 뒤에 zip을 다시 만들지 않는다.
2. **Android/JVM은 2단계에서 Maven Central로 배포한다.** 그룹 네임스페이스는 `dev.mirolab`(`mirolab.dev` DNS TXT로 인증, ADR-0013). 별도 계획으로 진행하며, 합의한 내용은 `{{PLANS_DIR}}2026-11-14-m6-release.md` "2단계"에 있다. 그때까지 Android는 소스에서 빌드한다. **GitHub Packages는 쓰지 않는다.**
3. **CocoaPods는 지원하지 않는다.**
4. **릴리스는 두 단계다.** 스크립트는 `{{RELEASE_TOOLS_DIR}}`에 있고 GitHub Actions 없이 Apple Silicon Mac 한 대에서 돈다(ADR-0012).
   - **준비**(`prepare.sh <버전>`): 최신 `origin/main`에서 `release/v<버전>` 브랜치를 만들고, `check-consumer-ios.sh`로 XCFramework를 빌드하고 격리된 소비자 앱을 검사한 뒤, zip과 체크섬과 빌드한 소스 트리를 `build/release/v<버전>/`에 보관하고, 그 체크섬으로 `Package.swift`를 써서 커밋 하나를 만든다. 푸시와 PR 생성은 사람이 한다.
   - **배포**(`publish.sh <버전>`, PR이 머지된 `main`에서): zip을 만들지 않는다. `Package.swift`·보관한 zip의 체크섬·소스 트리를 대조하고, `tools/ci/verify.sh`를 돌리고, 태그 `v<버전>`만 푸시한다(`main`은 푸시하지 않음). GitHub Release는 초안으로 만들어 zip을 올리고 다시 내려받아 체크섬을 확인한 뒤 정식 릴리스(prerelease 아님)로 게시한다. 게시 뒤 `Package.swift`의 URL에서 zip을 받아 체크섬을 다시 보고, 게시된 패키지로 소비자 앱을 검사한다(`check-consumer-ios.sh --remote <버전>`). 단계마다 이미 끝난 일을 확인해 건너뛰므로 실패한 뒤 같은 명령으로 다시 실행할 수 있다.
   - 격리된 소비자 앱(`{{RELEASE_TOOLS_DIR}}consumer-ios/`)은 SwiftPM으로 링크해 기기용으로 빌드하고(`generic/platform=iOS`), 기기용 Release 아카이브의 앱 dSYM에 Kotlin 디버그 정보가 있는지 보고, 시뮬레이터에서 시험 4개(SKIE async, Flow, sealed `switch`, 키 생성)를 돈다.
5. **버전은 SemVer, 첫 버전은 `1.0.0`**(태그 `v1.0.0`). 실차 확인 대기 항목(#118, Android 실기기)은 릴리스 노트와 문서에 그대로 적는다. 공개 API 호환성을 깨는 변경은 다음 메이저 버전(`2.0.0`)으로 낸다.
6. 샘플 앱은 계속 소스 빌드의 로컬 프레임워크(`sdk/build/bin/<target>/debugFramework`)를 쓴다. SPM으로 바꾸지 않는다.

## 결과
- iOS 앱 개발자는 토큰 없이 Xcode의 Add Package Dependencies에 `{{SPM_PACKAGE_URL}}`을 넣고 "Up to Next Major Version" `1.0.0`으로 받아 `import Teslable`로 쓴다(`{{LIB_DOCS_INDEX}}` §Installation).
- Android 앱 개발자는 2단계 전까지 소스에서 빌드한다. README와 사용 문서에 "개발은 끝났고 실기기 확인이 남았다, 기다려 달라"고 적는다.
- 시뮬레이터 슬라이스가 arm64뿐이라 Intel Mac에서는 시뮬레이터로 쓸 수 없고, 모든 아키텍처를 빌드하는 시뮬레이터 빌드(예: Release)는 `x86_64`를 빼야 링크된다(`17-lib-platform-notes.md` §iOS).
- 시뮬레이터에서 키 저장소(Keychain)를 쓰려면 앱이 서명되어 있어야 한다(ad-hoc 서명으로 충분). 소비자 앱 검사에서 확인했다(`17-lib-platform-notes.md` §Key storage).
- 별도 dSYM은 배포하지 않는다. Kotlin 디버그 정보는 정적 라이브러리 안에 있다가 앱 dSYM으로 들어가므로, 크래시 리포트의 Kotlin 프레임은 앱 dSYM만으로 파일:줄까지 심볼화된다. Xcode는 앱에 빈 stub `Teslable.framework`(약 33 KB)를 넣는다. 정적 프레임워크에 대한 Xcode의 의도된 동작이고 코드는 앱 바이너리에 링크된다. App Store 통과는 예상되지만 아직 확인하지 않았다(`17-lib-platform-notes.md` §iOS).
- 두 단계를 같은 Mac에서 해야 한다. 체크섬을 낸 zip은 그 Mac의 `build/release/v<버전>/`에만 있다. 다른 Mac에서는 `prepare.sh`부터 다시 한다.
- `1.0.0`으로 시작하므로 그 뒤 공개 API 호환성이 깨지면 메이저 버전을 올린다(`apiCheck`가 감지한다).
- `{{PATHS_FILE}}`에서 `PACKAGES_MAVEN_URL`을 지우고 `SPM_PACKAGE_URL`, `RELEASE_TOOLS_DIR`를 더했다. `tools/ci/verify.sh`는 9번째 단계로 릴리스 스크립트를 오프라인으로 시험한다(ADR-0012).

## 대안
- **GitHub Packages(Maven) + SPM 유지(D13 원안)**: 공개 패키지도 소비자 PAT가 필요하다. 기각.
- **Android Maven Central을 이번에 함께 배포**: Central 계정, 네임스페이스 DNS 인증, GPG 키 준비와 Android 실기기 확인이 남아 있다. 사용자 결정으로 2단계로 미룸.
- **CocoaPods**: trunk가 2026-12-02부터 읽기 전용이다. 기각.
- **스크립트 하나가 커밋·태그·푸시·배포**: 보호된 `main`과 충돌하고 릴리스 커밋이 머지되기 전에 배포된다. 기각, 준비와 배포를 나눈다.
- **`0.1.0` 프리릴리스로 시작**: 실차 확인 전이라 Claude가 권했다. 사용자 결정으로 `1.0.0`. 대신 확인 대기 항목을 릴리스 노트와 문서에 적는다.
- **`swift build`로 검증**: iOS 전용 바이너리라 부족하다. Xcode 앱으로 기기·시뮬레이터 빌드를 하고 실제로 호출한다.
- **GitHub Actions 릴리스 워크플로**: CI를 쓰지 않는다(ADR-0012). 기각.

## 협의 기록
Claude·Codex 협의(2026-11-14, main `a1f9afb`, 계획 "결정 기록").

| 항목 | Claude | Codex | 최종 |
|---|---|---|---|
| 배포 채널 | Android Maven Central + iOS SPM | Maven 부분 동의 | 사용자 결정으로 1단계는 iOS SPM만, Android Maven Central은 2단계 |
| SKIE | 기본 설정 | `produceDistributableFramework()` 필수(library evolution, 기기별 디버그 경로 제거) | 채택 |
| SPM 위치 | 같은 저장소 루트 | 동의. 릴리스 URL이 결정적이라 체크섬을 먼저 적을 수 있다. 체크섬 낸 zip을 그대로 올린다 | 채택 |
| 릴리스 흐름 | 스크립트 하나가 커밋·태그·푸시·배포 | 보호된 `main`과 충돌. 준비(PR)와 배포(머지 뒤)를 나누고 다시 실행할 수 있게 | Codex 채택 |
| 검증 | `swift build` | 부족. Xcode 앱으로 시뮬레이터·기기 빌드, SKIE async/Flow/sealed와 키 생성 경로를 실제 호출, 배포 뒤 원격 zip·체크섬 재확인 | 채택 |
| 버전 | `0.1.0` 프리릴리스 | 0.x 합리적 | 사용자 결정 `1.0.0` |

구현 리뷰에서 더한 것(2026-11-14): SKIE 사용 통계 끔, 빌드한 Mac의 경로 제거(SKIE relative source paths 끔), 준비한 zip을 버전별 폴더에 보관, GitHub Release를 초안으로 만들어 자산을 확인한 뒤 게시, 준비 뒤 소스가 바뀌면 `publish.sh`가 거부, 시뮬레이터 시험만 ad-hoc 서명(Keychain). 남은 이견 없음.

조사에서 더한 것(2026-11-14, Claude·Codex 합의, 계획 Task 1b): 배포 링크의 저장소 루트 매핑을 상대 경로 `.`에서 가짜 절대 경로 `/teslable`로 바꿨다. `.`이면 K/N 컴파일 단위 이름(`DW_AT_comp_dir` 없음)이 상대 경로가 되어 소비자 앱을 링크하는 Xcode `ld`가 Kotlin 오브젝트의 디버그 맵을 만들지 않고, 앱 dSYM에서 Kotlin 디버그 정보(파일:줄, 인라인 프레임)가 빠졌다(dsymutil `could not open` 경고). `build-xcframework.sh`가 컴파일 단위 이름을, `check-consumer-ios.sh`가 Release 아카이브의 앱 dSYM을 검사한다. 앱에 들어가는 빈 stub 프레임워크는 Xcode의 의도된 동작이라 바꾸지 않는다.
