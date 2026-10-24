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
        // 16자, 그리고 I가 든 17자
        val raw =
            byteArrayOf(1, 2, 3, 0x00) + "AAAAAAAAAAAAAAAA".toByteArray() + byteArrayOf(0x00) +
                "AAAAIAAAAAAAAAAAA".toByteArray()
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
        val text =
            "[drop:no-handler] 83B from=domain(DOMAIN_VEHICLE_SECURITY) 5YJ**********0000 " +
                "lowercase5yjs0000000000000 IOQIOQIOQIOQIOQIO"
        assertEquals(text, VinMask.maskText(text))
    }

    /** VIN 앞뒤에 VIN 문자가 붙어 있어도 실행 구간 전체를 가리고 구간의 앞 3자, 뒤 4자만 남긴다. */
    private fun assertRunMasked(
        input: String,
        output: String,
    ) {
        assertEquals(input.length, output.length)
        assertEquals(input.take(3), output.take(3))
        assertEquals(input.takeLast(4), output.takeLast(4))
        assertEquals("*".repeat(input.length - 7), output.substring(3, output.length - 4))
    }

    @Test
    fun textVinWithVinCharactersAroundIsMaskedAsOneRun() {
        val run = "XX${vin}YY"
        assertRunMasked(run, VinMask.maskText(run))
        assertRunMasked("A$vin", VinMask.maskText("A$vin"))
        assertRunMasked("${vin}Z", VinMask.maskText("${vin}Z"))
        assertEquals("a b ${VinMask.maskText(run)} c", VinMask.maskText("a b $run c"))
    }

    @Test
    fun bytesVinWithVinCharactersAroundIsMaskedAsOneRun() {
        val run = "XX${vin}YY"
        val raw = byteArrayOf(0x01) + run.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02)
        val out = VinMask.maskBytes(raw)
        assertEquals(raw.size, out.size)
        assertEquals(0x01.toByte(), out.first())
        assertEquals(0x02.toByte(), out.last())
        assertRunMasked(run, String(out.copyOfRange(1, out.size - 1), Charsets.US_ASCII))
    }

    @Test
    fun aRunOfExactly17IsStillMasked() {
        assertEquals(masked, VinMask.maskText(vin))
        assertEquals(17, VinMask.maskBytes(vin.toByteArray()).size)
    }
}
