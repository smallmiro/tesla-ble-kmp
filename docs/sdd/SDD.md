# SDD — tesla-ble-kmp (`{{LIB_NAME}}`) 소프트웨어 설계

| 항목 | 내용 |
|---|---|
| 상태 | **v1.0 승인** (2026-09-26) |
| 상위 문서 | `{{PRD_FILE}}` (FR/NFR), `{{HANDOFF_FILE}}` §4, §6, §7 |
| 정답 기준 | `{{REF_REPO_DIR}}` @ `{{REF_REPO_COMMIT}}` + `pkg/protocol/protocol.md` |
| 1차 가이드 | `{{MANUAL_DIR}}` (`01-architecture`, `02-ble-transport`, `03-protocol`, `08-errors`, `10-porting-guide`) |
| 설계 원본 | `{{SPECS_DIR}}2026-09-26-tesla-ble-kmp-design.md` |
| 결정 기록 | `{{ADR_DIR}}0001` ~ `0011` (§11) |
| 다음 문서 | `{{PLANS_DIR}}` (Phase 3) |

경로는 `{{PATHS_FILE}}` 의 키로 참조한다. Go 파일 경로는 `{{REF_REPO_DIR}}` 기준이며, 인용한 줄 번호는 커밋 `{{REF_REPO_COMMIT}}` 기준이다.

---

## 0. Phase 2에서 결정한 사항 (PRD D19에 이어 번호 부여)

| # | 결정 | 선택 | ADR |
|---|---|---|---|
| D20 | 명령·조회 결과 표현 | **sealed `VehicleResult<T>` 반환** (Success / Uncertain / Failure). 예외는 취소와 프로그래밍 오류에만 | 0006 |
| D21 | 모듈 경계 | **헥사고날 6모듈 + 테스트 픽스처 모듈**: `:domain`, `:application`, `:adapter-ble`, `:adapter-crypto`, `:adapter-storage`, `:sdk`, `:testing`. Gradle이 의존 방향을 강제 | 0001 |
| D22 | protobuf | **Wire 5.x** (Gradle 플러그인, commonMain 생성). `.proto` 9개 무수정. 생성 패키지는 `java_package` 옵션 그대로 `com.tesla.generated.*` | 0002 |
| D23 | BLE 스택 | **Kable**을 `Transport` 포트 뒤에 둠. indication·MTU가 실기기에서 기대와 다르면 같은 포트를 CoreBluetooth/BluetoothGatt로 직접 구현 | 0003 |
| D24 | iOS AES-GCM | **cryptography-kotlin 0.6.0 CryptoKit 프로바이더**를 AES-GCM에만 사용(Swift 브리지 플러그인). SHA·HMAC·난수는 CommonCrypto/Security, 키·ECDH는 Security.framework 직접 구현. M0에서 실패하면 SPM Swift 타깃 주입으로 전환 | 0004 |
| D25 | Swift 표면 | **SKIE** (`:sdk`에만 적용) | 0005 |
| D26 | 세션 캐시 | 포트 + 플랫폼 기본 구현(D18). **자체 바이너리 포맷 v1**, 키 공개키에 종속. Go JSON 포맷과 호환하지 않음 | 0007 |
| D27 | 키 보관과 세션 키 | 개인키는 플랫폼 키스토어 핸들로만 존재. ECDH 결과와 K는 `Session` 객체 메모리에만 있고 `close()` 시 0으로 덮어씀 | 0008 |
| D28 | add-key-request 응답 읽기 | Go와 달리 원시 `FromVCSECMessage` 프레임을 읽어 페어링 진행 상태를 알림 (FR-023) | 0009 |
| D29 | 타임아웃과 취소 | 명령별 `timeout` 파라미터(기본 5초)로 라이브러리가 `withTimeoutOrNull`을 걸고, 전송 후 타임아웃은 `Uncertain`. 외부 취소(`CancellationException`)는 값으로 바꾸지 않고 전파. `expires_at` 수명은 별도 `commandLifetime`(기본 5초) | 0010 |
| D30 | 골든 픽스처 저장 | 리소스 파일 대신 **Kotlin 상수**(`:testing` commonMain). iOS 네이티브 테스트에서 리소스 로딩 문제 회피. `{{PATHS_FILE}}` `FIXTURES_DIR` 갱신 | 0011 |

---

## 1. 아키텍처 개요

### 1.1 목표
Go SDK의 BLE 경로(`pkg/connector/ble` → `internal/dispatcher` → `internal/authentication` → `pkg/vehicle`)를 **바이트 수준으로 동일하게** 재현하되, 구조는 헥사고날로 재배치해 차량·BLE·OS 없이 도메인을 테스트할 수 있게 한다. 원본의 goroutine + channel 설계는 코루틴 + `Channel`/`Flow`로 옮기고, 원본이 락으로 보호하던 상태는 `Mutex`와 단일 수신 코루틴으로 보호한다.

### 1.2 모듈 다이어그램

```
 앱 (Android Compose / iOS SwiftUI)  ──▶  :sdk  (TeslaBle 파사드, Vehicle 인터페이스 구현 조립, SKIE)
                                             │ api(:domain)  implementation(:application, :adapter-*)
                                             ▼
                          ┌──────────── :application ────────────┐
                          │ Dispatcher  SessionState  Retry      │
                          │ VcsecCommands  InfotainmentCommands  │
                          │ Pairing  StateQueries  KeyManagement │
                          └───────────────┬──────────────────────┘
                                          │ implementation(:domain)
                                          ▼
 ┌────────────────────────────── :domain (순수 Kotlin) ──────────────────────────────┐
 │ 생성 코드: com.tesla.generated.* (Wire)                                           │
 │ 값 객체: Vin, VehicleDomain, LocalName, RoutingAddress, RequestUuid                │
 │ 프로토콜: Metadata(TLV), SessionKeys, Signer(암호화/복호화/세션정보 검증),          │
 │           SlidingWindow, Framer, Reassembler, RequestHash, ResponseClassifier       │
 │ 결과·에러: VehicleResult, VehicleError                                            │
 │ 포트: Transport, TransportFactory, EcdhPrivateKey, VehicleKeyStore,                 │
 │       CryptoPrimitives, SessionCache, Clock, RandomSource, TeslaLogger              │
 └────────────────────────────────────────────────────────────────────────────────────┘
        ▲                         ▲                          ▲
        │ implementation          │ implementation           │ implementation
 :adapter-ble (Kable)      :adapter-crypto              :adapter-storage
  KableTransport            android: JCA + AndroidKeyStore     android: 앱 전용 파일
  KableScanner              ios: CommonCrypto + Security.fw     ios: Keychain
                                 + AES-GCM 공급자(ADR-0004)      common: InMemory
        ▲
 :testing (commonMain, 테스트 전용 의존): FakeVehicle, FakeTransport, TestClock, FixedRandom,
          SoftwareEcdhKey(테스트 키), ProtocolVectors, GoldenFixtures
```

의존 방향은 항상 `:domain`을 향한다. `:domain`의 외부 의존은 `wire-runtime`, `kotlinx-coroutines-core`, `kotlinx-datetime`(없어도 됨, `Clock` 포트가 대체) 뿐이다. 위반은 Gradle 컨벤션 플러그인이 컴파일 시점에 막는다(§2.8).

### 1.3 Go 원본 ↔ 모듈 대응 (HANDOFF §7 확정판)

