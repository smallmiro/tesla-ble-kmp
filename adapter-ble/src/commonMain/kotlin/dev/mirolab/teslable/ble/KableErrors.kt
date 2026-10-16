package dev.mirolab.teslable.ble

import com.juul.kable.UnmetRequirementException
import com.juul.kable.UnmetRequirementReason
import dev.mirolab.teslable.model.VehicleError

/** Kable·플랫폼 예외 -> [VehicleError.TransportError](설계 구체화 7). 문구 판정은 Kable 0.45.0 소스 기준. */
internal object KableErrors {
    private const val MAX_CAUSE_DEPTH = 8
    private val PERMISSION = Regex("permission|authoriz", RegexOption.IGNORE_CASE)
    private val UNSUPPORTED = Regex("not supported|doesn't support", RegexOption.IGNORE_CASE)

    /** 예외를 값으로 바꾼다. 취소는 호출자가 먼저 걸러야 한다. */
    fun map(e: Throwable): VehicleError.TransportError =
        when {
            // 권한 판정이 먼저다: iOS는 미승인을 UnmetRequirementException(BluetoothDisabled, "...Unauthorized...")로 던진다.
            causes(e).any { it::class.simpleName == "SecurityException" || PERMISSION.containsMatchIn(it.message.orEmpty()) } -> {
                VehicleError.TransportError.PermissionDenied
            }

            e is UnmetRequirementException && e.reason == UnmetRequirementReason.BluetoothDisabled -> {
                VehicleError.TransportError.BluetoothOff
            }

            else -> {
                VehicleError.TransportError.ConnectFailed("${e::class.simpleName}: ${e.message}")
            }
        }

    /** BLE를 지원하지 않는 어댑터(Android "Bluetooth not supported", iOS "doesn't support ... central"). 재시도해도 소용없다. */
    fun isUnsupported(e: Throwable): Boolean = causes(e).any { UNSUPPORTED.containsMatchIn(it.message.orEmpty()) }

    /** Go `IsAdapterError`처럼 어댑터(권한·전원) 문제는 재시도하지 않는다(`ble.go:285-287`). */
    fun isRetriable(error: VehicleError.TransportError): Boolean =
        error !is VehicleError.TransportError.PermissionDenied && error !is VehicleError.TransportError.BluetoothOff

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)
}
