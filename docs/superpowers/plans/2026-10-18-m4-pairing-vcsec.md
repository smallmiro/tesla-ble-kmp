# M4 키 등록·VCSEC 제어 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 먼저 차량이 보내는 비요청(푸시) 메시지를 **동작 변경 없이** 관찰 로그로 남겨 M5 `vehicle.events` 설계의 근거를 모으고, 그다음 M3 파사드 위에 키 등록(`addKeyRequest` 진행 이벤트, `sessionInfo`, `addKey`/`removeKey`)과 VCSEC 제어 12종(`lock`·`unlock`·`wakeUp`·트렁크·프렁크·토노 등)을 붙여 **iPhone 샘플 앱에서 실차로 페어링·잠금·해제·트렁크를 확인한다**(PRD §9 M4 완료 기준; 실차 확인은 사용자).

**Architecture:** `{{SDD_FILE}}` §2.2(`KeyManagement`, `Pairing`), §2.3(`Vehicle` M4 메서드), §3.3(VCSEC 명령 경로), §4(키 등록 시퀀스, D28)를 그대로 따른다. `:application`에 순수 페이로드 빌더 `VcsecPayloads`, 명령 계층 `VcsecControl`·`KeyManagement`·`Pairing`을 더하고 `VehicleSession`이 연결마다 하나씩 들고 있는다. 모든 VCSEC 명령은 M2 `VcsecCommands.execute`(연결당 직렬화, 재시도, D29 타임아웃)를 지난다. `Dispatcher`에는 (Task 1) 버리는 메시지의 관찰 로그와 (Task 6) RoutableMessage로 감싸지 않은 프레임의 탭(`unroutedFrames`)·직접 전송(`sendUnrouted`)만 더한다 — 드롭 규칙과 응답 매칭은 바꾸지 않는다. `:domain`에는 공개 값 객체 `SessionInfoSummary`, `PairingEvent`와 오류 타입 두 개가 늘고, `:sdk`의 `Vehicle`/`DefaultVehicle`이 이들을 노출한다.

**Tech Stack:** Kotlin 2.4.20 (K2, `explicitApi`), Gradle 9.6.0 + JDK 17, kotlinx-coroutines 1.11.0, Wire 7.0.4(`ProtoReader`), okio, Kable 0.45.0, SKIE 0.10.15(`:sdk`), SwiftUI·Jetpack Compose 샘플, xcodegen, XCTest(샘플 단위 테스트 — Task 8).

**Spec:** `{{SDD_FILE}}` §2.1~§2.3, §3.3, §4, §5, §6, §9.3, §9.6, §10, §12; `{{PRD_FILE}}` FR-022~FR-024, FR-026~FR-027, FR-040~FR-047, FR-102, FR-104, FR-110~FR-111(VCSEC 부분), FR-112, NFR-002, NFR-003, NFR-006, NFR-007; ADR-0006(결과 모델), ADR-0009(add-key-request 응답 읽기), ADR-0010(타임아웃), ADR-0005(SKIE). 참고 매뉴얼: `{{MANUAL_DIR}}05-command-catalog.md` A·B·C, `03-protocol.md` §11·§12, `10-porting-guide.md` §10, `09-recipes.md` R4, `08-errors.md` §3.1·§3.7·§8, `02-ble-transport.md`, `01-architecture.md`. 로드맵: `{{PLANS_DIR}}2026-09-26-roadmap.md` M4 행. 직전 인계: `{{HANDOFF_DIR}}2026-10-18-m3-transport-keystore.md`("막힌 점"), `{{HANDOFF_DIR}}2026-10-16-m3-rulings.md`, **`{{HANDOFF_DIR}}2026-10-18-m4-push-observation.md`(Task 1의 범위·사실·M5 후보)**.

**M3까지 이미 있는 것 (다시 만들지 않는다):** `TeslaBle`/`TeslaBleConfig`/`Vehicle`/`DefaultVehicle`/`SessionStatus`, `VehicleSession`(`connect`/`startSession`/`disconnect`, `info`, `vcsec`, `send`, `infotainment`), `VcsecCommands.execute`(직렬화 `Mutex`)·`VcsecResponses`(`FIRST_MESSAGE`·`COMMAND_STATUS_ABSENT`·`WHITELIST_OPERATION_COMPLETE`, `interpret`, `readUntil`), `VehicleInfoQueries`(`vehicleStatus`·`keySummary`·`keyInfoBySlot`·`listKeys`), `VcsecInfoMapping`(Wire → `VehicleStatus`·`WhitelistEntry`, `internal`), `Dispatcher`(`send`·`requestSessionInfo`·`sessionEvents`·드롭 규칙), `SessionState`(`isUnconfirmedCacheRestore`·`isReportedStale`·영구 닫힘), `SessionCacheSync`(`markStale`), `KeyRole`/`KeyFormFactor`/`WhitelistEntry`, `VehicleError.*`(`KeychainRejected`, `UnknownKeychainCode`, `VcsecRejected`, …), `FakeVehicle`(`vcsecBusy`·`vcsecAuthSuccess`·`vcsecWhitelistStatus`·`vcsecNominalError`·`vcsecPayload`·`script`·`sleep`), `FakeTransport.deliver`, `RecordingLogger`, `RecordingSessionCache`, `TestCrypto`, `dispatcherHarness`/`manualHandshake`(`application` commonTest), `DebugLogger`, 샘플 앱 2종(스캔·연결·키 목록·VehicleStatus·결과 전체 복사).

**실차 환경:** M3와 같다 — iPhone 16 Pro + Model Y. Android는 에뮬레이터·단위 테스트로만 검증하고 Android 실기기는 "미확인"으로 남긴다. **Go `tesla-control`은 이 Mac에 없다**(골든 픽스처는 결정 항목 D-5).

## 설계 구체화 (승인 필요)

SDD·ADR이 비워 두었거나 M3 인계 노트·푸시 관찰 인계 노트가 "M4 계획에서 정한다"고 남긴 항목이다. 계획 승인과 함께 승인된다. 원본과 다른 동작은 구현 Task가 `{{SDD_FILE}}` §12 "원본과 다른 동작 (의도)"에 그 PR에서 바로 기록한다.

1. **차량 푸시 관찰 로그(Task 1, 동작 변경 없음).** `Dispatcher`가 메시지를 버리는 다섯 지점(해석 불가, 출발지 없음, 목적지 없음, 목적지가 도메인, 받을 핸들러 없음)과 열린 VCSEC 요청에 배달하는 지점에서 **DEBUG 레벨, 태그 `observe`**로 한 줄 요약을 남긴다. 공개 API·생성자 인자·`apiDump` 변화가 없도록 **항상 켜져 있고**, 로거가 DEBUG를 버리면(기본 `NoOp`) 람다가 평가되지 않아 비용이 없다. 형식은 `[<지점>] <바이트>B from=<출발지> to=<목적지> request_uuid=<empty|16B> [status=…] [session_info=NB] payload=<요약>`이고 `<요약>`은 `FromVCSECMessage{…}`(oneof 종류, `vehicleStatus`는 잠금·수면·탑승자·문 8개·`tonneauPercentOpen`과 `absent=[빠진 메시지 필드]`), 암호문은 `encrypted(NB)`, 모르는 필드는 `unknownFields=[필드 번호]`, 공개키는 `sha1:<앞 4바이트>`만. 출발지가 없거나 RoutableMessage로 해석되지 않는 프레임은 같은 바이트를 `FromVCSECMessage`로 다시 해석해 `reinterpreted=…`로 덧붙인다. 목적지가 라우팅 주소면 `address(16B, self|open-request|other)` — `self`는 이 디스패처의 Infotainment 주소, `open-request`는 닫히지 않은 요청의 주소다. BLE inbox 넘침은 연결당 횟수를 세어 기존 ERROR 줄에 `(dropped N since connect)`를 붙이고, 닫을 때 한 번 WARN 요약을 남긴다. Go는 이 메시지들을 조용히 버린다(`internal/dispatcher/dispatcher.go:269-293`, `pkg/connector/ble/ble.go:78-82`). **NFR 연결은 결정 항목 D-1**(추천: NFR-006 로그 + NFR-003 FakeVehicle 시나리오, 새 FR은 M5).
2. **오래된 캐시 세션의 처리(M3 "막힌 점").** 캐시 복원 후 미확인 세션이 세션정보 갱신에 실패해 `StaleCache`로 판정되면, 그 `SessionState`를 **무효화**한다 — 세션 키를 지우고 준비 신호를 되돌려 `Dispatcher.send`가 `Failure(NoSession)`을 돌려주게 하고, `Vehicle.sessions`의 그 도메인을 `None`으로 바꾼다. 앱이 `startSession()`을 다시 부르면 새로 핸드셰이크한다(Go는 같은 연결에서 회복하지 못하고 시간 초과까지 재시도한다 — §12 기록). 새 핸드셰이크가 성공하면 그 도메인은 다시 캐시에 저장된다(`SessionCacheSync.markFresh`). `SessionState.close()` 뒤에는 `isReady == false`이고 `HandshakeFlow.startSession`은 `Failure(NotConnected)`다(M3 최종 리뷰 "disconnect 뒤 startSession이 캐시 세션에 Success").
3. **`sessionInfo(publicKey, domain)`(FR-024).** Go `Vehicle.SessionInfo`(`pkg/vehicle/vehicle.go:120-142`)처럼 인증 없이 `SessionInfoRequest`를 한 번 보내고 첫 응답을 해석한다(재전송 재시도 없음 — Go와 같음, 전송 오류 재시도는 `Dispatcher.send`가 한다). 성공은 `status == OK`뿐이고(`KEY_NOT_ON_WHITELIST`·`UNKNOWN_KEY_ID`는 `Failure(KeyNotPaired)`), 값은 수기 값 객체 `SessionInfoSummary(counter: UInt, clockTime: UInt, vehiclePublicKey: PublicKeyBytes?)`. 응답 대기 중 시간 초과는 M3 조회와 같이 `Uncertain(Timeout(afterSend = true))`. **이 연결의 키가 아닌 공개키**로 물으면 응답의 세션정보로 이 연결의 세션을 갱신하지 않는다(`PendingRequest.acceptsSessionInfo = false`) — 그러지 않으면 태그 불일치가 캐시 복원 세션을 "오래됨"으로 잘못 판정한다(Go는 갱신을 시도하고 경고만 남긴다 — §12 기록). 이 연결의 키로 물으면 Go처럼 세션이 만들어지고(`Updated`), `Vehicle.sessions`도 `Ready(fromCache = false)`가 된다.
4. **`addKeyRequest`(FR-022, FR-023, ADR-0009).** 공개 시그니처는 `addKeyRequest(publicKey: PublicKeyBytes, role: KeyRole = OWNER, formFactor: KeyFormFactor = platformKeyFormFactor(), timeout: Duration? = null): Flow<PairingEvent>` — **공개키를 명시적으로 받는다**(Go `SendAddKeyRequestWithRole`과 같음, 키 없이 연결해도 쓸 수 있다; SDD §2.3의 인자 없는 형태를 바꾼다). 차가운 Flow라 수집할 때마다 한 번 보낸다. 순서: `Dispatcher.unroutedFrames` 구독(먼저) → VCSEC 직렬화 락 안에서 `Dispatcher.sendUnrouted(ToVCSECMessage{PRESENT_KEY})`만 하고 락을 놓음 → `Sent` → `timeout`(기본 `TeslaBleConfig.pairingTimeout` 60초) 동안 감싸지 않은 프레임을 `FromVCSECMessage`로 해석해 이벤트로 바꾼다. 이벤트: `Sent`, `WaitingForTap`(`commandStatus.operationStatus == WAIT`), `Accepted`(`whitelistOperationStatus` 있고 `NONE`), `Rejected(error)`(`KeychainRejected`/`UnknownKeychainCode`/`VcsecRejected`/`UnknownVcsecError`), `Unknown(raw)`(해석 불가 프레임), `Failed(error)`(전송 실패·연결 끊김·`role == UNKNOWN`), `TimedOut`. 끝 이벤트(`Accepted`·`Rejected`·`Failed`·`TimedOut`) 뒤 Flow가 완료된다. 링크가 끊기면 기다리지 않고 바로 `Failed`(이유가 있으면 그 `TransportError`, 없으면 `NotConnected`). 같은 연결의 페어링 요청끼리는 전송부터 끝 이벤트까지 직렬화한다(같은 응답을 두 요청이 나눠 갖지 않게). **`commandStatus`·`nominalError`가 없는 프레임은 무시한다** — 연결 직후 약 1.1초마다 오는 `FromVCSECMessage{authenticationRequest}`(공개 proto에 없음)와 VCSEC 브로드캐스트가 페어링 이벤트로 새지 않게(푸시 관찰 인계 노트의 사실). 등록 확정은 Go처럼 `sessionInfo(publicKey, INFOTAINMENT)` 폴링이다(앱 몫, 샘플이 시연).
5. **`addKey`/`removeKey`(FR-026, FR-027).** Go `AddKeyWithRole`/`RemoveKey`(`pkg/vehicle/security.go:256-282`): GCM 인증 + `WHITELIST_OPERATION_COMPLETE`. `role == KeyRole.UNKNOWN`은 보내지 않고 `Failure(InvalidArgument)`(Wire 값이 없다). 그 밖의 역할 검사는 차량에 맡긴다(Go와 같음).
6. **VCSEC 제어 12종(FR-040~FR-047).** Go `executeRKEAction`/`executeClosureAction`(`pkg/vehicle/vcsec.go:174-248`, `actions.go`): GCM 인증 + `COMMAND_STATUS_ABSENT`. `openTrunk`와 `actuateTrunk`는 같은 페이로드(`rearTrunk = MOVE`, 토글). `wakeUp`은 `RKE_ACTION_WAKE_VEHICLE`(VCSEC 세션만 필요). 명령은 핸드셰이크를 대신 해 주지 않는다 — 세션이 없으면 `Failure(NoSession)`(Go와 같음). **알려진 위험(M5로 넘김, 사용자 결정 2026-10-18):** 열린 요청의 주소로 브로드캐스트가 오면 `COMMAND_STATUS_ABSENT`가 그것을 성공으로 볼 수 있다. Task 1 관찰 로그(`[reply:vcsec]`)가 실차에서 이것이 일어나는지 보여 준다.
7. **FR-104 오류 타입.** `VehicleError.InsufficientPrivileges`(`MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES`, message는 Go처럼 fault 이름)를 `KeyNotPaired`와 같은 방식으로 분리한다. `KeyNotOnWhitelist`는 이미 `KeyNotPaired`, `MaxConnectionsExceeded`는 이미 있다. `VehicleAsleep`(Infotainment 무응답)은 Infotainment 명령과 함께 **M5**로 넘긴다. M3 인계 L58: 모르는 `nominalError.genericError` 코드는 `VehicleError.UnknownVcsecError(rawCode)`로 코드를 보존한다(`UnknownFault`·`UnknownKeychainCode`와 같은 방식).
8. **Swift 시간 제한(M3 "막힌 점" I4) — 결정 항목 D-2.** 추천안: Kotlin 시그니처(`timeout: Duration? = null`)는 그대로 두고, `:sdk`에 Swift용 도우미 `Timeouts.seconds(_: Double): Duration?`(반환형이 nullable이라 Swift의 `id` 인자로 박싱된 채 그대로 넘어간다)와 새 팩토리 `TeslaBleConfig.withSeconds(logger:, commandTimeoutSeconds:, handshakeTimeoutSeconds:, connectTimeoutSeconds:, pairingTimeoutSeconds:)`를 더한다(기존 `default(logger:)`는 유지 — Swift에는 기본 인자 오버로드가 없어 인자를 늘리면 기존 호출이 깨진다). 박싱 왕복은 샘플 프로젝트의 XCTest 타깃이 시뮬레이터에서 확인하고, `tools/ci/verify.sh` (d) 단계가 `xcodebuild build test`를 돌린다.
9. **FR-102 재조회 안내.** 새 API 없이 KDoc·문서·샘플로 한다: 모든 VCSEC 제어 메서드 KDoc에 "`Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인", 샘플은 `Uncertain`을 받으면 `vehicleStatus()`를 자동으로 한 번 불러 잠금·트렁크 상태를 함께 보여 준다.
10. **기본 키 형태.** `:sdk`의 `expect fun platformKeyFormFactor(): KeyFormFactor` — Android `ANDROID_DEVICE`, iOS `IOS_DEVICE`, JVM `CLOUD_KEY`(Go 레시피 R4의 예와 같음, JVM은 테스트·실험용).
11. **문서는 PR마다(AGENTS.md 절대 규칙 13).** 공개 표면(`:sdk`·`:domain`)이 바뀌는 PR은 그 PR에서 `{{LIB_DOCS_DIR}}` MD와 `{{LIB_DOCS_HTML_DIR}}` ko/en/ja를 함께 고친다(영어판 먼저, ko/ja는 같은 마크업으로 번역 — 서브에이전트에 맡겨도 된다). `:application`만 바뀌는 PR은 본문에 "문서 영향 없음 — 파사드 미노출"을 적는다.
12. **범위 밖(그대로 남김):** `SecureEnclaveKey`의 `SecKeyRef` 캐시, `KableErrors`의 `authoriz` 정규식, `listKeys` 기본 예산, Android BCV 덤프, Android 실기기 확인, #71 "closures 부재 = 모두 닫힘" 규칙, `vehicle.events`·`VehicleEvent`, 푸시로 `Uncertain` 확인, 응답·푸시 구분(M5 후보 A·B·C·F).

## Global Constraints

- 패키지 루트 `dev.mirolab.teslable`. `:application` → `…application.*`(새 하위 패키지 `observe`, `pairing`), `:sdk` → `dev.mirolab.teslable`, `:domain` 값 객체 → `dev.mirolab.teslable.model`. Wire 생성 코드는 `com.tesla.generated.*` 그대로.
- 타깃: `jvm()`, Android(`minSdk 31`, `compileSdk 37`), `iosArm64()`, `iosSimulatorArm64()`, iOS 배포 타깃 16.0.
- 모든 라이브러리 모듈은 `explicitApi()`, `allWarningsAsErrors = true`, 공개 API에 한국어 한 줄 이상 KDoc(detekt `UndocumentedPublic*`). `MaxLineLength` 140.
- 의존 방향(ADR-0001)은 바꾸지 않는다: `:application`·`:adapter-*` → `:domain`만, `:sdk` → 전부, `samples/*` → `:sdk`만, `:testing`은 테스트 소스셋에서만.
- 금지: `!!`, `lateinit`(테스트 제외), `GlobalScope`, `runBlocking`(테스트 제외), `println`, 자체 구현 암호, `INTERNET` 권한·네트워크 호출, 개인키 내보내기 API.
- 예외는 `CancellationException`과 프로그래밍 오류(`IllegalArgumentException`/`IllegalStateException`)에만(ADR-0006). 취소는 값으로 바꾸지 않는다(ADR-0010).
- 로그(FR-112, SDD §10, 절대 규칙 8): VIN은 `Vin.toString()`(마스킹)만, TX/RX 헥사는 DEBUG에서만(기존 `BleTransport`), **관찰 로그에는 암호문 바이트·세션정보 바이트·공개키 전체를 넣지 않는다**(길이와 `sha1:` 앞 4바이트만). 개인키·공유 X·K·복호화한 평문은 어떤 레벨에서도 금지.
- 포팅 파일 헤더 1행: `// Ported from vehicle-command@a4b43c1 <원본 경로> (Apache-2.0)`(심볼 목록은 2행). 새 포팅 파일은 `tools/ci/ported-files.txt`에 추가. 원본에 대응이 없는 새 파일(관찰 로그, 페어링 이벤트 해석)은 헤더 없이 KDoc 첫 줄에 "원본에 없음"을 적는다. 테스트 VIN은 `5YJ30123456789ABC`, `5YJS0000000000000`, `0123456789ABCDEFG`만.
- 커밋 접두어 `struct:`/`feat:`/`fix:`/`test:`/`docs:`/`chore:`, 구조 변경 커밋 먼저(Tidy First). `Refs:` 줄은 trailer 블록 안(`Co-Authored-By` 바로 위). 각 Task: 브랜치 `m4/<slug>`(최신 `origin/main`에서) → PR → **`tools/ci/verify.sh` 로컬 통과**(ADR-0012) → AI 리뷰(요약을 PR 본문에) → **rebase merge**. PR 본문 "원본 대응"에 참고한 매뉴얼 절과 Go 파일·줄.
- 공개 API가 바뀌는 Task는 `./gradlew :<module>:apiDump`(단독 실행)로 `<module>/api/*`를 갱신해 같은 커밋에 넣는다. **Task 1은 `apiDump` 변화가 없어야 한다**(`apiCheck`가 확인).
- `.proto`·`{{REF_REPO_DIR}}`는 읽기 전용, Go 참고 문서(`{{MANUAL_DIR}}` 00~10, `{{MANUAL_HTML_DIR}}` 01~09)는 수정 금지. AGPL 저장소(`yoziru/tesla-ble`, `yoziru/esphome-tesla-ble`, `dzid26/TeslaBatteryBLE`, `0Bu/tesla-key-esp32`)는 열지 않는다.
- 테스트 시간은 `runTest` 가상 시간만. 실제 sleep 금지.

## 빌드 사실 (구현자가 알아야 할 것)

- `./gradlew :<module>:apiDump`는 단독으로 실행한다(test/check와 섞지 않는다).
- Wire 이름: `UnsignedMessage(VCSEC_InformationRequest, RKEAction: RKEAction_E?, closureMoveRequest: ClosureMoveRequest?, VCSEC_WhitelistOperation: WhitelistOperation?)`(oneof 필드 번호 1·2·4·16), `ClosureMoveRequest(frontDriverDoor=1 … rearTrunk=5, frontTrunk=6, chargePort=7, tonneau=8)`, `ClosureMoveType_E.CLOSURE_MOVE_TYPE_{NONE,MOVE,STOP,OPEN,CLOSE}`(0~4), `RKEAction_E.RKE_ACTION_{UNLOCK(0),LOCK(1),REMOTE_DRIVE(20),AUTO_SECURE_VEHICLE(29),WAKE_VEHICLE(30)}`, `WhitelistOperation(removePublicKeyFromWhitelist=2, addKeyToWhitelistAndAddPermissions=5, metadataForKey=6)`, `PermissionChange(key=1, keyRole=4)`, `PublicKey(PublicKeyRaw)`, `KeyMetadata(keyFormFactor)`, `ToVCSECMessage(signedMessage=1)`, `SignedMessage(protobufMessageAsBytes=2, signatureType=3)`, `SignatureType.SIGNATURE_TYPE_PRESENT_KEY(2)`, `signatures.SessionInfo(counter, publicKey, epoch, clock_time, status, handle)`, `SignatureData(AES_GCM_Response_data, session_info_tag, …)`, `MessageStatus(operation_status, signed_message_fault)`, `NominalError(genericError=1)`, `WhitelistEntryInfo(keyId: KeyIdentifier?(publicKeySHA1), publicKey, metadataForKey, slot, keyRole)`, `carserver.server.Response(actionStatus, vehicleData, getSessionInfoResponse, getNearbyChargingSites, ping)`.
- proto3 oneof의 enum 0값(`RKE_ACTION_UNLOCK`)도 Wire는 필드를 싣는다(`1000`) — Go와 같다.
- Go protobuf 인코더는 oneof 멤버를 일반 필드 뒤에 놓는다. `addKey` 페이로드(`addKeyToWhitelistAndAddPermissions`(5, oneof) + `metadataForKey`(6))는 Wire와 바이트 순서가 다를 수 있으므로 **디코딩 후 필드 비교**한다(로드맵 공통 규칙). 필드가 하나뿐인 페이로드는 헥사로 비교해도 된다.
- Wire `ProtoReader(Buffer().write(bytes)).forEachTag { tag -> …; reader.skip() }`로 `unknownFields`의 필드 번호를 읽는다(`domain/…/protocol/UnknownFields.kt`의 패턴).
- `FakeVehicle.handle`은 지금 RoutableMessage로 해석되지 않는 프레임을 무시한다. `ToVCSECMessage`는 RoutableMessage에 없는 필드 1만 가져 **RoutableMessage로도 해석되므로** Task 6은 `ToVCSECMessage{PRESENT_KEY}` 판별을 RoutableMessage 해석보다 **먼저** 한다.
- 감싸지 않은 `FromVCSECMessage`(필드 1·4·16·17·46)는 RoutableMessage(필드 6·7·10·12~15·50~52)의 모르는 필드로만 읽혀 `from_destination == null` → "출발지 없음"으로 떨어진다. 테스트에서 모르는 필드를 흉내 낼 때는 RoutableMessage 필드 번호와 겹치지 않는 번호(예: 9 — FromVCSECMessage에서 reserved)를 쓴다.
- **Swift에는 Kotlin 기본 인자 오버로드가 생기지 않는다**(M3 샘플이 `vehicleStatus(timeout: nil)`, `connect(…, target: nil, options: …)`처럼 모든 인자를 넘기는 이유). 공개 함수에 인자를 더하면 Swift 호출이 깨지므로 기존 함수는 그대로 두고 새 함수를 더한다. Swift 예제·샘플은 `role:`·`formFactor:`·`timeout:`을 모두 명시한다.
- iOS 샘플: SKIE `onEnum(of:)`, Kotlin `fun interface TeslaLogger`는 ObjC 프로토콜이라 Swift 구현 클래스는 `NSObject`를 상속한다. Kotlin 객체는 `Sendable`이 아니므로 기존 `BleClient` 액터 패턴(`nonisolated(unsafe)`)을 따른다.
- iOS 테스트는 `./gradlew :<module>:iosSimulatorArm64Test`(Xcode 26, 기기 `iPhone 17`).