| Go (`{{REF_REPO_DIR}}`) | 줄 | KMP 모듈 · 파일 | 비고 |
|---|---|---|---|
| `internal/authentication/metadata.go` | 84 | `:domain` `protocol/Metadata.kt` | PoC 이관. `Checksum` = `serialize()` 후 해시 |
| `internal/authentication/native.go` (`NativeSession`, `sharedSecret`) | 212 | `:domain` `protocol/SessionKeys.kt`, `protocol/Session.kt` | ECDH·AES는 포트로 위임. PEM 로딩은 포팅하지 않음 |
| `internal/authentication/signer.go`, `peer.go` | 359 | `:domain` `protocol/Signer.kt`, `protocol/RequestHash.kt` | `Encrypt`, `Decrypt`, `UpdateSessionInfo`, `ExportSessionInfo`, `extractMetadata`, `responseMetadata`, `RequestID`. HMAC 인가(`AuthorizeHMAC`)는 포팅하지 않음(D6) |
| `internal/authentication/window.go` | 68 | `:domain` `protocol/SlidingWindow.kt` | PoC 이관 |
| `internal/authentication/crypto.go`, `error.go` | 134 | `:domain` `protocol/Constants.kt`, `VehicleError.kt` | 라벨, 길이, `epochLength`, `MessageFault` 오류 |
| `internal/dispatcher/dispatcher.go`, `session.go`, `receiver.go` | 729 | `:application` `dispatcher/Dispatcher.kt`, `SessionState.kt`, `PendingRequest.kt` | goroutine → 코루틴, `chan` → `Channel(10)`, `readySignal` → `CompletableDeferred` |
| `pkg/connector/connector.go` | 72 | `:domain` `port/Transport.kt` | `Receive()` → `Flow<ByteArray>`, `Send` → `suspend`, `AuthMethod`는 GCM만 |
| `pkg/connector/ble/ble.go` | 360 | `:domain` `transport/Framer.kt`, `Reassembler.kt`, `LocalName.kt` + `:adapter-ble` `KableTransport.kt`, `KableScanner.kt` | 프레이밍·재조립·이름은 순수 Kotlin(PoC), GATT는 Kable |
| `pkg/protocol/error.go` | 267 | `:domain` `VehicleError.kt`, `ResponseClassifier.kt` | `GetError`, `ShouldRetry`, `MayHaveSucceeded`, `Temporary` |
| `pkg/protocol/key.go` | 173 | `:domain` `port/EcdhPrivateKey.kt`, `PublicKey.kt` | 파일 로딩은 포팅하지 않음. 65바이트 검증만 |
| `pkg/vehicle/vehicle.go` | 275 | `:application` `vehicle/VehicleSession.kt`, `SendWithRetry.kt` | `Send`, `trySend`, `StartSession`, `SessionInfo`, `Wakeup` |
| `pkg/vehicle/vcsec.go`, `security.go`(VCSEC 부분), `state.go`(`BodyControllerState`) | ~450 | `:application` `vcsec/VcsecCommands.kt`, `vcsec/VcsecResponses.kt`, `keys/KeyManagement.kt`, `pairing/Pairing.kt` | `unmarshalVCSECResponse`, `readUntil`, 종료 판정, `addKeyPayload`, `SendAddKeyRequestWithRole` |
| `pkg/vehicle/infotainment.go`, `climate.go`, `charge.go`, `actions.go`, `security.go`(INFO 부분), `state.go`(`GetState`) | ~1,200 | `:application` `infotainment/*.kt` (영역별 파일) | `getCarServerResponse`, 명령별 `Action` 조립 |
| `pkg/cache/cache.go`, `session.go` `CacheEntry` | 113+ | `:domain` `port/SessionCache.kt`, `cache/CachedSession.kt` + `:adapter-storage` | 포맷은 자체(D26) |
| `internal/authentication/verifier.go` | 328 | `:testing` `TestVerifier.kt`(M1, GCM 경로만), `FakeVehicle.kt`(M2, 도메인마다 `TestVerifier` 하나를 감싼다) | 차량 측 검증·응답 암호화 재현 |
| `pkg/protocol/protobuf/*.proto` | 1,930 | `:domain` `src/commonMain/proto/` (복사본, 무수정) | Wire 입력. 파일 헤더에 출처 커밋 표기 |

---

## 2. 모듈별 책임과 공개 API

### 2.1 `:domain` — 프로토콜 모델과 포트

**값 객체** (`data class` / `value class`, 불변, `ByteArray`는 방어 복사)

```kotlin
public class Vin(val value: String)                     // 17자 검증. toString()은 마스킹 "5YJ**********9ABC"
public enum class VehicleDomain(val wire: Int) { VCSEC(2), INFOTAINMENT(3) }
public class PublicKeyBytes(bytes: ByteArray)           // 0x04‖X‖Y 65바이트 검증. sha1Prefix()로 KeyId
public class LocalName(val value: String)               // "S" + hex(SHA1(vin)[:8]) + "C"
public class RoutingAddress(bytes: ByteArray)           // 16바이트
public class RequestUuid(bytes: ByteArray)              // 16바이트
public class Epoch(bytes: ByteArray)                    // 16바이트
```

`Vin`·`LocalName`은 `value class`가 아닌 일반 `class`다(value class는 Kotlin/Native ObjC 헤더에서 기반 타입으로 지워져 `export(:domain)`으로 Swift에 타입으로 남지 않는다).

**프로토콜 코어** (Go 대응은 §1.3)

```kotlin
public class Metadata { fun add(tag: Tag, value: ByteArray?): Metadata; fun addUint32(tag, value: UInt); fun serialize(message: ByteArray = EMPTY): ByteArray }
public object SessionKeys { fun deriveK(sharedX: ByteArray, crypto: CryptoPrimitives): ByteArray /* SHA1(x)[:16] */; fun subkey(k, label) }
public class Session(k: ByteArray, val localPublic: PublicKeyBytes, val vehiclePublic: PublicKeyBytes) : AutoCloseable  // K 보관, close()에서 0으로 덮음
public class Signer private constructor(session, vin: Vin, epoch, counter, clockTime, crypto, random, timeSource, age = Duration.ZERO) : AutoCloseable {
    // signer.go 1:1. 시계는 kotlin.time.TimeSource 주입(M0 Reassembler와 동일), 벽시계 없음
    public val vin: Vin; public val vehiclePublicKey: PublicKeyBytes; public val localPublicKey: PublicKeyBytes
    public val counter: UInt; public val epoch: ByteArray
    public fun timestamp(): UInt                                                          // Go timestamp(): 차량 시계 기준 현재 초
    public fun updateSessionInfo(info: SessionInfo): SignerResult<Unit>
    public fun updateSignedSessionInfo(challenge: ByteArray, encodedInfo: ByteArray, tag: ByteArray): SignerResult<Unit>
    public fun exportSessionInfo(): ByteArray
    public fun encrypt(message: RoutableMessage, expiresIn: Duration): SignerResult<RoutableMessage>       // counter++, 롤오버 검사, nonce = random(12)
    public fun decrypt(message: RoutableMessage, requestHash: ByteArray): SignerResult<DecryptedResponse>  // DecryptedResponse(평문 message, counter)
    override fun close()                                                                  // 세션 키를 0으로 덮음
    public companion object {
        public suspend fun create(privateKey, vin, info: SessionInfo, crypto, random, timeSource = TimeSource.Monotonic): SignerResult<Signer>
        public suspend fun createAuthenticated(privateKey, vin, challenge, encodedInfo, tag, crypto, random, timeSource = TimeSource.Monotonic): SignerResult<Signer>
        public suspend fun importSessionInfo(privateKey, vin, encodedInfo, age: Duration, crypto, random, timeSource = TimeSource.Monotonic): SignerResult<Signer>
    }
}
public sealed interface SignerResult<out T> {             // Go authentication.Error
    public data class Ok<T>(val value: T) : SignerResult<T>
    public data class Fault(val fault: MessageFault_E, val detail: String) : SignerResult<Nothing>
}
public object RequestHash { fun of(message: RoutableMessage): ByteArray? }   // peer.go RequestID
public class SlidingWindow(size = 32) { fun update(counter: UInt): Boolean }
public object Framer { fun frame(message: ByteArray, blockLength: Int): List<ByteArray> }
public class Reassembler(timeSource: TimeSource = TimeSource.Monotonic, rxTimeout = 1.seconds, maxMessageSize = 1024) { fun push(chunk): List<ByteArray>; fun reset() }
public object ResponseClassifier { fun protocolError(msg: RoutableMessage): VehicleError? /* GetError */ }   // shouldRetry는 VehicleError.shouldRetry() 확장으로 옮김
```

`Session`의 `NONCE_SIZE`는 공개(호출자가 매 호출마다 새 nonce를 만들어 넘긴다, ADR-0008), `TAG_SIZE`는 비공개다(태그 길이는 와이어 입력 검증의 내부 규칙이지 호출자가 다룰 값이 아니다).

**결과와 에러** (D20)

```kotlin
public sealed interface VehicleResult<out T> {
    public data class Success<T>(val value: T) : VehicleResult<T>
    public data class Uncertain(val error: VehicleError) : VehicleResult<Nothing>   // MayHaveSucceeded == true
    public data class Failure(val error: VehicleError) : VehicleResult<Nothing>
}
public sealed interface VehicleError {                   // §6 전체 계층
    public val message: String; public val mayHaveSucceeded: Boolean; public val temporary: Boolean
}
```

**포트**

```kotlin
public interface Transport {                              // connector.Connector
    public val vin: Vin
    public val incoming: Flow<ByteArray>                  // 재조립된 메시지 단위
    public val state: StateFlow<TransportState>           // Connected / Disconnected(reason)
    public val retryInterval: Duration                    // BLE 1s
    public val allowedLatency: Duration                   // BLE 4s
    public suspend fun send(message: ByteArray)           // 프레이밍·분할 포함. 실패는 TransportException(temporary?)
    public suspend fun close()                            // 멱등
}
public interface TransportFactory {
    public fun scan(localName: LocalName): Flow<VehicleAdvertisement>   // Advertisement(rssi, isConnectable, identifier)
    public suspend fun connect(vin: Vin, target: VehicleAdvertisement?, options: ConnectOptions): Transport
}
public interface EcdhPrivateKey { public val publicKey: PublicKeyBytes; public suspend fun sharedX(peer: PublicKeyBytes): ByteArray /* 32B */ }
public interface VehicleKey : EcdhPrivateKey { public val alias: String; public val protection: KeyProtection /* SECURE_ENCLAVE, STRONGBOX, TEE, SOFTWARE */ }
public interface VehicleKeyStore { suspend fun getOrCreate(alias, policy: KeyPolicy = PreferHardware): VehicleKey; suspend fun get(alias): VehicleKey?; suspend fun delete(alias) }
public interface CryptoPrimitives { fun sha1(d); fun sha256(d); fun hmacSha256(key, d); fun aesGcmEncrypt(key, nonce, plaintext, aad): AesGcmOutput; fun aesGcmDecrypt(...): ByteArray?; fun constantTimeEquals(a, b): Boolean }
public interface RandomSource { fun nextBytes(n: Int): ByteArray }
// 시계: kotlin.time.TimeSource를 주입한다(Reassembler, Signer, TestVerifier). 벽시계는 세션 캐시(M2 :adapter-storage)의
// createdAt에서만 쓰고 age: Duration으로 변환해 넘긴다. age가 음수면(벽시계가 뒤로 감) 캐시가 0으로 자르거나 버린다 —
// Signer.importSessionInfo는 age >= 0을 요구한다(Go보다 엄격).
public interface SessionCache { suspend fun load(vin: Vin, keyId: KeyId): List<CachedSession>; suspend fun store(vin, keyId, entries); suspend fun clear(vin) }
public interface TeslaLogger { fun log(level: LogLevel, tag: String, message: () -> String) }   // 기본 NoOp
```

