# Handoff — Phase 0 준비 완료 (2026-09-26)

## 목표
`{{HANDOFF_FILE}}` §9 Phase 0: 착수 파일 생성, 원본 저장소 확인, PoC 확인, 개발 매뉴얼 대응표 작성.

## 완료
- git 저장소 초기화 (`main`, 원격 없음). 커밋 5개:
  - `docs:` AGENTS.md, CLAUDE.md, `{{PATHS_FILE}}`, `{{WORKFLOW_FILE}}`, `{{HANDOFF_FILE}}` (부록 A-1~A-4, 바이트 동일)
  - `chore:` .gitignore, .github/pull_request_template.md, .claude/settings.json (A-5, A-6)
  - `test:` `{{POC_DIR}}TeslaProtocolCore.kt`, `PocTest.kt` (A-7, A-8)
  - `docs:` `{{MANUAL_DIR}}` + `documents/html/` (사용자 제공 개발 매뉴얼, 읽기 전용)
  - `docs:` `{{PATHS_FILE}}` 의 `MANUAL_INDEX` = `{{MANUAL_DIR}}README.md` 기록
- `{{REF_REPO_DIR}}` HEAD = `{{REF_REPO_COMMIT}}` 확인. `.gitignore` 로 제외됨 (`git status` 에 나타나지 않음).
- 원본 `hanoff.md` 를 `HANDOFF.md` 로 이름 변경 (`HANDOFF_FILE` 키와 일치시키기 위함).
- 툴체인: Java 25, Xcode 26.6, Android SDK·Android Studio 있음. `kotlinc`, `gradle`, `go`, `protoc` 없음 → Gradle 래퍼 + Wire 플러그인으로 충분. PoC는 kotlinc 부재로 이 세션에서 재실행하지 않음.

## 진행 중
없음. Phase 1(PRD) 착수 대기.

## 결정
- 개발 매뉴얼 `documents/` 를 저장소에 커밋함 (다른 에이전트가 클론해도 1차 가이드를 볼 수 있어야 하므로).
- `MANUAL_INDEX` = `{{MANUAL_DIR}}README.md`. 규칙·불변 조건·상수 카드는 `{{MANUAL_DIR}}00-agent-guide.md`.

## 막힌 점 / 보고 사항
1. **PoC 벡터와 protocol.md 예제 불일치 (원본·매뉴얼은 일치, PoC가 다름)**
   - `{{MANUAL_DIR}}10-porting-guide.md` §0.3 항목 5 와 원본 `internal/authentication/protocol_doc_test.go:34-39`, `pkg/protocol/protocol.md:664` 는 메타데이터에 `FLAGS=2` TLV(`...070400000002ff`)를 포함하고 태그 `c228e0ff64991481db3a7bbc133696c5` 를 기대함.
   - `{{POC_DIR}}PocTest.kt` 항목 5·6 은 `flags=0`(FLAGS TLV 없음, `...050400000007ff`)으로 AAD를 만들고 태그 `8e128da165f162f4d7d2c8da866cf82a` 를 기대함. 암호문 `38038e8c0f2e` 는 AAD와 무관하므로 양쪽 모두 같음.
   - 조치: M0에서 commonTest로 옮길 때 **protocol.md 벡터(FLAGS=2, 태그 c228e0ff…)를 정본 케이스**로 추가하고, PoC의 flags=0 케이스는 "FLAGS 생략 규칙" 검증용 보조 케이스로 유지. PoC 파일 자체는 부록 A 원문이므로 수정하지 않음.
2. (해결됨, Phase 1 중) 2026-09-26 사용자가 `{{REPO_URL}}` 를 공개로 생성. 원격 연결·푸시 완료. 저장소 설정: rebase merge만 허용, squash·merge commit 비활성, 머지 후 브랜치 삭제. `main` 브랜치 보호: PR 필수(승인 수 0), 관리자 포함 적용, 선형 히스토리, force-push·삭제 금지, 대화 해결 필수, 상태 검사 strict(컨텍스트는 M0에서 CI 잡 이름으로 채움). 이후 모든 변경은 브랜치 → PR → rebase merge 로만 `main` 에 들어간다.

## 다음 한 걸음
Phase 1 PRD: `brainstorming` 스킬로 `{{HANDOFF_FILE}}` §10 열린 질문 10개를 **한 번에 하나씩** 결정 → `{{PRD_FILE}}` 작성 → 🛑 승인.
검증: `{{PRD_FILE}}` 의 각 FR/NFR 에 근거 매뉴얼 문서(아래 대응표)가 표기되어 있는지 확인.

## 읽을 파일
`{{PATHS_FILE}}` → `{{AGENTS_FILE}}` → `{{HANDOFF_FILE}}` §2~§5, §10, §11 → `{{MANUAL_DIR}}README.md` → `{{MANUAL_DIR}}00-agent-guide.md` §3~§4 → `{{MANUAL_DIR}}10-porting-guide.md`

---

## 주제 → 개발 매뉴얼 대응표 (PRD·SDD·계획에서 근거로 인용)

