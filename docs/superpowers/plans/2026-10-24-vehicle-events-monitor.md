# Vehicle Events and Monitoring Samples Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 라이브러리에 `Vehicle.events`(평문 브로드캐스트 + 원본 바이트), `VehicleStatus.gear`, iOS 키 접근성 선택을 추가하고, 이를 문서대로만 쓰는 모니터링 샘플(Android + iOS)을 만든다.

**Architecture:** 디스패처가 지금 버리는 `TO_DOMAIN` 프레임 중 평문 브로드캐스트 게이트를 통과한 것을 별도 `SharedFlow`로 내보내고(응답 매칭 경로는 그대로), `:application`의 매핑이 이를 공개 `VehicleEvent`로 바꾸며, `DefaultVehicle.events`가 링크 손실·`disconnect()`에서 완료되는 흐름으로 노출한다. iOS 키 저장소는 생성 시 접근성을 고를 수 있게 한다. 샘플은 라이브러리 PR 머지 후 공개 API만으로 탭 UI, 자동 연결·재연결, 백그라운드, 로그 파일을 구현한다.

**Tech Stack:** Kotlin Multiplatform, Wire, coroutines/Flow, Kable, Security.framework; Compose + 포그라운드 서비스; SwiftUI + SKIE.

**Spec:** `docs/superpowers/specs/2026-10-24-vehicle-events-monitor-design.md`(라이브러리·전체), 샘플 부분은 Task 1에서 `samples/documents/md/04-monitor-app.md`로 옮긴다. 추적: #91.

## Global Constraints

- 새 추적 ID: FR-035(`events`), FR-036(`gear`), FR-037(iOS 키 접근성), SFR-010~(모니터 앱).
- 역할: Task 3~8 `library-developer`(opus/max), Task 9 `android-developer`, Task 10 `ios-developer`(sonnet/high), HTML ko/ja `docs-translator`. 모든 역할은 AGENTS.md §9 Codex 협의 규칙을 따른다.
- 원본 Go는 브로드캐스트를 버린다. drop 판정·응답 매칭은 바꾸지 않고, 방출은 "원본과 다른 의도적 동작"으로 SDD §12에 적는다.
- 이벤트 게이트 = `ObservationLog.isPlainBroadcast`와 같은 조건(from `DOMAIN_VEHICLE_SECURITY`, to `DOMAIN_BROADCAST`, `request_uuid` 비어 있음, `signature_data == null`, `session_info == null`). 복호화한 평문은 절대 내보내지 않는다(FR-112).
- `raw`는 수신 `RoutableMessage` 프레임 바이트 전체의 방어 복사본, VIN 마스킹 없음(앱이 저장 전 마스킹).
- 디스패처는 이벤트 때문에 기다리지 않는다(`tryEmit`). 버퍼 64, 가득 차면 **새 프레임을 버리고**(설계 §5.3의 `DROP_OLDEST` 예시를 이 계획이 대체: `tryEmit` 실패로 버린 수를 정확히 셀 수 있고, 이미 버퍼에 든 프레임의 순서가 보존된다) 연결별 카운트, 첫 drop과 100번째 배수마다(1·100·200…) `WARN`.
- `events`는 `connection`이 `Disconnected`가 되거나 `disconnect()`가 불리면 **정상 완료**(예외 없음). replay 0.
- 키 접근성 기본값은 `WHEN_UNLOCKED`(지금과 같음). 샘플만 `AFTER_FIRST_UNLOCK`. 기존 키 마이그레이션 없음.
- 공개 API: `explicitApi`, KDoc, `apiDump`, `ByteArray` 방어 복사. `VehicleStatus`에 필드 추가는 배포 전이라 허용(PR에 명시).
- 샘플: `:sdk` 공개 API 중 문서에 available만. `INTERNET` 없음. 로그 파일 VIN 마스킹(앞 3·뒤 4 유지, 같은 길이). 조회 주기 기본 30 s. 재연결 백오프 2 s → 최대 30 s. 화면 유지는 앱이 보일 때만.
- 문서: 라이브러리 PR마다 `lib-docs-sync`(MD + HTML ko/en/ja), 샘플 PR마다 샘플 스펙 두 벌. 각 PR은 `tools/ci/verify.sh` + AI 리뷰 + rebase merge.

