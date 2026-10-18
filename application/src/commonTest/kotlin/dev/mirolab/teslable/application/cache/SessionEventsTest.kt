package dev.mirolab.teslable.application.cache

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.HandshakeFlow
import dev.mirolab.teslable.application.dispatcher.PendingRequest
import dev.mirolab.teslable.application.dispatcher.SessionEvent
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.dispatcher.testCommand
import dev.mirolab.teslable.application.vcsec.VcsecResponses
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.SessionCache
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SessionEventsTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)

    @Test
    fun successfulHelloEmitsUpdated() =
        runTest {
            val h = dispatcherHarness()
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSessions(setOf(vcsec, infotainment)))
            runCurrent()
            assertEquals(setOf<SessionEvent>(SessionEvent.Updated(vcsec), SessionEvent.Updated(infotainment)), events.toSet())
        }

    /** M2 `staleCachedVehicleKeyLeavesSessionStuckLikeGo`와 같은 상황: 다른 차량 키로 만든 캐시를 이 차량에 복원한다. */
    private suspend fun TestScope.restoreStaleCache(cache: RecordingSessionCache): SessionCacheSync {
        val otherCar = FakeVehicle(vehicleKey = TestCrypto.goKnownVerifierKey(), timeSource = testTimeSource)
        val first = dispatcherHarness(fake = otherCar)
        assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSessions(setOf(vcsec, infotainment)))
        val sync = SessionCacheSync(cache, otherCar.vin, keyId)
        sync.store(first.dispatcher)
        return sync
    }

    @Test
    fun failedHelloOnAnUnconfirmedCachedSessionEmitsStaleCacheOnce() =
        // 설계 구체화 14, 사용자 답 (a): 감지 = 캐시 복원 후 미확인 세션의 세션정보 갱신 실패(태그 불일치)
        runTest {
            val cache = RecordingSessionCache()
            val sync = restoreStaleCache(cache)
            val h = dispatcherHarness(fake = FakeVehicle(timeSource = testTimeSource), start = false)
            assertEquals(setOf(vcsec, infotainment), sync.load(h.dispatcher))
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            h.dispatcher.start()
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).value
            pending.use { it.receive() }
            // M4: 판정된 세션은 무효화되어 두 번째 전송은 NoSession이다(헛도는 재시도·재판정 없음)
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), h.dispatcher.send(testCommand(vcsec), AuthMethod.GCM))
            runCurrent()
            assertEquals(listOf<SessionEvent>(SessionEvent.StaleCache(vcsec)), events)
            assertTrue(h.logger.contains("Cached session for DOMAIN_VEHICLE_SECURITY looks stale"))
        }

    @Test
    fun confirmedSessionNeverReportsStale() =
        // (셋업 조정: 암호화 응답은 FLAG_ENCRYPT_RESPONSE 요청에만 오므로 플래그를 단다)
        // 정상 캐시: 첫 응답 복호화 성공으로 확인되면, 이후 세션정보 실패(예: 태그 손상)는 stale이 아니다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val first = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(first.dispatcher).startSession(infotainment))
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            sync.store(first.dispatcher)
            val h = dispatcherHarness(fake = fake, start = false)
            sync.load(h.dispatcher)
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { h.dispatcher.sessionEvents.collect { events += it } }
            h.dispatcher.start()
            val ok =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(infotainment, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), AuthMethod.GCM),
                ).value
            ok.use { it.receive() } // 복호화 성공 → 확인됨
            assertFalse(h.dispatcher.session(infotainment)?.isUnconfirmedCacheRestore ?: true)
            fake.corruptNextSessionInfoTag(infotainment)
            fake.attachSessionInfoOnce(infotainment)
            val bad =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(infotainment, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), AuthMethod.GCM),
                ).value
            bad.use { it.receive() }
            runCurrent()
            assertTrue(events.none { it is SessionEvent.StaleCache })
        }

    @Test
    fun vehicleSessionStoresOnUpdateAndDropsTheStaleDomainForGood() =
        runTest {
            val cache = RecordingSessionCache()
            val sync = restoreStaleCache(cache)
            val fake = FakeVehicle(timeSource = testTimeSource)
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = backgroundScope)
            assertEquals(setOf(vcsec, infotainment), session.connect())
            val storesBefore = cache.stores.size
            session.vcsec.execute(
                byteArrayOf(1),
                AuthMethod.GCM,
                dev.mirolab.teslable.application.vcsec.VcsecResponses.COMMAND_STATUS_ABSENT,
                timeout = 1.seconds,
            )
            runCurrent()
            val afterStale = cache.stores.drop(storesBefore)
            assertTrue(afterStale.isNotEmpty())
            assertTrue(afterStale.last().entries.none { it.domain == vcsec })
            assertTrue(afterStale.last().entries.any { it.domain == infotainment })
            session.disconnect()
            assertTrue(
                cache.stores
                    .last()
                    .entries
                    .none { it.domain == vcsec },
            ) // 해제 저장도 stale 도메인을 다시 넣지 않는다
        }

    @Test
    fun disconnectStopsEventStoresBeforeTheFinalStoreSoTheCacheIsNotWiped() =
        // 설계 구체화 14 + M2 storingAfterCloseWipesThePreviouslySavedCacheHazard
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val sync = SessionCacheSync(cache, fake.vin, keyId)
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = backgroundScope)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            runCurrent()
            session.disconnect()
            val storesAtDisconnect = cache.stores.size
            runCurrent()
            assertEquals(storesAtDisconnect, cache.stores.size)
            assertEquals(
                2,
                cache.stores
                    .last()
                    .entries.size,
            )
        }

    /** 첫 store만 1초 걸리는 캐시 — 이벤트 수집이 저장 중에 멈춰 있는 창을 만든다. */
    private class SlowFirstStoreCache(
        private val inner: RecordingSessionCache,
    ) : SessionCache by inner {
        private var first = true

        override suspend fun store(
            vin: Vin,
            keyId: KeyId,
            entries: List<SessionSnapshot>,
        ) {
            if (first) {
                first = false
                delay(1.seconds)
            }
            inner.store(vin, keyId, entries)
        }
    }

    @Test
    fun disconnectCancelsAnInFlightEventStoreSoNoStoreRunsAfterClose() =
        // 위 테스트는 대기 중인 이벤트가 없어 취소 순서를 변이로 잡지 못한다. 저장이 지연되는 동안 이벤트 둘이 쌓인 채 해제한다.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val inner = RecordingSessionCache()
            val sync = SessionCacheSync(SlowFirstStoreCache(inner), fake.vin, keyId)
            val h = dispatcherHarness(fake = fake, start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = backgroundScope)
            session.connect()
            val dispatcher = h.dispatcher
            // 두 도메인 핸드셰이크 → Updated 두 개. 첫 이벤트의 저장이 지연 중이고 둘째는 대기한다.
            val handshake = launch { HandshakeFlow(dispatcher).startSessions(setOf(vcsec, infotainment)) }
            runCurrent()
            assertTrue(handshake.isCompleted) // join()하면 테스트가 멈춰 가상 시간이 자동으로 흘러 지연 저장이 끝나 버린다
            session.disconnect()
            advanceTimeBy(2.seconds) // advanceUntilIdle은 백그라운드 지연 작업을 돌리지 않는다
            runCurrent()
            assertEquals(
                2,
                inner.stores
                    .last()
                    .entries.size,
            )
        }

    @Test
    fun staleVerdictSurvivesDisconnectEvenIfTheEventWasNeverCollected() =
        // 수집기가 없어(이벤트 유실) 마지막 저장이 판정만으로 stale 도메인을 빼야 한다
        runTest {
            val cache = RecordingSessionCache()
            val sync = restoreStaleCache(cache)
            val h = dispatcherHarness(fake = FakeVehicle(timeSource = testTimeSource), start = false)
            val session = VehicleSession(h.dispatcher, cacheSync = sync) // eventScope 없음 → 수집기 없음
            session.connect()
            session.vcsec.execute(byteArrayOf(1), AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            session.disconnect()
            val last = cache.stores.last()
            assertTrue(last.entries.none { it.domain == vcsec })
            assertTrue(last.entries.any { it.domain == infotainment })
        }

    /** [load]가 [gate]가 열릴 때까지 `connectMutex`를 쥔 채 멈추는 캐시. */
    private class GatedLoadCache(
        private val inner: RecordingSessionCache,
        private val gate: CompletableDeferred<Unit>,
    ) : SessionCache by inner {
        override suspend fun load(
            vin: Vin,
            keyId: KeyId,
        ): List<CachedSession> {
            gate.await()
            return inner.load(vin, keyId)
        }
    }

    @Test
    fun disconnectDuringAnInFlightConnectLeavesNoCollectorBehind() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val gate = CompletableDeferred<Unit>()
            val sync = SessionCacheSync(GatedLoadCache(RecordingSessionCache(), gate), fake.vin, keyId)
            val h = dispatcherHarness(fake = fake, start = false)
            val scopeJob = Job()
            val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = scope)
            val connecting = launch { session.connect() }
            runCurrent() // connect()가 load에서 connectMutex를 쥔 채 멈춘다
            val disconnecting = launch { session.disconnect() }
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            connecting.join()
            disconnecting.join()
            runCurrent()
            assertTrue(scopeJob.children.none { it.isActive }, "collector must not outlive disconnect()")
            assertFalse(h.dispatcher.isListening)
            scopeJob.cancel()
        }

    /** 첫 store만 예외를 던지는 캐시. */
    private class ThrowFirstStoreCache(
        private val inner: RecordingSessionCache,
    ) : SessionCache by inner {
        private var first = true

        override suspend fun store(
            vin: Vin,
            keyId: KeyId,
            entries: List<SessionSnapshot>,
        ) {
            if (first) {
                first = false
                error("disk full")
            }
            inner.store(vin, keyId, entries)
        }
    }

    @Test
    fun aThrowingEventStoreIsLoggedAndTheCollectorKeepsGoing() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val inner = RecordingSessionCache()
            val sync = SessionCacheSync(ThrowFirstStoreCache(inner), fake.vin, keyId)
            val h = dispatcherHarness(fake = fake, start = false)
            val scopeJob = Job()
            val scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler))
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = scope)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSessions(setOf(vcsec, infotainment)))
            runCurrent()
            assertTrue(scopeJob.isActive)
            assertTrue(inner.stores.isNotEmpty())
            assertTrue(h.logger.contains("Failed to store session cache after session update: IllegalStateException"))
            session.disconnect()
            scopeJob.cancel()
        }

    @Test
    fun staleCacheInvalidatesTheSessionAndAHandshakeRecovers() =
        // 설계 구체화 2, Review Focus 3: 헛도는 재시도 대신 NoSession, startSession 한 번으로 회복, 회복한 세션은 다시 캐시된다
        runTest {
            val cache = RecordingSessionCache()
            val sync = restoreStaleCache(cache)
            val h = dispatcherHarness(fake = FakeVehicle(timeSource = testTimeSource), start = false)
            // 이벤트 수집기는 별도 스케줄러에 둔다: 수동으로 돌릴 때만 실행되므로, 핸드셰이크 구간의 저장은 storeAfterHandshake만 할 수 있다
            val eventScheduler = TestCoroutineScheduler()
            val eventScope = CoroutineScope(Job() + StandardTestDispatcher(eventScheduler))
            val session = VehicleSession(h.dispatcher, cacheSync = sync, eventScope = eventScope)
            assertEquals(setOf(vcsec, infotainment), session.connect())
            eventScheduler.runCurrent() // 수집기 구독 시작
            val lock = byteArrayOf(0x10, 0x01)
            val first = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 5.seconds)
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), first)
            assertFalse(checkNotNull(h.dispatcher.session(vcsec)).isReady)
            eventScheduler.runCurrent() // StaleCache 처리(markStale) — 이후 Updated 처리는 돌리지 않는다
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(vcsec), timeout = 1.seconds))
            // 핸드셰이크 자체의 저장이 회복한 도메인을 넣는다(이벤트 처리 순서에 기대지 않는다 — runCurrent 전에 단언)
            assertTrue(
                cache.stores
                    .last()
                    .entries
                    .any { it.domain == vcsec },
            )
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
        }
}
