package dev.mirolab.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import dev.mirolab.teslable.application.cache.SessionCacheSync
import dev.mirolab.teslable.application.dispatcher.HandshakeFlow
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.SessionCache
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleSessionTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

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
        // vehicle_test.go TestVehicleConnectionRetry: BUSY(일시) 뒤 성공.
        // M7/N4: 3은 이 테스트에서 나오는 값이다 — runTest의 스케줄러가, VCSEC의 실패가 INFOTAINMENT 핸드셰이크를
        // 취소하기 전에 수신 코루틴이 두 도메인의 버퍼링된 응답을 모두 처리하게 해 주기 때문이다(다른 실행 순서라면
        // 4가 될 수 있다 — "인터리빙과 무관하게 결정적"이라던 이전 서술은 틀렸다).
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, MessageFault_E.MESSAGEFAULT_ERROR_BUSY)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            assertEquals(3, h.fake.sessionInfoRequests)
            // Go는 여기서 VCSEC로 보내지만 Kotlin은 SendWithRetry에서 VCSEC를 금지한다(설계 구체화 13)
            assertIs<VehicleResult.Success<*>>(session.send.send(infotainment, "x".encodeToByteArray(), AuthMethod.GCM))
        }

    @Test
    fun startSessionPropagatesCallerCancellationInsteadOfReturningATimeoutValue() =
        // vehicle_test.go TestVehicleConnectionTimeout: 실제로는 데드라인을 기다리지 않고 cancel()을 먼저 부른 뒤
        // StartSession을 호출해 context.Canceled를 기대한다(명시적 취소, 데드라인 초과가 아니다). M1(2차 리뷰):
        // job.getCompletionExceptionOrNull()은 취소 요청 뒤 항상 CancellationException이므로 아무 것도 증명하지
        // 못한다 — 대신 반환값이 실제로 대입되는지를 본다.
        runTest {
            val h = dispatcherHarness(start = false)
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptHandshake(vcsec, *Array(10) { MessageFault_E.MESSAGEFAULT_ERROR_BUSY })
            session.connect()
            var result: VehicleResult<Unit>? = null
            val job = launch { result = session.startSession(timeout = 5.seconds) }
            runCurrent()
            job.cancel()
            runCurrent()
            assertTrue(job.isCancelled)
            assertNull(result)
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
            // Go는 여기서 VCSEC로 보내지만 Kotlin은 SendWithRetry에서 VCSEC를 금지한다(설계 구체화 13)
            assertIs<VehicleResult.Success<*>>(session2.send.send(infotainment, "x".encodeToByteArray(), AuthMethod.GCM))
        }

    @Test
    fun disconnectTwiceStoresOnlyOnce() =
        // I2: Go Disconnect는 여러 번 불러도 안전하다(vehicle.go 169~173행). 두 번째 호출은 저장을 건너뛴다 —
        // 그러지 않으면 첫 호출의 Dispatcher.close()가 이미 세션을 소거한 뒤라 빈 목록을 저장해 첫 저장을 지워
        // 버린다(SessionCacheSync KDoc).
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession())
            session.disconnect()
            session.disconnect()
            assertEquals(2, cache.stores.size) // startSession 성공 1회 + disconnect 1회(두 번째는 건너뜀)
            val lastStoredEntries = cache.stores.last().entries
            val storedDomains = lastStoredEntries.map { it.domain }.toSet()
            assertEquals(setOf(vcsec, Domain.DOMAIN_INFOTAINMENT), storedDomains)
        }

    @Test
    fun startSessionFinishingAfterDisconnectDoesNotWipeTheCache() =
        // 최종 리뷰 M5: disconnect()가 Dispatcher.close()까지 끝낸 뒤 startSession이 성공으로 끝나면(캐시로 이미 준비된 세션은
        // 요청 없이 성공한다) 그 저장이 빈 exportSessions()로 disconnect 시점의 저장을 지워 버렸다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            val first = VehicleSession(dispatcherHarness(fake = fake, start = false).dispatcher, cacheSync = sync)
            first.connect()
            assertIs<VehicleResult.Success<Unit>>(first.startSession())
            first.disconnect()
            val second = VehicleSession(dispatcherHarness(fake = fake, start = false).dispatcher, cacheSync = sync)
            assertEquals(setOf(vcsec, infotainment), second.connect())
            second.disconnect()
            val storesAfterDisconnect = cache.stores.size
            second.startSession() // 늦게 끝난 핸드셰이크
            assertEquals(storesAfterDisconnect, cache.stores.size)
            assertEquals(setOf(vcsec, infotainment), cache.load(fake.vin, keyId).map { it.domain }.toSet())
        }

    @Test
    fun disconnectClosesDispatcherEvenWhenCacheStoreIsCancelled() =
        // I1: cacheSync?.store()가 suspend 중일 때(느린 캐시 I/O) 호출자가 취소되면 예전에는 그
        // CancellationException이 dispatcher.close() 호출까지 건너뛰게 만들어 세션 키가 메모리에 남고 전송이 열린
        // 채로 남을 수 있었다. disconnect()는 이제 close()를 finally의 NonCancellable 안에서 반드시 실행한다.
        // M1: job.getCompletionExceptionOrNull()은 job.cancel() 뒤에는 항상 CancellationException이므로(disconnect()가
        // 취소를 삼켜 정상 반환해도 Job 자체는 여전히 Cancelled로 끝난다) 그 어설션은 아무 것도 증명하지 못한다. 대신
        // disconnect()가 실제로 반환하는지를 플래그로 직접 본다: 취소가 전파되면 그 대입 줄에 도달하지 못한다.
        runTest {
            // startSession()도 성공하면 캐시에 저장한다(SDD §7.1) — 이 가짜는 그 첫 저장은 정상 처리하고,
            // disconnect()가 부르는 두 번째 저장부터만 절대 완료되지 않는 대기에 건다.
            class GatedSessionCache : SessionCache {
                private var calls = 0

                override suspend fun load(
                    vin: Vin,
                    keyId: KeyId,
                ): List<CachedSession> = emptyList()

                override suspend fun store(
                    vin: Vin,
                    keyId: KeyId,
                    entries: List<SessionSnapshot>,
                ) {
                    calls++
                    if (calls > 1) CompletableDeferred<Unit>().await() // 취소되기 전까지 계속 suspend한다
                }

                override suspend fun clear(vin: Vin) = Unit
            }
            val h = dispatcherHarness(start = false)
            val sync = SessionCacheSync(GatedSessionCache(), h.dispatcher.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            val session = VehicleSession(h.dispatcher, cacheSync = sync)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession())
            var returned = false
            val job =
                launch {
                    session.disconnect()
                    returned = true
                }
            runCurrent()
            job.cancel()
            runCurrent()
            assertTrue(job.isCancelled)
            assertFalse(returned)
            assertFalse(h.dispatcher.isListening)
            assertNull(h.dispatcher.session(vcsec)?.export())
            assertIs<TransportState.Disconnected>(h.transport.state.value) // fold (a)
        }

    @Test
    fun disconnectWithoutConnectDoesNotOverwriteAnExistingCache() =
        // cmd/tesla-control/main.go:174-177: the Go CLI only saves the cache after a successful Connect
        // (via `defer UpdateCachedSessions`). disconnect() without ever calling connect() must not store an
        // empty list over a cache that a previous, completed connect() already populated.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            val seeder = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(seeder.dispatcher).startSessions())
            sync.store(seeder.dispatcher)
            assertEquals(1, cache.stores.size)

            val h = dispatcherHarness(fake = fake, start = false)
            VehicleSession(h.dispatcher, cacheSync = sync).disconnect() // never called connect()

            assertEquals(1, cache.stores.size) // disconnect() must not have stored anything
            val restored = dispatcherHarness(fake = fake, start = false)
            assertEquals(setOf(vcsec, infotainment), sync.load(restored.dispatcher))
        }

    @Test
    fun disconnectAfterACancelledConnectDoesNotOverwriteAnExistingCache() =
        // Same Go reference as disconnectWithoutConnectDoesNotOverwriteAnExistingCache. Here connect() itself
        // is cancelled while still suspended loading the cache, so it never reaches dispatcher.start();
        // disconnect() must treat that exactly like "never connected" and skip storing.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            val seeder = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(seeder.dispatcher).startSessions())
            sync.store(seeder.dispatcher)
            assertEquals(1, cache.stores.size)

            val gate = CompletableDeferred<Unit>()

            class GatedLoadSessionCache : SessionCache {
                override suspend fun load(
                    vin: Vin,
                    keyId: KeyId,
                ): List<CachedSession> {
                    gate.await() // never opens: connect() gets cancelled while suspended here
                    error("unreachable")
                }

                override suspend fun store(
                    vin: Vin,
                    keyId: KeyId,
                    entries: List<SessionSnapshot>,
                ) = cache.store(vin, keyId, entries)

                override suspend fun clear(vin: Vin) = cache.clear(vin)
            }
            val gatedSync = SessionCacheSync(GatedLoadSessionCache(), fake.vin, keyId)
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = gatedSync)
            val job = launch { session.connect() }
            runCurrent()
            job.cancel()
            runCurrent()
            assertTrue(job.isCancelled)

            session.disconnect()

            assertEquals(1, cache.stores.size) // disconnect() must not have stored anything
        }

    @Test
    fun connectIsIdempotent() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            val session = VehicleSession(dispatcherHarness(fake = fake, start = false).dispatcher, cacheSync = sync)
            session.connect()
            session.connect()
            assertEquals(1, cache.loads)
        }

    @Test
    fun connectAfterDisconnectIsRejected() =
        // Review Focus 4, 설계 구체화 13: 재연결은 TeslaBle.connect()가 새 객체로
        runTest {
            val session = VehicleSession(dispatcherHarness(start = false).dispatcher)
            session.connect()
            session.disconnect()
            assertFailsWith<IllegalStateException> { session.connect() }
        }

    @Test
    fun cancelledDisconnectStillClosesAndMarksSessionWhileConnectHoldsTheLock() =
        // M2 인계: disconnect()의 바깥 NonCancellable을 직접 고정. connect()가 캐시 load에서 connectMutex를 쥔 채 멈춰 있으면
        // disconnect()는 finally의 connectMutex 대기에서 취소되는데, NonCancellable이 없으면 닫기와 disconnected 표시가 건너뛰어진다.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val gate = CompletableDeferred<Unit>()
            val gated =
                object : SessionCache by RecordingSessionCache() {
                    override suspend fun load(
                        vin: Vin,
                        keyId: KeyId,
                    ): List<CachedSession> {
                        gate.await()
                        return emptyList()
                    }
                }
            val sync = SessionCacheSync(gated, fake.vin, KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives))
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync)
            val connectJob = launch { session.connect() }
            runCurrent() // connect()가 connectMutex를 쥐고 load에서 대기
            val disconnectJob = launch { session.disconnect() }
            runCurrent() // disconnect()가 connectMutex 대기 중
            disconnectJob.cancel()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            connectJob.join()
            disconnectJob.join()
            assertTrue(disconnectJob.isCancelled || disconnectJob.isCompleted)
            assertFalse(h.dispatcher.isListening)
            assertEquals(TransportState.Disconnected(reason = null), h.transport.state.value)
            assertFailsWith<IllegalStateException> { session.connect() }
        }

    @Test
    fun startSessionAfterDisconnectIsNotConnectedEvenForCachedSessions() =
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            session.disconnect()
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), session.startSession(timeout = 1.seconds))
        }
}
