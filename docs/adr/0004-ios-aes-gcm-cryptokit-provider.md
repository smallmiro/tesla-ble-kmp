# ADR-0004: iOS AES-GCM: cryptography-kotlin CryptoKit 프로바이더

| 항목 | 내용 |
|---|---|
| 상태 | 제안 (2026-09-26) — SDD 승인 시 채택 |
| 결정 번호 | D24 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
Kotlin/Native는 Swift 전용 CryptoKit을 직접 호출할 수 없다. CommonCrypto의 `CCCryptorGCM*`은 `CommonCryptorSPI.h`의 비공개 SPI라 App Store 심사 위험이 있다(Apple DTS 답변). cryptography-kotlin 0.6.0의 `cryptography-provider-apple`은 GCM·ECDH를 지원하지 않지만, `cryptography-provider-cryptokit`(0.5.0+)은 `dev.whyoleg.swiftinterop` 플러그인으로 CryptoKit을 브리지해 AES-GCM(AAD 포함)을 Kotlin에서 호출할 수 있다. 자체 GCM 구현은 NFR-005로 금지.

## 결정
iOS의 `CryptoPrimitives.aesGcm*`만 `cryptography-provider-cryptokit`로 구현한다. SHA-1/256, HMAC, 난수는 CommonCrypto/Security(K/N 플랫폼 라이브러리). 키 생성·보관·ECDH는 Security.framework(`SecKeyCreateRandomKey`, `SecKeyCopyKeyExchangeResult`)를 직접 호출한다(cryptography-kotlin은 SecKey/Secure Enclave를 지원하지 않음). `CryptoPrimitives`는 포트이므로 앱이 Swift CryptoKit 구현을 주입해 교체할 수 있다.

## 결과
- iOS 시뮬레이터 Kotlin 테스트에서 GCM 벡터를 검증할 수 있고 XCFramework가 자족한다.
- 의존성 1개와 Swift 브리지 플러그인이 추가되어 iOS 빌드에 Xcode가 필요하다(원래 필요).
- M0에서 `protocol.md` GCM 벡터가 iOS 시뮬레이터에서 통과하지 않으면 SPM Swift 타깃 주입 방식으로 전환하고 이 ADR을 대체한다.

## 대안
- SPM Swift 타깃에서 CryptoKit 구현 주입: 외부 의존 없음. 그러나 iOS Kotlin 테스트에서 GCM 검증 불가, 앱이 반드시 SPM 패키지를 써야 함. 대체 경로로 보류.
- CommonCrypto SPI: 심사 위험. 기각.
