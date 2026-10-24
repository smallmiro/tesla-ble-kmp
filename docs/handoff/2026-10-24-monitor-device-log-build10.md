# iOS 모니터 앱 빌드 10 — 기기 로그 확인 (2026-10-24)

#91 Task 11 입력. 폰에 설치된 모니터 앱(빌드 10)의 로그를 `tools/samples/pull-monitor-logs.sh ios`로 가져와 읽은 결과다.
로그 원본은 `{{SAMPLE_LOGS_DIR}}ios/2026-10-24/`(git-ignored, 커밋하지 않음). VIN 모양 문자열 검사(`[A-HJ-NPR-Z0-9]{17}`): 0건.

## 환경
- iPhone 16 Pro(`iPhone17,1`), iOS 27.0.1, 앱 `dev.mirolab.TeslableSampleDevice` 0.4.0 (10)
- 가져온 시각 2026-10-24 23:02, 앱은 그때도 실행 중(`devicectl device info processes`)

## 로그 요약
| 파일 | 시간 | 내용 |
|---|---|---|
| `monitor-20261024-164035.jsonl` | 16:40:35~16:40:38 | `session` → `link connecting(1)` → 3초 뒤 `link stopped` |
| `monitor-20261024-164044.jsonl` | 16:40:44 | `session` → `link connecting(1)`, 그 뒤 줄 없음(5초 안에 앱 재시작) |
| `monitor-20261024-164049.jsonl` | 16:40:49 | 위와 같음 |
| `monitor-20261024-223648.jsonl` | 22:36:48~23:02:32 | `link connecting` 27번, 모두 30초 뒤 `backoff` (`no vehicle advertisement found before the scan timeout`) |

- 22:36~23:02 동안 연결 성공 0번. `event`·`query`·`mark` 줄 없음. 체크리스트 1~10, K1~K4 중 판정할 수 있는 항목 없음.
- 재시도 간격은 설계대로 2 → 4 → 8 → 16 → 30초(최대)에서 유지, 각 시도는 스캔 시간 초과 30초. 26분 동안 시도 사이에 큰 공백이 없다 → 그동안 앱은 정지되지 않고 계속 실행됐다.
- 16:40의 세 세션은 결과 줄이 없어 판정 불가(설치 직후 실행·종료로 보임).

## 해석 (미확정)
- 라이브러리는 로컬 이름만으로 스캔한다(`adapter-ble/.../KableGattConnector.kt` `Filter.Name.Exact`, 서비스 UUID 필터 없음).
  iOS는 서비스 UUID 없이 스캔하는 앱이 백그라운드이거나 폰이 잠겨 있으면 스캔 결과를 주지 않는다(Core Bluetooth 백그라운드 규칙).
- 따라서 이 로그는 다음 두 경우와 모두 맞아 아직 구분할 수 없다.
  1. 차가 BLE 범위 밖에 있었다 → 정상 동작, 차 근처에서 다시 확인 필요.
  2. 차는 근처였지만 앱이 백그라운드이거나 폰이 잠겨 있었다 → 계획 Task 8(스캔 필터에 서비스 UUID 추가) 필요.
  (차 근처에서 앱을 화면에 띄운 채 못 찾았다면 세 번째 경우, 결함 조사 대상.)
- 모니터 로그에는 앱 상태(active/background)와 잠금 여부가 없다. 다음 확인 때 시나리오별 시작·종료 시각과 폰 상태를 함께 적어야 판정할 수 있다.

## 다음 확인
- 빌드 11로 `samples/ios/README.md` "모니터 앱 실기기 확인 (빌드 11)" 1번(차 근처, 앱 화면 켬)부터. 연결되면 5·6번(화면 켠 채 / 잠근 채 재연결)으로 위 2번 가설을 확인한다.
- 각 시나리오의 시각과 폰 상태(화면 켬 / 홈 화면 / 잠금), 차와의 거리를 기록한다.
