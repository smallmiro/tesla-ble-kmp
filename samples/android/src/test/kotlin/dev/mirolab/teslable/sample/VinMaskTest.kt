package dev.mirolab.teslable.sample

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class VinMaskTest {
    // 실제 VIN이 아닌 가짜 값(형식만 같다). 17자, I·O·Q 없음.
    private val vin = "5YJS0000000000000"
    private val masked = "5YJ**********0000"

    @Test
    fun masksVinInBytesKeepingLength() {
        val raw = byteArrayOf(0x0a, 0x11) + vin.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x7f, 0x01)

        val out = VinMask.maskBytes(raw)

        assertEquals(raw.size, out.size)
        assertContentEquals(byteArrayOf(0x0a, 0x11) + masked.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x7f, 0x01), out)
    }

    @Test
    fun maskBytesDoesNotChangeTheInput() {
        val raw = vin.toByteArray(Charsets.US_ASCII)
        VinMask.maskBytes(raw)
        assertEquals(vin, String(raw, Charsets.US_ASCII))
    }

    @Test
    fun bytesWithoutVinAreUnchanged() {
        val raw = byteArrayOf(1, 2, 3, 0x00) + "AAAAAAAAAAAAAAAA".toByteArray() + byteArrayOf(0x00) + "AAAAIAAAAAAAAAAAA".toByteArray() // 16자, 그리고 I가 든 17자
        assertContentEquals(raw, VinMask.maskBytes(raw))
    }

    @Test
    fun masksVinInText() {
        assertEquals("vin=$masked end", VinMask.maskText("vin=$vin end"))
    }

    @Test
    fun masksEveryVinInText() {
        assertEquals("$masked $masked", VinMask.maskText("$vin $vin"))
    }

    @Test
    fun textWithoutVinIsUnchanged() {
        val text = "[drop:no-handler] 83B from=domain(DOMAIN_VEHICLE_SECURITY) 5YJ**********0000 lowercase5yjs0000000000000 IOQIOQIOQIOQIOQIO"
        assertEquals(text, VinMask.maskText(text))
    }
}
