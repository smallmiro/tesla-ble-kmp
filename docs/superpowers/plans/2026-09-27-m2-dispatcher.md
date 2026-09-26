# M2 디스패처·세션 계층 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** M1 프로토콜 계층(`Signer`, `ResponseClassifier`, `VehicleError`/`VehicleResult`, `TestVerifier`) 위에 Go `internal/dispatcher`와 `pkg/vehicle`의 클라이언트 경로를 포팅한다 — `Transport` 포트, `Dispatcher`(요청 조립·응답 매칭·세션정보 갱신·복호화·드롭 규칙), `SessionState`, `HandshakeFlow`, `SendWithRetry`, VCSEC `readUntil`·직렬화, Infotainment 응답 해석, 세션 캐시 포트·코덱·`InMemorySessionCache` — 그리고 차량 측을 결정적으로 재현하는 `FakeVehicle`/`FakeTransport`로 Go `dispatcher_test.go`(20개), `vehicle_test.go`(8개), `vcsec_test.go`(3개) 포팅본과 FakeVehicle 시나리오 7종을 JVM과 iOS 시뮬레이터에서 통과시킨다.

**Architecture:** `{{SDD_FILE}}` §1~§2의 헥사고날 구조. `:domain`에 포트(`Transport`, `SessionCache`, `TeslaLogger`)와 순수 코덱(`SessionCacheCodec`)·값 객체(`KeyId`, `SessionSnapshot`/`CachedSession`)를 더하고, `:application`에 `dispatcher/`(`PendingRequest`, `SessionState`, `Dispatcher`, `HandshakeFlow`), `vehicle/`(`SendWithRetry`, `VehicleSession`, `CommandTimeouts`), `vcsec/`(`VcsecResponses`, `VcsecCommands`), `infotainment/`(`InfotainmentResponses`, `InfotainmentCommands`), `cache/`(`SessionCacheSync`)를 만든다. `:adapter-storage`에는 `InMemorySessionCache`(벽시계는 여기서만), `:testing`에는 `FakeTransport`, `FakeVehicle`(도메인마다 M1 `TestVerifier` 하나를 감싼다), `RecordingSessionCache`, `RecordingLogger`를 만든다. 동시성은 SDD §5(차량당 스코프, 수신 코루틴 1개, `Channel(10)` + 가득 차면 드롭, `withTimeoutOrNull`, `CancellationException` 전파)를 따르고, 테스트는 `runTest`의 가상 시간(`testTimeSource`)만 쓴다. 새 외부 의존성은 없다.

**Tech Stack:** Kotlin 2.4.20 (K2, `explicitApi`), Gradle 9.6.0(configuration cache on) + JDK 17 툴체인, kotlinx-coroutines 1.11.0(`Channel`, `Mutex`, `select`, `withTimeoutOrNull`, `runTest`/`testTimeSource`), Wire 7.0.4 생성 코드(`com.tesla.generated.*`), okio `ByteString`/`Buffer`, M0/M1 `:domain`·`:adapter-crypto`·`:testing`.

**Spec:** `{{SDD_FILE}}` §1.3(Go 대응표), §2.1(`:domain` 포트: `Transport`, `SessionCache`, `TeslaLogger`), §2.2(`:application` 구성 요소), §2.6~§2.7, §3.2~§3.4(핸드셰이크·명령·응답·복구 시퀀스), §5(동시성 모델), §6(에러 모델), §7.1(세션 캐시 스키마, D26/ADR-0007), §9.3(FakeVehicle), §9.4(Go 테스트 포팅 목록 M2 행), §12; `{{PRD_FILE}}` FR-010~FR-012, FR-014(디스패처 측 규칙), FR-017~FR-018, FR-048~FR-049, FR-100~FR-102, NFR-003, NFR-007, NFR-012; ADR-0001(모듈 경계), ADR-0006(결과 모델), ADR-0007(세션 캐시), ADR-0009(M4 페어링, 참고), ADR-0010(타임아웃·취소). 참고 매뉴얼: `{{MANUAL_DIR}}01-architecture.md` §2~§6, `03-protocol.md` §9~§11, `08-errors.md` §4~§7, `10-porting-guide.md` §7~§9. 로드맵: `{{PLANS_DIR}}2026-09-26-roadmap.md` M2 행. 직전 인계: `{{HANDOFF_DIR}}2026-09-27-m1-protocol.md`("막힌 점"), `{{HANDOFF_DIR}}2026-09-27-m1-rulings.md`(L65~L120), M1 최종 리뷰 보고서의 "Recommendations for the M2 plan"·"Ledger triage".

**M1까지 이미 있는 것 (이 계획에서 다시 만들지 않는다):** `Signer`(`create`/`createAuthenticated`/`importSessionInfo`(음수 `age` 0 클램프)/`updateSignedSessionInfo`/`encrypt`/`decrypt`/`exportSessionInfo`/`close`), `SignerResult`, `ResponseClassifier.protocolError`(Wire `unknownFields` 기반 `UnknownFault(rawCode)`/`UnknownResponse` 포함), `VehicleError`(`ProtocolFault`, `KeyNotPaired`, `Busy`, `UnknownFault`, `UnknownResponse`, `NotConnected`, `NoSession`, `RequiresKey`, `BadResponse(detail, mayHaveSucceeded)`, `KeychainRejected`, `VcsecRejected`, `InfotainmentRejected`, `TransportError.*`, `KeyStoreError.*`, `Timeout(afterSend)`, `InvalidArgument`) + `shouldRetry()`/`toResult()`, `VehicleResult`, `RequestHash`, `SlidingWindow`, `CommandMetadata`/`ResponseMetadata`, `Session`, `Vin`, `PublicKeyBytes`, `TestVerifier`(GCM 경로), `TestCrypto`, `FixedRandom`, `ProtocolVectors`, `GoVectors`.

## 설계 구체화 (승인 필요)

SDD·ADR이 비워 두었거나 M1 결과와 어긋나는 점을 아래처럼 정한다. 계획 승인과 함께 승인된다.

1. **`Transport.send`는 예외 대신 값을 돌려준다.** SDD §2.1 주석은 "실패는 `TransportException(temporary?)`"라고 했지만 ADR-0006(예외는 취소와 프로그래밍 오류에만)과 M1 `VehicleError`에 맞춰 `suspend fun send(message: ByteArray): VehicleResult<Unit>`로 한다. Go `Connector.Send`의 "오류가 `protocol.Error`면 `MayHaveSucceeded`로 수신 여부를 판단"은 `Uncertain(error)`로 표현된다. Task 12에서 SDD §2.1을 고친다.
2. **`TransportFactory`는 M3로 미룬다.** 로드맵 표는 M2 인터페이스로 적었지만 입력(`VehicleAdvertisement`, `ConnectOptions`, 스캔)이 전부 M3(FR-001~005) 개념이라 지금 정의하면 자리표시자가 된다. M2의 `FakeVehicle.connect()`가 `connectable=false → Failure(MaxConnectionsExceeded)`를 모델링하고 M3 `TransportFactory` 가짜가 이를 감싼다.
3. **`Dispatcher` 내부는 Wire `Domain` enum을 그대로 쓴다.** SDD §2.1의 `VehicleDomain` 값 객체는 M3 `:sdk` 파사드(`Vehicle.sessions`)에서 만든다. M1 `Signer` API가 이미 Wire 타입(`SessionInfo`, `RoutableMessage`)을 노출하므로 일관된다.
4. **수신 코루틴의 락 규칙(인계 항목 4).** SDD §5 "수신 코루틴에서 suspend 대기 금지"는 **다른 코루틴의 진행을 기다리는 suspend**(채널 `receive`, `Deferred.await`, `delay`)를 금지하는 뜻으로 구체화한다. `SessionState`와 `Dispatcher`의 `Mutex`는 임계 구역이 `Signer` 호출·맵 조작뿐이고 어떤 코루틴 대기도 포함하지 않으므로 수신 코루틴이 `withLock`으로 잠가도 대기 시간이 µs 단위로 유계다(Go `session.lock`·`handlerLock`도 `sync.Mutex`로 같은 성질). `tryLock` + 드롭은 정당한 응답을 재전송 지연으로 바꾸므로 채택하지 않는다. `Signer` 멤버 분류(Task 5에서 KDoc으로 고정): **변경** = `encrypt`(counter++), `updateSessionInfo`, `updateSignedSessionInfo`, `close`; **읽기 전용** = `decrypt`, `exportSessionInfo`, `timestamp`, `counter`/`epoch`/공개키 getter. `decrypt`도 `close`와 경쟁하므로 Go처럼 락 안에서 부른다.
5. **`SessionState.authorize`는 `Signer.encrypt` 실패를 값으로 돌려준다.** Go `session.authorize`는 `Encrypt` 오류를 `nil`로 지우고 즉시 다시 시도해 ctx 만료까지 바쁘게 돈다. 우리는 `SignerResult.Fault`를 `Failure(ProtocolFault(fault))`로 돌려주고 `SendWithRetry`가 `retryInterval` 간격으로 재시도한다(관찰 결과 같음, CPU 낭비 없음). SDD §12 기록.
6. **`Dispatcher.loadSessions`는 손상된 캐시 항목을 건너뛴다.** Go `LoadCache`는 항목 하나가 잘못되면 전체를 오류로 돌려준다. 캐시는 최선 노력(SDD §7.1 "저장 실패는 명령 결과에 영향 없음")이므로 디코딩 실패·모르는 도메인 값은 WARN 로그 후 건너뛰고 나머지를 복원한다. SDD §12 기록.
7. **캐시 `age`는 어댑터가 원시값(음수 가능)으로 넘기고 `Signer.importSessionInfo`가 0으로 클램프한다(인계 항목 5).** 이중 클램프를 두지 않는다; Go도 미래 `generatedAt`을 그대로 받는다. Task 8 테스트가 "미래 항목 → age 0으로 import, 예외 없음"을 고정한다.
8. **캐시된 차량 공개키가 현재 차량과 다르면 Go처럼 세션 안에서 복구하지 않는다.** `processHello`의 `UNKNOWN_KEY_ID`는 ERROR 로그만 남기고 세션은 그대로다(공개키 고정을 약화시키지 않기 위해). Task 8 테스트가 이 동작을 고정한다. 복구는 M3 `connect()`가 해당 도메인의 캐시를 지우는 것으로 한다(아래 사용자 답 a).
9. **`TeslaLogger` 포트를 M2에 만든다.** SDD §2.1에 있으나 M3 항목이었다. 디스패처 드롭 사유(Go WARN 문구 그대로)를 테스트가 관찰하는 유일한 수단이므로 지금 만든다(`fun interface`, 기본 `NoOp`).
10. **응답 AAD의 `from_destination.domain`은 `unknownFields`를 읽지 않는다(인계 항목 2 후반).** 모르는 도메인에서 온 응답은 어떤 `PendingRequest`와도 매칭되지 않아(요청은 enum 도메인으로만 보낸다) Go도 우리도 복호화 전에 드롭한다. `signed_message_fault`만 원시값을 읽는다(Task 3). Task 6 테스트가 "모르는 from 도메인 → 핸들러 없음으로 드롭"을 고정한다.
11. **Wire가 모르는 enum 값의 처리(인계 항목 3)는 Go의 실제 분기를 따른다.** VCSEC `commandStatus.operationStatus`와 Infotainment `actionStatus.result`의 모르는 값은 Go에 `default` 분기가 **없어** 통과(성공)한다 — 테스트로 고정한다. `whitelistOperationInformation`의 모르는 값은 Go가 `KeychainError{Code}`로 실패시키므로 코드 보존형 `VehicleError.UnknownKeychainCode(rawCode)`를 추가한다(`UnknownFault`와 같은 모양). `nominalError.genericError`의 모르는 값은 `nominalError` 존재 자체가 실패이므로 `VcsecRejected(GENERICERROR_NONE)`로 실패는 보존되고 코드는 M4(L58, `VcsecRejected` 문구 재검토)에서 다룬다.
12. **`InMemorySessionCache`는 `:adapter-storage`에, `RecordingSessionCache`는 `:testing`에 둔다.** `:application` 테스트는 `:adapter-storage`를 볼 수 없으므로(경계 플러그인) 테스트용 캐시가 따로 필요하다. 둘 다 `SessionCacheCodec`(`:domain`)을 거친다.

**사용자 답(2026-09-27, 계획 승인과 함께):** (a) 위 8번 — 캐시된 차량 공개키 불일치(`UNKNOWN_KEY_ID`, 또는 캐시에서 복원한 세션의 세션정보 태그 실패)를 만나면 **M3 `connect()`가 그 도메인의 캐시를 지운다.** Go는 아무것도 하지 않으므로 의도된 차이다. M2 범위는 바뀌지 않는다(세션 안 동작은 Go 그대로, Task 8이 고정). 감지 방법은 M3 계획에서 정하고, 구현하는 M3 Task가 `{{SDD_FILE}}` §12 "원본과 다른 동작 (의도)"에 기록한다. 결정 자체는 이 계획 PR에서 `{{SDD_FILE}}` §7.1에 적었다. (b) 핸드셰이크 시간 초과는 **그대로** `Failure(Timeout(afterSend = false))`로 돌려준다. 전용 오류 타입을 추가하지 않는다.

## Global Constraints

- 패키지 루트 `io.github.smallmiro.teslable` (`{{PATHS_FILE}}` `BASE_PACKAGE`). Wire 생성 코드는 `com.tesla.generated.*` 그대로. `:application`은 `io.github.smallmiro.teslable.application.*`, `:adapter-storage`는 `io.github.smallmiro.teslable.storage`, `:testing`은 `io.github.smallmiro.teslable.testing`.
- 타깃: `jvm()`, Android(`minSdk 31`, `compileSdk 37`), `iosArm64()`, `iosSimulatorArm64()`. iOS 배포 타깃 16.0(컨벤션 플러그인이 선언).
- 모든 라이브러리 모듈은 `explicitApi()`, `allWarningsAsErrors = true`, 공개 API에 KDoc 필수. detekt `UndocumentedPublicClass/Function/Property`가 `:testing`을 포함한 모든 라이브러리 모듈에서 이를 강제한다.
- 의존 방향(ADR-0001): `:domain` ← `:application`, `:adapter-*` ← `:sdk`. **`:application`은 `:domain`에만 의존한다.** `:testing`은 `:domain`, `:adapter-crypto`에만 의존하고 다른 모듈의 **테스트 소스셋**에서만 참조된다. `:domain` 외부 의존은 `kotlin-stdlib*`, `kotlinx-coroutines*`, `wire-runtime`, `okio`뿐(경계 플러그인 allowlist).
- 금지: `!!`, `lateinit`(테스트 제외), `GlobalScope`, `runBlocking`(테스트 제외), `println`. 자체 구현 암호 알고리즘 금지(NFR-005). 네트워크 권한·호출 금지(NFR-014). 벽시계(`kotlin.time.Clock`, `System.currentTimeMillis`) 사용 금지 — **예외는 `:adapter-storage`의 세션 캐시뿐**이며, 그곳에서도 주입 가능한 `wallClockMillis: () -> Long`로 감싼다. 시간은 주입된 `TimeSource`로만.
- 포팅 파일 헤더: `// Ported from vehicle-command@a4b43c1 <원본 경로> (Apache-2.0)`. 새로 포팅한 파일은 `tools/ci/ported-files.txt`에 추가한다(`license` CI 잡이 검사). 바이트·순서 수준 규칙은 원본 줄을 주석으로 인용한 테스트로 고정한다(NFR-017). 의도적 차이는 KDoc + `{{SDD_FILE}}` §12 "원본과 다른 동작 (의도)"에 기록.
- 로그·픽스처·커밋에 실제 VIN, 개인키 금지. 테스트 VIN은 `5YJ30123456789ABC`, `5YJS0000000000000`, `0123456789ABCDEFG`(Go `dummyConnector.VIN()`)만. 테스트 키는 `protocol.md` 키(`ProtocolVectors`)와 Go 테스트 스칼라(`GoVectors`)만.
- 에러 메시지는 영어 + 코드 노출(D19, NFR-013). 예외는 `CancellationException`과 프로그래밍 오류(`IllegalArgumentException`/`IllegalStateException`)에만 쓴다(ADR-0006). 와이어·전송·캐시에서 온 입력의 오류는 값(`SignerResult.Fault`, `VehicleError`, `VehicleResult`)으로 돌려준다.
- 스레드 안전 계약: `Signer`, `TestVerifier`, `SlidingWindow`, `PendingRequest`의 채널 외 상태는 스레드 안전하지 않다. `SessionState`가 `Mutex`로 `Signer`를 지키고, `PendingRequest.antiReplay`는 수신 코루틴 한 곳에서만 갱신한다. `FakeVehicle`/`FakeTransport`는 `runTest` 단일 스레드 전용이다.
- **구조화된 동시성(SDD §5):** 차량(`Dispatcher`)마다 스코프 하나(테스트는 `runTest`의 `backgroundScope`), 수신 코루틴 1개(`transport.incoming.collect { process(it) }`), 그 안에서 다른 코루틴의 진행을 기다리는 suspend 금지(설계 구체화 4), `PendingRequest.channel = Channel(10)`이고 가득 차면 `trySend` 실패를 드롭 + WARN 로그로 처리한다.
- **취소(ADR-0010):** 호출 코루틴의 취소는 `CancellationException`으로 그대로 전파한다(값으로 바꾸지 않는다). 전송 후 취소·시간 초과된 명령의 `PendingRequest`는 `finally`(또는 `use`)에서 해제한다.
- **타임아웃(ADR-0010):** 라이브러리 안에서 `withTimeoutOrNull`로 건다. 전송 전(`transport.send`가 `Success`를 돌려주기 전)에 만료되면 `Failure(Timeout(afterSend = false))`, 전송 후 응답 대기 중이면 `Uncertain(Timeout(afterSend = true))`. `afterSend`는 **현재 시도**의 단계다(재시도마다 초기화; Go `Dispatcher.Send`의 ctx 분기 = false, `trySend`/`readUntil`의 ctx 분기 = true).
- **테스트 시간:** `kotlinx-coroutines-test`의 `runTest`/`TestScope`와 스케줄러의 `testTimeSource`(`@OptIn(ExperimentalCoroutinesApi::class)`)만 쓴다. 실제 sleep 금지. `FakeTransport.retryInterval = 1.milliseconds`, `allowedLatency = 1.seconds`(Go `dummyConnector`와 동일). `FakeVehicle`과 `Dispatcher`는 같은 `testTimeSource`를 받는다(Go 테스트의 실시간처럼 차량·클라이언트 시계가 함께 흐른다).
- 커밋 접두어 `struct:`/`feat:`/`fix:`/`test:`/`docs:`/`chore:`. `Refs:` 줄은 **trailer 블록 안**(`Co-Authored-By` 바로 위, 빈 줄 없이). 각 Task는 브랜치 `m2/<slug>` → PR → CI Green(필수 검사 6개: `lint`, `jvm-test`, `android`, `ios`, `license`, `secrets`) → AI 리뷰 → rebase merge(`{{WORKFLOW_FILE}}` §5). `main`은 보호되어 직접 푸시 불가. **리뷰 수정 라운드에서 구조 분리가 필요하면 `struct:` 커밋을 먼저 따로 만든다(M1 판정 L120; detekt 때문에만 쪼개는 경우도 포함).**
- `.proto`·Go 원본(`{{REF_REPO_DIR}}`)은 읽기 전용. 매뉴얼(`{{MANUAL_DIR}}`)은 수정 금지. 매뉴얼과 원본이 다르면 원본을 따르고 PR 본문 "원본 대응"에 보고한다.
- 골든/벡터 비교에서 protobuf 메시지 전체 바이트를 비교하지 않는다(필드 순서 차이). 디코딩 후 필드 비교.
- 공개 API가 바뀌는 Task는 `apiDump`를 갱신해 커밋하고(`<module>/api/*`), 사용 문서(`{{LIB_DOCS_DIR}}errors.md`)와 SDD를 같은 마일스톤 안에서 갱신한다(Task 12).

## 빌드 사실 (구현자가 알아야 할 것)

- Gradle 9.6은 configuration cache가 켜져 있다. **`./gradlew :<module>:apiDump`는 단독 호출**로 실행한다. test/check 태스크와 한 명령에 섞으면 configuration-cache 순서 오류가 난다. 각 Task의 "Run" 줄은 그래서 두 명령으로 나뉘어 있다.
- detekt `UndocumentedPublic*`는 모든 라이브러리 모듈(`:testing` 포함)에서 켜져 있다. 공개 클래스·함수·프로퍼티마다 한 줄 KDoc(한국어)을 쓴다. `LongParameterList`는 선언마다 `@Suppress("LongParameterList") // Go <함수>와 1:1 대응` 근거를 붙인다. `ReturnCount`는 꺼져 있다. `TooManyFunctions`가 걸리면 `@Suppress("TooManyFunctions") // Go <타입>의 메서드와 1:1 대응 + 테스트 전용 조작 N개`처럼 근거를 쓴다.
- ktlint/detekt `MaxLineLength`는 140이다. 계획의 코드는 이 안에 맞춰 두었다; 줄을 이어 붙이지 않는다.
- Kotlin의 `@OptIn(InternalTeslableApi::class)`는 같은 클래스 안의 호출에도 필요하다. `hexToBytes()`/`toHex()`도 `@InternalTeslableApi`다.
- `:domain` 테스트는 `:testing`을 볼 수 있지만 `:adapter-crypto`는 직접 볼 수 없다. 키는 항상 `TestCrypto`의 팩토리(`clientKey()`, `vehicleKey()`, `goKnownVerifierKey()`)에서 얻는다. `:application`·`:adapter-storage` 테스트도 같다.
- Wire uint32 필드는 `Int`로 온다(`-1` = 0xFFFFFFFF). `toUInt()`로 재해석한다. Wire oneof의 `copy()`는 다른 멤버를 **명시적으로 null**로 지워야 한다(안 지우면 "at most one of …" `IllegalArgumentException`). `RoutableMessage.payload` oneof = `protobuf_message_as_bytes` / `session_info_request` / `session_info`.
- Wire는 모르는 enum 값을 기본 상수로 두고 원시 varint를 `unknownFields`로 옮긴다. `Destination.domain`이 모르는 값이면 `domain == null && routing_address == null`이다.
- `kotlinx.coroutines.test.testTimeSource`(`TestScope` 확장)는 `@ExperimentalCoroutinesApi`다. 테스트 클래스에 `@OptIn(ExperimentalCoroutinesApi::class)`를 붙인다. `TestCoroutineScheduler.timeSource`의 `TimeMark`는 `plus`/`minus`/`elapsedNow`를 지원한다.
- `kotlin.time.Clock.System`이 Kotlin 2.4.20에서 옵트인을 요구하면 `@OptIn(kotlin.time.ExperimentalTime::class)`를 그 파일에만 붙인다(`:adapter-storage`뿐).
- iOS 테스트는 `./gradlew :<module>:iosSimulatorArm64Test`로 돌리며 로컬에 Xcode 26이 필요하다(기기 `iPhone 17`).
- `kotlinx.coroutines.selects.onTimeout`은 실험 API라 쓰지 않는다. 대신 `withTimeoutOrNull(interval) { select { … } }`를 쓴다(Task 7).

## Review Focus

스펙이 암시하지만 Go 테스트 포팅만으로는 다루지 않는, 사용자에게 문제가 될 입력 다섯 가지. 각 항목의 테스트를 소유 Task에 추가했다.

1. **`request_uuid`를 채워서 오는 VCSEC 응답** — 실제 VCSEC는 `request_uuid`를 회신하지 않지만(매뉴얼 `01-architecture §4.1`), 회신하더라도 매칭은 주소만으로 해야 한다. uuid까지 키에 넣으면 정상 응답을 드롭한다. → Task 6 `matchesVcsecResponseByAddressRegardlessOfRequestUuid`.
2. **응답 대기 중 전송이 끝남(`incoming` 완료 = BLE 끊김)** — 수신 루프가 조용히 종료되고 그 뒤 `send`는 `NotConnected`여야 하며, 기다리던 명령은 시간 초과로 `Uncertain`이 된다. → Task 6 `sendReturnsNotConnectedAfterIncomingCompletes`.
3. **응답 대기 중 호출자가 취소함** — `PendingRequest`가 해제되지 않으면 맵이 새고, 뒤늦은 응답이 엉뚱한 곳에 전달된다. → Task 9 `cancelledCommandReleasesPendingRequest`.
4. **캐시된 차량 공개키가 현재 차량과 다름**(차량 키 교체, 다른 차의 캐시) — 첫 명령이 차량 fault(`INCORRECT_EPOCH`, 새 검증자) + 세션정보로 실패하고, 동봉 세션정보는 우리 K(옛 차량 키로 유도)로 태그가 맞지 않아 `INVALID_SIGNATURE`로 거부되어 세션이 갇힌다(Go와 동일). 이를 로그로 드러내야 한다. → Task 8 `staleCachedVehicleKeyLeavesSessionStuckLikeGo`.
5. **응답 `flags`가 요청 `flags`와 다름**(차량이 0으로 회신) — 응답 AAD는 **응답**의 flags를 쓰므로 정상 복호화돼야 한다. 요청 flags를 쓰면 정당한 응답을 `INVALID_SIGNATURE`로 드롭한다. → Task 6 `decryptsResponseWhoseFlagsDifferFromRequest`.

## File Structure

| 파일 | 책임 | Go 대응 |
|---|---|---|
| `domain/…/port/Transport.kt` | `Transport`, `TransportState`, `AuthMethod`(NONE/GCM) | `pkg/connector/connector.go` |
| `domain/…/port/TeslaLogger.kt` | `TeslaLogger`, `LogLevel`, `NoOp` | `internal/log` |
| `domain/…/port/SessionCache.kt` | `SessionCache` 포트 | `pkg/cache/cache.go` |
| `domain/…/model/KeyId.kt` | `KeyId` = SHA1(공개키 65B) 20바이트 | – (D26) |
| `domain/…/cache/CachedSession.kt` | `SessionSnapshot`(내보내기), `CachedSession`(불러오기, `age`) | `session.go CacheEntry` |
| `domain/…/cache/SessionCacheCodec.kt` | SDD §7.1 v1 바이너리 코덱 | – (D26) |
| `domain/…/protocol/UnknownFields.kt` | `ByteString.unknownVarint(tag)` (ResponseClassifier에서 추출) | – |
| `domain/…/protocol/Signer.kt` (수정) | `decrypt`가 `signed_message_fault` 원시값을 AAD에 씀 | `signer.go Decrypt` |
| `domain/…/model/VehicleError.kt` (수정) | `UnknownKeychainCode(rawCode)` | `error.go KeychainError` |
| `application/…/dispatcher/PendingRequest.kt` | `PendingKey`, `PendingRequest`(채널 10, `SlidingWindow`, `sentAt`) | `receiver.go` |
| `application/…/dispatcher/SessionState.kt` | 도메인별 `Signer` + `Mutex` + ready | `session.go` |
| `application/…/dispatcher/Dispatcher.kt` | 조립·전송 재시도·수신 루프·드롭·세션 갱신·복호화·캐시 내보내기/불러오기 | `dispatcher.go` |
| `application/…/dispatcher/HandshakeFlow.kt` | `startSession`(재전송 루프), `startSessions`(병렬) | `dispatcher.go StartSession*` |
| `application/…/dispatcher/Results.kt` | `internal` 결과 헬퍼 | – |
| `application/…/vehicle/CommandTimeouts.kt` | `commandTimeout`, `commandLifetime`, `handshakeTimeout` | D29 |
| `application/…/vehicle/SendWithRetry.kt` | 단일 응답 명령 재시도 루프 | `vehicle.go Send/trySend` |
| `application/…/vehicle/VehicleSession.kt` | 연결·핸드셰이크 재시도·캐시 동기화·해제 | `vehicle.go NewVehicle/StartSession/Disconnect` |
| `application/…/cache/SessionCacheSync.kt` | 포트 ↔ 디스패처 동기화 | `Cache/LoadCache/UpdateCachedSessions` |
| `application/…/vcsec/VcsecResponses.kt` | `interpret`, `TerminalTest`, `readUntil` | `vcsec.go unmarshalVCSECResponse/readUntil` |
| `application/…/vcsec/VcsecCommands.kt` | `execute`(직렬화 `Mutex`, 재시도) | `vcsec.go getVCSECResult` |
| `application/…/infotainment/InfotainmentResponses.kt` | `Response` 해석 | `infotainment.go getCarServerResponse` |
| `application/…/infotainment/InfotainmentCommands.kt` | `execute(action)` | 〃 |
| `adapter-storage/…/storage/InMemorySessionCache.kt` | 메모리 캐시(벽시계 주입) | – |
| `testing/…/FakeTransport.kt` | 스크립트 가능한 `Transport` | `dispatcher_test.go dummyConnector` |
| `testing/…/FakeVehicle.kt` | 도메인별 `TestVerifier` + 대본 | `dummyConnector` + `verifier.go` |
| `testing/…/RecordingSessionCache.kt`, `RecordingLogger.kt` | 테스트 관찰용 | – |
| `application/src/commonTest/…/DispatcherFixtures.kt` | 테스트 공통 하네스 | `getTestSetup` |

---

### Task 1: `TestVerifier` 정리(인계 항목 7)와 문구 교정(인계 항목 9)

참고: `{{HANDOFF_DIR}}2026-09-27-m1-rulings.md` L80~L83, L120 잔여; M1 최종 리뷰 M-7, 권고 6. Go 원본: `internal/authentication/verifier.go`(`AssignHandle` 75~79행, `SetSessionInfo` 127~146행, `sessionInfo` 81~92행), `verifier_test.go`(`TestProvideHandle` 553~564행, `TestGCMExpired` 319~327행의 `verifier.timeZero` 직접 조작).

**Files:**
- Modify: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/TestVerifier.kt`
- Modify: `testing/src/commonTest/kotlin/io/github/smallmiro/teslable/testing/TestVerifierTest.kt`
- Modify: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/Signer.kt:309-311` (KDoc 문구만)
- Modify: `docs/sdd/SDD.md:187`, `docs/sdd/SDD.md:669`, `docs/handoff/2026-09-27-m1-protocol.md:24` (문구만)

**Interfaces:**
- Consumes: M1 `TestVerifier`, `SignedSessionInfo`, `FixedRandom`, `TestCrypto`, Wire `RoutableMessage`/`SessionInfo`.
- Produces (Task 4 `FakeVehicle`, Task 6~11 테스트가 사용):
  - `TestVerifier.exhaustCounter()` — `forceCounter(UInt.MAX_VALUE)`를 대체(Go와 동일한 유일한 사용처).
  - `TestVerifier.assignHandle(handle: UInt)` — Go `AssignHandle`.
  - `TestVerifier.shiftTimeZero(by: Duration)` — Go 테스트의 `verifier.timeZero = verifier.timeZero.Add(d)`와 동일. 양수면 `timestamp()`가 **줄어든다**(시계 역행), 음수면 커진다(만료 유발).
  - `setSessionInfo`/`encryptResponse`가 payload oneof의 다른 멤버를 지운다.

- [ ] **Step 1: 실패 테스트 작성 (기존 파일에 추가·수정)**

`rotatesEpochWhenCounterIsExhausted`의 `verifier.forceCounter(UInt.MAX_VALUE)`를 `verifier.exhaustCounter()`로 바꾸고, `encryptsResponseThatClientSessionDecrypts`의 `assertEquals(8, gcm.counter)` 바로 아래에 nonce 단언을 넣고, 테스트 셋을 추가한다.

```kotlin
            assertEquals("dbf79447fa156674dae1caed", gcm.nonce.hex()) // 주입한 FixedRandom 두 번째 값이 nonce로 쓰였다
```

```kotlin
    @Test
    fun sessionInfoCarriesAssignedHandle() =
        // verifier_test.go TestProvideHandle
        runTest {
            val verifier = docVerifier(TestTimeSource())
            verifier.assignHandle(0xDEADBEEFu)
            assertEquals(0xDEADBEEFu.toInt(), verifier.sessionInfo().handle)
        }

    @Test
    fun setSessionInfoClearsOtherPayloadMembers() =
        // M1 ledger L80: Go SetSessionInfo는 message.Payload(oneof 전체)를 교체한다
        runTest {
            val verifier = docVerifier(TestTimeSource())
            val request =
                RoutableMessage(
                    session_info_request = com.tesla.generated.universalmessage.SessionInfoRequest(public_key = TestCrypto.clientPublicKey.toByteArray().toByteString()),
                )
            val reply = verifier.setSessionInfo(ProtocolVectors.CHALLENGE.hexToBytes(), request)
            assertNull(reply.session_info_request)
            assertNull(reply.protobuf_message_as_bytes)
            assertNotNull(reply.session_info)
        }

    @Test
    fun encryptResponseClearsOtherPayloadMembers() =
        runTest {
            val verifier = docVerifier(TestTimeSource())
            val requestHash = RequestHash.of(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED, ByteArray(16) { 9 }, Domain.DOMAIN_VEHICLE_SECURITY)
            val withSessionInfo = RoutableMessage(session_info = "x".encodeUtf8())
            val encrypted = verifier.encryptResponse(withSessionInfo, requestHash, counter = 1u)
            assertNull(encrypted.session_info)
            assertNull(encrypted.session_info_request)
            assertEquals(0, assertNotNull(encrypted.protobuf_message_as_bytes).size) // 빈 평문의 암호문은 빈 바이트
        }

    @Test
    fun shiftTimeZeroMovesVehicleClock() =
        // verifier_test.go TestGCMExpired: verifier.timeZero.Add(-time.Hour) → timestamp가 1시간 커진다
        runTest {
            val time = TestTimeSource()
            val verifier = docVerifier(time)
            time += 10.seconds
            assertEquals(10u, verifier.timestamp())
            verifier.shiftTimeZero((-1).hours)
            assertEquals(3610u, verifier.timestamp())
            verifier.shiftTimeZero(1.hours + 5.seconds) // 5초 역행
            assertEquals(5u, verifier.timestamp())
        }
```

필요한 import를 추가한다: `com.tesla.generated.signatures.SignatureType`, `okio.ByteString.Companion.encodeUtf8`, `okio.ByteString.Companion.toByteString`, `kotlin.time.Duration.Companion.hours`.

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :testing:jvmTest --tests '*TestVerifierTest*' --console=plain`
Expected: 컴파일 실패 (`exhaustCounter`, `assignHandle`, `shiftTimeZero` 없음)

- [ ] **Step 3: 구현**

`TestVerifier.kt`에서 `forceCounter`를 아래 세 메서드로 교체하고, `setSessionInfo`·`encryptResponse`의 `copy()`에 oneof 정리를 넣는다. 클래스 `@Suppress` 근거도 갱신한다.

```kotlin
@Suppress("TooManyFunctions") // Go Verifier/Peer의 공개·비공개 메서드와 1:1 대응 + 테스트 전용 조작 3개(exhaustCounter, shiftTimeZero, assignHandle)
public class TestVerifier private constructor(
```

```kotlin
    /** counter를 `0xFFFFFFFF`로 놓는다(Go `TestGCMEpochRotation`의 `signer.counter = 0xFFFFFFFE` 이후 상태). 다음 [sessionInfo]/[verify]가 epoch를 돌린다. */
    @InternalTeslableApi
    public fun exhaustCounter() {
        counterValue = UInt.MAX_VALUE
    }

    /** Go `AssignHandle`: 이후 [sessionInfo]의 `handle`에 실린다. */
    public fun assignHandle(handle: UInt) {
        this.handle = handle
    }

    /**
     * Go 테스트의 `verifier.timeZero = verifier.timeZero.Add(by)`. [by]가 양수면 시계 원점이 뒤로 밀려 [timestamp]가
     * 줄어들고(시계 역행 시나리오), 음수면 [timestamp]가 커진다(`TestGCMExpired`의 `-time.Hour`).
     */
    @InternalTeslableApi
    public fun shiftTimeZero(by: Duration) {
        timeZero = timeZero + by
    }
```

```kotlin
    /** Go `SetSessionInfo`: 오류 응답에 세션정보와 태그를 싣는다. payload oneof의 다른 멤버는 Go처럼 지운다. */
    public fun setSessionInfo(
        challenge: ByteArray,
        message: RoutableMessage,
    ): RoutableMessage {
        val signed = signedSessionInfo(challenge)
        return message.copy(
            protobuf_message_as_bytes = null,
            session_info_request = null,
            session_info = signed.encoded.toByteString(),
            signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = signed.tag.toByteString())),
        )
    }
```

`encryptResponse`의 `message.copy(` 안에 `session_info = null,` 과 `session_info_request = null,` 두 줄을 `protobuf_message_as_bytes = out.ciphertext.toByteString(),` 바로 아래에 추가한다. `import kotlin.time.Duration`을 추가한다. `handle`은 이미 `private var handle = 0u`다.

- [ ] **Step 4: 통과 확인, 커밋 (test)**

Run: `./gradlew :testing:jvmTest --tests '*TestVerifierTest*' :testing:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS)

```bash
git add testing/src
git commit -m "test(testing): TestVerifier clears payload oneof, exhaustCounter/assignHandle/shiftTimeZero, nonce assertion

Refs: NFR-003, NFR-017
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: 문구 교정 (docs)**

세 파일에서 `timeZero`의 방향 서술을 바꾼다. Go `ImportSessionInfo(generatedAt)`는 `timeZero = generatedAt - clock_time`이므로 미래의 `generatedAt`은 `timeZero`를 **더 나중**으로 만든다(`timestamp()`가 작아져 명령이 일찍 만료될 수 있다).

- `Signer.kt` 309~311행 KDoc: `Go는 미래의 \`generatedAt\`을 그대로 받아 \`timeZero\`가 뒤로 간다.` → `Go는 미래의 \`generatedAt\`을 그대로 받아 \`timeZero\`가 더 나중이 된다(\`timestamp()\`가 작아져 명령이 일찍 만료될 수 있다).`
- `docs/sdd/SDD.md` 669행: `(Go는 미래 \`generatedAt\`을 그대로 받아 \`timeZero\`가 뒤로 감)` → `(Go는 미래 \`generatedAt\`을 그대로 받아 \`timeZero\`가 더 나중이 된다)`.
- `docs/sdd/SDD.md` 187행과 `docs/handoff/2026-09-27-m1-protocol.md` 24행의 `(벽시계가 뒤로 감)`는 벽시계 서술이라 그대로 두되, 뒤에 `— Go에서는 timeZero가 더 나중이 된다`를 덧붙인다.

