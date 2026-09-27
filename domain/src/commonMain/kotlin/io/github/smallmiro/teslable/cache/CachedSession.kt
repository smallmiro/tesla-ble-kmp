// Ported from vehicle-command@a4b43c1 internal/dispatcher/session.go (Apache-2.0) — CacheEntry (createdAt → age)
package io.github.smallmiro.teslable.cache

import com.tesla.generated.universalmessage.Domain
import kotlin.time.Duration

/** Go `session.export()` 결과 하나: 도메인의 `Signatures.SessionInfo` 바이트(clock_time = 내보낸 시점). 캐시가 저장 시각을 붙인다. */
public class SessionSnapshot(
    /** 도메인. */
    public val domain: Domain,
    sessionInfo: ByteArray,
) {
    private val info = sessionInfo.copyOf()

    /** protobuf 인코딩된 `SessionInfo`(사본). */
    public val sessionInfo: ByteArray get() = info.copyOf()
}

/** Go `CacheEntry`를 불러온 것. `createdAt` 대신 저장 후 경과 [age]를 준다(음수 가능 — `Signer.importSessionInfo`가 0으로 본다). */
public class CachedSession(
    /** 도메인. */
    public val domain: Domain,
    sessionInfo: ByteArray,
    /** 저장 시각부터 지금까지(Go `ImportSessionInfo(generatedAt)`의 `now - generatedAt`). */
    public val age: Duration,
) {
    private val info = sessionInfo.copyOf()

    /** protobuf 인코딩된 `SessionInfo`(사본). */
    public val sessionInfo: ByteArray get() = info.copyOf()
}
