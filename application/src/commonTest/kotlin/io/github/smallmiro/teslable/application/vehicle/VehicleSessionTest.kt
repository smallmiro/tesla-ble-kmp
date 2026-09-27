package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.application.cache.SessionCacheSync
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingSessionCache
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSessionTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    @Test
    fun startSessionReturnsFatalHandshakeErrorWithoutRetry() =
        // vehicle_test.go TestVehicleStartSessionFailed
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            session.connect()
            val result = assertIs<VehicleResult.Failure>(session.startSession(timeout = 1.seconds))
            assertEquals(VehicleError.KeyNotPaired, result.error)
        }

    @Test
    fun startSessionRetriesTransientHandshakeError() =
        // vehicle_test.go TestVehicleConnectionRetry: BUSY(일시) 뒤 성공
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_BUSY)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            assertTrue(h.fake.sessionInfoRequests in 3..4, "got ${h.fake.sessionInfoRequests}")
            assertIs<VehicleResult.Success<*>>(session.send.send(vcsec, "x".encodeToByteArray(), AuthMethod.GCM))
        }

    @Test
    fun startSessionTimesOutWhileErrorsStayTransient() =
        // vehicle_test.go TestVehicleConnectionTimeout → Failure(Timeout(afterSend = false)) (사용자 답 b: 전용 타입 없음)
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, *Array(10) { MessageFault_E.MESSAGEFAULT_ERROR_BUSY })
            session.connect()
            val result = assertIs<VehicleResult.Failure>(session.startSession(timeout = 5.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun connectRestoresCacheAndDisconnectStoresIt() =
        // vehicle.go NewVehicle(LoadCache) / Disconnect + SDD §7.1 저장 시점(핸드셰이크 완료, 해제)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            val first = dispatcherHarness(fake = fake, start = false)
            val session1 = VehicleSession(first.dispatcher, cacheSync = sync)
            assertTrue(session1.connect().isEmpty())
            assertIs<VehicleResult.Success<Unit>>(session1.startSession())
            assertEquals(1, cache.stores.size)
            session1.disconnect()
            assertEquals(2, cache.stores.size)
            assertFalse(first.dispatcher.isListening)
            val second = dispatcherHarness(fake = fake, start = false)
            val session2 = VehicleSession(second.dispatcher, cacheSync = sync)
            assertEquals(setOf(vcsec, Domain.DOMAIN_INFOTAINMENT), session2.connect())
            assertIs<VehicleResult.Success<Unit>>(session2.startSession()) // 이미 준비됨 → 요청 없음
            assertEquals(2, fake.sessionInfoRequests)
            assertIs<VehicleResult.Success<*>>(session2.send.send(vcsec, "x".encodeToByteArray(), AuthMethod.GCM))
        }
}