Run: `./gradlew :domain:detekt --console=plain`
Expected: PASS (KDoc만 바뀜)

```bash
git add domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/Signer.kt docs/sdd/SDD.md docs/handoff/2026-09-27-m1-protocol.md
git commit -m "docs: say a future generatedAt makes timeZero later, not earlier (Signer KDoc, SDD, M1 handoff)

Refs: FR-019
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `Transport` 포트, `AuthMethod`, `FakeTransport`

참고 매뉴얼: `01-architecture.md` §3.1(Connector 계약), §5(락·채널); `10-porting-guide.md` §3(수신 버퍼). Go 원본: `pkg/connector/connector.go`(`Connector`, `AuthMethod`, `BufferSize`), `dispatcher_test.go`(`dummyConnector` 84~274행: `Sleep`/`Wake`, `EnqueueReply`, `EnqueueSendError`, `Send`, `AckRequests`, `RetryInterval = 1ms`, `AllowedLatency = 1s`). 설계: `{{SDD_FILE}}` §2.1 포트, 설계 구체화 1·2.

**Files:**
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/Transport.kt`
- Create: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeTransport.kt`
- Modify: `tools/ci/ported-files.txt` (Transport.kt 추가)
- Modify: `domain/api/*` (`apiDump`)
- Test: `testing/src/commonTest/kotlin/io/github/smallmiro/teslable/testing/FakeTransportTest.kt`

**Interfaces:**
- Consumes: M0 `Vin`, M1 `VehicleError`, `VehicleResult`, `toResult()`.
- Produces:
  - `public enum class AuthMethod { NONE, GCM }`
  - `public sealed interface TransportState { Connected; Disconnected(reason: VehicleError.TransportError?) }`
  - `public interface Transport { val vin: Vin; val incoming: Flow<ByteArray>; val state: StateFlow<TransportState>; val retryInterval: Duration; val allowedLatency: Duration; suspend fun send(message: ByteArray): VehicleResult<Unit>; suspend fun close() }`
  - `public class FakeTransport(vin = Vin("0123456789ABCDEFG"), retryInterval = 1.milliseconds, allowedLatency = 1.seconds) : Transport { var onSend: (suspend (ByteArray) -> Unit)?; val sent: List<ByteArray>; var ackRequests: Boolean; fun enqueueSendError(error: VehicleError); fun sleep(); fun wake(); val isAsleep: Boolean; fun deliver(bytes: ByteArray): Boolean; val delivered: Int }`
  - Task 4 `FakeVehicle`이 `onSend`로 감싸고, Task 6~11 테스트가 `deliver`/`enqueueSendError`/`sleep`을 쓴다. M3 `KableTransport`가 `Transport`를 구현한다.

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// testing/src/commonTest/kotlin/io/github/smallmiro/teslable/testing/FakeTransportTest.kt
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeTransportTest {
    @Test
    fun matchesGoDummyConnectorTimings() {
        // dispatcher_test.go dummyConnector: RetryInterval = 1ms, AllowedLatency = 1s, VIN = "0123456789ABCDEFG"
        val transport = FakeTransport()
        assertEquals(1.milliseconds, transport.retryInterval)
        assertEquals(1.seconds, transport.allowedLatency)
        assertEquals("0123456789ABCDEFG", transport.vin.value)
        assertIs<TransportState.Connected>(transport.state.value)
    }

    @Test
    fun sendRecordsBytesAndInvokesHandler() =
        runTest {
            val transport = FakeTransport()
            val seen = mutableListOf<ByteArray>()
            transport.onSend = { seen += it }
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(1, 2, 3)))
            assertEquals(1, transport.sent.size)
            assertContentEquals(byteArrayOf(1, 2, 3), transport.sent.single())
            assertContentEquals(byteArrayOf(1, 2, 3), seen.single())
        }

    @Test
    fun queuedSendErrorsAreReturnedOnceInOrderWithoutInvokingHandler() =
        runTest {
            // dummyConnector.Send: errorQueue의 첫 오류를 돌려주고 handleAsync를 부르지 않는다
            val transport = FakeTransport()
            var handled = 0
            transport.onSend = { handled++ }
            transport.enqueueSendError(VehicleError.TransportError.WriteFailed("gatt"))
            transport.enqueueSendError(VehicleError.Timeout(afterSend = true))
            val first = assertIs<VehicleResult.Failure>(transport.send(byteArrayOf(1)))
            assertEquals(VehicleError.TransportError.WriteFailed("gatt"), first.error)
            val second = assertIs<VehicleResult.Uncertain>(transport.send(byteArrayOf(2)))
            assertEquals(VehicleError.Timeout(afterSend = true), second.error)
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(3)))
            assertEquals(1, handled)
            assertEquals(3, transport.sent.size) // 실패한 전송도 기록한다(Go inbox는 성공만 기록하지만 테스트 관찰용으로 전부 남긴다)
        }

    @Test
    fun ackRequestsFalseFailsEverySendWithoutRetry() =
        runTest {
            // dummyConnector.AckRequests = false → errTimeout(재시도 불가 오류)
            val transport = FakeTransport()
            transport.ackRequests = false
            val result = assertIs<VehicleResult.Failure>(transport.send(byteArrayOf(1)))
            assertEquals(VehicleError.TransportError.Disconnected, result.error)
            assertFalse(result.error.temporary)
        }

    @Test
    fun deliverFeedsIncomingUnlessAsleep() =
        runTest {
            // dummyConnector.EnqueueReply: dropReplies면 버린다. Close 후 incoming은 완료된다.
            val transport = FakeTransport()
            assertTrue(transport.deliver(byteArrayOf(9)))
            transport.sleep()
            assertTrue(transport.isAsleep)
            assertFalse(transport.deliver(byteArrayOf(8)))
            transport.wake()
            assertTrue(transport.deliver(byteArrayOf(7)))
            transport.close()
            val received = transport.incoming.toList()
            assertEquals(listOf(9.toByte(), 7.toByte()), received.map { it.single() })
            assertEquals(2, transport.delivered)
            assertIs<TransportState.Disconnected>(transport.state.value)
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :testing:jvmTest --tests '*FakeTransportTest*' --console=plain`
Expected: 컴파일 실패 (`Transport`, `FakeTransport` 없음)

- [ ] **Step 3: 구현**

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/Transport.kt
// Ported from vehicle-command@a4b43c1 pkg/connector/connector.go (Apache-2.0) — Connector, AuthMethod (GCM only, D6)
package io.github.smallmiro.teslable.port

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** Go `connector.AuthMethod`. BLE는 AES-GCM만 쓰므로 HMAC은 없다(D6). */
public enum class AuthMethod {
    /** 인증 없음(핸드셰이크, 정보 요청). Go `AuthMethodNone`. */
    NONE,

    /** AES-GCM 개인화 인증(`Signer.encrypt`). Go `AuthMethodGCM`. */
    GCM,
}

/** 전송 연결 상태. M3 `Vehicle.connection`이 노출한다. */
public sealed interface TransportState {
    /** 연결됨. */
    public data object Connected : TransportState

    /** 끊김. [reason]은 원인을 알 때만. */
    public data class Disconnected(
        /** 끊긴 원인. */
        public val reason: VehicleError.TransportError?,
    ) : TransportState
}

/**
 * 차량과 데이터그램(직렬화된 `RoutableMessage`)을 주고받는 포트. Go `connector.Connector`.
 * [incoming]은 재조립된 메시지 단위이며 수집자는 `Dispatcher`의 수신 코루틴 하나뿐이다.
 * 오류는 예외가 아니라 값이다(ADR-0006): [send]가 `Uncertain`이면 차량이 받았을 수 있다는 뜻이다(Go `MayHaveSucceeded`).
 */
public interface Transport {
    /** 연결된 차량의 VIN(TLV PERSONALIZATION). Go `VIN()`. */
    public val vin: Vin

    /** 차량이 보낸 메시지. 연결이 끝나면 완료된다. Go `Receive()`. */
    public val incoming: Flow<ByteArray>

    /** 연결 상태. */
    public val state: StateFlow<TransportState>

    /** 재전송 간격(BLE 1초). Go `RetryInterval()`. */
    public val retryInterval: Duration

    /** 요청 전송 후 세션정보를 받아들이는 최대 지연(BLE 4초). Go `AllowedLatency()`. */
    public val allowedLatency: Duration

    /** 메시지 하나를 보낸다(프레이밍·분할 포함). Go `Send`. */
    public suspend fun send(message: ByteArray): VehicleResult<Unit>

    /** 연결을 닫는다. 멱등. Go `Close()`. */
    public suspend fun close()
}
```

```kotlin
// testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeTransport.kt
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 대본을 따르는 [Transport](Go `dispatcher_test.go dummyConnector`). `runTest` 단일 스레드 전용이며 스레드 안전하지 않다.
 * [send]는 [onSend]를 **동기로** 부른다(Go는 `handleAsync` goroutine이지만 결정성을 위해 같은 코루틴에서 처리한다);
 * 응답은 [deliver]로 [incoming]에 넣는다.
 */
public class FakeTransport(
    override val vin: Vin = Vin(DEFAULT_VIN),
    override val retryInterval: Duration = 1.milliseconds,
    override val allowedLatency: Duration = 1.seconds,
) : Transport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Connected)
    private val sentMessages = mutableListOf<ByteArray>()
    private val sendErrors = ArrayDeque<VehicleError>()
    private var deliveredCount = 0

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
    override val state: StateFlow<TransportState> = stateFlow

    /** [send]가 부르는 차량 측 처리기(Go `dummyConnector.callback`). `FakeVehicle`이 설정한다. */
    public var onSend: (suspend (ByteArray) -> Unit)? = null

    /** false면 모든 [send]가 재시도 불가 오류로 실패한다(Go `AckRequests = false` → `errTimeout`). */
    public var ackRequests: Boolean = true

    /** 지금까지 [send]에 들어온 바이트(실패한 전송 포함). Go `inbox`. */
    public val sent: List<ByteArray> get() = sentMessages.map { it.copyOf() }

    /** [deliver]로 [incoming]에 실제로 들어간 메시지 수. */
    public val delivered: Int get() = deliveredCount

    /** true면 [deliver]가 버린다(Go `dropReplies`). */
    public var isAsleep: Boolean = false
        private set

    /** 다음 [send]가 [error]를 돌려주게 한다(Go `EnqueueSendError`). 큐는 순서대로 소비된다. */
    public fun enqueueSendError(error: VehicleError) {
        sendErrors.addLast(error)
    }

    /** 응답을 버리기 시작한다(Go `Sleep`). */
    public fun sleep() {
        isAsleep = true
    }

    /** 응답을 다시 전달한다(Go `Wake`). */
    public fun wake() {
        isAsleep = false
    }

    /** 차량 → 클라이언트 메시지를 넣는다(Go `EnqueueReply`). 잠들었거나 닫혔으면 false. */
    public fun deliver(bytes: ByteArray): Boolean {
        if (isAsleep) return false
        val ok = inbox.trySend(bytes.copyOf()).isSuccess
        if (ok) deliveredCount++
        return ok
    }

    override suspend fun send(message: ByteArray): VehicleResult<Unit> {
        sentMessages += message.copyOf()
        sendErrors.removeFirstOrNull()?.let { return it.toResult() }
        if (!ackRequests) return VehicleResult.Failure(VehicleError.TransportError.Disconnected)
        onSend?.invoke(message.copyOf())
        return VehicleResult.Success(Unit)
    }

    override suspend fun close() {
        isAsleep = true
        inbox.close()
        stateFlow.value = TransportState.Disconnected(reason = null)
    }

    /** 상수. */
    public companion object {
        /** Go `dummyConnector.VIN()`. 비밀 스캔 허용 목록에 있는 테스트 VIN. */
        public const val DEFAULT_VIN: String = "0123456789ABCDEFG"
    }
}
```

`tools/ci/ported-files.txt`에 `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/Transport.kt`를 정렬 위치에 추가한다.

- [ ] **Step 4: 통과 확인, API 덤프, 커밋**

Run: `./gradlew :domain:apiDump --console=plain`
Run: `./gradlew :testing:jvmTest --tests '*FakeTransportTest*' :domain:check :testing:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS). `domain/api/*`에 `Transport`, `TransportState`, `AuthMethod` 반영.

```bash
git add domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/Transport.kt domain/api tools/ci/ported-files.txt
git commit -m "feat(domain): Transport port and AuthMethod from connector.go (send returns VehicleResult, GCM only)

Refs: FR-010, NFR-012, ADR-0006
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add testing/src
git commit -m "test(testing): FakeTransport ports dispatcher_test.go dummyConnector (1ms retry, 1s latency, sleep/wake, send errors)

Refs: NFR-003
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: `Signer.decrypt`의 응답 AAD가 Wire가 모르는 fault 코드의 원시값을 쓰도록(인계 항목 2)

참고 매뉴얼: `03-protocol.md` §9.2(TAG_FAULT = 응답의 `signed_message_fault`를 uint32 BE 4바이트); `08-errors.md` §1.4. Go 원본: `internal/authentication/signer.go` `Decrypt`(`message.GetSignedMessageStatus().GetSignedMessageFault()`를 `uint32`로 `responseMetadata`에 넘김), `pkg/protocol/error.go` 241행. 설계: `{{SDD_FILE}}` §12 마지막 항목("M2에서 보완"), M1 판정 L101, 설계 구체화 10.

**Files:**
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/UnknownFields.kt`
- Modify: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/ResponseClassifier.kt:86-110` (`unknownVarint`를 확장 함수 호출로 교체)
- Modify: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/Signer.kt:202-245` (`decrypt`)
- Test: `domain/src/commonTest/kotlin/io/github/smallmiro/teslable/protocol/SignerCryptoTest.kt` (테스트 1개 추가)

**Interfaces:**
- Consumes: M1 `Signer.decrypt`, `ResponseMetadata.build`, `Session.encrypt`, Wire `MessageStatus.unknownFields`.
- Produces: `internal fun ByteString.unknownVarint(tag: Int): Int?` (`:domain` 내부, Task 10의 VCSEC/Infotainment 해석기도 사용). `Signer.decrypt`의 시그니처는 그대로(`decrypt(message, requestHash): SignerResult<DecryptedResponse>`) — 별도 변형이나 메타데이터 덮어쓰기 파라미터를 두지 않는다. 이유: Go `Decrypt`도 메시지에서 fault를 읽는 단일 진입점이고, 호출자(M2 `Dispatcher`)가 fault를 따로 알 방법이 없으며, SDD §12는 "응답 메타데이터를 만들 때 `unknownFields`의 원시 값을 쓰도록 보완"이라고 이 함수의 내부 동작으로 적었다.

- [ ] **Step 1: 구조 변경 — `unknownVarint` 추출 (동작 불변)**

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/UnknownFields.kt
package io.github.smallmiro.teslable.protocol

import com.squareup.wire.ProtoReader
import okio.Buffer
import okio.ByteString
import okio.IOException

/**
 * Wire `unknownFields`에서 [tag]가 가리키는 varint 필드의 원시 값(마지막 값). 없으면 null. Wire가 범위를 벗어난 enum
 * 값을 만나면 원본 바이트를 여기로 옮기므로, 값이 있으면 그 필드는 미인식 값이었다는 뜻이다. 손상된 버퍼는 "없음"(null)으로
 * 취급한다.
 */
internal fun ByteString.unknownVarint(tag: Int): Int? {
    if (size == 0) return null
    return try {
        var value: Int? = null
        val reader = ProtoReader(Buffer().write(this))
        reader.forEachTag { foundTag ->
            if (foundTag == tag) {
                value = reader.readVarint32()
            } else {
                reader.skip()
            }
        }
        value
    } catch (ignored: IOException) {
        null
    }
}
```

`ResponseClassifier.kt`에서 private `unknownVarint` 함수(86~110행)를 지우고 세 호출을 바꾼다: `unknownVarint(it.unknownFields, TAG_MESSAGE_STATUS_SIGNED_MESSAGE_FAULT)` → `it.unknownFields.unknownVarint(TAG_MESSAGE_STATUS_SIGNED_MESSAGE_FAULT)`, `unknownVarint(info.unknownFields, TAG_SESSION_INFO_STATUS)` → `info.unknownFields.unknownVarint(TAG_SESSION_INFO_STATUS)`, `unknownVarint(status.unknownFields, TAG_MESSAGE_STATUS_OPERATION_STATUS)` → `status.unknownFields.unknownVarint(TAG_MESSAGE_STATUS_OPERATION_STATUS)`. 쓰지 않게 된 import(`ProtoReader`, `Buffer`)를 지운다(`IOException`은 `decode` catch에 남는다).

Run: `./gradlew :domain:jvmTest --tests '*ResponseClassifierTest*' :domain:detekt --console=plain`
Expected: PASS (9개 그대로)

```bash
git add domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/UnknownFields.kt domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/ResponseClassifier.kt
git commit -m "struct(domain): extract ByteString.unknownVarint from ResponseClassifier for reuse

Refs: FR-016
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: 실패 테스트 작성 (`SignerCryptoTest`에 추가)**

```kotlin
    @Test
    fun decryptsResponseWhoseFaultCodeIsUnknownToWire() =
        // M1 ledger L101 / SDD §12: Go Decrypt는 GetSignedMessageFault()의 원시 uint32를 AAD에 쓴다. Wire는 proto에 없는
        // enum 값(신형 펌웨어의 fault 99)을 unknownFields로 옮기고 signed_message_fault는 NONE으로 둔다.
        runTest {
            val (_, signer) = pair()
            val requestHash = arbitraryRequestHash()
            val rawStatus = MessageStatus.ADAPTER.decode(byteArrayOf(0x10, 0x63)) // 태그 2(signed_message_fault) varint 99
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_NONE, rawStatus.signed_message_fault)
            val vehicleSession = Session.establish(TestCrypto.vehicleKey(), TestCrypto.clientPublicKey, crypto)
            val meta = ResponseMetadata.build(Domain.DOMAIN_VEHICLE_SECURITY, vin.toByteArray(), 7u, 0u, requestHash, 99u)
            val nonce = ByteArray(Session.NONCE_SIZE) { 3 }
            val out = vehicleSession.encrypt("0a00".hexToBytes(), crypto.sha256(meta.serialize()), nonce)
            val response =
                RoutableMessage(
                    from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                    protobuf_message_as_bytes = out.ciphertext.toByteString(),
                    signedMessageStatus = rawStatus,
                    signature_data =
                        SignatureData(
                            AES_GCM_Response_data =
                                AES_GCM_Response_Signature_Data(nonce = nonce.toByteString(), counter = 7, tag = out.tag.toByteString()),
                        ),
                )
            // 와이어 왕복(인코딩 → 디코딩) 뒤에도 unknownFields가 남아 있어야 실제 수신 경로와 같다
            val fromWire = RoutableMessage.ADAPTER.decode(RoutableMessage.ADAPTER.encode(response))
            val decrypted = assertIs<SignerResult.Ok<Signer.DecryptedResponse>>(signer.decrypt(fromWire, requestHash)).value
            assertEquals("0a00", assertNotNull(decrypted.message.protobuf_message_as_bytes).hex())
            assertEquals(7u, decrypted.counter)
        }
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :domain:jvmTest --tests '*SignerCryptoTest*' --console=plain`
Expected: `decryptsResponseWhoseFaultCodeIsUnknownToWire` FAIL — `SignerResult.Fault(INVALID_SIGNATURE)` (AAD의 FAULT가 0으로 계산됨)

- [ ] **Step 4: 구현 (`Signer.kt`)**

파일 상단 상수에 `private const val TAG_MESSAGE_STATUS_SIGNED_MESSAGE_FAULT = 2`를 추가하고, `decrypt`의 `val fault = message.signedMessageStatus?.signed_message_fault?.value ?: 0` 줄을 `val fault = responseFault(message.signedMessageStatus)`로, `ResponseMetadata.build(...)`의 마지막 인자 `fault.toUInt()`를 `fault`로 바꾼다. `import com.tesla.generated.universalmessage.MessageStatus`를 추가한다. `decrypt` 아래에 헬퍼를 넣는다.

```kotlin
        /**
         * Go `Decrypt`의 `GetSignedMessageFault()`: 원시 uint32를 AAD의 FAULT로 쓴다. Wire는 proto 스냅샷에 없는 코드를
         * `unknownFields`로 옮기므로(`signed_message_fault`는 NONE), 알려진 코드가 NONE이면 원시 varint를 되찾는다.
         * 그래야 신형 펌웨어가 모르는 fault를 실은 암호화 응답도 복호화된다(SDD §12, M2에서 보완).
         */
        private fun responseFault(status: MessageStatus?): UInt {
            if (status == null) return 0u
            val known = status.signed_message_fault.value
            if (known != 0) return known.toUInt()
            return status.unknownFields.unknownVarint(TAG_MESSAGE_STATUS_SIGNED_MESSAGE_FAULT)?.toUInt() ?: 0u
        }
```

- [ ] **Step 5: 통과 확인, 커밋**

Run: `./gradlew :domain:jvmTest --tests '*SignerCryptoTest*' :domain:detekt :domain:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS). 공개 API는 바뀌지 않으므로 `apiDump`는 불필요(변경이 있으면 `apiCheck`가 CI에서 잡는다).

```bash
git add domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/Signer.kt domain/src/commonTest/kotlin/io/github/smallmiro/teslable/protocol/SignerCryptoTest.kt
git commit -m "feat(domain): Signer.decrypt uses the raw signed_message_fault from unknownFields in the response AAD like Go

Refs: FR-016, NFR-017
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `FakeVehicle` — 도메인별 `TestVerifier` + 대본 (Go `dummyConnector` + `verifier.go`), `TestGCM*` 포팅

참고 매뉴얼: `03-protocol.md` §7(핸드셰이크), §9.4(복구 규칙), §11(VCSEC 응답); `{{WORKFLOW_FILE}}` §2.4(FakeVehicle 원칙 — 7 시나리오, sleep 없음); `{{SDD_FILE}}` §9.3(대본 API), §9.4 M2 행(`verifier_test.go` 포팅). Go 원본: `dispatcher_test.go`(`dummyConnector` 84~274행, `handleSessionInfoRequests` 204~227행, `initReply` 195~202행, `SessionInfoReply` 179~193행, `testUUID` 38~44행), `verifier.go`(`Verify`, `SetSessionInfo`, `Encrypt`), `verifier_test.go`(`TestValidGCMEncryption` 47행, `TestGCMFlags` 57행, `TestGCMMissingDestination` 141행, `TestGCMOutOfOrderMessage` 218행, `TestEpochChange` 232행, `TestGCMCorruptedCiphertext` 298행, `TestGCMExpired` 319행, `TestGCMInvalidEpoch` 329행, `TestGCMInvalidTime` 339행, `TestGCMWindow` 439행, `TestVerifierEncryption` 566행).

**Files:**
- Create: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicle.kt`
- Modify: `tools/ci/ported-files.txt` (FakeVehicle.kt 추가)
- Test: `testing/src/commonTest/kotlin/io/github/smallmiro/teslable/testing/FakeVehicleTest.kt`

**Interfaces:**
- Consumes: Task 1 `TestVerifier`(`create`, `verify`, `setSessionInfo`, `signedSessionInfo`, `encryptResponse`, `rotateEpoch`, `shiftTimeZero`, `epoch`), Task 2 `FakeTransport`, M1 `Signer`(테스트에서), `RequestHash.of`, Wire `RoutableMessage`/`SessionInfoRequest`/`FromVCSECMessage`/`CommandStatus`/`Response`/`ActionStatus`/`NominalError`.
- Produces (Task 6~11 테스트가 사용):
  - `public class FakeVehicle(vin = Vin("0123456789ABCDEFG"), vehicleKey = TestCrypto.vehicleKey(), crypto = TestCrypto.primitives, random = TestCrypto.random, timeSource = TimeSource.Monotonic)`
  - 관찰: `val received: List<RoutableMessage>`, `val sessionInfoRequests: Int`, `val isConnectable: Boolean`, `fun verifier(domain: Domain): TestVerifier`, `fun epoch(domain): ByteArray`
  - 연결: `fun transport(retryInterval: Duration = 1.milliseconds): FakeTransport`, `fun connect(): VehicleResult<FakeTransport>`, `fun setConnectable(value: Boolean)`
  - 대본: `fun sleep(domainsToSleep: Set<Domain> = ALL_DOMAINS)`, `fun wake()`, `fun dropNextReplies(count: Int)`, `fun script(domain, vararg perRequest: List<ScriptedReply>)`, `fun scriptHandshake(domain, vararg faults: MessageFault_E)`, `fun rotateEpoch(domain)`, `fun shiftClock(domain, by: Duration)`(양수 = 차량 시계가 앞으로), `fun attachSessionInfoOnce(domain)`, `fun corruptNextSessionInfoTag(domain)`, `fun replayLastResponse(domain)`
  - `public class ScriptedReply(payload: ByteArray? = null, fault: MessageFault_E = NONE, operationStatus: OperationStatus_E = OK)`와 companion 헬퍼 `vcsecEmpty()`, `vcsecBusy()`, `vcsecAuthSuccess(counter = 1337)`, `vcsecWhitelistStatus(info)`, `vcsecNominalError(code)`, `vcsecGibberish()`, `vcsecPayload(message)`, `infotainmentOk()`, `infotainmentError(reason)`, `infotainmentPayload(response)`, 상수 `FLAG_ENCRYPT_RESPONSE = 2`, `TEST_UUID`(0..15), `ALL_DOMAINS`.
  - 처리 규칙: `session_info_request` → 도메인의 검증자(없으면 요청의 공개키로 생성)로 `setSessionInfo(challenge = uuid)`; 인증 명령 → `verify` 실패면 fault(+동봉 세션정보), 성공이면 대본(기본: VCSEC 빈 메시지, Infotainment `actionStatus OK`)을 요청 `flags & 2`면 `encryptResponse(counter++)`로 암호화; 비인증 명령 → 대본 평문. 대본이 빈 리스트면 응답 없음(테스트가 `transport.deliver`로 직접 만든다).

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// testing/src/commonTest/kotlin/io/github/smallmiro/teslable/testing/FakeVehicleTest.kt
package io.github.smallmiro.teslable.testing

import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.protocol.CommandMetadata
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.protocol.Session
import io.github.smallmiro.teslable.protocol.Signer
import io.github.smallmiro.teslable.protocol.SignerResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class FakeVehicleTest {
    private val crypto = TestCrypto.primitives
    private val plaintext = "hello world".encodeToByteArray() // peer_test.go testMessagePlaintext
    private val domain = Domain.DOMAIN_VEHICLE_SECURITY

    /** dispatcher 없이 FakeVehicle과 직접 대화하는 최소 클라이언트. 주소·uuid는 TestCrypto.random으로 뽑는다. */
    private class Client(
        val fake: FakeVehicle,
        val transport: FakeTransport,
        val scope: TestScope,
    ) {
        val crypto = TestCrypto.primitives

        suspend fun exchange(message: RoutableMessage): RoutableMessage? {
            val before = transport.delivered
            assertIs<VehicleResult.Success<Unit>>(transport.send(RoutableMessage.ADAPTER.encode(message)))
            if (transport.delivered == before) return null
            return RoutableMessage.ADAPTER.decode(transport.incoming.first())
        }

        suspend fun handshake(domain: Domain): Signer {
            val request =
                RoutableMessage(
                    to_destination = Destination(domain = domain),
                    from_destination = Destination(routing_address = TestCrypto.random.nextBytes(16).toByteString()),
                    uuid = TestCrypto.random.nextBytes(16).toByteString(),
                    session_info_request = SessionInfoRequest(public_key = TestCrypto.clientPublicKey.toByteArray().toByteString()),
                )
            val reply = assertNotNull(exchange(request))
            val info = assertNotNull(reply.session_info).toByteArray()
            val tag = assertNotNull(reply.signature_data?.session_info_tag).tag.toByteArray()
            val created =
                Signer.createAuthenticated(
                    TestCrypto.clientKey(), fake.vin, request.uuid.toByteArray(), info, tag, crypto, TestCrypto.random, scope.testTimeSource,
                )
            return assertIs<SignerResult.Ok<Signer>>(created).value
        }

        fun command(domain: Domain, payload: ByteArray, flags: Int = 0): RoutableMessage =
            RoutableMessage(
                to_destination = Destination(domain = domain),
                from_destination = Destination(routing_address = TestCrypto.random.nextBytes(16).toByteString()),
                uuid = TestCrypto.random.nextBytes(16).toByteString(),
                protobuf_message_as_bytes = payload.toByteString(),
                flags = flags,
            )

        fun encrypt(signer: Signer, message: RoutableMessage, lifetime: Duration = 1.minutes): RoutableMessage =
            assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(message, lifetime)).value
    }

    private fun TestScope.client(fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource)): Client = Client(fake, fake.transport(), this)

    /** verifier_test.go runVerifyTest의 응답판: fault와 세션정보 동봉 여부를 본다. */
    private fun assertFault(
        reply: RoutableMessage?,
        expected: MessageFault_E,
        expectSessionInfo: Boolean,
    ) {
        val message = assertNotNull(reply, "vehicle must reply")
        assertEquals(expected, message.signedMessageStatus?.signed_message_fault ?: MessageFault_E.MESSAGEFAULT_ERROR_NONE)
        assertEquals(expectSessionInfo, message.session_info != null, "session info attached")
        if (expectSessionInfo) assertNotNull(message.signature_data?.session_info_tag)
    }

    @Test
    fun handshakeReplyMirrorsGoInitReply() =
        runTest {
            val c = client()
            val request =
                RoutableMessage(
                    to_destination = Destination(domain = domain),
                    from_destination = Destination(routing_address = ByteArray(16) { 5 }.toByteString()),
                    uuid = ByteArray(16) { 7 }.toByteString(),
                    session_info_request = SessionInfoRequest(public_key = TestCrypto.clientPublicKey.toByteArray().toByteString()),
                )
            val reply = assertNotNull(c.exchange(request))
            // dispatcher_test.go initReply: to = request.from, from = request.to, request_uuid = request.uuid, uuid = testUUID()
            assertContentEquals(ByteArray(16) { 5 }, assertNotNull(reply.to_destination?.routing_address).toByteArray())
            assertEquals(domain, reply.from_destination?.domain)
            assertContentEquals(ByteArray(16) { 7 }, reply.request_uuid.toByteArray())
            assertContentEquals(FakeVehicle.TEST_UUID, reply.uuid.toByteArray())
            assertNull(reply.session_info_request)
            assertEquals(1, c.fake.sessionInfoRequests)
            // 태그는 클라이언트 세션으로 검증된다(TestVerifier.setSessionInfo 경로)
            val session = Session.establish(TestCrypto.clientKey(), TestCrypto.vehiclePublicKey, crypto)
            assertTrue(
                session.verifySessionInfoTag(
                    c.fake.vin.toByteArray(),
                    ByteArray(16) { 7 },
                    assertNotNull(reply.session_info).toByteArray(),
                    assertNotNull(reply.signature_data?.session_info_tag).tag.toByteArray(),
                ),
            )
        }

    @Test
    fun acceptsThenRejectsReplayedCommand() =
        // verifier_test.go TestValidGCMEncryption
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            assertFault(c.exchange(message), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
            assertFault(c.exchange(message), MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, expectSessionInfo = true)
        }

    @Test
    fun rejectsTamperedFlags() =
        // verifier_test.go TestGCMFlags
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            assertFault(c.exchange(message.copy(flags = 1)), MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, expectSessionInfo = true)
            assertFault(c.exchange(message.copy(flags = 0)), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
        }

    @Test
    fun rejectsMissingDestinationAsInvalidDomains() =
        // verifier_test.go TestGCMMissingDestination — FakeVehicle은 도메인 없는 메시지를 라우팅할 수 없어 검증자를 직접 부른다
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val result = assertIs<TestVerifier.VerifyResult.Fault>(c.fake.verifier(domain).verify(message.copy(to_destination = null)))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_DOMAINS, result.fault)
            assertNull(result.sessionInfo)
            assertNull(c.exchange(message.copy(to_destination = null))) // 라우팅 불가 → 응답 없음
        }

    @Test
    fun rejectsOutOfOrderMessageWithTtlTooLong() =
        // verifier_test.go TestGCMOutOfOrderMessage
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val first = c.encrypt(signer, c.command(domain, plaintext))
            val second = c.encrypt(signer, c.command(domain, plaintext))
            assertFault(c.exchange(second), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
            assertFault(c.exchange(first), MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG, expectSessionInfo = true)
        }

    @Test
    fun rejectsAfterRebootThenResyncsWithAttachedSessionInfo() =
        // verifier_test.go TestEpochChange (차량 재부팅 = rotateEpoch)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            assertFault(c.exchange(c.encrypt(signer, c.command(domain, plaintext), 1.seconds)), MessageFault_E.MESSAGEFAULT_ERROR_NONE, false)
            val oldEpoch = c.fake.epoch(domain)
            c.fake.rotateEpoch(domain)
            assertFalse(oldEpoch.contentEquals(c.fake.epoch(domain)))
            val stale = c.encrypt(signer, c.command(domain, plaintext), 1.seconds)
            val reply = assertNotNull(c.exchange(stale))
            assertFault(reply, MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH, expectSessionInfo = true)
            // 동봉 세션정보로 재동기화 (challenge = 요청 uuid = 응답 request_uuid)
            val update =
                signer.updateSignedSessionInfo(
                    reply.request_uuid.toByteArray(),
                    assertNotNull(reply.session_info).toByteArray(),
                    assertNotNull(reply.signature_data?.session_info_tag).tag.toByteArray(),
                )
            assertIs<SignerResult.Ok<Unit>>(update)
            assertContentEquals(c.fake.epoch(domain), signer.epoch)
            assertFault(c.exchange(c.encrypt(signer, c.command(domain, plaintext), 1.seconds)), MessageFault_E.MESSAGEFAULT_ERROR_NONE, false)
        }

    @Test
    fun rejectsExpiredCommand() =
        // verifier_test.go TestGCMExpired: verifier.timeZero -1h → 차량 시계가 1시간 앞선다
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            c.fake.shiftClock(domain, 1.hours)
            assertFault(c.exchange(message), MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED, expectSessionInfo = true)
        }

    @Test
    fun rejectsWrongEpoch() =
        // verifier_test.go TestGCMInvalidEpoch (검증자 epoch를 뒤집는 대신 메시지 epoch를 뒤집는다 — 같은 분기)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val gcm = assertNotNull(message.signature_data?.AES_GCM_Personalized_data)
            val flipped = gcm.epoch.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val tampered =
                message.copy(
                    signature_data =
                        SignatureData(
                            signer_identity = message.signature_data?.signer_identity,
                            AES_GCM_Personalized_data = gcm.copy(epoch = flipped.toByteString()),
                        ),
                )
            assertFault(c.exchange(tampered), MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH, expectSessionInfo = true)
        }

    @Test
    fun rejectsCorruptedCiphertext() =
        // verifier_test.go TestGCMCorruptedCiphertext
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val ct = assertNotNull(message.protobuf_message_as_bytes).toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFault(c.exchange(message.copy(protobuf_message_as_bytes = ct.toByteString())), MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, true)
        }

    @Test
    fun rejectsExpirationBeyondEpochLength() =
        // verifier_test.go TestGCMInvalidTime: expires_at > epochLength → BAD_PARAMETER (서명 검사보다 앞서므로 세션정보 동봉)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val gcm = assertNotNull(message.signature_data?.AES_GCM_Personalized_data)
            val tooLate = gcm.copy(expires_at = (CommandMetadata.EPOCH_LENGTH_SECONDS + 1u).toInt())
            val tampered =
                message.copy(
                    signature_data = SignatureData(signer_identity = message.signature_data?.signer_identity, AES_GCM_Personalized_data = tooLate),
                )
            assertFault(c.exchange(tampered), MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, expectSessionInfo = true)
        }

    @Test
    fun encryptsResponseThatSignerDecrypts() =
        // verifier_test.go TestVerifierEncryption (flags & 2 → AES_GCM_Response)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            c.fake.script(domain, listOf(FakeVehicle.ScriptedReply(payload = "hello".encodeToByteArray())))
            val request = c.encrypt(signer, c.command(domain, plaintext, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE))
            val reply = assertNotNull(c.exchange(request))
            val gcm = assertNotNull(reply.signature_data?.AES_GCM_Response_data)
            assertEquals(1, gcm.counter)
            assertFalse("hello".encodeToByteArray().contentEquals(assertNotNull(reply.protobuf_message_as_bytes).toByteArray()))
            val id = assertNotNull(RequestHash.of(request))
            assertIs<SignerResult.Fault>(signer.decrypt(reply, id.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
            val decrypted = assertIs<SignerResult.Ok<Signer.DecryptedResponse>>(signer.decrypt(reply, id)).value
            assertEquals(1u, decrypted.counter)
            assertContentEquals("hello".encodeToByteArray(), assertNotNull(decrypted.message.protobuf_message_as_bytes).toByteArray())
        }

    @Test
    fun enforcesSlidingWindowLikeGo() =
        // verifier_test.go TestGCMWindow (windowSize 32, maxSecondsWithoutCounter 30)
        runTest {
            val windowSize = 32
            val duration = 28.seconds
            val c = client()
            val signer = c.handshake(domain)
            for (k in 0 until 3 * windowSize) {
                val prepared = List(windowSize) { c.encrypt(signer, c.command(domain, plaintext), duration) }
                repeat(k + 1) {
                    assertFault(c.exchange(c.encrypt(signer, c.command(domain, plaintext), duration)), MessageFault_E.MESSAGEFAULT_ERROR_NONE, false)
                }
                for (i in 0 until windowSize) {
                    val j = ((i + 1) * 97) % windowSize
                    if (j >= k) assertFault(c.exchange(prepared[j]), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
                    assertFault(c.exchange(prepared[j]), MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, expectSessionInfo = true)
                }
            }
        }

    @Test
    fun scriptsHandshakeFaultsDropsAndCorruptedTags() =
        runTest {
            val c = client()
            c.fake.scriptHandshake(domain, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            val request =
                c.command(domain, plaintext).copy(
                    protobuf_message_as_bytes = null,
                    session_info_request = SessionInfoRequest(public_key = TestCrypto.clientPublicKey.toByteArray().toByteString()),
                )
            assertFault(c.exchange(request), MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID, expectSessionInfo = false)
            c.fake.corruptNextSessionInfoTag(domain)
            val corrupted = assertNotNull(c.exchange(request))
            val session = Session.establish(TestCrypto.clientKey(), TestCrypto.vehiclePublicKey, crypto)
            assertFalse(
                session.verifySessionInfoTag(
                    c.fake.vin.toByteArray(),
                    request.uuid.toByteArray(),
                    assertNotNull(corrupted.session_info).toByteArray(),
                    assertNotNull(corrupted.signature_data?.session_info_tag).tag.toByteArray(),
                ),
            )
            c.fake.dropNextReplies(1)
            assertNull(c.exchange(request))
            c.fake.sleep()
            assertNull(c.exchange(request))
            c.fake.wake()
            assertNotNull(c.exchange(request))
            assertEquals(5, c.fake.sessionInfoRequests)
        }

    @Test
    fun replaysLastResponseAndAttachesSessionInfoOnce() =
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            c.fake.attachSessionInfoOnce(domain)
            val first = assertNotNull(c.exchange(c.encrypt(signer, c.command(domain, plaintext, FakeVehicle.FLAG_ENCRYPT_RESPONSE))))
            assertNotNull(first.session_info) // 선제적 세션정보는 평문 + 태그(암호화하지 않는다)
            assertNull(first.signature_data?.AES_GCM_Response_data)
            val second = assertNotNull(c.exchange(c.encrypt(signer, c.command(domain, plaintext, FakeVehicle.FLAG_ENCRYPT_RESPONSE))))
            assertNull(second.session_info)
            assertEquals(1, assertNotNull(second.signature_data?.AES_GCM_Response_data).counter)
            c.fake.replayLastResponse(domain)
            val replayed = RoutableMessage.ADAPTER.decode(c.transport.incoming.first())
            assertEquals(second, replayed)
        }

    @Test
    fun refusesConnectionWhenNotConnectable() =
        // 슬롯 초과(M2 수준): 광고가 connectable=false면 연결 자체가 MaxConnectionsExceeded로 실패하고 재시도하지 않는다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            assertIs<VehicleResult.Success<FakeTransport>>(fake.connect())
            fake.setConnectable(false)
            val refused = assertIs<VehicleResult.Failure>(fake.connect())
            assertEquals(VehicleError.TransportError.MaxConnectionsExceeded, refused.error)
            assertFalse(refused.error.shouldRetry())
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :testing:jvmTest --tests '*FakeVehicleTest*' --console=plain`
Expected: 컴파일 실패 (`FakeVehicle` 없음)

