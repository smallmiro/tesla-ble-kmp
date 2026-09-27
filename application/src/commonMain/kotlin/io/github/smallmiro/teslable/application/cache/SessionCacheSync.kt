// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle (cache load), UpdateCachedSessions
package io.github.smallmiro.teslable.application.cache

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.SessionCache

/**
 * [SessionCache] 포트와 [Dispatcher] 사이의 동기화(SDD §2.2 `SessionCacheSync`). 저장 시점: 핸드셰이크 완료·해제(§7.1).
 * SDD §3.4/§7.1의 "세션 갱신 시 저장" 트리거는 M2에서는 걸지 않는다 — 수신 코루틴이 캐시 I/O로 suspend해서는 안 되기 때문이다
 * (SDD §5). 그 트리거는 M3 파사드가 연결한다.
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
    /** Go `NewVehicle`: `GetEntry(vin)` → `LoadCache`. 복원한 도메인. */
    public suspend fun load(dispatcher: Dispatcher): Set<Domain> = dispatcher.loadSessions(cache.load(vin, keyId))

    /**
     * Go `UpdateCachedSessions`: `Cache()` → `Update`. 세션이 없으면 빈 목록을 저장한다(Go도 nil을 저장해 옛 캐시를 지운다).
     * [dispatcher]가 이미 [Dispatcher.close]된 뒤라면 세션이 전부 비어 있어 저장된 캐시를 지워 버린다 — 반드시 [close] 전에 부른다.
     */
    public suspend fun store(dispatcher: Dispatcher) {
        cache.store(vin, keyId, dispatcher.exportSessions())
    }
}
