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
 :testing (commonMain, 테스트 전용 의존): FakeVehicle, FakeTransport, FixedRandom,
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
| `internal/dispatcher/dispatcher.go`, `session.go`, `receiver.go` | 729 | `:application` `dispatcher/Dispatcher.kt`, `SessionState.kt`, `PendingRequest.kt`, `HandshakeFlow.kt` | goroutine → 코루틴, `chan` → `Channel(10)`, `readySignal` → `CompletableDeferred` |
| `pkg/connector/connector.go` | 72 | `:domain` `port/Transport.kt` | `send`는 `VehicleResult<Unit>`(값), `AuthMethod`는 NONE/GCM |
| `pkg/connector/ble/ble.go` | 360 | `:domain` `transport/Framer.kt`, `Reassembler.kt`, `LocalName.kt` + `:adapter-ble` `KableTransport.kt`, `KableScanner.kt` | 프레이밍·재조립·이름은 순수 Kotlin(PoC), GATT는 Kable |
| `pkg/protocol/error.go` | 267 | `:domain` `ResponseClassifier.kt`(`GetError` → `protocolError`), `VehicleError.kt`(`MayHaveSucceeded`/`Temporary` → `mayHaveSucceeded`/`temporary` 프로퍼티, `ShouldRetry` → `shouldRetry()` 확장) | `GetError`, `ShouldRetry`, `MayHaveSucceeded`, `Temporary` |
| `pkg/protocol/key.go` | 173 | `:domain` `port/EcdhPrivateKey.kt`, `PublicKey.kt` | 파일 로딩은 포팅하지 않음. 65바이트 검증만 |
| `pkg/vehicle/vehicle.go` | 275 | `:application` `vehicle/VehicleSession.kt`, `SendWithRetry.kt`, `CommandTimeouts.kt` | `Send`, `trySend`, `StartSession`, `SessionInfo`, `Wakeup` |
| `pkg/vehicle/vcsec.go`, `security.go`(VCSEC 부분), `state.go`(`BodyControllerState`) | ~450 | `:application` `vcsec/VcsecResponses.kt`, `VcsecCommands.kt`(M2: `unmarshalVCSECResponse`, `readUntil`, `getVCSECResult`; 명령 빌더는 M4), `keys/KeyManagement.kt`, `pairing/Pairing.kt` | `unmarshalVCSECResponse`, `readUntil`, 종료 판정, `addKeyPayload`, `SendAddKeyRequestWithRole` |
| `pkg/vehicle/infotainment.go`, `climate.go`, `charge.go`, `actions.go`, `security.go`(INFO 부분), `state.go`(`GetState`) | ~1,200 | `:application` `infotainment/InfotainmentResponses.kt`, `InfotainmentCommands.kt`(M2: `getCarServerResponse`) (그 밖의 영역별 파일은 M4~M5) | `getCarServerResponse`, 명령별 `Action` 조립 |
| `pkg/cache/cache.go`, `session.go` `CacheEntry` | 113+ | `:domain` `port/SessionCache.kt`, `cache/CachedSession.kt`, `cache/SessionCacheCodec.kt` + `:adapter-storage` `storage/InMemorySessionCache.kt` + `:application` `cache/SessionCacheSync.kt` | 포맷은 자체(D26) |
| `internal/authentication/verifier.go` | 328 | `:testing` `TestVerifier.kt`(M1, GCM 경로만), `FakeVehicle.kt`(M2, 도메인마다 `TestVerifier` 하나를 감싼다) | 차량 측 검증·응답 암호화 재현 |
| `internal/log` | – | `:domain` `port/TeslaLogger.kt` | Go `internal/log`(Error/Warning/Info/Debug) → `LogLevel`, `TeslaLogger` 함수형 인터페이스, 기본 `NoOp` |
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
    override fun close()                                                                  // 세션 키를 0으로 덮음. 이후 encrypt/decrypt/updateSignedSessionInfo는 IllegalStateException
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
public enum class AuthMethod { NONE, GCM }                // connector.AuthMethod (HMAC 인가는 없음, D6)
public sealed interface TransportState {                  // Connected / Disconnected(reason)
    public data object Connected : TransportState
    public data class Disconnected(val reason: VehicleError.TransportError?) : TransportState
}
public interface Transport {                              // connector.Connector
    public val vin: Vin
    public val incoming: Flow<ByteArray>                  // 재조립된 메시지 단위
    public val state: StateFlow<TransportState>           // Connected / Disconnected(reason)
    public val retryInterval: Duration                    // BLE 1s
    public val allowedLatency: Duration                   // BLE 4s
    public suspend fun send(message: ByteArray): VehicleResult<Unit>  // 값으로(ADR-0006). Uncertain = 차량이 받았을 수 있음
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
// 시계: kotlin.time.TimeSource를 주입한다(Reassembler, Signer, TestVerifier). 벽시계는 세션 캐시(:adapter-storage)의
// createdAt에서만 쓰고 age: Duration으로 변환해 넘긴다. age가 음수면(벽시계가 뒤로 감 — Go에서는 timeZero가 더 나중이 된다) Signer.importSessionInfo가
// 0으로 본다(런타임 조건이므로 예외 없음, ADR-0006).
public class KeyId(bytes: ByteArray /* 20 */) {           // SHA1(공개키 65B) = 세션 캐시 소유자(D26)
    public companion object { public fun of(publicKey: PublicKeyBytes, crypto: CryptoPrimitives): KeyId }
}
public class SessionSnapshot(public val domain: Domain, sessionInfo: ByteArray)                   // 내보내기(저장 시각 없음). Go session.export()
public class CachedSession(public val domain: Domain, sessionInfo: ByteArray, public val age: Duration)  // 불러오기(age = now − createdAt, 음수 가능)
public object SessionCacheCodec {                          // 세션 캐시 바이너리 형식 v1(§7.1). :domain 순수 Kotlin, Go JSON과 호환 없음(D26)
    public const val VERSION: Int = 1
    public fun encode(keyId: KeyId, entries: List<Entry>): ByteArray
    public fun decode(bytes: ByteArray, expectedKeyId: KeyId): List<Entry>?   // 매직·버전·keyId 불일치·잘림·꼬리 바이트면 null
}
public interface SessionCache {                             // pkg/cache (자체 형식 v1, D26)
    public suspend fun load(vin: Vin, keyId: KeyId): List<CachedSession>
    public suspend fun store(vin: Vin, keyId: KeyId, entries: List<SessionSnapshot>)
    public suspend fun clear(vin: Vin)
}
public enum class LogLevel { DEBUG, INFO, WARN, ERROR }     // Go internal/log의 Error/Warning/Info/Debug
public fun interface TeslaLogger {                           // 기본 NoOp
    public fun log(level: LogLevel, tag: String, message: () -> String)
    public companion object { public val NoOp: TeslaLogger }
}
```

`CryptoPrimitives`의 `aesGcmEncrypt`는 nonce를 **인자로 받는다**(테스트 벡터 고정용). 운영 코드는 항상 `RandomSource`에서 12바이트를 새로 뽑아 넘긴다. 이 설계는 Go `NativeSession.Encrypt`(내부에서 nonce 생성)와 다르지만 결과 바이트는 같다(ADR-0008에 기록).

`domain/protocol/UnknownFields.kt`의 `ByteString.unknownVarint(tag)`는 `@InternalTeslableApi`로 공개되어 `:application`(`VcsecResponses`)이 Wire `unknownFields`에서 등록되지 않은 varint 값(예: `whitelistOperationInformation`의 새 코드)을 되찾는 데 쓴다. 같은 `@InternalTeslableApi`로 공개된 `ProtoAdapter<M>.decodeOrNull(bytes)`(`WireDecoding.kt`)는 `IOException`뿐 아니라 Wire가 손상된 입력에 던지는 `IllegalStateException`·`IllegalArgumentException`도 잡아 `null`(값)로 돌려주며, `Dispatcher.process`·`VcsecResponses.interpret`·`InfotainmentResponses.interpret`가 모두 이 함수로 응답 바이트를 디코딩한다.

### 2.2 `:application` — 유스케이스

| 구성 요소 | 책임 | Go 대응 |
|---|---|---|
| `Dispatcher` | `Transport.incoming`을 단일 수신 코루틴에서 소비해 `RoutableMessage`로 파싱 → `PendingRequest` 매칭 → 세션정보 갱신 → 복호화 → 핸들러 채널 전달. `start`/`stop`/`close`로 수신 코루틴 생애주기 관리, `send`(인가+전송+재시도)·`requestSessionInfo`·`exportSessions`·`loadSessions`, `maxLatency` | `dispatcher.go` `New`, `Start`, `Stop`, `Send`, `RequestSessionInfo`, `Cache`, `LoadCache`, `listen`, `process`, `checkForSessionUpdate`, `decrypt` |
| `SessionState` (도메인별) | `Signer` 보유. `Mutex`(임계 구역은 `Signer` 호출뿐) + `CompletableDeferred<Unit>`(`ready`). `processHello`(첫 호출은 `Signer.createAuthenticated`)·`authorize`·`decrypt`·`export`·`loadFromCache` | `session.go` |
| `PendingRequest` | `(address, uuid?, domain)` 키, `Channel<RoutableMessage>(10)`, `SlidingWindow`(`antiReplay`), `requestHash`. `close()`는 suspend하지 않고 플래그 + 채널 닫기만(취소 중에도 안전); 맵에서 지우는 것은 `Dispatcher`가 `register`/`lookup` 때 지연 수행 | `receiver.go` |
| `HandshakeFlow` | `startSession(domain)`: 세션 준비(`ready`)·응답·재전송 간격(`select`) 중 먼저 오는 것을 처리, 캐시 복원 세션은 즉시 성공. `startSessions(domains)`: 도메인마다 병렬, 첫 실패에 나머지 취소 | `StartSession`, `tryStartSession`, `StartSessions` |
| `SendWithRetry` | 응답 하나를 기다리는 명령(Infotainment, 세션정보 등). `shouldRetry()` 오류면 `retryInterval` 후 새 counter·nonce로 재인가해 재전송. 시간 초과는 응답 대기 중이면 `Uncertain(Timeout(afterSend=true))`, 그 전이면 `Failure(Timeout(afterSend=false))`(D29) | `vehicle.go` `Send`, `trySend` |
| `VcsecCommands` | 페이로드를 VCSEC로 보내고 `VcsecResponses.readUntil`로 종료까지 읽는다. 한 연결의 VCSEC 명령은 인스턴스 안 `Mutex`로 직렬화(FR-049) — 생성자가 `internal`이라 `VehicleSession`이 연결마다 정확히 하나만 만든다 | `vcsec.go` `getVCSECResult` |
| `VcsecResponses` | `interpret`(Go `unmarshalVCSECResponse`): 프로토콜 오류 → payload 종류 → 파싱 → `nominalError` → `commandStatus` 순 해석. `readUntil`(Go `readUntil`). `TerminalTest` 3종: `FIRST_MESSAGE`, `COMMAND_STATUS_ABSENT`, `WHITELIST_OPERATION_COMPLETE` | `vcsec.go` `unmarshalVCSECResponse`, `readUntil`, `isWhitelistOperationComplete` |
| `InfotainmentCommands` | `Action`을 Infotainment에 GCM으로 보내고 단일 응답을 `InfotainmentResponses.interpret`로 해석. M5가 명령별 빌더를 더한다 | `infotainment.go` `executeCarServerAction` |
| `InfotainmentResponses` | `interpret`(Go `getCarServerResponse`): 파싱 실패 → `BadResponse(mayHaveSucceeded=true)`; `actionStatus.result == ERROR` → `InfotainmentRejected`; 그 외(모르는 값 포함)는 성공 | `infotainment.go` `getCarServerResponse` |
| `SessionCacheSync` | `load(dispatcher)`: `SessionCache.load` → `Dispatcher.loadSessions`. `store(dispatcher)`: `Dispatcher.exportSessions` → `SessionCache.store`(세션이 없으면 빈 목록도 저장). 저장 시점은 핸드셰이크 완료·`disconnect()`뿐이다(§3.4/§7.1) — 세션정보 갱신 시 저장은 M3 파사드가 연결한다 | `NewVehicle`(캐시 로드), `UpdateCachedSessions` |
| `VehicleSession` | `connect()`(캐시 복원 + 수신 시작), `startSession(domains, timeout)`(재시도 + 성공 시 캐시 저장), `disconnect()`(캐시 저장 1회 + `Dispatcher.close`). `handshake`/`send`/`vcsec`/`infotainment`를 조립해 들고 있다 | `vehicle.go` `NewVehicle`, `Connect`, `StartSession`, `Disconnect` |
| `CommandTimeouts` | `commandTimeout`(5s)·`commandLifetime`(5s, `expires_at`)·`handshakeTimeout`(20s). M3 `TeslaBleConfig`가 채운다 | `tesla-control` 플래그 기본값 |
| `KeyManagement`(M4) | `keySummary`, `keyInfoBySlot`, `listKeys`(슬롯 순회), `addKey`, `removeKey`, `sessionInfo(publicKey, domain)` | `security.go`, `vehicle.go` `SessionInfo` |
| `Pairing`(M4) | `ToVCSECMessage{PRESENT_KEY}` 직접 전송(RoutableMessage 아님) + 원시 프레임 구독으로 `FromVCSECMessage.commandStatus` 진행 상태 방출(D28) | `SendAddKeyRequestWithRole` + 확장 |

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

`FakeVehicle`(§9.3), `FakeTransport`, `FixedRandom`(시퀀스 주입), `SoftwareEcdhKey`, `ProtocolVectors`(`protocol.md` 값), `GoldenFixtures`(Kotlin 상수, D30). 시간은 클래스가 아니라 `kotlinx-coroutines-test`의 `testTimeSource`(`TestTimeSource`)를 `FakeVehicle`/`Dispatcher`에 그대로 주입해 흐른다. 이 모듈은 다른 모듈의 `commonTest`에서만 의존한다.

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
Vehicle.startSession({VCSEC, INFOTAINMENT})              // VehicleSession.startSession → HandshakeFlow.startSessions
  for each domain (병렬 async, HandshakeFlow.startSessions):
    HandshakeFlow.startSession(domain):
      dispatcher.session(domain) 없으면 RequiresKey; 캐시로 ready면 즉시 성공
      loop (tryStartSession):
        pending = dispatcher.requestSessionInfo(domain)   // AuthMethod.NONE. 내부에서 dispatcher.send가 register + transport.send
        withTimeoutOrNull(retryInterval) {
          select { session.ready.onAwait()                → Ready
                   pending.onReceive()                     → Reply(msg) }
        } ?: Retry                                          // 시간이 다 되면 재시도(다음 while 반복이 새 pending으로 재전송)
        Reply(msg) → ResponseClassifier.protocolError(msg)?.let { return Failure }
                       ?: withTimeoutOrNull(retryInterval) { session.awaitReady() } ?: 다시 루프
  수신 측 (Dispatcher.process):
    session_info 있음 → checkForSessionUpdate(순서대로 검사, Dispatcher.kt:452-483):
      privateKey == null → 폐기(WARN "does not have a private key")
      handler.expired(maxLatency=4s) → 폐기(WARN "received more than … after request")
      tag 없거나 비었음(`ByteString.EMPTY`) → 폐기(WARN "unauthenticated session info")
      key.domain에 등록된 세션 없음 → 드롭(ERROR "unregistered domain")
      SessionState.processHello(challenge=request_uuid, info, tag):
        최초(signer == null): Signer.createAuthenticated(challenge, encodedInfo, tag) — 태그 검증 포함 → ready.complete()
        기존: updateSignedSessionInfo (공개키 일치, epoch 변경 또는 setTime<=clock_time 일 때만 갱신, counter 비롤백)
        결과 로그: Fault면 WARN, Ok면 INFO
  VehicleSession.startSession 성공 → SessionCacheSync.store(dispatcher)   // 핸드셰이크 완료 시 저장(§3.4/§7.1). 세션정보 갱신 시 저장은 M2에 없음
```

### 3.3 명령과 응답 (FR-010, FR-011, FR-015, FR-016, FR-048, FR-049, FR-100~101)

```
Vehicle.lock(timeout)                                          // VcsecCommands.execute(payload, GCM, COMMAND_STATUS_ABSENT)
  withAttemptTimeout(timeout) {                                 // D29: 전송 전이면 Failure(Timeout(false)), 응답 대기 중이면 Uncertain(Timeout(true))
    serial.withLock {                                           // FR-049: 연결당 VcsecCommands 인스턴스 하나(생성자 internal)
      retryWhileRetriable(retryInterval) {                      // shouldRetry() 오류마다 새 시도(아래 attempt 전체를 다시)
        msg = RoutableMessage(to=VCSEC, payload, flags=2)        // uuid·from_destination은 dispatcher.send가 채운다
        pending = dispatcher.send(msg, AuthMethod.GCM, commandLifetime).valueOr { return it }
          // dispatcher.send 내부: SessionState.authorize(ready.await(); Signer.encrypt(msg, lifetime) — counter++, AAD=SHA256(TLV), nonce=rand12) → register → transport.send
        readUntil(pending, done):
          from = interpret(pending.receive())                    // 프로토콜 오류 → 값으로 반환(재시도는 retryWhileRetriable이 함)
            interpret: FromVCSECMessage 파싱; nominalError → Failure(VcsecRejected); commandStatus.operationStatus WAIT → Failure(Busy)
          done.check(from): Continue(다음 메시지) / Done(Success) / Fail(error)
      }
    }
  }
  // WAIT(Busy)는 shouldRetry() == true이므로 readUntil이 즉시 Failure(Busy)를 반환하고, retryWhileRetriable이 retryInterval 뒤
  // attempt를 처음부터 다시 부른다 — 같은 pending에서 계속 읽지 않고 새 routing_address·counter로 새 요청을 보낸다(Go와 동일).
  수신 측 (Dispatcher.process, 단일 코루틴):
    from_destination 없음 / request_uuid 길이 ≠ 0,16 / to_destination이 domain / address 길이 ≠ 16 → 드롭
    key = (address, uuid if domain != VCSEC else EMPTY, domain); pending 없음 → 드롭("without registered handler", WARN)
    checkForSessionUpdate (위와 동일)
    AES_GCM_Response_data 있음 → SessionState.decrypt(msg, pending.requestHash): AAD = SHA256(TLV{9, domain, VIN, counter, flags(항상), request_hash, fault})
       → pending.antiReplay.update(counter) 실패 → 드롭(중복 응답) / 복호화 실패 → 드롭
    pending.deliver(msg)(= channel.trySend) 실패(가득) → 드롭 로그(ERROR)
```

Infotainment(`climateOn`)는 `serial` 락 없이 같은 경로를 타며(`InfotainmentCommands.execute` → `SendWithRetry.send`), `key.uuid = msg.uuid`, 단일 응답, `CarServer.Response.actionStatus` 해석(`ERROR` → `Failure(InfotainmentRejected(reason))`).

### 3.4 세션 복구 (FR-018)

오류 응답에 동봉된 `session_info`는 위 §3.2 수신 규칙으로 갱신된 뒤 응답이 그대로 핸들러에 전달된다. `SendWithRetry`/`VcsecCommands`가 `shouldRetry()` 오류(INVALID_SIGNATURE, INCORRECT_EPOCH, TIME_EXPIRED, INVALID_TOKEN_OR_COUNTER, BUSY, TIMEOUT, INTERNAL, TIME_TO_LIVE_TOO_LONG, `Busy`)를 보면 `retryInterval` 후 **새 counter·nonce·expires_at으로 재인가**해 재전송한다. M2에서 세션 캐시 저장은 핸드셰이크 완료와 `disconnect()` 두 시점뿐이다(§7.1) — 수신 코루틴은 캐시 I/O로 suspend해서는 안 되므로(§5), 세션정보 갱신 때마다 저장하는 것은 M3 파사드가 연결한다.

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
| 수신 루프 | `Dispatcher.start()`가 띄우는 코루틴 1개가 `transport.incoming.collect { process(it) }`. 세션 갱신·복호화·채널 전달이 이 코루틴에서 순차 실행. **여기서 suspend 대기 금지** — 다른 코루틴의 진행을 기다리는 suspend(채널 receive, `Deferred.await`, `delay`)를 금지한다는 뜻이고, 채널 전달은 `trySend`(비suspend)만 쓴다. **예외 한 가지:** `start()`가 새 수신 코루틴을 띄울 때, 아직 끝나지 않은 이전 정지 대상 job이 있으면 `incoming` 구독 **전**에 그 job의 종료를 `NonCancellable`로 감싸 기다린다(같은 `incoming`을 두 수신 코루틴이 동시에 구독하지 않도록) — 구독 이후에는 이 대기가 없다 | `listen` goroutine |
| 핸들러 등록 | `Mutex`로 보호되는 `Map<PendingKey, PendingRequest>`(`pendingMutex`). `PendingRequest.close()`는 suspend하지 않고 플래그 + 채널 닫기만 한다(취소 중 `finally`에서도 안전) — 맵에서 지우는 것은 `Dispatcher`가 다음 `register`/`lookup` 때 지연 수행한다(Go `closeHandler`는 즉시 맵에서 지운다; 관찰 결과는 같다 — 닫힌 요청에 온 응답은 "without registered handler"로 드롭) | `handlerLock`, `closeHandler` |
| 세션 | `Dispatcher`가 만들 때 `Map<Domain, SessionState>`(도메인마다 하나, 불변 — 잠금으로 보호할 필요 없음). `SessionState` 내부 `Mutex`(Signer 상태) + `CompletableDeferred<Unit> ready`. `Signer` 변경 멤버: `encrypt`, `updateSessionInfo`, `updateSignedSessionInfo`, `close`; 읽기 전용: `decrypt`, `exportSessionInfo`, `timestamp`, `counter`/`epoch`/공개키 getter — 임계 구역이 이 호출들뿐이라 락 대기가 µs 단위로 유계다(설계 구체화 4) | `session.lock` |
| VCSEC 직렬화 | `VcsecCommands` 인스턴스 안의 `Mutex serial`(연결당 하나 — 생성자가 `internal`이라 `VehicleSession`이 정확히 하나만 만든다). `readUntil`이 끝날 때까지(재시도 포함) 다음 VCSEC 요청이 대기. Infotainment는 병렬 허용 | 프록시 `lockVIN`, 매뉴얼 `00-agent-guide §3.1-2` |
| 핸드셰이크 병렬 | `HandshakeFlow.startSessions`: 도메인마다 `async { startSession(it) }`, `select`로 먼저 끝나는 것을 처리; 하나가 실패하면 나머지 `job.cancel()`, 첫 실패 반환 | `StartSessions` |
| 타임아웃 | 명령별 `withAttemptTimeout(timeout)`(D29). `afterSend`는 **현재 시도**의 단계다: 응답을 기다리는 동안 초과했으면 `true`(`Uncertain`), 전송 전이거나 재시도 대기 중(`delay(retryInterval)`)이면 `false`(`Failure`) — 이전 시도에서 응답을 기다렸었는지는 영향을 주지 않는다. 핸드셰이크(`VehicleSession.startSession`)도 같은 헬퍼를 쓰지만 `HandshakeFlow`는 `setAwaiting`을 절대 부르지 않으므로 항상 `afterSend = false`(사용자 승인 답 b) | `ctx` deadline |
| 취소 | 호출 코루틴 취소 → `CancellationException` 전파. 전송 후 취소된 명령의 `PendingRequest`는 `finally`에서 해제(`close()`) | `ctx.Done()` |
| 백프레셔 | `PendingRequest.channel = Channel(10)`, 가득 차면 `deliver()`가 `false`를 돌려주고 디스패처가 **ERROR** 레벨로 드롭 로그를 남긴다(Go `dispatcher.go:313`도 error 레벨 — 동일). 전송은 `Transport` 구현이 자체 순서를 보장 | `receiverBufferSize` |
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
public data class UnknownFault(val rawCode: Int) : VehicleError  // 모르는 signed_message_fault: RoutableMessageError{Code}, "unrecognized error code N"
public object UnknownResponse : VehicleError               // ErrUnknown: 모르는 session_info.status·operation_status (코드 없음)
public object NotConnected, NoSession, RequiresKey : VehicleError
public data class BadResponse(val detail: String) : VehicleError   // 파싱 실패. VCSEC(Go vcsec.go)·Infotainment(Go infotainment.go) 응답 파싱 실패는 mayHaveSucceeded = true
// 애플리케이션 계층
public data class KeychainRejected(val code: WhitelistOperationInformation) : VehicleError
public data class UnknownKeychainCode(val rawCode: Int) : VehicleError             // 모르는 whitelistOperationInformation 코드(더 새 펌웨어). Go KeychainError{Code}와 같은 정보
public data class VcsecRejected(val error: GenericError) : VehicleError            // nominalError
public data class InfotainmentRejected(val reason: String) : VehicleError          // actionStatus ERROR + plain_text ("unspecified error" 기본)
// 전송·플랫폼 계층 (어댑터가 변환)
public sealed interface TransportError : VehicleError { ScanTimeout, MaxConnectionsExceeded, ConnectFailed(cause), Disconnected, BluetoothOff, PermissionDenied, WriteFailed(cause, temporary=true) }
public sealed interface KeyStoreError : VehicleError { HardwareUnavailable, KeyNotFound, PlatformFailure(cause) }
public data class Timeout(val afterSend: Boolean) : VehicleError                   // mayHaveSucceeded = afterSend, temporary = true
public data class InvalidArgument(val detail: String) : VehicleError               // PIN 형식, 볼륨 범위, 좌석 조합 등
// 중복 응답(재전송 응답)은 공개 VehicleError가 아니다: PendingRequest.antiReplay(SlidingWindow)가 실패하면 Dispatcher.process가
// 그냥 드롭하고 로그만 남긴다(핸들러에는 전달되지 않음) — Go도 오류를 만들지 않고 receiver에 전달하지 않는다.
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
- 캐시된 **차량** 공개키가 현재 차량과 다르면(차량 키 교체, 같은 VIN의 다른 캐시) 세션 안에서는 복구하지 않는다(Go와 같음, 공개키 고정 유지). 대신 M3 `connect()`가 그 도메인의 캐시 항목을 지운다. Go는 아무것도 하지 않는다(2026-09-27 사용자 결정, M2 계획 설계 구체화 8). 감지 방법(`UNKNOWN_KEY_ID`, 캐시에서 복원한 세션의 세션정보 태그 실패)은 M3 계획에서 정한다.
- 세션 키 K, 개인키, VIN 원문은 저장하지 않는다. 파일명은 `sha1(vin)`.
- 구현: `SessionCacheCodec`(`:domain`, `encode`/`decode`/`encodeSnapshots`/`decodeSessions`), `InMemorySessionCache`(`:adapter-storage`, 벽시계 `() -> Long` 주입). `age`는 어댑터가 원시값(`nowEpochMillis - createdAtEpochMillis`, 음수 가능)으로 넘기고 클램프하지 않는다 — `Signer.importSessionInfo`가 0으로 본다(설계 구체화 7). 세션이 없으면 빈 목록을 저장한다(Go `Update(nil)`도 옛 캐시를 지운다). 개별 `SessionInfo` 디코딩이 실패한 손상된 항목은 건너뛰고 `Dispatcher.loadSessions`가 WARN 로그를 남긴다. 반면 `SessionCacheCodec.decode`가 Wire가 인식하지 못하는 도메인 원시값의 항목은 로그 없이 조용히 버린다(그 항목은 결과에 실리지도 않는다) — Wire는 알지만 `Dispatcher`에 세션이 없는 도메인(예: `DOMAIN_BROADCAST`)은 여기서 걸러지지 않고 `Dispatcher.loadSessions`까지 도달해 "no session (private key missing)" WARN을 남긴다(`Dispatcher.kt:288` 부근).
- 저장 시점(M2): 핸드셰이크 완료(`VehicleSession.startSession` 성공), `disconnect()`(1회만 — 두 번째 이상 호출은 건너뛴다, `SessionCacheSync`/`VehicleSession` KDoc). **세션정보 갱신 때마다 저장하는 것은 M2에 없다** — 수신 코루틴이 캐시 I/O로 suspend해서는 안 되기 때문이다(§5). 그 트리거는 M3 파사드가 연결한다. `disconnect()`의 저장은 반드시 `Dispatcher.close()`보다 먼저 끝나야 한다 — `close()`가 모든 세션 키를 지운 뒤 저장하면 빈 목록이 이전 저장을 지워 버린다. 각 저장은 실패해도 명령 결과에 영향을 주지 않는다(로그만).

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
`:testing`의 `TestVerifier.kt`를 하나씩 감싸 verifier.go의 GCM 경로를 재사용한다(M1에서 이미 구현·검증됨). Go `dummyConnector`는
세션정보 요청만 처리하지만 이 픽스처는 인증 명령도 검증한다(NFR-003 시나리오 — Go와 다름, 픽스처 선택). 검증자는 도메인마다
**하나를 유지한다**(클라이언트 공개키가 바뀌면 새로 만든다) — Go `dummyConnector`는 요청마다 새로 만든다; 실차와 같은 epoch
지속성을 재현하기 위한 픽스처 선택이다.

```
FakeVehicle(vin, vehicleKey: EcdhPrivateKey, crypto, random, timeSource)
  domains: VCSEC, INFOTAINMENT 각각 { verifier: TestVerifier?, script, handshakeFaults, asleep, corruptTagsRemaining, lastReply }
  transport(retryInterval = 1ms): FakeTransport   // Transport 구현. send(bytes) → 프레이밍 없이 RoutableMessage 파싱 → handle → incoming으로 응답
  connect(): VehicleResult<FakeTransport>   // M3 TransportFactory.connect의 M2 모델: connectable이 아니면 Failure(MaxConnectionsExceeded), 재시도 없음
  handle(msg):
    asleep인 도메인은 inbox에 메시지가 도착한 기록(received)만 남고 콜백을 부르지 않는다(세션정보 요청 카운트·대본 소비·검증자 상태 불변) — Go dummyConnector.handleAsync와 동일
    session_info_request → verifier.setSessionInfo(challenge=msg.uuid, initReply(msg))
    인증 명령(AES_GCM_Personalized_data) → verifier.verify(msg): 실패 시 fault + 동봉 session_info, 성공 시 대본 응답
    대본 응답: flags&2 이면 verifier.encrypt(response, requestHash, counter++)로 AES_GCM_Response 암호화
  API (테스트가 조작): verifier(domain), epoch(domain), setConnectable(value), sleep(domains=ALL)/wake(),
    dropNextReplies(count), script(domain, *perRequest), scriptHandshake(domain, *faults), rotateEpoch(domain),
    shiftClock(domain, by), attachSessionInfoOnce(domain), corruptNextSessionInfoTag(domain, count=1), replayLastResponse(domain)
```

시간은 `timeSource`(`TimeSource.Monotonic` 또는 테스트의 `kotlinx-coroutines-test` `testTimeSource`/`TestTimeSource`)로만 흐른다. `retryInterval`은 `FakeTransport`에서 1ms로 줄여 테스트를 빠르게 한다(Go `dummyConnector.RetryInterval = 1ms`와 동일).

### 9.4 Go 클라이언트 측 테스트 포팅 목록

| 마일스톤 | 원본 | 포팅 대상 (Go) | Kotlin (완료) |
|---|---|---|---|
| M1 | `metadata_test.go` | `TestOutOfOrder`, `TestValueTooLong`, `TestCheckSum`, `TestHash512CheckSum`(HMAC 컨텍스트 = 우리는 `serialize` 후 HMAC이라 별도 이식 없음) | **완료 — M0.** `MetadataTest.rejectsOutOfOrderTags`, `.rejectsValueLongerThan255`, `.sha256ChecksumMatchesGoTestVector` |
| M1 | `window_test.go` | `TestSlidingWindow` | **완료 — M0.** `SlidingWindowTest.matchesGoWindowTable`(+ 경계값 테스트 4개) |
| M1 | `signer_test.go` | `TestUpdateSessionInfo`, `TestBadSessionInfoProto`, `TestBadSessionInfoTag`, `TestUpdateSessionInfoBadChallenge`, `…BadCounter`, `…BadEpoch`, `…BadPublicKey`, `TestRemotePublicKey`, `TestUpdateInvalidSessionInfo`, `TestSignerCounterRollover`, `TestNewAuthenticatedSigner`, `TestSetSessionInfo`, `TestExportImport`, `TestImportWrongTime`, `TestInvalidExpirationTime` (15개) | **완료 — M1.** `SignerTest`: `.acceptsValidSignedSessionInfo`(`TestUpdateSessionInfo`), `.rejectsTamperedSessionInfoProto`(`…BadSessionInfoProto`), `.rejectsTamperedTagAndChallenge`(`…BadSessionInfoTag`+`…BadChallenge`), `.rejectsReencodedCounterAndEpoch`(`…BadCounter`+`…BadEpoch`), `.rejectsImposterVehicleKey`(`…BadPublicKey`), `.exposesVehiclePublicKey`(`TestRemotePublicKey`), `.authenticatedCreationChecksProtoThenTag`(`TestNewAuthenticatedSigner`), `.acceptsSessionInfoAttachedToMessage`(`TestSetSessionInfo`), `.exportRoundTripsThroughImportWithAge`(`TestExportImport` 상태 부분). `SignerCryptoTest`: `.staleSessionInfoDoesNotRollBackVerifier`(`TestUpdateInvalidSessionInfo`), `.refusesToEncryptAfterCounterRollover`(`TestSignerCounterRollover`), `.exportedSessionResumesAfterThirtyMinutes`(`TestExportImport` 암복호화 부분), `.importWithWrongAgeExpiresImmediately`(`TestImportWrongTime`), `.rejectsExpirationBeyondEpochLength`(`TestInvalidExpirationTime` — BLE는 `AuthorizeHMAC`을 쓰지 않으므로(D6) `encrypt`로 같은 경계를 검증) |
| M1 | `peer_test.go` | `TestRequestID` | **완료 — M0.** `RequestHashTest.truncatesHmacTagTo16BytesForVcsec` |
| M1 | `protocol_doc_test.go` | `TestProtocolDocAESGCMExample` | **완료 — M0(`Session.encrypt`) + M1(`Signer.encrypt`).** `ProtocolVectorTest.encryptsHvacOnLikeProtocolDoc`, `SignerCryptoTest.reproducesProtocolDocHvacVector` |
| M1 | `native_test.go` | `TestSharedSecretPadding`(X 좌표 0-패딩), `TestLocalPublicBytes` | **완료 — M0에 작성, M1(PR #19)에서 서로 맞물리는 키 쌍으로 교체.** `:adapter-crypto` `SoftwareEcdhKeyTest.sharedXIsZeroPaddedTo32Bytes`; `SignerTest.exposesVehiclePublicKey` |
| M1 | `pkg/protocol/error.go`(`GetError`), `error_test.go` | `TestWrappedErrorClassification`, `TestRetriableError` | **완료 — M1.** `VehicleErrorTest.shouldRetryIsFalseWhenCommandMayHaveSucceeded`, `.classifiesEveryMessageFaultLikeGo`, `.messagesAreEnglishAndCarryCodes`(D19), `.unknownFaultCarriesRawCodeLikeGo`(`RoutableMessageError.Error()`의 미등록 코드). `ResponseClassifierTest`(9개)는 같은 `GetError`를 이식하며, Go에는 없는 Wire `unknownFields`(모르는 enum 값) 분류를 추가로 검증한다(`unknownFaultCarriesRawCodeAsUnknownFault` 등 — SDD §12) |
| M1 | `verifier_test.go`(GCM 경로만) | `TestGCMKnown` | **완료 — M1.** `TestVerifierTest.decryptsMessageProducedByGoSigner`(`GoVectors` 상수로 재현) |
| M2 | `verifier_test.go` 중 클라이언트 의미가 있는 것(FakeVehicle을 통해 검증) | `TestValidGCMEncryption`, `TestGCMFlags`, `TestGCMMissingDestination`, `TestGCMOutOfOrderMessage`, `TestEpochChange`, `TestGCMExpired`, `TestGCMInvalidEpoch`, `TestGCMInvalidTime`, `TestGCMCorruptedCiphertext`, `TestVerifierEncryption`, `TestGCMWindow`, `TestProvideHandle` | **완료 — M2.** `FakeVehicleTest.acceptsThenRejectsReplayedCommand`, `.rejectsTamperedFlags`, `.rejectsMissingDestinationAsInvalidDomains`, `.rejectsOutOfOrderMessageWithTtlTooLong`, `.rejectsAfterRebootThenResyncsWithAttachedSessionInfo`, `.rejectsExpiredCommand`, `.rejectsWrongEpoch`, `.rejectsExpirationBeyondEpochLength`, `.rejectsCorruptedCiphertext`, `.encryptsResponseThatSignerDecrypts`, `.enforcesSlidingWindowLikeGo`; `TestVerifierTest.sessionInfoCarriesAssignedHandle` |
| M2 | `dispatcher_test.go` | `TestSendWithoutSession`, `TestStartSession`, `TestTimeout`, `TestInvalidMessages`, `TestVehicleDropsReply`, `TestUnsolicitedSessionInfo`, `TestCorruptedSessionInfo`, `TestDiscardUnauthenticatedSessionInfo`, `TestVehicleUnreachable`, `TestConnect`, `TestWaitForAllSessions`, `TestRetrySend`, `TestSendTimeout`, `TestStopDispatcher`, `TestDoNotBlockOnResponder`, `TestRequestSessionWithoutKey`, `TestHandshakeWithoutKey`, `TestNoValidHandshakeResponse`, `TestRetryNonresponsive`, `TestCache` (20) | **완료 — M2.** `DispatcherTest.sendWithoutSessionReturnsNoSessionButUnauthenticatedSendWorks`, `HandshakeFlowTest.startSessionCompletesHandshakeAndAllowsAuthenticatedSend`, `.commandWithoutReplyTimesOut`, `DispatcherTest.dropsInvalidMessagesAndDeliversTheValidOne`, `HandshakeFlowTest.retransmitsSessionInfoRequestEveryRetryIntervalWhileVehicleSleeps`, `DispatcherTest.discardsSessionInfoWithBadTag`(×2), `.discardsUnauthenticatedSessionInfo`, `.unreachableVehicleFailsSendWithoutRetry`, `HandshakeFlowTest.startSessionsTimesOutWhileAsleepThenHandshakesBothDomains`(×2), `DispatcherTest.retriesTemporarySendErrorsAndStopsOnMayHaveSucceeded`, `.sendGivesUpWhenCallerTimesOutDuringRetries`, `.sendBeforeStartOrAfterStopReturnsNotConnected`, `.doesNotBlockOtherHandlersWhenOneQueueIsFull`, `.requestSessionInfoWithoutKeyReturnsRequiresKey`, `HandshakeFlowTest.startSessionWithoutKeyReturnsRequiresKey`, `.startSessionFailsWithKeyNotPairedAfterBogusRepliesThenUnknownKeyId`, `.retransmitsSessionInfoRequestEveryRetryIntervalWhileVehicleSleeps`, `SessionCacheSyncTest.resumesSessionFromCacheWithoutHandshake` |
| M2 | `pkg/vehicle/vehicle_test.go`, `vcsec_test.go`, `security_test.go` | `TestVehicle*` 8개, `TestNominalVSCECError`, `TestGibberishVCSECResponse`, `TestWhitelistOperationError`, `TestValidPIN` | **완료 — M2(`TestValidPIN` 제외).** `VehicleSessionTest.startSessionReturnsFatalHandshakeErrorWithoutRetry`, `.startSessionRetriesTransientHandshakeError`, `.startSessionTimesOutWhileErrorsStayTransient`, `SendWithRetryTest.returnsTerminalFailureAfterTransientSendError`, `.timesOutBeforeSendWhenTransportKeepsFailingTransiently`, `.retriesWhileVehicleAnswersBusyThenTimesOutBetweenAttempts`, `.noResponseTimesOutAsUncertainAndIsNeverResent`, `.retriesEveryRetriableFaultThenReturnsTheTerminalOne`; `VcsecCommandsTest.nominalErrorFailsWhitelistAndRkeCommands`, `.gibberishResponseIsUncertainBadResponse`, `.whitelistOperationRetriesBusyReadsIntermediateThenReportsKeychainError`. `TestValidPIN`(`SetPINToDrive` 인자 검증)은 M5로 이동 — M2 범위 아님 |
| M2 | `pkg/cache/cache_test.go` | `TestImportExport`(자체 포맷), `TestEviction`은 해당 없음(VIN당 1파일) | **완료 — M2.** `SessionCacheCodecTest.roundTripsTwoDomainsInOrder`(+ `encodesVersion1LayoutFromSdd` 벡터). `TestEviction` 해당 없음 |
| M2 | FakeVehicle 시나리오 7종(NFR-003) | – | **완료 — M2.** `FakeVehicleScenarioTest.scenario1VcsecWaitThenFinalSucceedsWithReauthorizedRetry` … `.scenario7NotConnectableVehicleRefusesConnectionWithoutRetry`(7개) |
| M2 | FR-014 디스패처 규칙(원본 테스트 없음, PRD 요구사항 검증) | – | **완료 — M2.** `DispatcherTest.discardsSessionInfoReceivedMoreThanMaxLatencyAfterRequest`, `.discardsSessionInfoWhoseChallengeMatchesNoOutstandingRequest`, `.appliesReplayedSessionInfoWithSameClockTimeLikeGo` |

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
- `ResponseClassifier`는 Wire가 모르는 enum 값(`unknownFields`)을 찾아 Go `GetError`와 같은 분류(temporary=false, mayHaveSucceeded=false)를 따른다. 모르는 fault는 `UnknownFault(rawCode)`로 코드를 보존한다(Go `RoutableMessageError{Code}`와 같음); 모르는 session_info status·operation_status는 `UnknownResponse`(Go `ErrUnknown`).
- `Signer.createAuthenticated`는 태그 검증이 실패하거나 예외가 나면 세션 키를 0으로 지운다(Go는 GC에 맡긴다). `Signer.importSessionInfo`에서 음수 `age`는 0으로 본다(Go는 미래 `generatedAt`을 그대로 받아 `timeZero`가 더 나중이 된다).
- `Signer.decrypt`는 nonce·태그 길이가 틀린 응답을 `Fault(INVALID_SIGNATURE)`로 돌려준다(M0 `Session.decrypt`가 null을 돌려주기 때문). Go `gcm.Open`은 nonce 길이가 틀리면 panic한다.
- Wire는 모르는 enum 값을 기본값(fault NONE, domain null→BROADCAST)으로 디코딩한다. **M2에서 보완:** `Signer.decrypt`가 응답 AAD를 만들 때 `unknownFields`에서 원시 fault 값을 되찾아 쓴다(`SignerCryptoTest.decryptsResponseWhoseFaultCodeIsUnknownToWire`) — 그래서 새 펌웨어가 모르는 fault 코드를 실은 암호화 응답도 태그가 맞아 `INVALID_SIGNATURE`로 잘못 드롭되지 않는다(M1 인계 노트의 우려 사항 해소). `from_destination.domain`이 모르는 값(`null`)이면 `Domain.DOMAIN_BROADCAST`로 떨어져 등록된 핸들러가 없으므로 Go와 같이 드롭된다(관찰 결과 동일 — 편차 아님).
- `Dispatcher.process`·`VcsecResponses.interpret`·`InfotainmentResponses.interpret`는 모두 `decodeOrNull`(`IOException`뿐 아니라 Wire가 손상된 입력에 던지는 `IllegalStateException`·`IllegalArgumentException`도 값으로 받는다, ADR-0006)로 응답을 디코딩한다. Go의 protobuf 파서 오류 문구는 재현하지 않는다 — "Dropping unparseable message"·"vcsec: undecodable response"·"unable to parse vehicle response: undecodable" 로그·오류 메시지에 원본 파서 상세가 없다(Wire는 Go처럼 oneof가 두 번 나오면 마지막에 이긴 멤버만 남긴다 — 이 부분은 Go와 동일).
- `SessionState.authorize`는 `Signer.encrypt` 실패를 `Failure(ProtocolFault)`로 돌려준다. Go `session.authorize`는 오류를 지우고 즉시 재시도한다(ctx 만료까지 바쁜 루프). 재시도는 `SendWithRetry`/`VcsecCommands`가 `retryInterval` 간격을 두고 한다 — 관찰 결과는 같다.
- `Dispatcher.loadSessions`는 손상된 항목·모르는 도메인 값의 항목을 건너뛰고 나머지를 복원한다(기존 세션 맵에 병합). Go `LoadCache`는 손상된 항목 하나만 있어도 전체 실패한다. 새 `Dispatcher`(세션이 모두 빈 상태)에서 부르면 병합과 교체의 결과는 같다.
- 핸드셰이크 시간 초과(`VehicleSession.startSession`)는 항상 `Failure(Timeout(afterSend = false))`다 — `HandshakeFlow`는 `withAttemptTimeout`의 `setAwaiting`을 절대 부르지 않기 때문이다(사용자 승인 답 b — 전용 타입 없음). Go는 `context.DeadlineExceeded`를 그대로 돌려준다.
- Wire가 모르는 `whitelistOperationInformation`은 `UnknownKeychainCode(rawCode)`(Go `KeychainError{Code}`와 같은 정보 — 원시 코드를 `unknownFields`에서 되찾는다). 모르는 VCSEC `operationStatus`·Infotainment `actionStatus.result`는 Go처럼 그냥 통과(default 분기가 없다). 모르는 VCSEC `nominalError.genericError`는 `VcsecRejected(GENERICERROR_NONE)`으로 뭉개진다(Go `vcsec.go:44-45`는 `GenericError_E`가 등록된 enum이라 항상 실제 코드를 담는다) — M4(L58)에서 재검토.
- `PendingRequest.close()`는 suspend하지 않고 플래그 + 채널 닫기만 한다(Go `closeHandler`는 즉시 맵에서 제거한다). 맵에서 지우는 것은 `Dispatcher`가 다음 `register`/`lookup` 때 지연 수행한다. 관찰 결과는 같다: 닫힌 요청에 온 응답은 "without registered handler"로 드롭된다.
- `Dispatcher.send`는 `Domain.DOMAIN_BROADCAST`도 `InvalidArgument`로 거부한다(Go도 `Domain_DOMAIN_BROADCAST`를 거부한다 — 동일 동작, 기록만).
- `Dispatcher`가 두 도메인(VCSEC, INFOTAINMENT)의 `SessionState`를 미리 만들어 둔다(Go는 `StartSession`이 부를 때 만든다). 그 결과 태그가 맞는 세션정보 응답이 오면 `HandshakeFlow.startSession`을 부르지 않은 도메인도 준비 상태가 된다 — Go는 등록되지 않은 도메인의 세션정보를 드롭한다. 핸드셰이크와 같은 신뢰 수준(HMAC 태그 검증)이라 관찰 가능한 안전 문제는 없다.
- `incoming`이 끝나면(BLE 연결 끊김) `Dispatcher.send`는 `NotConnected`를 돌려준다. Go의 `Send`는 `listen`이 반환할 때 `terminate`를 지우지 않으므로 오히려 성공한다(`dispatcher.go:350-351`) — 관찰 결과는 다르지만 더 안전한 방향(전송하지 않음)이다. `isListening`은 정지 중이거나(취소된 순간부터) 교대 대기 중(새 코루틴이 이전 코루틴의 종료를 기다리는 동안)에도 `false`다 — Go도 이 두 창에서 `Send`가 `ErrNotConnected`이므로 관찰 결과는 같다.
- `VehicleSession.disconnect()`는 `SessionCacheSync.store` 후 `Dispatcher.close()`를 부른다(반드시 이 순서 — `close()`가 먼저면 빈 세션을 저장해 이전 저장을 지운다). Go에서 이 저장은 `Vehicle.Disconnect` 자체가 아니라 CLI(`cmd/tesla-control/main.go`)의 `defer UpdateCachedSessions`다 — 이 라이브러리는 그 책임을 `VehicleSession`으로 옮겼다.
- `SendWithRetry.send`와 `VcsecCommands.execute`는 페이로드를 호출당 한 번만 `ByteString`으로 복사해 얼린다. Go `Vehicle.Send`(vehicle.go:236-238)도 한 번 복사하지만 Go `getVCSECResult`는 원본 슬라이스를 그대로 넘긴다 — 이 라이브러리가 더 엄격하다(호출자가 재시도 대기 중 배열을 바꿔도 재시도에 새어 들어가지 않는다).
- `HandshakeFlow.startSession`은 `Dispatcher.ALL_DOMAINS` 밖의 도메인에 대해 `RequiresKey`를 돌려준다(`dispatcher.session(domain) == null`이므로). Go `StartSession`은 그런 도메인의 세션을 그때 만든다 — M2는 `ALL_DOMAINS`(VCSEC, INFOTAINMENT) 두 개만 다루므로 관찰 가능한 차이는 없다.
