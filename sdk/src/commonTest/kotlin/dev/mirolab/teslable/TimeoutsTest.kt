package dev.mirolab.teslable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class TimeoutsTest {
    @Test
    fun secondsMakesADurationAndRejectsNonPositive() {
        assertEquals(2.5.seconds, Timeouts.seconds(2.5))
        assertFailsWith<IllegalArgumentException> { Timeouts.seconds(0.0) }
        assertFailsWith<IllegalArgumentException> { Timeouts.seconds(Double.NaN) }
        assertEquals(2500L, Timeouts.millisecondsOf(Timeouts.seconds(2.5)))
        assertEquals(-1L, Timeouts.millisecondsOf(null))
    }
}
