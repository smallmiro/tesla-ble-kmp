package io.github.smallmiro.teslable

import kotlin.test.Test
import kotlin.test.assertEquals

class TeslableDemoTest {
    @Test
    fun exposesVersionAndLocalName() {
        assertEquals("0.1.0-SNAPSHOT", TeslableDemo.VERSION)
        assertEquals("S1a87a5a75f3df858C", TeslableDemo.localNameFor("5YJS0000000000000"))
    }
}
