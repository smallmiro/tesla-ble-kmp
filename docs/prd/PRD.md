# PRD — tesla-ble-kmp (`{{LIB_NAME}}`)

| 항목 | 내용 |
|---|---|
| 상태 | **초안 v1.0 — 사용자 승인 대기** (2026-09-26) |
| 상위 문서 | `{{HANDOFF_FILE}}` §1~§5, §8, §10~§13 |
| 정답 기준 | `{{REF_REPO_DIR}}` @ `{{REF_REPO_COMMIT}}` + `pkg/protocol/protocol.md` |
| 1차 가이드 | `{{MANUAL_DIR}}` (각 요구사항의 "근거" 열에 문서와 절을 표기) |
| 다음 문서 | `{{SDD_FILE}}` (Phase 2) |

경로는 모두 `{{PATHS_FILE}}` 의 키로 참조한다. 근거 열의 `NN-xxx §n` 은 `{{MANUAL_DIR}}NN-xxx.md` 의 n절을 뜻한다.

---

## 0. Phase 1에서 결정한 사항 (HANDOFF D1~D8에 이어 번호 부여)

| # | 질문 (HANDOFF §10) | 결정 | 근거·영향 |
|---|---|---|---|
| D9 | 최소 지원 버전 | **Android API 31+ / iOS 16+** | Keystore ECDH(`PURPOSE_AGREE_KEY`)와 BLE 권한 모델(`BLUETOOTH_SCAN/CONNECT`)이 API 31에서 단일 경로. 소프트웨어 키 대체 경로 없음. `10-porting-guide §12` |
| D10 | 개인키 보관 | **하드웨어 기본 + 소프트웨어 대체 허용.** 실제 보관 수준(`HARDWARE` / `STRONGBOX` / `SOFTWARE`)을 조회하는 API 제공 | 에뮬레이터·시뮬레이터·CI에서도 동작. ECDH 공유 비밀과 세션 키 K는 프로토콜 구조상 앱 메모리에 존재함을 문서화. `00-agent-guide §3.3-21`, `10-porting-guide §12` |
| D11 | 키 등록 기본 역할 | **Owner.** 역할 지정 오버로드 제공 | Go `SendAddKeyRequest`와 동일 기본값. `03-protocol §3`, `05-command-catalog B` |
| D12 | 공개 API 스타일 | **commonMain은 `suspend` + `Flow`. iOS는 SKIE로 Swift async/await, AsyncSequence, enum 변환** | SKIE(Apache-2.0) 도입은 SDD에서 ADR로 기록. `04-go-api-reference pkg/vehicle` |
| D13 | 배포 | **v1은 비공개.** 배포 채널은 **GitHub Packages(Maven 레지스트리) + SPM(GitHub Release의 XCFramework)**. Maven Central은 쓰지 않음. 공개 배포 자체는 **M6의 마지막 과제**. 그 전까지 `mavenLocal` + 로컬 XCFramework | 소비자는 공개 패키지를 받을 때도 GitHub PAT(`read:packages`)가 필요하므로 `getting-started.md`에 설정법을 명시. 패키지 루트는 공개를 전제로 정함 |
| D14 | 패키지 루트 | **`io.github.smallmiro.teslable`** (`{{PATHS_FILE}}` `BASE_PACKAGE`에 기록됨) | GitHub Packages는 그룹 제약이 없지만, 나중에 Maven Central로 옮길 수 있도록 `io.github.<계정>` 규칙을 유지 |
| D15 | 샘플 앱 UI | **Android Jetpack Compose + iOS SwiftUI** (각각 네이티브) | 실제 소비자 앱과 같은 구성. SKIE가 만든 Swift 표면을 검증 |
| D16 | 동시 연결 차량 수 | **설계는 차량 단위 객체(연결마다 독립 인스턴스). v1 검증은 1대.** 다중 연결은 막지 않되 "미검증"으로 문서화 | Go와 동일 (연결 1 = Vehicle 1). `01-architecture §5`, `02-ble-transport §11` |
| D17 | v1 기능 범위 | **P0 + P1을 v1에 포함. P2는 v1.1** (§5 표 참조) | `{{HANDOFF_FILE}}` §12 M5 완료 기준 = P0/P1 |
| D18 | 세션 캐시 | **`SessionCache` 포트 + 플랫폼 기본 구현(Android 앱 전용 파일, iOS Keychain ThisDeviceOnly). 추가 암호화 없음.** 메모리 전용 구현도 제공(테스트용) | 캐시 내용은 비밀이 아님(개인키 없이는 사용 불가). OS 샌드박스로 충분. `10-porting-guide §9`, `00-agent-guide §3.2-17` |
| D19 | i18n | **에러 메시지·로그는 영어 + 구조화된 에러 코드.** 현지화는 앱 책임. 문서는 한국어 | Go 원본과 대조 용이. `08-errors §1` |

---

## 1. 배경과 목표, 성공 지표

### 1.1 배경
Tesla 공식 Go SDK `vehicle-command`는 BLE로 차량과 직접 통신하는 유일한 공개 레퍼런스이지만 Go 전용이며 데스크톱/Linux를 전제로 한다. 스마트폰 앱이 **서버 없이** 차량을 제어하려면 같은 프로토콜을 모바일 런타임(Android, iOS)에서 구현해야 한다. 기존 KMP 구현체는 없고(HANDOFF D3), 유일한 모바일 구현 후보는 AGPL이라 열람할 수 없다(D8).

### 1.2 목표
1. `vehicle-command`의 **BLE 경로**(스캔 → 연결 → 핸드셰이크 → 명령 → 응답)를 Kotlin Multiplatform으로 **바이트 단위로 동일하게** 포팅한다.
2. 앱 개발자가 **키 등록, 조회, 제어**를 `suspend` 함수 몇 개로 호출할 수 있는 공개 API를 제공한다.
3. 실제 차량 없이도 **FakeVehicle**로 전체 흐름을 테스트할 수 있게 한다.

### 1.3 성공 지표 (DoD, `{{HANDOFF_FILE}}` §14)
| 지표 | 목표 | 측정 |
|---|---|---|
| `protocol.md` 테스트 벡터 | 100% PASS (JVM + iOS 시뮬레이터) | `./gradlew jvmTest iosSimulatorArm64Test` |
| 골든 픽스처 (TX/RX 헥사) | 100% PASS | commonTest |
| FakeVehicle 통합 시나리오 | 핸드셰이크, 매칭, VCSEC 다중 응답, 재전송 방지, 세션 복구 전부 PASS | commonTest |
| 샘플 앱 | Android, iOS 각 1개 빌드 성공 | CI |
| 실차 체크리스트 (`{{WORKFLOW_FILE}}` §8.4) | 9개 시나리오 사용자 확인 | 사용자 수행 |
| 라이브러리 사용 문서 | `{{LIB_DOCS_DIR}}` 9개 페이지, 링크 검증 통과 | 링크 검사 |

