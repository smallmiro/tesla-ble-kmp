// Ported from vehicle-command@a4b43c1 pkg/protocol/error.go (Apache-2.0) — GetError
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.Session_Info_Status
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.VehicleError
import okio.IOException

/** RoutableMessage 계층의 오류 해석 (`08-errors.md` §1.4). 페이로드 안의 애플리케이션 오류는 M2 이후 별도 해석. */
public object ResponseClassifier {
    /**
     * Go `GetError` 순서 그대로: `signed_message_fault` → `session_info.status` → `operation_status`.
     * 오류가 없으면 null. `OPERATIONSTATUS_ERROR`는 Go처럼 오류로 취급하지 않는다(error.go 257~263).
     */
    public fun protocolError(message: RoutableMessage): VehicleError? {
        val status = message.signedMessageStatus
        val fault = status?.signed_message_fault ?: MessageFault_E.MESSAGEFAULT_ERROR_NONE
        if (fault != MessageFault_E.MESSAGEFAULT_ERROR_NONE) {
            return if (fault == MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) {
                VehicleError.KeyNotPaired
            } else {
                VehicleError.ProtocolFault(fault)
            }
        }
        val encodedInfo = message.session_info
        if (encodedInfo != null) {
            val info =
                try {
                    SessionInfo.ADAPTER.decode(encodedInfo.toByteArray())
                } catch (e: IOException) {
                    return VehicleError.BadResponse("session info: ${e.message ?: "undecodable"}")
                }
            when (info.status) {
                Session_Info_Status.SESSION_INFO_STATUS_OK -> Unit
                Session_Info_Status.SESSION_INFO_STATUS_KEY_NOT_ON_WHITELIST -> return VehicleError.KeyNotPaired
            }
        }
        return when (status?.operation_status ?: OperationStatus_E.OPERATIONSTATUS_OK) {
            OperationStatus_E.OPERATIONSTATUS_OK -> null
            OperationStatus_E.OPERATIONSTATUS_WAIT -> VehicleError.Busy
            OperationStatus_E.OPERATIONSTATUS_ERROR -> null
        }
    }
}
