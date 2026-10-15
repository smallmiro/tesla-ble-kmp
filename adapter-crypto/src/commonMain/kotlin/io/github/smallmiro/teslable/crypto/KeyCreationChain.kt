package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.CancellationException

/** 보관 수준 하나에서 키를 만드는 시도. 이 수준을 쓸 수 없으면 null. */
internal fun interface KeyAttempt {
    suspend fun create(): VehicleKey?
}

/** D10: 하드웨어(가장 강한 것부터) → 소프트웨어. 하드웨어 시도의 예외는 "쓸 수 없음"으로 보고 로그만 남긴다. */
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
        } catch (e: Exception) {
            VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("software key generation failed: ${e::class.simpleName}"))
        }
    }
}