## Review Focus

스펙이 암시하지만 포팅 테스트만으로는 다루지 않는, 실제 사용자에게 문제가 될 입력 다섯 가지. 각 줄의 테스트를 소유 Task에 넣었다.

1. **연결 직후 1.1초마다 오는 감싸지 않은 `authenticationRequest`와 VCSEC 브로드캐스트가 페어링 중에 섞여 옴** — 페어링 이벤트는 `WaitingForTap`/`Accepted`만 보여야 하고 `Unknown`이 쏟아지면 안 된다. → Task 6 `ignoresBroadcastsAndAuthenticationRequestsWhilePairing`.
2. **다른 공개키로 `sessionInfo`를 물음(친구 폰 키 확인)** — 이 연결의 캐시 복원 세션이 "오래됨"으로 지워지면 안 된다. → Task 5 `foreignKeyQueryLeavesOurCachedSessionAlone`.
3. **차량 키가 바뀐 뒤(차량 교체·초기화) 캐시로 연결해 잠금** — 시간 초과까지 헛돌지 말고 `NoSession`으로 끝나고, `startSession()` 한 번으로 회복해야 한다. → Task 2 `staleCacheInvalidatesTheSessionAndAHandshakeRecovers`, Task 7 `staleCacheTurnsTheDomainBackToNone`.
4. **잠금을 누른 채 다른 화면에서 키 목록을 새로고침 / 페어링 중 상태 조회** — VCSEC는 한 번에 하나(FR-049), 페어링 전송도 진행 중인 VCSEC 요청이 끝난 뒤에 나가야 하고 페어링 대기(최대 60초)가 조회를 막으면 안 된다. → Task 6 `presentKeyWaitsForAnInFlightVcsecRequestButDoesNotHoldTheLock`.
5. **관찰 로그가 공개키·암호문·VIN을 새게 함(사용자가 로그를 복사해 이슈에 붙임)** — 길이와 SHA-1 앞 4바이트만. → Task 1 `logsNeitherCiphertextNorFullPublicKeysNorVin`.

## File Structure

| 파일 | 책임 | Go 대응 / 근거 |
|---|---|---|
| `application/…/observe/ObservationLog.kt` | 관찰 로그 한 줄 요약(`internal`) | 원본에 없음(설계 구체화 1) |
| `application/…/dispatcher/Dispatcher.kt` (수정) | 드롭 사유 추출(`struct`), 관찰 로그, 세션 무효화 호출, `querySessionInfo`, `unroutedFrames`·`sendUnrouted` | `dispatcher.go` `process`, `checkForSessionUpdate`, `Send` |
| `application/…/dispatcher/PendingRequest.kt` (수정) | `acceptsSessionInfo` | 설계 구체화 3 |
| `application/…/dispatcher/SessionState.kt`, `HandshakeFlow.kt` (수정) | 무효화, 닫힌 뒤 준비 아님 | 설계 구체화 2 |
| `application/…/cache/SessionCacheSync.kt`, `vehicle/VehicleSession.kt` (수정) | `markFresh`, `sessionEvents`, `control`·`keys`·`pairing` | 설계 구체화 2~6 |
| `adapter-ble/…/ble/BleTransport.kt` (수정) | inbox 넘침 횟수 | `ble.go:78-82` |
| `domain/…/model/VehicleError.kt` (수정), `domain/…/protocol/ResponseClassifier.kt` (수정) | `InsufficientPrivileges`, `UnknownVcsecError` | `pkg/protocol/error.go` |
| `domain/…/model/SessionInfoSummary.kt`, `PairingEvent.kt` | 공개 값 객체 | SDD §2.3 |
| `application/…/vcsec/VcsecPayloads.kt` | RKE·closure·whitelist·PRESENT_KEY 페이로드(`internal`) | `vcsec.go`, `security.go`, `actions.go` |
| `application/…/vcsec/VcsecControl.kt` | 제어 12종 | `vcsec.go` `executeRKEAction`/`executeClosureAction`, `actions.go` |
| `application/…/vcsec/KeyManagement.kt` | `addKey`, `removeKey`, `sessionInfo` | `security.go` `AddKeyWithRole`/`RemoveKey`, `vehicle.go` `SessionInfo` |
| `application/…/pairing/Pairing.kt`, `PairingEvents.kt` | add-key-request 전송 + 진행 이벤트 | `security.go` `SendAddKeyRequestWithRole` + ADR-0009 |
| `application/…/vcsec/VcsecInfoMapping.kt`, `VcsecResponses.kt`, `VcsecCommands.kt` (수정) | 모델 → Wire 역매핑, `keychainError`·`nominalError` 공유, `exclusive` | – |
| `sdk/…/Vehicle.kt`, `DefaultVehicle.kt`, `TeslaBleConfig.kt` (수정), `KeyFormFactors.kt` (+ android/ios/jvm actual), `Timeouts.kt` | 파사드 | SDD §2.3 |
| `testing/…/FakeVehicle.kt` (수정) | 인증 명령 평문 기록, PRESENT_KEY 대본 | SDD §9.3 |
| `samples/ios/Sources/ObservingLogger.swift`, `SampleModel.swift`, `ContentView.swift`, `project.yml`, `README.md`, `Tests/TimeoutsTests.swift` | 관찰 모드, 실차 가이드, 페어링·제어 화면, Swift 시간 제한 테스트 | 인계 노트 5·6, FR-111 |
| `samples/android/…/SampleState.kt`, `MainActivity.kt` | 페어링·제어 화면 | FR-110 |
| `tools/ci/verify.sh` (수정) | (d) `xcodebuild build test` | 설계 구체화 8 |
| `testing/…/fixtures/GoldenFixtures.kt` | 골든 TX/RX(조건부, D-5) | SDD §9.2, NFR-002 |
| `documents/md/1x-lib-*.md`, `documents/html/{ko,en,ja}/1x-lib-*.html` | 사용 문서 | 절대 규칙 13 |

## 작업 순서와 의존

```
Task 1 (푸시 관찰 로깅 + iOS 관찰 모드 + 실차 가이드) ── 🛑 사용자 실차 관찰(병행, 이후 Task를 막지 않음)
Task 2 (세션 정리: 무효화·닫힘)          Task 3 (오류 타입: InsufficientPrivileges, UnknownVcsecError)
Task 4 (VcsecPayloads + VcsecControl 12종) ← 3
Task 5 (KeyManagement: addKey/removeKey/sessionInfo) ← 2, 4
Task 6 (Pairing: unroutedFrames + addKeyRequest) ← 1, 4
Task 7 (:sdk 파사드 + 문서 13/15/16/11) ← 2~6
Task 8 (Swift 시간 제한: Timeouts, TeslaBleConfig.default, XCTest, verify.sh) ← 7
Task 9 (샘플 앱 2종: 페어링·제어·재조회, 빌드 7) ← 8 ── 🛑 사용자 실차 확인
Task 10 (골든 픽스처, 조건부 D-5) ← 4
Task 11 (마무리: SDD·PRD·ADR 동기화, 상태 표, 인계 노트)
```

Task 1의 사용자 실차 관찰은 결과를 기다리지 않고 Task 2부터 진행한다. 결과는 Task 11 전까지 `{{HANDOFF_DIR}}<날짜>-m4-push-observation-results.md`에 기록한다(Task 1 Step 13의 형식).

---

### Task 1: 차량 푸시 관찰 로깅 — Dispatcher 관찰 로그, 감싸지 않은 프레임 재해석, inbox 넘침 횟수, iOS 관찰 모드, 실차 가이드 (동작 변경 없음)

**브랜치:** `m4/push-observation`. **Refs:** NFR-006(D-1 추천), NFR-003, FR-112. **원본 대응:** `internal/dispatcher/dispatcher.go:269-293`(process의 드롭), `:400-403`(VCSEC 응답 키), `pkg/connector/ble/ble.go:78-82`(flush 드롭). 매뉴얼 `03-protocol.md` §12, `02-ble-transport.md`, `01-architecture.md`. 인계 노트 `{{HANDOFF_DIR}}2026-10-18-m4-push-observation.md` "M4 첫 작업의 범위" 1~9.

**Files:**
- Modify: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/dispatcher/Dispatcher.kt`
- Create: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/observe/ObservationLog.kt`
- Create: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/observe/ObservationLogTest.kt`
- Create: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/observe/DispatcherObservationTest.kt`
- Modify: `adapter-ble/src/commonMain/kotlin/dev/mirolab/teslable/ble/BleTransport.kt`, `adapter-ble/src/commonTest/kotlin/dev/mirolab/teslable/ble/BleTransportTest.kt`
- Create: `samples/ios/Sources/ObservingLogger.swift`; Modify: `samples/ios/Sources/SampleModel.swift`, `ContentView.swift`, `samples/ios/project.yml`(빌드 6), `samples/ios/README.md`(실차 관찰 가이드)
- Modify: `documents/md/18-lib-troubleshooting.md`, `documents/html/{en,ko,ja}/18-lib-troubleshooting.html`, `docs/sdd/SDD.md`(§9.6, §10, §12)

**Interfaces:**
- Consumes: `Dispatcher.process`/`matchKey`의 기존 드롭 분기, `CryptoPrimitives.sha1(data: ByteArray): ByteArray`, `ProtoAdapter.decodeOrNull`, `ByteArray.toHex()`.
- Produces: `internal enum class ObservedAt`, `internal enum class AddressOwner`, `internal class ObservationLog(crypto)` — `routable(at, message, size, owner): String`, `reinterpret(bytes): String`, `unparseable(bytes): String`, `vcsec(from): String`; `internal fun unknownFieldNumbers(bytes: ByteString): List<Int>`; `ObservationLog.TAG = "observe"`. Dispatcher 내부 `DropReason`·`Routing`(Task 6이 `unroutedFrames` 방출 지점으로 재사용). 공개 API 변화 없음.

- [ ] **Step 1 (struct): 드롭 판정과 드롭 로그를 분리한다.** `Dispatcher.matchKey`가 로그를 직접 남기던 것을 `route()`(판정만)와 `logDrop()`(기존 로그 문구·레벨 그대로)로 나눈다. 동작·로그는 한 글자도 바뀌지 않는다. `Dispatcher.kt`에서 `matchKey`를 지우고 다음으로 바꾼다:

```kotlin
        /** [route]의 결과: 짝을 찾을 키, 또는 드롭 사유. */
        private sealed interface Routing {
            class Matched(
                val key: PendingKey,
            ) : Routing

            class Dropped(
                val reason: DropReason,
            ) : Routing
        }

        /** Go `process`의 드롭 사유(dispatcher.go:269-293). 로그 문구와 레벨은 [logDrop]이 M3와 똑같이 남긴다. */
        private enum class DropReason { MISSING_SOURCE, BAD_UUID_LENGTH, MISSING_DESTINATION, TO_DOMAIN, BAD_ADDRESS_LENGTH }

        /** Go `process`의 검증 부분: 짝을 찾을 키 또는 드롭 사유. 로그는 남기지 않는다. */
        private fun route(message: RoutableMessage): Routing {
            // 설계 구체화 10(M2): "누락된 소스"는 from_destination 자체가 없을 때만이다. 모르는 domain 원시값은 BROADCAST로 떨어져
            // 등록된 핸들러가 없어 "핸들러 없음"으로 드롭된다.
            val fromDestination = message.from_destination ?: return Routing.Dropped(DropReason.MISSING_SOURCE)
            val fromDomain = fromDestination.domain ?: Domain.DOMAIN_BROADCAST
            val requestUuid = message.request_uuid
            if (requestUuid.size != UUID_LENGTH && requestUuid.size != 0) return Routing.Dropped(DropReason.BAD_UUID_LENGTH)
            val destination = message.to_destination ?: return Routing.Dropped(DropReason.MISSING_DESTINATION)
            val routingAddress = destination.routing_address ?: return Routing.Dropped(DropReason.TO_DOMAIN)
            if (routingAddress.size != ADDRESS_LENGTH) return Routing.Dropped(DropReason.BAD_ADDRESS_LENGTH)
            val uuid = if (fromDomain == Domain.DOMAIN_VEHICLE_SECURITY) ByteString.EMPTY else requestUuid
            return Routing.Matched(PendingKey(routingAddress, uuid, fromDomain))
        }

        /** [route]가 드롭한 메시지의 기존 로그(문구·레벨은 M3와 같다). */
        private fun logDrop(
            reason: DropReason,
            message: RoutableMessage,
        ) {
            val id = message.request_uuid.hex()
            when (reason) {
                DropReason.MISSING_SOURCE -> {
                    logger.log(LogLevel.WARN, TAG) { "[xxx] Dropping message with missing source" }
                }

                DropReason.BAD_UUID_LENGTH -> {
                    logger.log(LogLevel.WARN, TAG) { "[xxx] Dropping message with invalid request UUID length" }
                }

                DropReason.MISSING_DESTINATION -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message with missing destination" }
                }

                DropReason.TO_DOMAIN -> {
                    val toDomain = message.to_destination?.domain
                    logger.log(LogLevel.DEBUG, TAG) {
                        if (toDomain != null) "[$id] Dropping message to $toDomain" else "[$id] Dropping message with unrecognized destination type"
                    }
                }

                DropReason.BAD_ADDRESS_LENGTH -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message with invalid address length" }
                }
            }
        }
```

`process`의 앞부분은 이렇게 바뀐다(나머지는 그대로):

```kotlin
            val key =
                when (val routing = route(message)) {
                    is Routing.Matched -> routing.key
                    is Routing.Dropped -> {
                        logDrop(routing.reason, message)
                        return
                    }
                }
            val id = message.request_uuid.hex()
            val handler = lookup(key)
```

- [ ] **Step 2: 구조 변경 전후 테스트가 같다.** Run: `./gradlew :application:jvmTest :application:iosSimulatorArm64Test` — Expected: 전부 PASS(기존 `DispatcherTest`의 드롭 로그 단언 포함).

- [ ] **Step 3: 구조 변경 커밋.**

```bash
git add application/src/commonMain/kotlin/dev/mirolab/teslable/application/dispatcher/Dispatcher.kt
git commit -m "struct(application): split Dispatcher drop decision from drop logging

Refs: NFR-006
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 4: 실패하는 단위 테스트(요약 형식).** `application/src/commonTest/kotlin/dev/mirolab/teslable/application/observe/ObservationLogTest.kt`:

```kotlin
package dev.mirolab.teslable.application.observe

import com.tesla.generated.keys.Role
import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistEntryInfo
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.hexToBytes
import dev.mirolab.teslable.util.toHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservationLogTest {
    private val log = ObservationLog(TestCrypto.primitives)
    private val vcsecFrom = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY)

    @Test
    fun addresslessVehicleStatusShowsStateAndWhichMessageFieldsAreAbsent() {
        // PR #178: 잠긴 채 잠든 차량의 브로드캐스트에는 closureStatuses가 없었다
        val status =
            FromVCSECMessage(
                vehicleStatus =
                    VehicleStatus(
                        vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                        vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
                        userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
                    ),
            )
        val message = RoutableMessage(from_destination = vcsecFrom, protobuf_message_as_bytes = status.encode().toByteString())
        val line = log.routable(ObservedAt.MISSING_DESTINATION, message, size = 20, owner = null)
        assertEquals(
            "[drop:missing-destination] 20B from=domain(DOMAIN_VEHICLE_SECURITY) to=none request_uuid=empty " +
                "payload=FromVCSECMessage{vehicleStatus{lock=VEHICLELOCKSTATE_LOCKED, sleep=VEHICLE_SLEEP_STATUS_ASLEEP, " +
                "presence=VEHICLE_USER_PRESENCE_NOT_PRESENT, absent=[closureStatuses, detailedClosureStatus]}}",
            line,
        )
    }

    @Test
    fun closuresAreListedByShortName() {
        val status =
            FromVCSECMessage(
                vehicleStatus = VehicleStatus(closureStatuses = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN)),
            )
        val text = log.vcsec(status)
        assertTrue(text.contains("closures={frontDriverDoor=OPEN, frontPassengerDoor=CLOSED"), text)
        assertTrue(text.contains("tonneau=CLOSED}"), text)
        assertTrue(text.contains("absent=[detailedClosureStatus]"), text)
    }

    @Test
    fun encryptedPayloadIsLengthOnly() {
        val ciphertext = ByteArray(32) { 0xAB.toByte() }
        val message =
            RoutableMessage(
                from_destination = vcsecFrom,
                to_destination = Destination(routing_address = ByteArray(16).toByteString()),
                protobuf_message_as_bytes = ciphertext.toByteString(),
                signature_data =
                    SignatureData(
                        AES_GCM_Response_data =
                            AES_GCM_Response_Signature_Data(
                                nonce = ByteArray(12).toByteString(),
                                counter = 1,
                                tag = ByteArray(16).toByteString(),
                            ),
                    ),
            )
        val line = log.routable(ObservedAt.VCSEC_REPLY, message, size = 80, owner = AddressOwner.OPEN_REQUEST)
        assertTrue(line.contains("to=address(16B, open-request)"), line)
        assertTrue(line.endsWith("payload=encrypted(32B)"), line)
        assertFalse(line.contains("abab"), line)
    }

    @Test
    fun publicKeysAreSha1PrefixOnly() {
        val key = TestCrypto.clientPublicKey.toByteArray()
        val entry =
            FromVCSECMessage(
                whitelistEntryInfo =
                    WhitelistEntryInfo(publicKey = PublicKey(PublicKeyRaw = key.toByteString()), slot = 3, keyRole = Role.ROLE_OWNER),
            )
        val text = log.vcsec(entry)
        val prefix = TestCrypto.primitives.sha1(key).copyOf(4).toHex()
        assertEquals("FromVCSECMessage{whitelistEntryInfo{slot=3, role=ROLE_OWNER, key=sha1:$prefix}}", text)
        assertFalse(text.contains(key.toHex()))
    }

    @Test
    fun unwrappedFrameIsReinterpretedAndUnknownFieldsAreListedByNumber() {
        // PR #179: 공개 proto에 없는 authenticationRequest — 여기서는 FromVCSECMessage의 reserved 9번으로 흉내 낸다
        val frame =
            FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT)).encode() +
                "4a00".hexToBytes()
        assertEquals("FromVCSECMessage{commandStatus{op=OPERATIONSTATUS_WAIT}, unknownFields=[9]}", log.reinterpret(frame))
    }

    @Test
    fun undecodableBytesAreLengthOnly() {
        assertEquals("[drop:unparseable] 1B reinterpreted=undecodable", log.unparseable(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun sessionInfoIsLengthOnly() {
        val message = RoutableMessage(from_destination = vcsecFrom, session_info = ByteArray(40) { 7 }.toByteString())
        val line = log.routable(ObservedAt.NO_HANDLER, message, size = 60, owner = AddressOwner.OTHER)
        assertTrue(line.contains("session_info=40B"), line)
        assertFalse(line.contains("0707"), line)
    }

    @Test
    fun emptyUnknownFieldsListNothing() {
        assertEquals(emptyList(), unknownFieldNumbers(okio.ByteString.EMPTY))
    }
}
```

- [ ] **Step 5: 실패하는 통합 테스트(드롭은 그대로, 로그만 추가).** `application/src/commonTest/kotlin/dev/mirolab/teslable/application/observe/DispatcherObservationTest.kt`:

```kotlin
package dev.mirolab.teslable.application.observe

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleStatus
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vcsec.VcsecResponses
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus as ModelVehicleStatus
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.util.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DispatcherObservationTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    private fun RecordingLogger.observed(): List<String> = entries.filter { it.tag == ObservationLog.TAG }.map { it.message }

    private fun locked() = FromVCSECMessage(vehicleStatus = VehicleStatus(vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED))

    @Test
    fun addresslessVehicleStatusIsLoggedAndStillDropped() =
        runTest {
            val h = dispatcherHarness()
            val push = RoutableMessage(from_destination = Destination(domain = vcsec), protobuf_message_as_bytes = locked().encode().toByteString())
            h.transport.deliver(RoutableMessage.ADAPTER.encode(push))
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:missing-destination] "), line)
            assertTrue(line.contains("lock=VEHICLELOCKSTATE_LOCKED"), line)
            assertTrue(h.logger.contains("Dropping message with missing destination")) // 기존 드롭 로그 그대로
            // 응답 처리는 그대로다: 이어지는 조회가 자기 응답을 받는다
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(locked())))
            assertIs<VehicleResult.Success<ModelVehicleStatus>>(VehicleSession(h.dispatcher).info.vehicleStatus())
        }

    @Test
    fun domainAddressedMessageIsLoggedAsToDomain() =
        runTest {
            val h = dispatcherHarness()
            val push =
                RoutableMessage(
                    from_destination = Destination(domain = vcsec),
                    to_destination = Destination(domain = Domain.DOMAIN_BROADCAST),
                    protobuf_message_as_bytes = locked().encode().toByteString(),
                )
            h.transport.deliver(RoutableMessage.ADAPTER.encode(push))
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:to-domain] "), line)
            assertTrue(line.contains("to=domain(DOMAIN_BROADCAST)"), line)
        }

    @Test
    fun unwrappedFromVcsecMessageIsReinterpretedAndDropped() =
        runTest {
            val h = dispatcherHarness()
            val frame =
                FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT)).encode() +
                    "4a00".hexToBytes()
            h.transport.deliver(frame)
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:missing-source] "), line)
            assertTrue(line.endsWith("reinterpreted=FromVCSECMessage{commandStatus{op=OPERATIONSTATUS_WAIT}, unknownFields=[9]}"), line)
            assertTrue(h.logger.contains("Dropping message with missing source"))
        }

    @Test
    fun vehicleStatusWithMissingFieldsToAnotherAddressIsNoHandler() =
        runTest {
            val h = dispatcherHarness()
            val push =
                RoutableMessage(
                    from_destination = Destination(domain = vcsec),
                    to_destination = Destination(routing_address = ByteArray(16) { 9 }.toByteString()),
                    protobuf_message_as_bytes = locked().encode().toByteString(),
                )
            h.transport.deliver(RoutableMessage.ADAPTER.encode(push))
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:no-handler] "), line)
            assertTrue(line.contains("to=address(16B, other)"), line)
            assertTrue(line.contains("absent=[closureStatuses, detailedClosureStatus]"), line)
        }

    @Test
    fun repliesToAnOpenVcsecRequestUseTheSameFormat() =
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy(), FakeVehicle.vcsecEmpty()))
            VehicleSession(h.dispatcher).vcsec.execute(byteArrayOf(1), AuthMethod.NONE, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            val replies = h.logger.observed().filter { it.startsWith("[reply:vcsec] ") }
            assertTrue(replies.first().contains("to=address(16B, open-request)"), replies.toString())
            assertTrue(replies.first().contains("commandStatus{op=OPERATIONSTATUS_WAIT}"), replies.toString())
        }

    @Test
    fun logsNeitherCiphertextNorFullPublicKeysNorVinAndOnlyAtDebug() =
        // Review Focus 5
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecEmpty()))
            session.vcsec.execute(byteArrayOf(1), AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE)
            val lines = h.logger.entries.filter { it.tag == ObservationLog.TAG }
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.all { it.level == LogLevel.DEBUG })
            assertTrue(lines.none { it.message.contains(h.fake.vin.value) })
            assertTrue(lines.none { it.message.contains(dev.mirolab.teslable.testing.TestCrypto.clientPublicKey.toString()) })
        }

    @Test
    fun undecodableFrameIsLoggedByLength() =
        runTest {
            val h = dispatcherHarness()
            h.transport.deliver(byteArrayOf(0xFF.toByte()))
            runCurrent()
            assertEquals(listOf("[drop:unparseable] 1B reinterpreted=undecodable"), h.logger.observed())
            assertFalse(h.logger.observed().any { it.contains("ff") && !it.contains("1B") })
        }
}
```

(`TestCrypto.clientPublicKey.toString()`이 헥사가 아니면 구현자는 `toByteArray().toHex()`로 바꾼다 — 단언의 뜻은 "공개키 전체가 없다"이다.)

- [ ] **Step 6: 실패 확인.** Run: `./gradlew :application:jvmTest --tests '*Observation*'` — Expected: 컴파일 실패(`ObservationLog` 없음).

- [ ] **Step 7: 관찰 로그 구현.** `application/src/commonMain/kotlin/dev/mirolab/teslable/application/observe/ObservationLog.kt`:

```kotlin
package dev.mirolab.teslable.application.observe