## Review Focus

1. 이벤트 구독자가 느리거나 없을 때 응답 매칭이 늦어지면 안 된다 → Task 4 Step 1 `slowCollectorDoesNotDelayReplies`.
2. 같은 프레임에 `vehicleStatus` + `authenticationRequest`가 같이 오면 이벤트 두 개, `raw` 동일 → Task 5 Step 1.
3. 링크 손실 뒤 `events` 수집이 영원히 매달리면 안 된다 → Task 6 Step 1 `eventsCompleteOnLinkLoss`.
4. 잠긴 iPhone에서 키 접근 실패가 일반 `PlatformFailure`로 섞이면 앱이 재생성을 유도하지 못한다 → Task 7 Step 1 `lockedKeychainMapsToLocked`.
5. 샘플이 백그라운드에서 끊긴 뒤 수동 "끊기"를 눌렀는데 재연결이 계속되면 차의 연결 슬롯을 계속 잡는다 → Task 9/10 상태 기계 테스트 `manualDisconnectStopsReconnect`.

---

### Task 1: 샘플 모니터 앱 스펙 분리 (docs)

**Files:**
- Create: `samples/documents/md/04-monitor-app.md`, `samples/documents/html/{ko,en,ja}/04-monitor-app.html`
- Modify: `samples/documents/md/README.md`, `01-requirements.md`(SFR-010~017), 각 HTML 사이드바(4쪽 → 5쪽), `docs/superpowers/specs/2026-10-24-vehicle-events-monitor-design.md` §6 머리에 "정본은 `04-monitor-app.md`" 메모(본문은 승인 기록으로 유지), §7·§8의 FR-113/114·PATHS 키 항목 정리

**Interfaces:**
- Produces: SFR-010 탭 구조, SFR-011 VIN 저장·자동 연결·수동 연결, SFR-012 자동 재연결(백그라운드 포함)·수동 끊기 시 중지, SFR-013 포그라운드 화면 유지, SFR-014 실시간 상태(events), SFR-015 주기 조회(30 s, 변경 가능), SFR-016 로그 파일(JSON Lines, 스키마는 아래 정본, VIN 마스킹, 회전), SFR-017 Mac 가져오기 스크립트. 로그 줄 스키마(아래)는 Task 9·10이 같은 이름으로 쓴다.

로그 줄 스키마의 정본은 `samples/documents/md/04-monitor-app.md` §Log format이다(첫 줄 `session`, 종류 `session`/`event`/`observe`/`query`/`link`/`mark`, `link.state`에 `stopped`, `delayMs`). Task 9·10과 테스트는 그 절을 그대로 따른다.

- [ ] **Step 1:** MD 작성(영어) — 설계 §6 내용 + 위 스키마 + 로그 위치(Android `getExternalFilesDir("logs")`, iOS `Documents/logs/` + `UIFileSharingEnabled`·`LSSupportsOpeningDocumentsInPlace`) + 파일 회전(파일 20 MB, 전체 200 MB) + mark 버튼(로그 탭, 메모 80자, VIN 모양 마스킹) + 백그라운드 설정 + 잠긴 폰 키(`AFTER_FIRST_UNLOCK` 새 별칭 `teslable-monitor`, 재등록 안내).
- [ ] **Step 2:** HTML en, `docs-translator`로 ko/ja, 빌더로 사이드바 5쪽, `tools/ci/check-manual-i18n.sh` OK.
- [ ] **Step 3:** 커밋 `docs: monitor app spec (SFR-010..017, md + html ko/en/ja)`; 이 계획 파일도 함께 PR → 머지.

### Task 2: 스파이크 — iOS 백그라운드 재연결과 잠긴 폰 키 (버림)

**Files:** 브랜치 `spike/ios-background`(머지하지 않음), 결과는 `docs/handoff/2026-10-xx-ios-background-spike.md`

