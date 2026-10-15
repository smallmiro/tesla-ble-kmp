package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.port.TeslaLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TeslaBleConfigTest {
    @Test
    fun defaultFactoryKeepsDocumentedDefaults() {
        val logger = TeslaLogger { _, _, _ -> }
        val config = TeslaBleConfig.default(logger)
        assertSame(logger, config.logger)
        assertEquals(TeslaBleConfig(logger = logger), config)
    }
}
