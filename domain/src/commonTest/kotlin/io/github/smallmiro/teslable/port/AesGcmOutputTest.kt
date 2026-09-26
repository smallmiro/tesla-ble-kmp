package io.github.smallmiro.teslable.port

import kotlin.test.Test
import kotlin.test.assertContentEquals

class AesGcmOutputTest {
    @Test
    fun defendsAgainstMutationOfConstructorInput() {
        val ciphertext = byteArrayOf(0x01, 0x02, 0x03)
        val tag = byteArrayOf(0x04, 0x05, 0x06)
        val output = AesGcmOutput(ciphertext, tag)

        // Mutate the input arrays
        ciphertext[0] = 0x00
        tag[0] = 0x00

        // Assert stored values are unchanged
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), output.ciphertext)
        assertContentEquals(byteArrayOf(0x04, 0x05, 0x06), output.tag)
    }

    @Test
    fun defendsAgainstMutationOfReturnedArrays() {
        val output = AesGcmOutput(byteArrayOf(0x01, 0x02, 0x03), byteArrayOf(0x04, 0x05, 0x06))

        // Mutate the first read
        val ciphertext1 = output.ciphertext
        ciphertext1[0] = 0x00
        val tag1 = output.tag
        tag1[0] = 0x00

        // Assert second read is unchanged
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), output.ciphertext)
        assertContentEquals(byteArrayOf(0x04, 0x05, 0x06), output.tag)
    }
}