`CryptoPrimitives`의 `aesGcmEncrypt`는 nonce를 **인자로 받는다**(테스트 벡터 고정용). 운영 코드는 항상 `RandomSource`에서 12바이트를 새로 뽑아 넘긴다. 이 설계는 Go `NativeSession.Encrypt`(내부에서 nonce 생성)와 다르지만 결과 바이트는 같다(ADR-0008에 기록).

### 2.2 `:application` — 유스케이스

| 구성 요소 | 책임 | Go 대응 |
|---|---|---|
| `Dispatcher` | `Transport.incoming`을 단일 코루틴에서 소비해 `RoutableMessage`로 파싱 → `PendingRequest` 매칭 → 세션정보 갱신 → 복호화 → 핸들러 채널 전달. 요청 조립(`uuid`, `routing_address`, `flags`), 인가, 전송 재시도 | `dispatcher.go` `listen`, `process`, `Send`, `checkForSessionUpdate`, `decrypt` |
| `SessionState` (도메인별) | `Signer` 보유, `ready: CompletableDeferred<Unit>`, `Mutex`. `processHello`, `authorize`, `export` | `session.go` |
| `PendingRequest` | `(address, uuid?, domain)` 키, `Channel<RoutableMessage>(10)`, `requestSentAt`, `SlidingWindow`, `requestHash` | `receiver.go` |
| `HandshakeFlow` | `startSession(domains)`: 도메인마다 병렬 코루틴, 1초 간격 재전송, `ready` 대기. 캐시 복원 세션은 즉시 ready | `StartSession`, `tryStartSession`, `StartSessions` |
| `SendWithRetry` | `ShouldRetry`면 `retryInterval` 후 재인가·재전송. 응답 대기 중 타임아웃 → `Uncertain`(D29) | `vehicle.go` `Send`, `trySend` |
| `VcsecCommands` | `UnsignedMessage` 조립, **VCSEC `Mutex`로 직렬화**, `readUntil(done)` 종료 판정 3종, `WAIT` 재시도 | `vcsec.go` |
| `InfotainmentCommands` | `CarServer.Action` 조립, 단일 응답, `actionStatus` 해석. 영역별 파일: `ClimateCommands`, `ChargingCommands`, `BodyCommands`, `MediaCommands`, `SecurityCommands`, `StateQueries` | `infotainment.go`, `climate.go`, `charge.go`, `actions.go`, `security.go`, `state.go` |
| `KeyManagement` | `keySummary`, `keyInfoBySlot`, `listKeys`(슬롯 순회), `addKey`, `removeKey`, `sessionInfo(publicKey, domain)` | `security.go`, `vehicle.go` `SessionInfo` |
| `Pairing` | `ToVCSECMessage{PRESENT_KEY}` 직접 전송(RoutableMessage 아님) + 원시 프레임 구독으로 `FromVCSECMessage.commandStatus` 진행 상태 방출(D28) | `SendAddKeyRequestWithRole` + 확장 |
| `SessionCacheSync` | 세션 준비·갱신 시 `SessionCache.store`, `Vehicle` 생성 시 `load` | `Cache`, `LoadCache`, `UpdateCachedSessions` |

### 2.3 `:sdk` — 공개 파사드

```kotlin
public class TeslaBle internal constructor(...) {
    public companion object {
        public fun create(platform: PlatformContext, config: TeslaBleConfig = TeslaBleConfig()): TeslaBle
        //  Android: PlatformContext(context: Context)  /  iOS: PlatformContext()
    }
    public val keys: VehicleKeyStore
    public fun scan(vin: Vin): Flow<VehicleAdvertisement>                           // FR-001, FR-030
    public suspend fun connect(vin: Vin, key: VehicleKey?, options: ConnectOptions = ConnectOptions()): VehicleResult<Vehicle>   // FR-002~004
}

public data class TeslaBleConfig(
    val logger: TeslaLogger = TeslaLogger.NoOp,
    val sessionCache: SessionCache? = null,      // null이면 플랫폼 기본 구현
    val commandTimeout: Duration = 5.seconds,    // D29
    val commandLifetime: Duration = 5.seconds,   // expires_at
    val handshakeTimeout: Duration = 20.seconds,
    val connectTimeout: Duration = 30.seconds,
)

public interface Vehicle {
    public val vin: Vin
    public val hasKey: Boolean
    public val connection: StateFlow<ConnectionState>
    public val sessions: StateFlow<Map<VehicleDomain, SessionStatus>>               // None / Handshaking / Ready(fromCache)

    public suspend fun startSession(domains: Set<VehicleDomain> = VehicleDomain.ALL, timeout: Duration? = null): VehicleResult<Unit>   // FR-012
    public suspend fun sessionInfo(publicKey: PublicKeyBytes, domain: VehicleDomain, timeout: Duration? = null): VehicleResult<SessionInfoSummary>  // FR-024
    public suspend fun disconnect()

    // 키 (FR-022~027)
    public fun addKeyRequest(role: KeyRole = KeyRole.OWNER, formFactor: KeyFormFactor = KeyFormFactor.platformDefault()): Flow<PairingEvent>
    public suspend fun listKeys(timeout: Duration? = null): VehicleResult<List<WhitelistEntry>>
    public suspend fun keySummary(...): VehicleResult<WhitelistSummary>
    public suspend fun addKey(publicKey: PublicKeyBytes, role: KeyRole, formFactor: KeyFormFactor, ...): VehicleResult<Unit>
    public suspend fun removeKey(publicKey: PublicKeyBytes, ...): VehicleResult<Unit>

    // 조회 (FR-031~034)
    public suspend fun vehicleStatus(...): VehicleResult<VehicleStatus>              // 수기 값 객체
    public suspend fun getState(category: StateCategory, ...): VehicleResult<VehicleData>   // Wire 생성 타입 그대로
    public suspend fun ping(...): VehicleResult<Unit>

    // VCSEC 제어 (FR-040~047), Infotainment 제어 (FR-050~094): PRD §5의 메서드명 그대로, 모두 suspend + VehicleResult<Unit>
    public suspend fun lock(timeout: Duration? = null): VehicleResult<Unit>
    ...
}
```

- 모든 `timeout: Duration? = null`은 `TeslaBleConfig.commandTimeout`을 기본으로 쓴다.
- `PairingEvent`: `Sent`, `WaitingForTap`, `Accepted`, `Rejected(KeychainError)`, `Unknown(raw)`. 실차에서 응답 형식이 확인되기 전까지 `Unknown`을 통해 원시 바이트를 노출한다(M4 실차 검증 항목).
- `VehicleStatus`, `WhitelistEntry`, `WhitelistSummary`, `SessionInfoSummary`, `VehicleAdvertisement`는 **수기 값 객체**다(작고 안정적). `VehicleData`(GetState 12 카테고리, 200여 필드)와 그 하위 타입은 **Wire 생성 타입을 그대로 노출**한다(FR-033). 이유: 필드가 많고 펌웨어에 따라 늘어나며, 래핑하면 손실·지연이 생긴다. 생성 타입은 `@TeslableGenerated` 문서 주석으로 표시하고 `apiDump`에 포함한다.
- iOS: SKIE가 `suspend` → `async throws`, `Flow` → `AsyncSequence`, `sealed` → `enum`으로 변환한다. `VehicleResult`는 Swift에서 `switch result { case .success(let v): ... case .uncertain(let e): ... case .failure(let e): ... }`.

### 2.4 `:adapter-ble`

