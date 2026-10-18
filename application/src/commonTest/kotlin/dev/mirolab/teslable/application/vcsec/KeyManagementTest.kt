package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.cache.SessionCacheSync
import dev.mirolab.teslable.application.dispatcher.HandshakeFlow
import dev.mirolab.teslable.application.dispatcher.SessionEvent
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.dispatcher.encode
import dev.mirolab.teslable.application.dispatcher.replyTo
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class KeyManagementTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val friend = TestCrypto.goKnownVerifierKey().publicKey

    private suspend fun ready(h: dev.mirolab.teslable.application.dispatcher.DispatcherHarness): VehicleSession =
        VehicleSession(h.dispatcher).also { assertIs<VehicleResult.Success<Unit>>(it.startSession(setOf(vcsec), timeout = 1.seconds)) }

    @Test
    fun addKeySucceedsWhenTheWhitelistStatusArrives() =
        // isWhitelistOperationComplete: 중간 응답(signedMessageStatus)은 건너뛰고 whitelistOperationStatus NONE에서 끝난다
        runTest {
            val h = dispatcherHarness()
            val session = ready(h)
            val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecWhitelistStatus(none)))
            assertEquals(VehicleResult.Success(Unit), session.keys.addKey(friend, KeyRole.DRIVER, KeyFormFactor.ANDROID_DEVICE))
            assertTrue(
                h.fake
                    .authenticatedPayloads(vcsec)
                    .single()
                    .toHex()
                    .startsWith("8201"),
            ) // WhitelistOperation(16)
        }

    @Test
    fun addKeyRejectedCarriesTheKeychainCode() =
        runTest {
            val h = dispatcherHarness()
            val session = ready(h)
            val code = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NO_PERMISSION_TO_ADD
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecWhitelistStatus(code)))
            assertEquals(
                VehicleResult.Failure(VehicleError.KeychainRejected(code)),
                session.keys.addKey(friend, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE),
            )
        }

    @Test
    fun unknownRoleIsInvalidArgumentAndNothingIsSent() =
        runTest {
            val h = dispatcherHarness()
            val session = ready(h)
            val sentBefore = h.transport.sent.size
            assertIs<VehicleResult.Failure>(session.keys.addKey(friend, KeyRole.UNKNOWN, KeyFormFactor.NFC_CARD)).also {
                assertIs<VehicleError.InvalidArgument>(it.error)
            }
            assertEquals(sentBefore, h.transport.sent.size)
        }

    @Test
    fun removeKeySendsGoPayloadAndNeedsASession() =
        runTest {
            val h = dispatcherHarness()
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), VehicleSession(h.dispatcher).keys.removeKey(friend))
            val session = ready(h)
            h.fake.script(
                vcsec,
                listOf(FakeVehicle.vcsecWhitelistStatus(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE)),
            )
            assertEquals(VehicleResult.Success(Unit), session.keys.removeKey(friend))
            assertEquals(
                "82014512430a41" + friend.toByteArray().toHex(),
                h.fake
                    .authenticatedPayloads(vcsec)
                    .single()
                    .toHex(),
            )
        }

    @Test
    fun sessionInfoWorksWithoutAPrivateKey() =
        // Go SessionInfo는 AuthMethodNone — 키 없이 연결해도 등록 여부를 물을 수 있다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val summary =
                assertIs<VehicleResult.Success<SessionInfoSummary>>(
                    VehicleSession(h.dispatcher).keys.sessionInfo(friend, vcsec),
                ).value
            assertEquals(TestCrypto.vehicleKey().publicKey, summary.vehiclePublicKey)
        }

    @Test
    fun notEnrolledKeyIsKeyNotPaired() =
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptHandshake(infotainment, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyNotPaired),
                VehicleSession(h.dispatcher).keys.sessionInfo(friend, infotainment),
            )
        }

    @Test
    fun replyWithoutSessionInfoIsBadResponse() =
        // Go SessionInfo: GetError 다음 session_info가 없으면 ErrBadResponse (status 필드는 보지 않는다)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val transport = fake.transport()
            transport.onSend = { bytes ->
                val request = RoutableMessage.ADAPTER.decode(bytes)
                transport.deliver(encode(replyTo(request, ByteArray(0)).copy(protobuf_message_as_bytes = null)))
            }
            val h = dispatcherHarness(fake = fake, transport = transport)
            assertEquals(
                VehicleResult.Failure(VehicleError.BadResponse("missing session_info")),
                VehicleSession(h.dispatcher).keys.sessionInfo(friend, infotainment),
            )
        }

    @Test
    fun lostReplyIsUncertainTimeout() =
        runTest {
            val h = dispatcherHarness()
            h.fake.sleep(setOf(infotainment))
            assertEquals(
                VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)),
                VehicleSession(h.dispatcher).keys.sessionInfo(friend, infotainment, timeout = 3.seconds),
            )
        }

    @Test
    fun ownKeyQueryEstablishesTheSessionLikeGo() =
        // checkForSessionUpdate는 이 연결의 키로 물은 응답의 세션정보를 반영한다(Go와 같음, 설계 구체화 3)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<SessionInfoSummary>>(
                VehicleSession(h.dispatcher).keys.sessionInfo(TestCrypto.clientPublicKey, vcsec),
            )
            assertTrue(checkNotNull(h.dispatcher.session(vcsec)).isReady)
        }

    @Test
    fun foreignKeyQueryLeavesOurCachedSessionAlone() =
        // Review Focus 2: 다른 키의 세션정보 태그는 우리 키로 검증되지 않는다 — 캐시 복원 세션을 "오래됨"으로 보면 안 된다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(vcsec))
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            sync.store(first.dispatcher)
            val h = dispatcherHarness(fake = fake, start = false)
            sync.load(h.dispatcher)
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            h.dispatcher.start()
            assertIs<VehicleResult.Success<SessionInfoSummary>>(VehicleSession(h.dispatcher).keys.sessionInfo(friend, vcsec))
            runCurrent()
            val session = checkNotNull(h.dispatcher.session(vcsec))
            assertTrue(session.isUnconfirmedCacheRestore)
            assertFalse(session.isReportedStale)
            assertTrue(events.isEmpty())
            assertFalse(h.logger.contains("Session info error"))
        }
}