- [ ] **Step 3: 구현**

```kotlin
// testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicle.kt
// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher_test.go (Apache-2.0) — dummyConnector, handleSessionInfoRequests, initReply, testUUID
package io.github.smallmiro.teslable.testing

import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.ResultReason
import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.errors.NominalError
import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.SignedMessage_status
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.WhitelistOperation_status
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.protocol.RequestHash
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import com.tesla.generated.carserver.server.OperationStatus_E as CarServerOperationStatus
import com.tesla.generated.vcsec.OperationStatus_E as VcsecOperationStatus

/**
 * 결정적 가짜 차량(SDD §9.3). 도메인마다 [TestVerifier] 하나를 감싸 `verifier.go`의 GCM 경로로 명령을 검증하고, 응답을 대본대로
 * 돌려준다. Go `dummyConnector`는 세션정보 요청만 처리하지만 여기서는 인증 명령도 검증한다(NFR-003 시나리오). 시간은 [timeSource]로만
 * 흐르고 `runTest` 단일 스레드 전용이다(스레드 안전하지 않다). 응답은 [FakeTransport.send] 안에서 동기로 만들어 [FakeTransport.deliver]로 넣는다.
 */
@OptIn(InternalTeslableApi::class)
@Suppress("TooManyFunctions") // dummyConnector 대본 API + verifier.go 위임 메서드와 1:1 대응
public class FakeVehicle(
    /** 차량 VIN. 기본은 Go `dummyConnector.VIN()`. */
    public val vin: Vin = Vin(FakeTransport.DEFAULT_VIN),
    private val vehicleKey: EcdhPrivateKey = TestCrypto.vehicleKey(),
    private val crypto: CryptoPrimitives = TestCrypto.primitives,
    private val random: RandomSource = TestCrypto.random,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** 대본 응답 하나. [payload]가 null이면 payload 없는 메시지(VCSEC "빈 메시지 = 성공"). */
    public class ScriptedReply(
        payload: ByteArray? = null,
        /** `signedMessageStatus.signed_message_fault`. */
        public val fault: MessageFault_E = MessageFault_E.MESSAGEFAULT_ERROR_NONE,
        /** `signedMessageStatus.operation_status`. */
        public val operationStatus: OperationStatus_E = OperationStatus_E.OPERATIONSTATUS_OK,
    ) {
        /** `protobuf_message_as_bytes` (사본). */
        public val payload: ByteArray? = payload?.copyOf()
    }

    private class DomainState {
        var verifier: TestVerifier? = null
        var clientPublic: PublicKeyBytes? = null
        var responseCounter = 0u
        val script = ArrayDeque<List<ScriptedReply>>()
        val handshakeFaults = ArrayDeque<MessageFault_E>()
        var corruptNextTag = false
        var attachSessionInfoOnce = false
        var lastReply: Pair<FakeTransport, ByteArray>? = null
        var asleep = false
    }

    private val domains = ALL_DOMAINS.associateWith { DomainState() }
    private val receivedMessages = mutableListOf<RoutableMessage>()
    private var dropRemaining = 0
    private var sessionInfoRequestCount = 0
    private var connectableFlag = true

    /** 지금까지 받은(디코딩된) 요청. Go `inbox`. */
    public val received: List<RoutableMessage> get() = receivedMessages.toList()

    /** 받은 세션정보 요청 수. Go `callbackCount`. */
    public val sessionInfoRequests: Int get() = sessionInfoRequestCount

    /** 광고의 connectable 플래그. false면 [connect]가 슬롯 초과로 실패한다. */
    public val isConnectable: Boolean get() = connectableFlag

    /** 도메인의 검증자. 핸드셰이크나 인증 명령을 한 번 받아야 존재한다. */
    public fun verifier(domain: Domain): TestVerifier = checkNotNull(domains.getValue(domain).verifier) { "no verifier for $domain yet" }

    /** 도메인의 현재 epoch. */
    public fun epoch(domain: Domain): ByteArray = verifier(domain).epoch

    /** 이 차량에 연결된 새 [FakeTransport]. Go 테스트의 `newDummyConnector`. [retryInterval]은 시간 초과 단계를 결정적으로 고정하는 테스트가 바꾼다. */
    public fun transport(retryInterval: Duration = 1.milliseconds): FakeTransport {
        val transport = FakeTransport(vin, retryInterval = retryInterval)
        transport.onSend = { bytes -> handle(transport, bytes) }
        return transport
    }

    /** M3 `TransportFactory.connect`의 M2 모델: connectable이 아니면 `MaxConnectionsExceeded`(재시도 없음, Go `tryToConnect`). */
    public fun connect(): VehicleResult<FakeTransport> =
        if (connectableFlag) VehicleResult.Success(transport()) else VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded)

    /** 광고 connectable 플래그를 놓는다(슬롯 초과 시나리오). */
    public fun setConnectable(value: Boolean) {
        connectableFlag = value
    }

    /** 해당 도메인의 응답을 버리기 시작한다(Go `Sleep`; Infotainment 수면 = `sleep(setOf(DOMAIN_INFOTAINMENT))`). */
    public fun sleep(domainsToSleep: Set<Domain> = ALL_DOMAINS) {
        for (domain in domainsToSleep) domains.getValue(domain).asleep = true
    }

    /** 모든 도메인의 응답을 다시 보낸다(Go `Wake`). */
    public fun wake() {
        for (state in domains.values) state.asleep = false
    }

    /** 다음 [count]개 응답을 버린다(응답 유실 시나리오). */
    public fun dropNextReplies(count: Int) {
        dropRemaining += count
    }

    /** 다음 명령들에 대한 응답 대본. 요청 하나에 리스트 하나(빈 리스트 = 응답 없음). 대본이 없으면 기본 응답 하나. */
    public fun script(
        domain: Domain,
        vararg perRequest: List<ScriptedReply>,
    ) {
        domains.getValue(domain).script.addAll(perRequest)
    }

    /** 다음 세션정보 요청들에 fault로 답한다(`NONE`이면 정상 응답). */
    public fun scriptHandshake(
        domain: Domain,
        vararg faults: MessageFault_E,
    ) {
        domains.getValue(domain).handshakeFaults.addAll(faults)
    }

    /** 차량 재부팅: 새 epoch, counter 0, 시계 원점 리셋. */
    public fun rotateEpoch(domain: Domain) {
        verifier(domain).rotateEpoch()
    }

    /** 차량 시계를 [by]만큼 옮긴다. 양수면 앞으로(명령 만료 유발), 음수면 뒤로(시계 역행). */
    public fun shiftClock(
        domain: Domain,
        by: Duration,
    ) {
        verifier(domain).shiftTimeZero(-by)
    }

    /** 다음 성공 응답에 세션정보를 선제적으로 동봉한다(평문 + 태그, payload 대신). */
    public fun attachSessionInfoOnce(domain: Domain) {
        domains.getValue(domain).attachSessionInfoOnce = true
    }

    /** 다음 세션정보 태그의 첫 바이트를 뒤집는다(HMAC 불일치 시나리오). */
    public fun corruptNextSessionInfoTag(domain: Domain) {
        domains.getValue(domain).corruptNextTag = true
    }

    /** 마지막으로 보낸 응답 바이트를 같은 전송으로 다시 넣는다(재전송 응답 시나리오). */
    public fun replayLastResponse(domain: Domain) {
        val (transport, bytes) = checkNotNull(domains.getValue(domain).lastReply) { "no reply sent for $domain yet" }
        transport.deliver(bytes)
    }

    private suspend fun handle(
        transport: FakeTransport,
        bytes: ByteArray,
    ) {
        val message =
            try {
                RoutableMessage.ADAPTER.decode(bytes)
            } catch (ignored: IOException) {
                return // RoutableMessage가 아닌 프레임(M4 ToVCSECMessage)은 무시
            }
        receivedMessages += message
        val domain = message.to_destination?.domain ?: return
        val state = domains[domain] ?: return
        val replies =
            when {
                message.session_info_request != null -> handleSessionInfoRequest(state, domain, message)
                message.signature_data?.AES_GCM_Personalized_data != null -> handleAuthenticated(state, domain, message)
                message.protobuf_message_as_bytes != null -> scriptedReplies(state, domain, message, verifier = null)
                else -> emptyList()
            }
        for (reply in replies) deliver(transport, state, reply)
    }

    private suspend fun handleSessionInfoRequest(
        state: DomainState,
        domain: Domain,
        message: RoutableMessage,
    ): List<RoutableMessage> {
        sessionInfoRequestCount++
        val fault = state.handshakeFaults.removeFirstOrNull()
        if (fault != null && fault != MessageFault_E.MESSAGEFAULT_ERROR_NONE) return listOf(faultReply(message, fault))
        val request = checkNotNull(message.session_info_request)
        val clientPublic =
            try {
                PublicKeyBytes(request.public_key.toByteArray())
            } catch (ignored: IllegalArgumentException) {
                return listOf(faultReply(message, MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER))
            }
        val verifier = verifierFor(state, domain, clientPublic)
        val reply = verifier.setSessionInfo(message.uuid.toByteArray(), initReply(message))
        return listOf(maybeCorruptTag(state, reply))
    }

    private suspend fun handleAuthenticated(
        state: DomainState,
        domain: Domain,
        message: RoutableMessage,
    ): List<RoutableMessage> {
        val signerPublic =
            message.signature_data?.signer_identity?.public_key
                ?: return listOf(faultReply(message, MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER))
        val clientPublic =
            try {
                PublicKeyBytes(signerPublic.toByteArray())
            } catch (ignored: IllegalArgumentException) {
                return listOf(faultReply(message, MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER))
            }
        val verifier = verifierFor(state, domain, clientPublic)
        return when (val result = verifier.verify(message)) {
            is TestVerifier.VerifyResult.Fault -> listOf(attachSessionInfo(state, faultReply(message, result.fault), result.sessionInfo))
            is TestVerifier.VerifyResult.Ok -> scriptedReplies(state, domain, message, verifier)
        }
    }

    /** 검증자는 도메인마다 하나를 유지한다. 클라이언트 공개키가 바뀌면(다른 클라이언트) 새로 만든다. Go dummyConnector는 요청마다 새로 만든다. */
    private suspend fun verifierFor(
        state: DomainState,
        domain: Domain,
        clientPublic: PublicKeyBytes,
    ): TestVerifier {
        val existing = state.verifier
        if (existing != null && state.clientPublic == clientPublic) return existing
        val created = TestVerifier.create(vehicleKey, vin.toByteArray(), domain, clientPublic, crypto, random, timeSource)
        state.verifier = created
        state.clientPublic = clientPublic
        state.responseCounter = 0u
        return created
    }

    private fun scriptedReplies(
        state: DomainState,
        domain: Domain,
        request: RoutableMessage,
        verifier: TestVerifier?,
    ): List<RoutableMessage> {
        val scripted = state.script.removeFirstOrNull() ?: listOf(defaultReply(domain))
        val requestHash = RequestHash.of(request)
        val encrypt = verifier != null && requestHash != null && (request.flags and FLAG_ENCRYPT_RESPONSE) != 0
        return scripted.map { entry ->
            var reply =
                initReply(request).copy(
                    protobuf_message_as_bytes = entry.payload?.toByteString(),
                    signedMessageStatus = statusOf(entry),
                )
            if (verifier != null && state.attachSessionInfoOnce) {
                state.attachSessionInfoOnce = false
                reply = attachSessionInfo(state, reply, verifier.signedSessionInfo(request.uuid.toByteArray()))
            }
            if (encrypt && reply.session_info == null) {
                checkNotNull(verifier).encryptResponse(reply, checkNotNull(requestHash), ++state.responseCounter)
            } else {
                reply
            }
        }
    }

    private fun statusOf(entry: ScriptedReply): MessageStatus? =
        if (entry.fault == MessageFault_E.MESSAGEFAULT_ERROR_NONE && entry.operationStatus == OperationStatus_E.OPERATIONSTATUS_OK) {
            null
        } else {
            MessageStatus(operation_status = entry.operationStatus, signed_message_fault = entry.fault)
        }

    private fun defaultReply(domain: Domain): ScriptedReply = if (domain == Domain.DOMAIN_INFOTAINMENT) infotainmentOk() else vcsecEmpty()

    private fun attachSessionInfo(
        state: DomainState,
        reply: RoutableMessage,
        signed: SignedSessionInfo?,
    ): RoutableMessage {
        if (signed == null) return reply
        val withInfo =
            reply.copy(
                protobuf_message_as_bytes = null,
                session_info_request = null,
                session_info = signed.encoded.toByteString(),
                signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = signed.tag.toByteString())),
            )
        return maybeCorruptTag(state, withInfo)
    }

    private fun maybeCorruptTag(
        state: DomainState,
        reply: RoutableMessage,
    ): RoutableMessage {
        if (!state.corruptNextTag) return reply
        state.corruptNextTag = false
        val tag = checkNotNull(reply.signature_data?.session_info_tag).tag.toByteArray()
        tag[0] = (tag[0].toInt() xor 1).toByte()
        return reply.copy(signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = tag.toByteString())))
    }

    private fun deliver(
        transport: FakeTransport,
        state: DomainState,
        reply: RoutableMessage,
    ) {
        if (state.asleep) return
        if (dropRemaining > 0) {
            dropRemaining--
            return
        }
        val bytes = RoutableMessage.ADAPTER.encode(reply)
        state.lastReply = transport to bytes
        transport.deliver(bytes)
    }

    /** Go `initReply`: to = 요청의 from(routing address), from = 요청의 to(domain), request_uuid = 요청 uuid, uuid = testUUID. */
    private fun initReply(message: RoutableMessage): RoutableMessage =
        RoutableMessage(
            to_destination = message.from_destination,
            from_destination = message.to_destination,
            request_uuid = message.uuid,
            uuid = TEST_UUID.toByteString(),
        )

    private fun faultReply(
        message: RoutableMessage,
        fault: MessageFault_E,
    ): RoutableMessage = initReply(message).copy(signedMessageStatus = MessageStatus(signed_message_fault = fault))

    /** 대본 헬퍼와 상수. */
    public companion object {
        /** `1 shl Flags.FLAG_ENCRYPT_RESPONSE` = 2 (Go `vehicle.DefaultFlags`). */
        public const val FLAG_ENCRYPT_RESPONSE: Int = 2

        /** Go `testUUID()`: 0x00..0x0f. 응답의 `uuid`. */
        public val TEST_UUID: ByteArray = ByteArray(16) { it.toByte() }

        /** VCSEC + INFOTAINMENT. */
        public val ALL_DOMAINS: Set<Domain> = setOf(Domain.DOMAIN_VEHICLE_SECURITY, Domain.DOMAIN_INFOTAINMENT)

        /** payload 없는 VCSEC 응답(RKE/closure 최종 성공). */
        public fun vcsecEmpty(): ScriptedReply = ScriptedReply()

        /** `commandStatus{WAIT}` (Go `EnqueueVCSECBusy`). */
        public fun vcsecBusy(): ScriptedReply =
            vcsecPayload(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = VcsecOperationStatus.OPERATIONSTATUS_WAIT)))

        /** `commandStatus{OK, signedMessageStatus{counter}}` (Go `EnqueueAuthenticationSuccessResponse`; whitelist 작업의 중간 응답). */
        public fun vcsecAuthSuccess(counter: Int = 1337): ScriptedReply =
            vcsecPayload(
                FromVCSECMessage(
                    commandStatus =
                        CommandStatus(
                            operationStatus = VcsecOperationStatus.OPERATIONSTATUS_OK,
                            signedMessageStatus = SignedMessage_status(counter = counter),
                        ),
                ),
            )

        /** `commandStatus{ERROR|OK, whitelistOperationStatus{info}}` (Go `EnqueueWhitelistOperationStatus`; NONE이면 OK로 성공). */
        public fun vcsecWhitelistStatus(info: WhitelistOperation_information_E): ScriptedReply =
            vcsecPayload(
                FromVCSECMessage(
                    commandStatus =
                        CommandStatus(
                            operationStatus =
                                if (info == WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE) {
                                    VcsecOperationStatus.OPERATIONSTATUS_OK
                                } else {
                                    VcsecOperationStatus.OPERATIONSTATUS_ERROR
                                },
                            whitelistOperationStatus = WhitelistOperation_status(whitelistOperationInformation = info),
                        ),
                ),
            )

        /** `nominalError{genericError}` (Go `TestNominalVSCECError`). */
        public fun vcsecNominalError(code: GenericError_E): ScriptedReply =
            vcsecPayload(FromVCSECMessage(nominalError = NominalError(genericError = code)))

        /** 파싱 불가 페이로드 `0xFF` (Go `TestGibberishVCSECResponse`). */
        public fun vcsecGibberish(): ScriptedReply = ScriptedReply(payload = byteArrayOf(0xFF.toByte()))

        /** 임의의 `FromVCSECMessage`. */
        public fun vcsecPayload(message: FromVCSECMessage): ScriptedReply = ScriptedReply(payload = message.encode())

        /** `Response{actionStatus{OK}}`. */
        public fun infotainmentOk(): ScriptedReply =
            infotainmentPayload(Response(actionStatus = ActionStatus(result = CarServerOperationStatus.OPERATIONSTATUS_OK)))

        /** `Response{actionStatus{ERROR, result_reason{plain_text}}}`. [reason]이 null이면 사유 없음. */
        public fun infotainmentError(reason: String?): ScriptedReply =
            infotainmentPayload(
                Response(
                    actionStatus =
                        ActionStatus(
                            result = CarServerOperationStatus.OPERATIONSTATUS_ERROR,
                            result_reason = reason?.let { ResultReason(plain_text = it) },
                        ),
                ),
            )

        /** 임의의 `CarServer.Response`. */
        public fun infotainmentPayload(response: Response): ScriptedReply = ScriptedReply(payload = response.encode())
    }
}
```

`tools/ci/ported-files.txt`에 `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicle.kt`를 추가한다. Wire enum 두 개가 같은 단순 이름(`OperationStatus_E`)이라 `as` 별칭 import를 쓴다(ktlint는 별칭 import를 파일 끝에 정렬한다 — `lintKotlin`이 지적하면 그 순서를 따른다).

- [ ] **Step 4: 통과 확인, 커밋**

Run: `./gradlew :testing:jvmTest --tests '*FakeVehicleTest*' :testing:detekt :testing:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS). `enforcesSlidingWindowLikeGo`는 약 1만 번의 검증을 하므로 수 초 걸릴 수 있다(Go와 같은 범위).

```bash
git add testing/src tools/ci/ported-files.txt
git commit -m "test(testing): FakeVehicle wraps one TestVerifier per domain; port verifier_test.go GCM cases through it

Refs: NFR-003, FR-014, FR-018
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 5: `PendingRequest`(Go `receiver`)와 `SessionState`(Go `session`)

참고 매뉴얼: `01-architecture.md` §4.1(receiverKey 매칭), §5(락 순서 `sessionLock → session.lock`, `readySignal`), `03-protocol.md` §9.2(요청별 counter 윈도우), §9.4; `08-errors.md` §5(processHello 흐름). Go 원본: `internal/dispatcher/receiver.go`(전부: `receiverBufferSize = 10`, `receiverKey`, `receiver`, `expired`), `session.go`(`newSession`, `decrypt`, `authorize`, `export`, `processHello`). 설계: `{{SDD_FILE}}` §2.2(`SessionState`, `PendingRequest`), §5, 설계 구체화 4·5.

**Files:**
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/PendingRequest.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionState.kt`
- Delete: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/Placeholder.kt`
- Modify: `tools/ci/ported-files.txt` (두 파일 추가)
- Modify: `application/api/*` (`apiDump`)
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/PendingRequestTest.kt`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionStateTest.kt`

**Interfaces:**
- Consumes: M1 `Signer`(`createAuthenticated`, `updateSignedSessionInfo`, `importSessionInfo`, `encrypt`, `decrypt`, `exportSessionInfo`, `timestamp`, `counter`, `close`), `SignerResult`, M0 `SlidingWindow`, Wire `RoutableMessage`, `Domain`, okio `ByteString`, `kotlinx.coroutines` `Channel`/`Mutex`/`CompletableDeferred`.
- Produces:
  - `internal data class PendingKey(address: ByteString, uuid: ByteString, domain: Domain)` — VCSEC는 `uuid = ByteString.EMPTY`.
  - `public class PendingRequest internal constructor(key, requestHash: ByteArray?, sentAt: TimeMark) : AutoCloseable { val requestHash: ByteArray?; val uuid: ByteArray; suspend fun receive(): RoutableMessage; fun tryReceive(): RoutableMessage?; close(); internal val onReceive: SelectClause1<RoutableMessage>; internal val antiReplay: SlidingWindow; internal val isClosed: Boolean; internal fun deliver(message): Boolean; internal fun expired(lifetime): Boolean; companion BUFFER_SIZE = 10 }`. `close()`는 non-suspend 플래그 + 채널 닫기이며 디스패처가 맵에서 지연 제거한다(취소 중 `finally`에서도 안전).
  - `public class SessionState internal constructor(vin, privateKey, crypto, random, timeSource) { val isReady: Boolean; suspend fun awaitReady(); suspend fun processHello(challenge, encodedInfo, tag): SignerResult<Unit>; suspend fun authorize(message, lifetime): SignerResult<RoutableMessage>; suspend fun decrypt(message, requestHash): SignerResult<Signer.DecryptedResponse>?; suspend fun export(): ByteArray?; suspend fun loadFromCache(encodedInfo, age: Duration): SignerResult<Unit>; suspend fun timestamp(): UInt?; suspend fun counter(): UInt?; suspend fun close(); internal val ready: Deferred<Unit> }`.
  - Task 6 `Dispatcher`, Task 7 `HandshakeFlow`, Task 8 캐시 동기화가 사용.

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/PendingRequestTest.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PendingRequestTest {
    private val key = PendingKey(ByteArray(16) { 1 }.toByteString(), ByteString.EMPTY, Domain.DOMAIN_VEHICLE_SECURITY)

    @Test
    fun buffersTenResponsesThenDropsLikeGo() =
        // receiver.go receiverBufferSize = 10; dispatcher.go process: select { handler.ch <- message; default: drop }
        runTest {
            val pending = PendingRequest(key, requestHash = null, sentAt = testTimeSource.markNow())
            repeat(PendingRequest.BUFFER_SIZE) { assertTrue(pending.deliver(RoutableMessage(flags = it))) }
            assertFalse(pending.deliver(RoutableMessage(flags = 99)))
            repeat(PendingRequest.BUFFER_SIZE) { assertEquals(it, pending.receive().flags) }
            assertNull(pending.tryReceive())
        }

    @Test
    fun expiresOnceLifetimeHasPassed() =
        // receiver.go expired: time.Now().After(requestSentAt.Add(lifetime))
        runTest {
            val pending = PendingRequest(key, requestHash = null, sentAt = testTimeSource.markNow())
            assertFalse(pending.expired(4.seconds))
            advanceTimeBy(4_000)
            assertFalse(pending.expired(4.seconds)) // 정확히 4초는 아직 유효
            advanceTimeBy(1)
            assertTrue(pending.expired(4.seconds))
        }

    @Test
    fun closeMarksClosedClosesChannelAndIsIdempotent() =
        runTest {
            val pending = PendingRequest(key, requestHash = ByteArray(17) { 5 }, sentAt = testTimeSource.markNow())
            assertFalse(pending.isClosed)
            pending.close()
            pending.close()
            assertTrue(pending.isClosed)
            assertFalse(pending.deliver(RoutableMessage()))
            assertNull(pending.tryReceive())
            assertFailsWith<ClosedReceiveChannelException> { pending.receive() }
        }

    @Test
    fun requestHashIsCopiedDefensively() =
        runTest {
            val original = ByteArray(17) { 7 }
            val pending = PendingRequest(key, requestHash = original, sentAt = testTimeSource.markNow())
            original[0] = 0
            assertEquals(7, pending.requestHash?.get(0))
            pending.requestHash?.set(1, 0)
            assertEquals(7, pending.requestHash?.get(1))
            assertEquals("<01010101010101010101010101010101-: DOMAIN_VEHICLE_SECURITY>", key.toString()) // receiver.go receiverKey.String()
        }
}
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionStateTest.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.protocol.SignerResult
import io.github.smallmiro.teslable.testing.FixedRandom
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.testing.TestVerifier
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class SessionStateTest {
    private val crypto = TestCrypto.primitives
    private val vin = Vin(ProtocolVectors.VIN)
    private val challenge = ByteArray(16) { it.toByte() }
    private val domain = Domain.DOMAIN_VEHICLE_SECURITY

    private fun TestScope.state(): SessionState = SessionState(vin, TestCrypto.clientKey(), crypto, TestCrypto.random, testTimeSource)

    private suspend fun TestScope.verifier(): TestVerifier =
        TestVerifier.create(
            TestCrypto.vehicleKey(),
            vin.toByteArray(),
            domain,
            TestCrypto.clientPublicKey,
            crypto,
            FixedRandom(ProtocolVectors.EPOCH.hexToBytes(), fallback = TestCrypto.random),
            testTimeSource,
        )

    private suspend fun hello(
        state: SessionState,
        verifier: TestVerifier,
        challenge: ByteArray = this.challenge,
    ): SignerResult<Unit> {
        val signed = verifier.signedSessionInfo(challenge)
        return state.processHello(challenge, signed.encoded, signed.tag)
    }

    private fun command(): RoutableMessage =
        RoutableMessage(to_destination = Destination(domain = domain), protobuf_message_as_bytes = "hello".encodeToByteArray().toByteString())

    @Test
    fun firstHelloCreatesSignerAndSignalsReady() =
        // session.go processHello: s.ctx == nil → NewAuthenticatedSigner; ready = true; close(readySignal)
        runTest {
            val state = state()
            assertFalse(state.isReady)
            assertNull(state.export())
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier()))
            assertTrue(state.isReady)
            state.awaitReady()
            assertEquals(0u, state.timestamp())
            assertEquals(0u, state.counter())
        }

    @Test
    fun firstHelloWithBadTagLeavesSessionNotReady() =
        runTest {
            val state = state()
            val signed = verifier().signedSessionInfo(challenge)
            val badTag = signed.tag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val result = assertIs<SignerResult.Fault>(state.processHello(challenge, signed.encoded, badTag))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, result.fault)
            assertFalse(state.isReady)
            assertNull(state.export())
            assertNull(state.decrypt(RoutableMessage(), ByteArray(0)))
        }

    @Test
    fun laterHelloUpdatesExistingSigner() =
        // session.go processHello: s.ctx != nil → UpdateSignedSessionInfo
        runTest {
            val state = state()
            val verifier = verifier()
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier))
            advanceTimeBy(100_000)
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier, ByteArray(16) { 9 }))
            assertEquals(100u, state.timestamp())
            assertTrue(state.isReady)
        }

    @Test
    fun authorizeWaitsForReadyThenEncryptsWithNextCounter() =
        // session.go authorize: <-readySignal 뒤 락 안에서 Encrypt
        runTest {
            val state = state()
            val verifier = verifier()
            val authorized = async { state.authorize(command(), 5.seconds) }
            runCurrent()
            assertFalse(authorized.isCompleted)
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier))
            val encrypted = assertIs<SignerResult.Ok<RoutableMessage>>(authorized.await()).value
            assertEquals(1, encrypted.signature_data?.AES_GCM_Personalized_data?.counter)
            assertIs<TestVerifier.VerifyResult.Ok>(verifier.verify(encrypted))
            assertEquals(1u, state.counter())
        }

    @Test
    fun loadFromCacheMakesSessionReadyWithoutHello() =
        // dispatcher.go LoadCache: ImportSessionInfo(..., entry.CreatedAt) + ready = true
        runTest {
            val source = state()
            assertIs<SignerResult.Ok<Unit>>(hello(source, verifier()))
            advanceTimeBy(10_000)
            val exported = checkNotNull(source.export()) // clock_time = 10
            advanceTimeBy(30_000)
            val restored = state()
            assertIs<SignerResult.Ok<Unit>>(restored.loadFromCache(exported, age = 30.seconds))
            assertTrue(restored.isReady)
            assertEquals(40u, restored.timestamp())
        }

    @Test
    fun negativeCacheAgeImportsWithAgeZeroAndNeverThrows() =
        // 인계 항목 5: 벽시계가 뒤로 가 createdAt이 미래면 age < 0 → Signer.importSessionInfo가 0으로 본다
        runTest {
            val source = state()
            assertIs<SignerResult.Ok<Unit>>(hello(source, verifier()))
            advanceTimeBy(10_000)
            val exported = checkNotNull(source.export()) // clock_time = 10
            val restored = state()
            assertIs<SignerResult.Ok<Unit>>(restored.loadFromCache(exported, age = (-5).seconds))
            assertEquals(10u, restored.timestamp()) // age 0으로 취급: 시계는 저장 시점 그대로
        }

    @Test
    fun corruptCacheEntryIsAFaultNotAnException() =
        runTest {
            val state = state()
            val result = assertIs<SignerResult.Fault>(state.loadFromCache(byteArrayOf(0x12), age = 0.seconds))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_DECODING, result.fault)
            assertFalse(state.isReady)
        }

    @Test
    fun closeInvalidatesSignerButKeepsReadySignal() =
        runTest {
            val state = state()
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier()))
            state.close()
            assertNull(state.export())
            assertNull(state.decrypt(RoutableMessage(), ByteArray(0)))
            val result = assertIs<SignerResult.Fault>(state.authorize(command(), 5.seconds))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, result.fault)
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :application:jvmTest --console=plain`
Expected: 컴파일 실패 (`PendingKey`, `PendingRequest`, `SessionState` 없음). `:application`의 `commonTest`가 `:testing`을 보려면 `application/build.gradle.kts`에 `commonTest.dependencies { implementation(project(":testing")) }`를 추가해야 한다(`domain/build.gradle.kts`와 같은 형태) — Step 3에서 함께 한다.

- [ ] **Step 3: 구현**

`application/build.gradle.kts`:

```kotlin
plugins { id("teslable.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies { implementation(project(":domain")) }
        commonTest.dependencies { implementation(project(":testing")) }
    }
}
```

`Placeholder.kt`를 삭제한다.

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/PendingRequest.kt
// Ported from vehicle-command@a4b43c1 internal/dispatcher/receiver.go (Apache-2.0) — receiverKey, receiver, expired
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.protocol.SlidingWindow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectClause1
import okio.ByteString
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeMark

/** Go `receiverKey`: `(routing_address, request_uuid | VCSEC면 EMPTY, from domain)`. `toString`은 Go `String()`과 같은 꼴. */
internal data class PendingKey(
    val address: ByteString,
    val uuid: ByteString,
    val domain: Domain,
) {
    override fun toString(): String = "<${address.hex()}-${uuid.hex()}: $domain>"
}

/**
 * 보낸 요청 하나에 대한 응답 대기(Go `receiver`). 응답은 [receive]로 순서대로 받는다(버퍼 [BUFFER_SIZE], 가득 차면 디스패처가 드롭).
 * 다 쓰면 반드시 [close]한다(`use {}` 권장) — 닫지 않으면 디스패처가 뒤늦은 응답을 이 요청에 계속 배달한다.
 * [close]는 suspend하지 않는 플래그 + 채널 닫기라서 취소 중 `finally`에서도 안전하다; 맵 제거는 디스패처가 지연 수행한다.
 */
public class PendingRequest internal constructor(
    internal val key: PendingKey,
    requestHash: ByteArray?,
    private val sentAt: TimeMark,
) : AutoCloseable {
    private val channel = Channel<RoutableMessage>(BUFFER_SIZE)
    private val requestHashBytes: ByteArray? = requestHash?.copyOf()

    @Volatile
    private var closed = false

    /** Go `receiver.antireplay`: 이 요청의 응답 counter 슬라이딩 윈도우(32). 수신 코루틴만 갱신한다. */
    internal val antiReplay: SlidingWindow = SlidingWindow()

    /** 응답 AAD의 REQUEST_HASH(Go `receiver.requestID`, `RequestHash.of(요청)`). 인증하지 않은 요청이면 null. 사본. */
    public val requestHash: ByteArray? get() = requestHashBytes?.copyOf()

    /** 매칭 키의 uuid(Infotainment 16바이트, VCSEC는 빈 배열). 로그·테스트용. */
    public val uuid: ByteArray get() = key.uuid.toByteArray()

    /** [close] 뒤 true. 디스패처가 맵에서 지운다. */
    internal val isClosed: Boolean get() = closed

    /** Go `Recv()`의 `select` 용 절. */
    internal val onReceive: SelectClause1<RoutableMessage> get() = channel.onReceive

    /**
     * 다음 응답을 기다린다(Go `<-recv.Recv()`).
     * @throws kotlinx.coroutines.channels.ClosedReceiveChannelException [close] 뒤에 부르면 발생한다(프로그래밍 오류).
     */
    public suspend fun receive(): RoutableMessage = channel.receive()

    /** 기다리지 않고 다음 응답을 꺼낸다. 없거나 닫혔으면 null. */
    public fun tryReceive(): RoutableMessage? = channel.tryReceive().getOrNull()

    /** 디스패처가 부른다: 버퍼가 가득 찼거나 닫혔으면 false(드롭). Go `select { case handler.ch <- message: default: }`. */
    internal fun deliver(message: RoutableMessage): Boolean = channel.trySend(message).isSuccess

    /** Go `expired`: 요청을 보낸 지 [lifetime]이 지났으면 동봉 세션정보를 버려야 한다. */
    internal fun expired(lifetime: Duration): Boolean = sentAt.elapsedNow() > lifetime

    /** Go `Close()`: 더 이상 응답을 받지 않는다. 멱등. */
    override fun close() {
        closed = true
        channel.close()
    }

    /** 상수. */
    public companion object {
        /** Go `receiverBufferSize`: 요청당 응답 버퍼. */
        public const val BUFFER_SIZE: Int = 10
    }
}
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/SessionState.kt
// Ported from vehicle-command@a4b43c1 internal/dispatcher/session.go (Apache-2.0) — session, processHello, authorize, decrypt, export; LoadCache (dispatcher.go)
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.protocol.Signer
import io.github.smallmiro.teslable.protocol.SignerResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 도메인 하나의 세션 상태(Go `session`): [Signer]와 준비 신호. `Signer`는 스레드 안전하지 않으므로 모든 호출을 뮤텍스 안에서 한다.
 *
 * 락 규칙(SDD §5 구체화): 임계 구역은 `Signer` 호출뿐이고 다른 코루틴의 진행을 기다리는 suspend(채널 receive, Deferred await, delay)를
 * 포함하지 않는다. 그래서 수신 코루틴이 [processHello]·[decrypt]로 잠가도 대기는 µs 단위로 유계다(Go `session.lock`도 `sync.Mutex`).
 * 첫 [processHello]의 ECDH(`sharedX`)는 Go `NewAuthenticatedSigner`처럼 수신 코루틴 안에서 계산된다.
 *
 * `Signer` 멤버 분류 — 상태 변경: `encrypt`(counter++), `updateSessionInfo`, `updateSignedSessionInfo`, `close`.
 * 읽기 전용: `decrypt`, `exportSessionInfo`, `timestamp`, `counter`/`epoch`/공개키 getter. `decrypt`는 `close`와 경쟁하므로 Go처럼 락 안에서 부른다.
 */
public class SessionState internal constructor(
    private val vin: Vin,
    private val privateKey: EcdhPrivateKey,
    private val crypto: CryptoPrimitives,
    private val random: RandomSource,
    private val timeSource: TimeSource,
) {
    private val mutex = Mutex()
    private var signer: Signer? = null
    private val readySignal = CompletableDeferred<Unit>()

    /** Go `session.ready`: 인가할 수 있는 상태인지. */
    public val isReady: Boolean get() = readySignal.isCompleted

    /** Go `readySignal`: 준비되면 완료되는 신호(`select`용). */
    internal val ready: Deferred<Unit> get() = readySignal

    /** 준비될 때까지 기다린다(Go `<-s.readySignal`). */
    public suspend fun awaitReady(): Unit = readySignal.await()

    /**
     * Go `processHello`: 세션정보를 검증해 반영한다. 첫 호출은 [Signer.createAuthenticated](태그 검증 포함), 이후는
     * [Signer.updateSignedSessionInfo]. 성공하면 준비 신호를 완료한다. challenge가 최근 요청의 uuid인지는 호출자(디스패처)가 보장한다.
     * 기존 세션이 다른 차량 공개키의 세션정보를 받으면 `UNKNOWN_KEY_ID`로 거부하고 세션은 그대로다(Go와 동일, 설계 구체화 8).
     */
    public suspend fun processHello(
        challenge: ByteArray,
        encodedInfo: ByteArray,
        tag: ByteArray,
    ): SignerResult<Unit> =
        mutex.withLock {
            val result: SignerResult<Unit> =
                when (val current = signer) {
                    null ->
                        when (val created = Signer.createAuthenticated(privateKey, vin, challenge, encodedInfo, tag, crypto, random, timeSource)) {
                            is SignerResult.Ok -> {
                                signer = created.value
                                SignerResult.Ok(Unit)
                            }
                            is SignerResult.Fault -> created
                        }
                    else -> current.updateSignedSessionInfo(challenge, encodedInfo, tag)
                }
            if (result is SignerResult.Ok) readySignal.complete(Unit)
            result
        }

    /**
     * Go `authorize`: 준비 신호는 락 밖에서 기다리고 [Signer.encrypt]만 락 안에서 부른다. Go는 `Encrypt` 오류를 지우고 즉시 재시도하지만
     * 여기서는 값으로 돌려준다(설계 구체화 5; `SendWithRetry`가 `retryInterval` 뒤 재시도).
     */
    public suspend fun authorize(
        message: RoutableMessage,
        lifetime: Duration,
    ): SignerResult<RoutableMessage> {
        readySignal.await()
        return mutex.withLock {
            signer?.encrypt(message, lifetime) ?: SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, "session closed")
        }
    }

    /** Go `session.decrypt`의 Signer 부분. 세션이 없으면 null(Go `ErrNoDecryptionContext`). 윈도우 검사는 디스패처가 한다. */
    public suspend fun decrypt(
        message: RoutableMessage,
        requestHash: ByteArray,
    ): SignerResult<Signer.DecryptedResponse>? = mutex.withLock { signer?.decrypt(message, requestHash) }

    /** Go `export`: 캐시용 `SessionInfo` 바이트. 세션이 없으면 null. */
    public suspend fun export(): ByteArray? = mutex.withLock { signer?.exportSessionInfo() }

    /**
     * Go `LoadCache`: 캐시된 세션정보로 [Signer]를 만들고 즉시 준비 상태로 둔다. [age]는 캐시 저장 후 경과(음수면 0으로 본다).
     * 기존 Signer가 있으면 닫고 교체한다. 디코딩 실패는 `DECODING` fault(예외 없음).
     */
    public suspend fun loadFromCache(
        encodedInfo: ByteArray,
        age: Duration,
    ): SignerResult<Unit> =
        mutex.withLock {
            when (val imported = Signer.importSessionInfo(privateKey, vin, encodedInfo, age, crypto, random, timeSource)) {
                is SignerResult.Ok -> {
                    signer?.close()
                    signer = imported.value
                    readySignal.complete(Unit)
                    SignerResult.Ok(Unit)
                }
                is SignerResult.Fault -> imported
            }
        }

    /** 차량 시계 추정(초). 세션이 없으면 null. 테스트·로그용. */
    public suspend fun timestamp(): UInt? = mutex.withLock { signer?.timestamp() }

    /** 마지막으로 쓴 counter. 세션이 없으면 null. */
    public suspend fun counter(): UInt? = mutex.withLock { signer?.counter }

    /** 세션 키를 0으로 덮는다. 이후 [authorize]는 `INTERNAL` fault, [decrypt]·[export]는 null. */
    public suspend fun close(): Unit =
        mutex.withLock {
            signer?.close()
            signer = null
        }
}
```

