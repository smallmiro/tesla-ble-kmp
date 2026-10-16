package dev.mirolab.teslable.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class VinTest {
    @Test
    fun acceptsSeventeenCharacterVinAndUppercases() {
        val vin = Vin("5yj30123456789abc")
        assertEquals("5YJ30123456789ABC", vin.value)
        assertEquals(17, vin.toByteArray().size)
    }

    @Test
    fun masksVinInToString() {
        assertEquals("5YJ**********9ABC", Vin("5YJ30123456789ABC").toString())
    }

    @Test
    fun rejectsWrongLengthAndForbiddenLetters() {
        assertFailsWith<IllegalArgumentException> { Vin("5YJ3012345678") }
        assertFailsWith<IllegalArgumentException> { Vin("5YJ30123456789ABI") } // I, O, Q 금지
    }

    @Test
    fun parseOrNullReturnsNullInsteadOfThrowing() {
        assertEquals(Vin("5YJ30123456789ABC"), Vin.parseOrNull("5yj30123456789abc"))
        assertNull(Vin.parseOrNull("5YJ3012345678")) // 짧음
        assertNull(Vin.parseOrNull("5YJ3012345678OABC")) // O는 VIN 문자가 아님
    }
}
