package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class DispatcherTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    private fun DispatcherHarness.lastRequest(): RoutableMessage = fake.received.last()

    @Test
    fun sendWithoutSessionReturnsNoSessionButUnauthenticatedSendWorks() =
        // dispatcher_test.go TestSendWithoutSession
        runTest {
            val h = dispatcherHarness()
            val refused = assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), AuthMethod.GCM))
            assertEquals(VehicleError.NoSession, refused.error)
            assertTrue(h.logger.contains("No session available for DOMAIN_INFOTAINMENT"))
            val sent = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE))
            sent.value.use { pending ->
                val request = h.lastRequest()
                assertEquals(16, request.uuid.size) // FR-010: 16B 랜덤 uuid
                assertContentEquals(pending.uuid, request.uuid.toByteArray()) // Infotainment 키 = uuid
                assertEquals(16, assertNotNull(request.from_destination?.routing_address).size)
                assertNull(pending.requestHash) // 인증 없음 → request hash 없음
            }
        }

    @Test
    fun vcsecRequestsUseFreshRoutingAddressAndInfotainmentUsesTheFixedOne() =
        // FR-010: VCSEC는 요청마다 새 routing_address, Infotainment는 디스패처의 고정 주소 (dispatcher.go Send 400~407행)
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList(), emptyList())
            h.fake.script(infotainment, emptyList(), emptyList())
            val none = AuthMethod.NONE
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), none)).value.use {
                assertEquals(0, it.uuid.size) // VCSEC 키는 uuid를 쓰지 않는다
            }
            val v1 = h.lastRequest().from_destination?.routing_address
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), none)).value.close()
            val v2 = h.lastRequest().from_destination?.routing_address
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(infotainment), none)).value.close()
            val i1 = h.lastRequest().from_destination?.routing_address
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(infotainment), none)).value.close()
            val i2 = h.lastRequest().from_destination?.routing_address
            assertFalse(v1 == v2)
            assertEquals(i1, i2)
            assertFalse(v1 == i1)
        }

    @Test
    fun sendBeforeStartOrAfterStopReturnsNotConnected() =
        // dispatcher_test.go TestStopDispatcher
        runTest {
            val h = dispatcherHarness(start = false)
            val none = AuthMethod.NONE
            assertEquals(VehicleError.NotConnected, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), none)).error)
            h.dispatcher.start()
            assertTrue(h.dispatcher.isListening)
            h.dispatcher.start() // 멱등
            h.dispatcher.stop()
            assertFalse(h.dispatcher.isListening)
            assertEquals(VehicleError.NotConnected, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), none)).error)
        }

    @Test
    fun stopRacingWithStartDoesNotOrphanTheReplacementCollector() =
        // Review round 1, Important 1: stop() used to suspend in cancelAndJoin() and only clear
        // receiveJob afterwards, so a start() that raced in during that suspension got its fresh job
        // silently wiped out by stop()'s post-suspend `receiveJob = null` — an orphaned, unstoppable
        // collector (isListening reports false while the job is still actually running). Go serializes
        // Start/Stop under doneLock (dispatcher.go:333-341, 369-375, 380-382); here a lifecycle Mutex
        // captures-and-clears the job before suspending in cancelAndJoin(), so a start() racing into the
        // window left by the (already-released) lock launches a replacement that survives stop()'s
        // cleanup of the OLD job. Both racers are launched before a single advanceUntilIdle() so the
        // test dispatcher interleaves them deterministically (stop()'s cancel() + suspend in join(),
        // then start() observing isListening == false and relaunching). The first stop()'s join()
        // resolves lazily on the next real suspension point (the `it.receive()` below), which is
        // exactly where the old code's post-suspend `receiveJob = null` used to silently orphan the
        // replacement collector — it kept running (it still delivers the response) while isListening
        // wrongly reported false.
        runTest {
            val h = dispatcherHarness()
            assertTrue(h.dispatcher.isListening)
            launch { h.dispatcher.stop() } // will cancel+join the original collector
            launch { h.dispatcher.start() } // races in while the first stop() is suspended in cancelAndJoin()
            advanceUntilIdle()
            assertTrue(h.dispatcher.isListening) // the replacement collector must survive stop()'s cleanup

            // The replacement collector must actually be the one processing messages (not a stale flag).
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            pending.use { assertNotNull(it.receive()) }
            assertTrue(h.dispatcher.isListening) // must still be tracked, not orphaned by the racing stop()'s cleanup

            h.dispatcher.stop()
            assertFalse(h.dispatcher.isListening) // now stoppable and fully stopped, no leaked collector
        }

    @Test
    fun rejectsMessageWithoutDestinationDomain() =
        // dispatcher.go Send: "cannot send message without a destination domain"
        runTest {
            val h = dispatcherHarness()
            val none = AuthMethod.NONE
            val noDomain = RoutableMessage(protobuf_message_as_bytes = "x".encodeToByteArray().toByteString())
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(h.dispatcher.send(noDomain, none)).error)
            val broadcast = testCommand(Domain.DOMAIN_BROADCAST)
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(h.dispatcher.send(broadcast, none)).error)
        }

    @Test
    fun unreachableVehicleFailsSendWithoutRetry() =
        // dispatcher_test.go TestVehicleUnreachable (errTimeout은 재시도 불가)
        runTest {
            val h = dispatcherHarness()
            h.transport.ackRequests = false
            val failed = assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), AuthMethod.NONE))
            assertEquals(VehicleError.TransportError.Disconnected, failed.error)
            assertEquals(1, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount()) // 실패한 요청의 등록은 풀린다
            assertTrue(h.logger.contains("Terminal transmission error"))
        }

    @Test
    fun retriesTemporarySendErrorsAndStopsOnMayHaveSucceeded() =
        // dispatcher_test.go TestRetrySend: 일시 오류 3번은 1ms 간격 재시도, PossibleSuccess 오류는 그대로 반환
        runTest {
            val h = dispatcherHarness()
            repeat(3) { h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("busy")) }
            h.transport.enqueueSendError(VehicleError.Timeout(afterSend = true))
            val result = assertIs<VehicleResult.Uncertain>(h.dispatcher.send(testCommand(), AuthMethod.NONE))
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(4, h.transport.sent.size)
            assertEquals(3, h.logger.count("Retrying transmission after error"))
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun sendGivesUpWhenCallerTimesOutDuringRetries() =
        // dispatcher_test.go TestSendTimeout: RetryInterval/2 안에 끝나지 않으면 취소되고 등록이 풀린다 (Timeout 매핑은 Task 9)
        // R4: retryInterval을 4ms로 넓혀 RetryInterval/2(2ms)가 첫 전송과 첫 재시도 사이에 정확히 걸리도록 한다
        // (1ms 간격이면 테스트 스케줄러가 두 지점을 같은 가상 시각으로 뭉갠다).
        runTest {
            val h = dispatcherHarness(retryInterval = 4.milliseconds)
            repeat(50) { h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("busy")) }
            val result = withTimeoutOrNull(h.transport.retryInterval / 2) { h.dispatcher.send(testCommand(), AuthMethod.NONE) }
            assertNull(result)
            assertEquals(1, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun deliversMatchedResponseToItsPendingRequest() =
        // dispatcher_test.go TestStartSession의 매칭 부분(인증 없이): FakeVehicle 기본 응답이 (address, uuid, domain)으로 매칭된다
        runTest {
            val h = dispatcherHarness()
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            pending.use {
                val reply = it.receive()
                assertNull(ResponseClassifier.protocolError(reply))
                assertEquals(
                    MessageFault_E.MESSAGEFAULT_ERROR_NONE,
                    reply.signedMessageStatus?.signed_message_fault ?: MessageFault_E.MESSAGEFAULT_ERROR_NONE,
                )
            }
        }

    @Test
    fun dropsInvalidMessagesAndDeliversTheValidOne() =
        // dispatcher_test.go TestInvalidMessages
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            val request = h.lastRequest()

            fun reply(payload: String): RoutableMessage = replyTo(request, payload.encodeToByteArray())
            h.transport.deliver("I'm not a valid protobuf".encodeToByteArray())
            h.transport.deliver(encode(reply("missing uuid").copy(request_uuid = okio.ByteString.EMPTY)))
            val badAddress =
                assertNotNull(request.from_destination?.routing_address).toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            h.transport.deliver(
                encode(reply("bad destination address").copy(to_destination = Destination(routing_address = badAddress.toByteString()))),
            )
            h.transport.deliver(encode(reply("invalid domain").copy(from_destination = Destination(domain = vcsec))))
            h.transport.deliver(encode(reply("missing domain").copy(from_destination = null)))
            h.transport.deliver(encode(reply("missing destination address").copy(to_destination = null)))
            h.transport.deliver(encode(reply("to a domain").copy(to_destination = Destination(domain = infotainment))))
            h.transport.deliver(encode(reply("short uuid").copy(request_uuid = ByteArray(5).toByteString())))
            val unknownUuid = request.uuid.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            h.transport.deliver(encode(reply("unknown uuid").copy(request_uuid = unknownUuid.toByteString())))
            h.transport.deliver(encode(reply("ack")))
            pending.use {
                assertEquals("ack", assertNotNull(it.receive().protobuf_message_as_bytes).utf8())
                assertNull(it.tryReceive()) // 나머지는 전부 드롭
            }
            assertTrue(h.logger.contains("Dropping unparseable message"))
            assertTrue(h.logger.contains("Dropping message with missing source"))
            assertTrue(h.logger.contains("Dropping message with missing destination"))
            assertTrue(h.logger.contains("Dropping message to DOMAIN_INFOTAINMENT"))
            assertTrue(h.logger.contains("Dropping message with invalid request UUID length"))
            // missing uuid, bad address, wrong domain, unknown uuid
            assertEquals(4, h.logger.count("Dropping message without registered handler"))
        }

    @Test
    fun dropsMessageWithInvalidAddressLength() =
        // Review round 1, Minor fold M5: dispatcher.go process (~278-281), untested by
        // dropsInvalidMessagesAndDeliversTheValidOne (that test's "bad destination address" mutates a
        // still-16-byte address). A routing_address of any other length is its own drop reason.
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            val request = h.lastRequest()
            val shortAddress = Destination(routing_address = ByteArray(5).toByteString())
            h.transport.deliver(encode(replyTo(request, ByteArray(0)).copy(to_destination = shortAddress)))
            runCurrent()
            pending.use { assertNull(it.tryReceive()) }
            assertTrue(h.logger.contains("Dropping message with invalid address length"))
        }

    @Test
    fun dropsMessageWithUnrecognizedDestinationType() =
        // Review round 1, Minor fold M5: dispatcher.go process's default case for a Destination with
        // neither a domain nor a routing_address set.
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            val request = h.lastRequest()
            h.transport.deliver(encode(replyTo(request, ByteArray(0)).copy(to_destination = Destination())))
            runCurrent()
            pending.use { assertNull(it.tryReceive()) }
            assertTrue(h.logger.contains("Dropping message with unrecognized destination type"))
        }

    @Test
    fun dropsResponseFromDomainUnknownToWire() =
        // 설계 구체화 10 + 컨트롤러 판정 R2: 모르는 from 도메인(raw varint 4)은 Wire에서 domain == null이지만
        // from_destination 자체는 있으므로 "누락된 소스"가 아니다. Go GetDomain()이 0(DOMAIN_BROADCAST)을 돌려주는 것처럼
        // domain을 DOMAIN_BROADCAST로 취급하고, 그 도메인으로 등록된 핸들러가 없어 "핸들러 없음"으로 드롭한다.
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            val unknownDomain = Destination.ADAPTER.decode(byteArrayOf(0x08, 0x04)) // 태그 1(domain) varint 4
            assertNull(unknownDomain.domain)
            h.transport.deliver(encode(replyTo(h.lastRequest(), "x".encodeToByteArray()).copy(from_destination = unknownDomain)))
            runCurrent()
            pending.use { assertNull(it.tryReceive()) }
            assertTrue(h.logger.contains("Dropping message without registered handler"))
        }

    @Test
    fun doesNotBlockOtherHandlersWhenOneQueueIsFull() =
        // dispatcher_test.go TestDoNotBlockOnResponder
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList(), emptyList())
            val none = AuthMethod.NONE
            val first = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), none)).value
            val firstRequest = h.lastRequest()
            val second = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), none)).value
            val secondRequest = h.lastRequest()
            repeat(2 * PendingRequest.BUFFER_SIZE) {
                h.transport.deliver(encode(replyTo(firstRequest, "mailbox stuffer".encodeToByteArray())))
            }
            h.transport.deliver(encode(replyTo(secondRequest, "I shouldn't be blocked".encodeToByteArray())))
            second.use { assertEquals("I shouldn't be blocked", assertNotNull(it.receive().protobuf_message_as_bytes).utf8()) }
            first.use {
                repeat(PendingRequest.BUFFER_SIZE) { _ -> assertNotNull(it.tryReceive()) }
                assertNull(it.tryReceive())
            }
            assertEquals(PendingRequest.BUFFER_SIZE, h.logger.count("response handler queue is full"))
        }

    @Test
    fun requestSessionInfoWithoutKeyReturnsRequiresKey() =
        // dispatcher_test.go TestRequestSessionWithoutKey
        runTest {
            val h = dispatcherHarness(privateKey = null)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(h.dispatcher.requestSessionInfo(infotainment)).error)
            assertNull(h.dispatcher.session(infotainment))
        }

    @Test
    fun sessionInfoReplyMakesSessionReadyAndIsStillDeliveredToHandler() =
        // dispatcher.go process 주석: 세션정보를 반영한 뒤에도 응답은 핸들러로 전달된다
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(infotainment)
            assertTrue(h.logger.contains("Updated session info for DOMAIN_INFOTAINMENT"))
            val request = h.lastRequest()
            // SessionInfoRequest{public_key}
            assertEquals(TestCryptoPublicKeyHex, assertNotNull(request.session_info_request?.public_key).hex())
        }

    @Test
    fun discardsSessionInfoWithBadTag() =
        // dispatcher_test.go TestUnsolicitedSessionInfo / TestCorruptedSessionInfo
        runTest {
            val h = dispatcherHarness()
            h.fake.corruptNextSessionInfoTag(vcsec)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            pending.use { assertNotNull(it.receive().session_info, "sanity: reply carries session info") }
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(h.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
            val refused = assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(vcsec), AuthMethod.GCM))
            assertEquals(VehicleError.NoSession, refused.error)
        }

    @Test
    fun discardsUnauthenticatedSessionInfo() =
        // dispatcher_test.go TestDiscardUnauthenticatedSessionInfo: session_info는 있으나 session_info_tag가 없다
        runTest {
            val h = dispatcherHarness()
            h.fake.dropNextReplies(1)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val request = h.lastRequest()
            val info = h.fake.verifier(vcsec).signedSessionInfo(request.uuid.toByteArray())
            val untagged = replyTo(request, ByteArray(0)).copy(protobuf_message_as_bytes = null, session_info = info.encoded.toByteString())
            h.transport.deliver(encode(untagged))
            pending.use { assertNotNull(it.receive().session_info) }
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(h.logger.contains("Discarding unauthenticated session info"))
        }

    @Test
    fun discardsSessionInfoWithPresentButEmptyTagAsUnauthenticated() =
        // Review round 1, Minor fold M1: Wire's proto3 `tag` defaults to ByteString.EMPTY (not null), so
        // an explicitly present `session_info_tag {}` with no tag bytes must still be treated as "no tag"
        // like Go's GetTag() returning nil (dispatcher.go:203-206), not fall through to HMAC verification.
        runTest {
            val h = dispatcherHarness()
            h.fake.dropNextReplies(1)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val request = h.lastRequest()
            val info = h.fake.verifier(vcsec).signedSessionInfo(request.uuid.toByteArray())
            val emptyTagged =
                replyTo(request, ByteArray(0)).copy(
                    protobuf_message_as_bytes = null,
                    session_info = info.encoded.toByteString(),
                    signature_data = SignatureData(session_info_tag = HMAC_Signature_Data()),
                )
            h.transport.deliver(encode(emptyTagged))
            pending.use { assertNotNull(it.receive().session_info) }
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(h.logger.contains("Discarding unauthenticated session info"))
        }

    @Test
    fun discardsSessionInfoReceivedMoreThanMaxLatencyAfterRequest() =
        // FR-014, dispatcher.go checkForSessionUpdate: handler.expired(maxLatency) → 폐기 (BLE 4초)
        runTest {
            val h = dispatcherHarness()
            h.dispatcher.setMaxLatency(4.seconds)
            h.dispatcher.setMaxLatency((-1).seconds) // Go SetMaxLatency: 0 이하는 무시
            assertEquals(4.seconds, h.dispatcher.maxLatency)
            h.fake.dropNextReplies(1)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val request = h.lastRequest()
            val late = h.fake.verifier(vcsec).setSessionInfo(request.uuid.toByteArray(), replyTo(request, ByteArray(0)))
            advanceTimeBy(4_001)
            h.transport.deliver(encode(late))
            pending.use { assertNotNull(it.receive().session_info) } // 메시지 자체는 전달된다
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(h.logger.contains("Discarding session info because it was received more than 4s after request"))
            // 대조군: 4초 안이면 받아들인다
            h.fake.dropNextReplies(1)
            val second = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val secondRequest = h.lastRequest()
            advanceTimeBy(3_000)
            h.transport.deliver(
                encode(h.fake.verifier(vcsec).setSessionInfo(secondRequest.uuid.toByteArray(), replyTo(secondRequest, ByteArray(0)))),
            )
            second.use { it.receive() }
            assertTrue(assertNotNull(h.dispatcher.session(vcsec)).isReady)
        }

    @Test
    fun discardsSessionInfoWhoseChallengeMatchesNoOutstandingRequest() =
        // FR-014 "오래된 uuid"(Go: challenge는 핸들러 키로 묶인다). (a) Infotainment: request_uuid가 미해결 요청과 다르면 핸들러가 없어 드롭.
        // (b) VCSEC: uuid는 키에 없지만 닫힌(오래된) 요청의 주소로 온 세션정보는 핸들러가 없어 드롭. (c) VCSEC: 주소는 살아 있는 요청인데
        // 태그가 다른 challenge로 계산된 세션정보는 HMAC 불일치로 폐기.
        runTest {
            val h = dispatcherHarness()
            h.fake.dropNextReplies(3)
            val infoPending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(infotainment)).value
            val infoRequest = h.lastRequest()
            val oldVcsecPending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val oldVcsecRequest = h.lastRequest()
            oldVcsecPending.close() // 옛 요청은 끝났다(Go: recv.Close → closeHandler)
            val vcsecPending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            val vcsecRequest = h.lastRequest()
            val staleUuid = ByteArray(16) { 0x42 }
            val staleInfo =
                h.fake
                    .verifier(infotainment)
                    .setSessionInfo(staleUuid, replyTo(infoRequest, ByteArray(0)))
                    .copy(request_uuid = staleUuid.toByteString())
            val lateVcsec =
                h.fake.verifier(vcsec).setSessionInfo(oldVcsecRequest.uuid.toByteArray(), replyTo(oldVcsecRequest, ByteArray(0)))
            val forgedChallenge = h.fake.verifier(vcsec).setSessionInfo(staleUuid, replyTo(vcsecRequest, ByteArray(0)))
            h.transport.deliver(encode(staleInfo))
            h.transport.deliver(encode(lateVcsec))
            h.transport.deliver(encode(forgedChallenge))
            runCurrent()
            infoPending.use { assertNull(it.tryReceive()) }
            vcsecPending.use { assertNotNull(it.tryReceive()) } // (c)는 메시지 자체는 전달된다(Go와 동일)
            assertFalse(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertEquals(2, h.logger.count("Dropping message without registered handler"))
            assertTrue(h.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
        }

    @Test
    fun appliesReplayedSessionInfoWithSameClockTimeLikeGo() =
        // FR-014 세 번째 규칙의 경계: signer.go "s.setTime <= info.ClockTime" — 같은 clock_time은 다시 반영된다(Go 동작).
        // 컨트롤러 판정 R3: 반영(적용)과 무시를 구별하도록, 클라이언트 시계만 2초 흘리고 차량 시계는 같은 만큼 되돌려
        // 두 번째 세션정보가 첫 번째와 같은 clock_time을 지니게 한다. 반영되면 timestamp()가 그대로(before)이고,
        // 무시됐다면 흘러간 2초만큼 커진다(before + 2s) — signer.go:103-110은 setTime <= ClockTime이면 timeZero를 다시 계산한다.
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            val before = assertNotNull(h.dispatcher.session(vcsec)).timestamp()
            advanceTimeBy(2_000)
            h.fake.shiftClock(vcsec, (-2).seconds) // 차량 시계를 2초 되돌려 다음 세션정보의 clock_time을 before와 같게 만든다
            h.manualHandshake(vcsec)
            assertEquals(2, h.logger.count("Updated session info for DOMAIN_VEHICLE_SECURITY"))
            assertEquals(before, assertNotNull(h.dispatcher.session(vcsec)).timestamp())
        }

    @Test
    fun decryptsResponseWhoseFlagsDifferFromRequest() =
        // Review Focus 5: 요청 flags = 2, FakeVehicle 응답 flags = 0 → 응답 AAD는 응답의 flags(0)로 계산돼야 복호화된다
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            val pending =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(vcsec, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), AuthMethod.GCM),
                ).value
            pending.use {
                assertContentEquals(RequestHash.of(h.lastRequest()), it.requestHash)
                val reply = it.receive()
                assertEquals(0, reply.flags)
                assertNull(reply.signature_data) // 복호화 뒤 signature_data 제거(Go Decrypt: SubSigData = nil)
                assertEquals(0, assertNotNull(reply.protobuf_message_as_bytes).size) // vcsecEmpty의 평문
            }
        }

    @Test
    fun dropsReplayedEncryptedResponse() =
        // 08-errors.md §6, dispatcher.go process: ErrReplayedResponse → "Dropping duplicate vehicle response"
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            val pending =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(vcsec, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), AuthMethod.GCM),
                ).value
            pending.use {
                assertNotNull(it.receive())
                h.fake.replayLastResponse(vcsec)
                runCurrent()
                assertNull(it.tryReceive())
            }
            assertTrue(h.logger.contains("Dropping duplicate vehicle response"))
        }

    @Test
    fun dropsResponseThatFailsDecryption() =
        // dispatcher.go process: decrypt 오류 → "Error decrypting vehicle response"
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(vcsec)
            h.fake.script(vcsec, emptyList())
            val pending =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(vcsec, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), AuthMethod.GCM),
                ).value
            val request = h.lastRequest()
            val wrongHash = assertNotNull(RequestHash.of(request)).also { it[3] = (it[3].toInt() xor 1).toByte() }
            val forged = h.fake.verifier(vcsec).encryptResponse(replyTo(request, "x".encodeToByteArray()), wrongHash, counter = 1u)
            h.transport.deliver(encode(forged))
            runCurrent()
            pending.use { assertNull(it.tryReceive()) }
            assertTrue(h.logger.contains("Error decrypting vehicle response"))
        }

    @Test
    fun matchesVcsecResponseByAddressRegardlessOfRequestUuid() =
        // Review Focus 1: VCSEC 키는 uuid를 쓰지 않으므로 request_uuid가 있든(가짜처럼 회신) 0이든 주소가 맞으면 전달된다
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), AuthMethod.NONE)).value
            val request = h.lastRequest()
            h.transport.deliver(encode(replyTo(request, "echoed".encodeToByteArray())))
            h.transport.deliver(encode(replyTo(request, "zeroed".encodeToByteArray()).copy(request_uuid = ByteArray(16).toByteString())))
            h.transport.deliver(encode(replyTo(request, "absent".encodeToByteArray()).copy(request_uuid = okio.ByteString.EMPTY)))
            val wrongAddress = Destination(routing_address = ByteArray(16) { 3 }.toByteString())
            h.transport.deliver(encode(replyTo(request, "wrong address".encodeToByteArray()).copy(to_destination = wrongAddress)))
            pending.use {
                assertEquals(
                    listOf("echoed", "zeroed", "absent"),
                    List(3) { _ -> assertNotNull(it.receive().protobuf_message_as_bytes).utf8() },
                )
                assertNull(it.tryReceive())
            }
        }

    @Test
    fun sendReturnsNotConnectedAfterIncomingCompletes() =
        // Review Focus 2: 전송이 끝나면(BLE 끊김) 수신 루프가 조용히 종료되고 이후 send는 NotConnected.
        // Review round 1, Important 2: 전송이 끝나기 전에 보낸 요청은 Go도 리시버 채널을 닫지 않으므로(그런 훅이
        // 없다) 응답을 받지 못한 채 호출자가 스스로 시간 초과할 때까지 계속 기다린다; 그 요청을 닫으면 등록은
        // 정상적으로 풀린다.
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList()) // 응답 없음: 전송이 끊길 때까지 아무것도 오지 않는다
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            h.transport.close()
            runCurrent()
            assertFalse(h.dispatcher.isListening)
            assertNull(withTimeoutOrNull(1.seconds) { pending.receive() }) // 채널이 닫히지 않아 시간 초과로 끝난다
            pending.close()
            assertEquals(0, h.dispatcher.pendingCount()) // 닫으면 등록이 풀린다
            assertEquals(
                VehicleError.NotConnected,
                assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).error,
            )
        }

    @Test
    fun discardsSessionInfoWhenClientHasNoPrivateKey() =
        // dispatcher.go checkForSessionUpdate: d.privateKey == nil → "Discarding session info because client does not have a private key"
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(vcsec, emptyList())
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), AuthMethod.NONE)).value
            val reply =
                replyTo(h.lastRequest(), ByteArray(0)).copy(
                    protobuf_message_as_bytes = null,
                    session_info = SessionInfo(counter = 1).encode().toByteString(),
                    signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = ByteArray(32).toByteString())),
                )
            h.transport.deliver(encode(reply))
            pending.use { assertNotNull(it.receive().session_info) }
            assertTrue(h.logger.contains("Discarding session info because client does not have a private key"))
        }

    private companion object {
        /** protocol.md client 공개키 hex (SessionInfoRequest.public_key 확인용). */
        val TestCryptoPublicKeyHex: String =
            TestCrypto.clientPublicKey
                .toByteArray()
                .toByteString()
                .hex()
    }
}