- `KableScanner.scan(localName)`: `Scanner { filters { match { name = Filter.Name.Exact(localName.value) } } }.advertisements` → `VehicleAdvertisement(identifier, rssi, isConnectable)`. iOS의 `isConnectable`은 `CBAdvertisementDataIsConnectable`, Android는 `ScanResult.isConnectable`(API 26+).
- `KableTransport.connect`: `Peripheral(advertisement)` → `connect()` → 서비스 `…0211` 특성 `…0212`/`…0213` 탐색 → `…0213` **indication** 구독(ADR-0003: Kable이 notify를 우선 선택하면 CCCD를 직접 써서 indication 강제) → Android `requestMtu(517)` 결과, iOS `maximumWriteValueLength(for: .withResponse)`로 `blockLength = min(mtu, 1024) - 3` → `Transport` 반환.
- 재시도 규칙은 Go `tryToConnect`/`NewConnectionFromScanResult`와 같다: 스캔·연결·탐색·구독 실패는 재시도, 이름 불일치·connectable=false·권한 없음은 즉시 실패.
- `send`: `Framer.frame` → `write(txChar, chunk, WriteType.WithResponse)` 순차. 전송 중 `Mutex`.
- `incoming`: `observe(rxChar)` → `Reassembler.push` → 메시지 단위 `Flow`. 버퍼는 `Channel(BUFFERED=64)`; Go의 5보다 크게 잡아 드롭 가능성을 줄인다(매뉴얼 `10-porting-guide §3` 권고).

### 2.5 `:adapter-crypto`

| 플랫폼 | `CryptoPrimitives` | `VehicleKeyStore` / `EcdhPrivateKey` |
|---|---|---|
| Android (API 31+) | JCA `MessageDigest`(SHA-1/256), `Mac`(HmacSHA256), `Cipher("AES/GCM/NoPadding")`, `SecureRandom`, `MessageDigest.isEqual` | `AndroidKeyStore` EC P-256, `PURPOSE_AGREE_KEY`, `setIsStrongBoxBacked(true)` 시도 → 실패 시 TEE → 실패 시 `KeyPairGenerator("EC")` 소프트웨어 키를 Keystore AES 키로 암호화해 앱 전용 파일 보관(D10). `KeyAgreement("ECDH", "AndroidKeyStore").generateSecret()` = 32바이트 X |
| iOS (16+) | CommonCrypto `CC_SHA1`, `CC_SHA256`, `CCHmac`; `SecRandomCopyBytes`; AES-GCM은 **ADR-0004** 공급자; 상수 시간 비교는 Kotlin 구현(XOR 누적) | `SecKeyCreateRandomKey` + `kSecAttrTokenIDSecureEnclave`(가능 시) → 실패 시 Keychain 저장 소프트웨어 키. `SecKeyCopyKeyExchangeResult(kSecKeyAlgorithmECDHKeyExchangeStandard)` = 32바이트 X. 공개키는 `SecKeyCopyExternalRepresentation`(65바이트 X9.63) |
| 테스트 (`:testing`) | JVM: 위 JCA 구현 재사용. iOS 시뮬레이터: 위 iOS 구현 | `SoftwareEcdhKey(privateScalar)`: JVM은 JCA `ECPrivateKeySpec`, iOS는 `SecKeyCreateWithData`(kSecAttrKeyClassPrivate). `protocol.md` 클라이언트 키로 벡터 검증 |

### 2.6 `:adapter-storage`

| 플랫폼 | 구현 | 위치 |
|---|---|---|
| Android | `FileSessionCache` | `context.filesDir/teslable/sessions/<sha1(vin)>.bin` (MODE_PRIVATE) |
| iOS | `KeychainSessionCache` | Keychain generic password, service `io.github.smallmiro.teslable.session`, account `<sha1(vin)>`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| 공통 | `InMemorySessionCache` | 테스트·옵트아웃용 |

포맷은 §7.1.

### 2.7 `:testing`

`FakeVehicle`(§9.3), `FakeTransport`, `TestClock`(수동 진행), `FixedRandom`(시퀀스 주입), `SoftwareEcdhKey`, `ProtocolVectors`(`protocol.md` 값), `GoldenFixtures`(Kotlin 상수, D30). 이 모듈은 다른 모듈의 `commonTest`에서만 의존한다.

### 2.8 경계 강제 (Gradle)

`build-logic/` 컨벤션 플러그인이 모듈 종류별로 허용 의존성을 선언하고, 그 밖의 `project(...)` 의존이나 금지 라이브러리(`:domain`에 Kable/Android SDK)를 추가하면 `configure` 단계에서 실패시킨다. `:domain`은 Android 타깃에서도 `android.*` 임포트가 없도록 `detekt` 규칙 `ForbiddenImport`를 함께 건다. `apiCheck`(binary-compatibility-validator)는 모든 라이브러리 모듈(`:domain`, `:application`, `:adapter-*`, `:sdk`)에 적용하고, `samples/*`와 `:testing`만 제외한다(루트 `build.gradle.kts`의 `apiValidation.ignoredProjects`).

---

## 3. 시퀀스: 스캔 → 연결 → 핸드셰이크 → 명령 → 응답 매칭 → 세션 복구

### 3.1 스캔과 연결 (FR-001~005)

```
App ─ scan(vin) ──▶ KableScanner: Filter.Name.Exact(LocalName(vin))  ──▶ Flow<VehicleAdvertisement>
App ─ connect(vin, key) ──▶ TransportFactory.connect
        loop(재시도, Go NewConnectionFromScanResult):
          scan 첫 매치 (없으면 connectTimeout까지 대기)
          isConnectable == false → Failure(MaxConnectionsExceeded)   (재시도 없음)
          peripheral.connect → discover 0211/0212/0213 → subscribe 0213 (indication) → MTU
          실패 → 1초 후 재시도 (connectTimeout 안에서)
        Transport 준비 → Dispatcher 생성(address 16B 랜덤) → 수신 코루틴 시작
        SessionCache.load(vin, keyId) → 있으면 SessionState(ready=true, Signer.import)   (Go LoadCache)
        → VehicleResult.Success(Vehicle)
```

### 3.2 핸드셰이크 (FR-012~014)

```
Vehicle.startSession({VCSEC, INFOTAINMENT})
  for each domain (병렬 coroutineScope):
    SessionState 없으면 생성; 캐시로 ready면 즉시 반환
    loop:
      req = RoutableMessage(to=domain, session_info_request{public_key}, uuid=rand16, from=address)   // VCSEC는 address도 rand16
      pending = dispatcher.register(key(address, uuid|0, domain), requestHash=null)
      transport.send(req)
      select {  ready.await()                         → return
                pending.channel.receive()             → ResponseClassifier.protocolError → Failure 또는 계속 대기(ready 기다림)
                delay(retryInterval)                  → 재전송 }
  수신 측 (Dispatcher.process):
    session_info 있음 → checkForSessionUpdate:
      key 없음 → 폐기 / pending.expired(allowedLatency=4s) → 폐기 / tag 없음 → 폐기
      SessionState.processHello(challenge=request_uuid, info, tag):
        최초: Signer.importSessionInfo + sessionInfoHmac 검증(상수 시간) → ready.complete()
        기존: updateSignedSessionInfo (공개키 일치, epoch 변경 또는 setTime<=clock_time 일 때만 갱신, counter 비롤백)
    SessionCacheSync.store(...)
```

### 3.3 명령과 응답 (FR-010, FR-011, FR-015, FR-016, FR-048, FR-049, FR-100~101)

```
Vehicle.lock(timeout)
  VcsecCommands.rke(LOCK): payload = UnsignedMessage{RKEAction=LOCK}
  vcsecMutex.withLock {                                        // FR-049
    SendWithRetry(domain=VCSEC, payload, done = { it.commandStatus == null }) {
      loop:
        msg = RoutableMessage(to=VCSEC, payload, flags=2, uuid=rand16, from=rand16)
        SessionState.authorize: ready.await(); Signer.encrypt(msg, commandLifetime)   // counter++, AAD=SHA256(TLV), nonce=rand12
        pending = register(key, RequestHash.of(msg))
        transport.send(bytes)   // 전송 오류가 temporary면 retryInterval 후 재전송, 아니면 Failure
        readUntil(done):
          withTimeoutOrNull(remaining) { pending.channel.receive() } ?: return Uncertain(Timeout)   // D29
          protocolError? → temporary && !mayHaveSucceeded → 재시도 / else → Failure|Uncertain
          FromVCSECMessage 파싱: nominalError → Failure(VcsecNominal); WAIT → Busy(재시도); ERROR+whitelist → Failure(Keychain)
          done(msg) → Success
    }
  }
  수신 측 (Dispatcher.process, 단일 코루틴):
    from_destination 없음 / request_uuid 길이 ≠ 0,16 / to_destination이 domain / address 길이 ≠ 16 → 드롭
    key = (address, uuid if domain != VCSEC else 0, domain); pending 없음 → 드롭
    checkForSessionUpdate (위와 동일)
    AES_GCM_Response_data 있음 → Signer.decrypt(msg, pending.requestHash): AAD = SHA256(TLV{9, domain, VIN, counter, flags(항상), request_hash, fault})
       → pending.window.update(counter) 실패 → 드롭(ErrReplayedResponse) / 복호화 실패 → 드롭
    pending.channel.trySend(msg) 실패(가득) → 드롭 로그
```

Infotainment(`climateOn`)는 뮤텍스 없이 같은 경로를 타며, `key.uuid = msg.uuid`, 단일 응답, `CarServer.Response.actionStatus` 해석(`ERROR` → `Failure(InfotainmentRejected(reason))`).

### 3.4 세션 복구 (FR-018)