import com.squareup.wire.ProtoReader
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistEntryInfo
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.protocol.decodeOrNull
import dev.mirolab.teslable.util.toHex
import okio.Buffer
import okio.ByteString
import okio.IOException

/** 관찰 지점(설계 구체화 1). [label]이 로그 줄의 머리다. */
internal enum class ObservedAt(
    val label: String,
) {
    UNPARSEABLE("drop:unparseable"),
    MISSING_SOURCE("drop:missing-source"),
    MISSING_DESTINATION("drop:missing-destination"),
    TO_DOMAIN("drop:to-domain"),
    MALFORMED("drop:malformed"),
    NO_HANDLER("drop:no-handler"),
    HANDLER_CLOSED("drop:handler-closed"),
    QUEUE_FULL("drop:queue-full"),
    VCSEC_REPLY("reply:vcsec"),
}

/** 라우팅 주소의 주인. */
internal enum class AddressOwner(
    val label: String,
) {
    /** 이 디스패처의 Infotainment 주소. */
    SELF("self"),

    /** 닫히지 않은 요청의 주소. */
    OPEN_REQUEST("open-request"),

    /** 그 밖(다른 클라이언트, 이미 닫힌 요청). */
    OTHER("other"),
}

/**
 * 원본에 없음 — 차량 푸시 관찰 로그 한 줄(M4 Task 1, 설계 구체화 1). M5 `vehicle.events` 설계의 근거를 모은다.
 * 바이트 필드는 길이만, 공개키는 SHA-1 앞 4바이트만, 암호문은 길이만 쓴다(절대 규칙 8, FR-112). VIN은 메시지에 없다.
 */
@OptIn(InternalTeslableApi::class)
internal class ObservationLog(
    private val crypto: CryptoPrimitives,
) {
    /** RoutableMessage 한 통. [size]는 받은 프레임 바이트 수, [owner]는 목적지가 라우팅 주소일 때만. */
    fun routable(
        at: ObservedAt,
        message: RoutableMessage,
        size: Int,
        owner: AddressOwner?,
    ): String =
        buildString {
            append('[').append(at.label).append("] ").append(size).append('B')
            append(" from=").append(destination(message.from_destination, owner = null))
            append(" to=").append(destination(message.to_destination, owner))
            append(" request_uuid=").append(if (message.request_uuid.size == 0) "empty" else "${message.request_uuid.size}B")
            message.signedMessageStatus?.let {
                append(" status=").append(it.operation_status.name).append('/').append(it.signed_message_fault.name)
            }
            if (message.session_info_request != null) append(" session_info_request")
            message.session_info?.let { append(" session_info=").append(it.size).append('B') }
            append(" payload=").append(payload(message))
        }

    /** 같은 바이트를 감싸지 않은 `FromVCSECMessage`로 다시 해석한 결과(처리는 하지 않는다). */
    fun reinterpret(bytes: ByteArray): String = FromVCSECMessage.ADAPTER.decodeOrNull(bytes)?.let(::vcsec) ?: "undecodable"

    /** RoutableMessage로 해석되지 않은 프레임. */
    fun unparseable(bytes: ByteArray): String = "[${ObservedAt.UNPARSEABLE.label}] ${bytes.size}B reinterpreted=${reinterpret(bytes)}"

    /** `FromVCSECMessage`의 oneof 종류와 내용 요약. */
    fun vcsec(from: FromVCSECMessage): String {
        val parts = mutableListOf<String>()
        from.vehicleStatus?.let { parts += "vehicleStatus{${vehicleStatus(it)}}" }
        from.commandStatus?.let { parts += "commandStatus{${commandStatus(it)}}" }
        from.whitelistInfo?.let { parts += "whitelistInfo{entries=${it.numberOfEntries}, slotMask=0x${it.slotMask.toUInt().toString(HEX)}}" }
        from.whitelistEntryInfo?.let { parts += "whitelistEntryInfo{${entry(it)}}" }
        from.nominalError?.let { parts += "nominalError{${it.genericError.name}}" }
        unknownFieldNumbers(from.unknownFields).takeIf { it.isNotEmpty() }?.let { parts += "unknownFields=$it" }
        return "FromVCSECMessage{${parts.joinToString(", ")}}"
    }

    private fun destination(
        d: Destination?,
        owner: AddressOwner?,
    ): String {
        if (d == null) return "none"
        d.routing_address?.let { address -> return "address(${address.size}B" + (owner?.let { ", ${it.label}" } ?: "") + ")" }
        d.domain?.let { return "domain(${it.name})" }
        return "empty"
    }

    private fun payload(message: RoutableMessage): String {
        val bytes = message.protobuf_message_as_bytes ?: return "none"
        val signature = message.signature_data
        if (signature != null && signature.session_info_tag == null) return "encrypted(${bytes.size}B)"
        val raw = bytes.toByteArray()
        return when (message.from_destination?.domain) {
            Domain.DOMAIN_VEHICLE_SECURITY -> FromVCSECMessage.ADAPTER.decodeOrNull(raw)?.let(::vcsec) ?: "undecodable(${bytes.size}B)"
            Domain.DOMAIN_INFOTAINMENT -> Response.ADAPTER.decodeOrNull(raw)?.let(::infotainment) ?: "undecodable(${bytes.size}B)"
            else -> "${bytes.size}B"
        }
    }

    // proto3 enum은 빠진 것과 0번 값을 구분할 수 없다(lock=UNLOCKED, sleep/presence=UNKNOWN). 메시지 필드만 absent로 알 수 있다.
    private fun vehicleStatus(s: VehicleStatus): String =
        buildString {
            append("lock=").append(s.vehicleLockState.name)
            append(", sleep=").append(s.vehicleSleepStatus.name)
            append(", presence=").append(s.userPresence.name)
            s.closureStatuses?.let { append(", closures={").append(closures(it)).append('}') }
            s.detailedClosureStatus?.let { append(", tonneauPercentOpen=").append(it.tonneauPercentOpen) }
            val absent =
                listOfNotNull(
                    "closureStatuses".takeIf { s.closureStatuses == null },
                    "detailedClosureStatus".takeIf { s.detailedClosureStatus == null },
                )
            if (absent.isNotEmpty()) append(", absent=").append(absent)
            unknownFieldNumbers(s.unknownFields).takeIf { it.isNotEmpty() }?.let { append(", unknownFields=").append(it) }
        }

    private fun closures(c: ClosureStatuses): String =
        listOf(
            "frontDriverDoor" to c.frontDriverDoor,
            "frontPassengerDoor" to c.frontPassengerDoor,
            "rearDriverDoor" to c.rearDriverDoor,
            "rearPassengerDoor" to c.rearPassengerDoor,
            "rearTrunk" to c.rearTrunk,
            "frontTrunk" to c.frontTrunk,
            "chargePort" to c.chargePort,
            "tonneau" to c.tonneau,
        ).joinToString(", ") { (name, state) -> "$name=${state.name.removePrefix("CLOSURESTATE_")}" }

    private fun commandStatus(s: CommandStatus): String =
        buildString {
            append("op=").append(s.operationStatus.name)
            s.whitelistOperationStatus?.let { append(", whitelist=").append(it.whitelistOperationInformation.name) }
            if (s.signedMessageStatus != null) append(", signedMessageStatus")
        }

    private fun entry(e: WhitelistEntryInfo): String =
        buildString {
            append("slot=").append(e.slot).append(", role=").append(e.keyRole.name)
            e.metadataForKey?.let { append(", formFactor=").append(it.keyFormFactor.name) }
            val raw = e.publicKey?.PublicKeyRaw?.takeIf { it.size > 0 }
            if (raw != null) {
                append(", key=sha1:").append(crypto.sha1(raw.toByteArray()).copyOf(KEY_PREFIX).toHex())
            } else {
                e.keyId?.publicKeySHA1?.takeIf { it.size >= KEY_PREFIX }?.let { append(", keyId=sha1:").append(it.substring(0, KEY_PREFIX).hex()) }
            }
        }

    private fun infotainment(r: Response): String {
        val fields =
            listOfNotNull(
                "actionStatus".takeIf { r.actionStatus != null },
                "vehicleData".takeIf { r.vehicleData != null },
                "getSessionInfoResponse".takeIf { r.getSessionInfoResponse != null },
                "getNearbyChargingSites".takeIf { r.getNearbyChargingSites != null },
                "ping".takeIf { r.ping != null },
            )
        val unknown = unknownFieldNumbers(r.unknownFields)
        return "Response{fields=$fields" + (if (unknown.isEmpty()) "" else ", unknownFields=$unknown") + "}"
    }

    companion object {
        /** 관찰 로그 태그. 레벨은 항상 DEBUG. */
        const val TAG: String = "observe"
        private const val KEY_PREFIX = 4
        private const val HEX = 16
    }
}

/** Wire `unknownFields`의 필드 번호(중복 제거, 나온 순서). 손상된 버퍼는 읽은 데까지. */
internal fun unknownFieldNumbers(bytes: ByteString): List<Int> {
    if (bytes.size == 0) return emptyList()
    val tags = mutableListOf<Int>()
    try {
        val reader = ProtoReader(Buffer().write(bytes))
        reader.forEachTag { tag ->
            tags += tag
            reader.skip()
        }
    } catch (ignored: IOException) {
        // 읽은 데까지만 남긴다
    }
    return tags.distinct()
}
```

- [ ] **Step 8: Dispatcher에 관찰 지점을 단다.** `Dispatcher.kt`에 필드와 도우미를 더한다:

```kotlin
        private val observation = ObservationLog(crypto)

        /** 관찰 로그(DEBUG, 태그 `observe`). 로거가 DEBUG를 버리면 [line]은 평가되지 않는다. */
        private inline fun observe(crossinline line: () -> String) {
            logger.log(LogLevel.DEBUG, ObservationLog.TAG) { line() }
        }

        /** 라우팅 주소의 주인(관찰 로그용). */
        private suspend fun ownerOf(routingAddress: ByteString): AddressOwner {
            if (routingAddress == address) return AddressOwner.SELF
            return pendingMutex.withLock {
                if (pending.values.any { !it.isClosed && it.key.address == routingAddress }) AddressOwner.OPEN_REQUEST else AddressOwner.OTHER
            }
        }

        private fun observeDrop(
            reason: DropReason,
            message: RoutableMessage,
            bytes: ByteArray,
        ) {
            when (reason) {
                DropReason.MISSING_SOURCE -> {
                    observe {
                        observation.routable(ObservedAt.MISSING_SOURCE, message, bytes.size, owner = null) +
                            " reinterpreted=" + observation.reinterpret(bytes)
                    }
                }

                DropReason.MISSING_DESTINATION -> {
                    observe { observation.routable(ObservedAt.MISSING_DESTINATION, message, bytes.size, owner = null) }
                }

                DropReason.TO_DOMAIN -> {
                    observe { observation.routable(ObservedAt.TO_DOMAIN, message, bytes.size, owner = null) }
                }

                DropReason.BAD_UUID_LENGTH, DropReason.BAD_ADDRESS_LENGTH -> {
                    observe { observation.routable(ObservedAt.MALFORMED, message, bytes.size, owner = null) }
                }
            }
        }
```

`process`는 이렇게 된다(드롭 판단과 기존 로그는 그대로, `observe…` 줄만 늘었다):

```kotlin
        private suspend fun process(bytes: ByteArray) {
            val message =
                RoutableMessage.ADAPTER.decodeOrNull(bytes) ?: run {
                    logger.log(LogLevel.WARN, TAG) { "Dropping unparseable message" }
                    observe { observation.unparseable(bytes) }
                    return
                }
            val key =
                when (val routing = route(message)) {
                    is Routing.Matched -> routing.key
                    is Routing.Dropped -> {
                        logDrop(routing.reason, message)
                        observeDrop(routing.reason, message, bytes)
                        return
                    }
                }
            val id = message.request_uuid.hex()
            val handler = lookup(key)
            if (handler == null) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message without registered handler $key" }
                val owner = ownerOf(key.address)
                observe { observation.routable(ObservedAt.NO_HANDLER, message, bytes.size, owner) }
                return
            }
            if (key.domain == Domain.DOMAIN_VEHICLE_SECURITY) {
                observe { observation.routable(ObservedAt.VCSEC_REPLY, message, bytes.size, AddressOwner.OPEN_REQUEST) }
            }
            // … checkForSessionUpdate, decryptIfNeeded는 그대로. deliver 실패 분기(기존 로그 두 줄)에 관찰 줄을 더한다:
            //   handler.isClosed → 기존 WARN 뒤 observe { observation.routable(ObservedAt.HANDLER_CLOSED, message, bytes.size, AddressOwner.OPEN_REQUEST) }
            //   그 밖(큐 가득) → 기존 ERROR 뒤 observe { observation.routable(ObservedAt.QUEUE_FULL, message, bytes.size, AddressOwner.OPEN_REQUEST) }
            //   (복호화 뒤의 평문 메시지가 아니라 받은 그대로의 message를 넘긴다 — 평문 페이로드는 어떤 레벨에서도 남기지 않는다, FR-112)
```

`DispatcherObservationTest`에 `closedHandlerAndFullQueueDropsAreObserved`를 더한다: (a) `h.dispatcher.send(...)`로 받은 `PendingRequest`를 바로 `close()`한 뒤 그 주소로 응답을 넣으면 `[drop:handler-closed]`(기존 "without registered handler" 로그는 그대로 — 닫힌 핸들러는 `lookup`에서 지워질 수도 있으므로 `[drop:no-handler]` 또는 `[drop:handler-closed]` 둘 중 하나가 정확히 한 줄), (b) 같은 열린 요청에 응답 `PendingRequest.BUFFER_SIZE + 1`개를 넣으면 `[drop:queue-full]` 한 줄.

- [ ] **Step 9: 통과 확인.** Run: `./gradlew :application:jvmTest :application:iosSimulatorArm64Test :application:apiCheck` — Expected: PASS, `apiCheck` 변화 없음(새 타입은 전부 `internal`).

- [ ] **Step 10: inbox 넘침 횟수(:adapter-ble).** 실패 테스트를 `BleTransportTest`에 더한다:

```kotlin
    @Test
    fun countsInboxOverflowsPerConnection() =
        // 인계 노트 범위 3: 넘친 횟수를 센다(Go ble.go:78-82는 조용히 버린다)
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            repeat(BleTransport.INBOX_CAPACITY + 3) { link.notify(byteArrayOf(0, 1, 7)) }
            runCurrent()
            assertTrue(logger.contains("Dropping received message because inbox is full (dropped 1 since connect)"))
            assertTrue(logger.contains("Dropping received message because inbox is full (dropped 3 since connect)"))
            t.close()
            val summary = logger.entries.single { it.message.contains("inbox overflowed") }
            assertEquals(LogLevel.WARN, summary.level)
            assertEquals("BLE inbox overflowed 3 time(s) during this connection", summary.message)
        }
```

구현(`BleTransport.kt`): 필드 `private var overflows = 0`(수신 코루틴에서만 바뀐다 — 읽기는 `close`/`linkEnded`가 수신 종료 뒤에 한다), `deliver`의 ERROR 줄을 `"Dropping received message because inbox is full (dropped ${++overflows} since connect)"`로, 요약은 **수신 코루틴의 `finally`가 부르는 `linkEnded()`에서만** 남긴다 — `if (overflows > 0) logger.log(LogLevel.WARN, TAG) { "BLE inbox overflowed $overflows time(s) during this connection" }`. `close()`도 수신 코루틴을 끝내므로 결국 같은 `finally`를 지나고, 이 코루틴은 연결당 하나라 요약은 정확히 한 번이며 카운터를 읽고 쓰는 쪽이 같은 코루틴이라 경합이 없다(`close()` 안에서 직접 요약하지 않는다). 테스트는 `t.close()` 뒤 `runCurrent()`를 부른 다음 요약 줄을 확인하고, `close()`를 두 번 불러도 요약이 한 줄인지도 단언한다. 기존 `dropsWithErrorLogWhenInboxIsFull`는 `contains`라 그대로 통과한다. Run: `./gradlew :adapter-ble:jvmTest :adapter-ble:apiCheck` — Expected: PASS.

- [ ] **Step 11: 기능 커밋 두 개.**

```bash
git add application/src/commonMain/kotlin/dev/mirolab/teslable/application/observe application/src/commonMain/kotlin/dev/mirolab/teslable/application/dispatcher/Dispatcher.kt application/src/commonTest/kotlin/dev/mirolab/teslable/application/observe
git commit -m "feat(application): log a decoded summary of every message the dispatcher drops and every VCSEC reply

Observation only: drop rules and response matching are unchanged. DEBUG, tag observe.
Ciphertext and session info by length, public keys by SHA-1 prefix, unwrapped frames reinterpreted as FromVCSECMessage.

Refs: NFR-006, NFR-003, FR-112
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
git add adapter-ble
git commit -m "feat(ble): count BLE inbox overflows per connection

Refs: NFR-006
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 12: iOS 샘플 관찰 모드(빌드 6).** `samples/ios/Sources/ObservingLogger.swift`:

```swift
import Foundation
import Teslable

/// 라이브러리 로그를 DebugLogger로 보내고, 관찰 로그(태그 `observe`)는 스트림으로도 내보낸다(M4 Task 1).
/// Kotlin 수신 스레드에서 불리므로 스레드 안전한 continuation만 쓴다.
final class ObservingLogger: NSObject, TeslaLogger, @unchecked Sendable {
    private let debug = DebugLogger()
    let lines: AsyncStream<String>
    private let continuation: AsyncStream<String>.Continuation

    override init() {
        var made: AsyncStream<String>.Continuation?
        lines = AsyncStream(bufferingPolicy: .bufferingNewest(500)) { made = $0 }
        continuation = made!
        super.init()
    }

    func log(level: LogLevel, tag: String, message: @escaping () -> String) {
        debug.log(level: level, tag: tag, message: message)
        if tag == "observe" { continuation.yield(message()) }
    }
}
```

(`made!`는 Swift 코드라 Kotlin 금지 규칙과 무관하다 — `AsyncStream` 초기화 클로저는 동기로 불린다.)

`SampleModel.swift` 변경:
- `BleClient`: `nonisolated let observer = ObservingLogger()`를 두고 `TeslaBle` 생성을 `config: TeslaBleConfig.companion.default(logger: observer)`로 바꾼다. `nonisolated var observations: AsyncStream<String> { observer.lines }`.
- `SampleModel`: `@Published var observing = false`, `@Published var observations: [String] = []`, `private var observeStart: Date?`. `init()`에서 `Task { for await line in client.observations { await self.observed(line) } }`를 띄운다. `observed(_:)`는 `observing`일 때만 `"[HH:mm:ss +12.3s] <line>"`을 붙인다(최대 500줄, 넘치면 앞에서 지움). `observing`을 켜면 `observeStart = Date()`와 `log("관찰 시작")`, 끄면 `log("관찰 종료: N줄")`.
- `report()`에 `"--- 관찰 (\(observations.count)줄) ---"`와 `observations`를 더한다.
- **복사되는 로그의 범위(비밀):** "결과 전체 복사"에는 화면 결과와 `observe` 태그 줄만 들어간다. `DebugLogger`로 가는 다른 줄(특히 `BleTransport`의 DEBUG `TX:`/`RX:` 원문 헥사 — 암호문이 들어 있다)은 iOS 통합 로그로만 가고 앱 화면·복사본에 들어가지 않는다. `ObservingLogger`는 `tag == "observe"`인 줄만 스트림에 넣으므로 이 경계가 코드로 강제된다. 가이드(Step 13)에도 "Console.app 로그는 공유하지 말 것(원문 헥사 포함)"을 적는다.
- 관찰 중에는 차량을 깨우는 요청 버튼(VehicleStatus, 키 목록)을 막는다(`busy || observing`). 샘플에는 주기적 자동 조회가 없다 — 앞으로도 넣지 않는다고 주석으로 남긴다.

`ContentView.swift`에 `Section("관찰 (요청 없이 수신만 기록)")`: `Toggle("관찰 모드", isOn: $model.observing)`, `Text("받은 메시지 \(model.observations.count)줄")`, `Button("관찰 기록 지우기", role: .destructive)`, 최근 200줄 `ForEach`(`.font(.caption2.monospaced())`, `.textSelection(.enabled)`). `project.yml`의 `CURRENT_PROJECT_VERSION`을 `"6"`으로.

- [ ] **Step 13: 실차 관찰 가이드(사용자용).** `samples/ios/README.md`에 "실차 푸시 관찰(M4 Task 1)" 절을 더한다 — `{{WORKFLOW_FILE}}` §8.4 형식의 표:

| # | 시나리오 | 할 일 | 기다릴 시간 | 기록할 내용 |
|---|---|---|---|---|
| 1 | 새로 연결한 직후 | 키 불러오기 → 연결 → 바로 관찰 모드 켜기 | 30초 | 처음 30초의 줄 수, `reinterpreted=…unknownFields=[…]` 줄의 간격(약 1.1초인지)과 필드 번호 |
| 2 | 문·트렁크를 손으로 열고 닫기 | 운전석 문 열기 → 10초 → 닫기, 트렁크도 같게 | 동작마다 10초 | 동작 뒤 몇 초 안에 `vehicleStatus` 줄이 오는지, `to=`의 형태(none/domain/address+owner), `closures` 값 |
| 3 | 키카드나 공식 앱으로 잠금·해제 | 샘플은 그대로 두고 카드/앱으로 잠금 → 10초 → 해제 | 동작마다 10초 | `lock=` 변화가 오는지, 주소 형태 |
| 4 | 차에 타고 내리기 | 운전석에 앉기 → 20초 → 내려 문 닫기 | 20초씩 | `presence=` 변화 |
| 5 | 충전 케이블 꽂고 빼기 | 충전구 열기 → 케이블 꽂기 → 빼기 | 동작마다 10초 | `chargePort=` 변화, Infotainment 줄(`from=domain(DOMAIN_INFOTAINMENT)`)이 있는지 |
| 6 | 차량이 잠들거나 깨어날 때 | 문 닫고 잠근 뒤 휴대폰을 두고 관찰 유지 | **최소 15분** | `sleep=` 변화 시각, 잠든 뒤 줄 간격, `absent=` 목록, 연결이 끊기는지 |
| 7 | 다른 폰 키가 다가올 때 | 다른 폰(공식 앱 키) 들고 차에 다가가기 | 30초 | `drop:no-handler`의 `to=address(16B, other)` 줄, 빈도 |

각 시나리오 뒤 "결과 전체 복사"로 붙여 넣어 보낸다(VIN은 들어가지 않는다). 컨트롤러는 받은 기록을 `{{HANDOFF_DIR}}<날짜>-m4-push-observation-results.md`에 아래 머리말로 정리한다: `## 관찰 환경`(iPhone/iOS, 차량/펌웨어, 빌드), `## 시나리오별 결과`(위 표 + "푸시 주소 형태 / 빈도 / 내용"), `## M5 판단에 쓸 사실`(인계 노트 "실차 로그로 확인할 것" 두 가지에 대한 답), `## 응답과 섞인 사례`(`[reply:vcsec]` 중 `vehicleStatus` 페이로드가 있었는지).

- [ ] **Step 14: 사용 문서(MD + HTML 3언어).** `documents/md/18-lib-troubleshooting.md`에 "## Observation log (development)" 절을 더한다(영어): 태그 `observe`·레벨 DEBUG·`DebugLogger`에서 보임, 줄 형식 표(`[drop:unparseable|missing-source|missing-destination|to-domain|malformed|no-handler]`, `[reply:vcsec]`, `from=`, `to=address(16B, self|open-request|other)`/`domain(…)`/`none`, `request_uuid=empty|16B`, `status=`, `session_info=NB`, `payload=FromVCSECMessage{…}|encrypted(NB)|Response{fields=[…]}|none`, `reinterpreted=`, `unknownFields=[n]`, `absent=[…]`, `key=sha1:xxxxxxxx`), 한계("proto3 enum: an absent lock state reads `VEHICLELOCKSTATE_UNLOCKED`; only message fields can be reported as absent"), 비밀 규칙, "the library still drops these messages; `vehicle.events` is planned for M5", BLE inbox overflow 줄. "Scope" 첫 줄을 "M3–M4 (observation log)"로. 같은 내용으로 `documents/html/en/18-lib-troubleshooting.html`을 먼저 고치고, `ko/`·`ja/`는 같은 마크업으로 번역한다. `tools/ci/check-manual-i18n.sh`가 통과해야 한다.

