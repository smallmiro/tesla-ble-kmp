# Handoff — M4 계획에 넣을 "차량 푸시 관찰 로깅" (2026-10-18)

M4 개발 계획을 세우는 세션에 넘기는 메타 프롬프트입니다. 아래 내용을 M4 상세 계획(`{{PLANS_DIR}}…-m4-pairing-vcsec.md`)에 반영해 주세요.

## 배경
- 차량은 BLE로 VCSEC 메시지를 요청 없이 먼저 보냅니다(브로드캐스트). 원본 Go와 우리 포팅본은 이 메시지를 버립니다(`vehicle-command/internal/dispatcher/dispatcher.go:269-293`, `application/.../dispatcher/Dispatcher.kt:425`).
- 사용자 결정(2026-10-18):
  - M4 **첫 작업**으로 관찰 로깅을 넣습니다. 동작은 바꾸지 않습니다.
  - 공개 API `vehicle.events: Flow<VehicleEvent>` 는 **M5** 에서 만듭니다. M4 에서는 만들지 않습니다.
  - 푸시와 VCSEC 응답이 섞이는 문제는 M5 에서 ADR 로 해결합니다.
- 로드맵 `{{PLANS_DIR}}2026-09-26-roadmap.md` 의 M4 행(첫 작업)과 M5 행(`vehicle.events`)에 이미 적어 두었습니다. 로드맵 수정과 이 노트는 `origin/main`(M3 완료, 207e268) 위의 브랜치 `docs/m4-push-observation` 에 커밋돼 있습니다. M4 계획 브랜치를 이 브랜치에서 시작하거나, 이 브랜치를 먼저 main 에 병합하세요.

## 조사로 확인한 사실 (계획의 근거)
| 사실 | 출처 |
|---|---|
| VCSEC 가 주소 없는 `VehicleStatus` 를 브로드캐스트합니다. 응답과 같은 BLE indication 으로 옵니다 | Teslemetry `python-tesla-fleet-api` (Apache-2.0) `broadcast.py`, PR #178 실측 |
| 브로드캐스트는 필드 일부만 담을 수 있습니다. 잠긴 채 잠든 차량의 프레임에 `closureStatuses` 가 없었습니다 | PR #178 |
| 새로 연결하면 `RoutableMessage` 로 감싸지 않은 `FromVCSECMessage{authenticationRequest}` 가 약 1.1초마다 옵니다. 이 필드는 Tesla 가 공개한 proto 에 없습니다 | PR #179 |
| 잠금이나 해제의 응답이 사라져도 바뀐 상태가 브로드캐스트로 옵니다 | Teslemetry `bluetooth.py` |
| VCSEC 응답은 요청마다 새로 만드는 랜덤 라우팅 주소로 짝을 맞춥니다. `request_uuid` 는 쓰지 않습니다 | `dispatcher.go:259-261`, `:400-403` |
| Go 는 응답 종류를 검사하지 않습니다. BLE 수신 큐(5칸)가 차면 메시지를 조용히 버립니다 | `pkg/vehicle/state.go:13-19`, `pkg/connector/ble/ble.go:78-82` |
| AGPL 이라 열지 않은 저장소: `yoziru/tesla-ble`, `yoziru/esphome-tesla-ble`, `dzid26/TeslaBatteryBLE`, `0Bu/tesla-key-esp32` | 절대 규칙 9 |

## M4 첫 작업의 범위
1. **Dispatcher 관찰 로그** (`:application`): 메시지를 버리는 지점마다 디코드한 요약을 로그로 남깁니다.
   - 대상: 받을 핸들러가 없는 메시지, 받는 쪽이 도메인 전체인 메시지, 출발지가 없는 메시지, 해석할 수 없는 메시지
   - 남길 내용: 보낸 도메인, 받는 쪽 형태(라우팅 주소인지 도메인인지), 주소가 열린 요청의 것인지, `request_uuid` 가 비었는지, `FromVCSECMessage` 의 oneof 종류. `vehicleStatus` 라면 잠금·문·수면·탑승자 상태와 어떤 필드가 빠졌는지
   - VCSEC 요청이 진행 중일 때 받은 응답도 같은 형식으로 남깁니다. M5 에서 섞임 문제를 판단할 근거입니다.
