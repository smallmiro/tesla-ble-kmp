package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.SessionCache

/**
 * 호출을 기록하는 테스트용 [SessionCache]. `:application` 테스트는 `:adapter-storage`를 볼 수 없어 따로 둔다(설계 구체화 12).
 * 벽시계는 [wallClockMillis]로 주입한다(기본 0). `runTest` 단일 스레드 전용. 인코딩·`age` 계산은 `InMemorySessionCache`
 * (`:adapter-storage`)와 같은 [SessionCacheCodec.encodeSnapshots]/[SessionCacheCodec.decodeSessions]로 위임해 매핑 코드를
 * 중복하지 않는다.
 */
public class RecordingSessionCache(
    private val wallClockMillis: () -> Long = { 0L },
) : SessionCache {
    /** [store] 호출 하나. */
    public class Stored(
        /** VIN. */
        public val vin: Vin,
        /** 키. */
        public val keyId: KeyId,
        /** 저장된 항목. */
        public val entries: List<SessionSnapshot>,
    )

    private val encoded = HashMap<String, ByteArray>()
    private val storeCalls = mutableListOf<Stored>()
    private var loadCalls = 0

    /** 지금까지의 [store] 호출(사본). */
    public val stores: List<Stored> get() = storeCalls.toList()

    /** [load] 호출 수. */
    public val loads: Int get() = loadCalls

    /** 저장 시각을 직접 정해 항목을 심는다(미래 시각 등). */
    public fun seed(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionCacheCodec.Entry>,
    ) {
        encoded[vin.value] = SessionCacheCodec.encode(keyId, entries)
    }

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> {
        loadCalls++
        val bytes = encoded[vin.value] ?: return emptyList()
        val decoded = SessionCacheCodec.decodeSessions(bytes, keyId, wallClockMillis())
        if (decoded == null) {
            encoded.remove(vin.value)
            return emptyList()
        }
        return decoded
    }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        storeCalls += Stored(vin, keyId, entries)
        encoded[vin.value] = SessionCacheCodec.encodeSnapshots(keyId, entries, wallClockMillis())
    }

    override suspend fun clear(vin: Vin) {
        encoded.remove(vin.value)
    }
}
