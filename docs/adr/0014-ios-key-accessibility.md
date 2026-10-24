# ADR-0014: iOS 키 접근성 선택 (잠긴 휴대폰에서 키 사용)

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-10-24, 설계 §5.6 승인, 계획 Task 7). 개정 2026-10-24: 핸드셰이크·캐시 복원 경로의 `Locked`(#96, 후속 계획 Task A) |
| 관련 | `{{PRD_FILE}}` FR-037, `{{SDD_FILE}}` §8.2, ADR-0008(보완), ADR-0007, `{{SPECS_DIR}}2026-10-24-vehicle-events-monitor-design.md` §5.6, 이슈 #91 |

## 맥락
- 사용자는 휴대폰이 잠긴 상태에서도 **키가 있는 채로** 차량을 모니터링하길 원한다(설계 U10, 키 없이 연결하는 대안은 거절됨).
- 지금 iOS 개인키는 Secure Enclave 키(`SecAccessControlCreateWithFlags(kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAccessControlPrivateKeyUsage)`)와 소프트웨어 대체 키(Keychain generic password, 같은 보호 등급) 모두 잠금이 풀린 동안만 쓸 수 있다. 잠긴 iPhone에서는 Keychain 조회가 `errSecInteractionNotAllowed`(-25308)로 실패하고 핸드셰이크(ECDH)를 할 수 없다.
- 그 오류는 지금 일반 `KeyStoreError.PlatformFailure("keychain status -25308")`로 나와 앱이 손상·플랫폼 실패와 구별하지 못한다. 앱이 키를 지우고 다시 만들도록 유도할 위험이 있다(계획 Review Focus 4).
- iOS 세션 캐시는 이미 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`다(ADR-0007). 바꿀 것은 개인키뿐이다.
- Android 키 생성은 `setUnlockedDeviceRequired`·`setUserAuthenticationRequired`를 쓰지 않는다(코드 확인: `AndroidVehicleKeyStore.generateKeystoreKey`의 `KeyGenParameterSpec`, 소프트웨어 키를 감싸는 `AndroidKeystoreWrapper`의 AES 키). API 문서상 잠긴 동안에도 쓸 수 있다. **실기기 확인은 아직이다.**

## 선택지
1. **WhenUnlocked 유지**: 가장 엄격하지만 잠긴 폰 모니터링이 불가능하다. 사용자 요구를 채우지 못한다.
2. **기본값을 AfterFirstUnlock으로 바꿈**: 모든 앱이 잠긴 폰에서 동작하지만, 라이브러리를 쓰는 다른 앱의 보안 수준이 몰래 낮아진다.
3. **옵트인(채택)**: 키를 만들 때 접근성을 고른다. 기본값은 지금과 같은 `WHEN_UNLOCKED`.

## 결정
- 공개 API(`:domain`): `enum class KeyAccessibility { WHEN_UNLOCKED, AFTER_FIRST_UNLOCK }`, `VehicleKey.accessibility`, `VehicleKeyStore.getOrCreate(alias, policy, accessibility)`. 기존 2인자 `getOrCreate(alias, policy)`는 `WHEN_UNLOCKED`로 위임한다(Swift에는 `getOrCreate(alias:policy:)`와 `getOrCreate(alias:policy:accessibility:)`가 둘 다 생긴다). 오류 `VehicleError.KeyStoreError.Locked`(`temporary = true`, `mayHaveSucceeded = false`).
- iOS 매핑: `WHEN_UNLOCKED` → `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, `AFTER_FIRST_UNLOCK` → `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`. 둘 다 `ThisDeviceOnly`(백업·기기 이전으로 다른 기기에 가지 않음). Secure Enclave 키는 `SecAccessControlCreateWithFlags`의 보호 인자, 소프트웨어 키는 Keychain 항목의 `kSecAttrAccessible`로 정한다.
- 다시 읽기: 이미 있는 키의 접근성은 `SecItemCopyMatching`에 `kSecReturnAttributes`를 더해 항목의 `kSecAttrAccessible`(속성 이름 `pdmn`)에서 읽는다. 반환 형식을 둘 이상 요청하면 결과는 딕셔너리이고 키 참조는 `kSecValueRef`, 값은 `kSecValueData`에 있다(키 참조는 딕셔너리를 해제하기 전에 retain한다). AfterFirstUnlock 계열 → `AFTER_FIRST_UNLOCK`, WhenUnlocked·WhenPasscodeSet 계열 → `WHEN_UNLOCKED`. 값이 없거나 모르는 값이면 "알 수 없음"으로 다뤄 `WHEN_UNLOCKED`로 보고하고 WARN을 남긴다(잠긴 사용을 과장하지 않고, 실기기에서 드러나게 한다). 라벨 같은 별도 메타데이터는 저장하지 않는다: 실제 보호 등급과 어긋날 수 있는 두 번째 출처를 만들지 않는다.
- 마이그레이션 없음: 접근성은 만들 때 정해진다. Secure Enclave 키는 내보낼 수 없어 다시 감쌀 수도 없다. 이미 있는 별칭에 다른 값을 요청하면 **기존 키를 그대로** 돌려주고 `accessibility`로 실제 값을 알린다. 다른 값이 필요하면 새 별칭으로 만들고 차량에 다시 등록한다.
- Locked 판정은 좁게: OSStatus `errSecInteractionNotAllowed`(-25308)만(Secure Enclave 조회, Keychain 읽기·쓰기·삭제), 그리고 `SecKeyCreateRandomKey`의 `CFError` 중 도메인이 OSStatus이고 코드가 -25308인 것(설명 문자열은 보지 않음). 같은 규칙을 `SecKeyCopyKeyExchangeResult`의 `CFError`에도 쓴다(#96, 아래 결과). `errSecAuthFailed`, `errSecItemNotFound` 등은 잠금으로 보지 않는다. 키를 만들다 Locked가 나오면 다음 보관 수준(소프트웨어)으로 넘어가지 않는다 — 일시적인 잠금 때문에 `PREFER_HARDWARE`가 영구 `SOFTWARE` 키를 남기면 안 된다(`KeyCreationChain`이 내부 `KeyStoreLockedException`을 `Locked`로 바꾼다).
- Android·JVM: 요청 값을 무시하고 `AFTER_FIRST_UNLOCK`을 보고한다. Android 근거는 위 맥락(키 생성에 잠금 해제·사용자 인증 요구가 없고, 앱 전용 `filesDir`는 재시동 뒤 첫 잠금 해제 다음부터 잠긴 동안에도 접근할 수 있으며, 앱은 direct boot를 지원하지 않아 그 전에는 실행되지 않는다). JVM 메모리 키는 프로세스가 살아 있는 한 잠금과 무관하다.

## 결과
- **위험(옵트인한 앱에만):** 재시동 뒤 한 번 잠금이 풀린 휴대폰을 손에 넣은 사람, 또는 앱 권한으로 실행되는 코드는 화면이 잠긴 동안에도 BLE로 이 키를 차량에 쓸 수 있다(키 역할이 허용하는 명령, 예: 문 열기). Secure Enclave는 키를 내보내지 못하게 막을 뿐 명령마다 사용자 확인을 요구하지 않는다. 소프트웨어 대체 키는 개인키 스칼라가 프로세스 메모리를 거치므로 노출면이 더 크다 — 하드웨어 보장이 필요한 앱은 `REQUIRE_HARDWARE`를 쓴다. 앱은 이 위험을 사용자에게 알린다(샘플은 키 접근성을 화면에 표시한다).
- `WHEN_UNLOCKED`도 화면이 잠기는 순간 모든 차량 조작이 멈춘다는 보장은 아니다: 이미 맺은 세션(메모리의 세션 키)은 개인키 연산 없이 계속 쓰인다. 개인키가 필요한 것은 새 핸드셰이크와 캐시 세션 복원이다.
- 마이그레이션이 없으므로 잠긴 폰에서 쓰려는 앱은 새 별칭으로 키를 만들고 차량에 다시 등록한다(`{{LIB_DOCS_DIR}}13-lib-pairing.md` 절차).
- **잠긴 동안의 핸드셰이크·캐시 복원(#96에서 해결, 실기기 확인 전):** 잠금이 풀린 동안 얻은 `WHEN_UNLOCKED` Secure Enclave 키 핸들로 잠긴 동안 핸드셰이크하면 ECDH(`SecKeyCopyKeyExchangeResult`)가 실패한다. #91 시점에는 `Signer`가 그 `IllegalArgumentException`을 `BAD_PARAMETER` fault로 바꿔(WARN `Session info error: MESSAGEFAULT_ERROR_BAD_PARAMETER: invalid public key`) `startSession`이 `Timeout`으로 끝났고, 캐시 세션 복원(`Signer.importSessionInfo` → `Session.establish`)은 그 항목을 건너뛴 뒤 세션 없이 `disconnect()`하면 빈 목록을 저장해 아직 유효한 캐시를 지웠다. #96부터:
  - 어댑터(`takeKeyExchangeFailure`)가 위 Locked 판정과 같은 규칙(OSStatus 도메인, -25308)의 `CFError`를 `:domain`의 `KeyLockedException`으로 던진다 — 포트 `EcdhPrivateKey.sharedX`의 계약이고, 앞의 판정처럼 설명 문자열은 보지 않으며 예외에 싣지도 않는다. 그 밖의 ECDH 실패는 이전과 같다(`BAD_PARAMETER`).
  - 이 예외는 `Session`·`Signer`·`SessionState`를 값으로 바뀌지 않고 지나 디스패처 경계의 좁은 `catch`에서 잡힌다. 핸드셰이크 응답이면 그 요청에 표시하고(WARN), `HandshakeFlow`가 첫 응답 직후 `Failure(KeyStoreError.Locked)`로 끝낸다. 차량이 응답에 실은 오류(`KeyNotPaired` 등)가 먼저다. 응답은 ECDH 전이라 인증되지 않았지만 `Locked`는 이 기기의 사실이고 응답의 상태는 아무것도 쓰지 않는다.
  - `retryWhileRetriable`은 `Locked`를 되풀이하지 않는다. `temporary = true`는 그대로 둔다(잠금이 풀리면 앱이 다시 부르면 된다는 뜻) — 잠금 해제는 사용자 몫이라 되풀이하면 `handshakeTimeout` 뒤 `Timeout`으로 바뀔 뿐이다.
  - 캐시 복원은 그 항목을 건너뛰되("invalid cache"가 아닌 WARN) 도메인을 `Dispatcher.restoresBlockedByLock`에 남기고, `SessionCacheSync.store`는 그 도메인에 세션이 생길 때까지 아무것도 쓰지 않는다. 포트는 목록 전체를 덮어쓰고 저장 시각을 새로 붙이므로 옛 항목만 남길 수 없다(나이가 틀려진다).
  - Go는 키 `Exchange` 오류를 구별하지 않는다(핸드셰이크는 로그 후 `RetryInterval`마다 재전송하다 데드라인으로 끝나고, `LoadCache`는 전체 실패한다) — 의도한 차이다.
  - 소프트웨어 대체 키 핸들은 개인키를 프로세스 메모리에 가지고 있어 잠긴 뒤에도 핸드셰이크할 수 있다(API 기준). `WHEN_UNLOCKED`가 막는 것은 Keychain에서 다시 읽는 것뿐이다 — 위 노출면 위험과 같은 이유다.
  - **실기기 확인 전:** 잠긴 iPhone의 Secure Enclave ECDH가 실제로 OSStatus -25308로 실패를 알리는지(Apple 문서가 보장하지 않는다, CryptoTokenKit 도메인일 수도 있다). 다른 형태면 이전처럼 `Timeout`으로 끝난다 — 실기기에서 `CFError` 도메인·코드를 확인하고, 다르면 판정 규칙을 근거와 함께 넓힌다.
- ~~**키 핸들은 해제되지 않는다(기존 동작, 후속 작업 후보):**~~ **#97(PR #105)에서 해결:** 키 객체가 수거될 때 Kotlin/Native `Cleaner`가 `SecKeyRef`를 한 번 해제하므로 다시 읽어도 핸들이 쌓이지 않는다. 아래는 당시 기록이다. `SecureEnclaveKey`와 `AppleSoftwareEcdhKey`는 `SecKeyRef`를 소유하지만 놓지 않는다. `getOrCreate`/`get`을 부를 때마다 새 핸들이 생기므로 앱은 핸들을 다시 읽기보다 재사용한다(사용 문서도 매 연결마다 다시 읽으라고 권하지 않는다). 해제하려면 Kotlin/Native `Cleaner` 같은 설계와 시험 방법이 따로 필요하다.
- 검증 범위: 단위 테스트는 매핑, 질의 결과 해석, 저장소의 결정(가짜 Keychain·Secure Enclave)만 확인한다. #96의 ECDH 잠금 판정은 `CFErrorCreate`로 만든 오류로(iOS 시뮬레이터), 핸드셰이크·복원·캐시 보존은 잠긴 키를 흉내 내는 가짜 키(`LockableEcdhKey`)로 확인한다. 일반 Kotlin/Native 시뮬레이터 테스트 바이너리는 Keychain을 쓸 수 없다(-25291). 실제 Keychain 왕복, `pdmn` 다시 읽기, 잠긴 iPhone에서 Secure Enclave 키 사용(이 보호 등급이 SE 키에 실제로 적용되는지), Android 잠긴 상태 사용은 샘플 앱 실기기 확인 항목이다(계획 Task 10·11).
- Codex 협의(2026-10-24, `feat/ios-key-accessibility`@`7084be7`, 읽기 전용): 옵트인·기본값 유지, -25308 매핑과 소프트웨어 대체 금지, `temporary = true`, Android 근거, API 모양에 동의. 반영한 의견: `pdmn`이 없으면 "알 수 없음"으로 다루고 드러낼 것(WARN), 라벨을 권위 있는 대체 출처로 쓰지 말 것, Locked는 도메인과 코드로만 판정할 것, 위 두 가지 위험을 결과에 적을 것. 의견이 갈린 점: Codex는 핸드셰이크 경로의 `Locked`를 이 작업에 넣거나 FR-037을 명시적으로 좁히라고 했다. 계획의 파일 목록 밖인 프로토콜 경로 변경이라 FR-037을 좁히고 후속 작업 여부를 사용자에게 묻는다.
- Codex 구현 리뷰(2026-10-24, `adcd323`, 읽기 전용): 반영 — `delete`에서 한쪽이라도 -25308이면 `Locked`(`deleteReportsLockedWhenEitherCopyIsLocked`), 캐시 복원도 ECDH를 한다는 설명 정정, 매 연결마다 키를 다시 읽으라는 권고 철회(핸들이 해제되지 않으므로 — #97 이후 이 이유는 없어졌고, 남은 이유는 잠긴 동안 다시 읽으면 `Locked`라는 점이다). 반영하지 않음 — `KeychainItems.write`의 사전 삭제 상태 확인(쓰기는 `load`가 항목을 찾지 못한 뒤에만 불리고, 잠긴 동안에는 추가 자체가 -25308을 돌려준다. `:adapter-storage` 복사본과 같은 동작을 유지), `SecKeyRef` 해제(기존 동작, 위 한계).
- Codex 협의(2026-10-24, #96 설계, `fix/96-locked-handshake`@`189d044`, `codex exec` 읽기 전용 — MCP 연결 실패로 CLI 사용): 동의 — 포트를 지나는 타입 있는 예외(`SignerResult` 변형이나 문자열 표시보다 낫다), 공유 재시도 루프에서 `Locked` 제외, 차량 오류 우선. 반영 — 캐시 보존을 "빈 목록이면 쓰지 않기"가 아니라 도메인 단위로(한 도메인만 복원된 경우 다른 도메인 항목이 지워지는 구멍), 요청 표시는 핸드셰이크의 첫 응답에만 쓰고 시도마다 새 요청임을 테스트로 고정(`startSessionsFailsWithLockedThenSucceedsOnceTheKeyIsUnlocked`), `CFError` 한 번만 해제(테스트로 고정). 남긴 의견 — Secure Enclave ECDH의 실제 오류 도메인은 확신이 낮다(실기기 확인 항목). 응답 표시를 응답 값에 함께 싣는 설계는 `PendingRequest` 공개 API가 바뀌어 택하지 않았다.

## 대안
- **라벨에 접근성 저장:** 실제 보호 등급과 어긋날 수 있고, 기존 키에는 라벨이 없어 어차피 대체 규칙이 필요하다. 기각.
- **iOS 전용 팩토리 인자(`appleVehicleKeyStore(accessibility)`):** 별칭마다 다른 값을 고를 수 없고 공통 코드에서 쓸 수 없다. 기각.
- **기존 키를 자동으로 다시 만들기:** 공개키가 바뀌어 차량 재등록이 필요하므로 사용자가 모르게 하면 안 된다(손상 키를 자동 교체하지 않는 원칙과 같음). 기각.