---

## 2. 대상 사용자

| 사용자 | 필요 | 성공 조건 |
|---|---|---|
| **앱 개발자** (라이브러리 사용자, Android/iOS 네이티브) | 프로토콜을 몰라도 페어링·조회·제어를 호출. 결과가 확실/불확실/실패로 구분되어 UI에 반영 가능 | `getting-started.md`만 읽고 30분 안에 샘플 수준 앱 작성 |
| **최종 사용자** (차주) | 폰이 차 근처에서 잠금 해제·공조·충전 조작. 서버·계정·인터넷 없음 | 첫 페어링은 차 안에서 NFC 카드 한 번. 이후 앱 실행 후 1~2초 안에 잠금/해제 |
| **기여자** | 원본 Go와 대응 관계를 보고 수정·확장 | 파일 헤더의 원본 경로, `architecture.md` |

---

## 3. 범위 / 비범위

### 3.1 In scope (HANDOFF §3 + Phase 1 결정)
- BLE 전송: 스캔(광고 이름 매칭), 연결, MTU 협상, 청크 분할·재조립, indication 구독, 연결 해제
- 프로토콜: RoutableMessage, 도메인별 핸드셰이크, 세션 키 유도, AES-GCM 명령 암호화, 응답 복호화, 세션정보 HMAC 검증, 재전송 방지 윈도우, 세션 캐시(D18), 세션 복구
- 키 관리: 키쌍 생성·보관(D10), add-key-request(D11), 인증된 add-key, 키 목록 조회, 키 삭제, 등록 확인
- 조회: VCSEC `VehicleStatus`, Whitelist, Infotainment `GetState` 12개 카테고리
- 제어: `pkg/vehicle` 공개 API 중 **BLE로 전송 가능한 전부** (§5, P0/P1은 v1, P2는 v1.1)
- 에러 모델: 성공 / 실패 / 결과 불확실(PossibleSuccess), 재시도 정책
- 샘플 앱 2종(D15), 라이브러리 사용 문서 `{{LIB_DOCS_DIR}}`
- iOS Swift 표면(D12)

### 3.2 Out of scope
- Fleet API, OAuth, Fleet Telemetry, HTTP 프록시, JWS/Schnorr (`internal/schnorr`, `pkg/account`, `pkg/proxy`, `pkg/cli`, `pkg/sign`)
- **`SetPINToDrive`**: Go 원본이 BLE(비-FleetAPIConnector)에서 `ErrRequiresEncryption`을 반환하므로 BLE로는 불가능. `ClearPINToDrive`만 포함. (HANDOFF §5.2에는 포함되어 있으나 원본 기준으로 제외. 근거: `05-command-catalog H`, `04-go-api-reference security.go`)
- **`rename-key`**: Fleet API 전용
- 차량 측 검증 코드(`authentication/verifier.go`). FakeVehicle 구현 시 참고만
- 2021년 이전 Model S/X
- 백그라운드 자동 잠금 해제(Phone-as-Key). **v2 후보**
- 다중 차량 동시 연결의 검증(D16). 설계상 허용, 테스트·실차 확인은 v1 범위 밖
- GitHub Packages·SPM 공개 배포 자체는 M6 마지막 과제(D13). Maven Central 배포는 범위 밖. 그 전 마일스톤의 DoD에는 포함하지 않음
- 소프트웨어 키 전용 경로(API 31 미만)(D9)
- 에러 메시지 현지화(D19)

---

## 4. 사용자 시나리오

각 시나리오는 최종 사용자 관점이며, 괄호 안은 라이브러리가 수행하는 동작과 근거 매뉴얼이다.

### S1. 최초 페어링
1. 사용자가 차 안에서 앱의 "차량 등록"을 누르고 VIN을 입력한다.
2. 앱은 키쌍을 만들고(FR-020) 차량을 스캔·연결한 뒤(FR-001~003) `addKeyRequest(role = OWNER)`를 보낸다(FR-022). (`10-porting-guide §10`: `ToVCSECMessage{PRESENT_KEY}` 직접 전송, 인증 없음)
3. 차량 화면에 승인 요청이 뜨고 사용자가 NFC 키카드를 센터 콘솔에 태그한 뒤 확인한다.
4. 앱은 `confirmKeyRegistered()`(FR-024)로 VCSEC와 Infotainment 세션이 열리는지 확인한다. Infotainment 동기화는 수십 초 걸릴 수 있으므로 진행 상태를 표시한다. (`10-porting-guide §10`, `08-errors §8` "페어링 직후")
5. 실패 시 `KeychainError` 코드(예: `WHITELIST_FULL`, `TIMED_OUT_WAITING_FOR_TAP`)를 사용자에게 안내한다. (`08-errors §3.7`)

### S2. 근접 잠금 해제 (수동)
1. 사용자가 앱을 열고 "잠금 해제"를 누른다.
2. 라이브러리는 세션 캐시로 핸드셰이크를 생략하고(FR-017) VCSEC에 `RKE_ACTION_UNLOCK`을 보낸다(FR-041). Infotainment가 잠들어 있어도 동작한다. (`02-ble-transport §11`, `05-command-catalog A`)
3. 응답이 1초 안팎에 오면 성공을 표시한다. 응답 없이 타임아웃되면 **결과 불확실**로 표시하고 `vehicleStatus()`로 잠금 상태를 재조회한다(FR-100~102). (`08-errors §7`)

### S3. 상태 확인
1. 사용자가 "상태" 탭을 연다.
2. 잠금·도어·트렁크·수면 상태는 인증 없이 `vehicleStatus()`(FR-031)로 즉시 표시한다.
3. 배터리·공조·타이어 등은 Infotainment 세션이 필요하므로, 차량이 잠들어 있으면 `wakeUp()`(FR-042) 후 `getState(category)`(FR-033)를 호출한다. (`05-command-catalog I`, `00-agent-guide §5` "StartSession이 Infotainment를 깨운다")

### S4. 원격 공조
1. 사용자가 "공조 켜기"와 온도를 선택한다.
2. 라이브러리는 필요 시 깨운 뒤 `climateOn()`, `setClimateTemp()`(FR-060, FR-062)를 보낸다.
3. Infotainment 응답의 `actionStatus.result == ERROR`면 차량이 준 사유 문자열을 그대로 에러에 담는다. (`08-errors §3.6`)

### S5. 충전 관리
1. 사용자가 충전 한도(%)와 전류(A)를 바꾸고 충전을 시작한다(FR-070~073).
2. 충전 스케줄 추가·삭제(FR-078~083)는 P1이며 v1에 포함된다.