- [ ] **Step 1:** `ios-developer`가 iOS 샘플 사본에 `UIBackgroundModes: [bluetooth-central]`과 버튼 3개를 붙인 실기기 빌드를 만든다: (a) 현재 `connect(vin, key)`(이름 필터 스캔), (b) 앱이 백그라운드로 간 뒤 링크 손실 → 30 s 간격 재시도, (c) 잠금 화면에서 `startSession(VCSEC)`. 라이브러리 코드는 바꾸지 않는다(스파이크용 Kable 직접 호출도 금지 — 관찰만).
- [ ] **Step 2:** 사용자에게 체크리스트 전달: 폰을 잠그고 차에서 멀어졌다 돌아오기(5분), 잠긴 상태로 30분 유지, 결과 줄 복사.
- [ ] **Step 3:** 결과를 인계 노트로 정리하고 사용자에게 보고 → 판단 요청: 스캔 필터 변경(Task 8) 필요 여부. **이 결정 전까지 Task 8과 Task 10의 백그라운드 재연결 부분은 시작하지 않는다.** Task 3~7, 9는 병행.

### Task 3: `VehicleStatus.gear` (FR-036)

**Files:**
- Modify: `domain/src/commonMain/kotlin/dev/mirolab/teslable/model/VehicleStatus.kt` (enum `Gear`, 필드 `gear`), `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vcsec/VcsecInfoMapping.kt:42-59`
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/vcsec/VcsecInfoMappingTest.kt`(없으면 생성)

**Interfaces:**
- Produces: `public enum class Gear { UNKNOWN, PARK, DRIVE, REVERSE, NEUTRAL }`; `VehicleStatus(lockState, closures, sleepStatus, userPresence, tonneauPercentOpen, gear: Gear)` — `gear`는 마지막 인자. `VcsecInfoMapping.status(wire)`가 채운다.

- [ ] **Step 1: 실패 테스트** — `gearDriveMapsToDrive`: `WireVehicleStatus(gear = Gear_E.GEAR_DRIVE)` → `status(...).gear == Gear.DRIVE`; `gearAbsentIsUnknown`: 기본값 → `Gear.UNKNOWN`; `gearOutsideSchemaIsUnknown`: unknownFields에 tag 7 varint 9 → `Gear.UNKNOWN`.
- [ ] **Step 2:** `./gradlew :application:jvmTest --tests '*VcsecInfoMapping*'` → FAIL(컴파일).
- [ ] **Step 3:** 구현(기존 enum 매핑과 같은 `unknownVarint(tag)` 규칙). 기존 `VehicleStatus(...)` 생성 호출부 수정.
- [ ] **Step 4:** 같은 명령 PASS, `./gradlew apiDump` 후 diff 확인(`Gear`, `VehicleStatus` 생성자·`copy`·`component6`).
- [ ] **Step 5:** 커밋 `feat: expose gear in VehicleStatus` (Refs: FR-036).

### Task 4: 디스패처의 평문 브로드캐스트 흐름

**Files:**
- Create: `application/src/commonMain/kotlin/dev/mirolab/teslable/application/observe/BroadcastGate.kt` (`internal fun isPlainBroadcast(message: RoutableMessage): Boolean`)
- Modify: `ObservationLog.kt:115-120`(위 함수 사용), `Dispatcher.kt` (`plainBroadcasts`, TO_DOMAIN 분기 L533-538 근처)
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/dispatcher/DispatcherBroadcastTest.kt`

**Interfaces:**
- Produces: `Dispatcher.plainBroadcasts: SharedFlow<ByteArray>` (public on `:application`, `@InternalTeslableApi` 아님 — `:application`은 공개 API 표면이 아님을 확인하고 BCV 덤프만 갱신). 원소는 수신 프레임 바이트 복사본.

