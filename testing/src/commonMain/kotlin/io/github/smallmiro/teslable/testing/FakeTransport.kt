// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher_test.go (Apache-2.0)
// dummyConnector: Sleep/Wake, EnqueueReply, EnqueueSendError, Send, AckRequests, RetryInterval, AllowedLatency
package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 대본을 따르는 [Transport](Go `dispatcher_test.go dummyConnector`). `runTest` 단일 스레드 전용이며 스레드 안전하지 않다.
 * [send]는 [onSend]를 **동기로** 부른다(Go는 `handleAsync` goroutine이지만 결정성을 위해 같은 코루틴에서 처리한다);
 * 응답은 [deliver]로 [incoming]에 넣는다.
 */
public class FakeTransport(
    override val vin: Vin = Vin(DEFAULT_VIN),
    override val retryInterval: Duration = 1.milliseconds,
    override val allowedLatency: Duration = 1.seconds,
) : Transport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Connected)
    private val sentMessages = mutableListOf<ByteArray>()
    private val sendErrors = ArrayDeque<VehicleError>()
    private var deliveredCount = 0

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
    override val state: StateFlow<TransportState> = stateFlow

    /** [send]가 부르는 차량 측 처리기(Go `dummyConnector.callback`). `FakeVehicle`이 설정한다. */
    public var onSend: (suspend (ByteArray) -> Unit)? = null

    /** false면 모든 [send]가 재시도 불가 오류로 실패한다(Go `AckRequests = false` → `errTimeout`). */
    public var ackRequests: Boolean = true

    /** 지금까지 [send]에 들어온 바이트(실패한 전송 포함). Go `inbox`. */
    public val sent: List<ByteArray> get() = sentMessages.map { it.copyOf() }

    /** [deliver]로 [incoming]에 실제로 들어간 메시지 수. */
    public val delivered: Int get() = deliveredCount

    /** true면 [deliver]가 버린다(Go `dropReplies`). */
    public var isAsleep: Boolean = false
        private set

    /** 다음 [send]가 [error]를 돌려주게 한다(Go `EnqueueSendError`). 큐는 순서대로 소비된다. */
    public fun enqueueSendError(error: VehicleError) {
        sendErrors.addLast(error)
    }

    /** 응답을 버리기 시작한다(Go `Sleep`). */
    public fun sleep() {
        isAsleep = true
    }

    /** 응답을 다시 전달한다(Go `Wake`). */
    public fun wake() {
        isAsleep = false
    }

    /** 차량 → 클라이언트 메시지를 넣는다(Go `EnqueueReply`). 잠들었거나 닫혔으면 false. */
    public fun deliver(bytes: ByteArray): Boolean {
        if (isAsleep) return false
        val ok = inbox.trySend(bytes.copyOf()).isSuccess
        if (ok) deliveredCount++
        return ok
    }

    override suspend fun send(message: ByteArray): VehicleResult<Unit> {
        sentMessages += message.copyOf()
        sendErrors.removeFirstOrNull()?.let { return it.toResult() }
        if (!ackRequests) return VehicleResult.Failure(VehicleError.TransportError.Disconnected)
        onSend?.invoke(message.copyOf())
        return VehicleResult.Success(Unit)
    }

    override suspend fun close() {
        isAsleep = true
        inbox.close()
        stateFlow.value = TransportState.Disconnected(reason = null)
    }

    /** 상수. */
    public companion object {
        /** Go `dummyConnector.VIN()`. 비밀 스캔 허용 목록에 있는 테스트 VIN. */
        public const val DEFAULT_VIN: String = "0123456789ABCDEFG"
    }
}
