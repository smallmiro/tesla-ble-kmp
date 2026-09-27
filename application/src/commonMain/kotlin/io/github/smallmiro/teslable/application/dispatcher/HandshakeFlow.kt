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
                Step.Ready -> {
                    VehicleResult.Success(Unit)
                }

                Step.Retry -> {
                    null
                }

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