오류 응답에 동봉된 `session_info`는 위 §3.2 수신 규칙으로 갱신된 뒤 응답이 그대로 핸들러에 전달된다. `SendWithRetry`가 `temporary` 오류(INVALID_SIGNATURE, INCORRECT_EPOCH, TIME_EXPIRED, INVALID_TOKEN_OR_COUNTER, BUSY, TIMEOUT, INTERNAL, TIME_TO_LIVE_TOO_LONG, WAIT)를 보면 `retryInterval` 후 **새 counter·nonce·expires_at으로 재인가**해 재전송한다. 갱신된 세션은 `SessionCache`에 즉시 저장한다.

---

## 4. 키 등록 시퀀스 (FR-022~024, D28)

```
App ─ vehicle.addKeyRequest(role=OWNER, formFactor=ANDROID_DEVICE|IOS_DEVICE).collect { event -> UI }
  1. Pairing: inner = UnsignedMessage{WhitelistOperation{addKeyToWhitelistAndAddPermissions{key, keyRole}, metadataForKey{keyFormFactor}}}
     envelope = ToVCSECMessage{signedMessage{protobufMessageAsBytes=inner, signatureType=PRESENT_KEY}}
     dispatcher.rawFrames 구독 시작 → transport.send(envelope)  (RoutableMessage 아님, 인증 없음)  → emit(Sent)
  2. 차량 화면에 승인 요청. 사용자가 NFC 키카드를 태그하고 확인.
  3. 수신 프레임 중 RoutableMessage로 파싱되지 않거나 from_destination이 없는 프레임을 FromVCSECMessage로 시도 파싱:
       commandStatus.operationStatus == WAIT                       → emit(WaitingForTap)
       commandStatus.whitelistOperationStatus.information == NONE  → emit(Accepted), 종료
       ... != NONE                                                  → emit(Rejected(KeychainError)), 종료
       파싱 불가                                                    → emit(Unknown(raw))
     타임아웃(기본 60초) → 종료 (결과는 4로 확인)
  4. App ─ vehicle.sessionInfo(myPublicKey, INFOTAINMENT) 를 수 초 간격으로 폴링 → status == OK 이면 등록·동기화 완료
```

Go는 1단계에서 `conn.Send` 후 즉시 반환하고 3단계를 하지 않는다. 3단계는 원본에 없는 동작이므로 실차 검증 전까지 `Unknown` 이벤트로 원시 바이트를 노출하고, `Dispatcher.process`의 드롭 로직에는 영향을 주지 않는다(원시 프레임 탭은 파싱 전에 분기).

---

## 5. 동시성 모델

| 항목 | 설계 | Go 대응 |
|---|---|---|
| 스코프 | `Vehicle`마다 `CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("teslable-$vinMasked"))`. `disconnect()`가 취소 | `Dispatcher.Start/Stop` |
| 수신 루프 | 스코프 안의 코루틴 1개가 `transport.incoming.collect { dispatcher.process(it) }`. 세션 갱신·복호화·채널 전달이 이 코루틴에서 순차 실행. **여기서 suspend 대기 금지**(`trySend`만) | `listen` goroutine |
| 핸들러 등록 | `Mutex`로 보호되는 `Map<PendingKey, PendingRequest>` | `handlerLock` |
| 세션 | `Mutex`로 보호되는 `Map<VehicleDomain, SessionState>`; `SessionState` 내부 `Mutex`(Signer 상태) + `CompletableDeferred<Unit> ready`. 잠금 순서: sessions → state | `sessionLock` → `session.lock` |
| VCSEC 직렬화 | `Vehicle` 수준 `Mutex vcsec`. `readUntil`이 끝날 때까지 다음 VCSEC 요청 대기. Infotainment는 병렬 허용 | 프록시 `lockVIN`, 매뉴얼 `00-agent-guide §3.1-2` |
| 핸드셰이크 병렬 | `coroutineScope { domains.map { async { startDomain(it) } }.awaitAll() }`; 하나가 실패하면 나머지 취소, 첫 non-cancel 오류 반환 | `StartSessions` |
| 타임아웃 | 명령별 `withTimeoutOrNull(timeout)`. 전송 전 타임아웃 → `Failure(Timeout(beforeSend))`, 전송 후 → `Uncertain(Timeout)`. 핸드셰이크·연결도 각각 기본값 | `ctx` deadline |
| 취소 | 호출 코루틴 취소 → `CancellationException` 전파. 전송 후 취소된 명령의 `PendingRequest`는 `finally`에서 해제 | `ctx.Done()` |
| 백프레셔 | `PendingRequest.channel = Channel(10)`, 가득 차면 드롭 + 로그. 전송 `Mutex`로 write 순서 보장 | `receiverBufferSize`, `Connection.lock` |
| 스레드 안전 공개 API | `Vehicle` 메서드는 어느 스레드에서든 호출 가능. 콜백은 없고 `Flow`/`StateFlow`만 노출 | – |

---

## 6. 에러 모델과 재시도 정책

```kotlin
public sealed interface VehicleError { val message: String; val mayHaveSucceeded: Boolean; val temporary: Boolean }
// 프로토콜 계층 (pkg/protocol/error.go)
public data class ProtocolFault(val fault: MessageFault) : VehicleError
        // temporary = fault in {BUSY, TIMEOUT, INVALID_SIGNATURE, INVALID_TOKEN_OR_COUNTER, INTERNAL, INCORRECT_EPOCH, TIME_EXPIRED, TIME_TO_LIVE_TOO_LONG}
        // mayHaveSucceeded = fault in {NONE, RESPONSE_MTU_EXCEEDED}
public object KeyNotPaired : VehicleError                 // UNKNOWN_KEY_ID 또는 SessionInfo.status KEY_NOT_ON_WHITELIST
public object Busy : VehicleError                          // operation_status WAIT / VCSEC commandStatus WAIT  (temporary)
public object UnknownResponse : VehicleError               // ErrUnknown
public object NotConnected, NoSession, RequiresKey : VehicleError
public data class BadResponse(val detail: String) : VehicleError   // 파싱 실패. VCSEC 응답 파싱 실패는 mayHaveSucceeded = true (Go vcsec.go)
// 애플리케이션 계층
public data class KeychainRejected(val code: WhitelistOperationInformation) : VehicleError
public data class VcsecRejected(val error: GenericError) : VehicleError            // nominalError
public data class InfotainmentRejected(val reason: String) : VehicleError          // actionStatus ERROR + plain_text ("unspecified error" 기본)
// 전송·플랫폼 계층 (어댑터가 변환)
public sealed interface TransportError : VehicleError { ScanTimeout, MaxConnectionsExceeded, ConnectFailed(cause), Disconnected, BluetoothOff, PermissionDenied, WriteFailed(cause, temporary=true) }
public sealed interface KeyStoreError : VehicleError { HardwareUnavailable, KeyNotFound, PlatformFailure(cause) }
public data class Timeout(val afterSend: Boolean) : VehicleError                   // mayHaveSucceeded = afterSend, temporary = true
public data class InvalidArgument(val detail: String) : VehicleError               // PIN 형식, 볼륨 범위, 좌석 조합 등
public data class ReplayedResponse : 내부 전용 (드롭, 공개 안 함)
```

- `shouldRetry(e) = !e.mayHaveSucceeded && e.temporary` (Go `ShouldRetry`).
- `VehicleResult` 매핑: `mayHaveSucceeded` → `Uncertain`, 그 외 → `Failure`.
- Go의 `GetError` 순서를 그대로 유지: `signed_message_fault` → `session_info.status` → `operation_status`. RoutableMessage 계층의 `OPERATIONSTATUS_ERROR`는 Go와 같이 **오류로 취급하지 않는다**(`error.go:257-263` 스위치가 빈 case로 통과).
- 재시도 간격은 `Transport.retryInterval`(BLE 1초). 재시도 횟수 상한 없이 `timeout` 안에서 반복한다(Go와 동일).

---

## 7. 데이터

### 7.1 세션 캐시 스키마 (D26)

```
CachedSessions v1 (little-endian 아님, 모두 big-endian; :domain 순수 Kotlin 코덱)
  magic "TBSC"(4) | version u8 = 1 | keyId(20: SHA1(publicKey 65B)) | count u8
  entry × count: domain u8 | createdAtEpochMillis i64 | infoLen u16 | Signatures.SessionInfo bytes
```

- `Signatures.SessionInfo{counter, publicKey(차량), epoch, clock_time}`의 `clock_time`은 Go `ExportSessionInfo`와 같이 저장 시점의 도메인 시각(`timestamp()`), 복원 시 `timeZero = createdAt - clock_time`.
- `keyId`가 현재 키와 다르면 캐시를 무시하고 삭제한다(Go는 잘못된 캐시를 로드 후 첫 명령 실패로 복구; 우리는 사전 차단).
- 세션 키 K, 개인키, VIN 원문은 저장하지 않는다. 파일명은 `sha1(vin)`.
- 저장 시점: 핸드셰이크 완료, 세션정보 갱신, `disconnect()`. 각 저장은 `Dispatchers.IO`(Android) / 기본 디스패처(iOS)에서 수행하고 실패해도 명령 결과에 영향을 주지 않는다(로그만).

### 7.2 키 메타데이터