### S6. 키 삭제
1. 사용자가 "등록된 키" 목록(FR-025, 인증 없음)에서 항목을 고른다.
2. Owner 키로 `removeKey(publicKey)`(FR-026)를 보낸다. Driver 역할 키는 `INSUFFICIENT_PRIVILEGES`를 받는다. (`03-protocol §3`, `08-errors §8`)

---

## 5. 기능 요구사항 (FR)

우선순위: **P0** = M4/M5 완료 기준이자 v1 DoD, **P1** = v1 포함, **P2** = v1.1. 도메인: VCSEC / INFO / – (전송·로컬).
Go 열은 `{{REF_REPO_DIR}}pkg/vehicle` 메서드명(포팅 대응). 근거 열은 `{{MANUAL_DIR}}` 문서.

### 5.1 BLE 전송

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-001 | VIN으로 광고 이름 `"S"+hex(SHA1(VIN)[:8])+"C"`를 계산해 스캔하고, 발견한 차량의 RSSI와 connectable 여부를 `Flow`로 알린다. connectable=false면 `MaxConnectionsExceeded`로 구분한다 | P0 | – | `VehicleLocalName`, `ScanVehicleBeacon` | `02-ble-transport §2~§3`, `10-porting-guide §1` |
| FR-002 | GATT 연결: 서비스 `…0211`, TX `…0212`(write with response), RX `…0213`(indication 구독), MTU 협상. 블록 길이 = `min(MTU,1024)-3`, 실패 시 20 | P0 | – | `tryToConnect` | `02-ble-transport §4~§5`, `10-porting-guide §2` |
| FR-003 | 프레이밍: 2바이트 BE 길이 + 메시지, 최대 1024바이트. 송신은 블록 길이로 분할해 순서대로 write. 수신은 재조립하고, 청크 간 1초 초과 시 버퍼 리셋, 길이>1024면 폐기 | P0 | – | `Send`, `rx`, `flush` | `02-ble-transport §6`, `10-porting-guide §3` |
| FR-004 | 연결 재시도: 스캔·연결·구독 실패 시 1초 간격 재시도. 어댑터 오류, 이름 불일치, connectable=false는 재시도하지 않음 | P0 | – | `NewConnectionFromScanResult` | `02-ble-transport §4.1`, `08-errors §4` |
| FR-005 | 명시적 연결 해제. 해제는 멱등. 명령을 보내지 않을 때 연결을 끊어 슬롯(3개)을 비우는 방법을 문서화 | P0 | – | `Close`, `Disconnect` | `02-ble-transport §11`, `00-agent-guide §3.3-19,20` |
| FR-006 | BLE 권한(Android 12+ `BLUETOOTH_SCAN/CONNECT`, iOS `NSBluetoothAlwaysUsageDescription`)이 없을 때 도메인 에러로 보고 | P0 | – | – | `10-porting-guide §12` |

### 5.2 프로토콜과 세션

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-010 | RoutableMessage 조립: 16B 랜덤 uuid, `FLAG_ENCRYPT_RESPONSE`(=2) 항상 설정. VCSEC는 요청마다 새 16B routing_address, Infotainment는 연결 동안 고정 주소 | P0 | 둘 다 | `Dispatcher.Send` | `03-protocol §1`, `10-porting-guide §4`, `01-architecture §4.1` |
| FR-011 | 응답 매칭: VCSEC는 routing_address만, Infotainment는 address+request_uuid로 매칭. 미등록 응답은 드롭 | P0 | 둘 다 | `receiverKey`, `process` | `01-architecture §4.1`, `00-agent-guide §5` |
| FR-012 | 도메인별 핸드셰이크(`SessionInfoRequest`)와 재전송(1초 간격). 두 도메인을 병렬로 수행하되 **VCSEC만** 지정할 수 있어야 함(잠든 차량) | P0 | 둘 다 | `StartSession(s)` | `03-protocol §7`, `10-porting-guide §5`, `00-agent-guide §5` |
| FR-013 | 키 합의와 KDF: `K = SHA1(ECDH_X)[:16]`, 서브키 `HMAC-SHA256(K, label)` | P0 | 둘 다 | `NativeSession` | `03-protocol §7.3`, `10-porting-guide §0.2` |
| FR-014 | 세션정보 HMAC 검증: TLV{SIG_TYPE=6, VIN, CHALLENGE=uuid} ‖ session_info, 상수 시간 비교. 태그 없음·불일치·오래된 uuid·요청 후 4초 초과·같은 epoch에서 clock 역행이면 폐기 | P0 | 둘 다 | `SessionInfoHMAC`, `UpdateSessionInfo`, `checkForSessionUpdate` | `03-protocol §7.4, §9.4`, `00-agent-guide §3.1-4,5,6` |
| FR-015 | 명령 암호화: AES-128-GCM, 12B nonce, AAD = SHA256(TLV{SIG_TYPE=5, DOMAIN, VIN, EPOCH, EXPIRES_AT, COUNTER, [FLAGS≠0]}). counter는 epoch 안에서 단조 증가, `0xFFFFFFFF`면 롤오버 에러. `expires_at`은 도메인 시계 기준, 기본 수명 5초 | P0 | 둘 다 | `Signer.Encrypt`, `extractMetadata` | `03-protocol §8.2`, `10-porting-guide §6`, `00-agent-guide §3.1-1,7,12` |
| FR-016 | 응답 복호화: AAD = SHA256(TLV{SIG_TYPE=9, DOMAIN, VIN, COUNTER, FLAGS(항상), REQUEST_HASH, FAULT}). `REQUEST_HASH = sigType(1B) ‖ 요청 tag`. 응답 counter는 요청별 슬라이딩 윈도우(32)로 재사용 검사, 실패 시 드롭 | P0 | 둘 다 | `Signer.Decrypt`, `responseMetadata`, `RequestID`, `SlidingWindow` | `03-protocol §9.2`, `10-porting-guide §7`, `00-agent-guide §3.1-3,10` |
| FR-017 | 세션 캐시: 도메인별 `SessionInfo{counter, vehiclePublicKey, epoch, clock_time}`와 저장 시각을 `SessionCache` 포트에 저장·복원. 복원 세션은 즉시 사용하고 첫 실패 시 FR-018로 복구. 세션 키 K는 저장하지 않음 | P0 | 둘 다 | `pkg/cache`, `CacheEntry`, `Export/ImportSessionInfo` | `10-porting-guide §9`, `01-architecture §6` |
| FR-018 | 세션 복구: 오류 응답에 동봉된 session_info로 FR-014 규칙에 따라 갱신하고 재시도. counter는 내리지 않음(Go 동작) | P0 | 둘 다 | `checkForSessionUpdate`, `processHello` | `08-errors §5`, `03-protocol §9.4` |
| FR-019 | 시계: `timeZero = now - clock_time`, `expiresAt = (now + lifetime) - timeZero`. `Clock` 포트로 주입 가능 | P0 | 둘 다 | `session.go` | `10-porting-guide §5~§6`, `{{WORKFLOW_FILE}}` §2.4 |

