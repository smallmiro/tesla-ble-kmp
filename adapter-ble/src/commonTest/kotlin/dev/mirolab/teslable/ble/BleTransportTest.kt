package dev.mirolab.teslable.ble

import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.transport.Framer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class BleTransportTest {
    private val vin = Vin("5YJ30123456789ABC")

    private fun TestScope.transport(
        link: FakeGattLink,
        logger: RecordingLogger = RecordingLogger(),
    ) = BleTransport(vin, link, backgroundScope, testTimeSource, logger)

    @Test
    fun framesAndSplitsByBlockLengthInOrder() =
        // ble.go:108-127: [len>>8, len&0xff] + payload, blockLength 단위 순차 쓰기(write with response)
        runTest {
            val link = FakeGattLink(blockLength = 4)
            val result = transport(link).send(byteArrayOf(1, 2, 3, 4, 5))
            assertEquals(VehicleResult.Success(Unit), result)
            assertEquals(listOf("00050102", "030405"), link.written.map { it.hex() })
        }

    @Test
    fun reassemblesChunksIntoMessages() =
        // ble.go:67-87 flush
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            link.notify(byteArrayOf(0, 3, 9))
            link.notify(byteArrayOf(8, 7, 0, 1))
            link.notify(byteArrayOf(6))
            runCurrent()
            val messages = t.incoming.take(2).toList()
            assertEquals(listOf("090807", "06"), messages.map { it.hex() })
        }

    @Test
    fun buffersNotificationsArrivingBeforeAnyCollector() =
        // Review Focus 2 / 설계 구체화 5: 디스패처가 구독하기 전에 온 응답도 버리지 않는다
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            repeat(10) { i -> link.notify(byteArrayOf(0, 1, i.toByte())) }
            runCurrent()
            assertEquals(
                (0 until 10).map { listOf(it.toByte()) },
                t.incoming
                    .take(10)
                    .toList()
                    .map { it.toList() },
            )
        }

    @Test
    fun countsInboxOverflowsPerConnection() =
        // 인계 노트 범위 3: 넘친 횟수를 센다(Go ble.go:78-82는 조용히 버린다)
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            repeat(BleTransport.INBOX_CAPACITY + 3) { link.notify(byteArrayOf(0, 1, 7)) }
            runCurrent()
            assertTrue(logger.contains("Dropping received message because inbox is full (dropped 1 since connect)"))
            assertTrue(logger.contains("Dropping received message because inbox is full (dropped 3 since connect)"))
            assertEquals(0, logger.count("inbox overflowed"))
            t.close()
            t.close()
            runCurrent()
            val summary = logger.entries.single { it.message.contains("inbox overflowed") }
            assertEquals(LogLevel.WARN, summary.level)
            assertEquals("BLE inbox overflowed 3 time(s) during this connection", summary.message)
        }

    @Test
    fun noOverflowSummaryWhenInboxNeverOverflowed() =
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            link.notify(byteArrayOf(0, 1, 7))
            runCurrent()
            t.close()
            runCurrent()
            assertEquals(0, logger.count("inbox overflowed"))
        }

    @Test
    fun dropsWithErrorLogWhenInboxIsFull() =
        // ble.go:80-84: inbox가 가득 차면 버린다(Go는 5, 우리는 64 — SDD §2.4)
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            repeat(BleTransport.INBOX_CAPACITY + 1) { link.notify(byteArrayOf(0, 1, 7)) }
            runCurrent()
            assertEquals(1, logger.count("Dropping received message because inbox is full"))
            assertEquals(LogLevel.ERROR, logger.entries.single { it.message.contains("inbox is full") }.level)
            assertEquals(
                BleTransport.INBOX_CAPACITY,
                t.incoming
                    .take(BleTransport.INBOX_CAPACITY)
                    .toList()
                    .size,
            )
        }

    @Test
    fun resetsReassemblyAfterOneSecondGap() =
        // ble.go:98-106 rxTimeout = 1s
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            link.notify(byteArrayOf(0, 2, 1))
            runCurrent()
            advanceTimeBy(1.seconds + 1.milliseconds)
            link.notify(byteArrayOf(0, 1, 5))
            runCurrent()
            assertEquals("05", t.incoming.first().hex())
        }

    @Test
    fun logsTxAndRxHexAtDebugOnly() =
        // ble.go:76 RX: %02x, :113 TX: %02x (FR-112)
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            t.send(byteArrayOf(0x0A))
            link.notify(byteArrayOf(0, 1, 0x0B))
            runCurrent()
            t.incoming.first()
            val tx = logger.entries.single { it.message.startsWith("TX: ") }
            val rx = logger.entries.single { it.message.startsWith("RX: ") }
            assertEquals("TX: 00010a", tx.message)
            assertEquals("RX: 0b", rx.message)
            assertEquals(LogLevel.DEBUG, tx.level)
            assertEquals(LogLevel.DEBUG, rx.level)
        }

    @Test
    fun exposesBleRetryIntervalAndAllowedLatency() =
        runTest {
            val t = transport(FakeGattLink())
            assertEquals(1.seconds, t.retryInterval) // ble.go:59-61
            assertEquals(4.seconds, t.allowedLatency) // ble.go:29, 94-96
            assertEquals(vin, t.vin)
            assertEquals(TransportState.Connected, t.state.value)
        }

    @Test
    fun oversizedMessageIsInvalidArgumentNotException() =
        runTest {
            val result = transport(FakeGattLink()).send(ByteArray(1025))
            val failure = assertIs<VehicleResult.Failure>(result)
            assertIs<VehicleError.InvalidArgument>(failure.error)
        }

    @Test
    fun aChargeScheduleNamePastTheMessageLimitIsRefusedWithTheDocumentedDetail() =
        // 16-lib-errors §Invalid arguments: 메시지(길이 접두사를 뺀 RoutableMessage)는 Framer.MAX_MESSAGE_SIZE(1024바이트, Go maxBLEMessageSize)까지다.
        // 명령 빌더는 Go처럼 일정 필드를 검사하지 않으므로 긴 name은 이 전송 검사에서 걸린다: 1024바이트는 보내고, 이름이 한 글자 더 길어
        // 1025바이트가 되면 쓰기를 부르지 않고 InvalidArgument("message exceeds 1024 bytes")다. 실제 명령은 암호화·서명이 붙어 더 길다
        runTest {
            fun message(nameLength: Int): ByteArray {
                val schedule = ChargeSchedule(id = 1, name = "n".repeat(nameLength), enabled = true)
                val action = Action(vehicleAction = VehicleAction(addChargeScheduleAction = schedule))
                return RoutableMessage(
                    to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
                    protobuf_message_as_bytes = action.encode().toByteString(),
                ).encode()
            }
            val atLimit = (900..Framer.MAX_MESSAGE_SIZE).first { message(it).size == Framer.MAX_MESSAGE_SIZE }
            val link = FakeGattLink()
            val transport = transport(link)
            assertEquals(VehicleResult.Success(Unit), transport.send(message(atLimit)))
            val writesAtLimit = link.written.size
            var writeInvoked = false

            val result = transport.send(message(atLimit + 1)) { writeInvoked = true }

            assertEquals(1025, message(atLimit + 1).size)
            assertEquals(VehicleResult.Failure(VehicleError.InvalidArgument("message exceeds 1024 bytes")), result)
            assertEquals(writesAtLimit, link.written.size)
            assertFalse(writeInvoked)
        }

    @Test
    fun writeFailureIsRetriableWriteFailed() =
        runTest {
            val link = FakeGattLink().apply { writeErrors.addLast(VehicleError.TransportError.WriteFailed("gatt 133")) }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.WriteFailed("gatt 133")), transport(link).send(byteArrayOf(1)))
        }

    @Test
    fun linkDropCompletesIncomingAndMarksDisconnected() =
        // Review Focus 1
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            val collected = mutableListOf<ByteArray>()
            val job = backgroundScope.launch { t.incoming.collect { collected += it } }
            link.notify(byteArrayOf(0, 1, 1))
            runCurrent()
            link.drop()
            runCurrent()
            assertTrue(job.isCompleted)
            assertEquals(1, collected.size)
            assertEquals(TransportState.Disconnected(VehicleError.TransportError.Disconnected), t.state.value)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.Disconnected), t.send(byteArrayOf(1)))
        }

    @Test
    fun linkFailureAlsoDisconnectsWithoutThrowing() =
        runTest {
            val link = FakeGattLink()
            val logger = RecordingLogger()
            val t = transport(link, logger)
            link.fail(IllegalStateException("gatt error"))
            runCurrent()
            assertEquals(TransportState.Disconnected(VehicleError.TransportError.Disconnected), t.state.value)
            assertEquals(emptyList(), t.incoming.toList())
            assertTrue(logger.contains("BLE link closed with error"))
        }

    @Test
    fun closeIsIdempotentAndDisconnectsOnce() =
        // connector.go:48-52 Close 멱등 계약 (Go ble.go:89-92 자체는 보장하지 않음 — 매뉴얼 02 §13.7과 원본 차이)
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            t.close()
            t.close()
            assertEquals(1, link.disconnects)
            assertEquals(TransportState.Disconnected(null), t.state.value)
            assertEquals(emptyList(), t.incoming.toList())
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.Disconnected), t.send(byteArrayOf(1)))
        }

    @Test
    fun closeAfterLinkDropKeepsTheDropReason() =
        // 최종 리뷰 I1: 링크 감시자가 끊긴 전송을 close()해도 차량이 끊은 이유가 Disconnected(null)로 덮이지 않는다
        runTest {
            val link = FakeGattLink()
            val t = transport(link)
            link.drop()
            runCurrent()
            t.close()
            assertEquals(TransportState.Disconnected(VehicleError.TransportError.Disconnected), t.state.value)
            assertEquals(1, link.disconnects)
        }

    @Test
    fun concurrentSendsDoNotInterleaveChunks() =
        // ble.go:109 c.lock: 한 프레임의 청크가 다른 프레임과 섞이지 않는다
        runTest {
            val link = FakeGattLink(blockLength = 2)
            val t = transport(link)
            val a = launch { t.send(byteArrayOf(1, 1, 1)) }
            val b = launch { t.send(byteArrayOf(2, 2, 2)) }
            a.join()
            b.join()
            assertEquals(listOf("0003", "0101", "01", "0003", "0202", "02"), link.written.map { it.hex() })
        }

    @Test
    fun cancellationMidFrameClosesTheTransport() =
        // 설계 구체화 5: 반쯤 쓴 프레임 뒤 다음 프레임이 차량 재조립 버퍼에서 섞이지 않게
        runTest {
            val link = FakeGattLink(blockLength = 2)
            val t = transport(link)
            link.gateAfterWrites = 1
            link.writeGate = CompletableDeferred()
            val job = launch { t.send(byteArrayOf(1, 2, 3)) }
            runCurrent() // 첫 청크("0003")를 쓰고 두 번째 청크에서 게이트를 기다린다
            assertEquals(1, link.written.size)
            job.cancel()
            runCurrent()
            assertEquals(TransportState.Disconnected(null), t.state.value)
            assertEquals(1, link.disconnects)
        }

    @Test
    fun cancellationBeforeFirstChunkKeepsTheTransportOpen() =
        runTest {
            val link = FakeGattLink().apply { writeGate = CompletableDeferred() }
            val t = transport(link)
            val job = launch { t.send(byteArrayOf(1)) }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(TransportState.Connected, t.state.value)
            assertEquals(0, link.disconnects)
        }

    @Test
    fun reportsTheWriteJustBeforeTheFirstChunkOfAFrame() =
        // H1(ADR-0010 2026-11-07 개정): 첫 link.write를 부르기 직전에 한 번 — 한 청크 프레임(2026-11-07 F4)과 여러 청크 프레임 모두
        runTest {
            for ((blockLength, chunks) in listOf(20 to 1, 2 to 3)) {
                val link = FakeGattLink(blockLength = blockLength)
                val writeCallsAtReport = mutableListOf<Int>()
                val result = transport(link).send(byteArrayOf(1, 2, 3)) { writeCallsAtReport += link.writeCalls }
                assertEquals(VehicleResult.Success(Unit), result)
                assertEquals(listOf(0), writeCallsAtReport) // 한 번, 아직 어떤 쓰기도 부르기 전에
                assertEquals(chunks, link.writeCalls)
            }
        }

    @Test
    fun doesNotReportAWriteWhileWaitingForTheSendMutex() =
        // ble.go:109 c.lock 대기는 "보내기 전"이다: 그동안 취소(시간 초과)되면 쓰기를 부른 적이 없다
        runTest {
            val link = FakeGattLink().apply { writeGate = CompletableDeferred() }
            val t = transport(link)
            val first = launch { t.send(byteArrayOf(1)) }
            runCurrent() // 첫 send가 잠금을 쥐고 쓰기 게이트에서 기다린다
            var reports = 0
            val second = launch { t.send(byteArrayOf(2)) { reports++ } }
            runCurrent() // 두 번째 send는 잠금을 기다린다
            second.cancel()
            runCurrent()
            assertEquals(0, reports)
            assertEquals(1, link.writeCalls)
            assertTrue(link.writeGate!!.complete(Unit))
            first.join()
            assertEquals(listOf("000101"), link.written.map { it.hex() })
        }

    @Test
    fun doesNotReportAWriteWhenTheSendEndsBeforeWriting() =
        // 크기 검사와 연결 검사에서 끝나면 쓰기를 부르지 않는다
        runTest {
            var reports = 0
            val oversized = transport(FakeGattLink()).send(ByteArray(1025)) { reports++ }
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(oversized).error)
            val link = FakeGattLink()
            val t = transport(link)
            link.drop()
            runCurrent()
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.Disconnected), t.send(byteArrayOf(1)) { reports++ })
            assertEquals(0, reports)
            assertEquals(0, link.writeCalls)
        }

    @Test
    fun reportsTheWriteEvenWhenTheWriteFails() =
        // 실패한 write-with-response도 바이트가 닿았을 수 있다: 알림은 쓰기 결과와 무관하다(되돌리지 않는 것은 디스패처 몫)
        runTest {
            val link = FakeGattLink().apply { writeErrors.addLast(VehicleError.TransportError.WriteFailed("gatt 133")) }
            var reports = 0
            val result = transport(link).send(byteArrayOf(1)) { reports++ }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.WriteFailed("gatt 133")), result)
            assertEquals(1, reports)
        }

    @Test
    fun doesNotWriteOrReportWhenAlreadyCancelledBeforeTheFirstWrite() =
        // 시간 초과가 잠금을 얻은 뒤·첫 쓰기 전에 취소를 걸었으면(TX 로그 직후) 쓰지도 알리지도 않는다 — 쓰기를 부르지 않았으므로 "보내기 전"
        runTest {
            val link = FakeGattLink()
            lateinit var sending: Job
            val cancelOnTx =
                object : TeslaLogger {
                    override fun log(
                        level: LogLevel,
                        tag: String,
                        message: () -> String,
                    ) {
                        if (message().startsWith("TX:")) sending.cancel()
                    }
                }
            val t = BleTransport(vin, link, backgroundScope, testTimeSource, cancelOnTx)
            var reports = 0
            sending = launch { t.send(byteArrayOf(1)) { reports++ } }
            runCurrent()
            assertTrue(sending.isCancelled)
            assertEquals(0, reports)
            assertEquals(0, link.writeCalls)
            assertEquals(TransportState.Connected, t.state.value)
        }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
