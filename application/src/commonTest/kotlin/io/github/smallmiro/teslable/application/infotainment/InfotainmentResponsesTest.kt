package io.github.smallmiro.teslable.application.infotainment

import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.ResultReason
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InfotainmentResponsesTest {
    @Test
    fun okResultAndMissingPayloadAreSuccess() {
        // infotainment.go getCarServerResponse: ERROR가 아니면 성공; nil payload는 빈 Response
        val ok = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_OK))
        assertEquals(ok, assertIs<VehicleResult.Success<Response>>(InfotainmentResponses.interpret(ok.encode())).value)
        assertEquals(Response(), assertIs<VehicleResult.Success<Response>>(InfotainmentResponses.interpret(null)).value)
    }

    @Test
    fun errorResultIsInfotainmentRejectedWithReasonOrUnspecified() {
        val withReason =
            Response(
                actionStatus =
                    ActionStatus(
                        result = OperationStatus_E.OPERATIONSTATUS_ERROR,
                        result_reason = ResultReason(plain_text = "car is in drive"),
                    ),
            )
        assertEquals(
            VehicleError.InfotainmentRejected("car is in drive"),
            assertIs<VehicleResult.Failure>(InfotainmentResponses.interpret(withReason.encode())).error,
        )
        val withoutReason = Response(actionStatus = ActionStatus(result = OperationStatus_E.OPERATIONSTATUS_ERROR))
        assertEquals(
            VehicleError.InfotainmentRejected("unspecified error"),
            assertIs<VehicleResult.Failure>(InfotainmentResponses.interpret(withoutReason.encode())).error,
        )
        val emptyReason =
            Response(
                actionStatus =
                    ActionStatus(
                        result = OperationStatus_E.OPERATIONSTATUS_ERROR,
                        result_reason = ResultReason(plain_text = ""),
                    ),
            )
        assertEquals(
            VehicleError.InfotainmentRejected("unspecified error"),
            assertIs<VehicleResult.Failure>(InfotainmentResponses.interpret(emptyReason.encode())).error,
        )
    }

    @Test
    fun undecodablePayloadIsUncertainBadResponse() {
        // Go: CommandError{"unable to parse vehicle response", PossibleSuccess: true}
        val result = assertIs<VehicleResult.Uncertain>(InfotainmentResponses.interpret(byteArrayOf(0xFF.toByte())))
        assertIs<VehicleError.BadResponse>(result.error)
        assertTrue(result.error.mayHaveSucceeded)
    }

    @Test
    fun malformedPayloadThrowingIllegalStateExceptionIsUncertainBadResponse() {
        // 컨트롤러 판정 R2(ADR-0006): tag=1(actionStatus, 메시지 타입)를 varint(wire type 0)로 실은 2바이트가 실측으로
        // Wire의 IllegalStateException을 던진다(Response.ADAPTER.decode 실측, 태스크 리포트 참고) — okio.IOException이
        // 아니므로 decodeOrNull(ADR-0006)이 이것도 값으로 바꿔야 한다.
        val result = assertIs<VehicleResult.Uncertain>(InfotainmentResponses.interpret(byteArrayOf(0x08, 0x01)))
        assertIs<VehicleError.BadResponse>(result.error)
        assertTrue(result.error.mayHaveSucceeded)
    }

    @Test
    fun passesThroughUnknownActionResultLikeGo() {
        // 인계 항목 3 / 설계 구체화 11: Go는 result == ERROR만 검사하므로 모르는 값(5)은 성공이다
        val raw = byteArrayOf(0x0a, 0x02, 0x08, 0x05) // Response.actionStatus(1, LEN 2){ result(1) = 5 }
        assertIs<VehicleResult.Success<Response>>(InfotainmentResponses.interpret(raw))
    }
}
