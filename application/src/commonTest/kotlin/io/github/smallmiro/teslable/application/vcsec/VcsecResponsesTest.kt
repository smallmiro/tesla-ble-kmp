package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.errors.NominalError
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.SignedMessage_status
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.WhitelistOperation_status
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VcsecResponsesTest {
    private fun message(payload: ByteArray?): RoutableMessage = RoutableMessage(protobuf_message_as_bytes = payload?.toByteString())

    private fun message(from: FromVCSECMessage): RoutableMessage = message(from.encode())

    private fun commandStatus(
        status: OperationStatus_E,
        whitelist: WhitelistOperation_information_E? = null,
        signed: Boolean = false,
    ): FromVCSECMessage =
        FromVCSECMessage(
            commandStatus =
                CommandStatus(
                    operationStatus = status,
                    signedMessageStatus = if (signed) SignedMessage_status(counter = 1337) else null,
                    whitelistOperationStatus = whitelist?.let { WhitelistOperation_status(whitelistOperationInformation = it) },
                ),
        )

    @Test
    fun emptyPayloadIsSuccessAndOtherPayloadKindsAreUncertain() {
        // vcsec.go unmarshalVCSECResponse: Payload nil → 빈 FromVCSECMessage; 다른 oneof → "payload missing" (MHS = true)
        assertEquals(FromVCSECMessage(), assertIs<VehicleResult.Success<FromVCSECMessage>>(VcsecResponses.interpret(message(null))).value)
        val withSessionInfo = RoutableMessage(session_info = byteArrayOf(0x08, 0x01).toByteString())
        val uncertain = assertIs<VehicleResult.Uncertain>(VcsecResponses.interpret(withSessionInfo))
        assertEquals(VehicleError.BadResponse("payload missing from vehicle response", mayHaveSucceeded = true), uncertain.error)
    }

    @Test
    fun gibberishPayloadIsUncertainBadResponse() {
        // vcsec.go: proto.Unmarshal 실패 → CommandError{ErrBadResponse, PossibleSuccess: true}
        val result = assertIs<VehicleResult.Uncertain>(VcsecResponses.interpret(message(byteArrayOf(0xFF.toByte()))))
        assertIs<VehicleError.BadResponse>(result.error)
        assertTrue(result.error.mayHaveSucceeded)
    }

    @Test
    fun malformedPayloadThrowingIllegalStateExceptionIsUncertainBadResponse() {
        // Wire는 메시지 타입 필드가 잘못된 wire type을 실으면 IllegalStateException을 던진다(okio.IOException이 아니다) —
        // WireDecodingTest 참고. 여기 2바이트: 0x20 = (필드 4=commandStatus << 3) | 0(wire type VARINT), 0x01 = 그 varint
        // 값. commandStatus는 메시지 타입 필드라 decode()가 항상 decodeMessageOrMerge → beginMessage()를 부르는데,
        // 실제 wire type이 LENGTH_DELIMITED가 아니므로(VARINT다) beginMessage()가 IllegalStateException("Unexpected
        // call to beginMessage()")을 던진다(FromVCSECMessage.ADAPTER.decode 실측). decodeOrNull(ADR-0006)이 이것도
        // 값으로 바꿔야 한다.
        val result = assertIs<VehicleResult.Uncertain>(VcsecResponses.interpret(message(byteArrayOf(0x20, 0x01))))
        assertIs<VehicleError.BadResponse>(result.error)
        assertTrue(result.error.mayHaveSucceeded)
    }

    @Test
    fun protocolFaultWinsOverPayload() {
        val faulted =
            message(
                commandStatus(OperationStatus_E.OPERATIONSTATUS_OK),
            ).copy(signedMessageStatus = MessageStatus(signed_message_fault = MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES))
        assertEquals(
            VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES),
            assertIs<VehicleResult.Failure>(VcsecResponses.interpret(faulted)).error,
        )
    }

    @Test
    fun nominalErrorIsVcsecRejected() {
        val from = FromVCSECMessage(nominalError = NominalError(genericError = GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK))
        val result = assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(from)))
        assertEquals(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK), result.error)
    }

    @Test
    fun commandStatusRulesMatchGo() {
        // 08-errors.md §3.5 5단계
        assertIs<VehicleResult.Success<FromVCSECMessage>>(
            VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK))),
        )
        assertEquals(
            VehicleError.Busy,
            assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_WAIT)))).error,
        )
        val full = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
        assertEquals(
            VehicleError.KeychainRejected(full),
            assertIs<VehicleResult.Failure>(
                VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, full))),
            ).error,
        )
        assertEquals(
            VehicleError.UnknownResponse,
            assertIs<VehicleResult.Failure>(
                VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR))),
            ).error,
        )
        assertIs<VehicleResult.Success<FromVCSECMessage>>(
            VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, signed = true))),
        ) // 레거시 signedMessageStatus는 통과
        val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE
        assertEquals(
            VehicleError.UnknownResponse,
            assertIs<VehicleResult.Failure>(
                VcsecResponses.interpret(message(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, none))),
            ).error,
        )
    }

    @Test
    fun passesThroughUnknownVcsecOperationStatusLikeGo() {
        // 인계 항목 3 / 설계 구체화 11: Go switch에 default가 없어 모르는 operationStatus(7)는 통과한다. Wire는 기본값 OK + unknownFields.
        val raw = byteArrayOf(0x22, 0x02, 0x08, 0x07) // FromVCSECMessage.commandStatus(4, LEN 2){ operationStatus(1) = 7 }
        val result = assertIs<VehicleResult.Success<FromVCSECMessage>>(VcsecResponses.interpret(message(raw)))
        assertEquals(
            1,
            result.value.commandStatus
                ?.unknownFields
                ?.size
                ?.let { if (it > 0) 1 else 0 },
        )
    }

    @Test
    fun unknownWhitelistInformationCodeIsAFailureWithTheRawCode() {
        // Go: code != NONE → KeychainError{Code: 99}. Wire는 NONE으로 바꾸므로 unknownFields에서 되찾는다.
        // commandStatus{ operationStatus = ERROR(2), whitelistOperationStatus(3){ information(1) = 99 } }
        val raw = byteArrayOf(0x22, 0x06, 0x08, 0x02, 0x1a, 0x02, 0x08, 0x63)
        val result = assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(raw)))
        assertEquals(VehicleError.UnknownKeychainCode(99), result.error)
    }

    @Test
    fun unknownNominalErrorCodeStillFails() {
        // nominalError 존재 자체가 실패(Go GetNominalError() != nil). 코드는 M4(L58)에서 보존 여부 재검토.
        val raw = byteArrayOf(0xF2.toByte(), 0x02, 0x02, 0x08, 0x63) // FromVCSECMessage.nominalError(46, LEN 2){ genericError(1) = 99 }
        val result = assertIs<VehicleResult.Failure>(VcsecResponses.interpret(message(raw)))
        assertEquals(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_NONE), result.error)
    }

    @Test
    fun terminalTestsMatchGo() {
        // vcsec.go: 정보 요청은 첫 메시지, RKE/closure는 commandStatus == nil, whitelist는 whitelistOperationStatus != nil
        assertEquals(
            VcsecResponses.TerminalCheck.Done,
            VcsecResponses.FIRST_MESSAGE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK)),
        )
        assertEquals(
            VcsecResponses.TerminalCheck.Continue,
            VcsecResponses.COMMAND_STATUS_ABSENT.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK, signed = true)),
        )
        assertEquals(VcsecResponses.TerminalCheck.Done, VcsecResponses.COMMAND_STATUS_ABSENT.check(FromVCSECMessage()))
        assertEquals(
            VcsecResponses.TerminalCheck.Continue,
            VcsecResponses.WHITELIST_OPERATION_COMPLETE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK, signed = true)),
        )
        val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE
        assertEquals(
            VcsecResponses.TerminalCheck.Done,
            VcsecResponses.WHITELIST_OPERATION_COMPLETE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_OK, none)),
        )
        val slotsFull = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_KEYFOB_SLOTS_FULL
        val fail =
            assertIs<VcsecResponses.TerminalCheck.Fail>(
                VcsecResponses.WHITELIST_OPERATION_COMPLETE.check(commandStatus(OperationStatus_E.OPERATIONSTATUS_ERROR, slotsFull)),
            )
        assertEquals(VehicleError.KeychainRejected(slotsFull), fail.error)
        // operationStatus 미기재(기본값 OK), whitelistOperationStatus.information = 99(모르는 코드).
        // commandStatus(4, LEN 4){ whitelistOperationStatus(3, LEN 2){ information(1) = 99 } }
        val unknownCodeRaw = byteArrayOf(0x22, 0x04, 0x1a, 0x02, 0x08, 0x63)
        val unknownCodeFail =
            assertIs<VcsecResponses.TerminalCheck.Fail>(
                VcsecResponses.WHITELIST_OPERATION_COMPLETE.check(FromVCSECMessage.ADAPTER.decode(unknownCodeRaw)),
            )
        assertEquals(VehicleError.UnknownKeychainCode(99), unknownCodeFail.error)
    }
}