`tools/ci/ported-files.txt`에 두 파일을 추가한다.

- [ ] **Step 4: 통과 확인, API 덤프, 커밋**

Run: `./gradlew :application:apiDump --console=plain`
Run: `./gradlew :application:jvmTest :application:check :application:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS). `application/api/*`에 `PendingRequest`, `SessionState` 반영.

```bash
git add application/build.gradle.kts application/src application/api tools/ci/ported-files.txt
git commit -m "feat(application): PendingRequest (receiver.go) and SessionState (session.go) with Signer confined behind a Mutex

Refs: FR-011, FR-014, FR-016, FR-018, NFR-012
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `Dispatcher` — 조립·전송 재시도·수신 루프·드롭 규칙·세션정보 갱신·복호화 (+ `TeslaLogger` 포트)

참고 매뉴얼: `01-architecture.md` §3.2, §4(전체 경로), §4.1(receiverKey), §5(goroutine 1개); `03-protocol.md` §9.2, §9.4; `08-errors.md` §5(폐기 조건과 로그 문구), §6(리플레이·드롭); `10-porting-guide.md` §4, §7(a)(b). Go 원본: `internal/dispatcher/dispatcher.go`(`New` 43~57행, `SetMaxLatency` 59~65행, `createHandler`/`closeHandler` 158~180행, `checkForSessionUpdate` 182~224행, `decrypt` 226~243행, `process` 245~315행, `Start`/`listen`/`Stop` 319~376행, `Send` 379~461행, `SessionInfoRequest` 464~478행, `RequestSessionInfo` 482~488행), `dispatcher_test.go`(`TestSendWithoutSession` 276행, `TestInvalidMessages` 382행, `TestVehicleUnreachable` 607행, `TestRetrySend` 698행, `TestSendTimeout` 722행, `TestStopDispatcher` 754행, `TestDoNotBlockOnResponder` 792행, `TestRequestSessionWithoutKey` 841행, `TestUnsolicitedSessionInfo` 472행, `TestCorruptedSessionInfo` 518행, `TestDiscardUnauthenticatedSessionInfo` 565행). 설계: `{{SDD_FILE}}` §2.2, §3.3 수신 측, §5, §10(로그 문구), FR-010, FR-011, FR-014, FR-016, 설계 구체화 4·9·10, Review Focus 1·2·5.

**Files:**
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/TeslaLogger.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/Dispatcher.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/Results.kt`
- Create: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/RecordingLogger.kt`
- Create: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/DispatcherFixtures.kt`
- Modify: `tools/ci/ported-files.txt`, `domain/api/*`, `application/api/*`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/DispatcherTest.kt`

**Interfaces:**
- Consumes: Task 2 `Transport`/`AuthMethod`/`FakeTransport`, Task 4 `FakeVehicle`, Task 5 `PendingKey`/`PendingRequest`/`SessionState`, M1 `RequestHash.of`, `SignerResult`, `VehicleError`, `VehicleResult`, `shouldRetry()`, `toResult()`.
- Produces:
  - `public enum class LogLevel { DEBUG, INFO, WARN, ERROR }`, `public fun interface TeslaLogger { fun log(level, tag: String, message: () -> String); companion NoOp }` (`:domain`).
  - `public class Dispatcher(transport, privateKey: EcdhPrivateKey?, crypto, random, scope: CoroutineScope, timeSource = TimeSource.Monotonic, logger = TeslaLogger.NoOp) { val vin; val retryInterval; val maxLatency; val isListening; fun setMaxLatency(d); fun session(domain): SessionState?; fun start(); suspend fun stop(); suspend fun close(); suspend fun send(message, auth, lifetime = 5.seconds): VehicleResult<PendingRequest>; suspend fun requestSessionInfo(domain): VehicleResult<PendingRequest>; internal suspend fun pendingCount(): Int; companion { ALL_DOMAINS; DEFAULT_LIFETIME; fun sessionInfoRequest(domain, publicKey): RoutableMessage } }`.
  - `internal fun SignerResult.Fault.toVehicleError(): VehicleError`, `internal fun <T> VehicleResult<T>.errorOrNull(): VehicleError?`, `internal inline fun <T> VehicleResult<T>.valueOr(onError: (VehicleError) -> Nothing): T` (Results.kt).
  - `public class RecordingLogger : TeslaLogger { val entries; fun contains(fragment): Boolean; fun count(fragment): Int }` (`:testing`).
  - 테스트 하네스(`DispatcherFixtures.kt`, `internal`): `TestScope.dispatcherHarness(privateKey = TestCrypto.clientKey(), fake = FakeVehicle(timeSource = testTimeSource), start = true, retryInterval = 1.milliseconds): DispatcherHarness(fake, transport, dispatcher, logger)`, `testCommand(domain = INFOTAINMENT, payload = "hello")`, `replyTo(request, payload): RoutableMessage`, `encode(message)`, `DispatcherHarness.manualHandshake(domain)`. Task 7~11 테스트가 재사용한다.
  - Go 로그 문구를 그대로 쓴다(SDD §10): `Dropping unparseable message`, `Dropping message with missing source`, `Dropping message with invalid request UUID length`, `Dropping message with missing destination`, `Dropping message to <domain>`, `Dropping message with invalid address length`, `Dropping message without registered handler`, `Discarding session info because client does not have a private key`, `Discarding session info because it was received more than <d> after request`, `Discarding unauthenticated session info`, `Dropping session from unregistered domain`, `Session info error: <fault>: <detail>`, `Updated session info for <domain>`, `Dropping duplicate vehicle response`, `Error decrypting vehicle response: <detail>`, `Dropping response to command because response handler queue is full`, `No session available for <domain>`, `Terminal transmission error: <msg>`, `Retrying transmission after error: <msg>`.

- [ ] **Step 1: 하네스와 실패 테스트 작성**

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/DispatcherFixtures.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.testing.FakeTransport
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Go `getTestSetup`의 결과물. `dispatcher`는 `backgroundScope`에서 수신한다(테스트가 끝나면 자동 취소). */
internal class DispatcherHarness(
    val fake: FakeVehicle,
    val transport: FakeTransport,
    val dispatcher: Dispatcher,
    val logger: RecordingLogger,
)

/** Go `getTestSetup` 앞부분(생성 + Start). 핸드셰이크는 `manualHandshake` 또는 Task 7 `HandshakeFlow`로. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.dispatcherHarness(
    privateKey: EcdhPrivateKey? = TestCrypto.clientKey(),
    fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource),
    start: Boolean = true,
    retryInterval: Duration = 1.milliseconds,
): DispatcherHarness {
    val transport = fake.transport(retryInterval)
    val logger = RecordingLogger()
    val dispatcher = Dispatcher(transport, privateKey, TestCrypto.primitives, TestCrypto.random, backgroundScope, testTimeSource, logger)
    if (start) dispatcher.start()
    return DispatcherHarness(fake, transport, dispatcher, logger)
}

/** dispatcher_test.go `testCommand()`: INFOTAINMENT, payload "hello". */
internal fun testCommand(
    domain: Domain = Domain.DOMAIN_INFOTAINMENT,
    payload: String = "hello",
    flags: Int = 0,
): RoutableMessage =
    RoutableMessage(to_destination = Destination(domain = domain), protobuf_message_as_bytes = payload.encodeToByteArray().toByteString(), flags = flags)

/** dispatcher_test.go `replyWithPayload` + `populateReplyMetadata`: 요청(디스패처가 조립한 뒤의 것)에 대한 평문 응답. */
internal fun replyTo(
    request: RoutableMessage,
    payload: ByteArray,
): RoutableMessage =
    RoutableMessage(
        to_destination = request.from_destination,
        from_destination = Destination(domain = request.to_destination?.domain),
        request_uuid = request.uuid,
        uuid = FakeVehicle.TEST_UUID.toByteString(),
        protobuf_message_as_bytes = payload.toByteString(),
    )

internal fun encode(message: RoutableMessage): ByteArray = RoutableMessage.ADAPTER.encode(message)

/** Task 7 이전의 수동 핸드셰이크: 세션정보 요청 → FakeVehicle 응답이 수신 루프에서 processHello된 뒤 채널로 온다. */
internal suspend fun DispatcherHarness.manualHandshake(domain: Domain) {
    val pending = assertIs<VehicleResult.Success<PendingRequest>>(dispatcher.requestSessionInfo(domain)).value
    pending.use { assertNotNull(it.receive().session_info) }
    assertTrue(assertNotNull(dispatcher.session(domain)).isReady, "session for $domain must be ready")
}
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/DispatcherTest.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import io.github.smallmiro.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class DispatcherTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    private fun DispatcherHarness.lastRequest(): RoutableMessage = fake.received.last()

    @Test
    fun sendWithoutSessionReturnsNoSessionButUnauthenticatedSendWorks() =
        // dispatcher_test.go TestSendWithoutSession
        runTest {
            val h = dispatcherHarness()
            val refused = assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.GCM))
            assertEquals(VehicleError.NoSession, refused.error)
            assertTrue(h.logger.contains("No session available for DOMAIN_INFOTAINMENT"))
            val sent = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE))
            sent.value.use { pending ->
                val request = h.lastRequest()
                assertEquals(16, request.uuid.size) // FR-010: 16B 랜덤 uuid
                assertContentEquals(pending.uuid, request.uuid.toByteArray()) // Infotainment 키 = uuid
                assertEquals(16, assertNotNull(request.from_destination?.routing_address).size)
                assertNull(pending.requestHash) // 인증 없음 → request hash 없음
            }
        }

    @Test
    fun vcsecRequestsUseFreshRoutingAddressAndInfotainmentUsesTheFixedOne() =
        // FR-010: VCSEC는 요청마다 새 routing_address, Infotainment는 디스패처의 고정 주소 (dispatcher.go Send 400~407행)
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList(), emptyList())
            h.fake.script(infotainment, emptyList(), emptyList())
            val none = io.github.smallmiro.teslable.port.AuthMethod.NONE
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), none)).value.use {
                assertEquals(0, it.uuid.size) // VCSEC 키는 uuid를 쓰지 않는다
            }
            val v1 = h.lastRequest().from_destination?.routing_address
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), none)).value.close()
            val v2 = h.lastRequest().from_destination?.routing_address
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(infotainment), none)).value.close()
            val i1 = h.lastRequest().from_destination?.routing_address
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(infotainment), none)).value.close()
            val i2 = h.lastRequest().from_destination?.routing_address
            assertFalse(v1 == v2)
            assertEquals(i1, i2)
            assertFalse(v1 == i1)
        }

    @Test
    fun sendBeforeStartOrAfterStopReturnsNotConnected() =
        // dispatcher_test.go TestStopDispatcher
        runTest {
            val h = dispatcherHarness(start = false)
            val none = io.github.smallmiro.teslable.port.AuthMethod.NONE
            assertEquals(VehicleError.NotConnected, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), none)).error)
            h.dispatcher.start()
            assertTrue(h.dispatcher.isListening)
            h.dispatcher.start() // 멱등
            h.dispatcher.stop()
            assertFalse(h.dispatcher.isListening)
            assertEquals(VehicleError.NotConnected, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), none)).error)
        }

    @Test
    fun rejectsMessageWithoutDestinationDomain() =
        // dispatcher.go Send: "cannot send message without a destination domain"
        runTest {
            val h = dispatcherHarness()
            val none = io.github.smallmiro.teslable.port.AuthMethod.NONE
            val noDomain = RoutableMessage(protobuf_message_as_bytes = "x".encodeToByteArray().toByteString())
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(h.dispatcher.send(noDomain, none)).error)
            val broadcast = testCommand(Domain.DOMAIN_BROADCAST)
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(h.dispatcher.send(broadcast, none)).error)
        }

    @Test
    fun unreachableVehicleFailsSendWithoutRetry() =
        // dispatcher_test.go TestVehicleUnreachable (errTimeout은 재시도 불가)
        runTest {
            val h = dispatcherHarness()
            h.transport.ackRequests = false
            val failed = assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE))
            assertEquals(VehicleError.TransportError.Disconnected, failed.error)
            assertEquals(1, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount()) // 실패한 요청의 등록은 풀린다
            assertTrue(h.logger.contains("Terminal transmission error"))
        }

    @Test
    fun retriesTemporarySendErrorsAndStopsOnMayHaveSucceeded() =
        // dispatcher_test.go TestRetrySend: 일시 오류 3번은 1ms 간격 재시도, PossibleSuccess 오류는 그대로 반환
        runTest {
            val h = dispatcherHarness()
            repeat(3) { h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("busy")) }
            h.transport.enqueueSendError(VehicleError.Timeout(afterSend = true))
            val result = assertIs<VehicleResult.Uncertain>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE))
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(4, h.transport.sent.size)
            assertEquals(3, h.logger.count("Retrying transmission after error"))
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun sendGivesUpWhenCallerTimesOutDuringRetries() =
        // dispatcher_test.go TestSendTimeout: RetryInterval/2 안에 끝나지 않으면 취소되고 등록이 풀린다 (Timeout 매핑은 Task 9)
        runTest {
            val h = dispatcherHarness()
            repeat(50) { h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("busy")) }
            val result = withTimeoutOrNull(h.transport.retryInterval / 2) { h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE) }
            assertNull(result)
            assertEquals(1, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun deliversMatchedResponseToItsPendingRequest() =
        // dispatcher_test.go TestStartSession의 매칭 부분(인증 없이): FakeVehicle 기본 응답이 (address, uuid, domain)으로 매칭된다
        runTest {
            val h = dispatcherHarness()
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE)).value
            pending.use {
                val reply = it.receive()
                assertNull(ResponseClassifier.protocolError(reply))
                assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_NONE, reply.signedMessageStatus?.signed_message_fault ?: MessageFault_E.MESSAGEFAULT_ERROR_NONE)
            }
        }

    @Test
    fun dropsInvalidMessagesAndDeliversTheValidOne() =
        // dispatcher_test.go TestInvalidMessages
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE)).value
            val request = h.lastRequest()
            fun reply(payload: String): RoutableMessage = replyTo(request, payload.encodeToByteArray())
            h.transport.deliver("I'm not a valid protobuf".encodeToByteArray())
            h.transport.deliver(encode(reply("missing uuid").copy(request_uuid = okio.ByteString.EMPTY)))
            val badAddress = assertNotNull(request.from_destination?.routing_address).toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            h.transport.deliver(encode(reply("bad destination address").copy(to_destination = Destination(routing_address = badAddress.toByteString()))))
            h.transport.deliver(encode(reply("invalid domain").copy(from_destination = Destination(domain = vcsec))))
            h.transport.deliver(encode(reply("missing domain").copy(from_destination = null)))
            h.transport.deliver(encode(reply("missing destination address").copy(to_destination = null)))
            h.transport.deliver(encode(reply("to a domain").copy(to_destination = Destination(domain = infotainment))))
            h.transport.deliver(encode(reply("short uuid").copy(request_uuid = ByteArray(5).toByteString())))
            val unknownUuid = request.uuid.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            h.transport.deliver(encode(reply("unknown uuid").copy(request_uuid = unknownUuid.toByteString())))
            h.transport.deliver(encode(reply("ack")))
            pending.use {
                assertEquals("ack", assertNotNull(it.receive().protobuf_message_as_bytes).utf8())
                assertNull(it.tryReceive()) // 나머지는 전부 드롭
            }
            assertTrue(h.logger.contains("Dropping unparseable message"))
            assertTrue(h.logger.contains("Dropping message with missing source"))
            assertTrue(h.logger.contains("Dropping message with missing destination"))
            assertTrue(h.logger.contains("Dropping message to DOMAIN_INFOTAINMENT"))
            assertTrue(h.logger.contains("Dropping message with invalid request UUID length"))
            assertEquals(4, h.logger.count("Dropping message without registered handler")) // missing uuid, bad address, wrong domain, unknown uuid
        }

    @Test
    fun dropsResponseFromDomainUnknownToWire() =
        // 설계 구체화 10: 모르는 from 도메인(raw varint 4)은 Wire에서 domain == null → 소스 없음으로 드롭(Go는 핸들러 없음으로 드롭)
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE)).value
            val unknownDomain = Destination.ADAPTER.decode(byteArrayOf(0x08, 0x04)) // 태그 1(domain) varint 4
            assertNull(unknownDomain.domain)
            h.transport.deliver(encode(replyTo(h.lastRequest(), "x".encodeToByteArray()).copy(from_destination = unknownDomain)))
            runCurrent()
            pending.use { assertNull(it.tryReceive()) }
            assertTrue(h.logger.contains("Dropping message with missing source"))
        }

    @Test
    fun doesNotBlockOtherHandlersWhenOneQueueIsFull() =
        // dispatcher_test.go TestDoNotBlockOnResponder
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList(), emptyList())
            val none = io.github.smallmiro.teslable.port.AuthMethod.NONE
            val first = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), none)).value
            val firstRequest = h.lastRequest()
            val second = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), none)).value
            val secondRequest = h.lastRequest()
            repeat(2 * PendingRequest.BUFFER_SIZE) { h.transport.deliver(encode(replyTo(firstRequest, "mailbox stuffer".encodeToByteArray()))) }
            h.transport.deliver(encode(replyTo(secondRequest, "I shouldn't be blocked".encodeToByteArray())))
            second.use { assertEquals("I shouldn't be blocked", assertNotNull(it.receive().protobuf_message_as_bytes).utf8()) }
            first.use {
                repeat(PendingRequest.BUFFER_SIZE) { _ -> assertNotNull(it.tryReceive()) }
                assertNull(it.tryReceive())
            }
            assertEquals(PendingRequest.BUFFER_SIZE, h.logger.count("response handler queue is full"))
        }

    @Test
    fun requestSessionInfoWithoutKeyReturnsRequiresKey() =
        // dispatcher_test.go TestRequestSessionWithoutKey
        runTest {
            val h = dispatcherHarness(privateKey = null)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(h.dispatcher.requestSessionInfo(infotainment)).error)
            assertNull(h.dispatcher.session(infotainment))
        }

    @Test
    fun sessionInfoReplyMakesSessionReadyAndIsStillDeliveredToHandler() =
        // dispatcher.go process 주석: 세션정보를 반영한 뒤에도 응답은 핸들러로 전달된다
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(infotainment)
            assertTrue(h.logger.contains("Updated session info for DOMAIN_INFOTAINMENT"))
            val request = h.lastRequest()
            assertEquals(TestCryptoPublicKeyHex, assertNotNull(request.session_info_request?.public_key).hex()) // SessionInfoRequest{public_key}
        }

    @Test
    fun discardsSessionInfoWithBadTag() =
        // dispatcher_test.go TestUnsolicitedSessionInfo / TestCorruptedSessionInfo
        runTest {
            val h = dispatcherHarness()
            h.fake.corruptNextSessionInfoTag(vcsec)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            pending.use { assertNotNull(it.receive().session_info, "sanity: reply carries session info") }
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(h.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
            val refused = assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(vcsec), io.github.smallmiro.teslable.port.AuthMethod.GCM))
            assertEquals(VehicleError.NoSession, refused.error)
        }

    @Test
    fun discardsUnauthenticatedSessionInfo() =
        // dispatcher_test.go TestDiscardUnauthenticatedSessionInfo: session_info는 있으나 session_info_tag가 없다
        runTest {
            val h = dispatcherHarness()
            h.fake.dropNextReplies(1)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val request = h.lastRequest()
            val info = h.fake.verifier(vcsec).signedSessionInfo(request.uuid.toByteArray())
            val untagged = replyTo(request, ByteArray(0)).copy(protobuf_message_as_bytes = null, session_info = info.encoded.toByteString())
            h.transport.deliver(encode(untagged))
            pending.use { assertNotNull(it.receive().session_info) }
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(h.logger.contains("Discarding unauthenticated session info"))
        }

    @Test
    fun discardsSessionInfoReceivedMoreThanMaxLatencyAfterRequest() =
        // FR-014, dispatcher.go checkForSessionUpdate: handler.expired(maxLatency) → 폐기 (BLE 4초)
        runTest {
            val h = dispatcherHarness()
            h.dispatcher.setMaxLatency(4.seconds)
            h.dispatcher.setMaxLatency((-1).seconds) // Go SetMaxLatency: 0 이하는 무시
            assertEquals(4.seconds, h.dispatcher.maxLatency)
            h.fake.dropNextReplies(1)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val request = h.lastRequest()
            val late = h.fake.verifier(vcsec).setSessionInfo(request.uuid.toByteArray(), replyTo(request, ByteArray(0)))
            advanceTimeBy(4_001)
            h.transport.deliver(encode(late))
            pending.use { assertNotNull(it.receive().session_info) } // 메시지 자체는 전달된다
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(h.logger.contains("Discarding session info because it was received more than 4s after request"))
            // 대조군: 4초 안이면 받아들인다
            h.fake.dropNextReplies(1)
            val second = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val secondRequest = h.lastRequest()
            advanceTimeBy(3_000)
            h.transport.deliver(encode(h.fake.verifier(vcsec).setSessionInfo(secondRequest.uuid.toByteArray(), replyTo(secondRequest, ByteArray(0)))))
            second.use { it.receive() }
            assertTrue(assertNotNull(h.dispatcher.session(vcsec)).isReady)
        }

    @Test
    fun discardsSessionInfoWhoseChallengeMatchesNoOutstandingRequest() =
        // FR-014 "오래된 uuid"(Go: challenge는 핸들러 키로 묶인다). (a) Infotainment: request_uuid가 미해결 요청과 다르면 핸들러가 없어 드롭.
        // (b) VCSEC: uuid는 키에 없지만 닫힌(오래된) 요청의 주소로 온 세션정보는 핸들러가 없어 드롭. (c) VCSEC: 주소는 살아 있는 요청인데
        // 태그가 다른 challenge로 계산된 세션정보는 HMAC 불일치로 폐기.
        runTest {
            val h = dispatcherHarness()
            h.fake.dropNextReplies(3)
            val infoPending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(infotainment)).value
            val infoRequest = h.lastRequest()
            val oldVcsecPending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val oldVcsecRequest = h.lastRequest()
            oldVcsecPending.close() // 옛 요청은 끝났다(Go: recv.Close → closeHandler)
            val vcsecPending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val vcsecRequest = h.lastRequest()
            val staleUuid = ByteArray(16) { 0x42 }
            val staleInfo = h.fake.verifier(infotainment).setSessionInfo(staleUuid, replyTo(infoRequest, ByteArray(0))).copy(request_uuid = staleUuid.toByteString())
            val lateVcsec = h.fake.verifier(vcsec).setSessionInfo(oldVcsecRequest.uuid.toByteArray(), replyTo(oldVcsecRequest, ByteArray(0)))
            val forgedChallenge = h.fake.verifier(vcsec).setSessionInfo(staleUuid, replyTo(vcsecRequest, ByteArray(0)))
            h.transport.deliver(encode(staleInfo))
            h.transport.deliver(encode(lateVcsec))
            h.transport.deliver(encode(forgedChallenge))
            runCurrent()
            infoPending.use { assertNull(it.tryReceive()) }
            vcsecPending.use { assertNotNull(it.tryReceive()) } // (c)는 메시지 자체는 전달된다(Go와 동일)
            assertFalse(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertEquals(2, h.logger.count("Dropping message without registered handler"))
            assertTrue(h.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
        }

    @Test
    fun appliesReplayedSessionInfoWithSameClockTimeLikeGo() =
        // FR-014 세 번째 규칙의 경계: signer.go "s.setTime <= info.ClockTime" — 같은 clock_time은 다시 반영된다(Go 동작). 무해하다:
        // challenge(uuid) 검사와 4초 규칙이 범위를 묶고 counter는 내려가지 않는다.
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            val before = assertNotNull(h.dispatcher.session(vcsec)).timestamp()
            h.manualHandshake(vcsec) // 시계가 흐르지 않았으므로 clock_time이 같은 세션정보
            assertEquals(2, h.logger.count("Updated session info for DOMAIN_VEHICLE_SECURITY"))
            assertEquals(before, assertNotNull(h.dispatcher.session(vcsec)).timestamp())
        }

    @Test
    fun decryptsResponseWhoseFlagsDifferFromRequest() =
        // Review Focus 5: 요청 flags = 2, FakeVehicle 응답 flags = 0 → 응답 AAD는 응답의 flags(0)로 계산돼야 복호화된다
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            val pending =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(vcsec, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), io.github.smallmiro.teslable.port.AuthMethod.GCM),
                ).value
            pending.use {
                assertContentEquals(RequestHash.of(h.lastRequest()), it.requestHash)
                val reply = it.receive()
                assertEquals(0, reply.flags)
                assertNull(reply.signature_data) // 복호화 뒤 signature_data 제거(Go Decrypt: SubSigData = nil)
                assertEquals(0, assertNotNull(reply.protobuf_message_as_bytes).size) // vcsecEmpty의 평문
            }
        }

    @Test
    fun dropsReplayedEncryptedResponse() =
        // 08-errors.md §6, dispatcher.go process: ErrReplayedResponse → "Dropping duplicate vehicle response"
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            val pending =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(vcsec, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), io.github.smallmiro.teslable.port.AuthMethod.GCM),
                ).value
            pending.use {
                assertNotNull(it.receive())
                h.fake.replayLastResponse(vcsec)
                runCurrent()
                assertNull(it.tryReceive())
            }
            assertTrue(h.logger.contains("Dropping duplicate vehicle response"))
        }

    @Test
    fun dropsResponseThatFailsDecryption() =
        // dispatcher.go process: decrypt 오류 → "Error decrypting vehicle response"
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            h.fake.script(vcsec, emptyList())
            val pending =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(vcsec, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), io.github.smallmiro.teslable.port.AuthMethod.GCM),
                ).value
            val request = h.lastRequest()
            val wrongHash = assertNotNull(RequestHash.of(request)).also { it[3] = (it[3].toInt() xor 1).toByte() }
            val forged = h.fake.verifier(vcsec).encryptResponse(replyTo(request, "x".encodeToByteArray()), wrongHash, counter = 1u)
            h.transport.deliver(encode(forged))
            runCurrent()
            pending.use { assertNull(it.tryReceive()) }
            assertTrue(h.logger.contains("Error decrypting vehicle response"))
        }

    @Test
    fun matchesVcsecResponseByAddressRegardlessOfRequestUuid() =
        // Review Focus 1: VCSEC 키는 uuid를 쓰지 않으므로 request_uuid가 있든(가짜처럼 회신) 0이든 주소가 맞으면 전달된다
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), io.github.smallmiro.teslable.port.AuthMethod.NONE)).value
            val request = h.lastRequest()
            h.transport.deliver(encode(replyTo(request, "echoed".encodeToByteArray())))
            h.transport.deliver(encode(replyTo(request, "zeroed".encodeToByteArray()).copy(request_uuid = ByteArray(16).toByteString())))
            h.transport.deliver(encode(replyTo(request, "absent".encodeToByteArray()).copy(request_uuid = okio.ByteString.EMPTY)))
            h.transport.deliver(encode(replyTo(request, "wrong address".encodeToByteArray()).copy(to_destination = Destination(routing_address = ByteArray(16) { 3 }.toByteString()))))
            pending.use {
                assertEquals(listOf("echoed", "zeroed", "absent"), List(3) { _ -> assertNotNull(it.receive().protobuf_message_as_bytes).utf8() })
                assertNull(it.tryReceive())
            }
        }

    @Test
    fun sendReturnsNotConnectedAfterIncomingCompletes() =
        // Review Focus 2: 전송이 끝나면(BLE 끊김) 수신 루프가 조용히 종료되고 이후 send는 NotConnected
        runTest {
            val h = dispatcherHarness()
            h.transport.close()
            runCurrent()
            assertFalse(h.dispatcher.isListening)
            assertEquals(VehicleError.NotConnected, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), io.github.smallmiro.teslable.port.AuthMethod.NONE)).error)
        }

    @Test
    fun discardsSessionInfoWhenClientHasNoPrivateKey() =
        // dispatcher.go checkForSessionUpdate: d.privateKey == nil → "Discarding session info because client does not have a private key"
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(vcsec, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), io.github.smallmiro.teslable.port.AuthMethod.NONE)).value
            val reply =
                replyTo(h.lastRequest(), ByteArray(0)).copy(
                    protobuf_message_as_bytes = null,
                    session_info = SessionInfo(counter = 1).encode().toByteString(),
                    signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = ByteArray(32).toByteString())),
                )
            h.transport.deliver(encode(reply))
            pending.use { assertNotNull(it.receive().session_info) }
            assertTrue(h.logger.contains("Discarding session info because client does not have a private key"))
        }

    private companion object {
        /** protocol.md client 공개키 hex (SessionInfoRequest.public_key 확인용). */
        val TestCryptoPublicKeyHex: String = io.github.smallmiro.teslable.testing.TestCrypto.clientPublicKey.toByteArray().toByteString().hex()
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :application:jvmTest --tests '*DispatcherTest*' --console=plain`
Expected: 컴파일 실패 (`Dispatcher`, `RecordingLogger`, `TeslaLogger` 없음)

- [ ] **Step 3: 구현**

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/TeslaLogger.kt
package io.github.smallmiro.teslable.port

/** 로그 레벨. Go `internal/log`의 Error/Warning/Info/Debug. */
public enum class LogLevel {
    /** TX/RX hex 등 상세. */
    DEBUG,

    /** 세션·연결 이벤트. */
    INFO,

    /** 드롭·폐기 사유. */
    WARN,

    /** 복구 불가 상황. */
    ERROR,
}

/**
 * 로거 포트(SDD §10). 기본은 [NoOp]. [message]는 지연 평가되며 VIN은 마스킹된 채로, 키·nonce·평문은 어떤 레벨에서도 넣지 않는다.
 */
public fun interface TeslaLogger {
    /** 한 줄을 기록한다. */
    public fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    )

    /** 기본 구현. */
    public companion object {
        /** 아무것도 하지 않는 로거. */
        public val NoOp: TeslaLogger = TeslaLogger { _, _, _ -> }
    }
}
```

```kotlin
// testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/RecordingLogger.kt
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger

/** 로그를 모아 두는 테스트용 로거. 드롭·폐기 사유를 단언하는 데 쓴다. */
public class RecordingLogger : TeslaLogger {
    /** 기록 한 줄. */
    public class Entry(
        /** 레벨. */
        public val level: LogLevel,
        /** 태그. */
        public val tag: String,
        /** 평가된 메시지. */
        public val message: String,
    )

    private val entryList = mutableListOf<Entry>()

    /** 지금까지의 기록(사본). */
    public val entries: List<Entry> get() = entryList.toList()

    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        entryList += Entry(level, tag, message())
    }

    /** [fragment]를 포함하는 메시지가 하나라도 있으면 true. */
    public fun contains(fragment: String): Boolean = entryList.any { it.message.contains(fragment) }

    /** [fragment]를 포함하는 메시지 수. */
    public fun count(fragment: String): Int = entryList.count { it.message.contains(fragment) }
}
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/Results.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.protocol.SignerResult

/** `Signer`의 fault를 공개 오류로. `UNKNOWN_KEY_ID`는 Go `GetError`처럼 [VehicleError.KeyNotPaired]. */
internal fun SignerResult.Fault.toVehicleError(): VehicleError =
    if (fault == MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) VehicleError.KeyNotPaired else VehicleError.ProtocolFault(fault)

/** 실패·불확실이면 그 오류, 성공이면 null. */
internal fun <T> VehicleResult<T>.errorOrNull(): VehicleError? =
    when (this) {
        is VehicleResult.Success -> null
        is VehicleResult.Failure -> error
        is VehicleResult.Uncertain -> error
    }

/** 성공이면 값, 아니면 [onError]로 빠져나간다(`return`용). */
internal inline fun <T> VehicleResult<T>.valueOr(onError: (VehicleError) -> Nothing): T =
    when (this) {
        is VehicleResult.Success -> value
        is VehicleResult.Failure -> onError(error)
        is VehicleResult.Uncertain -> onError(error)
    }
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/Dispatcher.kt
// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher.go (Apache-2.0) — New, SetMaxLatency, Send, RequestSessionInfo, SessionInfoRequest, listen, process, checkForSessionUpdate, decrypt, Stop
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.protocol.SignerResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val ADDRESS_LENGTH = 16
private const val UUID_LENGTH = 16
private const val TAG = "Dispatcher"

/**
 * Go `Dispatcher`: 요청 조립(`uuid`, `routing_address`)·인가·전송 재시도, 그리고 수신 루프(`RoutableMessage` 파싱 → [PendingRequest] 매칭 →
 * 세션정보 갱신 → 복호화 → 채널 전달). 수신 코루틴은 [start]가 [scope]에 하나 띄운다. 그 코루틴은 다른 코루틴의 진행을 기다리지 않는다
 * (채널은 `trySend`, 락은 `Signer` 호출·맵 조작만 — SDD §5 구체화). [privateKey]가 null이면 세션이 없다(Go `privateKey == nil`):
 * 인증 전송은 [VehicleError.NoSession], 핸드셰이크는 [VehicleError.RequiresKey].
 */
@Suppress("TooManyFunctions") // dispatcher.go의 메서드와 1:1 대응
public class Dispatcher
    @Suppress("LongParameterList") // Go New(conn, privateKey) + 주입 포트(crypto/random/scope/timeSource/logger)
    constructor(
        private val transport: Transport,
        private val privateKey: EcdhPrivateKey?,
        private val crypto: CryptoPrimitives,
        private val random: RandomSource,
        private val scope: CoroutineScope,
        private val timeSource: TimeSource = TimeSource.Monotonic,
        private val logger: TeslaLogger = TeslaLogger.NoOp,
    ) {
        /** 연결된 차량의 VIN. */
        public val vin: Vin = transport.vin

        /** Go `RetryInterval()`: 전송 계층의 재전송 간격. */
        public val retryInterval: Duration get() = transport.retryInterval

        /** Go `maxLatency`: 요청 후 이 시간이 지나 도착한 세션정보는 버린다(BLE 4초). */
        public var maxLatency: Duration = transport.allowedLatency
            private set

        /** 수신 루프가 돌고 있는지(Go `terminate != nil`). */
        public val isListening: Boolean get() = receiveJob?.isActive == true

        private val address: ByteString = random.nextBytes(ADDRESS_LENGTH).toByteString()
        private val sessions: Map<Domain, SessionState> =
            if (privateKey == null) emptyMap() else ALL_DOMAINS.associateWith { SessionState(vin, privateKey, crypto, random, timeSource) }
        private val pending = HashMap<PendingKey, PendingRequest>()
        private val pendingMutex = Mutex()
        private var receiveJob: Job? = null

        /** Go `SetMaxLatency`: 양수일 때만 바꾼다. */
        public fun setMaxLatency(latency: Duration) {
            if (latency > Duration.ZERO) maxLatency = latency
        }

        /** 도메인의 세션 상태. 개인키가 없으면 null. */
        public fun session(domain: Domain): SessionState? = sessions[domain]

        /** Go `Start` + `listen`: 수신 코루틴을 띄운다. `UNDISPATCHED`로 시작해 반환 전에 [Transport.incoming] 구독이 끝난다. 멱등. */
        public fun start() {
            if (isListening) return
            receiveJob =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    logger.log(LogLevel.INFO, TAG) { "Starting dispatcher service..." }
                    transport.incoming.collect { process(it) }
                }
        }

        /** Go `Stop`: 수신 코루틴을 취소하고 끝날 때까지 기다린다. 세션은 유지된다(Go와 동일). */
        public suspend fun stop() {
            receiveJob?.cancelAndJoin()
            receiveJob = null
        }

        /** Go `Vehicle.Disconnect`: [stop] + 세션 키 소거 + 전송 닫기. */
        public suspend fun close() {
            stop()
            for (session in sessions.values) session.close()
            transport.close()
        }

        /**
         * Go `Send`: uuid·routing_address를 채우고([AuthMethod.GCM]이면 인가한 뒤) 전송한다. 전송 오류가 `shouldRetry()`면 [retryInterval] 뒤
         * 다시 보내고, 아니면 그 오류를 돌려준다. 성공하면 응답을 받을 [PendingRequest] — 호출자가 반드시 닫는다.
         * [lifetime]은 `expires_at` 수명(D29 `commandLifetime`). 취소는 `CancellationException`으로 전파되고 등록은 `finally`에서 풀린다.
         */
        public suspend fun send(
            message: RoutableMessage,
            auth: AuthMethod,
            lifetime: Duration = DEFAULT_LIFETIME,
        ): VehicleResult<PendingRequest> {
            if (!isListening) return VehicleResult.Failure(VehicleError.NotConnected)
            val domain = message.to_destination?.domain
            if (domain == null || domain == Domain.DOMAIN_BROADCAST) {
                return VehicleResult.Failure(VehicleError.InvalidArgument("cannot send message without a destination domain"))
            }
            val uuid = random.nextBytes(UUID_LENGTH).toByteString()
            val isVcsec = domain == Domain.DOMAIN_VEHICLE_SECURITY
            val routingAddress = if (isVcsec) random.nextBytes(ADDRESS_LENGTH).toByteString() else address
            val key = PendingKey(routingAddress, if (isVcsec) ByteString.EMPTY else uuid, domain)
            val addressed = message.copy(uuid = uuid, from_destination = Destination(routing_address = routingAddress))
            val outgoing = authorize(addressed, domain, auth, lifetime).valueOr { return it.toResult() }
            val request = register(key, RequestHash.of(outgoing))
            return transmit(request, RoutableMessage.ADAPTER.encode(outgoing), uuid)
        }

        /** Go `RequestSessionInfo`: 개인키가 없으면 [VehicleError.RequiresKey]. 인증 없이 보낸다. */
        public suspend fun requestSessionInfo(domain: Domain): VehicleResult<PendingRequest> {
            val key = privateKey ?: return VehicleResult.Failure(VehicleError.RequiresKey)
            logger.log(LogLevel.INFO, TAG) { "Requesting session info from $domain" }
            return send(sessionInfoRequest(domain, key.publicKey), AuthMethod.NONE)
        }

        /** 열려 있는 요청 수(테스트용: 등록 누수 확인). */
        internal suspend fun pendingCount(): Int = pendingMutex.withLock { pending.values.count { !it.isClosed } }

        private suspend fun authorize(
            message: RoutableMessage,
            domain: Domain,
            auth: AuthMethod,
            lifetime: Duration,
        ): VehicleResult<RoutableMessage> {
            if (auth == AuthMethod.NONE) return VehicleResult.Success(message)
            val session = sessions[domain]
            if (session == null || !session.isReady) {
                logger.log(LogLevel.WARN, TAG) { "No session available for $domain" }
                return VehicleResult.Failure(VehicleError.NoSession)
            }
            return when (val result = session.authorize(message, lifetime)) {
                is SignerResult.Ok -> VehicleResult.Success(result.value)
                is SignerResult.Fault -> VehicleResult.Failure(result.toVehicleError())
            }
        }

        /** Go `Send`의 전송 루프. 넘겨주지 못했으면(오류·취소) `finally`에서 등록을 푼다. */
        private suspend fun transmit(
            request: PendingRequest,
            encoded: ByteArray,
            uuid: ByteString,
        ): VehicleResult<PendingRequest> {
            var handedOver = false
            try {
                while (true) {
                    val sent = transport.send(encoded)
                    if (sent is VehicleResult.Success) {
                        handedOver = true
                        return VehicleResult.Success(request)
                    }
                    val error = checkNotNull(sent.errorOrNull())
                    if (!error.shouldRetry()) {
                        logger.log(LogLevel.WARN, TAG) { "[${uuid.hex()}] Terminal transmission error: ${error.message}" }
                        return error.toResult()
                    }
                    logger.log(LogLevel.DEBUG, TAG) { "[${uuid.hex()}] Retrying transmission after error: ${error.message}" }
                    delay(retryInterval)
                }
            } finally {
                if (!handedOver) request.close()
            }
        }

        /** Go `createHandler`. 닫힌 요청은 이때 정리한다(`PendingRequest.close`가 suspend하지 않으므로 지연 제거). */
        private suspend fun register(
            key: PendingKey,
            requestHash: ByteArray?,
        ): PendingRequest =
            pendingMutex.withLock {
                pending.values.removeAll { it.isClosed }
                PendingRequest(key, requestHash, timeSource.markNow()).also { pending[key] = it }
            }

        private suspend fun lookup(key: PendingKey): PendingRequest? =
            pendingMutex.withLock {
                val found = pending[key] ?: return@withLock null
                if (found.isClosed) {
                    pending.remove(key)
                    null
                } else {
                    found
                }
            }

        /** Go `listen` 본문 + `process`: 수신 코루틴에서만 호출된다. */
        private suspend fun process(bytes: ByteArray) {
            val message =
                try {
                    RoutableMessage.ADAPTER.decode(bytes)
                } catch (e: IOException) {
                    logger.log(LogLevel.WARN, TAG) { "Dropping unparseable message: ${e.message}" }
                    return
                }
            val key = matchKey(message) ?: return
            val id = message.request_uuid.hex()
            val handler = lookup(key)
            if (handler == null) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message without registered handler $key" }
                return
            }
            // 차량은 desync가 의심되면 오류 응답에 세션정보를 동봉한다. 반영한 뒤에도 응답은 핸들러로 전달한다.
            checkForSessionUpdate(message, handler)
            val deliverable = decryptIfNeeded(message, handler) ?: return
            if (!handler.deliver(deliverable)) {
                logger.log(LogLevel.ERROR, TAG) { "[$id] Dropping response to command because response handler queue is full" }
            }
        }

        /** Go `process`의 검증 부분: 드롭이면 사유를 남기고 null. */
        private fun matchKey(message: RoutableMessage): PendingKey? {
            val id = message.request_uuid.hex()
            val fromDomain = message.from_destination?.domain
            if (fromDomain == null) {
                logger.log(LogLevel.WARN, TAG) { "[xxx] Dropping message with missing source" }
                return null
            }
            val requestUuid = message.request_uuid
            if (requestUuid.size != UUID_LENGTH && requestUuid.size != 0) {
                logger.log(LogLevel.WARN, TAG) { "[xxx] Dropping message with invalid request UUID length" }
                return null
            }
            val destination = message.to_destination
            if (destination == null) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message with missing destination" }
                return null
            }
            val routingAddress = destination.routing_address
            if (routingAddress == null) {
                val toDomain = destination.domain
                logger.log(LogLevel.DEBUG, TAG) {
                    if (toDomain != null) "[$id] Dropping message to $toDomain" else "[$id] Dropping message with unrecognized destination type"
                }
                return null
            }
            if (routingAddress.size != ADDRESS_LENGTH) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message with invalid address length" }
                return null
            }
            val uuid = if (fromDomain == Domain.DOMAIN_VEHICLE_SECURITY) ByteString.EMPTY else requestUuid
            return PendingKey(routingAddress, uuid, fromDomain)
        }

        /** Go `checkForSessionUpdate`: 폐기 조건(FR-014)을 지나면 [SessionState.processHello]. challenge = `request_uuid`. */
        private suspend fun checkForSessionUpdate(
            message: RoutableMessage,
            handler: PendingRequest,
        ) {
            val info = message.session_info ?: return
            val id = message.request_uuid.hex()
            if (privateKey == null) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Discarding session info because client does not have a private key" }
                return
            }
            if (handler.expired(maxLatency)) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Discarding session info because it was received more than $maxLatency after request" }
                return
            }
            val tag = message.signature_data?.session_info_tag?.tag
            if (tag == null) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Discarding unauthenticated session info" }
                return
            }
            val domain = handler.key.domain
            val session = sessions[domain]
            if (session == null) {
                logger.log(LogLevel.ERROR, TAG) { "[$id] Dropping session from unregistered domain $domain" }
                return
            }
            when (val result = session.processHello(message.request_uuid.toByteArray(), info.toByteArray(), tag.toByteArray())) {
                is SignerResult.Fault -> logger.log(LogLevel.WARN, TAG) { "[$id] Session info error: ${result.fault.name}: ${result.detail}" }
                is SignerResult.Ok -> logger.log(LogLevel.INFO, TAG) { "[$id] Updated session info for $domain" }
            }
        }

        /** Go `decrypt` + `session.decrypt`: 평문이면 그대로, 암호문이면 복호화 후 요청별 윈도우 검사. 드롭이면 null. */
        private suspend fun decryptIfNeeded(
            message: RoutableMessage,
            handler: PendingRequest,
        ): RoutableMessage? {
            if (message.signature_data?.AES_GCM_Response_data == null) return message
            val id = message.request_uuid.hex()
            val result = sessions[handler.key.domain]?.decrypt(message, handler.requestHash ?: ByteArray(0))
            if (result == null) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Error decrypting vehicle response: could not decrypt vehicle response without a session" }
                return null
            }
            return when (result) {
                is SignerResult.Fault -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Error decrypting vehicle response: ${result.fault.name}: ${result.detail}" }
                    null
                }
                is SignerResult.Ok ->
                    if (handler.antiReplay.update(result.value.counter)) {
                        result.value.message
                    } else {
                        logger.log(LogLevel.INFO, TAG) { "[$id] Dropping duplicate vehicle response" }
                        null
                    }
            }
        }

        /** 상수와 메시지 조립. */
        public companion object {
            /** 핸드셰이크 대상 도메인 전부(Go `StartSessions(nil)`). */
            public val ALL_DOMAINS: Set<Domain> = setOf(Domain.DOMAIN_VEHICLE_SECURITY, Domain.DOMAIN_INFOTAINMENT)

            /** Go `defaultExpiration`: 인가 명령의 기본 `expires_at` 수명. */
            public val DEFAULT_LIFETIME: Duration = 5.seconds

            /** Go `SessionInfoRequest(domain, publicBytes)`: 핸드셰이크 요청 메시지. */
            public fun sessionInfoRequest(
                domain: Domain,
                publicKey: PublicKeyBytes,
            ): RoutableMessage =
                RoutableMessage(
                    to_destination = Destination(domain = domain),
                    session_info_request = SessionInfoRequest(public_key = publicKey.toByteArray().toByteString()),
                )
        }
    }
```

`tools/ci/ported-files.txt`에 `application/…/dispatcher/Dispatcher.kt`를 추가한다. `DispatcherTest`의 `TestCryptoPublicKeyHex`는 `TestCrypto.clientPublicKey.toByteArray().toByteString().hex()`다(작성 시 `import io.github.smallmiro.teslable.testing.TestCrypto`를 추가하고 정규화해도 된다).

- [ ] **Step 4: 통과 확인, API 덤프, 커밋**

Run: `./gradlew :domain:apiDump --console=plain`
Run: `./gradlew :application:apiDump --console=plain`
Run: `./gradlew :application:jvmTest :domain:check :application:check :testing:check :application:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS). `domain/api/*`에 `TeslaLogger`/`LogLevel`, `application/api/*`에 `Dispatcher` 반영.

```bash
git add domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/TeslaLogger.kt domain/api
git commit -m "feat(domain): TeslaLogger port with NoOp default (SDD §10)

Refs: FR-112, NFR-006
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/RecordingLogger.kt
git commit -m "test(testing): RecordingLogger for asserting dispatcher drop reasons

Refs: NFR-003
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add application/src application/api tools/ci/ported-files.txt
git commit -m "feat(application): Dispatcher ports dispatcher.go send/receive path — matching, drop rules, session-info gate, decrypt + anti-replay

Refs: FR-010, FR-011, FR-014, FR-016, FR-018, NFR-012
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 7: `HandshakeFlow` — `startSession`(재전송 루프)·`startSessions`(병렬) + FR-014 규칙 테스트 마무리

참고 매뉴얼: `01-architecture.md` §3.2(`StartSession`, `tryStartSession`, `StartSessions`), `08-errors.md` §4(핸드셰이크 재시도 표); `03-protocol.md` §7. Go 원본: `internal/dispatcher/dispatcher.go`(`StartSession` 73~94행, `tryStartSession` 96~125행, `StartSessions` 131~156행), `dispatcher_test.go`(`TestStartSession` 328행, `TestTimeout` 350행, `TestVehicleDropsReply` 457행, `TestConnect` 621행, `TestWaitForAllSessions` 668행, `TestHandshakeWithoutKey` 856행, `TestNoValidHandshakeResponse` 878행, `TestRetryNonresponsive` 932행). 설계: `{{SDD_FILE}}` §3.2, §5(핸드셰이크 병렬), FR-012, FR-014.

**Files:**
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/HandshakeFlow.kt`
- Modify: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicle.kt` (`corruptNextSessionInfoTag(domain, count = 1)` — 횟수 지원)
- Modify: `tools/ci/ported-files.txt`, `application/api/*`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/HandshakeFlowTest.kt`

**Interfaces:**
- Consumes: Task 6 `Dispatcher`(`session`, `requestSessionInfo`, `retryInterval`, `ALL_DOMAINS`), Task 5 `SessionState.ready`/`awaitReady`, `PendingRequest.onReceive`, M1 `ResponseClassifier.protocolError`, `Results.kt` 헬퍼.
- Produces: `public class HandshakeFlow(dispatcher: Dispatcher) { suspend fun startSession(domain: Domain): VehicleResult<Unit>; suspend fun startSessions(domains: Set<Domain> = Dispatcher.ALL_DOMAINS): VehicleResult<Unit> }`. 시간 제한은 걸지 않는다 — Task 9 `VehicleSession.startSession`이 `withTimeoutOrNull`로 감싸고 `shouldRetry` 오류를 재시도한다(Go `Vehicle.StartSession`).
- `FakeVehicle.corruptNextSessionInfoTag(domain, count: Int = 1)`: 다음 [count]개의 세션정보 태그를 뒤집는다(Go `TestNoValidHandshakeResponse`의 4회 불량 응답 재현용).

- [ ] **Step 1: `FakeVehicle` 수정 (구조 변경 없음, 테스트 픽스처 확장)**

`DomainState.corruptNextTag: Boolean`을 `var corruptTagsRemaining = 0`으로 바꾸고, 메서드와 헬퍼를 고친다.

```kotlin
    /** 다음 [count]개의 세션정보 태그 첫 바이트를 뒤집는다(HMAC 불일치 시나리오). */
    public fun corruptNextSessionInfoTag(
        domain: Domain,
        count: Int = 1,
    ) {
        domains.getValue(domain).corruptTagsRemaining += count
    }
```

```kotlin
    private fun maybeCorruptTag(
        state: DomainState,
        reply: RoutableMessage,
    ): RoutableMessage {
        if (state.corruptTagsRemaining == 0) return reply
        state.corruptTagsRemaining--
        val tag = checkNotNull(reply.signature_data?.session_info_tag).tag.toByteArray()
        tag[0] = (tag[0].toInt() xor 1).toByte()
        return reply.copy(signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = tag.toByteString())))
    }
