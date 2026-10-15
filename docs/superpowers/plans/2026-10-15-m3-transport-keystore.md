# M3 전송·키스토어·파사드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** M2 디스패처·세션 계층 위에 실제 BLE 전송(Kable), 플랫폼 키스토어(Secure Enclave / Android Keystore + 소프트웨어 대체), 플랫폼 세션 캐시(Keychain / 앱 전용 파일), 인증 없는 VCSEC 조회(`vehicleStatus`, `keySummary`, `listKeys`), 공개 파사드 `TeslaBle`/`Vehicle`을 붙여, **iPhone 샘플 앱에서 실차를 스캔·연결하고 키 목록과 VehicleStatus를 읽는다**(PRD §9 M3 완료 기준; 실차 확인은 사용자).

**Architecture:** `{{SDD_FILE}}` §1~§3, §8의 헥사고날 구조를 그대로 따른다. `:domain`에 M3 포트·값 객체(`TransportFactory`, `ScanEvent`, `VehicleAdvertisement`, `ConnectOptions`, `VehicleDomain`, `VehicleKey`/`VehicleKeyStore`/`KeyProtection`/`KeyPolicy`, `VehicleStatus`·`WhitelistSummary`·`WhitelistEntry`와 그 enum)를 더한다. `:adapter-ble`은 **얇은 Kable 바인딩**(`KableGattConnector`/`KableGattLink`)과 **Kable 없이 테스트 가능한 코어**(`GattLink`/`GattConnector` 내부 포트 위의 `BleTransport`·`BleTransportFactory`)로 나눈다 — 프레이밍·재조립·버퍼링·재시도·오류 분류는 가짜 GATT로 JVM/iOS 시뮬레이터에서 검증하고, Kable 호출부는 실차 스파이크(Task 1)와 샘플 앱(Task 14)에서 사용자가 확인한다. `:adapter-crypto`는 같은 원칙으로 `KeyCreationChain`(하드웨어 → 소프트웨어 순서 결정)과 `KeychainItems`/`KeyWrapper` 내부 포트를 두고 플랫폼 호출을 얇게 감싼다. `:application`에 `VehicleInfoQueries`(Go `getVCSECInfo` 계열)와 M2 인계 항목(일회용 `VehicleSession`, 세션 이벤트 → 캐시 저장·오래된 캐시 삭제)을 넣는다. `:sdk`는 `TeslaBle`(조립)·`DefaultVehicle`·`PlatformContext`만 갖는다.

**Tech Stack:** Kotlin 2.4.20 (K2, `explicitApi`), Gradle 9.6.0(configuration cache) + JDK 17, kotlinx-coroutines 1.11.0, Wire 7.0.4, okio, **Kable 0.45.0**(`com.juul.kable`, `kotlin.uuid.Uuid`), Android `AndroidKeyStore`/JCA, Apple `Security.framework`/`CoreBluetooth`(cinterop `platform.*`), SKIE 0.10.15(`:sdk`), SwiftUI·Jetpack Compose 샘플, xcodegen.

**Spec:** `{{SDD_FILE}}` §1.3(`pkg/connector/ble/ble.go` 행), §2.1(포트: `Transport`, `TransportFactory`, `VehicleKeyStore`, `SessionCache`), §2.3(`:sdk` 파사드), §2.4(`:adapter-ble`), §2.5(키스토어), §2.6(플랫폼 캐시), §3.1(스캔·연결 시퀀스), §5(동시성), §6(`TransportError`, `KeyStoreError`), §7.1~§7.3, §8.2(키스토어), §8.4(BLE 권한), §9.5(어댑터 통합 테스트), §10(로깅), §12; `{{PRD_FILE}}` FR-001~FR-006, FR-020~FR-021, FR-025, FR-028, FR-030~FR-032, FR-112, NFR-004, NFR-006, NFR-009; ADR-0003(Kable·스파이크), ADR-0006(결과 모델), ADR-0007(세션 캐시), ADR-0008(키 보관), ADR-0010(타임아웃). 참고 매뉴얼: `{{MANUAL_DIR}}02-ble-transport.md` §2~§13, `10-porting-guide.md` §1~§3, §12, `00-agent-guide.md` §3.3-19~21, `05-command-catalog.md` B, `03-protocol.md` §12. 로드맵: `{{PLANS_DIR}}2026-09-26-roadmap.md` M3 행. 직전 인계: `{{HANDOFF_DIR}}2026-09-27-m2-dispatcher.md`("막힌 점" 전체), `{{HANDOFF_DIR}}2026-09-27-m2-rulings.md`.

**M2까지 이미 있는 것 (다시 만들지 않는다):** `Transport`/`TransportState`/`AuthMethod`, `Dispatcher`(수신 코루틴·드롭 규칙·`loadSessions`/`exportSessions`), `SessionState`, `PendingRequest`, `HandshakeFlow`, `SendWithRetry`, `VehicleSession`(`connect`/`startSession`/`disconnect`), `CommandTimeouts`, `VcsecCommands`(직렬화 `Mutex`)/`VcsecResponses`(`FIRST_MESSAGE`·`COMMAND_STATUS_ABSENT`·`WHITELIST_OPERATION_COMPLETE`), `InfotainmentCommands`/`InfotainmentResponses`, `SessionCache` 포트·`SessionCacheCodec`(`encodeSnapshots`/`decodeSessions`)·`KeyId`·`InMemorySessionCache`·`SessionCacheSync`, `TeslaLogger`/`LogLevel`, `Framer`/`Reassembler`/`LocalName`, `VehicleError.TransportError.*`/`KeyStoreError.*`, `FakeTransport`/`FakeVehicle`/`RecordingLogger`/`RecordingSessionCache`/`TestCrypto`, `platformCryptoPrimitives()`/`platformRandomSource()`/`softwareEcdhKey()`.

**실차 환경 (사용자 답, 2026-10-15):** **iPhone + 실차.** 스파이크와 실차 확인은 iOS 샘플 앱으로 한다. Android는 에뮬레이터·단위 테스트로만 검증하고 Android 실기기 확인은 체크리스트의 "미확인" 항목으로 남긴다.

## 설계 구체화 (승인 필요)

SDD·ADR이 비워 두었거나 M2 인계 노트가 "M3에서 정한다"고 남긴 항목을 아래처럼 정한다. 계획 승인과 함께 승인된다. 원본과 다른 동작은 구현 Task가 `{{SDD_FILE}}` §12 "원본과 다른 동작 (의도)"에 기록하고, Task 15가 한 번 더 확인한다.

1. **`TransportFactory`는 값으로 돌려준다(ADR-0006).** `scan(localName): Flow<ScanEvent>`(`ScanEvent.Found(advertisement)` / `ScanEvent.Failed(error)` 뒤 완료) — 권한 없음·블루투스 꺼짐을 예외 없이 앱에 알리기 위해서다(SDD §2.1의 `Flow<VehicleAdvertisement>`를 대체; Swift에서는 SKIE `AsyncSequence` + `onEnum(of:)`). `connect(vin, target: VehicleAdvertisement?, options: ConnectOptions): VehicleResult<Transport>`. `ConnectOptions(timeout: Duration? = null)`(null이면 `TeslaBleConfig.connectTimeout` 30초).
2. **connectable이 알려지지 않은 광고(`isConnectable == null`)는 연결을 시도한다.** Kable `Advertisement.isConnectable`은 `Boolean?`이다. `false`일 때만 Go처럼 `MaxConnectionsExceeded`로 즉시 실패한다(`ble.go:302-304`).
3. **연결 재시도는 FR-004대로 1초 간격.** Go `NewConnectionFromScanResult`(`ble.go:259-278`)는 시도 사이에 sleep이 없다(타깃이 고정되면 ctx 만료까지 바쁘게 돈다). 실패한 시도에서 열린 링크는 닫는다(Go는 닫지 않아 누수 — `ble.go:280-360`의 어느 오류 경로도 `client`를 닫지 않음). MTU 교환 실패는 Go처럼 재시도하지 않고 블록 길이 20으로 계속한다(`ble.go:350-352`). 매뉴얼 `10-porting-guide.md:92`("서비스·특성·구독·MTU 실패 시 연결을 끊고 재시도")·`:72`("timeout → retry")·`:88`(`ble.MaxMTU` = 517)와 원본의 차이는 Task 15가 사용자에게 보고한다.
4. **RX 구독 방식(notify/indication)은 Task 1 스파이크로 정한다(ADR-0003).** Go는 indication으로 구독한다(`ble.go:345` `Subscribe(rxChar, true, …)`의 두 번째 인자 `ind`). Kable 0.45.0은 특성이 notify를 지원하면 notify를 고른다(Android `BluetoothDeviceAndroidPeripheral`의 CCCD 쓰기). iOS CoreBluetooth는 앱이 CCCD(0x2902)를 직접 쓰는 것을 허용하지 않으므로 iOS에서 "indication 강제"는 불가능하다. 분기: (a) 0213이 indicate만 지원 → 기본 구독으로 충분, (b) 둘 다 지원하고 기본 구독으로 응답이 옴 → 기본 구독, (c) 둘 다 지원하는데 응답이 오지 않음 → Android는 `onSubscription`에서 CCCD `0x02 0x00` 쓰기, iOS는 **작업을 멈추고 사용자에게 보고**(ADR-0003 대안 4도 iOS에서는 같은 제약). 결과는 ADR-0003에 "스파이크 결과" 절로 남긴다.
5. **`BleTransport` 계약(M2 인계: `incoming` 버퍼링, 쓰기 중 취소).** 생성 즉시 `GattLink.notifications`를 수집해 `Channel(64)`에 넣는다 — 디스패처가 구독하기 전에 도착한 응답을 버리지 않는다(`Transport` KDoc에 계약으로 적는다). 가득 차면 드롭 + ERROR 로그(Go `flush`도 드롭, `ble.go:67-87`). TX/RX 헥사는 `BleTransport`가 DEBUG로 남긴다(Go `ble.go:76` `RX: %02x`, `ble.go:113` `TX: %02x` — 디스패처가 아니라 커넥터가 남김). `send`는 닫힌 뒤 `Failure(TransportError.Disconnected)`, 1024바이트 초과는 `Failure(InvalidArgument)`(Framer의 `IllegalArgumentException`을 값으로), 쓰기 실패는 `Failure(WriteFailed)`(재시도 가능, Go도 쓰기 오류를 그대로 돌려줌). **프레임 중간(첫 청크 이후)에 취소되면 전송을 닫는다** — Go는 `ctx`를 무시하고 끝까지 쓴다(`ble.go:108-127`). 반쯤 쓴 프레임 뒤에 다음 프레임을 바로 보내면 차량 재조립 버퍼(1초 리셋 전)가 두 프레임을 섞기 때문이다.
6. **블록 길이.** Go: `min(txMtu, 1024) - 3`(ATT MTU 기준, `ble.go:354`), 실패 시 `DefaultMTU(23) - 3 = 20`. Android: `requestMtu(517)`이 돌려준 ATT MTU에 같은 식을 쓴다. iOS: `maximumWriteValueLengthForType(WithResponse)`는 이미 `ATT MTU - 3`이므로 `min(len, 1021)`. 두 식은 `BlockLength.fromAttMtu`/`fromMaxWriteLength` 순수 함수로 테스트한다. 쓰기는 항상 `WriteType.WithResponse`(Go `noRsp=false`, `ble.go:121`; Kable 기본값은 `WithoutResponse`이므로 반드시 명시).
7. **권한·블루투스 꺼짐(FR-006).** Kable은 어댑터 상태·권한을 공개 흐름으로 주지 않는다(`Bluetooth.availability`는 ERROR 수준 deprecated). 스캔·연결 직전에 `BleAvailability.check()`(Android: `checkSelfPermission(BLUETOOTH_SCAN/CONNECT)`, iOS: `CBManager.authorization`의 denied/restricted)로 `PermissionDenied`를 먼저 돌려주고, 그 뒤 Kable 예외(`UnmetRequirementException(BluetoothDisabled)` → `BluetoothOff`, `IllegalStateException("Missing required … permission")`·`SecurityException` → `PermissionDenied`, `NotConnectedException`/`GattStatusException`/`IOException` → `ConnectFailed`)를 `KableErrors.map`이 변환한다. 실시간 어댑터 상태 흐름은 M3 범위가 아니다.
8. **`VehicleKeyStore`도 값으로 돌려준다(ADR-0006).** `getOrCreate(alias, policy): VehicleResult<VehicleKey>`, `get(alias): VehicleResult<VehicleKey?>`(없으면 `Success(null)`), `delete(alias): VehicleResult<Unit>`(없어도 성공). SDD §2.1 시그니처를 Task 2에서 고친다. `KeyPolicy`는 `PREFER_HARDWARE`(기본, D10) / `REQUIRE_HARDWARE`(하드웨어가 없으면 `Failure(HardwareUnavailable)`) / `SOFTWARE_ONLY`(테스트·에뮬레이터). JVM 타깃은 **비영속 `InMemoryVehicleKeyStore`**(소프트웨어 키)로 둔다 — 제품 타깃이 아니라 테스트·데스크톱 실험용이며 `platform-notes.md`에 적는다.
9. **FR-028(로컬 키 삭제 시 캐시 비움)은 기존 `keyId` 검사로 충족한다.** 세션 캐시는 VIN당 하나이고 `keyId = SHA1(공개키)`가 들어 있어(D26), 키를 지운 뒤 새 키로 `connect()`하면 `SessionCache.load`가 `keyId` 불일치로 그 항목을 지운다(M2 `InMemorySessionCache`·Task 9 플랫폼 캐시 모두 같은 규칙). 별도의 "모든 VIN 캐시 비우기" API는 만들지 않는다(YAGNI). `TeslaBle.keys`의 KDoc이 이 동작을 밝힌다.
10. **소프트웨어 대체 키의 보관 형식.** 두 플랫폼 모두 개인키를 `04‖X‖Y‖d` 97바이트(Apple `SecKeyCopyExternalRepresentation` 형식, `SoftwareKeyPair.toExternal`)로 다룬다. Android: 이 97바이트를 Keystore AES-256-GCM 키(`<alias>.wrap`)로 감싸 `filesDir/teslable/keys/<alias>.enc`에 둔다 — 형식 `"TBK1"(4) | iv(12) | ciphertext+tag`(SDD §8.2의 PKCS#8 대신; Task 15가 SDD를 고친다). 손상된 파일·항목은 새 키로 자동 교체하지 않고 `Failure(PlatformFailure("stored key is corrupted"))`(조용히 바꾸면 차량에 등록된 키를 잃는다). iOS: 같은 97바이트를 Keychain generic password(service `io.github.smallmiro.teslable.key`, account = alias, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`)에 둔다. Secure Enclave 키는 Keychain에 영속 키(`kSecAttrIsPermanent`, `kSecAttrApplicationTag = "io.github.smallmiro.teslable.key.<alias>"`)로 둔다.
11. **Keychain 래퍼는 어댑터마다 하나씩 둔다.** `:adapter-crypto`(키)와 `:adapter-storage`(세션 캐시)는 서로 의존할 수 없다(ADR-0001). 각 모듈에 `internal interface KeychainItems`(generic password `read`/`write`/`delete`)와 iOS 구현을 따로 둔다(약 60줄 중복). 로직 테스트는 가짜 `KeychainItems`로 하고, 실제 Keychain은 iPhone 샘플 앱에서 사용자가 확인한다 — Kotlin/Native 시뮬레이터 테스트 실행 파일은 호스트 앱·entitlement가 없어 Keychain이 `errSecMissingEntitlement(-34018)`로 실패할 수 있기 때문이다(Task 8 Step 1이 이것을 먼저 확인하고, 동작하면 실제 Keychain 왕복 테스트를 추가한다).
12. **인증 없는 VCSEC 조회(FR-025, FR-031, FR-032).** Go `getVCSECInfo`(`vcsec.go:107-130`): `UnsignedMessage{InformationRequest{type, slot?}}`를 `AuthMethod.NONE` + `FIRST_MESSAGE`로 보낸다. 기존 `VcsecCommands.execute`를 그대로 써서 VCSEC 직렬화(FR-049)를 공유한다. Go는 응답에 해당 필드가 없으면 `(nil, nil)`을 돌려주지만(`state.go:13-19`, `security.go:290-304`) 우리는 `Failure(BadResponse("missing <field>"))`로 돌려준다(값 객체를 만들 수 없음). `listKeys`는 Go `list-keys`(`cmd/tesla-control/commands.go:489-517`)처럼 `slotMask`의 켜진 비트마다 `keyInfoBySlot`을 부르되, Go가 실패한 슬롯을 출력하고 건너뛰는 것과 달리 **첫 실패를 돌려준다**(부분 목록은 앱이 "키가 없다"로 오해할 수 있다). `timeout`은 Go ctx처럼 `listKeys` 전체에 하나다(남은 시간을 각 요청에 넘긴다).
13. **`VehicleSession`은 연결당 한 번만 쓴다(M2 인계).** `connect()`는 멱등이고, `disconnect()` 뒤 `connect()`는 `IllegalStateException`(프로그래밍 오류) — 재연결은 `TeslaBle.connect()`가 새 `Dispatcher`/`VehicleSession`을 만든다. `SessionState`에 영구 "닫힘" 플래그를 두어 `close()` 뒤의 `processHello`/`loadFromCache`가 키를 다시 만들지 않게 한다. `VehicleSession.send`(=`SendWithRetry`)는 `DOMAIN_VEHICLE_SECURITY`를 `Failure(InvalidArgument)`로 거부한다(VCSEC 직렬화 우회 차단, M2 최종 리뷰 Minor 9).
14. **세션 이벤트와 캐시 저장(M2 인계 P7, 사용자 답 a).** `Dispatcher.sessionEvents: SharedFlow<SessionEvent>`(`Updated(domain)` / `StaleCache(domain)`, `extraBufferCapacity = 16`, `DROP_OLDEST`)를 수신 코루틴이 `tryEmit`(비suspend, SDD §5 규칙 유지)으로 낸다. `VehicleSession`이 생성자로 받은 스코프에서 수집해 `Updated` → `SessionCacheSync.store`, `StaleCache` → 그 도메인만 뺀 나머지를 다시 저장(포트에 도메인 단위 삭제가 없으므로 — M2 인계)한다. **오래된 캐시 감지:** 캐시에서 복원된 뒤 아직 한 번도 확인되지 않은(복호화 성공·세션정보 갱신 성공이 없는) `SessionState`가 세션정보 갱신에 실패(`SignerResult.Fault`, 실제로는 태그 불일치 `INVALID_SIGNATURE`)하면 `StaleCache`. 세션 자체는 Go처럼 복구하지 않는다(M2 설계 구체화 8) — 다음 `TeslaBle.connect()`가 새 핸드셰이크를 한다. 디스패처의 세션 키 소거 뒤 저장이 캐시를 지우는 위험(M2 `storingAfterCloseWipesThePreviouslySavedCacheHazard`)을 피하려고, 이벤트 수집은 `disconnect()`가 캐시를 저장하기 **전에** 멈춘다.
15. **파사드(SDD §2.3).** `TeslaBle.connect(vin, key, target: VehicleAdvertisement? = null, options): VehicleResult<Vehicle>`(`target`은 `scan`에서 받은 광고 — `TransportFactory.connect`와 같은 의미). `Vehicle.connection`은 새 `ConnectionState` 타입 대신 기존 `StateFlow<TransportState>`를 노출한다(같은 두 상태; YAGNI — SDD 갱신). M3의 `Vehicle`은 `vin`, `hasKey`, `connection`, `sessions`, `startSession`, `disconnect`, `vehicleStatus`, `keySummary`, `listKeys`만 갖고 M4/M5가 메서드를 더한다. 차량마다 `CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("teslable-<마스킹 VIN>") + CoroutineExceptionHandler(ERROR 로그))`. 사용자 로거는 `SafeLogger`로 감싸 로거가 던진 예외(취소 제외)를 삼킨다 — 수신 코루틴이 죽지 않게(M2 인계). `SessionCacheSync`의 `keyId`는 파사드가 `KeyId.of(key.publicKey, crypto)`로 직접 유도한다(M2 인계 — 다른 키의 `keyId`를 넘길 여지를 없앤다).
16. **ADR-0010은 유지한다.** `expires_at`은 계속 `commandLifetime`(기본 5초)이다. 남은 `timeout`으로 줄이는 안(M2 인계)은 채택하지 않는다 — 재시도 대기 중 수명이 0에 가까워지면 차량이 `TIME_EXPIRED`로 거부해 오히려 재시도가 늘고, Go도 줄이지 않는다(`defaultExpiration`). Task 15가 ADR-0010에 "M3 재검토: 유지" 한 줄을 남긴다.
17. **스파이크 코드는 TDD 예외다(ADR-0003이 정한 탐색 작업).** Task 1의 `GattProbe`와 `TeslableDiagnostics`는 실차에서만 의미가 있어 단위 테스트가 없다 — 대신 재사용하는 순수 부분(UUID 상수, 프로브 요청 바이트)은 테스트하고, 프로브 자체는 Task 14가 지운다(`@InternalTeslableApi`, `apiDump`에 남지 않게 `internal` + `:sdk` 진단 객체만 공개).
18. **iOS 실기기 서명 정보는 커밋하지 않는다.** `samples/ios/project.yml`이 git-ignored `samples/ios/Local.xcconfig`(사용자가 `DEVELOPMENT_TEAM = XXXXXXXXXX` 한 줄을 씀)를 포함하게 하고, 실기기용으로 `iosArm64` 프레임워크를 링크하는 별도 구성(`TeslableSampleDevice` 스킴)을 둔다. `verify.sh`는 지금처럼 시뮬레이터 빌드만 한다.

## Global Constraints

- 패키지 루트 `io.github.smallmiro.teslable`. `:adapter-ble` → `io.github.smallmiro.teslable.ble`, `:adapter-crypto` → `…crypto`, `:adapter-storage` → `…storage`, `:application` → `…application.*`, `:sdk` → `io.github.smallmiro.teslable`(파사드). Wire 생성 코드는 `com.tesla.generated.*` 그대로.
- 타깃: `jvm()`, Android(`minSdk 31`, `compileSdk 37`), `iosArm64()`, `iosSimulatorArm64()`, iOS 배포 타깃 16.0.
- 모든 라이브러리 모듈은 `explicitApi()`, `allWarningsAsErrors = true`, 공개 API에 한국어 한 줄 이상 KDoc(detekt `UndocumentedPublic*`). `MaxLineLength` 140.
- 의존 방향(ADR-0001, `build-logic/…/teslable.boundaries.gradle.kts`): `:application`·`:adapter-*` → `:domain`만, `:sdk` → 전부, `samples/*` → `:sdk`만, `:testing` → `:domain`·`:adapter-crypto`(테스트 소스셋에서만 참조). **이 계획은 경계 표를 바꾸지 않는다.** `:adapter-ble`·`:sdk`의 `commonTest`에 `:testing`을 추가하는 것은 허용된다(테스트 구성).
- `:domain` 외부 의존은 `kotlin-stdlib*`, `kotlinx-coroutines*`, `wire-runtime`, `okio`뿐. Kable·Android SDK·`platform.*`는 어댑터에만.
- 금지: `!!`, `lateinit`(테스트 제외), `GlobalScope`, `runBlocking`(테스트 제외), `println`, 자체 구현 암호 알고리즘(NFR-005), `INTERNET` 권한·네트워크 호출(NFR-014), 개인키 내보내기 API(NFR-004). 벽시계는 `:adapter-storage`의 세션 캐시(주입된 `wallClockMillis`)와 키 생성 시각 기록(`:adapter-crypto`, 주입)에서만.
- 예외는 `CancellationException`과 프로그래밍 오류(`IllegalArgumentException`/`IllegalStateException`)에만(ADR-0006). 플랫폼(Kable, Keystore, Keychain, 파일 I/O)에서 온 오류는 어댑터 경계에서 `VehicleError`(`TransportError.*`, `KeyStoreError.*`)로 바꾼다. 취소는 값으로 바꾸지 않는다(ADR-0010).
- 로그(FR-112, SDD §10): VIN은 `Vin.toString()`(마스킹)만, TX/RX 헥사는 DEBUG에서만, 개인키·공유 X·K·평문 페이로드는 어떤 레벨에서도 금지. 로컬 이름(`S…C`)은 그대로 써도 된다.
- 포팅 파일 헤더 1행: `// Ported from vehicle-command@a4b43c1 <원본 경로> (Apache-2.0)`(심볼 목록은 2행). 새 포팅 파일은 `tools/ci/ported-files.txt`에 추가(`check-license.sh`가 검사). 테스트 VIN은 `5YJ30123456789ABC`, `5YJS0000000000000`, `0123456789ABCDEFG`만. 실제 VIN·개인키는 픽스처·로그·커밋 금지.
- 커밋 접두어 `struct:`/`feat:`/`fix:`/`test:`/`docs:`/`chore:`, 구조 변경 커밋이 먼저(Tidy First). `Refs:` 줄은 trailer 블록 안(`Co-Authored-By` 바로 위, 빈 줄 없이). 각 Task는 브랜치 `m3/<slug>`(최신 `origin/main`에서) → PR → **`tools/ci/verify.sh` 로컬 통과**(ADR-0012) → AI 리뷰(요약을 PR 본문에) → rebase merge. PR 본문 "원본 대응"에 참고한 매뉴얼 절과 Go 파일·줄을 적는다(AGENTS.md §3 규칙 5-1).
- 공개 API가 바뀌는 Task는 `./gradlew :<module>:apiDump`(단독 호출)로 `<module>/api/*`를 갱신해 같은 커밋에 넣는다. 문서(SDD·PRD 용어·`{{LIB_DOCS_DIR}}`)는 Task 15에서 한 번에 맞추되, 각 Task의 "SDD 기록" 단계가 지시한 §12 항목은 그 Task PR에서 바로 쓴다.
- `.proto`·`{{REF_REPO_DIR}}`는 읽기 전용, `{{MANUAL_DIR}}`는 수정 금지. 매뉴얼과 원본이 다르면 원본을 따르고 PR 본문과 Task 15 인계 노트에 보고한다.
- 테스트 시간은 `runTest`의 가상 시간(`testTimeSource`, `advanceTimeBy`, `runCurrent`)만. 실제 sleep 금지. 실차·실기기에서만 확인할 수 있는 동작은 단위 테스트 대신 Task 14의 "사용자 확인" 체크리스트로 넘긴다.

## 빌드 사실 (구현자가 알아야 할 것)

- `./gradlew :<module>:apiDump`는 단독으로 실행한다(configuration cache 순서 오류 회피). test/check와 같은 명령에 섞지 않는다.
- detekt 어댑터 설정(`config/detekt/detekt-adapter.yml`)이 `platform.*`·`android.*` 임포트를 허용한다. `:sdk`는 지금 기본 설정이므로 Task 13에서 `:sdk/build.gradle.kts`에 어댑터 설정을 추가한다(`PlatformContext` actual이 `android.content.Context`를 쓰기 때문).
- Kable: 모든 공개 타입은 `com.juul.kable`. UUID는 `kotlin.uuid.Uuid`(`@OptIn(ExperimentalUuidApi::class)`가 Kotlin 2.4에서 필요하면 파일 단위로 붙인다). `Scanner { filters { match { name = Filter.Name.Exact(x) } } }`, `Peripheral(advertisement) { onServicesDiscovered { … } }`, `peripheral.connect()`(연결 스코프 반환), `peripheral.disconnect()`, `peripheral.state: StateFlow<State>`(`State.Disconnected(status)`), `characteristicOf(service, characteristic)`, `peripheral.write(ch, data, WriteType.WithResponse)`, `peripheral.observe(ch, onSubscription = {})`(재연결에도 살아남고 **연결이 끊겨도 완료되지 않는다** — `KableGattLink`이 `state`로 끊김을 감지해 흐름을 끝낸다), `peripheral.maximumWriteValueLengthForType(WriteType.WithResponse)`(공통; Android는 `requestMtu` 전에는 20), Android 전용 `(peripheral as AndroidPeripheral).requestMtu(517)`과 `ServicesDiscoveredPeripheral.requestMtu`. `Peripheral(identifier)`는 공통이 아니다 — 광고 객체로만 만든다. JVM은 btleplug(JNA) 실구현이 있어 컴파일된다(실행은 하지 않는다).
- Android `AndroidKeyStore`는 JVM/호스트 테스트(`testAndroidHostTest`)에서 쓸 수 없다. 그래서 Keystore 호출은 `androidMain`의 얇은 클래스에 가두고, 로직(체인 순서, 공개키 인코딩, 감싼 파일 형식)은 `jvmCommonMain`/`commonMain`에서 가짜로 테스트한다. 실제 Keystore 경로는 에뮬레이터에서 샘플 앱으로 확인한다(Task 14, 사용자 또는 컨트롤러가 로컬 에뮬레이터로).
- iOS 시뮬레이터에는 Secure Enclave가 없다(`SecKeyCreateRandomKey` + `kSecAttrTokenIDSecureEnclave`가 실패) — `KeyCreationChain`이 소프트웨어로 내려가는 것이 시뮬레이터의 정상 동작이다.
- cinterop: `platform.CoreBluetooth.CBManager.authorization`(iOS 13.1+ 클래스 프로퍼티, `CBManagerAuthorization` = `Long`), `platform.Security.*`, `platform.CoreFoundation.*`. CF 객체는 `CFRelease`로 해제한다(기존 `AppleSoftwareEcdhKey.kt`·`CoreFoundationInterop.kt`의 패턴과 헬퍼를 재사용한다).
- iOS 테스트는 `./gradlew :<module>:iosSimulatorArm64Test`(Xcode 26, 기기 `iPhone 17`). 샘플 앱 시뮬레이터 빌드는 `verify.sh` (d) 단계(xcodegen + xcodebuild).
- `kotlinx.coroutines.test`의 `testTimeSource`·`advanceTimeBy`·`runCurrent`는 `@OptIn(ExperimentalCoroutinesApi::class)`를 요구할 수 있다(M2와 같이 테스트 클래스에 붙인다).
- Wire 이름: `UnsignedMessage.VCSEC_InformationRequest`, `InformationRequest(informationRequestType, slot)`, `FromVCSECMessage.vehicleStatus/whitelistInfo/whitelistEntryInfo`, `WhitelistInfo(numberOfEntries: Int, slotMask: Int, whitelistEntries)`, `WhitelistEntryInfo(keyId, publicKey: PublicKey?(PublicKeyRaw), metadataForKey: KeyMetadata?(keyFormFactor), slot: Int, keyRole: Role)`, `VehicleStatus(closureStatuses, vehicleLockState, vehicleSleepStatus, userPresence, detailedClosureStatus)`. uint32는 `Int`로 온다.

## Review Focus

스펙이 암시하지만 포팅 테스트만으로는 다루지 않는, 실제 사용자에게 문제가 될 입력 다섯 가지. 각 줄의 테스트를 소유 Task에 넣었다.

1. **명령 대기 중 BLE 연결이 끊김(차량에서 멀어짐)** — `incoming`이 끝나고 `state`가 `Disconnected(Disconnected)`가 되며, 기다리던 명령은 `Uncertain(Timeout(afterSend = true))`, 그 뒤 명령은 `Failure(NotConnected)`여야 한다. 앱이 멈추거나 예외로 죽으면 안 된다. → Task 3 `linkDropCompletesIncomingAndMarksDisconnected`, Task 13 `linkDropDuringCommandIsUncertainThenNotConnected`.
2. **연결 직후, 디스패처가 구독하기 전에 도착한 알림** — 버려지면 첫 핸드셰이크 응답이 사라져 1초 재전송을 기다린다. → Task 3 `buffersNotificationsArrivingBeforeAnyCollector`.
3. **권한 없음·블루투스 꺼짐 상태에서 스캔·연결** — 크래시나 무한 대기가 아니라 즉시 `PermissionDenied`/`BluetoothOff`. → Task 4 `failsFastWithoutRetryOnPermissionOrBluetoothOff`, Task 5 `mapsKableExceptionsToTransportErrors`.
4. **`disconnect()` 뒤 같은 `Vehicle`로 명령을 부름 / 다시 연결** — 명령은 `Failure(NotConnected)`, 재연결은 `TeslaBle.connect()`가 새 객체로. 캐시가 빈 목록으로 덮이면 안 된다. → Task 11 `connectAfterDisconnectIsRejected`, Task 13 `commandsAfterDisconnectReturnNotConnectedAndKeepCache`.
5. **`vehicleStatus`와 `listKeys`를 동시에 부름(화면 두 곳에서 새로고침)** — VCSEC는 한 번에 한 요청만 처리하고 겹치면 `BUSY`를 돌려준다(매뉴얼 `00-agent-guide §3.1-2`). 응답은 요청마다 다른 routing_address로 매칭되므로 섞이지는 않지만, 두 요청이 동시에 차량에 가면 안 된다 — 직렬화(FR-049)가 조회에도 적용돼야 한다. → Task 10 `concurrentInfoQueriesAreSerialized`.

## File Structure

| 파일 | 책임 | Go 대응 / 근거 |
|---|---|---|
| `domain/…/model/VehicleDomain.kt` | 공개 도메인 enum(`VCSEC`, `INFOTAINMENT`) ↔ Wire `Domain` | SDD §2.1 |
| `domain/…/port/TransportFactory.kt` | `TransportFactory`, `ScanEvent`, `VehicleAdvertisement`, `ConnectOptions` | `ble.go` `ScanVehicleBeacon`, `NewConnectionFromScanResult` |
| `domain/…/port/Transport.kt` (수정) | `incoming` 버퍼링 계약 KDoc | M2 인계 |
| `domain/…/port/VehicleKeyStore.kt` | `VehicleKey`, `VehicleKeyStore`, `KeyProtection`, `KeyPolicy` | `pkg/protocol/key.go`(인터페이스만), D10 |
| `domain/…/model/VehicleStatus.kt` | `VehicleStatus` + `LockState`/`ClosureState`/`SleepStatus`/`UserPresence` | `vcsec.proto` `VehicleStatus`, SDD §7.3 |
| `domain/…/model/Whitelist.kt` | `WhitelistSummary`, `WhitelistEntry`, `KeyRole`, `KeyFormFactor` | `vcsec.proto` `WhitelistInfo`/`WhitelistEntryInfo`, `keys.proto` `Role` |
| `adapter-ble/…/ble/BleUuids.kt` | 서비스·TX·RX UUID | `ble.go:33-35` |
| `adapter-ble/…/ble/GattProbe.kt` | 스파이크 진단(Task 14에서 삭제) | ADR-0003 |
| `adapter-ble/…/ble/GattLink.kt` | 내부 포트: 청크 쓰기·알림 흐름·블록 길이·끊기 | `ble.go` `Connection` 필드 |
| `adapter-ble/…/ble/BleTransport.kt` | `Transport` 구현(프레이밍·재조립·버퍼·TX/RX 로그·닫기) | `ble.go` `Send`, `rx`, `flush`, `Close`, `Receive` |
| `adapter-ble/…/ble/GattConnector.kt` | 내부 포트: 스캔·링크 열기(재시도 여부 포함) | `ble.go` `tryToConnect` |
| `adapter-ble/…/ble/BleTransportFactory.kt` | `TransportFactory` 구현(재시도 루프·시간 제한·오류 분류) | `ble.go` `NewConnection`, `NewConnectionFromScanResult`, `ScanVehicleBeacon` |
| `adapter-ble/…/ble/BlockLength.kt` | MTU → 블록 길이 | `ble.go:349-354` |
| `adapter-ble/…/ble/BleAvailability.kt` (+ android/ios/jvm actual 팩토리) | 권한·지원 사전 검사 | SDD §8.4, FR-006 |
| `adapter-ble/…/ble/KableErrors.kt` | Kable 예외 → `TransportError` | – |
| `adapter-ble/…/ble/KableGattConnector.kt`, `KableGattLink.kt` (+ `MtuNegotiation` expect/actual) | 얇은 Kable 바인딩 | `ble.go` `tryToConnect` 본문 |
| `adapter-ble/…/ble/BleTransports.kt` | 공개 팩토리 함수 `kableTransportFactory(…)` | – |
| `adapter-crypto/…/crypto/KeyCreationChain.kt` | 정책별 하드웨어→소프트웨어 시도 순서 | D10, SDD §8.2 |
| `adapter-crypto/…/crypto/InMemoryVehicleKeyStore.kt` | 비영속 소프트웨어 키스토어(JVM 기본, 테스트) | 설계 구체화 8 |
| `adapter-crypto/…/crypto/SoftwareKeyGenerator.kt` (expect/actual) | 소프트웨어 P-256 키쌍 생성 | `pkg/protocol/key.go` `NewECDHPrivateKey` |
| `adapter-crypto/…/crypto/EcPublicKeyEncoding.kt` (jvmCommon) | `ECPoint` → 65바이트(0-패딩) | SDD §8.2 |
| `adapter-crypto/…/crypto/WrappedKeyFile.kt` (jvmCommon) | `TBK1` 감싼 개인키 파일 형식 | 설계 구체화 10 |
| `adapter-crypto/…/crypto/AndroidVehicleKeyStore.kt` (androidMain) | StrongBox→TEE→소프트웨어 | SDD §8.2 |
| `adapter-crypto/…/crypto/KeychainItems.kt` (+ iosMain 구현) | generic password 읽기/쓰기/삭제 포트 | 설계 구체화 11 |
| `adapter-crypto/…/crypto/AppleVehicleKeyStore.kt` (iosMain) | Secure Enclave→소프트웨어 Keychain | SDD §8.2 |
| `adapter-crypto/…/crypto/PlatformKeyStores.kt` | 공개 팩토리(`androidVehicleKeyStore(context)`, `appleVehicleKeyStore()`, `inMemoryVehicleKeyStore()`) | – |
| `adapter-storage/…/storage/FileSessionCache.kt` (jvmCommonMain) | 앱 전용 파일 캐시(원자적 쓰기) | SDD §2.6 |
| `adapter-storage/…/storage/KeychainItems.kt` (+ iosMain), `KeychainSessionCache.kt` (iosMain) | Keychain 캐시 | SDD §2.6 |
| `application/…/vcsec/VehicleInfoQueries.kt` | `vehicleStatus`, `keySummary`, `keyInfoBySlot`, `listKeys` | `vcsec.go` `getVCSECInfo`, `state.go` `BodyControllerState`, `security.go` `KeySummary`/`KeyInfoBySlot`, `commands.go` `list-keys` |
| `application/…/vcsec/VcsecInfoMapping.kt` | Wire → 값 객체 | SDD §7.3 |
| `application/…/dispatcher/SessionEvent.kt` | `SessionEvent.Updated/StaleCache` | 설계 구체화 14 |
| `application/…/dispatcher/SessionState.kt`, `Dispatcher.kt` (수정) | 영구 닫힘, 캐시 복원 미확인 추적, 이벤트 방출 | 설계 구체화 13·14 |
| `application/…/cache/SessionCacheSync.kt` (수정) | `storeExcluding(dispatcher, domain)` | 설계 구체화 14 |
| `application/…/vehicle/VehicleSession.kt`, `SendWithRetry.kt` (수정) | 일회용, 이벤트 수집, VCSEC 거부 | 설계 구체화 13·14 |
| `application/…/logging/SafeLogger.kt` | 로거 예외 격리 | M2 인계 |
| `sdk/…/PlatformContext.kt` (expect + android/ios/jvm actual) | 플랫폼 컨텍스트 | SDD §2.3 |
| `sdk/…/TeslaBle.kt`, `TeslaBleConfig.kt` | 파사드·조립 | SDD §2.3 |
| `sdk/…/Vehicle.kt`, `DefaultVehicle.kt`, `SessionStatus.kt` | 공개 `Vehicle`과 구현 | SDD §2.3 |
| `sdk/…/PlatformComponents.kt` (expect + actual) | 플랫폼 기본 어댑터 조립 | – |
| `sdk/…/DebugLogger.kt` (expect + actual) | 플랫폼 로그 출력 로거 | SDD §10 |
| `sdk/…/TeslableDiagnostics.kt` | 스파이크 진입점(Task 14에서 삭제) | ADR-0003 |
| `testing/…/FakeTransportFactory.kt` | `TransportFactory` 가짜(`FakeVehicle` 감쌈) | M2 설계 구체화 2 |
| `testing/…/FakeVehicleKeyStore.kt` | 메모리 `VehicleKeyStore` 가짜(`TestCrypto` 키) | – |
| `samples/ios/…`, `samples/android/…` | 스캔·연결·키·조회 샘플 | M3 완료 기준 |
| `tools/fixtures/collect-golden.sh`, `mask-vin.sh` | 실차 골든 TX/RX 수집·마스킹 | SDD §9.2, NFR-002 |
| `docs/manual/platform-notes.md`, `getting-started.md` | 사용 문서 초안 | 로드맵 M3 |

## 작업 순서와 의존

```
Task 1 (iOS 스파이크, 실차) ──────────────┐ 결과가 Task 5의 구독 방식을 정한다
Task 2 (:domain 포트·값 객체 + FakeTransportFactory)
  ├─ Task 3 (BleTransport) ─ Task 4 (BleTransportFactory) ─ Task 5 (Kable 바인딩) ◀┘
  ├─ Task 6 (키스토어 공통·소프트웨어) ─ Task 7 (Android Keystore) ─ Task 8 (iOS Secure Enclave/Keychain)
  ├─ Task 9 (플랫폼 세션 캐시)
  ├─ Task 10 (VCSEC 조회 + 값 객체)
  └─ Task 11 (일회용 세션·VCSEC 거부·SafeLogger·FakeVehicle 키 분리) ─ Task 12 (세션 이벤트·캐시 트리거)
Task 13 (:sdk 파사드) ← 2~12 전부
Task 14 (DebugLogger + 샘플 앱 2종 + 스파이크 코드 삭제 + 사용자 실차 확인)
Task 15 (골든 수집 스크립트 + 문서 + 인계 노트)
```

Task 1은 사용자가 실차에서 실행해야 끝난다. 결과를 기다리는 동안 Task 2~4, 6~12를 진행할 수 있다(Task 5만 결과가 필요하다).

---
### Task 1: iOS 실차 스파이크 — 0213 구독 방식·MTU 확인 (ADR-0003)

참고: ADR-0003 "결정" (1)~(4), `{{SDD_FILE}}` §9.5 마지막 항목, `{{MANUAL_DIR}}02-ble-transport.md` §4~§6, `10-porting-guide.md` §2. Go 원본: `pkg/connector/ble/ble.go:33-35`(UUID), `:121`(write with response), `:314-354`(탐색·구독·MTU), `:98-106`(rx). 응답 대조: `pkg/protocol/protocol.md:78`(`list-keys` TX 실제 캡처).

**목적:** 실차에서 (1) 0213의 properties(notify/indicate), (2) Kable 기본 `observe`로 응답이 오는지, (3) `maximumWriteValueLengthForType(WithResponse)` 값을 로그로 확인한다. 인증 없는 `GET_WHITELIST_INFO`(Go `list-keys`의 첫 요청)를 보내 `whitelistInfo`가 돌아오면 "기본 구독으로 충분"이다. 결과는 ADR-0003에 기록하고 Task 5의 구독 방식을 정한다(설계 구체화 4). 이 Task의 프로브 코드는 TDD 예외이며(설계 구체화 17) Task 14에서 지운다.

**Files:**
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleUuids.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/GattProbe.kt`
- Delete: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/Placeholder.kt`
- Create: `adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BleUuidsTest.kt`
- Modify: `adapter-ble/build.gradle.kts` (commonTest에 `:testing`)
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/TeslableDiagnostics.kt`
- Create: `sdk/src/commonTest/kotlin/io/github/smallmiro/teslable/ProbeRequestTest.kt`
- Modify: `sdk/build.gradle.kts` (commonTest에 `:testing`)
- Create: `samples/ios/Sources/DiagnosticsView.swift`
- Modify: `samples/ios/Sources/ContentView.swift`, `samples/ios/project.yml`, `.gitignore`
- Create: `samples/ios/Device.xcconfig`, `samples/ios/Local.xcconfig.example`
- Modify: `samples/ios/README.md`, `tools/ci/ported-files.txt`
- Modify (스파이크 후): `docs/adr/0003-ble-kable.md`

**Interfaces:**
- Consumes: `Framer.frame`, `Reassembler`, `LocalName.of`, `platformCryptoPrimitives()`, `platformRandomSource()`, `ProtoAdapter.decodeOrNull`(`@InternalTeslableApi`), `toHex()`(`@InternalTeslableApi`), Kable `Scanner`/`Peripheral`/`characteristicOf`/`descriptorOf`/`observe`/`write`.
- Produces (Task 3~5가 사용): `internal object BleUuids { SERVICE; TO_VEHICLE; FROM_VEHICLE; CCCD }`(`kotlin.uuid.Uuid`). 스파이크 결과(ADR-0003 "스파이크 결과" 절): `subscription = KABLE_DEFAULT | ANDROID_FORCE_INDICATION | BLOCKED_ON_IOS`, iOS `maximumWriteValueLength` 관측값.

- [ ] **Step 1: 실패 테스트 작성 — UUID와 프로브 요청 바이트**

`adapter-ble/build.gradle.kts`의 `sourceSets`에 추가:

```kotlin
        commonTest.dependencies { implementation(project(":testing")) }
```

`sdk/build.gradle.kts`의 `sourceSets`에 같은 줄을 추가한다.

```kotlin
// adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BleUuidsTest.kt
package io.github.smallmiro.teslable.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class BleUuidsTest {
    @Test
    fun matchesGoGattUuids() {
        // ble.go:33-35 vehicleServiceUUID, toVehicleUUID, fromVehicleUUID
        assertEquals("00000211-b2d1-43f0-9b88-960cebf8b91e", BleUuids.SERVICE.toString())
        assertEquals("00000212-b2d1-43f0-9b88-960cebf8b91e", BleUuids.TO_VEHICLE.toString())
        assertEquals("00000213-b2d1-43f0-9b88-960cebf8b91e", BleUuids.FROM_VEHICLE.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", BleUuids.CCCD.toString())
    }
}
```

```kotlin
// sdk/src/commonTest/kotlin/io/github/smallmiro/teslable/ProbeRequestTest.kt
package io.github.smallmiro.teslable

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.WhitelistInfo
import io.github.smallmiro.teslable.testing.FixedRandom
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalTeslableApi::class)
class ProbeRequestTest {
    @Test
    fun buildsUnauthenticatedWhitelistInfoRequestLikeGoListKeys() {
        val address = ByteArray(16) { 0x11 }
        val uuid = ByteArray(16) { 0x22 }
        val decoded = RoutableMessage.ADAPTER.decode(ProbeRequest.build(FixedRandom(address, uuid)))

        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, decoded.to_destination?.domain)
        assertEquals(address.toHex(), decoded.from_destination?.routing_address?.hex())
        assertEquals(uuid.toHex(), decoded.uuid.hex())
        assertEquals(2, decoded.flags) // vehicle.go:22 DefaultFlags = 1 << FLAG_ENCRYPT_RESPONSE (getReceiver가 인증 없는 요청에도 싣는다)
        // protocol.md:78의 실제 list-keys TX와 같은 페이로드(필드 단위 비교, 로드맵 공통 규칙)
        val goTx = RoutableMessage.ADAPTER.decode(PROTOCOL_DOC_LIST_KEYS_TX.hexToBytes())
        assertEquals(goTx.protobuf_message_as_bytes, decoded.protobuf_message_as_bytes)
        assertEquals("0a020805", decoded.protobuf_message_as_bytes?.hex())
        assertEquals(goTx.to_destination?.domain, decoded.to_destination?.domain)
    }

    @Test
    fun describesWhitelistInfoReply() {
        val payload = FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 3, slotMask = 0x7)).encode().toByteString()
        val reply = RoutableMessage(protobuf_message_as_bytes = payload).encode()
        assertEquals("RX whitelistInfo: entries=3 slotMask=0x7", ProbeRequest.describe(reply))
    }

    @Test
    fun describesUndecodableReply() {
        assertEquals("RX message: undecodable (1B)", ProbeRequest.describe(byteArrayOf(0xFF.toByte())))
    }

    private companion object {
        // pkg/protocol/protocol.md:78 — tesla-control -ble -debug list-keys의 TX(2023-12, flags 필드 도입 전 캡처)
        const val PROTOCOL_DOC_LIST_KEYS_TX =
            "320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805"
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :adapter-ble:jvmTest :sdk:jvmTest --console=plain`
Expected: 컴파일 실패 (`BleUuids`, `ProbeRequest` 없음)

- [ ] **Step 3: 구현**

`Placeholder.kt`를 지우고 아래 파일을 만든다.

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleUuids.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// vehicleServiceUUID, toVehicleUUID, fromVehicleUUID
package io.github.smallmiro.teslable.ble

import kotlin.uuid.Uuid

/** Tesla 차량 GATT UUID(Go `ble.go:33-35`)와 CCCD. */
internal object BleUuids {
    val SERVICE: Uuid = Uuid.parse("00000211-b2d1-43f0-9b88-960cebf8b91e")
    val TO_VEHICLE: Uuid = Uuid.parse("00000212-b2d1-43f0-9b88-960cebf8b91e")
    val FROM_VEHICLE: Uuid = Uuid.parse("00000213-b2d1-43f0-9b88-960cebf8b91e")

    /** Client Characteristic Configuration descriptor(0x2902). Android에서만 직접 쓸 수 있다(iOS CoreBluetooth는 금지). */
    val CCCD: Uuid = Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
}
```

`kotlin.uuid.Uuid`가 Kotlin 2.4.20에서 실험 API로 표시되면 파일 맨 위에 `@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)`를 붙인다(다른 파일에도 같은 규칙).

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/GattProbe.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.descriptorOf
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.transport.Framer
import io.github.smallmiro.teslable.transport.Reassembler
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val MAX_BLOCK_LENGTH = 1021 // min(MTU, 1024) - 3 의 상한(ble.go:354)

/**
 * ADR-0003 실차 스파이크 전용 진단(설계 구체화 17). 스캔 → 연결 → 0213 properties 로그 → 기본 `observe` 구독 →
 * [request] 전송 → [listenFor] 동안 수신 청크를 로그로 남기고 재조립한 메시지를 [onMessage]로 넘긴다. M3 Task 14에서 지운다.
 */
@InternalTeslableApi
public object GattProbe {
    /** 프로브를 한 번 실행한다. 예외는 로그로 바꾸고 취소만 전파한다. */
    @Suppress("LongParameterList", "LongMethod", "TooGenericExceptionCaught") // 스파이크 진단: 실패 원인을 전부 로그로 남긴다
    public suspend fun run(
        localName: String,
        request: ByteArray,
        forceIndication: Boolean,
        log: (String) -> Unit,
        onMessage: (ByteArray) -> Unit,
        listenFor: Duration = 10.seconds,
        scanTimeout: Duration = 30.seconds,
    ) {
        log("scan: looking for $localName")
        val advertisement =
            withTimeoutOrNull(scanTimeout) {
                Scanner { filters { match { name = Filter.Name.Exact(localName) } } }.advertisements.first()
            }
        if (advertisement == null) {
            log("scan: timeout after $scanTimeout")
            return
        }
        log("scan: found rssi=${advertisement.rssi} connectable=${advertisement.isConnectable}")
        val peripheral = Peripheral(advertisement)
        try {
            peripheral.connect()
            log("connect: ok")
            peripheral.services.value.orEmpty()
                .filter { it.serviceUuid == BleUuids.SERVICE }
                .flatMap { it.characteristics }
                .forEach { c ->
                    val p = c.properties
                    log("char ${c.characteristicUuid}: write=${p.write} notify=${p.notify} indicate=${p.indicate}")
                }
            val maxWrite = peripheral.maximumWriteValueLengthForType(WriteType.WithResponse)
            val blockLength = minOf(maxWrite, MAX_BLOCK_LENGTH)
            log("mtu: maximumWriteValueLength(WithResponse)=$maxWrite blockLength=$blockLength")
            val tx = characteristicOf(BleUuids.SERVICE, BleUuids.TO_VEHICLE)
            val rx = characteristicOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE)
            val reassembler = Reassembler()
            withTimeoutOrNull(listenFor) {
                peripheral.observe(rx, onSubscription = {
                    log("subscribe: enabled (forceIndication=$forceIndication)")
                    if (forceIndication) {
                        try {
                            peripheral.write(descriptorOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE, BleUuids.CCCD), byteArrayOf(0x02, 0x00))
                            log("cccd: wrote 0x0200 (indication)")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("cccd: write failed: ${e::class.simpleName}: ${e.message}")
                        }
                    }
                    val chunks = Framer.frame(request, blockLength)
                    log("TX: ${chunks.joinToString("") { it.toHex() }}")
                    chunks.forEach { peripheral.write(tx, it, WriteType.WithResponse) }
                    log("TX: sent ${chunks.size} chunk(s)")
                }).collect { chunk ->
                    log("RX chunk (${chunk.size}B): ${chunk.toHex()}")
                    reassembler.push(chunk).forEach(onMessage)
                }
            }
            log("listen: done after $listenFor")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("error: ${e::class.simpleName}: ${e.message}")
        } finally {
            withContext(NonCancellable) { peripheral.disconnect() }
            log("disconnected")
        }
    }
}
```

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/TeslableDiagnostics.kt
package io.github.smallmiro.teslable

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequest
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.UnsignedMessage
import io.github.smallmiro.teslable.ble.GattProbe
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.crypto.platformRandomSource
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.protocol.decodeOrNull
import io.github.smallmiro.teslable.transport.LocalName
import okio.ByteString.Companion.toByteString

/** M3 Task 1 실차 스파이크 진입점(ADR-0003). Task 14에서 지운다 — 앱 코드에서 쓰지 않는다. */
@OptIn(InternalTeslableApi::class)
public object TeslableDiagnostics {
    /** [vin] 차량에 인증 없는 `GET_WHITELIST_INFO`를 보내고 과정을 [onLine]으로 알린다. */
    public suspend fun probe(
        vin: String,
        forceIndication: Boolean,
        onLine: (String) -> Unit,
    ) {
        val parsed = runCatching { Vin(vin) }.getOrNull()
        if (parsed == null) {
            onLine("invalid VIN")
            return
        }
        val localName = LocalName.of(parsed, platformCryptoPrimitives())
        GattProbe.run(
            localName = localName.value,
            request = ProbeRequest.build(platformRandomSource()),
            forceIndication = forceIndication,
            log = onLine,
            onMessage = { onLine(ProbeRequest.describe(it)) },
        )
    }
}

/** 프로브 요청·응답 요약(Go `getVCSECInfo` + `getReceiver` 모양, 인증 없음). */
@OptIn(InternalTeslableApi::class)
internal object ProbeRequest {
    private const val ADDRESS_LENGTH = 16
    private const val UUID_LENGTH = 16
    private const val FLAG_ENCRYPT_RESPONSE = 2
    private const val HEX_RADIX = 16

    fun build(random: RandomSource): ByteArray {
        val payload =
            UnsignedMessage(
                VCSEC_InformationRequest =
                    InformationRequest(informationRequestType = InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO),
            ).encode().toByteString()
        return RoutableMessage(
            to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
            from_destination = Destination(routing_address = random.nextBytes(ADDRESS_LENGTH).toByteString()),
            protobuf_message_as_bytes = payload,
            uuid = random.nextBytes(UUID_LENGTH).toByteString(),
            flags = FLAG_ENCRYPT_RESPONSE,
        ).encode()
    }

    fun describe(message: ByteArray): String {
        val routable = RoutableMessage.ADAPTER.decodeOrNull(message) ?: return "RX message: undecodable (${message.size}B)"
        val from = routable.protobuf_message_as_bytes?.let { FromVCSECMessage.ADAPTER.decodeOrNull(it.toByteArray()) }
        val info = from?.whitelistInfo
        return when {
            info != null -> "RX whitelistInfo: entries=${info.numberOfEntries} slotMask=0x${info.slotMask.toUInt().toString(HEX_RADIX)}"
            routable.signedMessageStatus != null -> "RX status: ${routable.signedMessageStatus}"
            else -> "RX message: ${message.size}B"
        }
    }
}
```

`build`의 `random.nextBytes` 호출 순서(주소 → uuid)는 테스트의 `FixedRandom(address, uuid)` 순서와 같아야 한다 — 인자 평가 순서대로 `from_destination`이 `uuid`보다 먼저 평가된다.

`tools/ci/ported-files.txt`에 `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleUuids.kt`를 추가한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :adapter-ble:jvmTest :sdk:jvmTest :sdk:iosSimulatorArm64Test --console=plain`
Expected: PASS
Run: `./gradlew :sdk:apiDump` 그다음 `./gradlew :adapter-ble:apiDump`
Expected: `sdk/api/*`에 `TeslableDiagnostics`, `adapter-ble/api/*`에 `GattProbe`가 추가됨

- [ ] **Step 5: iOS 샘플에 진단 화면과 실기기 구성 추가**

`.gitignore`의 `# Xcode / iOS` 절에 추가:

```
samples/ios/Local.xcconfig
```

```
// samples/ios/Local.xcconfig.example — Local.xcconfig로 복사해 자신의 팀 ID를 넣는다(커밋 금지)
DEVELOPMENT_TEAM = XXXXXXXXXX
```

```
// samples/ios/Device.xcconfig — 실기기(iosArm64) 빌드 구성. Local.xcconfig가 없으면 무시된다.
#include? "Local.xcconfig"
CODE_SIGN_STYLE = Automatic
FRAMEWORK_SEARCH_PATHS = $(inherited) $(SRCROOT)/../../sdk/build/bin/iosArm64/debugFramework
```

`samples/ios/project.yml`의 `targets:` 아래에 실기기 타깃을 추가한다(기존 `TeslableSample`은 그대로 두어 `verify.sh`의 시뮬레이터 빌드에 영향이 없게 한다).

```yaml
  TeslableSampleDevice:
    type: application
    platform: iOS
    deploymentTarget: "16.0"
    sources: [Sources]
    configFiles:
      Debug: Device.xcconfig
      Release: Device.xcconfig
    info:
      path: Sources/Info.plist
      properties:
        CFBundleDisplayName: Teslable Sample
        UILaunchScreen: {}
        NSBluetoothAlwaysUsageDescription: 차량과 BLE로 통신하기 위해 블루투스가 필요합니다.
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: io.github.smallmiro.TeslableSampleDevice
        SWIFT_VERSION: "6.0"
    dependencies:
      - framework: ../../sdk/build/bin/iosArm64/debugFramework/Teslable.framework
        embed: false
```

```swift
// samples/ios/Sources/DiagnosticsView.swift
import SwiftUI
import Teslable

/// M3 Task 1 스파이크 화면(ADR-0003). Task 14에서 지운다.
struct DiagnosticsView: View {
    @State private var vin = ""
    @State private var forceIndication = false
    @State private var lines: [String] = []
    @State private var running = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("VIN", text: $vin).textFieldStyle(.roundedBorder).textInputAutocapitalization(.characters)
            Toggle("CCCD indication 강제 (Android 전용, iOS는 실패 로그가 정상)", isOn: $forceIndication)
            Button(running ? "실행 중…" : "Probe") { Task { await run() } }.disabled(running)
            ScrollView {
                Text(lines.joined(separator: "\n")).font(.system(.footnote, design: .monospaced)).textSelection(.enabled)
            }
        }
        .padding(16)
    }

    @MainActor
    private func run() async {
        running = true
        lines = []
        do {
            try await TeslableDiagnostics.shared.probe(vin: vin.uppercased(), forceIndication: forceIndication) { line in
                Task { @MainActor in lines.append(line) }
            }
        } catch {
            lines.append("swift error: \(error)")
        }
        running = false
    }
}
```

`ContentView.swift`의 `VStack` 마지막에 `NavigationLink("BLE 진단 (스파이크)") { DiagnosticsView() }`를 추가하고 `body`를 `NavigationStack { … }`으로 감싼다.

`samples/ios/README.md`에 "실기기 실행" 절을 추가한다: (1) `./gradlew :sdk:linkDebugFrameworkIosArm64`, (2) `cp Local.xcconfig.example Local.xcconfig` 후 팀 ID 입력, (3) `xcodegen generate`, (4) Xcode에서 `TeslableSampleDevice` 스킴을 iPhone에 실행, (5) 차량 근처에서 VIN 입력 후 Probe, 로그 전체를 복사해 전달(로그에는 VIN 원문이 없다 — 로컬 이름만 남는다).

Run: `tools/ci/verify.sh`
Expected: 6/6 통과(시뮬레이터 빌드는 `TeslableSample` 스킴만 빌드하므로 실기기 타깃이 영향을 주지 않는다)

- [ ] **Step 6: 커밋 → PR → 병합**

```bash
git add adapter-ble sdk samples/ios .gitignore tools/ci/ported-files.txt
git commit -m "feat(ble): Kable GATT probe and iOS diagnostics screen for the ADR-0003 spike

Refs: FR-002, ADR-0003
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

PR 본문 "원본 대응": `ble.go:33-35, 121, 314-354`, `protocol.md:78`, 매뉴얼 `02-ble-transport.md` §4~§6.

- [ ] **Step 7: 🛑 사용자 실차 실행과 결과 기록**

사용자에게 Step 5의 절차대로 iPhone에서 Probe를 기본 설정(`forceIndication` 끔)으로 한 번 실행해 로그 전체를 달라고 요청한다. 로그에서 다음을 읽는다.

| 관측 | 판정 (설계 구체화 4) |
|---|---|
| `char …0213: … notify=false indicate=true` 그리고 `RX whitelistInfo: …` | (a) 기본 구독 — `SubscriptionMode.KABLE_DEFAULT` |
| `notify=true indicate=true` 그리고 `RX whitelistInfo: …` | (b) 기본 구독 — `KABLE_DEFAULT` |
| `notify=true indicate=true`, `TX: sent …` 뒤 `RX chunk`가 없음 | (c) iOS 차단 — **작업을 멈추고 사용자에게 보고**(Task 5를 시작하지 않는다) |
| `scan: timeout` / `connect` 오류 | 차량·폰 상태 확인 후 재실행(앱 종료, 차량 깨우기). 세 번 실패하면 로그와 함께 보고 |

`docs/adr/0003-ble-kable.md` 끝에 절을 추가한다(관측값 그대로, VIN·실제 공개키 없음):

```markdown
## 스파이크 결과 (YYYY-MM-DD, iPhone <모델>/iOS <버전>, 차량 <모델>/<펌웨어>)
- 0213 properties: notify=<…>, indicate=<…>
- Kable 기본 `observe`로 `whitelistInfo` 응답 수신: <예/아니오>
- iOS `maximumWriteValueLength(WithResponse)`: <값> → blockLength <값>
- 판정: <(a)|(b)|(c)> — `KableGattLink`의 구독 방식 `<KABLE_DEFAULT|…>`
- Android 확인: 미실시(실기기 없음, 2026-10-15 사용자 답) — CCCD 강제 옵션은 Android에서만 의미가 있다
```

```bash
git checkout -b m3/adr-0003-spike-result origin/main
git add docs/adr/0003-ble-kable.md
git commit -m "docs(adr): record the ADR-0003 Kable spike result

Refs: ADR-0003
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 2: `:domain` M3 포트 — `TransportFactory`·`ScanEvent`·`VehicleAdvertisement`·`ConnectOptions`, `VehicleDomain`, `VehicleKeyStore`·`VehicleKey`, `Transport` 버퍼링 계약 + `FakeTransportFactory`·`FakeVehicleKeyStore`

참고: `{{SDD_FILE}}` §2.1(포트), §3.1, §8.2; 설계 구체화 1, 2, 5, 8; M2 설계 구체화 2(`FakeVehicle.connect()`가 M3 `TransportFactory` 가짜의 바탕). Go 원본: `pkg/connector/ble/ble.go:186-257`(`ScanResult`, `ScanVehicleBeacon`), `pkg/connector/connector.go:30-56`, `pkg/protocol/key.go`(`ECDHPrivateKey`).

**Files:**
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/VehicleDomain.kt`
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/TransportFactory.kt`
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/VehicleKeyStore.kt`
- Modify: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/Transport.kt` (KDoc만)
- Create: `domain/src/commonTest/kotlin/io/github/smallmiro/teslable/model/VehicleDomainTest.kt`
- Create: `domain/src/commonTest/kotlin/io/github/smallmiro/teslable/port/VehicleKeyStoreTest.kt`
- Create: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeTransportFactory.kt`
- Create: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicleKeyStore.kt`
- Create: `testing/src/commonTest/kotlin/io/github/smallmiro/teslable/testing/FakeTransportFactoryTest.kt`
- Modify: `domain/api/*`, `testing/api/*`(있으면), `tools/ci/ported-files.txt`

**Interfaces:**
- Consumes: `Vin`, `LocalName`, `Transport`, `VehicleResult`, `VehicleError.TransportError`, `EcdhPrivateKey`, `PublicKeyBytes`, `FakeVehicle.connect()`/`isConnectable`, `TestCrypto.clientKey()`/`clientPublicKey`.
- Produces (Task 3~13):
  - `enum class VehicleDomain { VCSEC, INFOTAINMENT }` + `val wire: Domain`, `companion { val ALL: Set<VehicleDomain>; fun of(domain: Domain): VehicleDomain? }`
  - `data class VehicleAdvertisement(localName: LocalName, identifier: String, rssi: Int, isConnectable: Boolean?)`
  - `sealed interface ScanEvent { data class Found(advertisement); data class Failed(error: VehicleError.TransportError) }`
  - `data class ConnectOptions(timeout: Duration? = null)`
  - `interface TransportFactory { fun scan(localName: LocalName): Flow<ScanEvent>; suspend fun connect(vin: Vin, target: VehicleAdvertisement?, options: ConnectOptions): VehicleResult<Transport> }`
  - `enum class KeyProtection { SECURE_ENCLAVE, STRONGBOX, TEE, SOFTWARE }` (+ `val isHardware: Boolean`), `enum class KeyPolicy { PREFER_HARDWARE, REQUIRE_HARDWARE, SOFTWARE_ONLY }`
  - `interface VehicleKey : EcdhPrivateKey { val alias: String; val protection: KeyProtection }`
  - `interface VehicleKeyStore { suspend fun getOrCreate(alias = DEFAULT_ALIAS, policy = PREFER_HARDWARE): VehicleResult<VehicleKey>; suspend fun get(alias = DEFAULT_ALIAS): VehicleResult<VehicleKey?>; suspend fun delete(alias = DEFAULT_ALIAS): VehicleResult<Unit>; companion { const val DEFAULT_ALIAS = "teslable-default"; fun requireValidAlias(alias: String) } }`
  - `:testing` `FakeTransportFactory(vehicle: FakeVehicle, crypto = TestCrypto.primitives, rssi = -60)`: `connectAttempts`, `enqueueConnectFailure(error)`, `scanFailure`, `lastTransport: FakeTransport?`
  - `:testing` `FakeVehicleKeyStore(key: EcdhPrivateKey = TestCrypto.clientKey(), protection = SOFTWARE)`: `failNext(error)`, `deleted: List<String>`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// domain/src/commonTest/kotlin/io/github/smallmiro/teslable/model/VehicleDomainTest.kt
package io.github.smallmiro.teslable.model

import com.tesla.generated.universalmessage.Domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VehicleDomainTest {
    @Test
    fun mapsToAndFromWireDomains() {
        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, VehicleDomain.VCSEC.wire)
        assertEquals(Domain.DOMAIN_INFOTAINMENT, VehicleDomain.INFOTAINMENT.wire)
        assertEquals(VehicleDomain.VCSEC, VehicleDomain.of(Domain.DOMAIN_VEHICLE_SECURITY))
        assertEquals(VehicleDomain.INFOTAINMENT, VehicleDomain.of(Domain.DOMAIN_INFOTAINMENT))
        assertNull(VehicleDomain.of(Domain.DOMAIN_BROADCAST))
        assertEquals(setOf(VehicleDomain.VCSEC, VehicleDomain.INFOTAINMENT), VehicleDomain.ALL)
    }
}
```

```kotlin
// domain/src/commonTest/kotlin/io/github/smallmiro/teslable/port/VehicleKeyStoreTest.kt
package io.github.smallmiro.teslable.port

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VehicleKeyStoreTest {
    @Test
    fun acceptsAliasesThatAreSafeAsFileNamesAndKeychainAccounts() {
        VehicleKeyStore.requireValidAlias(VehicleKeyStore.DEFAULT_ALIAS)
        VehicleKeyStore.requireValidAlias("car-2.backup_1")
        VehicleKeyStore.requireValidAlias("a".repeat(64))
    }

    @Test
    fun rejectsAliasesThatCouldEscapeTheKeyDirectory() {
        // 설계 구체화 10: 별칭은 파일 이름(filesDir/teslable/keys/<alias>.enc)과 Keychain account로 쓰인다
        listOf("", "../x", "a/b", "a b", "a".repeat(65), ".", "..").forEach { alias ->
            assertFailsWith<IllegalArgumentException>(alias) { VehicleKeyStore.requireValidAlias(alias) }
        }
    }

    @Test
    fun onlySecureEnclaveStrongBoxAndTeeAreHardware() {
        assertTrue(KeyProtection.SECURE_ENCLAVE.isHardware)
        assertTrue(KeyProtection.STRONGBOX.isHardware)
        assertTrue(KeyProtection.TEE.isHardware)
        assertFalse(KeyProtection.SOFTWARE.isHardware)
        assertEquals("teslable-default", VehicleKeyStore.DEFAULT_ALIAS)
    }
}
```

```kotlin
// testing/src/commonTest/kotlin/io/github/smallmiro/teslable/testing/FakeTransportFactoryTest.kt
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class FakeTransportFactoryTest {
    private val vehicle = FakeVehicle()
    private val localName = LocalName.of(vehicle.vin, TestCrypto.primitives)

    @Test
    fun scanFindsTheVehicleByItsLocalNameOnly() =
        runTest {
            val factory = FakeTransportFactory(vehicle)
            val found = factory.scan(localName).toList().single()
            val advertisement = assertIs<ScanEvent.Found>(found).advertisement
            assertEquals(localName, advertisement.localName)
            assertEquals(true, advertisement.isConnectable)
            val other = LocalName.of(Vin("5YJ30123456789ABC"), TestCrypto.primitives)
            assertEquals(emptyList(), factory.scan(other).toList())
        }

    @Test
    fun scanReportsConfiguredFailureInsteadOfThrowing() =
        runTest {
            val factory = FakeTransportFactory(vehicle).apply { scanFailure = VehicleError.TransportError.PermissionDenied }
            assertEquals(listOf<ScanEvent>(ScanEvent.Failed(VehicleError.TransportError.PermissionDenied)), factory.scan(localName).toList())
        }

    @Test
    fun connectWrapsFakeVehicleAndReplaysQueuedFailures() =
        runTest {
            val factory = FakeTransportFactory(vehicle)
            factory.enqueueConnectFailure(VehicleError.TransportError.ConnectFailed("gatt 133"))
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.ConnectFailed("gatt 133")),
                factory.connect(vehicle.vin, target = null, options = ConnectOptions()),
            )
            val connected = assertIs<VehicleResult.Success<*>>(factory.connect(vehicle.vin, null, ConnectOptions()))
            assertSame(factory.lastTransport, connected.value)
            assertEquals(2, factory.connectAttempts)
        }

    @Test
    fun notConnectableVehicleRefusesLikeGo() =
        runTest {
            vehicle.setConnectable(false)
            val factory = FakeTransportFactory(vehicle)
            val advertisement = assertIs<ScanEvent.Found>(factory.scan(localName).toList().single()).advertisement
            assertEquals(false, advertisement.isConnectable)
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded),
                factory.connect(vehicle.vin, advertisement, ConnectOptions()),
            )
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :domain:jvmTest :testing:jvmTest --console=plain`
Expected: 컴파일 실패 (`VehicleDomain`, `VehicleKeyStore`, `FakeTransportFactory` 없음)

- [ ] **Step 3: 구현**

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/VehicleDomain.kt
package io.github.smallmiro.teslable.model

import com.tesla.generated.universalmessage.Domain

/** 공개 API의 차량 도메인(SDD §2.1). 내부는 Wire [Domain]을 쓴다(M2 설계 구체화 3). */
public enum class VehicleDomain(
    /** 대응하는 Wire 도메인. */
    public val wire: Domain,
) {
    /** 차량 보안(VCSEC): 잠금, 키, 차체 상태. */
    VCSEC(Domain.DOMAIN_VEHICLE_SECURITY),

    /** 인포테인먼트: 공조, 충전, 상태 조회. */
    INFOTAINMENT(Domain.DOMAIN_INFOTAINMENT),
    ;

    /** 조회. */
    public companion object {
        /** 두 도메인 전부. */
        public val ALL: Set<VehicleDomain> = entries.toSet()

        /** Wire 도메인에 대응하는 값. 공개 도메인이 아니면 null. */
        public fun of(domain: Domain): VehicleDomain? = entries.firstOrNull { it.wire == domain }
    }
}
```

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/TransportFactory.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// ScanResult, ScanVehicleBeacon, NewConnectionFromScanResult as a port (values instead of errors, ADR-0006)
package io.github.smallmiro.teslable.port

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** 스캔으로 찾은 차량 광고(Go `ScanResult`, FR-001/FR-030). */
public data class VehicleAdvertisement(
    /** 광고 로컬 이름(`S…C`). */
    public val localName: LocalName,
    /** 플랫폼 기기 식별자(Android MAC, iOS UUID 문자열). 표시·재연결용, 비밀 아님. */
    public val identifier: String,
    /** 수신 신호 세기(dBm). */
    public val rssi: Int,
    /** 연결 가능 여부. false면 슬롯 초과(`MaxConnectionsExceeded`), null이면 플랫폼이 알려주지 않음(시도한다, 설계 구체화 2). */
    public val isConnectable: Boolean?,
)

/** 스캔 흐름의 사건. 오류도 값으로 흐른다(설계 구체화 1). */
public sealed interface ScanEvent {
    /** 광고를 찾음. */
    public data class Found(
        /** 찾은 광고. */
        public val advertisement: VehicleAdvertisement,
    ) : ScanEvent

    /** 스캔을 시작하거나 계속할 수 없음(권한·블루투스 꺼짐 등). 이 사건 뒤 흐름은 끝난다. */
    public data class Failed(
        /** 원인. */
        public val error: VehicleError.TransportError,
    ) : ScanEvent
}

/** 연결 옵션. */
public data class ConnectOptions(
    /** 스캔·연결·구독 전체 시간. null이면 파사드 설정(`TeslaBleConfig.connectTimeout`). */
    public val timeout: Duration? = null,
)

/** 차량 스캔과 연결(Go `ble.ScanVehicleBeacon` + `ble.NewConnectionFromScanResult`). */
public interface TransportFactory {
    /** [localName]과 정확히 같은 이름의 광고를 흘려보낸다. 수집을 멈추면 스캔도 멈춘다. */
    public fun scan(localName: LocalName): Flow<ScanEvent>

    /**
     * [vin] 차량에 연결한다. [target]이 있으면 그 광고로 바로 연결하고, 없으면 스캔해서 첫 광고를 쓴다.
     * 재시도 가능한 실패는 [options]의 시간 안에서 다시 시도한다(FR-004). 실패는 값이다.
     */
    public suspend fun connect(
        vin: Vin,
        target: VehicleAdvertisement?,
        options: ConnectOptions,
    ): VehicleResult<Transport>
}
```

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/VehicleKeyStore.kt
package io.github.smallmiro.teslable.port

import io.github.smallmiro.teslable.model.VehicleResult

/** 개인키가 실제로 보관된 곳(FR-021). */
public enum class KeyProtection(
    /** 하드웨어 보안 영역인지. */
    public val isHardware: Boolean,
) {
    /** iOS Secure Enclave. */
    SECURE_ENCLAVE(true),

    /** Android StrongBox. */
    STRONGBOX(true),

    /** Android TEE(Trusted Execution Environment). */
    TEE(true),

    /** 소프트웨어(플랫폼 키스토어가 감싸 보관하거나 메모리). */
    SOFTWARE(false),
}

/** 키 생성 정책(D10). */
public enum class KeyPolicy {
    /** 하드웨어를 먼저 시도하고 안 되면 소프트웨어(기본). */
    PREFER_HARDWARE,

    /** 하드웨어만. 없으면 `KeyStoreError.HardwareUnavailable`. */
    REQUIRE_HARDWARE,

    /** 소프트웨어만(테스트·에뮬레이터). */
    SOFTWARE_ONLY,
}

/** 키스토어에 보관된 차량 키 핸들. 개인키를 내보내는 API는 없다(NFR-004). */
public interface VehicleKey : EcdhPrivateKey {
    /** 키 별칭. */
    public val alias: String

    /** 실제 보관 수준. */
    public val protection: KeyProtection
}

/** 차량 키 보관소(FR-020, FR-021, FR-028). 실패는 값이다(설계 구체화 8). */
public interface VehicleKeyStore {
    /** [alias]의 키를 돌려주고, 없으면 [policy]대로 만든다. */
    public suspend fun getOrCreate(
        alias: String = DEFAULT_ALIAS,
        policy: KeyPolicy = KeyPolicy.PREFER_HARDWARE,
    ): VehicleResult<VehicleKey>

    /** [alias]의 키. 없으면 `Success(null)`. */
    public suspend fun get(alias: String = DEFAULT_ALIAS): VehicleResult<VehicleKey?>

    /** [alias]의 키를 지운다. 없어도 성공. */
    public suspend fun delete(alias: String = DEFAULT_ALIAS): VehicleResult<Unit>

    /** 상수와 검증. */
    public companion object {
        /** 기본 별칭(SDD §7.2). */
        public const val DEFAULT_ALIAS: String = "teslable-default"

        private val ALIAS = Regex("[A-Za-z0-9._-]{1,64}")

        /**
         * 별칭은 파일 이름과 Keychain account로 쓰이므로 `[A-Za-z0-9._-]{1,64}`이고 `.`/`..`이 아니어야 한다.
         * @throws IllegalArgumentException 규칙 위반(프로그래밍 오류).
         */
        public fun requireValidAlias(alias: String) {
            require(ALIAS.matches(alias) && alias != "." && alias != "..") { "invalid key alias" }
        }
    }
}
```

`Transport.kt`의 인터페이스 KDoc 마지막 문단 뒤와 `incoming` KDoc을 아래로 바꾼다(동작 변경 없음 — 계약 명시).

```kotlin
    /**
     * 차량이 보낸 메시지(재조립된 단위). 연결이 끝나면 완료된다. Go `Receive()`.
     * **버퍼링 계약:** 구현은 연결 직후부터 메시지를 모아 두어야 한다 — 수집자(디스패처의 수신 코루틴)가 구독하기 전에 도착한
     * 메시지도 버리지 않고 넘긴다(M3 `BleTransport`는 `Channel(64)`). 버퍼가 가득 차면 버리고 로그를 남긴다(Go `flush`).
     */
    public val incoming: Flow<ByteArray>
```

```kotlin
// testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeTransportFactory.kt
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [FakeVehicle]을 감싼 [TransportFactory](M2 설계 구체화 2). 스캔은 차량의 로컬 이름과 같을 때만 광고 하나를 내고,
 * 연결은 대기열의 실패를 먼저 돌려준 뒤 [FakeVehicle.connect]를 부른다(connectable=false → `MaxConnectionsExceeded`). 단일 스레드 전용.
 */
public class FakeTransportFactory(
    private val vehicle: FakeVehicle,
    crypto: CryptoPrimitives = TestCrypto.primitives,
    private val rssi: Int = -60,
) : TransportFactory {
    private val vehicleName = LocalName.of(vehicle.vin, crypto)
    private val connectFailures = ArrayDeque<VehicleError>()
    private var attempts = 0

    /** null이 아니면 [scan]이 이 오류 하나를 내고 끝난다. */
    public var scanFailure: VehicleError.TransportError? = null

    /** [connect] 호출 수. */
    public val connectAttempts: Int get() = attempts

    /** 마지막으로 연결에 성공한 전송. */
    public var lastTransport: FakeTransport? = null
        private set

    /** 다음 [connect]가 [error]를 돌려주게 한다(순서대로 소비). */
    public fun enqueueConnectFailure(error: VehicleError) {
        connectFailures.addLast(error)
    }

    override fun scan(localName: LocalName): Flow<ScanEvent> =
        flow {
            val failure = scanFailure
            if (failure != null) {
                emit(ScanEvent.Failed(failure))
            } else if (localName == vehicleName) {
                emit(ScanEvent.Found(VehicleAdvertisement(vehicleName, "fake-${vehicleName.value}", rssi, vehicle.isConnectable)))
            }
        }

    override suspend fun connect(
        vin: Vin,
        target: VehicleAdvertisement?,
        options: ConnectOptions,
    ): VehicleResult<Transport> {
        attempts++
        connectFailures.removeFirstOrNull()?.let { return it.toResult() }
        if (vin != vehicle.vin) return VehicleResult.Failure(VehicleError.TransportError.ScanTimeout)
        val result = vehicle.connect()
        if (result is VehicleResult.Success) lastTransport = result.value
        return result
    }
}
```

```kotlin
// testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicleKeyStore.kt
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore

/** 메모리 [VehicleKeyStore] 가짜. 모든 별칭이 같은 [key]를 감싼다(파사드 테스트용). 단일 스레드 전용. */
public class FakeVehicleKeyStore(
    private val key: EcdhPrivateKey = TestCrypto.clientKey(),
    private val protection: KeyProtection = KeyProtection.SOFTWARE,
) : VehicleKeyStore {
    private val keys = HashMap<String, VehicleKey>()
    private val failures = ArrayDeque<VehicleError>()
    private val deletedAliases = mutableListOf<String>()

    /** [delete]된 별칭(순서대로). */
    public val deleted: List<String> get() = deletedAliases.toList()

    /** 다음 호출 하나가 [error]를 돌려주게 한다. */
    public fun failNext(error: VehicleError) {
        failures.addLast(error)
    }

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        failures.removeFirstOrNull()?.let { return it.toResult() }
        if (policy == KeyPolicy.REQUIRE_HARDWARE && !protection.isHardware) {
            return VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable)
        }
        return VehicleResult.Success(keys.getOrPut(alias) { Handle(alias, key, protection) })
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        failures.removeFirstOrNull()?.let { return it.toResult() }
        return VehicleResult.Success(keys[alias])
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        failures.removeFirstOrNull()?.let { return it.toResult() }
        keys.remove(alias)
        deletedAliases += alias
        return VehicleResult.Success(Unit)
    }

    private class Handle(
        override val alias: String,
        private val delegate: EcdhPrivateKey,
        override val protection: KeyProtection,
    ) : VehicleKey {
        override val publicKey: PublicKeyBytes get() = delegate.publicKey

        override suspend fun sharedX(peer: PublicKeyBytes): ByteArray = delegate.sharedX(peer)
    }
}
```

`toResult()`가 `VehicleError`의 확장(`mayHaveSucceeded` → `Uncertain`, 아니면 `Failure`)인지 `domain/…/model/VehicleResult.kt`에서 확인하고 그대로 쓴다. 반환 타입이 `VehicleResult<Nothing>`이라 `VehicleResult<VehicleKey>`에 대입된다.

`tools/ci/ported-files.txt`에 `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/TransportFactory.kt`를 추가한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :domain:jvmTest :testing:jvmTest :domain:iosSimulatorArm64Test --console=plain`
Expected: PASS
Run: `./gradlew :domain:apiDump`
Expected: `VehicleDomain`, `TransportFactory`, `ScanEvent`, `VehicleAdvertisement`, `ConnectOptions`, `VehicleKeyStore`, `VehicleKey`, `KeyProtection`, `KeyPolicy`가 API 덤프에 추가됨

- [ ] **Step 5: SDD 기록**

`{{SDD_FILE}}` §2.1 포트 코드 블록의 `TransportFactory`와 `VehicleKeyStore` 줄을 위 시그니처로 바꾸고(설계 구체화 1, 8), `Transport.incoming` 주석에 "연결 직후부터 버퍼링(`Channel(64)`)"을 덧붙인다. §1.2 다이어그램은 그대로 둔다.

- [ ] **Step 6: 커밋 → PR(`m3/domain-ports`) → verify.sh → 리뷰 → 병합**

```bash
git add domain testing docs/sdd/SDD.md tools/ci/ported-files.txt
git commit -m "feat(domain): M3 ports TransportFactory, VehicleKeyStore and VehicleDomain with fakes

Refs: FR-001, FR-020, FR-021, FR-030, ADR-0006
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 3: `BleTransport` — `GattLink` 내부 포트 위의 `Transport` 구현 (프레이밍·재조립·버퍼·TX/RX 로그·닫기)

참고: `{{MANUAL_DIR}}02-ble-transport.md` §6(프레이밍·재조립), §13; `10-porting-guide.md` §3; 설계 구체화 5. Go 원본: `pkg/connector/ble/ble.go:67-87`(`flush`), `:89-92`(`Close`), `:94-96`·`:59-61`(`AllowedLatency` 4s, `RetryInterval` 1s), `:98-106`(`rx`), `:108-127`(`Send`), `:330`(`inbox` 5), `:76`·`:113`(RX/TX 디버그 로그); `pkg/connector/connector.go:48-52`(`Close` 멱등 계약).

**Files:**
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/GattLink.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleTransport.kt`
- Create: `adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/FakeGattLink.kt`
- Create: `adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BleTransportTest.kt`
- Modify: `tools/ci/ported-files.txt`

**Interfaces:**
- Consumes: `Transport`/`TransportState`, `Framer`, `Reassembler`, `TeslaLogger`/`LogLevel`, `VehicleError.TransportError.*`, `VehicleError.InvalidArgument`, `toHex()`.
- Produces (Task 4, 5):

```kotlin
internal interface GattLink {
    val blockLength: Int                               // min(MTU, 1024) - 3, 실패 시 20
    val notifications: Flow<ByteArray>                 // 0213 청크. 링크가 끊기면 완료(정상) 또는 예외
    suspend fun write(chunk: ByteArray): VehicleError.TransportError?   // null = 성공. 취소는 전파
    suspend fun disconnect()                           // 멱등
}
internal class BleTransport(vin: Vin, link: GattLink, scope: CoroutineScope, timeSource: TimeSource = TimeSource.Monotonic,
    logger: TeslaLogger = TeslaLogger.NoOp) : Transport
internal companion: BleTransport.INBOX_CAPACITY = 64, RETRY_INTERVAL = 1.seconds, ALLOWED_LATENCY = 4.seconds
```

- [ ] **Step 1: 테스트용 가짜 링크와 실패 테스트 작성**

```kotlin
// adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/FakeGattLink.kt
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.yield

/** 스크립트 가능한 [GattLink]. `runTest` 단일 스레드 전용. */
internal class FakeGattLink(
    override val blockLength: Int = 20,
) : GattLink {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    val written = mutableListOf<ByteArray>()
    val writeErrors = ArrayDeque<VehicleError.TransportError?>()
    var disconnects = 0
        private set

    /** non-null이면 [write]가 이 게이트를 기다린다(쓰기 도중 취소 시험). */
    var writeGate: CompletableDeferred<Unit>? = null

    /** 이미 쓴 청크가 이 수 이상일 때만 [writeGate]를 기다린다(0 = 첫 쓰기부터). */
    var gateAfterWrites: Int = 0

    override val notifications: Flow<ByteArray> = chunks.receiveAsFlow()

    fun notify(chunk: ByteArray) {
        chunks.trySend(chunk)
    }

    /** 링크가 끊김(정상 완료). */
    fun drop() {
        chunks.close()
    }

    /** 링크가 오류로 끊김. */
    fun fail(cause: Throwable) {
        chunks.close(cause)
    }

    override suspend fun write(chunk: ByteArray): VehicleError.TransportError? {
        yield() // 실제 GATT 쓰기처럼 중단점을 둔다 — 동시 send가 직렬화되지 않으면 청크가 섞여 테스트가 실패한다
        if (written.size >= gateAfterWrites) writeGate?.await()
        val error = writeErrors.removeFirstOrNull()
        if (error == null) written += chunk.copyOf()
        return error
    }

    override suspend fun disconnect() {
        disconnects++
        chunks.close()
    }
}
```

```kotlin
// adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BleTransportTest.kt
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TransportState
import io.github.smallmiro.teslable.testing.RecordingLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportTest {
    private val vin = Vin("5YJ30123456789ABC")

    private fun TestScope.transport(
        link: FakeGattLink,
        logger: RecordingLogger = RecordingLogger(),
    ) = BleTransport(vin, link, backgroundScope, testTimeSource, logger)

    @Test
    fun framesAndSplitsByBlockLengthInOrder() =
        // ble.go:108-127: [len>>8, len&0xff] + payload, blockLength 단위 순차 쓰기(write with response)
        runTest {
            val link = FakeGattLink(blockLength = 4)
            val result = transport(link).send(byteArrayOf(1, 2, 3, 4, 5))
            assertEquals(VehicleResult.Success(Unit), result)
            assertEquals(listOf("00050102", "030405"), link.written.map { it.hex() })
        }

    @Test
    fun reassemblesChunksIntoMessages() =
        // ble.go:67-87 flush
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            link.notify(byteArrayOf(0, 3, 9))
            link.notify(byteArrayOf(8, 7, 0, 1))
            link.notify(byteArrayOf(6))
            runCurrent()
            val messages = t.incoming.take(2).toList()
            assertEquals(listOf("090807", "06"), messages.map { it.hex() })
        }

    @Test
    fun buffersNotificationsArrivingBeforeAnyCollector() =
        // Review Focus 2 / 설계 구체화 5: 디스패처가 구독하기 전에 온 응답도 버리지 않는다
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            repeat(10) { i -> link.notify(byteArrayOf(0, 1, i.toByte())) }
            runCurrent()
            assertEquals((0 until 10).map { listOf(it.toByte()) }, t.incoming.take(10).toList().map { it.toList() })
        }

    @Test
    fun dropsWithErrorLogWhenInboxIsFull() =
        // ble.go:80-84: inbox가 가득 차면 버린다(Go는 5, 우리는 64 — SDD §2.4)
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            repeat(BleTransport.INBOX_CAPACITY + 1) { link.notify(byteArrayOf(0, 1, 7)) }
            runCurrent()
            assertEquals(1, logger.count("Dropping received message because inbox is full"))
            assertEquals(LogLevel.ERROR, logger.entries.single { it.message.contains("inbox is full") }.level)
            assertEquals(BleTransport.INBOX_CAPACITY, t.incoming.take(BleTransport.INBOX_CAPACITY).toList().size)
        }

    @Test
    fun resetsReassemblyAfterOneSecondGap() =
        // ble.go:98-106 rxTimeout = 1s
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            link.notify(byteArrayOf(0, 2, 1))
            runCurrent()
            advanceTimeBy(1.seconds + 1.milliseconds)
            link.notify(byteArrayOf(0, 1, 5))
            runCurrent()
            assertEquals("05", t.incoming.first().hex())
        }

    @Test
    fun logsTxAndRxHexAtDebugOnly() =
        // ble.go:76 RX: %02x, :113 TX: %02x (FR-112)
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            t.send(byteArrayOf(0x0A))
            link.notify(byteArrayOf(0, 1, 0x0B))
            runCurrent()
            t.incoming.first()
            val tx = logger.entries.single { it.message.startsWith("TX: ") }
            val rx = logger.entries.single { it.message.startsWith("RX: ") }
            assertEquals("TX: 00010a", tx.message)
            assertEquals("RX: 0b", rx.message)
            assertEquals(LogLevel.DEBUG, tx.level)
            assertEquals(LogLevel.DEBUG, rx.level)
        }

    @Test
    fun exposesBleRetryIntervalAndAllowedLatency() =
        runTest {
            val t = transport(FakeGattLink())
            assertEquals(1.seconds, t.retryInterval) // ble.go:59-61
            assertEquals(4.seconds, t.allowedLatency) // ble.go:29, 94-96
            assertEquals(vin, t.vin)
            assertEquals(TransportState.Connected, t.state.value)
        }

    @Test
    fun oversizedMessageIsInvalidArgumentNotException() =
        runTest {
            val result = transport(FakeGattLink()).send(ByteArray(1025))
            val failure = assertIs<VehicleResult.Failure>(result)
            assertIs<VehicleError.InvalidArgument>(failure.error)
        }

    @Test
    fun writeFailureIsRetriableWriteFailed() =
        runTest {
            val link = FakeGattLink().apply { writeErrors.addLast(VehicleError.TransportError.WriteFailed("gatt 133")) }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.WriteFailed("gatt 133")), transport(link).send(byteArrayOf(1)))
        }

    @Test
    fun linkDropCompletesIncomingAndMarksDisconnected() =
        // Review Focus 1
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            val collected = mutableListOf<ByteArray>()
            val job = backgroundScope.launch { t.incoming.collect { collected += it } }
            link.notify(byteArrayOf(0, 1, 1))
            runCurrent()
            link.drop()
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(1, collected.size)
            assertEquals(TransportState.Disconnected(VehicleError.TransportError.Disconnected), t.state.value)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.Disconnected), t.send(byteArrayOf(1)))
        }

    @Test
    fun linkFailureAlsoDisconnectsWithoutThrowing() =
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            link.fail(IllegalStateException("gatt error"))
            runCurrent()
            assertEquals(TransportState.Disconnected(VehicleError.TransportError.Disconnected), t.state.value)
            assertEquals(emptyList(), t.incoming.toList())
            assertTrue(logger.contains("BLE link closed with error"))
        }

    @Test
    fun closeIsIdempotentAndDisconnectsOnce() =
        // connector.go:48-52 Close 멱등 계약 (Go ble.go:89-92 자체는 보장하지 않음 — 매뉴얼 02 §13.7과 원본 차이)
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            t.close()
            t.close()
            assertEquals(1, link.disconnects)
            assertEquals(TransportState.Disconnected(null), t.state.value)
            assertEquals(emptyList(), t.incoming.toList())
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.Disconnected), t.send(byteArrayOf(1)))
        }

    @Test
    fun concurrentSendsDoNotInterleaveChunks() =
        // ble.go:109 c.lock: 한 프레임의 청크가 다른 프레임과 섞이지 않는다
        runTest {
            val link = FakeGattLink(blockLength = 2)
            val t = transport(link)
            val a = launch { t.send(byteArrayOf(1, 1, 1)) }
            val b = launch { t.send(byteArrayOf(2, 2, 2)) }
            a.join()
            b.join()
            assertEquals(listOf("0003", "0101", "01", "0003", "0202", "02"), link.written.map { it.hex() })
        }

    @Test
    fun cancellationMidFrameClosesTheTransport() =
        // 설계 구체화 5: 반쯤 쓴 프레임 뒤 다음 프레임이 차량 재조립 버퍼에서 섞이지 않게
        runTest {
            val link = FakeGattLink(blockLength = 2)
            val t = transport(link)
            link.gateAfterWrites = 1
            link.writeGate = CompletableDeferred()
            val job = launch { t.send(byteArrayOf(1, 2, 3)) }
            runCurrent() // 첫 청크("0003")를 쓰고 두 번째 청크에서 게이트를 기다린다
            assertEquals(1, link.written.size)
            job.cancel()
            runCurrent()
            assertEquals(TransportState.Disconnected(null), t.state.value)
            assertEquals(1, link.disconnects)
        }

    @Test
    fun cancellationBeforeFirstChunkKeepsTheTransportOpen() =
        runTest {
            val link = FakeGattLink().apply { writeGate = CompletableDeferred() }
            val t = transport(link)
            val job = launch { t.send(byteArrayOf(1)) }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(TransportState.Connected, t.state.value)
            assertEquals(0, link.disconnects)
        }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :adapter-ble:jvmTest --console=plain`
Expected: 컴파일 실패 (`GattLink`, `BleTransport` 없음)

- [ ] **Step 3: 구현**

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/GattLink.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// Connection.txChar/rxChar/blockLength/client as an internal port (Kable binding in KableGattLink)
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import kotlinx.coroutines.flow.Flow

/** 연결된 GATT 링크 하나(서비스 0211, TX 0212, RX 0213). Kable 없이 [BleTransport]를 시험하기 위한 내부 포트. */
internal interface GattLink {
    /** 쓰기 블록 길이(`min(MTU, 1024) - 3`, MTU 교환 실패 시 20 — `ble.go:349-354`). */
    val blockLength: Int

    /** 0213 알림·지시 청크. 링크가 끊기면 정상 완료하거나 예외로 끝난다. 한 번만 수집한다. */
    val notifications: Flow<ByteArray>

    /** 청크 하나를 write-with-response로 쓴다(`ble.go:121`). null이면 성공. 취소는 전파한다. */
    suspend fun write(chunk: ByteArray): VehicleError.TransportError?

    /** 링크를 끊는다. 멱등. */
    suspend fun disconnect()
}
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleTransport.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// Connection.Send, rx, flush, Close, Receive, RetryInterval, AllowedLatency
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportState
import io.github.smallmiro.teslable.transport.Framer
import io.github.smallmiro.teslable.transport.Reassembler
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * BLE [Transport](Go `ble.Connection`). 생성 즉시 [GattLink.notifications]를 [scope]에서 수집해 재조립하고 [INBOX_CAPACITY]
 * 버퍼에 넣는다(설계 구체화 5 — 수집자가 오기 전의 메시지를 버리지 않는다). 링크가 끊기면 [incoming]이 완료되고 [state]가
 * `Disconnected(TransportError.Disconnected)`가 된다. [send]는 [sendMutex]로 프레임 단위 직렬화(Go `c.lock`). 프레임 중간 취소는
 * 전송을 닫는다(Go는 ctx를 무시). TX/RX 헥사는 DEBUG 로그(Go `ble.go:76, 113`).
 */
@OptIn(InternalTeslableApi::class)
internal class BleTransport(
    override val vin: Vin,
    private val link: GattLink,
    scope: CoroutineScope,
    timeSource: TimeSource = TimeSource.Monotonic,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
) : Transport {
    private val inbox = Channel<ByteArray>(INBOX_CAPACITY)
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Connected)
    private val reassembler = Reassembler(timeSource)
    private val sendMutex = Mutex()
    private val closeMutex = Mutex()
    private var closed = false

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
    override val state: StateFlow<TransportState> = stateFlow
    override val retryInterval: Duration = RETRY_INTERVAL
    override val allowedLatency: Duration = ALLOWED_LATENCY

    private val receiver: Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                link.notifications.collect { chunk -> reassembler.push(chunk).forEach(::deliver) }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // 플랫폼 링크 오류는 끊김으로 본다
                logger.log(LogLevel.WARN, TAG) { "BLE link closed with error: ${e::class.simpleName}" }
            } finally {
                linkEnded()
            }
        }

    private fun deliver(message: ByteArray) {
        logger.log(LogLevel.DEBUG, TAG) { "RX: ${message.toHex()}" }
        if (inbox.trySend(message).isFailure) {
            logger.log(LogLevel.ERROR, TAG) { "Dropping received message because inbox is full" }
        }
    }

    private fun linkEnded() {
        inbox.close()
        stateFlow.compareAndSet(TransportState.Connected, TransportState.Disconnected(VehicleError.TransportError.Disconnected))
    }

    override suspend fun send(message: ByteArray): VehicleResult<Unit> {
        if (message.size > Framer.MAX_MESSAGE_SIZE) {
            return VehicleResult.Failure(VehicleError.InvalidArgument("message exceeds ${Framer.MAX_MESSAGE_SIZE} bytes"))
        }
        return sendMutex.withLock {
            if (stateFlow.value != TransportState.Connected) return@withLock VehicleResult.Failure(VehicleError.TransportError.Disconnected)
            val chunks = Framer.frame(message, link.blockLength)
            logger.log(LogLevel.DEBUG, TAG) { "TX: ${chunks.joinToString("") { it.toHex() }}" }
            writeAll(chunks)
        }
    }

    private suspend fun writeAll(chunks: List<ByteArray>): VehicleResult<Unit> {
        var written = 0
        try {
            for (chunk in chunks) {
                val error = link.write(chunk)
                if (error != null) return VehicleResult.Failure(error)
                written++
            }
            return VehicleResult.Success(Unit)
        } catch (e: CancellationException) {
            if (written in 1 until chunks.size) {
                logger.log(LogLevel.WARN, TAG) { "Send cancelled mid-frame; closing the BLE transport" }
                withContext(NonCancellable) { close() }
            }
            throw e
        }
    }

    override suspend fun close() {
        closeMutex.withLock {
            if (closed) return
            closed = true
        }
        stateFlow.value = TransportState.Disconnected(reason = null)
        receiver.cancel()
        inbox.close()
        link.disconnect()
    }

    companion object {
        /** 수신 버퍼(SDD §2.4: Go의 5보다 크게). */
        const val INBOX_CAPACITY: Int = 64

        /** Go `RetryInterval()`. */
        val RETRY_INTERVAL: Duration = 1.seconds

        /** Go `maxLatency`. */
        val ALLOWED_LATENCY: Duration = 4.seconds

        private const val TAG = "ble"
    }
}
```

`close()`에서 `stateFlow.value`를 먼저 `Disconnected(null)`로 두므로, 그 뒤 `linkEnded()`의 `compareAndSet`은 아무것도 바꾸지 않는다(사용자가 닫은 것과 링크가 끊긴 것을 `reason`으로 구분한다 — `TransportState.Disconnected(reason = null)` = 의도적 해제).

`tools/ci/ported-files.txt`에 `GattLink.kt`, `BleTransport.kt` 경로를 추가한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :adapter-ble:jvmTest :adapter-ble:iosSimulatorArm64Test --console=plain`
Expected: PASS (15개)

- [ ] **Step 5: SDD 기록**

`{{SDD_FILE}}` §12 "원본과 다른 동작 (의도)"에 추가:
- `BleTransport.send`가 프레임 중간(첫 청크 이후)에 취소되면 전송을 닫는다. Go `Connection.Send`는 `ctx`를 무시하고 끝까지 쓴다(`ble.go:108-127`).
- `BleTransport.close()`는 멱등이고 `incoming`을 완료한다. Go `Close`(`ble.go:89-92`)는 멱등 장치가 없고 `inbox`를 닫지 않는다(인터페이스 계약 `connector.go:48-52`는 멱등을 요구한다). 매뉴얼 `02-ble-transport.md` §13.7의 "멱등"은 계약 기준이다.
- 수신 버퍼는 64(Go 5), SDD §2.4 권고 그대로.

- [ ] **Step 6: 커밋 → PR(`m3/ble-transport`) → verify.sh → 리뷰 → 병합**

```bash
git add adapter-ble tools/ci/ported-files.txt docs/sdd/SDD.md
git commit -m "feat(ble): BleTransport over an internal GattLink port (framing, buffering, TX/RX debug log, idempotent close)

Refs: FR-002, FR-003, FR-005, FR-112
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 4: `BleTransportFactory` — 스캔·연결 재시도 루프와 오류 분류 (`GattConnector` 내부 포트)

참고: `{{MANUAL_DIR}}02-ble-transport.md` §3~§4.1, `08-errors.md` §4, `10-porting-guide.md` §1; 설계 구체화 1~3, 7. Go 원본: `pkg/connector/ble/ble.go:186-257`(`ScanVehicleBeacon`, 이름 정확 일치 필터), `:259-278`(`NewConnectionFromScanResult` 재시도 루프), `:280-360`(`tryToConnect` 재시도 여부 표), `:298-304`(이름 불일치·connectable=false는 재시도 없음); `cmd/tesla-control/main.go:120-121`(connect-timeout 기본 20s — 우리 기본은 `TeslaBleConfig.connectTimeout` 30s, SDD §2.3).

**Files:**
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/GattConnector.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleAvailability.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleTransportFactory.kt`
- Create: `adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/FakeGattConnector.kt`
- Create: `adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BleTransportFactoryTest.kt`
- Modify: `adapter-ble/api/*`, `tools/ci/ported-files.txt`

**Interfaces:**
- Consumes: Task 2 `TransportFactory`/`ScanEvent`/`VehicleAdvertisement`/`ConnectOptions`, Task 3 `GattLink`/`BleTransport`, `LocalName.of`, `CryptoPrimitives`.
- Produces (Task 5, 13):

```kotlin
internal class ScanHit(val advertisement: VehicleAdvertisement, val platform: Any?)   // platform = Kable PlatformAdvertisement
internal sealed interface ConnectorScan { data class Hit(val hit: ScanHit); data class Failed(val error: VehicleError.TransportError) }
internal sealed interface OpenResult { data class Opened(val link: GattLink); data class Failed(val error: VehicleError.TransportError, val retry: Boolean) }
internal interface GattConnector { fun scan(localName: LocalName): Flow<ConnectorScan>; suspend fun open(hit: ScanHit): OpenResult }
public fun interface BleAvailability { fun check(): VehicleError.TransportError?; companion { val ALWAYS } }
internal class BleTransportFactory(connector: GattConnector, availability: BleAvailability, scope: CoroutineScope,
    crypto: CryptoPrimitives, defaultTimeout: Duration = 30.seconds, retryInterval: Duration = 1.seconds,
    timeSource: TimeSource = TimeSource.Monotonic, logger: TeslaLogger = TeslaLogger.NoOp) : TransportFactory
```

- [ ] **Step 1: 가짜 커넥터와 실패 테스트 작성**

```kotlin
// adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/FakeGattConnector.kt
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 스크립트 가능한 [GattConnector]. */
internal class FakeGattConnector(
    private val advertisement: VehicleAdvertisement?,
) : GattConnector {
    val openResults = ArrayDeque<OpenResult>()
    var scanFailure: ConnectorScan.Failed? = null
    var scans = 0
        private set
    var opens = 0
        private set
    val links = mutableListOf<FakeGattLink>()

    override fun scan(localName: LocalName): Flow<ConnectorScan> =
        flow {
            scans++
            scanFailure?.let {
                emit(it)
                return@flow
            }
            val ad = advertisement
            if (ad != null && ad.localName == localName) emit(ConnectorScan.Hit(ScanHit(ad, platform = null)))
            awaitCancellation() // 실제 스캔처럼 수집자가 멈출 때까지 계속된다
        }

    override suspend fun open(hit: ScanHit): OpenResult {
        opens++
        return openResults.removeFirstOrNull() ?: OpenResult.Opened(FakeGattLink().also { links += it })
    }
}
```

```kotlin
// adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BleTransportFactoryTest.kt
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportFactoryTest {
    private val vin = Vin("5YJ30123456789ABC")
    private val localName = LocalName.of(vin, TestCrypto.primitives)
    private val ad = VehicleAdvertisement(localName, "AA:BB", rssi = -50, isConnectable = true)

    private fun TestScope.factory(
        connector: FakeGattConnector,
        availability: BleAvailability = BleAvailability.ALWAYS,
        logger: RecordingLogger = RecordingLogger(),
    ) = BleTransportFactory(connector, availability, backgroundScope, TestCrypto.primitives, timeSource = testTimeSource, logger = logger)

    @Test
    fun connectsToFirstScannedAdvertisement() =
        runTest {
            val connector = FakeGattConnector(ad)
            val result = factory(connector).connect(vin, target = null, options = ConnectOptions())
            val transport = assertIs<VehicleResult.Success<*>>(result).value
            assertIs<BleTransport>(transport)
            assertEquals(vin, transport.vin)
            assertEquals(1, connector.opens)
        }

    @Test
    fun retriesRetriableOpenFailuresEverySecondThenSucceeds() =
        // FR-004: 1초 간격(Go는 sleep 없음 — 설계 구체화 3)
        runTest {
            val connector = FakeGattConnector(ad)
            repeat(2) { connector.openResults.addLast(OpenResult.Failed(VehicleError.TransportError.ConnectFailed("discover"), retry = true)) }
            val logger = RecordingLogger()
            val pending = async { factory(connector, logger = logger).connect(vin, null, ConnectOptions()) }
            runCurrent()
            assertEquals(1, connector.opens)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(2, connector.opens)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertIs<VehicleResult.Success<*>>(pending.await())
            assertEquals(3, connector.opens)
            assertEquals(2, logger.count("Failed to connect to vehicle"))
        }

    @Test
    fun failsFastWithoutRetryOnPermissionOrBluetoothOff() =
        // Review Focus 3, ble.go:285-287 IsAdapterError → retry=false
        runTest {
            val noPermission = FakeGattConnector(ad)
            val denied = factory(noPermission, availability = { VehicleError.TransportError.PermissionDenied })
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.PermissionDenied), denied.connect(vin, null, ConnectOptions()))
            assertEquals(0, noPermission.scans)

            val off = FakeGattConnector(ad).apply { scanFailure = ConnectorScan.Failed(VehicleError.TransportError.BluetoothOff) }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.BluetoothOff), factory(off).connect(vin, null, ConnectOptions()))
            assertEquals(0, off.opens)

            val fatalOpen = FakeGattConnector(ad)
            fatalOpen.openResults.addLast(OpenResult.Failed(VehicleError.TransportError.PermissionDenied, retry = false))
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.PermissionDenied), factory(fatalOpen).connect(vin, null, ConnectOptions()))
            assertEquals(1, fatalOpen.opens)
        }

    @Test
    fun notConnectableAdvertisementFailsWithMaxConnectionsExceeded() =
        // ble.go:302-304
        runTest {
            val connector = FakeGattConnector(ad.copy(isConnectable = false))
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded),
                factory(connector).connect(vin, null, ConnectOptions()),
            )
            assertEquals(0, connector.opens)
        }

    @Test
    fun unknownConnectabilityIsAttempted() =
        // 설계 구체화 2
        runTest {
            val connector = FakeGattConnector(ad.copy(isConnectable = null))
            assertIs<VehicleResult.Success<*>>(factory(connector).connect(vin, null, ConnectOptions()))
            assertEquals(1, connector.opens)
        }

    @Test
    fun timesOutWithLastErrorWhenAttemptsKeepFailing() =
        // ble.go:270-275: ctx 만료 시 마지막 오류
        runTest {
            val connector = FakeGattConnector(ad)
            repeat(10) { connector.openResults.addLast(OpenResult.Failed(VehicleError.TransportError.ConnectFailed("gatt 133"), retry = true)) }
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 2500.milliseconds)) }
            advanceTimeBy(3.seconds)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.ConnectFailed("gatt 133")), pending.await())
            assertEquals(3, connector.opens) // t=0, 1s, 2s
        }

    @Test
    fun timesOutWithScanTimeoutWhenNothingIsFound() =
        runTest {
            val connector = FakeGattConnector(advertisement = null)
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 5.seconds)) }
            advanceTimeBy(6.seconds)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.ScanTimeout), pending.await())
        }

    @Test
    fun targetForAnotherVinIsInvalidArgument() =
        // ble.go:298-300: 이름 불일치는 재시도 없음
        runTest {
            val other = ad.copy(localName = LocalName.of(Vin("5YJS0000000000000"), TestCrypto.primitives))
            val result = factory(FakeGattConnector(ad)).connect(vin, other, ConnectOptions())
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(result).error)
        }

    @Test
    fun scannedTargetIsReusedWithoutRescanning() =
        // ble.go:259-278: target이 있으면 다시 스캔하지 않는다
        runTest {
            val connector = FakeGattConnector(ad)
            val f = factory(connector)
            val found = assertIs<ScanEvent.Found>(f.scan(localName).take(1).toList().single()).advertisement
            assertIs<VehicleResult.Success<*>>(f.connect(vin, found, ConnectOptions()))
            assertEquals(1, connector.scans)
        }

    @Test
    fun scanReportsAvailabilityFailureAsAnEvent() =
        runTest {
            val f = factory(FakeGattConnector(ad), availability = { VehicleError.TransportError.PermissionDenied })
            assertEquals(listOf<ScanEvent>(ScanEvent.Failed(VehicleError.TransportError.PermissionDenied)), f.scan(localName).toList())
        }

    @Test
    fun scanStopsAfterAFailureEvent() =
        runTest {
            val connector = FakeGattConnector(ad).apply { scanFailure = ConnectorScan.Failed(VehicleError.TransportError.BluetoothOff) }
            assertEquals(listOf<ScanEvent>(ScanEvent.Failed(VehicleError.TransportError.BluetoothOff)), factory(connector).scan(localName).toList())
        }

    @Test
    fun callerCancellationPropagatesInsteadOfBecomingAValue() =
        // ADR-0010
        runTest {
            val connector = FakeGattConnector(advertisement = null)
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 60.seconds)) }
            runCurrent()
            pending.cancel()
            runCurrent()
            assertTrue(pending.isCancelled)
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :adapter-ble:jvmTest --console=plain`
Expected: 컴파일 실패 (`GattConnector`, `BleAvailability`, `BleTransportFactory` 없음)

- [ ] **Step 3: 구현**

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/GattConnector.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0) — ScanVehicleBeacon, tryToConnect (as an internal port)
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.flow.Flow

/** 스캔으로 찾은 광고와 플랫폼 객체(Kable `PlatformAdvertisement`; 가짜에서는 null). */
internal class ScanHit(
    val advertisement: VehicleAdvertisement,
    val platform: Any?,
)

/** 커넥터 스캔 사건. */
internal sealed interface ConnectorScan {
    data class Hit(val hit: ScanHit) : ConnectorScan

    data class Failed(val error: VehicleError.TransportError) : ConnectorScan
}

/** 링크 열기 결과. [Failed.retry]는 Go `tryToConnect`의 `retry` 반환값. */
internal sealed interface OpenResult {
    data class Opened(val link: GattLink) : OpenResult

    data class Failed(val error: VehicleError.TransportError, val retry: Boolean) : OpenResult
}

/** 플랫폼 BLE 스택(Kable)에 대한 내부 포트. 실패한 열기 시도에서 연 연결은 구현이 닫는다(설계 구체화 3). */
internal interface GattConnector {
    /** [localName]과 정확히 같은 광고를 흘려보낸다. 수집을 멈추면 스캔이 멈춘다. */
    fun scan(localName: LocalName): Flow<ConnectorScan>

    /** 연결 → 서비스·특성 탐색 → 0213 구독 → MTU. */
    suspend fun open(hit: ScanHit): OpenResult
}
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleAvailability.kt
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError

/** 스캔·연결 직전 검사(FR-006, 설계 구체화 7). 문제가 없으면 null. */
public fun interface BleAvailability {
    /** 권한이 없으면 `PermissionDenied`, 그 밖에 알 수 있는 문제면 해당 오류. */
    public fun check(): VehicleError.TransportError?

    /** 기본값. */
    public companion object {
        /** 항상 통과(JVM·테스트). */
        public val ALWAYS: BleAvailability = BleAvailability { null }
    }
}
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleTransportFactory.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// NewConnection, NewConnectionFromScanResult, ScanVehicleBeacon (retry loop and fail-fast rules)
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * BLE [TransportFactory]. Go `NewConnectionFromScanResult`의 재시도 루프를 옮기되 시도 사이에 [retryInterval]을 둔다(FR-004,
 * 설계 구체화 3). 재시도하지 않는 실패: [BleAvailability] 검사 실패, 스캔 실패(권한·블루투스 꺼짐), 다른 VIN의 광고,
 * connectable=false(`MaxConnectionsExceeded`), `OpenResult.Failed(retry = false)`. 시간이 다 되면 마지막 오류(없으면 `ScanTimeout`).
 */
@Suppress("LongParameterList") // Go NewConnection(ctx, vin) + 주입 포트(connector/availability/scope/crypto/시간/로거)
internal class BleTransportFactory(
    private val connector: GattConnector,
    private val availability: BleAvailability,
    private val scope: CoroutineScope,
    private val crypto: CryptoPrimitives,
    private val defaultTimeout: Duration = 30.seconds,
    private val retryInterval: Duration = 1.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
) : TransportFactory {
    private val recentHits = LinkedHashMap<String, ScanHit>()
    private val hitsMutex = Mutex()

    override fun scan(localName: LocalName): Flow<ScanEvent> =
        flow {
            val unavailable = availability.check()
            if (unavailable != null) {
                emit(ScanEvent.Failed(unavailable))
                return@flow
            }
            connector.scan(localName).transformWhile { item ->
                when (item) {
                    is ConnectorScan.Hit -> {
                        remember(item.hit)
                        emit(ScanEvent.Found(item.hit.advertisement))
                        true
                    }
                    is ConnectorScan.Failed -> {
                        emit(ScanEvent.Failed(item.error))
                        false
                    }
                }
            }.collect { emit(it) }
        }

    override suspend fun connect(
        vin: Vin,
        target: VehicleAdvertisement?,
        options: ConnectOptions,
    ): VehicleResult<Transport> {
        availability.check()?.let { return VehicleResult.Failure(it) }
        val localName = LocalName.of(vin, crypto)
        if (target != null && target.localName != localName) {
            return VehicleResult.Failure(VehicleError.InvalidArgument("advertisement ${target.localName} is not for this VIN"))
        }
        val attempts = Attempts()
        return withTimeoutOrNull(options.timeout ?: defaultTimeout) { connectLoop(vin, localName, target, attempts) }
            ?: VehicleResult.Failure(attempts.lastError ?: VehicleError.TransportError.ScanTimeout)
    }

    private class Attempts {
        var count = 0
        var lastError: VehicleError.TransportError? = null
    }

    private suspend fun connectLoop(
        vin: Vin,
        localName: LocalName,
        target: VehicleAdvertisement?,
        attempts: Attempts,
    ): VehicleResult<Transport> {
        while (true) {
            val hit =
                when (val found = findHit(localName, target)) {
                    is ConnectorScan.Failed -> return VehicleResult.Failure(found.error)
                    is ConnectorScan.Hit -> found.hit
                }
            if (hit.advertisement.isConnectable == false) {
                return VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded)
            }
            attempts.count++
            when (val opened = connector.open(hit)) {
                is OpenResult.Opened -> return VehicleResult.Success(BleTransport(vin, opened.link, scope, timeSource, logger))
                is OpenResult.Failed -> {
                    if (!opened.retry) return VehicleResult.Failure(opened.error)
                    attempts.lastError = opened.error
                    logger.log(LogLevel.WARN, TAG) { "Failed to connect to vehicle (attempt ${attempts.count}): ${opened.error.message}" }
                    delay(retryInterval)
                }
            }
        }
    }

    private suspend fun findHit(
        localName: LocalName,
        target: VehicleAdvertisement?,
    ): ConnectorScan {
        if (target != null) hitsMutex.withLock { recentHits[target.identifier] }?.let { return ConnectorScan.Hit(it) }
        return connector.scan(localName).first().also { if (it is ConnectorScan.Hit) remember(it.hit) }
    }

    private suspend fun remember(hit: ScanHit) {
        hitsMutex.withLock {
            recentHits.remove(hit.advertisement.identifier)
            recentHits[hit.advertisement.identifier] = hit
            while (recentHits.size > MAX_REMEMBERED_HITS) recentHits.remove(recentHits.keys.first())
        }
    }

    private companion object {
        const val MAX_REMEMBERED_HITS = 16
        const val TAG = "ble"
    }
}
```

`scan()` 안의 `transformWhile`은 `ConnectorScan.Failed`를 낸 뒤 `false`를 돌려 흐름을 끝낸다. `connector.scan(...)`은 `flow { … awaitCancellation() }`처럼 끝나지 않을 수 있으므로 `connectLoop`의 `first()`가 첫 항목을 받는 즉시 수집을 멈춘다(실제 스캔도 멈춘다 — Go `ble.go:220-231`의 `cancel()`).

`tools/ci/ported-files.txt`에 `GattConnector.kt`, `BleTransportFactory.kt`를 추가한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :adapter-ble:jvmTest :adapter-ble:iosSimulatorArm64Test --console=plain`
Expected: PASS
Run: `./gradlew :adapter-ble:apiDump`
Expected: `BleAvailability`만 새 공개 API

- [ ] **Step 5: SDD 기록**

`{{SDD_FILE}}` §12 "원본과 다른 동작 (의도)"에 추가: "연결 재시도는 시도 사이에 1초를 둔다(FR-004). Go `NewConnectionFromScanResult`는 sleep 없이 반복한다(`ble.go:259-278`). 실패한 시도의 연결은 닫는다(Go는 닫지 않는다, `ble.go:280-360`). 다른 VIN의 광고를 `target`으로 주면 `InvalidArgument`(Go는 일반 오류, 재시도 없음 — 같은 결과)."

- [ ] **Step 6: 커밋 → PR(`m3/ble-transport-factory`) → verify.sh → 리뷰 → 병합**

```bash
git add adapter-ble tools/ci/ported-files.txt docs/sdd/SDD.md
git commit -m "feat(ble): BleTransportFactory retry loop and fail-fast classification over a GattConnector port

Refs: FR-001, FR-004, FR-006, FR-030
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 5: Kable 바인딩 — `KableGattConnector`/`KableGattLink`, 블록 길이, 권한 검사, 예외 변환, 공개 팩토리

**선행:** Task 1의 스파이크 판정이 (a) 또는 (b)여야 한다. (c)면 이 Task를 시작하지 않는다(설계 구체화 4).

참고: `{{MANUAL_DIR}}02-ble-transport.md` §4~§5, `10-porting-guide.md` §2, §12; SDD §2.4, §8.4; ADR-0003 "스파이크 결과"; 설계 구체화 4, 6, 7. Go 원본: `pkg/connector/ble/ble.go:280-360`(`tryToConnect`: dial → 서비스 → 특성 → 디스크립터 → 구독 → MTU), `:345`(indication 구독), `:349-354`(MTU), `device_darwin.go:9-12`(`IsAdapterError`는 macOS에서 항상 false).

**Files:**
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BlockLength.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/KableErrors.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/SubscriptionMode.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/KableGattConnector.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/KableGattLink.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.kt` (expect)
- Create: `adapter-ble/src/androidMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.android.kt`, `BleAvailability.android.kt`
- Create: `adapter-ble/src/iosMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.ios.kt`, `BleAvailability.ios.kt`
- Create: `adapter-ble/src/jvmMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.jvm.kt`
- Create: `adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleTransports.kt` (공개 팩토리)
- Create: `adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BlockLengthTest.kt`, `KableErrorsTest.kt`
- Modify: `adapter-ble/api/*`, `tools/ci/ported-files.txt`, `{{SDD_FILE}}` §2.4

**Interfaces:**
- Consumes: Task 3 `GattLink`, Task 4 `GattConnector`/`ScanHit`/`ConnectorScan`/`OpenResult`/`BleAvailability`/`BleTransportFactory`, Task 1 `BleUuids`, Kable.
- Produces (Task 13):
  - `public fun kableTransportFactory(scope: CoroutineScope, crypto: CryptoPrimitives, availability: BleAvailability, logger: TeslaLogger = TeslaLogger.NoOp, defaultTimeout: Duration = 30.seconds): TransportFactory`
  - `public fun androidBleAvailability(context: android.content.Context): BleAvailability` (androidMain)
  - `public fun appleBleAvailability(): BleAvailability` (iosMain)
  - `internal object BlockLength { DEFAULT = 20; fun fromAttMtu(mtu: Int): Int; fun fromMaxWriteLength(length: Int): Int }`
  - `internal object KableErrors { fun map(e: Throwable): VehicleError.TransportError; fun isRetriable(error: VehicleError.TransportError): Boolean }`
  - `internal enum class SubscriptionMode { KABLE_DEFAULT, ANDROID_FORCE_INDICATION }`, `internal val DEFAULT_SUBSCRIPTION`(Task 1 판정)

- [ ] **Step 1: 실패 테스트 작성 (순수 부분)**

```kotlin
// adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/BlockLengthTest.kt
package io.github.smallmiro.teslable.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockLengthTest {
    @Test
    fun attMtuFollowsGoFormula() {
        // ble.go:354 blockLength = min(txMtu, maxBLEMessageSize) - 3
        assertEquals(514, BlockLength.fromAttMtu(517))
        assertEquals(182, BlockLength.fromAttMtu(185))
        assertEquals(1021, BlockLength.fromAttMtu(2000))
        assertEquals(20, BlockLength.fromAttMtu(23))
    }

    @Test
    fun belowSpecMinimumFallsBackToDefault() {
        // ble.go:350-352: 실패 시 ble.DefaultMTU(23) - 3
        assertEquals(20, BlockLength.DEFAULT)
        assertEquals(20, BlockLength.fromAttMtu(10))
    }

    @Test
    fun appleMaxWriteLengthIsAlreadyMtuMinusThree() {
        // iOS maximumWriteValueLength(.withResponse) = ATT MTU - 3 (설계 구체화 6)
        assertEquals(182, BlockLength.fromMaxWriteLength(182))
        assertEquals(512, BlockLength.fromMaxWriteLength(512))
        assertEquals(1021, BlockLength.fromMaxWriteLength(4096))
        assertEquals(20, BlockLength.fromMaxWriteLength(0))
    }
}
```

```kotlin
// adapter-ble/src/commonTest/kotlin/io/github/smallmiro/teslable/ble/KableErrorsTest.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.NotConnectedException
import com.juul.kable.UnmetRequirementException
import com.juul.kable.UnmetRequirementReason
import io.github.smallmiro.teslable.model.VehicleError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KableErrorsTest {
    @Test
    fun mapsKableExceptionsToTransportErrors() {
        // Review Focus 3, 설계 구체화 7
        assertEquals(
            VehicleError.TransportError.BluetoothOff,
            KableErrors.map(UnmetRequirementException(UnmetRequirementReason.BluetoothDisabled, "Bluetooth was disabled")),
        )
        // Kable Android ScanPermissions: error("Missing required android.permission.BLUETOOTH_SCAN for scanning")
        assertEquals(
            VehicleError.TransportError.PermissionDenied,
            KableErrors.map(IllegalStateException("Missing required android.permission.BLUETOOTH_SCAN for scanning")),
        )
        // Kable BluetoothDevice.connectGatt: SecurityException을 IllegalStateException(e.message, e)로 다시 던진다
        assertEquals(
            VehicleError.TransportError.PermissionDenied,
            KableErrors.map(IllegalStateException("x", IllegalStateException("Need android.permission.BLUETOOTH_CONNECT permission"))),
        )
        assertIs<VehicleError.TransportError.ConnectFailed>(KableErrors.map(NotConnectedException("link lost")))
        assertEquals(VehicleError.TransportError.ConnectFailed("IllegalArgumentException: boom"), KableErrors.map(IllegalArgumentException("boom")))
    }

    @Test
    fun permissionAndBluetoothOffAreNotRetriedButConnectFailuresAre() {
        // ble.go:285-287 IsAdapterError → retry=false; :308-347 dial/discover/subscribe → retry=true
        assertFalse(KableErrors.isRetriable(VehicleError.TransportError.PermissionDenied))
        assertFalse(KableErrors.isRetriable(VehicleError.TransportError.BluetoothOff))
        assertTrue(KableErrors.isRetriable(VehicleError.TransportError.ConnectFailed("gatt 133")))
    }
}
```

`UnmetRequirementException`·`NotConnectedException`의 생성자 인자는 Kable 0.45.0 소스(`com/juul/kable/Exceptions.kt`, 공통)를 확인해 맞춘다(조사 결과: `NotConnectedException(message, cause)`, `UnmetRequirementException(reason, message, cause)`).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :adapter-ble:jvmTest --console=plain`
Expected: 컴파일 실패 (`BlockLength`, `KableErrors` 없음)

- [ ] **Step 3: 순수 부분 구현**

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BlockLength.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0) — tryToConnect MTU → blockLength
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.transport.Framer

/** MTU에서 쓰기 블록 길이를 구한다(`ble.go:349-354`). */
internal object BlockLength {
    private const val ATT_HEADER = 3
    private const val MIN_ATT_MTU = 23

    /** Go `ble.DefaultMTU - 3`. MTU 교환 실패 시 쓴다. */
    const val DEFAULT: Int = MIN_ATT_MTU - ATT_HEADER

    /** ATT MTU(Android `requestMtu` 반환값) → `min(mtu, 1024) - 3`. */
    fun fromAttMtu(mtu: Int): Int = if (mtu < MIN_ATT_MTU) DEFAULT else minOf(mtu, Framer.MAX_MESSAGE_SIZE) - ATT_HEADER

    /** iOS `maximumWriteValueLength(.withResponse)`(이미 `MTU - 3`) → `min(length, 1021)`. */
    fun fromMaxWriteLength(length: Int): Int = if (length < DEFAULT) DEFAULT else minOf(length, Framer.MAX_MESSAGE_SIZE - ATT_HEADER)
}
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/KableErrors.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.UnmetRequirementException
import com.juul.kable.UnmetRequirementReason
import io.github.smallmiro.teslable.model.VehicleError

/** Kable·플랫폼 예외 → [VehicleError.TransportError](설계 구체화 7). 문구 판정은 Kable 0.45.0 소스 기준. */
internal object KableErrors {
    private val PERMISSION = Regex("permission", RegexOption.IGNORE_CASE)

    fun map(e: Throwable): VehicleError.TransportError =
        when {
            e is UnmetRequirementException && e.reason == UnmetRequirementReason.BluetoothDisabled ->
                VehicleError.TransportError.BluetoothOff
            causes(e).any { it::class.simpleName == "SecurityException" || PERMISSION.containsMatchIn(it.message.orEmpty()) } ->
                VehicleError.TransportError.PermissionDenied
            else -> VehicleError.TransportError.ConnectFailed("${e::class.simpleName}: ${e.message}")
        }

    fun isRetriable(error: VehicleError.TransportError): Boolean =
        error !is VehicleError.TransportError.PermissionDenied && error !is VehicleError.TransportError.BluetoothOff

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)

    private const val MAX_CAUSE_DEPTH = 8
}
```

- [ ] **Step 4: 순수 부분 통과 확인**

Run: `./gradlew :adapter-ble:jvmTest :adapter-ble:iosSimulatorArm64Test --console=plain`
Expected: PASS

- [ ] **Step 5: 커밋(순수 부분)**

```bash
git add adapter-ble
git commit -m "feat(ble): MTU block length and Kable exception mapping

Refs: FR-002, FR-006
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 6: 바인딩 구현 (실차 확인은 Task 14)**

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/SubscriptionMode.kt
package io.github.smallmiro.teslable.ble

/** 0213 구독 방식(설계 구체화 4, ADR-0003 스파이크 결과). */
internal enum class SubscriptionMode {
    /** Kable 기본(`observe`): 특성이 notify를 지원하면 notify, 아니면 indication. */
    KABLE_DEFAULT,

    /** Android에서 구독 직후 CCCD에 `0x0200`(indication)을 쓴다. iOS에서는 쓰지 않는다(CoreBluetooth가 금지). */
    ANDROID_FORCE_INDICATION,
}

/** Task 1 판정 (a)/(b) → KABLE_DEFAULT. ADR-0003 "스파이크 결과" 절과 일치해야 한다. */
internal val DEFAULT_SUBSCRIPTION: SubscriptionMode = SubscriptionMode.KABLE_DEFAULT
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.Peripheral
import io.github.smallmiro.teslable.port.TeslaLogger

/** 연결된 [peripheral]의 블록 길이. 실패하면 [BlockLength.DEFAULT](Go `ble.go:350-352`, 재시도 없음). */
internal expect suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int
```

```kotlin
// adapter-ble/src/androidMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.android.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlin.coroutines.cancellation.CancellationException

private const val REQUESTED_MTU = 517 // Android 상한(SDD §2.4). Go는 ble.MaxMTU를 요청한다(ble.go:349)

internal actual suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int =
    try {
        BlockLength.fromAttMtu((peripheral as AndroidPeripheral).requestMtu(REQUESTED_MTU))
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) { // Go ble.go:350-352: 경고 후 기본값
        logger.log(LogLevel.WARN, "ble") { "Failed to set BLE MTU, using ${BlockLength.DEFAULT}-byte blocks: ${e::class.simpleName}" }
        BlockLength.DEFAULT
    }
```

```kotlin
// adapter-ble/src/iosMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.ios.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import io.github.smallmiro.teslable.port.TeslaLogger

/** iOS는 MTU 협상 API가 없다(ADR-0003). 시스템이 협상한 값에서 쓰기 최대 길이를 읽는다. */
internal actual suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int = BlockLength.fromMaxWriteLength(peripheral.maximumWriteValueLengthForType(WriteType.WithResponse))
```

```kotlin
// adapter-ble/src/jvmMain/kotlin/io/github/smallmiro/teslable/ble/MtuNegotiation.jvm.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.Peripheral
import io.github.smallmiro.teslable.port.TeslaLogger

/** Kable JVM(btleplug)은 MTU가 23 고정이다. */
internal actual suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int = BlockLength.DEFAULT
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/KableGattLink.kt
package io.github.smallmiro.teslable.ble

import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.github.smallmiro.teslable.model.VehicleError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kable [Peripheral] 위의 [GattLink]. 0213 관찰은 [KableGattConnector.open]이 연결 **전에** 시작해 [chunks]에 모은다(Kable은
 * 연결 중 구독 실패를 `connect()`에서 던진다 — Go `Subscribe` 실패 = 재시도). Kable `observe`는 연결이 끊겨도 끝나지 않으므로
 * `state`가 `Disconnected`가 되면 [chunks]를 닫아 [notifications]를 끝낸다.
 */
internal class KableGattLink(
    private val peripheral: Peripheral,
    override val blockLength: Int,
    private val chunks: Channel<ByteArray>,
    private val observeJob: Job,
    scope: CoroutineScope,
) : GattLink {
    private val tx = characteristicOf(BleUuids.SERVICE, BleUuids.TO_VEHICLE)
    private val closeMutex = Mutex()
    private var closed = false

    private val watcher: Job =
        scope.launch {
            peripheral.state.first { it is State.Disconnected }
            chunks.close()
        }

    override val notifications: Flow<ByteArray> = chunks.receiveAsFlow()

    override suspend fun write(chunk: ByteArray): VehicleError.TransportError? =
        try {
            peripheral.write(tx, chunk, WriteType.WithResponse) // ble.go:121 noRsp=false (Kable 기본값은 WithoutResponse)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            when (val mapped = KableErrors.map(e)) {
                is VehicleError.TransportError.ConnectFailed -> VehicleError.TransportError.WriteFailed(mapped.cause)
                else -> mapped
            }
        }

    override suspend fun disconnect() {
        closeMutex.withLock {
            if (closed) return
            closed = true
        }
        withContext(NonCancellable) {
            watcher.cancel()
            observeJob.cancel()
            chunks.close()
            peripheral.disconnect()
            peripheral.close()
        }
    }
}
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/KableGattConnector.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0) — ScanVehicleBeacon, tryToConnect (Kable binding)
package io.github.smallmiro.teslable.ble

import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.descriptorOf
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kable [GattConnector]. 스캔 필터는 로컬 이름 정확 일치(Go `ble.go:222`), 쓰기는 항상 with response. */
internal class KableGattConnector(
    private val scope: CoroutineScope,
    private val subscription: SubscriptionMode,
    private val logger: TeslaLogger,
) : GattConnector {
    private val rx = characteristicOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE)

    override fun scan(localName: LocalName): Flow<ConnectorScan> =
        Scanner { filters { match { name = Filter.Name.Exact(localName.value) } } }
            .advertisements
            .map<PlatformAdvertisement, ConnectorScan> { ad ->
                val advertisement = VehicleAdvertisement(localName, ad.identifier.toString(), ad.rssi, ad.isConnectable)
                ConnectorScan.Hit(ScanHit(advertisement, ad))
            }.catch { e ->
                if (e is CancellationException) throw e
                emit(ConnectorScan.Failed(KableErrors.map(e)))
            }

    @Suppress("TooGenericExceptionCaught") // 플랫폼 예외는 KableErrors가 값으로 바꾼다(ADR-0006)
    override suspend fun open(hit: ScanHit): OpenResult {
        val advertisement =
            hit.platform as? PlatformAdvertisement
                ?: return OpenResult.Failed(VehicleError.TransportError.ConnectFailed("advertisement from another scanner"), retry = false)
        val peripheral = Peripheral(advertisement)
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val observeJob = observe(peripheral, chunks)
        try {
            peripheral.connect()
            if (!hasVehicleService(peripheral)) {
                cleanup(peripheral, observeJob, chunks)
                return OpenResult.Failed(VehicleError.TransportError.ConnectFailed("vehicle GATT service not found"), retry = true)
            }
            val blockLength = negotiateBlockLength(peripheral, logger)
            logger.log(LogLevel.INFO, TAG) { "Connected to ${hit.advertisement.localName} (block length $blockLength)" }
            return OpenResult.Opened(KableGattLink(peripheral, blockLength, chunks, observeJob, scope))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cleanup(peripheral, observeJob, chunks) }
            throw e
        } catch (e: Exception) {
            cleanup(peripheral, observeJob, chunks)
            val error = KableErrors.map(e)
            return OpenResult.Failed(error, retry = KableErrors.isRetriable(error))
        }
    }

    private fun observe(
        peripheral: Peripheral,
        chunks: Channel<ByteArray>,
    ): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                peripheral.observe(rx, onSubscription = { onSubscribed(peripheral) }).collect { chunks.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                chunks.close(e)
            }
        }

    private suspend fun onSubscribed(peripheral: Peripheral) {
        if (subscription == SubscriptionMode.ANDROID_FORCE_INDICATION && isAndroid) {
            peripheral.write(descriptorOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE, BleUuids.CCCD), byteArrayOf(0x02, 0x00))
        }
    }

    private fun hasVehicleService(peripheral: Peripheral): Boolean =
        peripheral.services.value.orEmpty().any { service ->
            service.serviceUuid == BleUuids.SERVICE &&
                service.characteristics.map { it.characteristicUuid }.containsAll(listOf(BleUuids.TO_VEHICLE, BleUuids.FROM_VEHICLE))
        }

    private suspend fun cleanup(
        peripheral: Peripheral,
        observeJob: Job,
        chunks: Channel<ByteArray>,
    ) {
        observeJob.cancel()
        chunks.close()
        runCatching { peripheral.disconnect() }
        peripheral.close()
    }

    private companion object {
        const val TAG = "ble"
    }
}
```

`isAndroid`는 `internal expect val isAndroid: Boolean`(androidMain `true`, iosMain/jvmMain `false`)로 `MtuNegotiation.*.kt` 파일에 같이 둔다. `runCatching { peripheral.disconnect() }`는 `CancellationException`까지 삼키므로 `cleanup`은 항상 `NonCancellable` 안이나 이미 실패한 경로에서만 부른다(위 코드가 그렇다). detekt가 `runCatching`을 문제 삼으면 `try { … } catch (e: Exception) {}` + `@Suppress("SwallowedException") // 정리 중 이미 끊긴 연결`로 바꾼다.

```kotlin
// adapter-ble/src/androidMain/kotlin/io/github/smallmiro/teslable/ble/BleAvailability.android.kt
package io.github.smallmiro.teslable.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import io.github.smallmiro.teslable.model.VehicleError

/** Android 12+(API 31) BLE 런타임 권한 검사(SDD §8.4). 앱이 권한을 요청해야 한다. */
public fun androidBleAvailability(context: Context): BleAvailability {
    val app = context.applicationContext
    return BleAvailability {
        val granted =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted) null else VehicleError.TransportError.PermissionDenied
    }
}
```

```kotlin
// adapter-ble/src/iosMain/kotlin/io/github/smallmiro/teslable/ble/BleAvailability.ios.kt
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

/** iOS 블루투스 권한 검사(SDD §8.4). `notDetermined`면 통과시켜 첫 스캔이 시스템 권한 창을 띄우게 한다. */
public fun appleBleAvailability(): BleAvailability =
    BleAvailability {
        when (CBManager.authorization) {
            CBManagerAuthorizationDenied, CBManagerAuthorizationRestricted -> VehicleError.TransportError.PermissionDenied
            else -> null
        }
    }
```

```kotlin
// adapter-ble/src/commonMain/kotlin/io/github/smallmiro/teslable/ble/BleTransports.kt
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Kable 기반 BLE [TransportFactory]. [scope]는 앱 수명(파사드 `TeslaBle`)의 스코프. */
public fun kableTransportFactory(
    scope: CoroutineScope,
    crypto: CryptoPrimitives,
    availability: BleAvailability,
    logger: TeslaLogger = TeslaLogger.NoOp,
    defaultTimeout: Duration = 30.seconds,
): TransportFactory =
    BleTransportFactory(
        connector = KableGattConnector(scope, DEFAULT_SUBSCRIPTION, logger),
        availability = availability,
        scope = scope,
        crypto = crypto,
        defaultTimeout = defaultTimeout,
        logger = logger,
    )
```

`tools/ci/ported-files.txt`에 `BlockLength.kt`, `KableGattConnector.kt`를 추가한다. `kotlin.uuid`·`CBManager.authorization`의 정확한 cinterop 이름(`CBManager.authorization`이 Kotlin에서 `CBManager.Companion.authorization`일 수 있음)은 컴파일러가 알려 주는 대로 맞춘다.

- [ ] **Step 7: 빌드·테스트 확인**

Run: `./gradlew :adapter-ble:jvmTest :adapter-ble:iosSimulatorArm64Test :adapter-ble:compileAndroidMain :adapter-ble:lintKotlin :adapter-ble:detekt --console=plain`
Expected: PASS(바인딩은 컴파일만 — 동작은 Task 14 실차 확인)
Run: `./gradlew :adapter-ble:apiDump`
Expected: `kableTransportFactory`, `androidBleAvailability`, `appleBleAvailability` 추가

- [ ] **Step 8: SDD 기록·커밋 → PR(`m3/ble-kable-binding`) → verify.sh → 리뷰 → 병합**

`{{SDD_FILE}}` §2.4를 실제 구조로 고친다: "`KableGattConnector`(스캔·열기, 구독은 연결 전에 시작) / `KableGattLink`(`state`로 끊김 감지) / `BleTransportFactory`·`BleTransport`(Kable 없는 코어). 구독 방식: ADR-0003 스파이크 결과(`DEFAULT_SUBSCRIPTION`). 블록 길이: Android `requestMtu(517)` → `min(mtu,1024)-3`, iOS `maximumWriteValueLength` → `min(len,1021)`, JVM 20."

```bash
git add adapter-ble tools/ci/ported-files.txt docs/sdd/SDD.md
git commit -m "feat(ble): Kable GATT connector and link, platform permission checks, public kableTransportFactory

Refs: FR-001, FR-002, FR-006, ADR-0003
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 6: 키스토어 공통 — `KeyCreationChain`, 소프트웨어 키 생성, `InMemoryVehicleKeyStore`

참고: `{{SDD_FILE}}` §2.5, §7.2, §8.2; D10, ADR-0008; 설계 구체화 8; `{{MANUAL_DIR}}10-porting-guide.md` §12, `00-agent-guide.md` §3.3-21. Go 원본: `pkg/protocol/key.go`(`NewECDHPrivateKey` — `ecdh.P256().GenerateKey`), `internal/authentication/native.go`(`NativeECDHKey`).

**Files:**
- Create: `adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/KeyCreationChain.kt`
- Create: `adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareKeyGenerator.kt` (expect)
- Create: `adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareKeyGenerator.jvmCommon.kt`
- Create: `adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/EcPublicKeyEncoding.kt`
- Create: `adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareKeyGenerator.ios.kt`
- Create: `adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareVehicleKey.kt`
- Create: `adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/InMemoryVehicleKeyStore.kt`
- Create: `adapter-crypto/src/commonTest/kotlin/io/github/smallmiro/teslable/crypto/KeyCreationChainTest.kt`, `SoftwareKeyGeneratorTest.kt`, `InMemoryVehicleKeyStoreTest.kt`
- Create: `adapter-crypto/src/jvmCommonTest/kotlin/io/github/smallmiro/teslable/crypto/EcPublicKeyEncodingTest.kt`
- Modify: `adapter-crypto/api/*`

**Interfaces:**
- Consumes: Task 2 `VehicleKey`/`VehicleKeyStore`/`KeyPolicy`/`KeyProtection`, `softwareEcdhKey(privateScalar, publicKey)`, `PublicKeyBytes`, `VehicleResult`, `KeyStoreError`.
- Produces (Task 7, 8, 13):
  - `internal fun interface KeyAttempt { suspend fun create(): VehicleKey? }` (null = 이 수준 불가)
  - `internal object KeyCreationChain { suspend fun create(policy: KeyPolicy, hardware: List<KeyAttempt>, software: KeyAttempt, logger: (String) -> Unit = {}): VehicleResult<VehicleKey> }`
  - `internal class SoftwareKeyPair(val privateScalar: ByteArray /*32*/, val publicKey: PublicKeyBytes) { fun toExternal(): ByteArray /*97 = 04‖X‖Y‖d*/; companion fun fromExternal(bytes): SoftwareKeyPair? }`
  - `internal expect fun generateSoftwareKeyPair(): SoftwareKeyPair`
  - `internal class SoftwareVehicleKey(alias: String, pair: SoftwareKeyPair) : VehicleKey` (protection = SOFTWARE)
  - `internal object EcPublicKeyEncoding { fun encode(x: BigInteger, y: BigInteger): PublicKeyBytes }` (jvmCommon)
  - `public fun inMemoryVehicleKeyStore(): VehicleKeyStore`

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// adapter-crypto/src/commonTest/kotlin/io/github/smallmiro/teslable/crypto/KeyCreationChainTest.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class KeyCreationChainTest {
    private class StubKey(override val protection: KeyProtection) : VehicleKey {
        override val alias: String = "a"
        override val publicKey: PublicKeyBytes = PublicKeyBytes(ByteArray(65).also { it[0] = 4 })

        override suspend fun sharedX(peer: PublicKeyBytes): ByteArray = ByteArray(32)
    }

    private val strongBox = StubKey(KeyProtection.STRONGBOX)
    private val tee = StubKey(KeyProtection.TEE)
    private val software = StubKey(KeyProtection.SOFTWARE)
    private var calls = mutableListOf<String>()

    private fun attempt(
        name: String,
        key: VehicleKey?,
    ) = KeyAttempt {
        calls += name
        key
    }

    @Test
    fun preferHardwareTakesTheFirstAvailableLevel() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.PREFER_HARDWARE, listOf(attempt("sb", null), attempt("tee", tee)), attempt("sw", software))
            assertSame(tee, assertIs<VehicleResult.Success<VehicleKey>>(result).value)
            assertEquals(listOf("sb", "tee"), calls)
        }

    @Test
    fun preferHardwareFallsBackToSoftware() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.PREFER_HARDWARE, listOf(attempt("sb", null)), attempt("sw", software))
            assertSame(software, assertIs<VehicleResult.Success<VehicleKey>>(result).value)
        }

    @Test
    fun requireHardwareFailsWithoutTouchingSoftware() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.REQUIRE_HARDWARE, listOf(attempt("sb", null)), attempt("sw", software))
            assertEquals(VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable), result)
            assertEquals(listOf("sb"), calls)
        }

    @Test
    fun softwareOnlySkipsHardware() =
        runTest {
            KeyCreationChain.create(KeyPolicy.SOFTWARE_ONLY, listOf(attempt("sb", strongBox)), attempt("sw", software))
            assertEquals(listOf("sw"), calls)
        }

    @Test
    fun throwingHardwareAttemptCountsAsUnavailableAndIsLogged() =
        runTest {
            val logs = mutableListOf<String>()
            val result =
                KeyCreationChain.create(
                    KeyPolicy.PREFER_HARDWARE,
                    listOf(KeyAttempt { error("StrongBoxUnavailableException") }),
                    attempt("sw", software),
                    logger = { logs += it },
                )
            assertSame(software, assertIs<VehicleResult.Success<VehicleKey>>(result).value)
            assertEquals(1, logs.size)
        }

    @Test
    fun softwareFailureIsPlatformFailure() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.SOFTWARE_ONLY, emptyList(), KeyAttempt { error("no entropy") })
            assertIs<VehicleError.KeyStoreError.PlatformFailure>(assertIs<VehicleResult.Failure>(result).error)
        }

    @Test
    fun cancellationPropagates() =
        runTest {
            assertFailsWith<CancellationException> {
                KeyCreationChain.create(KeyPolicy.PREFER_HARDWARE, listOf(KeyAttempt { throw CancellationException("stop") }), attempt("sw", software))
            }
        }
}
```

```kotlin
// adapter-crypto/src/commonTest/kotlin/io/github/smallmiro/teslable/crypto/SoftwareKeyGeneratorTest.kt
package io.github.smallmiro.teslable.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SoftwareKeyGeneratorTest {
    @Test
    fun generatedKeysAgreeOnTheSameSharedX() =
        runTest {
            val a = generateSoftwareKeyPair()
            val b = generateSoftwareKeyPair()
            assertEquals(32, a.privateScalar.size)
            assertEquals(4, a.publicKey.toByteArray()[0].toInt())
            assertFalse(a.publicKey == b.publicKey)
            val ab = SoftwareVehicleKey("a", a).sharedX(b.publicKey)
            val ba = SoftwareVehicleKey("b", b).sharedX(a.publicKey)
            assertEquals(32, ab.size)
            assertContentEquals(ab, ba)
        }

    @Test
    fun externalRepresentationRoundTrips() {
        val pair = generateSoftwareKeyPair()
        val external = pair.toExternal()
        assertEquals(97, external.size) // 04‖X‖Y‖d (SecKeyCopyExternalRepresentation과 같은 형식)
        val back = SoftwareKeyPair.fromExternal(external)
        assertEquals(pair.publicKey, back?.publicKey)
        assertContentEquals(pair.privateScalar, back?.privateScalar)
        assertNull(SoftwareKeyPair.fromExternal(external.copyOf(96)))
        assertNull(SoftwareKeyPair.fromExternal(ByteArray(97)))
    }
}
```

```kotlin
// adapter-crypto/src/commonTest/kotlin/io/github/smallmiro/teslable/crypto/InMemoryVehicleKeyStoreTest.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class InMemoryVehicleKeyStoreTest {
    @Test
    fun getOrCreateIsStablePerAliasAndSoftware() =
        runTest {
            val store = inMemoryVehicleKeyStore()
            val first = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate()).value
            val again = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate()).value
            val other = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate("other")).value
            assertEquals(first.publicKey, again.publicKey)
            assertNotEquals(first.publicKey, other.publicKey)
            assertEquals(KeyProtection.SOFTWARE, first.protection)
            assertEquals("teslable-default", first.alias)
        }

    @Test
    fun getReturnsNullWhenMissingAndDeleteIsIdempotent() =
        runTest {
            val store = inMemoryVehicleKeyStore()
            assertEquals(VehicleResult.Success(null), store.get())
            store.getOrCreate()
            assertEquals(VehicleResult.Success(Unit), store.delete())
            assertEquals(VehicleResult.Success(Unit), store.delete())
            assertEquals(VehicleResult.Success(null), store.get())
        }

    @Test
    fun requireHardwareIsUnavailable() =
        runTest {
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable),
                inMemoryVehicleKeyStore().getOrCreate(policy = KeyPolicy.REQUIRE_HARDWARE),
            )
        }

    @Test
    fun invalidAliasIsAProgrammingError() =
        runTest {
            assertFailsWith<IllegalArgumentException> { inMemoryVehicleKeyStore().getOrCreate("../x") }
        }
}
```

```kotlin
// adapter-crypto/src/jvmCommonTest/kotlin/io/github/smallmiro/teslable/crypto/EcPublicKeyEncodingTest.kt
package io.github.smallmiro.teslable.crypto

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class EcPublicKeyEncodingTest {
    @Test
    fun padsShortCoordinatesTo32Bytes() {
        // SDD §8.2: ECPublicKey.w → 0x04‖X(32)‖Y(32), 좌표는 앞을 0으로 채운다(BigInteger.toByteArray는 짧거나 부호 바이트를 붙인다)
        val x = BigInteger("01", 16)
        val y = BigInteger("ff" + "00".repeat(31), 16) // 부호 비트가 켜져 toByteArray()가 33바이트
        val encoded = EcPublicKeyEncoding.encode(x, y).toByteArray()
        assertEquals(65, encoded.size)
        assertEquals(4, encoded[0].toInt())
        assertEquals(1, encoded[32].toInt())
        assertEquals(0xff, encoded[33].toInt() and 0xff)
        assertEquals(0, encoded[64].toInt())
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :adapter-crypto:jvmTest --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

```kotlin
// adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/KeyCreationChain.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.CancellationException

/** 보관 수준 하나에서 키를 만드는 시도. 이 수준을 쓸 수 없으면 null. */
internal fun interface KeyAttempt {
    suspend fun create(): VehicleKey?
}

/** D10: 하드웨어(가장 강한 것부터) → 소프트웨어. 하드웨어 시도의 예외는 "쓸 수 없음"으로 보고 로그만 남긴다. */
internal object KeyCreationChain {
    @Suppress("TooGenericExceptionCaught") // 플랫폼 키스토어 예외는 수준 불가 또는 PlatformFailure 값이 된다(ADR-0006)
    suspend fun create(
        policy: KeyPolicy,
        hardware: List<KeyAttempt>,
        software: KeyAttempt,
        logger: (String) -> Unit = {},
    ): VehicleResult<VehicleKey> {
        if (policy != KeyPolicy.SOFTWARE_ONLY) {
            for (attempt in hardware) {
                val key =
                    try {
                        attempt.create()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger("hardware key level unavailable: ${e::class.simpleName}")
                        null
                    }
                if (key != null) return VehicleResult.Success(key)
            }
        }
        if (policy == KeyPolicy.REQUIRE_HARDWARE) return VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable)
        return try {
            software.create()?.let { VehicleResult.Success(it) }
                ?: VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("software key generation unavailable"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("software key generation failed: ${e::class.simpleName}"))
        }
    }
}
```

```kotlin
// adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareKeyGenerator.kt
// Ported from vehicle-command@a4b43c1 pkg/protocol/key.go (Apache-2.0) — NewECDHPrivateKey
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes

private const val SCALAR_SIZE = 32
private const val EXTERNAL_SIZE = PublicKeyBytes.SIZE + SCALAR_SIZE

/** 소프트웨어 P-256 키쌍. [privateScalar]는 이 모듈 밖으로 나가지 않는다(NFR-004). */
internal class SoftwareKeyPair(
    val privateScalar: ByteArray,
    val publicKey: PublicKeyBytes,
) {
    init {
        require(privateScalar.size == SCALAR_SIZE) { "P-256 scalar must be 32 bytes" }
    }

    /** `04‖X‖Y‖d`(97바이트, Apple `SecKeyCopyExternalRepresentation` 개인키 형식). 저장 직전에만 만든다. */
    fun toExternal(): ByteArray = publicKey.toByteArray() + privateScalar

    companion object {
        /** [toExternal]의 역. 길이·형식이 틀리면 null. */
        fun fromExternal(bytes: ByteArray): SoftwareKeyPair? {
            if (bytes.size != EXTERNAL_SIZE || bytes[0].toInt() != 4) return null
            val publicKey = runCatching { PublicKeyBytes(bytes.copyOfRange(0, PublicKeyBytes.SIZE)) }.getOrNull() ?: return null
            return SoftwareKeyPair(bytes.copyOfRange(PublicKeyBytes.SIZE, EXTERNAL_SIZE), publicKey)
        }
    }
}

/** 플랫폼 CSPRNG로 P-256 키쌍을 만든다(JCA `KeyPairGenerator("EC")` / `SecKeyCreateRandomKey`). */
internal expect fun generateSoftwareKeyPair(): SoftwareKeyPair
```

`SoftwareKeyPair.fromExternal(ByteArray(97))`이 null인지(첫 바이트 0)는 위 `bytes[0] != 4` 검사가 맡는다. `PublicKeyBytes` 생성자가 곡선 위의 점인지 검사하지 않으면, 잘못된 점은 첫 `sharedX` 호출에서 `IllegalArgumentException`이 된다(기존 `JcaSoftwareEcdhKey` 동작).

```kotlin
// adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/EcPublicKeyEncoding.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import java.math.BigInteger

/** JCA 좌표 → 65바이트 비압축 공개키(SDD §8.2, 좌표 32바이트 0-패딩). */
internal object EcPublicKeyEncoding {
    private const val COORDINATE_SIZE = 32

    fun encode(
        x: BigInteger,
        y: BigInteger,
    ): PublicKeyBytes = PublicKeyBytes(byteArrayOf(4) + x.toFixed() + y.toFixed())

    /** 부호 바이트를 떼고 앞을 0으로 채운다. */
    fun BigInteger.toFixed(): ByteArray {
        val raw = toByteArray()
        val trimmed = if (raw.size > COORDINATE_SIZE) raw.copyOfRange(raw.size - COORDINATE_SIZE, raw.size) else raw
        return ByteArray(COORDINATE_SIZE).also { trimmed.copyInto(it, COORDINATE_SIZE - trimmed.size) }
    }
}
```

```kotlin
// adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareKeyGenerator.jvmCommon.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.crypto.EcPublicKeyEncoding.toFixed
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

internal actual fun generateSoftwareKeyPair(): SoftwareKeyPair {
    val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    val pair = generator.generateKeyPair()
    val publicKey = pair.public as ECPublicKey
    val privateKey = pair.private as ECPrivateKey
    return SoftwareKeyPair(privateKey.s.toFixed(), EcPublicKeyEncoding.encode(publicKey.w.affineX, publicKey.w.affineY))
}
```

```kotlin
// adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareKeyGenerator.ios.kt
package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCreateRandomKey
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom

private const val P256 = 256
private const val ATTRIBUTE_CAPACITY = 2L

/** 토큰 없는(소프트웨어) 비영속 P-256 키를 만들고 외부 표현 97바이트에서 키쌍을 읽는다. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun generateSoftwareKeyPair(): SoftwareKeyPair =
    memScoped {
        val attributes =
            checkNotNull(CFDictionaryCreateMutable(null, ATTRIBUTE_CAPACITY, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
        val bits = alloc<IntVar>().apply { value = P256 }
        val bitsNumber = checkNotNull(CFNumberCreate(null, kCFNumberIntType, bits.ptr))
        try {
            CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            CFDictionaryAddValue(attributes, kSecAttrKeySizeInBits, bitsNumber)
            val error = alloc<CFErrorRefVar>()
            val key = SecKeyCreateRandomKey(attributes, error.ptr) ?: error("SecKeyCreateRandomKey failed: ${error.takeDescription()}")
            try {
                val external = SecKeyCopyExternalRepresentation(key, error.ptr) ?: error("export failed: ${error.takeDescription()}")
                external.use { data ->
                    val bytes = data.toByteArray()
                    try {
                        checkNotNull(SoftwareKeyPair.fromExternal(bytes)) { "unexpected key representation" }
                    } finally {
                        bytes.fill(0)
                    }
                }
            } finally {
                CFRelease(key)
            }
        } finally {
            CFRelease(bitsNumber)
            CFRelease(attributes)
        }
    }
```

`SoftwareKeyPair.fromExternal`은 `copyOfRange`로 사본을 만들므로 원본 `bytes`를 지워도 키쌍은 남는다. `SoftwareVehicleKey`는 `softwareEcdhKey(...)`에 넘길 때 **사본**을 넘긴다(iOS `createSecKey`가 넘겨받은 배열을 0으로 지우기 때문 — `AppleSoftwareEcdhKey.kt` KDoc).

```kotlin
// adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/SoftwareVehicleKey.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey

/** 소프트웨어 키 핸들. ECDH는 기존 `softwareEcdhKey`(JCA/Security.framework)에 맡긴다. */
internal class SoftwareVehicleKey(
    override val alias: String,
    pair: SoftwareKeyPair,
) : VehicleKey {
    private val delegate: EcdhPrivateKey = softwareEcdhKey(pair.privateScalar.copyOf(), pair.publicKey)

    override val publicKey: PublicKeyBytes = pair.publicKey
    override val protection: KeyProtection = KeyProtection.SOFTWARE

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray = delegate.sharedX(peer)
}
```

```kotlin
// adapter-crypto/src/commonMain/kotlin/io/github/smallmiro/teslable/crypto/InMemoryVehicleKeyStore.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 비영속 소프트웨어 키스토어(설계 구체화 8): JVM 기본값, 테스트용. 프로세스가 끝나면 키가 사라진다. */
public fun inMemoryVehicleKeyStore(): VehicleKeyStore = InMemoryVehicleKeyStore()

internal class InMemoryVehicleKeyStore : VehicleKeyStore {
    private val keys = HashMap<String, VehicleKey>()
    private val mutex = Mutex()

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        return mutex.withLock {
            keys[alias]?.let { return@withLock VehicleResult.Success(it) }
            KeyCreationChain.create(policy, hardware = emptyList(), software = { SoftwareVehicleKey(alias, generateSoftwareKeyPair()) })
                .also { if (it is VehicleResult.Success) keys[alias] = it.value }
        }
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        return VehicleResult.Success(mutex.withLock { keys[alias] })
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        mutex.withLock { keys.remove(alias) }
        return VehicleResult.Success(Unit)
    }
}
```

`tools/ci/ported-files.txt`에 `SoftwareKeyGenerator.kt`를 추가한다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :adapter-crypto:jvmTest :adapter-crypto:testAndroidHostTest :adapter-crypto:iosSimulatorArm64Test --console=plain`
Expected: PASS
Run: `./gradlew :adapter-crypto:apiDump`
Expected: `inMemoryVehicleKeyStore` 추가

- [ ] **Step 5: 커밋 → PR(`m3/keystore-core`) → verify.sh → 리뷰 → 병합**

```bash
git add adapter-crypto tools/ci/ported-files.txt
git commit -m "feat(crypto): key creation chain, software P-256 key generation and in-memory VehicleKeyStore

Refs: FR-020, FR-021, NFR-004, ADR-0008
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 7: Android 키스토어 — StrongBox → TEE → 소프트웨어(Keystore AES로 감싼 파일)

참고: `{{SDD_FILE}}` §2.5, §7.2, §8.2(Android 열 전체); 설계 구체화 8, 10; ADR-0008. Go 원본: 없음(Go는 PEM 파일, `pkg/protocol/key.go` — 포팅하지 않음, SDD §1.3).

**Files:**
- Create: `adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/KeyWrapper.kt`
- Create: `adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/WrappedKeyFiles.kt`
- Create: `adapter-crypto/src/jvmCommonTest/kotlin/io/github/smallmiro/teslable/crypto/WrappedKeyFilesTest.kt`
- Create: `adapter-crypto/src/androidMain/kotlin/io/github/smallmiro/teslable/crypto/AndroidKeystoreWrapper.kt`
- Create: `adapter-crypto/src/androidMain/kotlin/io/github/smallmiro/teslable/crypto/AndroidKeystoreKey.kt`
- Create: `adapter-crypto/src/androidMain/kotlin/io/github/smallmiro/teslable/crypto/AndroidVehicleKeyStore.kt`
- Modify: `adapter-crypto/api/*`

**Interfaces:**
- Consumes: Task 6 `KeyCreationChain`/`KeyAttempt`/`SoftwareKeyPair`/`SoftwareVehicleKey`/`generateSoftwareKeyPair`/`EcPublicKeyEncoding`.
- Produces (Task 13):
  - `internal interface KeyWrapper { fun wrap(plain: ByteArray): ByteArray; fun unwrap(wrapped: ByteArray): ByteArray?; fun destroy() }` — `wrapped`는 `iv(12)‖ciphertext+tag`
  - `internal class WrappedKeyFiles(directory: File, wrapperFor: (alias: String) -> KeyWrapper)`: `read(alias): ReadResult`(`Missing` / `Found(pair)` / `Corrupted`), `write(alias, pair)`(원자적), `delete(alias)`
  - `public fun androidVehicleKeyStore(context: android.content.Context, logger: TeslaLogger = TeslaLogger.NoOp): VehicleKeyStore`

**설계 메모:** 소프트웨어 대체 키의 저장 형식은 iOS와 같은 `04‖X‖Y‖d` 97바이트를 감싼다(설계 구체화 10 — 두 플랫폼이 같은 `SoftwareKeyPair.toExternal/fromExternal`을 쓴다). 파일 형식: `"TBK1"(4) ‖ wrapped`. 손상된 파일(매직·길이·인증 태그 불일치)은 **자동으로 새 키로 바꾸지 않고** `Failure(PlatformFailure("stored key is corrupted"))`를 돌려준다 — 조용히 바꾸면 차량에 등록된 키를 잃는다. 앱은 `delete(alias)` 후 다시 만들 수 있다.

- [ ] **Step 1: 실패 테스트 작성 (JVM에서 시험 가능한 파일 형식)**

```kotlin
// adapter-crypto/src/jvmCommonTest/kotlin/io/github/smallmiro/teslable/crypto/WrappedKeyFilesTest.kt
package io.github.smallmiro.teslable.crypto

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WrappedKeyFilesTest {
    private val dir: File = Files.createTempDirectory("teslable-keys").toFile()

    /** 테스트용 JCA AES-GCM 래퍼(운영은 AndroidKeyStore AES 키). */
    private class JcaWrapper : KeyWrapper {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var destroyed = false

        override fun wrap(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return iv + cipher.doFinal(plain)
        }

        override fun unwrap(wrapped: ByteArray): ByteArray? =
            runCatching {
                val cipher =
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
                    }
                cipher.doFinal(wrapped.copyOfRange(12, wrapped.size))
            }.getOrNull()

        override fun destroy() {
            destroyed = true
        }
    }

    private val wrappers = HashMap<String, JcaWrapper>()
    private val files = WrappedKeyFiles(dir) { alias -> wrappers.getOrPut(alias) { JcaWrapper() } }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun writesWrappedKeyAndReadsItBack() {
        val pair = generateSoftwareKeyPair()
        files.write("teslable-default", pair)
        val stored = File(dir, "teslable-default.enc").readBytes()
        assertEquals("TBK1", stored.copyOfRange(0, 4).decodeToString())
        assertFalse(stored.toList().windowed(32).any { it.toByteArray().contentEquals(pair.privateScalar) }) // 평문 스칼라가 파일에 없다
        val read = assertIs<WrappedKeyFiles.ReadResult.Found>(files.read("teslable-default"))
        assertEquals(pair.publicKey, read.pair.publicKey)
        assertContentEquals(pair.privateScalar, read.pair.privateScalar)
    }

    @Test
    fun missingFileIsMissing() {
        assertEquals(WrappedKeyFiles.ReadResult.Missing, files.read("nope"))
    }

    @Test
    fun tamperedOrForeignFileIsCorruptedNotReplaced() {
        files.write("a", generateSoftwareKeyPair())
        val file = File(dir, "a.enc")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertEquals(WrappedKeyFiles.ReadResult.Corrupted, files.read("a"))
        File(dir, "b.enc").writeBytes("XXXX".encodeToByteArray() + ByteArray(40))
        assertEquals(WrappedKeyFiles.ReadResult.Corrupted, files.read("b"))
        assertTrue(file.exists()) // 지우지 않는다(설계 메모)
    }

    @Test
    fun deleteRemovesFileAndDestroysWrapperKey() {
        files.write("a", generateSoftwareKeyPair())
        files.delete("a")
        assertFalse(File(dir, "a.enc").exists())
        assertTrue(wrappers.getValue("a").destroyed)
        files.delete("a") // 멱등
    }

    @Test
    fun overwriteLeavesNoTemporaryFiles() {
        files.write("a", generateSoftwareKeyPair())
        files.write("a", generateSoftwareKeyPair())
        assertEquals(listOf("a.enc"), dir.list()?.sorted())
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :adapter-crypto:jvmTest --console=plain`
Expected: 컴파일 실패 (`KeyWrapper`, `WrappedKeyFiles` 없음)

- [ ] **Step 3: 파일 형식 구현**

```kotlin
// adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/KeyWrapper.kt
package io.github.smallmiro.teslable.crypto

/** 소프트웨어 개인키를 감싸는 대칭 키(Android: Keystore AES-256-GCM `<alias>.wrap`). 결과는 `iv(12)‖ciphertext+tag`. */
internal interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    /** 인증 실패·형식 오류면 null. */
    fun unwrap(wrapped: ByteArray): ByteArray?

    /** 감싸는 키를 지운다. 멱등. */
    fun destroy()
}
```

```kotlin
// adapter-crypto/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/crypto/WrappedKeyFiles.kt
package io.github.smallmiro.teslable.crypto

import java.io.File

/** `<directory>/<alias>.enc` = `"TBK1" ‖ wrap(04‖X‖Y‖d)`(설계 구체화 10). 쓰기는 임시 파일 + rename으로 원자적. */
internal class WrappedKeyFiles(
    private val directory: File,
    private val wrapperFor: (alias: String) -> KeyWrapper,
) {
    sealed interface ReadResult {
        data object Missing : ReadResult

        data object Corrupted : ReadResult

        class Found(val pair: SoftwareKeyPair) : ReadResult
    }

    fun read(alias: String): ReadResult {
        val file = fileFor(alias)
        if (!file.exists()) return ReadResult.Missing
        val bytes = file.readBytes()
        if (bytes.size <= MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return ReadResult.Corrupted
        val plain = wrapperFor(alias).unwrap(bytes.copyOfRange(MAGIC.size, bytes.size)) ?: return ReadResult.Corrupted
        return try {
            SoftwareKeyPair.fromExternal(plain)?.let { ReadResult.Found(it) } ?: ReadResult.Corrupted
        } finally {
            plain.fill(0)
        }
    }

    fun write(
        alias: String,
        pair: SoftwareKeyPair,
    ) {
        directory.mkdirs()
        val plain = pair.toExternal()
        val wrapped =
            try {
                wrapperFor(alias).wrap(plain)
            } finally {
                plain.fill(0)
            }
        val target = fileFor(alias)
        val temp = File(directory, "${target.name}.tmp")
        temp.writeBytes(MAGIC + wrapped)
        check(temp.renameTo(target)) { "could not move key file into place" }
    }

    fun delete(alias: String) {
        fileFor(alias).delete()
        wrapperFor(alias).destroy()
    }

    private fun fileFor(alias: String) = File(directory, "$alias.enc")

    private companion object {
        val MAGIC = "TBK1".encodeToByteArray()
    }
}
```

JVM의 `File.renameTo`는 대상이 있으면 플랫폼에 따라 실패할 수 있다(Windows). Android/Linux/macOS에서는 덮어쓴다. `check` 실패는 `IllegalStateException`이고 호출자(`AndroidVehicleKeyStore`)가 `PlatformFailure`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :adapter-crypto:jvmTest :adapter-crypto:testAndroidHostTest --console=plain`
Expected: PASS

- [ ] **Step 5: Android Keystore 구현 (단위 테스트 불가 — 에뮬레이터 확인은 Task 14)**

```kotlin
// adapter-crypto/src/androidMain/kotlin/io/github/smallmiro/teslable/crypto/AndroidKeystoreWrapper.kt
package io.github.smallmiro.teslable.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val IV_SIZE = 12
private const val TAG_BITS = 128
private const val AES_BITS = 256

/** Keystore AES-256-GCM 키(`<alias>.wrap`)로 감싼다. 키는 Keystore 밖으로 나가지 않는다. */
internal class AndroidKeystoreWrapper(
    alias: String,
) : KeyWrapper {
    private val wrapAlias = "$alias.wrap"
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun key(): SecretKey =
        (keyStore.getKey(wrapAlias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec.Builder(wrapAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(AES_BITS)
                        .build(),
                )
                generateKey()
            }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) } // Keystore가 IV를 만든다
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray? {
        if (wrapped.size <= IV_SIZE) return null
        val existing = keyStore.getKey(wrapAlias, null) as? SecretKey ?: return null
        return try {
            Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, IV_SIZE))) }
                .doFinal(wrapped.copyOfRange(IV_SIZE, wrapped.size))
        } catch (@Suppress("SwallowedException") e: java.security.GeneralSecurityException) {
            null // 인증 실패 = 손상(설계 메모)
        }
    }

    override fun destroy() {
        if (keyStore.containsAlias(wrapAlias)) keyStore.deleteEntry(wrapAlias)
    }
}
```

```kotlin
// adapter-crypto/src/androidMain/kotlin/io/github/smallmiro/teslable/crypto/AndroidKeystoreKey.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.crypto.EcPublicKeyEncoding.toFixed
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/** AndroidKeyStore 개인키 핸들. ECDH는 Keystore 안에서 한다(SDD §8.2: `KeyAgreement("ECDH", "AndroidKeyStore")`). */
internal class AndroidKeystoreKey(
    override val alias: String,
    private val privateKey: PrivateKey,
    override val publicKey: PublicKeyBytes,
    override val protection: KeyProtection,
) : VehicleKey {
    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray =
        withContext(Dispatchers.IO) {
            val params =
                AlgorithmParameters.getInstance("EC").run {
                    init(ECGenParameterSpec("secp256r1"))
                    getParameterSpec(ECParameterSpec::class.java)
                }
            val peerKey =
                try {
                    KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, peer.x), BigInteger(1, peer.y)), params))
                } catch (e: GeneralSecurityException) {
                    throw IllegalArgumentException("invalid peer public key", e)
                }
            val agreement = KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
            agreement.init(privateKey)
            agreement.doPhase(peerKey, true)
            BigInteger(1, agreement.generateSecret()).toFixed() // Go FillBytes(32)
        }
}
```

```kotlin
// adapter-crypto/src/androidMain/kotlin/io/github/smallmiro/teslable/crypto/AndroidVehicleKeyStore.kt
package io.github.smallmiro.teslable.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Android 키스토어(SDD §8.2): StrongBox → TEE → 감싼 소프트웨어 키 파일. */
public fun androidVehicleKeyStore(
    context: Context,
    logger: TeslaLogger = TeslaLogger.NoOp,
): VehicleKeyStore = AndroidVehicleKeyStore(File(context.applicationContext.filesDir, "teslable/keys"), logger)

@Suppress("TooGenericExceptionCaught") // Keystore 예외는 KeyStoreError 값이 된다(ADR-0006)
internal class AndroidVehicleKeyStore(
    directory: File,
    private val logger: TeslaLogger,
) : VehicleKeyStore {
    private val mutex = Mutex()
    private val files = WrappedKeyFiles(directory) { AndroidKeystoreWrapper(it) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            when (val existing = load(alias)) {
                is Loaded.Key -> VehicleResult.Success(existing.key)
                Loaded.Corrupted -> VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"))
                Loaded.Missing ->
                    KeyCreationChain.create(
                        policy,
                        hardware = listOf(KeyAttempt { generateKeystoreKey(alias, strongBox = true) }, KeyAttempt { generateKeystoreKey(alias, strongBox = false) }),
                        software = KeyAttempt { generateSoftwareKeyPair().also { files.write(alias, it) }.let { SoftwareVehicleKey(alias, it) } },
                        logger = { message -> logger.log(LogLevel.INFO, TAG) { message } },
                    )
            }
        }
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            when (val existing = load(alias)) {
                is Loaded.Key -> VehicleResult.Success(existing.key)
                Loaded.Missing -> VehicleResult.Success(null)
                Loaded.Corrupted -> VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"))
            }
        }
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        return guarded {
            val store = keyStore()
            if (store.containsAlias(alias)) store.deleteEntry(alias)
            files.delete(alias)
            VehicleResult.Success(Unit)
        }
    }

    private sealed interface Loaded {
        data object Missing : Loaded

        data object Corrupted : Loaded

        class Key(val key: VehicleKey) : Loaded
    }

    private fun load(alias: String): Loaded {
        val store = keyStore()
        val privateKey = store.getKey(alias, null) as? PrivateKey
        val certificate = store.getCertificate(alias)
        if (privateKey != null && certificate != null) {
            val publicKey = certificate.publicKey as ECPublicKey
            return Loaded.Key(AndroidKeystoreKey(alias, privateKey, encode(publicKey), protectionOf(privateKey)))
        }
        return when (val read = files.read(alias)) {
            WrappedKeyFiles.ReadResult.Missing -> Loaded.Missing
            WrappedKeyFiles.ReadResult.Corrupted -> Loaded.Corrupted
            is WrappedKeyFiles.ReadResult.Found -> Loaded.Key(SoftwareVehicleKey(alias, read.pair))
        }
    }

    /** null = 이 수준 불가. 생성은 됐지만 실제 보관 수준이 SOFTWARE(에뮬레이터 등)면 지우고 null — 소프트웨어 대체 경로가 맡는다. */
    private fun generateKeystoreKey(
        alias: String,
        strongBox: Boolean,
    ): VehicleKey? {
        val spec =
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setIsStrongBoxBacked(strongBox)
                .build()
        val pair =
            try {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
                    initialize(spec)
                    generateKeyPair()
                }
            } catch (e: StrongBoxUnavailableException) {
                return null
            }
        val protection = protectionOf(pair.private)
        if (!protection.isHardware) {
            keyStore().deleteEntry(alias)
            return null
        }
        return AndroidKeystoreKey(alias, pair.private, encode(pair.public as ECPublicKey), protection)
    }

    private fun protectionOf(privateKey: PrivateKey): KeyProtection {
        val info = KeyFactory.getInstance(privateKey.algorithm, ANDROID_KEY_STORE).getKeySpec(privateKey, KeyInfo::class.java)
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtection.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtection.TEE
            else -> KeyProtection.SOFTWARE
        }
    }

    private fun encode(publicKey: ECPublicKey) = EcPublicKeyEncoding.encode(publicKey.w.affineX, publicKey.w.affineY)

    private suspend fun <T> guarded(block: suspend () -> VehicleResult<T>): VehicleResult<T> =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure(e::class.simpleName ?: "keystore error"))
                }
            }
        }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TAG = "keystore"
    }
}
```

`KeyInfo.securityLevel`은 API 31+이다(`minSdk 31`). `PURPOSE_AGREE_KEY`도 API 31+.

- [ ] **Step 6: 빌드 확인**

Run: `./gradlew :adapter-crypto:compileAndroidMain :adapter-crypto:testAndroidHostTest :adapter-crypto:detekt :adapter-crypto:lintKotlin --console=plain`
Expected: PASS
Run: `./gradlew :adapter-crypto:apiDump`
Expected: `androidVehicleKeyStore`(Android 변형 API) 추가

- [ ] **Step 7: 커밋 → PR(`m3/keystore-android`) → verify.sh → 리뷰 → 병합**

```bash
git add adapter-crypto
git commit -m "feat(crypto): Android VehicleKeyStore (StrongBox, TEE, Keystore-wrapped software fallback)

Refs: FR-020, FR-021, FR-028, NFR-004
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 8: iOS 키스토어 — Secure Enclave → 소프트웨어(Keychain generic password)

참고: `{{SDD_FILE}}` §2.5, §7.2, §8.2(iOS 열); 설계 구체화 8, 10, 11; ADR-0008. 빌드 사실: 시뮬레이터에는 Secure Enclave가 없다.

**Files:**
- Create: `adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/KeychainInterop.kt` (CF 딕셔너리·문자열 헬퍼)
- Create: `adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/KeychainItems.kt` (포트 + `AppleKeychainItems`)
- Create: `adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/SecureEnclaveKeys.kt` (포트 + `AppleSecureEnclaveKeys`, `SecureEnclaveKey`)
- Create: `adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/AppleVehicleKeyStore.kt`
- Create: `adapter-crypto/src/iosTest/kotlin/io/github/smallmiro/teslable/crypto/AppleVehicleKeyStoreTest.kt`
- Create: `adapter-crypto/src/iosTest/kotlin/io/github/smallmiro/teslable/crypto/AppleKeychainItemsTest.kt` (Step 1 결과에 따라)
- Modify: `adapter-crypto/api/*`

**Interfaces:**
- Consumes: Task 6 `KeyCreationChain`/`KeyAttempt`/`SoftwareKeyPair`/`SoftwareVehicleKey`/`generateSoftwareKeyPair`, 기존 `toCFData`/`toByteArray`/`takeDescription`/`use`(`CoreFoundationInterop.kt`), `createSecKey`(`AppleSoftwareEcdhKey.kt`).
- Produces (Task 9의 같은 모양 래퍼, Task 13):
  - `internal interface KeychainItems { fun read(account: String): KeychainRead; fun write(account: String, data: ByteArray): Int /*OSStatus*/; fun delete(account: String): Int }`, `sealed interface KeychainRead { Missing; Found(bytes); Failed(status: Int) }`
  - `internal class AppleKeychainItems(service: String) : KeychainItems` (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`)
  - `internal interface SecureEnclaveKeys { fun find(alias: String): VehicleKey?; fun create(alias: String): VehicleKey?; fun delete(alias: String) }`
  - `public fun appleVehicleKeyStore(logger: TeslaLogger = TeslaLogger.NoOp): VehicleKeyStore`

- [ ] **Step 1: 시뮬레이터 Keychain 가능 여부 먼저 확인 (설계 구체화 11)**

```kotlin
// adapter-crypto/src/iosTest/kotlin/io/github/smallmiro/teslable/crypto/AppleKeychainItemsTest.kt
package io.github.smallmiro.teslable.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppleKeychainItemsTest {
    @Test
    fun genericPasswordRoundTripsAndDeletes() {
        val items = AppleKeychainItems("io.github.smallmiro.teslable.test")
        items.delete("probe")
        assertEquals(0, items.write("probe", byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), assertIs<KeychainRead.Found>(items.read("probe")).bytes)
        assertEquals(0, items.write("probe", byteArrayOf(4))) // 덮어쓰기
        assertContentEquals(byteArrayOf(4), assertIs<KeychainRead.Found>(items.read("probe")).bytes)
        items.delete("probe")
        assertEquals(KeychainRead.Missing, items.read("probe"))
    }
}
```

Step 3의 `KeychainItems.kt`를 먼저 구현한 뒤 이 테스트만 실행한다.
Run: `./gradlew :adapter-crypto:iosSimulatorArm64Test --tests '*AppleKeychainItemsTest*' --console=plain`
- PASS → 테스트를 유지한다(실제 Keychain 왕복이 자동 검증된다).
- `write`가 `-34018`(errSecMissingEntitlement)로 실패 → 이 테스트 파일을 **지우고**, Task 보고서와 PR 본문에 "시뮬레이터 테스트 실행 파일에서 Keychain 불가(-34018) — 실제 Keychain은 Task 14 iPhone 샘플로 확인"이라고 적는다. `{{SDD_FILE}}` §9.5에도 한 줄 남긴다. 테스트를 건너뛰는 코드(`if (status == -34018) return`)는 쓰지 않는다.

- [ ] **Step 2: 로직 실패 테스트 작성 (가짜 Keychain·가짜 Secure Enclave)**

```kotlin
// adapter-crypto/src/iosTest/kotlin/io/github/smallmiro/teslable/crypto/AppleVehicleKeyStoreTest.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppleVehicleKeyStoreTest {
    private class FakeKeychain : KeychainItems {
        val items = HashMap<String, ByteArray>()
        var failStatus: Int? = null

        override fun read(account: String): KeychainRead =
            failStatus?.let { KeychainRead.Failed(it) } ?: items[account]?.let { KeychainRead.Found(it.copyOf()) } ?: KeychainRead.Missing

        override fun write(
            account: String,
            data: ByteArray,
        ): Int {
            items[account] = data.copyOf()
            return 0
        }

        override fun delete(account: String): Int {
            items.remove(account)
            return 0
        }
    }

    private class FakeEnclave(var available: Boolean) : SecureEnclaveKeys {
        val keys = HashMap<String, VehicleKey>()

        override fun find(alias: String): VehicleKey? = keys[alias]

        override fun create(alias: String): VehicleKey? =
            if (!available) null else SoftwareVehicleKey(alias, generateSoftwareKeyPair()).let { Enclave(it) }.also { keys[alias] = it }

        override fun delete(alias: String) {
            keys.remove(alias)
        }

        private class Enclave(private val d: VehicleKey) : VehicleKey by d {
            override val protection: KeyProtection = KeyProtection.SECURE_ENCLAVE
        }
    }

    @Test
    fun simulatorWithoutEnclaveStoresSoftwareKeyInKeychainAndReloadsIt() =
        runTest {
            val keychain = FakeKeychain()
            val store = AppleVehicleKeyStore(keychain, FakeEnclave(available = false))
            val created = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate()).value
            assertEquals(KeyProtection.SOFTWARE, created.protection)
            assertEquals(97, keychain.items.getValue("teslable-default").size)
            val reloaded = assertIs<VehicleResult.Success<VehicleKey?>>(AppleVehicleKeyStore(keychain, FakeEnclave(false)).get()).value
            assertEquals(created.publicKey, reloaded?.publicKey)
            val peer = generateSoftwareKeyPair().publicKey
            assertContentEquals(created.sharedX(peer), assertIsNotNull(reloaded).sharedX(peer))
        }

    @Test
    fun enclaveKeyIsPreferredAndNotWrittenToKeychain() =
        runTest {
            val keychain = FakeKeychain()
            val key = assertIs<VehicleResult.Success<VehicleKey>>(AppleVehicleKeyStore(keychain, FakeEnclave(true)).getOrCreate()).value
            assertEquals(KeyProtection.SECURE_ENCLAVE, key.protection)
            assertTrue(keychain.items.isEmpty())
        }

    @Test
    fun requireHardwareFailsOnSimulator() =
        runTest {
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable),
                AppleVehicleKeyStore(FakeKeychain(), FakeEnclave(false)).getOrCreate(policy = KeyPolicy.REQUIRE_HARDWARE),
            )
        }

    @Test
    fun corruptedKeychainItemIsReportedNotReplaced() =
        runTest {
            val keychain = FakeKeychain().apply { items["teslable-default"] = ByteArray(10) }
            val store = AppleVehicleKeyStore(keychain, FakeEnclave(false))
            val failure = assertIs<VehicleResult.Failure>(store.getOrCreate())
            assertEquals(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"), failure.error)
            assertEquals(10, keychain.items.getValue("teslable-default").size)
        }

    @Test
    fun keychainErrorIsPlatformFailureWithStatus() =
        runTest {
            val keychain = FakeKeychain().apply { failStatus = -25308 } // errSecInteractionNotAllowed(잠긴 기기)
            val failure = assertIs<VehicleResult.Failure>(AppleVehicleKeyStore(keychain, FakeEnclave(false)).get())
            assertEquals(VehicleError.KeyStoreError.PlatformFailure("keychain status -25308"), failure.error)
        }

    @Test
    fun deleteRemovesBothEnclaveAndKeychainCopies() =
        runTest {
            val keychain = FakeKeychain().apply { items["teslable-default"] = ByteArray(97) }
            val enclave = FakeEnclave(true).apply { create("teslable-default") }
            assertEquals(VehicleResult.Success(Unit), AppleVehicleKeyStore(keychain, enclave).delete())
            assertTrue(keychain.items.isEmpty())
            assertTrue(enclave.keys.isEmpty())
        }

    private fun <T : Any> assertIsNotNull(value: T?): T = kotlin.test.assertNotNull(value)
}
```

- [ ] **Step 3: 구현**

```kotlin
// adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/KeychainInterop.kt
package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString

/** 키-값 쌍으로 CF 딕셔너리를 만든다(값은 딕셔너리가 retain). 호출자가 `CFRelease`. */
@OptIn(ExperimentalForeignApi::class)
internal fun cfDictionary(vararg entries: Pair<CFStringRef?, CFTypeRef?>): CFMutableDictionaryRef {
    val dictionary =
        checkNotNull(CFDictionaryCreateMutable(null, entries.size.toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr))
    entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
    return dictionary
}

/** Kotlin 문자열 → retain된 `CFStringRef`. 호출자가 `CFRelease`. */
@OptIn(ExperimentalForeignApi::class)
internal fun String.toCFString(): CFStringRef = checkNotNull(CFBridgingRetain(this as NSString)) as CFStringRef

/** [block]이 끝나면 [refs]를 모두 해제한다. */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> releasing(
    vararg refs: CFTypeRef?,
    block: () -> T,
): T =
    try {
        block()
    } finally {
        refs.forEach { if (it != null) CFRelease(it) }
    }
```

```kotlin
// adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/KeychainItems.kt
package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Keychain generic password 읽기 결과. */
internal sealed interface KeychainRead {
    data object Missing : KeychainRead

    class Found(val bytes: ByteArray) : KeychainRead

    data class Failed(val status: Int) : KeychainRead
}

/** 서비스 하나의 generic password 항목(설계 구체화 11). 반환 `Int`는 OSStatus. */
internal interface KeychainItems {
    fun read(account: String): KeychainRead

    /** 덮어쓴다(지우고 추가). */
    fun write(
        account: String,
        data: ByteArray,
    ): Int

    fun delete(account: String): Int
}

/** Security.framework 구현. 접근성은 [accessible](기본 `WhenUnlockedThisDeviceOnly`, 백업·이전 불가). */
@OptIn(ExperimentalForeignApi::class)
internal class AppleKeychainItems(
    private val service: String,
    private val accessible: platform.CoreFoundation.CFStringRef? = kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
) : KeychainItems {
    override fun read(account: String): KeychainRead =
        memScoped {
            val serviceRef = service.toCFString()
            val accountRef = account.toCFString()
            releasing(serviceRef, accountRef) {
                val query =
                    cfDictionary(
                        kSecClass to kSecClassGenericPassword,
                        kSecAttrService to serviceRef,
                        kSecAttrAccount to accountRef,
                        kSecReturnData to kCFBooleanTrue,
                        kSecMatchLimit to kSecMatchLimitOne,
                    )
                releasing(query) {
                    val result = alloc<CFTypeRefVar>()
                    when (val status = SecItemCopyMatching(query, result.ptr)) {
                        errSecSuccess -> {
                            @Suppress("UNCHECKED_CAST")
                            val data = result.value as CFDataRef
                            data.use { KeychainRead.Found(it.toByteArray()) }
                        }
                        errSecItemNotFound -> KeychainRead.Missing
                        else -> KeychainRead.Failed(status)
                    }
                }
            }
        }

    override fun write(
        account: String,
        data: ByteArray,
    ): Int {
        delete(account)
        val serviceRef = service.toCFString()
        val accountRef = account.toCFString()
        val dataRef = data.toCFData()
        return releasing(serviceRef, accountRef, dataRef) {
            val attributes =
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to serviceRef,
                    kSecAttrAccount to accountRef,
                    kSecAttrAccessible to accessible,
                    kSecValueData to dataRef,
                )
            releasing(attributes) { SecItemAdd(attributes, null) }
        }
    }

    override fun delete(account: String): Int {
        val serviceRef = service.toCFString()
        val accountRef = account.toCFString()
        return releasing(serviceRef, accountRef) {
            val query = cfDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef, kSecAttrAccount to accountRef)
            releasing(query) { SecItemDelete(query) }
        }
    }
}
```

cinterop 타입(`CFDataRef`/`CFTypeRef` 캐스트, `OSStatus` = `Int`)은 컴파일러가 알려 주는 대로 맞춘다. 같은 모양이 Task 9 `:adapter-storage`에 한 번 더 나온다(설계 구체화 11).

```kotlin
// adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/SecureEnclaveKeys.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnRef

/** Secure Enclave 영속 키(설계 구체화 10). 쓸 수 없으면 [create]가 null. */
internal interface SecureEnclaveKeys {
    fun find(alias: String): VehicleKey?

    fun create(alias: String): VehicleKey?

    fun delete(alias: String)
}

private const val P256 = 256
private const val TAG_PREFIX = "io.github.smallmiro.teslable.key."

@OptIn(ExperimentalForeignApi::class)
internal class AppleSecureEnclaveKeys : SecureEnclaveKeys {
    override fun find(alias: String): VehicleKey? =
        memScoped {
            val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
            releasing(tag) {
                val query = cfDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tag, kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef to kCFBooleanTrue)
                releasing(query) {
                    val result = alloc<CFTypeRefVar>()
                    if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
                    @Suppress("UNCHECKED_CAST")
                    SecureEnclaveKey(alias, result.value as SecKeyRef)
                }
            }
        }

    override fun create(alias: String): VehicleKey? =
        memScoped {
            val error = alloc<CFErrorRefVar>()
            val access =
                SecAccessControlCreateWithFlags(null, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAccessControlPrivateKeyUsage, error.ptr)
                    ?: return@memScoped null
            val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
            val bits = alloc<IntVar>().apply { value = P256 }
            val bitsNumber = checkNotNull(CFNumberCreate(null, kCFNumberIntType, bits.ptr))
            releasing(access, tag, bitsNumber) {
                val privateAttrs = cfDictionary(kSecAttrIsPermanent to kCFBooleanTrue, kSecAttrApplicationTag to tag, kSecAttrAccessControl to access)
                releasing(privateAttrs) {
                    val attributes =
                        cfDictionary(
                            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                            kSecAttrKeySizeInBits to bitsNumber,
                            kSecAttrTokenID to kSecAttrTokenIDSecureEnclave,
                            kSecPrivateKeyAttrs to privateAttrs,
                        )
                    releasing(attributes) {
                        val key = SecKeyCreateRandomKey(attributes, error.ptr)
                        if (key == null) {
                            error.takeDescription() // CFError 해제. 시뮬레이터: Secure Enclave 없음 → null → 소프트웨어로
                            null
                        } else {
                            SecureEnclaveKey(alias, key)
                        }
                    }
                }
            }
        }

    override fun delete(alias: String) {
        val tag = (TAG_PREFIX + alias).encodeToByteArray().toCFData()
        releasing(tag) {
            val query = cfDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tag)
            releasing(query) { SecItemDelete(query) }
        }
    }
}

/** Secure Enclave 개인키 핸들. 공개키는 `SecKeyCopyPublicKey` → 외부 표현(X9.63 65바이트). */
@OptIn(ExperimentalForeignApi::class)
internal class SecureEnclaveKey(
    override val alias: String,
    private val privateKey: SecKeyRef,
) : VehicleKey {
    override val protection: KeyProtection = KeyProtection.SECURE_ENCLAVE
    override val publicKey: PublicKeyBytes =
        memScoped {
            val publicRef = checkNotNull(SecKeyCopyPublicKey(privateKey)) { "no public key" }
            releasing(publicRef) {
                val error = alloc<CFErrorRefVar>()
                val data = SecKeyCopyExternalRepresentation(publicRef, error.ptr) ?: error("export failed: ${error.takeDescription()}")
                data.use { PublicKeyBytes(it.toByteArray()) }
            }
        }

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray =
        memScoped {
            val peerKey = createSecKey(peer.toByteArray(), platform.Security.kSecAttrKeyClassPublic)
            releasing(peerKey) {
                val params = cfDictionary()
                releasing(params) {
                    val error = alloc<CFErrorRefVar>()
                    val result =
                        SecKeyCopyKeyExchangeResult(privateKey, kSecKeyAlgorithmECDHKeyExchangeStandard, peerKey, params, error.ptr)
                            ?: throw IllegalArgumentException("ECDH failed: ${error.takeDescription()}")
                    result.use { it.toByteArray() }
                }
            }
        }
}
```

`SecureEnclaveKey`는 `SecKeyRef`를 소유한다(앱 수명 동안 유지; 별도 해제 없음 — 키 핸들이 GC될 때 누수는 키 하나 크기다). 이것이 문제가 되면 Task 13 파사드가 키를 캐시해 한 번만 만든다.

```kotlin
// adapter-crypto/src/iosMain/kotlin/io/github/smallmiro/teslable/crypto/AppleVehicleKeyStore.kt
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** iOS 키스토어(SDD §8.2): Secure Enclave → Keychain 소프트웨어 키. */
public fun appleVehicleKeyStore(logger: TeslaLogger = TeslaLogger.NoOp): VehicleKeyStore =
    AppleVehicleKeyStore(AppleKeychainItems(KEY_SERVICE), AppleSecureEnclaveKeys(), logger)

private const val KEY_SERVICE = "io.github.smallmiro.teslable.key"

internal class AppleVehicleKeyStore(
    private val keychain: KeychainItems,
    private val enclave: SecureEnclaveKeys,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
) : VehicleKeyStore {
    private val mutex = Mutex()

    private sealed interface Loaded {
        data object Missing : Loaded

        class Key(val key: VehicleKey) : Loaded

        class Error(val error: VehicleError) : Loaded
    }

    private fun load(alias: String): Loaded {
        enclave.find(alias)?.let { return Loaded.Key(it) }
        return when (val read = keychain.read(alias)) {
            KeychainRead.Missing -> Loaded.Missing
            is KeychainRead.Failed -> Loaded.Error(VehicleError.KeyStoreError.PlatformFailure("keychain status ${read.status}"))
            is KeychainRead.Found ->
                try {
                    SoftwareKeyPair.fromExternal(read.bytes)?.let { Loaded.Key(SoftwareVehicleKey(alias, it)) }
                        ?: Loaded.Error(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"))
                } finally {
                    read.bytes.fill(0)
                }
        }
    }

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        return mutex.withLock {
            when (val loaded = load(alias)) {
                is Loaded.Key -> VehicleResult.Success(loaded.key)
                is Loaded.Error -> VehicleResult.Failure(loaded.error)
                Loaded.Missing ->
                    KeyCreationChain.create(
                        policy,
                        hardware = listOf(KeyAttempt { enclave.create(alias) }),
                        software = KeyAttempt { createSoftware(alias) },
                        logger = { message -> logger.log(LogLevel.INFO, TAG) { message } },
                    )
            }
        }
    }

    private fun createSoftware(alias: String): VehicleKey {
        val pair = generateSoftwareKeyPair()
        val external = pair.toExternal()
        val status =
            try {
                keychain.write(alias, external)
            } finally {
                external.fill(0)
            }
        check(status == 0) { "keychain status $status" }
        return SoftwareVehicleKey(alias, pair)
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        return mutex.withLock {
            when (val loaded = load(alias)) {
                is Loaded.Key -> VehicleResult.Success(loaded.key)
                is Loaded.Error -> VehicleResult.Failure(loaded.error)
                Loaded.Missing -> VehicleResult.Success(null)
            }
        }
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        mutex.withLock {
            enclave.delete(alias)
            keychain.delete(alias)
        }
        return VehicleResult.Success(Unit)
    }

    private companion object {
        const val TAG = "keystore"
    }
}
```

`createSoftware`의 `check` 실패(`IllegalStateException`)는 `KeyCreationChain`이 `PlatformFailure("software key generation failed: IllegalStateException")`로 바꾼다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :adapter-crypto:iosSimulatorArm64Test :adapter-crypto:jvmTest --console=plain`
Expected: PASS(Step 1 결과에 따라 `AppleKeychainItemsTest` 포함 또는 삭제)
Run: `./gradlew :adapter-crypto:apiDump`
Expected: `appleVehicleKeyStore`(iOS klib API) 추가

- [ ] **Step 5: 커밋 → PR(`m3/keystore-ios`) → verify.sh → 리뷰 → 병합**

```bash
git add adapter-crypto docs/sdd/SDD.md
git commit -m "feat(crypto): iOS VehicleKeyStore (Secure Enclave with Keychain software fallback)

Refs: FR-020, FR-021, FR-028, NFR-004
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 9: 플랫폼 세션 캐시 — `FileSessionCache`(Android/JVM), `KeychainSessionCache`(iOS)

참고: `{{SDD_FILE}}` §2.6, §7.1; ADR-0007; 설계 구체화 9, 11; M2 Task 8(`InMemorySessionCache`, `SessionCacheCodec.encodeSnapshots/decodeSessions`). Go 원본: `pkg/cache/cache.go`(`Update`, `GetEntry` — 포맷은 다름, D26).

**Files:**
- Create: `adapter-storage/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/storage/FileSessionCache.kt`
- Create: `adapter-storage/src/androidMain/kotlin/io/github/smallmiro/teslable/storage/AndroidSessionCache.kt`
- Create: `adapter-storage/src/jvmCommonTest/kotlin/io/github/smallmiro/teslable/storage/FileSessionCacheTest.kt`
- Create: `adapter-storage/src/iosMain/kotlin/io/github/smallmiro/teslable/storage/KeychainItems.kt` (Task 8과 같은 모양, 설계 구체화 11)
- Create: `adapter-storage/src/iosMain/kotlin/io/github/smallmiro/teslable/storage/KeychainSessionCache.kt`
- Create: `adapter-storage/src/iosTest/kotlin/io/github/smallmiro/teslable/storage/KeychainSessionCacheTest.kt`
- Create: `adapter-storage/src/commonMain/kotlin/io/github/smallmiro/teslable/storage/CacheFileName.kt`
- Modify: `adapter-storage/api/*`

**Interfaces:**
- Consumes: `SessionCache`, `SessionCacheCodec.encodeSnapshots(keyId, entries, nowEpochMillis)`/`decodeSessions(bytes, keyId, nowEpochMillis)`, `CryptoPrimitives.sha1`, `TeslaLogger`.
- Produces (Task 13):
  - `internal fun cacheFileName(vin: Vin, crypto: CryptoPrimitives): String` = `hex(SHA1(vin))`(VIN 원문을 파일명·account에 남기지 않는다, SDD §7.1)
  - `public class FileSessionCache(directory: java.io.File, crypto: CryptoPrimitives, logger: TeslaLogger = NoOp, wallClockMillis: () -> Long = …) : SessionCache` (jvmCommonMain)
  - `public fun androidSessionCache(context: Context, crypto: CryptoPrimitives, logger: TeslaLogger = NoOp): SessionCache` (`filesDir/teslable/sessions`)
  - `public fun appleSessionCache(crypto: CryptoPrimitives, logger: TeslaLogger = NoOp): SessionCache` (service `io.github.smallmiro.teslable.session`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`)

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// adapter-storage/src/jvmCommonTest/kotlin/io/github/smallmiro/teslable/storage/FileSessionCacheTest.kt
package io.github.smallmiro.teslable.storage

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FileSessionCacheTest {
    private val dir: File = Files.createTempDirectory("teslable-sessions").toFile()
    private val vin = Vin("5YJ30123456789ABC")
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
    private var now = 1_000_000L
    private val logger = RecordingLogger()
    private val cache = FileSessionCache(dir, TestCrypto.primitives, logger) { now }
    private val info = byteArrayOf(8, 1)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun storesUnderSha1OfVinAndLoadsWithAge() =
        runTest {
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            val files = dir.list()?.toList().orEmpty()
            assertEquals(listOf(cacheFileName(vin, TestCrypto.primitives) + ".bin"), files)
            assertFalse(files.single().contains(vin.value)) // SDD §7.1: VIN 원문 없음
            now += 30_000
            val loaded = cache.load(vin, keyId).single()
            assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, loaded.domain)
            assertEquals(30.seconds, loaded.age)
            assertContentEquals(info, loaded.sessionInfo)
        }

    @Test
    fun otherKeysCacheIsDeletedOnLoad() =
        // ADR-0007, 설계 구체화 9(FR-028): keyId가 다르면 버리고 지운다
        runTest {
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            val otherKey = KeyId(ByteArray(KeyId.SIZE) { 7 })
            assertEquals(emptyList(), cache.load(vin, otherKey))
            assertTrue(dir.list().isNullOrEmpty())
        }

    @Test
    fun corruptedFileIsIgnoredAndDeleted() =
        runTest {
            dir.mkdirs()
            File(dir, cacheFileName(vin, TestCrypto.primitives) + ".bin").writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(emptyList(), cache.load(vin, keyId))
            assertTrue(dir.list().isNullOrEmpty())
        }

    @Test
    fun ioFailureIsLoggedNotThrown() =
        // SessionCache 포트 계약: 쓰기 실패는 로그로만
        runTest {
            val blocker = Files.createTempFile("not-a-dir", "").toFile()
            val broken = FileSessionCache(blocker, TestCrypto.primitives, logger) { now }
            broken.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            assertEquals(emptyList(), broken.load(vin, keyId))
            assertTrue(logger.contains("Failed to store session cache"))
            blocker.delete()
        }

    @Test
    fun emptyStoreOverwritesAndClearRemoves() =
        runTest {
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            cache.store(vin, keyId, emptyList())
            assertEquals(emptyList(), cache.load(vin, keyId))
            cache.clear(vin)
            assertTrue(dir.list().isNullOrEmpty())
        }
}
```

`CachedSession`의 `sessionInfo` 접근자 이름(`sessionInfo()` 또는 프로퍼티)은 `domain/…/cache/CachedSession.kt`를 확인해 맞춘다.

```kotlin
// adapter-storage/src/iosTest/kotlin/io/github/smallmiro/teslable/storage/KeychainSessionCacheTest.kt
package io.github.smallmiro.teslable.storage

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KeychainSessionCacheTest {
    private class FakeKeychain : KeychainItems {
        val items = HashMap<String, ByteArray>()
        var writeStatus = 0

        override fun read(account: String): KeychainRead = items[account]?.let { KeychainRead.Found(it.copyOf()) } ?: KeychainRead.Missing

        override fun write(
            account: String,
            data: ByteArray,
        ): Int {
            if (writeStatus == 0) items[account] = data.copyOf()
            return writeStatus
        }

        override fun delete(account: String): Int {
            items.remove(account)
            return 0
        }
    }

    private val vin = Vin("5YJ30123456789ABC")
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
    private var now = 5_000L

    @Test
    fun storesUnderSha1AccountAndLoadsWithAge() =
        runTest {
            val keychain = FakeKeychain()
            val cache = KeychainSessionCache(keychain, TestCrypto.primitives, RecordingLogger()) { now }
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(8, 2))))
            assertEquals(setOf(cacheFileName(vin, TestCrypto.primitives)), keychain.items.keys)
            now += 2_000
            assertEquals(2.seconds, cache.load(vin, keyId).single().age)
        }

    @Test
    fun otherKeyOrCorruptItemIsDeleted() =
        runTest {
            val keychain = FakeKeychain()
            val cache = KeychainSessionCache(keychain, TestCrypto.primitives, RecordingLogger()) { now }
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(8, 2))))
            assertEquals(emptyList(), cache.load(vin, KeyId(ByteArray(KeyId.SIZE))))
            assertTrue(keychain.items.isEmpty())
        }

    @Test
    fun keychainWriteFailureIsLoggedNotThrown() =
        runTest {
            val logger = RecordingLogger()
            val keychain = FakeKeychain().apply { writeStatus = -25308 }
            KeychainSessionCache(keychain, TestCrypto.primitives, logger) { now }.store(vin, keyId, emptyList())
            assertTrue(logger.contains("Failed to store session cache: keychain status -25308"))
        }
}
```

`adapter-storage/build.gradle.kts`에는 이미 `commonTest`에 `:testing`이 있다(M2). `jvmCommonTest`·`iosTest`는 `commonTest`를 상속하므로 추가할 것이 없다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :adapter-storage:jvmTest :adapter-storage:iosSimulatorArm64Test --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

```kotlin
// adapter-storage/src/commonMain/kotlin/io/github/smallmiro/teslable/storage/CacheFileName.kt
package io.github.smallmiro.teslable.storage

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.util.toHex

/** VIN 원문 대신 `hex(SHA1(VIN))`을 파일 이름·Keychain account로 쓴다(SDD §7.1). */
@OptIn(InternalTeslableApi::class)
internal fun cacheFileName(
    vin: Vin,
    crypto: CryptoPrimitives,
): String = crypto.sha1(vin.toByteArray()).toHex()
```

```kotlin
// adapter-storage/src/jvmCommonMain/kotlin/io/github/smallmiro/teslable/storage/FileSessionCache.kt
package io.github.smallmiro.teslable.storage

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 앱 전용 디렉터리의 파일 세션 캐시(SDD §2.6). `<directory>/<hex(SHA1(vin))>.bin` = [SessionCacheCodec] v1.
 * 쓰기는 임시 파일 + rename(원자적). I/O 실패는 로그만 남기고 빈 결과(포트 계약). 벽시계는 [wallClockMillis]로만.
 */
public class FileSessionCache(
    private val directory: File,
    private val crypto: CryptoPrimitives,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
) : SessionCache {
    private val mutex = Mutex()

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> =
        locked {
            val file = fileFor(vin)
            if (!file.exists()) return@locked emptyList()
            val decoded =
                try {
                    SessionCacheCodec.decodeSessions(file.readBytes(), keyId, wallClockMillis())
                } catch (e: IOException) {
                    logger.log(LogLevel.WARN, TAG) { "Failed to read session cache: ${e::class.simpleName}" }
                    return@locked emptyList()
                }
            if (decoded == null) file.delete()
            decoded.orEmpty()
        }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        locked {
            try {
                directory.mkdirs()
                val target = fileFor(vin)
                val temp = File(directory, "${target.name}.tmp")
                temp.writeBytes(SessionCacheCodec.encodeSnapshots(keyId, entries, wallClockMillis()))
                if (!temp.renameTo(target)) throw IOException("rename failed")
            } catch (e: IOException) {
                logger.log(LogLevel.WARN, TAG) { "Failed to store session cache: ${e::class.simpleName}" }
            }
        }
    }

    override suspend fun clear(vin: Vin) {
        locked { fileFor(vin).delete() }
    }

    private fun fileFor(vin: Vin) = File(directory, cacheFileName(vin, crypto) + ".bin")

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val TAG = "cache"
    }
}
```

`kotlinx.coroutines.Dispatchers.IO`는 JVM/Android에서만 있다(`jvmCommonMain`이므로 문제없음). `ioFailureIsLoggedNotThrown`에서 디렉터리 자리가 파일이면 `writeBytes`가 `FileNotFoundException`(=`IOException`)을 던진다.

```kotlin
// adapter-storage/src/androidMain/kotlin/io/github/smallmiro/teslable/storage/AndroidSessionCache.kt
package io.github.smallmiro.teslable.storage

import android.content.Context
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import java.io.File

/** Android 기본 세션 캐시: `filesDir/teslable/sessions`(MODE_PRIVATE 앱 전용 영역, SDD §2.6). */
public fun androidSessionCache(
    context: Context,
    crypto: CryptoPrimitives,
    logger: TeslaLogger = TeslaLogger.NoOp,
): SessionCache = FileSessionCache(File(context.applicationContext.filesDir, "teslable/sessions"), crypto, logger)
```

`adapter-storage/src/iosMain/…/KeychainItems.kt`는 Task 8의 `KeychainRead`/`KeychainItems`/`AppleKeychainItems`와 `cfDictionary`/`toCFString`/`releasing`/`toCFData`/`toByteArray`/`use` 헬퍼를 **같은 코드로** 이 패키지(`io.github.smallmiro.teslable.storage`)에 둔다(설계 구체화 11 — 모듈 경계 때문에 공유하지 않는다). 파일 머리 주석에 "`:adapter-crypto`의 같은 이름 파일과 의도적으로 같은 코드(ADR-0001 경계)"라고 적는다.

```kotlin
// adapter-storage/src/iosMain/kotlin/io/github/smallmiro/teslable/storage/KeychainSessionCache.kt
package io.github.smallmiro.teslable.storage

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import kotlin.time.Clock

/** iOS 기본 세션 캐시(SDD §2.6): Keychain generic password, account = `hex(SHA1(vin))`. */
@OptIn(ExperimentalForeignApi::class)
public fun appleSessionCache(
    crypto: CryptoPrimitives,
    logger: TeslaLogger = TeslaLogger.NoOp,
): SessionCache =
    KeychainSessionCache(AppleKeychainItems(SESSION_SERVICE, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly), crypto, logger) {
        Clock.System.now().toEpochMilliseconds()
    }

private const val SESSION_SERVICE = "io.github.smallmiro.teslable.session"

internal class KeychainSessionCache(
    private val keychain: KeychainItems,
    private val crypto: CryptoPrimitives,
    private val logger: TeslaLogger,
    private val wallClockMillis: () -> Long,
) : SessionCache {
    private val mutex = Mutex()

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> =
        mutex.withLock {
            val account = cacheFileName(vin, crypto)
            when (val read = keychain.read(account)) {
                KeychainRead.Missing -> emptyList()
                is KeychainRead.Failed -> {
                    logger.log(LogLevel.WARN, TAG) { "Failed to read session cache: keychain status ${read.status}" }
                    emptyList()
                }
                is KeychainRead.Found ->
                    SessionCacheCodec.decodeSessions(read.bytes, keyId, wallClockMillis()) ?: emptyList<CachedSession>().also {
                        keychain.delete(account)
                    }
            }
        }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        mutex.withLock {
            val status = keychain.write(cacheFileName(vin, crypto), SessionCacheCodec.encodeSnapshots(keyId, entries, wallClockMillis()))
            if (status != 0) logger.log(LogLevel.WARN, TAG) { "Failed to store session cache: keychain status $status" }
        }
    }

    override suspend fun clear(vin: Vin) {
        mutex.withLock { keychain.delete(cacheFileName(vin, crypto)) }
    }

    private companion object {
        const val TAG = "cache"
    }
}
```

`kotlin.time.Clock.System`이 옵트인을 요구하면 M2 `InMemorySessionCache`와 같은 방식(`@OptIn(kotlin.time.ExperimentalTime::class)`)을 쓴다. 벽시계는 `:adapter-storage`에서만 허용된다(Global Constraints).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :adapter-storage:jvmTest :adapter-storage:testAndroidHostTest :adapter-storage:iosSimulatorArm64Test --console=plain`
Expected: PASS
Run: `./gradlew :adapter-storage:apiDump`
Expected: `FileSessionCache`(jvm/android), `androidSessionCache`, `appleSessionCache` 추가

- [ ] **Step 5: 커밋 → PR(`m3/session-cache-platform`) → verify.sh → 리뷰 → 병합**

```bash
git add adapter-storage
git commit -m "feat(storage): platform session caches (app-private file, iOS Keychain)

Refs: FR-017, FR-028, ADR-0007
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 10: 인증 없는 VCSEC 조회 — `VehicleInfoQueries`(`vehicleStatus`, `keySummary`, `keyInfoBySlot`, `listKeys`)와 공개 값 객체

참고: `{{MANUAL_DIR}}05-command-catalog.md` B(`body-controller-state`, `list-keys`), `03-protocol.md` §12, `09-recipes.md`(키 목록), SDD §7.3; 설계 구체화 12; FR-025, FR-031, FR-032. Go 원본: `pkg/vehicle/vcsec.go:107-130`(`getVCSECInfo`), `pkg/vehicle/state.go:13-19`(`BodyControllerState`), `pkg/vehicle/security.go:290-304`(`KeySummary`, `KeyInfoBySlot`), `cmd/tesla-control/commands.go:489-517`(`list-keys` 슬롯 순회), `vcsec.proto:47-74`, `:244-286`, `keys.proto:7-17`.

**Files:**
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/VehicleStatus.kt`
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/Whitelist.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/Dispatcher.kt` (`internal val clock`) — `struct:` 커밋
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecInfoMapping.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VehicleInfoQueries.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSession.kt` (`val info`)
- Create: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vcsec/VehicleInfoQueriesTest.kt`
- Modify: `domain/api/*`, `application/api/*`, `tools/ci/ported-files.txt`

**Interfaces:**
- Consumes: `VcsecCommands.execute(payload, AuthMethod.NONE, VcsecResponses.FIRST_MESSAGE, flags, timeout)`, `CommandTimeouts`, `ByteString.unknownVarint(tag)`(`@InternalTeslableApi`), `PublicKeyBytes`, `FakeVehicle.script`/`ScriptedReply`/`vcsecPayload`/`vcsecEmpty`/`vcsecNominalError`/`sleep`/`received`.
- Produces (Task 13):
  - `:domain` `data class VehicleStatus(lockState: LockState, closures: Closures?, sleepStatus: SleepStatus, userPresence: UserPresence, tonneauPercentOpen: Int?)`, `data class Closures(frontDriverDoor, frontPassengerDoor, rearDriverDoor, rearPassengerDoor, rearTrunk, frontTrunk, chargePort, tonneau: ClosureState)`, `enum LockState { UNLOCKED, LOCKED, INTERNAL_LOCKED, SELECTIVE_UNLOCKED, UNKNOWN }`, `enum ClosureState { CLOSED, OPEN, AJAR, UNKNOWN, FAILED_UNLATCH, OPENING, CLOSING }`, `enum SleepStatus { UNKNOWN, AWAKE, ASLEEP }`, `enum UserPresence { UNKNOWN, NOT_PRESENT, PRESENT }`
  - `:domain` `data class WhitelistSummary(numberOfEntries: Int, slotMask: UInt) { val occupiedSlots: List<Int> }`, `data class WhitelistEntry(slot: Int, publicKey: PublicKeyBytes?, keyIdSha1Hex: String?, role: KeyRole, formFactor: KeyFormFactor)`, `enum KeyRole { NONE, SERVICE, OWNER, DRIVER, FLEET_MANAGER, VEHICLE_MONITOR, CHARGING_MANAGER, GUEST, UNKNOWN }`, `enum KeyFormFactor { UNKNOWN, NFC_CARD, IOS_DEVICE, ANDROID_DEVICE, CLOUD_KEY }`
  - `:application` `class VehicleInfoQueries internal constructor(vcsec: VcsecCommands, timeouts: CommandTimeouts, timeSource: TimeSource)`: `suspend fun vehicleStatus(timeout = timeouts.commandTimeout): VehicleResult<VehicleStatus>`, `keySummary(timeout)`, `keyInfoBySlot(slot: Int, timeout)`, `listKeys(timeout): VehicleResult<List<WhitelistEntry>>`; `companion fun informationRequest(type: InformationRequestType, slot: Int? = null): ByteArray`
  - `VehicleSession.info: VehicleInfoQueries`

- [ ] **Step 1: 구조 변경 먼저 (`struct:`)**

`Dispatcher`에 테스트·조회가 같은 시계를 쓰도록 노출만 추가한다(동작 변경 없음).

```kotlin
        /** 이 디스패처의 시계(`listKeys` 시간 예산 등, 같은 연결의 다른 계층이 공유한다). */
        internal val clock: TimeSource get() = timeSource
```

Run: `./gradlew :application:jvmTest --console=plain` → PASS(변화 없음)

```bash
git add application
git commit -m "struct(application): expose the dispatcher clock to sibling layers

Refs: FR-025
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 2: 실패 테스트 작성**

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vcsec/VehicleInfoQueriesTest.kt
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.DetailedClosureStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.KeyFormFactor as WireFormFactor
import com.tesla.generated.vcsec.KeyMetadata
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.ClosureState
import io.github.smallmiro.teslable.model.KeyFormFactor
import io.github.smallmiro.teslable.model.KeyRole
import io.github.smallmiro.teslable.model.LockState
import io.github.smallmiro.teslable.model.SleepStatus
import io.github.smallmiro.teslable.model.UserPresence
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleInfoQueriesTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    private fun reply(message: FromVCSECMessage) = listOf(FakeVehicle.vcsecPayload(message))

    private fun hex(bytes: okio.ByteString?) = bytes?.hex()

    @Test
    fun informationRequestBytesMatchGo() {
        // vcsec.go:107-130 getVCSECInfo; GET_STATUS(0)는 proto3 기본값이라 빈 InformationRequest
        assertEquals("0a00", VehicleInfoQueries.informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS).toByteString().hex())
        // protocol.md:78 list-keys TX의 payload
        assertEquals("0a020805", VehicleInfoQueries.informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO).toByteString().hex())
        // Go는 slot 0도 oneof로 싣는다(vcsec.go:117-121)
        assertEquals("0a0408062000", VehicleInfoQueries.informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO, slot = 0).toByteString().hex())
        assertEquals("0a0408062002", VehicleInfoQueries.informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO, slot = 2).toByteString().hex())
    }

    @Test
    fun vehicleStatusIsUnauthenticatedAndMapsEveryField() =
        // FR-031: 핸드셰이크 없이(개인키도 없이) 동작
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val session = VehicleSession(h.dispatcher)
            h.fake.script(
                vcsec,
                reply(
                    FromVCSECMessage(
                        vehicleStatus =
                            WireVehicleStatus(
                                closureStatuses = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN, tonneau = ClosureState_E.CLOSURESTATE_AJAR),
                                vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                                vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
                                userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
                                detailedClosureStatus = DetailedClosureStatus(tonneauPercentOpen = 40),
                            ),
                    ),
                ),
            )
            val status = assertIs<VehicleResult.Success<io.github.smallmiro.teslable.model.VehicleStatus>>(session.info.vehicleStatus()).value
            assertEquals(LockState.LOCKED, status.lockState)
            assertEquals(ClosureState.OPEN, status.closures?.frontDriverDoor)
            assertEquals(ClosureState.CLOSED, status.closures?.rearTrunk)
            assertEquals(ClosureState.AJAR, status.closures?.tonneau)
            assertEquals(SleepStatus.AWAKE, status.sleepStatus)
            assertEquals(UserPresence.PRESENT, status.userPresence)
            assertEquals(40, status.tonneauPercentOpen)
            val request = h.fake.received.last()
            assertEquals(vcsec, request.to_destination?.domain)
            assertEquals("0a00", hex(request.protobuf_message_as_bytes))
            assertNull(request.signature_data) // AuthMethod.NONE
        }

    @Test
    fun unknownLockAndClosureValuesBecomeUnknownNotTheWireDefault() =
        // Wire는 모르는 enum을 기본값(UNLOCKED/CLOSED)으로 디코딩한다 — 잠금 상태를 "열림"으로 보이면 안 된다(SDD §7.3)
        runTest {
            val h = dispatcherHarness(privateKey = null)
            // FromVCSECMessage{vehicleStatus(1){vehicleLockState(2)=9, closureStatuses(1){frontDriverDoor(1)=42}}}
            val raw = byteArrayOf(0x0a, 0x06, 0x10, 0x09, 0x0a, 0x02, 0x08, 0x2a)
            h.fake.script(vcsec, listOf(FakeVehicle.ScriptedReply(payload = raw)))
            val status = assertIs<VehicleResult.Success<io.github.smallmiro.teslable.model.VehicleStatus>>(VehicleSession(h.dispatcher).info.vehicleStatus()).value
            assertEquals(LockState.UNKNOWN, status.lockState)
            assertEquals(ClosureState.UNKNOWN, status.closures?.frontDriverDoor)
        }

    @Test
    fun replyWithoutVehicleStatusIsBadResponse() =
        // 설계 구체화 12: Go는 (nil, nil) — 우리는 값 객체를 만들 수 없으므로 실패
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecEmpty()))
            val failure = assertIs<VehicleResult.Failure>(VehicleSession(h.dispatcher).info.vehicleStatus())
            assertEquals(VehicleError.BadResponse("missing vehicleStatus"), failure.error)
            assertFalse(failure.error.mayHaveSucceeded)
        }

    @Test
    fun keySummaryExposesOccupiedSlots() =
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(vcsec, reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 2, slotMask = 0b101))))
            val summary = assertIs<VehicleResult.Success<WhitelistSummary>>(VehicleSession(h.dispatcher).info.keySummary()).value
            assertEquals(2, summary.numberOfEntries)
            assertEquals(5u, summary.slotMask)
            assertEquals(listOf(0, 2), summary.occupiedSlots)
            assertEquals("0a020805", hex(h.fake.received.last().protobuf_message_as_bytes))
        }

    @Test
    fun listKeysQueriesEachOccupiedSlotInOrder() =
        // commands.go:489-517: slotMask 비트마다 KeyInfoBySlot
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val key = TestCrypto.clientPublicKey.toByteArray().toByteString()
            h.fake.script(
                vcsec,
                reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 2, slotMask = 0b101))),
                reply(
                    FromVCSECMessage(
                        whitelistEntryInfo =
                            WhitelistEntryInfo(
                                publicKey = PublicKey(PublicKeyRaw = key),
                                metadataForKey = KeyMetadata(keyFormFactor = WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE),
                                slot = 0,
                                keyRole = Role.ROLE_OWNER,
                            ),
                    ),
                ),
                reply(FromVCSECMessage(whitelistEntryInfo = WhitelistEntryInfo(slot = 2, keyRole = Role.ROLE_DRIVER))),
            )
            val entries = assertIs<VehicleResult.Success<List<io.github.smallmiro.teslable.model.WhitelistEntry>>>(VehicleSession(h.dispatcher).info.listKeys()).value
            assertEquals(listOf(0, 2), entries.map { it.slot })
            assertEquals(TestCrypto.clientPublicKey, entries[0].publicKey)
            assertEquals(KeyRole.OWNER, entries[0].role)
            assertEquals(KeyFormFactor.IOS_DEVICE, entries[0].formFactor)
            assertEquals(KeyRole.DRIVER, entries[1].role)
            assertNull(entries[1].publicKey)
            assertEquals(
                listOf("0a020805", "0a0408062000", "0a0408062002"),
                h.fake.received.takeLast(3).map { hex(it.protobuf_message_as_bytes) },
            )
        }

    @Test
    fun listKeysReturnsTheFirstSlotFailure() =
        // 설계 구체화 12: Go는 실패 슬롯을 출력하고 건너뛴다 — 우리는 부분 목록 대신 실패를 돌려준다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(
                vcsec,
                reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 2, slotMask = 0b11))),
                listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_UNKNOWN)),
            )
            val failure = assertIs<VehicleResult.Failure>(VehicleSession(h.dispatcher).info.listKeys())
            assertEquals(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_UNKNOWN), failure.error)
        }

    @Test
    fun listKeysSharesOneTimeoutAcrossAllRequests() =
        // Go list-keys: ctx 하나가 KeySummary + 모든 KeyInfoBySlot을 덮는다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(vcsec, reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 3, slotMask = 0b111))))
            h.fake.dropNextReplies(100)
            val start = testTimeSource.markNow()
            val result = VehicleSession(h.dispatcher).info.listKeys(timeout = 3.seconds)
            assertIs<VehicleError.Timeout>(assertIs<VehicleResult.Uncertain>(result).error)
            assertEquals(3.seconds, start.elapsedNow())
        }

    @Test
    fun concurrentInfoQueriesAreSerialized() =
        // Review Focus 5 / FR-049: 한 연결의 VCSEC 요청은 한 번에 하나만 차량에 간다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val session = VehicleSession(h.dispatcher)
            h.fake.sleep(setOf(vcsec))
            val first = async { session.info.vehicleStatus(timeout = 2.seconds) }
            val second = async { session.info.keySummary(timeout = 2.seconds) }
            runCurrent()
            assertEquals(1, h.fake.received.count { it.to_destination?.domain == vcsec })
            first.await()
            second.await()
            assertEquals(2, h.fake.received.count { it.to_destination?.domain == vcsec })
        }
}
```

`listKeysSharesOneTimeoutAcrossAllRequests`: `dropNextReplies`가 요약 응답까지 지우지 않게, 대본 응답이 먼저 소비되는지 `FakeVehicle.dropNextReplies`의 의미(M2 `FakeVehicle.kt:133`)를 확인하고, 필요하면 `h.fake.sleep(setOf(vcsec))`를 요약 응답을 받은 **뒤**에 거는 방식으로 바꾼다 — 단언(전체가 정확히 3초에 끝남)은 그대로다. 테스트 이름의 값 객체 타입은 `io.github.smallmiro.teslable.model.*`와 Wire 이름이 겹치므로 완전한 이름 또는 import 별칭으로 쓴다.

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :application:jvmTest --tests '*VehicleInfoQueriesTest*' --console=plain`
Expected: 컴파일 실패

- [ ] **Step 4: 값 객체 구현 (`:domain`)**

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/VehicleStatus.kt
package io.github.smallmiro.teslable.model

/** 차량 잠금 상태(`VCSEC.VehicleLockState_E`). 모르는 값은 [UNKNOWN]. */
public enum class LockState {
    /** 열림. */
    UNLOCKED,

    /** 잠김. */
    LOCKED,

    /** 내부 잠금. */
    INTERNAL_LOCKED,

    /** 일부만 열림. */
    SELECTIVE_UNLOCKED,

    /** 알 수 없음(더 새 펌웨어). */
    UNKNOWN,
}

/** 문·트렁크·충전구 상태(`VCSEC.ClosureState_E`). 모르는 값은 [UNKNOWN]. */
public enum class ClosureState {
    /** 닫힘. */
    CLOSED,

    /** 열림. */
    OPEN,

    /** 반쯤 열림. */
    AJAR,

    /** 알 수 없음(차량 보고 또는 모르는 값). */
    UNKNOWN,

    /** 래치 해제 실패. */
    FAILED_UNLATCH,

    /** 여는 중. */
    OPENING,

    /** 닫는 중. */
    CLOSING,
}

/** 차량 수면 상태. */
public enum class SleepStatus {
    /** 알 수 없음. */
    UNKNOWN,

    /** 깨어 있음. */
    AWAKE,

    /** 잠듦. */
    ASLEEP,
}

/** 차 안 사용자 존재. */
public enum class UserPresence {
    /** 알 수 없음. */
    UNKNOWN,

    /** 없음. */
    NOT_PRESENT,

    /** 있음. */
    PRESENT,
}

/** 개폐부 8곳(`VCSEC.ClosureStatuses`). */
public data class Closures(
    /** 운전석 앞문. */
    val frontDriverDoor: ClosureState,
    /** 조수석 앞문. */
    val frontPassengerDoor: ClosureState,
    /** 운전석 뒷문. */
    val rearDriverDoor: ClosureState,
    /** 조수석 뒷문. */
    val rearPassengerDoor: ClosureState,
    /** 트렁크. */
    val rearTrunk: ClosureState,
    /** 프렁크. */
    val frontTrunk: ClosureState,
    /** 충전구. */
    val chargePort: ClosureState,
    /** 톤노 커버(사이버트럭). */
    val tonneau: ClosureState,
)

/** `vehicleStatus()` 결과(FR-031, SDD §7.3). 인증·깨우기 없이 읽는다. */
public data class VehicleStatus(
    /** 잠금 상태. */
    val lockState: LockState,
    /** 개폐부. 차량이 보내지 않으면 null. */
    val closures: Closures?,
    /** 수면 상태. */
    val sleepStatus: SleepStatus,
    /** 사용자 존재. */
    val userPresence: UserPresence,
    /** 톤노 열린 비율(%). 보고하지 않으면 null. */
    val tonneauPercentOpen: Int?,
)
```

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/Whitelist.kt
package io.github.smallmiro.teslable.model

/** 키 역할(`Keys.Role`). 모르는 값은 [UNKNOWN]. */
public enum class KeyRole {
    /** 없음. */
    NONE,

    /** 서비스. */
    SERVICE,

    /** 소유자. */
    OWNER,

    /** 운전자. */
    DRIVER,

    /** 플릿 매니저. */
    FLEET_MANAGER,

    /** 차량 모니터. */
    VEHICLE_MONITOR,

    /** 충전 매니저. */
    CHARGING_MANAGER,

    /** 게스트. */
    GUEST,

    /** 알 수 없음(더 새 펌웨어). */
    UNKNOWN,
}

/** 키 형태(`VCSEC.KeyFormFactor`). */
public enum class KeyFormFactor {
    /** 알 수 없음. */
    UNKNOWN,

    /** NFC 키카드. */
    NFC_CARD,

    /** iOS 기기. */
    IOS_DEVICE,

    /** Android 기기. */
    ANDROID_DEVICE,

    /** 클라우드 키. */
    CLOUD_KEY,
}

/** 화이트리스트 요약(FR-032, `VCSEC.WhitelistInfo`). */
public data class WhitelistSummary(
    /** 등록된 키 수. */
    val numberOfEntries: Int,
    /** 슬롯 비트마스크(비트 i = 슬롯 i 사용 중). */
    val slotMask: UInt,
) {
    /** 사용 중인 슬롯 번호(오름차순). */
    val occupiedSlots: List<Int> get() = (0 until UInt.SIZE_BITS).filter { (slotMask shr it) and 1u == 1u }
}

/** 화이트리스트 항목 하나(FR-025, `VCSEC.WhitelistEntryInfo`). */
public data class WhitelistEntry(
    /** 슬롯 번호. */
    val slot: Int,
    /** 공개키(65바이트). 차량이 보내지 않거나 형식이 틀리면 null. */
    val publicKey: PublicKeyBytes?,
    /** 공개키 SHA-1(hex). 보내지 않으면 null. */
    val keyIdSha1Hex: String?,
    /** 역할. */
    val role: KeyRole,
    /** 형태. */
    val formFactor: KeyFormFactor,
)
```

- [ ] **Step 5: 조회 구현 (`:application`)**

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecInfoMapping.kt
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.keys.Role
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.KeyFormFactor as WireFormFactor
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.ClosureState
import io.github.smallmiro.teslable.model.Closures
import io.github.smallmiro.teslable.model.KeyFormFactor
import io.github.smallmiro.teslable.model.KeyRole
import io.github.smallmiro.teslable.model.LockState
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.SleepStatus
import io.github.smallmiro.teslable.model.UserPresence
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.protocol.unknownVarint

/** Wire VCSEC 응답 → 공개 값 객체(SDD §7.3). 모르는 enum 값은 `unknownFields`로 찾아 `UNKNOWN`으로(Wire 기본값으로 보이지 않게). */
@OptIn(InternalTeslableApi::class)
internal object VcsecInfoMapping {
    private const val LOCK_STATE_TAG = 2 // vcsec.proto VehicleStatus.vehicleLockState
    private const val KEY_ROLE_TAG = 7 // WhitelistEntryInfo.keyRole
    private const val FORM_FACTOR_TAG = 1 // KeyMetadata.keyFormFactor

    fun status(wire: WireVehicleStatus): VehicleStatus =
        VehicleStatus(
            lockState = if (wire.unknownFields.unknownVarint(LOCK_STATE_TAG) != null) LockState.UNKNOWN else lock(wire.vehicleLockState),
            closures = wire.closureStatuses?.let(::closures),
            sleepStatus =
                when (wire.vehicleSleepStatus) {
                    VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE -> SleepStatus.AWAKE
                    VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP -> SleepStatus.ASLEEP
                    else -> SleepStatus.UNKNOWN
                },
            userPresence =
                when (wire.userPresence) {
                    UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT -> UserPresence.NOT_PRESENT
                    UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT -> UserPresence.PRESENT
                    else -> UserPresence.UNKNOWN
                },
            tonneauPercentOpen = wire.detailedClosureStatus?.tonneauPercentOpen,
        )

    private fun lock(state: VehicleLockState_E): LockState =
        when (state) {
            VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED -> LockState.UNLOCKED
            VehicleLockState_E.VEHICLELOCKSTATE_LOCKED -> LockState.LOCKED
            VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED -> LockState.INTERNAL_LOCKED
            VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED -> LockState.SELECTIVE_UNLOCKED
        }

    private fun closures(wire: ClosureStatuses): Closures {
        fun state(
            tag: Int,
            value: ClosureState_E,
        ): ClosureState = if (wire.unknownFields.unknownVarint(tag) != null) ClosureState.UNKNOWN else closure(value)
        return Closures(
            frontDriverDoor = state(1, wire.frontDriverDoor),
            frontPassengerDoor = state(2, wire.frontPassengerDoor),
            rearDriverDoor = state(3, wire.rearDriverDoor),
            rearPassengerDoor = state(4, wire.rearPassengerDoor),
            rearTrunk = state(5, wire.rearTrunk),
            frontTrunk = state(6, wire.frontTrunk),
            chargePort = state(7, wire.chargePort),
            tonneau = state(8, wire.tonneau),
        )
    }

    private fun closure(value: ClosureState_E): ClosureState =
        when (value) {
            ClosureState_E.CLOSURESTATE_CLOSED -> ClosureState.CLOSED
            ClosureState_E.CLOSURESTATE_OPEN -> ClosureState.OPEN
            ClosureState_E.CLOSURESTATE_AJAR -> ClosureState.AJAR
            ClosureState_E.CLOSURESTATE_UNKNOWN -> ClosureState.UNKNOWN
            ClosureState_E.CLOSURESTATE_FAILED_UNLATCH -> ClosureState.FAILED_UNLATCH
            ClosureState_E.CLOSURESTATE_OPENING -> ClosureState.OPENING
            ClosureState_E.CLOSURESTATE_CLOSING -> ClosureState.CLOSING
        }

    fun summary(wire: WhitelistInfo): WhitelistSummary = WhitelistSummary(wire.numberOfEntries, wire.slotMask.toUInt())

    fun entry(wire: WhitelistEntryInfo): WhitelistEntry =
        WhitelistEntry(
            slot = wire.slot,
            publicKey = wire.publicKey?.PublicKeyRaw?.toByteArray()?.let { runCatching { PublicKeyBytes(it) }.getOrNull() },
            keyIdSha1Hex = wire.keyId?.publicKeySHA1?.takeIf { it.size > 0 }?.hex(),
            role = if (wire.unknownFields.unknownVarint(KEY_ROLE_TAG) != null) KeyRole.UNKNOWN else role(wire.keyRole),
            formFactor =
                wire.metadataForKey?.let { meta ->
                    if (meta.unknownFields.unknownVarint(FORM_FACTOR_TAG) != null) KeyFormFactor.UNKNOWN else formFactor(meta.keyFormFactor)
                } ?: KeyFormFactor.UNKNOWN,
        )

    private fun role(role: Role): KeyRole =
        when (role) {
            Role.ROLE_NONE -> KeyRole.NONE
            Role.ROLE_SERVICE -> KeyRole.SERVICE
            Role.ROLE_OWNER -> KeyRole.OWNER
            Role.ROLE_DRIVER -> KeyRole.DRIVER
            Role.ROLE_FM -> KeyRole.FLEET_MANAGER
            Role.ROLE_VEHICLE_MONITOR -> KeyRole.VEHICLE_MONITOR
            Role.ROLE_CHARGING_MANAGER -> KeyRole.CHARGING_MANAGER
            Role.ROLE_GUEST -> KeyRole.GUEST
        }

    private fun formFactor(value: WireFormFactor): KeyFormFactor =
        when (value) {
            WireFormFactor.KEY_FORM_FACTOR_UNKNOWN -> KeyFormFactor.UNKNOWN
            WireFormFactor.KEY_FORM_FACTOR_NFC_CARD -> KeyFormFactor.NFC_CARD
            WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE -> KeyFormFactor.IOS_DEVICE
            WireFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE -> KeyFormFactor.ANDROID_DEVICE
            WireFormFactor.KEY_FORM_FACTOR_CLOUD_KEY -> KeyFormFactor.CLOUD_KEY
        }
}
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VehicleInfoQueries.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0)
// getVCSECInfo; state.go BodyControllerState; security.go KeySummary, KeyInfoBySlot; cmd/tesla-control/commands.go list-keys
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequest
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.UnsignedMessage
import io.github.smallmiro.teslable.application.vehicle.CommandTimeouts
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.port.AuthMethod
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 인증 없는 VCSEC 조회(Go `getVCSECInfo` 계열, FR-025/FR-031/FR-032). [VcsecCommands]를 통해 연결당 직렬화(FR-049)를 공유한다.
 * 응답에 기대한 필드가 없으면 `BadResponse("missing …")`(Go는 nil, nil — 설계 구체화 12). [listKeys]는 시간 예산 하나를 나눠 쓴다.
 */
public class VehicleInfoQueries internal constructor(
    private val vcsec: VcsecCommands,
    private val timeouts: CommandTimeouts,
    private val timeSource: TimeSource,
) {
    /** Go `BodyControllerState`. */
    public suspend fun vehicleStatus(timeout: Duration = timeouts.commandTimeout): VehicleResult<VehicleStatus> =
        query(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS, null, timeout, "vehicleStatus") { message ->
            message.vehicleStatus?.let(VcsecInfoMapping::status)
        }

    /** Go `KeySummary`. */
    public suspend fun keySummary(timeout: Duration = timeouts.commandTimeout): VehicleResult<WhitelistSummary> =
        query(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO, null, timeout, "whitelistInfo") { message ->
            message.whitelistInfo?.let(VcsecInfoMapping::summary)
        }

    /** Go `KeyInfoBySlot`. */
    public suspend fun keyInfoBySlot(
        slot: Int,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<WhitelistEntry> =
        query(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO, slot, timeout, "whitelistEntryInfo") { message ->
            message.whitelistEntryInfo?.let(VcsecInfoMapping::entry)
        }

    /** Go `list-keys`: [keySummary] 후 사용 중인 슬롯마다 [keyInfoBySlot]. 첫 실패를 돌려준다(설계 구체화 12). [timeout]은 전체 예산. */
    public suspend fun listKeys(timeout: Duration = timeouts.commandTimeout): VehicleResult<List<WhitelistEntry>> {
        val deadline = timeSource.markNow() + timeout
        fun remaining(): Duration = -deadline.elapsedNow()
        val summary =
            when (val result = keySummary(remaining())) {
                is VehicleResult.Success -> result.value
                is VehicleResult.Failure -> return result
                is VehicleResult.Uncertain -> return result
            }
        val entries = ArrayList<WhitelistEntry>(summary.occupiedSlots.size)
        for (slot in summary.occupiedSlots) {
            val left = remaining()
            if (left <= Duration.ZERO) return VehicleResult.Failure(VehicleError.Timeout(afterSend = false))
            when (val result = keyInfoBySlot(slot, left)) {
                is VehicleResult.Success -> entries += result.value
                is VehicleResult.Failure -> return result
                is VehicleResult.Uncertain -> return result
            }
        }
        return VehicleResult.Success(entries)
    }

    private suspend fun <T> query(
        type: InformationRequestType,
        slot: Int?,
        timeout: Duration,
        field: String,
        extract: (FromVCSECMessage) -> T?,
    ): VehicleResult<T> =
        when (val result = vcsec.execute(informationRequest(type, slot), AuthMethod.NONE, VcsecResponses.FIRST_MESSAGE, timeout = timeout)) {
            is VehicleResult.Success -> extract(result.value)?.let { VehicleResult.Success(it) } ?: VehicleResult.Failure(VehicleError.BadResponse("missing $field"))
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }

    /** 조립. */
    public companion object {
        /** Go `getVCSECInfo`의 페이로드: `UnsignedMessage{InformationRequest{type, slot?}}`. */
        public fun informationRequest(
            type: InformationRequestType,
            slot: Int? = null,
        ): ByteArray = UnsignedMessage(VCSEC_InformationRequest = InformationRequest(informationRequestType = type, slot = slot)).encode()
    }
}
```

`VehicleSession`에 추가(생성자 안, `vcsec` 아래):

```kotlin
    /** 인증 없는 VCSEC 조회(FR-025, FR-031, FR-032). [vcsec]의 직렬화를 공유한다. */
    public val info: VehicleInfoQueries = VehicleInfoQueries(vcsec, timeouts, dispatcher.clock)
```

`tools/ci/ported-files.txt`에 `VehicleInfoQueries.kt`를 추가한다.

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :application:jvmTest :application:iosSimulatorArm64Test :domain:jvmTest --console=plain`
Expected: PASS
Run: `./gradlew :domain:apiDump` 그다음 `./gradlew :application:apiDump`
Expected: 값 객체 9종, `VehicleInfoQueries`, `VehicleSession.info` 추가

- [ ] **Step 7: SDD 기록·커밋 → PR(`m3/vcsec-info`) → verify.sh → 리뷰 → 병합**

`{{SDD_FILE}}` §12에 추가: "인증 없는 VCSEC 조회에서 응답에 기대한 필드가 없으면 `BadResponse("missing …")`(Go `BodyControllerState`/`KeySummary`/`KeyInfoBySlot`은 nil, nil). `listKeys`는 첫 슬롯 실패를 돌려준다(Go `list-keys`는 실패 슬롯을 출력하고 계속한다). Wire가 모르는 잠금·개폐·역할·형태 값은 `UNKNOWN`(Wire 기본값으로 보이지 않게 `unknownFields`로 판별)."

```bash
git add domain application tools/ci/ported-files.txt docs/sdd/SDD.md
git commit -m "feat(application): unauthenticated VCSEC info queries (vehicleStatus, keySummary, listKeys) and public value objects

Refs: FR-025, FR-031, FR-032, FR-049
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 11: M2 인계 정리 — 일회용 `VehicleSession`, `SessionState` 영구 닫힘, VCSEC 우회 차단, `SafeLogger`, `FakeVehicle` 도메인별 차량 키

참고: `{{HANDOFF_DIR}}2026-09-27-m2-dispatcher.md` "막힌 점"(`storedOnDisconnect`, `SessionState` 영구 닫힘, `send`의 VCSEC 우회, 로거 예외, `FakeVehicle` 같은 키, `NonCancellable` 테스트); 설계 구체화 13, 15. Go 원본: `internal/dispatcher/dispatcher_test.go:126`(도메인마다 다른 차량 키), `pkg/vehicle/vehicle.go:116-118, 169-179`(`Connect`, `Disconnect`).

**Files:**
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionState.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSession.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/SendWithRetry.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/logging/SafeLogger.kt`
- Modify: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicle.kt` (도메인별 키)
- Modify: `application/src/commonTest/…/dispatcher/SessionStateTest.kt`, `…/vehicle/VehicleSessionTest.kt`, `…/vehicle/SendWithRetryTest.kt`
- Create: `application/src/commonTest/…/logging/SafeLoggerTest.kt`, `…/dispatcher/DistinctDomainKeysTest.kt`
- Modify: `application/api/*`, `testing/api/*`(있으면)

**Interfaces:**
- Consumes: M2 `SessionState`, `VehicleSession`, `SendWithRetry`, `FakeVehicle`, `TestCrypto.goKnownVerifierKey()`.
- Produces (Task 12, 13):
  - `SessionState.close()`가 영구적: 이후 `processHello`/`loadFromCache`는 `SignerResult.Fault(MESSAGEFAULT_ERROR_INTERNAL, "session closed")`, `isClosed: Boolean`
  - `VehicleSession.connect()` 멱등, `disconnect()` 뒤 `connect()`는 `IllegalStateException("VehicleSession cannot be reused after disconnect(); connect again through TeslaBle")`
  - `SendWithRetry.send(DOMAIN_VEHICLE_SECURITY, …)` → `Failure(InvalidArgument("VCSEC commands must go through VcsecCommands"))`
  - `public class SafeLogger(delegate: TeslaLogger) : TeslaLogger` (`:application.logging`)
  - `FakeVehicle(…, infotainmentKey: EcdhPrivateKey = vehicleKey)` — 생성자 마지막 인자 추가(기존 호출은 그대로 컴파일)

- [ ] **Step 1: 실패 테스트 작성**

`SessionStateTest`에 추가:

```kotlin
    @Test
    fun closeIsFinalAndRejectsLaterHelloAndCacheLoad() =
        // M2 인계: close() 뒤 수신 코루틴이 좁은 창에서 키를 다시 만들지 않는다(설계 구체화 13)
        runTest {
            val h = dispatcherHarness()
            val state = assertNotNull(h.dispatcher.session(Domain.DOMAIN_VEHICLE_SECURITY))
            val hello = h.fake.verifier(Domain.DOMAIN_VEHICLE_SECURITY) // 아래 signedHello 헬퍼가 쓰는 검증자
            state.close()
            assertTrue(state.isClosed)
            val fault = assertIs<SignerResult.Fault>(state.processHello(challenge, encodedInfo, tag))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, fault.fault)
            assertIs<SignerResult.Fault>(state.loadFromCache(encodedInfo, Duration.ZERO))
            assertFalse(state.isReady)
            assertNull(state.export())
        }
```

`challenge`/`encodedInfo`/`tag`는 `SessionStateTest`의 기존 정상 hello 헬퍼(M2 Task 5 `acceptsFirstHello…` 계열)가 만드는 값을 그대로 쓴다 — 같은 파일의 헬퍼를 재사용하고, 위의 `hello` 줄은 헬퍼가 필요로 하지 않으면 지운다.

`VehicleSessionTest`에 추가하고, 기존 `connectRestoresCacheAndDisconnectStoresIt`의 마지막 줄 `session2.send.send(vcsec, …)`를 `session2.send.send(infotainment, "x".encodeToByteArray(), AuthMethod.GCM)`으로 바꾼다(VCSEC는 이제 `vcsec`로만):

```kotlin
    @Test
    fun connectIsIdempotent() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            val session = VehicleSession(dispatcherHarness(fake = fake, start = false).dispatcher, cacheSync = sync)
            session.connect()
            session.connect()
            assertEquals(1, cache.loads)
        }

    @Test
    fun connectAfterDisconnectIsRejected() =
        // Review Focus 4, 설계 구체화 13: 재연결은 TeslaBle.connect()가 새 객체로
        runTest {
            val session = VehicleSession(dispatcherHarness(start = false).dispatcher)
            session.connect()
            session.disconnect()
            assertFailsWith<IllegalStateException> { session.connect() }
        }

    @Test
    fun disconnectClosesTheDispatcherEvenIfTheCallerIsAlreadyCancelled() =
        // M2 인계: disconnect()의 바깥 NonCancellable을 직접 고정
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            session.connect()
            val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.cancel()
                session.disconnect()
            }
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(h.dispatcher.isListening)
            assertEquals(TransportState.Disconnected(reason = null), h.transport.state.value)
        }
```

`SendWithRetryTest`에 추가:

```kotlin
    @Test
    fun rejectsVcsecSoSerializationCannotBeBypassed() =
        // M2 최종 리뷰 Minor 9: VCSEC 직렬화(FR-049)는 VcsecCommands로만 보장된다
        runTest {
            val h = dispatcherHarness()
            val result = SendWithRetry(h.dispatcher).send(Domain.DOMAIN_VEHICLE_SECURITY, byteArrayOf(1), AuthMethod.NONE)
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(result).error)
            assertTrue(h.transport.sent.isEmpty())
        }
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/logging/SafeLoggerTest.kt
package io.github.smallmiro.teslable.application.logging

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlin.test.Test
import kotlin.test.assertEquals

class SafeLoggerTest {
    @Test
    fun swallowsExceptionsFromTheUserLoggerAndFromTheMessageLambda() {
        // M2 인계: 사용자가 준 로거가 던지면 수신 코루틴이 죽는다
        var calls = 0
        val throwing = TeslaLogger { _, _, message ->
            calls++
            message()
            error("app logger bug")
        }
        val safe = SafeLogger(throwing)
        safe.log(LogLevel.INFO, "t") { "ok" }
        safe.log(LogLevel.INFO, "t") { error("message bug") }
        assertEquals(2, calls)
    }

    @Test
    fun passesThroughLevelTagAndMessage() {
        val seen = mutableListOf<String>()
        SafeLogger { level, tag, message -> seen += "$level/$tag/${message()}" }.log(LogLevel.WARN, "ble") { "x" }
        assertEquals(listOf("WARN/ble/x"), seen)
    }
}
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/DistinctDomainKeysTest.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DistinctDomainKeysTest {
    @Test
    fun handshakesBothDomainsWhenEachDomainHasItsOwnVehicleKey() =
        // dispatcher_test.go:126 — Go 테스트는 도메인마다 다른 차량 키를 쓴다(M2 인계: 같은 키면 도메인 간 세션정보 혼동이 HMAC을 통과할 수 있다)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource, infotainmentKey = TestCrypto.goKnownVerifierKey())
            val h = dispatcherHarness(fake = fake)
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            assertNotEquals(fake.verifier(Domain.DOMAIN_VEHICLE_SECURITY).publicKey, fake.verifier(Domain.DOMAIN_INFOTAINMENT).publicKey)
            assertIs<VehicleResult.Success<*>>(session.send.send(Domain.DOMAIN_INFOTAINMENT, "x".encodeToByteArray(), AuthMethod.GCM))
        }
}
```

`TestVerifier`의 차량 공개키 접근자 이름(`publicKey` 등)은 `testing/…/TestVerifier.kt`를 확인해 맞춘다. 공개 접근자가 없으면 `FakeVehicle`에 `vehiclePublicKey(domain): PublicKeyBytes`를 추가하고 그것으로 비교한다.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :application:jvmTest --console=plain`
Expected: 컴파일 실패(`isClosed`, `SafeLogger`, `infotainmentKey` 없음)

- [ ] **Step 3: 구현**

`SessionState`:

```kotlin
    private var closed = false

    /** [close]가 불렸는지. 닫힌 세션은 되살리지 않는다(설계 구체화 13). */
    public val isClosed: Boolean get() = closed
```

`processHello`와 `loadFromCache`의 `mutex.withLock {` 바로 안 첫 줄:

```kotlin
            if (closed) return@withLock SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, "session closed")
```

`close()`:

```kotlin
    /** 세션 키를 0으로 덮고 영구히 닫는다. 이후 [authorize]는 `INTERNAL` fault, [processHello]·[loadFromCache]도 거부한다. */
    public suspend fun close(): Unit =
        mutex.withLock {
            closed = true
            signer?.close()
            signer = null
        }
```

KDoc의 "영구적이지 않다 … (단일 사용 강제는 M3 항목)" 문장을 지운다.

`VehicleSession`: `hasLoadedCache` 옆에 `private var disconnected = false`와 `private val connectMutex = Mutex()`를 두고:

```kotlin
    /** Go `NewVehicle`(캐시 복원) + `Connect`(수신 시작). 멱등. [disconnect] 뒤에는 다시 쓸 수 없다(설계 구체화 13). */
    public suspend fun connect(): Set<Domain> =
        connectMutex.withLock {
            check(!disconnected) { "VehicleSession cannot be reused after disconnect(); connect again through TeslaBle" }
            if (hasLoadedCache) return@withLock restoredDomains
            val restored = cacheSync?.load(dispatcher) ?: emptySet()
            restoredDomains = restored
            hasLoadedCache = true
            dispatcher.start()
            restored
        }
```

(`private var restoredDomains: Set<Domain> = emptySet()` 추가.) `disconnect()`는 `finally`의 `NonCancellable` 안에서 `disconnected = true`를 놓는다 — 호출자가 이미 취소된 경우에도 반드시 표시되게 하려는 것이다.

```kotlin
    public suspend fun disconnect() {
        try {
            storeOnceForDisconnect()
        } finally {
            withContext(NonCancellable) {
                connectMutex.withLock { disconnected = true }
                dispatcher.close()
            }
        }
    }
```

`SendWithRetry.send` 첫 줄:

```kotlin
        if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
            return VehicleResult.Failure(VehicleError.InvalidArgument("VCSEC commands must go through VcsecCommands"))
        }
```

KDoc에 "VCSEC는 거부한다(FR-049 직렬화 우회 차단, 설계 구체화 13)"를 덧붙인다.

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/logging/SafeLogger.kt
package io.github.smallmiro.teslable.application.logging

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger

/**
 * 사용자 로거를 감싸 로거·메시지 람다가 던진 예외를 삼킨다(M2 인계). 로그 호출은 수신 코루틴 안에서도 일어나므로
 * 앱 로거의 버그가 BLE 수신을 멈추게 해서는 안 된다. `log`는 suspend가 아니라 취소 예외가 지나갈 일은 없다.
 */
public class SafeLogger(
    private val delegate: TeslaLogger,
) : TeslaLogger {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // 로깅 실패는 어떤 경우에도 호출자에게 전파하지 않는다
    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        try {
            delegate.log(level, tag, message)
        } catch (e: Exception) {
            // 의도적으로 무시
        }
    }
}
```

`FakeVehicle`: 생성자 마지막에 `private val infotainmentKey: EcdhPrivateKey = vehicleKey`를 추가하고(KDoc: "Infotainment 도메인의 차량 키. Go 테스트처럼 도메인마다 다르게 둘 수 있다"), 도메인 검증자를 만드는 곳(`verifierFor`)에서 `if (domain == Domain.DOMAIN_INFOTAINMENT) infotainmentKey else vehicleKey`를 쓴다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :application:jvmTest :application:iosSimulatorArm64Test :testing:jvmTest --console=plain`
Expected: PASS(기존 M2 테스트 포함)
Run: `./gradlew :application:apiDump`
Expected: `SafeLogger`, `SessionState.isClosed` 추가

- [ ] **Step 5: 커밋 → PR(`m3/m2-carry-hardening`) → verify.sh → 리뷰 → 병합**

테스트 인프라(`FakeVehicle` 키 인자 + `DistinctDomainKeysTest`)는 `test:` 커밋, 나머지는 `feat:`/`fix:` 커밋으로 나눈다.

```bash
git add testing application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/DistinctDomainKeysTest.kt
git commit -m "test(testing): FakeVehicle can use a distinct vehicle key per domain like dispatcher_test.go

Refs: NFR-003
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
git add application
git commit -m "fix(application): single-use VehicleSession, final SessionState close, VCSEC-only via VcsecCommands, SafeLogger

Refs: FR-005, FR-049, FR-112
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 12: 세션 이벤트 — 세션정보 갱신 시 캐시 저장, 오래된 캐시(차량 키 교체) 감지·삭제

참고: 설계 구체화 14; M2 판정 P7(세션정보 갱신 시 저장은 M3), 사용자 답 (a)(2026-09-27); `{{SDD_FILE}}` §3.4, §5(수신 코루틴에서 suspend 대기 금지), §7.1. M2 테스트 `SessionCacheSyncTest.staleCachedVehicleKeyLeavesSessionStuckLikeGo`(이 Task가 감지를 덧붙인다), `storingAfterCloseWipesThePreviouslySavedCacheHazard`. Go 원본: `internal/dispatcher/dispatcher.go`(`checkForSessionUpdate`), `pkg/vehicle/vehicle.go`(`UpdateCachedSessions`) — Go에는 감지·삭제가 없다(의도한 차이).

**Files:**
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionEvent.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionState.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/Dispatcher.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/cache/SessionCacheSync.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSession.kt`
- Create: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/cache/SessionEventsTest.kt`
- Modify: `application/api/*`, `{{SDD_FILE}}` §3.4, §7.1, §12

**Interfaces:**
- Consumes: Task 11 `VehicleSession`(일회용), `SessionState`, `RecordingSessionCache.stores`, `FakeVehicle(vehicleKey = …)`.
- Produces (Task 13):
  - `sealed interface SessionEvent { data class Updated(val domain: Domain); data class StaleCache(val domain: Domain) }`
  - `Dispatcher.sessionEvents: SharedFlow<SessionEvent>`
  - `SessionState.isUnconfirmedCacheRestore: Boolean`
  - `SessionCacheSync.markStale(domain: Domain)` — 이후 모든 `store`가 그 도메인을 뺀다
  - `VehicleSession(dispatcher, timeouts, cacheSync, eventScope: CoroutineScope? = null)` — `eventScope`가 있으면 `connect()`가 이벤트 수집을 시작하고 `disconnect()`가 캐시 저장 **전에** 멈춘다

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/cache/SessionEventsTest.kt
package io.github.smallmiro.teslable.application.cache

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.PendingRequest
import io.github.smallmiro.teslable.application.dispatcher.SessionEvent
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.testCommand
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingSessionCache
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
class SessionEventsTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)

    @Test
    fun successfulHelloEmitsUpdated() =
        runTest {
            val h = dispatcherHarness()
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSessions(setOf(vcsec, infotainment)))
            runCurrent()
            assertEquals(setOf<SessionEvent>(SessionEvent.Updated(vcsec), SessionEvent.Updated(infotainment)), events.toSet())
        }

    /** M2 `staleCachedVehicleKeyLeavesSessionStuckLikeGo`와 같은 상황: 다른 차량 키로 만든 캐시를 이 차량에 복원한다. */
    private suspend fun TestScope.restoreStaleCache(cache: RecordingSessionCache): SessionCacheSync {
        val otherCar = FakeVehicle(vehicleKey = TestCrypto.goKnownVerifierKey(), timeSource = testTimeSource)
        val first = dispatcherHarness(fake = otherCar)
        assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSessions(setOf(vcsec, infotainment)))
        val sync = SessionCacheSync(cache, otherCar.vin, keyId)
        sync.store(first.dispatcher)
        return sync
    }

    @Test
    fun failedHelloOnAnUnconfirmedCachedSessionEmitsStaleCacheOnce() =
        // 설계 구체화 14, 사용자 답 (a): 감지 = 캐시 복원 후 미확인 세션의 세션정보 갱신 실패(태그 불일치)
        runTest {
            val cache = RecordingSessionCache()
            val sync = restoreStaleCache(cache)
            val h = dispatcherHarness(fake = FakeVehicle(timeSource = testTimeSource), start = false)
            assertEquals(setOf(vcsec, infotainment), sync.load(h.dispatcher))
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            h.dispatcher.start()
            repeat(2) {
                val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).value
                pending.use { it.receive() }
            }
            runCurrent()
            assertEquals(listOf<SessionEvent>(SessionEvent.StaleCache(vcsec)), events)
            assertTrue(h.logger.contains("Cached session for DOMAIN_VEHICLE_SECURITY looks stale"))
        }

    @Test
    fun confirmedSessionNeverReportsStale() =
        // 정상 캐시: 첫 응답 복호화 성공으로 확인되면, 이후 세션정보 실패(예: 태그 손상)는 stale이 아니다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(infotainment))
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            sync.store(first.dispatcher)
            val h = dispatcherHarness(fake = fake, start = false)
            sync.load(h.dispatcher)
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            h.dispatcher.start()
            val ok = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(infotainment), AuthMethod.GCM)).value
            ok.use { it.receive() } // 복호화 성공 → 확인됨
            assertFalse(h.dispatcher.session(infotainment)?.isUnconfirmedCacheRestore ?: true)
            fake.corruptNextSessionInfoTag(infotainment)
            fake.attachSessionInfoOnce(infotainment)
            val bad = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(infotainment), AuthMethod.GCM)).value
            bad.use { it.receive() }
            runCurrent()
            assertTrue(events.none { it is SessionEvent.StaleCache })
        }

    @Test
    fun vehicleSessionStoresOnUpdateAndDropsTheStaleDomainForGood() =
        runTest {
            val cache = RecordingSessionCache()
            val sync = restoreStaleCache(cache)
            val fake = FakeVehicle(timeSource = testTimeSource)
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = backgroundScope)
            assertEquals(setOf(vcsec, infotainment), session.connect())
            val storesBefore = cache.stores.size
            session.vcsec.execute(byteArrayOf(1), AuthMethod.GCM, io.github.smallmiro.teslable.application.vcsec.VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            runCurrent()
            val afterStale = cache.stores.drop(storesBefore)
            assertTrue(afterStale.isNotEmpty())
            assertTrue(afterStale.last().entries.none { it.domain == vcsec })
            assertTrue(afterStale.last().entries.any { it.domain == infotainment })
            session.disconnect()
            assertTrue(cache.stores.last().entries.none { it.domain == vcsec }) // 해제 저장도 stale 도메인을 다시 넣지 않는다
        }

    @Test
    fun disconnectStopsEventStoresBeforeTheFinalStoreSoTheCacheIsNotWiped() =
        // 설계 구체화 14 + M2 storingAfterCloseWipesThePreviouslySavedCacheHazard
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = backgroundScope)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            runCurrent()
            session.disconnect()
            val storesAtDisconnect = cache.stores.size
            runCurrent()
            assertEquals(storesAtDisconnect, cache.stores.size)
            assertEquals(2, cache.stores.last().entries.size)
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :application:jvmTest --tests '*SessionEventsTest*' --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionEvent.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain

/** 디스패처 세션 사건(설계 구체화 14). 수신 코루틴이 `tryEmit`으로 낸다(suspend 없음, SDD §5). */
public sealed interface SessionEvent {
    /** 서명된 세션정보로 세션이 만들어지거나 갱신됨 → 캐시 저장 대상. */
    public data class Updated(
        /** 도메인. */
        public val domain: Domain,
    ) : SessionEvent

    /** 캐시에서 복원한 세션이 차량과 맞지 않음(차량 키 교체 등) → 그 도메인 캐시 삭제 대상. 세션당 한 번. */
    public data class StaleCache(
        /** 도메인. */
        public val domain: Domain,
    ) : SessionEvent
}
```

`SessionState`:

```kotlin
    @Volatile
    private var unconfirmedRestore = false

    /** 캐시에서 복원된 뒤 아직 차량 응답으로 확인되지 않았는지(복호화·세션정보 갱신 성공 전). */
    public val isUnconfirmedCacheRestore: Boolean get() = unconfirmedRestore

    /** 오래된 캐시로 보고했으므로 다시 보고하지 않게 한다. */
    internal fun markReportedStale() {
        unconfirmedRestore = false
    }
```

- `loadFromCache` 성공 분기에 `unconfirmedRestore = true`.
- `processHello` 성공(`result is SignerResult.Ok`)이면 `unconfirmedRestore = false`.
- `decrypt`를 `mutex.withLock { signer?.decrypt(message, requestHash)?.also { if (it is SignerResult.Ok) unconfirmedRestore = false } }`로 바꾼다.

`Dispatcher`:

```kotlin
        private val events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** 세션 사건(설계 구체화 14). 구독자가 없으면 버려진다 — `VehicleSession`이 `connect()`에서 구독을 먼저 시작한다. */
        public val sessionEvents: SharedFlow<SessionEvent> = events.asSharedFlow()
```

`checkForSessionUpdate`의 마지막 `when`을 바꾼다:

```kotlin
            val wasUnconfirmed = session.isUnconfirmedCacheRestore
            when (val result = session.processHello(message.request_uuid.toByteArray(), info.toByteArray(), tag.toByteArray())) {
                is SignerResult.Fault -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Session info error: ${result.fault.name}: ${result.detail}" }
                    if (wasUnconfirmed) {
                        session.markReportedStale()
                        logger.log(LogLevel.WARN, TAG) { "[$id] Cached session for $domain looks stale (vehicle key changed?); dropping it from the cache" }
                        events.tryEmit(SessionEvent.StaleCache(domain))
                    }
                }

                is SignerResult.Ok -> {
                    logger.log(LogLevel.INFO, TAG) { "[$id] Updated session info for $domain" }
                    events.tryEmit(SessionEvent.Updated(domain))
                }
            }
```

`companion object`에 `private const val EVENT_BUFFER = 16`. import: `kotlinx.coroutines.channels.BufferOverflow`, `kotlinx.coroutines.flow.MutableSharedFlow`, `SharedFlow`, `asSharedFlow`.

`SessionCacheSync`:

```kotlin
    private val staleDomains = mutableSetOf<Domain>()
    private val staleMutex = Mutex()

    /** 오래된 캐시로 보고된 도메인을 이후 모든 [store]에서 뺀다(사용자 답 a — 포트에 도메인 단위 삭제가 없으므로 나머지를 다시 저장). */
    public suspend fun markStale(domain: Domain) {
        staleMutex.withLock { staleDomains += domain }
    }

    public suspend fun store(dispatcher: Dispatcher) {
        val excluded = staleMutex.withLock { staleDomains.toSet() }
        cache.store(vin, keyId, dispatcher.exportSessions().filter { it.domain !in excluded })
    }
```

`VehicleSession`: 생성자에 `private val eventScope: CoroutineScope? = null`(마지막 인자)과 `private var eventJob: Job? = null`. `connect()`에서 `dispatcher.start()` **직전**:

```kotlin
            val sync = cacheSync
            if (eventScope != null && sync != null) {
                eventJob =
                    eventScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        dispatcher.sessionEvents.collect { event ->
                            when (event) {
                                is SessionEvent.Updated -> sync.store(dispatcher)
                                is SessionEvent.StaleCache -> {
                                    sync.markStale(event.domain)
                                    sync.store(dispatcher)
                                }
                            }
                        }
                    }
            }
```

`disconnect()`의 `try` 첫 줄에 `eventJob?.cancelAndJoin()`을 넣고, `finally`의 `NonCancellable` 블록에도 `eventJob?.cancel()`을 넣는다(취소된 호출자 경로). 이렇게 해야 마지막 해제 저장과 이벤트 저장이 겹치지 않고, `Dispatcher.close()` 뒤의 이벤트 저장이 캐시를 빈 목록으로 지우지 않는다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :application:jvmTest :application:iosSimulatorArm64Test --console=plain`
Expected: PASS(M2 `staleCachedVehicleKeyLeavesSessionStuckLikeGo` 포함 — 세션은 여전히 갇히고 이제 이벤트가 난다)
Run: `./gradlew :application:apiDump`

- [ ] **Step 5: SDD 기록·커밋 → PR(`m3/session-events`) → verify.sh → 리뷰 → 병합**

`{{SDD_FILE}}`:
- §3.4/§7.1 "저장 시점"을 "핸드셰이크 완료, `disconnect()`, **세션정보 갱신(`SessionEvent.Updated`, M3)**"으로 고치고 "세션 갱신 시 저장은 M2에 없음" 문장을 지운다.
- §7.1 "캐시된 차량 공개키가 …" 문단의 "감지 방법은 M3 계획에서 정한다"를 "감지: 캐시 복원 후 미확인 세션의 세션정보 갱신 실패 → `SessionEvent.StaleCache` → `SessionCacheSync.markStale` + 나머지 재저장"으로 바꾼다.
- §12에 추가: "오래된 캐시 감지·삭제는 Go에 없다(사용자 결정 2026-09-27). 세션 자체는 Go처럼 복구하지 않는다 — 다음 `connect()`가 새로 핸드셰이크한다."

```bash
git add application docs/sdd/SDD.md
git commit -m "feat(application): session events store the cache on session-info updates and drop stale cached domains

Refs: FR-017, FR-018, ADR-0007
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 13: `:sdk` 파사드 — `TeslaBle`, `TeslaBleConfig`, `Vehicle`/`DefaultVehicle`, `SessionStatus`, `PlatformContext`, 플랫폼 조립

참고: `{{SDD_FILE}}` §2.3, §3.1, §5(스코프), §8.4; 설계 구체화 1, 9, 13~15; ADR-0005(SKIE), ADR-0006, ADR-0010; FR-001~FR-005, FR-025, FR-031, FR-032. Go 원본: `pkg/vehicle/vehicle.go:77-99`(`NewVehicle`), `:116-118`(`Connect`), `:169-179`(`Disconnect`), `pkg/cli/config.go:365-386, 473-488`(CLI 조립 순서: 연결 → `NewVehicle`(캐시) → `Connect` → `StartSession`).

**Files:**
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/PlatformContext.kt` (expect)
- Create: `sdk/src/androidMain/kotlin/io/github/smallmiro/teslable/PlatformContext.android.kt`, `PlatformComponents.android.kt`
- Create: `sdk/src/iosMain/kotlin/io/github/smallmiro/teslable/PlatformContext.ios.kt`, `PlatformComponents.ios.kt`
- Create: `sdk/src/jvmMain/kotlin/io/github/smallmiro/teslable/PlatformContext.jvm.kt`, `PlatformComponents.jvm.kt`
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/PlatformComponents.kt` (expect + `Components`)
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/TeslaBleConfig.kt`
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/SessionStatus.kt`
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/Vehicle.kt`
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/DefaultVehicle.kt`
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/TeslaBle.kt`
- Create: `sdk/src/commonTest/kotlin/io/github/smallmiro/teslable/TeslaBleTest.kt`
- Modify: `sdk/build.gradle.kts` (detekt 어댑터 설정), `sdk/api/*`

**Interfaces:**
- Consumes: Task 2 `TransportFactory`/`ScanEvent`/`ConnectOptions`/`VehicleAdvertisement`/`VehicleDomain`/`VehicleKeyStore`/`VehicleKey`, Task 5 `kableTransportFactory`/`androidBleAvailability`/`appleBleAvailability`/`BleAvailability.ALWAYS`, Task 6~8 `inMemoryVehicleKeyStore`/`androidVehicleKeyStore`/`appleVehicleKeyStore`, Task 9 `androidSessionCache`/`appleSessionCache`, M2 `InMemorySessionCache`, Task 10 `VehicleSession.info`, Task 11 `SafeLogger`, Task 12 `VehicleSession(…, eventScope)`, `Dispatcher`, `SessionCacheSync`, `KeyId.of`, `LocalName.of`, `CommandTimeouts`; 테스트: `FakeTransportFactory`, `FakeVehicleKeyStore`, `FakeVehicle`, `RecordingSessionCache`, `RecordingLogger`.
- Produces (Task 14, 샘플·문서):

```kotlin
public expect class PlatformContext               // android: PlatformContext(context: Context), ios/jvm: PlatformContext()
public data class TeslaBleConfig(logger = NoOp, sessionCache: SessionCache? = null, commandTimeout = 5.s, commandLifetime = 5.s,
    handshakeTimeout = 20.s, connectTimeout = 30.s)
public sealed interface SessionStatus { data object None; data object Handshaking; data class Ready(val fromCache: Boolean) }
public interface Vehicle {
    val vin: Vin; val hasKey: Boolean; val connection: StateFlow<TransportState>; val sessions: StateFlow<Map<VehicleDomain, SessionStatus>>
    suspend fun startSession(domains: Set<VehicleDomain> = VehicleDomain.ALL, timeout: Duration? = null): VehicleResult<Unit>
    suspend fun disconnect()
    suspend fun vehicleStatus(timeout: Duration? = null): VehicleResult<VehicleStatus>
    suspend fun keySummary(timeout: Duration? = null): VehicleResult<WhitelistSummary>
    suspend fun listKeys(timeout: Duration? = null): VehicleResult<List<WhitelistEntry>>
}
public class TeslaBle internal constructor(…) {
    companion object { fun create(platform: PlatformContext, config: TeslaBleConfig = TeslaBleConfig()): TeslaBle }
    val keys: VehicleKeyStore
    fun scan(vin: Vin): Flow<ScanEvent>
    suspend fun connect(vin: Vin, key: VehicleKey?, target: VehicleAdvertisement? = null, options: ConnectOptions = ConnectOptions()): VehicleResult<Vehicle>
    fun close()
}
```

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// sdk/src/commonTest/kotlin/io/github/smallmiro/teslable/TeslaBleTest.kt
package io.github.smallmiro.teslable

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.WhitelistInfo
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.LockState
import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.TransportState
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.testing.FakeTransportFactory
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.FakeVehicleKeyStore
import io.github.smallmiro.teslable.testing.RecordingSessionCache
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TeslaBleTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    private class Rig(
        val fake: FakeVehicle,
        val factory: FakeTransportFactory,
        val cache: RecordingSessionCache,
        val keys: FakeVehicleKeyStore,
        val ble: TeslaBle,
    )

    private fun TestScope.rig(
        config: TeslaBleConfig = TeslaBleConfig(),
        fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource),
        cache: RecordingSessionCache = RecordingSessionCache(),
    ): Rig {
        val factory = FakeTransportFactory(fake)
        val keys = FakeVehicleKeyStore()
        val ble =
            TeslaBle(
                transports = factory,
                keys = keys,
                sessionCache = cache,
                crypto = TestCrypto.primitives,
                random = TestCrypto.random,
                config = config,
                timeSource = testTimeSource,
                vehicleScope = { CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])) },
            )
        return Rig(fake, factory, cache, keys, ble)
    }

    private suspend fun Rig.key(): VehicleKey = assertIs<VehicleResult.Success<VehicleKey>>(keys.getOrCreate()).value

    private suspend fun Rig.connected(withKey: Boolean = true): Vehicle =
        assertIs<VehicleResult.Success<Vehicle>>(ble.connect(fake.vin, if (withKey) key() else null)).value

    @Test
    fun scanUsesTheVinLocalName() =
        runTest {
            val r = rig()
            val found = assertIs<ScanEvent.Found>(r.ble.scan(r.fake.vin).toList().single())
            assertEquals(LocalName.of(r.fake.vin, TestCrypto.primitives), found.advertisement.localName)
        }

    @Test
    fun connectFailureIsAValueNotAnException() =
        runTest {
            val r = rig()
            r.factory.enqueueConnectFailure(VehicleError.TransportError.BluetoothOff)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.BluetoothOff), r.ble.connect(r.fake.vin, r.key()))
        }

    @Test
    fun handshakeUpdatesSessionsAndDisconnectStoresTheCacheUnderTheKeysId() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertTrue(vehicle.hasKey)
            assertEquals(TransportState.Connected, vehicle.connection.value)
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value)
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(timeout = 1.seconds))
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.Ready(fromCache = false) }, vehicle.sessions.value)
            vehicle.disconnect()
            val stored = r.cache.stores.last()
            assertEquals(KeyId.of(r.key().publicKey, TestCrypto.primitives), stored.keyId) // 설계 구체화 15: keyId는 파사드가 키에서 유도
            assertEquals(2, stored.entries.size)
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value)
        }

    @Test
    fun reconnectRestoresSessionsFromCacheWithoutHandshake() =
        runTest {
            val r = rig()
            val first = r.connected()
            first.startSession(timeout = 1.seconds)
            first.disconnect()
            val requestsBefore = r.fake.sessionInfoRequests
            val second = r.connected()
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.Ready(fromCache = true) }, second.sessions.value)
            assertIs<VehicleResult.Success<Unit>>(second.startSession(timeout = 1.seconds))
            assertEquals(requestsBefore, r.fake.sessionInfoRequests)
        }

    @Test
    fun vehicleStatusAndKeySummaryWorkWithoutAKey() =
        // FR-025, FR-031: 인증 없는 조회는 키 없이도
        runTest {
            val r = rig()
            r.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(FromVCSECMessage(vehicleStatus = WireVehicleStatus(vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED)))))
            r.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 1, slotMask = 1)))))
            val vehicle = r.connected(withKey = false)
            assertFalse(vehicle.hasKey)
            assertEquals(LockState.LOCKED, assertIs<VehicleResult.Success<io.github.smallmiro.teslable.model.VehicleStatus>>(vehicle.vehicleStatus()).value.lockState)
            assertEquals(listOf(0), assertIs<VehicleResult.Success<io.github.smallmiro.teslable.model.WhitelistSummary>>(vehicle.keySummary()).value.occupiedSlots)
            assertEquals(VehicleResult.Failure(VehicleError.RequiresKey), vehicle.startSession(timeout = 1.seconds))
        }

    @Test
    fun commandTimeoutDefaultsComeFromConfig() =
        // ADR-0010: timeout = null → TeslaBleConfig.commandTimeout
        runTest {
            val r = rig(config = TeslaBleConfig(commandTimeout = 2.seconds))
            val vehicle = r.connected(withKey = false)
            r.fake.sleep(setOf(vcsec))
            val start = testTimeSource.markNow()
            assertIs<VehicleResult.Uncertain>(vehicle.vehicleStatus())
            assertEquals(2.seconds, start.elapsedNow())
        }

    @Test
    fun linkDropDuringCommandIsUncertainThenNotConnected() =
        // Review Focus 1
        runTest {
            val r = rig()
            val vehicle = r.connected(withKey = false)
            r.fake.sleep(setOf(vcsec))
            val pending = async { vehicle.vehicleStatus(timeout = 3.seconds) }
            runCurrent()
            checkNotNull(r.factory.lastTransport).close() // 링크 끊김 — incoming 완료
            advanceTimeBy(3.seconds + 1.milliseconds)
            val uncertain = assertIs<VehicleResult.Uncertain>(pending.await())
            assertEquals(VehicleError.Timeout(afterSend = true), uncertain.error)
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.vehicleStatus(timeout = 1.seconds))
            assertIs<TransportState.Disconnected>(vehicle.connection.value)
        }

    @Test
    fun commandsAfterDisconnectReturnNotConnectedAndKeepCache() =
        // Review Focus 4
        runTest {
            val r = rig()
            val vehicle = r.connected()
            vehicle.startSession(timeout = 1.seconds)
            vehicle.disconnect()
            val stores = r.cache.stores.size
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.listKeys(timeout = 1.seconds))
            vehicle.disconnect() // 멱등
            assertEquals(stores, r.cache.stores.size)
            assertEquals(2, r.cache.stores.last().entries.size)
        }

    @Test
    fun throwingAppLoggerDoesNotBreakTheConnection() =
        // 설계 구체화 15: SafeLogger
        runTest {
            val r = rig(config = TeslaBleConfig(logger = TeslaLogger { _, _, _ -> error("app logger bug") }))
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(timeout = 1.seconds))
        }
}
```

`linkDropDuringCommandIsUncertainThenNotConnected`: `FakeTransport.close()`는 `incoming`을 닫아 디스패처 수신 루프를 끝낸다(M2 `sendReturnsNotConnectedAfterIncomingCompletes`와 같은 경로).

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :sdk:jvmTest --console=plain`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`sdk/build.gradle.kts` 끝에 추가:

```kotlin
detekt { config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml")) }
```

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/PlatformContext.kt
package io.github.smallmiro.teslable

/** 플랫폼 컨텍스트(SDD §2.3). Android: `PlatformContext(context)`, iOS·JVM: `PlatformContext()`. */
public expect class PlatformContext
```

```kotlin
// sdk/src/androidMain/kotlin/io/github/smallmiro/teslable/PlatformContext.android.kt
package io.github.smallmiro.teslable

import android.content.Context

/** Android 컨텍스트(애플리케이션 컨텍스트로 보관). */
public actual class PlatformContext(
    context: Context,
) {
    /** 애플리케이션 컨텍스트. */
    public val context: Context = context.applicationContext
}
```

```kotlin
// sdk/src/iosMain/kotlin/io/github/smallmiro/teslable/PlatformContext.ios.kt
package io.github.smallmiro.teslable

/** iOS에는 넘길 것이 없다. */
public actual class PlatformContext
```

`jvmMain`도 iOS와 같은 한 줄 actual.

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/TeslaBleConfig.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 파사드 설정(SDD §2.3, D29). */
public data class TeslaBleConfig(
    /** 로거(기본 NoOp, NFR-006). 던져도 라이브러리는 멈추지 않는다. */
    val logger: TeslaLogger = TeslaLogger.NoOp,
    /** 세션 캐시. null이면 플랫폼 기본(Android 파일, iOS Keychain, JVM 메모리). */
    val sessionCache: SessionCache? = null,
    /** 명령 기본 시간 제한. */
    val commandTimeout: Duration = 5.seconds,
    /** 인증 명령의 `expires_at` 수명. */
    val commandLifetime: Duration = 5.seconds,
    /** 핸드셰이크 기본 시간 제한. */
    val handshakeTimeout: Duration = 20.seconds,
    /** 스캔·연결 기본 시간 제한. */
    val connectTimeout: Duration = 30.seconds,
)
```

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/SessionStatus.kt
package io.github.smallmiro.teslable

/** 도메인 세션 상태(SDD §2.3 `Vehicle.sessions`). */
public sealed interface SessionStatus {
    /** 세션 없음. */
    public data object None : SessionStatus

    /** 핸드셰이크 중. */
    public data object Handshaking : SessionStatus

    /** 인증 명령을 보낼 수 있음. */
    public data class Ready(
        /** 세션 캐시에서 복원했는지. */
        val fromCache: Boolean,
    ) : SessionStatus
}
```

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/Vehicle.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * 연결된 차량(SDD §2.3). 연결 하나에 객체 하나 — [disconnect] 뒤에는 명령이 `Failure(NotConnected)`이고, 다시 쓰려면
 * `TeslaBle.connect`로 새로 연결한다(설계 구체화 13). M4/M5가 제어·조회 메서드를 더한다. 모든 `timeout`은 null이면 설정 기본값.
 */
public interface Vehicle {
    /** VIN. */
    public val vin: Vin

    /** 개인키로 연결했는지(인증 명령·핸드셰이크 가능 여부). */
    public val hasKey: Boolean

    /** BLE 연결 상태. */
    public val connection: StateFlow<TransportState>

    /** 도메인별 세션 상태. */
    public val sessions: StateFlow<Map<VehicleDomain, SessionStatus>>

    /** 핸드셰이크(FR-012). 캐시로 준비된 도메인은 요청 없이 성공. */
    public suspend fun startSession(
        domains: Set<VehicleDomain> = VehicleDomain.ALL,
        timeout: Duration? = null,
    ): VehicleResult<Unit>

    /** 연결을 끊는다(FR-005). 세션 캐시를 저장한 뒤 세션 키를 지운다. 멱등. */
    public suspend fun disconnect()

    /** 잠금·개폐·수면 상태(FR-031). 인증 없음. */
    public suspend fun vehicleStatus(timeout: Duration? = null): VehicleResult<VehicleStatus>

    /** 화이트리스트 요약(FR-032). 인증 없음. */
    public suspend fun keySummary(timeout: Duration? = null): VehicleResult<WhitelistSummary>

    /** 등록된 키 목록(FR-025). 인증 없음. [timeout]은 전체 시간. */
    public suspend fun listKeys(timeout: Duration? = null): VehicleResult<List<WhitelistEntry>>
}
```

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/DefaultVehicle.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/** [Vehicle] 구현: [VehicleSession] + 상태 흐름. */
internal class DefaultVehicle(
    override val vin: Vin,
    override val hasKey: Boolean,
    override val connection: StateFlow<TransportState>,
    private val session: VehicleSession,
    restored: Set<VehicleDomain>,
    private val scope: CoroutineScope,
    private val config: TeslaBleConfig,
) : Vehicle {
    private val states =
        MutableStateFlow(VehicleDomain.ALL.associateWith { if (it in restored) SessionStatus.Ready(fromCache = true) else SessionStatus.None })

    override val sessions: StateFlow<Map<VehicleDomain, SessionStatus>> = states.asStateFlow()

    override suspend fun startSession(
        domains: Set<VehicleDomain>,
        timeout: Duration?,
    ): VehicleResult<Unit> {
        states.update { current -> current.mapValues { (d, s) -> if (d in domains && s !is SessionStatus.Ready) SessionStatus.Handshaking else s } }
        val result = session.startSession(domains.map { it.wire }.toSet(), timeout ?: config.handshakeTimeout)
        states.update { current ->
            current.mapValues { (d, s) ->
                when {
                    d !in domains || s is SessionStatus.Ready -> s
                    result is VehicleResult.Success -> SessionStatus.Ready(fromCache = false)
                    else -> SessionStatus.None
                }
            }
        }
        return result
    }

    override suspend fun disconnect() {
        try {
            session.disconnect()
        } finally {
            states.value = VehicleDomain.ALL.associateWith { SessionStatus.None }
            scope.cancel()
        }
    }

    override suspend fun vehicleStatus(timeout: Duration?): VehicleResult<VehicleStatus> =
        session.info.vehicleStatus(timeout ?: config.commandTimeout)

    override suspend fun keySummary(timeout: Duration?): VehicleResult<WhitelistSummary> =
        session.info.keySummary(timeout ?: config.commandTimeout)

    override suspend fun listKeys(timeout: Duration?): VehicleResult<List<WhitelistEntry>> =
        session.info.listKeys(timeout ?: config.commandTimeout)
}
```

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/PlatformComponents.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleKeyStore
import kotlinx.coroutines.CoroutineScope

/** 플랫폼 기본 어댑터 묶음. */
internal class PlatformComponents(
    val transports: TransportFactory,
    val keys: VehicleKeyStore,
    val sessionCache: SessionCache,
)

/** 플랫폼 기본 어댑터를 만든다. [scope]는 `TeslaBle` 수명, [logger]는 이미 `SafeLogger`로 감싼 것. */
internal expect fun platformComponents(
    platform: PlatformContext,
    config: TeslaBleConfig,
    scope: CoroutineScope,
    logger: TeslaLogger,
): PlatformComponents
```

```kotlin
// sdk/src/androidMain/kotlin/io/github/smallmiro/teslable/PlatformComponents.android.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.ble.androidBleAvailability
import io.github.smallmiro.teslable.ble.kableTransportFactory
import io.github.smallmiro.teslable.crypto.androidVehicleKeyStore
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.storage.androidSessionCache
import kotlinx.coroutines.CoroutineScope

internal actual fun platformComponents(
    platform: PlatformContext,
    config: TeslaBleConfig,
    scope: CoroutineScope,
    logger: TeslaLogger,
): PlatformComponents {
    val crypto = platformCryptoPrimitives()
    return PlatformComponents(
        transports = kableTransportFactory(scope, crypto, androidBleAvailability(platform.context), logger, config.connectTimeout),
        keys = androidVehicleKeyStore(platform.context, logger),
        sessionCache = config.sessionCache ?: androidSessionCache(platform.context, crypto, logger),
    )
}
```

iOS(`appleBleAvailability()`, `appleVehicleKeyStore(logger)`, `appleSessionCache(crypto, logger)`)와 JVM(`BleAvailability.ALWAYS`, `inMemoryVehicleKeyStore()`, `config.sessionCache ?: InMemorySessionCache()`)도 같은 모양으로 쓴다.

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/TeslaBle.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.application.cache.SessionCacheSync
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.logging.SafeLogger
import io.github.smallmiro.teslable.application.vehicle.CommandTimeouts
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.crypto.platformRandomSource
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlin.time.TimeSource

/**
 * 공개 진입점(SDD §2.3). 스캔·연결·키 보관소를 제공한다. 앱 수명 동안 하나를 만들어 쓰고, 다 쓰면 [close].
 * 연결마다 차량 스코프(`SupervisorJob + Dispatchers.Default + CoroutineName`)를 따로 만든다(SDD §5).
 */
@Suppress("LongParameterList") // 포트 주입(테스트) — 앱은 create()만 쓴다
public class TeslaBle internal constructor(
    private val transports: TransportFactory,
    /** 차량 키 보관소(FR-020, FR-021, FR-028). 키를 지우면 그 키의 세션 캐시는 다음 연결 때 `keyId` 불일치로 지워진다(설계 구체화 9). */
    public val keys: VehicleKeyStore,
    private val sessionCache: SessionCache,
    private val crypto: CryptoPrimitives,
    private val random: RandomSource,
    private val config: TeslaBleConfig,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val vehicleScope: (Vin) -> CoroutineScope,
    private val ownScope: CoroutineScope? = null,
) {
    private val logger = SafeLogger(config.logger)

    /** [vin] 차량의 광고를 찾는다(FR-001, FR-030). 권한·블루투스 문제는 `ScanEvent.Failed`. */
    public fun scan(vin: Vin): Flow<ScanEvent> = transports.scan(LocalName.of(vin, crypto))

    /**
     * 연결(FR-002~FR-004)하고 캐시를 복원한다. [key]가 null이면 인증 없는 조회만 쓸 수 있다. [target]은 [scan]에서 받은 광고(없으면 스캔).
     * Go CLI 순서: 연결 → `NewVehicle`(캐시) → `Connect`(수신 시작). 핸드셰이크는 `Vehicle.startSession`.
     */
    public suspend fun connect(
        vin: Vin,
        key: VehicleKey?,
        target: VehicleAdvertisement? = null,
        options: ConnectOptions = ConnectOptions(),
    ): VehicleResult<Vehicle> {
        val transport =
            when (val result = transports.connect(vin, target, options.copy(timeout = options.timeout ?: config.connectTimeout))) {
                is VehicleResult.Success -> result.value
                is VehicleResult.Failure -> return result
                is VehicleResult.Uncertain -> return result
            }
        val scope = vehicleScope(vin)
        val dispatcher = Dispatcher(transport, key, crypto, random, scope, timeSource, logger)
        val sync = key?.let { SessionCacheSync(sessionCache, vin, KeyId.of(it.publicKey, crypto)) }
        val session = VehicleSession(dispatcher, CommandTimeouts(config.commandTimeout, config.commandLifetime, config.handshakeTimeout), sync, scope)
        val restored = session.connect().mapNotNull { VehicleDomain.of(it) }.toSet()
        logger.log(LogLevel.INFO, TAG) { "Connected to $vin (restored ${restored.size} session(s) from cache)" }
        return VehicleResult.Success(DefaultVehicle(vin, key != null, transport.state, session, restored, scope, config))
    }

    /** 이 인스턴스가 만든 백그라운드 작업을 멈춘다. 연결된 차량은 먼저 `disconnect()`한다. */
    public fun close() {
        ownScope?.cancel()
    }

    /** 생성. */
    public companion object {
        private const val TAG = "teslable"

        /** 플랫폼 기본 어댑터로 만든다. */
        public fun create(
            platform: PlatformContext,
            config: TeslaBleConfig = TeslaBleConfig(),
        ): TeslaBle {
            val logger = SafeLogger(config.logger)
            val handler = CoroutineExceptionHandler { _, e -> logger.log(LogLevel.ERROR, TAG) { "Unhandled error: ${e::class.simpleName}" } }
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("teslable") + handler)
            val components = platformComponents(platform, config, appScope, logger)
            return TeslaBle(
                transports = components.transports,
                keys = components.keys,
                sessionCache = components.sessionCache,
                crypto = platformCryptoPrimitives(),
                random = platformRandomSource(),
                config = config,
                vehicleScope = { vin ->
                    CoroutineScope(SupervisorJob(appScope.coroutineContext[kotlinx.coroutines.Job]) + Dispatchers.Default + CoroutineName("teslable-$vin") + handler)
                },
                ownScope = appScope,
            )
        }
    }
}
```

`CoroutineName("teslable-$vin")`의 `$vin`은 `Vin.toString()`(마스킹)이다(FR-112). 테스트 생성자 호출은 이름 있는 인자를 쓰므로 `ownScope`를 넘기지 않아도 된다.

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :sdk:jvmTest :sdk:iosSimulatorArm64Test :sdk:testAndroidHostTest --console=plain`
Expected: PASS
Run: `./gradlew :sdk:apiDump`
Run: `./gradlew :sdk:linkDebugFrameworkIosSimulatorArm64 --console=plain`
Expected: SKIE가 `Vehicle`/`TeslaBle`/`SessionStatus`/`ScanEvent`를 Swift로 내보냄(빌드 성공)

- [ ] **Step 5: 커밋 → PR(`m3/sdk-facade`) → verify.sh → 리뷰 → 병합**

```bash
git add sdk
git commit -m "feat(sdk): TeslaBle facade, Vehicle with sessions/connection state and unauthenticated queries, platform wiring

Refs: FR-001, FR-002, FR-003, FR-004, FR-005, FR-025, FR-031, FR-032, ADR-0006
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 14: `DebugLogger`, Swift 친화 진입점, 샘플 앱 2종(스캔·키·연결·조회), 스파이크·M0 임시 코드 삭제, 🛑 사용자 실차 확인

참고: SDD §8.4(권한 문구), §10(`DebugLogger`), §2.3(SKIE); 로드맵 M3 완료 기준; FR-001~FR-006, FR-020~FR-021, FR-025, FR-031, FR-112; 설계 구체화 17, 18.

**Files:**
- Create: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/DebugLogger.kt` (expect) + `androidMain`/`iosMain`/`jvmMain` actual
- Modify: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/TeslaBleConfig.kt` (`companion object { fun default(logger) }`)
- Modify: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/Vin.kt` (`Vin.parseOrNull`), `domain/src/commonTest/…/model/VinTest.kt`
- Delete: `sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/Teslable.kt`, `TeslableDiagnostics.kt`, `sdk/src/commonTest/…/TeslableTest.kt`, `ProbeRequestTest.kt`, `adapter-ble/src/commonMain/…/ble/GattProbe.kt`, `samples/ios/Sources/DiagnosticsView.swift`
- Modify: `samples/ios/Sources/ContentView.swift`, `TeslableSampleApp.swift`; Create: `samples/ios/Sources/SampleModel.swift`
- Modify: `samples/android/src/main/AndroidManifest.xml`, `samples/android/src/main/kotlin/io/github/smallmiro/teslable/sample/MainActivity.kt`; Create: `…/sample/SampleState.kt`
- Modify: `sdk/api/*`, `adapter-ble/api/*`, `domain/api/*`, `samples/ios/README.md`

**Interfaces:**
- Consumes: Task 13 `TeslaBle`/`Vehicle`/`TeslaBleConfig`/`SessionStatus`/`PlatformContext`, Task 2 `ScanEvent`/`VehicleKeyStore`, Task 10 값 객체.
- Produces: `public expect class DebugLogger() : TeslaLogger`; `TeslaBleConfig.default(logger: TeslaLogger = TeslaLogger.NoOp): TeslaBleConfig`(Swift에는 기본 인자가 없고 `Duration`은 Swift에서 다루기 어렵다); `Vin.Companion.parseOrNull(value: String): Vin?`(Swift에서 `Vin(…)`의 `require` 실패는 프로세스를 죽인다 — M0 `ContentView.swift` 주석).

- [ ] **Step 1: 실패 테스트 작성**

`VinTest`에 추가:

```kotlin
    @Test
    fun parseOrNullReturnsNullInsteadOfThrowing() {
        assertEquals(Vin("5YJ30123456789ABC"), Vin.parseOrNull("5yj30123456789abc"))
        assertNull(Vin.parseOrNull("5YJ3012345678")) // 짧음
        assertNull(Vin.parseOrNull("5YJ3012345678OABC")) // O는 VIN 문자가 아님
    }
```

`sdk/src/commonTest/kotlin/io/github/smallmiro/teslable/TeslaBleConfigTest.kt`:

```kotlin
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.TeslaLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TeslaBleConfigTest {
    @Test
    fun defaultFactoryKeepsDocumentedDefaults() {
        val logger = TeslaLogger { _, _, _ -> }
        val config = TeslaBleConfig.default(logger)
        assertSame(logger, config.logger)
        assertEquals(TeslaBleConfig(logger = logger), config)
    }
}
```

Run: `./gradlew :domain:jvmTest :sdk:jvmTest --console=plain` → 컴파일 실패 확인.

- [ ] **Step 2: 구현 (라이브러리)**

`Vin`의 `private companion object`를 공개 companion으로 바꾼다(`VIN_REGEX`는 private 유지):

```kotlin
    /** 생성 도우미. */
    public companion object {
        private val VIN_REGEX = Regex("^[A-HJ-NPR-Z0-9]{17}$")

        /** 형식이 틀리면 null(Swift·UI 입력용 — 생성자는 `IllegalArgumentException`). */
        public fun parseOrNull(value: String): Vin? = if (VIN_REGEX.matches(value.uppercase())) Vin(value) else null
    }
```

`TeslaBleConfig`에:

```kotlin
    /** Swift용 생성 도우미(기본 인자·`Duration`을 쓰지 않고 만든다). */
    public companion object {
        /** 모든 값을 기본값으로, 로거만 바꾼다. */
        public fun default(logger: TeslaLogger = TeslaLogger.NoOp): TeslaBleConfig = TeslaBleConfig(logger = logger)
    }
```

```kotlin
// sdk/src/commonMain/kotlin/io/github/smallmiro/teslable/DebugLogger.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.TeslaLogger

/** 플랫폼 로그(Android Logcat, iOS 통합 로그, JVM java.util.logging)로 보내는 개발용 로거(SDD §10). 릴리스 빌드에는 쓰지 않는다. */
public expect class DebugLogger() : TeslaLogger
```

```kotlin
// sdk/src/androidMain/kotlin/io/github/smallmiro/teslable/DebugLogger.android.kt
package io.github.smallmiro.teslable

import android.util.Log
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger

/** Logcat 로거. 태그는 `teslable/<tag>`. */
public actual class DebugLogger actual constructor() : TeslaLogger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        val priority =
            when (level) {
                LogLevel.DEBUG -> Log.DEBUG
                LogLevel.INFO -> Log.INFO
                LogLevel.WARN -> Log.WARN
                LogLevel.ERROR -> Log.ERROR
            }
        Log.println(priority, "teslable/$tag", message())
    }
}
```

```kotlin
// sdk/src/iosMain/kotlin/io/github/smallmiro/teslable/DebugLogger.ios.kt
package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import platform.Foundation.NSLog

/** NSLog 로거(Xcode 콘솔·Console.app). */
public actual class DebugLogger actual constructor() : TeslaLogger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        NSLog("[teslable/%@] %@: %@", tag, level.name, message())
    }
}
```

JVM actual은 `java.util.logging.Logger.getLogger("teslable.$tag").log(level 매핑, message())`.

삭제: `Teslable.kt`(M0 임시 파사드), `TeslableTest.kt`, `TeslableDiagnostics.kt`, `ProbeRequestTest.kt`, `GattProbe.kt`(설계 구체화 17). `BleUuids`는 Task 5가 쓰므로 남긴다.

Run: `./gradlew :domain:jvmTest :sdk:jvmTest :adapter-ble:jvmTest --console=plain` → PASS
Run: `./gradlew :domain:apiDump`, `./gradlew :sdk:apiDump`, `./gradlew :adapter-ble:apiDump`(각각 단독)

```bash
git add domain sdk adapter-ble
git commit -m "feat(sdk): DebugLogger, Swift-friendly TeslaBleConfig.default and Vin.parseOrNull; remove the M0 facade and the spike probe

Refs: FR-112, NFR-006, ADR-0003
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 3: iOS 샘플**

```swift
// samples/ios/Sources/SampleModel.swift
import Foundation
import Teslable

/// 샘플 화면 상태. 라이브러리 호출은 모두 여기서 한다(SKIE: suspend → async, Flow → AsyncSequence, sealed → onEnum).
@MainActor
final class SampleModel: ObservableObject {
    @Published var vinText = ""
    @Published var keyInfo = "키 없음"
    @Published var advertisements: [String] = []
    @Published var connection = "연결 안 됨"
    @Published var output: [String] = []

    private let ble = TeslaBle.companion.create(platform: PlatformContext(), config: TeslaBleConfig.companion.default(logger: DebugLogger()))
    private var key: VehicleKey?
    private var vehicle: Vehicle?

    private var vin: Vin? { Vin.companion.parseOrNull(value: vinText) }

    func createKey() async {
        do {
            let result = try await ble.keys.getOrCreate(alias: VehicleKeyStoreCompanion.shared.DEFAULT_ALIAS, policy: .preferHardware)
            switch onEnum(of: result) {
            case .success(let s):
                let k = s.value as! VehicleKey
                key = k
                keyInfo = "\(k.protection.name) \(k.publicKey.description())"
            case .uncertain(let u): keyInfo = "불확실: \(u.error.message)"
            case .failure(let f): keyInfo = "실패: \(f.error.message)"
            }
        } catch { keyInfo = "오류: \(error)" }
    }

    func scan() async {
        guard let vin else { output.append("VIN 형식 오류"); return }
        advertisements = []
        do {
            for try await event in ble.scan(vin: vin) {
                switch onEnum(of: event) {
                case .found(let f):
                    let ad = f.advertisement
                    advertisements.append("\(ad.localName) rssi=\(ad.rssi) connectable=\(String(describing: ad.isConnectable))")
                    return // 첫 광고에서 멈춘다(스캔 중단)
                case .failed(let e):
                    advertisements.append("스캔 실패: \(e.error.message)")
                    return
                }
            }
        } catch { output.append("스캔 오류: \(error)") }
    }

    func connect() async {
        guard let vin else { output.append("VIN 형식 오류"); return }
        connection = "연결 중…"
        do {
            let result = try await ble.connect(vin: vin, key: key, target: nil, options: ConnectOptions(timeout: nil))
            switch onEnum(of: result) {
            case .success(let s):
                vehicle = (s.value as! Vehicle)
                connection = "연결됨 (키: \(key == nil ? "없음" : "있음"))"
            case .uncertain(let u): connection = "불확실: \(u.error.message)"
            case .failure(let f): connection = "실패: \(f.error.message)"
            }
        } catch { connection = "오류: \(error)" }
    }

    func vehicleStatus() async { await run("VehicleStatus") { try await $0.vehicleStatus(timeout: nil) } }
    func listKeys() async { await run("키 목록") { try await $0.listKeys(timeout: nil) } }

    func disconnect() async {
        try? await vehicle?.disconnect()
        vehicle = nil
        connection = "연결 안 됨"
    }

    private func run(_ title: String, _ call: (Vehicle) async throws -> VehicleResult<AnyObject>) async {
        guard let vehicle else { output.append("\(title): 먼저 연결하세요"); return }
        do {
            let result = try await call(vehicle)
            switch onEnum(of: result) {
            case .success(let s): output.append("\(title): \(String(describing: s.value))")
            case .uncertain(let u): output.append("\(title) 불확실: \(u.error.message)")
            case .failure(let f): output.append("\(title) 실패: \(f.error.message)")
            }
        } catch { output.append("\(title) 오류: \(error)") }
    }
}
```

SKIE가 만든 Swift 이름(`TeslaBle.companion`, `VehicleKeyStoreCompanion`, `onEnum(of:)` 케이스 이름, 제네릭 `VehicleResult` 값의 캐스트 방식)은 `sdk/build/skie/…` 생성 헤더를 보고 맞춘다. 원칙만 고정한다: 모든 결과는 `onEnum(of:)`로 세 경우를 다 처리하고, 강제 언래핑으로 Kotlin 예외를 Swift에 흘리지 않는다.

```swift
// samples/ios/Sources/ContentView.swift
import SwiftUI

struct ContentView: View {
    @StateObject private var model = SampleModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("차량") {
                    TextField("VIN", text: $model.vinText).textInputAutocapitalization(.characters)
                    Text(model.connection)
                }
                Section("키") {
                    Button("키 만들기/불러오기") { Task { await model.createKey() } }
                    Text(model.keyInfo).font(.footnote.monospaced())
                }
                Section("BLE") {
                    Button("스캔") { Task { await model.scan() } }
                    ForEach(model.advertisements, id: \.self) { Text($0).font(.footnote) }
                    Button("연결") { Task { await model.connect() } }
                    Button("VehicleStatus") { Task { await model.vehicleStatus() } }
                    Button("키 목록") { Task { await model.listKeys() } }
                    Button("연결 끊기", role: .destructive) { Task { await model.disconnect() } }
                }
                Section("결과") {
                    ForEach(Array(model.output.enumerated()), id: \.offset) { Text($0.element).font(.footnote.monospaced()) }
                }
            }
            .navigationTitle("Teslable Sample")
        }
    }
}
```

`DiagnosticsView.swift`를 지우고, `samples/ios/README.md`의 "실기기 실행" 절에서 Probe 단계를 "샘플 화면으로 확인(Step 5 체크리스트)"으로 바꾼다.

Run: `tools/ci/verify.sh` (d) 단계가 시뮬레이터 빌드로 Swift 코드를 컴파일한다.

- [ ] **Step 4: Android 샘플**

`AndroidManifest.xml`(INTERNET 없음, NFR-014):

```xml
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

`MainActivity`는 `RequestMultiplePermissions`로 두 권한을 요청한 뒤 `SampleScreen`을 보여 준다. `SampleState`(Compose 상태 + `rememberCoroutineScope`)는 iOS `SampleModel`과 같은 다섯 동작(키, 스캔, 연결, VehicleStatus, 키 목록, 끊기)을 `when (result) { is VehicleResult.Success -> …; is VehicleResult.Uncertain -> …; is VehicleResult.Failure -> … }`로 처리한다. `TeslaBle.create(PlatformContext(applicationContext), TeslaBleConfig(logger = DebugLogger()))`는 `remember`로 한 번만 만든다. 권한을 거부한 채 스캔하면 화면에 `bluetooth permission not granted`가 나와야 한다(FR-006 — 크래시 금지).

```kotlin
// samples/android/src/main/kotlin/io/github/smallmiro/teslable/sample/SampleState.kt
package io.github.smallmiro.teslable.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import io.github.smallmiro.teslable.TeslaBle
import io.github.smallmiro.teslable.Vehicle
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.flow.first

/** 샘플 화면 상태(iOS SampleModel과 같은 동작). */
class SampleState(
    private val ble: TeslaBle,
) {
    val vinText = mutableStateOf("")
    val keyInfo = mutableStateOf("키 없음")
    val connection = mutableStateOf("연결 안 됨")
    val output = mutableStateListOf<String>()
    private var key: VehicleKey? = null
    private var vehicle: Vehicle? = null

    private fun vin(): Vin? = Vin.parseOrNull(vinText.value)

    suspend fun createKey() {
        keyInfo.value =
            when (val r = ble.keys.getOrCreate()) {
                is VehicleResult.Success -> r.value.also { key = it }.let { "${it.protection} ${it.publicKey}" }
                is VehicleResult.Uncertain -> "불확실: ${r.error.message}"
                is VehicleResult.Failure -> "실패: ${r.error.message}"
            }
    }

    suspend fun scan() {
        val vin =
            vin() ?: run {
                output.add("VIN 형식 오류")
                return
            }
        output.add(
            when (val e = ble.scan(vin).first()) {
                is ScanEvent.Found -> "광고: ${e.advertisement.localName} rssi=${e.advertisement.rssi} connectable=${e.advertisement.isConnectable}"
                is ScanEvent.Failed -> "스캔 실패: ${e.error.message}"
            },
        )
    }

    suspend fun connect() {
        val vin =
            vin() ?: run {
                output.add("VIN 형식 오류")
                return
            }
        connection.value =
            when (val r = ble.connect(vin, key)) {
                is VehicleResult.Success -> "연결됨".also { vehicle = r.value }
                is VehicleResult.Uncertain -> "불확실: ${r.error.message}"
                is VehicleResult.Failure -> "실패: ${r.error.message}"
            }
    }

    suspend fun vehicleStatus() = report("VehicleStatus") { it.vehicleStatus() }

    suspend fun listKeys() = report("키 목록") { it.listKeys() }

    suspend fun disconnect() {
        vehicle?.disconnect()
        vehicle = null
        connection.value = "연결 안 됨"
    }

    private suspend fun report(
        title: String,
        call: suspend (Vehicle) -> VehicleResult<Any>,
    ) {
        val v =
            vehicle ?: run {
                output.add("$title: 먼저 연결하세요")
                return
            }
        output.add(
            when (val r = call(v)) {
                is VehicleResult.Success -> "$title: ${r.value}"
                is VehicleResult.Uncertain -> "$title 불확실: ${r.error.message}"
                is VehicleResult.Failure -> "$title 실패: ${r.error.message}"
            },
        )
    }
}
```

`MainActivity`의 Compose UI는 iOS `ContentView`와 같은 배치(VIN 입력, 버튼 6개, 결과 목록)로 쓴다. 샘플은 라이브러리 모듈이 아니므로 detekt/KDoc 규칙 밖이다(`ktlint`는 적용된다).

Run: `tools/ci/verify.sh`
Expected: 6/6(병합 매니페스트에 INTERNET 없음, 샘플 두 개 빌드)

```bash
git add samples
git commit -m "feat(samples): scan, key, connect, VehicleStatus and key list screens on iOS and Android

Refs: FR-001, FR-006, FR-020, FR-025, FR-031
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

PR(`m3/samples`) → AI 리뷰 → 병합.

- [ ] **Step 5: 🛑 사용자 실차 확인 (iPhone)**

사용자에게 `samples/ios/README.md` "실기기 실행" 절차로 `TeslableSampleDevice`를 iPhone에 설치해 아래를 확인해 달라고 요청하고, 결과(관측값·오류 문구, VIN·공개키 전문 제외)를 받는다. M4 이전이라 이 폰의 키는 차량에 **등록되어 있지 않다** — 인증이 필요한 핸드셰이크·명령은 확인 대상이 아니다.

| # | 동작 | 기대 | 결과 |
|---|---|---|---|
| 1 | 키 만들기 | `SECURE_ENCLAVE` + 공개키 지문. 앱을 다시 실행해도 같은 지문(영속) | |
| 2 | 스캔(차량 근처) | `S…C` 광고, rssi, connectable=true | |
| 3 | 연결 | "연결됨". 실패하면 오류 문구 | |
| 4 | VehicleStatus | 잠금 상태·문 상태가 실제와 같음(문 하나 열고 다시) | |
| 5 | 키 목록 | 차량 화면(잠금 → 키)의 키 수와 같음, 역할·형태 표시 | |
| 6 | 연결 끊기 → 다시 연결 | 둘 다 성공 | |
| 7 | 블루투스 끄고 스캔 | `bluetooth is powered off` | |
| 8 | 설정에서 블루투스 권한 거부 후 스캔 | `bluetooth permission not granted`(크래시 없음) | |
| 9 | 명령 중 차량에서 멀어짐(선택) | 불확실(시간 초과) 뒤 `vehicle not connected` | |

Android는 에뮬레이터에서 (1)만 확인한다 — 기대: `SOFTWARE`(에뮬레이터 Keystore는 하드웨어 수준이 아니므로 소프트웨어 대체), 앱 재실행 후 같은 공개키. 컨트롤러가 로컬 에뮬레이터로 직접 확인해도 된다.

실패 항목은 오류 문구와 함께 `superpowers:systematic-debugging`으로 원인을 찾고(원본 Go 코드와 먼저 대조), 수정은 `fix:` PR로 한다. 결과 표는 Task 15 인계 노트에 옮긴다.

---
### Task 15: 골든 픽스처 수집 스크립트, 사용 문서(`platform-notes.md`·`getting-started.md`·`errors.md`), SDD·ADR 동기화, M3 인계 노트

참고: SDD §9.2(골든 수집·마스킹), §10, §12; NFR-002; AGENTS.md §3 규칙 5-1·11(매뉴얼 불일치 보고, 문서 동기화); `{{WORKFLOW_FILE}}` §10(인계 노트 형식); 로드맵 M3 행("골든 픽스처 수집 스크립트, `platform-notes.md`·`getting-started.md` 초안"); M2 인계("`errors.md`의 'SKIE가 enum으로 노출' 문장 교정", "기기 수면 중 단조 시계 정지는 `platform-notes.md`").

**Files:**
- Create: `tools/fixtures/collect-golden.sh`, `tools/fixtures/mask-vin.sh`, `tools/fixtures/README.md`
- Modify: `.gitignore` (`tools/fixtures/captures/`)
- Create: `docs/manual/platform-notes.md`, `docs/manual/getting-started.md`
- Modify: `docs/manual/errors.md`, `docs/manual/README.md`, `docs/manual/architecture.md`, `README.md`
- Modify: `docs/sdd/SDD.md` (§2.3, §2.5, §7.2, §8.2, §9.5, §12), `docs/adr/0010-timeouts-and-cancellation.md`
- Create: `docs/handoff/<YYYY-MM-DD>-m3-transport-keystore.md`(+ 서브에이전트 실행이면 `…-m3-rulings.md`)

**Interfaces:**
- Consumes: Task 1~14의 결과(스파이크 판정, 실차 확인 표, 각 PR의 판정).
- Produces: M4 계획의 입력(인계 노트 "막힌 점"·"다음 한 걸음"), 골든 TX/RX 수집 절차.

- [ ] **Step 1: VIN 마스킹 스크립트와 자가 시험**

```bash
#!/usr/bin/env bash
# tools/fixtures/mask-vin.sh — 캡처의 실제 VIN(ASCII 원문과 그 hex)을 테스트 VIN 5YJ30123456789ABC로 바꾼다(SDD §9.2).
# 길이가 같아 TLV 길이가 유지된다. 사용: mask-vin.sh <VIN> < capture.txt > masked.txt
#        mask-vin.sh --self-test
set -euo pipefail
TEST_VIN="5YJ30123456789ABC"
hex_of() { printf '%s' "$1" | od -An -tx1 | tr -d ' \n'; }
mask() {
  local vin; vin="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  if [ "${#vin}" -ne 17 ]; then echo "VIN must be 17 characters" >&2; exit 2; fi
  local vin_hex test_hex; vin_hex="$(hex_of "$vin")"; test_hex="$(hex_of "$TEST_VIN")"
  sed -e "s/${vin}/${TEST_VIN}/g" -e "s/${vin_hex}/${test_hex}/g"
}
if [ "${1:-}" = "--self-test" ]; then
  real="LRW3E7FA0MC123456"
  input="TX: 0a$(hex_of "$real")ff vin=$real"
  expected="TX: 0a$(hex_of "$TEST_VIN")ff vin=$TEST_VIN"
  actual="$(printf '%s\n' "$input" | mask "$real")"
  [ "$actual" = "$expected" ] && echo "mask-vin self-test: ok" || { echo "mask-vin self-test: FAILED: $actual" >&2; exit 1; }
  exit 0
fi
mask "${1:?usage: mask-vin.sh <VIN> < capture > masked}"
```

자가 시험의 `real`은 형식만 맞춘 가짜 VIN이다. `tools/ci/scan-secrets.sh`가 이 줄을 VIN으로 잡으면 허용 목록(`tools/ci/secret-allowlist.txt`)에 그 값을 추가하지 말고, 자가 시험 VIN을 스크립트 안에서 `"LRW3E7FA0MC" "123456"`처럼 두 조각을 이어 만든다.

Run: `bash tools/fixtures/mask-vin.sh --self-test`
Expected: `mask-vin self-test: ok`

- [ ] **Step 2: 수집 스크립트**

```bash
#!/usr/bin/env bash
# tools/fixtures/collect-golden.sh — 사용자가 실차 옆에서 Go tesla-control로 TX/RX hex를 모은다(SDD §9.2, NFR-002).
# 필요: Go로 빌드한 tesla-control(PATH), 블루투스. 출력: tools/fixtures/captures/<명령>.txt (git-ignored, 커밋 금지)
# 사용: VIN=... tools/fixtures/collect-golden.sh            # 인증 없는 명령(M3): list-keys, body-controller-state
#       VIN=... KEY_FILE=... tools/fixtures/collect-golden.sh --with-key   # 인증 명령(M4/M5): session-info, lock, unlock, climate-on, "state charge"
set -euo pipefail
: "${VIN:?set VIN}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/tools/fixtures/captures"
mkdir -p "$OUT"
run() {
  local name="$1"; shift
  echo "==> $name"
  tesla-control -ble -vin "$VIN" -debug "$@" 2>&1 | grep -E '\[debug\] (TX|RX): ' | "$ROOT/tools/fixtures/mask-vin.sh" "$VIN" > "$OUT/$name.txt" || true
  wc -l < "$OUT/$name.txt" | xargs echo "    lines:"
}
run list-keys list-keys
run body-controller-state body-controller-state
if [ "${1:-}" = "--with-key" ]; then
  : "${KEY_FILE:?set KEY_FILE for authenticated commands}"
  run session-info-vcsec session-info "$KEY_FILE" vcsec   # commands.go:861-868: PUBLIC_KEY는 키 파일 경로, 인증 불필요
  run lock -key-file "$KEY_FILE" lock
  run unlock -key-file "$KEY_FILE" unlock
  run climate-on -key-file "$KEY_FILE" climate-on
  run state-charge -key-file "$KEY_FILE" state charge
fi
echo "captures in $OUT — 실제 공개키가 담긴 RX(list-keys)는 커밋하지 않는다. 필요한 줄만 골라 FIXTURES_DIR의 Kotlin 상수로 옮긴다(ADR-0011)."
```

`tools/fixtures/README.md`에 위 사용법, "Go가 이 저장소에 없으므로 사용자 기기에서 실행"(메모리: go CLI 없음), 마스킹 규칙, 커밋 금지 규칙을 적는다. `.gitignore`에 `tools/fixtures/captures/`를 추가한다.

```bash
git add tools/fixtures .gitignore
git commit -m "chore(tools): golden TX/RX capture and VIN masking scripts for real-vehicle fixtures

Refs: NFR-002
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 3: 사용 문서 (`{{LIB_DOCS_DIR}}`, 상대 링크만)**

`docs/manual/platform-notes.md` — 절 구성(각 절 3~8줄, 이 계획의 결정을 출처로):
1. 지원 범위: Android 12+(API 31), iOS 16+, 포그라운드 전제(SDD §8.4). JVM은 테스트·실험용(BLE는 btleplug, 키스토어는 메모리 — 비영속).
2. Android 권한: `BLUETOOTH_SCAN`(`neverForLocation`), `BLUETOOTH_CONNECT` 런타임 요청, 없으면 `PermissionDenied`. `INTERNET` 불필요.
3. iOS: `NSBluetoothAlwaysUsageDescription`, 권한 거부 시 `PermissionDenied`, 첫 스캔이 권한 창을 띄움. 0213 구독 방식(ADR-0003 스파이크 결과 인용). MTU 협상 API 없음.
4. 키 보관: Secure Enclave / StrongBox / TEE / SOFTWARE의 뜻, 시뮬레이터·에뮬레이터는 SOFTWARE, 손상된 저장 키는 자동 교체하지 않음(앱이 `delete` 후 재생성 — 이 경우 차량에 다시 등록 필요), 개인키 내보내기 없음(ADR-0008).
5. 세션 캐시: 위치(Android `filesDir/teslable/sessions`, iOS Keychain `AfterFirstUnlockThisDeviceOnly`), 파일명은 VIN 해시, 키를 바꾸면 캐시 자동 폐기, 오래된 캐시 자동 삭제(차량 키 교체).
6. 시계: 세션 시계는 단조 시계(`TimeSource.Monotonic`) — 기기 수면 중 단조 시계가 멈추는 플랫폼에서는 캐시된 세션의 차량 시계 추정이 뒤처져 첫 명령이 `TIME_EXPIRED` 등으로 실패할 수 있고, 라이브러리가 동봉 세션정보로 재동기화 후 재시도한다(SDD §3.4). 캐시 `age`는 벽시계 기준(M2 인계 항목).
7. 연결 슬롯: 차량 BLE 연결은 3개까지 — 쓰지 않을 때 `disconnect()`(FR-005).

`docs/manual/getting-started.md` — Kotlin과 Swift 예제 하나씩: `TeslaBle.create` → `keys.getOrCreate()` → `scan(vin).first()` → `connect(vin, key)` → `vehicleStatus()`/`listKeys()` → `disconnect()`. Swift는 `onEnum(of:)`로 `VehicleResult` 세 경우를 모두 처리하는 모양(Task 14 `SampleModel.swift`와 같은 코드). "키 등록(페어링)은 M4" 안내.

`docs/manual/errors.md`: `TransportError`(ScanTimeout, MaxConnectionsExceeded, ConnectFailed, Disconnected, BluetoothOff, PermissionDenied, WriteFailed)와 `KeyStoreError`(HardwareUnavailable, KeyNotFound, PlatformFailure("stored key is corrupted" 포함))의 뜻·재시도 여부 표를 추가하고, "SKIE가 enum으로 노출" 문장을 "SKIE `onEnum(of:)`로 Swift `switch`에서 다룬다"로 고친다(M2 인계). `docs/manual/README.md` 목차에 두 새 문서를 넣고, `architecture.md`에 `:adapter-ble`(Kable 바인딩 + 테스트 가능한 코어)·`:sdk` 조립을 한 문단씩 더한다. 루트 `README.md` 상태 절을 "M3: 스캔·연결·키 목록·VehicleStatus(실차 확인: <Task 14 결과>)"로 갱신한다.

- [ ] **Step 4: SDD·ADR 동기화**

- `{{SDD_FILE}}` §2.3: 실제 공개 API(Task 13·14: `connect(vin, key, target, options)`, `scan(): Flow<ScanEvent>`, `Vehicle.connection: StateFlow<TransportState>`, M3 `Vehicle` 메서드 목록, `TeslaBleConfig.default`, `Vin.parseOrNull`, `DebugLogger`)로 고친다.
- §2.5/§7.2/§8.2: 소프트웨어 대체 키 형식을 `04‖X‖Y‖d`(97바이트) — Android는 Keystore AES로 감싼 `TBK1` 파일, iOS는 Keychain generic password — 로 고친다(설계 구체화 10). 손상 시 자동 교체 없음.
- §9.5: Task 8 Step 1 결과(시뮬레이터 Keychain 가능 여부)와 "Kable 바인딩·Android Keystore·Secure Enclave는 단위 테스트 대신 샘플 앱 확인(Task 14 표)".
- §12 "매뉴얼과 원본의 불일치" 표에 행 추가(원본을 따름, 매뉴얼은 사용자 승인 전 수정하지 않음):

| # | 매뉴얼 위치 | 원본 위치 | 내용 |
|---|---|---|---|
| 2 | `10-porting-guide.md:88` | `pkg/connector/ble/ble.go:20, 349` | 매뉴얼은 `ble.MaxMTU = 517`. Go는 go-ble `ble.MaxMTU`를 요청(go-ble 소스는 이 저장소에 없어 값 미확인; 517은 Android 상한). 우리는 Android `requestMtu(517)` |
| 3 | `10-porting-guide.md:92` | `ble.go:314-352` | 매뉴얼은 "서비스·특성·구독·MTU 실패 시 연결을 끊고 재시도". Go는 끊지 않고(누수), MTU 실패는 재시도 없이 블록 20 |
| 4 | `10-porting-guide.md:72` | `ble.go:234-250, 270-275` | 매뉴얼 "timeout → retry". Go 스캔은 ctx 만료까지 하나이고, 다른 스캔 오류일 때만 재시도 |
| 5 | `02-ble-transport.md` §13.7(351행) | `ble.go:89-92`, `connector.go:48-52` | 매뉴얼 "Close 멱등". Go BLE `Close`에는 멱등 장치가 없음(계약은 멱등 요구) |
| 6 | `02-ble-transport.md`:182 | `ble.go:98-109` | "청크는 락으로 직렬화"는 송신에만 해당. `rx`는 락 없이 버퍼를 만진다 |
| 7 | `00-agent-guide.md`:142 | `ble.go:345` | 표 머리 "Read/notify" — 원본은 indication 구독 |

- `{{ADR_DIR}}0010-timeouts-and-cancellation.md` 끝에 "## M3 재검토 (YYYY-MM-DD): `expires_at`을 남은 timeout으로 줄이지 않고 유지한다 — 설계 구체화 16(재시도 대기 중 수명이 0에 가까워지면 `TIME_EXPIRED`로 재시도만 늘어난다, Go도 `defaultExpiration` 고정)."

```bash
git add docs README.md
git commit -m "docs: M3 platform notes, getting started, error tables, SDD/ADR sync and manual-vs-source mismatches

Refs: FR-006, FR-112, NFR-006, ADR-0003, ADR-0010
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: M3 인계 노트**

`{{WORKFLOW_FILE}}` §10 형식으로 `docs/handoff/<YYYY-MM-DD>-m3-transport-keystore.md`를 쓴다. 반드시 담을 것:
- **완료:** 병합된 PR 목록(Task별), 모듈별 테스트 수(`jvmTest` + `iosSimulatorArm64Test` 실행 결과), ADR-0003 스파이크 판정, Task 14 실차 확인 표(그대로).
- **결정:** 설계 구체화 1~18 중 구현 중 바뀐 것, 사용자 승인 답.
- **막힌 점(M4 이후):** Android 실기기 BLE 미확인(사용자 환경 iPhone만), 골든 픽스처 실제 수집 미실시(스크립트만 — M4 첫 단계에서 사용자 실행), `MessageStatus` raw fault 판정 중복(`Signer.kt`/`ResponseClassifier.kt` — M2 인계 그대로, M4에서 응답 해석을 다시 만질 때), 로그 시점 차이(M2 인계 — 선택), `VcsecRejected` 원시 코드 보존(M4 L58), `TestValidPIN`(M5), 실시간 블루투스 상태 흐름(Kable 미제공 — 필요하면 플랫폼별 구현), Kable `observe`/`connect` 동작 중 실차에서만 드러난 것(Task 14 결과).
- **다음 한 걸음:** M4 상세 계획(`{{PLANS_DIR}}<날짜>-m4-pairing-vcsec.md`) 작성 → 🛑 승인. 첫 작업 후보: 사용자에게 `collect-golden.sh` 실행 요청 + `addKeyRequest`(ADR-0009).
- **읽을 파일:** `{{PATHS_FILE}}`, `{{AGENTS_FILE}}`, `{{SDD_FILE}}` §4(키 등록), ADR-0009, `{{MANUAL_DIR}}10-porting-guide.md` §10, `05-command-catalog.md` B, 이 노트.

서브에이전트 실행(subagent-driven-development)이었다면 원장의 판정(Ruling)을 M2와 같은 형식으로 `docs/handoff/<YYYY-MM-DD>-m3-rulings.md`에 옮긴다.

```bash
git add docs/handoff
git commit -m "docs(handoff): M3 transport, keystore and facade handoff note

Refs: FR-001, FR-020, FR-025, FR-031
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

PR(`m3/docs-handoff`) → verify.sh → AI 리뷰 → 병합. 그다음 컨텍스트가 길면 `/clear`를 제안한다(CLAUDE.md §4).

---

## 계획 자체 점검 (writing-plans 셀프 리뷰)

**1. 스펙 대응(로드맵 M3 행 + PRD M3 FR):**

| 항목 | Task |
|---|---|
| Kable 스파이크(실차, ADR-0003) | 1 |
| `KableScanner`/`KableTransport`(SDD 이름) | 3(`BleTransport`), 4(`BleTransportFactory`), 5(Kable 바인딩) — SDD 이름은 Task 5/15에서 실제 이름으로 고친다 |
| FR-001 스캔·RSSI·connectable, FR-030 | 2, 4, 5, 13 |
| FR-002 GATT·MTU·블록 길이 | 1, 3, 5 |
| FR-003 프레이밍·재조립 | 3(M0 `Framer`/`Reassembler` 재사용) |
| FR-004 재시도 | 4 |
| FR-005 해제(멱등) | 3, 11, 13 |
| FR-006 권한 오류 | 4, 5, 14 |
| FR-020/021 키 생성·보관·수준 | 6, 7, 8 |
| FR-025/032 키 목록·요약, FR-031 VehicleStatus | 10, 13 |
| FR-028 로컬 키 삭제 → 캐시 | 2, 9(keyId 규칙), 13 KDoc(설계 구체화 9) |
| FR-112 / NFR-006 로거 | 3(TX/RX), 11(`SafeLogger`), 14(`DebugLogger`) |
| NFR-004 키 보안 | 6~8(내보내기 API 없음, 메모리 지움) |
| 플랫폼 세션 캐시 | 9 |
| `TeslaBle` 파사드·`connect` | 13 |
| 샘플 앱(스캔·연결·listKeys·vehicleStatus) | 14 |
| 골든 수집 스크립트 | 15 |
| `platform-notes.md`·`getting-started.md` | 15 |
| M2 인계 "막힌 점" | `TransportFactory`(2), 오래된 캐시 감지(12), 일회용 세션·영구 닫힘(11), 세션 갱신 저장(12), `keyId` 유도(13), `incoming` 버퍼링(2, 3), 스코프·로거(11, 13), VCSEC 우회·`isListening`(11; `connect()`가 `dispatcher.start()`로 수신을 시작하므로 별도 확인 불필요), ADR-0010 재검토(15), 쓰기 중 취소(3), `FakeVehicle` 키 분리·`NonCancellable` 테스트(11), `errors.md` SKIE 문장(15), 단조 시계 노트(15). `MessageStatus` 중복·로그 시점·`VcsecRejected`·`TestValidPIN`은 M4 이후로 명시(15) |

**2. 자리표시자 점검:** "TBD"·"나중에" 없음. 플랫폼 cinterop/SKIE 이름처럼 컴파일러로만 확정되는 곳은 "컴파일러가 알려 주는 대로 맞춘다"와 함께 의도(지켜야 할 동작)를 고정했다 — Task 5(`CBManager.authorization`), 8(CF 캐스트), 13·14(SKIE 이름). Task 1 판정 (c)와 Task 8 Step 1(-34018)은 결과에 따른 분기를 명시했다.

**3. 타입 일관성:** `ScanEvent`(2) ↔ `BleTransportFactory.scan`(4) ↔ `TeslaBle.scan`(13) ↔ 샘플(14). `OpenResult`/`ConnectorScan`/`ScanHit`(4) ↔ `KableGattConnector`(5). `KeyAttempt`/`KeyCreationChain`/`SoftwareKeyPair`/`SoftwareVehicleKey`(6) ↔ 7, 8. `KeychainRead`/`KeychainItems`(8) ↔ 9(같은 모양 복제). `VehicleInfoQueries`(10) ↔ `VehicleSession.info`(10) ↔ `DefaultVehicle`(13). `SessionEvent`·`markStale`·`VehicleSession(…, eventScope)`(12) ↔ `TeslaBle.connect`(13). `SafeLogger`(11) ↔ 13. `VehicleDomain.of/wire`(2) ↔ 13.

**4. Review Focus:** 다섯 항목 모두 소유 Task에 테스트가 있다 — 1: Task 3 `linkDropCompletesIncomingAndMarksDisconnected`, Task 13 `linkDropDuringCommandIsUncertainThenNotConnected`; 2: Task 3 `buffersNotificationsArrivingBeforeAnyCollector`; 3: Task 4 `failsFastWithoutRetryOnPermissionOrBluetoothOff`, Task 5 `mapsKableExceptionsToTransportErrors`; 4: Task 11 `connectAfterDisconnectIsRejected`, Task 13 `commandsAfterDisconnectReturnNotConnectedAndKeepCache`; 5: Task 10 `concurrentInfoQueriesAreSerialized`.