- [ ] **Step 1: 구조 커밋** — 게이트 함수를 `BroadcastGate.kt`로 옮기기만(`struct: extract plain broadcast gate`). `./gradlew :application:jvmTest` PASS.
- [ ] **Step 2: 실패 테스트**(`dispatcherHarness`, `transport.deliver`, `runCurrent`): `plainBroadcastIsEmitted`(게이트 통과 프레임 → 같은 바이트 1건); `signedOrAddressedFrameIsNotEmitted`(`signature_data` 있음, `routing_address` 있음, `request_uuid` 16 B, `session_info` 있음 각각 → 0건); `frameFromOrToAnotherDomainIsNotEmitted`(게이트의 from/to 조건: from INFOTAINMENT, from 주소, to VCSEC 도메인, 빈 to → 0건); `broadcastStillDroppedFromRouting`(observe 로그의 `[drop:to-domain]` 줄 그대로, 열린 VCSEC 요청에 배달 안 됨); `slowCollectorDoesNotDelayReplies`(구독자가 `awaitCancellation`으로 멈춘 상태에서 브로드캐스트 100건 + VCSEC 응답 → 응답 정상 완료, 가상 시간 0); `overflowCountsAndWarns`(수집기가 첫 값을 **꺼낸 뒤**(첫 프레임 → `runCurrent`) 멈춘 상태에서 65건 더 → 버퍼 64 + 수집기 1, 66번째에서 첫 drop → `WARN` 1줄, 카운트 1. 실측: 수집기가 돌기 전에 한꺼번에 오면 첫 값도 버퍼에 남아 65번째가 첫 drop); `overflowWarnsAgainEveryHundredthDrop`(WARN은 drop 1·100·200번째); `emittedBytesAreACopyOfTheReceivedFrame`(받은 배열과 다른 인스턴스).
- [ ] **Step 3:** `./gradlew :application:jvmTest --tests '*DispatcherBroadcast*'` → FAIL.
- [ ] **Step 4:** 구현: `MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)`(기본 SUSPEND) + `tryEmit` 실패 시 카운트·로그. 디코딩은 이미 `process`에서 끝났으므로 재사용.
- [ ] **Step 5:** PASS, `apiDump`, 커밋 `feat: dispatcher emits plain VCSEC broadcasts` (Refs: FR-035).

### Task 5: `VehicleEvent` 모델과 매핑

**Files:**
- Create: `domain/src/commonMain/kotlin/dev/mirolab/teslable/model/VehicleEvent.kt`, `application/src/commonMain/kotlin/dev/mirolab/teslable/application/vcsec/VcsecBroadcasts.kt`
- Test: `application/src/commonTest/kotlin/dev/mirolab/teslable/application/vcsec/VcsecBroadcastsTest.kt`

**Interfaces:**
- Produces:
  - `public sealed interface VehicleEvent { public val raw: ByteArray /* copy on each get */; public val unknownFields: List<String> }`
  - `public class VehicleEvent.Status(public val status: VehicleStatus, raw, unknownFields)`
  - `public class VehicleEvent.AuthenticationRequest(public val requestedLevel: AuthenticationLevel, public val reasons: List<AuthenticationReason>, raw, unknownFields)`
  - `public class VehicleEvent.Unrecognized(raw, unknownFields)`
  - `public enum class AuthenticationLevel { NONE, UNLOCK, DRIVE, UNKNOWN }`; `public enum class AuthenticationReason { NOT_DOCUMENTED, IDENTIFICATION, POWER_ON_VEHICLE_REQUEST, GTW_REQUEST, UI_UNLOCK_PASSIVE_AUTH, PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL, PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL, PASSIVE_UNLOCK_AUTOPRESENT_DOOR, ENTERED_HIGHER_AUTH_ZONE, WALK_UP_UNLOCK, IMMOBILIZER, UNKNOWN }`
  - `internal object VcsecBroadcasts { fun events(frame: ByteArray): List<VehicleEvent> }` — 프레임을 다시 디코딩해 `FromVCSECMessage`를 얻는다.
  - `unknownFields` 표기: 경로 문자열, 최상위 `"53"`, `vehicleStatus` 하위 `"1.8"`, `authenticationRequest` 하위 `"3.<n>"`. 도착 순, 중복 유지.
  - `equals`/`hashCode`는 `raw` 내용 기준(`contentEquals`), `toString`은 `raw`를 길이로만.

- [ ] **Step 1: 실패 테스트** — `statusOnly`(vehicleStatus만 → `[Status]`, `status.gear` 포함); `statusAndAuthTogether`(둘 다 → `[Status, AuthenticationRequest]`, 두 `raw` 동일, `reasons == [PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL]`); `unknownOnly`(field 53만 → `[Unrecognized]`, `unknownFields == ["53"]`); `vehicleStatusField8Recorded`(→ `Status.unknownFields == ["1.8"]`); `undecodablePayload`(게이트 통과·페이로드 깨짐 → `[Unrecognized]`, `unknownFields == []`); `rawIsDefensiveCopy`(get 결과 변경이 다음 get에 영향 없음); `reasonOutsideSchemaIsUnknown`.
- [ ] **Step 2:** FAIL 확인 (`./gradlew :application:jvmTest --tests '*VcsecBroadcasts*'`).
- [ ] **Step 3:** 구현. `closures` 규칙과 enum 매핑은 `VcsecInfoMapping.status` 재사용. 하위 메시지 unknown 필드 번호 추출은 `ObservationLog`의 기존 헬퍼를 공용 internal 함수로 옮겨 쓴다(옮기는 것은 별도 `struct:` 커밋).
- [ ] **Step 4:** PASS, `apiDump`, 커밋 `feat: map plain VCSEC broadcasts to VehicleEvent` (Refs: FR-035).

