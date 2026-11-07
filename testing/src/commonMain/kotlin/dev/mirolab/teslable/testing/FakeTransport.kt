// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher_test.go (Apache-2.0)
// dummyConnector: Sleep/Wake, EnqueueReply, EnqueueSendError, Send, AckRequests, RetryInterval, AllowedLatency
package dev.mirolab.teslable.testing

import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.TransportState
import kotlinx.coroutines.CompletableDeferred
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
@OptIn(InternalTeslableApi::class)
public class FakeTransport(
    override val vin: Vin = Vin(DEFAULT_VIN),
    override val retryInterval: Duration = 1.milliseconds,
    override val allowedLatency: Duration = 1.seconds,
) : Transport {
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Connected)
    private val sentMessages = mutableListOf<ByteArray>()

    // 한 줄로 순서를 지킨다: afterWrite = false는 쓰기 전 실패(enqueueSendError), true는 쓰기를 부른 뒤의 실패(enqueueWriteError)
    private class ScriptedError(
        val error: VehicleError,
        val afterWrite: Boolean,
    )

    private val sendErrors = ArrayDeque<ScriptedError>()
    private var deliveredCount = 0
    private var writesInvokedCount = 0

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

    /** 쓰기를 부른 횟수(`Transport.send`의 `onWriteInvoked`를 부른 횟수). [sent]는 쓰기 전에 실패한 전송까지 담는다. */
    public val writesInvoked: Int get() = writesInvokedCount

    /**
     * non-null이면 [send]가 쓰기를 부르기 **전에** 이 게이트를 기다린다(`BleTransport`의 전송 직렬화 대기). 취소할 수 있다.
     * 그동안 끝나면 쓰기를 부른 적이 없다.
     */
    public var preWriteGate: CompletableDeferred<Unit>? = null

    /**
     * non-null이면 [send]가 쓰기를 부르고 바이트를 차량에 넘긴 **뒤**, 쓰기 완료를 이 게이트까지 미룬다(write-with-response의 완료
     * 통지가 늦는 경우 — 2026-11-07 F4). 취소할 수 있다. 차량은 이미 요청을 받았고 답할 수 있다.
     */
    public var writeGate: CompletableDeferred<Unit>? = null

    /**
     * 다음 [send]가 쓰기를 부르기 **전에** [error]로 끝나게 한다(Go `EnqueueSendError`: 아무것도 보내지 않는다). [enqueueWriteError]와
     * 한 큐를 써서 넣은 순서대로 소비된다.
     */
    public fun enqueueSendError(error: VehicleError) {
        sendErrors.addLast(ScriptedError(error, afterWrite = false))
    }

    /**
     * 다음 [send]가 쓰기를 부른 **뒤** [error]로 끝나게 한다(실패한 쓰기: 바이트가 차량에 닿았는지 모른다 — 이 가짜는 차량에 넘기지
     * 않는다). [enqueueSendError]와 한 큐를 써서 넣은 순서대로 소비된다.
     */
    public fun enqueueWriteError(error: VehicleError) {
        sendErrors.addLast(ScriptedError(error, afterWrite = true))
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

    override suspend fun send(message: ByteArray): VehicleResult<Unit> = send(message) {}

    /**
     * [preWriteGate] 대기, [ackRequests]와 [enqueueSendError]의 오류는 쓰기를 부르기 **전**이다(Go `dummyConnector.Send`처럼 아무것도
     * 보내지 않는다). 그 밖에는 [onWriteInvoked]를 부른 뒤, [enqueueWriteError]의 오류면 그것으로 끝나고, 아니면 바이트를 차량([onSend])에
     * 넘기고 [writeGate]를 기다린다 — 바이트는 쓰기를 부르는 순간 차량으로 간다.
     */
    override suspend fun send(
        message: ByteArray,
        onWriteInvoked: () -> Unit,
    ): VehicleResult<Unit> {
        sentMessages += message.copyOf()
        preWriteGate?.await()
        // Go dummyConnector.Send checks !d.AckRequests before consuming errorQueue (dispatcher_test.go:251-254).
        if (!ackRequests) return VehicleResult.Failure(VehicleError.TransportError.Disconnected)
        val scripted = sendErrors.removeFirstOrNull()
        if (scripted != null && !scripted.afterWrite) return scripted.error.toResult()
        onWriteInvoked()
        writesInvokedCount++
        if (scripted != null) return scripted.error.toResult()
        onSend?.invoke(message.copyOf())
        writeGate?.await()
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
