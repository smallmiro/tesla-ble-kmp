package dev.mirolab.teslable.application.cache

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.application.dispatcher.CounterReservation
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.HandshakeFlow
import dev.mirolab.teslable.application.dispatcher.PendingRequest
import dev.mirolab.teslable.application.dispatcher.SessionState
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.dispatcher.testCommand
import dev.mirolab.teslable.application.vcsec.VcsecResponses
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.cache.SessionCacheCodec
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.SessionCache
import dev.mirolab.teslable.protocol.SignerResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * H17 (분석 보고서 2026-11-14 F21, ADR-0016): 캐시는 마지막 인증 요청보다 앞선 counter를 담고 있었다. 프로세스가 `disconnect()` 없이
 * 끝나면(강제 종료, iOS가 멈춘 앱을 끝냄, 충돌) 다음 프로세스의 첫 인증 요청이 차량이 이미 받은 counter를 다시 써
 * `INVALID_TOKEN_OR_COUNTER` + 세션정보를 받고, 반영한 뒤 다시 보냈다(실차 약 1.2초). 여기서는 같은 [FakeVehicle]과 같은 캐시를 두
 * "프로세스"(새 `Dispatcher`·`VehicleSession`)가 차례로 쓴다. 고친 뒤의 불변 조건: 인증 요청이 쓰는 counter는 그 요청을 쓰기 전에
 * 캐시가 이미 담고 있고(같은 epoch), 그 뒤의 어떤 저장도 그보다 낮은 값을 쓰지 않는다([assertEveryCounterWasStoredFirst]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CounterReservationTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
    private val reservation = SessionState.COUNTER_RESERVATION.toInt()

    /**
     * 쓰기마다 그때까지 차량이 받은 메시지 수와 도메인별 항목을 기록하는 캐시. [FakeVehicle]은 쓰기 호출 안에서 요청을 동기로 받으므로
     * `receivedBefore <= i`인 쓰기는 i번째 메시지가 나가기 전에 끝났다. [slowStore]가 있으면 쓰기마다 그만큼 기다리고, [failStores]면 던진다.
     */
    private class LedgerCache(
        private val fake: FakeVehicle,
        private val inner: RecordingSessionCache = RecordingSessionCache(),
    ) : SessionCache by inner {
        class Write(
            val receivedBefore: Int,
            val entries: Map<Domain, SessionInfo>,
        )

        val writes = mutableListOf<Write>()
        var slowStore: Duration = Duration.ZERO
        var failStores = false

        override suspend fun store(
            vin: Vin,
            keyId: KeyId,
            entries: List<SessionSnapshot>,
        ) {
            if (slowStore > Duration.ZERO) delay(slowStore)
            check(!failStores) { "disk full" }
            inner.store(vin, keyId, entries)
            writes += Write(fake.received.size, entries.associate { it.domain to SessionInfo.ADAPTER.decode(it.sessionInfo) })
        }

        fun counter(
            write: Write,
            domain: Domain,
        ): UInt? = write.entries[domain]?.counter?.toUInt()
    }

    /**
     * 새 프로세스 하나: 새 디스패처와 세션이 같은 캐시를 쓴다. 샘플처럼 이벤트 수집기도 둔다. [events]가 false면 수집기가 없다:
     * 세션정보 갱신 저장(Updated)이 예약 경로의 빈틈을 가리지 못하게 할 때 쓴다.
     */
    private fun TestScope.process(
        fake: FakeVehicle,
        cache: SessionCache,
        events: Boolean = true,
    ): Pair<VehicleSession, DispatcherHarness> {
        val h = dispatcherHarness(fake = fake, start = false)
        val sync = SessionCacheSync(cache, fake.vin, keyId)
        return VehicleSession(h.dispatcher, cacheSync = sync, eventScope = if (events) backgroundScope else null) to h
    }

    private fun authenticated(
        fake: FakeVehicle,
        from: Int,
    ): List<RoutableMessage> = fake.received.drop(from).filter { it.signature_data?.AES_GCM_Personalized_data != null }

    /** 불변 조건(위 KDoc): 요청마다 그 앞의 마지막 쓰기가 counter를 덮고, 같은 epoch의 뒤 쓰기는 그보다 낮지 않다. */
    private fun assertEveryCounterWasStoredFirst(
        fake: FakeVehicle,
        ledger: LedgerCache,
    ) {
        fake.received.forEachIndexed { index, message ->
            val gcm = message.signature_data?.AES_GCM_Personalized_data ?: return@forEachIndexed
            val domain = checkNotNull(message.to_destination?.domain)
            val counter = gcm.counter.toUInt()
            val before =
                ledger.writes
                    .lastOrNull { it.receivedBefore <= index }
                    ?.entries
                    ?.get(domain)
            assertNotNull(before, "request $index ($domain, counter $counter) went out before any cache write")
            assertEquals(gcm.epoch, before.epoch, "request $index went out before its epoch was stored")
            assertTrue(
                counter <= before.counter.toUInt(),
                "request $index used counter $counter, the cache held ${before.counter.toUInt()}",
            )
            ledger.writes.filter { it.receivedBefore > index }.forEach { later ->
                val entry = later.entries[domain] ?: return@forEach
                if (entry.epoch == gcm.epoch) {
                    assertTrue(
                        entry.counter.toUInt() >= counter,
                        "a later write lowered $domain to ${entry.counter.toUInt()} below $counter",
                    )
                }
            }
        }
    }

    private suspend fun VehicleSession.tap(domain: Domain): VehicleResult<*> =
        if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
            vcsec.execute(byteArrayOf(0x10, 0x01), AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT)
        } else {
            send.send(domain, "tap".encodeToByteArray(), AuthMethod.GCM)
        }

    @Test
    fun firstInfotainmentRequestAfterAnUncleanExitDoesNotReuseAnAcceptedCounter() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val (first, _) = process(fake, cache)
            first.connect()
            // 샘플의 패턴(F21 근거 2): 요청마다 먼저 startSession을 부르고, 그 성공이 캐시를 쓴다
            repeat(3) {
                assertIs<VehicleResult.Success<Unit>>(first.startSession(setOf(infotainment), timeout = 1.seconds))
                assertIs<VehicleResult.Success<*>>(first.send.send(infotainment, "tap".encodeToByteArray(), AuthMethod.GCM))
            }
            // 깨끗하지 않은 종료: disconnect()가 불리지 않는다(마지막 counter를 저장하는 지점이 없다)
            val acceptedBefore = fake.verifier(infotainment).counter
            val handshakesBefore = fake.sessionInfoRequests

            val (second, h2) = process(fake, cache)
            assertEquals(setOf(infotainment), second.connect())
            val receivedBefore = fake.received.size
            assertIs<VehicleResult.Success<*>>(second.send.send(infotainment, "tap".encodeToByteArray(), AuthMethod.GCM))

            // 차량은 인증 요청을 한 번만 받았고 받아들였다: 거부 + 재전송이 없다
            assertEquals(1, authenticated(fake, receivedBefore).size)
            assertTrue(fake.verifier(infotainment).counter > acceptedBefore)
            assertFalse(h2.logger.contains("Updated session info"), "no resync may be needed")
            // 캐시 복원의 목적(FR-017, NFR-011): 첫 요청 앞에 세션정보 요청(핸드셰이크)이 없다
            assertEquals(handshakesBefore, fake.sessionInfoRequests)
        }

    @Test
    fun firstVcsecCommandAfterAnUncleanExitDoesNotReuseAnAcceptedCounter() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val (first, _) = process(fake, cache)
            first.connect()
            repeat(2) {
                assertIs<VehicleResult.Success<Unit>>(first.startSession(setOf(vcsec), timeout = 1.seconds))
                assertIs<VehicleResult.Success<*>>(first.tap(vcsec))
            }
            val acceptedBefore = fake.verifier(vcsec).counter
            val handshakesBefore = fake.sessionInfoRequests

            val (second, h2) = process(fake, cache)
            assertTrue(vcsec in second.connect())
            val receivedBefore = fake.received.size
            assertIs<VehicleResult.Success<*>>(second.tap(vcsec))

            assertEquals(1, authenticated(fake, receivedBefore).size)
            assertTrue(fake.verifier(vcsec).counter > acceptedBefore)
            assertFalse(h2.logger.contains("Updated session info"), "no resync may be needed")
            assertEquals(handshakesBefore, fake.sessionInfoRequests)
        }

    @Test
    fun anyNumberOfRequestsBeforeAnUncleanExitLeavesTheNextProcessACleanStart() =
        // 예약 경계 앞뒤(1, R-1, R, R+1, 2R+1)에서, startSession 없이 연달아 보낸 뒤 끝나도 다음 프로세스의 첫 요청은 한 번에 받아들여진다
        runTest {
            for (count in listOf(1, reservation - 1, reservation, reservation + 1, 2 * reservation + 1)) {
                for (domain in listOf(vcsec, infotainment)) {
                    val fake = FakeVehicle(timeSource = testTimeSource)
                    val ledger = LedgerCache(fake)
                    val (first, _) = process(fake, ledger)
                    first.connect()
                    assertIs<VehicleResult.Success<Unit>>(first.startSession(setOf(domain), timeout = 1.seconds))
                    repeat(count) { assertIs<VehicleResult.Success<*>>(first.tap(domain), "count=$count $domain") }
                    val handshakesBefore = fake.sessionInfoRequests

                    val (second, _) = process(fake, ledger)
                    assertTrue(domain in second.connect())
                    val receivedBefore = fake.received.size
                    assertIs<VehicleResult.Success<*>>(second.tap(domain), "count=$count $domain")
                    assertEquals(1, authenticated(fake, receivedBefore).size, "count=$count $domain")
                    assertEquals(handshakesBefore, fake.sessionInfoRequests, "count=$count $domain")
                    assertEveryCounterWasStoredFirst(fake, ledger)
                }
            }
        }

    @Test
    fun oneReservationWriteCoversSixteenRequests() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, _) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            runCurrent() // 핸드셰이크의 Updated 사건이 남긴 저장까지 끝낸다
            val writesBefore = ledger.writes.size
            repeat(2 * reservation + 1) { assertIs<VehicleResult.Success<*>>(session.tap(infotainment)) }
            runCurrent()
            // 첫 요청, R+1번째, 2R+1번째 요청 앞에서만 쓴다
            assertEquals(3, ledger.writes.size - writesBefore)
            assertEveryCounterWasStoredFirst(fake, ledger)
        }

    @Test
    fun aRequestWhoseReplyWasLostBeforeAnUncleanExitIsNotReused() =
        // 차량은 받았고 응답만 사라진 요청(Uncertain): 그 counter도 캐시가 이미 덮었다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (first, _) = process(fake, ledger)
            first.connect()
            assertIs<VehicleResult.Success<Unit>>(first.startSession(setOf(vcsec), timeout = 1.seconds))
            fake.dropNextReplies(1)
            val lost =
                first.vcsec.execute(
                    byteArrayOf(0x10, 0x01),
                    AuthMethod.GCM,
                    VcsecResponses.COMMAND_STATUS_ABSENT,
                    timeout = 1.seconds,
                )
            assertEquals(VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)), lost)

            val (second, _) = process(fake, ledger)
            second.connect()
            val receivedBefore = fake.received.size
            assertIs<VehicleResult.Success<*>>(second.tap(vcsec))
            assertEquals(1, authenticated(fake, receivedBefore).size)
            assertEveryCounterWasStoredFirst(fake, ledger)
        }

    @Test
    fun laterStoresNeverWriteBelowTheReservation() =
        // Ready 저장(startSession), 세션정보 갱신 저장(Updated), 해제 저장 모두 확정한 예약보다 낮게 쓰지 않는다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, _) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            assertIs<VehicleResult.Success<*>>(session.tap(infotainment))
            assertIs<VehicleResult.Success<*>>(session.tap(vcsec))
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds)) // 준비된 도메인의 저장
            fake.attachSessionInfoOnce(infotainment)
            assertIs<VehicleResult.Success<*>>(session.tap(infotainment)) // 세션정보가 실린 응답 → Updated → 저장
            runCurrent()
            session.disconnect()
            assertTrue(ledger.writes.size >= 5)
            assertEveryCounterWasStoredFirst(fake, ledger)
            // 마지막(해제) 저장은 두 도메인 모두 지금까지 쓴 counter보다 앞서 있다
            val last = ledger.writes.last()
            assertTrue(checkNotNull(ledger.counter(last, infotainment)) > fake.verifier(infotainment).counter)
            assertTrue(checkNotNull(ledger.counter(last, vcsec)) > fake.verifier(vcsec).counter)
        }

    @Test
    fun anEpochChangeMakesTheNextRequestStoreTheNewEpochBeforeUsingIt() =
        // 차량 재부팅: INCORRECT_EPOCH + 세션정보 → 반영(epoch가 바뀌어 예약이 무효) → 다시 보내기 전에 새 epoch를 예약해 쓴다.
        // 이벤트 수집기가 없다: Updated 저장(비동기, 유실될 수 있음)이 아니라 예약 경로가 새 epoch를 먼저 써야 한다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, _) = process(fake, ledger, events = false)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            assertIs<VehicleResult.Success<*>>(session.tap(infotainment))
            fake.rotateEpoch(infotainment)
            assertIs<VehicleResult.Success<*>>(session.tap(infotainment))
            runCurrent()
            assertEveryCounterWasStoredFirst(fake, ledger)
            assertEquals(
                fake.epoch(infotainment).toList(),
                checkNotNull(ledger.writes.last().entries[infotainment])
                    .epoch
                    .toByteArray()
                    .toList(),
            )
        }

    @Test
    fun concurrentRequestsBeyondOneReservationAllUseStoredCounters() =
        // 예약 폭보다 많은 Infotainment 요청이 동시에(쓰기가 느린 동안) 나가도 모든 counter를 캐시가 먼저 덮는다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, _) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            ledger.slowStore = 10.milliseconds
            val results = List(2 * reservation + 2) { async { session.tap(infotainment) } }.awaitAll()
            assertTrue(results.all { it is VehicleResult.Success<*> }, "$results")
            assertEquals(2 * reservation + 2, fake.authenticatedPayloads(infotainment).size)
            assertEveryCounterWasStoredFirst(fake, ledger)
        }

    @Test
    fun aReservationUsedUpByOtherRequestsIsStoredAgainBeforeSending() =
        // Codex 리뷰(H17 1번): 예약 훅이 끝난 뒤 다른 요청들이 그 예약을 다 쓰면, 다시 예약해야 한다(예약 없이 보내면 H17이 남는다)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val h = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(
                HandshakeFlow(h.dispatcher).startSession(infotainment),
            )
            val sync = SessionCacheSync(ledger, fake.vin, keyId)
            val session = checkNotNull(h.dispatcher.session(infotainment))
            var calls = 0
            h.dispatcher.counterReservation =
                CounterReservation { domain ->
                    calls++
                    val covered = sync.reserve(h.dispatcher, domain)
                    if (calls == 1) {
                        // 다른 요청들이 방금 확정한 예약을 다 쓴 것처럼, 예약 안의 counter를 모두 소비한다
                        while (session.coversNextCounter()) {
                            assertIs<SignerResult.Ok<*>>(
                                session.authorizeNow(testCommand(infotainment), 5.seconds, requireReservation = true),
                            )
                        }
                    }
                    if (covered) CounterReservation.Outcome.RESERVED else CounterReservation.Outcome.NOT_RESERVED
                }
            val pending = h.dispatcher.send(testCommand(infotainment, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE), AuthMethod.GCM)
            assertIs<VehicleResult.Success<PendingRequest>>(pending).value.close()
            assertEquals(2, calls)
            assertFalse(h.logger.contains("does not cover"))
            assertEveryCounterWasStoredFirst(fake, ledger)
        }

    @Test
    fun whenTheReservationCannotBeStoredTheRequestGoesOutAsBeforeWithAWarning() =
        // 포트가 던지면(앱의 캐시 구현) 예약 없이 보낸다: 결과는 그대로, 다음 프로세스는 예전처럼 한 번 회복할 수 있다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, h) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            runCurrent()
            ledger.failStores = true
            assertIs<VehicleResult.Success<*>>(session.tap(infotainment))
            assertTrue(h.logger.contains("Failed to store the counter reservation for DOMAIN_INFOTAINMENT: IllegalStateException"))
            assertTrue(h.logger.contains("Sending on DOMAIN_INFOTAINMENT with a counter the session cache does not cover"))
        }

    @Test
    fun aTimeoutWhileStoringTheReservationSendsNothingAndUsesNoCounter() =
        // ADR-0010: 쓰기 전의 시간 초과는 Failure(Timeout(afterSend = false)) — 예약 저장은 쓰기 전이다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, h) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(vcsec), timeout = 1.seconds))
            runCurrent()
            val counterBefore = checkNotNull(h.dispatcher.session(vcsec)).counter()
            val receivedBefore = fake.received.size
            ledger.slowStore = 10.seconds
            val result =
                session.vcsec.execute(
                    byteArrayOf(0x10, 0x01),
                    AuthMethod.GCM,
                    VcsecResponses.COMMAND_STATUS_ABSENT,
                    timeout = 1.seconds,
                )
            assertEquals(VehicleResult.Failure(VehicleError.Timeout(afterSend = false)), result)
            assertEquals(receivedBefore, fake.received.size)
            assertEquals(counterBefore, checkNotNull(h.dispatcher.session(vcsec)).counter())
        }

    @Test
    fun aClosingConnectionRefusesTheRequestInsteadOfUsingAnUncoveredCounter() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val h = dispatcherHarness(fake = fake)
            assertIs<VehicleResult.Success<Unit>>(
                HandshakeFlow(h.dispatcher).startSession(vcsec),
            )
            val counterBefore = checkNotNull(h.dispatcher.session(vcsec)).counter()
            val receivedBefore = fake.received.size
            h.dispatcher.counterReservation = CounterReservation { CounterReservation.Outcome.CLOSING }
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), h.dispatcher.send(testCommand(vcsec), AuthMethod.GCM))
            assertEquals(receivedBefore, fake.received.size)
            assertEquals(counterBefore, checkNotNull(h.dispatcher.session(vcsec)).counter())
        }

    @Test
    fun aFailedWriteResendsTheSameBytesWithoutAnotherReservation() =
        // ADR-0010 (#149/#150): 쓰기 실패 뒤에는 같은 바이트를 다시 쓴다 — 다시 인가하지 않으니 counter도 예약도 더 쓰지 않는다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, h) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            runCurrent()
            val writesBefore = ledger.writes.size
            val counterBefore = checkNotNull(checkNotNull(h.dispatcher.session(infotainment)).counter())
            h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
            assertIs<VehicleResult.Success<*>>(session.tap(infotainment))
            assertEquals(writesBefore + 1, ledger.writes.size)
            assertEquals(counterBefore + 1u, checkNotNull(h.dispatcher.session(infotainment)).counter())
        }

    @Test
    fun unauthenticatedRequestsNeverStoreAReservation() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, _) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            runCurrent()
            val countersBefore =
                ledger.writes
                    .last()
                    .entries
                    .mapValues { it.value.counter }
            val writesBefore = ledger.writes.size
            session.info.vehicleStatus(timeout = 1.seconds)
            // 이 연결의 키로 물으면 응답의 세션정보를 반영해 Updated 저장이 한 번 생긴다(예약이 아니다: counter를 늘리지 않는다)
            session.keys.sessionInfo(TestCrypto.clientPublicKey, vcsec, timeout = 1.seconds)
            runCurrent()
            ledger.writes.drop(writesBefore).forEach { write ->
                assertEquals(countersBefore, write.entries.mapValues { it.value.counter })
            }
        }

    @Test
    fun countersAboveTheSignedIntRangeRoundTripThroughTheCache() =
        // SessionInfo.counter는 Wire에서 Int(uint32)다: 2^31을 넘는 예약도 그대로 저장·복원되고 차량이 받아들인다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val (first, h1) = process(fake, cache)
            first.connect()
            assertIs<VehicleResult.Success<Unit>>(first.startSession(setOf(infotainment), timeout = 1.seconds))
            val start = Int.MAX_VALUE.toUInt() - 3u
            seedCounter(cache, fake, infotainment, h1, start)

            val (second, h2) = process(fake, cache)
            assertEquals(setOf(infotainment), second.connect())
            assertIs<VehicleResult.Success<*>>(second.tap(infotainment))
            val stored = SessionInfo.ADAPTER.decode(cache.load(fake.vin, keyId).single().sessionInfo)
            assertEquals(start + SessionState.COUNTER_RESERVATION, stored.counter.toUInt())
            assertEquals(start + 1u, fake.verifier(infotainment).counter)
            assertFalse(h2.logger.contains("Updated session info"))
        }

    @Test
    fun aCachedEntryWithoutRoomForOneReservationIsNotRestored() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val cache = RecordingSessionCache()
            val (first, h1) = process(fake, cache)
            first.connect()
            assertIs<VehicleResult.Success<Unit>>(first.startSession(setOf(infotainment), timeout = 1.seconds))

            seedCounter(cache, fake, infotainment, h1, UInt.MAX_VALUE - SessionState.COUNTER_RESERVATION)
            val (restorable, _) = process(fake, cache)
            assertEquals(setOf(infotainment), restorable.connect())

            seedCounter(cache, fake, infotainment, h1, UInt.MAX_VALUE - SessionState.COUNTER_RESERVATION + 1u)
            val (exhausted, h3) = process(fake, cache)
            assertTrue(exhausted.connect().isEmpty())
            assertTrue(h3.logger.contains("invalid cache: DOMAIN_INFOTAINMENT: anti-replay counter exhausted"))
        }

    @Test
    fun anExhaustedCounterEndsWithNoSessionAndAHandshakeStartsAFreshSession() =
        // 0xFFFFFFFF까지 쓴 세션: 예약은 되감지 않고 멈추며, 다음 인가는 NoSession(재시도 없음). startSession이 새 Signer를 만든다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val ledger = LedgerCache(fake)
            val (session, h) = process(fake, ledger)
            session.connect()
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            // 살아 있는 세션을 마지막 counter 바로 앞에 둔다(복원 검사를 거치지 않는 경로, 같은 epoch)
            val info = fake.verifier(infotainment).sessionInfo().copy(counter = (UInt.MAX_VALUE - 1u).toInt())
            checkNotNull(h.dispatcher.session(infotainment)).loadFromCache(SessionInfo.ADAPTER.encode(info), Duration.ZERO)

            assertIs<VehicleResult.Success<*>>(session.tap(infotainment)) // 0xFFFFFFFF을 쓴다
            assertEquals(UInt.MAX_VALUE, ledger.counter(ledger.writes.last(), infotainment)) // 예약은 멈췄고 되감지 않았다
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), session.tap(infotainment))
            assertTrue(h.logger.contains("The anti-replay counter for DOMAIN_INFOTAINMENT is exhausted"))
            assertFalse(session.isReady(infotainment))

            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            assertIs<VehicleResult.Success<*>>(session.tap(infotainment))
            assertTrue(checkNotNull(checkNotNull(h.dispatcher.session(infotainment)).counter()) < UInt.MAX_VALUE)
        }

    /** [domain]의 캐시 항목을 [counter]만 바꿔 다시 심는다(지금 세션의 epoch·차량 공개키 그대로). */
    private suspend fun seedCounter(
        cache: RecordingSessionCache,
        fake: FakeVehicle,
        domain: Domain,
        h: DispatcherHarness,
        counter: UInt,
    ) {
        val exported = h.dispatcher.exportSessions().single { it.domain == domain }
        val info = SessionInfo.ADAPTER.decode(exported.sessionInfo).copy(counter = counter.toInt())
        cache.seed(fake.vin, keyId, listOf(SessionCacheCodec.Entry(domain, 0L, SessionInfo.ADAPTER.encode(info))))
    }
}
