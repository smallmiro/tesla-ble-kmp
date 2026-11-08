// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — getCarServerResponse, executeCarServerAction;
// pkg/vehicle/state.go GetState
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
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
    /**
     * [action]을 실행한다. 시간 초과·재시도 규칙은 [SendWithRetry.send]와 같다: 명령으로 다루므로 쓰기 실패 뒤에는 새 사본을 인가해 보내지
     * 않는다(ADR-0010 2026-11-08 개정). 조회는 [getState]를 쓴다.
     */
    public suspend fun execute(
        action: Action,
        timeout: Duration = send.commandTimeout,
    ): VehicleResult<Response> = interpret(send.send(Domain.DOMAIN_INFOTAINMENT, action.encode(), AuthMethod.GCM, timeout = timeout))

    /** Go `getCarServerResponse`의 해석: 전송·프로토콜 오류는 그대로, 응답이면 `CarServer.Response`로. */
    private fun interpret(sent: VehicleResult<RoutableMessage>): VehicleResult<Response> {
        val reply = sent.valueOr { return it.toResult() }
        return InfotainmentResponses.interpret(reply.protobuf_message_as_bytes?.toByteArray())
    }

    /**
     * Go `GetState`(FR-033): [category] 하나를 조회한다. 세션을 시작하거나 차를 깨우지 않으므로 세션이 없으면 `Failure(NoSession)`이고,
     * 시간 초과·거부·해석 규칙은 [execute]와 같다. 재시도는 반복해도 안전한 조회로 보낸다([SendWithRetry.query]): 쓰기가 실패해 같은 바이트를
     * 다시 보낸 뒤에도 Go처럼 재시도할 오류에 새 사본으로 다시 묻는다(ADR-0010 2026-11-08 개정의 면제 — 조회는 차량 상태를 바꾸지 않는다).
     * 응답의 내용을 [category]와 맞춰 보지 않는다(Go와 같다).
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
        when (val result = interpret(send.query(Domain.DOMAIN_INFOTAINMENT, category.toAction().encode(), AuthMethod.GCM, timeout))) {
            is VehicleResult.Success -> VehicleResult.Success(result.value.vehicleData ?: VehicleData())
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }
}
