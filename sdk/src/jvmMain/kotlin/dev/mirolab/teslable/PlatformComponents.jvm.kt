package dev.mirolab.teslable

import dev.mirolab.teslable.ble.BleAvailability
import dev.mirolab.teslable.ble.kableTransportFactory
import dev.mirolab.teslable.crypto.inMemoryVehicleKeyStore
import dev.mirolab.teslable.crypto.platformCryptoPrimitives
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.storage.InMemorySessionCache
import kotlinx.coroutines.CoroutineScope

internal actual fun platformComponents(
    platform: PlatformContext,
    config: TeslaBleConfig,
    scope: CoroutineScope,
    logger: TeslaLogger,
): PlatformComponents =
    PlatformComponents(
        transports = kableTransportFactory(scope, platformCryptoPrimitives(), BleAvailability.ALWAYS, logger, config.connectTimeout),
        keys = inMemoryVehicleKeyStore(),
        sessionCache = config.sessionCache ?: InMemorySessionCache(),
    )
