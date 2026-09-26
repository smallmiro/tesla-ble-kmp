package io.github.smallmiro.teslable.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
