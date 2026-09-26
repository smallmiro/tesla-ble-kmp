package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.Session_Info_Status
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.VehicleError
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ResponseClassifierTest {
    private fun status(
        fault: MessageFault_E = MessageFault_E.MESSAGEFAULT_ERROR_NONE,
        op: OperationStatus_E = OperationStatus_E.OPERATIONSTATUS_OK,
    ) = MessageStatus(operation_status = op, signed_message_fault = fault)

    @Test
    fun faultWinsOverEverythingElse() { // error.go GetError 1단계
        val notOnWhitelist = SessionInfo(status = Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST)
        val message =
            RoutableMessage(
                signedMessageStatus = status(fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY, op = OperationStatus_E.OPERATIONSTATUS_WAIT),
                session_info = SessionInfo.ADAPTER.encode(notOnWhitelist).toByteString(),
            )
        assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_BUSY), ResponseClassifier.protocolError(message))
    }

    @Test
    fun unknownKeyIdBecomesKeyNotPaired() {
        val message = RoutableMessage(signedMessageStatus = status(fault = MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID))
        assertEquals(VehicleError.KeyNotPaired, ResponseClassifier.protocolError(message))
    }

    @Test
    fun sessionInfoStatusIsCheckedSecond() { // 2단계
        val notOnWhitelist = SessionInfo(status = Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST)
        assertEquals(
            VehicleError.KeyNotPaired,
            ResponseClassifier.protocolError(RoutableMessage(session_info = SessionInfo.ADAPTER.encode(notOnWhitelist).toByteString())),
        )
        val ok = SessionInfo(status = Session_Info_Status.SESSION_INFO_STATUS_OK, counter = 5)
        assertNull(ResponseClassifier.protocolError(RoutableMessage(session_info = SessionInfo.ADAPTER.encode(ok).toByteString())))
    }

    @Test
    fun undecodableSessionInfoIsBadResponse() {
        val garbage = byteArrayOf(0x12).toByteString() // publicKey(2번, length-delimited) 태그 뒤에 길이가 없음 → Wire EOF → 디코딩 실패
        assertIs<VehicleError.BadResponse>(ResponseClassifier.protocolError(RoutableMessage(session_info = garbage)))
    }

    @Test
    fun operationStatusIsCheckedLast() { // 3단계
        val ok = RoutableMessage(signedMessageStatus = status(op = OperationStatus_E.OPERATIONSTATUS_OK))
        assertNull(ResponseClassifier.protocolError(ok))
        val wait = RoutableMessage(signedMessageStatus = status(op = OperationStatus_E.OPERATIONSTATUS_WAIT))
        assertEquals(VehicleError.Busy, ResponseClassifier.protocolError(wait))
        // error.go 257~263: OPERATIONSTATUS_ERROR는 빈 case로 빠져나가 nil — 페이로드 계층이 해석한다
        val error = RoutableMessage(signedMessageStatus = status(op = OperationStatus_E.OPERATIONSTATUS_ERROR))
        assertNull(ResponseClassifier.protocolError(error))
        assertNull(ResponseClassifier.protocolError(RoutableMessage()))
    }
}
