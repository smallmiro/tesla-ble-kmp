# HANDOFF — Tesla BLE KMP 라이브러리 직접 포팅

> 이 문서는 **다음 작업 세션**에게 넘기는 메타 프롬프트입니다. 주 도구는 Claude Code + Superpowers이고, Codex CLI와 GitHub Copilot CLI도 같은 저장소에서 사용합니다.
> 공통 에이전트 지침(`AGENTS.md`), Claude 전용 지침(`CLAUDE.md`), 개발 표준(`docs/workflow.md`), 경로 정본(`docs/PATHS.md`) 등 **착수에 필요한 파일 전체가 이 문서의 부록 A에 들어 있습니다.** 빈 저장소에 이 문서 하나만 있어도 Phase 0에서 모두 생성됩니다.
> 이 문서를 기준으로 **PRD → SDD → 구현 계획 → 구현 → 매뉴얼** 순서로 산출물을 만듭니다.
> 에이전트는 이 문서를 처음부터 끝까지 읽은 뒤 §9 워크플로를 따라 작업을 시작하세요.

---

## 0. 경로 설정

> **경로의 정본은 `docs/PATHS.md` 입니다.** 아직 없으면 Phase 0에서 **부록 A-1**의 내용으로 생성합니다. 이 문서와 이후 모든 산출물은 경로를 직접 쓰지 않고 `{{키}}` 로 참조합니다.
> 매뉴얼 위치(`MANUAL_DIR`)를 포함해 경로를 바꾸려면 `docs/PATHS.md` 의 값 한 줄만 고치면 됩니다.

