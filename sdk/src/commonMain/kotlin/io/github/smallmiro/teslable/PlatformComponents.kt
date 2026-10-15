package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleKeyStore
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
