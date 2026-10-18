# Handoff — M3 전송·키스토어·파사드: 완료, iOS 실차 확인 통과 (2026-10-18)

## 목표
`{{PLANS_DIR}}2026-10-15-m3-transport-keystore.md`, FR-001~006, FR-020~021, FR-025, FR-028, FR-030~032, FR-112, NFR-004~006. M3 완료 기준: iPhone 샘플 앱에서 스캔·연결·키 목록·VehicleStatus를 실차로 확인.

## 완료
- 병합된 PR(rebase merge, 각 PR 머지 전 `tools/ci/verify.sh` + AI 리뷰):
  - #43 ~ #59: M3 계획, `:domain` 포트, `BleTransport`/`Factory`, 키스토어(공통·Android·iOS), 세션 캐시, VCSEC 조회, 세션 이벤트, Kable 바인딩, `:sdk` 파사드, 샘플 앱 2종, 문서·골든 스크립트 (상세는 `{{HANDOFF_DIR}}2026-10-16-m3-transport-keystore-interim.md`)
  - #60 중간 인계 노트
  - #64 DebugLogger 크래시 수정 (iOS NSLog `%@` 에 Kotlin 문자열 전달)
  - #65 샘플 앱 정보 화면 + 아이콘
  - #71 닫힘 상태 부재 = 모두 닫힘(Go getter와 동일), iOS 링크 손실 문구
  - #72 샘플 결과 전체 복사
  - 이 PR(Task 15B): ADR-0003 스파이크 결과 기록, 프로브 삭제, 샘플 다듬기(빌드 5), 상태·SDD·인계 노트
- iOS 실차 확인 (iPhone 16 Pro, iOS 27.0.1 (24A446), Model Y, 2026-10-16~18, 번들 `dev.mirolab.TeslableSampleDevice`, 0.3.0, 빌드 2~4; 빌드 5는 샘플 다듬기만 들어가 실차 재확인은 하지 않음):

| # | 확인 | 결과 |
|---|---|---|
| 1 | 키 생성 + 재실행 | 통과 — `SECURE_ENCLAVE`, 재실행 후 같은 공개키 지문 |
| 2 | 스캔 | 통과 — `S…C` rssi 약 -40~-50, connectable=true |
| 3 | 연결 | 빌드 1 실패(DebugLogger NSLog 크래시, #64) → 빌드 2부터 통과 |
| 4 | vehicleStatus | 통과 — 잠금 UNLOCKED / INTERNAL_LOCKED / LOCKED, 운전석 문 OPEN, 충전구 OPEN, userPresence. 모두 닫힘 `closures=null` 은 #71 에서 모두 CLOSED 로 수정 |
| 5 | listKeys | 통과 — 7개(슬롯 0 SERVICE 는 차량 화면에 안 보여 화면은 6개), 역할 OWNER/DRIVER/VEHICLE_MONITOR, 폼팩터 IOS_DEVICE/UNKNOWN |
| 6 | 끊기 → 재연결 → vehicleStatus | 통과 (빌드 4) |
| 7 | Bluetooth 끄고 스캔 | 통과 — `bluetooth is powered off` |
| 8 | Bluetooth 권한 거부 후 스캔 | 통과 — `bluetooth permission not granted`, 크래시 없음 |
| 9 | 연결 중 멀어지기 | 통과 — `연결 끊김 (링크 손실)` 후 `vehicle not connected` |

- 스파이크 판정 (b): `…0213` 은 notify+indicate 지원, Kable 기본 구독(notify)으로 응답 수신. `DEFAULT_SUBSCRIPTION = KABLE_DEFAULT` 확정, 우회 불필요. iOS 블록 길이 512. 다중 청크 응답(244+136 B) 재조립 확인. 상세는 `{{ADR_DIR}}0003-ble-kable.md`.

## 진행 중
없음.

## 결정
- 스파이크 판정 (b), ADR-0003 상태 채택 유지.
- 판정·재정 기록: `{{HANDOFF_DIR}}2026-10-16-m3-rulings.md`.
- 스파이크 프로브(`GattProbe`, `TeslableDiagnostics`, iOS 진단 화면) 삭제. 문제 보고는 샘플의 "결과 전체 복사" 로그와 `DebugLogger` 를 쓴다.

## 막힌 점
- **Swift 시간 제한:** `Duration` 파라미터가 Swift에서 `int64_t`/`id` 로 보여 사실상 설정 불가 — M4 설계 구체화로 한 번에 정한다.
- 오래된 캐시 세션이 앱에 보이지 않음(`sessions` 가 `Ready(fromCache)` 유지, `startSession` 이 재핸드셰이크 안 함) — M5 인증 명령 전에 결정.
- `SessionState.close()` 가 준비 신호를 되돌리지 않아 `disconnect()` 뒤 `startSession()` 이 캐시 복원 세션에 Success.
- `SecureEnclaveKey` 의 `SecKeyRef` 미해제(키 핸들 캐시 필요).
- `KableErrors` 의 `authoriz` 정규식이 GATT "insufficient authorization" 을 `PermissionDenied` 로 볼 수 있음(실기기에서는 아직 안 나타남).
- `listKeys` 기본 예산(5초) 여유.
- Android 공개 API(`androidVehicleKeyStore`, `androidSessionCache`, `androidBleAvailability`)는 BCV Android 덤프가 없어 `apiCheck` 밖.
- Android 실기기 BLE 미확인(사용자 환경 iPhone만), 에뮬레이터 키스토어 확인 미실시.
- 골든 픽스처 실제 수집 미실시(스크립트만 — `tools/fixtures/collect-golden.sh`, Go `tesla-control` 필요).
- 새로 관찰: 다른 라우팅 주소 앞으로 온 비요청 VCSEC 메시지가 도착한다. 디스패처가 "핸들러 없음" 으로 버리므로 문제 없음.
- 새로 관찰: `…0214` 특성은 속성이 없고 Go도 쓰지 않아 미사용.

## 다음 한 걸음
1. 사용자가 Go 가 있다면 `tools/fixtures/collect-golden.sh` 를 먼저 실행해 달라고 요청(없으면 건너뜀).
2. M4 상세 계획 `{{PLANS_DIR}}<날짜>-m4-pairing-vcsec.md` 작성 → 🛑 사용자 승인. 계획에 위 "막힌 점"의 Swift 시간 제한 결정을 포함한다.
검증: `tools/ci/verify.sh`.

## 읽을 파일
`{{PATHS_FILE}}`, `{{AGENTS_FILE}}`, 이 노트, `{{HANDOFF_DIR}}2026-10-16-m3-rulings.md`, `{{PRD_FILE}}`, `{{SDD_FILE}}`(§9.6, §12), `{{ADR_DIR}}0003-ble-kable.md`, `{{MANUAL_DIR}}00-agent-guide.md`, `{{MANUAL_DIR}}02-ble-transport.md`, `{{MANUAL_DIR}}05-command-catalog.md`, `{{LIB_DOCS_INDEX}}`.