```

Run: `./gradlew :testing:jvmTest --tests '*FakeVehicleTest*' --console=plain`
Expected: PASS (기존 테스트는 기본값 1로 그대로)

- [ ] **Step 2: 실패 테스트 작성**

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/dispatcher/HandshakeFlowTest.kt
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeFlowTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val quiescentDelay = 250.milliseconds // dispatcher_test.go quiescentDelay

    @Test
    fun startSessionCompletesHandshakeAndAllowsAuthenticatedSend() =
        // dispatcher_test.go getTestSetup + TestStartSession
        runTest {
            val h = dispatcherHarness()
            val flow = HandshakeFlow(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(withTimeoutOrNull(quiescentDelay) { flow.startSession(infotainment) })
            assertEquals(1, h.fake.sessionInfoRequests)
            assertTrue(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).value
            pending.use { assertNull(ResponseClassifier.protocolError(it.receive())) }
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun startSessionReturnsImmediatelyWhenSessionAlreadyExists() =
        // dispatcher.go StartSession: s.ctx != nil → "Session for %s loaded from cache" → 즉시 반환
        runTest {
            val h = dispatcherHarness()
            val flow = HandshakeFlow(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(flow.startSession(vcsec))
            assertIs<VehicleResult.Success<Unit>>(flow.startSession(vcsec))
            assertEquals(1, h.fake.sessionInfoRequests)
        }

    @Test
    fun commandWithoutReplyTimesOut() =
        // dispatcher_test.go TestTimeout
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.fake.dropNextReplies(1)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).value
            pending.use { assertNull(withTimeoutOrNull(quiescentDelay) { it.receive() }) }
        }

    @Test
    fun retransmitsSessionInfoRequestEveryRetryIntervalWhileVehicleSleeps() =
        // dispatcher_test.go TestRetryNonresponsive + TestVehicleDropsReply: 응답이 없으면 RetryInterval마다 재전송, 취소되면 그만
        runTest {
            val h = dispatcherHarness()
            h.fake.sleep()
            val start = testTimeSource.markNow()
            assertNull(withTimeoutOrNull(5.milliseconds) { HandshakeFlow(h.dispatcher).startSession(infotainment) })
            assertTrue(h.fake.sessionInfoRequests >= 5, "expected >= 5 requests, got ${h.fake.sessionInfoRequests}")
            assertEquals(5.milliseconds, start.elapsedNow())
            assertFalse(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            runCurrent()
            assertEquals(0, h.dispatcher.pendingCount()) // 취소돼도 등록은 풀린다
        }

    @Test
    fun malformedHandshakeReplyIsRetransmittedAfterRetryInterval() =
        // dispatcher.go tryStartSession 두 번째 select: 응답은 왔지만 ready가 안 되면 RetryInterval 뒤 재시도
        runTest {
            val h = dispatcherHarness()
            h.fake.corruptNextSessionInfoTag(vcsec)
            assertIs<VehicleResult.Success<Unit>>(withTimeoutOrNull(quiescentDelay) { HandshakeFlow(h.dispatcher).startSession(vcsec) })
            assertEquals(2, h.fake.sessionInfoRequests)
            assertTrue(h.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
        }

    @Test
    fun startSessionFailsWithKeyNotPairedAfterBogusRepliesThenUnknownKeyId() =
        // dispatcher_test.go TestNoValidHandshakeResponse: 불량 태그 4번 뒤 UNKNOWN_KEY_ID → ErrKeyNotPaired
        runTest {
            val h = dispatcherHarness()
            h.fake.corruptNextSessionInfoTag(infotainment, count = 4)
            val none = MessageFault_E.MESSAGEFAULT_ERROR_NONE
            h.fake.scriptHandshake(infotainment, none, none, none, none, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            val result = assertIs<VehicleResult.Failure>(withTimeoutOrNull(quiescentDelay) { HandshakeFlow(h.dispatcher).startSession(infotainment) })
            assertEquals(VehicleError.KeyNotPaired, result.error)
            assertEquals(5, h.fake.sessionInfoRequests)
        }

    @Test
    fun busyHandshakeReplyIsReturnedForTheCallerToRetry() =
        // dispatcher.go tryStartSession: GetError(reply) != nil → 반환 (Vehicle.StartSession이 ShouldRetry면 재시도, Task 9)
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_BUSY)
            val result = assertIs<VehicleResult.Failure>(HandshakeFlow(h.dispatcher).startSession(vcsec))
            assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_BUSY), result.error)
            assertTrue(result.error.temporary)
        }

    @Test
    fun startSessionsHandshakesBothDomainsAndFailsFastWhileAsleep() =
        // dispatcher_test.go TestConnect + TestWaitForAllSessions
        runTest {
            val h = dispatcherHarness()
            val flow = HandshakeFlow(h.dispatcher)
            h.fake.sleep()
            assertNull(withTimeoutOrNull(10.milliseconds) { flow.startSessions() })
            assertEquals(VehicleError.NoSession, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).error)
            h.fake.wake()
            assertIs<VehicleResult.Success<Unit>>(withTimeoutOrNull(quiescentDelay) { flow.startSessions() })
            assertTrue(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).value.close()
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun startSessionsReturnsFirstFailureAndCancelsTheRest() =
        // dispatcher.go StartSessions: 첫 non-Canceled 오류를 돌려주고 aggregateContext를 취소한다
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            h.fake.sleep(setOf(infotainment))
            val start = testTimeSource.markNow()
            val result = assertIs<VehicleResult.Failure>(HandshakeFlow(h.dispatcher).startSessions())
            assertEquals(VehicleError.KeyNotPaired, result.error)
            assertTrue(start.elapsedNow() < h.dispatcher.retryInterval * 2, "must not wait for the sleeping domain")
            runCurrent()
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun startSessionWithoutKeyReturnsRequiresKey() =
        // dispatcher_test.go TestHandshakeWithoutKey
        runTest {
            val h = dispatcherHarness(privateKey = null)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(HandshakeFlow(h.dispatcher).startSession(infotainment)).error)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(HandshakeFlow(h.dispatcher).startSessions()).error)
        }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :application:jvmTest --tests '*HandshakeFlowTest*' --console=plain`
Expected: 컴파일 실패 (`HandshakeFlow` 없음)

- [ ] **Step 4: 구현**

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/HandshakeFlow.kt
// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher.go (Apache-2.0) — StartSession, tryStartSession, StartSessions
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Go `StartSession`/`tryStartSession`/`StartSessions`: 도메인마다 세션정보 요청을 보내고 준비 신호·응답·재전송 간격 중 먼저 오는 것을
 * 처리한다. 시간 제한은 걸지 않는다 — 호출자(`VehicleSession.startSession`)가 `withTimeoutOrNull`로 감싼다(D29).
 */
public class HandshakeFlow(
    private val dispatcher: Dispatcher,
) {
    private sealed interface Step {
        data object Ready : Step

        data object Retry : Step

        class Reply(
            val message: RoutableMessage,
        ) : Step
    }

    /**
     * Go `StartSession`: 세션이 이미 있으면(캐시) 즉시 성공. 아니면 [tryStartSession]을 재시도가 아닌 결과가 나올 때까지 반복한다.
     * 개인키가 없으면 [VehicleError.RequiresKey](Go `RequestSessionInfo` → `ErrRequiresKey`).
     */
    public suspend fun startSession(domain: Domain): VehicleResult<Unit> {
        val session = dispatcher.session(domain) ?: return VehicleResult.Failure(VehicleError.RequiresKey)
        if (session.isReady) return VehicleResult.Success(Unit)
        while (true) {
            tryStartSession(session, domain)?.let { return it }
        }
    }

    /**
     * Go `StartSessions`: 도메인마다 병렬로 [startSession]. 첫 실패를 돌려주고 나머지를 취소한다(SDD §5).
     * 취소는 `CancellationException`으로 전파된다.
     */
    public suspend fun startSessions(domains: Set<Domain> = Dispatcher.ALL_DOMAINS): VehicleResult<Unit> =
        coroutineScope {
            val jobs: MutableList<Deferred<VehicleResult<Unit>>> = domains.map { domain -> async { startSession(domain) } }.toMutableList()
            try {
                while (jobs.isNotEmpty()) {
                    val (done, result) =
                        select<Pair<Deferred<VehicleResult<Unit>>, VehicleResult<Unit>>> {
                            jobs.forEach { job -> job.onAwait { job to it } }
                        }
                    jobs.remove(done)
                    if (result !is VehicleResult.Success) return@coroutineScope result
                }
                VehicleResult.Success(Unit)
            } finally {
                jobs.forEach { it.cancel() }
            }
        }

    /** Go `tryStartSession`: null이면 재시도(Go `retry == true`). */
    private suspend fun tryStartSession(
        session: SessionState,
        domain: Domain,
    ): VehicleResult<Unit>? {
        val request = dispatcher.requestSessionInfo(domain).valueOr { return it.toResult() }
        request.use { pending ->
            val step =
                withTimeoutOrNull(dispatcher.retryInterval) {
                    select<Step> {
                        session.ready.onAwait { Step.Ready }
                        pending.onReceive { Step.Reply(it) }
                    }
                } ?: Step.Retry
            return when (step) {
                Step.Ready -> VehicleResult.Success(Unit)
                Step.Retry -> null
                is Step.Reply -> {
                    ResponseClassifier.protocolError(step.message)?.let { return it.toResult() }
                    // 응답이 왔다. 정상이면 디스패처가 전달 전에 processHello를 끝내 ready가 이미 완료돼 있다; 아니면(잘못된 응답)
                    // 재전송 간격만큼 기다렸다가 다시 보낸다(Go 두 번째 select).
                    withTimeoutOrNull(dispatcher.retryInterval) {
                        session.awaitReady()
                        VehicleResult.Success(Unit)
                    }
                }
            }
        }
    }
}
```

`tools/ci/ported-files.txt`에 `HandshakeFlow.kt`를 추가한다.

- [ ] **Step 5: 통과 확인, API 덤프, 커밋**

Run: `./gradlew :application:apiDump --console=plain`
Run: `./gradlew :application:jvmTest :application:check :testing:jvmTest :application:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS)

