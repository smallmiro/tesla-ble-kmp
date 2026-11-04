# Handoff — iPhone Duo two-pane layout and #118 merge (2026-11-04)

## 목표
- #118 상태 진단(SFR-023)과 응답 관찰(attempt, auth, closed)을 main에 넣는다.
- iOS 샘플에 iPhone Duo 안쪽 화면용 두 영역 배치(SFR-024)를 넣는다.

## 완료
- **PR #144**를 rebase merge 했다. main은 `45ea954`. 들어간 것:
  - #138 응답 관찰: `edbae4d`…`e089107`, 리베이스 후 해시 기준.
  - #142 Query 탭 상태 진단: `0032dee`, `497e199`, `c491a7d`.
  - SFR-024 커밋 8개: 빌드 22, 두 영역 배치, 라이트·다크 모드, 테슬라 스타일 첫 화면, 탭 막대, 수동 Drive 속도.
  - 리뷰 후 수정 3개:
    - VIN 시트가 탭을 바꿀 때마다 다시 열리던 문제. `ScreenContinuity.vinKnownChanged` 로 false → true 일 때만 연다.
    - 조회 전 "km/h" 표시를 "단위 미확인"으로 바꿨다.
    - 샘플 문서의 파일 표를 고쳤다(`QuickControlsBar` 제거, `HomeSummary` 추가).
- #138, #142는 #144로 대체되어 닫았다. 같은 내용이지만 리베이스로 해시가 달라 자동으로 닫히지 않았다.
- `tools/ci/verify.sh` 는 8단계 모두 통과했다(548s). Swift 시험 327개 중 새 `ScreenContinuityTests` 5개도 통과. 문서 수정 뒤 `check-manual-i18n.sh` 도 다시 돌려 통과했다.
- 이 세션은 수정 시험의 Red를 따로 돌려 보지 않았다. 시험을 쓴 직후 구현까지 넣었다.

## 진행 중
- 없음. 작업 트리는 `.claude/worktrees/m5-get-state`, 브랜치 `feat/iphone-duo-layout`. 내용은 main과 같고 원격에 남아 있다. 지워도 된다.

## 결정
- 새 ADR은 없다.
- #144 머지 방식은 사용자가 정했다. 리뷰에서 Major가 나오자 "고친 뒤 머지"를 골랐다.
- 기존 결정은 그대로다. ADR-0015 응답 인증 정책, FR-104 수정은 첫 #118 실차 수집 뒤에 진행한다.

## 막힌 점
- 없음. 실기기와 실차가 필요한 확인만 남았다.

## 다음 한 걸음
1. 빌드 22(`TeslableSampleDevice`)를 실기기에 설치하고 실기기 확인을 한다. 절차는 `samples/ios/README.md` 에 있다:
   - §상태 진단 실기기 확인 S1~S12(SFR-023)
   - §iPhone Duo 확인(SFR-024). VIN 미저장 상태에서 메뉴와 탭을 바꿔도 시트가 한 번만 뜨는지 함께 본다.
2. 같은 빌드로 §응답 인증 수집 시나리오 T1~T5를 수행한다. 로그는 `{{SAMPLE_LOGS_DIR}}` 에만 두고 커밋하지 않는다. 그 뒤 `protocol-analyst`(`analyze-monitor-logs`)에 넘긴다.
3. 분석 결과로 `library-developer` 가 `14-lib-reading-state` Part B와 FR-104 수정을 진행한다(ADR-0015).

## 읽을 파일
- `{{PLANS_DIR}}2026-11-01-118-state-diagnostics.md`: #118 계획과 남은 작업
- `{{SAMPLES_DOCS_DIR}}04-monitor-app.md`: §State diagnostics, §Wide and foldable screens
- `samples/ios/README.md`: 빌드 22 runbook, T1~T5
- `{{LIB_DOCS_DIR}}14-lib-reading-state.md`(getState, Part B), `{{LIB_DOCS_DIR}}18-lib-troubleshooting.md`(관찰 `[reply:*]` 줄)
- `{{ADR_DIR}}` ADR-0015: 응답 인증 정책
