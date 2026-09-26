package io.github.smallmiro.teslable.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SlidingWindowTest {
    private data class Case(
        val counter: UInt,
        val window: ULong,
        val newCounter: UInt,
        val expectedCounter: UInt,
        val expectedWindow: ULong,
        val expectedOk: Boolean,
    )

    private val base = (1uL shl 0) or (1uL shl 5)

    private val cases =
        listOf(
            Case(100u, base, 101u, 101u, 1uL or (1uL shl 1) or (1uL shl 6), true),
            Case(100u, base, 103u, 103u, (1uL shl 2) or (1uL shl 3) or (1uL shl 8), true),
            Case(100u, base, 500u, 500u, 0uL, true),
            Case(100u, base, 98u, 100u, (1uL shl 0) or (1uL shl 1) or (1uL shl 5), true),
            Case(100u, base, 99u, 100u, base, false),
            Case(100u, base, 3u, 100u, base, false),
            Case(100u, base, 100u, 100u, base, false),
        )

    @Test
    fun matchesGoWindowTable() {
        for (case in cases) {
            val result = updateSlidingWindow(case.counter, case.window, case.newCounter, size = 32)
            assertEquals(case.expectedCounter, result.counter, "counter for $case")
            assertEquals(case.expectedWindow, result.window, "window for $case")
            assertEquals(case.expectedOk, result.ok, "ok for $case")

            val window = SlidingWindow.restore(counter = case.counter, history = case.window)
            assertEquals(case.expectedOk, window.update(case.newCounter), "SlidingWindow.update for $case")
        }
    }

    @Test
    fun firstCounterIsAlwaysAccepted() {
        assertEquals(true, SlidingWindow().update(0u))
        assertEquals(true, SlidingWindow().update(4_000_000_000u))
    }

    @Test
    fun rejectsDuplicatesAndAcceptsLateArrivals() { // PoC 시퀀스
        val w = SlidingWindow()
        assertEquals(listOf(true, true, true, false, false, true), listOf(10u, 12u, 11u, 11u, 12u, 50u).map(w::update))
    }

    @Test
    fun acceptsAgeAtWindowEdgeRejectsBeyond() { // Review Focus 4: Go는 age > 32 만 거부
        assertEquals(true, updateSlidingWindow(100u, 0uL, 68u, size = 32).ok)
        assertEquals(false, updateSlidingWindow(100u, 0uL, 67u, size = 32).ok)
    }

    @Test
    fun shiftOfSixtyFourOrMoreClearsHistory() { // Go: uint64 << 64 == 0
        assertEquals(0uL, updateSlidingWindow(1u, ULong.MAX_VALUE, 200u, size = 32).window)
        assertEquals(1uL shl 63, updateSlidingWindow(1u, ULong.MAX_VALUE, 65u, size = 32).window)
    }

    @Test
    fun rejectsCounterWhoseAgeExceedsIntRange() { // Go window.go: age는 uint32라 2^31 이상이어도 age > windowSize로 거부
        assertFalse(updateSlidingWindow(4_000_000_000u, 0uL, 0u, size = 32).ok)
        assertFalse(updateSlidingWindow(UInt.MAX_VALUE, 0uL, 1u, size = 32).ok)
    }
}
