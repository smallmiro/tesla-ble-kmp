# M5 Infotainment 명령 P0/P1 + ping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PRD §5의 Infotainment P0/P1 명령 전부(공조 10, 충전 14, 차체 4, 보안 2, 미디어 8, SW 업데이트 2)와 `ping()`을 공개 `Vehicle` API로 제공하고, 골든 픽스처(climate-on, state charge)를 고정하며, 샘플 앱에 공조·충전 항목을 넣어 M5를 끝낸다.

**Architecture:** 이미 있는 `InfotainmentCommands.execute`(GCM, Infotainment 라우팅, `SendWithRetry.send` 명령 경로, `InfotainmentResponses`) 위에 Go와 같은 `Action` 빌더만 얹는다. 공개 파사드는 VCSEC 명령과 같은 `timeout ?: config.commandTimeout` + `.reconciling(INFOTAINMENT)` 패턴. 새 재시도·자동 세션 시작·자동 깨우기는 없다.

**Tech Stack:** Kotlin Multiplatform, Wire 생성 타입, SKIE, `FakeVehicle`.

**Spec:** `{{PRD_FILE}}` FR-034, FR-050~053, FR-060~069, FR-070~083, FR-084~085, FR-090~092. `{{SDD_FILE}}` §공개 API(309행 "Infotainment 제어"). 로드맵 M5 행. 참고 매뉴얼: `{{MANUAL_DIR}}05-command-catalog.md` §C~I, `03-protocol.md` §10, `08-errors.md` §3.6, `04-go-api-reference.md`. Go 원본: `{{REF_REPO_DIR}}pkg/vehicle/climate.go`, `charge.go`, `actions.go`, `infotainment.go`, `security.go`.

## Global Constraints

- 모든 명령은 `InfotainmentCommands.execute`(명령 경로)로 보낸다. 쓰기 실패 뒤 새 사본 금지 규칙(ADR-0010 2026-11-08 개정)이 그대로 적용된다. `ping`도 Go처럼 `executeCarServerAction`이므로 명령 경로.
- 세션을 시작하거나 깨우지 않는다. 세션이 없으면 `Failure(NoSession)`.
- 로컬 인자 검증은 Go에 있는 것만: valet PIN 4자리 숫자, `setVolume` 0..10, `scheduleDeparture` 출발 시각 0..24h, 좌석 쿨러는 앞좌석만. 실패는 `Failure(InvalidArgument)`이고 아무것도 보내지 않는다. Go가 검증하지 않는 값(충전 한도, 전류 등)은 검증하지 않는다.
- `disableValetMode`: 거부 사유가 "already off"로 끝나면 `Success`(Go와 같음).
- `TESLEMETRY-EXT` 필드를 쓰지 않는다(ADR-0002 결정 3).
- TDD, Tidy First, `tools/ci/verify.sh`, 사용 문서 MD + HTML ko/en/ja(절대 규칙 13), Rebase merge.
- 역할: 라이브러리 `library-developer`, HTML ko/ja `docs-translator`, 샘플 `android-developer`·`ios-developer`.

## 결정 기록 (Claude·Codex 협의, 2026-11-08, main `97d6e33`)

