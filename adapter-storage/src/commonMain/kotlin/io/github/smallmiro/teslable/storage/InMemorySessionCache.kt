package io.github.smallmiro.teslable.storage

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.SessionCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * 메모리 세션 캐시(SDD §2.6 공통 구현; 테스트·옵트아웃용). 값은 [SessionCacheCodec] v1 바이트로 보관해 파일/Keychain 구현(M3)과
 * 같은 경로를 탄다. 벽시계는 이 라이브러리에서 유일하게 여기서만 쓰며 [wallClockMillis]로 주입한다. 인코딩·`age` 계산은
 * [SessionCacheCodec.encodeSnapshots]/[SessionCacheCodec.decodeSessions]에 맡겨 `RecordingSessionCache`(`:testing`)와
 * 매핑 코드를 중복하지 않는다.
 */
public class InMemorySessionCache(
    private val wallClockMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SessionCache {
    private val store = HashMap<String, ByteArray>()
    private val mutex = Mutex()

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> =
        mutex.withLock {
            val bytes = store[vin.value] ?: return@withLock emptyList()
            val decoded = SessionCacheCodec.decodeSessions(bytes, keyId, wallClockMillis())
            if (decoded == null) {
                store.remove(vin.value)
                emptyList()
            } else {
                decoded
            }
        }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        mutex.withLock {
            store[vin.value] = SessionCacheCodec.encodeSnapshots(keyId, entries, wallClockMillis())
        }
    }

    override suspend fun clear(vin: Vin) {
        mutex.withLock { store.remove(vin.value) }
    }
}
