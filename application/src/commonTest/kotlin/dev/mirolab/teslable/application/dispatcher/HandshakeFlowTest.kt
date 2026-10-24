package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.Session_Info_Status
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.protocol.ResponseClassifier
import dev.mirolab.teslable.testing.LockableEcdhKey
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeFlowTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val quiescentDelay = 250.milliseconds // dispatcher_test.go quiescentDelay

    @Test
    fun startSessionCompletesHandshakeAndAllowsAuthenticatedSend() =
        // dispatcher_test.go getTestSetup + TestStartSession
        runTest {
            val h = dispatcherHarness()
            val flow = HandshakeFlow(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(withTimeoutOrNull(quiescentDelay) { flow.startSession(infotainment) })
            assertEquals(1, h.fake.sessionInfoRequests)
            assertTrue(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).value
            pending.use { assertNull(ResponseClassifier.protocolError(it.receive())) }
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun startSessionReturnsImmediatelyWhenSessionAlreadyExists() =
        // dispatcher.go StartSession: s.ctx != nil → "Session for %s loaded from cache" → 즉시 반환
        runTest {
            val h = dispatcherHarness()
            val flow = HandshakeFlow(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(flow.startSession(vcsec))
            assertIs<VehicleResult.Success<Unit>>(flow.startSession(vcsec))
            assertEquals(1, h.fake.sessionInfoRequests)
        }

    @Test
    fun commandWithoutReplyTimesOut() =
        // dispatcher_test.go TestTimeout
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.fake.dropNextReplies(1)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).value
            pending.use { assertNull(withTimeoutOrNull(quiescentDelay) { it.receive() }) }
        }

    @Test
    fun retransmitsSessionInfoRequestEveryRetryIntervalWhileVehicleSleeps() =
        // dispatcher_test.go TestRetryNonresponsive + TestVehicleDropsReply: 응답이 없으면 RetryInterval마다
        // 재전송, 취소되면 그만. Go sleep 의미에선 잠든 동안의 요청을 세지 않으므로 received에서
        // 직접 센다(fake.sessionInfoRequests 대신).
        runTest {
            val h = dispatcherHarness()
            h.fake.sleep()
            val start = testTimeSource.markNow()
            assertNull(withTimeoutOrNull(5.milliseconds) { HandshakeFlow(h.dispatcher).startSession(infotainment) })
            val sentWhileAsleep = h.fake.received.count { it.session_info_request != null }
            assertTrue(sentWhileAsleep >= 5, "expected >= 5 requests, got $sentWhileAsleep")
            assertEquals(5.milliseconds, start.elapsedNow())
            assertFalse(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            runCurrent()
            assertEquals(0, h.dispatcher.pendingCount()) // 취소돼도 등록은 풀린다
        }

    @Test
    fun malformedHandshakeReplyIsRetransmittedAfterRetryInterval() =
        // dispatcher.go tryStartSession 두 번째 select: 응답은 왔지만 ready가 안 되면 RetryInterval 뒤 재시도
        runTest {
            val h = dispatcherHarness()
            h.fake.corruptNextSessionInfoTag(vcsec)
            assertIs<VehicleResult.Success<Unit>>(withTimeoutOrNull(quiescentDelay) { HandshakeFlow(h.dispatcher).startSession(vcsec) })
            assertEquals(2, h.fake.sessionInfoRequests)
            assertTrue(h.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
        }

    @Test
    fun startSessionFailsWithKeyNotPairedAfterBogusRepliesThenUnknownKeyId() =
        // dispatcher_test.go TestNoValidHandshakeResponse: 불량 태그 4번 뒤 UNKNOWN_KEY_ID → ErrKeyNotPaired
        runTest {
            val h = dispatcherHarness()
            h.fake.corruptNextSessionInfoTag(infotainment, count = 4)
            val none = MessageFault_E.MESSAGEFAULT_ERROR_NONE
            h.fake.scriptHandshake(infotainment, none, none, none, none, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            val flow = HandshakeFlow(h.dispatcher)
            val result = assertIs<VehicleResult.Failure>(withTimeoutOrNull(quiescentDelay) { flow.startSession(infotainment) })
            assertEquals(VehicleError.KeyNotPaired, result.error)
            assertEquals(5, h.fake.sessionInfoRequests)
        }

    @Test
    fun busyHandshakeReplyIsReturnedForTheCallerToRetry() =
        // dispatcher.go tryStartSession: GetError(reply) != nil → 반환 (Vehicle.StartSession이 ShouldRetry면 재시도, Task 9)
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_BUSY)
            val result = assertIs<VehicleResult.Failure>(HandshakeFlow(h.dispatcher).startSession(vcsec))
            assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_BUSY), result.error)
            assertTrue(result.error.temporary)
        }

    @Test
    fun startSessionsTimesOutWhileAsleepThenHandshakesBothDomains() =
        // dispatcher_test.go TestConnect + TestWaitForAllSessions: 시간 초과를 확인하는 것이지 "빠른 실패"를 확인하는 게 아니다.
        runTest {
            val h = dispatcherHarness()
            val flow = HandshakeFlow(h.dispatcher)
            h.fake.sleep()
            assertNull(withTimeoutOrNull(10.milliseconds) { flow.startSessions() })
            assertEquals(VehicleError.NoSession, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).error)
            h.fake.wake()
            assertIs<VehicleResult.Success<Unit>>(withTimeoutOrNull(quiescentDelay) { flow.startSessions() })
            assertTrue(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.GCM)).value.close()
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun startSessionsReturnsFirstFailureAndCancelsTheRest() =
        // dispatcher.go StartSessions: 첫 non-Canceled 오류를 돌려주고 aggregateContext를 취소한다
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            h.fake.sleep(setOf(infotainment))
            val start = testTimeSource.markNow()
            val result = assertIs<VehicleResult.Failure>(HandshakeFlow(h.dispatcher).startSessions())
            assertEquals(VehicleError.KeyNotPaired, result.error)
            assertTrue(start.elapsedNow() < h.dispatcher.retryInterval * 2, "must not wait for the sleeping domain")
            runCurrent()
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun startSessionFailsWithLockedRightAfterTheReplyWhenTheKeyIsLocked() =
        // FR-037(#96): 잠긴 키로는 다시 보내도 소용없다 — 재전송도 시간 초과도 없이 첫 응답 직후 Locked. Go tryStartSession은
        // 키 Exchange 오류를 로그만 남기고 RetryInterval마다 다시 보낸다(의도한 차이, ADR-0014).
        runTest {
            for (domain in listOf(vcsec, infotainment)) {
                val h = dispatcherHarness(privateKey = LockableEcdhKey())
                val start = testTimeSource.markNow()
                val result =
                    assertIs<VehicleResult.Failure>(withTimeoutOrNull(quiescentDelay) { HandshakeFlow(h.dispatcher).startSession(domain) })
                assertEquals(VehicleError.KeyStoreError.Locked, result.error, "$domain")
                assertTrue(start.elapsedNow() < h.dispatcher.retryInterval, "$domain must not wait for a retransmission")
                assertEquals(1, h.fake.sessionInfoRequests, "$domain")
                assertFalse(assertNotNull(h.dispatcher.session(domain)).isReady)
                runCurrent()
                assertEquals(0, h.dispatcher.pendingCount())
            }
        }

    @Test
    fun startSessionsFailsWithLockedThenSucceedsOnceTheKeyIsUnlocked() =
        // 두 도메인을 함께 시작해도 첫 Locked를 돌려주고 나머지를 취소한다. 잠금이 풀리면 다음 시도는 새 요청이라 이전 표시가 남지 않는다.
        runTest {
            val key = LockableEcdhKey()
            val h = dispatcherHarness(privateKey = key)
            val flow = HandshakeFlow(h.dispatcher)
            val start = testTimeSource.markNow()
            val locked = assertIs<VehicleResult.Failure>(withTimeoutOrNull(quiescentDelay) { flow.startSessions() })
            assertEquals(VehicleError.KeyStoreError.Locked, locked.error)
            assertTrue(start.elapsedNow() < h.dispatcher.retryInterval, "must not wait for a retransmission")
            runCurrent()
            assertEquals(0, h.dispatcher.pendingCount())
            key.locked = false
            assertIs<VehicleResult.Success<Unit>>(withTimeoutOrNull(quiescentDelay) { flow.startSessions() })
            assertTrue(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertTrue(assertNotNull(h.dispatcher.session(infotainment)).isReady)
        }

    @Test
    fun keyNotPairedInTheReplyWinsOverALockedKey() =
        // Go GetError 순서를 지킨다: 응답에 실린 차량의 오류(session_info.status KEY_NOT_ON_WHITELIST)가 잠금보다 먼저다 — 잠금을 풀어도
        // 소용없는 오류를 Locked로 가리지 않는다. 이 응답은 세션정보와 태그를 실어 수신 루프가 ECDH까지 가게 만든다.
        runTest {
            val h = dispatcherHarness(privateKey = LockableEcdhKey())
            h.fake.sleep() // 차량은 답하지 않는다 — 응답은 아래에서 직접 만든다
            val result = async { HandshakeFlow(h.dispatcher).startSession(vcsec) }
            runCurrent()
            val request = h.fake.received.last { it.session_info_request != null }
            val info =
                SessionInfo(
                    status = Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST,
                    publicKey = TestCrypto.vehiclePublicKey.toByteArray().toByteString(),
                )
            val reply =
                RoutableMessage(
                    to_destination = request.from_destination,
                    from_destination = request.to_destination,
                    request_uuid = request.uuid,
                    session_info = SessionInfo.ADAPTER.encode(info).toByteString(),
                    signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = ByteArray(32).toByteString())),
                )
            assertTrue(h.transport.deliver(encode(reply)))
            assertEquals(VehicleError.KeyNotPaired, assertIs<VehicleResult.Failure>(result.await()).error)
            assertTrue(h.logger.contains("private key is locked")) // ECDH까지 갔다
        }

    @Test
    fun startSessionWithoutKeyReturnsRequiresKey() =
        // dispatcher_test.go TestHandshakeWithoutKey
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val flow = HandshakeFlow(h.dispatcher)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(flow.startSession(infotainment)).error)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(flow.startSessions()).error)
        }
}
