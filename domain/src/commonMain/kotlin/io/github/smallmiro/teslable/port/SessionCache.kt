// Ported from vehicle-command@a4b43c1 pkg/cache/cache.go (Apache-2.0) — SessionCache.Update/GetEntry as a port (own format v1, D26)
package io.github.smallmiro.teslable.port

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin

/**
 * 세션 캐시 포트(FR-017, SDD §7.1). VIN당 항목 하나(도메인별 `SessionInfo` 묶음), 클라이언트 키(`keyId`)에 종속.
 * 구현은 예외를 던지지 않는다: 읽기 실패·키 불일치·손상은 빈 목록(+삭제), 쓰기 실패는 로그로만(명령 결과에 영향 없음).
 * 벽시계는 구현 안에서만 쓰고 밖으로는 `age`로 나간다.
 */
public interface SessionCache {
    /** [keyId]로 만든 항목만 돌려준다. 다른 키의 캐시는 버리고 빈 목록. Go `GetEntry`. */
    public suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession>

    /** 덮어쓴다(빈 목록이면 사실상 삭제). Go `Update`. */
    public suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    )

    /** VIN의 캐시를 지운다. */
    public suspend fun clear(vin: Vin)
}
