package dev.mirolab.teslable.sample

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 연결 시도 한 번의 결과. 실패 이유는 사람이 읽는 문장이다(로그의 `link.reason`). */
sealed interface ConnectOutcome<out V> {
    class Connected<V>(
        val vehicle: V,
    ) : ConnectOutcome<V>

    class Failed(
        val reason: String,
    ) : ConnectOutcome<Nothing>
}

/** 자동 재연결 상태 기계의 상태. */
sealed interface LinkState<out V> {
    /** 아직 아무것도 시작하지 않았다. */
    data object Idle : LinkState<Nothing>

    /** [attempt] 번째 연결 시도 중(시작이나 링크 끊김 뒤 1부터). */
    data class Connecting(
        val attempt: Int,
    ) : LinkState<Nothing>

    data class Connected<V>(
        val vehicle: V,
    ) : LinkState<V>

    /** 연속 [attempt] 번째 실패(링크 끊김 포함) 뒤 [delay] 만큼 쉬는 중. */
    data class Backoff(
        val attempt: Int,
        val delay: Duration,
        val reason: String?,
    ) : LinkState<Nothing>

    /** 사용자가 끊었다. 다시 연결 버튼을 누를 때까지 재연결하지 않는다(SFR-012). */
    data object Stopped : LinkState<Nothing>
}

/** 2초에서 시작해 두 배씩 늘어 최대 30초(SFR-012). */
data class BackoffPolicy(
    val initial: Duration = 2.seconds,
    val max: Duration = 30.seconds,
) {
    /** 연속 [failures] 번째 실패(1부터) 뒤의 대기 시간. */
    fun delayFor(failures: Int): Duration {
        var d = initial
        repeat((failures - 1).coerceAtLeast(0)) { d = (d * 2).coerceAtMost(max) }
        return d.coerceAtMost(max)
    }
}

/**
 * 연결 유지 상태 기계(SFR-012). 차량 타입 [V]는 모른다: 연결은 [connect]가, 링크 끊김 대기는 [awaitLoss]가, 정리는 [release]가 한다
 * (실제 앱에서는 `TeslaBle.connect`, `Vehicle.connection`이 `Disconnected`가 될 때까지 기다리기, `Vehicle.disconnect()`).
 *
 * - [start]: 연결 버튼과 앱 시작 자동 연결. 쉬는 중이면 기다리지 않고 바로 시도한다. 연결 중이거나 연결돼 있으면 아무 일도 없다.
 * - [stop]: 연결 끊기 버튼. 진행 중인 시도를 취소하고 차량을 [release]한 뒤 [LinkState.Stopped]. 이후 [start]를 누를 때까지 재연결하지 않는다.
 * - 연결이 [stableAfter] 이상 유지됐다가 끊기면 대기 시간이 처음(2초)부터 다시 시작한다. 그보다 빨리 끊기면 이어서 늘어난다.
 */
class ConnectionLoop<V : Any>(
    private val scope: CoroutineScope,
    private val connect: suspend () -> ConnectOutcome<V>,
    private val awaitLoss: suspend (V) -> String?,
    private val release: suspend (V) -> Unit,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val clock: TimeSource = TimeSource.Monotonic,
    private val stableAfter: Duration = 10.seconds,
    private val onTransition: (LinkState<V>) -> Unit = {},
) {
    private val mutableState = MutableStateFlow<LinkState<V>>(LinkState.Idle)
    val state: StateFlow<LinkState<V>> = mutableState

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    /** 취소했지만 아직 정리(`release`)가 끝나지 않았을 수 있는 이전 루프들. 새 루프는 이것이 끝난 뒤에야 연결한다. */
    private var teardown: Job? = null

    @Synchronized
    fun start() {
        val running = job
        if (running != null && running.isActive) {
            if (mutableState.value is LinkState.Backoff) wake.trySend(Unit)
            return
        }
        wake.tryReceive()
        val previous = teardown
        job =
            scope.launch {
                // 이전 차량의 disconnect()가 끝나기 전에 새로 연결하지 않는다(차의 연결 슬롯은 3개뿐이다).
                previous?.join()
                run()
            }
    }

    suspend fun stop() {
        val waiting =
            synchronized(this) {
                val running = job
                job = null
                if (running != null) {
                    running.cancel()
                    val older = teardown
                    teardown = scope.launch { older?.join(); running.join() }
                }
                teardown
            }
        waiting?.join()
        // 정리하는 동안 사용자가 연결을 다시 눌렀으면([start]가 새 루프를 띄웠다) 그 루프의 상태를 덮어쓰지 않는다.
        val restarted = synchronized(this) { job != null }
        if (!restarted) publish(LinkState.Stopped)
    }

    private fun publish(next: LinkState<V>) {
        mutableState.value = next
        onTransition(next)
    }

    private suspend fun run() {
        var tries = 0
        var failures = 0
        while (true) {
            tries++
            publish(LinkState.Connecting(tries))
            var reason: String?
            when (val outcome = attempt()) {
                is ConnectOutcome.Failed -> {
                    failures++
                    reason = outcome.reason
                }
                is ConnectOutcome.Connected -> {
                    val vehicle = outcome.vehicle
                    publish(LinkState.Connected(vehicle))
                    val since = clock.markNow()
                    reason =
                        try {
                            awaitLoss(vehicle)
                        } finally {
                            // 링크가 끊겨도, 사용자가 끊어도 차량은 반드시 정리한다(Vehicle.disconnect()).
                            withContext(NonCancellable) { release(vehicle) }
                        }
                    failures = if (since.elapsedNow() >= stableAfter) 1 else failures + 1
                    tries = 0
                }
            }
            val delay = backoff.delayFor(failures)
            wake.tryReceive() // 쉬기 전에 남은 신호를 비운다
            publish(LinkState.Backoff(failures, delay, reason))
            withTimeoutOrNull(delay) { wake.receive() }
        }
    }

    /** [connect]가 던진 예외(취소 제외)는 실패로 바꿔 루프가 죽지 않게 한다. */
    private suspend fun attempt(): ConnectOutcome<V> =
        try {
            connect()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            ConnectOutcome.Failed(e.message ?: e::class.simpleName.orEmpty())
        }
}
