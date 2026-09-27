# 개발 표준 및 워크플로

| 항목 | 내용 |
|---|---|
| 대상 | `{{LIB_NAME}}`: Tesla BLE 프로토콜 Kotlin Multiplatform 라이브러리 (Android + iOS) |
| 기반 | 켄트 벡 TDD, Tidy First, SOLID, DDD, Clean Code, 헥사고날 아키텍처, Trunk-based Development |
| 사용 도구 | Claude Code(+Superpowers), Codex CLI, GitHub Copilot CLI |
| 연계 | 개발 매뉴얼 `{{MANUAL_DIR}}`, 에이전트 공통 지침 `{{AGENTS_FILE}}`, Claude 전용 `{{CLAUDE_FILE}}`, 설계 `{{SDD_FILE}}`, 경로 `{{PATHS_FILE}}` |
| 버전 | v1.0 (2026-09-26) |

---

## 1. 개발 철학

네 축이 서로 맞물려 돌아갑니다.

1. **TDD (Red → Green → Refactor)**: 테스트가 설계를 이끕니다.
2. **헥사고날 + DDD**: 프로토콜 도메인을 BLE, 키스토어, OS로부터 격리합니다.
3. **Trunk-based**: 작게 나눠 자주 통합합니다.
4. **매뉴얼로 안내받고, 원본으로 검증**: 포팅 작업은 `{{MANUAL_DIR}}` 의 개발 매뉴얼(실제 `vehicle-command` 기준으로 정리됨)을 1차 가이드로 삼습니다. 프로토콜 동작의 최종 기준은 `{{REF_REPO_DIR}}` 의 Go 코드(`{{REF_REPO_COMMIT}}`)와 `protocol.md` 입니다. 우리 코드가 원본과 다르게 동작하면 버그입니다. 매뉴얼과 원본이 다르면 원본을 따르고 사용자에게 보고합니다(매뉴얼은 승인 없이 수정 금지).

헥사고날 경계 덕분에 도메인은 차량이나 BLE 없이도 테스트할 수 있습니다. 그래서 TDD가 가능하고, TDD의 작은 스텝이 Trunk-based의 잦은 통합을 떠받칩니다.

---

## 2. TDD 표준

### 2.1 사이클
| 단계 | 행동 | 규칙 |
|---|---|---|
| **Red** | 실패하는 테스트를 먼저 작성 | 구현 코드보다 테스트가 먼저 있어야 함 |
| **Green** | 통과시킬 최소 코드만 작성 | 필요 이상으로 짜지 않음 |
| **Refactor** | 통과 상태에서 구조 개선 | 테스트가 Green일 때만 |

### 2.2 규칙
- 한 번에 **테스트 하나**씩 진행합니다.
- 테스트 이름은 동작을 서술합니다. 예: `derivesSessionKeyFromEcdhSharedX`, `dropsResponseWithReplayedCounter`, `reassemblesFrameSplitAcrossThreeChunks`
- **결함 수정은 재현 테스트부터** 시작합니다. 원본 Go 코드가 같은 입력에 무엇을 하는지 먼저 확인합니다.
- 커버리지 수치는 목표가 아닙니다. 구현에 맞춰 사후에 쓴 테스트는 금지합니다.

### 2.3 테스트 계층
| 계층 | 대상 | 방식 | 실행 |
|---|---|---|---|
| **벡터 테스트** | Metadata TLV, 키 유도, 세션정보 HMAC, GCM, 광고 이름 | `protocol.md` 테스트 벡터와 바이트 단위 비교 | 매 푸시 |
| **골든 테스트** | 명령별 protobuf 인코딩, 응답 디코딩 | `tesla-control -ble -debug` 에서 수집한 TX/RX 헥사 (VIN 마스킹) | 매 푸시 |
| **도메인 단위** | Session, SlidingWindow, 프레이밍, 에러 분류 | 순수 Kotlin | 매 푸시 |
| **유스케이스** | Dispatcher, Vehicle API | 포트를 **FakeTransport, FakeVehicle** 로 대체 | 매 푸시 |
| **어댑터 통합** | Keystore(ECDH), Kable | 실기기 또는 시뮬레이터 | 수동, 야간 |
| **실차 체크리스트** | 페어링, 잠금, 공조, 충전, 조회 | 사용자가 수행 (§8.4) | 마일스톤마다 |

