package dev.mirolab.teslable.application.pairing

import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.vcsec.VcsecResponses
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.protocol.decodeOrNull

/**
 * 원본에 없음 — 감싸지 않은 VCSEC 프레임 → 페어링 이벤트(ADR-0009, SDD §4, 설계 구체화 4). null이면 페어링과 무관한 프레임(무시).
 * 순서: 해석 불가 → `Unknown`; `nominalError` → `Rejected`; `commandStatus` 없음 → 무시; `whitelistOperationStatus` → `Accepted`/`Rejected`;
 * `WAIT` → `WaitingForTap`; 그 밖(OK 중간 응답, legacy ERROR) → 무시.
 */
@OptIn(InternalTeslableApi::class)
internal object PairingEvents {
    fun interpret(frame: ByteArray): PairingEvent? {
        val from = FromVCSECMessage.ADAPTER.decodeOrNull(frame) ?: return PairingEvent.Unknown(frame)
        from.nominalError?.let { return PairingEvent.Rejected(VcsecResponses.nominalError(it)) }
        val status = from.commandStatus ?: return null
        status.whitelistOperationStatus?.let { whitelist ->
            return VcsecResponses.keychainError(whitelist)?.let { PairingEvent.Rejected(it) } ?: PairingEvent.Accepted
        }
        return if (status.operationStatus == OperationStatus_E.OPERATIONSTATUS_WAIT) PairingEvent.WaitingForTap else null
    }
}