### 5.3 키 관리

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-020 | P-256 키쌍 생성·보관. 하드웨어 보안 영역 우선, 불가 시 플랫폼 키스토어 소프트웨어 키(D10). 개인키는 내보내지 않음. ECDH는 키스토어 안에서 수행 | P0 | – | `ECDHPrivateKey` | `10-porting-guide §0.2, §12`, `00-agent-guide §3.3-21` |
| FR-021 | 공개키를 `0x04‖X‖Y`(65B)로 조회. 실제 보관 수준(HARDWARE/STRONGBOX/SOFTWARE)을 조회 | P0 | – | `PublicBytes` | `03-protocol §5` |
| FR-022 | `addKeyRequest(role = OWNER, formFactor = 플랫폼 기본)`: `ToVCSECMessage{SignedMessage{PRESENT_KEY, UnsignedMessage.whitelistOperation.addKeyToWhitelistAndAddPermissions}}`를 RoutableMessage 없이 직접 전송. formFactor 기본값은 Android `ANDROID_DEVICE(7)`, iOS `IOS_DEVICE(6)`. 역할 오버로드 제공(D11) | P0 | VCSEC | `SendAddKeyRequestWithRole` | `10-porting-guide §10`, `05-command-catalog B`, `00-agent-guide §4.2` |
| FR-023 | add-key-request 이후 차량의 `WAIT`/최종 응답을 **읽어** 진행 상태를 `Flow`로 노출(Go는 읽지 않음. 원본과 다른 점이므로 SDD와 ADR에 이유 기록) | P1 | VCSEC | (없음) | `10-porting-guide §10`, `03-protocol §11` |
| FR-024 | 등록 확인: 임의 공개키로 `SessionInfoRequest`를 보내 상태를 반환(`sessionInfo(publicKey, domain)`). 페어링 후 Infotainment 동기화 대기에 사용 | P0 | 둘 다 | `SessionInfo` | `05-command-catalog B`, `10-porting-guide §10` |
| FR-025 | 키 목록: `GET_WHITELIST_INFO` → 슬롯마다 `GET_WHITELIST_ENTRY_INFO`. 인증 없음. 공개키, 역할, 형태 반환 | P0 | VCSEC | `KeySummary`, `KeyInfoBySlot` | `05-command-catalog B`, `03-protocol §12` |
| FR-026 | 키 삭제: `removePublicKeyFromWhitelist` (인증 필요) | P0 | VCSEC | `RemoveKey` | `05-command-catalog B` |
| FR-027 | 인증된 키 추가: 등록된 Owner 키로 다른 공개키를 역할·형태와 함께 추가 | P1 | VCSEC | `AddKeyWithRole` | `05-command-catalog B` |
| FR-028 | 키 삭제(로컬): 키스토어의 키쌍을 삭제하고 관련 세션 캐시를 비움 | P0 | – | – | `10-porting-guide §12` |

### 5.4 조회

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-030 | 스캔 결과: 존재, RSSI, connectable (FR-001과 동일 Flow) | P0 | – | `ScanVehicleBeacon` | `02-ble-transport §3` |
| FR-031 | `vehicleStatus()`: `InformationRequest{GET_STATUS}` → `VehicleStatus`(잠금, 도어 4, 프렁크·트렁크, 충전구, 톤노, 수면 상태, 탑승자). 인증·깨우기 불필요 | P0 | VCSEC | `BodyControllerState` | `05-command-catalog B`, `03-protocol §12` |
| FR-032 | Whitelist 요약: 키 개수, 슬롯 비트마스크 (FR-025 하위) | P0 | VCSEC | `KeySummary` | `05-command-catalog B` |
| FR-033 | `getState(category)`: 12개 카테고리(Charge, Climate, Drive, Location, Closures, ChargeSchedule, PreconditioningSchedule, TirePressure, Media, MediaDetail, SoftwareUpdate, ParentalControls)를 Wire 생성 타입으로 반환. 인증·깨우기 필요. `RESPONSE_MTU_EXCEEDED`는 "실행됨"으로 분류 | P0 | INFO | `GetState` | `05-command-catalog I`, `04-go-api-reference state.go`, `08-errors §8` |
| FR-034 | `ping()`: 온라인+키 인식 확인 | P1 | INFO | `Ping` | `05-command-catalog I`, `00-agent-guide §5` |

### 5.5 VCSEC 제어

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-040 | `lock()` | P0 | VCSEC | `Lock` | `05-command-catalog A` |
| FR-041 | `unlock()` | P0 | VCSEC | `Unlock` | 〃 |
| FR-042 | `wakeUp()`: `RKE_ACTION_WAKE_VEHICLE`(인증 필요, VCSEC 세션만으로 가능) | P0 | VCSEC | `Wakeup` | `05-command-catalog A`, `00-agent-guide §5` |
| FR-043 | `remoteDrive()` | P1 | VCSEC | `RemoteDrive` | `05-command-catalog A` |
| FR-044 | `autoSecureVehicle()` (Model X) | P1 | VCSEC | `AutoSecureVehicle` | 〃 |
| FR-045 | `openTrunk()` / `actuateTrunk()` (동일 페이로드), `closeTrunk()` | P0 | VCSEC | `OpenTrunk`, `ActuateTrunk`, `CloseTrunk` | `05-command-catalog C` |
| FR-046 | `openFrunk()` | P0 | VCSEC | `OpenFrunk` | 〃 |
| FR-047 | `openTonneau()`, `closeTonneau()`, `stopTonneau()` (Cybertruck) | P1 | VCSEC | `Open/Close/StopTonneau` | 〃 |
| FR-048 | VCSEC 응답 종료 규칙: RKE/Closure는 `commandStatus` 없는 메시지가 최종, whitelist는 `whitelistOperationStatus` 있는 메시지가 최종, 정보 요청은 첫 메시지. `WAIT`는 재시도, `nominalError`는 실패, 빈 메시지는 성공. 최대 3개 응답 | P0 | VCSEC | `unmarshalVCSECResponse`, `readUntil` | `03-protocol §11`, `08-errors §3.5` |
| FR-049 | VCSEC 요청 직렬화: 한 연결에서 VCSEC 요청은 동시에 보내지 않음 | P0 | VCSEC | (proxy `lockVIN`) | `00-agent-guide §3.1-2`, `02-ble-transport §11` |

