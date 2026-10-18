// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle (cache load), UpdateCachedSessions
package dev.mirolab.teslable.application.cache

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.SessionCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [SessionCache] 포트와 [Dispatcher] 사이의 동기화(SDD §2.2 `SessionCacheSync`). 저장 시점: 핸드셰이크 완료·해제·세션정보 갱신(§7.1).
 * 갱신 트리거는 수신 코루틴이 아니라 `VehicleSession`이 `Dispatcher.sessionEvents`를 수집해 건다 — 수신 코루틴이 캐시 I/O로
 * suspend해서는 안 되기 때문이다(SDD §5).
 *
 * 연결(Dispatcher)당 하나만 만든다 — [markStale]한 도메인 목록이 이 객체에 산다. 스냅샷·필터·쓰기는 한 뮤텍스 안에서 해
 * 동시 [store]가 순서가 뒤바뀐 채 겹치지 않는다.
 *
 * **호출 순서 주의:** [Dispatcher.close]는 모든 세션 키를 지운다. 그 뒤에 [store]를 부르면 [Dispatcher.exportSessions]가 빈
 * 목록을 돌려주고, [store]는 그 빈 목록을 그대로 저장해(§7.1 "빈 목록도 저장" 규칙) 이전에 저장해 둔 캐시를 지워 버린다.
 * 그래서 호출자는 반드시 [store]를 [Dispatcher.close]보다 먼저 불러야 한다(Go CLI도 disconnect 전에 저장한다).
 */
public class SessionCacheSync(
    private val cache: SessionCache,
    private val vin: Vin,
    private val keyId: KeyId,
) {
    private val staleDomains = mutableSetOf<Domain>()
    private val storeMutex = Mutex()

    /** 오래된 캐시로 보고된 도메인을 이후 모든 [store]에서 뺀다(사용자 답 a — 포트에 도메인 단위 삭제가 없으므로 나머지를 다시 저장). */
    public suspend fun markStale(domain: Domain) {
        storeMutex.withLock { staleDomains += domain }
    }

    /** 새 핸드셰이크로 다시 확인된 도메인을 저장 대상에 되돌린다. */
    public suspend fun markFresh(domain: Domain) {
        storeMutex.withLock { staleDomains -= domain }
    }

    /** Go `NewVehicle`: `GetEntry(vin)` → `LoadCache`. 복원한 도메인. */
    public suspend fun load(dispatcher: Dispatcher): Set<Domain> = dispatcher.loadSessions(cache.load(vin, keyId))

    /**
     * Go `UpdateCachedSessions`: `Cache()` → `Update`. 세션이 없으면 빈 목록을 저장한다(Go도 nil을 저장해 옛 캐시를 지운다).
     * [dispatcher]가 이미 [Dispatcher.close]된 뒤라면 세션이 전부 비어 있어 저장된 캐시를 지워 버린다 — 반드시 [close] 전에 부른다.
     */
    public suspend fun store(dispatcher: Dispatcher) {
        storeMutex.withLock {
            cache.store(vin, keyId, dispatcher.exportSessions().filter { it.domain !in staleDomains })
        }
    }
}