`VehicleKeyStore`는 별칭(`alias`, 기본 `"teslable-default"`)으로 키를 찾는다. 키마다 `KeyProtection`(SECURE_ENCLAVE / STRONGBOX / TEE / SOFTWARE), 생성 시각, 공개키 65바이트를 노출한다. Android 소프트웨어 대체 키는 `filesDir/teslable/keys/<alias>.enc`(Keystore AES-GCM 키로 암호화)에 둔다. iOS 소프트웨어 대체 키는 Keychain(`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`)에 둔다.

### 7.3 공개 값 객체 ↔ protobuf

| 값 객체 | 원본 protobuf | 비고 |
|---|---|---|
| `VehicleStatus` | `VCSEC.VehicleStatus` | 잠금 상태, 도어 4, 프렁크·트렁크, 충전구, 톤노(+%), 수면, 탑승자. 알 수 없는 enum은 `UNKNOWN` |
| `WhitelistSummary`, `WhitelistEntry` | `VCSEC.WhitelistInfo`, `WhitelistEntryInfo` | 공개키, 역할, 형태, 슬롯 |
| `SessionInfoSummary` | `Signatures.SessionInfo` | status, counter, epoch(hex), clockTime |
| `VehicleData` | `CarServer.VehicleData` (Wire) | 그대로 노출 |
| `KeyRole`, `KeyFormFactor`, `StateCategory`, `SeatPosition`, `Level`, `ClimateKeeperMode`, `ChargingPolicy` 등 | 각 enum | Go의 상수와 같은 값 |

---

## 8. 플랫폼 계층

### 8.1 버전 고정 (2026-09-26 확인, M0에서 `gradle/libs.versions.toml`에 기록)

| 구성 요소 | 버전 | 비고 |
|---|---|---|
| Kotlin / KGP | 2.4.20 | K2. Gradle 9.6, JDK 17 |
| kotlinx-coroutines | 1.11.0 | |
| Android Gradle Plugin | 9.3.1 | KGP 2.4.20의 공식 지원 상한. `minSdk 31`, `compileSdk 37` (Compose BOM 2026.09.00 요구, M0 Task 13에서 상향) |
| Wire | 7.0.4 | `wire-runtime` iOS 아티팩트 있음. `google.protobuf.Timestamp` → `com.squareup.wire.Instant` |
| Kable | 0.45.0 | Kotlin 2.4.10으로 빌드. `Filter.Name.Exact`, `Advertisement.isConnectable`, `WriteType.WithResponse`, Android `requestMtu`, Apple `maximumWriteValueLengthForType` |
| SKIE | 0.10.15 | Kotlin 2.4.20 지원. `:sdk`에만 적용 |
| cryptography-kotlin | 0.6.0 | `cryptography-provider-cryptokit`(iOS AES-GCM). `dev.whyoleg.swiftinterop` 플러그인 필요, iOS 14+ |
| binary-compatibility-validator | 0.18.2 | `apiCheck` / `apiDump` |
| detekt | 1.23.8 | 경고 0 |
| ktlint / kotlinter | 1.8.0 / 5.7.0 | |
| Compose BOM (샘플) | 2026.09.00 | material3 1.4.0 |
| iOS 배포 타깃 | 16.0 | `iosArm64`, `iosSimulatorArm64` |

버전을 올릴 때는 SKIE ↔ Kotlin, Kable ↔ Kotlin 조합을 먼저 확인한다(SKIE는 Kotlin 버전마다 별도 릴리스).

### 8.2 키스토어 (FR-020, FR-021, FR-028, D10, D27)

| | Android | iOS |
|---|---|---|
| 생성 | `KeyPairGenerator("EC", "AndroidKeyStore")`, `KeyGenParameterSpec(alias, PURPOSE_AGREE_KEY)`, `setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))`. 1차 `setIsStrongBoxBacked(true)` → `StrongBoxUnavailableException`이면 2차 TEE → 실패 시 소프트웨어 대체 | `SecKeyCreateRandomKey` with `kSecAttrKeyTypeECSECPrimeRandom`, 256비트, `kSecAttrTokenIDSecureEnclave`, 접근 제어 `SecAccessControlCreateWithFlags(kSecAttrAccessibleWhenUnlockedThisDeviceOnly, kSecAccessControlPrivateKeyUsage)` → 실패(시뮬레이터 등) 시 토큰 없는 Keychain 소프트웨어 키 |
| 보관 수준 판정 | `KeyInfo.securityLevel` (API 31+): `STRONGBOX` / `TRUSTED_ENVIRONMENT` / `SOFTWARE` | 생성 경로로 판정: SE 성공이면 `SECURE_ENCLAVE`, 아니면 `SOFTWARE` |
| 공개키 | `ECPublicKey.w` → `0x04‖X(32)‖Y(32)` (좌표를 32바이트로 0-패딩) | `SecKeyCopyPublicKey` → `SecKeyCopyExternalRepresentation` (X9.63 65바이트 그대로) |
| ECDH | `KeyAgreement.getInstance("ECDH", "AndroidKeyStore").init(priv); doPhase(peerPub, true); generateSecret()` → 32바이트 X | `SecKeyCopyKeyExchangeResult(priv, kSecKeyAlgorithmECDHKeyExchangeStandard, peerPub, params{})` → 32바이트 X. peerPub는 `SecKeyCreateWithData`(65바이트, `kSecAttrKeyClassPublic`) |
| 삭제 | `KeyStore.deleteEntry(alias)` + 대체 키 파일 삭제 | `SecItemDelete` |
| 스레드 | `Dispatchers.IO` | 기본 디스패처(Security.framework는 스레드 안전) |

소프트웨어 대체 키(Android): `KeyPairGenerator("EC")`로 만든 PKCS#8 개인키를 Keystore의 AES-256-GCM 키(`alias + ".wrap"`)로 암호화해 `filesDir/teslable/keys/<alias>.enc`에 저장. ECDH는 JCA 소프트웨어 `KeyAgreement`. `KeyProtection.SOFTWARE`로 보고.

`EcdhPrivateKey.sharedX`는 항상 32바이트를 반환한다(Go `sharedX.FillBytes(32)`와 동일). 공유점이 무한원점이면 `KeyStoreError.PlatformFailure`.

### 8.3 암호 원시연산 (NFR-005, D24)

| 연산 | Android | iOS |
|---|---|---|
| SHA-1, SHA-256 | `MessageDigest` | `CC_SHA1`, `CC_SHA256` (`platform.CoreCrypto`) |
| HMAC-SHA256 | `Mac("HmacSHA256")` | `CCHmac(kCCHmacAlgSHA256)` |
| AES-128-GCM (12B nonce, 16B tag, AAD) | `Cipher("AES/GCM/NoPadding")` + `GCMParameterSpec(128, nonce)` + `updateAAD` | `cryptography-provider-cryptokit` `AES.GCM` (`createEncryptFunction(associatedData)`), nonce는 라이브러리가 주입 |
| CSPRNG | `SecureRandom` | `SecRandomCopyBytes(kSecRandomDefault)` |
| 상수 시간 비교 | `MessageDigest.isEqual` | Kotlin: 길이 비교 후 XOR 누적(분기 없음) |

`CryptoPrimitives`는 도메인 포트다. 앱은 `TeslaBleConfig.crypto`로 교체할 수 있다(예: Swift CryptoKit 직접 주입). 자체 구현 알고리즘은 어느 경로에도 없다.

### 8.4 BLE 권한과 백그라운드 (FR-006)

| | Android | iOS |
|---|---|---|
| 매니페스트/Info.plist | `BLUETOOTH_SCAN`(`neverForLocation`), `BLUETOOTH_CONNECT`. `INTERNET` 없음(NFR-014) | `NSBluetoothAlwaysUsageDescription` |
| 런타임 확인 | `checkSelfPermission` 두 가지 → 없으면 `TransportError.PermissionDenied`. 어댑터 꺼짐 → `BluetoothOff` | `CBCentralManager.state` → `.unauthorized` → `PermissionDenied`, `.poweredOff` → `BluetoothOff` |
| 백그라운드 | v1 포그라운드 전제. `platform-notes.md`에 제약 명시 | 백그라운드에서는 Local Name 광고가 제한될 수 있어 스캔이 실패할 수 있음. v1 포그라운드 전제 |

### 8.5 산출물과 배포 형태 (D13)

- Android/JVM: `:sdk` AAR + KLIB 변형. `maven-publish` → GitHub Packages(M6). 그 전엔 `mavenLocal`.
- iOS: `:sdk`가 `Teslable` static XCFramework를 만든다(`binaries.framework { baseName = "Teslable"; isStatic = true }`, SKIE 적용, cryptokit 프로바이더의 Swift 코드 포함 → Swift 런타임은 iOS 16에 내장). `Package.swift`는 `binaryTarget`으로 GitHub Release의 zip을 참조(M6).
- 샘플 앱은 M0~M5 동안 Gradle composite / 로컬 프레임워크 경로로 라이브러리를 참조한다.

---

## 9. 테스트 전략 (NFR-001~003)

### 9.1 벡터 테스트 (`:domain` commonTest, JVM + iOS 시뮬레이터)

