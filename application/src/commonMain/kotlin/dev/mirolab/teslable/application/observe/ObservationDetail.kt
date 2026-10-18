package dev.mirolab.teslable.application.observe

import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoReader
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.util.toHex
import okio.Buffer
import okio.ByteString
import okio.IOException

/**
 * 원본에 없음 — 관찰 로그 v2: 미공개 필드의 값을 탐색용으로 풀어 쓴다(M4 Task 1B).
 * 입구 조건은 호출하는 쪽(`ObservationLog`)이 지킨다. 여기서는 바이트를 포맷하기 전에 VIN을 가리고,
 * 키·서명처럼 보이는 LEN은 원문 대신 `redacted(NB)`(65바이트 공개키는 `sha1:` 앞 4바이트)만 남긴다.
 * 필드 이름은 지어내지 않고 번호와 wire type만 쓴다.
 */
@OptIn(InternalTeslableApi::class)
internal class ObservationDetail(
    private val crypto: CryptoPrimitives,
) {
    private sealed interface Value {
        data class Varint(
            val v: Long,
        ) : Value

        data class Fixed32(
            val v: Int,
        ) : Value

        data class Fixed64(
            val v: Long,
        ) : Value

        class Len(
            val bytes: ByteArray,
        ) : Value
    }

    private class Field(
        val tag: Int,
        val value: Value,
    )

    private class Parsed(
        val fields: List<Field>,
        val overLimit: Boolean,
    )

    /** `unknownFields` 전체를 도착 순서대로 쓴다. [detailed]에 든 번호만 값을 풀고 나머지는 번호만(중복 제거). */
    fun unknownFields(
        bytes: ByteString,
        detailed: Set<Int>,
    ): List<String> {
        if (bytes.size == 0) return emptyList()
        val parsed = parse(bytes.toByteArray())
        if (parsed == null) return listOf("len(${bytes.size}, invalid)")
        val out = mutableListOf<String>()
        for (f in parsed.fields) {
            if (f.tag in detailed) {
                out += "${f.tag}:${format(f.value, depth = 0)}"
            } else if (f.tag.toString() !in out) {
                out += f.tag.toString()
            }
        }
        if (parsed.overLimit) out += "truncated"
        return out
    }

    private fun format(
        value: Value,
        depth: Int,
    ): String =
        when (value) {
            is Value.Varint -> "varint=${value.v.toULong()}(0x${value.v.toULong().toString(HEX)})"
            is Value.Fixed32 -> "fixed32=0x${value.v.toUInt().toString(HEX).padStart(FIXED32_DIGITS, '0')}"
            is Value.Fixed64 -> "fixed64=0x${value.v.toULong().toString(HEX).padStart(FIXED64_DIGITS, '0')}"
            is Value.Len -> len(value.bytes, depth)
        }

    private fun len(
        raw: ByteArray,
        depth: Int,
    ): String {
        val n = raw.size
        if (n > MAX_LEN) return "len($n, truncated)"
        if (n == PUBLIC_KEY_SIZE && raw[0] == UNCOMPRESSED_PREFIX) return "sha1:" + crypto.sha1(raw).copyOf(SHA_PREFIX).toHex()
        if (n in KEY_LIKE_SIZES) return "redacted(${n}B)"
        val bytes = maskVin(raw)
        val parsed = if (n == 0) null else parse(bytes)
        if (parsed != null) {
            if (depth + 1 >= MAX_DEPTH || parsed.overLimit) return "len($n, truncated)"
            return "len($n){" + parsed.fields.joinToString(", ") { "${it.tag}:${format(it.value, depth + 1)}" } + "}"
        }
        if (n == 0) return "len(0)"
        return if (bytes.all { it in PRINTABLE_FROM..PRINTABLE_TO || it in WHITESPACE }) {
            "len($n)\"" + escape(bytes) + "\""
        } else {
            "len($n)0x" + bytes.toHex()
        }
    }

    /** 전체 바이트가 정상 protobuf로 소비될 때만 필드 목록을 돌려준다. 아니면 null. 64개를 넘으면 [Parsed.overLimit]. */
    private fun parse(bytes: ByteArray): Parsed? {
        val fields = mutableListOf<Field>()
        var over = false
        try {
            val reader = ProtoReader(Buffer().write(bytes))
            reader.forEachTag { tag ->
                val encoding = reader.peekFieldEncoding() ?: throw IOException("group")
                val value =
                    when (encoding) {
                        FieldEncoding.VARINT -> Value.Varint(reader.readVarint64())
                        FieldEncoding.FIXED32 -> Value.Fixed32(reader.readFixed32())
                        FieldEncoding.FIXED64 -> Value.Fixed64(reader.readFixed64())
                        FieldEncoding.LENGTH_DELIMITED -> Value.Len(reader.readBytes().toByteArray())
                    }
                if (fields.size < MAX_FIELDS) fields += Field(tag, value) else over = true
            }
        } catch (ignored: IOException) {
            return null
        }
        return Parsed(fields, over)
    }

    private fun maskVin(raw: ByteArray): ByteArray {
        val chars = CharArray(raw.size) { (raw[it].toInt() and BYTE_MASK).toChar() }
        val masked = maskVinText(chars.concatToString())
        return ByteArray(masked.length) { masked[it].code.toByte() }
    }

    private fun escape(bytes: ByteArray): String =
        buildString {
            for (b in bytes) {
                when (b) {
                    '\n'.code.toByte() -> append("\\n")
                    '\r'.code.toByte() -> append("\\r")
                    '\t'.code.toByte() -> append("\\t")
                    '"'.code.toByte() -> append("\\\"")
                    '\\'.code.toByte() -> append("\\\\")
                    else -> append(b.toInt().toChar())
                }
            }
        }

    companion object {
        private const val HEX = 16
        private const val FIXED32_DIGITS = 8
        private const val FIXED64_DIGITS = 16
        private const val MAX_LEN = 256
        private const val MAX_DEPTH = 3
        private const val MAX_FIELDS = 64
        private const val PUBLIC_KEY_SIZE = 65
        private const val UNCOMPRESSED_PREFIX: Byte = 0x04
        private const val SHA_PREFIX = 4
        private const val BYTE_MASK = 0xFF
        private const val PRINTABLE_FROM: Byte = 0x20
        private const val PRINTABLE_TO: Byte = 0x7E
        private val WHITESPACE = byteArrayOf('\n'.code.toByte(), '\r'.code.toByte(), '\t'.code.toByte()).toSet()
        private val KEY_LIKE_SIZES = setOf(16, 32, 33, 64, 65)

        private const val VIN_LENGTH = 17
        private const val VIN_HEAD = 3
        private const val VIN_TAIL = 4
        private val VIN_PATTERN = Regex("[A-HJ-NPR-Z0-9]{$VIN_LENGTH}")

        /** VIN 패턴(17자)을 앞 3자·뒤 4자만 남기고 `*`로 가린다. `Vin.toString`과 같은 모양. */
        fun maskVinText(text: String): String =
            VIN_PATTERN.replace(text) { m ->
                val s = m.value
                s.take(VIN_HEAD) + "*".repeat(VIN_LENGTH - VIN_HEAD - VIN_TAIL) + s.takeLast(VIN_TAIL)
            }
    }
}
