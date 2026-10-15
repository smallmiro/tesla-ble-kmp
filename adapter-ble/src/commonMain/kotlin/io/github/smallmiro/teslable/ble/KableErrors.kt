package io.github.smallmiro.teslable.ble

import com.juul.kable.UnmetRequirementException
import com.juul.kable.UnmetRequirementReason
import io.github.smallmiro.teslable.model.VehicleError

/** Kable·플랫폼 예외 -> [VehicleError.TransportError](설계 구체화 7). 문구 판정은 Kable 0.45.0 소스 기준. */
internal object KableErrors {
    private const val MAX_CAUSE_DEPTH = 8
    private val PERMISSION = Regex("permission", RegexOption.IGNORE_CASE)

    /** 예외를 값으로 바꾼다. 취소는 호출자가 먼저 걸러야 한다. */
    fun map(e: Throwable): VehicleError.TransportError =
        when {
            e is UnmetRequirementException && e.reason == UnmetRequirementReason.BluetoothDisabled -> {
                VehicleError.TransportError.BluetoothOff
            }

            causes(e).any { it::class.simpleName == "SecurityException" || PERMISSION.containsMatchIn(it.message.orEmpty()) } -> {
                VehicleError.TransportError.PermissionDenied
            }

            else -> {
                VehicleError.TransportError.ConnectFailed("${e::class.simpleName}: ${e.message}")
            }
        }

    /** Go `IsAdapterError`처럼 어댑터(권한·전원) 문제는 재시도하지 않는다(`ble.go:285-287`). */
    fun isRetriable(error: VehicleError.TransportError): Boolean =
        error !is VehicleError.TransportError.PermissionDenied && error !is VehicleError.TransportError.BluetoothOff

    private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)
}