| 이름 | 입력 → 기대값 | 출처 |
|---|---|---|
| `derivesSharedKeyFromProtocolDocKeys` | client.key × vehicle.pem → `K = 1b2fce19…ea9a` | `03-protocol §7.3` |
| `derivesSessionInfoSubkey` | `HMAC(K,"session info")` = `fceb679e…e300` | §7.4 |
| `serializesHandshakeMetadataTlv` | `{SIG_TYPE=6, VIN, CHALLENGE}` → `000106…ff` | §7.4 |
| `computesSessionInfoTag` | → `996c1fe3…a002` | §7.4 |
| `serializesCommandMetadataWithFlags` | `{5, 3, VIN, epoch, 2655, 7, flags=2}` → `000105…070400000002ff` | §8.2.1 |
| `encryptsHvacOnLikeProtocolDoc` | nonce `dbf79447…caed` → ct `38038e8c0f2e`, tag `c228e0ff…96c5` (**정본**) | `protocol_doc_test.go` |
| `omitsFlagsTagWhenFlagsZero` | flags=0 → `…050400000007ff`, 태그 `8e128da1…f82a` (PoC 보조 케이스) | PoC |
| `computesLocalNameFromVin` | `5YJS0000000000000` → `S1a87a5a75f3df858C` | `02-ble-transport §2` |
| `truncatesRequestHashForVcsecHmac` | HMAC+VCSEC → 17바이트, GCM → 17바이트 | `peer_test.go TestRequestID` |
| `decryptsResponseRoundTrip` | 응답 AAD `SHA256(TLV{9,…})`로 왕복 | PoC |
| `slidingWindowAcceptsAndRejects` | 10,12,11,11,12,50 → T,T,T,F,F,T + `window_test.go` 케이스 | `window_test.go` |
| `reassemblesAcrossChunksAndResetsAfterTimeout` | 20바이트 청크 3개 → 원문; 1초 초과 → 리셋; 길이>1024 → 폐기 | `02-ble-transport §6` |

### 9.2 골든 픽스처 (D30)

- 수집: 사용자가 실차에서 `tesla-control -ble -vin $VIN -debug <cmd>`로 TX/RX hex를 얻는다(M3 이후 제공). 명령: `list-keys`, `body-controller-state`, `session-info … vcsec`, `lock`, `unlock`, `climate-on`, `state charge`.
- 마스킹: VIN 17자는 `5YJ30123456789ABC`(테스트 VIN)로 치환하되 **바이트 길이가 같으므로** TLV 길이는 유지. 실제 키·세션 정보가 있는 캡처는 커밋하지 않는다(비밀 스캔 게이트).
- 저장: `{{FIXTURES_DIR}}` 아래 Kotlin `object` 상수(`GoldenFixtures.ListKeysTx = "3202…"`).
- 검증: 랜덤 필드(uuid, routing_address, nonce)를 `FixedRandom`으로 주입해 인코딩 바이트가 TX와 일치하는지, RX를 디코딩해 기대 필드가 나오는지 확인.

### 9.3 FakeVehicle (`:testing`)

`verifier.go`와 `dispatcher_test.go`의 `dummyConnector`를 합친 결정적 시뮬레이터. 도메인(VCSEC/INFOTAINMENT)마다
`:testing`의 `TestVerifier.kt`를 하나씩 감싸 verifier.go의 GCM 경로를 재사용한다(M1에서 이미 구현·검증됨).

```
FakeVehicle(vin, clock: TestClock, random: FixedRandom, crypto)
  domains: VCSEC, INFOTAINMENT 각각 { verifier: TestVerifier, whitelist }
  transport(): FakeTransport   // Transport 구현. send(bytes) → 프레이밍 없이 RoutableMessage 파싱 → handle → incoming으로 응답
  handle(msg):
    session_info_request → SignedSessionInfo(challenge=msg.uuid) (verifier.go SetSessionInfo)
    인증 명령 → Verify(verifySessionInfo: epoch/expires/counter+window → INCORRECT_EPOCH/TIME_EXPIRED/INVALID_TOKEN_OR_COUNTER/TIME_TO_LIVE_TOO_LONG; verifyGCM → INVALID_SIGNATURE) 실패 시 fault + 동봉 session_info
    성공 → 스크립트된 응답(들). flags&2 이면 Verifier.Encrypt(response, requestHash, counter++)로 AES_GCM_Response 암호화
  스크립트 API (테스트가 조작):
    sleepInfotainment()/wake()      – Infotainment 응답 드롭 (수면)
    dropNextReplies(n)              – 응답 유실 → Uncertain
    respondVcsec(sequence)          – WAIT, WAIT, 최종 / ERROR 단독 / nominalError / whitelistOperationStatus
    rotateEpoch()                   – 재부팅
    regressClock(seconds)           – clock 역행 세션정보
    corruptNextSessionInfoTag()     – HMAC 불일치
    replayLastResponse()            – 같은 counter 재전송
    setConnectable(false)           – 슬롯 초과 광고
    pairingResponses(...)           – ToVCSECMessage에 대한 원시 FromVCSECMessage 응답
```

시간은 `TestClock.advance()`로만 흐른다. `retryInterval`은 FakeTransport에서 1ms로 줄여 테스트를 빠르게 한다(Go `dummyConnector.RetryInterval = 1ms`와 동일).

### 9.4 Go 클라이언트 측 테스트 포팅 목록

