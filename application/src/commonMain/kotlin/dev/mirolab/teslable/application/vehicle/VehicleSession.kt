// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle, Connect, StartSession, Disconnect
package dev.mirolab.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.cache.SessionCacheSync
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.dispatcher.HandshakeFlow
import dev.mirolab.teslable.application.dispatcher.SessionEvent
import dev.mirolab.teslable.application.dispatcher.retryWhileRetriable
import dev.mirolab.teslable.application.dispatcher.withAttemptTimeout
import dev.mirolab.teslable.application.infotainment.InfotainmentCommands
import dev.mirolab.teslable.application.pairing.Pairing
import dev.mirolab.teslable.application.vcsec.KeyManagement
import dev.mirolab.teslable.application.vcsec.VcsecCommands
import dev.mirolab.teslable.application.vcsec.VcsecControl
import dev.mirolab.teslable.application.vcsec.VehicleInfoQueries
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * Go `Vehicle`의 세션 부분: 연결(캐시 복원 + 수신 시작), 핸드셰이크 재시도, 해제(캐시 저장 + 세션 소거). M3 `Vehicle` 구현이 감싼다.
 * 명령은 [send](단일 응답), Task 10의 `vcsec`/`infotainment`로 보낸다.
 *
 * @param eventScope [cacheSync]도 함께 줘야 동작한다. 세션정보 갱신·오래된 캐시 사건을 이 스코프에서 수집해 캐시를 저장하며,
 *   저장 실패는 로그만 남기고 스코프로 전파하지 않는다. [disconnect]가 수집을 멈춘다.
 */
