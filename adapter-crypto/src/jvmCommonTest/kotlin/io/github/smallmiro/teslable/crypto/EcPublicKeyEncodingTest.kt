package io.github.smallmiro.teslable.crypto

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("MagicNumber") // jvmCommonTest는 detekt 기본 테스트 제외 경로에 없다
class EcPublicKeyEncodingTest {
    @Test
    fun padsShortCoordinatesTo32Bytes() {
        // SDD §8.2: ECPublicKey.w → 0x04‖X(32)‖Y(32), 좌표는 앞을 0으로 채운다(BigInteger.toByteArray는 짧거나 부호 바이트를 붙인다)
        val x = BigInteger("01", 16)
        val y = BigInteger("ff" + "00".repeat(31), 16) // 부호 비트가 켜져 toByteArray()가 33바이트
        val encoded = EcPublicKeyEncoding.encode(x, y).toByteArray()
        assertEquals(65, encoded.size)
        assertEquals(4, encoded[0].toInt())
        assertEquals(1, encoded[32].toInt())
        assertEquals(0xff, encoded[33].toInt() and 0xff)
        assertEquals(0, encoded[64].toInt())
    }
}
