# M4 실차 푸시 관찰 결과 (2026-10-18)

계획 `{{PLANS_DIR}}2026-10-18-m4-pairing-vcsec.md` Task 1 Step 13 형식. 원본 기록은 사용자가 샘플의 "결과 전체 복사"로 보낸 것이며, VIN·원문 헥사는 들어 있지 않다(관찰 줄만).

## 관찰 환경
- iPhone 16 Pro, iOS 27.0.1, 샘플 `dev.mirolab.TeslableSampleDevice` 0.3.0 빌드 7, 키 `SECURE_ENCLAVE`(차량 미등록 키로 연결 — 관찰에는 등록이 필요 없다)
- 차량 Model Y(펌웨어 미기록)
- 1차: 2026-10-18 15:46:53~15:57:01(연결 15:46:57, 링크 손실 15:56:21), 관찰 71줄. 시나리오 구분 없이 연속 기록(타고 앉음 → 차 안에서 대기 → 내림·잠금 → 멀어짐으로 보인다)

## 시나리오별 결과 (1차)
1차는 시나리오를 나누지 않은 연속 기록이라 상태 변화 시각으로 정리한다.

| 시각 | 관찰 | 해석 |
|---|---|---|
| 15:47:01~ | `[53]` 54B, `vehicleStatus` UNLOCKED / AWAKE / PRESENT | 연결 직후 |
| 15:48:36~15:54:28 | `vehicleStatus` **INTERNAL_LOCKED** / AWAKE / PRESENT가 1~25초 간격으로 반복 | 운전석에 탄 채 문이 닫혀 실내 잠금 |
| 15:53:28~15:53:44 | `[53]` **220B** 세 번 + UNLOCKED 한 번 → 곧 INTERNAL_LOCKED | 문을 열었다 닫음(또는 키 인증 사건) |
| 15:55:58~15:56:14 | `[53]` 220B 세 번 + **LOCKED / NOT_PRESENT** | 내려서 잠금 |
| 15:56:21 | 링크 손실 | 멀어짐 |

메시지 종류별 정리:

| 종류 | 주소 형태 | 크기 | 빈도 | 내용 |
|---|---|---|---|---|
| `FromVCSECMessage{unknownFields=[48]}` | `to=domain(DOMAIN_BROADCAST)`, `request_uuid=empty`, 평문 | 20B | **정확히 약 5.0초마다**(차가 깨어 있고 사용자가 있을 때). 15:49:46~15:50:39, 15:51:29~15:53:28처럼 끊긴 구간이 있다 | 공개 proto에 없는 필드 48 — 주기 신호(하트비트·근접 확인으로 추정) |
| `FromVCSECMessage{unknownFields=[53]}` | 같음 | 54B 또는 220B | 연결 직후와 문·잠금 사건 전후에 몇 번씩 | 공개 proto에 없는 필드 53. 220B는 사건 때, 54B는 연결 직후 |
| `FromVCSECMessage{vehicleStatus{…}}` | 같음 | 26B(UNLOCKED)·28B(그 밖) | 상태가 바뀔 때 + 바뀌지 않아도 1~25초 간격으로 반복 | lock·sleep·presence만. **`closureStatuses`·`detailedClosureStatus`는 71줄 모두에서 빠짐**. VehicleStatus 안에 공개 proto에 없는 **필드 7, 8**이 항상 있다 |
| `drop:no-handler` 83B | `to=address(16B, other)`, `request_uuid=16B`, 서명된 응답(`payload=encrypted(0B)` — 본문 없음) | 83B | 약 1~2분마다(15:47:06, 15:48:37, 15:49:16, 15:51:05, 15:53:38) | **다른 클라이언트(공식 앱 폰 키 등)의 VCSEC 요청에 대한 응답**이 우리 링크로도 온다 |

관찰되지 않은 것:
- `drop:missing-source` / `drop:unparseable`(감싸지 않은 프레임): **0줄**. Teslemetry PR #179의 "약 1.1초마다 오는 감싸지 않은 `FromVCSECMessage{authenticationRequest}`"는 이 차량·이 연결에서는 보이지 않았다. 대신 감싼(`RoutableMessage`) 브로드캐스트의 필드 48·53이 보였다.
- `drop:missing-destination`(목적지 없음): 0줄. 브로드캐스트는 모두 `to=domain(DOMAIN_BROADCAST)`.
- Infotainment 발신(`from=domain(DOMAIN_INFOTAINMENT)`) 메시지: 0줄.
- `[reply:vcsec]`: 0줄(이 세션에서 요청을 보내지 않았다).
- BLE inbox 넘침: 없음.