### 5.6 Infotainment 제어: 차체

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-050 | `ventWindows()`, `closeWindows()` | P1 | INFO | `VentWindows`, `CloseWindows` | `05-command-catalog C` |
| FR-051 | `setSunroof(level)` | P1 | INFO | `ChangeSunroofState` | 〃 |
| FR-052 | `openChargePort()`, `closeChargePort()` | P1 | INFO | `OpenChargePort`, `CloseChargePort` | 〃 |
| FR-053 | `honkHorn()`, `flashLights()` | P1 | INFO | `HonkHorn`, `FlashLights` | 〃 |
| FR-054 | `triggerHomelink(lat, lon)` | P2 | INFO | `TriggerHomelink` | 〃 |

### 5.7 Infotainment 제어: 공조

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-060 | `climateOn()`, `climateOff()` | P0 | INFO | `ClimateOn/Off` | `05-command-catalog D` |
| FR-061 | `setSeatHeater(map<Seat, Level>)` (좌석 9종, 레벨 OFF/LOW/MED/HIGH) | P1 | INFO | `SetSeatHeater` | 〃 |
| FR-062 | `setClimateTemp(driverC, passengerC)` (섭씨) | P0 | INFO | `ChangeClimateTemp` | 〃 |
| FR-063 | `setSeatCooler(level, seat)` (앞좌석만) | P1 | INFO | `SetSeatCooler` | 〃 |
| FR-064 | `setSteeringWheelHeater(on)` | P1 | INFO | `SetSteeringWheelHeater` | 〃 |
| FR-065 | `autoSeatAndClimate(seats, on)` | P1 | INFO | `AutoSeatAndClimate` | 〃 |
| FR-066 | `setPreconditioningMax(on, manualOverride)` | P1 | INFO | `SetPreconditioningMax` | 〃 |
| FR-067 | `setClimateKeeperMode(mode, override)` (OFF/ON/DOG/CAMP) | P1 | INFO | `SetClimateKeeperMode` | 〃 |
| FR-068 | `setBioweaponDefenseMode(on, manualOverride)` | P1 | INFO | `SetBioweaponDefenseMode` | 〃 |
| FR-069 | `setCabinOverheatProtection(on, fanOnly)`, `setCabinOverheatProtectionTemperature(level)` | P1 | INFO | `SetCabinOverheatProtection(+Temperature)` | 〃 |

### 5.8 Infotainment 제어: 충전·전원

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-070 | `chargeStart()`, `chargeStop()` | P0 | INFO | `ChargeStart/Stop` | `05-command-catalog E` |
| FR-071 | `setChargeLimit(percent)` | P0 | INFO | `ChangeChargeLimit` | 〃 |
| FR-072 | `setChargingAmps(amps)` | P0 | INFO | `SetChargingAmps` | 〃 |
| FR-073 | `chargeStandardRange()`, `chargeMaxRange()` | P1 | INFO | `ChargeStandardRange`, `ChargeMaxRange` | 〃 |
| FR-074 | `scheduleCharging(enabled, minutesAfterMidnight)` | P1 | INFO | `ScheduleCharging` | 〃 |
| FR-075 | `scheduleDeparture(departAt, offPeakEnd, precondPolicy, offPeakPolicy)`, `clearScheduledDeparture()` | P1 | INFO | `ScheduleDeparture`, `ClearScheduledDeparture` | 〃 |
| FR-076 | `setLowPowerMode(on)` | P1 | INFO | `SetLowPowerMode` | 〃 |
| FR-077 | `setKeepAccessoryPowerMode(on)` | P1 | INFO | `SetKeepAccessoryPowerMode` | 〃 |
| FR-078 | `addChargeSchedule(schedule)` | P1 | INFO | `AddChargeSchedule` | 〃 |
| FR-079 | `removeChargeSchedule(id)` | P1 | INFO | `RemoveChargeSchedule` | 〃 |
| FR-080 | `batchRemoveChargeSchedules(home, work, other)` | P1 | INFO | `BatchRemoveChargeSchedules` | 〃 |
| FR-081 | `addPreconditionSchedule(schedule)` | P1 | INFO | `AddPreconditionSchedule` | 〃 |
| FR-082 | `removePreconditionSchedule(id)` | P1 | INFO | `RemovePreconditionSchedule` | 〃 |
| FR-083 | `batchRemovePreconditionSchedules(home, work, other)` | P1 | INFO | `BatchRemovePreconditionSchedules` | 〃 |

### 5.9 Infotainment 제어: 보안·모드

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-084 | `setSentryMode(on)` | P1 | INFO | `SetSentryMode` | `05-command-catalog H` |
| FR-085 | `enableValetMode(pin)`, `disableValetMode()` ("already off"는 성공), `resetValetPin()`. PIN은 4자리 숫자 검증 | P1 | INFO | `Enable/DisableValetMode`, `ResetValetPin` | 〃 |
| FR-086 | `clearPinToDrive()` (관리자 리셋). `SetPINToDrive`는 BLE 불가로 제외(§3.2) | P2 | INFO | `ClearPINToDrive` | 〃 |
| FR-087 | 속도 제한 5종: `activateSpeedLimit(pin)`, `deactivateSpeedLimit(pin)`, `clearSpeedLimitPin(pin)`, `clearSpeedLimitPinAdmin()`, `setSpeedLimitMph(mph)` | P2 | INFO | `*SpeedLimit*` | 〃 |
| FR-088 | 자녀 보호 5종: `parentalControlsActivate(pin)`, `Deactivate(pin)`, `EnableSetting(setting, on)`, `SetSpeedLimit(mph)`, `ClearPin()` | P2 | INFO | `ParentalControls*` | 〃 |
| FR-089 | `setGuestMode(on)`, `eraseGuestData()` | P2 | INFO | `SetGuestMode`, `EraseGuestData` | 〃 |

### 5.10 Infotainment 제어: 미디어·기타

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-090 | `volumeUp()`, `volumeDown()`, `setVolume(0..10)` | P1 | INFO | `VolumeUp/Down`, `SetVolume` | `05-command-catalog F` |
| FR-091 | `mediaNextTrack()`, `mediaPreviousTrack()`, `mediaNextFavorite()`, `mediaPreviousFavorite()`, `toggleMediaPlayback()` | P1 | INFO | `Media*` | 〃 |
| FR-092 | `scheduleSoftwareUpdate(delay)`, `cancelSoftwareUpdate()` | P1 | INFO | `Schedule/CancelSoftwareUpdate` | `05-command-catalog G` |
| FR-093 | `setVehicleName(name)` | P2 | INFO | `SetVehicleName` | `05-command-catalog H` |
| FR-094 | `getNearbyCharging()`: Go 원본은 응답 데이터를 버리고 오류만 반환. BLE로 데이터가 오는지 **미검증**. v1.1에서 응답 파싱 여부 결정 | P2 | INFO | `GetNearbyCharging` | `05-command-catalog I`, `04-go-api-reference infotainment.go` |

