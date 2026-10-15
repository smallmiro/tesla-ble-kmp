package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import java.math.BigInteger

/** JCA 좌표 → 65바이트 비압축 공개키(SDD §8.2, 좌표 32바이트 0-패딩). */
internal object EcPublicKeyEncoding {
    private const val COORDINATE_SIZE = 32
    private const val UNCOMPRESSED_TAG: Byte = 4

    fun encode(
        x: BigInteger,
        y: BigInteger,
    ): PublicKeyBytes = PublicKeyBytes(byteArrayOf(UNCOMPRESSED_TAG) + x.toFixed() + y.toFixed())

    /** 부호 바이트를 떼고 앞을 0으로 채운다. */
    fun BigInteger.toFixed(): ByteArray {
        val raw = toByteArray()
        val trimmed = if (raw.size > COORDINATE_SIZE) raw.copyOfRange(raw.size - COORDINATE_SIZE, raw.size) else raw
        return ByteArray(COORDINATE_SIZE).also { trimmed.copyInto(it, COORDINATE_SIZE - trimmed.size) }
    }
}
