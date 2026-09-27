// Ported from vehicle-command@a4b43c1 pkg/protocol/error.go (Apache-2.0) — Error, CommandError, RoutableMessageError, retriableErrors
package io.github.smallmiro.teslable.model

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.vcsec.WhitelistOperation_information_E

/**
 * 명령 실패 원인의 `sealed` 계층(FR-103, SDD §6). Go `protocol.Error`의 두 불리언을 그대로 노출한다.
 * 메시지는 영어이며 원인 코드를 담는다(D19).
 */
public sealed interface VehicleError {
    /** 사람이 읽을 수 있는 영어 메시지. */
    public val message: String

    /** 차량이 명령을 실행했을 가능성이 있으면 true (응답 미수신 등). true면 절대 재시도하지 않는다. */
    public val mayHaveSucceeded: Boolean

    /** 일시적 조건이면 true (재시도로 해결될 수 있음). */
    public val temporary: Boolean

    /** RoutableMessage 계층의 fault (`08-errors.md` §3.1). Go `RoutableMessageError`. */
    public data class ProtocolFault(
        /** 차량이 보고한 fault 코드. */
        public val fault: MessageFault_E,
    ) : VehicleError {
        override val message: String get() = fault.name
        override val mayHaveSucceeded: Boolean
            get() =
                fault == MessageFault_E.MESSAGEFAULT_ERROR_NONE ||
                    fault == MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED
        override val temporary: Boolean get() = fault in RETRIABLE_FAULTS
    }

    /** `UNKNOWN_KEY_ID` 또는 session_info `KEY_NOT_ON_WHITELIST` — 키 페어링 필요. Go `ErrKeyNotPaired`. */
    public data object KeyNotPaired : VehicleError {
        override val message: String = "vehicle rejected request: your public key has not been paired with the vehicle"
        override val mayHaveSucceeded: Boolean = false
        override val temporary: Boolean = false
    }

    /** `operation_status == WAIT` 또는 VCSEC `commandStatus WAIT`. Go `ErrBusy`. */
    public data object Busy : VehicleError {
        override val message: String = "vehicle busy or finishing wake-up"
        override val mayHaveSucceeded: Boolean = false
        override val temporary: Boolean = true
    }

    /** 이 라이브러리의 proto 스냅샷에 없는 `signed_message_fault` 코드. Go `RoutableMessageError{Code}`(등록되지 않은 코드). */
    public data class UnknownFault(
        /** 차량이 보낸 `MessageFault_E` 원시 varint(이 라이브러리의 proto 스냅샷보다 새 펌웨어). */
        public val rawCode: Int,
    ) : VehicleError {
        override val message: String get() = "unrecognized error code $rawCode"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }

    /** 이 라이브러리의 proto 스냅샷에 없는 `whitelistOperationInformation` 코드. Go `KeychainError{Code}`(등록되지 않은 코드). */
    public data class UnknownKeychainCode(
        /** 차량이 보낸 원시 varint. */
        public val rawCode: Int,
    ) : VehicleError {
        override val message: String get() = "keychain operation failed: unrecognized code $rawCode"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }

    /** 인식할 수 없는 `session_info.status` 또는 `operation_status`(코드 없음). Go `ErrUnknown`. */
    public data object UnknownResponse : VehicleError {
        override val message: String = "vehicle responded with an unrecognized status code"
        override val mayHaveSucceeded: Boolean = false
        override val temporary: Boolean = false
    }

    /** 연결 전/해제 후 전송. Go `ErrNotConnected`. */
    public data object NotConnected : VehicleError {
        override val message: String = "vehicle not connected"
        override val mayHaveSucceeded: Boolean = false
        override val temporary: Boolean = false
    }

    /** 세션 없이 인증 명령 전송. Go `ErrNoSession`. */
    public data object NoSession : VehicleError {
        override val message: String = "cannot send authenticated command before establishing a vehicle session"
        override val mayHaveSucceeded: Boolean = false
        override val temporary: Boolean = false
    }

    /** 개인키 없음. Go `ErrRequiresKey`. */
    public data object RequiresKey : VehicleError {
        override val message: String = "no private key available"
        override val mayHaveSucceeded: Boolean = false
        override val temporary: Boolean = false
    }

    /** 응답 파싱 실패. VCSEC 응답 파싱 실패는 `mayHaveSucceeded = true`(Go `vcsec.go`). Go `ErrBadResponse`. */
    public data class BadResponse(
        /** 무엇을 파싱하지 못했는지. */
        public val detail: String,
        override val mayHaveSucceeded: Boolean = false,
    ) : VehicleError {
        override val message: String get() = "invalid response: $detail"
        override val temporary: Boolean get() = false
    }

    /** 키체인 작업 거부. Go `KeychainError`. */
    public data class KeychainRejected(
        /** VCSEC `whitelistOperationInformation` 코드. */
        public val code: WhitelistOperation_information_E,
    ) : VehicleError {
        override val message: String get() = "keychain operation failed: ${code.name}"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }

    /** VCSEC가 인증은 통과시켰으나 실행을 거부함(`nominalError`). Go `NominalVCSECError`. */
    public data class VcsecRejected(
        /** VCSEC `GenericError_E`. */
        public val error: GenericError_E,
    ) : VehicleError {
        override val message: String get() = "vcsec could not execute command: ${error.name}"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }

