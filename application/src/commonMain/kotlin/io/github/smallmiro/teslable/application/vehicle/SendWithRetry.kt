// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — DefaultFlags, getReceiver, trySend, Send
package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.dispatcher.retryWhileRetriable
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.application.dispatcher.withAttemptTimeout
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration

/**
 * Go `Vehicle.Send`/`trySend`: 응답 하나를 기다리는 명령(Infotainment, 세션정보 조회). `shouldRetry()` 오류(BUSY, INVALID_SIGNATURE,
 * INCORRECT_EPOCH, TIME_EXPIRED, `Busy`, 일시 전송 오류 …)면 [Dispatcher.retryInterval] 뒤 **새 counter·nonce·expires_at으로 재인가**해
 * 다시 보낸다(FR-101; `Dispatcher.send`가 매번 `Signer.encrypt`). `mayHaveSucceeded` 오류는 재시도하지 않는다(NFR-007).
 *
 * 시간 초과(D29, `withAttemptTimeout`): 전송 전·재시도 대기 중이면
 * `Failure(Timeout(afterSend = false))`(Go `Dispatcher.Send`·`Vehicle.Send`의 ctx 분기), 응답 대기 중이면
 * `Uncertain(Timeout(afterSend = true))`(Go `trySend`의 `PossibleSuccess: true`).
 */
public class SendWithRetry(
    private val dispatcher: Dispatcher,
    private val timeouts: CommandTimeouts = CommandTimeouts(),
) {
    /** 기본 명령 시간(D29). */
    public val commandTimeout: Duration get() = timeouts.commandTimeout

    /**
     * 페이로드를 [domain]으로 보내고 응답 하나를 돌려준다. 프로토콜 계층 오류([ResponseClassifier.protocolError])는 값으로.
     * 외부 취소는 `CancellationException`으로 전파되고 `PendingRequest`는 `use`에서 풀린다. [payload]는 첫 시도 전에
     * 딱 한 번만 [ByteString]으로 복사해 얼린다(Go `Send` 236~238행과 동일) — `trySend`가 재시도마다 다시 복사하면
     * 호출자가 `send`를 호출한 뒤(예: 첫 시도가 실패해 재시도를 기다리는 동안) 넘겨준 배열을 제자리에서 바꿀 경우
     * 그 변경이 재시도에 새어 들어간다(`retriesReuseThePayloadCopiedBeforeTheFirstAttempt`가 고정한다).
     */
    public suspend fun send(
        domain: Domain,
        payload: ByteArray,
        auth: AuthMethod,
        flags: Int = DEFAULT_FLAGS,
        timeout: Duration = commandTimeout,
    ): VehicleResult<RoutableMessage> {
        val payloadCopy = payload.toByteString()
        return withAttemptTimeout(timeout) { setAwaiting ->
            retryWhileRetriable(dispatcher.retryInterval) { trySend(domain, payloadCopy, auth, flags, setAwaiting) }
        }
    }

    /** Go `trySend` + `getReceiver`. [awaiting]은 응답을 기다리는 동안만 true(취소되면 true로 남아 `Uncertain`이 된다). */
    private suspend fun trySend(
        domain: Domain,
        payload: ByteString,
        auth: AuthMethod,
        flags: Int,
        awaiting: (Boolean) -> Unit,
    ): VehicleResult<RoutableMessage> {
        awaiting(false)
        val message =
            RoutableMessage(
                to_destination = Destination(domain = domain),
                protobuf_message_as_bytes = payload,
                flags = flags,
            )
        val pending = dispatcher.send(message, auth, timeouts.commandLifetime).valueOr { return it.toResult() }
        awaiting(true)
        val response = pending.use { it.receive() }
        awaiting(false)
        ResponseClassifier.protocolError(response)?.let { return it.toResult() }
        return VehicleResult.Success(response)
    }

    /** 상수. */
    public companion object {
        /** Go `DefaultFlags = 1 << FLAG_ENCRYPT_RESPONSE`: 응답 암호화 요청(FR-010, 항상 설정). */
        public const val DEFAULT_FLAGS: Int = 2
    }
}
