package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.protocol.SignerResult

/** `Signer`의 fault를 공개 오류로. `UNKNOWN_KEY_ID`는 Go `GetError`처럼 [VehicleError.KeyNotPaired]. */
internal fun SignerResult.Fault.toVehicleError(): VehicleError =
    if (fault == MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) VehicleError.KeyNotPaired else VehicleError.ProtocolFault(fault)

/** 실패·불확실이면 그 오류, 성공이면 null. */
internal fun <T> VehicleResult<T>.errorOrNull(): VehicleError? =
    when (this) {
        is VehicleResult.Success -> null
        is VehicleResult.Failure -> error
        is VehicleResult.Uncertain -> error
    }

/** 성공이면 값, 아니면 [onError]로 빠져나간다(`return`용). */
internal inline fun <T> VehicleResult<T>.valueOr(onError: (VehicleError) -> Nothing): T =
    when (this) {
        is VehicleResult.Success -> value
        is VehicleResult.Failure -> onError(error)
        is VehicleResult.Uncertain -> onError(error)
    }
