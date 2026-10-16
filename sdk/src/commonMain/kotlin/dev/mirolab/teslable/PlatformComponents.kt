package dev.mirolab.teslable

import dev.mirolab.teslable.port.SessionCache
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.TransportFactory
import dev.mirolab.teslable.port.VehicleKeyStore
import kotlinx.coroutines.CoroutineScope

/** 플랫폼 기본 어댑터 묶음. */
internal class PlatformComponents(
    val transports: TransportFactory,
    val keys: VehicleKeyStore,
    val sessionCache: SessionCache,
)

/** 플랫폼 기본 어댑터를 만든다. [scope]는 `TeslaBle` 수명, [logger]는 이미 `SafeLogger`로 감싼 것. */
internal expect fun platformComponents(
    platform: PlatformContext,
    config: TeslaBleConfig,
    scope: CoroutineScope,
    logger: TeslaLogger,
): PlatformComponents
