// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — NewVehicle (cache load), UpdateCachedSessions
package dev.mirolab.teslable.application.cache

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.SessionCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SessionCacheSync"

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

    /** 잠금 보존을 한 번 포기하고 썼으면 막힌 항목은 이미 지워졌다 — 이 연결에서는 다시 보존하지 않는다(#96 후속). */
    private var lockKeepAbandoned = false
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
     *
     * 예외(Go에 없음, FR-037·#96): 이 연결에서 기기 잠금 때문에 복원하지 못한 도메인에 아직 세션이 없으면 아무것도 쓰지 않는다. 그 항목은
     * 아직 유효한데, 포트는 목록 전체를 덮어쓰고 저장 시각을 새로 붙이므로 옛 항목만 남겨 둘 수 없다(나이가 틀려진다). 잠금이 풀려 그
     * 도메인에 세션이 생기면 다시 평소처럼 쓴다. 단 [markStale]한 도메인이 있으면 이 예외를 쓰지 않는다 — 옛 목록을 남기면 이미 오래됐다고
     * 판정된 항목도 남아 다음 연결이 그것을 또 복원한다. 막힌 도메인의 항목을 잃는 비용(핸드셰이크 한 번)이 더 작다. 그렇게 한 번 쓴
     * 뒤에는 지킬 항목이 없으므로 그 연결이 끝날 때까지 이 예외를 다시 쓰지 않는다(오래된 도메인이 새 세션으로 돌아와도).
     */
    public suspend fun store(dispatcher: Dispatcher) {
        storeMutex.withLock {
            val sessions = dispatcher.exportSessions()
            val keptForLock = dispatcher.restoresBlockedByLock.filter { blocked -> sessions.none { it.domain == blocked } }
            if (keptForLock.isNotEmpty() && staleDomains.isEmpty() && !lockKeepAbandoned) {
                dispatcher.log.log(LogLevel.INFO, TAG) {
                    "Keeping the session cache: cached sessions for $keptForLock were not restored while the device was locked"
                }
                return@withLock
            }
            if (keptForLock.isNotEmpty()) lockKeepAbandoned = true
            cache.store(vin, keyId, sessions.filter { it.domain !in staleDomains })
        }
    }
}
