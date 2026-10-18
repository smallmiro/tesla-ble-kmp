// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0) — executeRKEAction, wakeupRKE, RemoteDrive, AutoSecureVehicle,
// executeClosureAction; pkg/vehicle/actions.go ActuateTrunk, OpenTrunk, CloseTrunk, OpenFrunk, OpenTonneau, CloseTonneau, StopTonneau
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.vcsec.ClosureMoveRequest
import com.tesla.generated.vcsec.ClosureMoveType_E
import com.tesla.generated.vcsec.RKEAction_E
import dev.mirolab.teslable.application.vehicle.CommandTimeouts
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import kotlin.time.Duration

/**
 * VCSEC 제어 명령(FR-040~FR-047). 모두 GCM 인증 + [VcsecResponses.COMMAND_STATUS_ABSENT](`commandStatus`가 없는 메시지가 최종 성공)이며
 * [VcsecCommands]의 연결당 직렬화·재시도·D29 시간 제한을 따른다. 세션이 없으면 `Failure(NoSession)`(핸드셰이크는 호출자 몫, Go와 같음).
 * `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 상태를 확인한다(FR-102).
 */
@Suppress("TooManyFunctions") // FR-040~047 명령 12종이 공개 API 1:1
public class VcsecControl internal constructor(
    private val vcsec: VcsecCommands,
    private val timeouts: CommandTimeouts,
) {
    /** 잠금(FR-040). */
    public suspend fun lock(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> = rke(RKEAction_E.RKE_ACTION_LOCK, timeout)

    /** 해제(FR-041). */
    public suspend fun unlock(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        rke(RKEAction_E.RKE_ACTION_UNLOCK, timeout)

    /** 깨우기(FR-042, Go `wakeupRKE`). VCSEC 세션만 필요. */
    public suspend fun wakeUp(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        rke(RKEAction_E.RKE_ACTION_WAKE_VEHICLE, timeout)

    /** 키 없이 운전(FR-043). */
    public suspend fun remoteDrive(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        rke(RKEAction_E.RKE_ACTION_REMOTE_DRIVE, timeout)

    /** Model X 문 닫고 잠금(FR-044). */
    public suspend fun autoSecureVehicle(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        rke(RKEAction_E.RKE_ACTION_AUTO_SECURE_VEHICLE, timeout)

    /** 트렁크 열기(FR-045) — [actuateTrunk]와 같은 토글 페이로드. */
    public suspend fun openTrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> = actuateTrunk(timeout)

    /** 트렁크 토글(FR-045, `rearTrunk = MOVE`). */
    public suspend fun actuateTrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE), timeout)

    /** 트렁크 닫기(FR-045). 전동 트렁크 차량만. */
    public suspend fun closeTrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE), timeout)

    /** 프렁크 열기(FR-046). 원격으로 닫는 명령은 없다. */
    public suspend fun openFrunk(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(frontTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE), timeout)

    /** 토노 열기(FR-047, Cybertruck). */
    public suspend fun openTonneau(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN), timeout)

    /** 토노 닫기(FR-047). */
    public suspend fun closeTonneau(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE), timeout)

    /** 토노 멈춤(FR-047). */
    public suspend fun stopTonneau(timeout: Duration = timeouts.commandTimeout): VehicleResult<Unit> =
        closure(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_STOP), timeout)

    private suspend fun rke(
        action: RKEAction_E,
        timeout: Duration,
    ): VehicleResult<Unit> = execute(VcsecPayloads.rke(action), timeout)

    private suspend fun closure(
        request: ClosureMoveRequest,
        timeout: Duration,
    ): VehicleResult<Unit> = execute(VcsecPayloads.closure(request), timeout)

    private suspend fun execute(
        payload: ByteArray,
        timeout: Duration,
    ): VehicleResult<Unit> =
        when (val result = vcsec.execute(payload, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = timeout)) {
            is VehicleResult.Success -> VehicleResult.Success(Unit)
            is VehicleResult.Failure -> result
            is VehicleResult.Uncertain -> result
        }
}
