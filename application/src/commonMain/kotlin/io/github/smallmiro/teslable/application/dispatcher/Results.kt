package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.protocol.SignerResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

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

/**
 * Go `Vehicle.Send`/`Vehicle.StartSession`의 재시도 루프(컨트롤러 판정 R1): [attempt]가 성공하거나 오류가
 * `shouldRetry()`가 아니면 그 결과를 바로 돌려주고, 그렇지 않으면 [interval] 만큼 기다린 뒤 다시 [attempt]한다.
 * `SendWithRetry.send`, `VehicleSession.startSession`, Task 10 `VcsecCommands`가 공유한다.
 */
internal suspend fun <T> retryWhileRetriable(
    interval: Duration,
    attempt: suspend () -> VehicleResult<T>,
): VehicleResult<T> {
    while (true) {
        val result = attempt()
        val error = result.errorOrNull() ?: return result
        if (!error.shouldRetry()) return result
        delay(interval)
    }
}

/**
 * Go `Send`/`trySend`의 `ctx.Done()` 분기(D29, ADR-0010, 컨트롤러 판정 R1): [timeout] 안에 [block]이 끝나지 못하면
 * [VehicleError.Timeout]을 값으로 돌려준다. `afterSend`는 [block]이 `setAwaiting`으로 마지막에 넘긴 값이다 — 응답을
 * 기다리는 동안 초과하면 true([VehicleResult.Uncertain]), 그 전이면 false([VehicleResult.Failure]).
 */
internal suspend fun <T> withAttemptTimeout(
    timeout: Duration,
    block: suspend (setAwaiting: (Boolean) -> Unit) -> VehicleResult<T>,
): VehicleResult<T> {
    var awaitingResponse = false
    val result = withTimeoutOrNull(timeout) { block { awaitingResponse = it } }
    return result ?: VehicleError.Timeout(afterSend = awaitingResponse).toResult()
}
