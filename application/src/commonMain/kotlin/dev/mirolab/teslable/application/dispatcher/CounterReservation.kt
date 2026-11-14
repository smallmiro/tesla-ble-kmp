// 원본에 없음(H17, ADR-0016): 세션 캐시 counter 예약. 훅([CounterReservation])과, `SessionCacheSync`가 쓰는 디스패처 쪽 도우미를 한 파일에 둔다.
@file:Suppress("MatchingDeclarationName")

package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.protocol.SignerResult
import dev.mirolab.teslable.protocol.decodeOrNull
import kotlin.time.Duration

/** 로그 태그: 인가는 디스패처의 일이므로 그 태그를 쓴다. */
private const val TAG = "Dispatcher"

/**
 * 인증 요청이 세션 캐시에 아직 예약되지 않은 counter를 쓰기 전에 [Dispatcher]가 부르는 훅. `VehicleSession.connect`가 세션 캐시가 있는
 * 연결에만 단다. 수신 코루틴에서는 절대 부르지 않고, 호출자가 `SessionState`의 락을 쥔 채 부르지도 않는다(캐시 I/O가 그 안에서 suspend하므로).
 */
internal fun interface CounterReservation {
    /**
     * [domain]의 다음 counter를 캐시가 덮도록 예약을 늘려 쓴다(이미 덮였으면 쓰지 않는다). 취소는 `CancellationException`으로 전파한다.
     */
    suspend fun reserve(domain: Domain): Outcome

    /** [reserve]의 결과. */
    enum class Outcome {
        /**
         * 이미 덮였거나 그 도메인의 예약을 썼다. 디스패처는 다시 인가하고, 그래도 덮이지 않았으면(다른 전송이 예약을 다 썼거나, 쓰는 사이
         * Signer나 epoch가 바뀌어 확정되지 않았으면) 또 부른다.
         */
        RESERVED,

        /**
         * 예약을 쓰지 못했다(기기 잠금 보존으로 쓰지 않음, 포트 예외, 그 도메인에 세션이 없음). 디스패처는 예약 없이 인가하고, 실제로
         * 보내면 WARN을 남긴다(이전 동작).
         */
        NOT_RESERVED,

        /** 연결이 닫히는 중이다. 디스패처는 counter를 쓰지 않고 `NotConnected`로 끝낸다. */
        CLOSING,
    }
}

/**
 * `Dispatcher.authorize`의 인가(Go `session.authorize`): [reservation]이 있으면 캐시가 다음 counter를 덮을 때까지 예약을 늘린 뒤에만
 * 암호화한다. [CounterReservation.Outcome.RESERVED] 뒤에도 덮이지 않았으면(동시 전송이 그 예약을 다 썼거나, 쓰는 사이 Signer·epoch가
 * 바뀌었으면) 다시 예약하고, 예약을 쓰지 못했을 때([CounterReservation.Outcome.NOT_RESERVED])만 예약 없이 인가한다(이전 동작, 보내면
 * WARN). 고리는 끝난다: 매번 쓰기 I/O를 하거나, 세션이 닫히거나 무효가 되면 `NO_SESSION`으로 빠진다. 닫히는 중이면 counter를 쓰지 않고
 * `NotConnected`. 결과: 세션 없음(닫힘·무효화·counter 소진)은 [VehicleError.NoSession], 그 밖의 fault는 공개 오류.
 */
