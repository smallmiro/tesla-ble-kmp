// Ported from vehicle-command@a4b43c1 pkg/vehicle/security.go (Apache-2.0) — AddKeyWithRole, RemoveKey; vcsec.go executeWhitelistOperation;
// vehicle.go SessionInfo
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.dispatcher.valueOr
import dev.mirolab.teslable.application.dispatcher.withAttemptTimeout
import dev.mirolab.teslable.application.vehicle.CommandTimeouts
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.protocol.ResponseClassifier
import dev.mirolab.teslable.protocol.decodeOrNull
import kotlin.time.Duration

/** 화이트리스트 관리와 등록 확인(FR-024, FR-026, FR-027). whitelist 작업은 [VcsecCommands]의 직렬화를 공유한다. */
@OptIn(InternalTeslableApi::class)
public class KeyManagement internal constructor(
    private val dispatcher: Dispatcher,
    private val vcsec: VcsecCommands,
    private val timeouts: CommandTimeouts,
) {
    /** Go `AddKeyWithRole`: 등록된 Owner 키로 다른 공개키를 추가(인증). [role]이 `UNKNOWN`이면 보내지 않고 `InvalidArgument`. */
    public suspend fun addKey(
        publicKey: PublicKeyBytes,
        role: KeyRole,
        formFactor: KeyFormFactor,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<Unit> {
        val wireRole =
            VcsecInfoMapping.wireRole(role)
                ?: return VehicleResult.Failure(VehicleError.InvalidArgument("role UNKNOWN cannot be enrolled"))
        return whitelist(VcsecPayloads.addKey(publicKey, wireRole, VcsecInfoMapping.wireFormFactor(formFactor)), timeout)
    }

    /** Go `RemoveKey`(인증). Driver 키는 차량이 거부한다(`InsufficientPrivileges` 또는 `KeychainRejected`). */
    public suspend fun removeKey(
        publicKey: PublicKeyBytes,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<Unit> = whitelist(VcsecPayloads.removeKey(publicKey), timeout)

    /**
     * Go `SessionInfo`: [publicKey]에 대한 [domain]의 세션정보. 등록돼 있지 않으면 `Failure(KeyNotPaired)`. 응답 대기 중 시간 초과는
     * `Uncertain(Timeout(afterSend = true))`(M3 조회와 같다). 다시 보내지 않는다(Go도 한 번 보낸다).
     */
    public suspend fun sessionInfo(
        publicKey: PublicKeyBytes,
        domain: Domain,
        timeout: Duration = timeouts.commandTimeout,
    ): VehicleResult<SessionInfoSummary> =
        withAttemptTimeout(timeout) { setAwaiting ->
            val pending = dispatcher.querySessionInfo(domain, publicKey).valueOr { return@withAttemptTimeout it.toResult() }
            setAwaiting(true)
            pending.use { interpret(it.receive()) }
        }

    private suspend fun whitelist(
        payload: ByteArray,
        timeout: Duration,
    ): VehicleResult<Unit> =
        when (val result = vcsec.execute(payload, AuthMethod.GCM, VcsecResponses.WHITELIST_OPERATION_COMPLETE, timeout = timeout)) {
            is VehicleResult.Success -> VehicleResult.Success(Unit)
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }

    /** Go `SessionInfo`의 응답 해석: `GetError` → `session_info` 없음은 `ErrBadResponse` → 디코딩. */
    private fun interpret(reply: RoutableMessage): VehicleResult<SessionInfoSummary> {
        ResponseClassifier.protocolError(reply)?.let { return it.toResult() }
        val encoded = reply.session_info ?: return VehicleResult.Failure(VehicleError.BadResponse("missing session_info"))
        val info =
            SessionInfo.ADAPTER.decodeOrNull(encoded.toByteArray())
                ?: return VehicleResult.Failure(VehicleError.BadResponse("session info: undecodable"))
        val vehicleKey =
            try {
                PublicKeyBytes(info.publicKey.toByteArray())
            } catch (ignored: IllegalArgumentException) {
                null
            }
        return VehicleResult.Success(SessionInfoSummary(info.counter.toUInt(), info.clock_time.toUInt(), vehicleKey))
    }
}
