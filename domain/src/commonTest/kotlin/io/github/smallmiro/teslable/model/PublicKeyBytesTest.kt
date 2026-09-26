package io.github.smallmiro.teslable.model

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(InternalTeslableApi::class)
class PublicKeyBytesTest {
    // protocol.md client.pem (03-protocol.md §6.2)
    private val clientPub =
        "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
            "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e"

    @Test
    fun acceptsUncompressed65BytePoint() {
        val key = PublicKeyBytes(clientPub.hexToBytes())
        assertEquals(65, key.toByteArray().size)
        assertEquals("b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7", key.x.toHex())
        assertEquals("14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e", key.y.toHex())
    }

    @Test
    fun rejectsWrongLengthOrPrefix() {
        assertFailsWith<IllegalArgumentException> { PublicKeyBytes(ByteArray(64)) }
        assertFailsWith<IllegalArgumentException> { PublicKeyBytes(ByteArray(65)) } // prefix 0x00
    }

    @Test
    fun copiesInputDefensively() {
        val raw = clientPub.hexToBytes()
        val key = PublicKeyBytes(raw)
        raw[1] = 0
        assertEquals(clientPub, key.toByteArray().toHex())
        assertEquals(PublicKeyBytes(clientPub.hexToBytes()), key)

        // Mutate returned x and assert second read is unchanged
        val x1 = key.x
        x1[0] = 0x00
        val x2 = key.x
        assertEquals("b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7", x2.toHex())
    }
}
