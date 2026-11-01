# #118 1단계: 상태 조회 진단과 응답 인증 수집 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** iOS 샘플에서 사용자가 직접 `getState` 를 호출해 값을 보고, 실차 수집에 필요한 응답 인증 관찰을 로그에 남긴다.

**Architecture:** 라이브러리는 자기 요청의 응답을 도메인과 상관없이 관찰하고 복호화 결과를 메타데이터로 덧붙인다. 샘플은 Query 탭에 수동 진단 영역을 추가한다. 실차 수집은 runbook과 `protocol-analyst` 보고서로 한다.

**Tech Stack:** Kotlin Multiplatform, FakeVehicle, SwiftUI, SKIE.

**Spec:** #118, `{{ADR_DIR}}0015-reply-authentication-policy.md`, `{{PRD_FILE}}` FR-033, FR-105, FR-112, `{{SAMPLES_DOCS_DIR}}01-requirements.md`, `04-monitor-app.md`. 사용 문서: `14-lib-reading-state.md` A8, `16-lib-errors.md`, `18-lib-troubleshooting.md` §Observation log.

**범위 밖 (#118은 열어 둔다):** 미디어 제어와 탑승 조건 확인(#117 뒤), Android(iOS 수집 뒤), FR-105 구현, 클러스터 속도 표시(단위 확인 뒤).

## Global Constraints

- PR 세 개: Task 1은 라이브러리, Task 2는 iOS 샘플, Task 3은 수집 결과. 샘플은 Task 1 머지 뒤 시작한다.
- 복호화한 내용을 로그·관찰에 남기지 않는다(FR-112). 관찰은 원래 봉투와 결과 메타데이터만 쓴다.
- "인증됨"처럼 응답 전체를 표시하는 말을 쓰지 않는다. `auth=` 는 페이로드 검사 결과일 뿐이고 바깥 `operation_status` 는 AES-GCM 범위 밖이다(ADR-0015).
- 소유 표시는 대응 관계일 뿐 인증된 성공이 아니다.
- 샘플은 문서에 "사용 가능"으로 적힌 `:sdk` API만 쓴다(절대 규칙 14). Swift는 모든 인자를 넘긴다.
- 조회는 사용자 동작으로만 한다. 새 주기 조회와 자동 재시도는 없다. `wakeUp()` 을 호출하지 않는다. Infotainment 핸드셰이크가 잠든 차에 주는 영향은 미확인이므로 "깨우지 않는다"고 쓰지 말고 "`wakeUp()` 을 호출하지 않는다"고 쓴다.
- 속도는 원시 값과 "단위 미확인"으로 표시한다. km/h로 변환하지 않는다.
- TDD, `tools/ci/verify.sh`, 사용 문서와 샘플 스펙 MD + HTML ko/en/ja, Rebase merge.
- 역할: Task 1 `library-developer`, Task 2 `ios-developer`, Task 3 `protocol-analyst`, HTML ko/ja `docs-translator`.

## 결정 기록 (Claude·Codex 협의, 2026-11-01, main `addd545`)

| 항목 | Claude 제안 | Codex 의견 | 최종 판단 |
|---|---|---|---|
| 순서 | 관찰 → 샘플 → 수집 → #117 | 동의. 단 이것이 #118 완료는 아니다 | 채택. #118은 열어 두고 남은 일을 적는다 |
| 인증 표시 | `authenticated/plaintext/failed` | 반대. 응답 전체를 인증했다고 말하게 된다 | Codex 채택. `auth=gcm-ok` 등 검사 결과로 쓴다 |
| 늦은 응답 대응 | 최근 닫은 주소 표시 | 제한된 연결 단위 이력과 불투명한 시도 번호 권장 | 채택. `attempt=N`, `closed` |
| 늦은 응답 검증 | – | 닫힌 요청의 응답은 복호화를 거치지 않으므로 검증할 수 없다 | 이번에는 대응만 본다. 검증은 하지 않고 한계로 보고한다. 재전송 창 상태를 건드리지 않기 위해서다 |
| UI 위치 | 키·제어 탭 | Query 탭. 진단 조회가 목적에 맞다 | Codex 채택 |
| 펌웨어 기록 | `getState` 값 | `SoftwareUpdateState.version` 은 설치 버전이라는 근거가 없고, `car_version` 은 미지원 확장 필드 | 차량 화면에서 사람이 runbook에 기록한다 |
| "깨우지 않음"의 뜻 | – | 명시적 깨우기 금지인가, 잠든 상태 유지 보장인가? | 명시적 깨우기 금지. 잠든 차 시험은 확인 대화상자가 있는 별도 동작으로만 한다 |

## Review Focus

1. 복호화된 위치·곡 제목이 관찰이나 샘플 로그로 나가는 경우: `getState` 값은 화면에만 나온다. Task 1 `infotainmentReplyContentNeverReachesLogsOrObservations`(확장), Task 2 `resultLogHoldsMetadataOnly`.
2. 조회 중 연결이 바뀌거나 끊길 때: 늦은 결과가 새 연결의 화면을 덮지 않는다. Task 2 `lateResultFromOldConnectionIsIgnored`.
3. 다른 탭의 키 교체나 명령과 겹칠 때: 조회가 거부되고 키가 바뀌지 않는다. Task 2 `queryIsRefusedWhileKeyOperationRuns`.
4. 이력 상한을 넘는 닫힌 요청: 메모리가 늘지 않고 가장 오래된 항목이 `other` 로 돌아간다. Task 1 `closedHistoryIsBounded`.
5. 연결이 끊긴 뒤의 시도 번호와 이력: 새 연결에 옛 이력을 남기지 않는다. Task 1 `closedHistoryClearsOnDisconnect`.

---

### Task 1: 자기 요청 응답 관찰 (라이브러리, PR 1)

**Files:**
- Modify: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/observe/ObservationLog.kt`
- Modify: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/dispatcher/Dispatcher.kt`
- Modify: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/dispatcher/PendingRequest.kt`
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/dispatcher/DispatcherObservationTest.kt`
- Docs: `documents/md/18-lib-troubleshooting.md`, `documents/html/{en,ko,ja}/18-lib-troubleshooting.html`

**Interfaces:**
- Produces 줄 형식: `[reply:vcsec]`, `[reply:infotainment]` 줄 끝에 ` attempt=<N> auth=<값>`.
- `auth` 값: `gcm-ok`(검증과 재전송 검사 통과), `gcm-failed`(검증 실패), `gcm-replay`(재전송 검사에서 버림), `gcm-no-session`, `none`(`AES_GCM_Response_data` 없음).
- `ObservedAt.INFOTAINMENT_REPLY("reply:infotainment")`, `AddressOwner.CLOSED("closed")`.
- `[drop:no-handler]` 의 주소가 닫힌 요청이면 `to=address(16B, closed)` 와 ` attempt=<N>`.

- [ ] **Step 1: 실패 시험** — `DispatcherObservationTest` 에 추가.
  - `ownInfotainmentReplyIsObservedWithGcmOk`: 암호화 응답이 `[reply:infotainment]`, `attempt=1`, `auth=gcm-ok`, `payload=encrypted(`.
  - `plaintextReplyReportsAuthNone`, `tamperedReplyReportsGcmFailed`, `duplicateReplyReportsGcmReplay`: 양 도메인.
  - `waitThenLateReplyShareTheAttempt`: `WAIT` 뒤 같은 주소로 온 응답이 `to=address(16B, closed) ... attempt=` 같은 번호.
  - `foreignAddressStaysOther`, `closedHistoryIsBounded`(상한 32), `closedHistoryClearsOnDisconnect`.
  - `infotainmentReplyContentNeverReachesLogsOrObservations` 를 `gcm-ok` 경로까지 확장: 복호화한 표지 문자열이 어떤 관찰 줄에도 없다.
- [ ] **Step 2: 실패 확인** — `./gradlew :application:jvmTest --tests '*DispatcherObservationTest*'`.
- [ ] **Step 3: 구현** — 관찰 호출을 `decryptIfNeeded` 뒤로 옮기고, `decryptIfNeeded` 가 결과와 함께 `auth` 값을 돌려주게 한다. 줄은 **복호화 전 원래 메시지**로 만든다. `PendingRequest` 에 `attempt: Long` 을 둔다(요청마다 디스패처 카운터 증가, 재시도는 새 번호). 닫을 때 `(PendingKey, attempt)` 를 FIFO 32개 이력에 넣고 연결이 끊기면 비운다. 닫힌 키로 온 응답은 복호화하지 않는다. 지금처럼 관찰이 꺼져 있으면 문자열을 만들지 않는다.
- [ ] **Step 4: 통과 확인** — 같은 명령, 이어서 `./gradlew :application:jvmTest`.
- [ ] **Step 5: 문서** — `18-lib-troubleshooting` 표에 `[reply:infotainment]`, `attempt=`, `auth=`, `closed` 를 추가한다. `auth=gcm-ok` 는 페이로드 검사이고 `operation_status` 는 포함하지 않으며, `closed` 는 대응 관계일 뿐 검증되지 않았음을 적는다.
- [ ] **Step 6: 커밋, PR** — `feat: observe own replies with attempt and auth outcome` (Refs: FR-105, ADR-0015, #118). 리뷰는 FR-112 누출을 중심으로 한다.

### Task 2: Query 탭 상태 진단 (iOS, PR 2)

**Files:**
- Create: `samples/ios/Sources/StateDiagnostics.swift`, `samples/ios/Tests/StateDiagnosticsTests.swift`
- Modify: `samples/ios/Sources/MonitorEngine.swift`, `MonitorModel.swift`, `Views/QueryTab.swift`, `LogLine.swift`, `project.yml`(빌드 번호 +1)
- Docs: `samples/documents/md/01-requirements.md`(SFR-023), `04-monitor-app.md`, 샘플 HTML ko/en/ja, `samples/ios/README.md`

**Interfaces:**
- Consumes (`14-lib-reading-state.md` A8): `vehicle.startSession(...)`, `vehicle.getState(category:timeout:)`, `vehicle.vehicleStatus(timeout:)`.
- Produces: `struct StateReading { category, outcome, at, durationMs, fields: [(String, String)] }`, `StateDiagnostics` 순수 규칙(버튼 활성, 값 표시, 로그 문구).

- [ ] **Step 1: 실패 시험** — `StateDiagnosticsTests`.
  - `buttonsNeedKeyLinkAndAwakeCar`: 연결, 등록된 키, 최근 `sleepStatus == AWAKE` 가 모두 있어야 활성. `UNKNOWN` 은 비활성.
  - `speedShowsRawValueWithUnknownUnit`: `speed = 0` 은 `0 (단위 미확인)`, 없는 값은 `—`. 0으로 바꾸지 않는다.
  - `resultLogHoldsMetadataOnly`: 로그 문구에 범주, 결과, 소요 시간, 오류 타입만 있고 값과 곡 제목이 없다.
  - `lateResultFromOldConnectionIsIgnored`, `queryIsRefusedWhileKeyOperationRuns`, `doubleTapRunsOneQuery`.
  - `uncertainAndFailureAreDistinct`: `ProtocolFault` 응답 크기 초과, `Timeout`, `NoSession` 문구가 서로 다르다.
- [ ] **Step 2: 실패 확인** — 샘플 시험 실행(`samples/ios/README.md` 의 명령).
- [ ] **Step 3: 구현** — 버튼 `Drive`, `Media`, `MediaDetail`, 그리고 `Location` 은 넣지 않는다(위치 값을 다룰 이유가 없다). 조회 순서: `vehicleStatus` → 세션이 없으면 `startSession(INFOTAINMENT)` → `getState` 한 번. `Uncertain` 은 재시도 없이 그대로 보여 준다. `KeyOperationGate` 를 쓴다. 기존 `record` 는 쓰지 않는다(요약이 로그로 간다).
- [ ] **Step 4: 잠든 차 시험 동작** — 별도 버튼 "잠든 차에 보내기(실험)". 확인 대화상자에 차가 깨어날 수 있다고 적고, 승인하면 `sleepStatus` 를 무시하고 같은 순서로 한 번 보낸다.
- [ ] **Step 5: 문서** — SFR-023 추가(iOS built, Android planned). 사용한 사용 문서 절을 PR 본문에 적는다.
- [ ] **Step 6: 커밋, PR** — `feat: Query tab state diagnostics on iOS` (Refs: SFR-023, FR-033, #118). 클러스터 속도와 SFR-022 문구는 바꾸지 않는다.

### Task 3: 실차 수집과 분석 (PR 3, 사용자 차량 필요)

**Files:**
- Modify: `samples/ios/README.md`(수집 시나리오)
- Create: `docs/analysis/2026-11-XX-118-state-and-reply-auth.md`

- [ ] **Step 1: 시나리오 작성** — 첫 줄에 펌웨어·모델·앱 빌드를 차량 화면에서 옮겨 적는다. T1 정차 `Drive`/`Media`/`MediaDetail` 각 5회, T2 주행 중 `Drive`(경로 없음·있음), T3 긴 곡 제목, T4 잠금·해제 각 5회(`WAIT` 대응), T5 잠든 차 실험 1회. 안전을 위해 주행 중 조작은 동승자가 한다.
- [ ] **Step 2: 수집** — 사용자가 실행하고 로그를 `{{SAMPLE_LOGS_DIR}}` 에 둔다.
- [ ] **Step 3: 분석** — `protocol-analyst` 보고서. 분모는 사용자 요청 수와 전송 시도 수를 나눠 센다. 사건이 없었던 항목(MTU, 세션 불일치)은 "미관찰"로 적고 결론을 내지 않는다.
- [ ] **Step 4: 반영** — 사용 문서의 미확인 표기, ADR-0015의 `WAIT` 판단, #118 체크리스트를 갱신하고 FR-105 계획을 시작할 수 있는지 보고한다.
