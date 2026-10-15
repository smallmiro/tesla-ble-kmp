package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.ble.appleBleAvailability
import io.github.smallmiro.teslable.ble.kableTransportFactory
import io.github.smallmiro.teslable.crypto.appleVehicleKeyStore
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.storage.appleSessionCache
import kotlinx.coroutines.CoroutineScope

internal actual fun platformComponents(
    platform: PlatformContext,
    config: TeslaBleConfig,
    scope: CoroutineScope,
    logger: TeslaLogger,
): PlatformComponents {
    val crypto = platformCryptoPrimitives()
    return PlatformComponents(
        transports = kableTransportFactory(scope, crypto, appleBleAvailability(), logger, config.connectTimeout),
        keys = appleVehicleKeyStore(logger),
        sessionCache = config.sessionCache ?: appleSessionCache(crypto, logger),
    )
}
