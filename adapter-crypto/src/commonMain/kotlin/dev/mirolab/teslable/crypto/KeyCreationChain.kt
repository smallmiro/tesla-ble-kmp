package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.coroutines.CancellationException

/** 보관 수준 하나에서 키를 만드는 시도. 이 수준을 쓸 수 없으면 null, 기기가 잠겨 만들 수 없으면 [KeyStoreLockedException]. */
internal fun interface KeyAttempt {
    suspend fun create(): VehicleKey?
}

/**
 * 기기가 잠겨 키를 만들거나 저장할 수 없음(iOS `errSecInteractionNotAllowed`, FR-037). [KeyCreationChain]이
 * `KeyStoreError.Locked`로 바꾸고 다음 보관 수준으로 넘어가지 않는다.
 */
internal class KeyStoreLockedException : Exception("device locked")

/**
 * D10: 하드웨어(가장 강한 것부터) → 소프트웨어. 하드웨어 시도의 예외는 "쓸 수 없음"으로 보고 로그만 남긴다.
 * 단 [KeyStoreLockedException]은 어느 수준에서 나와도 바로 `Failure(KeyStoreError.Locked)`다: 일시적인 잠금 때문에
 * `PREFER_HARDWARE`가 영구 소프트웨어 키를 남기면 안 된다(FR-037, ADR-0014).
 */
internal object KeyCreationChain {
    @Suppress("TooGenericExceptionCaught") // 플랫폼 키스토어 예외는 수준 불가 또는 PlatformFailure 값이 된다(ADR-0006)
    suspend fun create(
        policy: KeyPolicy,
        hardware: List<KeyAttempt>,
        software: KeyAttempt,
        logger: (String) -> Unit = {},
    ): VehicleResult<VehicleKey> {
        if (policy != KeyPolicy.SOFTWARE_ONLY) {
            for (attempt in hardware) {
                val key =
                    try {
                        attempt.create()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ignored: KeyStoreLockedException) {
                        return VehicleResult.Failure(VehicleError.KeyStoreError.Locked)
                    } catch (e: Exception) {
                        logger("hardware key level unavailable: ${e::class.simpleName}")
                        null
                    }
                if (key != null) return VehicleResult.Success(key)
            }
        }
        if (policy == KeyPolicy.REQUIRE_HARDWARE) return VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable)
        return try {
            software.create()?.let { VehicleResult.Success(it) }
                ?: VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("software key generation unavailable"))
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: KeyStoreLockedException) {
            VehicleResult.Failure(VehicleError.KeyStoreError.Locked)
        } catch (e: Exception) {
            VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("software key generation failed: ${e::class.simpleName}"))
        }
    }
}