- [ ] **Step 15: SDD 기록.** `{{SDD_FILE}}` §10에 "관찰 로그(M4): DEBUG, 태그 `observe`, 형식·마스킹 규칙(설계 구체화 1)" 한 단락, §12 "원본과 다른 동작"에 "Dispatcher가 버리는 메시지·VCSEC 응답의 요약을 DEBUG로 남긴다(Go는 조용히 버린다, `dispatcher.go:269-293`); BLE inbox 넘침을 센다(`ble.go:78-82`). 버리는 동작은 같다", §9.6에 "푸시 관찰 7개 시나리오는 `samples/ios/README.md`" 한 줄.

- [ ] **Step 16: 문서 커밋.**

```bash
git add samples/ios documents docs/sdd/SDD.md
git commit -m "feat(samples): iOS observation mode and the real-vehicle push observation guide; build 6

Refs: NFR-006
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(문서만 바뀐 부분을 따로 `docs:` 커밋으로 나눠도 된다 — 샘플 코드와 문서를 한 커밋에 섞지 않는 쪽을 권한다.)

- [ ] **Step 17: 게이트·리뷰·병합.** `tools/ci/verify.sh` 통과 → 새 컨텍스트 리뷰어(`{{WORKFLOW_FILE}}` §9 DoD + "로그에 비밀 없음, 드롭 동작 불변, apiDump 불변, 문서 3언어 일치") → PR(본문: 원본 대응, 리뷰 요약, "공개 API 변화 없음") → rebase merge.

- [ ] **Step 18: 🛑 사용자 실차 관찰(병행).** 사용자에게 빌드 6 설치와 Step 13 시나리오 1~7을 요청하고 **기다리지 않고 Task 2로 넘어간다**. 결과가 오면 Step 13의 형식으로 결과 노트를 쓴다(`docs:` 커밋, 작은 PR).

---

### Task 2: 세션 정리 — 오래된 캐시 세션 무효화, 닫힌 세션은 준비 아님

**브랜치:** `m4/session-hygiene`. **Refs:** FR-017, FR-018, NFR-012. **원본 대응:** `internal/dispatcher/session.go`(`processHello`, `ready`), `dispatcher.go` `StartSession`·`checkForSessionUpdate`. Go에는 오래된 캐시 판정이 없다(M3 사용자 결정 2026-09-27).

**Files:**
- Modify: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/dispatcher/SessionState.kt`, `HandshakeFlow.kt`, `Dispatcher.kt`
- Modify: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/cache/SessionCacheSync.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vehicle/VehicleSession.kt`
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/dispatcher/SessionStateTest.kt`, `application/src/commonTest/kotlin/dev/mirolab/teslable/application/cache/SessionEventsTest.kt`, `application/src/commonTest/kotlin/dev/mirolab/teslable/application/vehicle/VehicleSessionTest.kt`

**Interfaces:**
- Produces: `SessionState.invalidate()`(internal), `SessionState.isReady`(닫힘·무효화 반영), `SessionCacheSync.markFresh(domain: Domain)`, `VehicleSession.sessionEvents: SharedFlow<SessionEvent>` — Task 7 `DefaultVehicle`이 구독한다.

- [ ] **Step 1: 실패 테스트.** `SessionStateTest`:

```kotlin
    @Test
    fun closedSessionIsNeverReady() =
        // M3 최종 리뷰: close()가 준비 신호를 되돌리지 않아 disconnect 뒤 startSession이 캐시 세션에 Success였다
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(Domain.DOMAIN_VEHICLE_SECURITY)
            val session = checkNotNull(h.dispatcher.session(Domain.DOMAIN_VEHICLE_SECURITY))
            assertTrue(session.isReady)
            session.close()
            assertFalse(session.isReady)
        }
```

`VehicleSessionTest`:

```kotlin
    @Test
    fun startSessionAfterDisconnectIsNotConnectedEvenForCachedSessions() =
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            session.disconnect()
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), session.startSession(timeout = 1.seconds))
        }
```

`SessionEventsTest`(기존 `restoreStaleCache` 사용):

```kotlin
    @Test
    fun staleCacheInvalidatesTheSessionAndAHandshakeRecovers() =
        // 설계 구체화 2, Review Focus 3: 헛도는 재시도 대신 NoSession, startSession 한 번으로 회복, 회복한 세션은 다시 캐시된다
        runTest {
            val cache = RecordingSessionCache()
            val sync = restoreStaleCache(cache)
            val h = dispatcherHarness(fake = FakeVehicle(timeSource = testTimeSource), start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = backgroundScope)
            assertEquals(setOf(vcsec, infotainment), session.connect())
            val lock = byteArrayOf(0x10, 0x01)
            val first = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 5.seconds)
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), first)
            assertFalse(checkNotNull(h.dispatcher.session(vcsec)).isReady)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(vcsec), timeout = 1.seconds))
            // 핸드셰이크 자체의 저장이 회복한 도메인을 넣는다(이벤트 처리 순서에 기대지 않는다 — runCurrent 전에 단언)
            assertTrue(cache.stores.last().entries.any { it.domain == vcsec })
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
        }
```

`SessionStateTest`(무효화와 대기 중인 인가의 경합 — codex 검토 지적):

```kotlin
    @Test
    fun authorizeThatWasWaitingWhenTheSessionWasInvalidatedFailsWithNoSession() =
        // 준비 신호를 이미 받은 authorize가 무효화 뒤에 옛 Signer로 진행하면 안 된다. 단일 스레드 스케줄러로 순서를 고정한다.
        runTest {
            val h = dispatcherHarness(start = false)
            val session = checkNotNull(h.dispatcher.session(Domain.DOMAIN_VEHICLE_SECURITY))
            val waiting = async { session.authorize(testCommand(Domain.DOMAIN_VEHICLE_SECURITY), 5.seconds) }
            runCurrent() // authorize가 준비 신호를 기다린다
            h.loadVcsecSessionFromCache(session) // 준비 신호 완료 — authorize는 재개 가능하지만 아직 실행되지 않았다
            session.invalidate() // 재개 전에 무효화
            val result = assertIs<SignerResult.Fault>(waiting.await())
            assertEquals(SessionState.NO_SESSION, result.detail)
        }
```

(`h.loadVcsecSessionFromCache`는 이 테스트 파일에 두는 작은 도우미다 — `manualHandshake`로 만든 다른 디스패처의 `export()` 바이트를 `session.loadFromCache(bytes, Duration.ZERO)`로 넣는다. `testCommand`는 기존 `DispatcherFixtures`의 것.)

Run: `./gradlew :application:jvmTest` — Expected: 네 테스트 FAIL(`isReady` true, startSession Success, 첫 명령이 `Timeout`, authorize가 `Ok`).

- [ ] **Step 2: 구현.** `SessionState`:
  - `private val readySignal`를 `@Volatile private var readySignal = CompletableDeferred<Unit>()`로(바꾸는 곳은 뮤텍스 안뿐).
  - `public val isReady: Boolean get() = !closed && readySignal.isCompleted`.
  - `processHello`의 `Ok` 분기에 `reportedStale = false`를 더한다(새 핸드셰이크가 성공하면 다시 내보낼 수 있다).
  - 새 함수:

```kotlin
    /**
     * 오래된 캐시로 판정된 세션을 버린다(설계 구체화 2): 세션 키를 0으로 덮고 준비 신호를 되돌린다. 이후 인증 전송은 `NoSession`이고,
     * 다음 핸드셰이크가 새 [Signer]를 만든다. 닫힌 세션에는 아무것도 하지 않는다.
     */
    internal suspend fun invalidate(): Unit =
        mutex.withLock {
            if (closed) return@withLock
            signer?.close()
            signer = null
            unconfirmedRestore = false
            if (readySignal.isCompleted) readySignal = CompletableDeferred()
        }
```

  - `authorize`는 기다린 준비 신호가 여전히 현재 신호인지 락 안에서 다시 확인한다(무효화와의 경합):

```kotlin
    public suspend fun authorize(
        message: RoutableMessage,
        lifetime: Duration,
    ): SignerResult<RoutableMessage> {
        val signal = readySignal
        signal.await()
        return mutex.withLock {
            val current = signer
            if (closed || signal !== readySignal || current == null) {
                SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, NO_SESSION)
            } else {
                current.encrypt(message, lifetime)
            }
        }
    }

    /** 상수. */
    public companion object {
        /** [authorize]가 세션 없음(닫힘·무효화)으로 실패할 때의 `detail`. 디스패처가 `NoSession`으로 바꾼다. */
        public const val NO_SESSION: String = "no session"
    }
```

  `Dispatcher.authorize`의 `Fault` 분기: `if (result.detail == SessionState.NO_SESSION) VehicleResult.Failure(VehicleError.NoSession) else VehicleResult.Failure(result.toVehicleError())` — 그러지 않으면 `INTERNAL`(재시도 가능 fault)로 보여 시간 초과까지 재시도한다. 기존 "session closed" 문구를 쓰던 테스트가 있으면 `NO_SESSION`으로 고친다.
  - `VehicleSession.startSession`이 성공하면 `storeAfterHandshake()` **전에** `cacheSync?.let { sync -> domains.forEach { sync.markFresh(it) } }` — 회복한 도메인을 핸드셰이크 자체의 저장에 넣는다(이벤트 처리 순서·유실과 무관, codex 검토 지적). 이벤트의 `markFresh`는 보조 경로로 남긴다.
  `Dispatcher.checkForSessionUpdate`의 `Fault` 분기에서 `session.markReportedStale()` 바로 뒤에 `session.invalidate()`를 부른다(수신 코루틴 — 임계 구역은 `Signer.close`뿐이라 SDD §5 규칙을 지킨다). `HandshakeFlow.startSession`의 `isReady` 검사 앞에 `if (session.isClosed) return VehicleResult.Failure(VehicleError.NotConnected)`. `SessionCacheSync`에 `public suspend fun markFresh(domain: Domain) { storeMutex.withLock { staleDomains -= domain } }`(KDoc: "새 핸드셰이크로 다시 확인된 도메인을 저장 대상에 되돌린다"). `VehicleSession.handleEvent`의 첫 줄을 `when (event) { is SessionEvent.StaleCache -> sync.markStale(event.domain); is SessionEvent.Updated -> sync.markFresh(event.domain) }`로, 그리고 `public val sessionEvents: SharedFlow<SessionEvent> get() = dispatcher.sessionEvents`(KDoc: "파사드가 세션 상태 표시에 쓴다").

  주의: `Updated`는 정상 세션의 갱신에도 오지만 `markFresh`는 그때 아무것도 바꾸지 않는다. `StaleCache`가 난 도메인의 `Updated`는 무효화 뒤 새 핸드셰이크에서만 온다(태그가 맞아야 `Ok`).

- [ ] **Step 3: 통과 확인 + 회귀.** Run: `./gradlew :application:jvmTest :application:iosSimulatorArm64Test :application:apiCheck` — Expected: PASS. `apiCheck`가 `SessionCacheSync.markFresh`·`VehicleSession.sessionEvents` 추가로 실패하면 `./gradlew :application:apiDump`를 단독 실행해 같은 커밋에 넣는다. 기존 `vehicleSessionStoresOnUpdateAndDropsTheStaleDomainForGood`는 새 핸드셰이크가 없으므로 그대로 통과해야 한다.

- [ ] **Step 4: SDD 기록·커밋.** §12에 "오래된 캐시로 판정된 세션은 무효화되어 `NoSession`을 돌려주고 `startSession()`이 같은 연결에서 새로 핸드셰이크한다(Go는 같은 연결에서 회복하지 못한다)"와 "닫힌 세션은 준비 상태가 아니다". 문서 영향 없음(파사드 표시는 Task 7).

```bash
git add application docs/sdd/SDD.md
git commit -m "fix(application): invalidate stale cached sessions and never report a closed session as ready

Refs: FR-017, FR-018
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

PR → verify.sh → 리뷰(동시성 — opus 권장) → rebase merge.

---

### Task 3: 오류 타입 — `InsufficientPrivileges`(FR-104), `UnknownVcsecError`(L58)

**브랜치:** `m4/error-types`. **Refs:** FR-103, FR-104. **원본 대응:** `pkg/protocol/error.go:193-230`(`RoutableMessageError`), `:177-181`(`NominalVCSECError`), `pkg/vehicle/vcsec.go:44-45`. 매뉴얼 `08-errors.md` §3.1(fault 7), §8.

**Files:**
- Modify: `domain/src/commonMain/kotlin/dev/mirolab/teslable/model/VehicleError.kt`, `domain/src/commonMain/kotlin/dev/mirolab/teslable/protocol/ResponseClassifier.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/dispatcher/Results.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vcsec/VcsecResponses.kt`
- Test: `domain/src/commonTest/kotlin/dev/mirolab/teslable/model/VehicleErrorTest.kt`, `domain/src/commonTest/kotlin/dev/mirolab/teslable/protocol/ResponseClassifierTest.kt`, `application/src/commonTest/kotlin/dev/mirolab/teslable/application/vcsec/VcsecResponsesTest.kt`
- Docs: `documents/md/16-lib-errors.md`, `documents/html/{en,ko,ja}/16-lib-errors.html`

**Interfaces:**
- Produces: `VehicleError.InsufficientPrivileges`(data object), `VehicleError.UnknownVcsecError(rawCode: Int)`; `VcsecResponses.keychainError(status: WhitelistOperation_status): VehicleError?`와 `VcsecResponses.nominalError(error: NominalError): VehicleError`를 `internal`로 공개(Task 6이 쓴다).

- [ ] **Step 1: 실패 테스트.**

```kotlin
    // ResponseClassifierTest
    @Test
    fun insufficientPrivilegesIsItsOwnTypeWithGoMessage() {
        // FR-104. Go RoutableMessageError.Error()는 fault 이름(error.go:225-230)
        val message = RoutableMessage(signedMessageStatus = MessageStatus(signed_message_fault = MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES))
        val error = ResponseClassifier.protocolError(message)
        assertEquals(VehicleError.InsufficientPrivileges, error)
        assertEquals("MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES", error?.message)
        assertFalse(error?.temporary ?: true)
        assertFalse(error?.mayHaveSucceeded ?: true)
    }

    // VcsecResponsesTest
    @Test
    fun unknownGenericErrorKeepsItsRawCode() {
        // L58: Go는 GenericError_E에 실제 코드를 담는다(vcsec.go:44-45). Wire는 NONE + unknownFields로 읽는다
        val nominal = NominalError(unknownFields = okio.Buffer().writeByte(0x08).writeByte(0x63).readByteString())
        val reply = RoutableMessage(protobuf_message_as_bytes = FromVCSECMessage(nominalError = nominal).encode().toByteString())
        assertEquals(VehicleResult.Failure(VehicleError.UnknownVcsecError(99)), VcsecResponses.interpret(reply))
    }
```

`VehicleErrorTest`에 두 타입의 `temporary == false`, `mayHaveSucceeded == false`, `UnknownVcsecError(99).message == "vcsec could not execute command: unrecognized error code 99"`. 기존에 `ProtocolFault(MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES)`나 모르는 `genericError` → `VcsecRejected(GENERICERROR_NONE)`을 단언하던 테스트가 있으면(`grep -rn "INSUFFICIENT_PRIVILEGES\|GENERICERROR_NONE" */src/commonTest`) 새 기대값으로 고친다 — 그 변경도 이 Task의 의도다.

Run: `./gradlew :domain:jvmTest :application:jvmTest` — Expected: FAIL(타입 없음).

- [ ] **Step 2: 구현.** `VehicleError.kt`(`KeyNotPaired` 다음):

```kotlin
    /** `MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES`: 키 역할이나 차량 상태(주행 중, 발레 등)가 명령을 허용하지 않음(FR-104). Go `RoutableMessageError`. */
    public data object InsufficientPrivileges : VehicleError {
        override val message: String = "MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES"
        override val mayHaveSucceeded: Boolean = false
        override val temporary: Boolean = false
    }
```

`VcsecRejected` 다음:

```kotlin
    /** 이 라이브러리의 proto 스냅샷에 없는 VCSEC `nominalError.genericError` 코드. Go `NominalVCSECError`(등록되지 않은 코드). */
    public data class UnknownVcsecError(
        /** 차량이 보낸 `GenericError_E` 원시 varint. */
        public val rawCode: Int,
    ) : VehicleError {
        override val message: String get() = "vcsec could not execute command: unrecognized error code $rawCode"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }
```

`ResponseClassifier.faultError`의 분기를 `when (fault) { UNKNOWN_KEY_ID -> KeyNotPaired; INSUFFICIENT_PRIVILEGES -> InsufficientPrivileges; else -> ProtocolFault(fault) }`로, `Results.kt`의 `SignerResult.Fault.toVehicleError()`도 같은 `when`으로. `VcsecResponses`:

```kotlin
    private const val TAG_GENERIC_ERROR = 1 // errors.proto NominalError.genericError

    /** Go `NominalError{NominalVCSECError}`. Wire가 모르는 코드는 `unknownFields`에서 되찾아 [VehicleError.UnknownVcsecError]. */
    internal fun nominalError(error: NominalError): VehicleError =
        error.unknownFields.unknownVarint(TAG_GENERIC_ERROR)?.let { VehicleError.UnknownVcsecError(it) }
            ?: VehicleError.VcsecRejected(error.genericError)
```

`interpret`의 `from.nominalError?.let { return VehicleResult.Failure(VehicleError.VcsecRejected(it.genericError)) }`를 `from.nominalError?.let { return VehicleResult.Failure(nominalError(it)) }`로, `keychainError`의 `private`을 `internal`로. `interpret` KDoc의 "의도적 차이(설계 구체화 11) … M4(L58)에서 재검토" 문단을 "모르는 코드는 `UnknownVcsecError(rawCode)`"로 고친다.

- [ ] **Step 3: 통과·apiDump.** Run: `./gradlew :domain:jvmTest :application:jvmTest :domain:iosSimulatorArm64Test` → PASS. 그다음 단독으로 `./gradlew :domain:apiDump`.

- [ ] **Step 4: 문서(MD + HTML 3언어).** `16-lib-errors.md` 표에 두 줄: `InsufficientPrivileges` — "`INSUFFICIENT_PRIVILEGES` (fault 7): the key's role or the vehicle state does not allow the command (Driver managing keys, Vehicle Monitor sending control commands, driving, valet)" / false / false, 그리고 `UnknownVcsecError(rawCode)` — "A VCSEC `nominalError` code this library does not know" / false / false. `ProtocolFault` 행의 설명에 "(except `UNKNOWN_KEY_ID` → `KeyNotPaired`, `INSUFFICIENT_PRIVILEGES` → `InsufficientPrivileges`)". "Scope" 줄에 M4. HTML en → ko/ja. SDD §6 계층 블록과 §12의 L58 항목을 고친다.

- [ ] **Step 5: 커밋·PR.**

```bash
git add domain application documents docs/sdd/SDD.md
git commit -m "feat(domain): InsufficientPrivileges and UnknownVcsecError error types

Refs: FR-104, FR-103
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

verify.sh → 리뷰(sonnet) → rebase merge.

---

### Task 4: VCSEC 페이로드 빌더와 제어 12종 — `VcsecPayloads`, `VcsecControl`

**브랜치:** `m4/vcsec-control`. **Refs:** FR-040~FR-047, FR-048, FR-049, NFR-017. **원본 대응:** `pkg/vehicle/vcsec.go:151-248`(`addKeyPayload`, `executeRKEAction`, `wakeupRKE`, `RemoteDrive`, `AutoSecureVehicle`, `executeClosureAction`), `pkg/vehicle/actions.go:12-29, 120-132`, `pkg/vehicle/security.go:268-282, 330-357`(`RemoveKey`, `SendAddKeyRequestWithRole` envelope). 매뉴얼 `05-command-catalog.md` A·C, `03-protocol.md` §12.

