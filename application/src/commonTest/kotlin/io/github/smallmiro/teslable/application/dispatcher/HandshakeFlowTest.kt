package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
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
        // 재전송, 취소되면 그만. 컨트롤러 판정 R1: Go sleep 의미에선 잠든 동안의 요청을 세지 않으므로 received에서
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
        // dispatcher_test.go TestConnect + TestWaitForAllSessions
        // 컨트롤러 판정 R2: 이 테스트는 시간 초과를 확인하는 것이지 "빠른 실패"를 확인하는 게 아니므로
        // 이름을 고쳤다.
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
    fun startSessionWithoutKeyReturnsRequiresKey() =
        // dispatcher_test.go TestHandshakeWithoutKey
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val flow = HandshakeFlow(h.dispatcher)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(flow.startSession(infotainment)).error)
            assertEquals(VehicleError.RequiresKey, assertIs<VehicleResult.Failure>(flow.startSessions()).error)
        }
}
