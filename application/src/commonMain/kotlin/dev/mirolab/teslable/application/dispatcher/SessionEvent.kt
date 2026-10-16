package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain

/** 디스패처 세션 사건(설계 구체화 14). 수신 코루틴이 `tryEmit`으로 낸다(suspend 없음, SDD §5). */
public sealed interface SessionEvent {
    /** 서명된 세션정보로 세션이 만들어지거나 갱신됨 → 캐시 저장 대상. */
    public data class Updated(
        /** 도메인. */
        public val domain: Domain,
    ) : SessionEvent

    /** 캐시에서 복원한 세션이 차량과 맞지 않음(차량 키 교체 등) → 그 도메인 캐시 삭제 대상. 세션당 한 번. */
    public data class StaleCache(
        /** 도메인. */
        public val domain: Domain,
    ) : SessionEvent
}
