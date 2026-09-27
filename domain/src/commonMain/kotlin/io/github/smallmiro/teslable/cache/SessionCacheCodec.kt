package io.github.smallmiro.teslable.cache

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.model.KeyId
import okio.Buffer
import okio.EOFException
import kotlin.time.Duration.Companion.milliseconds

private const val MAGIC = "TBSC"
private const val MAGIC_LENGTH = 4L
private const val BYTE_MASK = 0xff
private const val SHORT_MASK = 0xffff
private const val MAX_ENTRIES = 255
private const val MAX_INFO_LENGTH = 65_535

/**
 * 세션 캐시 바이너리 형식 v1(SDD §7.1, ADR-0007). 모두 big-endian:
 * `"TBSC"(4) | version u8 = 1 | keyId(20) | count u8 | (domain u8 | createdAtEpochMillis i64 | infoLen u16 | SessionInfo bytes) × count`.
 * Go의 JSON 캐시와 호환하지 않는다(D26). 순수 Kotlin.
 */
public object SessionCacheCodec {
    /** 형식 버전. */
    public const val VERSION: Int = 1

    /** 항목 하나(Go `CacheEntry`). */
    public class Entry(
        /** 도메인. */
        public val domain: Domain,
        /** 저장 시각(epoch millis, 벽시계). */
        public val createdAtEpochMillis: Long,
        sessionInfo: ByteArray,
    ) {
        private val info = sessionInfo.copyOf()

        /** protobuf 인코딩된 `SessionInfo`(사본). */
        public val sessionInfo: ByteArray get() = info.copyOf()
    }

    /**
     * 인코딩.
     * @throws IllegalArgumentException 항목이 255개를 넘거나 `SessionInfo`가 65535바이트를 넘으면 발생한다(프로그래밍 오류).
     */
    public fun encode(
        keyId: KeyId,
        entries: List<Entry>,
    ): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "at most $MAX_ENTRIES entries" }
        val buffer = Buffer()
        buffer.writeUtf8(MAGIC)
        buffer.writeByte(VERSION)
        buffer.write(keyId.toByteArray())
        buffer.writeByte(entries.size)
        for (entry in entries) {
            val info = entry.sessionInfo
            require(info.size <= MAX_INFO_LENGTH) { "session info too long" }
            buffer.writeByte(entry.domain.value)
            buffer.writeLong(entry.createdAtEpochMillis)
            buffer.writeShort(info.size)
            buffer.write(info)
        }
        return buffer.readByteArray()
    }

    /**
     * 디코딩. 매직·버전·[expectedKeyId] 불일치, 잘림, 꼬리 바이트면 null(캐시 무시 + 삭제 대상). 모르는 도메인 값의 항목은 건너뛴다.
     */
    public fun decode(
        bytes: ByteArray,
        expectedKeyId: KeyId,
    ): List<Entry>? {
        val buffer = Buffer().write(bytes)
        return try {
            if (buffer.readUtf8(MAGIC_LENGTH) != MAGIC) return null
            if (buffer.readByte().toInt() != VERSION) return null
            if (!buffer.readByteArray(KeyId.SIZE.toLong()).contentEquals(expectedKeyId.toByteArray())) return null
            val count = buffer.readByte().toInt() and BYTE_MASK
            val entries = ArrayList<Entry>(count)
            repeat(count) {
                val domainValue = buffer.readByte().toInt() and BYTE_MASK
                val createdAt = buffer.readLong()
                val length = buffer.readShort().toInt() and SHORT_MASK
                val info = buffer.readByteArray(length.toLong())
                Domain.fromValue(domainValue)?.let { entries += Entry(it, createdAt, info) }
            }
            if (!buffer.exhausted()) return null
            entries
        } catch (ignored: EOFException) {
            null
        }
    }

    /**
     * [entries]마다 [nowEpochMillis]를 저장 시각으로 찍어 인코딩한다(Go `Update`). `InMemorySessionCache`(`:adapter-storage`)와
     * `RecordingSessionCache`(`:testing`) 둘 다 [SessionSnapshot]↔`Entry` 매핑을 각자 반복하지 않도록 이 헬퍼를 쓴다.
     */
    public fun encodeSnapshots(
        keyId: KeyId,
        entries: List<SessionSnapshot>,
        nowEpochMillis: Long,
    ): ByteArray = encode(keyId, entries.map { Entry(it.domain, nowEpochMillis, it.sessionInfo) })

    /**
     * [decode] 후 [CachedSession] 목록으로 바꾼다. `age = nowEpochMillis - createdAtEpochMillis`(음수 가능, 클램프하지 않는다 —
     * 설계 구체화 7; `Signer.importSessionInfo`가 0으로 본다). [decode]가 null이면(다른 키·손상) null을 돌려줘 호출자가
     * 저장값을 지우게 한다(ADR-0007).
     */
    public fun decodeSessions(
        bytes: ByteArray,
        keyId: KeyId,
        nowEpochMillis: Long,
    ): List<CachedSession>? =
        decode(bytes, keyId)?.map { CachedSession(it.domain, it.sessionInfo, (nowEpochMillis - it.createdAtEpochMillis).milliseconds) }
}
