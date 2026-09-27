package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportState
import io.github.smallmiro.teslable.testing.FakeTransport
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [Dispatcher]의 start/stop/close 생명주기와 [VehicleError.NotConnected] — 수신 코루틴 교대·정지 경쟁 포함. */
@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class DispatcherLifecycleTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    /** 인증 없는 [testCommand] 전송이 실패해야 할 때 그 오류. */
    private suspend fun DispatcherHarness.unauthenticatedSendError(): VehicleError =
        assertIs<VehicleResult.Failure>(dispatcher.send(testCommand(), AuthMethod.NONE)).error

    /**
     * 수신을 시작한 하네스지만 디스패처는 [GatedIncomingTransport]로 감싼 전송을 받는다: 수신 코루틴이 메시지를 하나 받으면
     * [gate]가 열릴 때까지 `process()` 앞에서(어떤 뮤텍스도 잡지 않은 채) 멈춘다. [DispatcherHarness.transport]는 감싸기 전의
     * [FakeTransport]다. [deliverAfterCancel]은 [GatedIncomingTransport] 참고.
     */
    private fun TestScope.gatedHarness(
        gate: CompletableDeferred<Unit>,
        deliverAfterCancel: Boolean = false,
    ): DispatcherHarness {
        val fake = FakeVehicle(timeSource = testTimeSource)
        val transport = fake.transport()
        val logger = RecordingLogger()
        val dispatcher =
            Dispatcher(
                GatedIncomingTransport(transport, gate, deliverAfterCancel),
                TestCrypto.clientKey(),
                TestCrypto.primitives,
                TestCrypto.random,
                backgroundScope,
                testTimeSource,
                logger,
            )
        dispatcher.start()
        return DispatcherHarness(fake, transport, dispatcher, logger)
    }

    @Test
    fun sendBeforeStartOrAfterStopReturnsNotConnected() =
        // dispatcher_test.go TestStopDispatcher
        runTest {
            val h = dispatcherHarness(start = false)
            val none = AuthMethod.NONE
            assertEquals(VehicleError.NotConnected, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), none)).error)
            h.dispatcher.start()
            assertTrue(h.dispatcher.isListening)
            h.dispatcher.start() // 멱등
            h.dispatcher.stop()
            assertFalse(h.dispatcher.isListening)
            assertEquals(VehicleError.NotConnected, assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), none)).error)
        }

    @Test
    fun stopRacingWithStartDoesNotOrphanTheReplacementCollector() =
        // Review round 1, Important 1: stop() used to suspend in cancelAndJoin() and only clear
        // receiveJob afterwards, so a start() that raced in during that suspension got its fresh job
        // silently wiped out by stop()'s post-suspend `receiveJob = null` — an orphaned, unstoppable
        // collector (isListening reports false while the job is still actually running). Go serializes
        // Start/Stop under doneLock (dispatcher.go:333-341, 369-375, 380-382); here a lifecycle Mutex
        // captures-and-clears the job before suspending in cancelAndJoin(), so a start() racing into the
        // window left by the (already-released) lock launches a replacement that survives stop()'s
        // cleanup of the OLD job. Both racers are launched before a single advanceUntilIdle() so the
        // test dispatcher interleaves them deterministically (stop()'s cancel() + suspend in join(),
        // then start() finding no assigned receiver and launching the replacement, which waits for the
        // old collector before subscribing). advanceUntilIdle() stops once only backgroundScope work
        // remains scheduled (documented, deterministic — see TestScope.backgroundScope), and the
        // collectors live in backgroundScope, so the old collector's unwinding — and with it the first
        // stop()'s join() and the replacement's subscription — is still pending when it returns; the
        // runCurrent() below resolves it. That resolution is exactly where the old code's post-suspend
        // `receiveJob = null` used to silently orphan the replacement collector — it kept running (it
        // still delivers the response) while isListening wrongly reported false.
        // Review round 4 (corrects round 3's A2): before that runCurrent() the stop is still in flight and
        // the replacement has not subscribed, so isListening must be false there (Go's Send returns
        // ErrNotConnected while Stop holds doneLock); round 3 asserted true at that point, which only held
        // because isListening was still reporting the cancelled old collector.
        runTest {
            val h = dispatcherHarness()
            assertTrue(h.dispatcher.isListening)
            launch { h.dispatcher.stop() } // will cancel+join the original collector
            launch { h.dispatcher.start() } // races in while the first stop() is suspended in cancelAndJoin()
            advanceUntilIdle()
            assertFalse(h.dispatcher.isListening) // stop in flight, replacement not subscribed yet: nobody can receive
            runCurrent() // the old collector finishes unwinding; stop() returns; the replacement subscribes
            assertTrue(h.dispatcher.isListening) // the replacement collector must survive stop()'s cleanup

            // The replacement collector must actually be the one processing messages (not a stale flag).
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            pending.use { assertNotNull(it.receive()) }
            assertTrue(h.dispatcher.isListening) // must still be tracked, not orphaned by the racing stop()'s cleanup

            h.dispatcher.stop()
            // Not just isListening (which is trivially false whenever receiveJob is null, orphaned or
            // not) — check backgroundScope directly for a leaked, still-running collector.
            val backgroundJob = checkNotNull(backgroundScope.coroutineContext[Job])
            assertFalse(h.dispatcher.isListening)
            assertTrue(backgroundJob.children.all { it.isCompleted }) // no leaked collector, not just a cleared flag
        }

    @Test
    fun closeWaitsForTheCollectorEvenWhenAnotherStopRacesIn() =
        // Review round 2, Important N1 (regression in the round 1 fix above): only the first stop()
        // call captured receiveJob into a local before clearing the field; a concurrent stop() —
        // including the one close() calls internally — captured null and returned immediately without
        // waiting, so close() could zeroize sessions and close the transport while the cancelled
        // collector was still finishing inside process() (a real hazard on a multi-threaded scope). Go's
        // Stop holds doneLock across <-d.done (dispatcher.go:369-375): every stopper must join the same
        // job. `stopping` remembers the job currently being stopped so a second stop() (here, close()'s)
        // falls back to it instead of capturing null.
        runTest {
            val h = dispatcherHarness()
            val backgroundJob = checkNotNull(backgroundScope.coroutineContext[Job])
            // Job.isActive turns false as soon as cancel() runs, well before the coroutine actually
            // finishes unwinding — so the only reliable "truly done" signal is isCompleted, checked at
            // the exact moment close() returns (not after a later drain, by when everything has long
            // since settled either way).
            var collectorWasDoneWhenCloseReturned = false
            launch { h.dispatcher.stop() }
            launch {
                h.dispatcher.close()
                collectorWasDoneWhenCloseReturned = backgroundJob.children.all { it.isCompleted }
            }
            runCurrent()
            assertFalse(h.dispatcher.isListening)
            assertTrue(collectorWasDoneWhenCloseReturned) // close() must not return before the collector fully finished
        }

    @Test
    fun startWhileAnEarlierStopIsStillJoiningDoesNotCreateASecondLiveCollector() =
        // Review round 2, Important N1 (controller ruling), made provable in round 3: a start() racing
        // in while an earlier stop() is still joining must not create a second live collector alongside
        // the one being stopped — Go's listen() and Stop() share doneLock, so a concurrent Start() would
        // block until Stop() releases it. `children.count { it.isActive } <= 1` cannot fail: Job.isActive
        // turns false the instant cancel() runs, long before the coroutine actually finishes (see
        // closeWaitsForTheCollectorEvenWhenAnotherStopRacesIn) — so a cancelled-but-still-running old
        // collector never shows up as "active" even when the bug is present. This version holds the
        // original collector stuck *inside* process() with a gate its own cancellation cannot get it out
        // of (StuckEcdhKey), and counts "Starting dispatcher service..." log lines instead of job
        // liveness: that log line only fires once a collector actually subscribes to transport.incoming.
        runTest {
            val gate = CompletableDeferred<Unit>()
            val h = dispatcherHarness(privateKey = StuckEcdhKey(TestCrypto.clientKey(), gate))
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            runCurrent() // collector A now stuck inside process() -> checkForSessionUpdate -> sharedX -> gate.await()
            launch { h.dispatcher.stop() } // cancels A; A cannot actually finish until the gate opens
            runCurrent()
            h.dispatcher.start() // B: must wait for A to finish before subscribing
            runCurrent()
            assertEquals(1, h.logger.count("Starting dispatcher service...")) // B has not subscribed yet
            gate.complete(Unit)
            runCurrent()
            assertEquals(2, h.logger.count("Starting dispatcher service...")) // A finally unwound; B subscribed after
            pending.close()
        }

    @Test
    fun closeDoesNotReturnBeforeTheOriginalCollectorFinishes() =
        // Review round 3, Important N1 (remaining regression): B, started during a stop, used to wait
        // with a *cancellable* awaitedStop?.join(). A later stop() (here, the one close() calls
        // internally) then captured receiveJob == B instead of the true original collector A, and —
        // because B's join(A) was cancellable — cancelling B let it complete immediately without ever
        // actually waiting for A. close() then zeroized sessions and closed the transport while A was
        // still stuck delivering its message. Fixed by wrapping the pre-subscribe join in
        // `withContext(NonCancellable) { awaitedStop.join() }` + `ensureActive()`: every stopper's wait
        // now transitively chains back to A no matter how many stop()/start() generations sit in
        // between. Gates the *transport's* incoming flow (not the crypto key, as the sibling test
        // below does) so A is stuck upstream of process() entirely — a crypto-key gate inside
        // checkForSessionUpdate holds the target SessionState's mutex for as long as it's stuck, which
        // then independently forces close()'s session.close() call to block on that same mutex and
        // masks this exact bug. Adapted from the reviewer's scratch repro
        // (ReviewReproTest2.reproCloseReturnsBeforeCollectorCompletes).
        runTest {
            val gate = CompletableDeferred<Unit>()
            val logger = RecordingLogger()
            val fake = FakeVehicle(timeSource = testTimeSource)
            val dispatcher =
                Dispatcher(
                    GatedIncomingTransport(fake.transport(), gate),
                    TestCrypto.clientKey(),
                    TestCrypto.primitives,
                    TestCrypto.random,
                    backgroundScope,
                    testTimeSource,
                    logger,
                )
            dispatcher.start()
            val bg = checkNotNull(backgroundScope.coroutineContext[Job])
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(dispatcher.requestSessionInfo(vcsec)).value
            runCurrent()
            val a = bg.children.single() // collector A, now stuck waiting for the gate before it can even emit the reply
            launch { dispatcher.stop() } // stop1: cancels A, joins A (stays suspended: A is stuck)
            runCurrent()
            dispatcher.start() // B: must wait for A (the fix under test)
            var closeReturned = false
            launch {
                dispatcher.close()
                closeReturned = true
            }
            runCurrent()
            assertFalse(a.isCompleted) // the gate still holds A, so the check below is not vacuous
            assertFalse(closeReturned, "close() returned while the original collector was still trying to deliver its message")
            gate.complete(Unit)
            runCurrent()
            pending.close()
            assertTrue(closeReturned) // once A has finished, close() does return
        }

    @Test
    fun noSessionKeysSurviveCloseWhenTheOriginalCollectorProcessesAHandshakeReplyLate() =
        // Review round 4 (minor fold): the security-relevant symptom of the round 3 join-chain bug fixed in a846ed1.
        // stop() -> start() -> close() while the original collector A holds a VCSEC session-info reply it has already
        // received. With the old cancellable pre-subscribe join, close() returned — zeroizing every session — while A
        // still held that reply; A then went on to process() it and re-derived VCSEC session keys *after* close() had
        // returned, leaving live keys in a closed dispatcher. Now close() waits (through B's uncancellable join) until A
        // is done, so A's late handshake lands first and close() zeroizes it. The gate uses deliverAfterCancel: the
        // flow {} builder's emit would throw on the cancelled A and drop the reply, hiding the symptom. Adapted from the
        // reviewer's scratch repro (ReviewReproTest2.reproSessionEstablishedAfterClose).
        runTest {
            val gate = CompletableDeferred<Unit>()
            val h = gatedHarness(gate, deliverAfterCancel = true)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            runCurrent() // A holds the VCSEC session-info reply at the gate
            launch { h.dispatcher.stop() } // cancels A, joins it
            runCurrent()
            h.dispatcher.start() // B: waits for A
            var closeReturned = false
            launch {
                h.dispatcher.close()
                closeReturned = true
            }
            runCurrent()
            gate.complete(Unit) // the cancelled A now processes the reply it was holding
            runCurrent()
            pending.close()
            assertTrue(h.logger.contains("Updated session info for DOMAIN_VEHICLE_SECURITY")) // A did derive keys, late
            assertTrue(closeReturned)
            assertNull(assertNotNull(h.dispatcher.session(vcsec)).export()) // ...and none survive close()
        }

    @Test
    fun laterStartDoesNotSubscribeWhileTheOriginalCollectorIsStillRunning() =
        // Review round 3, Important N1 (remaining regression), second scenario:
        // stop() -> start() -> stop() -> start(). With a cancellable pre-subscribe join, the second
        // stop() cancels B; B's join(A) is interrupted immediately (not waiting for A), so B completes,
        // `stopping` is cleared, and the next start() (C) sees `stopping == null` and subscribes right
        // away — "Starting dispatcher service..." logs twice while A is still alive (two receive
        // coroutines on the same transport.incoming). The NonCancellable join fix makes B's own
        // cancellation unable to cut its wait for A short, so C's wait (via B) still transitively
        // reaches A. Adapted from the reviewer's scratch repro
        // (ReviewReproTest.reproSecondStartSubscribesWhileOldCollectorStillRunning).
        runTest {
            val gate = CompletableDeferred<Unit>()
            val h = dispatcherHarness(privateKey = StuckEcdhKey(TestCrypto.clientKey(), gate))
            val bg = checkNotNull(backgroundScope.coroutineContext[Job])
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            runCurrent()
            val a = bg.children.single()
            launch { h.dispatcher.stop() }
            runCurrent()
            h.dispatcher.start() // B waits for A
            launch { h.dispatcher.stop() } // cancels B
            runCurrent()
            h.dispatcher.start() // C
            runCurrent()
            // Two separate checks: combined into one boolean, the assertion would pass vacuously if the gate ever
            // stopped holding A.
            assertFalse(a.isCompleted) // the gate still holds A inside process()
            assertEquals(1, h.logger.count("Starting dispatcher service...")) // no new collector subscribed while A is alive
            gate.complete(Unit)
            runCurrent()
            pending.close()
            assertEquals(2, h.logger.count("Starting dispatcher service...")) // A finished; only then did C subscribe
            assertTrue(h.dispatcher.isListening)
        }

    @Test
    fun closeStillZeroizesSessionsAndClosesTransportWhenTheCallerIsCancelledMidJoin() =
        // Review round 3, Important A1: if the close() caller is cancelled while stop() is suspended in
        // job.join(), the CancellationException used to propagate straight out of close() and skip
        // session zeroization + transport.close() entirely — session keys could be left live in memory.
        // close() now wraps that cleanup in `finally { withContext(NonCancellable) { ... } }`, so it
        // always runs regardless of the caller's own cancellation.
        runTest {
            var blocking = false
            val gate = CompletableDeferred<Unit>()
            val key = HookedEcdhKey(TestCrypto.clientKey()) { if (blocking) withContext(NonCancellable) { gate.await() } }
            val h = dispatcherHarness(privateKey = key)
            h.manualHandshake(infotainment) // establish a real, ready session so we can later prove it got zeroized
            blocking = true
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            runCurrent() // collector stuck inside VCSEC's handshake -> sharedX -> gate.await()
            val closeJob = launch { h.dispatcher.close() }
            runCurrent() // close() -> stop() -> cancelAndJoin(the stuck collector): genuinely suspended
            closeJob.cancel() // cancel the close() CALLER, not the collector
            runCurrent()
            gate.complete(Unit) // let the stuck collector finish unwinding (test hygiene)
            runCurrent()
            pending.close()
            assertNull(assertNotNull(h.dispatcher.session(infotainment)).export()) // signer zeroized despite the cancellation
            assertIs<TransportState.Disconnected>(h.transport.state.value)
        }

    @Test
    fun isListeningIsFalseAndSendReturnsNotConnectedWhileAStopIsInFlight() =
        // Review round 4, Important (corrects round 3's A2): isListening read `activeJob != null`, and activeJob is
        // cleared only in the collector's own finally — so while stop() was still joining a cancelled collector that had
        // not finished unwinding, isListening stayed true and send() transmitted to the car. Go's Stop sets
        // `terminate = nil` under doneLock *before* it waits on <-d.done (dispatcher.go:369-375), and Send reads
        // `terminate` under that same lock (dispatcher.go:380-385), so Send returns ErrNotConnected for the whole stop.
        runTest {
            val gate = CompletableDeferred<Unit>()
            val h = gatedHarness(gate)
            val bg = checkNotNull(backgroundScope.coroutineContext[Job])
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            runCurrent() // collector A received the reply and is held at the gate, upstream of process()
            val a = bg.children.single()
            launch { h.dispatcher.stop() } // cancels A, then joins it: A cannot finish until the gate opens
            runCurrent()
            assertFalse(a.isCompleted) // the stop is genuinely still in flight
            assertFalse(h.dispatcher.isListening)
            assertEquals(VehicleError.NotConnected, h.unauthenticatedSendError())
            gate.complete(Unit)
            runCurrent()
            pending.close()
            assertTrue(a.isCompleted)
            assertFalse(h.dispatcher.isListening)
        }

    @Test
    fun isListeningStaysFalseUntilAReplacementStartedDuringAStopHasSubscribed() =
        // Review round 4, Important (corrects round 3's A2), second window: a start() during that stop launches the
        // replacement B, which waits (uncancellably) for A before it subscribes. Until then no collector can receive —
        // A is cancelled and B has not subscribed — so isListening must stay false and send() must return NotConnected,
        // as in Go, where the new listen() blocks on doneLock until Stop() has drained <-d.done and only then sets
        // `terminate` (dispatcher.go:331-341). Once the gate opens, A unwinds, B subscribes, and sending works through B.
        runTest {
            val gate = CompletableDeferred<Unit>()
            val h = gatedHarness(gate)
            val bg = checkNotNull(backgroundScope.coroutineContext[Job])
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.requestSessionInfo(vcsec)).value
            runCurrent()
            val a = bg.children.single()
            launch { h.dispatcher.stop() }
            runCurrent()
            h.dispatcher.start() // B: waits for A before subscribing
            runCurrent()
            assertFalse(a.isCompleted)
            assertEquals(1, h.logger.count("Starting dispatcher service...")) // B has not subscribed yet
            assertFalse(h.dispatcher.isListening)
            assertEquals(VehicleError.NotConnected, h.unauthenticatedSendError())
            gate.complete(Unit)
            runCurrent()
            pending.close()
            assertEquals(2, h.logger.count("Starting dispatcher service...")) // A finished; B subscribed after it
            assertTrue(h.dispatcher.isListening)
            val sent = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            sent.use { assertNotNull(it.receive()) } // B is the collector actually receiving
        }

    @Test
    fun sendReturnsNotConnectedAfterIncomingCompletes() =
        // Review Focus 2: 전송이 끝나면(BLE 끊김) 수신 루프가 조용히 종료되고 이후 send는 NotConnected.
        // Review round 1, Important 2: 전송이 끝나기 전에 보낸 요청은 Go도 리시버 채널을 닫지 않으므로(그런 훅이
        // 없다) 응답을 받지 못한 채 호출자가 스스로 시간 초과할 때까지 계속 기다린다; 그 요청을 닫으면 등록은
        // 정상적으로 풀린다.
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList()) // 응답 없음: 전송이 끊길 때까지 아무것도 오지 않는다
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).value
            h.transport.close()
            runCurrent()
            assertFalse(h.dispatcher.isListening)
            assertNull(withTimeoutOrNull(1.seconds) { pending.receive() }) // 채널이 닫히지 않아 시간 초과로 끝난다
            pending.close()
            assertEquals(0, h.dispatcher.pendingCount()) // 닫으면 등록이 풀린다
            assertEquals(
                VehicleError.NotConnected,
                assertIs<VehicleResult.Failure>(h.dispatcher.send(testCommand(), AuthMethod.NONE)).error,
            )
        }
}

