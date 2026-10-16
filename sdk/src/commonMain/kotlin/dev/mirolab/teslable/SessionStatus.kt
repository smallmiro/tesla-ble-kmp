package dev.mirolab.teslable

/** 도메인 세션 상태(SDD §2.3 `Vehicle.sessions`). */
public sealed interface SessionStatus {
    /** 세션 없음. */
    public data object None : SessionStatus

    /** 핸드셰이크 중. */
    public data object Handshaking : SessionStatus

    /** 인증 명령을 보낼 수 있음. */
    public data class Ready(
        /** 세션 캐시에서 복원했는지. */
        val fromCache: Boolean,
    ) : SessionStatus
}
