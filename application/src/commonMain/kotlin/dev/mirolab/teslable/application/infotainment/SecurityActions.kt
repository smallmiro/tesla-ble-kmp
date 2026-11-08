// Ported from vehicle-command@a4b43c1 pkg/vehicle/security.go (Apache-2.0) — IsValidPIN, ErrInvalidPIN, EnableValetMode,
// DisableValetMode, SetValetMode (incl. the "already off" suffix rule), ResetValetPin, SetSentryMode
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.server.VehicleControlResetValetPinAction
import com.tesla.generated.carserver.server.VehicleControlSetSentryModeAction
import com.tesla.generated.carserver.server.VehicleControlSetValetModeAction
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult

/**
 * Go security.go의 Infotainment 보안 `VehicleAction` 빌더(FR-084, FR-085). [InfotainmentCommands]가 `Action`에 담아 보낸다.
 *
 * 발레 PIN은 오류 문구·로그에 넣지 않는다(검증 실패의 `detail`은 Go `ErrInvalidPIN` 문구 그대로다).
 */
internal object SecurityActions {
    /** Go `ErrInvalidPIN`의 문구. PIN 자체는 담지 않는다. */
    const val INVALID_PIN: String = "PIN codes must be four digits"

    /** Go `SetValetMode`가 끄기에서 성공으로 보는 오류 접미사(대소문자 구분). */
    private const val ALREADY_OFF = "already off"

    /** Go `IsValidPIN`의 길이(`len(pin) == 4`, 바이트). ASCII 숫자만 받으므로 문자 수와 같다. */
    private const val PIN_LENGTH = 4

    /** Go `SetSentryMode`: `vehicleControlSetSentryModeAction{on}`. */
    fun sentryMode(enabled: Boolean): VehicleAction =
        VehicleAction(vehicleControlSetSentryModeAction = VehicleControlSetSentryModeAction(on = enabled))

    /** Go `EnableValetMode`(`SetValetMode(true, pin)`): PIN이 ASCII 숫자 네 개가 아니면 보내기 전에 `ErrInvalidPIN`. */
    fun enableValetMode(pin: String): VehicleResult<VehicleAction> {
        if (!isValidPin(pin)) return VehicleResult.Failure(VehicleError.InvalidArgument(INVALID_PIN))
        return VehicleResult.Success(
            VehicleAction(vehicleControlSetValetModeAction = VehicleControlSetValetModeAction(on = true, password = pin)),
        )
    }

    /** Go `DisableValetMode`(`SetValetMode(false, "")`): `vehicleControlSetValetModeAction{}` — 비어 있는 멤버다. */
    fun disableValetMode(): VehicleAction =
        VehicleAction(vehicleControlSetValetModeAction = VehicleControlSetValetModeAction(on = false, password = ""))

    /** Go `ResetValetPin`: `vehicleControlResetValetPinAction {}`. */
    fun resetValetPin(): VehicleAction = VehicleAction(vehicleControlResetValetPinAction = VehicleControlResetValetPinAction())

    /**
     * Go `SetValetMode`의 끄기 규칙(`security.go:63-65`): 차량의 거부(`InfotainmentRejected`) 사유가 "already off"로 끝나면 이미 원하는
     * 상태이므로 `Success`다. Go는 어떤 오류든 문자열 접미사를 보지만, 그런 문구는 차량이 준 사유에서만 나오므로 이 타입으로 좁힌다
     * (M5 계획 결정 기록, SDD §12). 대소문자를 구분하고, 그 밖의 결과는 그대로 돌려준다.
     */
    fun settleDisableValetMode(result: VehicleResult<Unit>): VehicleResult<Unit> {
        val rejected = (result as? VehicleResult.Failure)?.error as? VehicleError.InfotainmentRejected
        return if (rejected?.reason?.endsWith(ALREADY_OFF) == true) VehicleResult.Success(Unit) else result
    }

    /** Go `IsValidPIN`: 정확히 네 개의 ASCII `'0'`~`'9'`. `Char.isDigit()`은 다른 문자 체계의 숫자도 참이라 쓰지 않는다. */
    private fun isValidPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
}