### 2.4 FakeVehicle 원칙
- 원본의 `internal/authentication/verifier.go` 와 `dispatcher_test.go` 를 참고해 **차량 측 핸드셰이크와 응답을 흉내냅니다.**
- 다음 시나리오를 결정적(deterministic)으로 재현할 수 있어야 합니다.
  - VCSEC 응답 여러 개 (`WAIT` → 최종)
  - 응답 유실 (결과 불확실)
  - epoch 변경 (차량 재부팅)
  - clock 역행
  - 잘못된 HMAC
  - 재전송된 응답
- 시간은 `Clock` 포트로 주입합니다. 테스트에서 실제로 sleep하지 않습니다.

---

## 3. Tidy First: 구조 변경과 동작 변경 분리

| 종류 | 정의 | 예 |
|---|---|---|
| 구조 변경 (STRUCTURAL) | 동작은 그대로, 코드만 재배열 | 이름 변경, 함수 추출, 파일 이동, 모듈 분리 |
| 동작 변경 (BEHAVIORAL) | 기능 추가나 수정 | 새 명령, 응답 처리 로직 변경 |

- 한 커밋에 두 종류를 **섞지 않습니다.** 둘 다 필요하면 **구조 변경을 먼저** 별도 커밋으로 합니다.
- 구조 변경 전후에 테스트를 돌려 동작이 바뀌지 않았음을 확인합니다.
- 커밋 접두어로 종류를 표시합니다(§5.2).

---

## 4. 아키텍처 표준 (헥사고날 + DDD)

### 4.1 의존 방향
```
   인바운드 (앱 코드 → 공개 파사드 :sdk)
                 │
                 ▼
 ┌──────── :application (Dispatcher, Vehicle 유스케이스) ────────┐
 │                          │                                   │
 │                          ▼                                   │
 │   ┌──────────── :domain (모델 + 포트) ────────────┐           │
 │   │ RoutableMessage 모델(Wire), Session, Metadata  │           │
 │   │ SlidingWindow, 에러 타입                        │           │
 │   │ 포트: Transport, KeyStore, CryptoPrimitives,   │◀── 어댑터가 구현
 │   │       SessionCache, Clock                      │           │
 │   └───────────────────────────────────────────────┘           │
 └───────────────────────────────────────────────────────────────┘
   아웃바운드 어댑터: :adapter-ble(Kable), :adapter-crypto(Keystore/SecKey), :adapter-storage
```
의존은 항상 안쪽(`:domain`)을 향합니다. `:domain` 은 바깥을 모릅니다.

### 4.2 경계 강제 (빌드가 잡는다)
| 모듈 | 허용 의존 | 금지 예 (컴파일 에러가 나야 함) |
|---|---|---|
| `:domain` | Wire 런타임, kotlinx-coroutines-core | Kable, Android SDK, platform.Security |
| `:application` | `:domain` | 어댑터 타입 직접 참조 |
| `:adapter-*` | `:domain` + 외부 SDK | 유스케이스(`:application`) 참조 |
| `:sdk` | 전부 | (조립만 담당) |
| `samples/*` | `:sdk` | 내부 모듈 직접 참조 |

- 위반은 리뷰가 아니라 **빌드가 잡아야 합니다.** 리뷰어의 주의력에 기대지 않습니다.
- `build.gradle.kts` 에 의존성을 추가하는 PR은 **아키텍처 변경**으로 표시하고 ADR을 작성합니다.

### 4.3 SOLID 적용 예
| 원칙 | 이 프로젝트에서 |
|---|---|
| SRP | `Reassembler` 는 청크 재조립만 합니다. 메시지 해석은 Dispatcher가 담당합니다 |
| OCP | 새 명령을 추가해도 Dispatcher는 바뀌지 않습니다. 명령 빌더만 늘어납니다 |
| LSP | 모든 `Transport` 구현(Kable, Fake)은 같은 계약(프레임 단위 송수신, 순서 보장)을 지킵니다 |
| ISP | `KeyStore`(키 생성, ECDH)와 `CryptoPrimitives`(해시, GCM)를 분리합니다. iOS에서 GCM만 Swift로 주입하는 선택지가 열립니다 |
| DIP | Dispatcher는 `Transport` 포트에 의존하고 Kable을 모릅니다 |