/**
 * [EcdhPrivateKey]를 감싸 [sharedX]가 호출될 때마다 [onSharedX]를 먼저 부른다 — 테스트 전용. [StuckEcdhKey]와 달리
 * 매번 다르게 동작하도록(예: 첫 호출은 그냥 지나가고 이후 호출만 막도록) 호출자가 훅을 직접 제어할 수 있다.
 */
private class HookedEcdhKey(
    private val delegate: EcdhPrivateKey,
    private val onSharedX: suspend () -> Unit,
) : EcdhPrivateKey {
    override val publicKey: PublicKeyBytes get() = delegate.publicKey

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray {
        onSharedX()
        return delegate.sharedX(peer)
    }
}

/**
 * [Transport]를 감싸 [incoming]의 각 항목을 [gate]가 열릴 때까지 넘기지 않는다 — 테스트 전용. 크립토 키를 막는
 * [StuckEcdhKey]와 달리 `process()`에 들어가기도 전에(따라서 어떤 [SessionState] 뮤텍스도 잡지 않은 채) 수신
 * 코루틴을 멈춰 세운다 — `close()`가 세션을 지우려다 그 뮤텍스에서 우연히 막혀서(리뷰 라운드 3 N1 회귀와는
 * 무관하게) 이 시나리오를 가려 버리는 것을 피한다.
 *
 * 기본은 `flow {}` 빌더라, 게이트가 열렸을 때 수신 코루틴이 이미 취소됐으면 `emit`이 `CancellationException`을 던지고 그
 * 항목은 버려진다. [deliverAfterCancel]이면 [Flow]를 직접 구현해 그 검사 없이 넘긴다 — 이미 손에 쥔 메시지(예: 조각을 다
 * 모은 뒤)를 취소와 무관하게 넘기는 전송을 흉내 내어, 취소된 수신 코루틴이 그 메시지를 `process()`까지 가져가게 한다.
 */
private class GatedIncomingTransport(
    private val delegate: Transport,
    private val gate: CompletableDeferred<Unit>,
    deliverAfterCancel: Boolean = false,
) : Transport by delegate {
    override val incoming: Flow<ByteArray> =
        if (deliverAfterCancel) {
            object : Flow<ByteArray> {
                override suspend fun collect(collector: FlowCollector<ByteArray>) {
                    delegate.incoming.collect { bytes ->
                        withContext(NonCancellable) { gate.await() }
                        collector.emit(bytes)
                    }
                }
            }
        } else {
            flow {
                delegate.incoming.collect { bytes ->
                    withContext(NonCancellable) { gate.await() }
                    emit(bytes)
                }
            }
        }
}