| 주제 | 매뉴얼 문서 (`{{MANUAL_DIR}}` 기준) | 원본 Go (`{{REF_REPO_DIR}}` 기준) |
|---|---|---|
| 작업 규칙, 불변 조건 23개, 상수·UUID 카드, 흔한 함정 | `00-agent-guide.md` §2~§5 | – |
| 패키지 계층, 명령 하나의 전체 경로, receiverKey 매칭, 동시성, 세션 캐시 구조, 테스트 지도 | `01-architecture.md` §2~§6, §10 | `internal/dispatcher/*`, `pkg/cache/` |
| BLE 전송: GATT UUID, 광고 이름, 스캔, 연결·재시도, MTU/블록, 프레이밍·재조립, 오류, 플랫폼 차이, 교체 체크리스트 | `02-ble-transport.md` §1~§13 | `pkg/connector/ble/ble.go` |
| RoutableMessage, 도메인, 라우팅 주소·uuid 규칙 | `03-protocol.md` §1~§3; `10-porting-guide.md` §4 | `internal/dispatcher/dispatcher.go` `Send`, `receiver.go` |
| 메타데이터 TLV | `03-protocol.md` §4; `10-porting-guide.md` §6 | `internal/authentication/metadata.go` |
| 테스트 키·테스트 벡터 | `03-protocol.md` §6; `10-porting-guide.md` §0.3 | `pkg/protocol/protocol.md`, `internal/authentication/protocol_doc_test.go` |
| 핸드셰이크, ECDH/KDF, 세션정보 HMAC 검증, 시계 원점 | `03-protocol.md` §7; `10-porting-guide.md` §5 | `internal/dispatcher/session.go` `processHello`, `internal/authentication/{signer,native}.go` |
| 명령 인증 AES-GCM (AAD, nonce, counter, expires_at) | `03-protocol.md` §8.2; `10-porting-guide.md` §6 | `internal/authentication/{signer,peer}.go` |
| 응답 복호화, request_hash, 슬라이딩 윈도우 | `03-protocol.md` §9.2; `10-porting-guide.md` §7 | `internal/dispatcher/dispatcher.go` `decrypt`, `internal/authentication/{peer,window}.go` |
| 세션 복구(MUST 규칙), 재시도 정책, desync 자동 복구 | `03-protocol.md` §9.4; `08-errors.md` §4~§6; `10-porting-guide.md` §8 | `internal/authentication/signer.go` `UpdateSessionInfo`, `pkg/protocol/error.go` |
| 세션 캐시 파일 형식 (Go 호환) | `01-architecture.md` §6; `10-porting-guide.md` §9; `09-recipes.md` R2 | `pkg/cache/cache.go`, `internal/dispatcher/session.go` `CacheEntry` |
| VCSEC 응답 종료 규칙, 다중 응답, WAIT/ERROR 해석 | `03-protocol.md` §11; `08-errors.md` §3.5 | `pkg/vehicle/vcsec.go` `unmarshalVCSECResponse`, `readUntil` |
| VCSEC 페이로드 (RKE, closure, whitelist, InformationRequest, VehicleStatus) | `03-protocol.md` §12; `05-command-catalog.md` A~C | `pkg/protocol/protobuf/vcsec.proto`, `pkg/vehicle/{vcsec,security,state}.go` |
| 키 등록 (add-key-request, NFC 태그, 등록 확인), KeychainError 코드 | `10-porting-guide.md` §10; `09-recipes.md` R4, R5; `08-errors.md` §3.7 | `pkg/vehicle/security.go` `SendAddKeyRequestWithRole` |
| 역할(Role), 키 형태(KeyFormFactor) | `03-protocol.md` §3; `00-agent-guide.md` §4.2 | `pkg/protocol/protobuf/keys.proto`, `protocol.md` "Roles" |
| Infotainment 페이로드, GetState 12개 카테고리, 응답 해석 | `03-protocol.md` §13; `05-command-catalog.md` D~I; `04-go-api-reference.md` pkg/vehicle | `pkg/protocol/protobuf/car_server.proto`, `pkg/vehicle/{infotainment,climate,charge,...}.go` |
| 명령 카탈로그 전체 표 (도메인, 인증 요구, 인자 파싱) | `05-command-catalog.md` A~I | `cmd/tesla-control/commands.go` |
| 공개 API 시그니처 (Vehicle, 옵션, 오류 인터페이스) | `04-go-api-reference.md` pkg/protocol, pkg/vehicle, pkg/connector | `pkg/vehicle/vehicle.go`, `pkg/protocol/{error,key}.go`, `pkg/connector/connector.go` |
| 에러 모델 (protocol.Error, MessageFault 표, NominalError, GenericError) | `08-errors.md` §1~§3 | `pkg/protocol/error.go`, `universal_message.proto`, `errors.proto` |
| 트러블슈팅 매트릭스, 디버그 로그, TX/RX hex 디코딩 | `08-errors.md` §7~§9 | `cmd/tesla-control -debug` |
| 테스트 작성 (testSender, dummyConnector = FakeVehicle 원형) | `09-recipes.md` R15; `01-architecture.md` §10 | `pkg/vehicle/*_test.go`, `internal/dispatcher/dispatcher_test.go`, `internal/authentication/verifier.go` |
| 포팅 단계별 체크리스트, 검증 절차, 플랫폼·보안 체크리스트 | `10-porting-guide.md` 전체 (특히 §11, §12, 부록) | – |
| 골든 픽스처 수집 (`tesla-control -ble -debug`) | `06-cli-tools.md` tesla-control; `08-errors.md` §9.2; `10-porting-guide.md` §11 | `cmd/tesla-control/` |
| 범위 밖 참고 (HTTP 프록시, Fleet API, JWS) | `07-http-proxy.md`; `04-go-api-reference.md` pkg/account·proxy·sign | – |
