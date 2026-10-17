# ADR-0013: 패키지 루트를 `dev.mirolab.teslable` 로 변경

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-10-17, 사용자 결정) |
| 관련 | `{{PRD_FILE}}` D14, `{{PATHS_FILE}}` `BASE_PACKAGE` |

## 맥락
PRD D14(Phase 1 Q5-b)는 소유 도메인이 없어서 패키지 루트를 `io.github.smallmiro.teslable` 로 정했다. Maven Central로 옮길 때 `io.github.<계정>` 그룹은 GitHub 계정만으로 검증할 수 있기 때문이었다. 2026-10-17에 프로젝트 도메인이 `mirolab.dev` 로 정해졌다. 아직 첫 배포(M6) 전이라 패키지를 바꿔도 외부 소비자가 깨지지 않는다.

## 결정
패키지 루트를 역도메인 규칙에 따라 `dev.mirolab.teslable` 로 바꾼다. 같이 바뀌는 것:
- 모든 모듈의 Kotlin 패키지와 소스 디렉터리(`.../kotlin/dev/mirolab/teslable/...`), 공개 API 덤프(`*/api/*`)
- Android `namespace`(`build-logic` 컨벤션 플러그인), 샘플 `applicationId` → `dev.mirolab.teslable.sample`
- iOS 샘플 `bundleIdPrefix` → `dev.mirolab`, `PRODUCT_BUNDLE_IDENTIFIER` → `dev.mirolab.TeslableSampleDevice`
- iOS Keychain 서비스 이름과 Secure Enclave 태그 접두사 → `dev.mirolab.teslable.key`, `dev.mirolab.teslable.session`, `dev.mirolab.teslable.key.`

저장소 URL(`{{REPO_URL}}`)과 GitHub Packages 주소(`{{PACKAGES_MAVEN_URL}}`)는 GitHub 계정 기준이라 그대로 둔다.
Android Keystore 별칭(`teslable.key:` / `teslable.wrap:`)과 Android 세션 캐시 경로(`filesDir/teslable/sessions`)는 패키지 이름과 무관한 값이라 바꾸지 않는다.

## 결과
- 배포 전 변경이므로 하위 호환 조치는 하지 않는다. 예전 Keychain 서비스 이름에 저장된 키와 세션 캐시는 새 버전에서 보이지 않는다. 실차 확인을 하던 기기에서는 키를 다시 등록한다. 예전 항목을 옮기는 코드는 넣지 않는다(사용자 결정).
- 샘플 앱의 applicationId/bundle id가 바뀌므로, 기기에서는 별도의 새 앱으로 설치된다. 예전 샘플 앱은 지워도 된다.
- Maven Central로 옮길 때는 `mirolab.dev` 도메인의 DNS TXT 레코드로 그룹 `dev.mirolab` 소유를 검증해야 한다.

## 대안
- **`io.github.smallmiro.teslable` 유지**: 도메인 검증 없이 Central에 배포할 수 있지만, 정해진 도메인과 공개 식별자가 어긋난다. 기각.
- **문서에만 기록하고 M6 직전에 변경**: 그사이에 쌓이는 코드와 키 저장 데이터가 늘어나 나중에 바꾸는 비용이 커진다. 기각.