### 4.4 Kotlin 코딩 규약
- 라이브러리는 **`explicitApi()` 모드**를 사용합니다. 공개 API에는 KDoc을 필수로 답니다.
- 공개 API 변경은 **binary-compatibility-validator**(`apiCheck`)로 감지합니다.
- `!!`, `lateinit`(테스트 제외), `GlobalScope`, `runBlocking`(테스트 제외), `println` 은 금지합니다.
- 값 객체는 `data class` / `value class` 로 두고 불변으로 만듭니다. `ByteArray` 를 그대로 노출하지 않고 방어적으로 복사합니다.
- 에러는 도메인 `sealed` 타입으로 정의합니다. 외부 예외(Kable, 플랫폼)는 어댑터 안에서 도메인 에러로 바꿉니다.
- **구조화된 동시성**을 지킵니다. 모든 코루틴은 호출자 스코프나 명시적 스코프에 속하고, 취소를 항상 처리합니다.
- `expect/actual` 은 최소한으로 씁니다. 포트 인터페이스 + 주입을 우선합니다.
- 포팅한 파일 헤더에 다음을 적습니다: `// Ported from vehicle-command@<짧은커밋> <원본경로> (Apache-2.0)`

---

## 5. 브랜치와 커밋 (Trunk-based)

### 5.1 브랜치 모델
```
main (트렁크: 항상 Green, 항상 릴리스 가능)
 ├─● feat/fr-012-lock-unlock   (수명 1~2일)  ─── PR → rebase merge
 └─● fix/replayed-vcsec-resp   ─────────────────── PR → rebase merge
```
- 트렁크는 `main` 하나입니다. **브랜치 수명은 1~2일**이고, 길어지면 쪼갭니다.
- 미완성 기능은 **피처 플래그**(내부 `ExperimentalTeslaApi` 옵트인 어노테이션 등) 뒤에 감춘 채 병합합니다.
- 릴리스는 `main` 에서 **태그**(`vX.Y.Z`)로 자릅니다.
- Superpowers의 worktree도 같은 규칙을 따릅니다. 작업 단위 하나에 worktree 하나를 쓰고, 끝나면 바로 병합하고 정리합니다.

### 5.2 커밋 규율
다음을 모두 충족할 때만 커밋합니다.
1. 빠른 테스트가 전부 통과
2. 컴파일 경고 0, detekt 경고 0, ktlint 위반 0
3. 하나의 논리적 작업 단위
4. 접두어로 구조/동작을 명시

```
struct: extract Reassembler from BleTransport          # 구조 변경
feat: verify session info HMAC before accepting epoch  # 동작 변경
fix: drop VCSEC response with stale routing address    # 결함 수정
test: add protocol.md GCM vector                       # 테스트만
docs: update pairing manual for WAIT status            # 문서
chore: bump Kable to x.y.z                             # 의존성·빌드

Refs: FR-012
```

### 5.3 PR
- 작게 유지합니다. 구조와 동작을 분리해 두면 리뷰가 쉬워집니다.
- **머지 조건**
  1. `tools/ci/verify.sh` 통과 (§6, 로컬 검증 게이트, ADR-0012)
  2. **AI 코드 리뷰 1회 통과**
     - Claude Code: `requesting-code-review`
     - Codex / Copilot CLI: 각 도구의 리뷰 기능
     - 리뷰 요약과 처리 결과를 PR 본문에 첨부합니다.
  3. PR 템플릿 체크리스트 완료
- 머지 방식은 **Rebase merge**입니다. Squash는 금지합니다(Tidy First 커밋 보존).
- 다음 변경이 포함된 PR에는 라벨을 붙이고 ADR을 링크합니다.
  - 의존성 추가나 변경: `architecture`
  - 암호, 키, 로그 관련 코드: `security`

### 5.4 저장소에 넣지 않는 것
- `{{REF_REPO_DIR}}` (원본 클론)
- 실차용 개인키, 실제 VIN, 마스킹하지 않은 BLE 캡처
- 서명 인증서, 프로비저닝 프로파일, `local.properties`

---

## 6. 로컬 검증 게이트 (`tools/ci/verify.sh`)

예전에는 GitHub Actions(`.github/workflows/ci.yml`)가 모든 푸시와 PR에서 이 게이트들을 실행했다. 혼자 +
에이전트로 작업하는 이 프로젝트는 iOS 시뮬레이터까지 포함해 전부 실행할 수 있는 Mac 한 대가 있어서, 그
인프라를 없애고 `tools/ci/verify.sh` 하나로 대체했다(ADR-0012). **모든 푸시와 PR 병합 전에 로컬에서 돌리고,
하나라도 실패하면 병합하지 않습니다.**

