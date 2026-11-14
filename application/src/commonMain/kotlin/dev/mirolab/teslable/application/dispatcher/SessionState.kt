// Ported from vehicle-command@a4b43c1 internal/dispatcher/session.go (Apache-2.0)
// session, processHello, authorize, decrypt, export; LoadCache (dispatcher.go)
package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.RandomSource
import dev.mirolab.teslable.protocol.Signer
import dev.mirolab.teslable.protocol.SignerResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
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
 *
 * counter 예약(원본에 없음, H17, ADR-0016): 캐시에 쓴 counter(확정한 예약)를 기억해, 그보다 큰 counter는 예약을 늘려 쓴 뒤에만
 * 인가한다([authorizeNow]의 `requireReservation`, [exportForCache], [commitReservation]). 예약은 이 [Signer]와 그 epoch에만 유효하다:
 * Signer를 새로 만들거나 바꾸거나 버리거나, epoch가 바뀌면 [generation]이 올라 예약이 무효가 된다.
 */
@Suppress("TooManyFunctions") // session.go의 메서드와 1:1 대응 + counter 예약
public class SessionState internal constructor(
    private val vin: Vin,
    private val privateKey: EcdhPrivateKey,
    private val crypto: CryptoPrimitives,
    private val random: RandomSource,
    private val timeSource: TimeSource,
) {
    private val mutex = Mutex()
    private var signer: Signer? = null

    // 아래 셋은 mutex 안에서만 읽고 쓴다(H17). generation = 지금 Signer와 그 epoch의 식별자.
    private var generation = 0L
    private var reservedThrough: UInt? = null
    private var reservedGeneration = -1L

    @Volatile
    private var readySignal = CompletableDeferred<Unit>()

    @Volatile
    private var closed = false

    @Volatile
    private var unconfirmedRestore = false

    /** 캐시에서 복원된 뒤 아직 차량 응답으로 확인되지 않았는지(복호화·세션정보 갱신 성공 전). */
    public val isUnconfirmedCacheRestore: Boolean get() = unconfirmedRestore

    @Volatile
    private var reportedStale = false

    /** 오래된 캐시로 판정됐는지. 판정은 동기 상태다 — 이벤트가 유실돼도 [Dispatcher.exportSessions]는 이 세션을 다시 내보내지 않는다. */
    public val isReportedStale: Boolean get() = reportedStale

    /** 오래된 캐시로 판정해 기록하고(이벤트보다 먼저) 다시 보고하지 않게 한다. */
    internal fun markReportedStale() {
        reportedStale = true
        unconfirmedRestore = false
    }

    /** [close]가 불렸는지. 닫힌 세션은 되살리지 않는다(설계 구체화 13). */
    public val isClosed: Boolean get() = closed

    /** Go `session.ready`: 인가할 수 있는 상태인지. */
    public val isReady: Boolean get() = !closed && readySignal.isCompleted

    /** Go `readySignal`: 준비되면 완료되는 신호(`select`용). */
    internal val ready: Deferred<Unit> get() = readySignal

    /** 준비될 때까지 기다린다(Go `<-s.readySignal`). */
    public suspend fun awaitReady(): Unit = readySignal.await()

    /**
     * Go `processHello`: 세션정보를 검증해 반영한다. 첫 호출은 [Signer.createAuthenticated](태그 검증 포함), 이후는
     * [Signer.updateSignedSessionInfo]. 성공하면 준비 신호를 완료한다. challenge가 최근 요청의 uuid인지는 호출자(디스패처)가 보장한다.
     * 기존 세션이 다른 차량 공개키의 세션정보를 받으면 `UNKNOWN_KEY_ID`로 거부하고 세션은 그대로다(Go와 동일, 설계 구체화 8).
     * 원본에 없음(H17, ADR-0016): counter를 `0xFFFFFFFF`까지 다 쓴 Signer는 갱신하지 않고 이 세션정보로 새로 만든다(같은 차량 공개키일
     * 때만). Go는 갱신해도 counter를 내리지 않아 그 Signer로는 회복하지 못한다. 차량이 그 counter를 받지 못해 epoch가 그대로면 counter가
     * 차량의 값으로 내려간다 — 차량이 받은 적 없는 counter라 재사용이 아니다.
     *
     * @throws dev.mirolab.teslable.port.KeyLockedException 첫 호출의 ECDH를 기기 잠금 때문에 할 수 없을 때(FR-037, #96). 세션은 그대로
     *   없다. 디스패처가 받아 요청에 표시한다.
     */
    public suspend fun processHello(
        challenge: ByteArray,
        encodedInfo: ByteArray,
        tag: ByteArray,
    ): SignerResult<Unit> =
        mutex.withLock {
            if (closed) return@withLock SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, "session closed")
            val before = signer
            val epochBefore = before?.epoch
            // counter를 다 쓴 Signer는 갱신해도 counter를 내리지 않으므로(Go 규칙) 새로 만든다(H17, ADR-0016 — Go에는 없는 회복)
            val result =
                when {
                    before == null -> {
                        createSigner(challenge, encodedInfo, tag, pinnedVehicleKey = null)
                    }

                    before.counter == UInt.MAX_VALUE -> {
                        createSigner(
                            challenge,
                            encodedInfo,
                            tag,
                            pinnedVehicleKey = before.vehiclePublicKey,
                        )
                    }

                    else -> {
                        before.updateSignedSessionInfo(challenge, encodedInfo, tag)
                    }
                }
            if (result is SignerResult.Ok) {
                // Signer가 바뀌었거나 epoch가 바뀌었으면 캐시에 쓴 예약은 이제 이 세션의 것이 아니다(같은 epoch의 갱신은 예약을 유지한다)
                if (signer !== before || !epochBefore.contentEquals(signer?.epoch)) generation++
                unconfirmedRestore = false
                reportedStale = false
                readySignal.complete(Unit)
            }
            result
        }

    // processHello의 첫 호출 분기(signer == null, 또는 counter를 다 쓴 Signer의 교체): Go NewAuthenticatedSigner. 락은 processHello가 이미 쥐고 있다.
    // [pinnedVehicleKey]가 있으면(교체, H17) 세션 안의 차량 공개키 고정을 지킨다: 다른 키의 세션정보는 Go UpdateSessionInfo처럼 UNKNOWN_KEY_ID.
    private suspend fun createSigner(
        challenge: ByteArray,
        encodedInfo: ByteArray,
        tag: ByteArray,
        pinnedVehicleKey: PublicKeyBytes?,
    ): SignerResult<Unit> {
        val created = Signer.createAuthenticated(privateKey, vin, challenge, encodedInfo, tag, crypto, random, timeSource)
        return when (created) {
            is SignerResult.Ok -> {
                val replacement = created.value
                if (pinnedVehicleKey != null && !replacement.vehiclePublicKey.toByteArray().contentEquals(pinnedVehicleKey.toByteArray())) {
                    replacement.close()
                    return SignerResult.Fault(
                        MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID,
                        "public key in SessionInfo doesn't match value used to initialize Signer",
                    )
                }
                signer?.close()
                signer = replacement
                SignerResult.Ok(Unit)
            }

            is SignerResult.Fault -> {
                created
            }
        }
    }

    /**
     * Go `authorize`: 준비 신호는 락 밖에서 기다리고 [Signer.encrypt]만 락 안에서 부른다. Go는 `Encrypt` 오류를 지우고 즉시 재시도하지만
     * 여기서는 값으로 돌려준다(설계 구체화 5; `SendWithRetry`가 `retryInterval` 뒤 재시도). 원본에 없음(H17, ADR-0016): counter를
     * `0xFFFFFFFF`까지 다 썼으면 암호화하지 않고 준비 상태를 되돌린 뒤 `INVALID_TOKEN_OR_COUNTER`(`detail` = 소진, 디스패처가 `NoSession`으로
     * 바꾼다)를 돌려준다. 다음 인증된 세션정보가 새 Signer를 만든다([processHello]).
     */
    public suspend fun authorize(
        message: RoutableMessage,
        lifetime: Duration,
    ): SignerResult<RoutableMessage> = authorize(message, lifetime, requireReservation = false)

    private suspend fun authorize(
        message: RoutableMessage,
        lifetime: Duration,
        requireReservation: Boolean,
    ): SignerResult<RoutableMessage> {
        val signal = readySignal
        signal.await()
        return mutex.withLock {
            val current = signer
            when {
                closed || signal !== readySignal || current == null -> {
                    SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, NO_SESSION)
                }

                current.counter == UInt.MAX_VALUE -> {
                    // 원본에 없음(H17, ADR-0016): 마지막 counter까지 썼다. Go는 롤오버 오류를 ctx가 끝날 때까지 되풀이하고 이 Signer로는
                    // 회복하지 못한다(epoch가 바뀐 세션정보도 counter를 내리지 않는다). 준비 상태만 되돌려 startSession()이 새로 핸드셰이크하게
                    // 하고, 다음 인증된 세션정보가 새 Signer를 만든다([processHello]). 이미 보낸 요청의 응답은 그때까지 이 Signer로 복호화한다.
                    generation++
                    if (readySignal.isCompleted) readySignal = CompletableDeferred()
                    SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, COUNTER_EXHAUSTED)
                }

                requireReservation && !covers(current) -> {
                    SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, RESERVATION_NEEDED)
                }

                else -> {
                    current.encrypt(message, lifetime)
                }
            }
        }
    }

    /**
     * [authorize]의 비대기 변형: 호출 시점의 준비 신호가 아직 완료되지 않았으면 기다리지 않고 [NO_SESSION] fault를 돌려준다.
     * 디스패처는 `isReady` 확인과 인가 사이에 세션이 무효화돼도 새 핸드셰이크를 기다리며 매달리지 않도록 이쪽을 쓴다.
     *
     * [requireReservation]이면(H17) 다음 counter가 확정한 예약 안에 있을 때만 암호화하고, 아니면 counter를 쓰지 않고
     * [RESERVATION_NEEDED] fault를 돌려준다. 확인과 암호화가 한 임계 구역이라 동시 전송이 예약 밖 counter를 쓰지 못한다.
     */
    internal suspend fun authorizeNow(
        message: RoutableMessage,
        lifetime: Duration,
        requireReservation: Boolean = false,
    ): SignerResult<RoutableMessage> {
        if (!readySignal.isCompleted) return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, NO_SESSION)
        return authorize(message, lifetime, requireReservation)
    }

    /**
     * 오래된 캐시로 판정된 세션을 버린다(설계 구체화 2): 세션 키를 0으로 덮고 준비 신호를 되돌린다. 이후 인증 전송은 `NoSession`이고,
     * 다음 핸드셰이크가 새 [Signer]를 만든다. 닫힌 세션에는 아무것도 하지 않는다.
     */
    internal suspend fun invalidate(): Unit =
        mutex.withLock {
            if (closed) return@withLock
            dropSigner()
        }

    // invalidate의 본문. mutex 안에서만 부른다
    private fun dropSigner() {
        signer?.close()
        signer = null
        generation++
        unconfirmedRestore = false
        if (readySignal.isCompleted) readySignal = CompletableDeferred()
    }

    /** 캐시에 쓸 내용 하나(H17). [counter]는 [sessionInfo]에 실린 값이고, [generation]은 내보낼 때의 Signer·epoch 식별자다. */
    internal class CacheExport(
        val generation: Long,
        val counter: UInt,
        val sessionInfo: ByteArray,
    )

    /**
     * 캐시에 쓸 `SessionInfo`(H17, ADR-0016). Go `export`와 같되 counter는 이미 확정한 예약보다 낮지 않다: 확정한 예약 안의 counter는
     * 다시 예약하지 않고 쓰이므로, 낮은 값을 쓰면 그 counter를 다음 프로세스가 다시 쓸 수 있다. [extend]면 지금 counter + [COUNTER_RESERVATION]
     * (넘치면 `0xFFFFFFFF`)까지 늘린다. 세션이 없으면 null.
     */
    internal suspend fun exportForCache(extend: Boolean): CacheExport? =
        mutex.withLock {
            val current = signer ?: return@withLock null
            val target = if (extend) reserveAhead(current.counter) else current.counter
            val written = committedReservation()?.let { maxOf(it, target) } ?: target
            CacheExport(generation, written, current.exportSessionInfo().withCounter(written))
        }

    /**
     * [exportForCache]가 돌려준 [export]를 캐시에 쓴 뒤 부른다: 같은 Signer·epoch이면 그 counter까지를 확정한 예약으로 기억한다(늘리기만 한다). 그사이 Signer나
     * epoch가 바뀌었으면 아무것도 하지 않는다 — 옛 세션의 예약이 새 세션을 덮었다고 보면 안 된다.
     */
    internal suspend fun commitReservation(export: CacheExport): Unit =
        mutex.withLock {
            if (closed || export.generation != generation) return@withLock
            val committed = committedReservation()
            if (committed == null || export.counter > committed) {
                reservedThrough = export.counter
                reservedGeneration = generation
            }
        }

    /** 다음 counter가 확정한 예약 안에 있는지(H17). 세션이 없으면 false. */
    internal suspend fun coversNextCounter(): Boolean = mutex.withLock { signer?.let { covers(it) } == true }

    // 아래 둘은 mutex 안에서만 부른다
    private fun committedReservation(): UInt? = if (reservedGeneration == generation) reservedThrough else null

    private fun covers(current: Signer): Boolean = committedReservation()?.let { current.counter < it } == true

    /** 상수. */
    public companion object {
        /** [authorize]가 세션 없음(닫힘·무효화)으로 실패할 때의 `detail`. 디스패처가 `NoSession`으로 바꾼다. */
        public const val NO_SESSION: String = "no session"

        /** [authorizeNow]가 예약을 먼저 늘려야 해서 암호화하지 않았을 때의 `detail`(H17). 디스패처만 본다. */
        internal const val RESERVATION_NEEDED: String = "counter reservation needed"

        /**
         * 마지막 counter(`0xFFFFFFFF`)까지 써서 준비 상태를 되돌렸을 때의 `detail`(H17). 디스패처가 `NoSession`으로 바꾼다. Signer는 다음 인증된
         * 세션정보가 새로 만들 때까지 남아 이미 보낸 요청의 응답을 복호화한다.
         */
        internal const val COUNTER_EXHAUSTED: String = "counter exhausted"

        /**
         * 예약 폭 R(H17, ADR-0016): 예약 하나가 덮는 counter 수. 저장 한 번이 다음 R개 인증 요청을 덮고, 깨끗하지 않은 종료 뒤
         * 차량이 보는 counter 건너뜀은 보통 R 이하다.
         */
        internal const val COUNTER_RESERVATION: UInt = 16u

        /** [counter] + [COUNTER_RESERVATION]. 넘치면 `0xFFFFFFFF`에서 멈춘다(되감지 않는다). */
        internal fun reserveAhead(counter: UInt): UInt =
            if (counter > UInt.MAX_VALUE - COUNTER_RESERVATION) UInt.MAX_VALUE else counter + COUNTER_RESERVATION
    }

    /** Go `session.decrypt`의 Signer 부분. 세션이 없으면 null(Go `ErrNoDecryptionContext`). 윈도우 검사는 디스패처가 한다. */
    public suspend fun decrypt(
        message: RoutableMessage,
        requestHash: ByteArray,
    ): SignerResult<Signer.DecryptedResponse>? =
        mutex.withLock {
            signer?.decrypt(message, requestHash)?.also { if (it is SignerResult.Ok) unconfirmedRestore = false }
        }

    /** Go `export`: 캐시용 `SessionInfo` 바이트. 세션이 없으면 null. */
    public suspend fun export(): ByteArray? = mutex.withLock { signer?.exportSessionInfo() }

    /**
     * Go `LoadCache`: 캐시된 세션정보로 [Signer]를 만들고 즉시 준비 상태로 둔다. [age]는 캐시 저장 후 경과(음수면 0으로 본다).
     * 기존 Signer가 있으면 닫고 교체한다. 디코딩 실패는 `DECODING` fault(예외 없음).
     *
     * @throws dev.mirolab.teslable.port.KeyLockedException ECDH를 기기 잠금 때문에 할 수 없을 때(FR-037, #96). 기존 상태는 그대로다.
     */
    public suspend fun loadFromCache(
        encodedInfo: ByteArray,
        age: Duration,
    ): SignerResult<Unit> =
        mutex.withLock {
            if (closed) return@withLock SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, "session closed")
            when (val imported = Signer.importSessionInfo(privateKey, vin, encodedInfo, age, crypto, random, timeSource)) {
                is SignerResult.Ok -> {
                    signer?.close()
                    signer = imported.value
                    generation++
                    unconfirmedRestore = true
                    readySignal.complete(Unit)
                    SignerResult.Ok(Unit)
                }

                is SignerResult.Fault -> {
                    imported
                }
            }
        }

    /** 차량 시계 추정(초). 세션이 없으면 null. 테스트·로그용. */
    public suspend fun timestamp(): UInt? = mutex.withLock { signer?.timestamp() }

    /** 마지막으로 쓴 counter. 세션이 없으면 null. */
    public suspend fun counter(): UInt? = mutex.withLock { signer?.counter }

    /** 세션 키를 0으로 덮고 영구히 닫는다. 이후 [authorize]는 `INTERNAL` fault, [processHello]·[loadFromCache]도 거부한다. */
    public suspend fun close(): Unit =
        mutex.withLock {
            closed = true
            unconfirmedRestore = false
            signer?.close()
            signer = null
            generation++
        }
}

/** Go `ExportSessionInfo`의 바이트에서 counter만 [counter]로 바꾼다(H17 예약). 다른 필드와 Go의 인코딩 규칙은 그대로다. */
private fun ByteArray.withCounter(counter: UInt): ByteArray =
    SessionInfo.ADAPTER.encode(SessionInfo.ADAPTER.decode(this).copy(counter = counter.toInt()))
