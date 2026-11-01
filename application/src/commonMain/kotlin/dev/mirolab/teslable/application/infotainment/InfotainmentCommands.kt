// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — getCarServerResponse, executeCarServerAction;
// pkg/vehicle/state.go GetState
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.valueOr
import dev.mirolab.teslable.application.vehicle.SendWithRetry
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.AuthMethod
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

    /**
     * Go `GetState`(FR-033): [category] 하나를 조회한다. 세션을 시작하거나 차를 깨우지 않으므로 세션이 없으면 `Failure(NoSession)`이고,
     * 시간 초과·재시도·거부·해석 규칙은 [execute]와 같다. 응답의 내용을 [category]와 맞춰 보지 않는다(Go와 같다).
     *
     * 응답에 `vehicleData`가 없으면 `Success(VehicleData())`다. Go는 `(nil, nil)`을 돌려주지만 nil 메시지의 필드는 빈 메시지처럼 읽히고,
     * 호출자는 어차피 필드마다 null을 확인하므로 반환 타입을 nullable로 넓히지 않는다(M5 계획 결정 기록).
     *
     * 결과에는 위치·목적지가 들어 있을 수 있다. `toString()`을 포함해 로그에 남기지 않는다(FR-112).
     */
    public suspend fun getState(
        category: StateCategory,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<VehicleData> =
        when (val result = execute(category.toAction(), timeout)) {
            is VehicleResult.Success -> VehicleResult.Success(result.value.vehicleData ?: VehicleData())
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }
}
