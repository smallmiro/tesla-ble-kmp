// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle, Connect, StartSession, Disconnect
package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.cache.SessionCacheSync
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.retryWhileRetriable
import io.github.smallmiro.teslable.application.dispatcher.withAttemptTimeout
import io.github.smallmiro.teslable.model.VehicleResult
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

    /** Go `Disconnect`: 캐시 저장 → 수신 중단 → 세션 키 소거 → 전송 닫기. */
    public suspend fun disconnect() {
        cacheSync?.store(dispatcher)
        dispatcher.close()
    }
}
