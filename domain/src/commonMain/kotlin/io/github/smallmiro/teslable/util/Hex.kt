package io.github.smallmiro.teslable.util

import io.github.smallmiro.teslable.InternalTeslableApi

private const val BYTE_MASK = 0xff
private const val HEX_PADDING_WIDTH = 2
private const val HEX_RADIX = 16

/** 바이트 배열을 소문자 hex 문자열로 바꾼다 (라이브러리 내부·테스트용). */
@InternalTeslableApi
public fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PADDING_WIDTH, '0') }

/** 짝수 길이 hex 문자열을 바이트 배열로 바꾼다 (길이가 홀수이면 IllegalArgumentException, 라이브러리 내부·테스트용). */
@InternalTeslableApi
public fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / HEX_PADDING_WIDTH) { i ->
        substring(HEX_PADDING_WIDTH * i, HEX_PADDING_WIDTH * i + HEX_PADDING_WIDTH).toInt(HEX_RADIX).toByte()
    }
}
