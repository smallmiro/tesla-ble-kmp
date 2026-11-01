package dev.mirolab.teslable.application.observe

import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistEntryInfo
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.PendingRequest
import dev.mirolab.teslable.application.dispatcher.StuckEcdhKey
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.dispatcher.encode
import dev.mirolab.teslable.application.dispatcher.manualHandshake
import dev.mirolab.teslable.application.dispatcher.replyTo
import dev.mirolab.teslable.application.dispatcher.testCommand
import dev.mirolab.teslable.application.vcsec.VcsecResponses
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.hexToBytes
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import dev.mirolab.teslable.model.VehicleStatus as ModelVehicleStatus

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class DispatcherObservationTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val replyPoints = listOf(vcsec to "[reply:vcsec] ", infotainment to "[reply:infotainment] ")
    private val latePayload = "late".encodeToByteArray()

    private fun RecordingLogger.observed(): List<String> = entries.filter { it.tag == ObservationLog.TAG }.map { it.message }

    private fun locked() = FromVCSECMessage(vehicleStatus = VehicleStatus(vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED))

    /** 응답을 암호화해 달라는 인증 명령. 차량은 대본대로 답한다. */
    private suspend fun DispatcherHarness.sendEncrypted(domain: Domain): PendingRequest =
        assertIs<VehicleResult.Success<PendingRequest>>(
            dispatcher.send(testCommand(domain, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), AuthMethod.GCM),
        ).value

    /** 차량이 답하지 않는 요청. 응답은 시험이 직접 넣는다(`fake.received.last()`가 그 요청이다). */
    private suspend fun DispatcherHarness.sendUnanswered(domain: Domain): PendingRequest {
        fake.script(domain, emptyList())
        return assertIs<VehicleResult.Success<PendingRequest>>(dispatcher.send(testCommand(domain), AuthMethod.NONE)).value
    }

    /** 태그가 맞지 않는 AES-GCM 봉투 응답. 복호화하지 않아도 되는 경로를 시험할 때 쓴다. */
    private fun gcmEnvelope(
        request: RoutableMessage,
        ciphertext: ByteArray,
    ): RoutableMessage =
        replyTo(request, ciphertext).copy(
            signature_data =
                SignatureData(
                    AES_GCM_Response_data =
                        AES_GCM_Response_Signature_Data(
                            nonce = ByteArray(12).toByteString(),
                            counter = 1,
                            tag = ByteArray(16).toByteString(),
                        ),
                ),
        )

    /** 세션을 캐시에서 복원한 하네스. 핸드셰이크 요청이 시도 번호를 쓰지 않으므로 첫 명령이 `attempt=1`이다. */
    private suspend fun TestScope.restoredHarness(domain: Domain): DispatcherHarness {
        val source = dispatcherHarness()
        source.manualHandshake(domain)
        val entries = source.dispatcher.exportSessions().map { CachedSession(it.domain, it.sessionInfo, Duration.ZERO) }
        source.dispatcher.stop()
        val h = dispatcherHarness(fake = source.fake)
        assertEquals(setOf(domain), h.dispatcher.loadSessions(entries))
        return h
    }

    /** 디스패처가 [RelinkableTransport]를 쓰는 하네스. 이후 [dropLink][RelinkableTransport.dropLink]가 지금 구독을 끝낼 수 있게 구독을 마치고 돌려준다. */
    private fun TestScope.relinkableHarness(): Pair<DispatcherHarness, RelinkableTransport> {
        val fake = FakeVehicle(timeSource = testTimeSource)
        val transport = fake.transport()
        val relinkable = RelinkableTransport(transport)
        val logger = RecordingLogger()
        val dispatcher =
            Dispatcher(
                relinkable,
                TestCrypto.clientKey(),
                TestCrypto.primitives,
                TestCrypto.random,
                backgroundScope,
                testTimeSource,
                logger,
            )
        dispatcher.start()
        runCurrent()
        return DispatcherHarness(fake, transport, dispatcher, logger) to relinkable
    }

    /** Review Focus 5: 연결이 끝나면 그 전에 닫힌 요청도, 그때 열려 있다가 뒤에 닫힌 요청도 다음 연결의 이력에 남지 않고 시도 번호는 되풀이되지 않는다. */
    private suspend fun TestScope.assertConnectionEndForgetsClosedRequests(
        h: DispatcherHarness,
        endConnection: suspend () -> Unit,
    ) {
        h.sendUnanswered(vcsec).close()
        val closedBefore = h.fake.received.last()
        val openAtTheEnd = h.sendUnanswered(vcsec)
        val closedAfter = h.fake.received.last()
        endConnection()
        runCurrent()
        assertFalse(h.dispatcher.isListening)
        openAtTheEnd.close() // 호출자는 끊긴 뒤에야 시간 초과로 닫는다
        h.dispatcher.start()
        h.sendUnanswered(vcsec).close()
        val closedOnTheNewConnection = h.fake.received.last()

        listOf(closedBefore, closedAfter, closedOnTheNewConnection).forEach { h.transport.deliver(encode(replyTo(it, latePayload))) }
        runCurrent()

        val lines = h.logger.observed()
        assertEquals(3, lines.size, lines.toString())
        lines.take(2).forEach {
            assertTrue(it.contains("to=address(16B, other)"), it)
            assertFalse(it.contains(" attempt="), it)
        }
        assertTrue(lines[2].contains("to=address(16B, closed)"), lines[2])
        assertTrue(lines[2].endsWith(" attempt=3"), lines[2])
    }

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
    fun infotainmentReplyContentNeverReachesLogsOrObservations() =
        // FR-112: 응답 값은 어떤 레벨의 로그에도 나가지 않는다. 네 경로를 지난다: getState 성공, 그 응답의 gcm-ok 관찰 줄, 복호화 뒤의 드롭(큐 초과), 평문 프레임의 드롭.
        // 복호화한 메시지는 ObservationLog.infotainment에 오지 않으므로(그게 맞다) 그 요약을 지나려면 평문 프레임이 필요하다. 복호화 전에 드롭되는 프레임이다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            val destination = "FR112-SENTINEL-DESTINATION"
            val response = Response(vehicleData = VehicleData(drive_state = DriveState(active_route_destination = destination)))
            val reply = FakeVehicle.infotainmentPayload(response)

            h.fake.script(Domain.DOMAIN_INFOTAINMENT, listOf(reply))
            val result = session.infotainment.getState(StateCategory.DRIVE)

            h.fake.script(Domain.DOMAIN_INFOTAINMENT, List(PendingRequest.BUFFER_SIZE + 1) { reply })
            val command = testCommand(flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE)
            val open = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(command, AuthMethod.GCM)).value
            runCurrent()
            open.close()

            h.transport.deliver(encode(replyTo(h.fake.received.last(), response.encode())))
            runCurrent()

            val logged = h.logger.entries.map { it.message }
            val hex = destination.encodeToByteArray().toHex()
            assertTrue(logged.none { it.contains(destination) || it.contains(hex) }, logged.toString())
            // 빈 시험이 아니라는 확인: 값은 복호화됐고, 복호화 뒤의 드롭은 암호문으로 남았고, 평문 프레임은 요약을 지났다
            val data = assertIs<VehicleResult.Success<VehicleData>>(result).value
            assertEquals(destination, data.drive_state?.active_route_destination)
            val observed = h.logger.observed()
            assertTrue(observed.any { it.startsWith("[drop:queue-full] ") && it.contains("payload=encrypted(") }, observed.toString())
            assertTrue(observed.any { it.startsWith("[drop:no-handler] ") && it.contains("payload=Response{") }, observed.toString())
            // 자기 응답 줄은 복호화에 성공해도 원래 봉투로 요약된다
            val verified = observed.filter { it.startsWith("[reply:infotainment] ") && it.endsWith(" auth=gcm-ok") }
            assertEquals(PendingRequest.BUFFER_SIZE + 2, verified.size, observed.toString()) // getState 하나 + 대본 응답 전부
            assertTrue(verified.all { it.contains("payload=encrypted(") }, observed.toString())
        }

    @Test
    fun decryptedVcsecReplyStaysOutOfObservations() =
        // FR-112: 복호화한 VCSEC 메시지를 요약하면 값이 그대로 나온다(lock=…). 응답 줄은 복호화 전 봉투로 만든다
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(locked())))

            val delivered = h.sendEncrypted(vcsec).use { it.receive() }

            // 빈 시험이 아니라는 확인: 응답은 복호화되어 값을 실어 왔다
            assertEquals(locked(), FromVCSECMessage.ADAPTER.decode(assertNotNull(delivered.protobuf_message_as_bytes)))
            val line = h.logger.observed().last()
            assertTrue(line.startsWith("[reply:vcsec] "), line)
            assertTrue(line.contains("payload=encrypted("), line)
            assertTrue(line.endsWith(" auth=gcm-ok"), line)
            assertTrue(h.logger.entries.none { it.message.contains("VEHICLELOCKSTATE_LOCKED") }, h.logger.observed().toString())
        }

    @Test
    fun ownInfotainmentReplyIsObservedWithGcmOk() =
        runTest {
            val h = restoredHarness(infotainment)

            h.sendEncrypted(infotainment).use { assertNotNull(it.receive()) }

            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[reply:infotainment] "), line)
            assertTrue(line.contains("to=address(16B, open-request)"), line)
            assertTrue(line.contains("payload=encrypted("), line)
            assertTrue(line.endsWith(" attempt=1 auth=gcm-ok"), line)
        }

    @Test
    fun plaintextReplyReportsAuthNone() =
        runTest {
            val expected = listOf(vcsec to "payload=none", infotainment to "payload=Response{fields=[actionStatus]}")
            for ((domain, payload) in expected) {
                val h = dispatcherHarness()

                assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(domain), AuthMethod.NONE))
                    .value
                    .use { assertNotNull(it.receive()) }

                val line = h.logger.observed().single()
                assertTrue(line.startsWith(replyPoints.toMap().getValue(domain)), line)
                assertTrue(line.endsWith(" $payload attempt=1 auth=none"), line)
            }
        }

    @Test
    fun sessionInfoReplyReportsAuthNone() =
        // session_info_tag의 HMAC은 따로 검증하고 auth=는 AES_GCM_Response_data만 말한다
        runTest {
            for ((domain, point) in replyPoints) {
                val h = dispatcherHarness()

                h.manualHandshake(domain)

                val line = h.logger.observed().single()
                assertTrue(line.startsWith(point), line)
                assertTrue(line.contains(" session_info="), line)
                assertTrue(line.endsWith(" attempt=1 auth=none"), line)
            }
        }

    @Test
    fun tamperedReplyReportsGcmFailed() =
        runTest {
            for ((domain, point) in replyPoints) {
                val h = dispatcherHarness()
                h.manualHandshake(domain)
                h.fake.script(domain, emptyList())

                h.sendEncrypted(domain).use { pending ->
                    val genuine =
                        h.fake.verifier(domain).encryptResponse(
                            replyTo(h.fake.received.last(), "x".encodeToByteArray()),
                            assertNotNull(pending.requestHash),
                            counter = 1u,
                        )
                    val ciphertext = assertNotNull(genuine.protobuf_message_as_bytes).toByteArray()
                    ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
                    h.transport.deliver(encode(genuine.copy(protobuf_message_as_bytes = ciphertext.toByteString())))
                    runCurrent()
                    assertNull(pending.tryReceive())
                }

                val lines = h.logger.observed().drop(1) // 핸드셰이크 줄 뒤
                val line = lines.single()
                assertTrue(line.startsWith(point), line)
                assertTrue(line.contains("payload=encrypted(1B)"), line)
                assertTrue(line.endsWith(" attempt=2 auth=gcm-failed"), line)
            }
        }

    @Test
    fun duplicateReplyReportsGcmReplay() =
        runTest {
            for ((domain, point) in replyPoints) {
                val h = dispatcherHarness()
                h.manualHandshake(domain)

                h.sendEncrypted(domain).use { pending ->
                    assertNotNull(pending.receive())
                    h.fake.replayLastResponse(domain)
                    runCurrent()
                    assertNull(pending.tryReceive())
                }

                val lines = h.logger.observed().drop(1) // 핸드셰이크 줄 뒤
                assertEquals(2, lines.size, lines.toString())
                assertTrue(lines.all { it.startsWith(point) && it.contains("payload=encrypted(") }, lines.toString())
                assertTrue(lines[0].endsWith(" attempt=2 auth=gcm-ok"), lines[0])
                assertTrue(lines[1].endsWith(" attempt=2 auth=gcm-replay"), lines[1])
            }
        }

    @Test
    fun encryptedReplyWithoutASessionReportsGcmNoSession() =
        // 개인키가 없으면 도메인의 세션 자체가 없고, 핸드셰이크 전이면 세션에 Signer가 없다. 둘 다 검사할 수 없다
        runTest {
            for (privateKey in listOf(null, TestCrypto.clientKey())) {
                for ((domain, point) in replyPoints) {
                    val h = dispatcherHarness(privateKey = privateKey)

                    h.sendUnanswered(domain).use { pending ->
                        h.transport.deliver(encode(gcmEnvelope(h.fake.received.last(), "x".encodeToByteArray())))
                        runCurrent()
                        assertNull(pending.tryReceive())
                    }

                    val line = h.logger.observed().single()
                    assertTrue(line.startsWith(point), line)
                    assertTrue(line.contains("payload=encrypted(1B)"), line)
                    assertTrue(line.endsWith(" attempt=1 auth=gcm-no-session"), line)
                }
            }
        }

    @Test
    fun everyReplyFrameGetsOneReplyLineEvenWhenTheQueueDropsIt() =
        runTest {
            val h = dispatcherHarness()
            val open = h.sendUnanswered(infotainment)

            val frame = encode(replyTo(h.fake.received.last(), "stuffer".encodeToByteArray()))
            repeat(PendingRequest.BUFFER_SIZE + 1) { h.transport.deliver(frame) }
            runCurrent()

            val lines = h.logger.observed()
            assertEquals(PendingRequest.BUFFER_SIZE + 1, lines.count { it.startsWith("[reply:infotainment] ") }, lines.toString())
            assertEquals(1, lines.count { it.startsWith("[drop:queue-full] ") }, lines.toString())
            open.close()
        }

    @Test
    fun replyLineIsNotBuiltWhileObservationIsOff() =
        runTest {
            val rawKey = TestCrypto.clientPublicKey.toByteArray().toByteString()
            val reply = FromVCSECMessage(whitelistEntryInfo = WhitelistEntryInfo(publicKey = PublicKey(PublicKeyRaw = rawKey)))

            suspend fun sha1CallsWith(logger: TeslaLogger): Int {
                val crypto = Sha1Counter(TestCrypto.primitives)
                val fake = FakeVehicle(timeSource = testTimeSource)
                val dispatcher =
                    Dispatcher(fake.transport(), TestCrypto.clientKey(), crypto, TestCrypto.random, backgroundScope, testTimeSource, logger)
                dispatcher.start()
                fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(reply)))
                assertIs<VehicleResult.Success<PendingRequest>>(dispatcher.send(testCommand(vcsec), AuthMethod.NONE))
                    .value
                    .use { assertNotNull(it.receive()) }
                return crypto.calls
            }

            assertEquals(1, sha1CallsWith(RecordingLogger())) // 줄을 만들면 공개키를 해시한다
            assertEquals(0, sha1CallsWith(TeslaLogger { level, _, message -> if (level != LogLevel.DEBUG) message() }))
        }

    @Test
    fun waitThenLateReplyShareTheAttempt() =
        // ADR-0015 미결정: WAIT 뒤에 같은 요청의 최종 응답이 오는가. WAIT는 요청을 닫고 새 요청(새 주소)으로 다시 보내므로 늦은 응답은 닫힌 키로 온다
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy()), listOf(FakeVehicle.vcsecEmpty()))
            VehicleSession(
                h.dispatcher,
            ).vcsec.execute(byteArrayOf(1), AuthMethod.NONE, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)

            h.transport.deliver(encode(gcmEnvelope(h.fake.received.first(), ByteArray(0))))
            runCurrent()

            val lines = h.logger.observed()
            assertEquals(3, lines.size, lines.toString())
            val (wait, retried, late) = lines
            assertTrue(wait.startsWith("[reply:vcsec] ") && wait.contains("commandStatus{op=OPERATIONSTATUS_WAIT}"), wait)
            assertTrue(wait.endsWith(" attempt=1 auth=none"), wait)
            assertTrue(retried.endsWith(" attempt=2 auth=none"), retried) // 재시도는 새 번호
            assertTrue(late.startsWith("[drop:no-handler] "), late)
            assertTrue(late.contains("to=address(16B, closed)"), late)
            assertTrue(late.endsWith(" payload=encrypted(0B) attempt=1"), late) // 대응만 말한다: auth=가 없다
            assertFalse(h.logger.contains("Error decrypting vehicle response")) // 닫힌 키로 온 응답은 복호화하지 않는다
        }

    @Test
    fun lateInfotainmentReplyMatchesOnTheRequestUuid() =
        // Infotainment 요청은 주소 하나를 공유하므로 주소만 보면 모두 self다. uuid까지 맞아야 closed다
        runTest {
            val h = dispatcherHarness()
            h.sendUnanswered(infotainment).close()
            val closed = h.fake.received.last()
            h.sendUnanswered(infotainment).close()

            repeat(2) { h.transport.deliver(encode(replyTo(closed, latePayload))) } // 늦은 응답이 또 와도 이력은 그대로다
            val otherUuid = replyTo(closed, latePayload).copy(request_uuid = ByteArray(16) { 9 }.toByteString())
            h.transport.deliver(encode(otherUuid))
            runCurrent()

            val lines = h.logger.observed()
            assertEquals(3, lines.size, lines.toString())
            lines.take(2).forEach {
                assertTrue(it.contains("to=address(16B, closed)"), it)
                assertTrue(it.endsWith(" attempt=1"), it)
            }
            assertTrue(lines[2].contains("to=address(16B, self)"), lines[2])
            assertFalse(lines[2].contains(" attempt="), lines[2])
        }

    @Test
    fun foreignAddressStaysOther() =
        // 닫힌 요청과 uuid가 같아도 주소가 다르면 이 연결의 응답이 아니다
        runTest {
            val h = dispatcherHarness()
            h.sendUnanswered(infotainment).close()
            val foreign = Destination(routing_address = ByteArray(16) { 9 }.toByteString())

            h.transport.deliver(encode(replyTo(h.fake.received.last(), latePayload).copy(to_destination = foreign)))
            runCurrent()

            val line = h.logger.observed().single()
            assertTrue(line.startsWith("[drop:no-handler] "), line)
            assertTrue(line.contains("to=address(16B, other)"), line)
            assertFalse(line.contains(" attempt="), line)
        }

    @Test
    fun closedHistoryIsBounded() =
        // Review Focus 4: 닫힌 요청 33개 중 첫 번째만 밀려난다. 밀려나면 주소의 주인으로 돌아간다(Infotainment 주소는 이 연결의 것이라 self)
        runTest {
            for ((domain, evicted) in listOf(vcsec to "other", infotainment to "self")) {
                val h = dispatcherHarness()
                val requests =
                    List(33) {
                        h.sendUnanswered(domain).close()
                        h.fake.received.last()
                    }

                listOf(requests[0], requests[1], requests[32]).forEach { h.transport.deliver(encode(replyTo(it, latePayload))) }
                runCurrent()

                val lines = h.logger.observed()
                assertEquals(3, lines.size, lines.toString())
                assertTrue(lines[0].contains("to=address(16B, $evicted)"), lines[0])
                assertFalse(lines[0].contains(" attempt="), lines[0])
                assertTrue(lines[1].contains("to=address(16B, closed)"), lines[1])
                assertTrue(lines[1].endsWith(" attempt=2"), lines[1])
                assertTrue(lines[2].contains("to=address(16B, closed)"), lines[2])
                assertTrue(lines[2].endsWith(" attempt=33"), lines[2])
            }
        }

    @Test
    fun requestsClosedTogetherEnterTheHistoryOldestFirst() =
        // 닫힌 요청은 다음 정리 때 함께 이력에 들어간다. 맵 순서로 넣으면 엉뚱한 항목이 밀려난다
        runTest {
            val h = dispatcherHarness()
            val open = List(33) { h.sendUnanswered(vcsec) to h.fake.received.last() }
            open.forEach { (pending, _) -> pending.close() }

            open.take(2).forEach { (_, request) -> h.transport.deliver(encode(replyTo(request, latePayload))) }
            runCurrent()

            val lines = h.logger.observed()
            assertEquals(2, lines.size, lines.toString())
            assertTrue(lines[0].contains("to=address(16B, other)"), lines[0])
            assertTrue(lines[1].contains("to=address(16B, closed)"), lines[1])
            assertTrue(lines[1].endsWith(" attempt=2"), lines[1])
        }

    @Test
    fun closedHistoryClearsOnDisconnect() =
        // BLE 끊김: incoming이 정상 완료된다
        runTest {
            val (h, link) = relinkableHarness()
            assertConnectionEndForgetsClosedRequests(h) { link.dropLink() }
        }

    @Test
    fun closedHistoryClearsOnStop() =
        // stop: 수신 코루틴이 취소된다
        runTest {
            val h = dispatcherHarness()
            assertConnectionEndForgetsClosedRequests(h) { h.dispatcher.stop() }
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

/** `sha1` 호출을 센다. 공개키가 실린 응답은 관찰 줄을 만들 때만 해시되므로 줄이 만들어졌는지 알 수 있다. */
private class Sha1Counter(
    private val delegate: CryptoPrimitives,
) : CryptoPrimitives by delegate {
    var calls = 0
        private set

    override fun sha1(data: ByteArray): ByteArray {
        calls++
        return delegate.sha1(data)
    }
}

/** 링크가 끊겼다 다시 이어지는 [Transport] — 시험 전용. [dropLink]는 지금 구독을 정상 완료시키고(BLE 끊김), 다음 구독은 같은 전송을 다시 읽는다. */
private class RelinkableTransport(
    private val delegate: Transport,
) : Transport by delegate {
    private var link = CompletableDeferred<Unit>()

    fun dropLink() {
        link.complete(Unit)
        link = CompletableDeferred()
    }

    override val incoming: Flow<ByteArray> =
        channelFlow {
            val current = link
            val forward = launch { delegate.incoming.collect { send(it) } }
            current.await()
            forward.cancel()
        }
}
