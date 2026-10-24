# 키·제어 탭(SFR-018) 머지 뒤 인계 (2026-10-25)

## 한 일
- PR #111 (rebase merge, main `58bdd43`): 모니터 앱 다섯째 탭 **키·제어**. 키 만들기 → 연결 → 등록(NFC 카드, 3초 간격 최대 2분 확인) → VCSEC 버튼(잠금·해제·깨우기·트렁크·프렁크·원격 주행, 접힌 "다른 차종"에 Model X·Cybertruck).
  - 스펙: `{{SAMPLES_DOCS_DIR}}05-key-control-tab.md` + HTML ko/en/ja. 순수 규칙 `KeyControlSteps`·`KeyControlRunner`(Android `KeyControl.kt`, iOS `KeyControl.swift`)와 단위 테스트.
  - 빌드: Android 0.3.0 (3), iOS 0.5.0 (12). 리뷰: `/simplify`, `/code-review`, `/codex:review`(P2 두 건: iOS 키 없는 연결의 다시 연결 버튼, 연결 단위 상태 카드) 모두 반영, PR 본문에 요약.
- 머지된 main으로 iOS 빌드 12를 다시 만들어 iPhone 16 Pro에 설치. 실행 뒤 20초 넘게 살아 있음, 로그 첫 줄 `0.5.0(12)`, VIN 모양 문자열 0건.
  차가 범위 밖이라 연결은 `no vehicle advertisement found before the scan timeout` 로 재시도 중이었다(정상).

## 남은 일
1. 차 근처에서 실기기 확인: `samples/ios/README.md` "키·제어 탭 실기기 확인 (SFR-018, 빌드 12)" C1~C14, Android는 `05-key-control-tab.md` §Real-device checklist.
   끝나면 `tools/samples/pull-monitor-logs.sh ios` 로 로그를 받아 결과를 보고, `01-requirements.md` SFR-018 상태를 "In progress"에서 바꾼다.
2. 라이브러리 후속(PR #111 본문): `startSession` 이 도메인이 이미 `Ready` 여도 세션 캐시를 다시 저장한다. 필요하면 `library-api-request` 로 올린다.
3. #91 계획 Task 8(iOS 서비스 UUID 스캔 필터)은 main `32fb798` 에서 승인 대기 중.
