package io.github.smallmiro.teslable.model

import io.github.smallmiro.teslable.port.CryptoPrimitives

private const val DISPLAY_BYTES = 4
private const val BYTE_MASK = 0xff
private const val HEX_RADIX = 16
private const val HEX_PADDING_WIDTH = 2

/** 세션 캐시를 묶는 클라이언트 키 식별자 = SHA1(공개키 65바이트) 20바이트(SDD §7.1, D26). 불변, 방어 복사. */
public class KeyId(
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == SIZE) { "key id must be $SIZE bytes (SHA-1)" }
    }

    /** 20바이트 사본. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is KeyId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String {
        val prefix =
            bytes.take(DISPLAY_BYTES).joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PADDING_WIDTH, '0') }
        return "KeyId($prefix…)"
    }

    /** 생성. */
    public companion object {
        /** SHA-1 다이제스트 길이. */
        public const val SIZE: Int = 20

        /** `SHA1(publicKey)`. */
        public fun of(
            publicKey: PublicKeyBytes,
            crypto: CryptoPrimitives,
        ): KeyId = KeyId(crypto.sha1(publicKey.toByteArray()))
    }
}
