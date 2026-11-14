// Ported from vehicle-command@a4b43c1 pkg/cache/cache.go (Apache-2.0) — SessionCache.Update/GetEntry as a port (own format v1, D26)
package dev.mirolab.teslable.port

import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.Vin

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

    /**
     * 덮어쓴다(빈 목록이면 사실상 삭제). Go `Update`.
     *
     * 라이브러리는 이 호출을 인증 요청을 보내기 **전에**도 부른다(counter 예약, ADR-0016): 연결의 첫 인증 요청 앞과 그 뒤 16개마다. 그
     * 시간은 그 명령의 시간 제한에 들어가므로 빨리 끝나야 한다. 정상으로 돌아오면 라이브러리는 쓴 값을 저장된 것으로 본다: 실패를 로그로만
     * 남기면(기본 구현이 그렇다) 다음 16개 요청이 저장되지 않은 counter를 쓰고, 그사이 프로세스가 끝나면 다음 연결의 첫 요청이 한 번 거부될
     * 수 있다(회복한 뒤 결과는 `Success`). 항목을 잃는 것은 안전하다(다음 연결이 핸드셰이크한다).
     */
    public suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    )

    /** VIN의 캐시를 지운다. */
    public suspend fun clear(vin: Vin)
}