### Task 6: `Vehicle.events` + 문서 (FR-035, FR-036 마무리)

**Files:**
- Modify: `sdk/src/commonMain/kotlin/dev/mirolab/teslable/Vehicle.kt:24`, `DefaultVehicle.kt:35`(생성자 인자 `broadcasts: Flow<ByteArray>`), `TeslaBle.kt:117-122`(`dispatcher.plainBroadcasts` 전달)
- Test: `sdk/src/commonTest/kotlin/dev/mirolab/teslable/VehicleEventsTest.kt`
- Docs: `documents/md/11,12,14,16,18-lib-*.md` + HTML ko/en/ja, `docs/prd/PRD.md`(FR-035·036 추가, §3.2·M5 표), `docs/sdd/SDD.md`(§2.3, §3.3, §12), `documents/md/19-lib-architecture.md`

**Interfaces:**
- Consumes: `Dispatcher.plainBroadcasts`(Task 4), `VcsecBroadcasts.events`(Task 5).
- Produces: `public val Vehicle.events: Flow<VehicleEvent>` (인터페이스 멤버). Swift: `for await e in vehicle.events { switch onEnum(of: e) { … } }` (SKIE 0.10.15 `SkieSwiftFlowIterator.next()`는 throws가 아니다. `for try await`도 컴파일되지만 필요 없다 — 기존 문서·샘플과 같은 `for await`).

