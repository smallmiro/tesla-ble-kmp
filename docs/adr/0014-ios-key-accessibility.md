# ADR-0014: iOS 키 접근성 선택 (잠긴 휴대폰에서 키 사용)

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-10-24, 설계 §5.6 승인, 계획 Task 7) |
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
- Locked 판정은 좁게: OSStatus `errSecInteractionNotAllowed`(-25308)만(Secure Enclave 조회, Keychain 읽기·쓰기·삭제), 그리고 `SecKeyCreateRandomKey`의 `CFError` 중 도메인이 OSStatus이고 코드가 -25308인 것(설명 문자열은 보지 않음). `errSecAuthFailed`, `errSecItemNotFound` 등은 잠금으로 보지 않는다. 키를 만들다 Locked가 나오면 다음 보관 수준(소프트웨어)으로 넘어가지 않는다 — 일시적인 잠금 때문에 `PREFER_HARDWARE`가 영구 `SOFTWARE` 키를 남기면 안 된다(`KeyCreationChain`이 내부 `KeyStoreLockedException`을 `Locked`로 바꾼다).
- Android·JVM: 요청 값을 무시하고 `AFTER_FIRST_UNLOCK`을 보고한다. Android 근거는 위 맥락(키 생성에 잠금 해제·사용자 인증 요구가 없고, 앱 전용 `filesDir`는 재시동 뒤 첫 잠금 해제 다음부터 잠긴 동안에도 접근할 수 있으며, 앱은 direct boot를 지원하지 않아 그 전에는 실행되지 않는다). JVM 메모리 키는 프로세스가 살아 있는 한 잠금과 무관하다.

## 결과
- **위험(옵트인한 앱에만):** 재시동 뒤 한 번 잠금이 풀린 휴대폰을 손에 넣은 사람, 또는 앱 권한으로 실행되는 코드는 화면이 잠긴 동안에도 BLE로 이 키를 차량에 쓸 수 있다(키 역할이 허용하는 명령, 예: 문 열기). Secure Enclave는 키를 내보내지 못하게 막을 뿐 명령마다 사용자 확인을 요구하지 않는다. 소프트웨어 대체 키는 개인키 스칼라가 프로세스 메모리를 거치므로 노출면이 더 크다 — 하드웨어 보장이 필요한 앱은 `REQUIRE_HARDWARE`를 쓴다. 앱은 이 위험을 사용자에게 알린다(샘플은 키 접근성을 화면에 표시한다).
- `WHEN_UNLOCKED`도 화면이 잠기는 순간 모든 차량 조작이 멈춘다는 보장은 아니다: 이미 맺은 세션(메모리의 세션 키)은 개인키 연산 없이 계속 쓰인다. 개인키가 필요한 것은 새 핸드셰이크와 캐시 세션 복원이다.
- 마이그레이션이 없으므로 잠긴 폰에서 쓰려는 앱은 새 별칭으로 키를 만들고 차량에 다시 등록한다(`{{LIB_DOCS_DIR}}13-lib-pairing.md` 절차).
- **알려진 한계(후속 작업 후보, 사용자 확인 필요):** 잠금이 풀린 동안 얻은 `WHEN_UNLOCKED` 키 핸들로 잠긴 동안 핸드셰이크나 캐시 세션 복원을 하면 ECDH가 실패하는데, 지금은 `Signer`가 그 예외를 `BAD_PARAMETER` fault로 바꿔 `startSession`이 `Timeout`으로 끝난다(`Locked`가 아님). 그래서 FR-037의 `Locked`는 키 저장소 호출(`getOrCreate`/`get`/`delete`)에 한정한다. 앱은 (재)연결 직전에 키를 저장소에서 다시 읽어 `Locked`를 받는다. 핸드셰이크 경로까지 `Locked`를 올리려면 `Signer`·`SessionState`·`HandshakeFlow`를 지나는 타입 있는 예외가 필요하다(프로토콜 경로 변경이라 이 작업 범위 밖). 이 실패는 캐시를 오래된 것으로 지우지 않는다(복원 중인 세션은 이미 세션 키가 있어 `processHello`에서 ECDH를 하지 않는다).
- 검증 범위: 단위 테스트는 매핑, 질의 결과 해석, 저장소의 결정(가짜 Keychain·Secure Enclave)만 확인한다. 일반 Kotlin/Native 시뮬레이터 테스트 바이너리는 Keychain을 쓸 수 없다(-25291). 실제 Keychain 왕복, `pdmn` 다시 읽기, 잠긴 iPhone에서 Secure Enclave 키 사용(이 보호 등급이 SE 키에 실제로 적용되는지), Android 잠긴 상태 사용은 샘플 앱 실기기 확인 항목이다(계획 Task 10·11).
- Codex 협의(2026-10-24, `feat/ios-key-accessibility`@`7084be7`, 읽기 전용): 옵트인·기본값 유지, -25308 매핑과 소프트웨어 대체 금지, `temporary = true`, Android 근거, API 모양에 동의. 반영한 의견: `pdmn`이 없으면 "알 수 없음"으로 다루고 드러낼 것(WARN), 라벨을 권위 있는 대체 출처로 쓰지 말 것, Locked는 도메인과 코드로만 판정할 것, 위 두 가지 위험을 결과에 적을 것. 의견이 갈린 점: Codex는 핸드셰이크 경로의 `Locked`를 이 작업에 넣거나 FR-037을 명시적으로 좁히라고 했다. 계획의 파일 목록 밖인 프로토콜 경로 변경이라 FR-037을 좁히고 후속 작업 여부를 사용자에게 묻는다.

## 대안
- **라벨에 접근성 저장:** 실제 보호 등급과 어긋날 수 있고, 기존 키에는 라벨이 없어 어차피 대체 규칙이 필요하다. 기각.
- **iOS 전용 팩토리 인자(`appleVehicleKeyStore(accessibility)`):** 별칭마다 다른 값을 고를 수 없고 공통 코드에서 쓸 수 없다. 기각.
- **기존 키를 자동으로 다시 만들기:** 공개키가 바뀌어 차량 재등록이 필요하므로 사용자가 모르게 하면 안 된다(손상 키를 자동 교체하지 않는 원칙과 같음). 기각.
