package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.ble.androidBleAvailability
import io.github.smallmiro.teslable.ble.kableTransportFactory
import io.github.smallmiro.teslable.crypto.androidVehicleKeyStore
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.storage.androidSessionCache
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