**Files:**
- Create: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vcsec/VcsecPayloads.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vcsec/VcsecControl.kt`
- Modify: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vcsec/VcsecInfoMapping.kt`(역매핑), `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vehicle/VehicleSession.kt`(`control`), `testing/src/commonMain/kotlin/dev/mirolab/teslable/testing/FakeVehicle.kt`(인증 평문 기록), `tools/ci/ported-files.txt`
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/vcsec/VcsecPayloadsTest.kt`, `VcsecControlTest.kt`, `testing/src/commonTest/kotlin/dev/mirolab/teslable/testing/FakeVehicleTest.kt`

**Interfaces:**
- Consumes: `VcsecCommands.execute(payload, auth, done, flags, timeout): VehicleResult<FromVCSECMessage>`, `VcsecResponses.COMMAND_STATUS_ABSENT`.
- Produces: `internal object VcsecPayloads { rke(action: RKEAction_E): ByteArray; closure(request: ClosureMoveRequest): ByteArray; addKey(publicKey: PublicKeyBytes, role: Role, formFactor: KeyFormFactor /*Wire*/): ByteArray; removeKey(publicKey: PublicKeyBytes): ByteArray; presentKeyEnvelope(unsignedPayload: ByteArray): ByteArray }`; `VcsecInfoMapping.wireRole(role: KeyRole): Role?`, `VcsecInfoMapping.wireFormFactor(formFactor: KeyFormFactor): WireFormFactor`; `public class VcsecControl internal constructor(vcsec: VcsecCommands, timeouts: CommandTimeouts)` — `lock`, `unlock`, `wakeUp`, `remoteDrive`, `autoSecureVehicle`, `openTrunk`, `actuateTrunk`, `closeTrunk`, `openFrunk`, `openTonneau`, `closeTonneau`, `stopTonneau`(모두 `suspend fun x(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit>`); `VehicleSession.control: VcsecControl`; `FakeVehicle.authenticatedPayloads(domain: Domain): List<ByteArray>`.

- [ ] **Step 1: FakeVehicle이 인증 명령의 평문을 기록한다(테스트 도구).** 실패 테스트(`FakeVehicleTest`):

```kotlin
    @Test
    fun recordsPlaintextOfVerifiedCommands() =
        runTest {
            val h = dispatcherHarnessForTesting() // FakeVehicleTest의 기존 연결·핸드셰이크 헬퍼를 쓴다
            h.handshake(Domain.DOMAIN_VEHICLE_SECURITY)
            h.sendAuthenticated(Domain.DOMAIN_VEHICLE_SECURITY, byteArrayOf(0x10, 0x01))
            assertEquals(listOf("1001"), h.fake.authenticatedPayloads(Domain.DOMAIN_VEHICLE_SECURITY).map { it.toHex() })
        }
```

(`FakeVehicleTest`에 이미 있는 연결·핸드셰이크·인증 전송 방식을 그대로 써서 위 세 줄의 뜻 — 핸드셰이크, 인증 명령 하나, 기록된 평문 확인 — 을 만든다. 헬퍼 이름은 그 파일의 것을 따른다.)

구현: `DomainState`에 `val plaintexts = mutableListOf<ByteArray>()`, `handleAuthenticated`의 `VerifyResult.Ok` 분기 첫 줄에 `state.plaintexts += result.plaintext.copyOf()`, 공개 함수:

```kotlin
    /** 검증에 성공한 인증 명령의 평문 페이로드(받은 순서, 사본). 명령 빌더가 Go와 같은 바이트를 보냈는지 확인하는 데 쓴다. */
    public fun authenticatedPayloads(domain: Domain): List<ByteArray> = domains.getValue(domain).plaintexts.map { it.copyOf() }
```

Run: `./gradlew :testing:jvmTest` → PASS. 커밋: `test(testing): FakeVehicle records verified command plaintext`.

- [ ] **Step 2: 실패 테스트 — 페이로드 바이트.** `VcsecPayloadsTest.kt`:

```kotlin
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.keys.Role
import com.tesla.generated.vcsec.ClosureMoveRequest
import com.tesla.generated.vcsec.ClosureMoveType_E
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.SignatureType
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.tesla.generated.vcsec.KeyFormFactor as WireFormFactor

class VcsecPayloadsTest {
    private val key = TestCrypto.clientPublicKey

    @Test
    fun rkeActionsMatchGoBytes() {
        // vcsec.go:174-195 executeRKEAction: UnsignedMessage{RKEAction} (필드 2, oneof — 0값도 싣는다)
        assertEquals("1000", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_UNLOCK).toHex())
        assertEquals("1001", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_LOCK).toHex())
        assertEquals("1014", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_REMOTE_DRIVE).toHex())
        assertEquals("101d", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_AUTO_SECURE_VEHICLE).toHex())
        assertEquals("101e", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_WAKE_VEHICLE).toHex())
    }

    @Test
    fun closureMovesMatchGoBytes() {
        // vcsec.go:217-248 executeClosureAction: trunk→rearTrunk(5), frunk→frontTrunk(6), tonneau→tonneau(8)
        fun hex(r: ClosureMoveRequest) = VcsecPayloads.closure(r).toHex()
        assertEquals("22022801", hex(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE)))
        assertEquals("22022804", hex(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE)))
        assertEquals("22023001", hex(ClosureMoveRequest(frontTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE)))
        assertEquals("22024003", hex(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN)))
        assertEquals("22024004", hex(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE)))
        assertEquals("22024002", hex(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_STOP)))
    }

    @Test
    fun removeKeyMatchesGoBytes() {
        // security.go:268-282: UnsignedMessage{WhitelistOperation(16){removePublicKeyFromWhitelist(2){PublicKeyRaw(1)}}}
        assertEquals("82014512430a41" + key.toByteArray().toHex(), VcsecPayloads.removeKey(key).toHex())
    }

    @Test
    fun addKeyCarriesKeyRoleAndFormFactorFieldByField() {
        // vcsec.go:151-172 addKeyPayload. Go는 oneof를 뒤에 놓을 수 있어 바이트가 아니라 필드로 비교한다(로드맵 공통 규칙)
        val decoded = UnsignedMessage.ADAPTER.decode(VcsecPayloads.addKey(key, Role.ROLE_OWNER, WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE))
        val op = checkNotNull(decoded.VCSEC_WhitelistOperation)
        val change = checkNotNull(op.addKeyToWhitelistAndAddPermissions)
        assertEquals(key.toByteArray().toHex(), checkNotNull(change.key).PublicKeyRaw.hex())
        assertEquals(Role.ROLE_OWNER, change.keyRole)
        assertEquals(0, change.secondsToBeActive)
        assertEquals(WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE, op.metadataForKey?.keyFormFactor)
    }

    @Test
    fun presentKeyEnvelopeIsAToVcsecMessageNotARoutableMessage() {
        // security.go:345-356: ToVCSECMessage{SignedMessage{protobufMessageAsBytes, SIGNATURE_TYPE_PRESENT_KEY}}
        val inner = VcsecPayloads.addKey(key, Role.ROLE_DRIVER, WireFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE)
        val envelope = ToVCSECMessage.ADAPTER.decode(VcsecPayloads.presentKeyEnvelope(inner))
        val signed = checkNotNull(envelope.signedMessage)
        assertEquals(SignatureType.SIGNATURE_TYPE_PRESENT_KEY, signed.signatureType)
        assertEquals(inner.toHex(), signed.protobufMessageAsBytes.hex())
    }

    @Test
    fun modelRolesAndFormFactorsMapToWire() {
        assertEquals(Role.ROLE_OWNER, VcsecInfoMapping.wireRole(KeyRole.OWNER))
        assertEquals(Role.ROLE_FM, VcsecInfoMapping.wireRole(KeyRole.FLEET_MANAGER))
        assertNull(VcsecInfoMapping.wireRole(KeyRole.UNKNOWN))
        assertEquals(WireFormFactor.KEY_FORM_FACTOR_CLOUD_KEY, VcsecInfoMapping.wireFormFactor(KeyFormFactor.CLOUD_KEY))
        assertEquals(WireFormFactor.KEY_FORM_FACTOR_UNKNOWN, VcsecInfoMapping.wireFormFactor(KeyFormFactor.UNKNOWN))
        for (role in KeyRole.entries - KeyRole.UNKNOWN) {
            assertEquals(role.name.replace("FLEET_MANAGER", "FM"), checkNotNull(VcsecInfoMapping.wireRole(role)).name.removePrefix("ROLE_"))
        }
    }
}
```

- [ ] **Step 3: 실패 테스트 — 명령 경로.** `VcsecControlTest.kt`:

```kotlin
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VcsecControlTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    @Test
    fun everyCommandSendsItsGoPayloadAuthenticated() =
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(vcsec), timeout = 1.seconds))
            val c = session.control
            val calls: List<Pair<String, suspend () -> VehicleResult<Unit>>> =
                listOf(
                    "1001" to { c.lock() }, "1000" to { c.unlock() }, "101e" to { c.wakeUp() },
                    "1014" to { c.remoteDrive() }, "101d" to { c.autoSecureVehicle() },
                    "22022801" to { c.openTrunk() }, "22022801" to { c.actuateTrunk() }, "22022804" to { c.closeTrunk() },
                    "22023001" to { c.openFrunk() },
                    "22024003" to { c.openTonneau() }, "22024004" to { c.closeTonneau() }, "22024002" to { c.stopTonneau() },
                )
            for ((_, call) in calls) assertEquals(VehicleResult.Success(Unit), call())
            assertEquals(calls.map { it.first }, h.fake.authenticatedPayloads(vcsec).map { it.toHex() })
        }

    @Test
    fun waitThenEmptyIsSuccessAfterRetry() =
        // vcsec_test.go 다중 응답: WAIT(Busy) → 재시도 → 빈 메시지 = 성공 (FR-048)
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            session.startSession(setOf(vcsec), timeout = 1.seconds)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy()), listOf(FakeVehicle.vcsecEmpty()))
            assertEquals(VehicleResult.Success(Unit), session.control.lock())
            assertEquals(2, h.fake.authenticatedPayloads(vcsec).size)
        }

    @Test
    fun nominalErrorIsFailureWithItsCode() =
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            session.startSession(setOf(vcsec), timeout = 1.seconds)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_CLOSURES_OPEN)))
            assertEquals(
                VehicleResult.Failure(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_CLOSURES_OPEN)),
                session.control.closeTrunk(),
            )
        }

    @Test
    fun lostReplyIsUncertainAndNotResent() =
        // NFR-007, FR-100: 응답이 없으면 Uncertain(Timeout(afterSend = true)), 다시 보내지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            session.startSession(setOf(vcsec), timeout = 1.seconds)
            h.fake.script(vcsec, emptyList())
            assertEquals(VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)), session.control.unlock(timeout = 2.seconds))
            assertEquals(1, h.fake.authenticatedPayloads(vcsec).size)
        }

    @Test
    fun withoutASessionIsNoSessionAndNothingIsSent() =
        runTest {
            val h = dispatcherHarness()
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), VehicleSession(h.dispatcher).control.lock())
            assertEquals(0, h.transport.sent.size)
        }
}
```

Run: `./gradlew :application:jvmTest --tests '*VcsecPayloads*' --tests '*VcsecControl*'` — Expected: 컴파일 실패.

- [ ] **Step 4: 구현.** `VcsecPayloads.kt`:

```kotlin
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0)
// addKeyPayload, executeRKEAction, executeClosureAction payloads; security.go RemoveKey, SendAddKeyRequestWithRole envelope
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.keys.Role
import com.tesla.generated.vcsec.ClosureMoveRequest
import com.tesla.generated.vcsec.KeyFormFactor
import com.tesla.generated.vcsec.KeyMetadata
import com.tesla.generated.vcsec.PermissionChange
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.SignatureType
import com.tesla.generated.vcsec.SignedMessage
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation
import dev.mirolab.teslable.model.PublicKeyBytes
import okio.ByteString.Companion.toByteString

/** VCSEC 요청 페이로드(`UnsignedMessage` 바이트)와 add-key-request 봉투. 필드 하나짜리는 Go와 바이트가 같다(`VcsecPayloadsTest`). */
internal object VcsecPayloads {
    /** Go `executeRKEAction`: `UnsignedMessage{RKEAction}`. */
    fun rke(action: RKEAction_E): ByteArray = UnsignedMessage(RKEAction = action).encode()

    /** Go `executeClosureAction`: `UnsignedMessage{closureMoveRequest}`. */
    fun closure(request: ClosureMoveRequest): ByteArray = UnsignedMessage(closureMoveRequest = request).encode()

    /** Go `addKeyPayload`: `addKeyToWhitelistAndAddPermissions{key, keyRole}` + `metadataForKey{keyFormFactor}`. */
    fun addKey(
        publicKey: PublicKeyBytes,
        role: Role,
        formFactor: KeyFormFactor,
    ): ByteArray =
        UnsignedMessage(
            VCSEC_WhitelistOperation =
                WhitelistOperation(
                    addKeyToWhitelistAndAddPermissions = PermissionChange(key = wireKey(publicKey), keyRole = role),
                    metadataForKey = KeyMetadata(keyFormFactor = formFactor),
                ),
        ).encode()

    /** Go `RemoveKey`: `removePublicKeyFromWhitelist{PublicKeyRaw}`. */
    fun removeKey(publicKey: PublicKeyBytes): ByteArray =
        UnsignedMessage(VCSEC_WhitelistOperation = WhitelistOperation(removePublicKeyFromWhitelist = wireKey(publicKey))).encode()

    /** Go `SendAddKeyRequestWithRole`의 봉투: RoutableMessage가 아닌 `ToVCSECMessage{SignedMessage{PRESENT_KEY}}`(인증 없음). */
    fun presentKeyEnvelope(unsignedPayload: ByteArray): ByteArray =
        ToVCSECMessage(
            signedMessage =
                SignedMessage(
                    protobufMessageAsBytes = unsignedPayload.toByteString(),
                    signatureType = SignatureType.SIGNATURE_TYPE_PRESENT_KEY,
                ),
        ).encode()

    private fun wireKey(publicKey: PublicKeyBytes): PublicKey = PublicKey(PublicKeyRaw = publicKey.toByteArray().toByteString())
}
```

`VcsecInfoMapping`에 역매핑(기존 `role()`/`formFactor()` 바로 아래):

```kotlin
    /** 모델 역할 → Wire. [KeyRole.UNKNOWN]은 보낼 값이 없어 null. */
    fun wireRole(role: KeyRole): Role? =
        when (role) {
            KeyRole.NONE -> Role.ROLE_NONE
            KeyRole.SERVICE -> Role.ROLE_SERVICE
            KeyRole.OWNER -> Role.ROLE_OWNER
            KeyRole.DRIVER -> Role.ROLE_DRIVER
            KeyRole.FLEET_MANAGER -> Role.ROLE_FM
            KeyRole.VEHICLE_MONITOR -> Role.ROLE_VEHICLE_MONITOR
            KeyRole.CHARGING_MANAGER -> Role.ROLE_CHARGING_MANAGER
            KeyRole.GUEST -> Role.ROLE_GUEST
            KeyRole.UNKNOWN -> null
        }

    /** 모델 형태 → Wire. */
    fun wireFormFactor(formFactor: KeyFormFactor): WireFormFactor =
        when (formFactor) {
            KeyFormFactor.UNKNOWN -> WireFormFactor.KEY_FORM_FACTOR_UNKNOWN
            KeyFormFactor.NFC_CARD -> WireFormFactor.KEY_FORM_FACTOR_NFC_CARD
            KeyFormFactor.IOS_DEVICE -> WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE
            KeyFormFactor.ANDROID_DEVICE -> WireFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE
            KeyFormFactor.CLOUD_KEY -> WireFormFactor.KEY_FORM_FACTOR_CLOUD_KEY
        }
```

`VcsecControl.kt`:

```kotlin
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0) — executeRKEAction, wakeupRKE, RemoteDrive, AutoSecureVehicle,
// executeClosureAction; pkg/vehicle/actions.go ActuateTrunk, OpenTrunk, CloseTrunk, OpenFrunk, OpenTonneau, CloseTonneau, StopTonneau
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.vcsec.ClosureMoveRequest
import com.tesla.generated.vcsec.ClosureMoveType_E
import com.tesla.generated.vcsec.RKEAction_E
import dev.mirolab.teslable.application.vehicle.CommandTimeouts
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import kotlin.time.Duration

/**
 * VCSEC 제어 명령(FR-040~FR-047). 모두 GCM 인증 + [VcsecResponses.COMMAND_STATUS_ABSENT](`commandStatus`가 없는 메시지가 최종 성공)이며
 * [VcsecCommands]의 연결당 직렬화·재시도·D29 시간 제한을 따른다. 세션이 없으면 `Failure(NoSession)`(핸드셰이크는 호출자 몫, Go와 같음).
 * `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 상태를 확인한다(FR-102).
 */
public class VcsecControl internal constructor(
    private val vcsec: VcsecCommands,
    private val timeouts: CommandTimeouts,
) {
    /** 잠금(FR-040). */
    public suspend fun lock(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> = rke(RKEAction_E.RKE_ACTION_LOCK, timeout)

    /** 해제(FR-041). */
    public suspend fun unlock(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> = rke(RKEAction_E.RKE_ACTION_UNLOCK, timeout)

    /** 깨우기(FR-042, Go `wakeupRKE`). VCSEC 세션만 필요. */
    public suspend fun wakeUp(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> = rke(RKEAction_E.RKE_ACTION_WAKE_VEHICLE, timeout)

    /** 키 없이 운전(FR-043). */
    public suspend fun remoteDrive(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        rke(RKEAction_E.RKE_ACTION_REMOTE_DRIVE, timeout)

    /** Model X 문 닫고 잠금(FR-044). */
    public suspend fun autoSecureVehicle(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        rke(RKEAction_E.RKE_ACTION_AUTO_SECURE_VEHICLE, timeout)

    /** 트렁크 열기(FR-045) — [actuateTrunk]와 같은 토글 페이로드. */
    public suspend fun openTrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> = actuateTrunk(timeout)

    /** 트렁크 토글(FR-045, `rearTrunk = MOVE`). */
    public suspend fun actuateTrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE), timeout)

    /** 트렁크 닫기(FR-045). 전동 트렁크 차량만. */
    public suspend fun closeTrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE), timeout)

    /** 프렁크 열기(FR-046). 원격으로 닫는 명령은 없다. */
    public suspend fun openFrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(frontTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE), timeout)

    /** 토노 열기(FR-047, Cybertruck). */
    public suspend fun openTonneau(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN), timeout)

    /** 토노 닫기(FR-047). */
    public suspend fun closeTonneau(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE), timeout)

    /** 토노 멈춤(FR-047). */
    public suspend fun stopTonneau(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_STOP), timeout)

    private suspend fun rke(
        action: RKEAction_E,
        timeout: Duration,
    ): VehicleResult<Unit> = execute(VcsecPayloads.rke(action), timeout)

    private suspend fun closure(
        request: ClosureMoveRequest,
        timeout: Duration,
    ): VehicleResult<Unit> = execute(VcsecPayloads.closure(request), timeout)

    private suspend fun execute(
        payload: ByteArray,
        timeout: Duration,
    ): VehicleResult<Unit> =
        when (val result = vcsec.execute(payload, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = timeout)) {
            is VehicleResult.Success -> VehicleResult.Success(Unit)
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }
}
```

`VehicleSession`에 `public val control: VcsecControl = VcsecControl(vcsec, timeouts)`(KDoc: "VCSEC 제어 12종(FR-040~047). [vcsec]의 직렬화를 공유한다"). `tools/ci/ported-files.txt`에 두 파일을 더한다.

- [ ] **Step 5: 통과·apiDump·커밋.** Run: `./gradlew :application:jvmTest :application:iosSimulatorArm64Test` → PASS, 단독 `./gradlew :application:apiDump`. 문서 영향 없음 — 파사드 미노출(Task 7).

```bash
git add application testing tools/ci/ported-files.txt
git commit -m "feat(application): VCSEC control commands (lock, unlock, wake, remote drive, auto secure, trunk, frunk, tonneau)

Refs: FR-040, FR-041, FR-042, FR-043, FR-044, FR-045, FR-046, FR-047
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(`testing` 변경은 Step 1에서 이미 커밋했으면 여기서 빠진다.) PR → verify.sh → 리뷰(sonnet) → rebase merge.

---

### Task 5: 키 관리 — `addKey`, `removeKey`, `sessionInfo`, `SessionInfoSummary`

**브랜치:** `m4/key-management`. **Refs:** FR-024, FR-026, FR-027. **원본 대응:** `pkg/vehicle/security.go:243-282`(`AddKey`, `AddKeyWithRole`, `RemoveKey`), `pkg/vehicle/vcsec.go:132-149`(`isWhitelistOperationComplete`, `executeWhitelistOperation`), `pkg/vehicle/vehicle.go:120-142`(`SessionInfo`), `internal/dispatcher/dispatcher.go` `checkForSessionUpdate`. 매뉴얼 `05-command-catalog.md` B, `10-porting-guide.md` §10, `08-errors.md` §3.7.

**Files:**
- Create: `domain/src/commonMain/kotlin/dev/mirolab/teslable/model/SessionInfoSummary.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vcsec/KeyManagement.kt`
- Modify: `application/…/dispatcher/Dispatcher.kt`(`querySessionInfo`), `PendingRequest.kt`(`acceptsSessionInfo`), `application/…/vehicle/VehicleSession.kt`(`keys`), `tools/ci/ported-files.txt`
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/vcsec/KeyManagementTest.kt`

**Interfaces:**
- Consumes: `VcsecPayloads.addKey/removeKey`, `VcsecInfoMapping.wireRole/wireFormFactor`(Task 4), `VcsecResponses.WHITELIST_OPERATION_COMPLETE`, `ResponseClassifier.protocolError`, `withAttemptTimeout`.
- Produces: `public data class SessionInfoSummary(val counter: UInt, val clockTime: UInt, val vehiclePublicKey: PublicKeyBytes?)`; `Dispatcher.querySessionInfo(domain: Domain, publicKey: PublicKeyBytes): VehicleResult<PendingRequest>`; `public class KeyManagement internal constructor(dispatcher, vcsec, timeouts)` — `addKey(publicKey: PublicKeyBytes, role: KeyRole, formFactor: KeyFormFactor, timeout: Duration = …): VehicleResult<Unit>`, `removeKey(publicKey: PublicKeyBytes, timeout: Duration = …): VehicleResult<Unit>`, `sessionInfo(publicKey: PublicKeyBytes, domain: Domain, timeout: Duration = …): VehicleResult<SessionInfoSummary>`; `VehicleSession.keys: KeyManagement`.

- [ ] **Step 1: 실패 테스트.** `KeyManagementTest.kt`:

```kotlin
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import dev.mirolab.teslable.application.cache.SessionCacheSync
import dev.mirolab.teslable.application.dispatcher.HandshakeFlow
import dev.mirolab.teslable.application.dispatcher.SessionEvent
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class KeyManagementTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val friend = TestCrypto.goKnownVerifierKey().publicKey

    private suspend fun ready(h: dev.mirolab.teslable.application.dispatcher.DispatcherHarness): VehicleSession =
        VehicleSession(h.dispatcher).also { assertIs<VehicleResult.Success<Unit>>(it.startSession(setOf(vcsec), timeout = 1.seconds)) }

    @Test
    fun addKeySucceedsWhenTheWhitelistStatusArrives() =
        // isWhitelistOperationComplete: 중간 응답(signedMessageStatus)은 건너뛰고 whitelistOperationStatus NONE에서 끝난다
        runTest {
            val h = dispatcherHarness()
            val session = ready(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecWhitelistStatus(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE)))
            assertEquals(VehicleResult.Success(Unit), session.keys.addKey(friend, KeyRole.DRIVER, KeyFormFactor.ANDROID_DEVICE))
            assertTrue(h.fake.authenticatedPayloads(vcsec).single().toHex().startsWith("8201")) // WhitelistOperation(16)
        }

    @Test
    fun addKeyRejectedCarriesTheKeychainCode() =
        runTest {
            val h = dispatcherHarness()
            val session = ready(h)
            val code = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NO_PERMISSION_TO_ADD
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecWhitelistStatus(code)))
            assertEquals(VehicleResult.Failure(VehicleError.KeychainRejected(code)), session.keys.addKey(friend, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE))
        }

    @Test
    fun unknownRoleIsInvalidArgumentAndNothingIsSent() =
        runTest {
            val h = dispatcherHarness()
            val session = ready(h)
            val sentBefore = h.transport.sent.size
            assertIs<VehicleResult.Failure>(session.keys.addKey(friend, KeyRole.UNKNOWN, KeyFormFactor.NFC_CARD)).also {
                assertIs<VehicleError.InvalidArgument>(it.error)
            }
            assertEquals(sentBefore, h.transport.sent.size)
        }

    @Test
    fun removeKeySendsGoPayloadAndNeedsASession() =
        runTest {
            val h = dispatcherHarness()
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), VehicleSession(h.dispatcher).keys.removeKey(friend))
            val session = ready(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecWhitelistStatus(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE)))
            assertEquals(VehicleResult.Success(Unit), session.keys.removeKey(friend))
            assertEquals("82014512430a41" + friend.toByteArray().toHex(), h.fake.authenticatedPayloads(vcsec).single().toHex())
        }

    @Test
    fun sessionInfoWorksWithoutAPrivateKey() =
        // Go SessionInfo는 AuthMethodNone — 키 없이 연결해도 등록 여부를 물을 수 있다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val summary = assertIs<VehicleResult.Success<SessionInfoSummary>>(VehicleSession(h.dispatcher).keys.sessionInfo(friend, vcsec)).value
            assertEquals(TestCrypto.vehicleKey().publicKey, summary.vehiclePublicKey)
        }

    @Test
    fun notEnrolledKeyIsKeyNotPaired() =
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptHandshake(infotainment, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            assertEquals(VehicleResult.Failure(VehicleError.KeyNotPaired), VehicleSession(h.dispatcher).keys.sessionInfo(friend, infotainment))
        }

    @Test
    fun lostReplyIsUncertainTimeout() =
        runTest {
            val h = dispatcherHarness()
            h.fake.sleep(setOf(infotainment))
            assertEquals(
                VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)),
                VehicleSession(h.dispatcher).keys.sessionInfo(friend, infotainment, timeout = 3.seconds),
            )
        }

    @Test
    fun ownKeyQueryEstablishesTheSessionLikeGo() =
        // checkForSessionUpdate는 이 연결의 키로 물은 응답의 세션정보를 반영한다(Go와 같음, 설계 구체화 3)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<SessionInfoSummary>>(VehicleSession(h.dispatcher).keys.sessionInfo(TestCrypto.clientPublicKey, vcsec))
            assertTrue(checkNotNull(h.dispatcher.session(vcsec)).isReady)
        }

    @Test
    fun foreignKeyQueryLeavesOurCachedSessionAlone() =
        // Review Focus 2: 다른 키의 세션정보 태그는 우리 키로 검증되지 않는다 — 캐시 복원 세션을 "오래됨"으로 보면 안 된다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(vcsec))
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            sync.store(first.dispatcher)
            val h = dispatcherHarness(fake = fake, start = false)
            sync.load(h.dispatcher)
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            h.dispatcher.start()
            assertIs<VehicleResult.Success<SessionInfoSummary>>(VehicleSession(h.dispatcher).keys.sessionInfo(friend, vcsec))
            runCurrent()
            val session = checkNotNull(h.dispatcher.session(vcsec))
            assertTrue(session.isUnconfirmedCacheRestore)
            assertFalse(session.isReportedStale)
            assertTrue(events.isEmpty())
            assertFalse(h.logger.contains("Session info error"))
        }
}
```

Run: `./gradlew :application:jvmTest --tests '*KeyManagement*'` — Expected: 컴파일 실패.

- [ ] **Step 2: 구현.** `SessionInfoSummary.kt`:

```kotlin
package dev.mirolab.teslable.model

/**
 * 공개키 하나에 대한 차량 도메인의 세션정보 요약(FR-024, Go `signatures.SessionInfo`). `Vehicle.sessionInfo`가 `status == OK`일 때만 돌려준다
 * (`KEY_NOT_ON_WHITELIST`는 `Failure(KeyNotPaired)`). 페어링 뒤 Infotainment 동기화를 기다리는 데 쓴다.
 */