| 항목 | Claude 제안 | Codex 의견 | 최종 판단 |
|---|---|---|---|
| 빌더 위치 | `:application` 영역별 internal 파일 | 동의. 단 `:sdk`는 internal을 못 부르므로 공개 메서드는 `InfotainmentCommands`에 둔다 | 채택. 빌더 internal, 명령 메서드는 `InfotainmentCommands`(public 클래스) 멤버 |
| ping | `execute` 경로 | 동의. `ping_id = 1`, 응답 Ping 내용은 검사하지 않는다(Go) | 채택 |
| 좌석 | `SeatPosition` 9개 + UNKNOWN? | Go가 UNKNOWN을 다루므로 포함 | `SeatPosition` 10값(UNKNOWN 포함), 명시적 `when` 대응(ordinal 금지) |
| 단계 이름 | `Level`/`HeaterLevel` | 쿨러도 쓰므로 `SeatClimateLevel` | 채택. 쿨러는 OFF→1 … HIGH→4(Go `level + 1`) |
| COP 온도 | Go의 `Level` 캐스트 | 캐스트는 Go와 같지만 OFF→Unspecified라 의미가 어색하다. 전용 enum 권장 | **전용 `CabinOverheatProtectionTemperature { UNSPECIFIED, LOW, MEDIUM, HIGH }`**. 와이어 값 0~3은 Go와 같다. "UNSPECIFIED가 끈다"고 문서화하지 않는다 |
| 일정 | Wire `ChargeSchedule`/`PreconditionSchedule` 그대로 | 동의. Swift에서 생성자가 모든 필드 + `unknownFields`를 요구하므로 Swift 컴파일 확인 필수. ID는 `Long`(Wire uint64 표현) | 채택 |
| 시간 인자 | 분/초 `Int` | 동의. 차량 현지 자정 기준 분임을 문서화. Go는 출발 시각만 0..1440(포함) 검사 | 채택. 나머지 시간 값은 검사하지 않는다. 정책 OFF는 하위 메시지 없음, `clearScheduledDeparture`는 `enabled = false`만 |
| 검증 | PIN, 볼륨, 출발 시각, 쿨러 좌석 | PIN은 ASCII `0`~`9` 4자(Kotlin `isDigit()`은 넓다), 오류에 PIN을 넣지 않는다. **볼륨 NaN**: Go는 통과시키지만 거부 권장 | 채택. NaN 거부는 Go와의 **의도된 차이**로 SDD §12에 기록. `autoSeatAndClimate`는 Go처럼 지원하지 않는 좌석을 조용히 거르고(UNKNOWN 유지, 순서·중복 유지) 비어도 보낸다. 빈 히터 맵도 보낸다 |
| valet "already off" | Go처럼 성공 | 동의. Go는 모든 오류 문자열의 접미사를 보지만 `Failure(InfotainmentRejected)`로 좁히는 것이 합리적. 기록할 것 | 채택. 대소문자 구분 `endsWith("already off")`, `disableValetMode`에만 |
| 히터 맵 순서 | 삽입 순서 | 공개 `Map`은 순서를 보장하지 않는다(Swift 브리지). 좌석 순서로 정렬 권장 | 채택. `SeatPosition` 선언 순서로 정렬해 바이트를 결정적으로 만든다 |
| PR 분할 | 라이브러리 1 + 샘플 1 | 라이브러리 2(공조+충전 / 차체·미디어·보안·SW·ping) + 샘플 1. 공개 메서드는 51개 | **Codex 채택**: PR A(공조+충전), PR B(나머지+ping), PR C(샘플). 리뷰 범위가 작아지고 Swift 확인이 빨라진다 |
| 골든 픽스처 | climate-on은 `protocol.md` 예제 | NFR-002는 실차 캡처 기준. 합성 벡터와 캡처를 구분할 것. VIN 치환만으로 암호 캡처를 재생할 수 없다 | climate-on: `ProtocolVectors.HVAC_*`(protocol.md, 고정 nonce)로 평문·암호문 전체를 고정(합성, 출처 표기). state charge: 평문 인코딩 벡터 + 응답 디코딩(합성). **실차 캡처 골든은 사용자 실차 항목**으로 체크리스트에 남긴다(#118) |
| ADR | 불필요 | 라우틴 추가는 ADR 불필요. Go와 다른 동작은 기록 | 새 ADR 없음. 의도된 차이(NaN 거부, valet 접미사 범위, 히터 정렬, COP enum)는 SDD §12 "원본과 다른 동작 (의도)"와 ADR-0002 개정 절(입력 Wire 타입)에 기록 |

## 공개 API (확정)

`Vehicle`에 추가. 모두 `suspend`, `VehicleResult<Unit>`, 마지막 인자 `timeout: Duration? = null`.

| FR | 메서드 | PR |
|---|---|---|
| FR-060 | `climateOn()`, `climateOff()` | A |
| FR-061 | `setSeatHeater(levels: Map<SeatPosition, SeatClimateLevel>)` | A |
| FR-062 | `setClimateTemp(driverCelsius: Float, passengerCelsius: Float)` | A |
| FR-063 | `setSeatCooler(level: SeatClimateLevel, seat: SeatPosition)` | A |
| FR-064 | `setSteeringWheelHeater(enabled: Boolean)` | A |
| FR-065 | `autoSeatAndClimate(seats: List<SeatPosition>, enabled: Boolean)` | A |
| FR-066 | `setPreconditioningMax(enabled: Boolean, manualOverride: Boolean)` | A |
| FR-067 | `setClimateKeeperMode(mode: ClimateKeeperMode, manualOverride: Boolean)` | A |
| FR-068 | `setBioweaponDefenseMode(enabled: Boolean, manualOverride: Boolean)` | A |
| FR-069 | `setCabinOverheatProtection(enabled: Boolean, fanOnly: Boolean)`, `setCabinOverheatProtectionTemperature(temperature: CabinOverheatProtectionTemperature)` | A |
| FR-070 | `chargeStart()`, `chargeStop()` | A |
| FR-071 | `setChargeLimit(percent: Int)` | A |
| FR-072 | `setChargingAmps(amps: Int)` | A |
| FR-073 | `chargeStandardRange()`, `chargeMaxRange()` | A |
| FR-074 | `scheduleCharging(enabled: Boolean, minutesAfterMidnight: Int)` | A |
| FR-075 | `scheduleDeparture(departAtMinutes: Int, offPeakEndMinutes: Int, preconditioning: ChargingPolicy, offPeak: ChargingPolicy)`, `clearScheduledDeparture()` | A |
| FR-076 | `setLowPowerMode(enabled: Boolean)` | A |
| FR-077 | `setKeepAccessoryPowerMode(enabled: Boolean)` | A |
| FR-078~080 | `addChargeSchedule(schedule: ChargeSchedule)`, `removeChargeSchedule(id: Long)`, `batchRemoveChargeSchedules(home, work, other: Boolean)` | A |
| FR-081~083 | `addPreconditionSchedule(schedule: PreconditionSchedule)`, `removePreconditionSchedule(id: Long)`, `batchRemovePreconditionSchedules(home, work, other: Boolean)` | A |
| FR-052 | `openChargePort()`, `closeChargePort()` | A |
| FR-034 | `ping()` | B |
| FR-050 | `ventWindows()`, `closeWindows()` | B |
| FR-051 | `setSunroof(level: Int)` | B |
| FR-053 | `honkHorn()`, `flashLights()` | B |
| FR-084 | `setSentryMode(enabled: Boolean)` | B |
| FR-085 | `enableValetMode(pin: String)`, `disableValetMode()`, `resetValetPin()` | B |
| FR-090 | `volumeUp()`, `volumeDown()`, `setVolume(volume: Float)` | B |
| FR-091 | `mediaNextTrack()`, `mediaPreviousTrack()`, `mediaNextFavorite()`, `mediaPreviousFavorite()`, `toggleMediaPlayback()` | B |
| FR-092 | `scheduleSoftwareUpdate(delaySeconds: Int)`, `cancelSoftwareUpdate()` | B |

새 `:domain` `model/` 타입: `SeatPosition`, `SeatClimateLevel`, `ClimateKeeperMode`, `ChargingPolicy`(PR A), `CabinOverheatProtectionTemperature`(PR A).

## Review Focus

1. 인코딩 함정: OFF 단계, 선루프 0, 절대 볼륨 0, 충전 start/stop의 oneof가 명시적으로 선택되는지(Wire는 기본값 필드를 생략하지만 oneof 멤버는 보낸다). `setClimateTemp`에 `Temperature(TEMP_MAX)`가 들어가는지.
2. 잘못된 인자는 아무 프레임도 보내지 않는다(`authenticatedPayloads` 비어 있음).
3. 응답 유실은 `Uncertain(Timeout(afterSend = true))`, 재전송 없음. `toggleMediaPlayback`는 특히(FR-091).
4. 세션이 없으면 `Failure(NoSession)`이고 파사드 `sessions`가 `None`으로 맞춰진다.
5. 모든 명령의 바이트 기대값은 Go 빌더를 손으로 옮긴 독립 hex로 적는다(구현 코드로 기대값을 만들지 않는다).

---

### Task A1: 매개변수 타입 (struct 커밋)
**Files:** Create `domain/src/commonMain/kotlin/dev/mirolab/teslable/model/{SeatPosition,SeatClimateLevel,ClimateKeeperMode,ChargingPolicy,CabinOverheatProtectionTemperature}.kt`
- [x] enum만 추가(동작 없음). KDoc에 Go 이름 대응. `struct: seat, climate keeper, charging policy and COP temperature types`.

### Task A2: 공조 명령 (FR-060~069)
**Files:** Create `application/.../infotainment/ClimateActions.kt`(internal 빌더), Modify `InfotainmentCommands.kt`(공개 메서드), Test `application/src/commonTest/.../infotainment/ClimateCommandsTest.kt`
- [x] 실패 테스트: 명령마다 `fake.authenticatedPayloads(DOMAIN_INFOTAINMENT)`를 독립 hex와 비교. `climateOn` = `120452020801`(`ProtocolVectors.HVAC_ON_PLAINTEXT`). 쿨러 뒷좌석 → `Failure(InvalidArgument)` + 보낸 것 없음. 빈 히터 맵은 보냄. autoSeat 필터. 히터 맵 정렬.
- [x] 구현 → 통과 → `feat: climate commands over Infotainment` (Refs: FR-060~069).

### Task A3: 충전 명령 (FR-052, FR-070~083)
**Files:** Create `ChargeActions.kt`, Modify `InfotainmentCommands.kt`, Test `ChargeCommandsTest.kt`
- [x] 실패 테스트: 명령별 hex. `scheduleDeparture` 1440 허용·1441/-1 거부, 정책 OFF는 하위 메시지 없음. `clearScheduledDeparture`. 일정 추가는 받은 Wire 메시지를 그대로 담는다.
- [x] 구현 → 통과 → `feat: charging commands over Infotainment` (Refs: FR-052, FR-070~083).

### Task A4: 공통 실패 경로
**Test:** `InfotainmentCommandFailureTest.kt` — 거부(`InfotainmentRejected`), 응답 유실(`Uncertain(Timeout(true))`, 1회만 전송), 세션 없음(`NoSession`, 전송 없음), 쓰기 실패 뒤 새 사본 없음(기존 fake), 취소 뒤 다음 명령 성공. 통과하면 `test:` 커밋.

### Task A5: 공개 API (PR A 범위)
**Files:** `sdk/.../Vehicle.kt`, `DefaultVehicle.kt`, `sdk/api/`, Test `sdk/src/commonTest/.../VehicleInfotainmentCommandsTest.kt`
- [x] 기본/사용자 timeout, `NoSession` 시 `sessions` 맞춤. `apiDump` 추가만. Swift typecheck(`climateOn`, `setSeatHeater` 딕셔너리, `ChargeSchedule` 생성, `scheduleDeparture`). `feat: public climate and charging commands`.

### Task A6: 골든 벡터
**Files:** `testing/.../fixtures/ProtocolVectors.kt`(필요 시 상수 추가), Test `application/.../infotainment/InfotainmentGoldenTest.kt`
- [x] climate-on: `protocol.md` 예제 키·epoch·counter·expires·nonce를 주입해 암호문 `38038e8c0f2e`·태그 `c228e0ff…`와 일치. state charge: `GetChargeState` 평문 hex 고정 + 응답 디코딩. 합성 벡터임을 주석에 명시. `test:` 커밋.

### Task A7: 문서 (PR A)
- [x] PRD FR-061~083 시그니처 정정(위 표), SDD 공개 API·§12 원본과 다른 동작, ADR-0002 개정 절(입력에 Wire 일정 타입), 로드맵.
- [x] `lib-docs-sync`: `11`(상태 표), `15-lib-commands`(공조·충전, Kotlin·Swift 예제), `16`(InvalidArgument), `17`(Swift 딕셔너리·일정 생성) MD + HTML en → ko/ja(`docs-translator`). `check-manual-i18n.sh`.

### Task A8: 검증·리뷰·머지 (PR A)
- [ ] `tools/ci/verify.sh` → 새 컨텍스트 리뷰(general-purpose, `git diff main...HEAD`, DoD, Go 대조) → 수정 → PR(rebase merge).

### Task B1~B6: 나머지 명령 + ping (PR B)
A2~A8과 같은 순서: `VehicleControlActions.kt`(창문, 선루프, 경적, 라이트), `SecurityActions.kt`(센트리, 발레), `MediaActions.kt`(볼륨, 트랙, 즐겨찾기, 토글), `SoftwareUpdateActions.kt`, `ping`. 검증: PIN ASCII 4자리(오류에 PIN 없음), 볼륨 0..10 + NaN 거부, `disableValetMode` "already off" → Success. `toggleMediaPlayback` 응답 유실 시 재전송 없음 시험. 문서 `11`·`15`·`16`·`17` + PRD/SDD. 머지.

### Task C1~C4: 샘플 (PR C)
- [ ] `android-developer`·`ios-developer`(`sample-from-lib-docs`): Query/Control 화면에 공조 on/off, 충전 start/stop. 연결·키·세션 조건, `Uncertain`은 그대로 보여 주고 `getState(CLIMATE/CHARGE)` 재조회 버튼, 연결이 바뀐 뒤 늦은 결과는 버린다, 자동 재시도·깨우기 없음.
- [ ] 샘플 스펙(SFR-001/002 상태, 앱 스펙) MD + HTML ko/en/ja, runbook에 실차 확인 절차.
- [ ] 빌드·단위 시험, verify.sh, 리뷰, 머지.

### Task D: 마일스톤 종료
- [ ] `11-lib-overview` 상태 표 점검, 로드맵 M5 완료 표시, 실차 체크리스트(명령별 확인 항목, 실차 캡처 골든 climate-on·state charge)를 `{{HANDOFF_DIR}}` 인계 노트와 #118에 남긴다.
