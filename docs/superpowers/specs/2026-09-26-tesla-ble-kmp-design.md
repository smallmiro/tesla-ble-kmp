# 설계 브레인스토밍 기록 — tesla-ble-kmp (2026-09-26)

> `brainstorming` 스킬(architectural 경로)로 진행한 Phase 1(PRD)·Phase 2(SDD) 설계 대화의 원본 기록이다.
> 확정된 설계는 `{{SDD_FILE}}`, 요구사항은 `{{PRD_FILE}}`, 개별 결정은 `{{ADR_DIR}}`에 있다. 이 파일은 "왜 그렇게 결정했는가"의 대화 맥락만 보존한다.

## 1. 분류와 이해

- 분류: **architectural** (새 라이브러리, 기존 흐름 없음). HANDOFF의 Phase 1 → 2 → 3이 스킬의 질문 → 설계 → 스펙 → 계획에 대응.
- 이해한 목적: 서버 없이 스마트폰이 BLE로 테슬라 차량과 직접 통신하는 KMP 라이브러리. 사용자는 앱 개발자, 최종 사용자는 차주. 정답 기준은 Go `a4b43c1` + `protocol.md`, 1차 가이드는 `{{MANUAL_DIR}}`.
- 성공 기준(가정, 사용자 이의 없음): 벡터·골든 100%, 샘플 2종 빌드, 실차에서 페어링·잠금·공조·충전·조회.

## 2. Phase 1 질문과 답 (PRD D9~D19)

| 질문 | 제시한 선택지 | 답 | 근거 매뉴얼 |
|---|---|---|---|
| Q1 최소 버전 | 31+/16+ (권장) · 26+/15+ · 28+/15+ | **Android 31+ / iOS 16+** | `10-porting-guide §12` |
| Q2 개인키 보관 | 하드웨어 기본+소프트웨어 대체 (권장) · 하드웨어 전용 · 앱 선택 | **하드웨어 기본 + 대체 허용** | `00-agent-guide §3.3-21` |
| Q3 기본 역할 | Owner (권장) · Driver · 명시 필수 | **Owner** | `03-protocol §3` |
| Q4 API 스타일 | suspend+Flow+SKIE (권장) · suspend+Flow만 · 수기 Swift | **SKIE** | `04-go-api-reference` |
| Q5 배포 | 오픈소스+Maven Central+SPM (권장) · GitHub Release만 · 비공개 → 이후 세 차례 재논의 | **처음부터 공개(Apache-2.0), GitHub Packages + SPM, Maven Central 안 씀** | – |
| Q5-b 패키지 | io.github.<계정> (권장) · 도메인 역순 | **`io.github.smallmiro.teslable`** | – |
| Q6 샘플 UI | Compose+SwiftUI (권장) · Compose Multiplatform · Android 먼저 | **Compose + SwiftUI** | – |
| Q7 동시 연결 | 차량 단위 설계, v1 1대 (권장) · 1대 강제 · 다중 포함 | **차량 단위, v1 검증 1대** | `01-architecture §5`, `02-ble-transport §11` |
| Q8 v1 범위 | P0+P1 (권장) · 전체 · P0만 | **P0 + P1** | `05-command-catalog` |
| Q9 세션 캐시 | 포트+플랫폼 기본, 샌드박스만 (권장) · 암호화 · 메모리만 | **포트 + 플랫폼 기본, 추가 암호화 없음** | `10-porting-guide §9` |
| Q10 i18n | 영어+코드 (권장) · 영/한 리소스 · 한국어만 | **영어 메시지 + 구조화 코드** | `08-errors §1` |

배포 결정의 경위: "비공개 → 나중 공개" → "Maven 대신 GitHub" → GitHub Packages → "완전 공개" → GitHub Pages → 사용자가 `/packages` 페이지 사용 의사 확인 → **GitHub Packages 최종**. 토큰 필요 사실을 `getting-started.md`에 명시하기로 함.

## 3. Phase 2 질문과 답 (SDD D20~D30)

| 질문 | 제시한 선택지 | 답 |
|---|---|---|
| SDD-1 결과 모델 | sealed 결과 (권장) · 예외 · 둘 다 | **sealed `VehicleResult`** |
| SDD-2 iOS AES-GCM | cryptography-kotlin CryptoKit 프로바이더 (권장) · SPM Swift 주입 · 둘 다 | **cryptography-kotlin CryptoKit 프로바이더** |

나머지 D21~D23, D25~D30은 트레이드오프가 분명해 에이전트가 결정하고 SDD §0과 ADR에 근거를 적었다. 사용자는 SDD 승인 게이트에서 검토한다.

## 4. 조사로 확인한 사실 (2026-09-26)

- Kotlin 2.4.20, coroutines 1.11.0, AGP 9.3.1(KGP 지원 상한), Wire 7.0.4, Kable 0.45.0, SKIE 0.10.15, cryptography-kotlin 0.6.0, BCV 0.18.2, detekt 1.23.8, ktlint 1.8.0, Compose BOM 2026.09.00.
- Wire: commonMain 생성, iOS 아티팩트, `java_package` 우선 패키지명, `Timestamp` → `com.squareup.wire.Instant`.
- Kable: notify·indicate 둘 다 지원 시 notify 선택, 강제 옵션 없음(CCCD 직접 쓰기 우회 미검증). `Filter.Name.Exact`, `isConnectable`, `WriteType.WithResponse`, Android `requestMtu`, Apple `maximumWriteValueLengthForType` 있음.
- cryptography-kotlin: Apple 프로바이더는 GCM·ECDH 없음. CryptoKit 프로바이더(0.5.0+)는 Swift 브리지로 AES-GCM·ECDH 지원, SecKey/Secure Enclave 미지원.
- K/N: `platform.Security`, `platform.CoreCrypto` 사용 가능, CryptoKit 불가. `CCCryptorGCM*`은 비공개 SPI(Apple DTS: 심사 거절 가능).
- SKIE: suspend→async, Flow→AsyncSequence, sealed→enum, Apache-2.0.

## 5. 원본 대조에서 확인한 세부 동작 (SDD에 반영)

- `Dispatcher.Send`: VCSEC는 요청마다 `routing_address` 랜덤, `key.uuid` = 0; Infotainment는 고정 주소 + uuid. `FLAG_ENCRYPT_RESPONSE` 항상.
- `session.authorize`: `readySignal` 대기 후 `Encrypt`; 실패해도 ctx 만료까지 반복.
- `GetError` 순서: fault → session_info.status → operation_status. RoutableMessage 계층 `OPERATIONSTATUS_ERROR`는 오류 아님.
- `unmarshalVCSECResponse`: payload 없음 = 빈 메시지(성공), 파싱 실패는 `PossibleSuccess=true`.
- `responseMetadata`: AAD = SHA256(TLV‖0xFF) — 매뉴얼 `03-protocol §9.2` 첫 문장과 다름(SDD §12).
- `SendAddKeyRequestWithRole`: 응답을 읽지 않음 → ADR-0009로 확장.
- `SlidingWindow`: Go의 64비트 시프트 의미(≥64는 0)를 PoC가 명시적 분기로 재현함을 확인.
