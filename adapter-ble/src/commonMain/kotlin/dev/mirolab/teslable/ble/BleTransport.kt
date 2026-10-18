// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// Connection.Send, rx, flush, Close, Receive, RetryInterval, AllowedLatency
package dev.mirolab.teslable.ble

import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.transport.Framer
import dev.mirolab.teslable.transport.Reassembler
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * BLE [Transport](Go `ble.Connection`). 생성 즉시 [GattLink.notifications]를 [scope]에서 수집해 재조립하고 [INBOX_CAPACITY]
 * 버퍼에 넣는다(설계 구체화 5 — 수집자가 오기 전의 메시지를 버리지 않는다). 링크가 끊기면 [incoming]이 완료되고 [state]가
 * `Disconnected(TransportError.Disconnected)`가 된다. [send]는 [sendMutex]로 프레임 단위 직렬화(Go `c.lock`). 프레임 중간 취소는
 * 전송을 닫는다(Go는 ctx를 무시). TX/RX 헥사는 DEBUG 로그(Go `ble.go:76, 113`).
 */
@OptIn(InternalTeslableApi::class)
internal class BleTransport(
    override val vin: Vin,
    private val link: GattLink,
    scope: CoroutineScope,
    timeSource: TimeSource = TimeSource.Monotonic,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
) : Transport {
    private val inbox = Channel<ByteArray>(INBOX_CAPACITY)
    private val stateFlow = MutableStateFlow<TransportState>(TransportState.Connected)
    private val reassembler = Reassembler(timeSource)
    private val sendMutex = Mutex()
    private val closeMutex = Mutex()
    private var closed = false

    // 수신 코루틴(deliver, linkEnded)에서만 읽고 쓴다 — 연결당 하나뿐이라 경합이 없다
    private var overflows = 0

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
    override val state: StateFlow<TransportState> = stateFlow
    override val retryInterval: Duration = RETRY_INTERVAL
    override val allowedLatency: Duration = ALLOWED_LATENCY

    private val receiver: Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                link.notifications.collect { chunk -> reassembler.push(chunk).forEach(::deliver) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // 플랫폼 링크 오류는 끊김으로 본다
                logger.log(LogLevel.WARN, TAG) { "BLE link closed with error: ${e::class.simpleName}" }
            } finally {
                linkEnded()
            }
        }

    private fun deliver(message: ByteArray) {
        logger.log(LogLevel.DEBUG, TAG) { "RX: ${message.toHex()}" }
        if (inbox.trySend(message).isFailure) {
            overflows++ // 로거가 지연 평가하므로 람다 밖에서 센다
            val count = overflows
            logger.log(LogLevel.ERROR, TAG) { "Dropping received message because inbox is full (dropped $count since connect)" }
        }
    }

    private fun linkEnded() {
        // 원본에 없음: Go(ble.go:78-82)는 조용히 버린다. 요약은 연결당 정확히 한 번, 수신 코루틴의 finally에서만 남긴다
        if (overflows > 0) logger.log(LogLevel.WARN, TAG) { "BLE inbox overflowed $overflows time(s) during this connection" }
        inbox.close()
        stateFlow.compareAndSet(TransportState.Connected, TransportState.Disconnected(VehicleError.TransportError.Disconnected))
    }

    override suspend fun send(message: ByteArray): VehicleResult<Unit> {
        if (message.size > Framer.MAX_MESSAGE_SIZE) {
            return VehicleResult.Failure(VehicleError.InvalidArgument("message exceeds ${Framer.MAX_MESSAGE_SIZE} bytes"))
        }
        return sendMutex.withLock {
            if (stateFlow.value != TransportState.Connected) return@withLock VehicleResult.Failure(VehicleError.TransportError.Disconnected)
            val chunks = Framer.frame(message, link.blockLength)
            logger.log(LogLevel.DEBUG, TAG) { "TX: ${chunks.joinToString("") { it.toHex() }}" }
            writeAll(chunks)
        }
    }

    private suspend fun writeAll(chunks: List<ByteArray>): VehicleResult<Unit> {
        var written = 0
        try {
            for (chunk in chunks) {
                val error = link.write(chunk)
                if (error != null) return VehicleResult.Failure(error)
                written++
            }
            return VehicleResult.Success(Unit)
        } catch (e: CancellationException) {
            if (written in 1 until chunks.size) {
                logger.log(LogLevel.WARN, TAG) { "Send cancelled mid-frame; closing the BLE transport" }
                withContext(NonCancellable) { close() }
            }
            throw e
        }
    }

    override suspend fun close() {
        closeMutex.withLock {
            if (closed) return
            closed = true
        }
        // 이미 끊긴 링크는 그 이유(Disconnected(TransportError))를 유지한다 — 의도한 종료만 reason = null
        stateFlow.compareAndSet(TransportState.Connected, TransportState.Disconnected(reason = null))
        receiver.cancel()
        inbox.close()
        link.disconnect()
    }

    companion object {
        /** 수신 버퍼(SDD §2.4: Go의 5보다 크게). */
        const val INBOX_CAPACITY: Int = 64

        /** Go `RetryInterval()`. */
        val RETRY_INTERVAL: Duration = 1.seconds

        /** Go `maxLatency`. */
        val ALLOWED_LATENCY: Duration = 4.seconds

        private const val TAG = "ble"
    }
}
