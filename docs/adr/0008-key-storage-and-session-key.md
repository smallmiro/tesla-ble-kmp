# ADR-0008: 키 보관과 세션 키 메모리 정책

| 항목 | 내용 |
|---|---|
| 상태 | 제안 (2026-09-26) — SDD 승인 시 채택 |
| 결정 번호 | D27 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
`00-agent-guide §3.3-21`은 TEE 구현이 공유 비밀을 내보내거나 호스트 제공 nonce로 AES-GCM을 하면 안 된다고 경고한다. 그러나 Android Keystore `KeyAgreement`와 iOS `SecKeyCopyKeyExchangeResult`는 모두 공유 X를 앱에 반환하고, 플랫폼 AES-GCM API는 nonce를 앱이 제공한다. 프로토콜 구조상 K와 nonce 생성은 앱 메모리에서 일어날 수밖에 없다.

## 결정
개인키는 플랫폼 키스토어 핸들로만 존재하고 내보내기 API가 없다(D10: 하드웨어 우선, 소프트웨어 대체 허용, 보관 수준 조회). 공유 X → K → 서브키는 `Session` 객체 메모리에만 두고 `close()`에서 0으로 덮는다. `CryptoPrimitives.aesGcmEncrypt`는 nonce를 인자로 받으며(테스트 벡터 고정용), 운영 경로는 항상 `RandomSource`의 새 12바이트를 넘긴다. Go `NativeSession.Encrypt`(내부 nonce 생성)와 구조가 다르지만 출력 바이트는 같다.

## 결과
- 보안 수준은 Go의 네이티브 키(파일)보다 높고 HSM 이상은 아니다. `platform-notes.md`에 명시.
- 테스트 키(`protocol.md`)는 `:testing`의 `SoftwareEcdhKey`로만 사용한다.

## 대안
- 하드웨어 전용: 에뮬레이터·CI 불가. 기각(D10).