## M5 판단에 쓸 사실
1. **차량은 푸시를 어떤 주소로 보내는가** → 전부 `to_destination.domain = DOMAIN_BROADCAST`(라우팅 주소 없음), `request_uuid` 비어 있음, 평문. 우리 요청의 라우팅 주소(`self`/`open-request`)로 온 푸시는 없다. 다른 클라이언트 앞 응답은 그 클라이언트의 주소(`other`)로 온다. → M5 후보 **A(라우팅 주소로 구분)가 그대로 성립**한다: 응답은 우리 주소, 푸시는 도메인 브로드캐스트. 현재 `Dispatcher.route()`가 브로드캐스트를 `TO_DOMAIN`으로 버리므로 응답과 섞이지 않는다(계획 설계 구체화 6의 "알려진 위험"은 이 차량에서는 나타나지 않음 — 9번 확인으로 재확인 필요).
2. **얼마나 자주, 어떤 상황에서** → 필드 48 약 5초 주기(깨어 있고 사용자 있음), `vehicleStatus`는 상태 변화 + 1~25초 반복, 필드 53은 연결·문/잠금 사건 전후. 잠든 차량(시나리오 6)은 아직 관찰하지 않음.
3. **`vehicleStatus` 브로드캐스트에는 닫힘 상태가 없다**(71줄 모두). *(2차 관찰로 정정: 무언가 열려 있을 때만 실리고 모두 닫히면 빠진다 — 아래 "2차 관찰" 사실 3. 아래 이 항목의 #71 판단도 2차 결과로 대체된다.)* #71 규칙("closures 부재 = 모두 닫힘")을 브로드캐스트에 적용하면 틀린다 → M5에서 브로드캐스트의 닫힘 상태는 "모름"으로(인계 노트 "하지 않을 것"과 일치). 대신 VehicleStatus의 미공개 필드 7·8에 정보가 있을 수 있다.
4. 감싸지 않은 프레임(후보 B)은 이 차량에서 관찰되지 않았다. 페어링(add-key-request) 응답이 감싸지 않은 프레임으로 오는지는 M4 확인 1번에서 본다.

## 응답과 섞인 사례
1차에서는 요청이 없어 해당 없음. M4 확인 9번(관찰 모드를 켜고 잠금·해제)에서 `[reply:vcsec]`에 `vehicleStatus` 페이로드가 섞이는지 본다.

## 남은 관찰
- 시나리오 2(문·트렁크를 손으로), 5(충전 케이블), 6(수면 15분 이상), 7(다른 폰 키가 다가올 때)을 따로 기록하면 필드 48·53의 의미와 닫힘 정보(필드 7·8)를 더 좁힐 수 있다.

---

## 2차 관찰 (2026-10-24 08:36~08:50, 빌드 8 — 관찰 v2 상세 디코드 + Teslemetry 스냅샷, `표시` 대신 시나리오별 기록)

### 시나리오별 결과
- **기어(P→R→N→D→P ×2):** 기어를 바꿀 때마다 약 10초 간격으로 `vehicleStatus` 브로드캐스트가 하나씩 왔고 `gear=`가 조작 순서와 정확히 일치했다 — `GEAR_REVERSE → GEAR_NEUTRAL → GEAR_DRIVE → GEAR_PARK`, 두 번 반복 모두. **VehicleStatus 필드 7 = 기어(Teslemetry `Gear_E`)를 실차로 확정.**
- **방향지시등 좌·우, 비상등, 전조등·상향등:** 관찰 줄 **0줄**. 이 동작들은 VCSEC 브로드캐스트를 일으키지 않는다.
- **운전석 문 열기·닫기(실내 손잡이로):**
  - 열 때 동시에 세 메시지: `authenticationRequest{requestedLevel=DRIVE, reasons=[PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL], token=20B}`, 필드 53 205B(항목 5개 `{1: id(1,3,6,7,4), 2: 35B 블롭}` — 블롭은 마지막 2바이트만 다름), `vehicleStatus`에 **`closures={frontDriverDoor=OPEN, …}`가 처음으로 실림**. 이어서 다른 클라이언트 앞 응답(83B, `other`) 두 개.
  - 닫은 뒤(약 6초) `vehicleStatus`의 `closureStatuses`가 다시 빠짐.
- **트렁크 열기·닫기(실내 버튼으로 — 사용자 정정 2026-10-24):** 관찰 줄 0줄("트렁크 기록 안 남음"). 밖에서 손으로·키카드·폰 키·앱으로 여는 경우는 미확인.
- **충전구 열기 → 충전기 연결 → 충전 시작:** `closures`에 `chargePort=OPEN`. 필드 8의 하위 필드 2가 2 → 1로 바뀜. `authenticationRequest`(reasons에 `ENTERED_HIGHER_AUTH_ZONE` 추가), 필드 53 54B(항목 1개, id 4).
- **주기 메시지:** `vehicleStatus`가 1초 안에 연달아 오며 필드 8의 하위 필드 1이 1 ↔ 2로 번갈아 바뀜.
- **연결한 채 멀어짐:** `lock=UNLOCKED → LOCKED`(걸어서 멀어지면 잠김) 브로드캐스트 뒤 약 30초 후 링크 손실.

### 사실 정리
1. **기어(PRND)는 BLE로 실시간 받을 수 있다** — VCSEC 브로드캐스트 `VehicleStatus.gear`(필드 7). Infotainment 세션이 없어도, 차량 미등록 키로 연결해도 온다.
2. **방향지시등·비상등·전조등·상향등은 BLE로 오지 않는다** — VCSEC 브로드캐스트가 없고, vehicle-command·Teslemetry의 BLE용 Infotainment proto(`vehicle.proto`, `car_server.proto`)에도 해당 상태 필드가 없다. 알려진 경로는 Fleet Telemetry(`telemetry/vehicle_data.proto`의 `LightsTurnSignal` 240, `LightsHazardsActive` 239, `LightsHighBeams` 241 — 차량 → 인터넷 서버)뿐이다.
3. **닫힘 상태:** 무언가 열려 있을 때만 `closureStatuses`가 실리고 모두 닫히면 빠진다(문·충전구에서 확인). 1차의 "항상 빠짐"은 모두 닫혀 있었기 때문으로 보인다 → M3 #71 규칙("부재 = 모두 닫힘")이 브로드캐스트에서도 관찰과 맞는다. 단 트렁크를 **실내 버튼으로** 열고 닫았을 때는 브로드캐스트가 오지 않았다 — 다른 방법(밖에서 손으로, 키카드·폰 키·앱)으로 열 때는 미확인이므로, 확인 전까지 트렁크 상태는 조회 `vehicleStatus()`로 본다.
4. **필드 3 `authenticationRequest`:** 문 손잡이를 당기거나 더 높은 인증 구역에 들어갈 때 감싼 브로드캐스트로 온다(Teslemetry PR #179의 "감싸지 않은 프레임"과 달리 이 차는 `RoutableMessage` 안에 담아 보냈다).
5. **필드 53:** 인증 사건과 함께 오며 항목 `{1: 작은 정수, 2: 35바이트}`의 반복. 정수 1·3·4·6·7은 등록된 키 슬롯 번호(M3에서 7개 확인)와 겹친다 — 키마다의 인증 챌린지로 추정(미확정).
6. **필드 8:** `{1: 1|2, 2: 1|2, 3: 1}` — 하위 1은 주기적으로 1↔2, 하위 2는 충전구·충전 시점에 2→1. 의미 미확정(방향지시등·라이트와는 무관 — 그 조작 때 메시지가 없었다).
7. **필드 48(5초 하트비트):** 2차 기록 구간에는 나타나지 않았다(1차에서만). 조건 미확정.
8. **주소:** 2차도 모든 푸시가 `to=domain(DOMAIN_BROADCAST)`. M5 후보 A(라우팅 주소로 응답·푸시 구분) 근거 유지.

### M5 입력
- `vehicle.events`에 바로 쓸 수 있는 실측 항목: 잠금 상태, 수면, 탑승, **기어**, 열린 닫힘 부위(문·충전구), 인증 요청 사건.
- 브로드캐스트로 알 수 없는 것: 방향지시등·라이트(BLE에 없음). 트렁크는 실내 버튼 조작 시 브로드캐스트 없음 — 다른 조작 방법은 추가 관찰 필요.

## 3차 관찰 (2026-10-24 16:11~16:12, 빌드 8 — 시나리오 하나, 관찰 25줄)

원본은 사용자가 보낸 "결과 전체 복사"(VIN·원문 헥사 없음). 25줄 모두 `[drop:to-domain]` `FromVCSECMessage{vehicleStatus{…}}`이며 `authenticationRequest`·필드 53·필드 48·다른 클라이언트 앞 응답은 한 줄도 없었다.

### 시나리오 (사용자 기술)
잠기고 잠든 차 → 운전석 뒷문을 밖에서 엶 → 손잡이를 잡은 채로 충전 단자 잠금 해제 → 충전기 분리 → 뒷문 닫음 → 충전구 닫힘 → 트렁크를 밖에서 엶 → 밖에서 닫음 → 운전석을 밖에서 엶 → 닫음

### 시각별 결과 (관찰 시작 기준 +초)
| +초 | 관찰 | 해석 |
|---|---|---|
| 2.8 | `sleep=ASLEEP`, 문 4개·`rearTrunk`·`frontTrunk` = `UNKNOWN`(값 3), `gear=GEAR_UNKNOWN` (38B, 다음 줄은 `rearTrunk=CLOSED` 36B) | 잠든 차가 외부 손잡이로 깨어나는 첫 프레임 |
| 2.9 | `AWAKE`, `frontTrunk`만 `UNKNOWN` → `closureStatuses` 없음 → `rearDriverDoor=OPEN` | 깨어남, 뒷문 열림 |
| 3.3 | `chargePort=OPEN`, 이어서 `gear=GEAR_PARK` | 손잡이를 잡아 충전 단자 잠금 해제 |
| 5.5 | `presence=PRESENT` | 차 밖에서 문을 열고 서 있음 |
| 8.8 / 9.0 | `rearDriverDoor=CLOSED` / `NOT_PRESENT` | 뒷문 닫음 |
| 14.1 | 필드 8 하위 2 **1→2**, 곧 `closureStatuses` 없음 | 충전구 닫힘(충전기는 그 전에 분리) |
| 21.8 | `rearTrunk=OPEN` | 트렁크를 밖에서 엶 |
| 34.0 | `closureStatuses` 없음 | 트렁크 닫음 |
| 36.9 / 37.0 | `frontDriverDoor=OPEN` / `PRESENT` | 운전석을 밖에서 엶 |
| 40.2 | `closureStatuses` 없음 | 운전석 닫음 |

`lock`은 25줄 모두 `UNLOCKED`(0번 값이라 인코딩되지 않으므로 "빠짐"과 구분 불가). 하위 1은 이전처럼 1↔2를 오감.

### 사실 정리
1. **트렁크를 밖에서 손으로 열고 닫으면 브로드캐스트가 온다**(`rearTrunk=OPEN` → 부재). 2차의 실내 버튼(브로드캐스트 없음)은 그대로다. 키카드·폰 키·앱은 미확인.
2. **"부재 = 모두 닫힘" 규칙이 뒷문·트렁크·충전구 닫힘에서도 맞았다.** 프레임 길이를 대조하면 0이 아닌 enum 하나가 2바이트씩이고(38B→36B→28B), proto3가 0번 값(`CLOSED`)을 인코딩하지 않으므로 모든 필드가 `CLOSED`인 `ClosureStatuses`는 빈 메시지라 빠지는 것으로 설명된다. 깨어나는 순간의 "모두 CLOSED처럼 보이는 프레임"도 실제로는 `frontTrunk=UNKNOWN` 하나가 있었다.
3. **잠든 차의 첫 프레임을 처음 관찰했다.** `ASLEEP` → 약 0.1초 뒤 `AWAKE`. 첫 프레임들은 닫힘 상태가 `UNKNOWN`, `gear`가 `UNKNOWN`이고 약 0.5초 안에 실제 값이 된다.
4. **`userPresence=PRESENT`는 "앉아 있음"이 아니다.** 차 밖에서 문을 열고 서 있을 때도 `PRESENT`였다.
5. **충전기가 꽂혀 잠긴 상태의 `chargePort`는 `CLOSED`.** 잠금을 풀자 `OPEN`, 분리 후 덮개가 닫히자 부재. `CLOSED`가 "케이블 없음"을 뜻하지 않는다.
6. **필드 8 하위 2:** 충전기가 꽂혀 있는 동안 1, 분리 후 충전구가 닫힐 때 1→2(2차의 충전 시작 2→1과 반대 방향). 충전 케이블·충전 상태(1 = 사용 중, 2 = 대기)로 추정, 미확정(분리 시각보다 5초 이상 늦게 바뀜).
7. **외부 손잡이 조작에서는 `authenticationRequest`·필드 53이 오지 않았다**(2차 실내 손잡이에서는 왔다). `PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL`은 아직 한 번도 보지 못했다.

### 라이브러리 영향
- 코드 동작 변경 없음. `VehicleEvent.Status`는 조회와 같은 매핑(부재 = 모두 `CLOSED`, 스키마 밖·값 3 = `UNKNOWN`)이라 이번 프레임을 그대로 `rearTrunk=OPEN` 등으로 낸다.
- 문서·KDoc의 "트렁크 주의"를 "밖에서 손으로는 옴, 실내 버튼으로는 안 옴"으로 좁히고, 깨어나는 차의 `UNKNOWN`·`PRESENT`의 뜻·`chargePort` 주의를 `14-lib-reading-state` Part B에 추가했다.
- 샘플 Live 탭의 안내 문구("실내 버튼으로 트렁크를 열고 닫으면 방송이 오지 않을 수 있습니다")는 여전히 맞으므로 그대로 둔다.
