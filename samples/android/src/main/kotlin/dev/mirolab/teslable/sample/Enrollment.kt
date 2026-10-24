package dev.mirolab.teslable.sample

import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Control 탭([SampleState])과 키·제어 탭([KeyControlState])이 함께 쓰는 키 등록 요청과 확인(`13-lib-pairing.md` §Enrolling a key).

private val ENROLL_WINDOW = 120.seconds
private val ENROLL_INTERVAL = 3.seconds
private val ENROLL_CALL_TIMEOUT = 5.seconds

/** 등록 확인 한 번의 끝: [confirmed]이면 차량이 이 키를 안다. [line]은 결과에 남길 한 줄. */
data class EnrollmentConfirmation(
    val confirmed: Boolean,
    val line: String,
)

/** 둘 다 공개키가 있고 같다(`PublicKeyBytes`는 내용으로 비교한다). */
fun PublicKeyBytes?.isSameKeyAs(other: PublicKeyBytes?): Boolean = this != null && this == other

/** 3초 간격, 최대 2분 동안 Infotainment `sessionInfo`로 등록을 확인한다. 끝나면 마지막 결과 한 줄을 돌려준다. */
suspend fun confirmEnrollment(
    vehicle: Vehicle,
    publicKey: PublicKeyBytes,
): EnrollmentConfirmation {
    var last = "등록 확인: 시도하지 못함"
    val mark = TimeSource.Monotonic.markNow()
    while (mark.elapsedNow() < ENROLL_WINDOW) {
        when (val r = vehicle.sessionInfo(publicKey, VehicleDomain.INFOTAINMENT, ENROLL_CALL_TIMEOUT)) {
            is VehicleResult.Success -> {
                return EnrollmentConfirmation(true, "등록 확인됨(Infotainment)")
            }

            is VehicleResult.Failure -> {
                last =
                    if (r.error is VehicleError.KeyNotPaired) "아직 등록되지 않음(KeyNotPaired) — 2분까지 반복" else "등록 확인 실패: ${r.error.message}"
            }

            is VehicleResult.Uncertain -> {
                last = "등록 확인 불확실: ${r.error.message}"
            }
        }
        delay(ENROLL_INTERVAL)
    }
    return EnrollmentConfirmation(false, "$last (2분 안에 확인되지 않음)")
}

/**
 * 키 등록을 요청한다. 이벤트마다 한 줄을 [onLine]에 남기고, `Accepted`/`TimedOut`이면 [confirmEnrollment]로 이어서
 * 결과 줄도 남긴다. 등록을 확인했으면 `true`.
 */
suspend fun requestAndConfirmEnrollment(
    vehicle: Vehicle,
    publicKey: PublicKeyBytes,
    onLine: (String) -> Unit,
): Boolean {
    var confirm = false
    vehicle.addKeyRequest(publicKey).collect { event ->
        onLine(pairingLine(event))
        if (event == PairingEvent.Accepted || event == PairingEvent.TimedOut) confirm = true
    }
    if (!confirm) return false
    val result = confirmEnrollment(vehicle, publicKey)
    onLine(result.line)
    return result.confirmed
}

@Suppress("CyclomaticComplexMethod") // PairingEvent 7종을 한 `when` 으로 문장에 옮기는 표라 갈래 수가 곧 복잡도다
private fun pairingLine(event: PairingEvent): String =
    when (event) {
        PairingEvent.Sent -> "요청 보냄 — 중앙 콘솔에 키카드를 대고 화면에서 승인하세요"
        PairingEvent.WaitingForTap -> "카드 태그 대기 중"
        PairingEvent.Accepted -> "차량이 승인함 — Infotainment 동기화 확인 중"
        is PairingEvent.Rejected -> "거부: ${event.error.message}"
        is PairingEvent.Unknown -> "모르는 응답 ${event.raw.joinToString("") { "%02x".format(it) }}"
        is PairingEvent.Failed -> "실패: ${event.error.message}"
        PairingEvent.TimedOut -> "시간 초과 — 등록 여부를 확인합니다"
    }
