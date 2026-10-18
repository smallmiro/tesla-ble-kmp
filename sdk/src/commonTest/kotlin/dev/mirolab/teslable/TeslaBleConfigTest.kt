package dev.mirolab.teslable

import dev.mirolab.teslable.port.TeslaLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class TeslaBleConfigTest {
    @Test
    fun defaultFactoryKeepsDocumentedDefaults() {
        val logger = TeslaLogger { _, _, _ -> }
        val config = TeslaBleConfig.default(logger)
        assertSame(logger, config.logger)
        assertEquals(TeslaBleConfig(logger = logger), config)
    }

    @Test
    fun withSecondsBuildsDurationsFromDoubles() {
        val config = TeslaBleConfig.withSeconds(TeslaLogger.NoOp, 10.0, 20.0, 30.0, 90.0)
        assertEquals(10.seconds, config.commandTimeout)
        assertEquals(20.seconds, config.handshakeTimeout)
        assertEquals(30.seconds, config.connectTimeout)
        assertEquals(90.seconds, config.pairingTimeout)
        assertEquals(5.seconds, config.commandLifetime)
    }

    @Test
    fun withSecondsRejectsNonPositiveArguments() {
        assertFailsWith<IllegalArgumentException> { TeslaBleConfig.withSeconds(TeslaLogger.NoOp, 0.0, 20.0, 30.0, 90.0) }
        assertFailsWith<IllegalArgumentException> { TeslaBleConfig.withSeconds(TeslaLogger.NoOp, 10.0, -1.0, 30.0, 90.0) }
        assertFailsWith<IllegalArgumentException> { TeslaBleConfig.withSeconds(TeslaLogger.NoOp, 10.0, 20.0, Double.NaN, 90.0) }
        assertFailsWith<IllegalArgumentException> { TeslaBleConfig.withSeconds(TeslaLogger.NoOp, 10.0, 20.0, 30.0, 0.0) }
    }
}
