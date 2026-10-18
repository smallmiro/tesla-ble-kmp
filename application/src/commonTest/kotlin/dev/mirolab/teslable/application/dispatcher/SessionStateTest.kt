package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.protocol.SignerResult
import dev.mirolab.teslable.testing.FixedRandom
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.testing.TestVerifier
import dev.mirolab.teslable.testing.fixtures.ProtocolVectors
import dev.mirolab.teslable.util.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class SessionStateTest {
    private val crypto = TestCrypto.primitives
    private val vin = Vin(ProtocolVectors.VIN)
    private val challenge = ByteArray(16) { it.toByte() }
    private val domain = Domain.DOMAIN_VEHICLE_SECURITY

    private fun TestScope.state(): SessionState = SessionState(vin, TestCrypto.clientKey(), crypto, TestCrypto.random, testTimeSource)

    private suspend fun TestScope.verifier(): TestVerifier =
        TestVerifier.create(
            TestCrypto.vehicleKey(),
            vin.toByteArray(),
            domain,
            TestCrypto.clientPublicKey,
            crypto,
            FixedRandom(ProtocolVectors.EPOCH.hexToBytes(), fallback = TestCrypto.random),
            testTimeSource,
        )

    private suspend fun hello(
        state: SessionState,
        verifier: TestVerifier,
        challenge: ByteArray = this.challenge,
    ): SignerResult<Unit> {
        val signed = verifier.signedSessionInfo(challenge)
        return state.processHello(challenge, signed.encoded, signed.tag)
    }

    private fun command(): RoutableMessage =
        RoutableMessage(
            to_destination = Destination(domain = domain),
            protobuf_message_as_bytes = "hello".encodeToByteArray().toByteString(),
        )

    @Test
    fun firstHelloCreatesSignerAndSignalsReady() =
        // session.go processHello: s.ctx == nil → NewAuthenticatedSigner; ready = true; close(readySignal)
        runTest {
            val state = state()
            assertFalse(state.isReady)
            assertNull(state.export())
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier()))
            assertTrue(state.isReady)
            state.awaitReady()
            assertEquals(0u, state.timestamp())
            assertEquals(0u, state.counter())
        }

    @Test
    fun firstHelloWithBadTagLeavesSessionNotReady() =
        runTest {
            val state = state()
            val signed = verifier().signedSessionInfo(challenge)
            val badTag = signed.tag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val result = assertIs<SignerResult.Fault>(state.processHello(challenge, signed.encoded, badTag))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, result.fault)
            assertFalse(state.isReady)
            assertNull(state.export())
            assertNull(state.decrypt(RoutableMessage(), ByteArray(0)))
        }

    @Test
    fun laterHelloUpdatesExistingSigner() =
        // session.go processHello: s.ctx != nil → UpdateSignedSessionInfo
        // signer.go UpdateSessionInfo (signer.go:103-110) resets timeZero when setTime <= info.ClockTime, so the
        // vehicle clock jumping forward by 50s on top of the 100s already advanced must be reflected: 100 + 50 = 150.
        runTest {
            val state = state()
            val verifier = verifier()
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier))
            advanceTimeBy(100_000)
            verifier.shiftTimeZero((-50).seconds)
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier, ByteArray(16) { 9 }))
            assertEquals(150u, state.timestamp())
            assertTrue(state.isReady)
        }

    @Test
    fun authorizeWaitsForReadyThenEncryptsWithNextCounter() =
        // session.go authorize: <-readySignal 뒤 락 안에서 Encrypt
        runTest {
            val state = state()
            val verifier = verifier()
            val authorized = async { state.authorize(command(), 5.seconds) }
            runCurrent()
            assertFalse(authorized.isCompleted)
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier))
            val encrypted = assertIs<SignerResult.Ok<RoutableMessage>>(authorized.await()).value
            assertEquals(1, encrypted.signature_data?.AES_GCM_Personalized_data?.counter)
            assertIs<TestVerifier.VerifyResult.Ok>(verifier.verify(encrypted))
            assertEquals(1u, state.counter())
        }

    @Test
    fun loadFromCacheMakesSessionReadyWithoutHello() =
        // dispatcher.go LoadCache: ImportSessionInfo(..., entry.CreatedAt) + ready = true
        runTest {
            val source = state()
            assertIs<SignerResult.Ok<Unit>>(hello(source, verifier()))
            advanceTimeBy(10_000)
            val exported = checkNotNull(source.export()) // clock_time = 10
            advanceTimeBy(30_000)
            val restored = state()
            assertIs<SignerResult.Ok<Unit>>(restored.loadFromCache(exported, age = 30.seconds))
            assertTrue(restored.isReady)
            assertEquals(40u, restored.timestamp())
        }

    @Test
    fun negativeCacheAgeImportsWithAgeZeroAndNeverThrows() =
        // 인계 항목 5: 벽시계가 뒤로 가 createdAt이 미래면 age < 0 → Signer.importSessionInfo가 0으로 본다
        runTest {
            val source = state()
            assertIs<SignerResult.Ok<Unit>>(hello(source, verifier()))
            advanceTimeBy(10_000)
            val exported = checkNotNull(source.export()) // clock_time = 10
            val restored = state()
            assertIs<SignerResult.Ok<Unit>>(restored.loadFromCache(exported, age = (-5).seconds))
            assertEquals(10u, restored.timestamp()) // age 0으로 취급: 시계는 저장 시점 그대로
        }

    @Test
    fun corruptCacheEntryIsAFaultNotAnException() =
        runTest {
            val state = state()
            val result = assertIs<SignerResult.Fault>(state.loadFromCache(byteArrayOf(0x12), age = 0.seconds))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_DECODING, result.fault)
            assertFalse(state.isReady)
        }

    @Test
    fun closeInvalidatesSignerAndIsNoLongerReady() =
        runTest {
            val state = state()
            assertIs<SignerResult.Ok<Unit>>(hello(state, verifier()))
            state.close()
            assertFalse(state.isReady) // M4: a closed session is never ready
            assertNull(state.export())
            assertNull(state.decrypt(RoutableMessage(), ByteArray(0)))
            val result = assertIs<SignerResult.Fault>(state.authorize(command(), 5.seconds))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, result.fault)
            assertEquals(SessionState.NO_SESSION, result.detail)
        }

    @Test
    fun closeIsFinalAndRejectsLaterHelloAndCacheLoad() =
        // M2 인계: close() 뒤 수신 코루틴이 좁은 창에서 키를 다시 만들지 않는다(설계 구체화 13)
        runTest {
            val state = state()
            val signed = verifier().signedSessionInfo(challenge)
            state.close()
            assertTrue(state.isClosed)
            val fault = assertIs<SignerResult.Fault>(state.processHello(challenge, signed.encoded, signed.tag))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL, fault.fault)
            assertIs<SignerResult.Fault>(state.loadFromCache(signed.encoded, Duration.ZERO))
            assertFalse(state.isReady)
            assertNull(state.export())
        }

    @Test
    fun closedSessionIsNeverReady() =
        // M3 최종 리뷰: close()가 준비 신호를 되돌리지 않아 disconnect 뒤 startSession이 캐시 세션에 Success였다
        runTest {
            val h = dispatcherHarness()
            h.manualHandshake(Domain.DOMAIN_VEHICLE_SECURITY)
            val session = checkNotNull(h.dispatcher.session(Domain.DOMAIN_VEHICLE_SECURITY))
            assertTrue(session.isReady)
            session.close()
            assertFalse(session.isReady)
        }

    // 다른 디스패처에서 핸드셰이크한 세션을 export()해 이 세션에 캐시로 넣는다.
    private suspend fun TestScope.loadVcsecSessionFromCache(session: SessionState) {
        val other = dispatcherHarness()
        other.manualHandshake(Domain.DOMAIN_VEHICLE_SECURITY)
        val bytes = checkNotNull(checkNotNull(other.dispatcher.session(Domain.DOMAIN_VEHICLE_SECURITY)).export())
        assertIs<SignerResult.Ok<Unit>>(session.loadFromCache(bytes, Duration.ZERO))
    }

    @Test
    fun authorizeThatWasWaitingWhenTheSessionWasInvalidatedFailsWithNoSession() =
        // 준비 신호를 이미 받은 authorize가 무효화 뒤에 옛 Signer로 진행하면 안 된다. 단일 스레드 스케줄러로 순서를 고정한다.
        runTest {
            val h = dispatcherHarness(start = false)
            val session = checkNotNull(h.dispatcher.session(Domain.DOMAIN_VEHICLE_SECURITY))
            val waiting = async { session.authorize(testCommand(Domain.DOMAIN_VEHICLE_SECURITY), 5.seconds) }
            runCurrent() // authorize가 준비 신호를 기다린다
            loadVcsecSessionFromCache(session) // 준비 신호 완료 — authorize는 재개 가능하지만 아직 실행되지 않았다
            session.invalidate() // 재개 전에 무효화
            loadVcsecSessionFromCache(session) // 새 Signer·새 신호 — 옛 신호 식별 검사가 막아야 한다(signer는 null이 아니다)
            val result = assertIs<SignerResult.Fault>(waiting.await())
            assertEquals(SessionState.NO_SESSION, result.detail)
        }

    @Test
    fun authorizeNowOnANeverReadySessionFailsWithNoSessionWithoutWaiting() =
        runTest {
            val h = dispatcherHarness(start = false)
            val session = checkNotNull(h.dispatcher.session(Domain.DOMAIN_VEHICLE_SECURITY))
            val result = assertIs<SignerResult.Fault>(session.authorizeNow(testCommand(Domain.DOMAIN_VEHICLE_SECURITY), 5.seconds))
            assertEquals(SessionState.NO_SESSION, result.detail)
        }
}