internal suspend fun SessionState.authorizeReserving(
    domain: Domain,
    message: RoutableMessage,
    lifetime: Duration,
    reservation: CounterReservation?,
    logger: TeslaLogger,
): VehicleResult<RoutableMessage> {
    var result = authorizeNow(message, lifetime, requireReservation = reservation != null)
    while (reservation != null && result.needsReservation()) {
        result =
            when (reservation.reserve(domain)) {
                CounterReservation.Outcome.RESERVED -> {
                    authorizeNow(message, lifetime, requireReservation = true)
                }

                CounterReservation.Outcome.NOT_RESERVED -> {
                    authorizeNow(message, lifetime, requireReservation = false).also { uncovered ->
                        // 실제로 암호화했을 때만 알린다(세션이 없어 보내지 않으면 경고할 일이 아니다)
                        if (uncovered is SignerResult.Ok) {
                            logger.log(LogLevel.WARN, TAG) {
                                "Sending on $domain with a counter the session cache does not cover " +
                                    "(the reservation was not stored); after an unclean exit the vehicle may refuse it once"
                            }
                        }
                    }
                }

                CounterReservation.Outcome.CLOSING -> {
                    return VehicleResult.Failure(VehicleError.NotConnected)
                }
            }
    }
    return when (result) {
        is SignerResult.Ok -> {
            VehicleResult.Success(result.value)
        }

        is SignerResult.Fault -> {
            when (result.detail) {
                SessionState.NO_SESSION -> {
                    VehicleResult.Failure(VehicleError.NoSession)
                }

                SessionState.COUNTER_EXHAUSTED -> {
                    logger.log(
                        LogLevel.WARN,
                        TAG,
                    ) { "The anti-replay counter for $domain is exhausted; startSession() starts a new session" }
                    VehicleResult.Failure(VehicleError.NoSession)
                }

                else -> {
                    VehicleResult.Failure(result.toVehicleError())
                }
            }
        }
    }
}

private fun SignerResult<RoutableMessage>.needsReservation(): Boolean =
    this is SignerResult.Fault && detail == SessionState.RESERVATION_NEEDED

/** 캐시에 쓸 도메인 하나: [SessionState.CacheExport]와 그 도메인. */
internal class DomainCacheExport(
    val domain: Domain,
    val state: SessionState.CacheExport,
) {
    /** 포트에 넘길 항목. */
    val snapshot: SessionSnapshot get() = SessionSnapshot(domain, state.sessionInfo)
}

/**
 * `SessionCacheSync`가 캐시에 쓸 내용. [Dispatcher.exportSessions]와 같은 도메인을 내보내되(오래된 캐시로 판정된 세션은 빼고), 각
 * counter는 이미 확정한 예약보다 낮지 않고 [extend] 도메인만 예약 폭만큼 앞선다([SessionState.exportForCache]).
 */
internal suspend fun Dispatcher.exportForCache(extend: Domain?): List<DomainCacheExport> =
    Dispatcher.ALL_DOMAINS.mapNotNull { domain ->
        val session = session(domain) ?: return@mapNotNull null
        if (session.isReportedStale) null else session.exportForCache(domain == extend)?.let { DomainCacheExport(domain, it) }
    }

/** [exportForCache]로 내보내 실제로 캐시에 쓴 항목만 넘긴다: 그 counter까지를 확정한 예약으로 기억한다. */
internal suspend fun Dispatcher.commitReservations(written: List<DomainCacheExport>) {
    for (entry in written) session(entry.domain)?.commitReservation(entry.state)
}

/** [domain]의 다음 counter가 확정한 예약 안에 있는지. */
internal suspend fun Dispatcher.coversNextCounter(domain: Domain): Boolean = session(domain)?.coversNextCounter() == true

/**
 * 캐시 항목의 counter가 예약 하나도 남기지 못하는지: 예약은 `0xFFFFFFFF`에서 멈추므로 그런 항목은 곧 롤오버한다. 디코딩할 수 없는 항목은
 * 여기서 판단하지 않는다(복원이 `DECODING`으로 거부한다).
 */
@OptIn(InternalTeslableApi::class)
internal fun CachedSession.hasExhaustedCounter(): Boolean {
    val counter =
        SessionInfo.ADAPTER
            .decodeOrNull(sessionInfo)
            ?.counter
            ?.toUInt() ?: return false
    return counter > UInt.MAX_VALUE - SessionState.COUNTER_RESERVATION
}
