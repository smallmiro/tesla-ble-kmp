package dev.mirolab.teslable.sample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** 차 없이 상태 기계만 시험한다. 차량 `V`는 문자열로 대신한다. 시간은 가상 시간이다. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLoopTest {
    private class Fixture(
        scope: CoroutineScope,
        timeSource: kotlin.time.TimeSource,
        /** 연결 시도마다 꺼내 쓰는 결과. 비면 계속 실패한다. */
        private val outcomes: ArrayDeque<ConnectOutcome<String>> = ArrayDeque(),
    ) {
        val transitions = mutableListOf<LinkState<String>>()
        val connectCalls = mutableListOf<Long>()
        val released = mutableListOf<String>()
        val losses = Channel<String?>(Channel.UNLIMITED)
        private var now: () -> Long = { 0L }

        val loop =
            ConnectionLoop<String>(
                scope = scope,
                connect = {
                    connectCalls += now()
                    outcomes.removeFirstOrNull() ?: ConnectOutcome.Failed("no car")
                },
                awaitLoss = { losses.receive() },
                release = { released += it },
                clock = timeSource,
                onTransition = { transitions += it },
            )

        fun clockFrom(testScope: TestScope) {
            now = { testScope.testScheduler.currentTime }
        }

        val backoffs get() = transitions.filterIsInstance<LinkState.Backoff>()
    }

    private fun TestScope.fixture(vararg outcomes: ConnectOutcome<String>) =
        Fixture(backgroundScope, testScheduler.timeSource, ArrayDeque(outcomes.toList())).also { it.clockFrom(this) }

    @Test
    fun backoffDoublesTo30s() =
        runTest {
            val f = fixture() // 계속 실패
            f.loop.start()
            advanceTimeBy(61.seconds)
            runCurrent()

            assertEquals(listOf(2.seconds, 4.seconds, 8.seconds, 16.seconds, 30.seconds, 30.seconds), f.backoffs.take(6).map { it.delay })
            assertEquals(listOf(1, 2, 3, 4, 5, 6), f.backoffs.take(6).map { it.attempt })
            assertEquals("no car", f.backoffs.first().reason)
            // 연결 시도는 0, 2, 6, 14, 30, 60초에 일어난다
            assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L, 30_000L, 60_000L), f.connectCalls)
        }

    @Test
    fun startsIdleAndConnectingAttemptsCountUp() =
        runTest {
            val f = fixture()
            assertEquals(LinkState.Idle, f.loop.state.value)
            f.loop.start()
            runCurrent()
            assertEquals(listOf<LinkState<String>>(LinkState.Connecting(1), f.backoffs.first()), f.transitions)
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(LinkState.Connecting(2), f.transitions[2])
        }

    @Test
    fun reconnectsAfterLinkLoss() =
        runTest {
            val f = fixture(ConnectOutcome.Connected("car1"), ConnectOutcome.Connected("car2"))
            f.loop.start()
            runCurrent()
            assertEquals(LinkState.Connected("car1"), f.loop.state.value)

            f.losses.send("link lost")
            runCurrent()

            // 끊긴 차량은 반드시 release(= Vehicle.disconnect()) 한다
            assertEquals(listOf("car1"), f.released)
            val backoff = assertIs<LinkState.Backoff>(f.loop.state.value)
            assertEquals(2.seconds, backoff.delay)
            assertEquals("link lost", backoff.reason)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertIs<LinkState.Backoff>(f.loop.state.value)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(LinkState.Connected("car2"), f.loop.state.value)
            assertEquals(2, f.connectCalls.size)
        }

    @Test
    fun stableConnectionResetsBackoff() =
        runTest {
            val f = fixture(ConnectOutcome.Failed("a"), ConnectOutcome.Failed("b"), ConnectOutcome.Failed("c"), ConnectOutcome.Connected("car1"))
            f.loop.start()
            advanceTimeBy(2.seconds + 4.seconds + 8.seconds)
            runCurrent()
            assertEquals(LinkState.Connected("car1"), f.loop.state.value)

            advanceTimeBy(1.minutes) // 10초 넘게 안정적으로 연결돼 있었다
            f.losses.send(null)
            runCurrent()

            assertEquals(2.seconds, f.backoffs.last().delay)
            assertEquals(1, f.backoffs.last().attempt)
        }

    @Test
    fun quickLossKeepsBackoffGrowing() =
        runTest {
            val f = fixture(ConnectOutcome.Failed("a"), ConnectOutcome.Connected("car1"))
            f.loop.start()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(LinkState.Connected("car1"), f.loop.state.value)

            advanceTimeBy(1.seconds) // 1초 만에 다시 끊김: 안정적이지 않다
            f.losses.send("flap")
            runCurrent()

            assertEquals(4.seconds, f.backoffs.last().delay)
            assertEquals(2, f.backoffs.last().attempt)
        }

    @Test
    fun manualDisconnectStopsReconnect() =
        runTest {
            val f = fixture(ConnectOutcome.Connected("car1"))
            f.loop.start()
            runCurrent()

            f.loop.stop()
            runCurrent()

            assertEquals(LinkState.Stopped, f.loop.state.value)
            assertEquals(listOf("car1"), f.released)
            // 5분이 지나도 다시 연결하지 않는다
            val calls = f.connectCalls.size
            advanceTimeBy(5.minutes)
            runCurrent()
            assertEquals(calls, f.connectCalls.size)
            assertEquals(LinkState.Stopped, f.loop.state.value)
        }

    @Test
    fun manualDisconnectDuringBackoffStopsReconnect() =
        runTest {
            val f = fixture()
            f.loop.start()
            runCurrent()
            assertIs<LinkState.Backoff>(f.loop.state.value)

            f.loop.stop()
            advanceTimeBy(5.minutes)
            runCurrent()

            assertEquals(1, f.connectCalls.size)
            assertEquals(LinkState.Stopped, f.loop.state.value)
        }

    @Test
    fun manualConnectSkipsBackoff() =
        runTest {
            val f = fixture(ConnectOutcome.Failed("a"), ConnectOutcome.Failed("b"), ConnectOutcome.Failed("c"), ConnectOutcome.Failed("d"), ConnectOutcome.Failed("e"))
            f.loop.start()
            advanceTimeBy(2.seconds + 4.seconds + 8.seconds + 16.seconds + 1.seconds)
            runCurrent()
            assertEquals(30.seconds, f.backoffs.last().delay)
            val before = f.connectCalls.size
            val at = testScheduler.currentTime

            f.loop.start() // 연결 버튼
            runCurrent()

            assertEquals(before + 1, f.connectCalls.size)
            assertEquals(at, f.connectCalls.last()) // 기다리지 않고 바로 시도했다
        }

    @Test
    fun connectAfterStopStartsFreshWithShortBackoff() =
        runTest {
            val f = fixture()
            f.loop.start()
            advanceTimeBy(7.seconds)
            runCurrent()
            assertTrue(f.backoffs.last().delay >= 8.seconds)

            f.loop.stop()
            f.loop.start()
            runCurrent()

            assertEquals(2.seconds, f.backoffs.last().delay)
            assertEquals(1, f.backoffs.last().attempt)
        }

    @Test
    fun connectDuringStopKeepsTheNewLoop() =
        runTest {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            var calls = 0
            val loop =
                ConnectionLoop<String>(
                    scope = backgroundScope,
                    connect = { ConnectOutcome.Connected("car${++calls}") },
                    awaitLoss = { Channel<String?>().receive() },
                    release = { gate.await() }, // 정리가 끝나기 전에 사용자가 연결을 다시 누른 상황
                    clock = testScheduler.timeSource,
                )
            loop.start()
            runCurrent()
            val stopping = launch { loop.stop() }
            runCurrent()

            loop.start()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            stopping.join()

            assertEquals(2, calls)
            assertEquals(LinkState.Connected("car2"), loop.state.value)
        }

    @Test
    fun startWhileConnectedDoesNothing() =
        runTest {
            val f = fixture(ConnectOutcome.Connected("car1"))
            f.loop.start()
            runCurrent()

            f.loop.start()
            runCurrent()

            assertEquals(1, f.connectCalls.size)
            assertEquals(LinkState.Connected("car1"), f.loop.state.value)
        }

    @Test
    fun aThrowingConnectBecomesAFailureAndTheLoopGoesOn() =
        runTest {
            var calls = 0
            val states = mutableListOf<LinkState<String>>()
            val loop =
                ConnectionLoop<String>(
                    scope = backgroundScope,
                    connect = {
                        calls++
                        if (calls == 1) error("boom") else ConnectOutcome.Connected("car")
                    },
                    awaitLoss = { Channel<String?>().receive() },
                    release = {},
                    clock = testScheduler.timeSource,
                    onTransition = { states += it },
                )
            loop.start()
            advanceTimeBy(2.seconds)
            runCurrent()

            assertEquals("boom", (states.filterIsInstance<LinkState.Backoff>().first()).reason)
            assertEquals(LinkState.Connected("car"), loop.state.value)
        }
}
