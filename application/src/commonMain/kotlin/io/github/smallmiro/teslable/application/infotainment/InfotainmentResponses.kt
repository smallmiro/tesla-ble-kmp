// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — getCarServerResponse (response interpretation)
package io.github.smallmiro.teslable.application.infotainment

import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.protocol.decodeOrNull

/** Infotainment 응답 해석(`03-protocol.md` §10, `08-errors.md` §3.6). `CarServer.OperationStatus_E`는 OK=0, ERROR=1이다(VCSEC와 다르다). */
@OptIn(InternalTeslableApi::class)
public object InfotainmentResponses {
    private const val UNSPECIFIED_ERROR = "unspecified error"

    /**
     * Go `getCarServerResponse`: 파싱 실패 → `BadResponse(mayHaveSucceeded = true)`; `actionStatus.result == ERROR` →
     * [VehicleError.InfotainmentRejected]`(plain_text 또는 "unspecified error")`; 그 외(모르는 값 포함, 설계 구체화 11)는 성공. null payload는 빈 응답.
     * 컨트롤러 판정 R2: Wire는 손상된 메시지 타입 필드에 `IllegalStateException`도 던지므로 `decodeOrNull`로 값으로 받는다(ADR-0006).
     */
    public fun interpret(payload: ByteArray?): VehicleResult<Response> {
        val response =
            Response.ADAPTER.decodeOrNull(payload ?: ByteArray(0))
                ?: return VehicleError.BadResponse("unable to parse vehicle response: undecodable", mayHaveSucceeded = true).toResult()
        val status = response.actionStatus
        if (status?.result == OperationStatus_E.OPERATIONSTATUS_ERROR) {
            val reason = status.result_reason?.plain_text?.takeIf { it.isNotEmpty() } ?: UNSPECIFIED_ERROR
            return VehicleResult.Failure(VehicleError.InfotainmentRejected(reason))
        }
        return VehicleResult.Success(response)
    }
}
