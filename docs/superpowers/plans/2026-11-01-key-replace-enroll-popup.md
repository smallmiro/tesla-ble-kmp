# 키 삭제 후 다시 만들기(SFR-020)와 등록 진행 팝업(SFR-021) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 키·제어 탭에서 모니터 키를 지우고 새로 만드는 동작(SFR-020)과, 키 등록 요청 뒤 진행을 보여 주고 실제로 멈출 수 있는 팝업(SFR-021)을 iOS 샘플에 넣는다. Android는 같은 스펙으로 나중에 한다.

**Spec:** `{{SAMPLES_DOCS_DIR}}01-requirements.md`(SFR-020, SFR-021), `{{SAMPLES_DOCS_DIR}}05-key-control-tab.md` §Delete and recreate the key, §Enrollment progress popup. 참고 라이브러리 문서: `{{LIB_DOCS_DIR}}13-lib-pairing.md`(§Deleting or replacing a key locally, §Key accessibility, §Enrolling a key), `17-lib-platform-notes.md`(§Session cache, Swift 인자), `16-lib-errors.md`. 새 라이브러리 API는 필요 없다(`keys.get`, `keys.delete`, `keys.getOrCreate`, `addKeyRequest`, `sessionInfo` 모두 "사용 가능").

## Global Constraints

- 라이브러리 모듈은 바꾸지 않는다. 문서에 "사용 가능"으로 적힌 `:sdk` API만 쓴다. 필요한 API가 없으면 멈추고 `library-api-request` 를 쓴다(이번에는 필요 없었다).
- `KeyStoreError.Locked` 에서는 키를 지우거나 다시 만들지 않는다. 지우기가 실패하면 새 키를 조용히 만들지 않는다.
- 옛 키 ID에 붙은 별명(SFR-019)은 새 키로 옮기지 않는다.
- TDD(순수 로직은 실패 테스트부터), Tidy First(구조 변경 `struct:` 커밋을 먼저), 샘플 스펙 MD + HTML ko/en/ja 같은 PR, `tools/ci/check-manual-i18n.sh`.
- 역할: iOS `ios-developer`(sonnet/high), Android `android-developer`, HTML ko/ja `docs-translator`.

### Task A: SFR-020 키 삭제 후 다시 만들기 (iOS, 빌드 16)

- [x] **Step 1: 실패 테스트** — `Tests/KeyReplacementTests.swift`: `KeyReplacer` 순서(읽기 → 연결 끊기 → 핸들 버리기 → 지우기 → 만들기 → 다시 연결), 잠금이면 아무것도 바꾸지 않음, 지우기 실패면 새 키를 만들지 않고 옛 키를 복원, 만들기 실패면 재시도·재연결 없음, 확인 문구의 네 가지 말, 버튼 활성 규칙.
- [x] **Step 2:** 구현: `KeyReplacement.swift`, `MonitorEngine.replaceKey()`(연결 처리 결정: 쓰던 연결만 먼저 끊고 새 키로 다시 연결), `KeyControlModel.replaceKey()`, 1단계 버튼과 확인 대화상자. `CURRENT_PROJECT_VERSION` 15.
- [x] **Step 3:** 문서: `01-requirements.md`(SFR-020), `05-key-control-tab.md`, HTML en/ko/ja, `samples/ios/README.md` 실기기 체크리스트(R1~R8).
- [x] **Step 4 (리뷰 반영):** 손상된 키(`stored key is corrupted`, 문서에 적힌 경우만) 복구 경로, 엔진 수준의 문(`KeyOperationGate`: 다른 탭의 동작·연결과 교체가 서로 막음, 핸들 캐시 세대), 지우기 실패 뒤 복원 결과를 따름(`lost`/`unverified`), `KeyControlBackend` 이음매와 `KeyControlModel` 시험.
- [ ] **Step 5:** 실기기 확인(R1~R11), AI 리뷰, PR (Refs: SFR-020).

### Task B: SFR-021 키 등록 진행 팝업 (iOS, 빌드 16)

- [x] **Step 1:** 구조 변경 먼저(`struct:`): `BleClient.pair` 가 줄 대신 `EnrollmentUpdate` 단계를 보낸다(Control 탭 결과 줄은 그대로).
- [x] **Step 2: 실패 테스트** — `Tests/EnrollmentPopupTests.swift`: 단계(요청 → 카드 → 확인 → 성공/미확인/실패/취소), 3초 간격 확인의 시도 번호·경과·남은 시간(`m:ss`, 0 아래로 내려가지 않음), 끝난 뒤의 늦은 알림 무시, 취소 요청과 취소 결과의 문구("되돌릴 수 없"), 취소가 실제로 2분 대기를 멈춤(`EnrollmentWaiter`).
- [x] **Step 3:** 구현: `EnrollmentProgress.swift`(`EnrollmentUpdate`, `EnrollmentPopup`), `EnrollmentWaiter.wait(onAttempt:)`, `BleClient.pair` 의 취소 처리, `KeyControlModel`(`startEnroll`, `cancelEnroll`, 연결·키가 바뀌면 팝업 정리), 시트 화면.
- [x] **Step 4:** 문서: `01-requirements.md`(SFR-021), `05-key-control-tab.md` §Enrollment progress popup, HTML en/ko/ja, `samples/ios/README.md` 실기기 체크리스트(P1~P8).
- [ ] **Step 5:** 실기기 확인(P1~P8). 특히 P4: 취소가 `addKeyRequest` 수집을 실제로 끝내는지(SKIE 취소 전파)는 기기에서만 확인할 수 있다. AI 리뷰, PR (Refs: SFR-021).

### Task C: Android (예정, 같은 스펙)

- [ ] SFR-019·020·021을 Android `KeyControl.kt`/`KeyControlState.kt`/화면에 같은 규칙으로 구현하고(순수 규칙은 JVM 단위 테스트), 스펙 상태를 "planned"에서 "built"로 고친다(MD + HTML ko/en/ja).
