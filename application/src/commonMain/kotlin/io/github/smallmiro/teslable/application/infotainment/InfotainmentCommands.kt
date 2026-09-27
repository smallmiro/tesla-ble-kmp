// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — getCarServerResponse, executeCarServerAction
package io.github.smallmiro.teslable.application.infotainment

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.application.vehicle.SendWithRetry
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.AuthMethod
import kotlin.time.Duration

/** Go `executeCarServerAction`/`getCarServerResponse`: `Action`을 Infotainment에 보내고(단일 응답, GCM) `Response`로 해석한다. M5가 명령별 빌더를 더한다. */
public class InfotainmentCommands(
    private val send: SendWithRetry,
) {
    /** [action]을 실행한다. 시간 초과·재시도 규칙은 [SendWithRetry.send]와 같다. */
    public suspend fun execute(
        action: Action,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Response> {
        val sent = send.send(Domain.DOMAIN_INFOTAINMENT, action.encode(), AuthMethod.GCM, timeout = timeout)
        val reply = sent.valueOr { return it.toResult() }
        return InfotainmentResponses.interpret(reply.protobuf_message_as_bytes?.toByteArray())
    }
}