- [ ] **Step 1: 실패 테스트**(`FakeVehicle`/`FakeTransport.deliver`): `eventsDeliverBroadcast`(연결 후 브로드캐스트 1건 → `Status` 1건); `eventsCompleteOnLinkLoss`(`transport.close()` → `Disconnected(null)`, **그리고** 수신 흐름을 열어 둔 채 `Disconnected(TransportError.Disconnected)`를 내는 전송 — 스파이크: 거리 이탈이 어느 쪽인지 문서에 없음 → 둘 다 정상 완료, 끝난 뒤 시작한 수집도 바로 끝남); `eventsCompleteOnDisconnect`(disconnect 뒤 시작한 수집이 가상 시간 없이 끝남); `noReplayBeforeSubscribe`(늦게 온 수집자는 그 뒤 프레임만); `newConnectionHasNewEvents`.
- [ ] **Step 2:** FAIL 확인 (`./gradlew :sdk:jvmTest --tests '*VehicleEvents*'`).
- [ ] **Step 3:** 구현: `broadcasts.transform { VcsecBroadcasts.events(it).forEach { e -> emit(e) } }`를 `connection.first { it is Disconnected }` 또는 `closed`까지만 수집(예: `channelFlow` + 종료 조건 `select`). 실제: `channelFlow` 안에서 `UNDISPATCHED`로 구독 → `combine(connection, closed).first { it }` → 구독 취소(앱 수집 코루틴은 취소되지 않음), `.buffer(RENDEZVOUS)`(버퍼는 디스패처 64 하나). `VcsecBroadcasts`(Task 5, `internal`)는 `:sdk`가 부를 수 있게 먼저 `struct:` 커밋으로 공개.
- [ ] **Step 4:** PASS, `apiDump`, `./gradlew check`.
- [ ] **Step 5:** 문서 — `lib-docs-sync`: 14 Part B2 "드롭만 함" → "`events`로 나옴"(게이트, 이벤트 종류, 반복은 변화가 아님, 트렁크 주의, `gear` 노출, A2 표 `gear` 행 "public `gear`"), 12 이벤트 구독 예제(Kotlin·Swift), 11 상태 표, 16 오버플로 WARN, 18 observe와 events 관계, 19 디스패처 경로. PRD/SDD 갱신. (`gear` 항목은 PR #94에서 이미 반영 — 이 작업에서는 건너뜀. 샘플 스펙 04·01은 "`Vehicle.events` 사용 가능, iOS 키 접근성(FR-037)만 대기"로.)
- [ ] **Step 6:** 커밋(`feat: Vehicle.events …`, `docs: …`), `verify.sh`, AI 리뷰, PR(Refs #91) → 머지. #91 체크.

### Task 7: iOS 키 접근성 선택 (FR-037) + ADR-0014

**Files:**
- Modify: `domain/src/commonMain/kotlin/dev/mirolab/teslable/port/VehicleKeyStore.kt` (`KeyAccessibility`, `VehicleKey.accessibility`, `getOrCreate` 오버로드), `domain/.../model/VehicleError.kt:216`(`KeyStoreError.Locked`), `adapter-crypto/src/iosMain/.../SecureEnclaveKeys.kt:121-125`, `KeychainItems.kt:63-66,119`, `AppleVehicleKeyStore.kt`, Android·JVM 키 저장소(값 보고만)
- Create: `docs/adr/0014-ios-key-accessibility.md`
- Test: `adapter-crypto/src/commonTest/...`(공통 계약), `adapter-crypto/src/iosTest/...`(속성 매핑 — Keychain 왕복은 시뮬레이터 테스트 바이너리에서 불가, 17번 문서 참고: 매핑 함수 단위 테스트 + 샘플 앱 실기기 확인)
- Docs: `13-lib-pairing`, `17-lib-platform-notes` §Key storage, `16-lib-errors`(Locked) + HTML, PRD FR-037, SDD §8.2

**Interfaces:**
- Produces: `public enum class KeyAccessibility { WHEN_UNLOCKED, AFTER_FIRST_UNLOCK }`; `VehicleKey.accessibility: KeyAccessibility`; `VehicleKeyStore.getOrCreate(alias: String, policy: KeyPolicy, accessibility: KeyAccessibility): VehicleResult<VehicleKey>` (기존 2인자 버전은 `WHEN_UNLOCKED`로 위임); `VehicleError.KeyStoreError.Locked`. 기존 별칭에 다른 접근성을 요청하면 **기존 키를 그대로** 돌려주고 `accessibility`로 실제 값을 알린다. Android·JVM은 요청 값을 무시하고 실제 값(`AFTER_FIRST_UNLOCK`)을 보고 — ADR에 근거.

- [ ] **Step 1: 실패 테스트** — `accessibilityMapsToSecAttr`(`WHEN_UNLOCKED` → `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, `AFTER_FIRST_UNLOCK` → `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`); `lockedKeychainMapsToLocked`(OSStatus `errSecInteractionNotAllowed` -25308 → `KeyStoreError.Locked`); `existingAliasKeepsItsAccessibility`(인메모리 계약 테스트); `twoArgOverloadIsWhenUnlocked`.
- [ ] **Step 2:** FAIL 확인 (`./gradlew :adapter-crypto:iosSimulatorArm64Test :domain:jvmTest`).
- [ ] **Step 3:** 구현. Secure Enclave는 `SecAccessControlCreateWithFlags`의 protection 인자, 소프트웨어 대체 키는 `AppleKeychainItems(accessible)`; 접근성은 키 속성에서 다시 읽어 `VehicleKey.accessibility`에 채운다.
- [ ] **Step 4:** PASS, `apiDump`, ADR-0014(배경: 잠긴 폰 모니터링, 선택지, 기본값 유지 이유, 위험, Android 근거), 문서·PRD·SDD.
- [ ] **Step 5:** 커밋, `verify.sh`, AI 리뷰(`security` 라벨), PR → 머지. #91 체크.

**구현 기록(2026-10-24, ADR-0014):**
- 다시 읽기: 기존 질의에 `kSecReturnAttributes`를 더해 항목의 `kSecAttrAccessible`(`pdmn`)을 읽는다(결과 딕셔너리의 `kSecValueRef`는 retain). 라벨 같은 별도 메타데이터는 저장하지 않는다. 보고되지 않거나 모르는 값이면 `WHEN_UNLOCKED`로 보고하고 WARN.
- `Locked`: OSStatus -25308(조회·읽기·쓰기·삭제)과 `SecKeyCreateRandomKey`의 CFError(OSStatus 도메인, -25308)만. 생성 중 `Locked`면 소프트웨어 대체 없음(`KeyCreationChain` + 내부 `KeyStoreLockedException`). `temporary = true`.
- 범위를 좁힘: FR-037의 `Locked`는 키 저장소 호출에 한정한다. 잠금 전에 얻은 `WHEN_UNLOCKED` 키 핸들의 핸드셰이크 ECDH 실패는 지금 `startSession` 시간 초과다(Codex는 이 작업에 넣거나 FR-037을 명시적으로 좁히라고 함 → 좁히고 문서화, 후속 작업 여부는 사용자 결정).
- 함께 바뀐 파일: `KeychainAccessibility.kt`(+Test), `KeyCreationChain.kt`, `SoftwareVehicleKey.kt`, `InMemoryVehicleKeyStore.kt`, `testing/FakeVehicleKeyStore.kt`(iOS처럼 생성 때 접근성 기억), `adapter-storage` 복사본 머리 주석(차이 기록), `18-lib-troubleshooting`, 매뉴얼 `index.html` 13번 카드. 구조 변경은 `struct:` 커밋 하나(Secure Enclave 키 생성의 CFError 범위 분리).
- 추가 테스트: `keychainQueryResultCarriesTheItemsAccessibility`, `enclaveQueryResultCarriesTheKeysAccessibilityAndOutlivesTheResult`, `protectionClassIsComparedByContentNotIdentity`, `secureEnclaveLockedErrorIsRecognized`, `creatingAKeyWhileLockedIsLockedAndLeavesNoSoftwareKey`, `unreportedProtectionClassReadsAsWhenUnlockedWithAWarning`, `lockedHardwareAttemptIsLockedAndNeverFallsBackToSoftware`, `keystoreKeysReportAfterFirstUnlock`, `lockedKeyStoreIsTemporaryAndNeverExecuted`.
- Swift 표면(SKIE 생성 코드로 확인): `getOrCreate(alias:policy:)`, `getOrCreate(alias:policy:accessibility:)`, `KeyAccessibility { .whenUnlocked, .afterFirstUnlock }`, `VehicleKey.accessibility`, `VehicleErrorKeyStoreErrorLocked`(`onEnum`의 `.locked`).
- Codex 구현 리뷰 반영: `delete`는 한쪽이라도 -25308이면 `Locked`(`fix:` 커밋, `deleteReportsLockedWhenEitherCopyIsLocked`). 캐시 세션 복원도 ECDH를 해 잠긴 동안 건너뛰고 세션 없는 `disconnect()`가 캐시를 비운다는 점을 ADR에 정정. `SecKeyRef` 핸들이 해제되지 않는 기존 동작 때문에 "매 연결마다 키를 다시 읽으라"는 권고는 철회(후속 작업 후보).

### Task 8: (조건부) 스캔 필터 변경

Task 2 결과로 사용자가 승인한 경우에만. 이 계획을 먼저 고쳐 정확한 단계를 적은 뒤 진행한다(AGENTS §4 "계획에 없는 일은 계획을 먼저 고친다").

### Task 9: Android 모니터 앱 (SFR-010~017)

**Files:**
- Modify/Create under `samples/android/src/main/kotlin/dev/mirolab/teslable/sample/`: `MonitorService.kt`(포그라운드 서비스, `TeslaBle` 소유), `ConnectionLoop.kt`(상태 기계), `LogWriter.kt`(JSON Lines, 회전, `LogSink` 인터페이스), `VinMask.kt`, `ui/LiveTab.kt`, `ui/QueryTab.kt`, `ui/LogTab.kt`, `ui/ControlTab.kt`(기존 화면 이동), `MainActivity.kt`(탭, `FLAG_KEEP_SCREEN_ON`)
- Modify: `AndroidManifest.xml`(`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `<service foregroundServiceType="connectedDevice">`)
- Create: `samples/android/src/test/kotlin/dev/mirolab/teslable/sample/{ConnectionLoopTest,LogWriterTest,VinMaskTest}.kt`, `tools/samples/pull-monitor-logs.sh`

**Interfaces:**
- Consumes: `Vehicle.events`, `VehicleEvent.*`, `VehicleStatus.gear`, `vehicleStatus()`, `keySummary()`, `listKeys()`, `sessions`, `connection`, `keys.getOrCreate(alias, policy, accessibility)` — 문서 페이지 12·13·14·16·17·18.
- Produces: `ConnectionLoop(connect: suspend () -> VehicleResult<Vehicle>, clock, backoff = 2.seconds..30.seconds)` 상태 `Idle | Connecting(attempt) | Connected(vehicle) | Backoff(attempt, delay) | Stopped`; `LogLine` 직렬화는 Task 1 스키마 그대로.

- [ ] **Step 1: 실패 테스트** — `VinMaskTest.masksVinInBytesKeepingLength`(ASCII VIN 포함 바이트 → 앞 3·뒤 4 유지, 길이 동일); `LogWriterTest.rotatesAt20MbAndPrunesAt200Mb`, `eventLineMatchesSchema`, `unrecognizedFlag`; `ConnectionLoopTest.backoffDoublesTo30s`, `reconnectsAfterLinkLoss`, `manualDisconnectStopsReconnect`, `manualConnectSkipsBackoff`.
- [ ] **Step 2:** FAIL (`./gradlew :samples:android:testDebugUnitTest`).
- [ ] **Step 3:** 구현(테스트 대상 로직 먼저, 그다음 서비스·UI). 조회 주기 30 s(설정 가능), 실시간 탭은 마지막 `Status`와 항목별 "마지막으로 바뀐 시각"(같은 값 반복은 갱신하지 않음).
- [ ] **Step 4:** PASS, `./gradlew :samples:android:assembleDebug`.
- [ ] **Step 5:** `pull-monitor-logs.sh android` (`adb pull /sdcard/Android/data/dev.mirolab.teslable.sample/files/logs/ samples/logs/android/<date>/`) — `shellcheck` 있으면 통과.
- [ ] **Step 6:** 실기기 체크리스트(설계 §8의 5)를 PR 본문에, 샘플 스펙 03·04 갱신(두 벌), 커밋, `verify.sh`, AI 리뷰(예제가 문서 호출 순서·오류 처리와 맞는지), PR → 머지. #91 체크.

### Task 10: iOS 모니터 앱 (SFR-010~017)

**Files:**
- Modify/Create under `samples/ios/Sources/`: `MonitorModel.swift`(앱 수명 단일 객체), `ConnectionLoop.swift`, `LogWriter.swift`, `VinMask.swift`, `Views/{LiveTab,QueryTab,LogTab,ControlTab}.swift`, `ContentView.swift`(TabView, `isIdleTimerDisabled` ↔ scenePhase)
- Modify: `project.yml` (`UIBackgroundModes: [bluetooth-central]`, `UIFileSharingEnabled: true`, `LSSupportsOpeningDocumentsInPlace: true`)
- Create: `samples/ios/Tests/{ConnectionLoopTests,LogWriterTests,VinMaskTests}.swift`; `tools/samples/pull-monitor-logs.sh`에 `ios` 분기(`xcrun devicectl device copy from --device <id> --domain-type appDataContainer --domain-identifier <bundle id> --source Documents/logs --destination samples/logs/ios/<date>/`)

**Interfaces:**
- Consumes: Task 9와 같은 공개 API(Swift 표면, 모든 인자 전달). 같은 로그 스키마·같은 상태 기계 이름.

- [ ] **Step 1~6:** Task 9와 같은 테스트 이름(Swift 관례로 `testMasksVinInBytesKeepingLength` 등), 같은 순서. 백그라운드 재연결 구현은 Task 2 결정(필요하면 Task 8 머지) 뒤에 한다. 실기기 체크리스트에 잠긴 폰 30분, 걸어 나갔다 돌아오기 포함. `samples/ios/README.md` runbook 갱신.

### Task 11: 실차 확인과 마무리

- [ ] **Step 1:** 사용자에게 두 앱 체크리스트 전달, 결과 수집.
- [ ] **Step 2:** 결과를 `17-lib-platform-notes`(백그라운드 실기기 확인 범위, Android 잠긴 상태 키) 문서 PR로 반영(`library-developer`).
- [ ] **Step 3:** 첫 로그 묶음을 `protocol-analyst`로 분석(보고서만) → 반영 여부는 `library-developer`.
- [ ] **Step 4:** 인계 노트 `docs/handoff/YYYY-MM-DD-events-monitor.md`, #91 닫기.
