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
    private val vinPattern = Regex("[A-HJ-NPR-Z0-9]{$VIN_LENGTH}")

    private fun isVinChar(b: Int): Boolean =
        (b in '0'.code..'9'.code) ||
            (b in 'A'.code..'Z'.code && b != 'I'.code && b != 'O'.code && b != 'Q'.code)

    /** 텍스트 안의 VIN 모양을 가린다. */
    fun maskText(text: String): String =
        vinPattern.replace(text) { m ->
            m.value.take(KEEP_HEAD) + MASK.toString().repeat(VIN_LENGTH - KEEP_HEAD - KEEP_TAIL) + m.value.takeLast(KEEP_TAIL)
        }

    /** 원본 바이트에서 VIN 모양의 ASCII 구간을 같은 길이로 가린다. 입력은 바꾸지 않고 새 배열을 돌려준다. */
    fun maskBytes(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        var i = 0
        while (i + VIN_LENGTH <= out.size) {
            if ((0 until VIN_LENGTH).all { isVinChar(bytes[i + it].toInt() and BYTE_MASK) }) {
                for (k in KEEP_HEAD until VIN_LENGTH - KEEP_TAIL) out[i + k] = MASK.code.toByte()
                i += VIN_LENGTH
            } else {
                i++
            }
        }
        return out
    }

    private const val BYTE_MASK = 0xff
}