### 5.11 에러 모델과 명령 결과

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-100 | 모든 명령 결과는 **성공 / 실패 / 결과 불확실(PossibleSuccess)** 셋 중 하나. 응답 없이 타임아웃되면 불확실로 반환하고 자동 재전송하지 않음 | P0 | 둘 다 | `CommandError{MayHaveSucceeded}` | `08-errors §1, §7`, `00-agent-guide §3.3-22` |
| FR-101 | 재시도 정책: `Temporary`이고 `MayHaveSucceeded`가 아닌 오류만 1초 간격 재시도. 재시도는 새 counter·nonce·expires_at으로 재인가. 재시도 가능 fault: BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL, INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG, `WAIT`. `RESPONSE_MTU_EXCEEDED`는 실행됨 | P0 | 둘 다 | `ShouldRetry`, `retriableErrors` | `08-errors §4`, `10-porting-guide §8` |
| FR-102 | 결과 불확실 시 상태를 재조회할 수 있는 API 안내: VCSEC 명령은 `vehicleStatus()`, Infotainment는 `getState()` | P0 | 둘 다 | – | `08-errors §7` |
| FR-103 | 에러는 `sealed` 계층으로 정의하고 원인 코드를 노출: `MessageFault`(0~28), `OperationStatus`, `SessionInfoStatus`, VCSEC `GenericError`, `WhitelistOperation_information`(KeychainError), Infotainment `actionStatus.result_reason` 문자열, 전송·키스토어·권한 오류 | P0 | 둘 다 | `pkg/protocol/error.go` | `08-errors §1~§3` |
| FR-104 | `InsufficientPrivileges`, `KeyNotOnWhitelist`, `MaxConnectionsExceeded`, `VehicleAsleep`(Infotainment 응답 없음) 등 앱이 UI 분기에 쓰는 상황을 별도 타입으로 구분 | P0 | 둘 다 | sentinel errors | `08-errors §2, §8` |

### 5.12 샘플 앱과 로깅

| ID | 요구사항 | P | 도메인 | Go | 근거 |
|---|---|---|---|---|---|
| FR-110 | Android 샘플(Compose): VIN 입력, 스캔, 페어링(진행 상태), 키 목록, 잠금/해제/트렁크, VehicleStatus, 깨우기, 공조 on/off, 충전 시작/정지, GetState 1개 이상, 결과 불확실 표시, 디버그 로그 보기 | P0 | – | `examples/ble` | `06-cli-tools examples/`, `09-recipes R1, R4~R6` |
| FR-111 | iOS 샘플(SwiftUI): FR-110과 같은 흐름을 SKIE Swift API로 구현 | P0 | – | 〃 | 〃 |
| FR-112 | 디버그 로거 포트: TX/RX 헥사는 디버그 모드에서만, VIN 마스킹, 키·세션키·복호화 전 페이로드는 어떤 레벨에서도 출력하지 않음 | P0 | – | `internal/log`, `-debug` | `08-errors §9.1`, `10-porting-guide §12` |

### 5.13 HANDOFF §5 ↔ FR 추적 요약

| HANDOFF §5 항목 | FR |
|---|---|
| 스캔 | FR-001, FR-030 |
| VCSEC VehicleStatus | FR-031 |
| VCSEC Whitelist | FR-025, FR-032 |
| Infotainment GetState 12 | FR-033 |
| VCSEC 제어 14개 | FR-040~047, FR-022, FR-024~027 |
| Infotainment 차체 8개 | FR-050~054 |
| 공조 10개 | FR-060~069 |
| 충전 14개 | FR-070~083 |
| 보안·모드 | FR-084~089 (`SetPINToDrive` 제외) |
| 미디어·기타 | FR-090~094, FR-034 |

---

## 6. 비기능 요구사항 (NFR)

