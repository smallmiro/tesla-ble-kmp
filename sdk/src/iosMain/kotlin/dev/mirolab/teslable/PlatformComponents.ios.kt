package dev.mirolab.teslable

import dev.mirolab.teslable.ble.appleBleAvailability
import dev.mirolab.teslable.ble.kableTransportFactory
import dev.mirolab.teslable.crypto.appleVehicleKeyStore
import dev.mirolab.teslable.crypto.platformCryptoPrimitives
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.storage.appleSessionCache
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
