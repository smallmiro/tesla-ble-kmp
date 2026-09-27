package io.github.smallmiro.teslable.application.cache

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.PendingRequest
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.testCommand
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingSessionCache
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCacheSyncTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)

    @Test
    fun resumesSessionFromCacheWithoutHandshake() =
        // dispatcher_test.go TestCache: Cache() → 새 디스패처 LoadCache() → 인증 명령이 핸드셰이크 없이 성공
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSessions())
            var now = 100_000L
            val cache = RecordingSessionCache { now }
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            sync.store(first.dispatcher)
            assertEquals(
                setOf(vcsec, infotainment),
                cache.stores
                    .single()
                    .entries
                    .map { it.domain }
                    .toSet(),
            )
            first.dispatcher.close()
            advanceTimeBy(30_000)
            now += 30_000
            val second = dispatcherHarness(fake = fake)
            assertEquals(setOf(vcsec, infotainment), sync.load(second.dispatcher))
            assertEquals(1, cache.loads)
            assertTrue(assertNotNull(second.dispatcher.session(vcsec)).isReady)
            assertTrue(second.logger.contains("Session for DOMAIN_VEHICLE_SECURITY loaded from cache"))
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(second.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).value
            pending.use { assertNull(ResponseClassifier.protocolError(it.receive())) }
            assertEquals(2, fake.sessionInfoRequests) // 두 번째 연결은 핸드셰이크하지 않았다
        }

    @Test
    fun entryFromTheFutureImportsWithAgeZeroAndNeverThrows() =
        // 인계 항목 5 (캐시 경로): createdAt이 미래 → age 음수 → Signer가 0으로 본다. 예외 없음.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(vcsec))
            advanceTimeBy(10_000)
            val exported = first.dispatcher.exportSessions().single { it.domain == vcsec } // clock_time = 10
            var now = 100_000L
            val cache = RecordingSessionCache { now }
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            cache.store(fake.vin, keyId, listOf(exported))
            now = 50_000L // 벽시계가 50초 뒤로 갔다
            val second = dispatcherHarness(fake = fake)
            assertEquals(setOf(vcsec), sync.load(second.dispatcher))
            assertEquals(10u, assertNotNull(second.dispatcher.session(vcsec)).timestamp())
        }

    @Test
    fun loadSkipsCorruptEntriesAndRestoresTheRest() =
        // 설계 구체화 6: Go LoadCache는 전체 실패, 우리는 건너뛰고 로그
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(infotainment))
            val good = first.dispatcher.exportSessions().single()
            val cache = RecordingSessionCache()
            cache.seed(
                fake.vin,
                keyId,
                listOf(
                    SessionCacheCodec.Entry(vcsec, 0L, byteArrayOf(0x12)), // publicKey 태그 뒤가 잘림 → DECODING
                    SessionCacheCodec.Entry(infotainment, 0L, good.sessionInfo),
                ),
            )
            val second = dispatcherHarness(fake = fake)
            assertEquals(setOf(infotainment), SessionCacheSync(cache, fake.vin, keyId).load(second.dispatcher))
            assertTrue(second.logger.contains("invalid cache: DOMAIN_VEHICLE_SECURITY: MESSAGEFAULT_ERROR_DECODING"))
            assertTrue(assertNotNull(second.dispatcher.session(infotainment)).isReady)
            assertTrue(!assertNotNull(second.dispatcher.session(vcsec)).isReady)
        }

    @Test
    fun staleCachedVehicleKeyLeavesSessionStuckLikeGo() =
        // Review Focus 4 / 설계 구체화 8: 다른 차량 키로 만든 캐시 → 이 차량은 이 클라이언트를 처음 보므로 새 검증자(새 epoch)가
        // INCORRECT_EPOCH + 세션정보로 답한다. 동봉 세션정보는 우리 K(옛 차량 키)로 태그가 맞지 않아 거부(Session info error:
        // INVALID_SIGNATURE) → 세션은 갇힌다. Go와 동일. 복구는 M3 connect()의 도메인 캐시 삭제(사용자 답 a).
        runTest {
            val otherCar = FakeVehicle(vehicleKey = TestCrypto.goKnownVerifierKey(), timeSource = testTimeSource)
            val first = dispatcherHarness(fake = otherCar)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(vcsec))
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, otherCar.vin, keyId)
            sync.store(first.dispatcher)
            val thisCar = FakeVehicle(timeSource = testTimeSource) // 같은 VIN, 다른 차량 키
            val second = dispatcherHarness(fake = thisCar)
            assertEquals(setOf(vcsec), sync.load(second.dispatcher))
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(second.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).value
            pending.use {
                assertEquals(
                    VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH),
                    ResponseClassifier.protocolError(it.receive()),
                )
            }
            assertTrue(second.logger.contains("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
            assertTrue(assertNotNull(second.dispatcher.session(vcsec)).isReady) // 여전히 옛 키로 "준비됨"
        }

    @Test
    fun exportSkipsDomainsWithoutSessionAndStoreWritesEvenWhenEmpty() =
        // dispatcher.go Cache(): ctx == nil인 세션은 건너뛴다; vehicle.go UpdateCachedSessions는 빈 목록도 저장(캐시 정리)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val h = dispatcherHarness(fake = fake)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            sync.store(h.dispatcher)
            assertTrue(
                cache.stores
                    .single()
                    .entries
                    .isEmpty(),
            )
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            sync.store(h.dispatcher)
            val stored =
                cache.stores
                    .last()
                    .entries
                    .single()
            assertEquals(infotainment, stored.domain)
            assertEquals(0, SessionInfo.ADAPTER.decode(stored.sessionInfo).clock_time)
        }

    @Test
    fun storingAfterCloseWipesThePreviouslySavedCacheHazard() =
        // M1 fold: Dispatcher.close() zeroes every signer, so exportSessions() afterwards is empty and store()
        // (which writes empty lists by design, see exportSkipsDomainsWithoutSessionAndStoreWritesEvenWhenEmpty
        // above) overwrites whatever was saved before. SessionCacheSync.store's KDoc says it must run BEFORE
        // Dispatcher.close() for exactly this reason; this pins what happens when a caller gets that order wrong
        // instead of leaving the hazard undocumented.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val h = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(vcsec))
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            sync.store(h.dispatcher)
            assertEquals(
                1,
                cache.stores
                    .single()
                    .entries.size,
            ) // 정상 순서: close() 이전에 저장

            h.dispatcher.close()
            sync.store(h.dispatcher) // 순서를 어김: close() 이후에 store()를 부르면
            assertTrue(
                cache.stores
                    .last()
                    .entries
                    .isEmpty(),
            ) // 방금 지운 세션이 반영돼 이전 저장을 덮어써 캐시가 비워진다
        }
}
