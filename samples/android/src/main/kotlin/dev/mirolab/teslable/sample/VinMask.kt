package dev.mirolab.teslable.sample

/**
 * VIN 가리기(04-monitor-app.md §Log format 규칙). `[A-HJ-NPR-Z0-9]{17}` 에 맞는 ASCII 연속 구간을 같은 길이로 바꾸되
 * 앞 3자와 뒤 4자는 남기고 나머지는 `*` 로 한다. 파일에 쓰기 전에 원본 바이트와 텍스트 모두에 적용한다.
 */
object VinMask {
    private const val VIN_LENGTH = 17
    private const val KEEP_HEAD = 3
    private const val KEEP_TAIL = 4
    private const val MASK = '*'
    private const val BYTE_MASK = 0xff

    /** VIN 문자가 [VIN_LENGTH]자 이상 이어지는 구간. 앞뒤로 VIN 문자가 더 붙어 있어도 구간 전체를 한 덩어리로 본다. */
    private val runPattern = Regex("[A-HJ-NPR-Z0-9]{$VIN_LENGTH,}")

    private fun isVinChar(b: Int): Boolean =
        (b in '0'.code..'9'.code) ||
            (b in 'A'.code..'Z'.code && b != 'I'.code && b != 'O'.code && b != 'Q'.code)

    /** 텍스트 안의 VIN 모양을 가린다. 17자 이상 이어지는 VIN 문자 구간은 통째로 가리고 구간의 앞 3자와 뒤 4자만 남긴다. */
    fun maskText(text: String): String =
        runPattern.replace(text) { m ->
            m.value.take(KEEP_HEAD) + MASK.toString().repeat(m.value.length - KEEP_HEAD - KEEP_TAIL) + m.value.takeLast(KEEP_TAIL)
        }

    /** 원본 바이트에서 VIN 모양의 ASCII 구간을 같은 길이로 가린다. 입력은 바꾸지 않고 새 배열을 돌려준다. */
    fun maskBytes(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        var i = 0
        while (i < bytes.size) {
            if (!isVinChar(bytes[i].toInt() and BYTE_MASK)) {
                i++
                continue
            }
            var end = i
            while (end < bytes.size && isVinChar(bytes[end].toInt() and BYTE_MASK)) end++
            if (end - i >= VIN_LENGTH) {
                for (k in i + KEEP_HEAD until end - KEEP_TAIL) out[k] = MASK.code.toByte()
            }
            i = end
        }
        return out
    }
}