| 게이트 | 내용 | `verify.sh` 단계 |
|---|---|---|
| 포맷 | `ktlintCheck`(`lintKotlin`) | 1/6 |
| 정적 분석 | `detekt` (경고 0) | 1/6 |
| 빌드 | 경고를 에러로 처리 (`allWarningsAsErrors`) | 1/6 |
| 아키텍처 | 모듈 의존 방향 (빌드에 내포) | 1/6 |
| 공개 API | `apiCheck` | 1/6 |
| 공통 테스트 | `jvmTest` (벡터, 골든, 도메인, 유스케이스) | 1/6 |
| Android 단위 | `testAndroidHostTest` (AGP 9 내장 Kotlin) | 1/6 |
| iOS 테스트 | `iosSimulatorArm64Test` | 1/6 |
| 샘플 빌드 (Android) | `:samples:android:assembleDebug` | 1/6 |
| 서버 없음 확인 | 병합 매니페스트에 `INTERNET` 권한 없음 (NFR-014) | 2/6 |
| iOS 최소 버전 | `Teslable.framework`의 `MinimumOSVersion` 16.0 (NFR-009) | 3/6 |
| 샘플 빌드 (iOS) | `xcodegen generate` + `xcodebuild` (`CODE_SIGNING_ALLOWED=NO`) | 4/6 |
| 라이선스 | NOTICE 존재 확인, 포팅 파일 헤더 확인 (`tools/ci/check-license.sh`) | 5/6 |
| 비밀 스캔 | VIN 패턴(`[A-HJ-NPR-Z0-9]{17}`)과 PEM 개인키 탐지 (테스트 벡터 허용 목록 제외, `tools/ci/scan-secrets.sh`) | 6/6 |

> **"Red를 트렁크에 올리지 않는다"** 가 Trunk-based의 생명줄입니다. 푸시·병합 전에 로컬에서 `tools/ci/verify.sh` 를 돌립니다.
> `tools/ci/verify.sh`는 M0에서 작성한 `.github/workflows/ci.yml`이 하던 일을 그대로 재현하며, ADR-0012(2026-09-27)로
> GitHub Actions를 대체했다. macOS 전용이며(iOS 시뮬레이터·xcodebuild·plutil 필요), 클린 워크트리에서 병합 직전에
> 한 번 더 돌리는 것을 권장한다(독립된 클린 환경 검증이 없어진 것을 완화).

---

## 7. Clean Code
- 중복 제거를 최우선으로 합니다.
- 이름은 **프로토콜 용어를 그대로** 씁니다(유비쿼터스 언어): RoutableMessage, Domain, epoch, counter, session info, whitelist, RKE, closure.
- 싱글턴과 전역 상태를 만들지 않습니다. 의존성은 포트로 명시합니다.
- 함수는 작게 나누고 책임은 하나만 둡니다.
- 순수 로직(프레이밍, TLV, 매칭)과 I/O(BLE, 키스토어)를 분리합니다.
- 주석은 **"왜"** 만 씁니다. 원본과 일부러 다르게 구현한 곳에는 반드시 이유를 남깁니다.

---

## 8. 프로젝트 고유 지침

### 8.1 프로토콜 정확성
- 작업을 시작하기 전에 해당 주제의 `{{MANUAL_DIR}}` 매뉴얼 문서를 먼저 읽습니다. 계획과 PR에 참고한 매뉴얼 문서를 표기합니다.
- 바이트 수준 동작(TLV 순서, 플래그 포함 규칙, 요청 해시 절단, counter 규칙)은 **원본 코드 줄을 인용**해 테스트로 고정합니다.
- VCSEC는 **요청을 직렬화**합니다(동시 요청 금지). Infotainment는 uuid로 매칭합니다.
- 응답이 유실되면 결과를 **불확실(PossibleSuccess)** 로 반환합니다. 실패로 단정하지 않습니다.

### 8.2 보안과 개인정보
- 로그는 디버그 빌드에서만 TX/RX 헥사를 출력하고, VIN은 항상 마스킹합니다.
- 개인키와 세션 키는 **어떤 로그 레벨에서도** 출력하지 않습니다.
- 개인키는 가능한 한 하드웨어 보안 영역(Secure Enclave, Android Keystore)에서 생성하고 밖으로 내보내지 않습니다.
- 새 네트워크 호출을 추가하는 PR은 **서버 없음 원칙 위반**으로 보고 반려합니다.

