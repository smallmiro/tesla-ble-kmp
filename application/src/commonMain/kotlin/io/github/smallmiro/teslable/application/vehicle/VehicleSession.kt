// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle, Connect, StartSession, Disconnect
package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.cache.SessionCacheSync
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.retryWhileRetriable
import io.github.smallmiro.teslable.application.dispatcher.withAttemptTimeout
import io.github.smallmiro.teslable.application.infotainment.InfotainmentCommands
import io.github.smallmiro.teslable.application.vcsec.VcsecCommands
import io.github.smallmiro.teslable.model.VehicleResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val disconnectMutex = Mutex()
    private var storedOnDisconnect = false

    /** Go `StartSessions` 계층. */
    public val handshake: HandshakeFlow = HandshakeFlow(dispatcher)

    /** Go `Vehicle.Send`. */
    public val send: SendWithRetry = SendWithRetry(dispatcher, timeouts)

    /** Go `getVCSECResult` 계층(직렬화 포함). */
    public val vcsec: VcsecCommands = VcsecCommands(dispatcher, timeouts)

    /** Go `executeCarServerAction` 계층. */
    public val infotainment: InfotainmentCommands = InfotainmentCommands(send)

    /** 전송 계층의 재전송 간격. */
    public val retryInterval: Duration get() = dispatcher.retryInterval

    /** Go `NewVehicle`(캐시 복원) + `Connect`(수신 시작). 캐시로 준비된 도메인을 돌려준다. */
    public suspend fun connect(): Set<Domain> {
        val restored = cacheSync?.load(dispatcher) ?: emptySet()
        dispatcher.start()
        return restored
    }

    /**
     * Go `Vehicle.StartSession`: `shouldRetry()` 오류면 [retryInterval] 뒤 다시 핸드셰이크
     * ([io.github.smallmiro.teslable.application.dispatcher.retryWhileRetriable]). [handshake]는 절대 `setAwaiting`을
     * 부르지 않으므로 시간 초과는 항상 `Failure(Timeout(afterSend = false))`다
     * ([io.github.smallmiro.teslable.application.dispatcher.withAttemptTimeout] — 세션이 만들어지지 않았고 부작용이
     * 없다; 사용자 승인 답 b — 전용 타입 없음). 성공하면 세션 캐시에 저장한다(SDD §7.1).
     */
    public suspend fun startSession(
        domains: Set<Domain> = Dispatcher.ALL_DOMAINS,
        timeout: Duration = timeouts.handshakeTimeout,
    ): VehicleResult<Unit> {
        val result =
            withAttemptTimeout(timeout) {
                retryWhileRetriable(dispatcher.retryInterval) { handshake.startSessions(domains) }
            }
        if (result is VehicleResult.Success) cacheSync?.store(dispatcher)
        return result
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
     * 저장이 호출자의 취소로 중단돼도(느린 캐시 I/O 등) [Dispatcher.close]는 `finally`의 `NonCancellable` 안에서 반드시
     * 실행된다 — 세션 키가 메모리에 남거나 전송이 열린 채로 남지 않는다. 호출자의 취소는 값이 되지 않고 `CancellationException`으로
     * 그대로 전파된다(ADR-0010).
     *
     * **동시 호출 주의(N3):** 두 번째 호출이 [disconnectMutex] 획득을 기다리는 동안 취소되면, 그 호출은 저장을 건너뛰고
     * (락을 얻지 못했으므로 저장 여부 확인도 못 한다) 곧바로 `finally`로 가 [Dispatcher.close]를 실행한다. 실제 멀티스레드
     * 디스패처(테스트의 단일 스레드 스케줄러가 아니라)에서는 이 `close()`가 첫 호출이 아직 락 안에서 진행 중인
     * [cacheSync]`.store`와 겹쳐 실행될 수 있다(마이크로초 단위 창) — `SessionState`마다 자체 뮤텍스가 `export`/`close`를
     * 직렬화해 메모리 안전성은 깨지지 않지만, 그 저장이 살아있는 세션을 내보낼지 이미 소거된(빈) 세션을 내보낼지는
     * 그 경쟁의 승자에 따라 달라진다.
     */
    public suspend fun disconnect() {
        try {
            storeOnceForDisconnect()
        } finally {
            withContext(NonCancellable) { dispatcher.close() }
        }
    }

    private suspend fun storeOnceForDisconnect() {
        disconnectMutex.withLock {
            if (storedOnDisconnect) return@withLock
            storedOnDisconnect = true
            cacheSync?.store(dispatcher)
        }
    }
}