```bash
git add testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/FakeVehicle.kt
git commit -m "test(testing): FakeVehicle.corruptNextSessionInfoTag takes a count

Refs: NFR-003
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add application/src application/api tools/ci/ported-files.txt
git commit -m "feat(application): HandshakeFlow ports StartSession/tryStartSession/StartSessions (1s retransmit, parallel domains, first failure wins)

Refs: FR-012, FR-014, NFR-012
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 세션 캐시 — `SessionCache` 포트, `KeyId`, `SessionCacheCodec`(SDD §7.1), `InMemorySessionCache`, `RecordingSessionCache`, `Dispatcher` 내보내기/불러오기, `SessionCacheSync`

참고 매뉴얼: `01-architecture.md` §6(세션 캐시 데이터 구조), `03-protocol.md` §9.3, `10-porting-guide.md` §9(Go 형식 — 우리는 자체 형식 v1, D26). Go 원본: `internal/dispatcher/session.go`(`CacheEntry` 20~24행), `dispatcher.go`(`Cache` 492~512행, `LoadCache` 516~536행), `pkg/cache/cache.go`(`Update`, `GetEntry`), `pkg/vehicle/vehicle.go`(`NewVehicle` 91~97행, `UpdateCachedSessions`, `LoadCachedSessions`), `cache_test.go`(`TestImportExport` — 자체 형식으로; `TestEviction`은 VIN당 1항목이라 해당 없음), `dispatcher_test.go`(`TestCache` 977행). 설계: `{{SDD_FILE}}` §2.1(`SessionCache`), §2.6, §7.1, ADR-0007, FR-017, 설계 구체화 6·7·8·12, Review Focus 4.

**Files:**
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/KeyId.kt`
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/cache/CachedSession.kt`
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/cache/SessionCacheCodec.kt`
- Create: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/SessionCache.kt`
- Create: `adapter-storage/src/commonMain/kotlin/io/github/smallmiro/teslable/storage/InMemorySessionCache.kt`
- Delete: `adapter-storage/src/commonMain/kotlin/io/github/smallmiro/teslable/storage/Placeholder.kt`
- Create: `testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/RecordingSessionCache.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/dispatcher/Dispatcher.kt` (`exportSessions`, `loadSessions`)
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/cache/SessionCacheSync.kt`
- Modify: `adapter-storage/build.gradle.kts` (`commonTest` → `:testing`), `tools/ci/ported-files.txt`, `domain/api/*`, `application/api/*`, `adapter-storage/api/*`
- Test: `domain/src/commonTest/kotlin/io/github/smallmiro/teslable/cache/SessionCacheCodecTest.kt`
- Test: `adapter-storage/src/commonTest/kotlin/io/github/smallmiro/teslable/storage/InMemorySessionCacheTest.kt`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/cache/SessionCacheSyncTest.kt`

**Interfaces:**
- Consumes: M0 `Vin`, `PublicKeyBytes`, `CryptoPrimitives.sha1`, Task 5 `SessionState.export`/`loadFromCache`, Task 6 `Dispatcher`, Task 7 `HandshakeFlow`, okio `Buffer`.
- Produces:
  - `public class KeyId(bytes: ByteArray /*20*/) { fun toByteArray(); companion { SIZE = 20; fun of(publicKey: PublicKeyBytes, crypto: CryptoPrimitives): KeyId } }`
  - `public class SessionSnapshot(val domain: Domain, sessionInfo: ByteArray) { val sessionInfo }` — Go `session.export()` 결과(저장 시각 없음).
  - `public class CachedSession(val domain: Domain, sessionInfo: ByteArray, val age: Duration)` — Go `CacheEntry`를 불러온 것; `age = now - createdAt`(음수 가능, 클램프는 `Signer`).
  - `public object SessionCacheCodec { VERSION = 1; class Entry(domain, createdAtEpochMillis: Long, sessionInfo); fun encode(keyId, entries: List<Entry>): ByteArray; fun decode(bytes, expectedKeyId): List<Entry>? }` — SDD §7.1: `"TBSC"(4) | u8 1 | keyId(20) | count u8 | (domain u8 | createdAt i64 BE | len u16 BE | info)*`. null = 매직/버전/keyId 불일치·손상; 모르는 도메인 값의 항목은 건너뛴다.
  - `public interface SessionCache { suspend fun load(vin, keyId): List<CachedSession>; suspend fun store(vin, keyId, entries: List<SessionSnapshot>); suspend fun clear(vin) }` — 구현은 예외를 던지지 않는다(실패 = 빈 목록/로그).
  - `public class InMemorySessionCache(wallClockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : SessionCache` — 벽시계는 여기서만.
  - `public class RecordingSessionCache(wallClockMillis: () -> Long = { 0L }) : SessionCache { val stores: List<Stored>; val loads: Int; fun seed(vin, keyId, entries: List<SessionCacheCodec.Entry>) }` (`:testing`).
  - `Dispatcher.exportSessions(): List<SessionSnapshot>`(Go `Cache`), `Dispatcher.loadSessions(entries: List<CachedSession>): Set<Domain>`(Go `LoadCache`; 손상 항목은 건너뛰고 로그).
  - `public class SessionCacheSync(cache: SessionCache, vin: Vin, keyId: KeyId) { suspend fun load(dispatcher): Set<Domain>; suspend fun store(dispatcher) }`.

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// domain/src/commonTest/kotlin/io/github/smallmiro/teslable/cache/SessionCacheCodecTest.kt
package io.github.smallmiro.teslable.cache

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(InternalTeslableApi::class)
class SessionCacheCodecTest {
    private val keyId = KeyId(ByteArray(20) { (it + 1).toByte() })
    private val createdAt = 1_700_000_000_000L // 0x0000018bcfe56800 (printf '%016x')
    private val entry = SessionCacheCodec.Entry(Domain.DOMAIN_VEHICLE_SECURITY, createdAt, "0806".hexToBytes()) // SessionInfo{counter=6}

    // SDD §7.1: magic "TBSC" | version 1 | keyId(20) | count | domain u8 | createdAt i64 BE | len u16 BE | info
    private val expectedHex =
        "54425343" + "01" + "0102030405060708090a0b0c0d0e0f1011121314" + "01" +
            "02" + "0000018bcfe56800" + "0002" + "0806"

    @Test
    fun encodesVersion1LayoutFromSdd() {
        assertEquals(expectedHex, SessionCacheCodec.encode(keyId, listOf(entry)).toHex())
    }

    @Test
    fun decodesTheSameBytesBack() {
        val decoded = assertNotNull(SessionCacheCodec.decode(expectedHex.hexToBytes(), keyId))
        assertEquals(1, decoded.size)
        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, decoded[0].domain)
        assertEquals(createdAt, decoded[0].createdAtEpochMillis)
        assertContentEquals("0806".hexToBytes(), decoded[0].sessionInfo)
    }

    @Test
    fun roundTripsTwoDomainsInOrder() {
        // cache_test.go TestImportExport의 자체 형식판
        val second = SessionCacheCodec.Entry(Domain.DOMAIN_INFOTAINMENT, createdAt + 1, ByteArray(300) { it.toByte() })
        val decoded = assertNotNull(SessionCacheCodec.decode(SessionCacheCodec.encode(keyId, listOf(entry, second)), keyId))
        assertEquals(listOf(Domain.DOMAIN_VEHICLE_SECURITY, Domain.DOMAIN_INFOTAINMENT), decoded.map { it.domain })
        assertEquals(createdAt + 1, decoded[1].createdAtEpochMillis)
        assertContentEquals(ByteArray(300) { it.toByte() }, decoded[1].sessionInfo)
        assertEquals(0, assertNotNull(SessionCacheCodec.decode(SessionCacheCodec.encode(keyId, emptyList()), keyId)).size)
    }

    @Test
    fun rejectsOtherKeyIdBadMagicBadVersionTruncationAndTrailingBytes() {
        val bytes = expectedHex.hexToBytes()
        assertNull(SessionCacheCodec.decode(bytes, KeyId(ByteArray(20) { 9 }))) // 다른 키 → 무시(ADR-0007)
        assertNull(SessionCacheCodec.decode(("54425344" + expectedHex.substring(8)).hexToBytes(), keyId)) // "TBSD"
        assertNull(SessionCacheCodec.decode(("54425343" + "02" + expectedHex.substring(10)).hexToBytes(), keyId)) // version 2
        assertNull(SessionCacheCodec.decode(bytes.copyOf(bytes.size - 1), keyId)) // 잘림
        assertNull(SessionCacheCodec.decode(bytes + byteArrayOf(0), keyId)) // 꼬리 바이트
        assertNull(SessionCacheCodec.decode(ByteArray(0), keyId))
    }

    @Test
    fun skipsEntriesWithUnknownDomainValue() {
        // 신형 펌웨어 도메인 7 — Go LoadCache는 universal.Domain(7)을 그대로 세션 맵에 넣지만 우리는 세션을 만들 수 없어 건너뛴다(설계 구체화 6)
        val withUnknown = expectedHex.replace("01" + "02" + "0000018bcfe56800", "02" + "07" + "0000018bcfe56800" + "0002" + "0806" + "02" + "0000018bcfe56800")
        val decoded = assertNotNull(SessionCacheCodec.decode(withUnknown.hexToBytes(), keyId))
        assertEquals(listOf(Domain.DOMAIN_VEHICLE_SECURITY), decoded.map { it.domain })
    }

    @Test
    fun keyIdIsSha1OfThePublicKeyAndRejectsOtherLengths() {
        val crypto = TestCrypto.primitives
        val id = KeyId.of(TestCrypto.clientPublicKey, crypto)
        assertContentEquals(crypto.sha1(TestCrypto.clientPublicKey.toByteArray()), id.toByteArray())
        assertEquals(id, KeyId.of(TestCrypto.clientPublicKey, crypto))
        assertFailsWith<IllegalArgumentException> { KeyId(ByteArray(19)) }
        assertFailsWith<IllegalArgumentException> { SessionCacheCodec.encode(keyId, List(256) { entry }) }
        assertFailsWith<IllegalArgumentException> { SessionCacheCodec.encode(keyId, listOf(SessionCacheCodec.Entry(Domain.DOMAIN_INFOTAINMENT, 0L, ByteArray(65_536)))) }
    }
}
```

```kotlin
// adapter-storage/src/commonTest/kotlin/io/github/smallmiro/teslable/storage/InMemorySessionCacheTest.kt
package io.github.smallmiro.teslable.storage

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class InMemorySessionCacheTest {
    private val vin = Vin(ProtocolVectors.VIN)
    private val keyId = KeyId(ByteArray(20) { 1 })
    private val otherKeyId = KeyId(ByteArray(20) { 2 })
    private var now = 10_000L
    private val cache = InMemorySessionCache { now }
    private val snapshot = SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, byteArrayOf(0x08, 0x06))

    @Test
    fun storeStampsWallClockAndLoadComputesAge() =
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            now = 40_000L
            val loaded = cache.load(vin, keyId).single()
            assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, loaded.domain)
            assertContentEquals(byteArrayOf(0x08, 0x06), loaded.sessionInfo)
            assertEquals(30.seconds, loaded.age)
        }

    @Test
    fun entryFromTheFutureYieldsNegativeAgeWithoutThrowing() =
        // 인계 항목 5: 벽시계가 뒤로 감 → age < 0. 클램프는 Signer.importSessionInfo 몫(설계 구체화 7)
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            now = 5_000L
            assertEquals((-5).seconds, cache.load(vin, keyId).single().age)
        }

    @Test
    fun loadWithAnotherKeyDiscardsTheCache() =
        // ADR-0007: keyId가 현재 키와 다르면 캐시를 무시하고 삭제한다
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            assertTrue(cache.load(vin, otherKeyId).isEmpty())
            assertTrue(cache.load(vin, keyId).isEmpty()) // 삭제됨
        }

    @Test
    fun storeOverwritesAndClearRemoves() =
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(1))))
            assertEquals(listOf(Domain.DOMAIN_INFOTAINMENT), cache.load(vin, keyId).map { it.domain })
            cache.clear(vin)
            assertTrue(cache.load(vin, keyId).isEmpty())
            assertTrue(cache.load(Vin(ProtocolVectors.LOCAL_NAME_VIN), keyId).isEmpty())
        }
}
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/cache/SessionCacheSyncTest.kt
package io.github.smallmiro.teslable.application.cache

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.PendingRequest
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.testCommand
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingSessionCache
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheSyncTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)

    @Test
    fun resumesSessionFromCacheWithoutHandshake() =
        // dispatcher_test.go TestCache: Cache() → 새 디스패처 LoadCache() → 인증 명령이 핸드셰이크 없이 성공
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSessions())
            var now = 100_000L
            val cache = RecordingSessionCache { now }
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            sync.store(first.dispatcher)
            assertEquals(setOf(vcsec, infotainment), cache.stores.single().entries.map { it.domain }.toSet())
            first.dispatcher.close()
            advanceTimeBy(30_000)
            now += 30_000
            val second = dispatcherHarness(fake = fake)
            assertEquals(setOf(vcsec, infotainment), sync.load(second.dispatcher))
            assertEquals(1, cache.loads)
            assertTrue(assertNotNull(second.dispatcher.session(vcsec)).isReady)
            assertTrue(second.logger.contains("Session for DOMAIN_VEHICLE_SECURITY loaded from cache"))
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(second.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).value
            pending.use { assertNull(ResponseClassifier.protocolError(it.receive())) }
            assertEquals(2, fake.sessionInfoRequests) // 두 번째 연결은 핸드셰이크하지 않았다
        }

    @Test
    fun entryFromTheFutureImportsWithAgeZeroAndNeverThrows() =
        // 인계 항목 5 (캐시 경로): createdAt이 미래 → age 음수 → Signer가 0으로 본다. 예외 없음.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(vcsec))
            advanceTimeBy(10_000)
            val exported = first.dispatcher.exportSessions().single { it.domain == vcsec } // clock_time = 10
            var now = 100_000L
            val cache = RecordingSessionCache { now }
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            cache.store(fake.vin, keyId, listOf(exported))
            now = 50_000L // 벽시계가 50초 뒤로 갔다
            val second = dispatcherHarness(fake = fake)
            assertEquals(setOf(vcsec), sync.load(second.dispatcher))
            assertEquals(10u, assertNotNull(second.dispatcher.session(vcsec)).timestamp())
        }

    @Test
    fun loadSkipsCorruptEntriesAndRestoresTheRest() =
        // 설계 구체화 6: Go LoadCache는 전체 실패, 우리는 건너뛰고 로그
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(infotainment))
            val good = first.dispatcher.exportSessions().single()
            val cache = RecordingSessionCache()
            cache.seed(
                fake.vin,
                keyId,
                listOf(
                    SessionCacheCodec.Entry(vcsec, 0L, byteArrayOf(0x12)), // publicKey 태그 뒤가 잘림 → DECODING
                    SessionCacheCodec.Entry(infotainment, 0L, good.sessionInfo),
                ),
            )
            val second = dispatcherHarness(fake = fake)
            assertEquals(setOf(infotainment), SessionCacheSync(cache, fake.vin, keyId).load(second.dispatcher))
            assertTrue(second.logger.contains("invalid cache: DOMAIN_VEHICLE_SECURITY: MESSAGEFAULT_ERROR_DECODING"))
            assertTrue(assertNotNull(second.dispatcher.session(infotainment)).isReady)
            assertTrue(!assertNotNull(second.dispatcher.session(vcsec)).isReady)
        }

    @Test
    fun staleCachedVehicleKeyLeavesSessionStuckLikeGo() =
        // Review Focus 4 / 설계 구체화 8: 다른 차량 키로 만든 캐시 → 이 차량은 이 클라이언트를 처음 보므로 새 검증자(새 epoch)가
        // INCORRECT_EPOCH + 세션정보로 답한다. 동봉 세션정보는 우리 K(옛 차량 키)로 태그가 맞지 않아 거부(Session info error:
        // INVALID_SIGNATURE) → 세션은 갇힌다. Go와 동일. 복구는 M3 connect()의 도메인 캐시 삭제(사용자 답 a).
        runTest {
            val otherCar = FakeVehicle(vehicleKey = TestCrypto.goKnownVerifierKey(), timeSource = testTimeSource)
            val first = dispatcherHarness(fake = otherCar)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(vcsec))
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, otherCar.vin, keyId)
            sync.store(first.dispatcher)
            val thisCar = FakeVehicle(timeSource = testTimeSource) // 같은 VIN, 다른 차량 키
            val second = dispatcherHarness(fake = thisCar)
            assertEquals(setOf(vcsec), sync.load(second.dispatcher))
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(second.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).value
            pending.use {
                assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH), ResponseClassifier.protocolError(it.receive()))
            }
            assertTrue(second.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
            assertTrue(assertNotNull(second.dispatcher.session(vcsec)).isReady) // 여전히 옛 키로 "준비됨"
        }

    @Test
    fun exportSkipsDomainsWithoutSessionAndStoreWritesEvenWhenEmpty() =
        // dispatcher.go Cache(): ctx == nil인 세션은 건너뛴다; vehicle.go UpdateCachedSessions는 빈 목록도 저장(캐시 정리)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val h = dispatcherHarness(fake = fake)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            sync.store(h.dispatcher)
            assertTrue(cache.stores.single().entries.isEmpty())
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            sync.store(h.dispatcher)
            val stored = cache.stores.last().entries.single()
            assertEquals(infotainment, stored.domain)
            assertEquals(0, SessionInfo.ADAPTER.decode(stored.sessionInfo).clock_time)
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :domain:jvmTest --tests '*SessionCacheCodecTest*' --console=plain`
Expected: 컴파일 실패 (`SessionCacheCodec`, `KeyId` 없음). `adapter-storage/build.gradle.kts`에 `commonTest.dependencies { implementation(project(":testing")) }`를 추가해야 `InMemorySessionCacheTest`가 컴파일된다(Step 3).

- [ ] **Step 3: 구현**

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/KeyId.kt
package io.github.smallmiro.teslable.model

import io.github.smallmiro.teslable.port.CryptoPrimitives

private const val DISPLAY_BYTES = 4
private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_PADDING_WIDTH = 2

/** 세션 캐시를 묶는 클라이언트 키 식별자 = SHA1(공개키 65바이트) 20바이트(SDD §7.1, D26). 불변, 방어 복사. */
public class KeyId(
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == SIZE) { "key id must be $SIZE bytes (SHA-1)" }
    }

    /** 20바이트 사본. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is KeyId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "KeyId(${bytes.take(DISPLAY_BYTES).joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PADDING_WIDTH, '0') }}…)"

    /** 생성. */
    public companion object {
        /** SHA-1 다이제스트 길이. */
        public const val SIZE: Int = 20

        /** `SHA1(publicKey)`. */
        public fun of(
            publicKey: PublicKeyBytes,
            crypto: CryptoPrimitives,
        ): KeyId = KeyId(crypto.sha1(publicKey.toByteArray()))
    }
}
```

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/cache/CachedSession.kt
// Ported from vehicle-command@a4b43c1 internal/dispatcher/session.go (Apache-2.0) — CacheEntry (createdAt → age)
package io.github.smallmiro.teslable.cache

import com.tesla.generated.universalmessage.Domain
import kotlin.time.Duration

/** Go `session.export()` 결과 하나: 도메인의 `Signatures.SessionInfo` 바이트(clock_time = 내보낸 시점). 캐시가 저장 시각을 붙인다. */
public class SessionSnapshot(
    /** 도메인. */
    public val domain: Domain,
    sessionInfo: ByteArray,
) {
    private val info = sessionInfo.copyOf()

    /** protobuf 인코딩된 `SessionInfo`(사본). */
    public val sessionInfo: ByteArray get() = info.copyOf()
}

/** Go `CacheEntry`를 불러온 것. `createdAt` 대신 저장 후 경과 [age]를 준다(음수 가능 — `Signer.importSessionInfo`가 0으로 본다). */
public class CachedSession(
    /** 도메인. */
    public val domain: Domain,
    sessionInfo: ByteArray,
    /** 저장 시각부터 지금까지(Go `ImportSessionInfo(generatedAt)`의 `now - generatedAt`). */
    public val age: Duration,
) {
    private val info = sessionInfo.copyOf()

    /** protobuf 인코딩된 `SessionInfo`(사본). */
    public val sessionInfo: ByteArray get() = info.copyOf()
}
```

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/cache/SessionCacheCodec.kt
package io.github.smallmiro.teslable.cache

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.model.KeyId
import okio.Buffer
import okio.EOFException

private const val MAGIC = "TBSC"
private const val MAGIC_LENGTH = 4L
private const val BYTE_MASK = 0xff
private const val SHORT_MASK = 0xffff
private const val MAX_ENTRIES = 255
private const val MAX_INFO_LENGTH = 65_535

/**
 * 세션 캐시 바이너리 형식 v1(SDD §7.1, ADR-0007). 모두 big-endian:
 * `"TBSC"(4) | version u8 = 1 | keyId(20) | count u8 | (domain u8 | createdAtEpochMillis i64 | infoLen u16 | SessionInfo bytes) × count`.
 * Go의 JSON 캐시와 호환하지 않는다(D26). 순수 Kotlin.
 */
public object SessionCacheCodec {
    /** 형식 버전. */
    public const val VERSION: Int = 1

    /** 항목 하나(Go `CacheEntry`). */
    public class Entry(
        /** 도메인. */
        public val domain: Domain,
        /** 저장 시각(epoch millis, 벽시계). */
        public val createdAtEpochMillis: Long,
        sessionInfo: ByteArray,
    ) {
        private val info = sessionInfo.copyOf()

        /** protobuf 인코딩된 `SessionInfo`(사본). */
        public val sessionInfo: ByteArray get() = info.copyOf()
    }

    /**
     * 인코딩.
     * @throws IllegalArgumentException 항목이 255개를 넘거나 `SessionInfo`가 65535바이트를 넘으면 발생한다(프로그래밍 오류).
     */
    public fun encode(
        keyId: KeyId,
        entries: List<Entry>,
    ): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "at most $MAX_ENTRIES entries" }
        val buffer = Buffer()
        buffer.writeUtf8(MAGIC)
        buffer.writeByte(VERSION)
        buffer.write(keyId.toByteArray())
        buffer.writeByte(entries.size)
        for (entry in entries) {
            val info = entry.sessionInfo
            require(info.size <= MAX_INFO_LENGTH) { "session info too long" }
            buffer.writeByte(entry.domain.value)
            buffer.writeLong(entry.createdAtEpochMillis)
            buffer.writeShort(info.size)
            buffer.write(info)
        }
        return buffer.readByteArray()
    }

    /**
     * 디코딩. 매직·버전·[expectedKeyId] 불일치, 잘림, 꼬리 바이트면 null(캐시 무시 + 삭제 대상). 모르는 도메인 값의 항목은 건너뛴다.
     */
    public fun decode(
        bytes: ByteArray,
        expectedKeyId: KeyId,
    ): List<Entry>? {
        val buffer = Buffer().write(bytes)
        return try {
            if (buffer.readUtf8(MAGIC_LENGTH) != MAGIC) return null
            if (buffer.readByte().toInt() != VERSION) return null
            if (!buffer.readByteArray(KeyId.SIZE.toLong()).contentEquals(expectedKeyId.toByteArray())) return null
            val count = buffer.readByte().toInt() and BYTE_MASK
            val entries = ArrayList<Entry>(count)
            repeat(count) {
                val domainValue = buffer.readByte().toInt() and BYTE_MASK
                val createdAt = buffer.readLong()
                val length = buffer.readShort().toInt() and SHORT_MASK
                val info = buffer.readByteArray(length.toLong())
                Domain.fromValue(domainValue)?.let { entries += Entry(it, createdAt, info) }
            }
            if (!buffer.exhausted()) return null
            entries
        } catch (ignored: EOFException) {
            null
        }
    }
}
```

```kotlin
// domain/src/commonMain/kotlin/io/github/smallmiro/teslable/port/SessionCache.kt
// Ported from vehicle-command@a4b43c1 pkg/cache/cache.go (Apache-2.0) — SessionCache.Update/GetEntry as a port (own format v1, D26)
package io.github.smallmiro.teslable.port

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin

/**
 * 세션 캐시 포트(FR-017, SDD §7.1). VIN당 항목 하나(도메인별 `SessionInfo` 묶음), 클라이언트 키(`keyId`)에 종속.
 * 구현은 예외를 던지지 않는다: 읽기 실패·키 불일치·손상은 빈 목록(+삭제), 쓰기 실패는 로그로만(명령 결과에 영향 없음).
 * 벽시계는 구현 안에서만 쓰고 밖으로는 `age`로 나간다.
 */
public interface SessionCache {
    /** [keyId]로 만든 항목만 돌려준다. 다른 키의 캐시는 버리고 빈 목록. Go `GetEntry`. */
    public suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession>

    /** 덮어쓴다(빈 목록이면 사실상 삭제). Go `Update`. */
    public suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    )

    /** VIN의 캐시를 지운다. */
    public suspend fun clear(vin: Vin)
}
```

```kotlin
// adapter-storage/build.gradle.kts
plugins { id("teslable.kmp-library") }

kotlin {
    sourceSets {
        commonMain.dependencies { implementation(project(":domain")) }
        commonTest.dependencies { implementation(project(":testing")) }
    }
}
detekt { config.setFrom(files("$rootDir/config/detekt/detekt.yml", "$rootDir/config/detekt/detekt-adapter.yml")) }
```

```kotlin
// adapter-storage/src/commonMain/kotlin/io/github/smallmiro/teslable/storage/InMemorySessionCache.kt
package io.github.smallmiro.teslable.storage

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.SessionCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * 메모리 세션 캐시(SDD §2.6 공통 구현; 테스트·옵트아웃용). 값은 [SessionCacheCodec] v1 바이트로 보관해 파일/Keychain 구현(M3)과
 * 같은 경로를 탄다. 벽시계는 이 라이브러리에서 유일하게 여기서만 쓰며 [wallClockMillis]로 주입한다.
 */
public class InMemorySessionCache(
    private val wallClockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SessionCache {
    private val store = HashMap<String, ByteArray>()
    private val mutex = Mutex()

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> =
        mutex.withLock {
            val bytes = store[vin.value] ?: return@withLock emptyList()
            val entries = SessionCacheCodec.decode(bytes, keyId)
            if (entries == null) {
                store.remove(vin.value)
                return@withLock emptyList()
            }
            val now = wallClockMillis()
            entries.map { CachedSession(it.domain, it.sessionInfo, (now - it.createdAtEpochMillis).milliseconds) }
        }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        mutex.withLock {
            val now = wallClockMillis()
            store[vin.value] = SessionCacheCodec.encode(keyId, entries.map { SessionCacheCodec.Entry(it.domain, now, it.sessionInfo) })
        }
    }

    override suspend fun clear(vin: Vin) {
        mutex.withLock { store.remove(vin.value) }
    }
}
```

`kotlin.time.Clock`이 옵트인을 요구하면 파일 상단에 `@file:OptIn(kotlin.time.ExperimentalTime::class)`를 붙인다.

```kotlin
// testing/src/commonMain/kotlin/io/github/smallmiro/teslable/testing/RecordingSessionCache.kt
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.SessionCache
import kotlin.time.Duration.Companion.milliseconds

/**
 * 호출을 기록하는 테스트용 [SessionCache]. `:application` 테스트는 `:adapter-storage`를 볼 수 없어 따로 둔다(설계 구체화 12).
 * 벽시계는 [wallClockMillis]로 주입한다(기본 0). `runTest` 단일 스레드 전용.
 */
public class RecordingSessionCache(
    private val wallClockMillis: () -> Long = { 0L },
) : SessionCache {
    /** [store] 호출 하나. */
    public class Stored(
        /** VIN. */
        public val vin: Vin,
        /** 키. */
        public val keyId: KeyId,
        /** 저장된 항목. */
        public val entries: List<SessionSnapshot>,
    )

    private val encoded = HashMap<String, ByteArray>()
    private val storeCalls = mutableListOf<Stored>()
    private var loadCalls = 0

    /** 지금까지의 [store] 호출(사본). */
    public val stores: List<Stored> get() = storeCalls.toList()

    /** [load] 호출 수. */
    public val loads: Int get() = loadCalls

    /** 저장 시각을 직접 정해 항목을 심는다(미래 시각 등). */
    public fun seed(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionCacheCodec.Entry>,
    ) {
        encoded[vin.value] = SessionCacheCodec.encode(keyId, entries)
    }

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> {
        loadCalls++
        val bytes = encoded[vin.value] ?: return emptyList()
        val entries = SessionCacheCodec.decode(bytes, keyId)
        if (entries == null) {
            encoded.remove(vin.value)
            return emptyList()
        }
        val now = wallClockMillis()
        return entries.map { CachedSession(it.domain, it.sessionInfo, (now - it.createdAtEpochMillis).milliseconds) }
    }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        storeCalls += Stored(vin, keyId, entries)
        val now = wallClockMillis()
        encoded[vin.value] = SessionCacheCodec.encode(keyId, entries.map { SessionCacheCodec.Entry(it.domain, now, it.sessionInfo) })
    }

    override suspend fun clear(vin: Vin) {
        encoded.remove(vin.value)
    }
}
```

`Dispatcher.kt`에 추가(import `io.github.smallmiro.teslable.cache.CachedSession`, `io.github.smallmiro.teslable.cache.SessionSnapshot`):

```kotlin
        /** Go `Cache()`: 세션이 있는 도메인의 `SessionInfo`(clock_time = 지금)를 내보낸다. */
        public suspend fun exportSessions(): List<SessionSnapshot> =
            sessions.mapNotNull { (domain, session) -> session.export()?.let { SessionSnapshot(domain, it) } }

        /**
         * Go `LoadCache`: 항목마다 [SessionState.loadFromCache](즉시 준비 상태). 손상된 항목·개인키 없음은 WARN 로그 후 건너뛴다
         * (Go는 전체 실패 — 설계 구체화 6). 복원한 도메인 집합을 돌려준다.
         */
        public suspend fun loadSessions(entries: List<CachedSession>): Set<Domain> {
            val loaded = mutableSetOf<Domain>()
            for (entry in entries) {
                val session = sessions[entry.domain]
                if (session == null) {
                    logger.log(LogLevel.WARN, TAG) { "invalid cache: ${entry.domain}: no session (private key missing)" }
                    continue
                }
                when (val result = session.loadFromCache(entry.sessionInfo, entry.age)) {
                    is SignerResult.Ok -> {
                        logger.log(LogLevel.INFO, TAG) { "Session for ${entry.domain} loaded from cache" }
                        loaded += entry.domain
                    }
                    is SignerResult.Fault -> logger.log(LogLevel.WARN, TAG) { "invalid cache: ${entry.domain}: ${result.fault.name}: ${result.detail}" }
                }
            }
            return loaded
        }
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/cache/SessionCacheSync.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle (cache load), UpdateCachedSessions
package io.github.smallmiro.teslable.application.cache

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.SessionCache

/** [SessionCache] 포트와 [Dispatcher] 사이의 동기화(SDD §2.2 `SessionCacheSync`). 저장 시점: 핸드셰이크 완료·세션 갱신·해제(§7.1). */
public class SessionCacheSync(
    private val cache: SessionCache,
    private val vin: Vin,
    private val keyId: KeyId,
) {
    /** Go `NewVehicle`: `GetEntry(vin)` → `LoadCache`. 복원한 도메인. */
    public suspend fun load(dispatcher: Dispatcher): Set<Domain> = dispatcher.loadSessions(cache.load(vin, keyId))

    /** Go `UpdateCachedSessions`: `Cache()` → `Update`. 세션이 없으면 빈 목록을 저장한다(Go도 nil을 저장해 옛 캐시를 지운다). */
    public suspend fun store(dispatcher: Dispatcher) {
        cache.store(vin, keyId, dispatcher.exportSessions())
    }
}
```

`tools/ci/ported-files.txt`에 `CachedSession.kt`, `SessionCache.kt`, `SessionCacheSync.kt`를 추가한다. `adapter-storage`의 `Placeholder.kt`를 삭제한다.

- [ ] **Step 4: 통과 확인, API 덤프, 커밋**

Run: `./gradlew :domain:apiDump --console=plain`
Run: `./gradlew :adapter-storage:apiDump --console=plain`
Run: `./gradlew :application:apiDump --console=plain`
Run: `./gradlew :domain:jvmTest :adapter-storage:jvmTest :application:jvmTest :domain:check :adapter-storage:check :application:check :domain:iosSimulatorArm64Test :adapter-storage:iosSimulatorArm64Test :application:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS)

```bash
git add domain/src domain/api tools/ci/ported-files.txt
git commit -m "feat(domain): SessionCache port, KeyId, SessionSnapshot/CachedSession and the v1 binary codec from SDD §7.1

Refs: FR-017, ADR-0007
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add adapter-storage
git commit -m "feat(adapter-storage): InMemorySessionCache with injectable wall clock (age computed on load)

Refs: FR-017, ADR-0007
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add testing/src
git commit -m "test(testing): RecordingSessionCache for :application tests

Refs: NFR-003
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add application/src application/api
git commit -m "feat(application): Dispatcher exportSessions/loadSessions (Cache/LoadCache) and SessionCacheSync; skip corrupt entries

Refs: FR-017, FR-018
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: `SendWithRetry`(Go `Vehicle.Send`/`trySend`), `CommandTimeouts`(D29), `VehicleSession`(Go `NewVehicle`/`StartSession`/`Disconnect`)

참고 매뉴얼: `01-architecture.md` §4(명령 전체 경로), §7(재시도 정책 위치 표); `08-errors.md` §4(재시도 루프 표), §7("Couldn't verify success" — 자동 재전송 금지); `10-porting-guide.md` §8. Go 원본: `pkg/vehicle/vehicle.go`(`DefaultFlags` 22행, `NewVehicle` 77~99행, `Connect` 116행, `StartSession` 149~167행, `Disconnect` 174~179행, `getReceiver` 181~199행, `trySend` 201~214행, `Send` 236~257행), `vehicle_test.go`(`TestVehicleStartSessionFailed` 145행, `TestVehicleConnectionRetry` 164행, `TestVehicleConnectionTimeout` 183행, `TestVehicleSendError` 205행, `TestVehicleSendTimeout` 221행, `TestVehicleRetryTimeout` 242행, `TestVehicleNoResponseTimeout` 265행, `TestVehicleRetryFail` 281행). 설계: `{{SDD_FILE}}` §2.2(`SendWithRetry`, `VehicleSession`), §3.3, §3.4, §5(타임아웃·취소), §6, ADR-0010, FR-100, FR-101, FR-102, NFR-007, NFR-012, Review Focus 3.

**Files:**
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/CommandTimeouts.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/SendWithRetry.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSession.kt`
- Modify: `tools/ci/ported-files.txt`, `application/api/*`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vehicle/SendWithRetryTest.kt`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSessionTest.kt`

**Interfaces:**
- Consumes: Task 6 `Dispatcher`(`send`, `retryInterval`, `start`, `close`, `pendingCount`), Task 7 `HandshakeFlow`, Task 8 `SessionCacheSync`, `Results.kt`(`errorOrNull`, `valueOr`), M1 `ResponseClassifier`, `shouldRetry()`, `toResult()`.
- Produces:
  - `public data class CommandTimeouts(commandTimeout = 5.seconds, commandLifetime = 5.seconds, handshakeTimeout = 20.seconds)` — M3 `TeslaBleConfig`가 채운다.
  - `public class SendWithRetry(dispatcher, timeouts = CommandTimeouts()) { val commandTimeout: Duration; suspend fun send(domain, payload: ByteArray, auth: AuthMethod, flags: Int = DEFAULT_FLAGS, timeout: Duration = commandTimeout): VehicleResult<RoutableMessage>; companion DEFAULT_FLAGS = 2 }` — 응답 하나(Infotainment). 시간 초과: 전송 전 `Failure(Timeout(false))`, 응답 대기 중 `Uncertain(Timeout(true))`, 재시도 대기 중(`delay`) `Failure(Timeout(false))`(Go `Vehicle.Send`의 `ctx.Err()`).
  - `public class VehicleSession(dispatcher, timeouts = CommandTimeouts(), cacheSync: SessionCacheSync? = null) { val handshake: HandshakeFlow; val send: SendWithRetry; val retryInterval; suspend fun connect(): Set<Domain>; suspend fun startSession(domains = ALL_DOMAINS, timeout = timeouts.handshakeTimeout): VehicleResult<Unit>; suspend fun disconnect() }`. Task 10이 `vcsec`/`infotainment`를 더한다. M3 `Vehicle` 구현이 이 클래스를 감싼다.

