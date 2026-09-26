package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.Session_Info_Status
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.shouldRetry
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

    // Wire는 범위를 벗어난 enum 값을 만나면 필드를 기본값으로 두고 원본 바이트를 unknownFields로 옮긴다.
    // 아래 네 테스트는 Go GetError의 `default: ErrUnknown` 분기를 원시 바이트로 재현한다.

    @Test
    fun unknownFaultCarriesRawCodeAsUnknownFault() { // error.go GetError 241: &RoutableMessageError{Code: fault}
        // tag=2(signed_message_fault, varint) value=99: 0x10 0x63
        val unknownFault = MessageStatus.ADAPTER.decode("1063".decodeHex())
        val error = assertNotNull(ResponseClassifier.protocolError(RoutableMessage(signedMessageStatus = unknownFault)))
        assertEquals(VehicleError.UnknownFault(99), error)
        // error.go 225~230 RoutableMessageError.Error(): 등록되지 않은 코드 → "unrecognized error code %d"
        assertEquals("unrecognized error code 99", error.message)
        // Temporary(): retriableErrors에 없음. MayHaveSucceeded(): NONE·RESPONSE_MTU_EXCEEDED가 아님.
        assertFalse(error.temporary)
        assertFalse(error.mayHaveSucceeded)
        assertFalse(error.shouldRetry())
        // 여러 바이트 varint: 300 = 0xAC 0x02
        val wide = MessageStatus.ADAPTER.decode("10ac02".decodeHex())
        assertEquals(VehicleError.UnknownFault(300), ResponseClassifier.protocolError(RoutableMessage(signedMessageStatus = wide)))
    }

    @Test
    fun unknownOperationStatusBecomesUnknownResponse() {
        // tag=1(operation_status, varint) value=99: 0x08 0x63
        val unknownOp = MessageStatus.ADAPTER.decode("0863".decodeHex())
        val message = RoutableMessage(signedMessageStatus = unknownOp)
        assertEquals(VehicleError.UnknownResponse, ResponseClassifier.protocolError(message))
    }

    @Test
    fun unknownSessionInfoStatusBecomesUnknownResponse() {
        // tag=5(SessionInfo.status, varint) value=99: 0x28 0x63
        val message = RoutableMessage(session_info = "2863".decodeHex())
        assertEquals(VehicleError.UnknownResponse, ResponseClassifier.protocolError(message))
    }

    @Test
    fun knownFaultWinsOverUnknownOperationStatus() {
        // operation_status=99(unknown) 뒤에 signed_message_fault=1(BUSY): 0x08 0x63 0x10 0x01
        val status = MessageStatus.ADAPTER.decode("08631001".decodeHex())
        val message = RoutableMessage(signedMessageStatus = status)
        assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_BUSY), ResponseClassifier.protocolError(message))
    }
}
