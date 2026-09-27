// Ported from vehicle-command@a4b43c1 internal/dispatcher/session.go (Apache-2.0)
// session, processHello, authorize, decrypt, export; LoadCache (dispatcher.go)
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
            val result = signer?.updateSignedSessionInfo(challenge, encodedInfo, tag) ?: createSigner(challenge, encodedInfo, tag)
            if (result is SignerResult.Ok) readySignal.complete(Unit)
            result
        }

    // processHello의 첫 호출 분기(signer == null): Go NewAuthenticatedSigner. 락은 processHello가 이미 쥐고 있다.
    private suspend fun createSigner(
        challenge: ByteArray,
        encodedInfo: ByteArray,
        tag: ByteArray,
    ): SignerResult<Unit> {
        val created = Signer.createAuthenticated(privateKey, vin, challenge, encodedInfo, tag, crypto, random, timeSource)
        return when (created) {
            is SignerResult.Ok -> {
                signer = created.value
                SignerResult.Ok(Unit)
            }

            is SignerResult.Fault -> {
                created
            }
        }
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

                is SignerResult.Fault -> {
                    imported
                }
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