| ID | 요구사항 | 검증 | 근거 |
|---|---|---|---|
| NFR-001 | **TDD**: 모든 동작은 실패 테스트로 시작. `protocol.md` 테스트 벡터 전부(키 유도, 세션정보 키, TLV, HMAC 태그, GCM, 광고 이름)를 commonTest로 옮김. Go 클라이언트 측 `*_test.go` 케이스 포팅. **GCM 벡터는 protocol.md 원문(FLAGS=2 포함, 태그 `c228e0ff…`)을 정본으로 하고, PoC의 flags=0 케이스는 FLAGS 생략 규칙 검증용으로 유지** | `jvmTest`, `iosSimulatorArm64Test` | `10-porting-guide §0.3`, `03-protocol §6`, `{{HANDOFF_DIR}}2026-09-26-phase0-setup.md` |
| NFR-002 | **골든 테스트**: `tesla-control -ble -debug`로 수집한 TX/RX 헥사(VIN 마스킹)를 픽스처로 인코딩·디코딩 검증. 랜덤 필드(uuid, routing_address, nonce)는 주입해 고정 | commonTest | `10-porting-guide §11`, `08-errors §9.2` |
| NFR-003 | **FakeVehicle**: `verifier.go`, `dispatcher_test.go`를 참고해 차량 측 핸드셰이크·응답을 결정적으로 재현. 시나리오: VCSEC 다중 응답(WAIT→최종), 응답 유실, epoch 변경, clock 역행, 잘못된 HMAC, 재전송 응답, 슬롯 초과. 시간은 `Clock` 포트로 주입, 실제 sleep 없음 | commonTest | `09-recipes R15`, `{{WORKFLOW_FILE}}` §2.4 |
| NFR-004 | **키 보안**: 개인키는 하드웨어 우선 생성, 내보내기 API 없음(D10). 세션 키는 메모리에만. 캐시에는 세션정보만 | 코드 리뷰, 테스트 | `10-porting-guide §12`, `00-agent-guide §3.3-21` |
| NFR-005 | **암호 원시연산은 플랫폼 제공만** (JCA/Keystore, Security.framework/CommonCrypto 또는 ADR로 정한 GCM 공급자). 자체 구현 금지. HMAC 비교는 상수 시간 | 코드 리뷰 | `00-agent-guide §3.1-4` |
| NFR-006 | **로그**: FR-112. 릴리스 빌드 기본 로거는 no-op | 테스트 | `08-errors §9.1` |
| NFR-007 | **결과 불확실 명령은 자동 재전송 금지** (토글성 명령 이중 실행 방지) | FakeVehicle 테스트 | `08-errors §7` |
| NFR-008 | **라이선스**: Apache-2.0, `NOTICE`에 원본 저작권 고지, 포팅 파일 헤더 `// Ported from vehicle-command@a4b43c1 <경로> (Apache-2.0)`. AGPL 코드 유입 없음. 의존성(Wire, Kable, SKIE, kotlinx)은 모두 Apache-2.0 | CI 라이선스 게이트 | `{{WORKFLOW_FILE}}` §4.4, §6 |
| NFR-009 | **플랫폼**: Android `minSdk 31`, iOS 16.0+ (D9). Kotlin/Native 타깃: `iosArm64`, `iosSimulatorArm64` | 빌드 | `10-porting-guide §12` |
| NFR-010 | **API 안정성**: `explicitApi()`, 공개 API KDoc 필수, `binary-compatibility-validator`(`apiCheck`), `ByteArray` 방어 복사, 값 객체 불변 | CI | `{{WORKFLOW_FILE}}` §4.4 |
| NFR-011 | **지연**: 캐시된 세션으로 잠금/해제 왕복 1초 안팎(실차). 앱 재실행 후 첫 명령은 핸드셰이크 생략 | 실차 체크리스트 | `{{WORKFLOW_FILE}}` §8.4 |
| NFR-012 | **동시성**: 구조화된 동시성, 모든 호출 취소 가능, VCSEC 직렬화(FR-049), 타임아웃은 호출자가 `withTimeout`으로 제어. `GlobalScope`, `runBlocking` 금지 | 코드 리뷰, 테스트 | `{{WORKFLOW_FILE}}` §4.4, `01-architecture §5` |
| NFR-013 | **i18n**: 에러 메시지·로그 영어, 코드 노출(D19). 문서 한국어 | 리뷰 | – |
| NFR-014 | **서버 없음**: 네트워크 권한·호출 없음. 새 네트워크 호출 PR은 반려 | 코드 리뷰, Android 매니페스트에 `INTERNET` 없음 | HANDOFF D1 |
| NFR-015 | **CI 게이트**: `{{WORKFLOW_FILE}}` §6 전부 (ktlint, detekt 0, 경고 0, apiCheck, jvmTest, Android 단위, iOS 시뮬레이터, 샘플 빌드, 라이선스, 비밀 스캔) | GitHub Actions | `{{WORKFLOW_FILE}}` §6 |
| NFR-016 | **아키텍처 경계**: `:domain`은 Wire 런타임·coroutines만 의존. 위반은 빌드가 잡음 | Gradle | `{{WORKFLOW_FILE}}` §4.2 |
| NFR-017 | **원본 충실**: 바이트 수준 동작(TLV 순서, FLAGS 포함 규칙, request hash 절단, counter 규칙)은 원본 코드 줄을 인용한 테스트로 고정. 의도적 차이는 주석 + ADR | 리뷰 | `{{WORKFLOW_FILE}}` §8.1 |

---

## 7. 제약과 의존성

| 구분 | 내용 | 근거 |
|---|---|---|
| iOS 암호 | Kotlin/Native는 CryptoKit(Swift 전용) 호출 불가. 키·ECDH는 Security.framework(`SecKeyCreateRandomKey` + Secure Enclave, `SecKeyCopyKeyExchangeResult`). AES-GCM은 CommonCrypto 공개 API 없음 → SDD에서 (a) Swift CryptoKit 주입 / (b) cryptography-kotlin Apple 프로바이더 중 ADR로 결정 | HANDOFF §6.3 |
| Android 암호 | Keystore `KeyAgreement`(API 31+), AES-GCM은 JCA. StrongBox는 있으면 사용 | HANDOFF §6.3 |
| BLE | Kable(JuulLabs). indication 구독과 MTU 처리를 M3 초기에 실기기로 확인. iOS 백그라운드에서는 Local Name 광고가 제한될 수 있음 → v1은 포그라운드 전제 | HANDOFF §6.2, `10-porting-guide §12` |
| Protobuf | Wire(Square). `pkg/protocol/protobuf/*.proto` 9개를 수정 없이 입력. `google.protobuf.Timestamp` 사용 | HANDOFF §6.2 |
| iOS 노출 | SKIE(Touchlab). Kotlin 버전과 호환 범위를 SDD에서 고정 | D12 |
| 차종 | 2021년 이후 차량. VCSEC 동시 연결 3개(키포브·폰키 공유) | `02-ble-transport §11` |
| 라이선스 | 원본 Apache-2.0. `shoujiaxin/swift-tesla-ble`(MIT)는 참고 시 고지. `yoziru/tesla-ble`(AGPL)는 열람 금지 | HANDOFF §6.5 |
| 툴체인 | 개발 머신: Java 25, Xcode 26.6, Android SDK. `go`/`protoc` 없음(Wire는 protoc 불필요). Gradle 래퍼 사용 | Phase 0 |
| 실차 | 실차 테스트는 사용자가 수행. 테스트 키(`protocol.md`)는 실차에 등록 금지 | `00-agent-guide §3.1-11` |

---

## 8. 리스크와 대응

| 리스크 | 영향 | 대응 |
|---|---|---|
| 펌웨어 변경으로 프로토콜·응답 형식 변화 | 명령 실패, 파싱 오류 | 원본 커밋 고정. 골든 픽스처 재수집 절차를 `troubleshooting.md`에 문서화. 알 수 없는 enum은 실패가 아닌 `Unknown`으로 파싱 |
| BLE 불안정(연결 끊김, indication 유실) | 결과 불확실 증가 | FR-100~102: 불확실 결과 + 재조회 API. 청크 1초 타임아웃, 연결 재시도 |
| 결과 불확실 명령의 이중 실행 | 토글 명령(트렁크) 오작동 | NFR-007 자동 재전송 금지. 문서에 재조회 후 재시도 안내 |
| 슬롯 3개 초과(키포브·폰키) | 연결 불가 | `MaxConnectionsExceeded` 별도 타입(FR-104). 미사용 시 연결 해제 권장(FR-005) |
| Kable이 iOS indication/MTU를 기대대로 처리하지 못함 | M3 지연 | M3 첫 작업을 실기기 스파이크로 배치. 실패 시 `Transport` 포트 뒤에서 CoreBluetooth 직접 구현으로 대체(ADR) |
| iOS AES-GCM 공급자 선택 오류 | 암호 불일치 | 후보 두 가지 모두 `protocol.md` GCM 벡터로 검증한 뒤 ADR 확정 |
| Android Keystore ECDH가 일부 기기에서 공유 비밀을 다르게 반환 | 세션 키 불일치 | 테스트 벡터 개인키는 Keystore에 넣을 수 없으므로, 소프트웨어 키 경로로 벡터 검증 + 실기기에서 Keystore 경로 상호 검증(같은 공개키 → 같은 K) |
| Secure Enclave·StrongBox 미지원 기기 | 키 생성 실패 | D10 소프트웨어 대체 + 보관 수준 조회 |
| 페어링 직후 Infotainment 미동기화 | 첫 Infotainment 명령 실패 | FR-024 등록 확인 API + 샘플 앱 대기 UI |
| PoC 벡터가 스펙 예제와 다름 | 잘못된 벡터 이관 | NFR-001에 정본 명시 (Phase 0 인계 노트) |
| 매뉴얼과 원본 불일치 | 잘못된 구현 | 원본 우선, 발견 시 사용자 보고(현재까지 매뉴얼 불일치 없음. HANDOFF §5와 원본 불일치 2건은 §3.2·FR-094에 반영) |