    /** Infotainment `actionStatus.result == ERROR`. `reason`은 `result_reason.plain_text`("unspecified error" 기본). */
    public data class InfotainmentRejected(
        /** 차량이 준 사유 문자열. */
        public val reason: String,
    ) : VehicleError {
        override val message: String get() = "car could not execute command: $reason"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }

    /** 전송 계층 오류(어댑터가 변환). */
    public sealed interface TransportError : VehicleError {
        /** 스캔 시간 초과. */
        public data object ScanTimeout : TransportError {
            override val message: String = "no vehicle advertisement found before the scan timeout"
            override val mayHaveSucceeded: Boolean = false
            override val temporary: Boolean = true
        }

        /** 광고가 connectable이 아님(BLE 슬롯 초과). Go `ErrMaxConnectionsExceeded`. */
        public data object MaxConnectionsExceeded : TransportError {
            override val message: String = "the vehicle is already connected to the maximum number of BLE devices"
            override val mayHaveSucceeded: Boolean = false
            override val temporary: Boolean = false
        }

        /** 연결 실패. */
        public data class ConnectFailed(
            /** 플랫폼 원인 요약. */
            public val cause: String,
        ) : TransportError {
            override val message: String get() = "connect failed: $cause"
            override val mayHaveSucceeded: Boolean get() = false
            override val temporary: Boolean get() = true
        }

        /** 연결이 끊김. */
        public data object Disconnected : TransportError {
            override val message: String = "bluetooth connection lost"
            override val mayHaveSucceeded: Boolean = false
            override val temporary: Boolean = false
        }

        /** 블루투스가 꺼져 있음. */
        public data object BluetoothOff : TransportError {
            override val message: String = "bluetooth is powered off"
            override val mayHaveSucceeded: Boolean = false
            override val temporary: Boolean = false
        }

        /** BLE 권한 없음(FR-006). */
        public data object PermissionDenied : TransportError {
            override val message: String = "bluetooth permission not granted"
            override val mayHaveSucceeded: Boolean = false
            override val temporary: Boolean = false
        }

        /** 쓰기 실패(재시도 가능). */
        public data class WriteFailed(
            /** 플랫폼 원인 요약. */
            public val cause: String,
        ) : TransportError {
            override val message: String get() = "bluetooth write failed: $cause"
            override val mayHaveSucceeded: Boolean get() = false
            override val temporary: Boolean get() = true
        }
    }

    /** 키스토어 오류(어댑터가 변환). */
    public sealed interface KeyStoreError : VehicleError {
        /** 하드웨어 키 저장소를 쓸 수 없음. */
        public data object HardwareUnavailable : KeyStoreError {
            override val message: String = "hardware-backed key storage unavailable"
            override val mayHaveSucceeded: Boolean = false
            override val temporary: Boolean = false
        }

        /** 별칭에 해당하는 키 없음. */
        public data object KeyNotFound : KeyStoreError {
            override val message: String = "vehicle key not found"
            override val mayHaveSucceeded: Boolean = false
            override val temporary: Boolean = false
        }

        /** 플랫폼 키스토어 실패. */
        public data class PlatformFailure(
            /** 플랫폼 원인 요약. */
            public val cause: String,
        ) : KeyStoreError {
            override val message: String get() = "key store failure: $cause"
            override val mayHaveSucceeded: Boolean get() = false
            override val temporary: Boolean get() = false
        }
    }

    /** 명령 시간 초과(ADR-0010). 전송 후면 실행됐을 수 있다. */
    public data class Timeout(
        /** 전송 후 응답 대기 중 초과했으면 true. */
        public val afterSend: Boolean,
    ) : VehicleError {
        override val message: String get() = if (afterSend) "timed out waiting for vehicle response" else "timed out before sending command"
        override val mayHaveSucceeded: Boolean get() = afterSend
        override val temporary: Boolean get() = true
    }

    /** 호출 인자 오류(PIN 형식, 범위 등). */
    public data class InvalidArgument(
        /** 무엇이 잘못됐는지. */
        public val detail: String,
    ) : VehicleError {
        override val message: String get() = "invalid argument: $detail"
        override val mayHaveSucceeded: Boolean get() = false
        override val temporary: Boolean get() = false
    }

    /** 상수. */
    public companion object {
        /** Go `retriableErrors`: 세션 갱신 후 재시도로 해결될 수 있는 fault 8개. */
        public val RETRIABLE_FAULTS: Set<MessageFault_E> =
            setOf(
                MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
                MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT,
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
                MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL,
                MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
                MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
                MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG,
            )
    }
}

/** Go `ShouldRetry`: 실행됐을 가능성이 없고 일시적일 때만 true. */
public fun VehicleError.shouldRetry(): Boolean = !mayHaveSucceeded && temporary

/** `mayHaveSucceeded`면 [VehicleResult.Uncertain], 아니면 [VehicleResult.Failure] (ADR-0006). */
public fun VehicleError.toResult(): VehicleResult<Nothing> =
    if (mayHaveSucceeded) VehicleResult.Uncertain(this) else VehicleResult.Failure(this)
