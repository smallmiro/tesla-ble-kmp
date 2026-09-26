// Ported from vehicle-command@a4b43c1 pkg/protocol/error.go (Apache-2.0) — GetError
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.Session_Info_Status
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.VehicleError
import okio.ByteString
import okio.IOException

/** RoutableMessage 계층의 오류 해석 (`08-errors.md` §1.4). 페이로드 안의 애플리케이션 오류는 M2 이후 별도 해석. */
public object ResponseClassifier {
    // MessageStatus, SessionInfo의 proto 필드 번호 (domain/src/commonMain/proto).
    private const val TAG_MESSAGE_STATUS_OPERATION_STATUS = 1
    private const val TAG_MESSAGE_STATUS_SIGNED_MESSAGE_FAULT = 2
    private const val TAG_SESSION_INFO_STATUS = 5

    /**
     * Go `GetError` 순서 그대로: `signed_message_fault` → `session_info.status` → `operation_status`.
     * 오류가 없으면 null. `OPERATIONSTATUS_ERROR`는 Go처럼 오류로 취급하지 않는다(error.go 257~263).
     *
     * Wire는 proto에 없는(범위를 벗어난) enum 값을 만나면 해당 필드를 그 enum의 첫 상수(기본값)로 남기고
     * 원본 정수를 메시지의 `unknownFields`로 옮긴다. 그 결과 신형 펌웨어가 보낸 미인식 코드가 조용히
     * 성공(`OK`)으로 오분류될 수 있다. 이 함수는 세 필드(`signed_message_fault`, `session_info.status`,
     * `operation_status`) 각각의 태그가 `unknownFields`에 있는지 확인해 Go의 분기를 재현한다. 모르는 fault는
     * 원시 코드를 보존해 [VehicleError.UnknownFault]로(Go `RoutableMessageError{Code}`), 모르는 `session_info.status`와
     * `operation_status`는 코드 없이 [VehicleError.UnknownResponse]로(Go `default: ErrUnknown`) 알린다.
     */
    public fun protocolError(message: RoutableMessage): VehicleError? {
        val status = message.signedMessageStatus
        faultError(status)?.let { return it }
        sessionInfoError(message.session_info)?.let { return it }
        return operationStatusError(status)
    }

    /** Go `GetError` 1단계: `signed_message_fault`. 값이 없으면(NONE) 다음 단계로 넘어가도록 null. */
    private fun faultError(status: MessageStatus?): VehicleError? {
        val fault = status?.signed_message_fault ?: MessageFault_E.MESSAGEFAULT_ERROR_NONE
        if (fault != MessageFault_E.MESSAGEFAULT_ERROR_NONE) {
            return if (fault == MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) {
                VehicleError.KeyNotPaired
            } else {
                VehicleError.ProtocolFault(fault)
            }
        }
        // Go는 등록되지 않은 fault도 &RoutableMessageError{Code: fault}로 코드를 보존한다(error.go 241).
        val rawFault = status?.let { it.unknownFields.unknownVarint(TAG_MESSAGE_STATUS_SIGNED_MESSAGE_FAULT) }
        return rawFault?.let { VehicleError.UnknownFault(it) }
    }

    /** Go `GetError` 2단계: `session_info` 페이로드. 없으면(null) 다음 단계로 넘어가도록 null. */
    private fun sessionInfoError(encodedInfo: ByteString?): VehicleError? {
        if (encodedInfo == null) return null
        val info =
            try {
                SessionInfo.ADAPTER.decode(encodedInfo.toByteArray())
            } catch (e: IOException) {
                return VehicleError.BadResponse("session info: ${e.message ?: "undecodable"}")
            }
        if (info.unknownFields.unknownVarint(TAG_SESSION_INFO_STATUS) != null) {
            return VehicleError.UnknownResponse
        }
        return when (info.status) {
            Session_Info_Status.SESSION_INFO_STATUS_OK -> null
            Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST -> VehicleError.KeyNotPaired
        }
    }

    /** Go `GetError` 3단계: `operation_status`. `ERROR`는 Go처럼 nil(error.go 257~263). */
    private fun operationStatusError(status: MessageStatus?): VehicleError? {
        if (status != null && status.unknownFields.unknownVarint(TAG_MESSAGE_STATUS_OPERATION_STATUS) != null) {
            return VehicleError.UnknownResponse
        }
        return when (status?.operation_status ?: OperationStatus_E.OPERATIONSTATUS_OK) {
            OperationStatus_E.OPERATIONSTATUS_OK -> null
            OperationStatus_E.OPERATIONSTATUS_WAIT -> VehicleError.Busy
            OperationStatus_E.OPERATIONSTATUS_ERROR -> null
        }
    }
}
