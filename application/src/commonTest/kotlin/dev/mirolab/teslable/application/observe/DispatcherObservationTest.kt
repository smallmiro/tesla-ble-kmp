package dev.mirolab.teslable.application.observe

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleStatus
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.PendingRequest
import dev.mirolab.teslable.application.dispatcher.StuckEcdhKey
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.dispatcher.encode
import dev.mirolab.teslable.application.dispatcher.replyTo
import dev.mirolab.teslable.application.dispatcher.testCommand
import dev.mirolab.teslable.application.vcsec.VcsecResponses
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.hexToBytes
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import dev.mirolab.teslable.model.VehicleStatus as ModelVehicleStatus

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class DispatcherObservationTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    private fun RecordingLogger.observed(): List<String> = entries.filter { it.tag == ObservationLog.TAG }.map { it.message }

    private fun locked() = FromVCSECMessage(vehicleStatus = VehicleStatus(vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED))

    @Test
    fun addresslessVehicleStatusIsLoggedAndStillDropped() =
        runTest {
            val h = dispatcherHarness()
            val push =
                RoutableMessage(
                    from_destination = Destination(domain = vcsec),
                    protobuf_message_as_bytes = locked().encode().toByteString(),
                )
            h.transport.deliver(RoutableMessage.ADAPTER.encode(push))
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:missing-destination] "), line)
            assertTrue(line.contains("lock=VEHICLELOCKSTATE_LOCKED"), line)
            assertTrue(h.logger.contains("Dropping message with missing destination")) // 기존 드롭 로그 그대로
            // 응답 처리는 그대로다: 이어지는 조회가 자기 응답을 받는다
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(locked())))
            assertIs<VehicleResult.Success<ModelVehicleStatus>>(VehicleSession(h.dispatcher).info.vehicleStatus())
        }

    @Test
    fun domainAddressedMessageIsLoggedAsToDomain() =
        runTest {
            val h = dispatcherHarness()
            val push =
                RoutableMessage(
                    from_destination = Destination(domain = vcsec),
                    to_destination = Destination(domain = Domain.DOMAIN_BROADCAST),
                    protobuf_message_as_bytes = locked().encode().toByteString(),
                )
            h.transport.deliver(RoutableMessage.ADAPTER.encode(push))
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:to-domain] "), line)
            assertTrue(line.contains("to=domain(DOMAIN_BROADCAST)"), line)
        }

    @Test
    fun broadcastUnknownField48IsDetailedAndStillDropped() =
        runTest {
            val h = dispatcherHarness()
            val unknown48 = "820308" + "0803" + "1204" + "41424344" // field 48 LEN(8): {1:varint=3, 2:"ABCD"}
            val push =
                RoutableMessage(
                    from_destination = Destination(domain = vcsec),
                    to_destination = Destination(domain = Domain.DOMAIN_BROADCAST),
                    protobuf_message_as_bytes = (locked().encode() + unknown48.hexToBytes()).toByteString(),
                )
            h.transport.deliver(RoutableMessage.ADAPTER.encode(push))
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:to-domain] "), line)
            assertTrue(line.endsWith("unknownFields=[48:len(8){1:varint=3(0x3), 2:len(4)\"ABCD\"}]}"), line)
        }

    @Test
    fun unwrappedFromVcsecMessageIsReinterpretedAndDropped() =
        runTest {
            val h = dispatcherHarness()
            val frame =
                FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT)).encode() +
                    "4a00".hexToBytes()
            h.transport.deliver(frame)
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:missing-source] "), line)
            assertTrue(line.endsWith("reinterpreted=FromVCSECMessage{commandStatus{op=OPERATIONSTATUS_WAIT}, unknownFields=[9]}"), line)
            assertTrue(h.logger.contains("Dropping message with missing source"))
        }

    @Test
    fun vehicleStatusWithMissingFieldsToAnotherAddressIsNoHandler() =
        runTest {
            val h = dispatcherHarness()
            val push =
                RoutableMessage(
                    from_destination = Destination(domain = vcsec),
                    to_destination = Destination(routing_address = ByteArray(16) { 9 }.toByteString()),
                    protobuf_message_as_bytes = locked().encode().toByteString(),
                )
            h.transport.deliver(RoutableMessage.ADAPTER.encode(push))
            runCurrent()
            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:no-handler] "), line)
            assertTrue(line.contains("to=address(16B, other)"), line)
            assertTrue(line.contains("absent=[closureStatuses, detailedClosureStatus]"), line)
        }

    @Test
    fun repliesToAnOpenVcsecRequestUseTheSameFormat() =
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy(), FakeVehicle.vcsecEmpty()))
            VehicleSession(
                h.dispatcher,
            ).vcsec.execute(byteArrayOf(1), AuthMethod.NONE, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            val replies = h.logger.observed().filter { it.startsWith("[reply:vcsec] ") }
            assertTrue(replies.first().contains("to=address(16B, open-request)"), replies.toString())
            assertTrue(replies.first().contains("commandStatus{op=OPERATIONSTATUS_WAIT}"), replies.toString())
        }

    @Test
    fun logsNeitherCiphertextNorFullPublicKeysNorVinAndOnlyAtDebug() =
        // Review Focus 5
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecEmpty()))
            session.vcsec.execute(
                byteArrayOf(1),
                AuthMethod.GCM,
                VcsecResponses.COMMAND_STATUS_ABSENT,
                flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE,
            )
            val lines = h.logger.entries.filter { it.tag == ObservationLog.TAG }
            assertTrue(lines.isNotEmpty())
            assertTrue(lines.all { it.level == LogLevel.DEBUG })
            assertTrue(lines.none { it.message.contains(h.fake.vin.value) })
            assertTrue(lines.none { it.message.contains(TestCrypto.clientPublicKey.toByteArray().toHex()) })
        }

    @Test
    fun undecodableFrameIsLoggedByLength() =
        runTest {
            val h = dispatcherHarness()
            h.transport.deliver(byteArrayOf(0xFF.toByte()))
            runCurrent()
            assertEquals(listOf("[drop:unparseable] 1B reinterpreted=undecodable"), h.logger.observed())
            assertFalse(h.logger.observed().any { it.contains("ff") && !it.contains("1B") })
        }

    @Test
    fun closedHandlerAndFullQueueDropsAreObserved() =
        runTest {
            val h = dispatcherHarness()
            h.fake.script(Domain.DOMAIN_INFOTAINMENT, emptyList(), emptyList())
            // (a) 닫힌 요청에 온 응답: lookup이 닫힌 핸들러를 지우면 no-handler, 그 전에 닫히면 handler-closed — 정확히 한 줄
            val closed = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            val closedRequest = h.fake.received.last()
            closed.close()
            h.transport.deliver(encode(replyTo(closedRequest, "late".encodeToByteArray())))
            runCurrent()
            val closedLine = h.logger.observed().single()
            assertTrue(closedLine.startsWith("[drop:no-handler] ") || closedLine.startsWith("[drop:handler-closed] "), closedLine)
            assertTrue(h.logger.contains("Dropping message without registered handler"))
            // (b) 열린 요청의 큐가 가득 차면 넘친 한 통이 queue-full로 남는다
            val open = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            val openRequest = h.fake.received.last()
            repeat(PendingRequest.BUFFER_SIZE + 1) { h.transport.deliver(encode(replyTo(openRequest, "stuffer".encodeToByteArray()))) }
            runCurrent()
            val full = h.logger.observed().filter { it.startsWith("[drop:queue-full] ") }
            assertEquals(1, full.size, full.toString())
            assertTrue(full.single().contains("to=address(16B, open-request)"), full.single())
            open.close()
        }

    @Test
    fun handlerClosedWhileProcessingIsObservedAsHandlerClosed() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val h = dispatcherHarness(privateKey = StuckEcdhKey(TestCrypto.clientKey(), gate))
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            runCurrent() // 수신 코루틴이 checkForSessionUpdate 안에서 gate에 멈춘다
            pending.close()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, h.logger.observed().count { it.startsWith("[drop:handler-closed] ") }, h.logger.observed().toString())
        }
}
