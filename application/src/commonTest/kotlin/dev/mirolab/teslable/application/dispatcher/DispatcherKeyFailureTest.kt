package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.testing.LockableEcdhKey
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * 최종 리뷰 I3: 플랫폼 키의 ECDH가 예상 밖 예외를 던져도(예: 합의할 수 없는 StrongBox 키) 수신 코루틴이 죽거나
 * 캐시 복원(`connect()`)이 예외로 끝나지 않는다 — 수신 경계에서 Fault처럼 다룬다.
 * FR-037(#96): 기기 잠금(`KeyLockedException`)은 그와 따로 구별한다 — WARN, 요청 표시, 캐시 보존.
 */
class DispatcherKeyFailureTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    /** 처음 [failures]번의 [sharedX]가 [IllegalStateException]을 던진다. */
    private class FailingEcdhKey(
        private val delegate: EcdhPrivateKey,
        var failures: Int,
    ) : EcdhPrivateKey {
        override val publicKey: PublicKeyBytes get() = delegate.publicKey

        override suspend fun sharedX(peer: PublicKeyBytes): ByteArray {
            if (failures > 0) {
                failures--
                error("keystore agreement failed")
            }
            return delegate.sharedX(peer)
        }
    }

    @Test
    fun ecdhExceptionWhileProcessingSessionInfoKeepsTheReceiveLoopAlive() =
        runTest {
            val h = dispatcherHarness(privateKey = FailingEcdhKey(TestCrypto.clientKey(), failures = 1))
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            pending.use { assertNotNull(it.receive().session_info) } // Fault처럼: 이벤트 없이 응답은 그대로 전달
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            val logged = h.logger.entries.single { it.message.contains("Session info processing failed") }
            assertEquals(LogLevel.ERROR, logged.level)
            assertEquals("Session info processing failed: IllegalStateException", logged.message.substringAfter("] "))
            h.manualHandshake(vcsec) // 수신 루프가 살아 있어 다음 세션정보를 처리한다
        }

    @Test
    fun ecdhExceptionWhileLoadingTheCacheSkipsOnlyThatEntry() =
        runTest {
            val source = dispatcherHarness()
            source.manualHandshake(vcsec)
            source.manualHandshake(infotainment)
            val entries = source.dispatcher.exportSessions().map { CachedSession(it.domain, it.sessionInfo, Duration.ZERO) }
            assertEquals(2, entries.size)
            val h = dispatcherHarness(privateKey = FailingEcdhKey(TestCrypto.clientKey(), failures = 1), fake = source.fake)
            assertEquals(setOf(entries[1].domain), h.dispatcher.loadSessions(entries))
            val logged = h.logger.entries.single { it.message.contains("processing failed") }
            assertEquals(LogLevel.WARN, logged.level)
            assertEquals("invalid cache: ${entries[0].domain}: processing failed: IllegalStateException", logged.message)
            assertEquals(emptySet(), h.dispatcher.restoresBlockedByLock) // 잠금이 아닌 실패는 캐시를 지켜 주지 않는다
        }

    @Test
    fun lockedKeyWhileProcessingSessionInfoMarksTheRequestAndKeepsTheReceiveLoopAlive() =
        // FR-037(#96): 잠긴 기기의 ECDH는 예상 밖 오류(ERROR)가 아니라 WARN이다. 응답은 그대로 전달하고, 그 응답을 받은 요청에
        // 표시해 핸드셰이크가 재전송 없이 Locked로 끝나게 한다. 표시는 요청마다라서 잠금이 풀린 뒤의 요청에는 남지 않는다.
        runTest {
            val key = LockableEcdhKey()
            val h = dispatcherHarness(privateKey = key)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            pending.use {
                assertNotNull(it.receive().session_info)
                assertTrue(it.keyLocked)
            }
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            val logged = h.logger.entries.single { it.message.contains("private key is locked") }
            assertEquals(LogLevel.WARN, logged.level)
            assertEquals(
                "Session info not applied for DOMAIN_VEHICLE_SECURITY: the private key is locked (device locked)",
                logged.message.substringAfter("] "),
            )
            assertFalse(h.logger.contains("Session info processing failed"))
            assertFalse(h.logger.contains("Session info error"))
            key.locked = false
            val next = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            next.use {
                assertNotNull(it.receive().session_info)
                assertFalse(it.keyLocked)
            }
            assertTrue(assertNotNull(h.dispatcher.session(vcsec)).isReady)
        }

    @Test
    fun lockedKeyWhileLoadingTheCacheSkipsTheEntriesAndRemembersTheirDomains() =
        // FR-037(#96): 잠금 때문에 복원하지 못한 항목은 "invalid cache"가 아니다 — 아직 유효하다. 어느 도메인이 막혔는지 기억해
        // 캐시 저장(SessionCacheSync)이 그 항목을 지우지 않게 한다.
        runTest {
            val source = dispatcherHarness()
            source.manualHandshake(vcsec)
            source.manualHandshake(infotainment)
            val entries = source.dispatcher.exportSessions().map { CachedSession(it.domain, it.sessionInfo, Duration.ZERO) }
            val h = dispatcherHarness(privateKey = LockableEcdhKey(), fake = source.fake)
            assertEquals(emptySet(), h.dispatcher.loadSessions(entries))
            assertEquals(setOf(vcsec, infotainment), h.dispatcher.restoresBlockedByLock)
            assertFalse(assertNotNull(h.dispatcher.session(vcsec)).isReady)
            assertFalse(assertNotNull(h.dispatcher.session(infotainment)).isReady)
            val logged = h.logger.entries.filter { it.message.contains("private key is locked") }
            assertEquals(listOf(LogLevel.WARN, LogLevel.WARN), logged.map { it.level })
            assertEquals(
                "Cached session for DOMAIN_VEHICLE_SECURITY not restored: the private key is locked (device locked)",
                logged.first().message,
            )
            assertFalse(h.logger.contains("invalid cache"))
        }
}
