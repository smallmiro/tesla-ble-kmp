// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0) — getVCSECResult (+ per-connection serialization, FR-049)
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.dispatcher.retryWhileRetriable
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.application.dispatcher.withAttemptTimeout
import io.github.smallmiro.teslable.application.vehicle.CommandTimeouts
import io.github.smallmiro.teslable.application.vehicle.SendWithRetry
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.AuthMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration

/**
 * Go `getVCSECResult`: VCSEC에 보내고 [VcsecResponses.readUntil]로 종료까지 읽는다. `shouldRetry()` 오류(`Busy` 포함)면 [Dispatcher.retryInterval]
 * 뒤 새 요청(새 routing_address·counter)으로 다시 보낸다([io.github.smallmiro.teslable.application.dispatcher.retryWhileRetriable]).
 * 한 연결의 VCSEC 명령은 [serial]로 직렬화한다(FR-049, SDD §5) — 시간 제한([io.github.smallmiro.teslable.application.dispatcher.withAttemptTimeout])은
 * 락 대기를 포함하며, 락 대기 중 만료는 `Failure(Timeout(afterSend = false))`, 응답 대기 중 만료는 `Uncertain(Timeout(afterSend = true))`다.
 * [payload]는 첫 시도 전에 딱 한 번만 [ByteString]으로 복사해 얼린다(N1, `SendWithRetry.send`와 같은 이유).
 */
public class VcsecCommands(
    private val dispatcher: Dispatcher,
    private val timeouts: CommandTimeouts = CommandTimeouts(),
) {
    private val serial = Mutex()

    /** 페이로드(`UnsignedMessage` 바이트)를 보내고 [done]이 최종으로 판정한 메시지를 돌려준다. */
    public suspend fun execute(
        payload: ByteArray,
        auth: AuthMethod,
        done: VcsecResponses.TerminalTest,
        flags: Int = SendWithRetry.DEFAULT_FLAGS,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<FromVCSECMessage> {
        val payloadCopy = payload.toByteString()
        return withAttemptTimeout(timeout) { setAwaiting ->
            serial.withLock {
                retryWhileRetriable(dispatcher.retryInterval) { attempt(payloadCopy, auth, done, flags, setAwaiting) }
            }
        }
    }

    /** Go `getReceiver` + [VcsecResponses.readUntil]. [awaiting]은 응답을 기다리는 동안만 true(취소되면 true로 남아 `Uncertain`이 된다). */
    private suspend fun attempt(
        payload: ByteString,
        auth: AuthMethod,
        done: VcsecResponses.TerminalTest,
        flags: Int,
        awaiting: (Boolean) -> Unit,
    ): VehicleResult<FromVCSECMessage> {
        awaiting(false)
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                protobuf_message_as_bytes = payload,
                flags = flags,
            )
        val pending = dispatcher.send(message, auth, timeouts.commandLifetime).valueOr { return it.toResult() }
        awaiting(true)
        val result = pending.use { VcsecResponses.readUntil(it, done) }
        awaiting(false)
        return result
    }
}
