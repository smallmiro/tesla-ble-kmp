// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0)
// Send/StartSession retry loop and trySend/StartSession ctx.Done() branches, factored out as retryWhileRetriable/withAttemptTimeout
package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.universalmessage.MessageFault_E
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.shouldRetry
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.protocol.SignerResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** 재시도 판단의 로그 태그. 전송 재시도 로그("Retrying transmission after error")와 같은 `Dispatcher`다. */
private const val RETRY_TAG = "Dispatcher"

/** `Signer`의 fault를 공개 오류로: `UNKNOWN_KEY_ID`는 [VehicleError.KeyNotPaired], `INSUFFICIENT_PRIVILEGES`는 전용 타입. */
internal fun SignerResult.Fault.toVehicleError(): VehicleError =
    when (fault) {
        MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID -> VehicleError.KeyNotPaired
        MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES -> VehicleError.InsufficientPrivileges
        else -> VehicleError.ProtocolFault(fault)
    }

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
 * [retryWhileRetriable]이 새 시도를 시작하는 오류: `shouldRetry()`이고 [VehicleError.KeyStoreError.Locked]가 아니다.
 *
 * 예외(Go에 없음, FR-037·#96): [VehicleError.KeyStoreError.Locked]는 temporary(`shouldRetry()`가 true)지만 되풀이하지 않는다 — 잠금
 * 해제는 사용자 몫이라 기다려도 풀리지 않고, 되풀이하면 시간 제한 뒤 `Timeout`으로 바뀌어 앱이 원인을 알 수 없다.
 */
internal fun VehicleError.startsNewAttempt(): Boolean = shouldRetry() && this != VehicleError.KeyStoreError.Locked

/**
 * Go `Vehicle.Send`/`Vehicle.StartSession`의 재시도 루프: [attempt]가 성공하거나 오류가 새 시도를 시작하지 않으면([startsNewAttempt])
 * 그 결과를 바로 돌려주고, 그렇지 않으면 [interval] 만큼 기다린 뒤 다시 [attempt]한다.
 * `SendWithRetry.send`, `VehicleSession.startSession`, Task 10 `VcsecCommands`가 공유한다.
 */
internal suspend fun <T> retryWhileRetriable(
    interval: Duration,
    attempt: suspend () -> VehicleResult<T>,
): VehicleResult<T> {
    while (true) {
        val result = attempt()
        val error = result.errorOrNull() ?: return result
        if (!error.startsNewAttempt()) return result
        delay(interval)
    }
}

/**
 * 요청의 쓰기가 실패해 같은 바이트를 다시 보낸 뒤([PendingRequest.hadFailedWrite])에는 이 오류를 담은 응답이 요청의 결과를 정하지 않는다
 * (원본에 없음, ADR-0010 2026-11-08 개정). 다시 보낸 사본은 첫 사본의 중복일 수 있고, 차량의 답은 그 중복에 대한 판정일 수 있기 때문이다:
 * 재전송 검사의 `INVALID_TOKEN_OR_COUNTER`(Go `verifier.go:217-220`)나 `REPEATED_COUNTER`, 첫 사본이 바꾼 상태에 대한 `UNKNOWN_KEY_ID`
 * (자기 키를 지운 경우)·`INSUFFICIENT_PRIVILEGES` 등. 해당하는 것: 새 시도를 시작할 오류([startsNewAttempt]) 전부와 메시지에 대한 프로토콜
 * 계층의 판정 전부([VehicleError.ProtocolFault], [VehicleError.KeyNotPaired], [VehicleError.InsufficientPrivileges],
 * [VehicleError.UnknownFault] — 응답 경로에서는 `ResponseClassifier.protocolError`만 만든다). `mayHaveSucceeded` 오류는 이미 `Uncertain`이라
 * 결과를 정한다. 페이로드 안의 애플리케이션 결과(`VcsecRejected`, `KeychainRejected`, `InfotainmentRejected` …)도 결과를 정한다: 그 사본이
 * counter 검사를 통과했다는 뜻이고 같은 counter의 사본은 하나만 통과하므로 다른 사본은 실행되지 않았다. 값만 보는 판정이라 완전한 계층
 * 분류는 아니다 — 프로토콜 계층에서 온 `UnknownResponse`·`BadResponse`는 가려내지 못해 그대로 결과로 둔다(SDD §12 알려진 한계).
 */
internal fun VehicleError.isInconclusiveAfterFailedWrite(): Boolean {
    if (mayHaveSucceeded) return false
    return startsNewAttempt() ||
        this is VehicleError.ProtocolFault ||
        this == VehicleError.KeyNotPaired ||
        this == VehicleError.InsufficientPrivileges ||
        this is VehicleError.UnknownFault
}

/**
 * [read]로 이 요청의 응답을 읽어 결과를 돌려준다. 단 이 요청의 쓰기가 실패해 같은 바이트를 다시 보냈고([PendingRequest.hadFailedWrite])
 * 반복해도 안전한 조회가 아니면([repeatSafe]가 false), 결과를 정하지 않는 오류([isInconclusiveAfterFailedWrite])는 경고만 남기고 건너뛰어
 * 같은 요청의 다음 응답을 읽는다 — 새로 인가한 사본을 보내지 않는다(실패로 보인 첫 쓰기가 실행됐을 수 있다, NFR-007). 첫 사본의 응답이
 * 늦게 오면 그것이 결과고, 끝내 오지 않으면 호출자의 `withAttemptTimeout`이 `Uncertain(Timeout(afterSend = true))`로 끝낸다(호출자는
 * 그동안 `afterSend`를 true로 둔다). 호출자는 [read] 전체를 한 번의 `use` 안에서 부른다. 원본에 없음: Go의 BLE 연결은 실패한 쓰기를
 * 다시 보내지 않고 그 오류로 끝낸다(`pkg/connector/ble/ble.go:121`, ADR-0010 2026-11-08 개정).
 */
internal suspend fun <T> PendingRequest.awaitOutcome(
    repeatSafe: Boolean,
    logger: TeslaLogger,
    read: suspend () -> VehicleResult<T>,
): VehicleResult<T> {
    while (true) {
        val result = read()
        val error = result.errorOrNull() ?: return result
        if (repeatSafe || !hadFailedWrite || !error.isInconclusiveAfterFailedWrite()) return result
        logger.log(LogLevel.WARN, RETRY_TAG) {
            "[attempt=$attempt] Not signing a new copy: a write of this request failed and the same bytes were sent again, " +
                "so this reply (${error.message}) may answer a possible duplicate. Waiting for the first copy's reply until the timeout"
        }
    }
}

/**
 * Go `Send`/`trySend`의 `ctx.Done()` 분기(D29, ADR-0010): [timeout] 안에 [block]이 끝나지 못하면
 * [VehicleError.Timeout]을 값으로 돌려준다. `afterSend`는 [block]이 `setAfterSend`로 마지막에 넘긴 값이다 — 호출자는 요청의 첫 BLE
 * 쓰기를 부를 때 true를 넘기고(쓰기 확인이 아니라 쓰기 호출, ADR-0010 2026-11-07 개정) 응답 처리를 마치면 false로 돌린다. true일 때
 * 초과하면 [VehicleResult.Uncertain], 아니면 [VehicleResult.Failure].
 */
internal suspend fun <T> withAttemptTimeout(
    timeout: Duration,
    block: suspend (setAfterSend: (Boolean) -> Unit) -> VehicleResult<T>,
): VehicleResult<T> {
    var afterSend = false
    val result = withTimeoutOrNull(timeout) { block { afterSend = it } }
    return result ?: VehicleError.Timeout(afterSend = afterSend).toResult()
}
