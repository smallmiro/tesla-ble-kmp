package dev.mirolab.teslable.model

/**
 * `Vehicle.addKeyRequest`의 진행 상태(FR-023, ADR-0009). Go는 요청을 보내고 응답을 읽지 않는다 — 이 이벤트는 원본에 없는 동작이며,
 * 실차에서 형식이 확인되기 전까지 해석하지 못한 프레임은 [Unknown]으로 원시 바이트를 그대로 준다. 끝 이벤트([isTerminal]) 뒤 Flow가 끝난다.
 * 등록 확정은 `Vehicle.sessionInfo(publicKey, INFOTAINMENT)`로 확인한다.
 */
public sealed interface PairingEvent {
    /** 요청을 보냈다. 사용자가 중앙 콘솔에 키카드를 태그하고 화면에서 승인해야 한다. */
    public data object Sent : PairingEvent

    /** 차량이 카드 태그를 기다린다(`commandStatus.operationStatus == WAIT`). */
    public data object WaitingForTap : PairingEvent

    /** 차량이 등록을 받아들였다(`whitelistOperationStatus` NONE). Infotainment 동기화는 수십 초 더 걸릴 수 있다. */
    public data object Accepted : PairingEvent

    /** 차량이 거부했다(`KeychainRejected`, `UnknownKeychainCode`, `VcsecRejected`, `UnknownVcsecError`). */
    public data class Rejected(
        /** 원인. */
        val error: VehicleError,
    ) : PairingEvent

    /** 해석하지 못한 프레임(실차 형식 확인용). */
    public class Unknown(
        raw: ByteArray,
    ) : PairingEvent {
        private val bytes = raw.copyOf()

        /** 받은 바이트(사본). */
        public val raw: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?): Boolean = other is Unknown && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Unknown(${bytes.size}B)"
    }

    /** 보내지 못했다(연결 없음, 쓰기 실패, 잘못된 인자). */
    public data class Failed(
        /** 원인. */
        val error: VehicleError,
    ) : PairingEvent

    /** 시간 안에 끝 응답이 오지 않았다. 등록됐는지는 `sessionInfo`로 확인한다. */
    public data object TimedOut : PairingEvent
}

/** 이 이벤트 뒤에 더 오는 것이 없는지. */
public val PairingEvent.isTerminal: Boolean
    get() =
        this is PairingEvent.Accepted || this is PairingEvent.Rejected || this is PairingEvent.Failed || this is PairingEvent.TimedOut