> 📘 **개발 매뉴얼을 반드시 사용하세요.** 사용자가 실제 `vehicle-command` 저장소(https://github.com/teslamotors/vehicle-command)를 기준으로 만든 개발 매뉴얼이 **`documents/md/`**(`{{MANUAL_DIR}}`)에 있습니다.
> - 포팅, PRD, SDD, 계획, 구현의 **1차 가이드**로 사용합니다. 이 HANDOFF의 §4~§7 요약보다 매뉴얼의 내용이 더 자세하면 매뉴얼을 따릅니다.
> - 매뉴얼과 원본 Go 코드가 다르면 **원본 코드 기준**으로 구현하고, 불일치 내용을 사용자에게 보고합니다.
> - 매뉴얼은 **읽기 전용**입니다. 사용자 승인 없이 수정하지 않습니다.
> - 이 라이브러리의 사용 문서(에이전트가 작성하는 산출물)는 별도 위치 `{{LIB_DOCS_DIR}}` 에 씁니다.
> 값이 `TBD` 인 키를 써야 하는 시점이 오면 **작업을 멈추고 사용자에게 확인**합니다.

이 문서에서 쓰는 주요 키: `MANUAL_DIR`, `MANUAL_INDEX`, `LIB_DOCS_DIR`, `LIB_DOCS_INDEX`, `PRD_FILE`, `SDD_FILE`, `SPECS_DIR`, `PLANS_DIR`, `ADR_DIR`, `HANDOFF_DIR`, `WORKFLOW_FILE`, `REF_REPO_DIR`, `REF_REPO_COMMIT`, `POC_DIR`, `LIB_NAME`, `BASE_PACKAGE`

---

## 1. 역할과 미션

당신은 **Kotlin Multiplatform 라이브러리 엔지니어**입니다.

**미션:** Tesla 공식 Go SDK `teslamotors/vehicle-command` 중 **BLE 경로에 필요한 부분만** Kotlin Multiplatform(Android + iOS)으로 직접 포팅해,
**서버 없이** 스마트폰에서 테슬라 차량과 BLE로 통신(키 등록, 조회, 제어)하는 라이브러리 `{{LIB_NAME}}` 을 만듭니다.
이 라이브러리를 사용하는 앱(Android/iOS)은 별도 프로젝트이며, 이번 범위에는 **샘플 앱만** 포함합니다.

---

## 2. 이미 결정된 사항 (재논의 금지, 변경은 ADR로)

| # | 결정 | 근거 |
|---|---|---|
| D1 | **서버 없음.** Fleet API, OAuth, 도메인 공개키 호스팅을 쓰지 않고 BLE만 사용 | 제품 요구 |
| D2 | **Kotlin Multiplatform** (Android + iOS). Flutter 검토 후 기각 | 위젯, 워치, 네이티브 확장성 |
| D3 | 기존 KMP 구현체가 없으므로 **직접 포팅** | 조사 결과 (§6.5) |
| D4 | 동작의 정답 기준 = **공식 Go 구현 (`{{REF_REPO_COMMIT}}`) + `protocol.md`** | 공식 레퍼런스 |
| D4-1 | 작업 가이드 = **`{{MANUAL_DIR}}` 개발 매뉴얼** (원본 기준으로 사용자가 작성). 원본과 다르면 원본을 따르고 보고 | 사용자 제공 |
| D5 | 키 등록은 **BLE add-key-request + NFC 키카드 태그** 방식 | 서버가 없어도 가능한 유일한 방법 |
| D6 | BLE 명령 인증은 **AES-GCM만** 구현. HMAC 서명은 응답 검증과 세션정보 검증에만 사용 | Go 구현의 BLE 기본 동작 |
| D7 | 라이선스는 **Apache-2.0** (원본 고지 NOTICE 유지) | 원본 Apache-2.0 |
| D8 | **AGPL 코드(yoziru/tesla-ble 등)는 열람과 복사 모두 금지** | 라이선스 오염 방지 |

---

## 3. 범위

### In scope
- BLE 전송: 스캔(광고 이름 매칭), 연결, MTU 협상, 청크 분할과 재조립, indication 구독
- 프로토콜: RoutableMessage, 도메인별 핸드셰이크, 세션 키 유도, AES-GCM 명령 암호화, 응답 복호화, 세션정보 HMAC 검증, 재전송 방지 윈도우, 세션 캐시
- 키 관리: 키쌍 생성과 보관(플랫폼 보안 영역), add-key-request, 키 목록 조회, 키 삭제
- 조회: VCSEC `VehicleStatus`, Infotainment `GetState` 12개 카테고리 (§5.1)
- 제어: `pkg/vehicle` 공개 API 전체 (§5.2)
- 에러 모델: `PossibleSuccess`, `PossibleTemporary`, 재시도 정책
- 샘플 앱(Android, iOS 각 1개), 라이브러리 사용 문서(`{{LIB_DOCS_DIR}}`)

### Out of scope
- Fleet API, OAuth, Fleet Telemetry, HTTP proxy, JWS/Schnorr 서명(`internal/schnorr`, `pkg/account`, `pkg/proxy`, `pkg/cli`)
- 차량 측 검증 코드(`authentication/verifier.go`). 단, 테스트용 가짜 차량 구현에는 참고 가능
- 2021년 이전 Model S/X (이 프로토콜을 지원하지 않음)
- 백그라운드 자동 잠금 해제(Phone-as-Key 수준). **v2 후보**로만 기록

---

## 4. 프로토콜 핵심 사실 (코드 검토로 확인됨)

### 4.1 BLE 전송
- Service `00000211-b2d1-43f0-9b88-960cebf8b91e`
- TX `00000212-…`: **write with response**
- RX `00000213-…`: **indication** 구독
- 광고 이름: `"S" + hex(SHA1(VIN)[0:8]) + "C"`
  - 예: VIN `5YJS0000000000000` → `S1a87a5a75f3df858C`
- 광고의 connectable=false는 연결 슬롯이 가득 찼다는 뜻 (`ErrMaxConnectionsExceeded`). 슬롯은 키포브와 폰 키를 합쳐 3개
- 프레임: `2바이트 BE 길이 + 메시지`. 메시지 최대 1024바이트
- TX 청크 크기 = `min(MTU, 1024) - 3`
- RX 청크 사이 간격이 **1초를 넘으면 버퍼를 리셋**
- 연결 재시도 간격 1초

### 4.2 도메인과 라우팅
- `DOMAIN_VEHICLE_SECURITY(2)` = VCSEC
  - 잠금, 트렁크, 원격 시동, 키 관리 담당. **인포테인먼트가 잠들어 있어도 동작**
- `DOMAIN_INFOTAINMENT(3)`: 나머지 전부. 차량을 깨워야 동작
- **VCSEC 요청**
  - 요청마다 **새 랜덤 16B routing_address** 를 쓰고, 응답은 주소로 매칭 (VCSEC는 request_uuid를 돌려주지 않음)
  - **동시 요청 금지**
- **Infotainment 요청**
  - 연결 동안 고정된 routing_address를 쓰고, 응답은 **request_uuid** 로 매칭
- 요청 uuid는 16B 랜덤. 모든 요청에 `FLAG_ENCRYPT_RESPONSE` 를 설정

### 4.3 암호와 세션 (PoC 검증 완료)
- 키: NIST P-256
- 공개키 인코딩: `0x04||X||Y` (65B)
- `K = SHA1(ECDH_X)[:16]`
- 서브키 = `HMAC-SHA256(K, label)`. label은 `"session info"` 또는 `"authenticated command"`
- **메타데이터 TLV**
  - `tag(1B)||len(1B)||value` 를 **tag 오름차순**으로 나열하고 끝에 `0xFF`
  - uint32 값은 4바이트 BE
- **세션정보 검증**
  - 태그 = `HMAC(SESSION_INFO_KEY, TLV{SIG_TYPE=HMAC(6), PERSONALIZATION=VIN, CHALLENGE=요청 uuid} || session_info)`
  - 비교는 상수 시간으로 수행
- **명령 암호화**
  - AES-GCM, nonce 12B
  - AAD = `SHA256(TLV{SIG_TYPE=5, DOMAIN, VIN, EPOCH, EXPIRES_AT, COUNTER, [FLAGS≠0]})`
- **응답 복호화**
  - AAD = `SHA256(TLV{SIG_TYPE=9, DOMAIN, VIN, COUNTER, FLAGS(항상 포함), REQUEST_HASH, FAULT})`
  - `REQUEST_HASH` = `sigType(1B) || 요청 tag`
- **counter**
  - 같은 epoch 안에서 단조 증가해야 함. epoch가 바뀔 때만 되돌릴 수 있음
  - 응답 counter는 슬라이딩 윈도우(32)로 재사용 여부 검사
- **시계**
  - `timeZero = now - clock_time` 을 저장하고, `expiresAt = (now + expiresIn) - timeZero` 로 계산
  - BLE에서는 세션정보 응답이 **요청 후 4초를 넘으면 폐기**
- **세션정보 폐기 조건**: HMAC 불일치, 오래된 uuid, 같은 epoch에서 clock 역행

### 4.4 VCSEC 특이사항
- **키 등록 요청은 형식이 다름**
  - `RoutableMessage` 로 감싸지 않고 **`ToVCSECMessage{ signedMessage{ SIGNATURE_TYPE_PRESENT_KEY, payload=UnsignedMessage.whitelistOperation.addKeyToWhitelistAndAddPermissions } }`** 를 그대로 전송. 인증 없음
  - 이후 사용자가 콘솔에 NFC 키카드를 태그하고 화면에서 승인
- **응답이 최대 3개**까지 옴
  - `OPERATIONSTATUS_WAIT`: 바쁨 또는 키카드 대기. 재시도하거나 계속 대기
  - `OPERATIONSTATUS_ERROR` 단독 응답: 무시하고 다음 응답을 기다림
  - whitelist 작업: `whitelistOperationStatus` 가 있는 응답이 최종
  - RKE 명령: `commandStatus` 가 없는 응답이 최종
  - 그 외: 빈 메시지는 성공, `nominalError` 는 실패
- **인증 없이 가능한 조회**: `InformationRequest(GET_STATUS / GET_WHITELIST_INFO / GET_WHITELIST_ENTRY_INFO)`
- **깨우기**: BLE에서는 `RKEAction WAKE_VEHICLE` (VCSEC) 사용

### 4.5 역할(Role)
- Owner: 전부
- Driver: 키와 PIN 관리 불가
- ChargingManager: 조회 + 충전
- VehicleMonitor: 조회만
- FleetManager: **BLE 불가**
- 라이브러리는 등록할 키의 역할을 선택할 수 있어야 함

---

## 5. 기능 카탈로그

### 5.1 조회
| 구분 | 인증 | 차량 깨우기 | 항목 |
|---|---|---|---|
| 스캔 | – | – | 존재, RSSI, 연결 가능 여부 |
| VCSEC `VehicleStatus` | 불필요 | 불필요 | 잠금 상태, 도어 4개, 프렁크·트렁크, 충전구, 톤노커버(+%), 수면 상태, 탑승자 |
| VCSEC Whitelist | 불필요 | 불필요 | 키 개수, 슬롯, 공개키, 역할, 형태 |
| Infotainment `GetState` | 필요 | 필요 | Charge(74), Climate(45), Drive(13), Location(19), Closures(24), ChargeSchedule(6), PreconditioningSchedule(4), TirePressure(18), Media(8), MediaDetail(6), SoftwareUpdate(7), ParentalControls(3) (괄호 안은 필드 수) |

### 5.2 제어 (`pkg/vehicle` 기준)
- **VCSEC**
  - `Lock`, `Unlock`, `Wakeup`, `RemoteDrive`, `AutoSecureVehicle`
  - `OpenTrunk`, `CloseTrunk`, `ActuateTrunk`, `OpenFrunk`
  - `Open/Close/StopTonneau`
  - `AddKey(WithRole)`, `RemoveKey`, `SendAddKeyRequest(WithRole)`, `KeySummary`, `KeyInfoBySlot`
- **Infotainment: 차체**
  - `Vent/CloseWindows`, `ChangeSunroofState`, `ChargePortOpen/Close`
  - `HonkHorn`, `FlashLights`, `TriggerHomelink`
- **Infotainment: 공조**
  - `ClimateOn/Off`, `ChangeClimateTemp`, `SetSeatHeater`, `SetSeatCooler`, `SetSteeringWheelHeater`
  - `AutoSeatAndClimate`, `SetPreconditioningMax`, `SetClimateKeeperMode`, `SetBioweaponDefenseMode`
  - `SetCabinOverheatProtection(+Temperature)`
- **Infotainment: 충전**
  - `ChargeStart/Stop`, `ChangeChargeLimit`, `ChargeStandard/MaxRange`, `SetChargingAmps`
  - `ScheduleCharging`, `ScheduleDeparture`, `ClearScheduledDeparture`
  - `Add/Remove/BatchRemoveChargeSchedule(s)`, `Add/Remove/BatchRemovePreconditionSchedule(s)`
  - `SetLowPowerMode`, `SetKeepAccessoryPowerMode`
- **Infotainment: 보안·모드**
  - `SetSentryMode`, `SetValetMode`, `ResetValetPin`
  - `SetPINToDrive`, `ClearPINToDrive`
  - SpeedLimit 계열 5개, ParentalControls 계열 5개
  - `SetGuestMode`, `EraseGuestData`
- **Infotainment: 미디어·기타**
  - `VolumeUp/Down`, `SetVolume`, `MediaNext/PreviousTrack`, `MediaNext/PreviousFavorite`, `ToggleMediaPlayback`
  - `ScheduleSoftwareUpdate`, `CancelSoftwareUpdate`, `SetVehicleName`, `Ping`
  - `GetNearbyCharging`: BLE로 데이터가 오는지 **미검증**. PRD에서 우선순위를 낮게 둠
- 기준 목록: `{{REF_REPO_DIR}}pkg/vehicle/*.go`, `{{REF_REPO_DIR}}cmd/tesla-control/commands.go`

---

## 6. 기술 기반

### 6.1 목표 모듈 구조 (SDD에서 확정)
```
{{LIB_NAME}}/
 ├─ protocol/    commonMain  Wire 생성 코드, Metadata, Session, SlidingWindow, 에러 타입
 ├─ dispatcher/  commonMain  코루틴·Flow 기반 요청/응답 매칭, 도메인 세션, 재시도, 세션 캐시
 ├─ transport/   commonMain  Transport 인터페이스, Kable BLE 구현, 프레이밍/재조립
 ├─ vehicle/     commonMain  공개 API (Vehicle, 조회, 제어)
 ├─ keystore/    expect/actual  키 생성·보관·ECDH (iOS Security.framework / Android Keystore)
 ├─ testing/     commonTest   가짜 차량(FakeVehicle), 테스트 벡터, 골든 TX/RX
 └─ samples/     androidApp, iosApp
```

### 6.2 라이브러리 후보 (SDD에서 버전 고정)
| 역할 | 후보 | 비고 |
|---|---|---|
| Protobuf | **Wire** (Square) | .proto 9개 모두 proto3. `google.protobuf.Timestamp` 지원 |
| BLE | **Kable** (JuulLabs) | Android와 iOS 지원. indication 구독과 MTU 처리 확인 필요 |
| 비동기 | kotlinx-coroutines | |
| 암호 원시연산 | `CryptoPrimitives` 인터페이스 + 플랫폼 구현 | 아래 제약 참조 |

### 6.3 플랫폼 제약
- **iOS**
  - Kotlin/Native는 Swift 전용 API인 **CryptoKit을 직접 호출할 수 없음**
  - 키와 ECDH는 Security.framework(C API)로 구현
    - 키 생성: `SecKeyCreateRandomKey` + Secure Enclave 옵션
    - ECDH: `SecKeyCopyKeyExchangeResult(.ecdhKeyExchangeStandard)`. 반환값은 공유점 X좌표 그대로
  - AES-GCM은 CommonCrypto에 공개 API가 없음. 다음 중 하나를 SDD에서 ADR로 결정
    - (a) Swift CryptoKit 구현을 인터페이스로 주입
    - (b) cryptography-kotlin의 Apple 프로바이더 (도입 전에 ECDH와 GCM 지원을 반드시 확인)
- **Android**
  - Keystore 안에서 ECDH를 하려면 `PURPOSE_AGREE_KEY` 가 필요하고 **API 31 이상**에서만 가능
  - 더 낮은 버전 지원 여부와 대체 방식(소프트웨어 키를 암호화해 저장)은 PRD에서 결정
- **공통**: 백그라운드 BLE는 OS 제약을 받음. v1은 앱이 포그라운드에 있을 때의 사용을 기준으로 함

### 6.4 기존 PoC (`{{POC_DIR}}`)
- `TeslaProtocolCore.kt`
  - commonMain용 순수 Kotlin 코드
  - Metadata, Session(K 유도, 세션정보 태그, GCM 암호화와 응답 복호화), BleTransport(이름, 프레이밍, 재조립), SlidingWindow
- `PocTest.kt`
  - JCA 구현과 `protocol.md` 테스트 벡터 **17개가 모두 PASS**
  - 스펙 예제의 GCM 암호문과 바이트 단위로 일치
- → **commonMain, commonTest의 출발점으로 이관**합니다. 수기로 작성한 protobuf 리더는 Wire로 교체합니다.

### 6.5 참고 구현 (라이선스 준수)
| 저장소 | 사용 방식 |
|---|---|
| `teslamotors/vehicle-command` (Apache-2.0) | 정답 기준. 포팅 원본 |
| `shoujiaxin/swift-tesla-ble` (MIT) | iOS CoreBluetooth와 키체인 처리 참고. 코드를 가져오면 고지 필요 |
| `Teslemetry/python-tesla-fleet-api` | 응답 확인 흐름과 재시도 방식 참고 |
| `yoziru/tesla-ble` (**AGPL**) | **열람 금지** |

---

## 7. Go → KMP 포팅 매핑

| Go (기준 커밋) | 줄 수* | KMP 대상 | 비고 |
|---|---|---|---|
| `internal/authentication/{metadata,native,peer,signer,window,crypto,error}.go` | ~500 | `protocol/` | PoC로 대부분 완료 |
| `internal/dispatcher/*.go` | 595 | `dispatcher/` | goroutine/채널을 코루틴/Flow로 재작성 |
| `pkg/connector/connector.go`, `ble/ble.go` | ~430 | `transport/` | Kable로 대체. 프레이밍은 PoC 사용 |
| `pkg/vehicle/*.go` | 1,662 | `vehicle/` | 명령별 protobuf 조립 |
| `pkg/protocol/{error,key}.go` | 319 | `protocol/` | 에러 분류 (`ShouldRetry`, `PossibleSuccess`) |
| `pkg/cache/cache.go` | 79 | `dispatcher/` | 세션 캐시 저장소는 플랫폼별 |
| `pkg/protocol/protobuf/*.proto` | 1,930 | Wire 입력 | 수정 없이 사용 |

\* 주석과 빈 줄을 제외한 수치. 포팅할 전체 분량은 **약 3,000줄**.

---

## 8. 품질·보안 요구 (PRD의 NFR 초안)
1. **테스트 우선(TDD)**
   - `protocol.md` 테스트 벡터를 전부 commonTest로 옮김
   - Go 테스트(`*_test.go`) 중 클라이언트 측 케이스를 포팅
2. **골든 테스트**
   - PC에서 `tesla-control -ble -debug` 로 수집한 TX/RX 헥사 값을 픽스처로 사용해 인코딩과 디코딩을 검증
3. **가짜 차량(FakeVehicle)**
   - Go `verifier.go` 를 참고해 차량 측 핸드셰이크와 응답을 흉내냄
   - 다음 흐름을 실제 차량 없이 통합 테스트: dispatcher 매칭, VCSEC 다중 응답, 재전송 방지, 세션 복구
4. **키 보안**
   - 개인키는 가능한 한 하드웨어 보안 영역에서 생성하고 밖으로 내보내지 않음
   - 세션 키는 메모리에만 두고, 캐시에는 세션정보만 저장
5. **상수 시간 비교**: HMAC 검증은 상수 시간으로. 자체 구현 암호 알고리즘은 금지(원시 연산은 플랫폼 제공 기능만 사용)
6. **로그**: 디버그 모드에서만 TX/RX 헥사를 출력. VIN은 마스킹. 개인키와 세션 키는 절대 출력하지 않음
7. **명령 결과**
   - 명령 결과는 세 가지로 구분: 성공 / 실패 / **결과 불확실(PossibleSuccess)**
   - 결과가 불확실하면 상태를 다시 조회해 확인할 수 있는 API를 제공
8. **라이선스**: NOTICE에 원본 저작권을 고지하고, 포팅한 파일 헤더에 출처 커밋과 경로를 표기

---

## 9. 작업 워크플로 (Superpowers)

> 각 Phase가 끝날 때마다 **🛑 사용자 승인 게이트**를 둡니다. 승인 없이 다음 Phase로 넘어가지 않습니다.
> Superpowers 스킬을 우선 사용하고, 스킬의 기본 저장 경로 대신 §0의 경로 키를 사용하세요.

### Phase 0: 준비
1. **부록 A의 파일을 생성합니다.**
   - 부록 A의 각 항목을 표시된 경로에 **내용 그대로(바이트 단위로 동일하게)** 씁니다. 바깥 `~~~~` 펜스는 파일 내용에 포함하지 않습니다.
   - 같은 경로에 파일이 이미 있으면 덮어쓰지 않습니다. 차이(diff)를 보여주고 사용자에게 어느 쪽을 쓸지 확인합니다.
   - 생성 후 `docs:`(지침·문서), `chore:`(.gitignore, 템플릿), `test:`(PoC) 로 **나눠서** 커밋합니다.
   - 이후 `AGENTS.md`, `CLAUDE.md`, `{{WORKFLOW_FILE}}`, `{{PATHS_FILE}}` 를 읽습니다. (Claude Code는 `using-superpowers` 로 스킬 사용법도 확인)
2. 원본 저장소를 준비합니다.
   - `{{REF_REPO_DIR}}` 에 원본이 이미 있으면 그대로 사용하고, 없으면 `git clone {{REF_REPO_URL}}.git {{REF_REPO_DIR}}` 로 받습니다.
   - `git -C {{REF_REPO_DIR}} rev-parse HEAD` 가 `{{REF_REPO_COMMIT}}` 와 다르면, 사용자에게 알리고 어느 커밋을 기준으로 할지 확인합니다.
   - 루트 `.gitignore` 에 `/vehicle-command/` 가 있는지 확인하고, `git status` 로 원본이 추적되지 않는지 확인합니다. 원본은 절대 커밋하지 않습니다.
   - 원본은 **읽기 전용**으로 취급합니다. 수정하지 않습니다.
3. `{{POC_DIR}}` 의 PoC(부록 A-7, A-8)가 생성됐는지 확인합니다.
4. **개발 매뉴얼을 확인합니다.**
   - `{{MANUAL_DIR}}`(`documents/md/`)의 파일 목록을 확인하고, 목차 파일을 찾아 `{{PATHS_FILE}}` 의 `MANUAL_INDEX` 칸에 기록합니다.
   - 매뉴얼 전체를 훑고 **주제 → 매뉴얼 문서** 대응표를 만들어 사용자에게 보여줍니다. 예: BLE 전송 → `documents/md/…`, 핸드셰이크 → …, VCSEC → …
   - 이 대응표를 이후 PRD, SDD, 계획에서 참조합니다. PRD/SDD의 각 항목에 근거가 된 매뉴얼 문서를 표기합니다.
   - 매뉴얼 폴더가 없거나 비어 있으면 작업을 멈추고 사용자에게 확인합니다.
5. 브랜치 보호(main 직접 푸시 금지, CI 필수, rebase merge만 허용)를 설정하도록 사용자에게 안내합니다.

### Phase 1: PRD (`brainstorming`)
- §10의 열린 질문을 **한 번에 하나씩** 사용자에게 묻고 결정합니다.
- Phase 0에서 만든 **주제 → 개발 매뉴얼 대응표**를 근거로 질문하고, 각 요구사항에 근거 매뉴얼 문서를 표기합니다.
- 결과를 `{{PRD_FILE}}` 로 작성합니다(§11.1 템플릿).
- 🛑 PRD 승인

### Phase 2: SDD (`brainstorming` 설계 단계)
- 모듈 경계, 공개 API 시그니처, 스레딩 모델, 에러 모델, 세션 캐시, 키스토어 추상화, iOS GCM 방식을 결정합니다.
- 설계는 `{{MANUAL_DIR}}` 개발 매뉴얼을 기준으로 잡고, 원본 Go 코드로 검증합니다. 매뉴얼과 원본이 다른 부분은 SDD의 별도 절에 목록으로 남깁니다.
- 결과를 `{{SDD_FILE}}` 로 작성합니다(§11.2 템플릿). 설계 원본은 `{{SPECS_DIR}}` 에 보관합니다.
- 주요 결정마다 `{{ADR_DIR}}NNNN-*.md` 를 작성합니다.
- 🛑 SDD 승인

### Phase 3: 구현 계획 (`writing-plans`)
- §12의 마일스톤 단위로 계획을 `{{PLANS_DIR}}` 에 작성합니다.
- 각 작업에 포함할 것: 대상 파일, 대응하는 Go 원본 경로, 먼저 작성할 실패 테스트, 검증 명령
- 🛑 계획 승인

### Phase 4: 구현
- 작업 공간은 `using-git-worktrees` 로 분리합니다.
- 구현은 `subagent-driven-development` 로 진행하고, 모든 작업에 `test-driven-development`(RED→GREEN→REFACTOR)를 적용합니다.
- 디버깅은 `systematic-debugging` 을 사용합니다. 스펙이 애매하면 Go 원본 코드를 기준으로 판단합니다.
- 마일스톤이 끝날 때마다 `requesting-code-review` 를 수행합니다.
- 마일스톤이 끝날 때마다 **라이브러리 사용 문서를 갱신합니다(§13).**
- 각 작업은 해당 주제의 `{{MANUAL_DIR}}` 개발 매뉴얼 문서를 먼저 읽고 시작합니다.
- 작업 단위마다 짧은 브랜치 → PR(CI Green + AI 리뷰 통과) → rebase merge 로 main에 병합합니다(`{{WORKFLOW_FILE}}` §5).
- 마일스톤이 끝나거나 컨텍스트가 길어지면 `{{HANDOFF_DIR}}` 에 인계 노트를 남깁니다(`{{WORKFLOW_FILE}}` §10).

### Phase 5: 마무리 (`finishing-a-development-branch`)
- 전체 테스트, 샘플 앱 빌드, 매뉴얼 링크 검증을 마칩니다.
- 실차 검증 체크리스트를 사용자에게 전달합니다. 실차 테스트는 사용자가 수행합니다.

---

## 10. 열린 질문 (Phase 1에서 결정)
1. 최소 지원 버전: iOS __ / Android API __ (Android 31 미만을 지원할지, 지원한다면 키를 어떻게 보관할지)
2. 개인키 보관: 하드웨어 전용 / 하드웨어를 기본으로 하되 소프트웨어 대체 허용
3. 등록할 키의 기본 역할: Owner / Driver
4. 공개 API 스타일: `suspend` 함수 + `Flow` 만 사용할지, iOS용 Swift 친화 래퍼(SKIE 등)를 둘지
5. 배포 방식: Maven Central, SPM(XCFramework) 등 채널과 오픈소스 공개 여부. 공개한다면 이름과 `{{BASE_PACKAGE}}`
6. 샘플 앱 UI: Compose Multiplatform / Android는 Compose + iOS는 SwiftUI
7. 한 번에 연결할 차량 수: 1대 / 여러 대
8. v1 기능 범위: §5 전체 / 핵심 기능만 먼저(키 등록, 잠금, 공조, 충전, 상태 조회)
9. 세션 캐시 저장소와 암호화 여부
10. 에러 메시지·로그의 i18n (한국어/영어)

---

## 11. 산출물 템플릿

### 11.1 PRD (`{{PRD_FILE}}`)
1. 배경과 목표, 성공 지표
2. 대상 사용자: 앱 개발자(라이브러리 사용자), 최종 사용자(차주)
3. 범위 / 비범위 (§3 반영)
4. 사용자 시나리오: 최초 페어링, 근접 잠금 해제(수동), 상태 확인, 원격 공조, 충전 관리, 키 삭제
5. 기능 요구사항: FR-xxx 번호를 붙이고 §5 기능과 1:1로 추적. 각 항목에 우선순위(P0~P2)와 도메인 표기
6. 비기능 요구사항: §8을 기반으로 NFR-xxx 번호 부여
7. 제약과 의존성: 플랫폼, 라이선스, 차종
8. 리스크와 대응: 펌웨어 변경, BLE 불안정, 결과 불확실 명령, 슬롯 3개 제한
9. 릴리스 계획 (§12 연계)
10. 라이브러리 사용 문서 요구사항 (§13 연계, 위치는 `{{LIB_DOCS_DIR}}`)

### 11.2 SDD (`{{SDD_FILE}}`)
1. 아키텍처 개요와 모듈 다이어그램
2. 모듈별 책임, 공개 API, Go 원본 대응표
3. 시퀀스: 스캔 → 연결 → (VCSEC/Infotainment) 핸드셰이크 → 명령 → 응답 매칭 → 세션 복구
4. 키 등록 시퀀스 (add-key-request → WAIT → 키카드 태그 → 완료)
5. 동시성 모델: 코루틴 스코프, VCSEC 직렬화, 타임아웃, 취소
6. 에러 모델과 재시도 정책
7. 데이터: 세션 캐시 스키마, 키 메타데이터
8. 플랫폼 계층: 키스토어, 암호 원시연산, BLE 권한
9. 테스트 전략: 테스트 벡터, 골든 픽스처, FakeVehicle, 실차 체크리스트
10. 로깅과 보안
11. ADR 목록

---

## 12. 마일스톤 (계획 단계에서 세분화)
| M | 내용 | 완료 기준 |
|---|---|---|
| M0 | 프로젝트 골격, Wire 코드 생성, PoC 이관 | commonTest에서 테스트 벡터 전부 PASS (JVM, iOS 시뮬레이터) |
| M1 | protocol 전체 (Signer, 응답 복호화, 에러) | Go 클라이언트 측 테스트 포팅본 PASS |
| M2 | dispatcher + FakeVehicle | 핸드셰이크, 매칭, VCSEC 다중 응답, 재전송 방지, 세션 복구 테스트 PASS |
| M3 | transport (Kable) + keystore | 샘플 앱에서 스캔, 연결, `list-keys`(인증 없음) 성공 (실차는 사용자 확인) |
| M4 | 키 등록 + VCSEC 제어 + VehicleStatus | 페어링, 잠금, 해제, 트렁크 동작 (실차는 사용자 확인) |
| M5 | Infotainment 제어 + GetState | §5 P0/P1 항목 완료 |
| M6 | 문서화, 배포 준비 | 매뉴얼 완성, API 문서, NOTICE, 배포 설정 |

---

## 13. 문서 규칙 (개발 매뉴얼 / 라이브러리 사용 문서)

**개발 매뉴얼 `{{MANUAL_DIR}}` (입력, 읽기 전용)**
- 사용자가 실제 `vehicle-command` 를 기준으로 작성한 포팅 가이드입니다. 모든 Phase에서 1차 참고 자료로 사용합니다.
- 계획의 각 작업과 PR에 참고한 매뉴얼 문서를 표기합니다.
- 원본 코드와 다른 내용을 발견하면 원본을 따르고, 사용자에게 보고합니다. 수정은 사용자 승인 후에만 합니다.

**라이브러리 사용 문서 `{{LIB_DOCS_DIR}}` (산출물)**
- 위치는 **항상 `{{LIB_DOCS_DIR}}`** 입니다. 하드코딩을 금지하며, 위치가 바뀌면 `{{PATHS_FILE}}` 만 수정합니다.
- 권장 구성 (Phase 1에서 확정):
  - `{{LIB_DOCS_INDEX}}`: 목차
  - `getting-started.md`: 설치, 권한 설정, 첫 페어링
  - `pairing.md`: 키 등록, 역할, 키 삭제
  - `reading-state.md`: 조회 API와 차량 깨우기 정책
  - `commands.md`: 제어 API 전체 표
  - `errors.md`: 결과 불확실 처리, 재시도
  - `platform-notes.md`: iOS/Android 제약, 백그라운드
  - `troubleshooting.md`: 슬롯 초과, 세션 오류, 디버그 로그 수집
  - `architecture.md`: 기여자용, SDD 요약
- 사용 문서 안의 링크는 `{{LIB_DOCS_INDEX}}` 기준 **상대경로만** 사용합니다.
- 공개 API를 추가하거나 변경하는 PR에는 해당 사용 문서 페이지 갱신을 **반드시 포함**합니다. 코드 리뷰 체크 항목입니다.

---

## 14. 완료 정의 (DoD)
- [ ] PRD, SDD, ADR, 계획이 모두 승인됨
- [ ] commonTest(JVM + iOS 시뮬레이터)와 Android 단위 테스트 모두 PASS
- [ ] `protocol.md` 테스트 벡터와 골든 픽스처 100% 통과
- [ ] 샘플 앱 2종 빌드 성공
- [ ] §5 P0 기능 구현. 실차 체크리스트 전달 완료
- [ ] `{{LIB_DOCS_DIR}}` 라이브러리 사용 문서 완성, 링크 검증 통과
- [ ] 개발 매뉴얼과 원본의 불일치 목록을 사용자에게 전달
- [ ] NOTICE와 파일 헤더에 출처 표기. AGPL 코드 유입 없음

## 15. 금지 사항
- Fleet API나 외부 서버 호출 추가
- 자체 구현 암호 알고리즘 (원시 연산은 플랫폼 제공 기능만 사용)
- 개인키 내보내기, 로그에 키 출력
- AGPL 저장소 열람과 코드 복사
- 경로 하드코딩 (§0 키로만 참조)
- 사용자 승인 없이 `{{MANUAL_DIR}}` 개발 매뉴얼 수정
- 승인 게이트 건너뛰기
- `{{REF_REPO_DIR}}` 원본 코드 수정 또는 커밋

---

## 부록 A. 착수 시 생성할 파일

> Phase 0 1단계에서 아래 파일을 **표시된 경로에 내용 그대로** 생성합니다.
> 각 블록의 바깥 `~~~~` 펜스는 파일 내용이 아닙니다. 파일 안의 `{{키}}` 표기도 그대로 둡니다(치환하지 않음).
> 개발 매뉴얼 `documents/md/` 는 사용자가 이미 제공한 것이므로 생성 대상이 아닙니다.

| # | 경로 | 내용 |
|---|---|---|
| A-1 | `docs/PATHS.md` | 경로 정본 |
| A-2 | `AGENTS.md` | 모든 에이전트 공통 지침 (Codex, Copilot CLI, Claude Code) |
| A-3 | `CLAUDE.md` | Claude Code 전용 지침 |
| A-4 | `docs/workflow.md` | 개발 표준 및 워크플로 전문 |
| A-5 | `.gitignore` | 원본 저장소 등 제외 목록 |
| A-6 | `.github/pull_request_template.md` | PR 템플릿 |
| A-7 | `reference/poc/TeslaProtocolCore.kt` | 검증된 PoC: commonMain 출발점 |
| A-8 | `reference/poc/PocTest.kt` | 검증된 PoC: 테스트 벡터 17개 (JVM) |

### A-1. `docs/PATHS.md`

~~~~markdown
# PATHS — 경로 정본 (Single Source of Truth)

> 이 저장소의 모든 문서, 에이전트 지침, 코드 주석은 경로를 직접 쓰지 않고 **`{{키}}`** 로 참조합니다.
> 위치를 바꾸려면 **이 표의 값만** 고치세요. 다른 파일은 수정할 필요가 없습니다.
> 값이 `TBD` 인 키를 써야 하는 시점이 오면, 에이전트는 작업을 멈추고 사용자에게 확인합니다.

| 키 | 값 | 설명 |
|---|---|---|
| `PATHS_FILE` | `docs/PATHS.md` | 이 파일 |
| `AGENTS_FILE` | `AGENTS.md` | 모든 코딩 에이전트 공통 지침 (정본) |
| `CLAUDE_FILE` | `CLAUDE.md` | Claude Code 전용 추가 지침 |
| `HANDOFF_FILE` | `HANDOFF.md` | 프로젝트 착수 메타 프롬프트 |
| `WORKFLOW_FILE` | `docs/workflow.md` | 개발 표준·워크플로 전문 |
| `MANUAL_DIR` | `documents/md/` | **개발 매뉴얼 (사용자 작성, 참조용).** 실제 `vehicle-command` 저장소를 기준으로 정리한 문서. 포팅 작업의 1차 가이드. 사용자 승인 없이 수정 금지 |
| `MANUAL_INDEX` | `{{MANUAL_DIR}}` 의 목차 파일 (Phase 0에서 확인해 이 칸에 기록) | 개발 매뉴얼 목차 |
| `LIB_DOCS_DIR` | `docs/manual/` | **이 라이브러리의 사용 문서 (에이전트 작성).** 공개 API, 페어링, 에러 처리 등 |
| `LIB_DOCS_INDEX` | `{{LIB_DOCS_DIR}}README.md` | 라이브러리 사용 문서 목차 |
| `PRD_FILE` | `docs/prd/PRD.md` | 제품 요구사항 문서 (FR-xxx / NFR-xxx) |
| `SDD_FILE` | `docs/sdd/SDD.md` | 소프트웨어 설계 문서 |
| `SPECS_DIR` | `docs/superpowers/specs/` | 설계 브레인스토밍 산출물 |
| `PLANS_DIR` | `docs/superpowers/plans/` | 구현 계획 |
| `ADR_DIR` | `docs/adr/` | 아키텍처 결정 기록 (`NNNN-<slug>.md`) |
| `HANDOFF_DIR` | `docs/handoff/` | 세션 인계 노트 (`YYYY-MM-DD-<slug>.md`) |
| `FIXTURES_DIR` | `testing/src/commonTest/resources/fixtures/` ← SDD에서 확정 | 테스트 벡터, 골든 TX/RX (VIN 마스킹본만) |
| `REF_REPO_DIR` | `vehicle-command/` | 공식 Go 저장소 로컬 클론. **git-ignored, 읽기 전용** |
| `REF_REPO_URL` | `https://github.com/teslamotors/vehicle-command` | 원본 저장소 |
| `REF_REPO_COMMIT` | `a4b43c1eff0e09d77deb9f2dce97031141fe8c8a` | 포팅 기준 커밋 (2026-09-25) |
| `POC_DIR` | `reference/poc/` | 검증된 PoC 코드 |
| `LIB_NAME` | `tesla-ble-kmp` (가칭) | 라이브러리 / Gradle 루트 이름 |
| `BASE_PACKAGE` | `TBD` (예: `io.github.<owner>.teslable`) | Kotlin 패키지 루트 |

## 규칙
1. 문서와 지침에서는 `{{MANUAL_DIR}}` 처럼 **키로 참조**합니다. 링크가 꼭 필요하면 이 파일로 링크합니다.
2. 라이브러리 사용 문서의 내부 링크는 `{{LIB_DOCS_INDEX}}` 기준 **상대경로만** 사용합니다. 폴더를 통째로 옮겨도 링크가 유지됩니다.
3. `{{MANUAL_DIR}}`(개발 매뉴얼)과 `{{LIB_DOCS_DIR}}`(라이브러리 사용 문서)은 **다른 문서**입니다. 앞의 것은 읽기 전용 참조, 뒤의 것은 산출물입니다.
4. 키를 추가하거나 바꿀 때는 `docs:` 커밋으로 이 파일만 수정합니다.
~~~~

### A-2. `AGENTS.md`

~~~~markdown
# AGENTS.md — tesla-ble-kmp

> **모든 코딩 에이전트(Claude Code, Codex CLI, GitHub Copilot CLI) 공통 지침의 정본**입니다.
> 도구별 추가 지침은 각 도구 파일에만 둡니다. Claude Code 전용 지침은 `CLAUDE.md` 에 있습니다.
> 이 파일과 도구별 파일이 충돌하면 **이 파일이 우선**합니다.
> 경로는 모두 [`docs/PATHS.md`](docs/PATHS.md) 의 `{{키}}` 로 참조합니다.

## 1. 프로젝트 한 줄 요약
Tesla 공식 Go SDK `vehicle-command` 의 **BLE 경로**를 **Kotlin Multiplatform(Android + iOS)** 으로 직접 포팅해,
**서버 없이** 스마트폰에서 테슬라 차량과 통신(키 등록, 조회, 제어)하는 라이브러리를 만듭니다.

## 2. 먼저 읽을 문서 (순서대로)
| 순서 | 키 | 내용 | 언제 |
|---|---|---|---|
| 1 | `{{PATHS_FILE}}` | 모든 경로의 정본 | 항상 |
| 2 | `{{MANUAL_DIR}}` | **개발 매뉴얼.** 실제 `vehicle-command` 기준으로 정리된 포팅 가이드 (사용자 작성). **포팅 작업 시 반드시 먼저 참고** | 포팅·설계·구현 전 항상 |
| 3 | `{{HANDOFF_FILE}}` | 착수 메타 프롬프트: 결정 사항, 범위, 프로토콜 사실, 마일스톤 | 착수할 때, 방향이 헷갈릴 때 |
| 4 | `{{WORKFLOW_FILE}}` | 개발 표준 전문: TDD, Tidy First, 아키텍처, 브랜치, CI, DoD | 코드를 쓰기 전 |
| 5 | `{{PRD_FILE}}` | 요구사항 (FR-xxx / NFR-xxx) | 기능 작업 전 |
| 6 | `{{SDD_FILE}}` + `{{ADR_DIR}}` | 설계와 결정 기록 | 구조를 바꾸기 전 |
| 7 | `{{PLANS_DIR}}` | 현재 구현 계획 | 작업을 고를 때 |
| 8 | `{{HANDOFF_DIR}}` 최신 파일 | 직전 세션 인계 노트 | 세션을 시작할 때 |
| 9 | `{{LIB_DOCS_INDEX}}` | 라이브러리 사용 문서 (산출물) | 공개 API를 바꿀 때 |

PRD나 SDD가 아직 없으면 `{{HANDOFF_FILE}}` 의 워크플로를 따릅니다(Phase 0부터).

## 3. 절대 규칙 (예외 없음)
1. **TDD**: 실패하는 테스트를 먼저 작성합니다. 테스트 없이 동작을 바꾸지 않습니다.
2. **Tidy First**: 구조 변경과 동작 변경을 **한 커밋에 섞지 않습니다.** 구조 변경을 먼저 커밋합니다.
3. **main은 항상 Green**: 로컬에서 `./gradlew check` 가 통과한 것만 푸시합니다.
4. **아키텍처 경계는 Gradle이 강제**합니다. 금지된 모듈 의존성을 추가해 우회하지 않습니다. 의존성 변경은 ADR 대상입니다.
5. **원본 존중**: `{{REF_REPO_DIR}}` 는 읽기 전용입니다. 수정하거나 커밋하지 않습니다. 동작이 애매하면 **원본 Go 코드가 정답 기준**입니다.
5-1. **개발 매뉴얼 사용**: 포팅, 설계, 구현은 `{{MANUAL_DIR}}` 의 개발 매뉴얼을 **1차 가이드로 사용**합니다. 작업 계획과 커밋에 참고한 매뉴얼 문서를 표기합니다.
   - 매뉴얼과 원본 Go 코드가 다르면 **원본 코드 기준으로 구현**하고, 불일치 내용(매뉴얼 위치, 원본 파일과 줄)을 사용자에게 보고합니다.
   - 매뉴얼은 **사용자 승인 없이 수정하지 않습니다.**
6. **서버 없음**: Fleet API, 외부 네트워크 호출, 텔레메트리를 추가하지 않습니다.
7. **암호**: 알고리즘을 직접 구현하지 않습니다. 원시 연산은 플랫폼이 제공하는 것만 쓰고, HMAC은 상수 시간으로 비교합니다.
8. **비밀**: 개인키, 세션키, 실제 VIN을 로그, 테스트 픽스처, 커밋에 남기지 않습니다. 픽스처는 VIN을 마스킹한 것만 씁니다.
9. **라이선스**: AGPL 저장소(예: `yoziru/tesla-ble`)는 열람하지 않습니다. 포팅한 파일 헤더에 원본 경로와 커밋을 표기합니다.
10. **경로 하드코딩 금지**: `{{PATHS_FILE}}` 의 키로만 참조합니다.
11. **문서 동기화**: 요구사항, 설계, 공개 API가 바뀌면 PRD, SDD, 매뉴얼을 **같은 PR에서** 갱신합니다.

## 4. 작업 흐름 (도구 공통)
```
PRD ─🛑승인→ SDD ─🛑승인→ 계획 ─🛑승인→ [작업 단위 반복: 브랜치 → Red → Green → Refactor → 커밋 → AI 리뷰 → PR(CI Green) → main]
                                                                                        └→ 마일스톤 종료: 매뉴얼 갱신 + 인계 노트
```
- 🛑는 **사용자 승인 게이트**입니다. 승인 없이 다음 단계로 넘어가지 않습니다.
- 한 번에 **계획의 작업 하나만** 진행합니다. 계획에 없는 일은 먼저 계획을 고친 뒤에 합니다.
- 세부 절차는 `{{WORKFLOW_FILE}}` 에 있습니다.

## 5. 모듈과 의존 방향 (요약, 확정은 SDD)
```
:sdk (조립 / 공개 파사드)
 ├─ :application  ──▶ :domain
 ├─ :adapter-ble  ──▶ :domain      (Kable)
 ├─ :adapter-crypto ─▶ :domain     (Android Keystore / iOS Security.framework)
 └─ :adapter-storage ▶ :domain     (세션 캐시)
:domain = 순수 Kotlin (프로토콜 모델, Session, Metadata, SlidingWindow, 포트 인터페이스). 외부 의존은 Wire 런타임만.
samples/* ──▶ :sdk 만
```

## 6. 명령어 (M0 이후 유효, SDD에서 확정)
| 목적 | 명령 |
|---|---|
| 전체 검사 (푸시 전 필수) | `./gradlew check` |
| 공통 테스트 (JVM) | `./gradlew jvmTest` |
| iOS 시뮬레이터 테스트 (macOS) | `./gradlew iosSimulatorArm64Test` |
| 정적 분석 | `./gradlew detekt ktlintCheck` |
| 공개 API 호환성 | `./gradlew apiCheck` (변경을 의도했다면 `apiDump` 후 커밋) |
| protobuf 생성 | `./gradlew generateCommonMainProtos` |

## 7. 커밋과 PR
- 접두어: `struct:` (구조) · `feat:` (기능) · `fix:` (결함) · `test:` (테스트만) · `docs:` · `chore:` (의존성·빌드)
- 본문에 추적 ID를 적습니다: `Refs: FR-012` 또는 `Refs: NFR-003`, `ADR-0004`
- PR 머지 조건: **CI Green + AI 코드 리뷰 통과**(리뷰 요약을 PR 본문에 첨부) + PR 템플릿 체크리스트 완료
- 머지 방식: **Rebase merge.** Squash는 금지합니다. `struct` 커밋과 `feat` 커밋이 따로 보존되어야 하기 때문입니다.

## 8. 막혔을 때
1. `{{MANUAL_DIR}}` 개발 매뉴얼 → 원본 Go 코드 → `vehicle-command/pkg/protocol/protocol.md` 순으로 해당 동작을 찾습니다.
2. 그래도 모호하면 추측으로 구현하지 않습니다. 질문 목록을 만들어 사용자에게 묻습니다.
3. 컨텍스트가 길어졌거나 주제가 바뀌면 `{{HANDOFF_DIR}}` 에 인계 노트를 쓰고 새 세션을 제안합니다(`{{WORKFLOW_FILE}}` §10).
~~~~

### A-3. `CLAUDE.md`

~~~~markdown
@AGENTS.md

# CLAUDE.md — Claude Code 전용 추가 지침

> 공통 규칙은 위에서 불러온 `AGENTS.md` 에 있습니다. 이 파일에는 **Claude Code + Superpowers 전용 내용만** 둡니다.
> (GitHub Copilot CLI도 이 파일을 읽습니다. Copilot과 다른 도구는 이 파일의 Superpowers 절을 무시하고 `AGENTS.md` 를 따릅니다.)

## 1. Superpowers 스킬 매핑
| 공통 흐름 단계 (AGENTS.md §4) | 사용할 스킬 | 산출 위치 |
|---|---|---|
| 세션 시작 | `using-superpowers` → `{{HANDOFF_DIR}}` 최신 노트 읽기 | – |
| PRD | `brainstorming`: 질문은 **한 번에 하나씩** | `{{PRD_FILE}}` |
| SDD | `brainstorming` (설계 단계) | `{{SDD_FILE}}`, 원본은 `{{SPECS_DIR}}`, 결정은 `{{ADR_DIR}}` |
| 계획 | `writing-plans` | `{{PLANS_DIR}}` |
| 작업 공간 | `using-git-worktrees`: 작업 단위마다 짧게 쓰고 바로 병합 | – |
| 구현 | `subagent-driven-development` + `test-driven-development` | 코드 |
| 디버깅 | `systematic-debugging`: 가설을 세우기 전에 원본 Go 코드와 대조 | – |
| 리뷰 (머지 조건) | `requesting-code-review` → 지적 사항은 `receiving-code-review` 로 처리 | PR 본문에 요약 첨부 |
| 마무리 | `verification-before-completion`(있다면) → `finishing-a-development-branch` | – |

- **스킬의 기본 저장 경로 대신 `{{PATHS_FILE}}` 의 키를 사용합니다.**
- 스킬 지시와 `AGENTS.md` 가 충돌하면 `AGENTS.md` 를 따르고, 충돌이 있었다는 사실을 사용자에게 알립니다.

## 2. 승인 게이트 운영
- PRD, SDD, 계획을 마치면 **요약 + 결정이 필요한 항목**만 보여주고 멈춥니다. 전문을 다시 붙여넣지 않습니다.
- 사용자가 "진행"이라고 명시하기 전에는 다음 Phase로 넘어가지 않습니다.

## 3. 서브에이전트 사용 원칙
- 구현 서브에이전트에는 **작업 1개 + 관련 FR ID + 참고할 `{{MANUAL_DIR}}` 매뉴얼 문서 + 대응하는 Go 원본 경로 + 먼저 작성할 실패 테스트**만 전달합니다.
- 리뷰 서브에이전트는 구현을 보지 않은 새 컨텍스트로 띄우고, `{{WORKFLOW_FILE}}` §9 DoD를 체크리스트로 줍니다.
- 원본 저장소 탐색(파일이 많을 때)은 읽기 전용 탐색 에이전트에 맡기고, 결론만 받습니다.

## 4. 컨텍스트 관리
- 마일스톤이 끝났거나, 주제가 바뀌었거나, 컨텍스트가 길어졌으면 `{{WORKFLOW_FILE}}` §10 형식으로 `{{HANDOFF_DIR}}` 에 인계 노트를 쓰고 `/clear` 를 제안합니다.
~~~~

### A-4. `docs/workflow.md`

~~~~markdown
# 개발 표준 및 워크플로

| 항목 | 내용 |
|---|---|
| 대상 | `{{LIB_NAME}}`: Tesla BLE 프로토콜 Kotlin Multiplatform 라이브러리 (Android + iOS) |
| 기반 | 켄트 벡 TDD, Tidy First, SOLID, DDD, Clean Code, 헥사고날 아키텍처, Trunk-based Development |
| 사용 도구 | Claude Code(+Superpowers), Codex CLI, GitHub Copilot CLI |
| 연계 | 개발 매뉴얼 `{{MANUAL_DIR}}`, 에이전트 공통 지침 `{{AGENTS_FILE}}`, Claude 전용 `{{CLAUDE_FILE}}`, 설계 `{{SDD_FILE}}`, 경로 `{{PATHS_FILE}}` |
| 버전 | v1.0 (2026-09-26) |

---

## 1. 개발 철학

네 축이 서로 맞물려 돌아갑니다.

1. **TDD (Red → Green → Refactor)**: 테스트가 설계를 이끕니다.
2. **헥사고날 + DDD**: 프로토콜 도메인을 BLE, 키스토어, OS로부터 격리합니다.
3. **Trunk-based**: 작게 나눠 자주 통합합니다.
4. **매뉴얼로 안내받고, 원본으로 검증**: 포팅 작업은 `{{MANUAL_DIR}}` 의 개발 매뉴얼(실제 `vehicle-command` 기준으로 정리됨)을 1차 가이드로 삼습니다. 프로토콜 동작의 최종 기준은 `{{REF_REPO_DIR}}` 의 Go 코드(`{{REF_REPO_COMMIT}}`)와 `protocol.md` 입니다. 우리 코드가 원본과 다르게 동작하면 버그입니다. 매뉴얼과 원본이 다르면 원본을 따르고 사용자에게 보고합니다(매뉴얼은 승인 없이 수정 금지).

헥사고날 경계 덕분에 도메인은 차량이나 BLE 없이도 테스트할 수 있습니다. 그래서 TDD가 가능하고, TDD의 작은 스텝이 Trunk-based의 잦은 통합을 떠받칩니다.

---

## 2. TDD 표준

### 2.1 사이클
| 단계 | 행동 | 규칙 |
|---|---|---|
| **Red** | 실패하는 테스트를 먼저 작성 | 구현 코드보다 테스트가 먼저 있어야 함 |
| **Green** | 통과시킬 최소 코드만 작성 | 필요 이상으로 짜지 않음 |
| **Refactor** | 통과 상태에서 구조 개선 | 테스트가 Green일 때만 |

### 2.2 규칙
- 한 번에 **테스트 하나**씩 진행합니다.
- 테스트 이름은 동작을 서술합니다. 예: `derivesSessionKeyFromEcdhSharedX`, `dropsResponseWithReplayedCounter`, `reassemblesFrameSplitAcrossThreeChunks`
- **결함 수정은 재현 테스트부터** 시작합니다. 원본 Go 코드가 같은 입력에 무엇을 하는지 먼저 확인합니다.
- 커버리지 수치는 목표가 아닙니다. 구현에 맞춰 사후에 쓴 테스트는 금지합니다.

### 2.3 테스트 계층
| 계층 | 대상 | 방식 | 실행 |
|---|---|---|---|
| **벡터 테스트** | Metadata TLV, 키 유도, 세션정보 HMAC, GCM, 광고 이름 | `protocol.md` 테스트 벡터와 바이트 단위 비교 | 매 푸시 |
| **골든 테스트** | 명령별 protobuf 인코딩, 응답 디코딩 | `tesla-control -ble -debug` 에서 수집한 TX/RX 헥사 (VIN 마스킹) | 매 푸시 |
| **도메인 단위** | Session, SlidingWindow, 프레이밍, 에러 분류 | 순수 Kotlin | 매 푸시 |
| **유스케이스** | Dispatcher, Vehicle API | 포트를 **FakeTransport, FakeVehicle** 로 대체 | 매 푸시 |
| **어댑터 통합** | Keystore(ECDH), Kable | 실기기 또는 시뮬레이터 | 수동, 야간 |
| **실차 체크리스트** | 페어링, 잠금, 공조, 충전, 조회 | 사용자가 수행 (§8.4) | 마일스톤마다 |

### 2.4 FakeVehicle 원칙
- 원본의 `internal/authentication/verifier.go` 와 `dispatcher_test.go` 를 참고해 **차량 측 핸드셰이크와 응답을 흉내냅니다.**
- 다음 시나리오를 결정적(deterministic)으로 재현할 수 있어야 합니다.
  - VCSEC 응답 여러 개 (`WAIT` → 최종)
  - 응답 유실 (결과 불확실)
  - epoch 변경 (차량 재부팅)
  - clock 역행
  - 잘못된 HMAC
  - 재전송된 응답
- 시간은 `Clock` 포트로 주입합니다. 테스트에서 실제로 sleep하지 않습니다.

---

## 3. Tidy First: 구조 변경과 동작 변경 분리

| 종류 | 정의 | 예 |
|---|---|---|
| 구조 변경 (STRUCTURAL) | 동작은 그대로, 코드만 재배열 | 이름 변경, 함수 추출, 파일 이동, 모듈 분리 |
| 동작 변경 (BEHAVIORAL) | 기능 추가나 수정 | 새 명령, 응답 처리 로직 변경 |

- 한 커밋에 두 종류를 **섞지 않습니다.** 둘 다 필요하면 **구조 변경을 먼저** 별도 커밋으로 합니다.
- 구조 변경 전후에 테스트를 돌려 동작이 바뀌지 않았음을 확인합니다.
- 커밋 접두어로 종류를 표시합니다(§5.2).

---

## 4. 아키텍처 표준 (헥사고날 + DDD)

### 4.1 의존 방향
```
   인바운드 (앱 코드 → 공개 파사드 :sdk)
                 │
                 ▼
 ┌──────── :application (Dispatcher, Vehicle 유스케이스) ────────┐
 │                          │                                   │
 │                          ▼                                   │
 │   ┌──────────── :domain (모델 + 포트) ────────────┐           │
 │   │ RoutableMessage 모델(Wire), Session, Metadata  │           │
 │   │ SlidingWindow, 에러 타입                        │           │
 │   │ 포트: Transport, KeyStore, CryptoPrimitives,   │◀── 어댑터가 구현
 │   │       SessionCache, Clock                      │           │
 │   └───────────────────────────────────────────────┘           │
 └───────────────────────────────────────────────────────────────┘
   아웃바운드 어댑터: :adapter-ble(Kable), :adapter-crypto(Keystore/SecKey), :adapter-storage
```
의존은 항상 안쪽(`:domain`)을 향합니다. `:domain` 은 바깥을 모릅니다.

### 4.2 경계 강제 (빌드가 잡는다)
| 모듈 | 허용 의존 | 금지 예 (컴파일 에러가 나야 함) |
|---|---|---|
| `:domain` | Wire 런타임, kotlinx-coroutines-core | Kable, Android SDK, platform.Security |
| `:application` | `:domain` | 어댑터 타입 직접 참조 |
| `:adapter-*` | `:domain` + 외부 SDK | 유스케이스(`:application`) 참조 |
| `:sdk` | 전부 | (조립만 담당) |
| `samples/*` | `:sdk` | 내부 모듈 직접 참조 |

- 위반은 리뷰가 아니라 **빌드가 잡아야 합니다.** 리뷰어의 주의력에 기대지 않습니다.
- `build.gradle.kts` 에 의존성을 추가하는 PR은 **아키텍처 변경**으로 표시하고 ADR을 작성합니다.

### 4.3 SOLID 적용 예
| 원칙 | 이 프로젝트에서 |
|---|---|
| SRP | `Reassembler` 는 청크 재조립만 합니다. 메시지 해석은 Dispatcher가 담당합니다 |
| OCP | 새 명령을 추가해도 Dispatcher는 바뀌지 않습니다. 명령 빌더만 늘어납니다 |
| LSP | 모든 `Transport` 구현(Kable, Fake)은 같은 계약(프레임 단위 송수신, 순서 보장)을 지킵니다 |
| ISP | `KeyStore`(키 생성, ECDH)와 `CryptoPrimitives`(해시, GCM)를 분리합니다. iOS에서 GCM만 Swift로 주입하는 선택지가 열립니다 |
| DIP | Dispatcher는 `Transport` 포트에 의존하고 Kable을 모릅니다 |

### 4.4 Kotlin 코딩 규약
- 라이브러리는 **`explicitApi()` 모드**를 사용합니다. 공개 API에는 KDoc을 필수로 답니다.
- 공개 API 변경은 **binary-compatibility-validator**(`apiCheck`)로 감지합니다.
- `!!`, `lateinit`(테스트 제외), `GlobalScope`, `runBlocking`(테스트 제외), `println` 은 금지합니다.
- 값 객체는 `data class` / `value class` 로 두고 불변으로 만듭니다. `ByteArray` 를 그대로 노출하지 않고 방어적으로 복사합니다.
- 에러는 도메인 `sealed` 타입으로 정의합니다. 외부 예외(Kable, 플랫폼)는 어댑터 안에서 도메인 에러로 바꿉니다.
- **구조화된 동시성**을 지킵니다. 모든 코루틴은 호출자 스코프나 명시적 스코프에 속하고, 취소를 항상 처리합니다.
- `expect/actual` 은 최소한으로 씁니다. 포트 인터페이스 + 주입을 우선합니다.
- 포팅한 파일 헤더에 다음을 적습니다: `// Ported from vehicle-command@<짧은커밋> <원본경로> (Apache-2.0)`

---

## 5. 브랜치와 커밋 (Trunk-based)

### 5.1 브랜치 모델
```
main (트렁크: 항상 Green, 항상 릴리스 가능)
 ├─● feat/fr-012-lock-unlock   (수명 1~2일)  ─── PR → rebase merge
 └─● fix/replayed-vcsec-resp   ─────────────────── PR → rebase merge
```
- 트렁크는 `main` 하나입니다. **브랜치 수명은 1~2일**이고, 길어지면 쪼갭니다.
- 미완성 기능은 **피처 플래그**(내부 `ExperimentalTeslaApi` 옵트인 어노테이션 등) 뒤에 감춘 채 병합합니다.
- 릴리스는 `main` 에서 **태그**(`vX.Y.Z`)로 자릅니다.
- Superpowers의 worktree도 같은 규칙을 따릅니다. 작업 단위 하나에 worktree 하나를 쓰고, 끝나면 바로 병합하고 정리합니다.

### 5.2 커밋 규율
다음을 모두 충족할 때만 커밋합니다.
1. 빠른 테스트가 전부 통과
2. 컴파일 경고 0, detekt 경고 0, ktlint 위반 0
3. 하나의 논리적 작업 단위
4. 접두어로 구조/동작을 명시

```
struct: extract Reassembler from BleTransport          # 구조 변경
feat: verify session info HMAC before accepting epoch  # 동작 변경
fix: drop VCSEC response with stale routing address    # 결함 수정
test: add protocol.md GCM vector                       # 테스트만
docs: update pairing manual for WAIT status            # 문서
chore: bump Kable to x.y.z                             # 의존성·빌드

Refs: FR-012
```

### 5.3 PR
- 작게 유지합니다. 구조와 동작을 분리해 두면 리뷰가 쉬워집니다.
- **머지 조건**
  1. CI Green (§6)
  2. **AI 코드 리뷰 1회 통과**
     - Claude Code: `requesting-code-review`
     - Codex / Copilot CLI: 각 도구의 리뷰 기능
     - 리뷰 요약과 처리 결과를 PR 본문에 첨부합니다.
  3. PR 템플릿 체크리스트 완료
- 머지 방식은 **Rebase merge**입니다. Squash는 금지합니다(Tidy First 커밋 보존).
- 다음 변경이 포함된 PR에는 라벨을 붙이고 ADR을 링크합니다.
  - 의존성 추가나 변경: `architecture`
  - 암호, 키, 로그 관련 코드: `security`

### 5.4 저장소에 넣지 않는 것
- `{{REF_REPO_DIR}}` (원본 클론)
- 실차용 개인키, 실제 VIN, 마스킹하지 않은 BLE 캡처
- 서명 인증서, 프로비저닝 프로파일, `local.properties`

---

## 6. CI 게이트 (GitHub Actions)

모든 푸시와 PR에서 실행합니다. **하나라도 실패하면 병합을 막습니다.**

| 게이트 | 내용 | 러너 | 시점 |
|---|---|---|---|
| 포맷 | `ktlintCheck` | ubuntu | 매 푸시 |
| 정적 분석 | `detekt` (경고 0) | ubuntu | 매 푸시 |
| 빌드 | 경고를 에러로 처리 (`allWarningsAsErrors`) | ubuntu | 매 푸시 |
| 아키텍처 | 모듈 의존 방향 (빌드에 내포) | – | 빌드 |
| 공개 API | `apiCheck` | ubuntu | 매 푸시 |
| 공통 테스트 | `jvmTest` (벡터, 골든, 도메인, 유스케이스) | ubuntu | 매 푸시 |
| Android 단위 | `testDebugUnitTest` | ubuntu | 매 푸시 |
| iOS 테스트 | `iosSimulatorArm64Test` | **macos** | 매 PR |
| 샘플 빌드 | Android assemble, iOS xcodebuild | ubuntu / macos | 매 PR |
| 라이선스 | NOTICE 존재 확인, 포팅 파일 헤더 확인 | ubuntu | 매 PR |
| 비밀 스캔 | VIN 패턴(`[A-HJ-NPR-Z0-9]{17}`)과 PEM 개인키 탐지 (테스트 벡터 허용 목록 제외) | ubuntu | 매 푸시 |

> **"Red를 트렁크에 올리지 않는다"** 가 Trunk-based의 생명줄입니다. 푸시 전에 로컬에서 `./gradlew check` 를 돌립니다.
> 워크플로 파일(`.github/workflows/ci.yml`)은 M0에서 SDD에 맞춰 작성합니다.

---

## 7. Clean Code
- 중복 제거를 최우선으로 합니다.
- 이름은 **프로토콜 용어를 그대로** 씁니다(유비쿼터스 언어): RoutableMessage, Domain, epoch, counter, session info, whitelist, RKE, closure.
- 싱글턴과 전역 상태를 만들지 않습니다. 의존성은 포트로 명시합니다.
- 함수는 작게 나누고 책임은 하나만 둡니다.
- 순수 로직(프레이밍, TLV, 매칭)과 I/O(BLE, 키스토어)를 분리합니다.
- 주석은 **"왜"** 만 씁니다. 원본과 일부러 다르게 구현한 곳에는 반드시 이유를 남깁니다.

---

## 8. 프로젝트 고유 지침

### 8.1 프로토콜 정확성
- 작업을 시작하기 전에 해당 주제의 `{{MANUAL_DIR}}` 매뉴얼 문서를 먼저 읽습니다. 계획과 PR에 참고한 매뉴얼 문서를 표기합니다.
- 바이트 수준 동작(TLV 순서, 플래그 포함 규칙, 요청 해시 절단, counter 규칙)은 **원본 코드 줄을 인용**해 테스트로 고정합니다.
- VCSEC는 **요청을 직렬화**합니다(동시 요청 금지). Infotainment는 uuid로 매칭합니다.
- 응답이 유실되면 결과를 **불확실(PossibleSuccess)** 로 반환합니다. 실패로 단정하지 않습니다.

### 8.2 보안과 개인정보
- 로그는 디버그 빌드에서만 TX/RX 헥사를 출력하고, VIN은 항상 마스킹합니다.
- 개인키와 세션 키는 **어떤 로그 레벨에서도** 출력하지 않습니다.
- 개인키는 가능한 한 하드웨어 보안 영역(Secure Enclave, Android Keystore)에서 생성하고 밖으로 내보내지 않습니다.
- 새 네트워크 호출을 추가하는 PR은 **서버 없음 원칙 위반**으로 보고 반려합니다.

### 8.3 플랫폼
- iOS: Kotlin/Native에서 CryptoKit(Swift 전용)은 쓸 수 없습니다. GCM 구현 방식은 ADR을 따릅니다.
- Android: Keystore 안에서 ECDH는 API 31 이상에서만 가능합니다. 대체 방식은 PRD와 ADR을 따릅니다.
- BLE 권한, 백그라운드 제약은 샘플 앱과 `{{LIB_DOCS_DIR}}` 의 platform-notes에 반영합니다.

### 8.4 실차 검증 체크리스트 (사용자 수행)
| 시나리오 | 확인 |
|---|---|
| 스캔: 광고 이름 매칭, RSSI | 내 차만 잡히는지 |
| 키 등록 → 키카드 태그 → 승인 | WAIT 이후 완료, 키 목록에 표시 |
| 차량 수면 중 잠금·해제 | VCSEC만으로 1초 안팎 |
| 수면 → 깨우기 → 공조 켜기 | Infotainment 세션 수립 |
| GetState 12개 카테고리 | 필드 파싱 |
| 앱 재실행 후 첫 명령 | 세션 캐시로 핸드셰이크 생략 |
| 차량 재부팅 후 명령 | epoch 변경을 복구 |
| 슬롯 3개 초과 | `MaxConnectionsExceeded` 안내 |
| BLE 거리 밖으로 이탈 중 명령 | 결과 불확실 처리 |

---

## 9. 완료 정의 (DoD)

**코드**
- [ ] 실패 테스트로 시작해서 통과시켰다 (Red → Green)
- [ ] 구조 변경을 별도 커밋으로 분리했다
- [ ] `./gradlew check` Green, 경고 0
- [ ] 모듈 경계 위반 없음 (빌드가 보장)
- [ ] 공개 API 변경을 `apiDump` 로 반영하고 KDoc을 작성했다

**프로토콜**
- [ ] 참고한 `{{MANUAL_DIR}}` 매뉴얼 문서를 PR에 표기했다
- [ ] 원본 Go 코드와 동작이 같다 (다르면 이유를 주석과 ADR에)
- [ ] 매뉴얼과 원본의 불일치를 발견했다면 사용자에게 보고했다
- [ ] 관련 벡터·골든 테스트를 추가했다

**보안**
- [ ] 로그와 픽스처에 키, VIN 원문이 없다

**문서**
- [ ] 요구사항이 바뀌었다면 `{{PRD_FILE}}`, 설계가 바뀌었다면 `{{SDD_FILE}}`/ADR을 같은 PR에서 갱신했다
- [ ] 공개 API가 바뀌었다면 `{{LIB_DOCS_DIR}}` 해당 페이지를 갱신했다

**통합**
- [ ] AI 리뷰 통과, 요약을 PR에 첨부했다
- [ ] rebase merge로 `main` 에 병합했다

---

## 10. 세션 인계 (Handoff)

다음 경우 `{{HANDOFF_DIR}}YYYY-MM-DD-<slug>.md` 를 작성하고 새 세션을 시작합니다.
- 마일스톤이 끝났을 때
- 주제가 바뀌었을 때
- 컨텍스트가 길어졌을 때

```markdown
# Handoff — <주제> (YYYY-MM-DD)
## 목표          : 이 세션이 달성하려던 것 (FR ID)
## 완료          : 병합된 PR과 커밋, 통과한 테스트
## 진행 중       : 브랜치/worktree, 마지막 Red 테스트 이름
## 결정          : 새 ADR, 사용자 승인 사항
## 막힌 점       : 원본 코드 위치 + 질문
## 다음 한 걸음  : 바로 실행할 작업 1개와 검증 명령
## 읽을 파일     : 새 세션이 먼저 읽어야 할 문서 (키로 표기, 관련 `{{MANUAL_DIR}}` 매뉴얼 문서 포함)
```

---

## 11. 문서와 코드의 관계
- **문서가 코드보다 먼저 틀립니다.** 요구사항이 바뀌면 코드보다 문서를 먼저 고칩니다.
- 코드와 문서가 어긋난 것을 발견하면 어느 쪽이 옳은지 판단해서 **둘 중 하나를 반드시 고칩니다.**

다음 세 쌍은 항상 함께 움직입니다.

| 코드 | 문서 |
|---|---|
| 공개 API | `{{LIB_DOCS_DIR}}` + KDoc + `apiDump` |
| 프로토콜 동작, 원본과의 차이 | `{{SDD_FILE}}` + ADR |
| 요구사항 충족 여부 | `{{PRD_FILE}}` FR 상태 |
~~~~

### A-5. `.gitignore`

~~~~gitignore
# Reference: official Tesla Go SDK (https://github.com/teslamotors/vehicle-command)
# Read-only local clone used as porting source. Never commit.
/vehicle-command/

# Gradle / Kotlin
.gradle/
build/
.kotlin/
local.properties
*.iml
.idea/

# Xcode / iOS
xcuserdata/
DerivedData/
*.xcworkspace/xcuserdata/
Pods/

# macOS
.DS_Store

# Secrets / local keys used in real-vehicle testing
*.pem
!**/src/*Test/resources/**/*.pem
*.key
.env
~~~~

### A-6. `.github/pull_request_template.md`

~~~~markdown
## 무엇을 / 왜
<!-- 한두 줄. 추적 ID 필수 -->
Refs: FR-___ / NFR-___ / ADR-____

## 변경 종류
- [ ] struct (구조만, 동작 불변)
- [ ] feat / fix (동작 변경)
- [ ] docs / test / chore
> 구조 변경과 동작 변경이 한 커밋에 섞이지 않았는지 확인하세요 (Tidy First).

## 원본 대응
<!-- 참고한 개발 매뉴얼 문서 (documents/md/...) -->
<!-- 포팅/수정한 동작의 Go 원본 경로 (vehicle-command@<커밋> ...) 또는 "해당 없음" -->
<!-- 매뉴얼과 원본의 불일치가 있었다면 내용 -->

## 체크리스트 (docs/workflow.md §9 DoD)
- [ ] 실패 테스트로 시작 → 통과 (Red → Green)
- [ ] `./gradlew check` 로컬 Green, 경고 0
- [ ] 원본 Go 동작과 같음 (다르면 주석과 ADR에 이유 기록)
- [ ] 로그와 픽스처에 키, VIN 원문 없음
- [ ] 공개 API 변경 시 `apiDump`, KDoc, 라이브러리 사용 문서(`{{LIB_DOCS_DIR}}`) 갱신
- [ ] 요구사항·설계 변경 시 PRD, SDD, ADR 갱신
- [ ] 의존성 변경 시 `architecture` 라벨, 암호·키·로그 변경 시 `security` 라벨

## AI 코드 리뷰
<!-- 사용한 도구, 지적 사항 요약, 처리 결과 (반영 / 반박 사유) -->
~~~~

### A-7. `reference/poc/TeslaProtocolCore.kt`

~~~~kotlin
// commonMain 에 그대로 들어갈 수 있는 순수 Kotlin 로직 (플랫폼 API 의존 없음).
// Go 원본: internal/authentication/{metadata.go, native.go, peer.go, signer.go, window.go},
//          pkg/connector/ble/ble.go (framing, local name)
package tesla.protocol

// ---- 플랫폼별로 구현할 암호화 원시 연산 (KMP에서는 expect/actual 또는 cryptography-kotlin) ----
interface CryptoPrimitives {
    fun sha1(data: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    /** 표준 ECDH: 공유점의 X좌표 32바이트 (Secure Enclave / Android Keystore 모두 이 형태를 반환) */
    fun ecdhRawX(peerPublicUncompressed: ByteArray): ByteArray
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> // (ciphertext, tag)
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray
    fun randomBytes(n: Int): ByteArray
}

// ---- signatures.proto 의 Tag / SignatureType 값 ----
object Tag {
    const val SIGNATURE_TYPE = 0; const val DOMAIN = 1; const val PERSONALIZATION = 2; const val EPOCH = 3
    const val EXPIRES_AT = 4; const val COUNTER = 5; const val CHALLENGE = 6; const val FLAGS = 7
    const val REQUEST_HASH = 8; const val FAULT = 9; const val END = 255
}
object SignatureType { const val AES_GCM = 0; const val AES_GCM_PERSONALIZED = 5; const val HMAC = 6; const val HMAC_PERSONALIZED = 8; const val AES_GCM_RESPONSE = 9 }
object Domain { const val BROADCAST = 0; const val VEHICLE_SECURITY = 2; const val INFOTAINMENT = 3 }

// ---- metadata.go: TLV 직렬화 ----
class Metadata {
    private val buf = ArrayList<Byte>()
    private var last = -1
    fun add(tag: Int, value: ByteArray?): Metadata {
        require(tag >= last) { "metadata items need to be added in increasing tag order" }
        if (value == null) return this
        require(value.size <= 255) { "metadata field too long" }
        last = tag
        buf += tag.toByte(); buf += value.size.toByte(); value.forEach { buf += it }
        return this
    }
    fun addUint32(tag: Int, v: Long) = add(tag, u32be(v))
    /** Checksum 입력: 메타데이터 || 0xFF || message */
    fun serialize(message: ByteArray = ByteArray(0)): ByteArray = buf.toByteArray() + byteArrayOf(Tag.END.toByte()) + message
}

fun u32be(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

// ---- ble.go: 광고 이름, 2바이트 길이 프레이밍 ----
object BleTransport {
    const val SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e"
    const val TX_CHAR_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e" // write with response
    const val RX_CHAR_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e" // indicate
    const val MAX_MESSAGE = 1024

    fun localName(vin: String, c: CryptoPrimitives) = "S" + c.sha1(vin.encodeToByteArray()).copyOf(8).toHex() + "C"

    fun frame(msg: ByteArray, mtuPayload: Int): List<ByteArray> {
        val out = byteArrayOf((msg.size shr 8).toByte(), msg.size.toByte()) + msg
        return out.toList().chunked(mtuPayload) { it.toByteArray() }
    }

    /** 수신 청크 재조립 (Go: Connection.rx / flush). 1초 이상 끊기면 버퍼 리셋은 호출측에서. */
    class Reassembler {
        private var buffer = ByteArray(0)
        fun push(chunk: ByteArray): List<ByteArray> {
            buffer += chunk
            val out = mutableListOf<ByteArray>()
            while (buffer.size >= 2) {
                val len = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
                if (len > MAX_MESSAGE) { buffer = ByteArray(0); break }
                if (buffer.size < 2 + len) break
                out += buffer.copyOfRange(2, 2 + len)
                buffer = buffer.copyOfRange(2 + len, buffer.size)
            }
            return out
        }
        fun reset() { buffer = ByteArray(0) }
    }
}

// ---- native.go + signer.go: 세션 키, 세션정보 HMAC, 명령 암호화, 응답 복호화 ----
class Session(
    private val c: CryptoPrimitives,
    vehiclePublicKey: ByteArray,
    private val vin: String,
) {
    /** K = SHA1(ECDH_X)[:16] */
    val key: ByteArray = c.sha1(c.ecdhRawX(vehiclePublicKey)).copyOf(16)

    fun subkey(label: String) = c.hmacSha256(key, label.encodeToByteArray())

    /** 핸드셰이크 응답 검증 태그 (Go: SessionInfoHMAC) */
    fun sessionInfoTag(challengeUuid: ByteArray, encodedSessionInfo: ByteArray): ByteArray {
        val m = Metadata()
            .add(Tag.SIGNATURE_TYPE, byteArrayOf(SignatureType.HMAC.toByte()))
            .add(Tag.PERSONALIZATION, vin.encodeToByteArray())
            .add(Tag.CHALLENGE, challengeUuid)
        return c.hmacSha256(subkey("session info"), m.serialize(encodedSessionInfo))
    }

    fun commandMetadata(domain: Int, epoch: ByteArray, expiresAt: Long, counter: Long, flags: Long, sigType: Int = SignatureType.AES_GCM_PERSONALIZED): Metadata {
        val m = Metadata()
            .add(Tag.SIGNATURE_TYPE, byteArrayOf(sigType.toByte()))
            .add(Tag.DOMAIN, byteArrayOf(domain.toByte()))
            .add(Tag.PERSONALIZATION, vin.encodeToByteArray())
            .add(Tag.EPOCH, epoch)
            .addUint32(Tag.EXPIRES_AT, expiresAt)
            .addUint32(Tag.COUNTER, counter)
        if (flags > 0) m.addUint32(Tag.FLAGS, flags)
        return m
    }

    data class Encrypted(val nonce: ByteArray, val ciphertext: ByteArray, val tag: ByteArray)

    /** BLE 기본 인증 방식: AES-GCM, AAD = SHA256(metadata || 0xFF) */
    fun encryptCommand(plaintext: ByteArray, meta: Metadata, nonce: ByteArray = c.randomBytes(12)): Encrypted {
        val (ct, tag) = c.aesGcmEncrypt(key, nonce, plaintext, c.sha256(meta.serialize()))
        return Encrypted(nonce, ct, tag)
    }

    /** 요청 해시: sigType 1바이트 + 태그 (VCSEC + HMAC이면 태그 16바이트로 절단) */
    fun requestHash(sigType: Int, tag: ByteArray, domain: Int): ByteArray {
        val t = if (sigType == SignatureType.HMAC_PERSONALIZED && domain == Domain.VEHICLE_SECURITY) tag.copyOf(16) else tag
        return byteArrayOf(sigType.toByte()) + t
    }

    fun decryptResponse(
        fromDomain: Int, counter: Long, responseFlags: Long, requestHash: ByteArray, fault: Long,
        nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray,
    ): ByteArray {
        val m = Metadata()
            .add(Tag.SIGNATURE_TYPE, byteArrayOf(SignatureType.AES_GCM_RESPONSE.toByte()))
            .add(Tag.DOMAIN, byteArrayOf(fromDomain.toByte()))
            .add(Tag.PERSONALIZATION, vin.encodeToByteArray())
            .addUint32(Tag.COUNTER, counter)
            .addUint32(Tag.FLAGS, responseFlags)   // 응답에서는 0이어도 항상 포함
            .add(Tag.REQUEST_HASH, requestHash)
            .addUint32(Tag.FAULT, fault)
        return c.aesGcmDecrypt(key, nonce, ciphertext, tag, c.sha256(m.serialize()))
    }
}

// ---- window.go: 응답 counter 재사용(replay) 방지 슬라이딩 윈도우 ----
class SlidingWindow(private val size: Int = 32) {
    private var used = false; private var counter = 0L; private var history = 0UL
    fun update(newCounter: Long): Boolean {
        if (!used) { used = true; counter = newCounter; return true }
        if (newCounter == counter) return false
        if (newCounter < counter) {
            val age = counter - newCounter
            if (age > size) return false
            val bit = 1UL shl (age - 1).toInt()
            if (history and bit != 0UL) return false
            history = history or bit; return true
        }
        val shift = (newCounter - counter).toInt()
        history = if (shift >= 64) 0UL else history shl shift
        if (shift <= 64) history = history or (1UL shl (shift - 1))
        counter = newCounter; return true
    }
}

fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
~~~~

### A-8. `reference/poc/PocTest.kt`

~~~~kotlin
// jvmMain / androidMain 역할: JCA로 CryptoPrimitives 구현 + protocol.md 테스트 벡터 검증
package tesla.poc

import tesla.protocol.*
import java.math.BigInteger
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class JcaCrypto(private val privateScalar: BigInteger) : CryptoPrimitives {
    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
    }
    override fun sha1(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)
    override fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    override fun hmacSha256(key: ByteArray, data: ByteArray) =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    override fun ecdhRawX(peerPublicUncompressed: ByteArray): ByteArray {
        require(peerPublicUncompressed.size == 65 && peerPublicUncompressed[0] == 4.toByte())
        val x = BigInteger(1, peerPublicUncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, peerPublicUncompressed.copyOfRange(33, 65))
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        val priv = kf.generatePrivate(ECPrivateKeySpec(privateScalar, params))
        return KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
    }
    override fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(plaintext)
        }
        return out.copyOfRange(0, out.size - 16) to out.copyOfRange(out.size - 16, out.size)
    }
    override fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(ciphertext + tag)
        }
    override fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}

