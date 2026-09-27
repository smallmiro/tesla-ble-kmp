// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0) — unmarshalVCSECResponse, isTerminalTest, readUntil,
// isWhitelistOperationComplete, executeRKEAction/getVCSECInfo done
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.WhitelistOperation_status
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.application.dispatcher.PendingRequest
import io.github.smallmiro.teslable.application.dispatcher.valueOr
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.protocol.ResponseClassifier
import io.github.smallmiro.teslable.protocol.decodeOrNull
import io.github.smallmiro.teslable.protocol.unknownVarint

/** VCSEC 응답 해석과 종료 판정(`03-protocol.md` §11, `08-errors.md` §3.5). */
@OptIn(InternalTeslableApi::class)
public object VcsecResponses {
    private const val TAG_WHITELIST_OPERATION_INFORMATION = 1

    /** Go `isTerminalTest`: 이 메시지로 끝낼지. */
    public fun interface TerminalTest {
        /** 판정. */
        public fun check(message: FromVCSECMessage): TerminalCheck
    }

    /** [TerminalTest]의 결과(Go `(bool, error)`). */
    public sealed interface TerminalCheck {
        /** 다음 메시지를 기다린다. */
        public data object Continue : TerminalCheck

        /** 이 메시지가 최종 성공. */
        public data object Done : TerminalCheck

        /** 이 메시지가 최종 실패. */
        public data class Fail(
            /** 원인. */
            public val error: VehicleError,
        ) : TerminalCheck
    }

    /** 정보 요청: 첫 메시지가 결과(Go `getVCSECInfo`의 `done`). */
    public val FIRST_MESSAGE: TerminalTest = TerminalTest { TerminalCheck.Done }

    /** RKE·closure: `commandStatus`가 없는 메시지가 최종(Go `executeRKEAction`/`executeClosureAction`의 `done`). */
    public val COMMAND_STATUS_ABSENT: TerminalTest =
        TerminalTest { if (it.commandStatus == null) TerminalCheck.Done else TerminalCheck.Continue }

    /** 화이트리스트 작업: `whitelistOperationStatus`가 있으면 종료, `NONE`이 아니면 실패(Go `isWhitelistOperationComplete`). */
    public val WHITELIST_OPERATION_COMPLETE: TerminalTest =
        TerminalTest { message ->
            val status = message.commandStatus?.whitelistOperationStatus ?: return@TerminalTest TerminalCheck.Continue
            keychainError(status)?.let { TerminalCheck.Fail(it) } ?: TerminalCheck.Done
        }

    /**
     * Go `unmarshalVCSECResponse`: 프로토콜 오류 → payload 종류 → 파싱 → `nominalError` → `commandStatus` 순.
     * payload가 없으면 빈 메시지(성공), 다른 oneof(세션정보)면 `BadResponse(mayHaveSucceeded = true)`, 파싱 실패도 `mayHaveSucceeded = true`
     * (컨트롤러 판정 R2: Wire는 손상된 메시지 타입 필드에 `IllegalStateException`도 던지므로 `decodeOrNull`로 값으로 받는다, ADR-0006).
     */
    public fun interpret(message: RoutableMessage): VehicleResult<FromVCSECMessage> {
        ResponseClassifier.protocolError(message)?.let { return it.toResult() }
        val payload = message.protobuf_message_as_bytes
        if (payload == null) {
            if (message.session_info == null && message.session_info_request == null) return VehicleResult.Success(FromVCSECMessage())
            return VehicleError.BadResponse("payload missing from vehicle response", mayHaveSucceeded = true).toResult()
        }
        val from =
            FromVCSECMessage.ADAPTER.decodeOrNull(payload.toByteArray())
                ?: return VehicleError.BadResponse("vcsec: undecodable response", mayHaveSucceeded = true).toResult()
        from.nominalError?.let { return VehicleResult.Failure(VehicleError.VcsecRejected(it.genericError)) }
        val status = from.commandStatus ?: return VehicleResult.Success(from)
        return commandStatusError(status)?.toResult() ?: VehicleResult.Success(from)
    }

    /** Go `readUntil`: [done]이 `Done`인 첫 메시지. 오류는 즉시. 시간 제한은 호출자([VcsecCommands.execute]). */
    public suspend fun readUntil(
        pending: PendingRequest,
        done: TerminalTest,
    ): VehicleResult<FromVCSECMessage> {
        while (true) {
            val from = interpret(pending.receive()).valueOr { return it.toResult() }
            when (val check = done.check(from)) {
                TerminalCheck.Continue -> Unit
                TerminalCheck.Done -> return VehicleResult.Success(from)
                is TerminalCheck.Fail -> return check.error.toResult()
            }
        }
    }

    /**
     * Go `switch status.GetOperationStatus()`: OK 통과, WAIT → [VehicleError.Busy], ERROR → whitelist 코드 또는 `signedMessageStatus` 없음 →
     * [VehicleError.UnknownResponse]. Go에 `default` 분기가 없으므로 Wire가 모르는 값(기본값 OK로 디코딩)은 통과한다(설계 구체화 11).
     */
    private fun commandStatusError(status: CommandStatus): VehicleError? =
        when (status.operationStatus) {
            OperationStatus_E.OPERATIONSTATUS_OK -> {
                null
            }

            OperationStatus_E.OPERATIONSTATUS_WAIT -> {
                VehicleError.Busy
            }

            OperationStatus_E.OPERATIONSTATUS_ERROR -> {
                val whitelist = status.whitelistOperationStatus
                val keychain = whitelist?.let { keychainError(it) }
                when {
                    keychain != null -> keychain
                    status.signedMessageStatus == null -> VehicleError.UnknownResponse
                    else -> null
                }
            }
        }

    /** Go `code != NONE → KeychainError{Code}`. Wire가 모르는 코드는 `unknownFields`에서 되찾아 [VehicleError.UnknownKeychainCode]. */
    private fun keychainError(status: WhitelistOperation_status): VehicleError? {
        status.unknownFields.unknownVarint(TAG_WHITELIST_OPERATION_INFORMATION)?.let { return VehicleError.UnknownKeychainCode(it) }
        val code = status.whitelistOperationInformation
        val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE
        return if (code == none) null else VehicleError.KeychainRejected(code)
    }
}
