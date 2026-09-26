package io.github.smallmiro.teslable.util

import io.github.smallmiro.teslable.InternalTeslableApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(InternalTeslableApi::class)
class HexTest {
    @Test
    fun roundTripsBytesThroughHex() {
        val bytes = byteArrayOf(0x00, 0x0a, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("000a7f80ff", bytes.toHex())
        assertContentEquals(bytes, "000a7f80ff".hexToBytes())
        assertContentEquals(bytes, "000A7F80FF".hexToBytes())
    }

    @Test
    fun rejectsOddLengthHex() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
    }
}
