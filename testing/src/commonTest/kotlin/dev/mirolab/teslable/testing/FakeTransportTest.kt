package dev.mirolab.teslable.testing

import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
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
    fun reportsTheWriteBeforeHandingTheBytesToTheVehicleButNotForPreWriteFailures() =
        runTest {
            // Transport.send(message, onWriteInvoked): 쓰기 전 실패(ackRequests, enqueueSendError)는 알리지 않고, 쓰면 차량에 넘기기 전에 알린다
            val transport = FakeTransport()
            val events = mutableListOf<String>()
            transport.onSend = { events += "vehicle" }
            transport.ackRequests = false
            transport.send(byteArrayOf(1)) { events += "write" }
            transport.ackRequests = true
            transport.enqueueSendError(VehicleError.TransportError.WriteFailed("gatt"))
            transport.send(byteArrayOf(2)) { events += "write" }
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(3)) { events += "write" })
            assertEquals(listOf("write", "vehicle"), events)
        }

    @Test
    fun writeErrorsFailAfterTheWriteIsReportedInTheOrderTheyWereQueued() =
        runTest {
            // enqueueSendError(쓰기 전)와 enqueueWriteError(쓰기 뒤)는 한 큐: 넣은 순서대로 소비되고, 쓰기 뒤 실패는 차량에 넘기지 않는다
            val transport = FakeTransport()
            var handled = 0
            transport.onSend = { handled++ }
            transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("first"))
            transport.enqueueSendError(VehicleError.TransportError.WriteFailed("second"))
            var reports = 0
            val first = transport.send(byteArrayOf(1)) { reports++ }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.WriteFailed("first")), first)
            assertEquals(1, reports)
            val second = transport.send(byteArrayOf(2)) { reports++ }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.WriteFailed("second")), second)
            assertEquals(1, reports)
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(3)) { reports++ })
            assertEquals(2, reports)
            assertEquals(2, transport.writesInvoked)
            assertEquals(1, handled)
            assertEquals(3, transport.sent.size)
        }

    @Test
    fun aWriteErrorAfterDeliveryHandsTheBytesToTheVehicleThenFails() =
        runTest {
            // 쓰기 완료 통지만 실패한 경우: 차량은 바이트를 받아 처리했고(응답도 보낼 수 있다) 전송은 오류를 돌려준다. 같은 큐를 쓴다
            val transport = FakeTransport()
            val events = mutableListOf<String>()
            transport.onSend = { events += "vehicle:${it.single()}" }
            transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("ack lost"))
            transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("not delivered"))
            val first = transport.send(byteArrayOf(1)) { events += "write" }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.WriteFailed("ack lost")), first)
            val second = transport.send(byteArrayOf(2)) { events += "write" }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.WriteFailed("not delivered")), second)
            assertIs<VehicleResult.Success<Unit>>(transport.send(byteArrayOf(3)) { events += "write" })
            assertEquals(listOf("write", "vehicle:1", "write", "write", "vehicle:3"), events)
            assertEquals(3, transport.writesInvoked)
        }

    @Test
    fun gatesHoldTheSendBeforeOrAfterTheWrite() =
        runTest {
            // preWriteGate: 쓰기를 부르기 전 대기. writeGate: 차량이 바이트를 받은 뒤 쓰기 완료를 미룬다
            val transport = FakeTransport()
            val handled = mutableListOf<Byte>()
            transport.onSend = { handled += it.single() }
            transport.preWriteGate = CompletableDeferred()
            val waiting = launch { transport.send(byteArrayOf(1)) }
            runCurrent()
            assertEquals(0, transport.writesInvoked)
            waiting.cancel()
            runCurrent()
            assertEquals(0, transport.writesInvoked)
            assertEquals(emptyList(), handled)
            transport.preWriteGate = null
            transport.writeGate = CompletableDeferred()
            val writing = async { transport.send(byteArrayOf(2)) }
            runCurrent()
            assertEquals(1, transport.writesInvoked)
            assertEquals(listOf<Byte>(2), handled) // 완료 전에 차량이 이미 받았다
            assertFalse(writing.isCompleted)
            transport.writeGate?.complete(Unit)
            assertIs<VehicleResult.Success<Unit>>(writing.await())
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