- [ ] **Step 1: 실패 테스트 작성**

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vehicle/SendWithRetryTest.kt
package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.OperationStatus_E
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SendWithRetryTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val payload = "payload".encodeToByteArray()

    private val retriableFaults =
        listOf(
            MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
            MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
            MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL,
            MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
            MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
            MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG,
        )

    @Test
    fun returnsTerminalFailureAfterTransientSendError() =
        // vehicle_test.go TestVehicleSendError: 일시 전송 오류 뒤 치명 오류 → 치명 오류 반환
        runTest {
            val h = dispatcherHarness()
            val send = SendWithRetry(h.dispatcher)
            h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("synergize"))
            h.transport.enqueueSendError(VehicleError.TransportError.Disconnected)
            val result = assertIs<VehicleResult.Failure>(send.send(vcsec, payload, AuthMethod.NONE))
            assertEquals(VehicleError.TransportError.Disconnected, result.error)
            assertEquals(2, h.transport.sent.size)
        }

    @Test
    fun timesOutBeforeSendWhenTransportKeepsFailingTransiently() =
        // vehicle_test.go TestVehicleSendTimeout: 전송 전 만료 → Failure(Timeout(afterSend = false)), temporary, !mayHaveSucceeded
        runTest {
            val h = dispatcherHarness()
            repeat(100) { h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("libations")) }
            val result = assertIs<VehicleResult.Failure>(SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 1.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertTrue(result.error.temporary)
            assertFalse(result.error.mayHaveSucceeded)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun retriesWhileVehicleAnswersBusyThenTimesOutBetweenAttempts() =
        // vehicle_test.go TestVehicleRetryTimeout: WAIT+BUSY 고정 응답 → 재시도 반복, 재시도 대기 중 만료 → Go ctx.Err() = 부작용 없음.
        // retryInterval 3ms, timeout 10ms: 시도는 t=0,3,6,9ms에 즉시 응답을 받고 만료(t=10ms)는 항상 delay 중에 걸린다(결정적).
        runTest {
            val h = dispatcherHarness(retryInterval = 3.milliseconds)
            val busy = FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY, operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT)
            h.fake.script(vcsec, *Array(100) { listOf(busy) })
            val result = assertIs<VehicleResult.Failure>(SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 10.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertEquals(4, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun noResponseTimesOutAsUncertainAndIsNeverResent() =
        // vehicle_test.go TestVehicleNoResponseTimeout + NFR-007: 응답 없음 → Uncertain(Timeout(afterSend = true)), 자동 재전송 금지
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList())
            val result = assertIs<VehicleResult.Uncertain>(SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 1.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertTrue(result.error.mayHaveSucceeded)
            assertEquals(1, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun retriesEveryRetriableFaultThenReturnsTheTerminalOne() =
        // vehicle_test.go TestVehicleRetryFail: retriableErrors 8개 뒤 INSUFFICIENT_PRIVILEGES → 5초 안에 그 오류로 끝난다
        runTest {
            val h = dispatcherHarness()
            val scripts = retriableFaults.map { listOf(FakeVehicle.ScriptedReply(fault = it)) } + listOf(listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES)))
            h.fake.script(vcsec, *scripts.toTypedArray())
            val start = testTimeSource.markNow()
            val result = assertIs<VehicleResult.Failure>(SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 5.seconds))
            assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES), result.error)
            assertEquals(9, h.transport.sent.size)
            assertEquals(h.dispatcher.retryInterval * 8, start.elapsedNow())
        }

    @Test
    fun reauthorizesEachRetryWithFreshCounterAndNonce() =
        // FR-101: 재시도는 새 counter·nonce·expires_at으로 재인가한다(Go Vehicle.Send는 trySend마다 getReceiver → Dispatcher.Send → Encrypt)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(vcsec))
            h.fake.script(vcsec, listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL)), listOf(FakeVehicle.vcsecEmpty()))
            val result = SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.GCM)
            assertIs<VehicleResult.Success<*>>(result)
            val authenticated = h.fake.received.filter { it.signature_data?.AES_GCM_Personalized_data != null }
            assertEquals(2, authenticated.size)
            val first = assertNotNull(authenticated[0].signature_data?.AES_GCM_Personalized_data)
            val second = assertNotNull(authenticated[1].signature_data?.AES_GCM_Personalized_data)
            assertEquals(first.counter + 1, second.counter)
            assertFalse(first.nonce == second.nonce)
            assertTrue(second.expires_at >= first.expires_at)
            assertEquals(SendWithRetry.DEFAULT_FLAGS, authenticated[1].flags) // FR-010: FLAG_ENCRYPT_RESPONSE 항상
        }

    @Test
    fun cancelledCommandReleasesPendingRequest() =
        // Review Focus 3 / ADR-0010: 취소는 CancellationException으로 전파되고 PendingRequest는 finally에서 풀린다
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList())
            val job = launch { SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 10.seconds) }
            runCurrent()
            assertEquals(1, h.dispatcher.pendingCount())
            job.cancel()
            runCurrent()
            assertTrue(job.isCancelled)
            assertEquals(0, h.dispatcher.pendingCount())
            val late = io.github.smallmiro.teslable.application.dispatcher.replyTo(h.fake.received.last(), "late".encodeToByteArray())
            h.transport.deliver(io.github.smallmiro.teslable.application.dispatcher.encode(late))
            runCurrent()
            assertTrue(h.logger.contains("Dropping message without registered handler"))
        }
}
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSessionTest.kt
package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.application.cache.SessionCacheSync
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingSessionCache
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class VehicleSessionTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    @Test
    fun startSessionReturnsFatalHandshakeErrorWithoutRetry() =
        // vehicle_test.go TestVehicleStartSessionFailed
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            session.connect()
            val result = assertIs<VehicleResult.Failure>(session.startSession(timeout = 1.seconds))
            assertEquals(VehicleError.KeyNotPaired, result.error)
        }

    @Test
    fun startSessionRetriesTransientHandshakeError() =
        // vehicle_test.go TestVehicleConnectionRetry: BUSY(일시) 뒤 성공
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_BUSY)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            assertTrue(h.fake.sessionInfoRequests in 3..4, "got ${h.fake.sessionInfoRequests}")
            assertIs<VehicleResult.Success<*>>(session.send.send(vcsec, "x".encodeToByteArray(), AuthMethod.GCM))
        }

    @Test
    fun startSessionTimesOutWhileErrorsStayTransient() =
        // vehicle_test.go TestVehicleConnectionTimeout → Failure(Timeout(afterSend = false)) (사용자 답 b: 전용 타입 없음)
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, *Array(10) { MessageFault_E.MESSAGEFAULT_ERROR_BUSY })
            session.connect()
            val result = assertIs<VehicleResult.Failure>(session.startSession(timeout = 5.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun connectRestoresCacheAndDisconnectStoresIt() =
        // vehicle.go NewVehicle(LoadCache) / Disconnect + SDD §7.1 저장 시점(핸드셰이크 완료, 해제)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            val first = dispatcherHarness(fake = fake, start = false)
            val session1 = VehicleSession(first.dispatcher, cacheSync = sync)
            assertTrue(session1.connect().isEmpty())
            assertIs<VehicleResult.Success<Unit>>(session1.startSession())
            assertEquals(1, cache.stores.size)
            session1.disconnect()
            assertEquals(2, cache.stores.size)
            assertFalse(first.dispatcher.isListening)
            val second = dispatcherHarness(fake = fake, start = false)
            val session2 = VehicleSession(second.dispatcher, cacheSync = sync)
            assertEquals(setOf(vcsec, Domain.DOMAIN_INFOTAINMENT), session2.connect())
            assertIs<VehicleResult.Success<Unit>>(session2.startSession()) // 이미 준비됨 → 요청 없음
            assertEquals(2, fake.sessionInfoRequests)
            assertIs<VehicleResult.Success<*>>(session2.send.send(vcsec, "x".encodeToByteArray(), AuthMethod.GCM))
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :application:jvmTest --tests '*SendWithRetryTest*' --console=plain`
Expected: 컴파일 실패 (`SendWithRetry`, `VehicleSession`, `CommandTimeouts` 없음)

- [ ] **Step 3: 구현**

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/CommandTimeouts.kt
package io.github.smallmiro.teslable.application.vehicle

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** D29/ADR-0010의 시간 값. M3 `TeslaBleConfig`가 채운다. Go `tesla-control`의 `-command-timeout 5s`, `defaultExpiration 5s`, `-connect-timeout 20s`. */
public data class CommandTimeouts(
    /** 명령 하나의 전체 시간(전송 재시도·응답 대기 포함). */
    val commandTimeout: Duration = 5.seconds,
    /** 인가 명령의 `expires_at` 수명(Go `defaultExpiration`). */
    val commandLifetime: Duration = 5.seconds,
    /** 핸드셰이크 전체 시간. */
    val handshakeTimeout: Duration = 20.seconds,
)
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/SendWithRetry.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — DefaultFlags, getReceiver, trySend, Send
package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.dispatcher.errorOrNull
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration

/**
 * Go `Vehicle.Send`/`trySend`: 응답 하나를 기다리는 명령(Infotainment, 세션정보 조회). `shouldRetry()` 오류(BUSY, INVALID_SIGNATURE,
 * INCORRECT_EPOCH, TIME_EXPIRED, `Busy`, 일시 전송 오류 …)면 [Dispatcher.retryInterval] 뒤 **새 counter·nonce·expires_at으로 재인가**해
 * 다시 보낸다(FR-101; `Dispatcher.send`가 매번 `Signer.encrypt`). `mayHaveSucceeded` 오류는 재시도하지 않는다(NFR-007).
 *
 * 시간 초과(D29): 전송 전·재시도 대기 중이면 `Failure(Timeout(afterSend = false))`(Go `Dispatcher.Send`·`Vehicle.Send`의 ctx 분기),
 * 응답 대기 중이면 `Uncertain(Timeout(afterSend = true))`(Go `trySend`의 `PossibleSuccess: true`).
 */
public class SendWithRetry(
    private val dispatcher: Dispatcher,
    private val timeouts: CommandTimeouts = CommandTimeouts(),
) {
    /** 기본 명령 시간(D29). */
    public val commandTimeout: Duration get() = timeouts.commandTimeout

    /**
     * 페이로드를 [domain]으로 보내고 응답 하나를 돌려준다. 프로토콜 계층 오류([ResponseClassifier.protocolError])는 값으로.
     * 외부 취소는 `CancellationException`으로 전파되고 `PendingRequest`는 `use`에서 풀린다.
     */
    public suspend fun send(
        domain: Domain,
        payload: ByteArray,
        auth: AuthMethod,
        flags: Int = DEFAULT_FLAGS,
        timeout: Duration = commandTimeout,
    ): VehicleResult<RoutableMessage> {
        var awaitingResponse = false
        val result = withTimeoutOrNull(timeout) { attemptUntilTerminal(domain, payload, auth, flags) { awaitingResponse = it } }
        return result ?: VehicleError.Timeout(afterSend = awaitingResponse).toResult()
    }

    /** Go `Send`의 루프. */
    private suspend fun attemptUntilTerminal(
        domain: Domain,
        payload: ByteArray,
        auth: AuthMethod,
        flags: Int,
        awaiting: (Boolean) -> Unit,
    ): VehicleResult<RoutableMessage> {
        while (true) {
            val attempt = trySend(domain, payload, auth, flags, awaiting)
            val error = attempt.errorOrNull() ?: return attempt
            if (!error.shouldRetry()) return attempt
            delay(dispatcher.retryInterval)
        }
    }

    /** Go `trySend` + `getReceiver`. [awaiting]은 응답을 기다리는 동안만 true(취소되면 true로 남아 `Uncertain`이 된다). */
    private suspend fun trySend(
        domain: Domain,
        payload: ByteArray,
        auth: AuthMethod,
        flags: Int,
        awaiting: (Boolean) -> Unit,
    ): VehicleResult<RoutableMessage> {
        awaiting(false)
        val message =
            RoutableMessage(
                to_destination = Destination(domain = domain),
                protobuf_message_as_bytes = payload.toByteString(),
                flags = flags,
            )
        val pending = dispatcher.send(message, auth, timeouts.commandLifetime).valueOr { return it.toResult() }
        awaiting(true)
        val response = pending.use { it.receive() }
        awaiting(false)
        ResponseClassifier.protocolError(response)?.let { return it.toResult() }
        return VehicleResult.Success(response)
    }

    /** 상수. */
    public companion object {
        /** Go `DefaultFlags = 1 << FLAG_ENCRYPT_RESPONSE`: 응답 암호화 요청(FR-010, 항상 설정). */
        public const val DEFAULT_FLAGS: Int = 2
    }
}
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSession.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle, Connect, StartSession, Disconnect
package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.cache.SessionCacheSync
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.errorOrNull
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Go `Vehicle`의 세션 부분: 연결(캐시 복원 + 수신 시작), 핸드셰이크 재시도, 해제(캐시 저장 + 세션 소거). M3 `Vehicle` 구현이 감싼다.
 * 명령은 [send](단일 응답), Task 10의 `vcsec`/`infotainment`로 보낸다.
 */
public class VehicleSession(
    private val dispatcher: Dispatcher,
    private val timeouts: CommandTimeouts = CommandTimeouts(),
    private val cacheSync: SessionCacheSync? = null,
) {
    /** Go `StartSessions` 계층. */
    public val handshake: HandshakeFlow = HandshakeFlow(dispatcher)

    /** Go `Vehicle.Send`. */
    public val send: SendWithRetry = SendWithRetry(dispatcher, timeouts)

    /** 전송 계층의 재전송 간격. */
    public val retryInterval: Duration get() = dispatcher.retryInterval

    /** Go `NewVehicle`(캐시 복원) + `Connect`(수신 시작). 캐시로 준비된 도메인을 돌려준다. */
    public suspend fun connect(): Set<Domain> {
        val restored = cacheSync?.load(dispatcher) ?: emptySet()
        dispatcher.start()
        return restored
    }

    /**
     * Go `Vehicle.StartSession`: `shouldRetry()` 오류면 [retryInterval] 뒤 다시 핸드셰이크. 시간 초과는 `Failure(Timeout(afterSend = false))`
     * (세션이 만들어지지 않았고 부작용이 없다). 성공하면 세션 캐시에 저장한다(SDD §7.1).
     */
    public suspend fun startSession(
        domains: Set<Domain> = Dispatcher.ALL_DOMAINS,
        timeout: Duration = timeouts.handshakeTimeout,
    ): VehicleResult<Unit> {
        val result =
            withTimeoutOrNull(timeout) { handshakeUntilTerminal(domains) }
                ?: return VehicleResult.Failure(VehicleError.Timeout(afterSend = false))
        if (result is VehicleResult.Success) cacheSync?.store(dispatcher)
        return result
    }

    /** Go `Disconnect`: 캐시 저장 → 수신 중단 → 세션 키 소거 → 전송 닫기. */
    public suspend fun disconnect() {
        cacheSync?.store(dispatcher)
        dispatcher.close()
    }

    private suspend fun handshakeUntilTerminal(domains: Set<Domain>): VehicleResult<Unit> {
        while (true) {
            val result = handshake.startSessions(domains)
            val error = result.errorOrNull() ?: return result
            if (!error.shouldRetry()) return result
            delay(dispatcher.retryInterval)
        }
    }
}
```

`tools/ci/ported-files.txt`에 `SendWithRetry.kt`, `VehicleSession.kt`를 추가한다.

- [ ] **Step 4: 통과 확인, API 덤프, 커밋**

Run: `./gradlew :application:apiDump --console=plain`
Run: `./gradlew :application:jvmTest :application:check :application:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS)

```bash
git add application/src application/api tools/ci/ported-files.txt
git commit -m "feat(application): SendWithRetry (Vehicle.Send/trySend, D29 timeouts), CommandTimeouts, VehicleSession (connect/startSession/disconnect)

Refs: FR-100, FR-101, FR-102, NFR-007, NFR-012, ADR-0010
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: VCSEC 응답 해석·`readUntil`·직렬화(`VcsecResponses`, `VcsecCommands`), Infotainment 응답 해석, 모르는 enum 값 처리(인계 항목 3)

참고 매뉴얼: `03-protocol.md` §10(Infotainment 응답), §11(VCSEC 종료 규칙); `08-errors.md` §3.5(VCSEC 해석 규칙 6단계), §3.6(Infotainment — `CarServer.OperationStatus_E`는 값이 다르다: OK=0, ERROR=1), §3.7; `00-agent-guide.md` §3.1-2(VCSEC 직렬화). Go 원본: `pkg/vehicle/vcsec.go`(`unmarshalVCSECResponse` 23~62행, `isTerminalTest` 64행, `readUntil` 67~82행, `getVCSECResult` 85~105행, `isWhitelistOperationComplete` 132~144행, `executeRKEAction`의 `done` 175~180행, `getVCSECInfo`의 `done` 128행), `infotainment.go`(`getCarServerResponse` 19~45행), `vcsec_test.go`(`TestNominalVSCECError` 79행, `TestGibberishVCSECResponse` 114행, `TestWhitelistOperationError` 214행), `pkg/protocol/error.go`(`KeychainError` 88~100행). 설계: `{{SDD_FILE}}` §2.2(`VcsecCommands`, `InfotainmentCommands`), §3.3, §5(VCSEC 직렬화), §6, FR-048, FR-049, 설계 구체화 11.

**Files:**
- Modify: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/VehicleError.kt` (`UnknownKeychainCode` 추가)
- Modify: `domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/UnknownFields.kt` (`internal` → `@InternalTeslableApi public`; `:application`이 쓴다)
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecResponses.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecCommands.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/infotainment/InfotainmentResponses.kt`
- Create: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/infotainment/InfotainmentCommands.kt`
- Modify: `application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vehicle/VehicleSession.kt` (`vcsec`, `infotainment` 프로퍼티)
- Modify: `tools/ci/ported-files.txt`, `domain/api/*`, `application/api/*`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecResponsesTest.kt`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecCommandsTest.kt`
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/infotainment/InfotainmentResponsesTest.kt`
- Test: `domain/src/commonTest/kotlin/io/github/smallmiro/teslable/model/VehicleErrorTest.kt` (`UnknownKeychainCode` 1개 추가)

**Interfaces:**
- Consumes: Task 3 `ByteString.unknownVarint`, Task 6 `Dispatcher`, Task 9 `SendWithRetry`/`CommandTimeouts`/`VehicleSession`, M1 `ResponseClassifier`, `VehicleError`, Wire `FromVCSECMessage`/`CommandStatus`/`WhitelistOperation_status`/`Response`/`ActionStatus`/`Action`.
- Produces:
  - `VehicleError.UnknownKeychainCode(rawCode: Int)` — message `"keychain operation failed: unrecognized code N"`, temporary=false, mayHaveSucceeded=false.
  - `public object VcsecResponses { fun interface TerminalTest { fun check(message: FromVCSECMessage): TerminalCheck }; sealed interface TerminalCheck { Continue; Done; Fail(error) }; fun interpret(message: RoutableMessage): VehicleResult<FromVCSECMessage>; suspend fun readUntil(pending: PendingRequest, done: TerminalTest): VehicleResult<FromVCSECMessage>; val FIRST_MESSAGE; val COMMAND_STATUS_ABSENT; val WHITELIST_OPERATION_COMPLETE }`.
  - `public class VcsecCommands(dispatcher, timeouts = CommandTimeouts()) { suspend fun execute(payload: ByteArray, auth: AuthMethod, done: TerminalTest, flags = DEFAULT_FLAGS, timeout = timeouts.commandTimeout): VehicleResult<FromVCSECMessage> }` — Go `getVCSECResult` + VIN 수준 직렬화 `Mutex`(FR-049; 시간 제한은 락 대기를 포함한다).
  - `public object InfotainmentResponses { fun interpret(payload: ByteArray?): VehicleResult<Response> }`, `public class InfotainmentCommands(send: SendWithRetry) { suspend fun execute(action: Action, timeout = send.commandTimeout): VehicleResult<Response> }`.
  - `VehicleSession.vcsec: VcsecCommands`, `VehicleSession.infotainment: InfotainmentCommands`. M4/M5가 명령 빌더(`UnsignedMessage`, `Action`)를 더한다.
  - 모르는 enum 값(설계 구체화 11): VCSEC `commandStatus.operationStatus` 모르는 값 → 통과(Go switch에 default 없음); `whitelistOperationInformation` 모르는 값 → `UnknownKeychainCode(raw)`; Infotainment `actionStatus.result` 모르는 값 → 성공(Go `== ERROR`만 검사); `nominalError.genericError` 모르는 값 → `VcsecRejected(GENERICERROR_NONE)`(실패 보존, 코드는 M4 L58).

- [ ] **Step 1: 실패 테스트 작성**

`VehicleErrorTest`에 추가:

```kotlin
    @Test
    fun unknownKeychainCodeCarriesRawCodeLikeGoKeychainError() {
        // error.go KeychainError.Error(): 등록되지 않은 코드도 Code를 보존한다. 설계 구체화 11.
        val error = VehicleError.UnknownKeychainCode(99)
        assertEquals("keychain operation failed: unrecognized code 99", error.message)
        assertFalse(error.temporary)
        assertFalse(error.mayHaveSucceeded)
        assertIs<VehicleResult.Failure>(error.toResult())
    }
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecResponsesTest.kt
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.errors.NominalError
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.SignedMessage_status
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.WhitelistOperation_status
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VcsecResponsesTest {
    private fun message(payload: ByteArray?): RoutableMessage = RoutableMessage(protobuf_message_as_bytes = payload?.toByteString())

    private fun message(from: FromVCSECMessage): RoutableMessage = message(from.encode())

    private fun commandStatus(
        status: OperationStatus_E,
        whitelist: WhitelistOperation_information_E? = null,
        signed: Boolean = false,
    ): FromVCSECMessage =
        FromVCSECMessage(
            commandStatus =
                CommandStatus(
                    operationStatus = status,
                    signedMessageStatus = if (signed) SignedMessage_status(counter = 1337) else null,
                    whitelistOperationStatus = whitelist?.let { WhitelistOperation_status(whitelistOperationInformation = it) },
                ),
        )

    @Test
    fun emptyPayloadIsSuccessAndOtherPayloadKindsAreUncertain() {
        // vcsec.go unmarshalVCSECResponse: Payload nil → 빈 FromVCSECMessage; 다른 oneof → "payload missing" (MHS = true)
        assertEquals(FromVCSECMessage(), assertIs<VehicleResult.Success<FromVCSECMessage>>(VcsecResponses.interpret(message(null))).value)
        val withSessionInfo = RoutableMessage(session_info = byteArrayOf(0x08, 0x01).toByteString())
        val uncertain = assertIs<VehicleResult.Uncertain>(VcsecResponses.interpret(withSessionInfo))
        assertEquals(VehicleError.BadResponse("payload missing from vehicle response", mayHaveSucceeded = true), uncertain.error)
    }

    @Test
    fun gibberishPayloadIsUncertainBadResponse() {
        // vcsec.go: proto.Unmarshal 실패 → CommandError{ErrBadResponse, PossibleSuccess: true}
        val result = assertIs<VehicleResult.Uncertain>(VcsecResponses.interpret(message(byteArrayOf(0xFF.toByte()))))
        assertIs<VehicleError.BadResponse>(result.error)
        assertTrue(result.error.mayHaveSucceeded)
    }

    @Test
    fun protocolFaultWinsOverPayload() {
        val faulted = message(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK)).copy(signedMessageStatus = MessageStatus(signed_message_fault = MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES))
        assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES), assertIs<VehicleResult.Failure>(VcsecResponses.interpret(faulted)).error)
    }

    @Test
    fun nominalErrorIsVcsecRejected() {
        val from = FromVCSECMessage(nominalError = NominalError(genericError = GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK))
        val result = assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(from)))
        assertEquals(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK), result.error)
    }

    @Test
    fun commandStatusRulesMatchGo() {
        // 08-errors.md §3.5 5단계
        assertIs<VehicleResult.Success<FromVCSECMessage>>(VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK))))
        assertEquals(VehicleError.Busy, assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_WAIT)))).error)
        val full = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
        assertEquals(VehicleError.KeychainRejected(full), assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, full)))).error)
        assertEquals(VehicleError.UnknownResponse, assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR)))).error)
        assertIs<VehicleResult.Success<FromVCSECMessage>>(VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, signed = true)))) // 레거시 signedMessageStatus는 통과
        val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE
        assertEquals(VehicleError.UnknownResponse, assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, none)))).error)
    }

    @Test
    fun passesThroughUnknownVcsecOperationStatusLikeGo() {
        // 인계 항목 3 / 설계 구체화 11: Go switch에 default가 없어 모르는 operationStatus(7)는 통과한다. Wire는 기본값 OK + unknownFields.
        val raw = byteArrayOf(0x22, 0x02, 0x08, 0x07) // FromVCSECMessage.commandStatus(4, LEN 2){ operationStatus(1) = 7 }
        val result = assertIs<VehicleResult.Success<FromVCSECMessage>>(VcsecResponses.interpret(message(raw)))
        assertEquals(1, result.value.commandStatus?.unknownFields?.size?.let { if (it > 0) 1 else 0 })
    }

    @Test
    fun unknownWhitelistInformationCodeIsAFailureWithTheRawCode() {
        // Go: code != NONE → KeychainError{Code: 99}. Wire는 NONE으로 바꾸므로 unknownFields에서 되찾는다.
        val raw = byteArrayOf(0x22, 0x06, 0x08, 0x02, 0x1a, 0x02, 0x08, 0x63) // commandStatus{ operationStatus = ERROR(2), whitelistOperationStatus(3){ information(1) = 99 } }
        val result = assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(raw)))
        assertEquals(VehicleError.UnknownKeychainCode(99), result.error)
    }

    @Test
    fun unknownNominalErrorCodeStillFails() {
        // nominalError 존재 자체가 실패(Go GetNominalError() != nil). 코드는 M4(L58)에서 보존 여부 재검토.
        val raw = byteArrayOf(0xF2.toByte(), 0x02, 0x02, 0x08, 0x63) // FromVCSECMessage.nominalError(46, LEN 2){ genericError(1) = 99 }
        val result = assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(raw)))
        assertEquals(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_NONE), result.error)
    }

    @Test
    fun terminalTestsMatchGo() {
        // vcsec.go: 정보 요청은 첫 메시지, RKE/closure는 commandStatus == nil, whitelist는 whitelistOperationStatus != nil
        assertEquals(VcsecResponses.TerminalCheck.Done, VcsecResponses.FIRST_MESSAGE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK)))
        assertEquals(VcsecResponses.TerminalCheck.Continue, VcsecResponses.COMMAND_STATUS_ABSENT.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK, signed = true)))
        assertEquals(VcsecResponses.TerminalCheck.Done, VcsecResponses.COMMAND_STATUS_ABSENT.check(FromVCSECMessage()))
        assertEquals(VcsecResponses.TerminalCheck.Continue, VcsecResponses.WHITELIST_OPERATION_COMPLETE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK, signed = true)))
        val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE
        assertEquals(VcsecResponses.TerminalCheck.Done, VcsecResponses.WHITELIST_OPERATION_COMPLETE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK, none)))
        val slotsFull = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_KEYFOB_SLOTS_FULL
        val fail = assertIs<VcsecResponses.TerminalCheck.Fail>(VcsecResponses.WHITELIST_OPERATION_COMPLETE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, slotsFull)))
        assertEquals(VehicleError.KeychainRejected(slotsFull), fail.error)
    }
}
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/infotainment/InfotainmentResponsesTest.kt
package io.github.smallmiro.teslable.application.infotainment

import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.ResultReason
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InfotainmentResponsesTest {
    @Test
    fun okResultAndMissingPayloadAreSuccess() {
        // infotainment.go getCarServerResponse: ERROR가 아니면 성공; nil payload는 빈 Response
        val ok = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_OK))
        assertEquals(ok, assertIs<VehicleResult.Success<Response>>(InfotainmentResponses.interpret(ok.encode())).value)
        assertEquals(Response(), assertIs<VehicleResult.Success<Response>>(InfotainmentResponses.interpret(null)).value)
    }

    @Test
    fun errorResultIsInfotainmentRejectedWithReasonOrUnspecified() {
        val withReason = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_ERROR, result_reason = ResultReason(plain_text = "car is in drive")))
        assertEquals(VehicleError.InfotainmentRejected("car is in drive"), assertIs<VehicleResult.Failure>(InfotainmentResponses.interpret(withReason.encode())).error)
        val withoutReason = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_ERROR))
        assertEquals(VehicleError.InfotainmentRejected("unspecified error"), assertIs<VehicleResult.Failure>(InfotainmentResponses.interpret(withoutReason.encode())).error)
        val emptyReason = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_ERROR, result_reason = ResultReason(plain_text = "")))
        assertEquals(VehicleError.InfotainmentRejected("unspecified error"), assertIs<VehicleResult.Failure>(InfotainmentResponses.interpret(emptyReason.encode())).error)
    }

    @Test
    fun undecodablePayloadIsUncertainBadResponse() {
        // Go: CommandError{"unable to parse vehicle response", PossibleSuccess: true}
        val result = assertIs<VehicleResult.Uncertain>(InfotainmentResponses.interpret(byteArrayOf(0xFF.toByte())))
        assertIs<VehicleError.BadResponse>(result.error)
        assertTrue(result.error.mayHaveSucceeded)
    }

    @Test
    fun passesThroughUnknownActionResultLikeGo() {
        // 인계 항목 3 / 설계 구체화 11: Go는 result == ERROR만 검사하므로 모르는 값(5)은 성공이다
        val raw = byteArrayOf(0x0a, 0x02, 0x08, 0x05) // Response.actionStatus(1, LEN 2){ result(1) = 5 }
        assertIs<VehicleResult.Success<Response>>(InfotainmentResponses.interpret(raw))
    }
}
```

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecCommandsTest.kt
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Ping
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VcsecCommandsTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val lock = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_LOCK).encode() // vcsec.go executeRKEAction payload (M4가 빌더로 만든다)
    private val addKey = "whitelist operation".encodeToByteArray() // FakeVehicle은 payload를 해석하지 않으므로 임의 바이트로 충분하다

    private suspend fun connected(h: io.github.smallmiro.teslable.application.dispatcher.DispatcherHarness): VehicleSession {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
        return session
    }

    @Test
    fun nominalErrorFailsWhitelistAndRkeCommands() =
        // vcsec_test.go TestNominalVSCECError: AddKey, RemoveKey, Lock 모두 NominalVCSECError(VEHICLE_NOT_IN_PARK), !MHS, !Temporary
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val nominal = listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK))
            h.fake.script(vcsec, nominal, nominal, nominal)
            val expected = VehicleError.VcsecRejected(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK)
            for (done in listOf(VcsecResponses.WHITELIST_OPERATION_COMPLETE, VcsecResponses.WHITELIST_OPERATION_COMPLETE, VcsecResponses.COMMAND_STATUS_ABSENT)) {
                val result = assertIs<VehicleResult.Failure>(session.vcsec.execute(if (done === VcsecResponses.COMMAND_STATUS_ABSENT) lock else addKey, AuthMethod.GCM, done))
                assertEquals(expected, result.error)
                assertFalse(result.error.mayHaveSucceeded)
                assertFalse(result.error.temporary)
            }
        }

    @Test
    fun gibberishResponseIsUncertainBadResponse() =
        // vcsec_test.go TestGibberishVCSECResponse: errors.Is(err, ErrBadResponse)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecGibberish()), listOf(FakeVehicle.vcsecGibberish()))
            for (done in listOf(VcsecResponses.WHITELIST_OPERATION_COMPLETE, VcsecResponses.COMMAND_STATUS_ABSENT)) {
                val result = assertIs<VehicleResult.Uncertain>(session.vcsec.execute(lock, AuthMethod.GCM, done))
                assertIs<VehicleError.BadResponse>(result.error)
            }
        }

    @Test
    fun whitelistOperationRetriesBusyReadsIntermediateThenReportsKeychainError() =
        // vcsec_test.go TestWhitelistOperationError: WAIT, WAIT(재시도), [authSuccess(중간), whitelistStatus(WHITELIST_FULL)] → KeychainError
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val full = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
            h.fake.script(
                vcsec,
                listOf(FakeVehicle.vcsecBusy()),
                listOf(FakeVehicle.vcsecBusy()),
                listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecWhitelistStatus(full)),
            )
            val result = assertIs<VehicleResult.Failure>(session.vcsec.execute(addKey, AuthMethod.GCM, VcsecResponses.WHITELIST_OPERATION_COMPLETE, timeout = 5.seconds))
            assertEquals(VehicleError.KeychainRejected(full), result.error)
            assertEquals(3, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun rkeSucceedsOnEmptyMessageAfterIntermediateCommandStatus() =
        // FR-048: RKE는 commandStatus 없는 메시지가 최종. 한 요청에 응답 두 개(중간 + 최종)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecEmpty()))
            val result = assertIs<VehicleResult.Success<com.tesla.generated.vcsec.FromVCSECMessage>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
            assertEquals(null, result.value.commandStatus)
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun serializesVcsecCommandsOnOneConnection() =
        // FR-049 / SDD §5: 두 번째 VCSEC 명령은 첫 번째가 끝날(응답 유실 → 시간 초과) 때까지 전송되지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, emptyList(), listOf(FakeVehicle.vcsecEmpty()))
            val first = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 100.milliseconds) }
            val second = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            advanceTimeBy(50)
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
            assertFalse(second.isCompleted)
            assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(first.await()).error)
            assertIs<VehicleResult.Success<*>>(second.await())
            assertEquals(2, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun timeoutWhileWaitingForTheVcsecLockIsFailureNotUncertain() =
        // D29: 락 대기 중 만료 = 전송 전 → Failure(Timeout(afterSend = false))
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, emptyList())
            val blocker = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            runCurrent()
            val waiter = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 10.milliseconds)
            assertEquals(VehicleError.Timeout(afterSend = false), assertIs<VehicleResult.Failure>(waiter).error)
            assertTrue(blocker.await() is VehicleResult.Uncertain)
        }

    @Test
    fun infotainmentPingRoundTripsAndErrorsCarryTheReason() =
        // infotainment.go Ping + getCarServerResponse 통합
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val ping = Action(vehicleAction = VehicleAction(ping = Ping(ping_id = 1)))
            assertIs<VehicleResult.Success<*>>(session.infotainment.execute(ping))
            h.fake.script(Domain.DOMAIN_INFOTAINMENT, listOf(FakeVehicle.infotainmentError("car is in drive")))
            val rejected = assertIs<VehicleResult.Failure>(session.infotainment.execute(ping))
            assertEquals(VehicleError.InfotainmentRejected("car is in drive"), rejected.error)
        }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :application:jvmTest --tests '*VcsecResponsesTest*' --console=plain`
Expected: 컴파일 실패 (`VcsecResponses`, `UnknownKeychainCode` 없음)

- [ ] **Step 3: 구조 변경 — `unknownVarint` 공개 (동작 불변)**

`UnknownFields.kt`의 `internal fun`을 `@InternalTeslableApi public fun`으로 바꾸고 `import io.github.smallmiro.teslable.InternalTeslableApi`를 추가한다. `ResponseClassifier`와 `Signer`는 같은 파일 안 호출이 아니라 다른 파일이므로 `@OptIn(InternalTeslableApi::class)`가 필요하다 — `Signer`는 이미 클래스에 붙어 있고, `ResponseClassifier`의 `object`에 `@OptIn(InternalTeslableApi::class)`를 붙인다.

Run: `./gradlew :domain:apiDump --console=plain`
Run: `./gradlew :domain:jvmTest :domain:detekt --console=plain`
Expected: PASS

```bash
git add domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/UnknownFields.kt domain/src/commonMain/kotlin/io/github/smallmiro/teslable/protocol/ResponseClassifier.kt domain/api
git commit -m "struct(domain): expose ByteString.unknownVarint as InternalTeslableApi for :application response interpreters

Refs: FR-048
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: 구현**

`VehicleError.kt`의 `UnknownFault` 바로 아래에 추가한다:

```kotlin
    /** 이 라이브러리의 proto 스냅샷에 없는 `whitelistOperationInformation` 코드. Go `KeychainError{Code}`(등록되지 않은 코드). */
    public data class UnknownKeychainCode(
        /** 차량이 보낸 원시 varint. */
        public val rawCode: Int,
    ) : VehicleError {
        override val message: String get() = "keychain operation failed: unrecognized code $rawCode"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecResponses.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0) — unmarshalVCSECResponse, isTerminalTest, readUntil, isWhitelistOperationComplete, executeRKEAction/getVCSECInfo done
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.WhitelistOperation_status
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.application.dispatcher.PendingRequest
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import io.github.smallmiro.teslable.protocol.unknownVarint
import okio.IOException

/** VCSEC 응답 해석과 종료 판정(`03-protocol.md` §11, `08-errors.md` §3.5). */
@OptIn(InternalTeslableApi::class)
public object VcsecResponses {
    private const val TAG_WHITELIST_OPERATION_INFORMATION = 1

    /** Go `isTerminalTest`: 이 메시지로 끝낼지. */
    public fun interface TerminalTest {
        /** 판정. */
        public fun check(message: FromVCSECMessage): TerminalCheck
    }

    /** [TerminalTest]의 결과(Go `(bool, error)`). */
    public sealed interface TerminalCheck {
        /** 다음 메시지를 기다린다. */
        public data object Continue : TerminalCheck

        /** 이 메시지가 최종 성공. */
        public data object Done : TerminalCheck

        /** 이 메시지가 최종 실패. */
        public data class Fail(
            /** 원인. */
            public val error: VehicleError,
        ) : TerminalCheck
    }

    /** 정보 요청: 첫 메시지가 결과(Go `getVCSECInfo`의 `done`). */
    public val FIRST_MESSAGE: TerminalTest = TerminalTest { TerminalCheck.Done }

    /** RKE·closure: `commandStatus`가 없는 메시지가 최종(Go `executeRKEAction`/`executeClosureAction`의 `done`). */
    public val COMMAND_STATUS_ABSENT: TerminalTest =
        TerminalTest { if (it.commandStatus == null) TerminalCheck.Done else TerminalCheck.Continue }

    /** 화이트리스트 작업: `whitelistOperationStatus`가 있으면 종료, `NONE`이 아니면 실패(Go `isWhitelistOperationComplete`). */
    public val WHITELIST_OPERATION_COMPLETE: TerminalTest =
        TerminalTest { message ->
            val status = message.commandStatus?.whitelistOperationStatus ?: return@TerminalTest TerminalCheck.Continue
            keychainError(status)?.let { TerminalCheck.Fail(it) } ?: TerminalCheck.Done
        }

    /**
     * Go `unmarshalVCSECResponse`: 프로토콜 오류 → payload 종류 → 파싱 → `nominalError` → `commandStatus` 순.
     * payload가 없으면 빈 메시지(성공), 다른 oneof(세션정보)면 `BadResponse(mayHaveSucceeded = true)`, 파싱 실패도 `mayHaveSucceeded = true`.
     */
    public fun interpret(message: RoutableMessage): VehicleResult<FromVCSECMessage> {
        ResponseClassifier.protocolError(message)?.let { return it.toResult() }
        val payload = message.protobuf_message_as_bytes
        if (payload == null) {
            if (message.session_info == null && message.session_info_request == null) return VehicleResult.Success(FromVCSECMessage())
            return VehicleError.BadResponse("payload missing from vehicle response", mayHaveSucceeded = true).toResult()
        }
        val from =
            try {
                FromVCSECMessage.ADAPTER.decode(payload)
            } catch (e: IOException) {
                return VehicleError.BadResponse("vcsec: ${e.message ?: "undecodable"}", mayHaveSucceeded = true).toResult()
            }
        from.nominalError?.let { return VehicleResult.Failure(VehicleError.VcsecRejected(it.genericError)) }
        val status = from.commandStatus ?: return VehicleResult.Success(from)
        return commandStatusError(status)?.toResult() ?: VehicleResult.Success(from)
    }

    /** Go `readUntil`: [done]이 `Done`인 첫 메시지. 오류는 즉시. 시간 제한은 호출자([VcsecCommands.execute]). */
    public suspend fun readUntil(
        pending: PendingRequest,
        done: TerminalTest,
    ): VehicleResult<FromVCSECMessage> {
        while (true) {
            val from = interpret(pending.receive()).valueOr { return it.toResult() }
            when (val check = done.check(from)) {
                TerminalCheck.Continue -> Unit
                TerminalCheck.Done -> return VehicleResult.Success(from)
                is TerminalCheck.Fail -> return check.error.toResult()
            }
        }
    }

    /**
     * Go `switch status.GetOperationStatus()`: OK 통과, WAIT → [VehicleError.Busy], ERROR → whitelist 코드 또는 `signedMessageStatus` 없음 →
     * [VehicleError.UnknownResponse]. Go에 `default` 분기가 없으므로 Wire가 모르는 값(기본값 OK로 디코딩)은 통과한다(설계 구체화 11).
     */
    private fun commandStatusError(status: CommandStatus): VehicleError? =
        when (status.operationStatus) {
            OperationStatus_E.OPERATIONSTATUS_OK -> null
            OperationStatus_E.OPERATIONSTATUS_WAIT -> VehicleError.Busy
            OperationStatus_E.OPERATIONSTATUS_ERROR -> {
                val whitelist = status.whitelistOperationStatus
                val keychain = whitelist?.let { keychainError(it) }
                when {
                    keychain != null -> keychain
                    status.signedMessageStatus == null -> VehicleError.UnknownResponse
                    else -> null
                }
            }
        }

    /** Go `code != NONE → KeychainError{Code}`. Wire가 모르는 코드는 `unknownFields`에서 되찾아 [VehicleError.UnknownKeychainCode]. */
    private fun keychainError(status: WhitelistOperation_status): VehicleError? {
        status.unknownFields.unknownVarint(TAG_WHITELIST_OPERATION_INFORMATION)?.let { return VehicleError.UnknownKeychainCode(it) }
        val code = status.whitelistOperationInformation
        return if (code == WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE) null else VehicleError.KeychainRejected(code)
    }
}
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/vcsec/VcsecCommands.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0) — getVCSECResult (+ per-connection serialization, FR-049)
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.dispatcher.errorOrNull
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.application.vehicle.CommandTimeouts
import io.github.smallmiro.teslable.application.vehicle.SendWithRetry
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.AuthMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration

/**
 * Go `getVCSECResult`: VCSEC에 보내고 [VcsecResponses.readUntil]로 종료까지 읽는다. `shouldRetry()` 오류(`Busy` 포함)면 [Dispatcher.retryInterval]
 * 뒤 새 요청(새 routing_address·counter)으로 다시 보낸다. 한 연결의 VCSEC 명령은 [serial]로 직렬화한다(FR-049, SDD §5) — 시간 제한은 락
 * 대기를 포함하며 락 대기 중 만료는 `Failure(Timeout(afterSend = false))`, 응답 대기 중 만료는 `Uncertain(Timeout(afterSend = true))`.
 */
public class VcsecCommands(
    private val dispatcher: Dispatcher,
    private val timeouts: CommandTimeouts = CommandTimeouts(),
) {
    private val serial = Mutex()

    /** 페이로드(`UnsignedMessage` 바이트)를 보내고 [done]이 최종으로 판정한 메시지를 돌려준다. */
    public suspend fun execute(
        payload: ByteArray,
        auth: AuthMethod,
        done: VcsecResponses.TerminalTest,
        flags: Int = SendWithRetry.DEFAULT_FLAGS,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<FromVCSECMessage> {
        var awaitingResponse = false
        val result =
            withTimeoutOrNull(timeout) {
                serial.withLock { attemptUntilTerminal(payload, auth, done, flags) { awaitingResponse = it } }
            }
        return result ?: VehicleError.Timeout(afterSend = awaitingResponse).toResult()
    }

    private suspend fun attemptUntilTerminal(
        payload: ByteArray,
        auth: AuthMethod,
        done: VcsecResponses.TerminalTest,
        flags: Int,
        awaiting: (Boolean) -> Unit,
    ): VehicleResult<FromVCSECMessage> {
        while (true) {
            val attempt = attempt(payload, auth, done, flags, awaiting)
            val error = attempt.errorOrNull() ?: return attempt
            if (!error.shouldRetry()) return attempt
            delay(dispatcher.retryInterval)
        }
    }

    private suspend fun attempt(
        payload: ByteArray,
        auth: AuthMethod,
        done: VcsecResponses.TerminalTest,
        flags: Int,
        awaiting: (Boolean) -> Unit,
    ): VehicleResult<FromVCSECMessage> {
        awaiting(false)
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                protobuf_message_as_bytes = payload.toByteString(),
                flags = flags,
            )
        val pending = dispatcher.send(message, auth, timeouts.commandLifetime).valueOr { return it.toResult() }
        awaiting(true)
        val result = pending.use { VcsecResponses.readUntil(it, done) }
        awaiting(false)
        return result
    }
}
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/infotainment/InfotainmentResponses.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — getCarServerResponse (response interpretation)
package io.github.smallmiro.teslable.application.infotainment

import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import okio.IOException

/** Infotainment 응답 해석(`03-protocol.md` §10, `08-errors.md` §3.6). `CarServer.OperationStatus_E`는 OK=0, ERROR=1이다(VCSEC와 다르다). */
public object InfotainmentResponses {
    private const val UNSPECIFIED_ERROR = "unspecified error"

    /**
     * Go `getCarServerResponse`: 파싱 실패 → `BadResponse(mayHaveSucceeded = true)`; `actionStatus.result == ERROR` →
     * [VehicleError.InfotainmentRejected]`(plain_text 또는 "unspecified error")`; 그 외(모르는 값 포함, 설계 구체화 11)는 성공. null payload는 빈 응답.
     */
    public fun interpret(payload: ByteArray?): VehicleResult<Response> {
        val response =
            try {
                Response.ADAPTER.decode(payload ?: ByteArray(0))
            } catch (e: IOException) {
                return VehicleError.BadResponse("unable to parse vehicle response: ${e.message ?: "undecodable"}", mayHaveSucceeded = true).toResult()
            }
        val status = response.actionStatus
        if (status?.result == OperationStatus_E.OPERATIONSTATUS_ERROR) {
            val reason = status.result_reason?.plain_text?.takeIf { it.isNotEmpty() } ?: UNSPECIFIED_ERROR
            return VehicleResult.Failure(VehicleError.InfotainmentRejected(reason))
        }
        return VehicleResult.Success(response)
    }
}
```

```kotlin
// application/src/commonMain/kotlin/io/github/smallmiro/teslable/application/infotainment/InfotainmentCommands.kt
// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — getCarServerResponse, executeCarServerAction
package io.github.smallmiro.teslable.application.infotainment

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.application.vehicle.SendWithRetry
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.AuthMethod
import kotlin.time.Duration

/** Go `executeCarServerAction`/`getCarServerResponse`: `Action`을 Infotainment에 보내고(단일 응답, GCM) `Response`로 해석한다. M5가 명령별 빌더를 더한다. */
public class InfotainmentCommands(
    private val send: SendWithRetry,
) {
    /** [action]을 실행한다. 시간 초과·재시도 규칙은 [SendWithRetry.send]와 같다. */
    public suspend fun execute(
        action: Action,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Response> {
        val reply = send.send(Domain.DOMAIN_INFOTAINMENT, action.encode(), AuthMethod.GCM, timeout = timeout).valueOr { return it.toResult() }
        return InfotainmentResponses.interpret(reply.protobuf_message_as_bytes?.toByteArray())
    }
}
```

`VehicleSession`에 두 프로퍼티를 추가한다(`send` 아래):

```kotlin
    /** Go `getVCSECResult` 계층(직렬화 포함). */
    public val vcsec: VcsecCommands = VcsecCommands(dispatcher, timeouts)

    /** Go `executeCarServerAction` 계층. */
    public val infotainment: InfotainmentCommands = InfotainmentCommands(send)
```

`tools/ci/ported-files.txt`에 네 파일을 추가한다.

- [ ] **Step 5: 통과 확인, API 덤프, 커밋**

Run: `./gradlew :domain:apiDump --console=plain`
Run: `./gradlew :application:apiDump --console=plain`
Run: `./gradlew :domain:jvmTest :application:jvmTest :domain:check :application:check :domain:iosSimulatorArm64Test :application:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS)

```bash
git add domain/src/commonMain/kotlin/io/github/smallmiro/teslable/model/VehicleError.kt domain/src/commonTest/kotlin/io/github/smallmiro/teslable/model/VehicleErrorTest.kt domain/api
git commit -m "feat(domain): VehicleError.UnknownKeychainCode preserves whitelist codes unknown to Wire (Go KeychainError{Code})

Refs: FR-103, FR-048
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git add application/src application/api tools/ci/ported-files.txt
git commit -m "feat(application): VCSEC interpret/readUntil/serialized execute and Infotainment response interpretation from vcsec.go and infotainment.go

Refs: FR-048, FR-049, FR-100, FR-101
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 11: FakeVehicle 시나리오 7종 종단 테스트(NFR-003, `{{WORKFLOW_FILE}}` §2.4)

참고 매뉴얼: `08-errors.md` §5(desync 복구), §6(리플레이·드롭), §7("Couldn't verify success"), `03-protocol.md` §9.4, §11; `{{WORKFLOW_FILE}}` §2.4; `{{SDD_FILE}}` §3.3~§3.4, §9.3. Go 원본: 시나리오는 `verifier_test.go`(`TestEpochChange`, `TestGCMExpired`)와 `dispatcher_test.go`(`TestVehicleDropsReply`, `TestCorruptedSessionInfo`)의 조합이며 `pkg/vehicle` 경로(`getVCSECResult`, `Send`)를 끝까지 탄다. 설계: NFR-003(7 시나리오), NFR-007, FR-018, FR-048, FR-049, 설계 구체화 2.

**Files:**
- Test: `application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/scenario/FakeVehicleScenarioTest.kt`

**Interfaces:**
- Consumes: Task 4 `FakeVehicle`(대본 전부), Task 6 하네스, Task 9 `VehicleSession`, Task 10 `VcsecResponses`/`VcsecCommands`, `RecordingLogger`.
- Produces: 없음(테스트만). 각 시나리오 이름이 SDD §9.3·§9.4 M2 행에 들어간다(Task 12).

- [ ] **Step 1: 시나리오 테스트 작성 (전부 통과해야 한다 — 앞 Task들이 이미 구현했으므로 여기서는 Red가 없다; 실패하면 그 Task의 결함이다)**

```kotlin
// application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/scenario/FakeVehicleScenarioTest.kt
package io.github.smallmiro.teslable.application.scenario

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage
import io.github.smallmiro.teslable.application.dispatcher.DispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.PendingRequest
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.encode
import io.github.smallmiro.teslable.application.dispatcher.manualHandshake
import io.github.smallmiro.teslable.application.dispatcher.replyTo
import io.github.smallmiro.teslable.application.dispatcher.testCommand
import io.github.smallmiro.teslable.application.vcsec.VcsecResponses
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleScenarioTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val lock = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_LOCK).encode()

    private suspend fun DispatcherHarness.connectedSession(): VehicleSession {
        val session = VehicleSession(dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(vcsec), timeout = 1.seconds))
        return session
    }

    private fun DispatcherHarness.authenticatedRequests() = fake.received.filter { it.signature_data?.AES_GCM_Personalized_data != null }

    @Test
    fun scenario1VcsecWaitThenFinalSucceedsWithReauthorizedRetry() =
        // 시나리오 1: VCSEC 다중 응답 WAIT → 최종. WAIT는 Busy로 재시도(새 요청·새 counter), 최종은 빈 메시지.
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy()), listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecEmpty()))
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT)
            assertIs<VehicleResult.Success<*>>(result)
            val requests = h.authenticatedRequests()
            assertEquals(2, requests.size)
            val c1 = assertNotNull(requests[0].signature_data?.AES_GCM_Personalized_data).counter
            val c2 = assertNotNull(requests[1].signature_data?.AES_GCM_Personalized_data).counter
            assertEquals(c1 + 1, c2)
            assertFalse(requests[0].from_destination?.routing_address == requests[1].from_destination?.routing_address) // VCSEC: 요청마다 새 주소
        }

    @Test
    fun scenario2LostResponseIsUncertainAndNeverResent() =
        // 시나리오 2: 응답 유실 → Uncertain(Timeout(afterSend = true)), 자동 재전송 없음(NFR-007), 등록 해제
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            h.fake.dropNextReplies(1)
            val result = assertIs<VehicleResult.Uncertain>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 100.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(1, h.authenticatedRequests().size)
            assertEquals(0, h.dispatcher.pendingCount())
            // 앱은 재조회로 확인한다(FR-102). 다음 명령은 정상.
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
        }

    @Test
    fun scenario3EpochChangeRecoversViaAttachedSessionInfo() =
        // 시나리오 3: 차량 재부팅(epoch 변경) → INCORRECT_EPOCH + 세션정보 동봉 → 갱신 → 재시도 성공(FR-018)
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
            val oldEpoch = h.fake.epoch(vcsec)
            h.fake.rotateEpoch(vcsec)
            val before = h.authenticatedRequests().size
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
            val retried = h.authenticatedRequests().drop(before)
            assertEquals(2, retried.size)
            assertContentEquals(oldEpoch, assertNotNull(retried[0].signature_data?.AES_GCM_Personalized_data).epoch.toByteArray())
            assertContentEquals(h.fake.epoch(vcsec), assertNotNull(retried[1].signature_data?.AES_GCM_Personalized_data).epoch.toByteArray())
            assertTrue(h.logger.contains("Updated session info for DOMAIN_VEHICLE_SECURITY"))
        }

    @Test
    fun scenario4ClockRegressionInSameEpochIsIgnored() =
        // 시나리오 4: 같은 epoch에서 차량 시계가 뒤로 간 세션정보(선제 동봉)는 폐기(FR-014 3항) — 시계 추정이 그대로이고 다음 명령도 성공
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            advanceTimeBy(30_000)
            h.manualHandshake(vcsec) // clock_time 30인 세션정보를 반영해 setTime = 30 (FR-014 3항의 기준점)
            val state = assertNotNull(h.dispatcher.session(vcsec))
            assertEquals(30u, state.timestamp())
            h.fake.shiftClock(vcsec, (-10).seconds) // 차량: 20초
            h.fake.attachSessionInfoOnce(vcsec)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec, flags = 2), AuthMethod.GCM)).value
            pending.use { assertNotNull(it.receive().session_info) }
            assertEquals(30u, state.timestamp()) // 20으로 되돌아가지 않았다
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
        }

    @Test
    fun scenario5BadHmacOnSessionInfoIsDiscarded() =
        // 시나리오 5: 잘못된 HMAC → 폐기 후 재전송으로 회복; 계속 잘못되면 시간 초과, 세션 없음
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            h.fake.corruptNextSessionInfoTag(infotainment)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            assertEquals(2, h.fake.sessionInfoRequests)
            assertEquals(1, h.logger.count("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
            val h2 = dispatcherHarness()
            h2.fake.corruptNextSessionInfoTag(vcsec, count = 100)
            val result = assertIs<VehicleResult.Failure>(VehicleSession(h2.dispatcher).startSession(setOf(vcsec), timeout = 20.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertFalse(assertNotNull(h2.dispatcher.session(vcsec)).isReady)
            assertEquals(VehicleError.NoSession, assertIs<VehicleResult.Failure>(h2.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).error)
        }

    @Test
    fun scenario6ReplayedResponseIsDroppedWhileRequestIsStillOpen() =
        // 시나리오 6: 같은 counter의 응답 재전송은 요청이 열려 있어도 드롭되고, 그 뒤의 정상 응답은 전달된다
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess())) // 중간 응답만, 최종은 아래서 직접
            val command = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            runCurrent()
            assertFalse(command.isCompleted)
            h.fake.replayLastResponse(vcsec)
            runCurrent()
            assertTrue(h.logger.contains("Dropping duplicate vehicle response"))
            assertFalse(command.isCompleted)
            val request = h.authenticatedRequests().last()
            val final = h.fake.verifier(vcsec).encryptResponse(replyTo(request, ByteArray(0)).copy(protobuf_message_as_bytes = null), assertNotNull(RequestHash.of(request)), counter = 2u)
            h.transport.deliver(encode(final))
            assertIs<VehicleResult.Success<*>>(command.await())
        }

    @Test
    fun scenario7NotConnectableVehicleRefusesConnectionWithoutRetry() =
        // 시나리오 7: 슬롯 초과(connectable=false) — M2 수준: 연결이 MaxConnectionsExceeded로 즉시 실패, 재시도 없음. BLE 스캔은 M3.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            fake.setConnectable(false)
            val refused = assertIs<VehicleResult.Failure>(fake.connect())
            assertEquals(VehicleError.TransportError.MaxConnectionsExceeded, refused.error)
            assertFalse(refused.error.shouldRetry())
            fake.setConnectable(true)
            assertIs<VehicleResult.Success<*>>(fake.connect())
            val h = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(VehicleSession(h.dispatcher).startSession(timeout = 1.seconds))
        }
}
```

시나리오 6에서 마지막 응답을 직접 만드는 이유: FakeVehicle은 한 요청의 대본 응답을 한 번에 넣으므로, "중간 응답 → 재전송 → 최종"의 순서를 재현하려면 최종 응답을 테스트가 넣어야 한다. counter 2는 FakeVehicle이 다음에 쓸 값과 같다(중간 응답이 1).

- [ ] **Step 2: 통과 확인, 커밋**

Run: `./gradlew :application:jvmTest --tests '*FakeVehicleScenarioTest*' :application:iosSimulatorArm64Test --console=plain`
Expected: PASS (JVM, iOS). 실패하면 해당 시나리오가 가리키는 Task의 결함이다 — 재현 테스트가 이미 있으므로 그 Task 코드를 고친다(`superpowers:systematic-debugging`, 원본 Go와 대조).

```bash
git add application/src/commonTest/kotlin/io/github/smallmiro/teslable/application/scenario
git commit -m "test(application): seven FakeVehicle end-to-end scenarios (WAIT→final, lost reply, epoch change, clock regression, bad HMAC, replay, not connectable)

