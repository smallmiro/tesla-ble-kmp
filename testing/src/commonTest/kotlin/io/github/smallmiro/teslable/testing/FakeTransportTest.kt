package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeTransportTest {
    @Test
    fun matchesGoDummyConnectorTimings() {
        // dispatcher_test.go dummyConnector: RetryInterval = 1ms, AllowedLatency = 1s, VIN = "0123456789ABCDEFG"
        val transport = FakeTransport()
        assertEquals(1.milliseconds, transport.retryInterval)
        assertEquals(1.seconds, transport.allowedLatency)
        assertEquals("0123456789ABCDEFG", transport.vin.value)
        assertIs<TransportState.Connected>(transport.state.value)
    }

    @Test
    fun sendRecordsBytesAndInvokesHandler() =
        runTest {
            val transport = FakeTransport()
            val seen = mutableListOf<ByteArray>()
            transport.onSend = { seen += it }
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(1, 2, 3)))
            assertEquals(1, transport.sent.size)
            assertContentEquals(byteArrayOf(1, 2, 3), transport.sent.single())
            assertContentEquals(byteArrayOf(1, 2, 3), seen.single())
        }

    @Test
    fun queuedSendErrorsAreReturnedOnceInOrderWithoutInvokingHandler() =
        runTest {
            // dummyConnector.Send: errorQueue의 첫 오류를 돌려주고 handleAsync를 부르지 않는다
            val transport = FakeTransport()
            var handled = 0
            transport.onSend = { handled++ }
            transport.enqueueSendError(VehicleError.TransportError.WriteFailed("gatt"))
            transport.enqueueSendError(VehicleError.Timeout(afterSend = true))
            val first = assertIs<VehicleResult.Failure>(transport.send(byteArrayOf(1)))
            assertEquals(VehicleError.TransportError.WriteFailed("gatt"), first.error)
            val second = assertIs<VehicleResult.Uncertain>(transport.send(byteArrayOf(2)))
            assertEquals(VehicleError.Timeout(afterSend = true), second.error)
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(3)))
            assertEquals(1, handled)
            assertEquals(3, transport.sent.size) // 실패한 전송도 기록한다(Go inbox는 성공만 기록하지만 테스트 관찰용으로 전부 남긴다)
        }

    @Test
    fun ackRequestsFalseFailsEverySendWithoutRetry() =
        runTest {
            // dummyConnector.AckRequests = false → errTimeout(재시도 불가 오류)
            val transport = FakeTransport()
            transport.ackRequests = false
            val result = assertIs<VehicleResult.Failure>(transport.send(byteArrayOf(1)))
            assertEquals(VehicleError.TransportError.Disconnected, result.error)
            assertFalse(result.error.temporary)
        }

    @Test
    fun ackFailureTakesPrecedenceAndKeepsQueuedError() =
        runTest {
            // dummyConnector.Send: !d.AckRequests를 errorQueue보다 먼저 검사하고, 이때 errorQueue는 소비하지 않는다
            val transport = FakeTransport()
            transport.ackRequests = false
            transport.enqueueSendError(VehicleError.TransportError.WriteFailed("gatt"))
            val ackFailure = assertIs<VehicleResult.Failure>(transport.send(byteArrayOf(1)))
            assertEquals(VehicleError.TransportError.Disconnected, ackFailure.error)
            transport.ackRequests = true
            val queued = assertIs<VehicleResult.Failure>(transport.send(byteArrayOf(2)))
            assertEquals(VehicleError.TransportError.WriteFailed("gatt"), queued.error)
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(3)))
        }

    @Test
    fun deliverFeedsIncomingUnlessAsleep() =
        runTest {
            // dummyConnector.EnqueueReply: dropReplies면 버린다. Close 후 incoming은 완료된다.
            val transport = FakeTransport()
            assertTrue(transport.deliver(byteArrayOf(9)))
            transport.sleep()
            assertTrue(transport.isAsleep)
            assertFalse(transport.deliver(byteArrayOf(8)))
            transport.wake()
            assertTrue(transport.deliver(byteArrayOf(7)))
            transport.close()
            val received = transport.incoming.toList()
            assertEquals(listOf(9.toByte(), 7.toByte()), received.map { it.single() })
            assertEquals(2, transport.delivered)
            assertIs<TransportState.Disconnected>(transport.state.value)
        }
}
