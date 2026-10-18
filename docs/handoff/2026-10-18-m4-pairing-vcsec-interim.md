# Handoff — M4 키 등록·VCSEC 제어: Task 1~9 병합, 실차 확인 대기 (2026-10-18, 중간)

## 목표
`{{PLANS_DIR}}2026-10-18-m4-pairing-vcsec.md`(결정 D-1~D-6 확정 — 계획의 "결정 (2026-10-18 확정)" 절). M4 완료 기준: iPhone 샘플 앱에서 실차 페어링·잠금·해제·트렁크(PRD §9).

## 완료 (rebase merge, 각 PR 머지 전 `tools/ci/verify.sh` + AI 리뷰)
| PR | Task | 내용 |
|---|---|---|
| #75 | 계획 | M4 상세 계획 + 결정 D-1~D-6(codex와 상의해 추천안으로 확정) |
| #76 | 1 | 차량 푸시 관찰 로깅(DEBUG `observe`), 감싸지 않은 프레임 재해석, BLE inbox 넘침 횟수, iOS 관찰 모드(빌드 6), 실차 관찰 가이드, `18-lib-troubleshooting`. 동작·공개 API 변화 없음 |
| #77 | 2 | 오래된 캐시 세션 무효화 → `NoSession` → `startSession()` 회복(같은 연결), 닫힌 세션은 준비 아님, `authorizeNow` |
| #78 | 3 | `InsufficientPrivileges`, `UnknownVcsecError`, `16-lib-errors` |
| #79 | 4 | `VcsecPayloads`, `VcsecControl` 12종 |
| #80 | 5 | `KeyManagement`(`addKey`/`removeKey`/`sessionInfo`), 다른 공개키 질의는 세션 갱신 안 함, `SessionInfoSummary` |
| #81 | 6 | `Pairing.addKeyRequest` + `PairingEvent`, `unroutedFrames`, 페어링 직렬화·링크 끊김 즉시 실패·락 대기 중 프레임 폐기 |
| #82 | 7 | `:sdk` 파사드(M4 메서드 전부), 세션 표시 재조정, `platformKeyFormFactor`, `pairingTimeout`, 사용 문서 11~19(MD + HTML ko/en/ja) |
| #83 | 8 | Swift `Timeouts.seconds`·`TeslaBleConfig.withSeconds`, 샘플 XCTest, verify.sh (d) `build test` |
| #84 | 9 | 샘플 2종: 페어링·등록 확인·핸드셰이크·제어 6종(Uncertain 재조회)·키 삭제, iOS 빌드 7, M4 실차 확인표 |

Task 10(골든 픽스처): 건너뜀 — 캡처 없음, Go·`tesla-control` 미설치(D-5: NFR-002 잔여, M4 완료 판정과 별개).

## 진행 중 — 🛑 사용자 실차 확인 (빌드 7, `TeslableSampleDevice`)
빌드 7에는 Task 1 관찰 모드도 들어 있으므로 한 번의 실차 세션으로 둘 다 확인할 수 있다.
1. `samples/ios/README.md` "실차 푸시 관찰" 시나리오 1~7 → "결과 전체 복사"(관찰 줄 포함, TX/RX 헥사는 들어가지 않음).
2. `samples/ios/README.md` "M4 실차 확인" 표 1~9 → 같은 방식으로 기록.
결과를 받으면: 관찰 결과 노트 `{{HANDOFF_DIR}}<날짜>-m4-push-observation-results.md`(계획 Task 1 Step 13 형식), 실패 항목은 `fix:` PR, 페어링이 `Unknown(raw)`이면 헥사로 `PairingEvents` 매핑 수정 + ADR-0009 실측 기록. 그 뒤 Task 11(마무리)과 M4 최종 리뷰(opus, 전체 범위 `3400085..main`).

## 결정 (판정 기록 — 원장 `.superpowers/sdd/2026-10-18-m4-pairing-vcsec/progress.md`)
- D-1~D-6: 추천안(codex와 상의). 사용자 지시: 사용자 결정이 필요한 부분은 오른쪽 codex와 상의해 결정(같은 브랜치·HEAD를 명시).
- 모델: 구현 sonnet, 리뷰 opus(T1·T2·T5·T6·T7)·sonnet(그 외), 최종 리뷰 opus.
- T2: 구현이 테스트보다 먼저였음(RED는 컴파일 실패) — 리뷰가 새 테스트의 행동 검증을 확인하고 핸드셰이크 저장 테스트를 수정 1회로 분리.
- T5 Minor(`SessionInfoSummary` KDoc, `BadResponse` 테스트)는 T7에서 처리.
- T6: 리뷰 Minor 2건(락 대기 중 오래된 프레임, `sendUnrouted` 공개 범위)을 판정으로 수정.
- T7: feat 커밋 단독은 detekt 실패(다음 chore 커밋이 고침) — 수용.
- T8: 서브에이전트 커밋 trailer가 "Claude Sonnet 5.5" — 수용(실제 작성 모델).
- T9: 등록 확인 2분 폴링 중 연결 끊기 불가 — 샘플 한정 수용(parked).

## 막힌 점 / 최종 리뷰가 분류할 Minor (원장 `minor (deferred)` 줄)
관찰 로그 테스트 단언 보강, DEBUG 꺼짐 시 `ownerOf` 비용, HTML 18·16 표의 세부 항목, `.reconciling` 회귀 테스트 부재, 페어링 drain→send 창의 늦은 결과(상관 ID 없음 — SDD §4에 기록 예정), verify.sh 시뮬레이터 이름 고정, 초 검증 중복, 샘플 폴링 등. M5로 넘기는 것: `vehicle.events`·응답/푸시 구분 ADR(후보 A+B+C+D+F), #71 규칙, `VehicleAsleep`, 알려진 위험(열린 요청 주소로 온 브로드캐스트를 `COMMAND_STATUS_ABSENT`가 성공으로 볼 수 있음 — 관찰 로그 `[reply:vcsec]`로 실차 확인).

## 다음 한 걸음
실차 결과 수신 → 결과 노트 → 필요 시 `fix:` → Task 11 → M4 최종 리뷰 → M4 인계 노트 → M5 상세 계획.
검증: `tools/ci/verify.sh`.

## 읽을 파일
`{{PATHS_FILE}}`, `{{AGENTS_FILE}}`, 이 노트, 계획 파일, `{{HANDOFF_DIR}}2026-10-18-m4-push-observation.md`, 원장(git-ignored), `samples/ios/README.md`.