Refs: NFR-003, NFR-007, FR-014, FR-018, FR-048, FR-049
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

M2 완료 기준 확인(전부 Green이어야 Task 12로 간다):

```bash
./gradlew check --console=plain
./gradlew :domain:iosSimulatorArm64Test :application:iosSimulatorArm64Test :adapter-storage:iosSimulatorArm64Test :testing:iosSimulatorArm64Test --console=plain
gh run list --limit 3
```

---

### Task 12: 문서 동기화(SDD·errors.md·README·architecture.md), M2 인계 노트

참고: `{{PRD_FILE}}` §10, `{{WORKFLOW_FILE}}` §10~§11, `{{HANDOFF_DIR}}2026-09-27-m1-protocol.md`(형식). 원본 대응 없음.

**Files:**
- Modify: `docs/sdd/SDD.md` §1.3, §2.1, §2.2, §3.2~§3.4, §5, §7.1, §9.3, §9.4, §12
- Modify: `docs/prd/PRD.md` FR-019·NFR-003의 "`Clock` 포트" 표현 → "`kotlin.time.TimeSource` 주입"(요구 변경 아님, 용어만)
- Modify: `docs/manual/errors.md` (재시도·시간 초과 절, `UnknownKeychainCode` 행)
- Modify: `docs/manual/README.md` (`errors.md` 상태, `architecture.md` 상태)
- Create: `docs/manual/architecture.md` (기여자용 설계 요약 초안 — 모듈 그림, 명령 한 개의 경로, M6에서 완성)
- Modify: `README.md` 상태 표
- Create: `docs/handoff/<완료일>-m2-dispatcher.md`

- [ ] **Step 1: SDD 갱신**

§1.3 표: `dispatcher.go, session.go, receiver.go` 행의 파일을 `:application dispatcher/Dispatcher.kt, SessionState.kt, PendingRequest.kt, HandshakeFlow.kt`로; `connector.go` 행 비고를 "`send`는 `VehicleResult<Unit>`(값), `AuthMethod`는 NONE/GCM"으로; `pkg/cache` 행을 "`:domain port/SessionCache.kt`, `cache/CachedSession.kt`, `cache/SessionCacheCodec.kt` + `:adapter-storage storage/InMemorySessionCache.kt` + `:application cache/SessionCacheSync.kt`"로; `vehicle.go` 행을 "`:application vehicle/VehicleSession.kt`, `SendWithRetry.kt`, `CommandTimeouts.kt`"로; `vcsec.go` 행에 "`vcsec/VcsecResponses.kt`, `VcsecCommands.kt`(M2: `unmarshalVCSECResponse`, `readUntil`, `getVCSECResult`; 명령 빌더는 M4)"; `infotainment.go` 행에 "`infotainment/InfotainmentResponses.kt`, `InfotainmentCommands.kt`(M2: `getCarServerResponse`)"; `internal/log` 행 추가 → `:domain port/TeslaLogger.kt`.

§2.1 포트 블록을 실제 시그니처로 바꾼다:

```kotlin
public enum class AuthMethod { NONE, GCM }                                            // connector.AuthMethod (HMAC 없음, D6)
public sealed interface TransportState { Connected; Disconnected(reason: VehicleError.TransportError?) }
public interface Transport {                                                          // connector.Connector
    public val vin: Vin; public val incoming: Flow<ByteArray>; public val state: StateFlow<TransportState>
    public val retryInterval: Duration; public val allowedLatency: Duration
    public suspend fun send(message: ByteArray): VehicleResult<Unit>                  // 값으로(ADR-0006). Uncertain = 차량이 받았을 수 있음
    public suspend fun close()
}
public class KeyId(bytes: ByteArray /*20*/) { companion fun of(publicKey, crypto) }  // SHA1(공개키), 캐시 소유자
public class SessionSnapshot(domain: Domain, sessionInfo: ByteArray)                  // 내보내기(저장 시각 없음)
public class CachedSession(domain: Domain, sessionInfo: ByteArray, age: Duration)     // 불러오기(age = now − createdAt, 음수 가능)
public object SessionCacheCodec { VERSION = 1; encode(keyId, entries): ByteArray; decode(bytes, expectedKeyId): List<Entry>? }
public interface SessionCache {                                                       // pkg/cache (자체 형식 v1)
    suspend fun load(vin, keyId): List<CachedSession>; suspend fun store(vin, keyId, entries: List<SessionSnapshot>); suspend fun clear(vin)
}
public enum class LogLevel { DEBUG, INFO, WARN, ERROR }
public fun interface TeslaLogger { fun log(level, tag, message: () -> String); companion NoOp }
```

`TransportException` 언급을 지운다. `ByteString.unknownVarint(tag)`가 `@InternalTeslableApi`로 공개되어 `:application`이 쓴다고 적는다. `VehicleError.UnknownKeychainCode(rawCode)`를 §6에 추가한다.

§2.2 표를 실제 이름으로 바꾼다: `Dispatcher`(start/stop/close/send/requestSessionInfo/exportSessions/loadSessions, `maxLatency`), `SessionState`(processHello/authorize/decrypt/export/loadFromCache; `Mutex` + `CompletableDeferred`), `PendingRequest`(`Channel(10)`, `SlidingWindow`, non-suspend `close` + 지연 제거), `HandshakeFlow`(startSession/startSessions), `SendWithRetry`(단일 응답, D29 `afterSend` 단계), `VcsecCommands`(직렬화 `Mutex`, `readUntil`) + `VcsecResponses`(interpret, `TerminalTest` 3종), `InfotainmentCommands` + `InfotainmentResponses`, `SessionCacheSync`, `VehicleSession`(connect/startSession/disconnect), `CommandTimeouts`. `KeyManagement`/`Pairing`은 M4 그대로.

§3.2~§3.4: `pending = dispatcher.register(...)` → `dispatcher.send(...)`가 `PendingRequest`를 돌려준다는 표현으로; `SessionState.processHello` 첫 호출이 `Signer.createAuthenticated`(`importSessionInfo` 아님); 복호화 뒤 `pending.antiReplay.update(counter)`; VCSEC WAIT는 `readUntil`이 `Busy`를 돌려주고 `VcsecCommands`가 새 요청으로 재시도(같은 요청에서 계속 읽지 않음 — Go와 동일).

§5 표에 추가: "수신 루프의 suspend 금지는 **다른 코루틴의 진행을 기다리는** suspend를 뜻한다. `SessionState`·`Dispatcher`의 `Mutex`는 임계 구역이 `Signer` 호출·맵 조작뿐이라 유계다(설계 구체화 4). `Signer` 변경 멤버: `encrypt`, `updateSessionInfo`, `updateSignedSessionInfo`, `close`; 읽기: `decrypt`, `exportSessionInfo`, `timestamp`, getter". `PendingRequest.close()`는 suspend하지 않고 플래그만 세워 취소 중 `finally`에서도 안전하며 디스패처가 `register`/`lookup` 때 지운다. 타임아웃 행: `afterSend`는 현재 시도의 단계(응답 대기 중만 true; 재시도 대기 중은 false).

§7.1: "구현: `SessionCacheCodec`(`:domain`), `InMemorySessionCache`(`:adapter-storage`, 벽시계 주입). `age`는 어댑터가 원시값으로 넘기고 `Signer.importSessionInfo`가 0으로 클램프. 세션이 없으면 빈 목록을 저장(Go도 nil 저장). 손상 항목·모르는 도메인은 건너뛰고 로그."

§9.3: FakeVehicle 실제 API 목록(Task 4 Interfaces의 것)으로 교체하고 "명령도 검증한다(Go dummyConnector와 다름 — 픽스처 선택)", "`corruptNextSessionInfoTag(domain, count)`", "`connect()`가 connectable=false를 모델링(M3 TransportFactory 가짜가 감싼다)"을 적는다.

§9.4 M2 행을 완료로 바꾸고 이름을 대응시킨다:

| 원본 | Kotlin |
|---|---|
| `verifier_test.go` `TestValidGCMEncryption`, `TestGCMFlags`, `TestGCMMissingDestination`, `TestGCMOutOfOrderMessage`, `TestEpochChange`, `TestGCMExpired`, `TestGCMInvalidEpoch`, `TestGCMInvalidTime`, `TestGCMCorruptedCiphertext`, `TestVerifierEncryption`, `TestGCMWindow`, `TestProvideHandle` | `FakeVehicleTest.acceptsThenRejectsReplayedCommand`, `.rejectsTamperedFlags`, `.rejectsMissingDestinationAsInvalidDomains`, `.rejectsOutOfOrderMessageWithTtlTooLong`, `.rejectsAfterRebootThenResyncsWithAttachedSessionInfo`, `.rejectsExpiredCommand`, `.rejectsWrongEpoch`, `.rejectsExpirationBeyondEpochLength`, `.rejectsCorruptedCiphertext`, `.encryptsResponseThatSignerDecrypts`, `.enforcesSlidingWindowLikeGo`; `TestVerifierTest.sessionInfoCarriesAssignedHandle` |
| `dispatcher_test.go` `TestSendWithoutSession`, `TestStartSession`, `TestTimeout`, `TestInvalidMessages`, `TestVehicleDropsReply`, `TestUnsolicitedSessionInfo`, `TestCorruptedSessionInfo`, `TestDiscardUnauthenticatedSessionInfo`, `TestVehicleUnreachable`, `TestConnect`, `TestWaitForAllSessions`, `TestRetrySend`, `TestSendTimeout`, `TestStopDispatcher`, `TestDoNotBlockOnResponder`, `TestRequestSessionWithoutKey`, `TestHandshakeWithoutKey`, `TestNoValidHandshakeResponse`, `TestRetryNonresponsive`, `TestCache` (20) | `DispatcherTest.sendWithoutSessionReturnsNoSessionButUnauthenticatedSendWorks`, `HandshakeFlowTest.startSessionCompletesHandshakeAndAllowsAuthenticatedSend`, `.commandWithoutReplyTimesOut`, `DispatcherTest.dropsInvalidMessagesAndDeliversTheValidOne`, `HandshakeFlowTest.retransmitsSessionInfoRequestEveryRetryIntervalWhileVehicleSleeps`, `DispatcherTest.discardsSessionInfoWithBadTag`(×2), `.discardsUnauthenticatedSessionInfo`, `.unreachableVehicleFailsSendWithoutRetry`, `HandshakeFlowTest.startSessionsHandshakesBothDomainsAndFailsFastWhileAsleep`(×2), `DispatcherTest.retriesTemporarySendErrorsAndStopsOnMayHaveSucceeded`, `.sendGivesUpWhenCallerTimesOutDuringRetries`, `.sendBeforeStartOrAfterStopReturnsNotConnected`, `.doesNotBlockOtherHandlersWhenOneQueueIsFull`, `.requestSessionInfoWithoutKeyReturnsRequiresKey`, `HandshakeFlowTest.startSessionWithoutKeyReturnsRequiresKey`, `.startSessionFailsWithKeyNotPairedAfterBogusRepliesThenUnknownKeyId`, `.retransmitsSessionInfoRequestEveryRetryIntervalWhileVehicleSleeps`, `SessionCacheSyncTest.resumesSessionFromCacheWithoutHandshake` |
| `vehicle_test.go` 8개 | `VehicleSessionTest.startSessionReturnsFatalHandshakeErrorWithoutRetry`, `.startSessionRetriesTransientHandshakeError`, `.startSessionTimesOutWhileErrorsStayTransient`, `SendWithRetryTest.returnsTerminalFailureAfterTransientSendError`, `.timesOutBeforeSendWhenTransportKeepsFailingTransiently`, `.retriesWhileVehicleAnswersBusyThenTimesOutBetweenAttempts`, `.noResponseTimesOutAsUncertainAndIsNeverResent`, `.retriesEveryRetriableFaultThenReturnsTheTerminalOne` |
| `vcsec_test.go` 3개 | `VcsecCommandsTest.nominalErrorFailsWhitelistAndRkeCommands`, `.gibberishResponseIsUncertainBadResponse`, `.whitelistOperationRetriesBusyReadsIntermediateThenReportsKeychainError` |
| `security_test.go` `TestValidPIN` | M5(`SetPINToDrive` 인자 검증, `InvalidArgument`)로 이동 — M2 범위 아님 |
| `cache_test.go` `TestImportExport` | `SessionCacheCodecTest.roundTripsTwoDomainsInOrder`(+ `encodesVersion1LayoutFromSdd` 벡터). `TestEviction` 해당 없음 |
| FakeVehicle 시나리오 7종 | `FakeVehicleScenarioTest.scenario1…scenario7` |
| FR-014 디스패처 규칙 | `DispatcherTest.discardsSessionInfoReceivedMoreThanMaxLatencyAfterRequest`, `.discardsSessionInfoWhoseChallengeMatchesNoOutstandingRequest`, `.appliesReplayedSessionInfoWithSameClockTimeLikeGo` |

§12 "원본과 다른 동작 (의도)"에 추가하고, 마지막 항목("암호화 응답 … M2에서 보완")은 "M2에서 `Signer.decrypt`가 `unknownFields`의 원시 fault를 AAD에 쓴다(`SignerCryptoTest.decryptsResponseWhoseFaultCodeIsUnknownToWire`). `from_destination.domain`은 모르는 값이면 핸들러가 없어 Go와 같이 드롭"으로 갱신한다:

- `SessionState.authorize`는 `Signer.encrypt` 실패를 `Failure(ProtocolFault)`로 돌려준다. Go `session.authorize`는 오류를 지우고 즉시 재시도한다(ctx 만료까지 바쁜 루프). 재시도는 `SendWithRetry`가 간격을 두고 한다 — 관찰 결과 같음.
- `Dispatcher.loadSessions`는 손상 항목·모르는 도메인을 건너뛰고 나머지를 복원한다. Go `LoadCache`는 전체 실패.
- 핸드셰이크 시간 초과는 `Failure(Timeout(afterSend = false))`. Go는 `context.DeadlineExceeded`.
- Wire가 모르는 `whitelistOperationInformation`은 `UnknownKeychainCode(rawCode)`(Go `KeychainError{Code}`와 같은 정보). 모르는 VCSEC `operationStatus`·Infotainment `actionStatus.result`는 Go처럼 통과. 모르는 `genericError`는 `VcsecRejected(NONE)`(M4 L58).
- `PendingRequest.close()`는 suspend하지 않고 플래그만 세운다(Go `closeHandler`는 즉시 맵에서 제거). 관찰 결과 같음: 닫힌 요청에 온 응답은 "without registered handler"로 드롭.
- `Dispatcher.send`는 `DOMAIN_BROADCAST`도 `InvalidArgument`로 거부한다(Go도 `Domain_DOMAIN_BROADCAST`를 거부 — 동일, 기록만).

§9.3에 "FakeVehicle은 도메인마다 검증자를 유지한다(Go dummyConnector는 요청마다 새로 만든다) — 실차와 같은 epoch 지속성을 위한 픽스처 선택"을 적는다.

- [ ] **Step 2: `errors.md` 재시도·시간 초과 절, `UnknownKeychainCode` 행**

`## VehicleError 계층` 표에 행을 추가한다: `| UnknownKeychainCode(rawCode) | 이 라이브러리가 모르는 whitelistOperationInformation 코드 (더 새 펌웨어). "keychain operation failed: unrecognized code <rawCode>" | false | false |`. 표 아래에 절을 추가한다:

```markdown
## 재시도와 시간 초과 (M2)

라이브러리가 Go `vehicle-command`와 같은 규칙으로 재시도한다. 앱은 재시도 루프를 만들지 않는다.

| 상황 | 라이브러리 동작 |
|---|---|
| `error.shouldRetry()`(= `temporary && !mayHaveSucceeded`)인 오류: `BUSY`, `TIMEOUT`, `INVALID_SIGNATURE`, `INVALID_TOKEN_OR_COUNTER`, `INTERNAL`, `INCORRECT_EPOCH`, `TIME_EXPIRED`, `TIME_TO_LIVE_TOO_LONG`, `Busy`(VCSEC `WAIT`), 일시 전송 오류 | 1초(BLE `retryInterval`) 뒤 **새 counter·nonce·expires_at으로 다시 인가**해 재전송. 오류 응답에 동봉된 세션정보는 그 전에 반영된다 |
| `mayHaveSucceeded` 오류(응답 없이 시간 초과, `RESPONSE_MTU_EXCEEDED`, VCSEC 응답 파싱 실패) | **자동 재전송하지 않는다**(토글 명령 이중 실행 방지). `Uncertain`으로 돌려주므로 `vehicleStatus()`/`getState()`로 재조회한다 |
| 시간 초과(`timeout`, 기본 5초) — 전송 전이거나 재시도 대기 중 | `Failure(Timeout(afterSend = false))` |
| 시간 초과 — 응답 대기 중 | `Uncertain(Timeout(afterSend = true))` |
| 핸드셰이크 시간 초과(`handshakeTimeout`, 기본 20초) | `Failure(Timeout(afterSend = false))` — 세션이 만들어지지 않았고 부작용은 없다 |
| VCSEC 명령 | 한 연결에서 한 번에 하나만 보낸다. 앞 명령이 끝날 때까지 기다린 시간도 `timeout`에 포함된다 |
| 호출 코루틴 취소 | `CancellationException`이 그대로 전파된다(값으로 바뀌지 않는다) |
```

`docs/manual/README.md`의 `errors.md` 행 상태를 `M1 초안 · M2 재시도 절 · M5`로, `architecture.md` 행을 `M2 초안 · M6`으로 바꾼다. `architecture.md` 초안은 SDD §1.2 모듈 그림, §3.3의 "명령 하나의 경로"(`VehicleSession → VcsecCommands/SendWithRetry → Dispatcher.send → Transport`, 수신 `Transport.incoming → Dispatcher.process → PendingRequest`), 동시성 요약(수신 코루틴 1개, VCSEC 직렬화), 세션 캐시 한 단락을 옮겨 적는다(SDD의 해당 절로 링크하지 않고 요약만 — 이 문서는 앱 개발자용 상대경로 규칙을 따른다).

- [ ] **Step 3: README 상태, PRD 용어, 인계 노트**

`README.md` 상태 표: `M0 · M1 · M2 완료 (<완료일>) · M3 진행 예정`. 본문 한 줄: "M2로 디스패처·세션 계층(`:application`)과 `FakeVehicle`이 들어왔고, Go `dispatcher_test`/`vehicle_test`/`vcsec_test` 포팅본과 시나리오 7종이 JVM·iOS 시뮬레이터에서 통과합니다. BLE 연결(M3)과 명령 API(M4~M5)는 아직입니다."

`docs/prd/PRD.md` FR-019·NFR-003의 "`Clock` 포트"를 "`kotlin.time.TimeSource` 주입"으로 바꾼다(M1 결정의 용어 반영, 요구 변경 아님).

인계 노트 `docs/handoff/<완료일>-m2-dispatcher.md`는 `{{WORKFLOW_FILE}}` §10 형식(M1 노트와 같은 절 구성)으로 쓴다:
- 목표: 이 계획 파일, FR-010~012, FR-014, FR-017~018, FR-048~049, FR-100~102, NFR-003, NFR-007, NFR-012.
- 완료: 병합 PR 목록(번호·Task), 테스트 수(`:domain`, `:application`, `:adapter-storage`, `:testing` — JVM + iOS), CI 6/6, 최종 리뷰 수정 요약.
- 진행 중: 없음.
- 결정: 설계 구체화 1~12의 채택 결과와 사용자 답(a, b); `TeslaLogger` 도입; `unknownVarint` 공개; `PendingRequest.close` 비suspend.
- 막힌 점(M3 이후로): `TransportFactory`·`VehicleAdvertisement`·`ConnectOptions`(M3), 캐시된 차량 키 불일치 시 M3 `connect()`의 도메인 캐시 삭제(사용자 답 a — 감지 방법 설계와 SDD §12 기록은 M3), `VcsecRejected` 문구·모르는 `genericError` 코드(M4, L58), `errors.md`의 SKIE 문장(M3), `TestValidPIN`(M5), 기기 수면 중 단조 시계 정지(M3 `platform-notes.md`, M1 권고 8), 실차 골든 RX(M3, M1 권고 9).
- 다음 한 걸음: M3 상세 계획(`{{PLANS_DIR}}<날짜>-m3-transport-keystore.md`) 작성 후 🛑 승인. 첫 작업: Kable 스파이크(ADR-0003). 검증: `./gradlew check --console=plain`.
- 읽을 파일: `{{PATHS_FILE}}`, `{{AGENTS_FILE}}`, `{{SDD_FILE}}` §2.3~§2.5, §3.1, §8, `{{MANUAL_DIR}}02-ble-transport.md`, `{{REF_REPO_DIR}}pkg/connector/ble/ble.go`, 이 노트.

- [ ] **Step 4: 커밋 (PR 후 병합)**

Run: `./gradlew check --console=plain`
Expected: Green (문서만 바뀌었으므로 캐시 히트)

```bash
git add docs/sdd/SDD.md docs/prd/PRD.md docs/manual/errors.md docs/manual/README.md docs/manual/architecture.md README.md docs/handoff
git commit -m "docs: sync SDD with the M2 dispatcher/session API, errors.md retry section, architecture.md draft, README status, M2 handoff note

Refs: PRD §10
Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## 계획 자체 점검 (writing-plans 셀프 리뷰)

**1. 완료 기준 ↔ Task**

| 완료 기준(PRD §9 / 로드맵 M2) | Task |
|---|---|
| `dispatcher_test.go` 20개 | 6(8개), 7(9개), 8(`TestCache`) — §9.4 대응표(Task 12)에 이름별 매핑 |
| `vehicle_test.go` 8개 | 9 |
| `vcsec_test.go` 3개 | 10 |
| SDD §9.4 M2 `verifier_test.go` 행(`TestGCM*` FakeVehicle 경유) | 4(+ `TestProvideHandle`은 1) |
| FakeVehicle 시나리오 7종 | 11(종단), 부분 재현: 4(`TestEpochChange`, replay/attach), 6(replay 드롭), 7(bad HMAC), 9(응답 유실 → Uncertain) |
| `Transport` 포트 | 2 |
| `Dispatcher` 매칭·세션 갱신(4초·challenge)·복호화·드롭 | 6 |
| `SessionState`(첫/기존 hello) | 5 |
| `HandshakeFlow`, `SendWithRetry`(ShouldRetry, 간격, 재인가), VCSEC `readUntil`·직렬화 | 7, 9, 10 |
| Infotainment 응답 해석 | 10 |
| 세션 캐시 포트·코덱·`InMemorySessionCache` | 8 |
| `FakeVehicle`/`FakeTransport`(도메인별 `TestVerifier`) | 2, 4 |
| JVM + iOS 시뮬레이터 | 모든 Task의 Run 줄에 `iosSimulatorArm64Test` |

**2. 인계 항목 1~10 ↔ Task**

| # | 항목 | 처리 |
|---|---|---|
| 1 | 응답 counter 윈도우를 요청별 `PendingRequest`에서 | 5(`antiReplay`), 6(`dropsReplayedEncryptedResponse`), 11(시나리오 6) |
| 2 | 응답 AAD의 fault 원시값(`unknownFields`) | 3(`decryptsResponseWhoseFaultCodeIsUnknownToWire`, `Signer.decrypt` 내부 — 변형 없음, 근거 Task 3 Interfaces); 도메인은 설계 구체화 10 + 6(`dropsResponseFromDomainUnknownToWire`) |
| 3 | 다른 곳의 모르는 enum 값 | 10(VCSEC operationStatus 통과, whitelist → `UnknownKeychainCode(raw)`, Infotainment result 통과, nominalError 실패 보존) |
| 4 | 수신 루프 락 설계 | 설계 구체화 4, 5(`SessionState` KDoc에 `Signer` 멤버 분류), 12(SDD §5) |
| 5 | 캐시 `age` 클램프 | 설계 구체화 7; 5(`negativeCacheAgeImportsWithAgeZeroAndNeverThrows`), 8(`entryFromTheFutureYieldsNegativeAgeWithoutThrowing`, `entryFromTheFutureImportsWithAgeZeroAndNeverThrows`) |
| 6 | FR-014 규칙 3개 | 6(`discardsSessionInfoReceivedMoreThanMaxLatencyAfterRequest`, `discardsSessionInfoWhoseChallengeMatchesNoOutstandingRequest`, `appliesReplayedSessionInfoWithSameClockTimeLikeGo`) |
| 7 | `TestVerifier` 정리 4건 | 1 |
| 8 | 모든 `TestVerifier` 거부 분기를 FakeVehicle로 | 4(INVALID_DOMAINS·INCORRECT_EPOCH·TIME_EXPIRED·BAD_PARAMETER·TIME_TO_LIVE_TOO_LONG·INVALID_SIGNATURE·INVALID_TOKEN_OR_COUNTER) |
| 9 | "timeZero가 뒤로 감" 문구 | 1 Step 5 |
| 10 | 수정 라운드 Tidy First | Global Constraints(커밋 규칙), 3·10의 `struct:` 선행 커밋이 본보기 |

**3. 자리표시자 스캔:** "TBD/TODO/나중에/Similar to Task" 없음. 모든 코드 단계에 완전한 코드가 있다. Task 12의 문서 편집은 대상 절과 문구를 지정했다.

**4. 타입 일관성(교차 확인):** `FakeVehicle.script(domain, vararg List<ScriptedReply>)`, `scriptHandshake(domain, vararg MessageFault_E)`, `corruptNextSessionInfoTag(domain, count = 1)`(Task 7에서 확장; Task 4 테스트는 기본값), `transport(retryInterval = 1.milliseconds)`; `dispatcherHarness(privateKey, fake, start, retryInterval)`; `Dispatcher.send(message, auth, lifetime): VehicleResult<PendingRequest>`, `requestSessionInfo(domain)`, `session(domain): SessionState?`, `pendingCount()`, `exportSessions()`, `loadSessions(entries): Set<Domain>`; `SessionState.processHello/authorize/decrypt/export/loadFromCache/timestamp/counter/close`, `ready: Deferred<Unit>`; `PendingRequest.receive/tryReceive/close/requestHash/uuid`, `internal onReceive/antiReplay/isClosed/deliver/expired`; `HandshakeFlow.startSession/startSessions`; `SendWithRetry.send(domain, payload, auth, flags, timeout)`, `commandTimeout`, `DEFAULT_FLAGS`; `VehicleSession.connect/startSession/disconnect/handshake/send/vcsec/infotainment`; `VcsecCommands.execute(payload, auth, done, flags, timeout)`; `VcsecResponses.interpret/readUntil/TerminalTest/TerminalCheck/FIRST_MESSAGE/COMMAND_STATUS_ABSENT/WHITELIST_OPERATION_COMPLETE`; `InfotainmentCommands.execute(action, timeout)`; `SessionCacheSync.load/store`; `SessionCache.load/store/clear`; `KeyId.of`; `SessionCacheCodec.Entry/encode/decode`; `Results.kt`의 `toVehicleError/errorOrNull/valueOr`; `ByteString.unknownVarint`(Task 3 `internal` → Task 10 `@InternalTeslableApi public`).

**5. Review Focus ↔ 테스트:** 1 → Task 6 `matchesVcsecResponseByAddressRegardlessOfRequestUuid`; 2 → Task 6 `sendReturnsNotConnectedAfterIncomingCompletes`; 3 → Task 9 `cancelledCommandReleasesPendingRequest`; 4 → Task 8 `staleCachedVehicleKeyLeavesSessionStuckLikeGo`; 5 → Task 6 `decryptsResponseWhoseFlagsDifferFromRequest`.

**6. 결정적 가상 시간:** 시간 초과 단계(`afterSend`)를 고정하는 테스트는 `timeout`이 `retryInterval`의 배수에 걸리지 않게 잡았다(Task 9 `retriesWhileVehicleAnswersBusyThenTimesOutBetweenAttempts`는 하네스 `retryInterval = 3ms`, `timeout = 10ms`). 같은 가상 시각에 두 이벤트가 걸리는 테스트는 단계와 무관한 단언만 둔다.
