# Handoff — M3 전송·키스토어·파사드: 코드 완료, 실차 확인 대기 (2026-10-16)

## 목표
`{{PLANS_DIR}}2026-10-15-m3-transport-keystore.md`, FR-001~006, FR-020~021, FR-025, FR-028, FR-030~032, FR-112, NFR-004~006. M3 완료 기준: iPhone 샘플 앱에서 스캔·연결·키 목록·VehicleStatus(실차는 사용자 확인).

## 완료
- 병합된 PR(모두 rebase merge, 각 PR 머지 전 `tools/ci/verify.sh` 6/6 + AI 리뷰):
  - #43 M3 계획 / #44 Task 1 iOS 스파이크 프로브(ADR-0003) / #45 Task 2 `:domain` M3 포트·가짜 / #46 Task 3 `BleTransport` / #47 Task 4 `BleTransportFactory`
  - #48 Task 6 키스토어 공통 / #49 Task 7 Android 키스토어 / #50 Task 8 iOS 키스토어 / #51 Task 9 플랫폼 세션 캐시
  - #52 Task 10 VCSEC 조회(`vehicleStatus`·`keySummary`·`listKeys`) / #53 Task 11 M2 인계 정리 / #54 Task 12 세션 이벤트·오래된 캐시
  - #55 Task 5 Kable 바인딩(구독 방식 `KABLE_DEFAULT`, 스파이크 판정 전 선구현) / #56 Task 13 `:sdk` 파사드 / #57 Task 14 샘플 앱 2종
  - #58 Task 15 앞부분(골든 수집 스크립트, `platform-notes.md`·`getting-started.md`·`errors.md`, SDD·ADR-0010 동기화)
  - #59 최종 리뷰(opus) 수정 웨이브: 링크 끊김 원인 보존, `TeslaBle.close()`(suspend)가 연결된 차량 정리, Android Keystore ECDH 예외 격리·생성 직후 자체 시험, 해제 후 저장 방지, iOS 키스토어·Keychain 캐시를 호출 스레드 밖으로, 문서 표류, Android 샘플 `allowBackup=false`
- 최종 리뷰: "With fixes" → 수정 웨이브 1회 → 재리뷰 APPROVED.
- 판정·연기 Minor 전체: `{{HANDOFF_DIR}}2026-10-16-m3-rulings.md`.

## 진행 중
- **사용자 iPhone 실차 확인(내일 예정).** 워크트리 `../tesla-spike`(origin/main `b117e80`), 앱 `TeslableSampleDevice`가 주난폰(iPhone 16 Pro, iOS 27.0.1)에 설치됨. `samples/ios/Local.xcconfig`는 팀 ID 한 줄만 남김(편집기가 넣은 U+00A0 때문에 xcconfig 파싱 실패했던 것을 정리).
- 1차 Probe(2026-10-16, 차량에서 떨어진 곳): `scan: looking for S609a019fbe407ebcC` → `scan: timeout after 30s`. 권한·블루투스 꺼짐이면 `error:`가 찍히므로 원인은 거리/수면 또는 VIN 입력 오류. 내일 차 옆에서 정확한 VIN으로 재실행.

## 결정
- 설계 구체화 1~18(계획) 채택. 실행 중 판정(전체는 rulings 파일): Task 5를 스파이크 판정 전에 `KABLE_DEFAULT`로 구현(판정 c면 재작업), Task 15를 A(문서·스크립트)/B(실차 후)로 분리, 프로브 삭제를 Task 15B로 이동, Android Keystore 별칭 `teslable.key:`/`teslable.wrap:`, 시뮬레이터 Keychain -25291을 -34018과 같이 처리, `listKeys`는 요청 슬롯 번호 사용, `TeslaBle.close()`를 suspend로.
- 계획 대비 공개 API 차이: `TeslaBle.close()`가 `suspend`, M0 `Teslable` 객체 삭제(중간에 `TeslableDemo`로 이름 변경 후 삭제).

## 막힌 점 (M4 이후로 미룬 것)
- **Swift 시간 제한:** `Duration` 파라미터가 Swift에서 `int64_t`/`id`로 보여 사실상 설정 불가 — M4 계획의 설계 구체화로 한 번에 정한다(최종 리뷰 Important 4).
- 오래된 캐시 세션이 앱에 보이지 않음(`sessions`가 `Ready(fromCache)` 유지, `startSession`이 재핸드셰이크 안 함) — M5 인증 명령 전에 결정.
- `SessionState.close()`가 준비 신호를 되돌리지 않아 `disconnect()` 뒤 `startSession()`이 캐시 복원 세션에 Success.
- `SecureEnclaveKey`의 `SecKeyRef` 미해제(키 핸들 캐시 필요), `KableErrors`의 `authoriz` 정규식이 GATT "insufficient authorization"을 `PermissionDenied`로 볼 수 있음(실기기 확인), `listKeys` 기본 예산(5초) 여유.
- Android 공개 API(`androidVehicleKeyStore`, `androidSessionCache`, `androidBleAvailability`)는 BCV Android 덤프가 없어 `apiCheck` 밖.
- Android 실기기 BLE 미확인(사용자 환경 iPhone만), 에뮬레이터 키스토어 확인 미실시.
- 골든 픽스처 실제 수집 미실시(스크립트만 — `tools/fixtures/collect-golden.sh`, Go `tesla-control` 필요).

## 다음 한 걸음
1. 사용자가 차 옆에서 Probe 로그 + 샘플 확인표(9항목, `samples/ios/README.md` "실기기 실행")를 전달.
2. Task 15B: ADR-0003 "스파이크 결과" 절 기록(판정 a/b → `DEFAULT_SUBSCRIPTION` 유지, c → 멈추고 보고), `GattProbe`·`TeslableDiagnostics`·`ProbeRequestTest`·`DiagnosticsView.swift` 삭제, README 상태, 최종 M3 인계 노트(확인표 포함). 실패 항목은 `superpowers:systematic-debugging`으로.
3. 그다음 M4 상세 계획(`{{PLANS_DIR}}<날짜>-m4-pairing-vcsec.md`) → 🛑 승인.
검증: `tools/ci/verify.sh`.

## 읽을 파일
`{{PATHS_FILE}}`, `{{AGENTS_FILE}}`, 이 노트, `{{HANDOFF_DIR}}2026-10-16-m3-rulings.md`, `{{PLANS_DIR}}2026-10-15-m3-transport-keystore.md`(Task 15), `{{ADR_DIR}}0003-ble-kable.md`, `samples/ios/README.md`, `docs/manual/platform-notes.md`, `{{MANUAL_DIR}}02-ble-transport.md`.