// 아주 작은 protobuf 리더 (실제 앱에서는 Wire 생성 코드 사용)
fun readFields(b: ByteArray): Map<Int, Any> {
    val out = LinkedHashMap<Int, Any>(); var i = 0
    fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
    while (i < b.size) {
        val key = varint(); val f = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            0 -> out[f] = varint()
            2 -> { val n = varint().toInt(); out[f] = b.copyOfRange(i, i + n); i += n }
            5 -> { out[f] = (0..3).fold(0L) { a, k -> a or ((b[i + k].toLong() and 0xff) shl (8 * k)) }; i += 4 }
            else -> error("wire type")
        }
    }
    return out
}

var fails = 0
fun check(name: String, actual: String, expected: String) {
    val ok = actual == expected; if (!ok) fails++
    println((if (ok) "PASS " else "FAIL ") + name + if (ok) "" else "\n   got      $actual\n   expected $expected")
}

fun main() {
    // protocol.md 테스트 키
    val c = JcaCrypto(BigInteger("2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB", 16))
    val vehiclePub = "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97".hex()
    val vin = "5YJ30123456789ABC"

    // 1. BLE 광고 이름
    check("BLE local name", BleTransport.localName("5YJS0000000000000", c), "S1a87a5a75f3df858C")

    // 2. 핸드셰이크 응답 session_info 디코딩
    val sessionInfo = "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a104c463f9cc0d3d26906e982ed224adde6255a0a0000".hex()
    val si = readFields(sessionInfo)
    check("session_info.counter", si[1].toString(), "6")
    check("session_info.publicKey", (si[2] as ByteArray).toHex(), vehiclePub.toHex())
    check("session_info.epoch", (si[3] as ByteArray).toHex(), "4c463f9cc0d3d26906e982ed224adde6")
    check("session_info.clock_time", si[4].toString(), "2650")

    // 3. ECDH -> K
    val s = Session(c, si[2] as ByteArray, vin)
    check("shared key K", s.key.toHex(), "1b2fce19967b79db696f909cff89ea9a")
    check("SESSION_INFO_KEY", s.subkey("session info").toHex(), "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300")

    // 4. 세션정보 HMAC 태그 검증 (MITM 방지)
    val challenge = "1588d5a30eabc6f8fc9a951b11f6fd11".hex()
    check("session info HMAC tag", s.sessionInfoTag(challenge, sessionInfo).toHex(), "996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002")

    // 5. 명령 메타데이터 (HVAC on 예제)
    val epoch = si[3] as ByteArray
    val meta = s.commandMetadata(Domain.INFOTAINMENT, epoch, expiresAt = 2655, counter = 7, flags = 0)
    check("command metadata TLV", meta.serialize().toHex(), "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007ff")

    // 6. AES-GCM 암호화 → 외부(Python cryptography)에서 복호화 검증용으로 출력
    val hvacOn = "120452020801".hex()
    val nonce = "dbf79447fa156674dae1caed".hex()
    val enc = s.encryptCommand(hvacOn, meta, nonce)
    println("GCM nonce=${enc.nonce.toHex()} ct=${enc.ciphertext.toHex()} tag=${enc.tag.toHex()}")
    check("GCM ciphertext (matches protocol.md example w/ same nonce)", enc.ciphertext.toHex() + enc.tag.toHex(), "38038e8c0f2e" + "8e128da165f162f4d7d2c8da866cf82a")

    // 7. 응답 복호화 왕복 (차량 측 역할을 흉내내 암호화 후 복호화)
    val reqHash = s.requestHash(SignatureType.AES_GCM_PERSONALIZED, enc.tag, Domain.INFOTAINMENT)
    val respMeta = Metadata().add(Tag.SIGNATURE_TYPE, byteArrayOf(SignatureType.AES_GCM_RESPONSE.toByte()))
        .add(Tag.DOMAIN, byteArrayOf(Domain.INFOTAINMENT.toByte())).add(Tag.PERSONALIZATION, vin.encodeToByteArray())
        .addUint32(Tag.COUNTER, 8).addUint32(Tag.FLAGS, 0).add(Tag.REQUEST_HASH, reqHash).addUint32(Tag.FAULT, 0)
    val rn = c.randomBytes(12)
    val (rct, rtag) = c.aesGcmEncrypt(s.key, rn, "0a00".hex(), c.sha256(respMeta.serialize()))
    check("response decrypt roundtrip", s.decryptResponse(Domain.INFOTAINMENT, 8, 0, reqHash, 0, rn, rct, rtag).toHex(), "0a00")

    // 8. BLE 프레이밍 + 청크 재조립 (MTU 23 → payload 20)
    val msg = "320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805".hex()
    val chunks = BleTransport.frame(msg, 20)
    val r = BleTransport.Reassembler()
    val got = chunks.flatMap { r.push(it) }
    check("BLE frame/reassemble (${chunks.size} chunks)", got.single().toHex(), msg.toHex())

    // 9. protocol.md 의 실제 TX 로그 디코딩 (list-keys 요청)
    val rm = readFields(msg)
    val toDomain = readFields(rm[6] as ByteArray)[1]
    val routingAddr = readFields(rm[7] as ByteArray)[2] as ByteArray
    check("TX log: to_destination.domain", toDomain.toString(), Domain.VEHICLE_SECURITY.toString())
    check("TX log: routing_address", routingAddr.toHex(), "0a7962c10d38b61dd2a7722780a4f096")
    check("TX log: payload (VCSEC GET_WHITELIST_INFO)", (rm[10] as ByteArray).toHex(), "0a020805")

    // 10. 슬라이딩 윈도우 (replay 방지)
    val w = SlidingWindow()
    check("sliding window", listOf(w.update(10), w.update(12), w.update(11), w.update(11), w.update(12), w.update(50)).toString(), "[true, true, true, false, false, true]")

    println(if (fails == 0) "\nALL PASSED" else "\n$fails FAILED")
}
~~~~

