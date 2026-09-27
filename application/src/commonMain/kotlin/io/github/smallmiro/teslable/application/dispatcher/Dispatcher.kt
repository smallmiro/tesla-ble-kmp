// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher.go (Apache-2.0)
// New, SetMaxLatency, Send, RequestSessionInfo, SessionInfoRequest, listen, process, checkForSessionUpdate, decrypt, Stop
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import io.github.smallmiro.teslable.InternalTeslableApi
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
import io.github.smallmiro.teslable.protocol.decodeOrNull
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
import kotlin.concurrent.Volatile
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
@OptIn(InternalTeslableApi::class)
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

        /**
         * Go `maxLatency`(`latencyLock`으로 보호): 요청 후 이 시간이 지나 도착한 세션정보는 버린다(BLE 4초).
         * [setMaxLatency]는 수신 코루틴이 아닌 임의의 호출자가 부르고 [checkForSessionUpdate]는 수신 코루틴에서 읽으므로
         * `@Volatile`로 스레드 간 가시성을 보장한다(리뷰 라운드 1 Important 1).
         */
        @Volatile
        public var maxLatency: Duration = transport.allowedLatency
            private set

        /** 수신 루프가 돌고 있는지(Go `terminate != nil`). */
        public val isListening: Boolean get() = receiveJob?.isActive == true

        private val address: ByteString = random.nextBytes(ADDRESS_LENGTH).toByteString()
        private val sessions: Map<Domain, SessionState> =
            if (privateKey == null) {
                emptyMap()
            } else {
                ALL_DOMAINS.associateWith { SessionState(vin, privateKey, crypto, random, timeSource) }
            }
        private val pending = HashMap<PendingKey, PendingRequest>()
        private val pendingMutex = Mutex()

        // 리뷰 라운드 1 Important 1: Go는 Start/Stop을 doneLock으로 직렬화한다(dispatcher.go:333-341, 369-375,
        // 380-382). start()는 suspend가 아니므로(공개 API 유지) lock()이 아닌 tryLock/unlock으로 receiveJob 필드를
        // 지킨다. stop()은 withLock으로 필드를 캡처·해제한 뒤 잠금을 놓고 나서(락 밖에서) cancelAndJoin()으로
        // 정지를 기다린다 — 잠긴 상태로 정지를 기다리면 그사이 start()가 tryLock에 계속 실패해 새 수신 코루틴을
        // 띄울 수 없기 때문이다. 이렇게 "먼저 비우고 나중에 정지를 기다리기" 순서를 지키면, 정지를 기다리는 도중에
        // start()가 새로 띄운 코루틴을 stop()이 뒤늦게 null로 덮어써 고아로 만드는 일이 없다.
        //
        // 리뷰 라운드 2 N1(회귀): 위 방식은 첫 stop() 호출자만 job을 캡처하고, 동시에 들어온 다른 stop()
        // 호출자(예: close()가 안에서 부르는 stop())는 receiveJob이 이미 null이라 아무 job도 얻지 못한 채
        // 곧바로 반환했다 — Go Stop은 doneLock을 쥔 채 <-d.done까지 기다리므로(dispatcher.go:369-375) 모든
        // 호출자가 실제로 끝날 때까지 기다려야 하는데, 그러지 못해 close()가 수신 코루틴이 채 끝나기도 전에
        // 세션을 지우고 전송을 닫을 수 있었다. stopping에 "지금 정지 중인 job"을 남겨 두어, receiveJob이 이미
        // 비었어도 나머지 stop() 호출자가 같은 job을 join하게 한다.
        private val lifecycleMutex = Mutex()

        @Volatile
        private var receiveJob: Job? = null

        @Volatile
        private var stopping: Job? = null

        /** Go `SetMaxLatency`: 양수일 때만 바꾼다. */
        public fun setMaxLatency(latency: Duration) {
            if (latency > Duration.ZERO) maxLatency = latency
        }

        /** 도메인의 세션 상태. 개인키가 없으면 null. */
        public fun session(domain: Domain): SessionState? = sessions[domain]

        /**
         * Go `Start` + `listen`: 수신 코루틴을 띄운다. `UNDISPATCHED`로 시작해 반환 전에 [Transport.incoming] 구독이
         * 끝난다(단, 직전에 정지 중이던 job이 있으면 그 job이 끝난 뒤에 구독한다 — 아래 참고). 멱등. [lifecycleMutex]를
         * [stop]과 공유하므로(리뷰 라운드 1 Important 1) tryLock이 실패하는 경우는 둘이다: (1) [stop]이 필드를
         * 캡처·해제하는 아주 짧은 창과 겹쳤을 때, (2) **다른 [start] 호출이 이미 잠금을 쥐고 있을 때** — `UNDISPATCHED`
         * 본문은 첫 suspension(아래 [stopping] join 대기, 또는 그것이 없으면 `incoming` 구독)에 이르기 전까지 잠금을
         * 놓지 않으므로, 이 잠깐의 동기 구간 동안 [receiveJob]은 아직 이전 값(흔히 `null`)일 수 있다 — 즉 진 쪽이
         * 이긴 쪽보다 먼저 반환할 수 있고, 그 시점의 [isListening]은 아직 이긴 쪽의 갱신을 반영하지 않을 수 있다
         * (리뷰 라운드 2 N4). 두 경우 모두 이 호출은 그냥 반환한다 — 곧(또는 이미) 최신 상태가 반영되므로 호출자는
         * 다시 부르거나 [isListening]으로 다시 확인하면 된다.
         */
        public fun start() {
            if (!lifecycleMutex.tryLock()) return
            try {
                if (isListening) return
                val awaitedStop = stopping
                receiveJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        // 리뷰 라운드 2 N1(컨트롤러 판정): 직전 stop()이 아직 끝나지 않았으면 그 job이 끝날 때까지
                        // 기다린 뒤에야 incoming을 구독한다 — 두 수신 코루틴이 동시에 살아 있으면 안 된다(Go의
                        // listen과 Stop은 doneLock을 공유한다).
                        awaitedStop?.join()
                        logger.log(LogLevel.INFO, TAG) { "Starting dispatcher service..." }
                        transport.incoming.collect { process(it) }
                    }
            } finally {
                lifecycleMutex.unlock()
            }
        }

        /**
         * Go `Stop`: 수신 코루틴을 취소하고 끝날 때까지 기다린다. 세션은 유지된다(Go와 동일). 필드를 캡처하고 비우는
         * 것은 잠금 안에서, 실제로 끝나기를 기다리는 것(suspend)은 잠금 밖에서 한다(리뷰 라운드 1 Important 1) —
         * 그래야 기다리는 동안 레이스로 들어온 [start]가 새로 띄운 코루틴을 이 함수가 나중에 `null`로 덮어써
         * 고아로 만들지 않는다.
         *
         * 리뷰 라운드 2 N1(회귀 수정): 동시에 여러 [stop] 호출(예: [close]가 안에서 부르는 것과 별도의 직접 호출)이
         * 들어오면, 첫 호출자 이후에는 [receiveJob]이 이미 `null`이라 캡처할 job이 없다 — 그래서 [stopping]에
         * "지금 정지 중인 job"을 남겨 두어, 이후 호출자도 같은 job을 잡아 함께 join하게 한다. job이 끝나면 그 값을
         * 여전히 가리키고 있는 경우에만(다음 [start]가 새 job으로 갈아 치우지 않았다면) [stopping]을 비운다.
         */
        public suspend fun stop() {
            val job =
                lifecycleMutex.withLock {
                    val target = receiveJob ?: stopping
                    receiveJob = null
                    stopping = target
                    target
                }
            job?.cancelAndJoin()
            lifecycleMutex.withLock { if (stopping === job) stopping = null }
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
            // 리뷰 라운드 1 Minor M6: Go는 개인키 확인보다 먼저 로그를 남긴다(dispatcher.go:483-486).
            logger.log(LogLevel.INFO, TAG) { "Requesting session info from $domain" }
            val key = privateKey ?: return VehicleResult.Failure(VehicleError.RequiresKey)
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
            // 컨트롤러 판정 R1: Wire는 손상된 프로토버프에 IllegalStateException을 던지므로(예: "I'm not a valid protobuf"),
            // decodeOrNull(ADR-0006)로 값으로 받는다. Go의 protobuf 오류 문구는 재현하지 않는다(dispatcher.go:355).
            val message =
                RoutableMessage.ADAPTER.decodeOrNull(bytes) ?: run {
                    logger.log(LogLevel.WARN, TAG) { "Dropping unparseable message" }
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
            // 리뷰 라운드 1 Minor M2 + 라운드 2 N3: checkForSessionUpdate/decryptIfNeeded가 실행되는 동안
            // 호출자가 이 핸들러를 닫았을 수 있다(둘 다 suspend). deliver() 시도 전에 isClosed를 먼저 검사해도
            // 그 검사와 deliver() 사이에 또 닫힐 수 있으므로, 실패 원인은 deliver()가 실패한 **뒤에** 판단한다
            // — Go는 Close()가 맵에서 핸들러를 먼저 지우므로 그 뒤 process()는 그냥 핸들러가 없는 것으로 본다;
            // 닫힌 채널에 trySend가 실패한 것을 "큐가 가득 찼다"로 잘못 보고하지 않는다.
            val delivered = handler.deliver(deliverable)
            if (!delivered) {
                if (handler.isClosed) {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message without registered handler $key" }
                } else {
                    logger.log(LogLevel.ERROR, TAG) { "[$id] Dropping response to command because response handler queue is full" }
                }
            }
        }

        /** Go `process`의 검증 부분: 드롭이면 사유를 남기고 null. */
        private fun matchKey(message: RoutableMessage): PendingKey? {
            val id = message.request_uuid.hex()
            // 컨트롤러 판정 R2(설계 구체화 10): "누락된 소스"는 from_destination 자체가 없을 때만이다. Go
            // `message.GetFromDestination().GetDomain()`은 non-domain oneof에도 0(DOMAIN_BROADCAST)을 돌려준다 —
            // Wire에서 모르는 domain 원시값도 domain == null이 되므로 같은 취급으로 BROADCAST로 떨어진다. 그 결과
            // 이런 메시지는 등록된 핸들러가 없어 "핸들러 없음"으로 드롭된다(아래에서).
            val fromDestination = message.from_destination
            if (fromDestination == null) {
                logger.log(LogLevel.WARN, TAG) { "[xxx] Dropping message with missing source" }
                return null
            }
            val fromDomain = fromDestination.domain ?: Domain.DOMAIN_BROADCAST
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
                    if (toDomain != null) {
                        "[$id] Dropping message to $toDomain"
                    } else {
                        "[$id] Dropping message with unrecognized destination type"
                    }
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
                logger.log(LogLevel.WARN, TAG) {
                    "[$id] Discarding session info because it was received more than $maxLatency after request"
                }
                return
            }
            // 리뷰 라운드 1 Minor M1: Wire proto3 bytes 필드는 기본값이 null이 아니라 ByteString.EMPTY다.
            // session_info_tag가 실려 있지만 태그가 비어 있으면 Go의 GetTag() == nil과 같은 뜻이므로 없는 것으로 본다.
            val tag =
                message.signature_data
                    ?.session_info_tag
                    ?.tag
                    ?.takeIf { it.size > 0 }
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
                is SignerResult.Fault -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Session info error: ${result.fault.name}: ${result.detail}" }
                }

                is SignerResult.Ok -> {
                    logger.log(LogLevel.INFO, TAG) { "[$id] Updated session info for $domain" }
                }
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
                logger.log(LogLevel.WARN, TAG) {
                    "[$id] Error decrypting vehicle response: could not decrypt vehicle response without a session"
                }
                return null
            }
            return when (result) {
                is SignerResult.Fault -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Error decrypting vehicle response: ${result.fault.name}: ${result.detail}" }
                    null
                }

                is SignerResult.Ok -> {
                    if (handler.antiReplay.update(result.value.counter)) {
                        result.value.message
                    } else {
                        logger.log(LogLevel.INFO, TAG) { "[$id] Dropping duplicate vehicle response" }
                        null
                    }
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