public data class SessionInfoSummary(
    /** 차량이 마지막으로 본 counter. */
    val counter: UInt,
    /** 차량 시계(epoch 시작부터의 초). */
    val clockTime: UInt,
    /** 차량 도메인 공개키. 형식이 틀리면 null. */
    val vehiclePublicKey: PublicKeyBytes?,
)
```

`PendingRequest` 생성자에 `internal val acceptsSessionInfo: Boolean = true`를 더한다(KDoc: "false면 응답의 세션정보로 세션을 갱신하지 않는다 — 다른 공개키로 물은 `sessionInfo`, 설계 구체화 3"). `Dispatcher`:
  - `register(key, requestHash, acceptsSessionInfo: Boolean = true)`가 그 값으로 `PendingRequest`를 만든다.
  - `send`의 본문을 `private suspend fun sendWith(message, auth, lifetime, acceptsSessionInfo: Boolean)`로 옮기고 공개 `send`는 `sendWith(…, acceptsSessionInfo = true)`를 부른다(동작 동일 — 이 Task 안에서 `struct:` 커밋으로 먼저 나눈다).
  - 새 공개 함수:

```kotlin
        /**
         * Go `Vehicle.SessionInfo`의 전송(vehicle.go:120-125): 임의 공개키의 세션정보 요청을 인증 없이 보낸다. 개인키가 없어도 된다.
         * 이 연결의 키가 아니면 응답의 세션정보로 세션을 갱신하지 않는다(설계 구체화 3 — Go는 갱신을 시도하고 태그 불일치를 경고한다).
         */
        public suspend fun querySessionInfo(
            domain: Domain,
            publicKey: PublicKeyBytes,
        ): VehicleResult<PendingRequest> =
            sendWith(sessionInfoRequest(domain, publicKey), AuthMethod.NONE, DEFAULT_LIFETIME, acceptsSessionInfo = publicKey == privateKey?.publicKey)
```

  - `checkForSessionUpdate`의 `privateKey == null` 검사 바로 뒤: `if (!handler.acceptsSessionInfo) { logger.log(LogLevel.DEBUG, TAG) { "[$id] Not applying session info from a query for another public key" }; return }`.

`KeyManagement.kt`:

```kotlin
// Ported from vehicle-command@a4b43c1 pkg/vehicle/security.go (Apache-2.0) — AddKeyWithRole, RemoveKey; vcsec.go executeWhitelistOperation;
// vehicle.go SessionInfo
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.dispatcher.valueOr
import dev.mirolab.teslable.application.dispatcher.withAttemptTimeout
import dev.mirolab.teslable.application.vehicle.CommandTimeouts
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.protocol.ResponseClassifier
import dev.mirolab.teslable.protocol.decodeOrNull
import kotlin.time.Duration

/** 화이트리스트 관리와 등록 확인(FR-024, FR-026, FR-027). whitelist 작업은 [VcsecCommands]의 직렬화를 공유한다. */
@OptIn(InternalTeslableApi::class)
public class KeyManagement internal constructor(
    private val dispatcher: Dispatcher,
    private val vcsec: VcsecCommands,
    private val timeouts: CommandTimeouts,
) {
    /** Go `AddKeyWithRole`: 등록된 Owner 키로 다른 공개키를 추가(인증). [role]이 `UNKNOWN`이면 보내지 않고 `InvalidArgument`. */
    public suspend fun addKey(
        publicKey: PublicKeyBytes,
        role: KeyRole,
        formFactor: KeyFormFactor,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<Unit> {
        val wireRole =
            VcsecInfoMapping.wireRole(role)
                ?: return VehicleResult.Failure(VehicleError.InvalidArgument("role UNKNOWN cannot be enrolled"))
        return whitelist(VcsecPayloads.addKey(publicKey, wireRole, VcsecInfoMapping.wireFormFactor(formFactor)), timeout)
    }

    /** Go `RemoveKey`(인증). Driver 키는 차량이 거부한다(`InsufficientPrivileges` 또는 `KeychainRejected`). */
    public suspend fun removeKey(
        publicKey: PublicKeyBytes,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<Unit> = whitelist(VcsecPayloads.removeKey(publicKey), timeout)

    /**
     * Go `SessionInfo`: [publicKey]에 대한 [domain]의 세션정보. 등록돼 있지 않으면 `Failure(KeyNotPaired)`. 응답 대기 중 시간 초과는
     * `Uncertain(Timeout(afterSend = true))`(M3 조회와 같다). 다시 보내지 않는다(Go도 한 번 보낸다).
     */
    public suspend fun sessionInfo(
        publicKey: PublicKeyBytes,
        domain: Domain,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<SessionInfoSummary> =
        withAttemptTimeout(timeout) { setAwaiting ->
            val pending = dispatcher.querySessionInfo(domain, publicKey).valueOr { return@withAttemptTimeout it.toResult() }
            setAwaiting(true)
            pending.use { interpret(it.receive()) }
        }

    private suspend fun whitelist(
        payload: ByteArray,
        timeout: Duration,
    ): VehicleResult<Unit> =
        when (val result = vcsec.execute(payload, AuthMethod.GCM, VcsecResponses.WHITELIST_OPERATION_COMPLETE, timeout = timeout)) {
            is VehicleResult.Success -> VehicleResult.Success(Unit)
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }

    /** Go `SessionInfo`의 응답 해석: `GetError` → `session_info` 없음은 `ErrBadResponse` → 디코딩. */
    private fun interpret(reply: RoutableMessage): VehicleResult<SessionInfoSummary> {
        ResponseClassifier.protocolError(reply)?.let { return it.toResult() }
        val encoded = reply.session_info ?: return VehicleResult.Failure(VehicleError.BadResponse("missing session_info"))
        val info =
            SessionInfo.ADAPTER.decodeOrNull(encoded.toByteArray())
                ?: return VehicleResult.Failure(VehicleError.BadResponse("session info: undecodable"))
        val vehicleKey =
            try {
                PublicKeyBytes(info.publicKey.toByteArray())
            } catch (ignored: IllegalArgumentException) {
                null
            }
        return VehicleResult.Success(SessionInfoSummary(info.counter.toUInt(), info.clock_time.toUInt(), vehicleKey))
    }
}
```

`VehicleSession`에 `public val keys: KeyManagement = KeyManagement(dispatcher, vcsec, timeouts)`. `ported-files.txt`에 `KeyManagement.kt`.

- [ ] **Step 3: 통과·apiDump.** Run: `./gradlew :domain:jvmTest :application:jvmTest :application:iosSimulatorArm64Test` → PASS. 단독: `./gradlew :domain:apiDump`, `./gradlew :application:apiDump`.

- [ ] **Step 4: SDD 기록·커밋.** §12: "다른 공개키로 물은 `sessionInfo` 응답의 세션정보는 반영하지 않는다(Go는 반영을 시도하고 경고한다) — 캐시 복원 세션의 오판 방지". 문서: `SessionInfoSummary`는 `:domain` 공개 타입이지만 파사드 노출은 Task 7 → "문서 영향 없음 — Task 7에서 13-lib-pairing 갱신".

```bash
git commit -m "struct(application): route Dispatcher.send through sendWith (no behaviour change)
..."   # sendWith 분리만 먼저
git commit -m "feat(application): addKey, removeKey and sessionInfo for any public key

Refs: FR-024, FR-026, FR-027
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

PR → verify.sh → 리뷰(opus — 디스패처 수정) → rebase merge.

---

### Task 6: 페어링 — `unroutedFrames`·`sendUnrouted`, `Pairing.addKeyRequest`, `PairingEvent`

**브랜치:** `m4/pairing`. **Refs:** FR-022, FR-023, FR-049, ADR-0009. **원본 대응:** `pkg/vehicle/security.go:310-357`(`SendAddKeyRequest`, `SendAddKeyRequestWithRole`), `pkg/vehicle/vcsec.go:151-172`(`addKeyPayload`). 응답 읽기는 원본에 없다(ADR-0009, SDD §4). 매뉴얼 `10-porting-guide.md` §10, `03-protocol.md` §11("NFC card pairing request: WAIT = waiting for card tap"), `09-recipes.md` R4.

**Files:**
- Create: `domain/src/commonMain/kotlin/dev/mirolab/teslable/model/PairingEvent.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/pairing/Pairing.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/pairing/PairingEvents.kt`
- Modify: `application/…/dispatcher/Dispatcher.kt`(탭·직접 전송), `application/…/vcsec/VcsecCommands.kt`(`exclusive`), `application/…/vehicle/VehicleSession.kt`(`pairing`), `testing/…/FakeVehicle.kt`(PRESENT_KEY 대본), `tools/ci/ported-files.txt`
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/pairing/PairingTest.kt`, `PairingEventsTest.kt`, `application/…/dispatcher/DispatcherTest.kt`, `testing/…/FakeVehicleTest.kt`, `domain/…/model/PairingEventTest.kt`

**Interfaces:**
- Consumes: `VcsecPayloads.addKey/presentKeyEnvelope`, `VcsecInfoMapping.wireRole/wireFormFactor`(Task 4), `VcsecResponses.keychainError`/`nominalError`(Task 3), Task 1의 `Routing.Dropped(MISSING_SOURCE)`·해석 불가 분기.
- Produces: `public sealed interface PairingEvent { Sent; WaitingForTap; Accepted; Rejected(error: VehicleError); Unknown(raw); Failed(error: VehicleError); TimedOut }` + `public val PairingEvent.isTerminal: Boolean`; `Dispatcher.unroutedFrames: SharedFlow<ByteArray>`, `Dispatcher.sendUnrouted(frame: ByteArray): VehicleResult<Unit>`; `VcsecCommands.exclusive(block)`(internal); `public class Pairing internal constructor(dispatcher, vcsec)` — `addKeyRequest(publicKey: PublicKeyBytes, role: KeyRole, formFactor: KeyFormFactor, timeout: Duration): Flow<PairingEvent>`; `VehicleSession.pairing: Pairing`; `FakeVehicle.presentKeys: List<ToVCSECMessage>`, `FakeVehicle.scriptPresentKey(vararg frames: ByteArray)`, `FakeVehicle.presentKeyWait(): ByteArray`, `FakeVehicle.presentKeyResult(info): ByteArray`.

- [ ] **Step 1: `PairingEvent`(도메인) — 실패 테스트와 구현.** `PairingEventTest`: `Unknown(byteArrayOf(1,2))`는 내용이 같으면 같고(`equals`/`hashCode`), `raw`는 사본이며, `toString()`은 `"Unknown(2B)"`; `isTerminal`은 `Accepted`·`Rejected`·`Failed`·`TimedOut`만 true. 구현:

```kotlin
package dev.mirolab.teslable.model

/**
 * `Vehicle.addKeyRequest`의 진행 상태(FR-023, ADR-0009). Go는 요청을 보내고 응답을 읽지 않는다 — 이 이벤트는 원본에 없는 동작이며,
 * 실차에서 형식이 확인되기 전까지 해석하지 못한 프레임은 [Unknown]으로 원시 바이트를 그대로 준다. 끝 이벤트([isTerminal]) 뒤 Flow가 끝난다.
 * 등록 확정은 `Vehicle.sessionInfo(publicKey, INFOTAINMENT)`로 확인한다.
 */
public sealed interface PairingEvent {
    /** 요청을 보냈다. 사용자가 중앙 콘솔에 키카드를 태그하고 화면에서 승인해야 한다. */
    public data object Sent : PairingEvent

    /** 차량이 카드 태그를 기다린다(`commandStatus.operationStatus == WAIT`). */
    public data object WaitingForTap : PairingEvent

    /** 차량이 등록을 받아들였다(`whitelistOperationStatus` NONE). Infotainment 동기화는 수십 초 더 걸릴 수 있다. */
    public data object Accepted : PairingEvent

    /** 차량이 거부했다(`KeychainRejected`, `UnknownKeychainCode`, `VcsecRejected`, `UnknownVcsecError`). */
    public data class Rejected(
        /** 원인. */
        val error: VehicleError,
    ) : PairingEvent

    /** 해석하지 못한 프레임(실차 형식 확인용). */
    public class Unknown(
        raw: ByteArray,
    ) : PairingEvent {
        private val bytes = raw.copyOf()

        /** 받은 바이트(사본). */
        public val raw: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?): Boolean = other is Unknown && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Unknown(${bytes.size}B)"
    }

    /** 보내지 못했다(연결 없음, 쓰기 실패, 잘못된 인자). */
    public data class Failed(
        /** 원인. */
        val error: VehicleError,
    ) : PairingEvent

    /** 시간 안에 끝 응답이 오지 않았다. 등록됐는지는 `sessionInfo`로 확인한다. */
    public data object TimedOut : PairingEvent
}

/** 이 이벤트 뒤에 더 오는 것이 없는지. */
public val PairingEvent.isTerminal: Boolean
    get() =
        this is PairingEvent.Accepted || this is PairingEvent.Rejected || this is PairingEvent.Failed || this is PairingEvent.TimedOut
```

- [ ] **Step 2: 이벤트 해석 — 실패 테스트와 구현.** `PairingEventsTest`:

```kotlin
class PairingEventsTest {
    private fun frame(m: FromVCSECMessage) = m.encode()

    @Test
    fun waitIsWaitingForTap() =
        assertEquals(
            PairingEvent.WaitingForTap,
            PairingEvents.interpret(frame(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT)))),
        )

    @Test
    fun whitelistNoneIsAccepted() =
        assertEquals(PairingEvent.Accepted, PairingEvents.interpret(FakeVehicle.presentKeyResult(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE)))

    @Test
    fun whitelistCodeIsRejected() {
        val code = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
        assertEquals(PairingEvent.Rejected(VehicleError.KeychainRejected(code)), PairingEvents.interpret(FakeVehicle.presentKeyResult(code)))
    }

    @Test
    fun nominalErrorIsRejected() =
        assertEquals(
            PairingEvent.Rejected(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_UNAUTHORIZED)),
            PairingEvents.interpret(frame(FromVCSECMessage(nominalError = NominalError(genericError = GenericError_E.GENERICERROR_UNAUTHORIZED)))),
        )

    @Test
    fun framesWithoutCommandStatusOrNominalErrorAreIgnored() {
        // Review Focus 1: 브로드캐스트 vehicleStatus, authenticationRequest(모르는 필드), 중간 OK·ERROR 응답
        assertNull(PairingEvents.interpret(frame(FromVCSECMessage(vehicleStatus = VehicleStatus()))))
        assertNull(PairingEvents.interpret("4a00".hexToBytes()))
        assertNull(PairingEvents.interpret(frame(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_OK)))))
        assertNull(PairingEvents.interpret(frame(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_ERROR)))))
    }

    @Test
    fun undecodableIsUnknownWithTheRawBytes() =
        assertEquals(PairingEvent.Unknown(byteArrayOf(0xFF.toByte())), PairingEvents.interpret(byteArrayOf(0xFF.toByte())))
}
```

(`OPERATIONSTATUS_ERROR` 단독은 매뉴얼 `03-protocol.md` §11의 "legacy — 다음 메시지의 구체 오류를 기다린다"를 따른다.) 구현 `PairingEvents.kt`:

```kotlin
package dev.mirolab.teslable.application.pairing

import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.vcsec.VcsecResponses
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.protocol.decodeOrNull

/**
 * 원본에 없음 — 감싸지 않은 VCSEC 프레임 → 페어링 이벤트(ADR-0009, SDD §4, 설계 구체화 4). null이면 페어링과 무관한 프레임(무시).
 * 순서: 해석 불가 → `Unknown`; `nominalError` → `Rejected`; `commandStatus` 없음 → 무시; `whitelistOperationStatus` → `Accepted`/`Rejected`;
 * `WAIT` → `WaitingForTap`; 그 밖(OK 중간 응답, legacy ERROR) → 무시.
 */
@OptIn(InternalTeslableApi::class)
internal object PairingEvents {
    fun interpret(frame: ByteArray): PairingEvent? {
        val from = FromVCSECMessage.ADAPTER.decodeOrNull(frame) ?: return PairingEvent.Unknown(frame)
        from.nominalError?.let { return PairingEvent.Rejected(VcsecResponses.nominalError(it)) }
        val status = from.commandStatus ?: return null
        status.whitelistOperationStatus?.let { whitelist ->
            return VcsecResponses.keychainError(whitelist)?.let { PairingEvent.Rejected(it) } ?: PairingEvent.Accepted
        }
        return if (status.operationStatus == OperationStatus_E.OPERATIONSTATUS_WAIT) PairingEvent.WaitingForTap else null
    }
}
```

- [ ] **Step 3: FakeVehicle PRESENT_KEY 대본.** 실패 테스트(`FakeVehicleTest`): `ToVCSECMessage{PRESENT_KEY}`를 보내면 `presentKeys`에 기록되고 `scriptPresentKey`로 넣은 프레임이 그 순서대로 전송에 배달되며, `received`(RoutableMessage 목록)에는 들어가지 않는다; 잠든 도메인과 무관하다. 구현(`FakeVehicle`):

```kotlin
    private val presentKeyRequests = mutableListOf<ToVCSECMessage>()
    private val presentKeyScript = ArrayDeque<List<ByteArray>>()

    /** 받은 add-key-request 봉투(`ToVCSECMessage{PRESENT_KEY}`). */
    public val presentKeys: List<ToVCSECMessage> get() = presentKeyRequests.toList()

    /** 다음 add-key-request에 감싸지 않은 프레임들로 답한다(요청 하나에 호출 하나). 대본이 없으면 답하지 않는다(Go 차량도 사용자가 태그할 때까지 기다린다). */
    public fun scriptPresentKey(vararg frames: ByteArray) {
        presentKeyScript.addLast(frames.map { it.copyOf() })
    }
```

`handle`의 첫 줄(RoutableMessage 해석보다 먼저):

```kotlin
        // ToVCSECMessage(필드 1)는 RoutableMessage의 모르는 필드로도 읽히므로 먼저 판별한다(빌드 사실)
        val presentKey = ToVCSECMessage.ADAPTER.decodeOrNull(bytes)
        if (presentKey?.signedMessage?.signatureType == SignatureType.SIGNATURE_TYPE_PRESENT_KEY) {
            presentKeyRequests += presentKey
            presentKeyScript.removeFirstOrNull()?.forEach { transport.deliver(it) }
            return
        }
```

동반 객체 도우미:

```kotlin
        /** 카드 태그 대기 프레임(감싸지 않은 `FromVCSECMessage{commandStatus{WAIT}}`). */
        public fun presentKeyWait(): ByteArray =
            FromVCSECMessage(commandStatus = CommandStatus(operationStatus = VcsecOperationStatus.OPERATIONSTATUS_WAIT)).encode()

        /** 페어링 결과 프레임(감싸지 않은 `whitelistOperationStatus{info}`; NONE이면 OK). */
        public fun presentKeyResult(info: WhitelistOperation_information_E): ByteArray = checkNotNull(vcsecWhitelistStatus(info).payload)
```

커밋: `test(testing): FakeVehicle answers add-key-request with scripted unwrapped frames`.

- [ ] **Step 4: Dispatcher 탭 — 실패 테스트.** `DispatcherTest`:

```kotlin
    @Test
    fun unroutedFramesAreTappedAndStillDropped() =
        // ADR-0009: 원시 프레임 탭은 파싱 단계에서 분기하고 드롭 규칙은 그대로다
        runTest {
            val h = dispatcherHarness()
            val tapped = mutableListOf<String>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.unroutedFrames.collect { tapped += it.toHex() } }
            h.transport.deliver(FakeVehicle.presentKeyWait())   // 출발지 없음
            h.transport.deliver(byteArrayOf(0xFF.toByte()))     // 해석 불가
            h.transport.deliver(RoutableMessage.ADAPTER.encode(RoutableMessage(from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY))))
            runCurrent()
            assertEquals(listOf(FakeVehicle.presentKeyWait().toHex(), "ff"), tapped) // 출발지가 있는 메시지는 탭하지 않는다
            assertTrue(h.logger.contains("Dropping message with missing source"))
            assertTrue(h.logger.contains("Dropping unparseable message"))
        }

    @Test
    fun sendUnroutedNeedsAListeningDispatcher() =
        runTest {
            val h = dispatcherHarness(start = false)
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), h.dispatcher.sendUnrouted(byteArrayOf(1)))
        }
```

구현(`Dispatcher`):

```kotlin
        private val unrouted =
            MutableSharedFlow<ByteArray>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * RoutableMessage로 해석되지 않거나 출발지가 없는 프레임(사본). add-key-request 진행 상태(ADR-0009)가 구독한다. 구독자가 없으면 버려지고,
         * 이 프레임의 드롭 규칙·로그는 그대로다. 수신 코루틴은 `tryEmit`만 한다(SDD §5).
         */
        public val unroutedFrames: SharedFlow<ByteArray> = unrouted.asSharedFlow()

        /** 감싸지 않은 프레임을 그대로 보낸다(Go `v.conn.Send`, security.go:356). 재시도하지 않는다(Go와 같음). */
        public suspend fun sendUnrouted(frame: ByteArray): VehicleResult<Unit> {
            if (!isListening) return VehicleResult.Failure(VehicleError.NotConnected)
            return transport.send(frame.copyOf())
        }
```

`process`의 해석 불가 분기와 `Routing.Dropped(DropReason.MISSING_SOURCE)` 분기에 `unrouted.tryEmit(bytes.copyOf())`를 더한다(로그 뒤).

- [ ] **Step 5: `Pairing` — 실패 테스트.** `PairingTest.kt`:

```kotlin
package dev.mirolab.teslable.application.pairing

import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.FromVCSECMessage
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.WhitelistSummary
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PairingTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val key = TestCrypto.clientPublicKey
    private val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE

    @Test
    fun sendsThePresentKeyEnvelopeLikeGoWithoutASession() =
        // security.go:330-357. 키 없이 연결해도(Go R4는 NewVehicle(conn, nil, nil)) 보낼 수 있다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.scriptPresentKey(FakeVehicle.presentKeyResult(none))
            VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            val inner = UnsignedMessage.ADAPTER.decode(h.fake.presentKeys.single().signedMessage!!.protobufMessageAsBytes)
            val change = inner.VCSEC_WhitelistOperation!!.addKeyToWhitelistAndAddPermissions!!
            assertEquals(key.toByteArray().toList(), change.key!!.PublicKeyRaw.toByteArray().toList())
            assertEquals(Role.ROLE_OWNER, change.keyRole)
        }

    @Test
    fun waitingForTapThenAccepted() =
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptPresentKey(FakeVehicle.presentKeyWait(), FakeVehicle.presentKeyResult(none))
            val events = VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.WaitingForTap, PairingEvent.Accepted), events)
        }

    @Test
    fun ignoresBroadcastsAndAuthenticationRequestsWhilePairing() =
        // Review Focus 1
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptPresentKey(
                "4a00".hexToBytes(),
                FromVCSECMessage(vehicleStatus = VehicleStatus()).encode(),
                FakeVehicle.presentKeyWait(),
                "4a00".hexToBytes(),
                FakeVehicle.presentKeyResult(none),
            )
            val events = VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.WaitingForTap, PairingEvent.Accepted), events)
        }

    @Test
    fun undecodableFrameIsUnknownAndPairingContinues() =
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptPresentKey(byteArrayOf(0xFF.toByte()), FakeVehicle.presentKeyResult(none))
            val events = VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.Unknown(byteArrayOf(0xFF.toByte())), PairingEvent.Accepted), events)
        }

    @Test
    fun noAnswerTimesOutAfterTheTimeout() =
        runTest {
            val h = dispatcherHarness()
            val start = currentTime
            val events = VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.TimedOut), events)
            assertEquals(60_000, currentTime - start)
        }

    @Test
    fun sendFailureIsFailedAndNotRetried() =
        runTest {
            val h = dispatcherHarness()
            h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("gatt 133"))
            val events = VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf<PairingEvent>(PairingEvent.Failed(VehicleError.TransportError.WriteFailed("gatt 133"))), events)
            assertEquals(1, h.transport.sent.size)
        }

    @Test
    fun unknownRoleFailsWithoutSending() =
        runTest {
            val h = dispatcherHarness()
            val events = VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.UNKNOWN, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertIs<VehicleError.InvalidArgument>(assertIs<PairingEvent.Failed>(events.single()).error)
            assertEquals(0, h.transport.sent.size)
        }

    @Test
    fun presentKeyWaitsForAnInFlightVcsecRequestButDoesNotHoldTheLock() =
        // Review Focus 4, FR-049: 보낼 때만 직렬화 락을 쓰고, 60초 대기 중에는 다른 VCSEC 조회가 지나간다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            h.fake.script(vcsec, emptyList()) // 첫 조회는 답이 없다 → 5초 뒤 Uncertain
            val query = async { session.info.keySummary(timeout = 5.seconds) }
            runCurrent()
            val pairing = async { session.pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList() }
            runCurrent()
            assertTrue(h.fake.presentKeys.isEmpty()) // 앞선 VCSEC 요청이 끝나기 전에는 보내지 않는다
            advanceTimeBy(5.seconds + 1.seconds)
            assertIs<VehicleResult.Uncertain>(query.await())
            assertEquals(1, h.fake.presentKeys.size)
            // 페어링이 기다리는 동안 다른 VCSEC 조회가 막히지 않는다
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(FromVCSECMessage(whitelistInfo = com.tesla.generated.vcsec.WhitelistInfo(numberOfEntries = 1, slotMask = 1)))))
            assertEquals(VehicleResult.Success(WhitelistSummary(1, 1u)), session.info.keySummary())
            h.transport.deliver(FakeVehicle.presentKeyResult(none))
            assertEquals(PairingEvent.Accepted, pairing.await().last())
        }
}
```

Run: `./gradlew :application:jvmTest --tests '*Pairing*'` — Expected: 컴파일 실패.

- [ ] **Step 6: 구현.** `VcsecCommands`에:

```kotlin
    /** [block]을 VCSEC 직렬화 락 안에서 실행한다(add-key-request 전송용 — 응답 대기는 락 밖). */
    internal suspend fun <T> exclusive(block: suspend () -> T): T = serial.withLock { block() }
