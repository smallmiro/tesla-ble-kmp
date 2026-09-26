package io.github.smallmiro.teslable.model

private const val VIN_PREFIX_LENGTH = 3
private const val VIN_MASKED_LENGTH = 10
private const val VIN_SUFFIX_LENGTH = 4
private const val MASK_CHAR = '*'

/** 차대번호 17자. `toString()`은 로그용으로 마스킹한다(NFR-006). */
public class Vin(
    value: String,
) {
    /** 대문자로 정규화한 VIN 17자 (마스킹하지 않은 원문이므로 로그에 쓰지 않는다). */
    public val value: String = value.uppercase()

    init {
        require(VIN_REGEX.matches(this.value)) { "VIN must be 17 characters of [A-HJ-NPR-Z0-9]" }
    }

    /** 프로토콜 personalization 필드에 넣는 ASCII 바이트. */
    public fun toByteArray(): ByteArray = value.encodeToByteArray()

    override fun equals(other: Any?): Boolean = other is Vin && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String =
        value.take(VIN_PREFIX_LENGTH) + MASK_CHAR.toString().repeat(VIN_MASKED_LENGTH) +
            value.takeLast(VIN_SUFFIX_LENGTH)

    private companion object {
        val VIN_REGEX = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    }
}
