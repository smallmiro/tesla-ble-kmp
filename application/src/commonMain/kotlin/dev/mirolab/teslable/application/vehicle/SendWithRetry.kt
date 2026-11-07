// Ported from vehicle-command@a4b43c1 pkg/vehicle/vehicle.go (Apache-2.0) — DefaultFlags, getReceiver, trySend, Send
package dev.mirolab.teslable.application.vehicle

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.dispatcher.retryWhileRetriable
import dev.mirolab.teslable.application.dispatcher.valueOr
import dev.mirolab.teslable.application.dispatcher.withAttemptTimeout
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.protocol.ResponseClassifier
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration

/**
 * Go `Vehicle.Send`/`trySend`: 응답 하나를 기다리는 명령(Infotainment, 세션정보 조회). `shouldRetry()` 오류(BUSY, INVALID_SIGNATURE,
 * INCORRECT_EPOCH, TIME_EXPIRED, `Busy`, 일시 전송 오류 …)면 [Dispatcher.retryInterval] 뒤 **새 counter·nonce·expires_at으로 재인가**해
 * 다시 보낸다(FR-101; `Dispatcher.send`가 매번 `Signer.encrypt`). `mayHaveSucceeded` 오류는 재시도하지 않는다(NFR-007).
 *
 * 시간 초과(D29, `withAttemptTimeout`): 요청의 BLE 쓰기를 부르기 전(전송 직렬화 대기, 쓰기 전 검사, 재시도할 응답을 받고 다음 시도를
 * 기다리는 동안)이면 `Failure(Timeout(afterSend = false))`(Go `Dispatcher.Send`·`Vehicle.Send`의 ctx 분기), 쓰기를 부른 뒤면 쓰기가 끝나지
 * 않았어도 `Uncertain(Timeout(afterSend = true))`(Go `trySend`의 `PossibleSuccess: true`). Go는 BLE 쓰기에서 ctx를 무시하므로(`ble.go:108`)
 * 쓰는 도중의 만료가 없다. 쓰기를 부른 뒤 실패해 같은 바이트를 다시 쓰는 동안(`WriteFailed`는 일시 오류, SDD §6)의 만료도 `Uncertain`이다 —
 * Go의 BLE 연결은 실패한 쓰기를 다시 시도하지 않고 그 오류로 끝내지만(`ble.go:121`의 오류는 `ShouldRetry`가 false), 실패한
 * write-with-response도 차량에 닿았을 수 있다(ADR-0010 2026-11-07 개정).
 *
 * VCSEC는 거부한다(FR-049 직렬화 우회 차단, 설계 구체화 13): VCSEC 명령은 `VcsecCommands`로만 보낸다.
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
        if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
            return VehicleResult.Failure(VehicleError.InvalidArgument("VCSEC commands must go through VcsecCommands"))
        }
        val payloadCopy = payload.toByteString()
        return withAttemptTimeout(timeout) { setAfterSend ->
            retryWhileRetriable(dispatcher.retryInterval) { trySend(domain, payloadCopy, auth, flags, setAfterSend) }
        }
    }

    /**
     * Go `trySend` + `getReceiver`. [afterSend]는 요청의 첫 BLE 쓰기를 부른 때부터(전송 재시도 동안에도) 응답을 받을 때까지 true다 —
     * 그사이 취소되면 true로 남아 `Uncertain`이 된다. 응답을 받으면 false로 돌아가므로 재시도 대기 중 만료는 `Failure`다(Go `vehicle.go:250-252`).
     */
    private suspend fun trySend(
        domain: Domain,
        payload: ByteString,
        auth: AuthMethod,
        flags: Int,
        afterSend: (Boolean) -> Unit,
    ): VehicleResult<RoutableMessage> {
        afterSend(false)
        val message =
            RoutableMessage(
                to_destination = Destination(domain = domain),
                protobuf_message_as_bytes = payload,
                flags = flags,
            )
        val pending = dispatcher.send(message, auth, timeouts.commandLifetime) { afterSend(true) }.valueOr { return it.toResult() }
        afterSend(true) // 넘겨준 요청은 쓰기를 부른 요청이다(알리지 않는 전송 구현에 대비)
        val response = pending.use { it.receive() }
        afterSend(false)
        ResponseClassifier.protocolError(response)?.let { return it.toResult() }
        return VehicleResult.Success(response)
    }

    /** 상수. */
    public companion object {
        /** Go `DefaultFlags = 1 << FLAG_ENCRYPT_RESPONSE`: 응답 암호화 요청(FR-010, 항상 설정). */
        public const val DEFAULT_FLAGS: Int = 2
    }
}