```

`Pairing.kt`:

```kotlin
// Ported from vehicle-command@a4b43c1 pkg/vehicle/security.go (Apache-2.0) — SendAddKeyRequestWithRole (+ response reading, ADR-0009)
package dev.mirolab.teslable.application.pairing

import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.vcsec.VcsecCommands
import dev.mirolab.teslable.application.vcsec.VcsecInfoMapping
import dev.mirolab.teslable.application.vcsec.VcsecPayloads
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.isTerminal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Go `SendAddKeyRequestWithRole` + 응답 읽기(ADR-0009, SDD §4, 설계 구체화 4). 차가운 Flow — 수집할 때마다 한 번 보낸다.
 * 순서: [Dispatcher.unroutedFrames] 구독 → VCSEC 직렬화 락 안에서 전송만(FR-049) → `Sent` → [timeout] 동안 프레임을 [PairingEvents]로 해석.
 */
public class Pairing internal constructor(
    private val dispatcher: Dispatcher,
    private val vcsec: VcsecCommands,
) {
    /** [publicKey]의 등록을 요청한다. 사용자가 키카드를 태그하고 차량 화면에서 승인해야 한다. */
    public fun addKeyRequest(
        publicKey: PublicKeyBytes,
        role: KeyRole,
        formFactor: KeyFormFactor,
        timeout: Duration,
    ): Flow<PairingEvent> {
        val wireRole =
            VcsecInfoMapping.wireRole(role)
                ?: return flowOf(PairingEvent.Failed(VehicleError.InvalidArgument("role UNKNOWN cannot be enrolled")))
        val envelope = VcsecPayloads.presentKeyEnvelope(VcsecPayloads.addKey(publicKey, wireRole, VcsecInfoMapping.wireFormFactor(formFactor)))
        return channelFlow {
            // 페어링끼리는 전송부터 끝 이벤트까지 직렬화한다 — 두 요청이 같은 감싸지 않은 응답을 자기 결과로 보지 않게(codex 검토).
            // 일반 VCSEC 명령과 공유하는 락(vcsec.exclusive)은 전송하는 동안만 잡는다(Review Focus 4).
            pairingMutex.withLock {
                val inputs = Channel<Input>(Channel.UNLIMITED)
                val tap = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.unroutedFrames.collect { inputs.send(Input.Frame(it)) } }
                val link =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        val down = dispatcher.transportState.first { it is TransportState.Disconnected } as TransportState.Disconnected
                        inputs.send(Input.LinkLost(down.reason ?: VehicleError.NotConnected))
                    }
                try {
                    run(envelope, timeout, inputs)
                } finally {
                    tap.cancel()
                    link.cancel()
                }
            }
        }
    }

    private sealed interface Input {
        class Frame(
            val bytes: ByteArray,
        ) : Input

        class LinkLost(
            val error: VehicleError,
        ) : Input
    }

    private suspend fun ProducerScope<PairingEvent>.run(
        envelope: ByteArray,
        timeout: Duration,
        inputs: Channel<Input>,
    ) {
        val sent = vcsec.exclusive { dispatcher.sendUnrouted(envelope) }
        if (sent is VehicleResult.Failure) {
            send(PairingEvent.Failed(sent.error))
            return
        }
        send(PairingEvent.Sent) // Uncertain(차량이 받았을 수 있음)도 기다린다
        val finished =
            withTimeoutOrNull(timeout) {
                for (input in inputs) {
                    val event =
                        when (input) {
                            is Input.Frame -> PairingEvents.interpret(input.bytes) ?: continue
                            is Input.LinkLost -> PairingEvent.Failed(input.error) // 60초를 기다리지 않고 바로 끝낸다
                        }
                    send(event)
                    if (event.isTerminal) return@withTimeoutOrNull true
                }
                false
            }
        if (finished != true) send(PairingEvent.TimedOut)
    }
}
```

클래스 본문 맨 위에 `private val pairingMutex = Mutex()`(연결당 `Pairing` 하나 — `VehicleSession`이 만든다). `Dispatcher`에 `internal val transportState: StateFlow<TransportState> get() = transport.state`(KDoc: "같은 연결의 형제 계층이 링크 끊김을 본다"). 임포트: `kotlinx.coroutines.channels.ProducerScope`, `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.sync.Mutex`, `kotlinx.coroutines.sync.withLock`, `dev.mirolab.teslable.port.TransportState`.

`PairingTest`에 두 테스트를 더한다(Step 5와 같은 파일, 먼저 실패를 확인한다):

```kotlin
    @Test
    fun linkLossDuringTheWaitFailsImmediately() =
        // codex 검토: 링크가 끊기면 60초 TimedOut이 아니라 바로 Failed
        runTest {
            val h = dispatcherHarness()
            val start = currentTime
            val pairing = async { VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList() }
            runCurrent()
            h.transport.close() // FakeTransport: Disconnected(reason = null)
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.Failed(VehicleError.NotConnected)), pairing.await())
            assertEquals(0, currentTime - start)
        }

    @Test
    fun concurrentPairingsRunOneAfterAnother() =
        // codex 검토: 동시에 수집해도 한 응답을 두 요청이 나눠 갖지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptPresentKey(FakeVehicle.presentKeyResult(none))
            h.fake.scriptPresentKey(FakeVehicle.presentKeyResult(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL))
            val first = async { session.pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList() }
            val second = async { session.pairing.addKeyRequest(key, KeyRole.DRIVER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList() }
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.Accepted), first.await())
            assertEquals(
                PairingEvent.Rejected(VehicleError.KeychainRejected(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL)),
                second.await().last(),
            )
            assertEquals(2, h.fake.presentKeys.size)
        }
```

`VehicleSession`에 `public val pairing: Pairing = Pairing(dispatcher, vcsec)`. `ported-files.txt`에 `Pairing.kt`.

- [ ] **Step 7: 통과·apiDump.** Run: `./gradlew :domain:jvmTest :application:jvmTest :testing:jvmTest :application:iosSimulatorArm64Test` → PASS. 단독 `./gradlew :domain:apiDump`, `./gradlew :application:apiDump`.

- [ ] **Step 8: SDD 기록·커밋.** SDD §4 시퀀스를 실제 이벤트 집합(`Failed`, `TimedOut` 추가, 무시 규칙, 공개키 인자)으로 고치고 §2.3 `addKeyRequest` 시그니처를 바꾼다. ADR-0009 "결과"에 "M4: 이벤트 집합과 무시 규칙(설계 구체화 4)" 한 줄. 문서는 Task 7.

```bash
git commit -m "feat(application): add-key-request with pairing progress events read from unwrapped VCSEC frames

Refs: FR-022, FR-023, FR-049, ADR-0009
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

PR → verify.sh → 리뷰(opus — 동시성·Flow 취소) → rebase merge.

---

### Task 7: `:sdk` 파사드 — `Vehicle` M4 메서드, 세션 상태 표시, 기본 키 형태, 사용 문서

**브랜치:** `m4/facade`. **Refs:** FR-022~FR-024, FR-026~FR-027, FR-040~FR-047, FR-102, FR-104. **원본 대응:** Task 4~6과 같음. 매뉴얼 `05-command-catalog.md` A·B·C.

**Files:**
- Modify: `sdk/src/commonMain/kotlin/dev/mirolab/teslable/Vehicle.kt`, `DefaultVehicle.kt`, `TeslaBleConfig.kt`(`pairingTimeout`), `TeslaBle.kt`(필요 시)
- Create: `sdk/src/commonMain/kotlin/dev/mirolab/teslable/KeyFormFactors.kt`(expect) + `sdk/src/androidMain/…/KeyFormFactors.android.kt`, `sdk/src/iosMain/…/KeyFormFactors.ios.kt`, `sdk/src/jvmMain/…/KeyFormFactors.jvm.kt`
- Test: `sdk/src/commonTest/kotlin/dev/mirolab/teslable/TeslaBleTest.kt`, `sdk/src/jvmTest/kotlin/dev/mirolab/teslable/KeyFormFactorsJvmTest.kt`
- Docs: `documents/md/11-lib-overview.md`, `12-lib-getting-started.md`, `13-lib-pairing.md`, `15-lib-commands.md`, `16-lib-errors.md`, `18-lib-troubleshooting.md` + `documents/html/{en,ko,ja}/` 같은 이름의 HTML; `docs/sdd/SDD.md` §2.3

**Interfaces:**
- Consumes: `VehicleSession.control`(Task 4), `.keys`(Task 5), `.pairing`(Task 6), `.sessionEvents`(Task 2).
- Produces(공개): `Vehicle.sessionInfo(publicKey: PublicKeyBytes, domain: VehicleDomain, timeout: Duration? = null): VehicleResult<SessionInfoSummary>`, `Vehicle.addKeyRequest(publicKey: PublicKeyBytes, role: KeyRole = KeyRole.OWNER, formFactor: KeyFormFactor = platformKeyFormFactor(), timeout: Duration? = null): Flow<PairingEvent>`, `Vehicle.addKey(publicKey, role, formFactor, timeout: Duration? = null)`, `Vehicle.removeKey(publicKey, timeout: Duration? = null)`, `Vehicle.lock/unlock/wakeUp/remoteDrive/autoSecureVehicle/openTrunk/actuateTrunk/closeTrunk/openFrunk/openTonneau/closeTonneau/stopTonneau(timeout: Duration? = null): VehicleResult<Unit>`, `expect fun platformKeyFormFactor(): KeyFormFactor`, `TeslaBleConfig.pairingTimeout: Duration = 60.seconds`.

- [ ] **Step 1: 실패 테스트(`TeslaBleTest`).** 기존 `rig()`/`connected()`를 쓴다.

```kotlin
    @Test
    fun lockUnlockAndTrunkGoThroughTheVcsecSession() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.lock()) // 핸드셰이크는 앱 몫
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.VCSEC), timeout = 1.seconds))
            assertEquals(VehicleResult.Success(Unit), vehicle.lock())
            assertEquals(VehicleResult.Success(Unit), vehicle.unlock())
            assertEquals(VehicleResult.Success(Unit), vehicle.openTrunk())
            assertEquals(listOf("1001", "1000", "22022801"), r.fake.authenticatedPayloads(vcsec).map { it.toHex() })
        }

    @Test
    fun commandsAfterDisconnectAreNotConnectedAndPairingFails() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            vehicle.disconnect()
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.unlock())
            assertEquals(
                listOf<PairingEvent>(PairingEvent.Failed(VehicleError.NotConnected)),
                vehicle.addKeyRequest(r.key().publicKey).toList(),
            )
        }

    @Test
    fun addKeyRequestUsesThePlatformFormFactorAndConfiguredTimeout() =
        runTest {
            val r = rig(config = TeslaBleConfig(pairingTimeout = 10.seconds))
            val vehicle = r.connected(withKey = false)
            val start = currentTime
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.TimedOut), vehicle.addKeyRequest(r.key().publicKey).toList())
            assertEquals(10_000, currentTime - start)
            val inner = UnsignedMessage.ADAPTER.decode(r.fake.presentKeys.single().signedMessage!!.protobufMessageAsBytes)
            assertEquals(WireFormFactor.KEY_FORM_FACTOR_CLOUD_KEY, inner.VCSEC_WhitelistOperation!!.metadataForKey!!.keyFormFactor) // JVM 기본
        }

    @Test
    fun sessionInfoWithOwnKeyMakesTheDomainReady() =
        // 설계 구체화 3: 페어링 확인 폴링이 세션을 만들면 sessions에도 보인다
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<SessionInfoSummary>>(vehicle.sessionInfo(r.key().publicKey, VehicleDomain.INFOTAINMENT))
            runCurrent()
            assertEquals(SessionStatus.Ready(fromCache = false), vehicle.sessions.value[VehicleDomain.INFOTAINMENT])
        }

    @Test
    fun noSessionResultReconcilesTheShownStatusEvenIfTheEventWasMissed() =
        // codex 검토: 표시는 사건만이 아니라 실제 세션 상태를 원천으로 한다
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.VCSEC])
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.lock())
            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.VCSEC]) // 바뀌지 않음(원래 None)
        }

    @Test
    fun staleCacheTurnsTheDomainBackToNone() =
        // Review Focus 3: 다른 차량 키로 저장된 캐시 → 첫 명령 NoSession, 표시 None, startSession으로 회복
        runTest {
            val cache = RecordingSessionCache()
            val other = rig(fake = FakeVehicle(vehicleKey = TestCrypto.goKnownVerifierKey(), timeSource = testTimeSource), cache = cache)
            val first = other.connected()
            first.startSession(timeout = 1.seconds)
            first.disconnect()
            val r = rig(cache = cache)
            val vehicle = r.connected()
            assertEquals(SessionStatus.Ready(fromCache = true), vehicle.sessions.value[VehicleDomain.VCSEC])
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.lock())
            runCurrent()
            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.VCSEC])
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.VCSEC), timeout = 1.seconds))
            assertEquals(VehicleResult.Success(Unit), vehicle.lock())
        }
```

(두 `rig`는 같은 VIN·같은 `FakeVehicleKeyStore` 키를 쓴다 — `FakeVehicleKeyStore`가 `TestCrypto` 키를 돌려주므로 `keyId`가 같다. 다르면 첫 rig의 `keys`를 둘째에 넘기도록 `rig()`에 `keys` 인자를 더한다.)

Run: `./gradlew :sdk:jvmTest` — Expected: 컴파일 실패.

- [ ] **Step 2: 구현.** `KeyFormFactors.kt`:

```kotlin
package dev.mirolab.teslable

import dev.mirolab.teslable.model.KeyFormFactor

/** 이 플랫폼의 기본 키 형태(FR-022): Android `ANDROID_DEVICE`, iOS `IOS_DEVICE`, JVM `CLOUD_KEY`(테스트·실험용). */
public expect fun platformKeyFormFactor(): KeyFormFactor
```

actual 세 개는 각각 `= KeyFormFactor.ANDROID_DEVICE` / `IOS_DEVICE` / `CLOUD_KEY`. `TeslaBleConfig`에 `/** add-key-request 진행 이벤트를 기다리는 기본 시간(설계 구체화 4). */ val pairingTimeout: Duration = 60.seconds`(마지막 인자). `Vehicle` 인터페이스에 Interfaces 블록의 메서드를 더한다 — KDoc 규칙: 제어 메서드는 "VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102)." 한 줄과 각 FR, `addKeyRequest`는 `PairingEvent` 순서와 "등록 확정은 `sessionInfo(publicKey, INFOTAINMENT)`", `removeKey`는 "`InsufficientPrivileges`/`KeychainRejected`". `DefaultVehicle`:

```kotlin
    init {
        // 기존 링크 손실 감시 다음에 — 세션 사건이 오면 그 도메인의 표시를 실제 SessionState에 맞춘다(설계 구체화 2·3).
        // 사건은 신호일 뿐이고 원천은 session.isReady(domain)이다 — 사건이 유실돼도 다음 사건이나 NoSession 결과가 다시 맞춘다(codex 검토).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.sessionEvents.collect { event ->
                val domain = when (event) { is SessionEvent.StaleCache -> event.domain; is SessionEvent.Updated -> event.domain }
                VehicleDomain.of(domain)?.let(::reconcile)
            }
        }
    }

    /** [domain]의 표시를 실제 세션에 맞춘다. 핸드셰이크 중이거나 disconnect 뒤에는 건드리지 않는다. */
    private fun reconcile(domain: VehicleDomain) {
        if (closed.value) return
        val ready = session.isReady(domain.wire)
        states.update { current ->
            when (val shown = current[domain]) {
                SessionStatus.Handshaking -> current
                is SessionStatus.Ready -> if (ready) current else current + (domain to SessionStatus.None)
                else -> if (ready) current + (domain to SessionStatus.Ready(fromCache = false)) else current
            }
        }
    }

    /** 명령이 `NoSession`으로 끝나면 표시를 다시 맞춘다(사건 유실 대비). */
    private fun <T> VehicleResult<T>.reconciling(domain: VehicleDomain): VehicleResult<T> =
        also { if (it is VehicleResult.Failure && it.error == VehicleError.NoSession) reconcile(domain) }

    override suspend fun sessionInfo(publicKey: PublicKeyBytes, domain: VehicleDomain, timeout: Duration?) =
        session.keys.sessionInfo(publicKey, domain.wire, timeout ?: config.commandTimeout)

    override fun addKeyRequest(publicKey: PublicKeyBytes, role: KeyRole, formFactor: KeyFormFactor, timeout: Duration?) =
        session.pairing.addKeyRequest(publicKey, role, formFactor, timeout ?: config.pairingTimeout)

    override suspend fun addKey(publicKey: PublicKeyBytes, role: KeyRole, formFactor: KeyFormFactor, timeout: Duration?) =
        session.keys.addKey(publicKey, role, formFactor, timeout ?: config.commandTimeout)

    override suspend fun removeKey(publicKey: PublicKeyBytes, timeout: Duration?) = session.keys.removeKey(publicKey, timeout ?: config.commandTimeout)

    override suspend fun lock(timeout: Duration?): VehicleResult<Unit> =
        session.control.lock(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)
    // unlock, wakeUp, remoteDrive, autoSecureVehicle, openTrunk, actuateTrunk, closeTrunk, openFrunk, openTonneau, closeTonneau, stopTonneau도
    // 같은 모양으로 session.control의 같은 이름 메서드에 위임한다(12개 전부 명시적으로 쓴다 — 반환형은 VehicleResult<Unit>).
```

`VehicleSession`에 `public fun isReady(domain: Domain): Boolean = dispatcher.session(domain)?.isReady == true`(KDoc: "파사드 표시의 원천")를 더한다. `addKey`/`removeKey`도 `.reconciling(VehicleDomain.VCSEC)`로 감싼다.
(`session.sessionEvents`의 `SessionEvent`는 `:application` 타입이다 — `:sdk`는 `:application`을 `implementation`으로 쓰므로 가능하다. 반환형은 명시적으로 적는다 — `explicitApi`.)

- [ ] **Step 3: 통과·apiDump.** Run: `./gradlew :sdk:jvmTest :sdk:iosSimulatorArm64Test :sdk:linkDebugFrameworkIosSimulatorArm64` → PASS. 단독 `./gradlew :sdk:apiDump`.

- [ ] **Step 4: 사용 문서(MD 먼저, HTML en → ko/ja).**
  - `13-lib-pairing.md`: "Scope"를 M4로, "Planned (M4, not implemented)" 표를 "Enrolling a key" 절로 바꾼다 — `addKeyRequest` 시그니처·이벤트 표(Sent / WaitingForTap / Accepted / Rejected(error) / Unknown(raw) / Failed(error) / TimedOut, 끝 이벤트)·"ignored frames"(commandStatus·nominalError 없는 프레임)·NFC 절차, `sessionInfo` 폴링 Kotlin/Swift 예제(3초 간격, 2분 한도, `KeyNotPaired`면 계속), `addKey`/`removeKey` 표, "Unknown(raw)이 오면 `raw`를 헥사로 보고해 달라"(실차 확인 전 원본에 없는 동작이라는 경고, ADR-0009).
  - `15-lib-commands.md`: "Planned: VCSEC (M4)" 표를 "Implemented: VCSEC" 표(메서드·FR·페이로드·비고)로, `wakeUp`은 VCSEC 세션만, `openTrunk`=`actuateTrunk`(토글), `openFrunk`은 닫기 없음, 토노는 Cybertruck. "Uncertain → `vehicleStatus()`로 확인" 문단.
  - `16-lib-errors.md`: "Per-command re-query"(FR-102) 표 — VCSEC 제어 전부 `vehicleStatus()`, 키 작업은 `listKeys()`, 페어링은 `sessionInfo(…, INFOTAINMENT)`.
  - `11-lib-overview.md`: 상태 표의 M4 행을 "Done (real-vehicle check pending)"으로, 파일 목록의 13/15 설명.
  - `12-lib-getting-started.md`: 끝에 "Pair, then lock" 짧은 Kotlin/Swift 예제(`addKeyRequest` 수집 → `sessionInfo` 확인 → `startSession(VCSEC)` → `lock()`, Swift `for await event in vehicle.addKeyRequest(publicKey: key.publicKey)`).
  - `18-lib-troubleshooting.md`: "Pairing" 행(`TimedOut` → 카드 태그·화면 승인 확인, `Rejected(KeychainRejected(WHITELIST_FULL))` 등), "`lock()` → `NoSession`" 행(핸드셰이크 먼저, 캐시가 오래됐으면 `sessions`가 `None`으로 바뀐다).
  - SDD §2.3: M3 구현 범위 문단을 M4로 갱신, `addKeyRequest` 시그니처, `PairingEvent` 목록.
  `tools/ci/check-manual-i18n.sh` 통과.

- [ ] **Step 5: 커밋·PR.**

```bash
git add sdk documents docs/sdd/SDD.md
git commit -m "feat(sdk): Vehicle pairing, key management and VCSEC control commands

Refs: FR-022, FR-023, FR-024, FR-026, FR-027, FR-040, FR-041, FR-042, FR-043, FR-044, FR-045, FR-046, FR-047, FR-102, FR-104
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

(문서를 별도 `docs:` 커밋으로 나눠도 된다.) verify.sh → 리뷰(opus — 파사드 수명·상태 흐름, 문서와 코드 일치) → rebase merge.

---

### Task 8: Swift 시간 제한 — `Timeouts.seconds`, `TeslaBleConfig.withSeconds`, XCTest, verify.sh (결정 D-2)

**브랜치:** `m4/swift-timeouts`. **Refs:** NFR-012, ADR-0005, ADR-0010. **원본 대응:** 없음(Swift 표면). D-2에서 다른 안이 선택되면 이 Task를 그 안으로 다시 쓴다.

**Files:**
- Create: `sdk/src/commonMain/kotlin/dev/mirolab/teslable/Timeouts.kt`, `sdk/src/commonTest/kotlin/dev/mirolab/teslable/TimeoutsTest.kt`, `samples/ios/Tests/TimeoutsTests.swift`
- Modify: `sdk/src/commonMain/kotlin/dev/mirolab/teslable/TeslaBleConfig.kt`, `sdk/src/commonTest/kotlin/dev/mirolab/teslable/TeslaBleConfigTest.kt`, `samples/ios/project.yml`, `tools/ci/verify.sh`, `documents/md/12-lib-getting-started.md`, `17-lib-platform-notes.md` + HTML 3언어

**Interfaces:**
- Produces: `public object Timeouts { fun seconds(seconds: Double): Duration?; fun millisecondsOf(timeout: Duration?): Long }`, **새** `TeslaBleConfig.withSeconds(logger: TeslaLogger, commandTimeoutSeconds: Double, handshakeTimeoutSeconds: Double, connectTimeoutSeconds: Double, pairingTimeoutSeconds: Double): TeslaBleConfig`(모든 인자 필수). 기존 `TeslaBleConfig.default(logger)`는 **그대로 둔다** — Swift에는 기본 인자 오버로드가 생기지 않아(M3 샘플이 `timeout: nil`을 명시하는 이유) 인자를 늘리면 `default(logger:)` 호출이 깨진다(codex 검토).

- [ ] **Step 1: 실패 테스트(Kotlin).**

```kotlin
class TimeoutsTest {
    @Test
    fun secondsMakesADurationAndRejectsNonPositive() {
        assertEquals(2.5.seconds, Timeouts.seconds(2.5))
        assertFailsWith<IllegalArgumentException> { Timeouts.seconds(0.0) }
        assertFailsWith<IllegalArgumentException> { Timeouts.seconds(Double.NaN) }
        assertEquals(2500L, Timeouts.millisecondsOf(Timeouts.seconds(2.5)))
        assertEquals(-1L, Timeouts.millisecondsOf(null))
    }
}
```

`TeslaBleConfigTest`에 `withSeconds(TeslaLogger.NoOp, 10.0, 20.0, 30.0, 90.0)`가 `commandTimeout == 10.seconds`, `handshakeTimeout == 20.seconds`, `connectTimeout == 30.seconds`, `pairingTimeout == 90.seconds`, `commandLifetime`은 기본 5초인지, 0 이하 인자는 `IllegalArgumentException`인지. 기존 `default(logger)` 테스트는 그대로 통과해야 한다.

- [ ] **Step 2: 구현.**

```kotlin
package dev.mirolab.teslable

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Swift에서 `timeout:` 인자를 넘기는 도우미(설계 구체화 8). Kotlin `Duration`은 Swift에서 값 타입으로 보이지 않는다 — 반환형이 nullable이라
 * 박싱된 채로 `timeout:`(`id`)에 그대로 넘어간다: `try await vehicle.lock(timeout: Timeouts.shared.seconds(seconds: 10))`. Kotlin은 `10.seconds`.
 */
