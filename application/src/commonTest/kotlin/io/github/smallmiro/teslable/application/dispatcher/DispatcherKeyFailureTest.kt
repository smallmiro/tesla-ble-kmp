package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration

/**
 * 최종 리뷰 I3: 플랫폼 키의 ECDH가 예상 밖 예외를 던져도(예: 합의할 수 없는 StrongBox 키) 수신 코루틴이 죽거나
 * 캐시 복원(`connect()`)이 예외로 끝나지 않는다 — 수신 경계에서 Fault처럼 다룬다.
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
        }
}
