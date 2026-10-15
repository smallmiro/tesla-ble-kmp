@file:OptIn(ExperimentalUuidApi::class)

package io.github.smallmiro.teslable.ble

import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import io.github.smallmiro.teslable.model.VehicleError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

/** `disconnect()`가 플랫폼 끊김을 기다리는 상한. 넘으면 기다리기를 멈추고 자원만 정리한다. */
internal val DISCONNECT_TIMEOUT = 5.seconds

/**
 * Kable [Peripheral] 위의 [GattLink]. 0213 관찰은 [KableGattConnector.open]이 연결 **전에** 시작해 [chunks]에 모은다(Kable은
 * 연결 중 구독 실패를 `connect()`에서 던진다 — Go `Subscribe` 실패 = 재시도). Kable `observe`는 연결이 끊겨도 끝나지 않으므로
 * `state`가 `Disconnected`가 되면 [chunks]를 닫아 [notifications]를 끝낸다.
 *
 * [disconnect]는 `NonCancellable`에서 돌지만 플랫폼 끊김 대기는 [DISCONNECT_TIMEOUT]으로 제한한다(멈춘 끊김이 영원히 막지 않도록).
 */
internal class KableGattLink(
    private val peripheral: Peripheral,
    override val blockLength: Int,
    private val chunks: Channel<ByteArray>,
    private val observeJob: Job,
    scope: CoroutineScope,
) : GattLink {
    private val tx = characteristicOf(BleUuids.SERVICE, BleUuids.TO_VEHICLE)
    private val closeMutex = Mutex()
    private var closed = false

    private val watcher: Job =
        scope.launch {
            peripheral.state.first { it is State.Disconnected }
            chunks.close()
        }

    override val notifications: Flow<ByteArray> = chunks.receiveAsFlow()

    override suspend fun write(chunk: ByteArray): VehicleError.TransportError? =
        try {
            peripheral.write(tx, chunk, WriteType.WithResponse) // ble.go:121 noRsp=false (Kable 기본값은 WithoutResponse)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            when (val mapped = KableErrors.map(e)) {
                is VehicleError.TransportError.ConnectFailed -> VehicleError.TransportError.WriteFailed(mapped.cause)
                else -> mapped
            }
        }

    override suspend fun disconnect() {
        closeMutex.withLock {
            if (closed) return
            closed = true
        }
        withContext(NonCancellable) {
            watcher.cancel()
            observeJob.cancel()
            chunks.close()
            @Suppress("TooGenericExceptionCaught", "SwallowedException") // 끊는 중의 오류는 이미 끊긴 것과 같다
            try {
                withTimeoutOrNull(DISCONNECT_TIMEOUT) { peripheral.disconnect() }
            } catch (e: Exception) {
                // 정리 계속
            }
            peripheral.close()
        }
    }
}