---

## 9. 릴리스 계획 (`{{HANDOFF_FILE}}` §12 연계)

| M | 내용 | 포함 FR/NFR | 완료 기준 |
|---|---|---|---|
| M0 | 프로젝트 골격(모듈 5개 + 샘플 2개), Wire 코드 생성, PoC 이관, CI | NFR-001(벡터), NFR-008, NFR-010, NFR-015, NFR-016 | 테스트 벡터 전부 PASS (JVM, iOS 시뮬레이터) |
| M1 | protocol 전체: Metadata, Signer(암호화·복호화), 세션정보 검증, SlidingWindow, 에러 분류 | FR-013~016, FR-019, FR-103, NFR-017 | Go 클라이언트 측 테스트 포팅본 PASS |
| M2 | dispatcher + FakeVehicle: 매칭, 핸드셰이크, VCSEC 직렬화·다중 응답, 재시도, 세션 캐시·복구 | FR-010~012, FR-017~018, FR-048~049, FR-100~102, NFR-003, NFR-007, NFR-012 | 시나리오 테스트 전부 PASS |
| M3 | transport(Kable) + keystore + 샘플 앱 골격 | FR-001~006, FR-020~021, FR-025, FR-028, FR-031, FR-112, NFR-004~006, NFR-009 | 샘플 앱에서 스캔, 연결, 키 목록, VehicleStatus (실차는 사용자 확인) |
| M4 | 키 등록 + VCSEC 제어 | FR-022~024, FR-026~027, FR-040~047, FR-104, FR-110~111(VCSEC 부분) | 페어링, 잠금, 해제, 트렁크 (실차는 사용자 확인) |
| M5 | Infotainment 제어 + GetState (P0/P1) | FR-033~034, FR-050~053, FR-060~085, FR-090~092, FR-110~111(전체) | P0/P1 항목 완료, 실차 체크리스트 전달 |
| M6 | 문서화, 배포 준비 | `{{LIB_DOCS_DIR}}` 완성, API 문서, NOTICE, mavenLocal/XCFramework 빌드. **마지막 과제**: GitHub Packages + SPM 공개(D13), 소비자용 PAT 설정 문서 | 문서 링크 검증, 배포 설정 |
| v1.1 | P2 기능 | FR-054, FR-086~089, FR-093~094 | – |

---

## 10. 라이브러리 사용 문서 요구사항 (`{{HANDOFF_FILE}}` §13)

위치는 `{{LIB_DOCS_DIR}}`, 목차는 `{{LIB_DOCS_INDEX}}`. 내부 링크는 상대경로만. 공개 API를 바꾸는 PR은 해당 페이지를 함께 갱신한다(리뷰 체크 항목).

| 페이지 | 내용 | 갱신 시점 |
|---|---|---|
| `README.md` | 목차, 지원 플랫폼(D9), 한 줄 설치 | M0 |
| `getting-started.md` | 의존성 추가(mavenLocal/XCFramework, M6 이후 GitHub Packages + PAT 설정 / SPM), 권한 설정(Android 12+ BLE, iOS Info.plist), 첫 페어링 코드 | M3, M4 |
| `pairing.md` | 키 생성·보관 수준(D10), add-key-request 흐름과 NFC 안내, 역할(D11), 등록 확인, 키 목록·삭제 | M4 |
| `reading-state.md` | `vehicleStatus`(인증 불필요) vs `getState`(깨우기 필요), 12개 카테고리 필드 표, 깨우기 정책 | M3, M5 |
| `commands.md` | 제어 API 전체 표: 메서드 ↔ FR ↔ 도메인 ↔ 인증 ↔ 우선순위 | M4, M5 |
| `errors.md` | 성공/실패/불확실 3분류, sealed 에러 계층, 코드 표, 재시도 정책, 재조회 안내 | M2, M5 |
| `platform-notes.md` | iOS(Security.framework, GCM 공급자, 백그라운드 제한, SKIE 사용법), Android(Keystore, 권한), 세션 캐시 저장 위치(D18) | M3 |
| `troubleshooting.md` | 슬롯 초과, 세션 오류, 페어링 직후 동기화, 디버그 로그 수집, 골든 픽스처 재수집 | M5 |
| `architecture.md` | 기여자용 SDD 요약, 모듈 의존 방향, Go 원본 대응표 | M2, M6 |

---

## 부록 A. 매뉴얼·HANDOFF 불일치 보고 (Phase 1까지)

| # | 종류 | 내용 | 처리 |
|---|---|---|---|
| 1 | PoC ↔ protocol.md | PoC 테스트 항목 5·6의 GCM 메타데이터·태그가 `protocol.md` 예제(FLAGS=2, 태그 `c228e0ff…`)와 다름. 매뉴얼(`10-porting-guide §0.3`)과 원본은 일치 | NFR-001에 정본 명시. PoC 파일은 수정하지 않음 |
| 2 | HANDOFF §5.2 ↔ 원본 | `SetPINToDrive`는 BLE에서 `ErrRequiresEncryption`으로 전송 불가 (`pkg/vehicle/security.go`) | §3.2 비범위로 이동. `ClearPINToDrive`만 FR-086 |
| 3 | HANDOFF §5.2 ↔ 원본 | `GetNearbyCharging`은 Go가 응답 데이터를 버리고 오류만 반환 (`pkg/vehicle/infotainment.go`) | FR-094 P2, 응답 파싱 여부는 v1.1에서 결정 |
| 4 | HANDOFF §4.4 ↔ 원본 | HANDOFF는 "응답이 최대 3개까지 옴"이라 하고, Go `SendAddKeyRequest`는 응답을 **읽지 않음**. 라이브러리는 진행 상태를 위해 읽기로 함 | FR-023 P1, 원본과 다른 점으로 SDD·ADR에 기록 |

매뉴얼(`{{MANUAL_DIR}}`)과 원본 Go 코드 사이의 불일치는 Phase 1까지 발견되지 않았다.
