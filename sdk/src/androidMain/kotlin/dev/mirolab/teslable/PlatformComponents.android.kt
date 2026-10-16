package dev.mirolab.teslable

import dev.mirolab.teslable.ble.androidBleAvailability
import dev.mirolab.teslable.ble.kableTransportFactory
import dev.mirolab.teslable.crypto.androidVehicleKeyStore
import dev.mirolab.teslable.crypto.platformCryptoPrimitives
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.storage.androidSessionCache
import kotlinx.coroutines.CoroutineScope

internal actual fun platformComponents(
    platform: PlatformContext,
    config: TeslaBleConfig,
    scope: CoroutineScope,
    logger: TeslaLogger,
): PlatformComponents {
    val crypto = platformCryptoPrimitives()
    return PlatformComponents(
        transports = kableTransportFactory(scope, crypto, androidBleAvailability(platform.context), logger, config.connectTimeout),
        keys = androidVehicleKeyStore(platform.context, logger),
        sessionCache = config.sessionCache ?: androidSessionCache(platform.context, crypto, logger),
    )
}
