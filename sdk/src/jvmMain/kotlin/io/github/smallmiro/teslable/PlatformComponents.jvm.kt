package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.ble.BleAvailability
import io.github.smallmiro.teslable.ble.kableTransportFactory
import io.github.smallmiro.teslable.crypto.inMemoryVehicleKeyStore
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.storage.InMemorySessionCache
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