| 마일스톤 | 원본 | 포팅 대상 (Go) | Kotlin (완료) |
|---|---|---|---|
| M1 | `metadata_test.go` | `TestOutOfOrder`, `TestValueTooLong`, `TestCheckSum`, `TestHash512CheckSum`(HMAC 컨텍스트 = 우리는 `serialize` 후 HMAC이라 별도 이식 없음) | **완료 — M0.** `MetadataTest.rejectsOutOfOrderTags`, `.rejectsValueLongerThan255`, `.sha256ChecksumMatchesGoTestVector` |
| M1 | `window_test.go` | `TestSlidingWindow` | **완료 — M0.** `SlidingWindowTest.matchesGoWindowTable`(+ 경계값 테스트 4개) |
| M1 | `signer_test.go` | `TestUpdateSessionInfo`, `TestBadSessionInfoProto`, `TestBadSessionInfoTag`, `TestUpdateSessionInfoBadChallenge`, `…BadCounter`, `…BadEpoch`, `…BadPublicKey`, `TestRemotePublicKey`, `TestUpdateInvalidSessionInfo`, `TestSignerCounterRollover`, `TestNewAuthenticatedSigner`, `TestSetSessionInfo`, `TestExportImport`, `TestImportWrongTime`, `TestInvalidExpirationTime` (15개) | **완료 — M1.** `SignerTest`: `.acceptsValidSignedSessionInfo`(`TestUpdateSessionInfo`), `.rejectsTamperedSessionInfoProto`(`…BadSessionInfoProto`), `.rejectsTamperedTagAndChallenge`(`…BadSessionInfoTag`+`…BadChallenge`), `.rejectsReencodedCounterAndEpoch`(`…BadCounter`+`…BadEpoch`), `.rejectsImposterVehicleKey`(`…BadPublicKey`), `.exposesVehiclePublicKey`(`TestRemotePublicKey`), `.authenticatedCreationChecksProtoThenTag`(`TestNewAuthenticatedSigner`), `.acceptsSessionInfoAttachedToMessage`(`TestSetSessionInfo`), `.exportRoundTripsThroughImportWithAge`(`TestExportImport` 상태 부분). `SignerCryptoTest`: `.staleSessionInfoDoesNotRollBackVerifier`(`TestUpdateInvalidSessionInfo`), `.refusesToEncryptAfterCounterRollover`(`TestSignerCounterRollover`), `.exportedSessionResumesAfterThirtyMinutes`(`TestExportImport` 암복호화 부분), `.importWithWrongAgeExpiresImmediately`(`TestImportWrongTime`), `.rejectsExpirationBeyondEpochLength`(`TestInvalidExpirationTime` — BLE는 `AuthorizeHMAC`을 쓰지 않으므로(D6) `encrypt`로 같은 경계를 검증) |
| M1 | `peer_test.go` | `TestRequestID` | **완료 — M0.** `RequestHashTest.truncatesHmacTagTo16BytesForVcsec` |
| M1 | `protocol_doc_test.go` | `TestProtocolDocAESGCMExample` | **완료 — M0(`Session.encrypt`) + M1(`Signer.encrypt`).** `ProtocolVectorTest.encryptsHvacOnLikeProtocolDoc`, `SignerCryptoTest.reproducesProtocolDocHvacVector` |
| M1 | `native_test.go` | `TestSharedSecretPadding`(X 좌표 0-패딩), `TestLocalPublicBytes` | **완료 — M0에 작성, M1(PR #19)에서 서로 맞물리는 키 쌍으로 교체.** `:adapter-crypto` `SoftwareEcdhKeyTest.sharedXIsZeroPaddedTo32Bytes`; `SignerTest.exposesVehiclePublicKey` |
| M1 | `pkg/protocol/error.go`(`GetError`), `error_test.go` | `TestWrappedErrorClassification`, `TestRetriableError` | **완료 — M1.** `VehicleErrorTest.shouldRetryIsFalseWhenCommandMayHaveSucceeded`, `.classifiesEveryMessageFaultLikeGo`, `.messagesAreEnglishAndCarryCodes`(D19). `ResponseClassifierTest`(9개)는 같은 `GetError`를 이식하며, Go에는 없는 Wire `unknownFields`(모르는 enum 값) 분류를 추가로 검증한다(`unknownFaultBecomesUnknownResponse` 등 — SDD §12) |
| M1 | `verifier_test.go`(GCM 경로만) | `TestGCMKnown` | **완료 — M1.** `TestVerifierTest.decryptsMessageProducedByGoSigner`(`GoVectors` 상수로 재현) |
| M2 | `verifier_test.go` 중 클라이언트 의미가 있는 것(FakeVehicle을 통해 검증) | `TestGCMWindow`, `TestGCMOutOfOrderMessage`, `TestGCMFlags`, `TestEpochChange`, `TestGCMExpired`, `TestGCMInvalidEpoch`, `TestGCMCorruptedCiphertext`, `TestVerifierEncryption` — FakeVehicle 동작 검증용 | – |
| M2 | `dispatcher_test.go` | 20개 전부 (`TestSendWithoutSession` … `TestCache`) | – |
| M2 | `pkg/vehicle/vehicle_test.go`, `vcsec_test.go`, `security_test.go` | `TestVehicle*` 8개, `TestNominalVSCECError`, `TestGibberishVCSECResponse`, `TestWhitelistOperationError`, `TestValidPIN` | – |
| M2 | `pkg/cache/cache_test.go` | `TestImportExport`(자체 포맷), `TestEviction`은 해당 없음(VIN당 1파일) | – |

### 9.5 어댑터 통합 테스트 (수동·야간)

- Android instrumented: Keystore 키와 소프트웨어 키가 같은 상대 공개키에 대해 같은 K를 만드는지(Keystore ECDH 정합성), StrongBox/TEE/소프트웨어 대체 경로.
- iOS XCTest(샘플 앱 타깃): Secure Enclave 키 ECDH vs 소프트웨어 키 ECDH 정합성, cryptokit GCM 벡터.
- Kable 스파이크(M3 첫 작업): 실차에서 0213 구독 방식(notify/indicate)과 MTU 협상 결과를 로그로 확인. 실패 시 ADR-0003 대체 경로.

### 9.6 실차 체크리스트

`{{WORKFLOW_FILE}}` §8.4의 9개 시나리오에 **페어링 진행 이벤트(D28) 관찰**을 추가한다: `addKeyRequest` 수집 중 `WaitingForTap`/`Accepted`가 실제로 오는지, 오지 않으면 `Unknown(raw)` 바이트를 보고.

---

## 10. 로깅과 보안 (FR-112, NFR-004~006)

- `TeslaLogger` 포트. 기본 `NoOp`. 샘플 앱은 `DebugLogger`(플랫폼 로그)를 주입.
- `DEBUG` 레벨에서만 `TX: <hex>` / `RX: <hex>`(암호문 그대로, Go `-debug`와 동일). `INFO`는 세션·연결 이벤트, `WARN`은 드롭·폐기 사유(Go 로그 문구 유지: "Discarding unauthenticated session info" 등).
- VIN은 모든 로그에서 `Vin.toString()` 마스킹(`5YJ**********9ABC`). 로컬 이름(`S…C`)은 VIN의 해시라 그대로 남겨도 된다.
- 개인키(핸들만 존재), 공유 X, K, 서브키, 복호화 전 페이로드는 어떤 레벨에서도 로그에 쓰지 않는다. `Session.toString()`은 공개키 지문만 출력.
- `Session.close()`는 K와 서브키 배열을 0으로 덮는다. `Vehicle.disconnect()`가 모든 세션을 닫는다.
- `protocol.md` 테스트 키는 `:testing`에만 존재하고 `:sdk`에서 참조하지 않는다(detekt `ForbiddenImport`로 `:testing` → 운영 모듈 유입 차단).
- 비밀 스캔 CI 게이트: `[A-HJ-NPR-Z0-9]{17}` VIN 패턴은 테스트 VIN(`5YJ30123456789ABC`, `5YJS0000000000000`, `0123456789ABCDEFG`) 허용 목록만 통과.
- 서버 없음: Android 매니페스트에 `INTERNET` 권한이 없어야 하며, CI가 확인한다.

---

## 11. ADR 목록

| ADR | 제목 | 결정 |
|---|---|---|
| 0001 | 헥사고날 모듈 경계와 Gradle 강제 | D21 |
| 0002 | protobuf: Wire | D22 |
| 0003 | BLE: Kable을 Transport 포트 뒤에, indication 강제 방안과 대체 경로 | D23 |
| 0004 | iOS AES-GCM: cryptography-kotlin CryptoKit 프로바이더 | D24 |
| 0005 | Swift 표면: SKIE | D25 |
| 0006 | 결과 모델: sealed `VehicleResult` | D20 |
| 0007 | 세션 캐시: 포트 + 플랫폼 기본 구현 + 자체 포맷 v1 | D26 |
| 0008 | 키 보관과 세션 키 메모리 정책 | D27 |
| 0009 | add-key-request 응답 읽기 (원본과 다른 동작) | D28 |
| 0010 | 타임아웃 파라미터와 취소 전파 | D29 |
| 0011 | 골든 픽스처를 Kotlin 상수로 | D30 |

---

## 12. 매뉴얼과 원본의 불일치 (Phase 2까지)

| # | 매뉴얼 위치 | 원본 위치 | 내용 | 처리 |
|---|---|---|---|---|
| 1 | `03-protocol.md` §9.2 "응답 메타데이터 (AAD)" 첫 문장 | `internal/authentication/peer.go` `responseMetadata` (`newMetadata()` = SHA-256 컨텍스트, `Checksum(nil)` 반환) | 매뉴얼은 "AAD는 SHA-256 해시가 아니라 직렬화 바이트 자체"라고 굵게 적었으나, 같은 문단 괄호와 원본 코드는 `SHA256(TLV‖0xFF)`를 AAD로 사용 | **원본을 따름**(SHA-256 다이제스트). §3.3, PoC `decryptResponse`도 동일. 사용자에게 보고. 매뉴얼 문장 수정은 승인 후 |

HANDOFF ↔ 원본 불일치는 `{{PRD_FILE}}` 부록 A에 있다. 매뉴얼의 나머지 내용은 이번 설계에서 인용한 범위(`01`, `02`, `03`, `05`, `08`, `10`)에서 원본과 일치했다.

### 원본과 다른 동작 (의도)

- `Framer.frame`는 1024바이트를 넘는 메시지를 `IllegalArgumentException`으로 거부한다. Go `Connection.Send`(`pkg/connector/ble/ble.go`)는 검사하지 않지만 차량이 1024바이트 초과 메시지를 버리므로(`maxBLEMessageSize`) 정상 입력의 wire 바이트는 동일하다. 근거: NFR-017, 최종 리뷰 M-5.
- `Signer.encrypt`는 `expiresIn`으로 계산한 만료 초가 음수이거나 2^30(`CommandMetadata.EPOCH_LENGTH_SECONDS`)을 넘으면 `BAD_PARAMETER`로 거부한다. Go는 먼저 `uint32`로 잘라서 2^32 이상이면 wrap된 값으로 검사한다. 정상 수명에서는 도달 불가.
- `Signer.decrypt`의 인증 실패는 `SignerResult.Fault(INVALID_SIGNATURE)`다. Go는 AEAD 오류를 그대로 돌려준다(둘 다 드롭 대상이라는 결과는 같다).
- `ResponseClassifier`는 Wire가 모르는 enum 값(`unknownFields`)을 `VehicleError.UnknownResponse`로 분류해 Go `GetError`의 `default:` 분기와 같은 분류(temporary=false, mayHaveSucceeded=false)를 따르되, 모르는 fault의 원시 코드는 싣지 않는다(Go는 `RoutableMessageError{Code}`로 코드를 보존한다).
- `Signer.createAuthenticated`는 태그 검증이 실패하거나 예외가 나면 세션 키를 0으로 지운다(Go는 GC에 맡긴다). `Signer.importSessionInfo`는 음수 `age`를 거부한다(Go는 미래의 `generatedAt`을 허용한다).
- `Signer.decrypt`는 nonce·태그 길이가 틀린 응답을 `Fault(INVALID_SIGNATURE)`로 돌려준다(M0 `Session.decrypt`가 null을 돌려주기 때문). Go `gcm.Open`은 nonce 길이가 틀리면 panic한다.
- Wire는 모르는 enum 값을 기본값(fault NONE, domain null→BROADCAST)으로 디코딩하므로, 새 펌웨어가 모르는 fault 코드나 도메인을 실은 **암호화 응답**은 응답 AAD가 달라져 `INVALID_SIGNATURE`로 드롭된다. Go는 원시 uint32를 써서 복호화한다. M2에서 응답 메타데이터를 만들 때 `unknownFields`의 원시 값을 쓰도록 보완한다(인계 노트에도 기록).