public object Timeouts {
    /** [seconds]초. 0 이하·NaN·무한이면 `IllegalArgumentException`. */
    public fun seconds(seconds: Double): Duration? {
        require(seconds > 0 && seconds.isFinite()) { "timeout must be a positive finite number of seconds: $seconds" }
        return seconds.seconds
    }

    /** [timeout]의 밀리초(null이면 -1). Swift 쪽 왕복 확인용. */
    public fun millisecondsOf(timeout: Duration?): Long = timeout?.inWholeMilliseconds ?: -1
}
```

`TeslaBleConfig` 동반 객체에 `withSeconds(…)`를 더한다(각 값은 `seconds.seconds`, 0 이하·NaN이면 `require` 실패). `default(logger)`는 바꾸지 않는다. `apiDump`.

- [ ] **Step 3: Swift 테스트 타깃.** `samples/ios/Tests/TimeoutsTests.swift`:

```swift
import XCTest
import Teslable

final class TimeoutsTests: XCTestCase {
    func testBoxedDurationRoundTripsThroughKotlin() {
        XCTAssertEqual(Timeouts.shared.millisecondsOf(timeout: Timeouts.shared.seconds(seconds: 2.5)), 2500)
        XCTAssertEqual(Timeouts.shared.millisecondsOf(timeout: nil), -1)
    }

    func testConfigFactoryTakesSeconds() {
        let config = TeslaBleConfig.companion.withSeconds(logger: DebugLogger(), commandTimeoutSeconds: 10, handshakeTimeoutSeconds: 20,
                                                          connectTimeoutSeconds: 30, pairingTimeoutSeconds: 90)
        XCTAssertNotNil(config)
        XCTAssertNotNil(TeslaBleConfig.companion.default(logger: DebugLogger())) // 기존 호출이 그대로 컴파일된다
    }
}
```

(SKIE/ObjC가 만든 정확한 Swift 이름 — `Timeouts.shared`, 인자 레이블 — 은 생성된 헤더 `sdk/build/bin/iosSimulatorArm64/debugFramework/Teslable.framework/Headers/Teslable.h`를 보고 맞춘다.) `project.yml`에 타깃을 더한다:

```yaml
  TeslableSampleTests:
    type: bundle.unit-test
    platform: iOS
    deploymentTarget: "16.0"
    sources: [Tests]
    dependencies:
      - target: TeslableSample
    settings:
      base:
        GENERATE_INFOPLIST_FILE: "YES"
        SWIFT_VERSION: "6.0"
        FRAMEWORK_SEARCH_PATHS: "$(inherited) $(SRCROOT)/../../sdk/build/bin/iosSimulatorArm64/debugFramework"
        ARCHS: "arm64"
        EXCLUDED_ARCHS: "x86_64"
        CODE_SIGNING_REQUIRED: "NO"
        CODE_SIGN_IDENTITY: ""
```

그리고 `TeslableSample` 타깃에 `scheme: { testTargets: [TeslableSampleTests] }`. `tools/ci/verify.sh` (d)의 `xcodebuild … build -quiet`를 `-destination 'platform=iOS Simulator,name=iPhone 17' … build test -quiet`로 바꾸고 단계 이름·주석의 "빌드"를 "빌드 + Swift 단위 테스트"로. Run: `tools/ci/verify.sh` — Expected: (d)에서 두 테스트 PASS. 실패하면(박싱이 Swift에서 동작하지 않음) **멈추고 D-2의 다른 안을 사용자에게 묻는다**.

- [ ] **Step 4: 문서.** `12-lib-getting-started.md` Swift 예제에 `Timeouts.shared.seconds(seconds:)`와 `TeslaBleConfig.companion.withSeconds(…)`, `17-lib-platform-notes.md` "Swift: timeouts" 절(Kotlin `Duration`이 Swift에서 쓰기 어려운 이유와 두 도우미). HTML 3언어. SDD §2.3에 `TeslaBleConfig.withSeconds`.

- [ ] **Step 5: 커밋·PR.** `feat(sdk): Swift-friendly timeouts` + `chore(ci): run the iOS sample unit tests in the local gate`(verify.sh는 별도 커밋). verify.sh → 리뷰(sonnet) → rebase merge.

---

### Task 9: 샘플 앱 2종 — 페어링·키 관리·VCSEC 제어·재조회 (빌드 7) + 🛑 사용자 실차 확인

**브랜치:** `m4/samples`. **Refs:** FR-110, FR-111(VCSEC 부분), FR-102. **원본 대응:** `09-recipes.md` R1(잠금·해제), R4(페어링), R5(키 목록).

**Files:**
- Modify: `samples/ios/Sources/SampleModel.swift`, `ContentView.swift`, `samples/ios/project.yml`(빌드 7), `samples/ios/README.md`(M4 실차 체크리스트)
- Modify: `samples/android/src/main/kotlin/dev/mirolab/teslable/sample/SampleState.kt`, `MainActivity.kt`

**Interfaces:**
- Consumes: Task 7의 `Vehicle` 메서드, Task 8의 `Timeouts`.

- [ ] **Step 1: iOS `BleClient`에 동작을 더한다.** 기존 `run(_:_:)` 패턴(결과를 한 줄 문자열로)을 따른다:
  - `startSession(vcsecOnly: Bool)` → `vehicle.startSession(domains: vcsecOnly ? [VehicleDomain.vcsec] : VehicleDomain.companion.ALL, timeout: nil)`.
  - `pair(onEvent:)`: `guard let key` → `for await event in vehicle.addKeyRequest(publicKey: key.publicKey, role: .owner, formFactor: KeyFormFactorsKt.platformKeyFormFactor(), timeout: nil)` — 이벤트마다 한국어 한 줄(`Sent` "요청 보냄 — 중앙 콘솔에 키카드를 대고 화면에서 승인하세요", `WaitingForTap` "카드 태그 대기 중", `Accepted` "차량이 승인함 — Infotainment 동기화 확인 중", `Rejected` "거부: \(error.message)", `Unknown` "모르는 응답 \(raw 헥사)", `Failed` "실패: …", `TimedOut` "시간 초과 — 등록 여부를 확인합니다")를 `onEvent`로. `Accepted`나 `TimedOut`이면 `confirmEnrollment()`를 이어서 부른다.
  - `confirmEnrollment()`: 3초 간격, 최대 2분 `sessionInfo(publicKey: key.publicKey, domain: .infotainment, timeout: Timeouts.shared.seconds(seconds: 5))` — `Success`면 "등록 확인됨(Infotainment)", `KeyNotPaired`면 계속, 그 밖은 마지막 오류 줄.
  - `lock/unlock/openTrunk/closeTrunk/openFrunk/wakeUp` — 결과가 `Uncertain`이면 곧바로 `vehicleStatus()`를 불러 "불확실 → 재조회: 잠금 …, 트렁크 …"를 덧붙인다(FR-102).
  - `removeKey(slot:)`: 마지막 `listKeys` 결과에서 고른 항목의 `publicKey`로(자기 키면 확인 문구 "이 기기의 키를 지웁니다").
- [ ] **Step 2: iOS 화면.** `Section("페어링")`(키 등록 요청, 등록 확인), `Section("세션")`(핸드셰이크: VCSEC만 / 전체, `sessions` 표시), `Section("제어")`(잠금, 해제, 트렁크 열기, 트렁크 닫기, 프렁크 열기, 깨우기), 키 목록 항목마다 "삭제" 버튼. 관찰 모드(Task 1) 중에는 제어·조회 버튼을 막는다. `CURRENT_PROJECT_VERSION: "7"`.
- [ ] **Step 3: Android 같은 흐름.** `SampleState`에 `startSession`, `pair()`(이벤트를 `output`에), `confirmEnrollment()`, 제어 6개(+`Uncertain` 재조회), `removeKey(entry)`; `MainActivity`에 버튼. `timeout`은 Kotlin `Duration` 그대로.
- [ ] **Step 4: 빌드 확인.** Run: `./gradlew :samples:android:assembleDebug` 와 `tools/ci/verify.sh`(iOS 샘플 빌드 + 테스트). Expected: PASS.
- [ ] **Step 5: 실차 체크리스트.** `samples/ios/README.md`에 "M4 실차 확인" 표(`{{WORKFLOW_FILE}}` §8.4 형식)를 더한다:

| # | 확인 | 할 일 | 기대 |
|---|---|---|---|
| 1 | 페어링 진행 이벤트(D28) | 새 키로 연결 → 키 등록 요청 → 카드 태그 → 화면 승인 | `요청 보냄` → `카드 태그 대기`(오는지 기록) → `승인` 또는 `모르는 응답 <헥사>`(헥사를 그대로 보고) |
| 2 | 등록 확인 | 위에 이어 | 2분 안에 "등록 확인됨(Infotainment)", 키 목록에 IOS_DEVICE·OWNER |
| 3 | 핸드셰이크 VCSEC만 | 세션 → VCSEC만 | `Ready`, 1초 안팎 |
| 4 | 잠금·해제 | 잠금 → 해제 | 각각 성공, 1초 안팎(NFR-011), 차량 잠금 상태가 실제로 바뀜 |
| 5 | 트렁크·프렁크 | 트렁크 열기, (전동이면) 닫기, 프렁크 열기 | 성공, 실제로 움직임 |
| 6 | 깨우기 | 차량이 잠든 뒤 깨우기 → VehicleStatus | 성공, `sleep`이 AWAKE |
| 7 | 앱 재실행 후 첫 명령 | 앱 종료 → 재실행 → 연결 → 잠금(핸드셰이크 없이) | `Ready(fromCache)`로 바로 성공 |
| 8 | 다른 키 삭제 | 목록에서 다른 기기 키(테스트용) 삭제 | 성공, 목록에서 사라짐 (주의: 실제 사용하는 키는 지우지 않는다) |
| 9 | 응답 섞임 관찰 | 잠금·해제 중 관찰 모드 로그(디버그 로그) | `[reply:vcsec]` 중 `vehicleStatus` 페이로드가 있었는지 기록(M5 입력) |

- [ ] **Step 6: 커밋·PR.** `feat(samples): pairing, key removal and VCSEC control in both samples; build 7`. verify.sh → 리뷰(sonnet) → rebase merge.
- [ ] **Step 7: 🛑 사용자 실차 확인.** 빌드 7(`TeslableSampleDevice`)로 위 표를 요청하고 **결과가 올 때까지 Task 11을 시작하지 않는다**. 실패한 항목은 `superpowers:systematic-debugging`으로 원본 Go와 대조해 `fix:` PR로 고친 뒤 그 항목만 다시 요청한다. 페어링 이벤트가 `Unknown`이면 받은 헥사로 `PairingEvents` 매핑을 고치고(ADR-0009가 예정한 경로) ADR에 실측 형식을 기록한다.

---

### Task 10: 골든 픽스처 — list-keys, lock, unlock (조건부, 결정 D-5)

**브랜치:** `m4/golden`. **Refs:** NFR-002, SDD §9.2, ADR-0011. **원본 대응:** `cmd/tesla-control` `list-keys`, `lock`, `unlock`.

**조건:** 사용자가 Go를 설치해 `tools/fixtures/collect-golden.sh`(인증 명령은 `--with-key`, Go용 키가 차량에 등록돼 있어야 함)로 `tools/fixtures/captures/{list-keys,lock,unlock}.txt`를 만든 경우에만 진행한다. 캡처가 없으면 이 Task를 건너뛰고 Task 11 인계 노트에 "골든 픽스처 미수집 — NFR-002 잔여"로 적는다. **M4 완료 판정(PRD §9: 실차 페어링·잠금·해제·트렁크, Task 9)과는 별개다**(codex 검토). 치환한 픽스처는 서명·태그·암호문이 원래 키에 묶여 있으므로 **필드 구조 검사 전용**이며, 암호 검증이나 평문 동등성을 주장하지 않는다(KDoc에 명시).

**Files:**
- Create: `testing/src/commonMain/kotlin/dev/mirolab/teslable/testing/fixtures/GoldenFixtures.kt`, `application/src/commonTest/kotlin/dev/mirolab/teslable/application/golden/GoldenFixturesTest.kt`

- [ ] **Step 1: 캡처 정리.** 캡처의 `TX:`/`RX:` 줄에서 필요한 메시지만 고른다. VIN은 이미 `mask-vin.sh`로 `5YJ30123456789ABC`. **공개키 65바이트(`04…`)는 `TestCrypto.clientPublicKey`의 바이트로, 차량 공개키는 `TestCrypto.vehiclePublicKey`로 바꾼다**(같은 길이 — TLV·protobuf 길이 유지). 프레임 앞 2바이트 길이 머리는 떼고 메시지 바이트만 둔다. 원본 캡처는 커밋하지 않는다.
- [ ] **Step 2: 상수.** `GoldenFixtures`(KDoc: 출처 명령, 수집 날짜, 펌웨어, 치환 규칙): `LIST_KEYS_SUMMARY_TX`, `LIST_KEYS_SUMMARY_RX`, `LIST_KEYS_ENTRY_TX`, `LIST_KEYS_ENTRY_RX`, `LOCK_TX`, `LOCK_RX`, `UNLOCK_TX`, `UNLOCK_RX`(헥사 문자열).
- [ ] **Step 3: 필드 단위 테스트(실패부터).** `GoldenFixturesTest`:
  - list-keys TX: Go TX를 디코딩한 `RoutableMessage`와 우리 `informationRequest(GET_WHITELIST_INFO)`로 만든 요청을 디코딩한 것의 `to_destination.domain`, `protobuf_message_as_bytes`(헥사 `0a020805`), `flags`가 같다(`uuid`·`routing_address`는 랜덤이라 길이만 16).
  - list-keys RX: `VcsecResponses.interpret` → `VcsecInfoMapping.summary`/`entry`가 성공하고 `numberOfEntries`·`slotMask`·역할·형태가 캡처의 값과 같다.
  - lock/unlock TX: 필드 구조 — `to_destination.domain == VCSEC`, `signature_data.signer_identity.public_key` 65B, `AES_GCM_Personalized_data`의 `epoch` 16B·`nonce` 12B·`tag` 16B·`counter > 0`·`expires_at > 0`, `flags`가 우리 `VcsecCommands`가 만든 같은 명령의 값과 같다(평문은 Go 키 없이는 복호화할 수 없으므로 필드 구조와 길이를 비교한다).
  - lock/unlock RX: `signature_data.AES_GCM_Response_data` 구조(nonce 12B, tag 16B), `from_destination.domain == VCSEC`, `request_uuid` 길이 0 또는 16.
- [ ] **Step 4: 통과·커밋.** `test: golden fixtures for list-keys, lock and unlock (field-level)`, `Refs: NFR-002`. `tools/ci/scan-secrets.sh` 통과 확인(테스트 VIN만). PR → verify.sh → 리뷰 → rebase merge.

---

### Task 11: M4 마무리 — SDD·PRD·ADR 동기화, 상태 표, 인계 노트

**브랜치:** `m4/finish`. **Refs:** 절대 규칙 11·13.

- [ ] **Step 1: 결과 반영.** Task 1 관찰 결과 노트(`{{HANDOFF_DIR}}<날짜>-m4-push-observation-results.md`)와 Task 9 실차 표를 SDD §9.6에 요약한다(M3 단락과 같은 형식). 실차에서 드러난 형식 차이(페어링 프레임, 브로드캐스트 주소)는 ADR-0009 "M4 실차 결과" 절에.
- [ ] **Step 2: PRD.** D-1 결정에 따라 NFR-006(또는 결정된 NFR)의 문구에 "관찰 로그(DEBUG, 태그 `observe`)"를 더하고, M5 행에 "푸시 이벤트 FR 신설(M4 관찰 결과 기반)"을 적는다. FR-022 시그니처(공개키 인자), FR-023 이벤트 집합을 실제와 맞춘다.
- [ ] **Step 3: 상태 표.** `11-lib-overview.md`(MD + HTML 3언어)의 M4 행을 실차 결과에 맞게 "Done (real-vehicle check on iOS <날짜>)" 또는 남은 항목과 함께 고친다. 로드맵 M4 행에 완료 표시.
- [ ] **Step 4: 매뉴얼 불일치 보고.** M4 중 발견한 매뉴얼(`{{MANUAL_DIR}}` 00~10)과 원본의 차이를 SDD §12 표에 추가하고 사용자에게 보고한다(수정은 승인 후).
- [ ] **Step 5: 인계 노트.** `{{HANDOFF_DIR}}<날짜>-m4-pairing-vcsec.md`(`{{WORKFLOW_FILE}}` §10 형식: 목표, 완료(PR 목록), 실차 표, 진행 중, 결정, 막힌 점 — M5로 넘기는 것: `vehicle.events`·응답/푸시 구분 ADR(후보 A+B+C+D+F), #71 규칙 재검토, `VehicleAsleep`, 골든 미수집이면 그 사실, 범위 밖 항목 목록), 다음 한 걸음(M5 상세 계획), 읽을 파일). 판정 기록은 `{{HANDOFF_DIR}}<날짜>-m4-rulings.md`(원장 `.superpowers/sdd/2026-10-18-m4-pairing-vcsec/progress.md`의 `Ruling:` 전체).
- [ ] **Step 6: 최종 리뷰·병합.** 새 컨텍스트 리뷰어(opus)로 M4 전체 브랜치 범위(`git diff <M4 시작 커밋>..origin/main`)를 DoD + 문서·코드 일치로 검토 → 지적 처리 → verify.sh → `docs: M4 complete …` PR → rebase merge → `/clear` 제안.

---

## 계획 검토 기록

- 2026-10-18 codex(GPT-6-Sol high) 2차 검토: 높음 4건(무효화와 대기 중 인가의 경합, `markFresh` 저장 순서, 동시 페어링, 링크 끊김 시 60초 대기), 중간 5건(닫힌 핸들러·큐 넘침 관찰 누락과 넘침 요약 중복, 복사되는 로그 범위, 세션 표시 원천, `TeslaBleConfig.default` 호환, 골든 픽스처의 한계), 낮음 2건(헥사 기대값 이상 없음, D-5와 M4 완료 판정 분리)을 모두 반영했다. 원문은 git-ignored `.superpowers/sdd/codex-m4-plan-review.md`.

## 결정이 필요한 항목 (계획 승인 때 함께 답해 주세요)

| # | 항목 | 추천 | 다른 안 |
|---|---|---|---|
| D-1 | 관찰 로깅을 연결할 요구사항 | **NFR-006(로그) + NFR-003(FakeVehicle 시나리오)**, 새 FR은 M5 `vehicle.events`에서 | 새 NFR(진단) 신설 / 연결 없음 |
| D-2 | Swift 시간 제한 | **`Timeouts.seconds()` 도우미 + 새 `TeslaBleConfig.withSeconds(…)`(기존 `default(logger:)` 유지) + XCTest로 확인** | Swift 전용 오버로드(`…(timeoutSeconds:)`, API 두 배) / 현행 유지(설정 기본값만) |
| D-3 | `addKeyRequest` 시그니처 | **공개키를 인자로 받는다**(Go와 같음, 키 없이 연결해도 페어링 가능) | SDD 원안: 연결한 키를 쓴다(인자 없음, 키 없는 연결은 불가) |
| D-4 | 오래된 캐시 세션 | **무효화 → `NoSession` + `sessions` None → 앱이 `startSession()`** | 명령이 자동으로 재핸드셰이크 / M3처럼 다음 `connect`까지 그대로 |
| D-5 | 골든 픽스처(로드맵의 M4 완료 기준 일부, NFR-002) | **조건부 수집**: Go 설치가 가능하면 `list-keys`(키 불필요) + `lock`/`unlock`(Go 키를 `tesla-control add-key-request`로 등록). 못 하면 NFR-002 잔여로 기록하고 M4 완료 판정(실차, PRD §9)과 분리 | M5로 미룸(climate-on 등과 함께) / 완료 기준에서 제외 |
| D-6 | 실차 일정 | Task 1 관찰은 빌드 6 직후(병행), Task 9 확인은 빌드 7 | – |

---

## 부록: 계획 승인 뒤 `goal`에 넣을 프롬프트

```
tesla-ble-kmp M4(키 등록·VCSEC 제어)를 계획대로 끝까지 실행한다.

계획: docs/superpowers/plans/2026-10-18-m4-pairing-vcsec.md (승인됨, 결정 D-1~D-6은 계획 승인 때 받은 답을 따른다. 계획 승인 전 codex 2차 검토 지적은 이미 반영됨)
먼저 읽을 것: AGENTS.md, CLAUDE.md, docs/PATHS.md, 위 계획 전체, docs/handoff/2026-10-18-m3-transport-keystore.md,
docs/handoff/2026-10-16-m3-rulings.md, docs/handoff/2026-10-18-m4-push-observation.md, docs/workflow.md §5·§9·§10.

실행 방식:
- superpowers:subagent-driven-development로 Task 1부터 순서대로 하나씩. 한 번에 Task 하나.
- 구현 서브에이전트에는 Task 1개 + 관련 FR/NFR + 참고 매뉴얼 절 + 대응 Go 원본 경로 + 먼저 쓸 실패 테스트만 준다.
- 리뷰 서브에이전트는 구현을 보지 않은 새 컨텍스트로, workflow.md §9 DoD와 "라이브러리 사용 문서(MD + HTML ko/en/ja)가 코드와 맞는지"를 체크리스트로 준다.
  디스패처·동시성·Flow·파사드 Task(1, 2, 5, 6, 7)와 최종 리뷰는 opus, 나머지는 sonnet.
- 원장: .superpowers/sdd/2026-10-18-m4-pairing-vcsec/progress.md (git-ignored). 내가 내린 판단은 "Ruling: 결정 — 이유 — 틀렸을 때의 비용" 형식으로 남기고,
  마지막에 docs/handoff/<날짜>-m4-rulings.md로 옮긴다.

모든 Task에 지킬 규칙:
- 브랜치 m4/<slug>를 최신 origin/main에서 → Red → Green → Refactor → struct:/feat: 커밋 분리(Tidy First) → PR → tools/ci/verify.sh 로컬 통과 →
  AI 리뷰(요약을 PR 본문에) → rebase merge(squash 금지). 지적 사항은 superpowers:receiving-code-review로 처리.
- 공개 API가 바뀌면 같은 PR에서 apiDump와 사용 문서(MD 영어 + HTML 3언어)를 고친다. 바뀔 문서가 없으면 PR 본문에 "문서 영향 없음"과 이유.
- Task 1은 공개 API·apiDump·드롭 동작을 바꾸지 않는다. vehicle.events, VehicleEvent, #71 규칙 변경, 푸시로 Uncertain 확인은 M5다.
- vehicle-command/는 읽기 전용. 매뉴얼(00~10)은 수정하지 않고 원본과 다르면 원본을 따르고 보고한다. AGPL 저장소는 열지 않는다.
- 로그·픽스처·커밋에 실제 VIN, 개인키, 세션키, 공개키 전체, 암호문을 남기지 않는다.

멈추고 나에게 물을 때:
- 🛑 Task 1 Step 18: 빌드 6 실차 관찰을 요청하되 기다리지 말고 Task 2로 진행한다. 결과를 받으면 결과 노트를 쓴다.
- 🛑 Task 9 Step 7: 빌드 7 실차 확인을 요청하고 결과가 올 때까지 Task 11을 시작하지 않는다.
- Task 8 Step 3에서 Swift 박싱 왕복이 실패하면 멈추고 D-2의 다른 안을 묻는다.
- Task 10은 골든 캡처가 없으면 건너뛰고 그 사실을 기록한다(D-5).
- 원본 Go와 계획이 다르거나, 계획에 없는 일이 필요하거나, 의존성·모듈 경계를 바꿔야 하면 추측하지 말고 멈추고 묻는다.
- 컨텍스트가 길어지면 workflow.md §10 형식으로 docs/handoff/에 중간 인계 노트를 쓰고 /clear를 제안한다.

완료 조건: Task 1~11의 PR이 모두 main에 병합되고, 실차 확인표(Task 9)와 푸시 관찰 결과 노트(Task 1)가 기록되고,
docs/handoff/<날짜>-m4-pairing-vcsec.md 인계 노트와 M5 상세 계획 작성 제안까지 마친 상태.
```
