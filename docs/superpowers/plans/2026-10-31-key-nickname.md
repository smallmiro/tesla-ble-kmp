# 로컬 키 별명 (SFR-019) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 차량에는 키 이름 칸이 없다(VCSEC `KeyMetadata`는 `keyFormFactor`뿐). 사용자가 키 목록에서 키를 구분할 수 있도록, Control 탭 키 목록의 각 행에 **이 폰에만 저장되는 별명**을 붙인다. 별명은 차량으로 가지 않는다.

**Spec:** `{{SAMPLES_DOCS_DIR}}01-requirements.md`(SFR-019), `{{SAMPLES_DOCS_DIR}}04-monitor-app.md` §Key nickname. 참고 라이브러리 문서: `{{LIB_DOCS_DIR}}14-lib-reading-state.md`(`WhitelistEntry.keyIdSha1Hex`, §A4 `listKeys()`), `13-lib-pairing.md`, `17-lib-platform-notes.md`(Swift 인자). 새 라이브러리 API는 필요 없다.

## Global Constraints

- 라이브러리 모듈은 바꾸지 않는다. 문서에 "사용 가능"으로 적힌 `:sdk` API(`WhitelistEntry.keyIdSha1Hex`)만 쓴다.
- 키 ID(공개키 SHA-1 16진수)로 저장한다(슬롯은 바뀔 수 있다). 키 ID가 없는 항목은 별명을 가질 수 없다. 공개키 전문·VIN은 화면과 로그에 쓰지 않는다.
- 입력: 앞뒤 공백을 자르고 최대 32자, 빈 입력은 삭제.
- TDD(순수 로직은 실패 테스트부터), Tidy First(구조 변경 `struct:` 커밋을 먼저), 샘플 스펙 MD + HTML ko/en/ja 같은 PR, `tools/ci/check-manual-i18n.sh`.
- 역할: iOS `ios-developer`(sonnet/high), Android `android-developer`, HTML ko/ja `docs-translator`.

### Task A: iOS (빌드 14)

- [x] **Step 1: 실패 테스트** — `samples/ios/Tests/KeyNicknameTests.swift`: 정규화(자르기, 32자, 빈 입력 = 삭제, 글자 단위 길이), 저장소 set/get/remove(주입한 `UserDefaults`, 키 ID 소문자 처리, 키 ID 없음, 너무 길면 이전 이름 유지). 타입이 없어 컴파일이 실패하는 것을 확인.
- [x] **Step 2:** 구조 변경 먼저: `BleClient.KeyRow`에 `keyId` 추가(`struct:`, 동작 변경 없음).
- [x] **Step 3:** `KeyNickname`·`KeyNicknameStore`(`Sources/KeyNickname.swift`) 구현 → PASS. `SampleModel`에 별명 상태, `ControlTab` 행에 별명 표시와 **이름**/**이름 수정** 버튼, 입력창(32자 제한, 폰에만 저장된다는 안내). `CURRENT_PROJECT_VERSION` 14.
- [x] **Step 4:** 문서: `01-requirements.md`(SFR-019), `04-monitor-app.md` §Key nickname, `03-current-apps.md`, HTML en/ko/ja, `samples/ios/README.md` 실기기 체크리스트(N1~N11).
- [ ] **Step 5:** 실기기 확인(N1~N11), AI 리뷰, PR (Refs: SFR-019).

### Task B: Android (예정, 같은 스펙)

- [ ] **Step 1: 실패 테스트** — iOS와 같은 규칙의 순수 타입(`KeyNickname`, 주입한 `SharedPreferences` 대용 저장소)을 JVM 단위 테스트로.
- [ ] **Step 2:** 구현 → PASS. Control 화면 키 목록 행에 별명 표시와 편집 대화상자, 키 ID는 `WhitelistEntry.keyIdSha1Hex`.
- [ ] **Step 3:** 스펙 상태를 "planned"에서 "built"로 고치고(MD + HTML ko/en/ja), 실기기 체크리스트는 Android 샘플의 확인 절차를 두는 곳(스펙 04의 실차 체크리스트)에 추가.
