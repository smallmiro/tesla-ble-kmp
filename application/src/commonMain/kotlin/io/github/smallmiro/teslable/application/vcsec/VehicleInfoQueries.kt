// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0)
// getVCSECInfo; state.go BodyControllerState; security.go KeySummary, KeyInfoBySlot; cmd/tesla-control/commands.go list-keys
package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequest
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.UnsignedMessage
import io.github.smallmiro.teslable.application.vehicle.CommandTimeouts
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.port.AuthMethod
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * 인증 없는 VCSEC 조회(Go `getVCSECInfo` 계열, FR-025/FR-031/FR-032). [VcsecCommands]를 통해 연결당 직렬화(FR-049)를 공유한다.
 * 응답에 기대한 필드가 없으면 `BadResponse("missing …")`(Go는 nil, nil — 설계 구체화 12). [listKeys]는 시간 예산 하나를 나눠 쓴다.
 */
public class VehicleInfoQueries internal constructor(
    private val vcsec: VcsecCommands,
    private val timeouts: CommandTimeouts,
    private val timeSource: TimeSource,
) {
    /** Go `BodyControllerState`. */
    public suspend fun vehicleStatus(timeout: Duration = timeouts.commandTimeout): VehicleResult<VehicleStatus> =
        query(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS, null, timeout, "vehicleStatus") { message ->
            message.vehicleStatus?.let(VcsecInfoMapping::status)
        }

    /** Go `KeySummary`. */
    public suspend fun keySummary(timeout: Duration = timeouts.commandTimeout): VehicleResult<WhitelistSummary> =
        query(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO, null, timeout, "whitelistInfo") { message ->
            message.whitelistInfo?.let(VcsecInfoMapping::summary)
        }

    /** Go `KeyInfoBySlot`. */
    public suspend fun keyInfoBySlot(
        slot: Int,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<WhitelistEntry> =
        query(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO, slot, timeout, "whitelistEntryInfo") { message ->
            message.whitelistEntryInfo?.let(VcsecInfoMapping::entry)
        }

    /** Go `list-keys`: [keySummary] 후 사용 중인 슬롯마다 [keyInfoBySlot]. 첫 실패를 돌려준다(설계 구체화 12). [timeout]은 전체 예산. */
    public suspend fun listKeys(timeout: Duration = timeouts.commandTimeout): VehicleResult<List<WhitelistEntry>> {
        val deadline = timeSource.markNow() + timeout

        fun remaining(): Duration = -deadline.elapsedNow()
        val summary =
            when (val result = keySummary(remaining())) {
                is VehicleResult.Success -> result.value
                is VehicleResult.Failure -> return result
                is VehicleResult.Uncertain -> return result
            }
        val entries = ArrayList<WhitelistEntry>(summary.occupiedSlots.size)
        for (slot in summary.occupiedSlots) {
            val left = remaining()
            if (left <= Duration.ZERO) return VehicleResult.Failure(VehicleError.Timeout(afterSend = false))
            when (val result = keyInfoBySlot(slot, left)) {
                is VehicleResult.Success -> entries += result.value
                is VehicleResult.Failure -> return result
                is VehicleResult.Uncertain -> return result
            }
        }
        return VehicleResult.Success(entries)
    }

    private suspend fun <T> query(
        type: InformationRequestType,
        slot: Int?,
        timeout: Duration,
        field: String,
        extract: (FromVCSECMessage) -> T?,
    ): VehicleResult<T> =
        when (
            val result =
                vcsec.execute(
                    informationRequest(type, slot),
                    AuthMethod.NONE,
                    VcsecResponses.FIRST_MESSAGE,
                    timeout = timeout,
                )
        ) {
            is VehicleResult.Success -> {
                extract(result.value)?.let { VehicleResult.Success(it) }
                    ?: VehicleResult.Failure(VehicleError.BadResponse("missing $field"))
            }

            is VehicleResult.Failure -> {
                result
            }

            is VehicleResult.Uncertain -> {
                result
            }
        }

    /** 조립. */
    public companion object {
        /** Go `getVCSECInfo`의 페이로드: `UnsignedMessage{InformationRequest{type, slot?}}`. */
        public fun informationRequest(
            type: InformationRequestType,
            slot: Int? = null,
        ): ByteArray = UnsignedMessage(VCSEC_InformationRequest = InformationRequest(informationRequestType = type, slot = slot)).encode()
    }
}
