package io.github.smallmiro.teslable.testing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class FixedRandomTest {
    @Test
    fun returnsSequenceInOrderThenFails() {
        val random = FixedRandom(byteArrayOf(1, 2), byteArrayOf(3))
        assertContentEquals(byteArrayOf(1, 2), random.nextBytes(2))
        assertContentEquals(byteArrayOf(3), random.nextBytes(1))
        assertFailsWith<IllegalStateException> { random.nextBytes(1) }
    }

    @Test
    fun rejectsLengthMismatch() {
        val random = FixedRandom(byteArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { random.nextBytes(3) }
    }
}
