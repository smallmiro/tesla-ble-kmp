// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher.go (Apache-2.0)
// New, SetMaxLatency, Send, RequestSessionInfo, SessionInfoRequest, listen, process, checkForSessionUpdate, decrypt, Stop,
// Cache, LoadCache
package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.observe.AddressOwner
import dev.mirolab.teslable.application.observe.ObservationLog
import dev.mirolab.teslable.application.observe.ObservedAt
import dev.mirolab.teslable.application.observe.isPlainBroadcast
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.model.shouldRetry
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.RandomSource
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.protocol.RequestHash
import dev.mirolab.teslable.protocol.SignerResult
import dev.mirolab.teslable.protocol.decodeOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * (채널은 `trySend`, 락은 `Signer` 호출·맵 조작만 — SDD §5 구체화 4). **예외 한 가지:** [start]가 새로 띄우는 수신
 * 코루틴은 `incoming`을 구독하기 **전에**, 아직 끝나지 않은 이전 정지 대상 job이 있으면 그 job이 끝날 때까지 기다린다 — 두 수신
 * 코루틴이 동시에 같은 `incoming`을 구독하면 안 되기 때문이다(Go의 `listen`과 `Stop`은 `doneLock`을 공유해 이 순서를 강제한다). 이
 * 대기는 `NonCancellable`로 감싸 자기 자신이 취소되더라도 이전 job의 종료를 확실히 기다린 뒤 `ensureActive()`로 자기 취소를
 * 확인한다 — 그래야 `CancellationException`은 여전히 `collect()`를 통해 정상적으로 전파된다(구독을 시작한 뒤에는 이 예외가 다시
 * 적용되지 않는다). [privateKey]가 null이면 세션이 없다(Go `privateKey == nil`): 인증 전송은 [VehicleError.NoSession], 핸드셰이크는
 * [VehicleError.RequiresKey].
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

        /** 이 디스패처의 시계(`listKeys` 시간 예산 등, 같은 연결의 다른 계층이 공유한다). */
        internal val clock: TimeSource get() = timeSource

        /** 형제 계층(`VehicleSession`)이 같은 로거로 남기도록 노출한다. */
        internal val log: TeslaLogger get() = logger

        /** Go `RetryInterval()`: 전송 계층의 재전송 간격. */
        public val retryInterval: Duration get() = transport.retryInterval

        /**
         * Go `maxLatency`(`latencyLock`으로 보호): 요청 후 이 시간이 지나 도착한 세션정보는 버린다(BLE 4초).
         * [setMaxLatency]는 수신 코루틴이 아닌 임의의 호출자가 부르고 [checkForSessionUpdate]는 수신 코루틴에서 읽으므로
         * `@Volatile`로 스레드 간 가시성을 보장한다.
         */
        @Volatile
        public var maxLatency: Duration = transport.allowedLatency
            private set

        /**
         * 취소되지 않은 수신 코루틴이 실제로 [Transport.incoming]을 구독해 응답을 받을 수 있는 상태인지(Go `terminate != nil`).
         * [send]는 이것이 `false`면 [VehicleError.NotConnected]를 돌려준다. 다음 두 창에서는 `false`다.
         * - **정지 중:** [stop]이 수신 코루틴을 취소한 순간부터. 그 코루틴이 아직 다 풀리지 않아 [activeJob]에 남아 있어도
         *   이미 취소됐으므로 `isActive == false`다 — Go `Stop`은 `<-d.done`을 기다리기 **전에** `doneLock` 안에서
         *   `terminate = nil`로 바꾸고, `Send`는 같은 잠금 안에서 `terminate`를 읽는다(dispatcher.go:369-375, 380-385).
         * - **교대 대기 중:** 정지 도중 [start]가 띄운 새 코루틴이 이전 코루틴이 끝나기를 기다리는 동안. 새 코루틴은 아직
         *   구독 전이라 [activeJob]을 채우지 않았다 — Go도 새 `listen`이 `doneLock`을 얻어 `terminate`를 만들기 전까지는
         *   `Send`가 `ErrNotConnected`다(dispatcher.go:331-341).
         *
         * `activeJob != null`만으로는 부족하다 — 취소된 코루틴은 `finally`에 이르기 전까지 [activeJob]에 남아 있으므로,
         * 그 존재만 보면 정지 중(교대가 없어도)에도 `true`가 되어 [send]가 차량으로 전송까지 해 버린다 — Go와 다르다.
         */
        public val isListening: Boolean get() = activeJob?.isActive == true

        private val address: ByteString = random.nextBytes(ADDRESS_LENGTH).toByteString()
        private val sessions: Map<Domain, SessionState> =
            if (privateKey == null) {
                emptyMap()
            } else {
                ALL_DOMAINS.associateWith { SessionState(vin, privateKey, crypto, random, timeSource) }
            }
        private val unrouted =
            MutableSharedFlow<ByteArray>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /**
         * RoutableMessage로 해석되지 않거나 출발지가 없는 프레임(사본). add-key-request 진행 상태(ADR-0009)가 구독한다. 구독자가 없으면 버려지고,
         * 이 프레임의 드롭 규칙·로그는 그대로다. 수신 코루틴은 `tryEmit`만 한다(SDD §5).
         */
        public val unroutedFrames: SharedFlow<ByteArray> = unrouted.asSharedFlow()

        // 기본 SUSPEND라서 버퍼가 차면 tryEmit이 실패한다 — 버린 수를 정확히 세고, 이미 든 프레임의 순서를 지킨다
        private val broadcasts = MutableSharedFlow<ByteArray>(extraBufferCapacity = BROADCAST_BUFFER)

        /**
         * 원본에 없음(FR-035) — 차량이 요청 없이 보낸 **평문** VCSEC 브로드캐스트 프레임(받은 `RoutableMessage` 바이트 전체의 사본).
         * 원본처럼 응답 매칭에서 버리는(`TO_DOMAIN`, dispatcher.go:270-272) 프레임 중 관찰 로그와 같은 게이트(from VCSEC, to BROADCAST,
         * 요청 UUID·서명·세션 정보 없음)를 통과한 것만 나온다 — 복호화한 평문은 나오지 않는다(FR-112). replay 0: 구독 전에 온 프레임은
         * 받지 못한다. 수신 코루틴은 `tryEmit`만 하고 기다리지 않는다(SDD §5): 구독자가 밀려 버퍼(64)가 차면 새 프레임을 버리고 세며,
         * 첫 drop과 이후 100번째 drop마다 WARN을 남긴다. 드롭 규칙과 관찰 로그는 그대로다.
         */
        public val plainBroadcasts: SharedFlow<ByteArray> = broadcasts.asSharedFlow()

        // 수신 코루틴(process)에서만 읽고 쓴다 — 수신 코루틴은 한 번에 하나뿐이다(start는 이전 job이 끝난 뒤 구독한다)
        private var droppedBroadcasts = 0

        /** 같은 연결의 형제 계층이 링크 끊김을 본다. */
        internal val transportState: StateFlow<TransportState> get() = transport.state

        /** 감싸지 않은 프레임을 그대로 보낸다(Go `v.conn.Send`, security.go:356). 재시도하지 않는다(Go와 같음).
         * `internal` — VCSEC 직렬화(FR-049)를 우회하지 못하게 같은 모듈의 `Pairing`만 쓴다(락은 호출자가 잡는다). */
        internal suspend fun sendUnrouted(frame: ByteArray): VehicleResult<Unit> {
            if (!isListening) return VehicleResult.Failure(VehicleError.NotConnected)
            return transport.send(frame.copyOf())
        }

        private val events =
            MutableSharedFlow<SessionEvent>(
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        /** 세션 사건(설계 구체화 14). 구독자가 없으면 버려진다 — `VehicleSession`이 `connect()`에서 구독을 먼저 시작한다. */
        public val sessionEvents: SharedFlow<SessionEvent> = events.asSharedFlow()

        private val pending = HashMap<PendingKey, PendingRequest>()
        private val pendingMutex = Mutex()
        private val observation = ObservationLog(crypto)

        /** 관찰 로그(DEBUG, 태그 `observe`). 로거가 DEBUG를 버리면 [line]은 평가되지 않는다. */
        private inline fun observe(crossinline line: () -> String) {
            logger.log(LogLevel.DEBUG, ObservationLog.TAG) { line() }
        }

        /** 라우팅 주소의 주인(관찰 로그용). */
        private suspend fun ownerOf(routingAddress: ByteString): AddressOwner {
            if (routingAddress == address) return AddressOwner.SELF
            return pendingMutex.withLock {
                if (pending.values.any {
                        !it.isClosed && it.key.address == routingAddress
                    }
                ) {
                    AddressOwner.OPEN_REQUEST
                } else {
                    AddressOwner.OTHER
                }
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

        // Go는 Start/Stop을 doneLock으로 직렬화한다(dispatcher.go:333-341, 369-375,
        // 380-382). start()는 suspend가 아니므로(공개 API 유지) lock()이 아닌 tryLock/unlock으로 receiveJob 필드를
        // 지킨다. stop()은 withLock으로 필드를 캡처·해제한 뒤 잠금을 놓고 나서(락 밖에서) cancelAndJoin()으로
        // 정지를 기다린다 — 잠긴 상태로 정지를 기다리면 그사이 start()가 tryLock에 계속 실패해 새 수신 코루틴을
        // 띄울 수 없기 때문이다. 이렇게 "먼저 비우고 나중에 정지를 기다리기" 순서를 지키면, 정지를 기다리는 도중에
        // start()가 새로 띄운 코루틴을 stop()이 뒤늦게 null로 덮어써 고아로 만드는 일이 없다.
        //
        // 위 방식은 첫 stop() 호출자만 job을 캡처하고, 동시에 들어온 다른 stop()
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

        /**
         * 실제로 `incoming`을 구독한 수신 코루틴(구독 전이면 null). 취소된 뒤에도 `finally`에 이르기 전까지는
         * 남아 있으므로, [isListening]은 이것의 존재가 아니라 `isActive`를 본다.
         */
        @Volatile
        private var activeJob: Job? = null

        /** [start]의 멱등성 검사용(공개 [isListening]과 다르다): 아직 구독 전이라도 이미 배정된 job이 있으면 중복 실행하지 않는다. */
        private val hasAssignedReceiver: Boolean get() = receiveJob?.isActive == true

        /** Go `SetMaxLatency`: 양수일 때만 바꾼다. */
        public fun setMaxLatency(latency: Duration) {
            if (latency > Duration.ZERO) maxLatency = latency
        }

        /** 도메인의 세션 상태. 개인키가 없으면 null. */
        public fun session(domain: Domain): SessionState? = sessions[domain]

        /**
         * Go `Start` + `listen`: 수신 코루틴을 띄운다. `UNDISPATCHED`로 시작해 반환 전에 [Transport.incoming] 구독이
         * 끝난다(단, 직전에 정지 중이던 job이 있으면 그 job이 끝난 뒤에 구독한다 — 아래 참고). 멱등. [lifecycleMutex]를
         * [stop]과 공유하므로 tryLock이 실패하는 경우는 둘이다: (1) [stop]이 필드를
         * 캡처·해제하는 아주 짧은 창과 겹쳤을 때, (2) **다른 [start] 호출이 이미 잠금을 쥐고 있을 때** — `UNDISPATCHED`
         * 본문은 첫 suspension(아래 [stopping] join 대기, 또는 그것이 없으면 `incoming` 구독)에 이르기 전까지 잠금을
         * 놓지 않으므로, 이 잠깐의 동기 구간 동안 [receiveJob]은 아직 이전 값(흔히 `null`)일 수 있다 — 즉 진 쪽이
         * 이긴 쪽보다 먼저 반환할 수 있다. 두 경우 모두 이 호출은 그냥 반환한다 — 곧(또는 이미)
         * 최신 상태가 반영되므로 호출자는 다시 부르거나 [isListening]으로 다시 확인하면 된다. 멱등 여부는
         * [hasAssignedReceiver] 기준이다(공개 [isListening]과 다르다) — 구독 전에 대기 중인 job도 이미 "배정된"
         * 것으로 쳐서 중복 실행하지 않는다.
         */
        public fun start() {
            if (!lifecycleMutex.tryLock()) return
            try {
                if (hasAssignedReceiver) return
                val awaitedStop = stopping
                receiveJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        // join()이 취소 가능한 채로 있으면, 이 코루틴 자신이 취소됐을 때
                        // awaitedStop이 아직 끝나지 않았는데도 join을 빠져나가 버린다 — 그러면 다음 stop()이
                        // stopping을 이 코루틴으로 덮어써 더 이전 세대 job의 추적을 완전히 잃는다. NonCancellable로
                        // 감싸 그 job이 실제로 끝날 때까지는 반드시 기다리고, 그 다음에야 ensureActive()로 (이
                        // 코루틴 자신의) 취소를 확인한다 — CancellationException은 여전히 collect()로 정상
                        // 전파된다. 매 세대가 앞 세대의 완료를 정직하게 기다리면, 몇 단계를 거치더라도
                        // (stop→start→stop→start…) 결국 맨 처음 job까지 사슬로 이어져 대기한다.
                        if (awaitedStop != null) {
                            withContext(NonCancellable) { awaitedStop.join() }
                            ensureActive()
                        }
                        logger.log(LogLevel.INFO, TAG) { "Starting dispatcher service..." }
                        // 실제 구독은 여기서부터다 — activeJob을 채워야 isListening이 true가 된다.
                        val self = coroutineContext[Job]
                        activeJob = self
                        try {
                            transport.incoming.collect { process(it) }
                        } finally {
                            if (activeJob === self) activeJob = null
                        }
                    }
            } finally {
                lifecycleMutex.unlock()
            }
        }

        /**
         * Go `Stop`: 수신 코루틴을 취소하고 끝날 때까지 기다린다. 세션은 유지된다(Go와 동일). 필드를 캡처하고 비우는
         * 것은 잠금 안에서, 실제로 끝나기를 기다리는 것(suspend)은 잠금 밖에서 한다 —
         * 그래야 기다리는 동안 레이스로 들어온 [start]가 새로 띄운 코루틴을 이 함수가 나중에 `null`로 덮어써
         * 고아로 만들지 않는다.
         *
         * 동시에 여러 [stop] 호출(예: [close]가 안에서 부르는 것과 별도의 직접 호출)이
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

        /**
         * Go `Vehicle.Disconnect`: [stop] + 세션 키 소거 + 전송 닫기.
         *
         * [stop]이 [Job.join]으로 정지를 기다리는 동안 **이 함수의 호출자**가 취소되면, 예전에는
         * 그 `CancellationException`이 그대로 빠져나가 세션 키 소거와 전송 닫기를 건너뛰었다 — 키가 메모리에 남을 수
         * 있었다. `finally`에서 `NonCancellable`로 감싸 호출자의 취소와 무관하게 항상 실행한다(순서는 그대로: 정지
         * 시도 → 세션 소거 → 전송 닫기).
         */
        public suspend fun close() {
            try {
                stop()
            } finally {
                withContext(NonCancellable) {
                    for (session in sessions.values) session.close()
                    transport.close()
                }
            }
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
        ): VehicleResult<PendingRequest> = sendWith(message, auth, lifetime, acceptsSessionInfo = true)

        private suspend fun sendWith(
            message: RoutableMessage,
            auth: AuthMethod,
            lifetime: Duration,
            acceptsSessionInfo: Boolean,
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
            val request = register(key, RequestHash.of(outgoing), acceptsSessionInfo)
            return transmit(request, RoutableMessage.ADAPTER.encode(outgoing), uuid)
        }

        /**
         * Go `Vehicle.SessionInfo`의 전송(vehicle.go:120-125): 임의 공개키의 세션정보 요청을 인증 없이 보낸다. 개인키가 없어도 된다.
         * 이 연결의 키가 아니면 응답의 세션정보로 세션을 갱신하지 않는다(설계 구체화 3 — Go는 갱신을 시도하고 태그 불일치를 경고한다).
         */
        public suspend fun querySessionInfo(
            domain: Domain,
            publicKey: PublicKeyBytes,
        ): VehicleResult<PendingRequest> =
            sendWith(
                sessionInfoRequest(domain, publicKey),
                AuthMethod.NONE,
                DEFAULT_LIFETIME,
                acceptsSessionInfo = publicKey == privateKey?.publicKey,
            )

        /** Go `RequestSessionInfo`: 개인키가 없으면 [VehicleError.RequiresKey]. 인증 없이 보낸다. */
        public suspend fun requestSessionInfo(domain: Domain): VehicleResult<PendingRequest> {
            // Go는 개인키 확인보다 먼저 로그를 남긴다(dispatcher.go:483-486).
            logger.log(LogLevel.INFO, TAG) { "Requesting session info from $domain" }
            val key = privateKey ?: return VehicleResult.Failure(VehicleError.RequiresKey)
            return send(sessionInfoRequest(domain, key.publicKey), AuthMethod.NONE)
        }

        /**
         * Go `Cache()`: 세션이 있는 도메인의 `SessionInfo`(clock_time = 지금)를 내보낸다. 오래된 캐시로 판정된 세션
         * ([SessionState.isReportedStale])은 다시 내보내지 않는다(Go에 없는 의도한 차이, 설계 구체화 14).
         */
        public suspend fun exportSessions(): List<SessionSnapshot> =
            sessions.mapNotNull { (domain, session) ->
                if (session.isReportedStale) null else session.export()?.let { SessionSnapshot(domain, it) }
            }

        /**
         * Go `LoadCache`: 항목마다 [SessionState.loadFromCache](즉시 준비 상태). 손상된 항목·개인키 없음·키의 ECDH 예외는 WARN 로그 후 건너뛴다
         * (Go는 전체 실패 — 설계 구체화 6). 복원한 도메인 집합을 돌려준다. Go는 세션 맵을 통째로 새로 만들어 교체하지만 이 구현은
         * 기존 [sessions]에 도메인별로 병합한다 — 새 [Dispatcher](세션이 전부 비어 있는 상태)에서 부르면 결과는 같다.
         */
        public suspend fun loadSessions(entries: List<CachedSession>): Set<Domain> =
            entries.filter { loadEntry(it) }.map { it.domain }.toSet()

        /** [loadSessions]의 항목 하나. 복원했으면 true, 건너뛰었으면(WARN 로그) false. */
        private suspend fun loadEntry(entry: CachedSession): Boolean {
            val session = sessions[entry.domain]
            if (session == null) {
                logger.log(LogLevel.WARN, TAG) { "invalid cache: ${entry.domain}: no session (private key missing)" }
                return false
            }
            val result =
                try {
                    session.loadFromCache(entry.sessionInfo, entry.age)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    // 플랫폼 키의 ECDH 예외(최종 리뷰 I3) — 이 항목만 건너뛴다
                    logger.log(LogLevel.WARN, TAG) { "invalid cache: ${entry.domain}: processing failed: ${e::class.simpleName}" }
                    return false
                }
            return when (result) {
                is SignerResult.Ok -> {
                    logger.log(LogLevel.INFO, TAG) { "Session for ${entry.domain} loaded from cache" }
                    true
                }

                is SignerResult.Fault -> {
                    logger.log(LogLevel.WARN, TAG) { "invalid cache: ${entry.domain}: ${result.fault.name}: ${result.detail}" }
                    false
                }
            }
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
            return when (val result = session.authorizeNow(message, lifetime)) {
                is SignerResult.Ok -> {
                    VehicleResult.Success(result.value)
                }

                is SignerResult.Fault -> {
                    if (result.detail == SessionState.NO_SESSION) {
                        VehicleResult.Failure(VehicleError.NoSession)
                    } else {
                        VehicleResult.Failure(result.toVehicleError())
                    }
                }
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
            acceptsSessionInfo: Boolean = true,
        ): PendingRequest =
            pendingMutex.withLock {
                pending.values.removeAll { it.isClosed }
                PendingRequest(key, requestHash, timeSource.markNow(), acceptsSessionInfo).also { pending[key] = it }
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
            // Wire는 손상된 프로토버프에 IllegalStateException을 던지므로(예: "I'm not a valid protobuf"),
            // decodeOrNull(ADR-0006)로 값으로 받는다. Go의 protobuf 오류 문구는 재현하지 않는다(dispatcher.go:355).
            val message =
                RoutableMessage.ADAPTER.decodeOrNull(bytes) ?: run {
                    logger.log(LogLevel.WARN, TAG) { "Dropping unparseable message" }
                    observe { observation.unparseable(bytes) }
                    unrouted.tryEmit(bytes.copyOf())
                    return
                }
            val key =
                when (val routing = route(message)) {
                    is Routing.Matched -> {
                        routing.key
                    }

                    is Routing.Dropped -> {
                        logDrop(routing.reason, message)
                        observeDrop(routing.reason, message, bytes)
                        if (routing.reason == DropReason.MISSING_SOURCE) unrouted.tryEmit(bytes.copyOf())
                        if (routing.reason == DropReason.TO_DOMAIN && isPlainBroadcast(message)) emitPlainBroadcast(bytes)
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
            // 차량은 desync가 의심되면 오류 응답에 세션정보를 동봉한다. 반영한 뒤에도 응답은 핸들러로 전달한다.
            checkForSessionUpdate(message, handler)
            val deliverable = decryptIfNeeded(message, handler) ?: return
            // checkForSessionUpdate/decryptIfNeeded가 실행되는 동안
            // 호출자가 이 핸들러를 닫았을 수 있다(둘 다 suspend). deliver() 시도 전에 isClosed를 먼저 검사해도
            // 그 검사와 deliver() 사이에 또 닫힐 수 있으므로, 실패 원인은 deliver()가 실패한 **뒤에** 판단한다
            // — Go는 Close()가 맵에서 핸들러를 먼저 지우므로 그 뒤 process()는 그냥 핸들러가 없는 것으로 본다;
            // 닫힌 채널에 trySend가 실패한 것을 "큐가 가득 찼다"로 잘못 보고하지 않는다.
            val delivered = handler.deliver(deliverable)
            if (!delivered) {
                if (handler.isClosed) {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message without registered handler $key" }
                    observe { observation.routable(ObservedAt.HANDLER_CLOSED, message, bytes.size, AddressOwner.OPEN_REQUEST) }
                } else {
                    logger.log(LogLevel.ERROR, TAG) { "[$id] Dropping response to command because response handler queue is full" }
                    observe { observation.routable(ObservedAt.QUEUE_FULL, message, bytes.size, AddressOwner.OPEN_REQUEST) }
                }
            }
        }

        /** [plainBroadcasts]로 사본을 넘긴다. 기다리지 않는다 — 버퍼가 차서 실패하면 세고, 첫 drop과 100번째마다 WARN. */
        private fun emitPlainBroadcast(bytes: ByteArray) {
            if (broadcasts.tryEmit(bytes.copyOf())) return
            droppedBroadcasts++ // 로거가 지연 평가하므로 람다 밖에서 센다
            val count = droppedBroadcasts
            if (count == 1 || count % BROADCAST_DROP_LOG_EVERY == 0) {
                logger.log(LogLevel.WARN, TAG) {
                    "Dropping plain broadcast because the broadcast buffer is full (dropped $count since connect)"
                }
            }
        }

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
                        if (toDomain !=
                            null
                        ) {
                            "[$id] Dropping message to $toDomain"
                        } else {
                            "[$id] Dropping message with unrecognized destination type"
                        }
                    }
                }

                DropReason.BAD_ADDRESS_LENGTH -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Dropping message with invalid address length" }
                }
            }
        }

        /** [checkForSessionUpdate]의 폐기 조건 셋(개인키 없음, 다른 공개키로 물은 질의, 지연 초과). 버리면 true. */
        private fun discardsSessionInfo(
            id: String,
            handler: PendingRequest,
        ): Boolean {
            if (privateKey == null) {
                logger.log(LogLevel.WARN, TAG) { "[$id] Discarding session info because client does not have a private key" }
                return true
            }
            if (!handler.acceptsSessionInfo) {
                logger.log(LogLevel.DEBUG, TAG) { "[$id] Not applying session info from a query for another public key" }
                return true
            }
            if (handler.expired(maxLatency)) {
                logger.log(LogLevel.WARN, TAG) {
                    "[$id] Discarding session info because it was received more than $maxLatency after request"
                }
                return true
            }
            return false
        }

        /** Go `checkForSessionUpdate`: 폐기 조건(FR-014)을 지나면 [SessionState.processHello]. challenge = `request_uuid`. */
        private suspend fun checkForSessionUpdate(
            message: RoutableMessage,
            handler: PendingRequest,
        ) {
            val info = message.session_info ?: return
            val id = message.request_uuid.hex()
            if (discardsSessionInfo(id, handler)) return
            // Wire proto3 bytes 필드는 기본값이 null이 아니라 ByteString.EMPTY다.
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
            val wasUnconfirmed = session.isUnconfirmedCacheRestore
            val result = processHelloOrNull(session, message, info, tag) ?: return
            when (result) {
                is SignerResult.Fault -> {
                    logger.log(LogLevel.WARN, TAG) { "[$id] Session info error: ${result.fault.name}: ${result.detail}" }
                    if (wasUnconfirmed) {
                        session.markReportedStale()
                        session.invalidate()
                        logger.log(LogLevel.WARN, TAG) {
                            "[$id] Cached session for $domain looks stale (vehicle key changed?); dropping it from the cache"
                        }
                        events.tryEmit(SessionEvent.StaleCache(domain))
                    }
                }

                is SignerResult.Ok -> {
                    logger.log(LogLevel.INFO, TAG) { "[$id] Updated session info for $domain" }
                    events.tryEmit(SessionEvent.Updated(domain))
                }
            }
        }

        /**
         * [checkForSessionUpdate]의 [SessionState.processHello] 호출. 플랫폼 키의 ECDH 예외(최종 리뷰 I3)가 수신 코루틴을 죽이지 않게
         * 로그로 바꾸고 null을 돌려준다 — 호출자는 Fault처럼(이벤트 없이) 넘어가고 응답은 그대로 전달한다.
         */
        private suspend fun processHelloOrNull(
            session: SessionState,
            message: RoutableMessage,
            info: ByteString,
            tag: ByteString,
        ): SignerResult<Unit>? =
            try {
                session.processHello(message.request_uuid.toByteArray(), info.toByteArray(), tag.toByteArray())
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.log(LogLevel.ERROR, TAG) { "[${message.request_uuid.hex()}] Session info processing failed: ${e::class.simpleName}" }
                null
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
            private const val EVENT_BUFFER = 16

            /** [plainBroadcasts] 버퍼(BLE inbox와 같은 64). */
            private const val BROADCAST_BUFFER = 64

            /** 브로드캐스트 drop WARN 간격: 첫 drop, 그다음은 이 수의 배수마다. */
            private const val BROADCAST_DROP_LOG_EVERY = 100

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
