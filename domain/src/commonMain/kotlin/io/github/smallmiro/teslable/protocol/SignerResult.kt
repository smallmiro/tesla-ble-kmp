// Ported from vehicle-command@a4b43c1 internal/authentication/error.go (Apache-2.0) — Error{Code, Info}
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.universalmessage.MessageFault_E

/** [Signer] 연산 결과. Go `authentication.Error`처럼 `MessageFault` 코드로 실패 원인을 표시한다. */
public sealed interface SignerResult<out T> {
    /** 성공. */
    public data class Ok<T>(
        /** 결과 값. */
        public val value: T,
    ) : SignerResult<T>

    /** 실패. 로그·재시도 판단용이며 앱에는 노출되지 않는다(M2가 `VehicleError`로 바꾼다). */
    public data class Fault(
        /** Go `Error.Code`. */
        public val fault: MessageFault_E,
        /** Go `Error.Info` (영어). */
        public val detail: String,
    ) : SignerResult<Nothing>
}