2. **감싸지 않은 프레임**: `RoutableMessage` 로 해석되지 않거나 출발지가 없으면, 같은 바이트를 `FromVCSECMessage` 로 다시 해석해 결과를 로그로 남깁니다. 로그만 남기고 처리는 하지 않습니다. 모르는 필드(`authenticationRequest`)는 필드 번호만 남깁니다.
3. **BLE inbox 넘침** (`:adapter-ble` `BleTransport.kt:79`): 넘친 횟수를 셉니다.
4. **비밀**(절대 규칙 8): VIN 은 마스킹합니다. 키, 세션키, 암호문은 남기지 않고 암호문은 길이만 남깁니다. 공개키는 SHA-1 앞 4바이트만 남깁니다.
5. **iOS 샘플 관찰 모드**: 연결을 유지한 채 받은 메시지 요약을 시각과 함께 목록으로 보여 주고, "결과 전체 복사"에 포함합니다. 주기적인 자동 조회는 끕니다(조회가 차량을 깨우기 때문). 빌드 번호를 올립니다.
6. **실차 확인 가이드**(사용자용 문서, `{{WORKFLOW_FILE}}` §8.4 형식): 시나리오마다 할 일, 기다릴 시간, 기록할 내용을 적습니다.
   - 문이나 트렁크를 손으로 열고 닫기
   - 키카드나 공식 앱으로 잠그거나 열기
   - 차에 타고 내리기
   - 충전 케이블 꽂고 빼기
   - 차량이 잠들거나 깨어날 때(최소 15분 대기)
   - 다른 폰 키가 다가올 때
   - 새로 연결한 직후 30초 동안 받는 메시지
7. **테스트**(TDD, `FakeVehicle`): 다음 프레임이 응답 처리를 바꾸지 않고 정해진 로그만 남기는지 확인합니다.
   - 주소 없는 `vehicleStatus`
   - 도메인 전체로 온 메시지
   - 감싸지 않은 `FromVCSECMessage`
   - 필드가 빠진 `VehicleStatus`
8. **문서**(절대 규칙 13): `18-lib-troubleshooting` 에 관찰 로그를 읽는 법을 씁니다. MD 와 HTML ko/en/ja 를 모두 고칩니다.
9. **커밋 분리**(Tidy First): 로그 추출을 위한 구조 변경은 `struct:`, 로그 추가는 `feat:` 로 나눕니다.

## 하지 않을 것 (M5 로 넘김)
- `vehicle.events`, `VehicleEvent` 같은 공개 API 추가, `apiDump`
- 메시지를 버리던 동작이나 응답 매칭 방식 변경
- #71 의 "closures 가 비어 있으면 모두 닫힘" 규칙 변경. 브로드캐스트에서는 "모름"이 맞지만, 이 판단은 M5 에서 합니다.
- `Uncertain` 결과를 브로드캐스트로 확인하는 기능

## M5 를 위해 남겨 둘 해결 방법 후보 (참고)
- A: 라우팅 주소로 응답과 푸시를 구분합니다.
- B: 감싸지 않은 프레임을 따로 처리합니다.
- C: 기대한 응답 종류만 응답으로 받습니다.
- D: VCSEC 직렬화. FR-049 로 이미 구현했습니다.
- F: BLE 수신 큐를 보강합니다.

추천 조합은 A + B + C + D + F 입니다. 실차 로그로 확인할 것은 두 가지입니다.
- 차량이 푸시를 어떤 주소로 보내는지(주소 없음, 도메인 전체, 마지막에 쓴 주소)
- 푸시가 얼마나 자주, 어떤 상황에서 오는지

## 추적
- 관련 FR: 새 FR 은 M5 에서 만듭니다. M4 관찰 로깅은 NFR(진단·로그)로 연결할지 계획 세션에서 정해 주세요.
- 참고 매뉴얼: `{{MANUAL_DIR}}03-protocol.md` §12, `{{MANUAL_DIR}}02-ble-transport.md`, `{{MANUAL_DIR}}01-architecture.md`
