# ADR-0007: 세션 캐시: 포트 + 플랫폼 기본 구현 + 자체 포맷 v1

| 항목 | 내용 |
|---|---|
| 상태 | 제안 (2026-09-26) — SDD 승인 시 채택 |
| 결정 번호 | D26 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
앱 재실행 후 핸드셰이크를 생략하려면 도메인별 `SessionInfo`(counter, 차량 공개키, epoch, clock_time)와 저장 시각이 필요하다. 내용은 비밀이 아니지만(개인키 없이는 무용) 개인키에 종속된다. Go는 JSON 파일(`~/.tesla-cache.json`)을 쓴다.

## 결정
`SessionCache` 포트를 `:domain`에 두고 기본 구현을 플랫폼별로 제공한다(Android 앱 전용 파일, iOS Keychain ThisDeviceOnly, 공통 메모리). 포맷은 자체 바이너리 v1(`TBSC` 매직, keyId = SHA1(공개키), 도메인·createdAt·SessionInfo 바이트). keyId가 현재 키와 다르면 캐시를 버린다. 추가 암호화는 하지 않는다(D18). Go JSON 포맷과 호환하지 않는다(`tesla-control`과 캐시를 공유할 이유가 없음).

## 결과
- 저장 실패는 명령 결과에 영향을 주지 않는다(로그만).
- 시계 복원은 Go와 같이 `timeZero = createdAt - clock_time`.

## 대안
- Go JSON 호환: kotlinx-serialization 의존 추가, 이득 없음. 기각.
- 암호화 저장: 내용이 비밀이 아니라 이득이 작다. 기각(D18).
