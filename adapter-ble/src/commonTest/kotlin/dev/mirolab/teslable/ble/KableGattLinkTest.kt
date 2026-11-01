package dev.mirolab.teslable.ble

import com.juul.kable.Characteristic
import com.juul.kable.Descriptor
import com.juul.kable.DiscoveredService
import com.juul.kable.ExperimentalKableApi
import com.juul.kable.Identifier
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class KableGattLinkTest {
    @Test
    fun disconnectStopsWaitingAtTheLimitAndStillClosesThePeripheral() =
        // #130 H5: connect가 시간을 넘겨 기다릴 수 있는 유일한 곳(시간이 다 된 순간 만들어진 전송을 닫을 때)의 상한
        runTest {
            val peripheral = StuckPeripheral()
            val link = KableGattLink(peripheral, blockLength = 20, Channel(Channel.UNLIMITED), Job(), backgroundScope)
            link.disconnect()
            assertEquals(5_000, currentTime) // 상수가 아니라 문서의 5초를 고정한다(상수를 읽으면 상수를 바꿔도 통과한다)
            assertEquals(1, peripheral.disconnects)
            assertEquals(1, peripheral.closes)
        }

    /** 플랫폼 끊김 신호가 오지 않는 주변기기: [disconnect]는 Kable의 `cancelAndJoin`처럼 취소될 때까지 기다린다. 나머지는 이 시험이 쓰지 않는다. */
    @OptIn(ExperimentalKableApi::class)
    private class StuckPeripheral : Peripheral {
        var disconnects = 0
            private set
        var closes = 0
            private set

        override val state: StateFlow<State> = MutableStateFlow(State.Connecting.Bluetooth)
        override val services: StateFlow<List<DiscoveredService>?> = MutableStateFlow(null)
        override val scope: CoroutineScope get() = unused()
        override val identifier: Identifier get() = unused()
        override val name: String? get() = unused()

        override suspend fun disconnect() {
            disconnects++
            awaitCancellation()
        }

        override fun close() {
            closes++
        }

        override suspend fun connect(): CoroutineScope = unused()

        override suspend fun maximumWriteValueLengthForType(writeType: WriteType): Int = unused()

        override suspend fun rssi(): Int = unused()

        override suspend fun read(characteristic: Characteristic): ByteArray = unused()

        override suspend fun write(
            characteristic: Characteristic,
            data: ByteArray,
            writeType: WriteType,
        ): Unit = unused()

        override suspend fun read(descriptor: Descriptor): ByteArray = unused()

        override suspend fun write(
            descriptor: Descriptor,
            data: ByteArray,
        ): Unit = unused()

        override fun observe(
            characteristic: Characteristic,
            onSubscription: suspend () -> Unit,
        ): Flow<ByteArray> = unused()

        private fun unused(): Nothing = error("not used by this test")
    }
}
