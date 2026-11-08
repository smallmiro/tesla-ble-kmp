// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0) — getVCSECResult (+ per-connection serialization, FR-049)
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.dispatcher.awaitOutcome
import dev.mirolab.teslable.application.dispatcher.retryWhileRetriable
import dev.mirolab.teslable.application.dispatcher.valueOr
import dev.mirolab.teslable.application.dispatcher.withAttemptTimeout
import dev.mirolab.teslable.application.vehicle.CommandTimeouts
import dev.mirolab.teslable.application.vehicle.SendWithRetry
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.AuthMethod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration

/**
 * Go `getVCSECResult`: VCSEC에 보내고 [VcsecResponses.readUntil]로 종료까지 읽는다. `shouldRetry()` 오류(`Busy` 포함)면 [Dispatcher.retryInterval]
 * 뒤 새 요청(새 routing_address·counter)으로 다시 보낸다(`retryWhileRetriable`). 예외(원본에 없음, ADR-0010 2026-11-08 개정): 쓰기를 부른 뒤
 * 실패해 같은 바이트를 다시 보낸 명령([execute])은 새 요청을 보내지 않는다 — [query]로 보낸 조회만 Go처럼 다시 보낸다.
 * 한 연결의 VCSEC 명령은 [serial]로 직렬화한다(FR-049, SDD §5) — 시간 제한(`withAttemptTimeout`)은
 * 락 대기를 포함한다. 요청의 BLE 쓰기를 부르기 전(락 대기, 전송 직렬화 대기, 재시도할 응답을 받고 다음 시도를 기다리는 동안)의 만료는
 * `Failure(Timeout(afterSend = false))`, 그 뒤(쓰기가 끝나지 않았어도, 전송 재시도 중에도)의 만료는 `Uncertain(Timeout(afterSend = true))`다
 * (D29, ADR-0010 2026-11-07 개정).
 * [payload]는 첫 시도 전에 딱 한 번만 [ByteString]으로 복사해 얼린다 — 그러지 않으면 호출자가 첫 시도 뒤(예: 재시도를 기다리는 동안)
 * 넘겨준 배열을 제자리에서 바꿀 경우 그 변경이 재시도로 새어 들어간다(`SendWithRetry.send`와 같은 이유).
 *
 * SDD §5는 이 직렬화를 `Vehicle` 수준의 락으로 규정한다. [serial]은 이 클래스 인스턴스 하나에 묶여 있으므로, 같은
 * [Dispatcher](한 연결)를 감싼 [VcsecCommands]가 두 개 이상 존재하면 이 보장이 깨진다 — 그래서 생성자를 `internal`로 두어
 * `VehicleSession`이 연결마다 정확히 하나만 만들도록 강제한다.
 */
public class VcsecCommands internal constructor(
    private val dispatcher: Dispatcher,
    private val timeouts: CommandTimeouts = CommandTimeouts(),
) {
    private val serial = Mutex()

    /** [block]을 VCSEC 직렬화 락 안에서 실행한다(add-key-request 전송용 — 응답 대기는 락 밖). */
    internal suspend fun <T> exclusive(block: suspend () -> T): T = serial.withLock { block() }

    /**
     * 페이로드(`UnsignedMessage` 바이트)를 보내고 [done]이 최종으로 판정한 메시지를 돌려준다. 명령으로 다룬다: 요청의 쓰기가 실패해 같은
     * 바이트를 다시 보낸 뒤에는 재시도할 오류나 프로토콜 계층의 거부로 새 사본을 인가해 보내지 않고, 첫 사본의 응답을 시간 제한까지 기다린다
     * (`awaitOutcome`, ADR-0010 2026-11-08 개정 — 두 번 실행 방지, NFR-007).
     */
    public suspend fun execute(
        payload: ByteArray,
        auth: AuthMethod,
        done: VcsecResponses.TerminalTest,
        flags: Int = SendWithRetry.DEFAULT_FLAGS,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<FromVCSECMessage> = perform(Request(payload.toByteString(), auth, done, flags, repeatSafe = false), timeout)

    /**
     * 반복해도 안전한 조회(차량 상태를 바꾸지 않는 정보 요청)를 보낸다. [execute]와 같되 쓰기 실패 뒤에도 Go처럼 재시도할 오류에 새 요청을
     * 보낸다(ADR-0010 2026-11-08 개정의 면제). 감사를 거친 조회 경로(`VehicleInfoQueries`)만 부른다.
     */
    internal suspend fun query(
        payload: ByteArray,
        auth: AuthMethod,
        done: VcsecResponses.TerminalTest,
        timeout: Duration,
    ): VehicleResult<FromVCSECMessage> =
        perform(Request(payload.toByteString(), auth, done, SendWithRetry.DEFAULT_FLAGS, repeatSafe = true), timeout)

    /** 시도마다 같은 요청 내용. [payload]는 첫 시도 전에 한 번 얼린 사본이다. [repeatSafe]는 [query]로 보낸 조회만 true다. */
    private class Request(
        val payload: ByteString,
        val auth: AuthMethod,
        val done: VcsecResponses.TerminalTest,
        val flags: Int,
        val repeatSafe: Boolean,
    )

    private suspend fun perform(
        request: Request,
        timeout: Duration,
    ): VehicleResult<FromVCSECMessage> =
        withAttemptTimeout(timeout) { setAfterSend ->
            serial.withLock {
                retryWhileRetriable(dispatcher.retryInterval) { attempt(request, setAfterSend) }
            }
        }

    /**
     * Go `getReceiver` + [VcsecResponses.readUntil]. [afterSend]는 요청의 첫 BLE 쓰기를 부른 때부터(전송 재시도 동안에도) 응답 처리를
     * 마칠 때까지 true다 — 그사이 취소되면 true로 남아 `Uncertain`이 된다. 응답(재시도할 오류 포함)을 다 읽으면 false로 돌아가므로
     * 재시도 대기 중 만료는 `Failure`다(Go `vcsec.go:98-100`). 쓰기 실패 뒤 결과를 정하지 않는 응답을 건너뛰는 동안(`awaitOutcome`)은
     * true로 남는다.
     */
    private suspend fun attempt(
        request: Request,
        afterSend: (Boolean) -> Unit,
    ): VehicleResult<FromVCSECMessage> {
        afterSend(false)
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                protobuf_message_as_bytes = request.payload,
                flags = request.flags,
            )
        val pending = dispatcher.send(message, request.auth, timeouts.commandLifetime) { afterSend(true) }.valueOr { return it.toResult() }
        afterSend(true) // 넘겨준 요청은 쓰기를 부른 요청이다(알리지 않는 전송 구현에 대비)
        val result =
            pending.use { sent ->
                sent.awaitOutcome(request.repeatSafe, dispatcher.log) { VcsecResponses.readUntil(sent, request.done) }
            }
        afterSend(false)
        return result
    }
}