### 8.3 플랫폼
- iOS: Kotlin/Native에서 CryptoKit(Swift 전용)은 쓸 수 없습니다. GCM 구현 방식은 ADR을 따릅니다.
- Android: Keystore 안에서 ECDH는 API 31 이상에서만 가능합니다. 대체 방식은 PRD와 ADR을 따릅니다.
- BLE 권한, 백그라운드 제약은 샘플 앱과 `{{LIB_DOCS_DIR}}` 의 platform-notes에 반영합니다.

### 8.4 실차 검증 체크리스트 (사용자 수행)
| 시나리오 | 확인 |
|---|---|
| 스캔: 광고 이름 매칭, RSSI | 내 차만 잡히는지 |
| 키 등록 → 키카드 태그 → 승인 | WAIT 이후 완료, 키 목록에 표시 |
| 차량 수면 중 잠금·해제 | VCSEC만으로 1초 안팎 |
| 수면 → 깨우기 → 공조 켜기 | Infotainment 세션 수립 |
| GetState 12개 카테고리 | 필드 파싱 |
| 앱 재실행 후 첫 명령 | 세션 캐시로 핸드셰이크 생략 |
| 차량 재부팅 후 명령 | epoch 변경을 복구 |
| 슬롯 3개 초과 | `MaxConnectionsExceeded` 안내 |
| BLE 거리 밖으로 이탈 중 명령 | 결과 불확실 처리 |

---

## 9. 완료 정의 (DoD)

**코드**
- [ ] 실패 테스트로 시작해서 통과시켰다 (Red → Green)
- [ ] 구조 변경을 별도 커밋으로 분리했다
- [ ] `tools/ci/verify.sh` Green, 경고 0
- [ ] 모듈 경계 위반 없음 (빌드가 보장)
- [ ] 공개 API 변경을 `apiDump` 로 반영하고 KDoc을 작성했다

**프로토콜**
- [ ] 참고한 `{{MANUAL_DIR}}` 매뉴얼 문서를 PR에 표기했다
- [ ] 원본 Go 코드와 동작이 같다 (다르면 이유를 주석과 ADR에)
- [ ] 매뉴얼과 원본의 불일치를 발견했다면 사용자에게 보고했다
- [ ] 관련 벡터·골든 테스트를 추가했다

**보안**
- [ ] 로그와 픽스처에 키, VIN 원문이 없다

**문서**
- [ ] 요구사항이 바뀌었다면 `{{PRD_FILE}}`, 설계가 바뀌었다면 `{{SDD_FILE}}`/ADR을 같은 PR에서 갱신했다
- [ ] 공개 API가 바뀌었다면 `{{LIB_DOCS_DIR}}` 해당 페이지를 갱신했다

**통합**
- [ ] AI 리뷰 통과, 요약을 PR에 첨부했다
- [ ] rebase merge로 `main` 에 병합했다

---

## 10. 세션 인계 (Handoff)

다음 경우 `{{HANDOFF_DIR}}YYYY-MM-DD-<slug>.md` 를 작성하고 새 세션을 시작합니다.
- 마일스톤이 끝났을 때
- 주제가 바뀌었을 때
- 컨텍스트가 길어졌을 때

```markdown
# Handoff — <주제> (YYYY-MM-DD)
## 목표          : 이 세션이 달성하려던 것 (FR ID)
## 완료          : 병합된 PR과 커밋, 통과한 테스트
## 진행 중       : 브랜치/worktree, 마지막 Red 테스트 이름
## 결정          : 새 ADR, 사용자 승인 사항
## 막힌 점       : 원본 코드 위치 + 질문
## 다음 한 걸음  : 바로 실행할 작업 1개와 검증 명령
## 읽을 파일     : 새 세션이 먼저 읽어야 할 문서 (키로 표기, 관련 `{{MANUAL_DIR}}` 매뉴얼 문서 포함)
```

---

## 11. 문서와 코드의 관계
- **문서가 코드보다 먼저 틀립니다.** 요구사항이 바뀌면 코드보다 문서를 먼저 고칩니다.
- 코드와 문서가 어긋난 것을 발견하면 어느 쪽이 옳은지 판단해서 **둘 중 하나를 반드시 고칩니다.**

다음 세 쌍은 항상 함께 움직입니다.

| 코드 | 문서 |
|---|---|
| 공개 API | `{{LIB_DOCS_DIR}}` + KDoc + `apiDump` |
| 프로토콜 동작, 원본과의 차이 | `{{SDD_FILE}}` + ADR |
| 요구사항 충족 여부 | `{{PRD_FILE}}` FR 상태 |
