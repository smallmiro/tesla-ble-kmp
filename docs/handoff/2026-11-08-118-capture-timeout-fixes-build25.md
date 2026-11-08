# Handoff — #118 awake-car capture, timeout fixes, build 25 (2026-11-08)

## 목표
- #118 실차 수집을 분석하고, 분석에서 나온 라이브러리 결함을 고친다.
- iOS 샘플 빌드 25를 실기기에 넣고 다음 실차 수집 순서를 runbook에 정리한다.

## 완료
- **PR #146**: Live 클러스터에 사용자 사진(top/side)과 빨간 열림 아이콘. 빌드 23.
- **PR #147**: iOS 샘플은 항상 다크 모드(`UIUserInterfaceStyle: Dark`). 빌드 24.
- **PR #148**: 분석 보고서 두 개.
  - `docs/analysis/2026-11-05-118-sleeping-car-getstate.md`: 11-05 두 번의 수집이 모두 잠든 차였다.
  - `docs/analysis/2026-11-07-118-awake-car-capture.md`: 처음으로 깨어 있는 차에서 수집했다.
    - T1~T4 일부를 채웠다.
    - 데이터 응답은 모두 `gcm-ok` 였다.
    - 캐시된 세션 재동기화를 보았다(F2).
    - 시간 초과를 잘못 분류했다(F4).
    - 사용자 확인 2건: 09:15부터 내비 켬, 10:18:56은 두 번째 잠금 해제.
- **PR #149**: 요청의 첫 BLE 쓰기를 시작한 뒤의 시간 초과는 `Uncertain(Timeout(afterSend = true))`.
  - `Transport.send(message, onWriteInvoked)` 를 추가했다(`@InternalTeslableApi`). `:sdk` 덤프는 그대로다.
  - ADR-0010, SDD D29, PRD NFR-012와 매뉴얼 14·15·16·18·19를 고쳤다.
- **PR #150**: 쓰기가 실패한 명령은 새로 서명한 사본을 보내지 않는다. 그래서 이중 실행이 없다.
  - 프로토콜 거부는 무시하고 같은 요청을 기다린다. 끝내 응답이 없으면 시간 초과 때 `Uncertain` 이다.
  - 읽기(`vehicleStatus`, `keySummary`, `listKeys`, `getState`)는 Go 방식 재시도를 유지한다.
  - ADR-0010 2026-11-08 절과 SDD §6/§12에 Go와 다른 점을 기록했다.
  - 사용자가 범위, 시점, 읽기 예외를 승인했다.
- **PR #151**: 빌드 25.
  - `TimeoutNote` 시간 초과 문구.
  - README runbook에 "다음 수집 순서"(#118: 단계 0~7, mark와 펌웨어부터)를 넣었다.
  - "링크 안정화 시나리오 L1~L6"(#124 N1~N6)도 넣었다. #118 순서 뒤에 한다.
- 빌드 25는 폰에 설치되어 있다(0.5.0 (25), PR #151 브랜치에서 빌드). 머지 뒤 바뀐 것은 주석과 README뿐이다.
- 각 PR은 `tools/ci/verify.sh` 8단계를 통과했고 AI 리뷰를 거쳤다. #148·#149를 합친 main에서도 게이트를 다시 통과했다.

## 진행 중
- 없음.

## 결정
- ADR-0010 개정(#149, #150): "보냄" = 첫 BLE 쓰기를 시작함. 쓰기가 실패한 명령은 다시 서명하지 않는다.
- 속도 push는 BLE 경로에 없다. 속도는 `getState(DRIVE)` 폴링으로만 얻는다. Fleet Telemetry는 서버가 필요해 범위 밖이다.
- 속도 자동 조회(기어 D/R일 때 3초 간격) 계획은 사용자가 보류했다. 빌드 25에서는 빠졌다.
- M5와 링크 안정화 마일스톤 사이에 선후관계는 없다. 실차 수집 한 번으로 #118과 #124를 같이 채운다.

## 막힌 점
- 실차 수집이 필요하다. 빌드 25 runbook대로 사용자가 수집한다.
- GitHub 브랜치 보호: 해결되지 않은 리뷰 스레드가 있으면 머지가 막힌다(Codex 자동 리뷰). 해결하고 머지한다.
- 작업 트리에서 기기 빌드를 하려면 `samples/ios/Local.xcconfig`(git-ignored, 서명 팀)를 복사해야 한다.

## 다음 한 걸음
1. 사용자가 빌드 25로 README "다음 수집 순서"(#118)와 "링크 안정화 시나리오"(#124)를 수집한다.
2. 로그를 가져와 `protocol-analyst` 로 분석한다. 앞 두 보고서와 build18 보고서에 이어서 한다.
3. `library-developer`:
   - A8.2, `18-lib-troubleshooting`, README S6에 캐시된 세션 재동기화(F2, 보고서 H3)를 적는다.
   - #125는 #150의 읽기 재시도 결정과 맞춰 진행한다.
   - FR-104·FR-105 설계를 시작한다(ADR-0015).
4. 남은 후보: 속도 자동 조회 계획(보류), 제어 명령 로그 보강(보고서 권고).

## 읽을 파일
- `docs/analysis/2026-11-07-118-awake-car-capture.md`: §Findings, §Library hardening, §Next scenarios
- `docs/analysis/2026-11-01-build18-link-health.md`: #124 근거
- `samples/ios/README.md`: §응답 인증 수집 시나리오 → 다음 수집 순서, §링크 안정화 시나리오
- `{{ADR_DIR}}` ADR-0010(2026-11-07, 2026-11-08 개정), ADR-0015
- `{{LIB_DOCS_DIR}}16-lib-errors.md`: `Timeout(afterSend)`, §"Sent" means the write was started