public class VehicleSession(
    private val dispatcher: Dispatcher,
    private val timeouts: CommandTimeouts = CommandTimeouts(),
    private val cacheSync: SessionCacheSync? = null,
    private val eventScope: CoroutineScope? = null,
) {
    private var eventJob: Job? = null // connectMutex 안에서만 읽고 쓴다
    private val disconnectMutex = Mutex()
    private var storedOnDisconnect = false
    private var closing = false // disconnectMutex 안에서만 읽고 쓴다 — true면 Dispatcher.close()가 시작됐다
    private val connectMutex = Mutex()
    private var hasLoadedCache = false
    private var disconnected = false
    private var restoredDomains: Set<Domain> = emptySet()

    /** Go `StartSessions` 계층. */
    public val handshake: HandshakeFlow = HandshakeFlow(dispatcher)

    /** Go `Vehicle.Send`. */
    public val send: SendWithRetry = SendWithRetry(dispatcher, timeouts)

    /** Go `getVCSECResult` 계층(직렬화 포함). */
    public val vcsec: VcsecCommands = VcsecCommands(dispatcher, timeouts)

    /** 인증 없는 VCSEC 조회(FR-025, FR-031, FR-032). [vcsec]의 직렬화를 공유한다. */
    public val info: VehicleInfoQueries = VehicleInfoQueries(vcsec, timeouts, dispatcher.clock)

    /** VCSEC 제어 12종(FR-040~047). [vcsec]의 직렬화를 공유한다. */
    public val control: VcsecControl = VcsecControl(vcsec, timeouts)

    /** 키 등록·삭제·등록 확인(FR-024, FR-026, FR-027). [vcsec]의 직렬화를 공유한다. */
    public val keys: KeyManagement = KeyManagement(dispatcher, vcsec, timeouts)

    /** add-key-request와 진행 상태(FR-022, FR-023, ADR-0009). [vcsec]의 직렬화를 보낼 때만 공유한다. */
    public val pairing: Pairing = Pairing(dispatcher, vcsec)

    /** Go `executeCarServerAction` 계층. */
    public val infotainment: InfotainmentCommands = InfotainmentCommands(send)

    /** 전송 계층의 재전송 간격. */
    public val retryInterval: Duration get() = dispatcher.retryInterval

    /** Go `NewVehicle`(캐시 복원) + `Connect`(수신 시작). 멱등. [disconnect] 뒤에는 다시 쓸 수 없다(설계 구체화 13). */
    public suspend fun connect(): Set<Domain> =
        connectMutex.withLock {
            check(!disconnected) { "VehicleSession cannot be reused after disconnect(); connect again through TeslaBle" }
            if (hasLoadedCache) return@withLock restoredDomains
            val restored = cacheSync?.load(dispatcher) ?: emptySet()
            restoredDomains = restored
            hasLoadedCache = true
            startEventCollector()
            dispatcher.start()
            restored
        }

    /**
     * Go `Vehicle.StartSession`: `shouldRetry()` 오류면 [retryInterval] 뒤 다시 핸드셰이크(`retryWhileRetriable`).
     * [handshake]는 절대 `setAwaiting`을 부르지 않으므로 시간 초과는 항상 `Failure(Timeout(afterSend = false))`다
     * (`withAttemptTimeout` — 세션이 만들어지지 않았고 부작용이 없다; 사용자 승인 답 b — 전용 타입 없음). 성공하면 세션 캐시에 저장한다(SDD §7.1).
     * 단 [disconnect]가 이미 닫기에 들어갔으면 저장하지 않는다.
     */
    public suspend fun startSession(
        domains: Set<Domain> = Dispatcher.ALL_DOMAINS,
        timeout: Duration = timeouts.handshakeTimeout,
    ): VehicleResult<Unit> {
        val result =
            withAttemptTimeout(timeout) {
                retryWhileRetriable(dispatcher.retryInterval) { handshake.startSessions(domains) }
            }
        if (result is VehicleResult.Success) {
            // 회복한 도메인을 핸드셰이크 자체의 저장에 넣는다(이벤트 처리 순서·유실과 무관)
            cacheSync?.let { sync -> domains.forEach { sync.markFresh(it) } }
            storeAfterHandshake()
        }
        return result
    }

    /** 파사드가 세션 상태 표시에 쓴다. */
    public val sessionEvents: SharedFlow<SessionEvent> get() = dispatcher.sessionEvents

    /**
     * 핸드셰이크 성공 저장. [disconnect]가 [Dispatcher.close]에 들어갔으면 건너뛴다(최종 리뷰 M5) — 닫힌 뒤의
     * `exportSessions()`는 비어 있어 disconnect 시점의 저장을 지워 버린다. [disconnectMutex] 안에서 확인·저장하므로
     * [disconnect]의 `closing` 표시와 겹치지 않는다(저장이 먼저 끝나거나, 표시를 보고 건너뛴다).
     */
    private suspend fun storeAfterHandshake() {
        val sync = cacheSync ?: return
        disconnectMutex.withLock {
            if (closing) return@withLock
            sync.store(dispatcher)
        }
    }

    /**
     * Go `Disconnect`: 캐시 저장(최초 호출만) → [Dispatcher.close](수신 중단 + 세션 키 소거 + 전송 닫기). [cacheSync] 저장은
     * Go `Vehicle.Disconnect` 자체가 아니라 Go CLI `cmd/tesla-control/main.go`의 `defer UpdateCachedSessions`에 대응한다
     * (176~177행) — 이 라이브러리는 그 책임을 여기로 옮겼다.
     *
     * Go처럼 여러 번 불러도 안전하다(vehicle.go 169~173행): 두 번째 호출부터는 저장을 건너뛴다(그러지 않으면 첫 호출이 이미
     * [Dispatcher.close]로 세션을 소거한 뒤라 빈 목록을 저장해 첫 저장을 지워 버린다 — [SessionCacheSync] KDoc). [Dispatcher.close]
     * 자체는 이미 멱등이라 매번 부른다.
     *
     * [connect]가 캐시 로드를 마치지 못했으면(부르지 않았거나 로드 중 취소됐으면) 저장을 건너뛴다 — 그러지 않으면 세션이 하나도 없는 채로
     * 빈 목록을 저장해 이전에 다른 [connect]가 채워 둔 캐시를 지워 버린다. Go CLI도 성공적인 Connect 뒤에만 저장한다
     * (`cmd/tesla-control/main.go:174-177`). [connect]에서 유일하게 suspend하는 지점은 [cacheSync]`.load`이므로, 그 지점에서
     * 취소되면(캐시를 다 읽기 전) 이 조건에 걸려 저장을 건너뛴다.
     *
     * 진행 중인 [connect]의 캐시 로드가 끝나기를 (취소 불가로) 기다린 뒤 닫는다.
     *
     * 저장이 호출자의 취소로 중단돼도(느린 캐시 I/O 등) [Dispatcher.close]는 `finally`의 `NonCancellable` 안에서 반드시
     * 실행된다 — 세션 키가 메모리에 남거나 전송이 열린 채로 남지 않는다. 호출자의 취소는 값이 되지 않고 `CancellationException`으로
     * 그대로 전파된다(ADR-0010).
     *
     * **동시 호출 주의:** 두 번째 호출이 [disconnectMutex] 획득을 기다리는 동안 취소되면, 그 호출은 저장을 건너뛰고
     * (락을 얻지 못했으므로 저장 여부 확인도 못 한다) 곧바로 `finally`로 가 [Dispatcher.close]를 실행한다. 실제 멀티스레드
     * 디스패처(테스트의 단일 스레드 스케줄러가 아니라)에서는 이 `close()`가 첫 호출이 아직 락 안에서 진행 중인
     * [cacheSync]`.store`와 겹쳐 실행될 수 있다(마이크로초 단위 창) — `SessionState`마다 자체 뮤텍스가 `export`/`close`를
     * 직렬화해 메모리 안전성은 깨지지 않지만, 그 저장이 살아있는 세션을 내보낼지 이미 소거된(빈) 세션을 내보낼지는
     * 그 경쟁의 승자에 따라 달라진다.
     */
    public suspend fun disconnect() {
        try {
            connectMutex.withLock { eventJob }?.cancelAndJoin()
            storeOnceForDisconnect()
        } finally {
            withContext(NonCancellable) {
                disconnectMutex.withLock { closing = true } // 이후 startSession의 저장은 건너뛴다(빈 목록으로 캐시를 지우지 않게)
                // 진행 중인 connect()가 있으면 그 락을 기다린 뒤 수집기를 잡는다 — 닫힌 뒤에 저장하는 수집기가 남지 않는다.
                val job =
                    connectMutex.withLock {
                        disconnected = true
                        eventJob.also { eventJob = null }
                    }
                job?.cancelAndJoin()
                dispatcher.close()
            }
        }
    }

    // 설계 구체화 14: 세션정보 갱신 시 캐시 저장, 오래된 캐시 도메인 삭제. 수신 코루틴이 아니라 eventScope에서 저장한다(SDD §5).
    // dispatcher.start() 전에 UNDISPATCHED로 구독해 첫 이벤트를 놓치지 않는다.
    private fun startEventCollector() {
        val sync = cacheSync
        val scope = eventScope
        if (scope == null || sync == null) return
        eventJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.sessionEvents.collect { event -> handleEvent(sync, event) }
            }
    }

    private suspend fun handleEvent(
        sync: SessionCacheSync,
        event: SessionEvent,
    ) {
        try {
            when (event) {
                is SessionEvent.StaleCache -> sync.markStale(event.domain)
                is SessionEvent.Updated -> sync.markFresh(event.domain)
            }
            sync.store(dispatcher)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            dispatcher.log.log(
                LogLevel.WARN,
                "VehicleSession",
            ) { "Failed to store session cache after session update: ${e::class.simpleName}" }
        }
    }

    private suspend fun storeOnceForDisconnect() {
        disconnectMutex.withLock {
            if (storedOnDisconnect || !hasLoadedCache) return@withLock
            storedOnDisconnect = true
            cacheSync?.store(dispatcher)
        }
    }
}
