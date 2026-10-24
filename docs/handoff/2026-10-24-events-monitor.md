# Handoff — 역할 에이전트 분리와 Vehicle.events·모니터 앱 (2026-10-24)

## 목표          : 
- #90 역할 에이전트·샘플 문서 구조 분리 (완료)
- #91 `Vehicle.events`(FR-035), `VehicleStatus.gear`(FR-036), iOS 키 접근성(FR-037), Android·iOS 모니터 앱(SFR-010~017)

## 완료          :
- PR #92 역할 에이전트 5개(library-developer opus/max, protocol-analyst opus/xhigh, android/ios-developer sonnet/high, docs-translator sonnet/low), 스킬 4개, `samples/documents` 두 벌(MD + HTML ko/en/ja), 게이트 확장, `tools/analysis/pbwire.py`
- PR #93 모니터 앱 스펙(`samples/documents/md/04-monitor-app.md`)과 계획 `docs/superpowers/plans/2026-10-24-vehicle-events-monitor.md`
- PR #94 `VehicleStatus.gear` (브로드캐스트에서만 실차 확인, 조회 응답은 미확인)
- PR #95 `Vehicle.events` (평문 브로드캐스트 게이트, 원본 바이트, oneof 필드 1·3 분리 읽기)
- PR #98 iOS 키 접근성 `KeyAccessibility` + ADR-0014 + `KeyStoreError.Locked`
- PR #100 Android 모니터 앱(단위 테스트 44), PR #101 iOS 모니터 앱(Swift 테스트 52, 버전 0.4.0 (10))
- 매 PR `tools/ci/verify.sh` 8단계 통과, 새 컨텍스트 AI 리뷰 반영

## 진행 중       :
- #91 Task 2 스파이크: 브랜치 `spike/ios-background`(머지 안 함, worktree `.claude/worktrees/spike-ios-bg`), 빌드 9, 절차 `docs/handoff/2026-10-24-ios-background-spike.md`(스파이크 브랜치 안). **사용자 실차 확인 대기**
- #91 Task 8: 스캔 필터 변경 — Task 2 결과로 필요 여부 결정(필요하면 계획을 먼저 고친다)
- #91 Task 11: 두 모니터 앱 실차 확인 — Android 체크리스트는 PR #100 본문, iOS는 `samples/ios/README.md` "모니터 앱 실기기 확인 (빌드 10)"(K1~K4 키 확인 포함). 결과로 17-lib-platform-notes의 unconfirmed 표기 갱신, 첫 로그를 `protocol-analyst`로 분석

## 결정          :
- ADR-0014: 키 접근성은 옵트인, 기본값 `WHEN_UNLOCKED` 유지, 마이그레이션 없음(재생성 + 재등록)
- 이벤트 버퍼 64, 가득 차면 새 프레임 버림 + WARN(1, 100, 200…번째) — 설계의 DROP_OLDEST를 계획이 대체
- `events`는 어떤 `Disconnected`든 또는 `disconnect()`에서 정상 완료
- 샘플 요구사항은 SFR(FR-110/111 → SFR-001/002 이관)
- 로그는 폰 저장 + Mac에서 `tools/samples/pull-monitor-logs.sh android|ios`, 클라우드 전송은 나중(ADR 필요)

## 막힌 점       :
- 실차 확인은 사용자만 할 수 있음(Task 2, 11)
- 이 세션에서는 새 역할 에이전트와 Codex MCP 도구가 로드되지 않아 general-purpose 에이전트에 정의를 읽혀 썼고, Codex는 `codex exec` CLI로 대신함. **새 세션에서 Codex MCP 서버 승인 필요**
- 후속 이슈: #96(잠긴 폰 + WHEN_UNLOCKED 키 핸드셰이크가 시간 초과로 끝남), #97(iOS 키 핸들 해제), #99(samples에 lint/detekt 미적용)

## 다음 한 걸음  : 사용자에게서 스파이크 결과(시나리오 A~E 복사본)를 받아 `docs/handoff/`에 정리하고 Task 8 필요 여부를 판단한다. 검증: 결과 문서 + 판단을 #91에 기록

## 읽을 파일     : `{{PLANS_DIR}}2026-10-24-vehicle-events-monitor.md`, `{{SAMPLES_DOCS_DIR}}04-monitor-app.md`, `{{ADR_DIR}}0014-ios-key-accessibility.md`, `{{LIB_DOCS_DIR}}14-lib-reading-state.md` Part B, `{{LIB_DOCS_DIR}}17-lib-platform-notes.md`, `AGENTS.md` §9
