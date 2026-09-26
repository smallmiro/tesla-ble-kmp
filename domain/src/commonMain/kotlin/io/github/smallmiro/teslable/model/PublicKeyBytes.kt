package io.github.smallmiro.teslable.model

private const val X_START = 1
private const val X_END = 33
private const val Y_END = 65
private const val PREFIX_BYTE = 0x04
private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_PADDING_WIDTH = 2
private const val DISPLAY_BYTES = 4

/** NIST P-256 공개키의 비압축 SEC1 인코딩 `0x04 || X(32) || Y(32)` (65바이트). 불변, 방어 복사. */
public class PublicKeyBytes(
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == SIZE && this.bytes[0] == PREFIX) {
            "public key must be $SIZE bytes uncompressed (0x04 || X || Y)"
        }
    }

    public val x: ByteArray get() = bytes.copyOfRange(X_START, X_END)
    public val y: ByteArray get() = bytes.copyOfRange(X_END, Y_END)

    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is PublicKeyBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "PublicKeyBytes(04${x.take(
            DISPLAY_BYTES,
        ).joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PADDING_WIDTH, '0') }}…)"

    public companion object {
        public const val SIZE: Int = 65
        private const val PREFIX: Byte = PREFIX_BYTE.toByte()
    }
}
