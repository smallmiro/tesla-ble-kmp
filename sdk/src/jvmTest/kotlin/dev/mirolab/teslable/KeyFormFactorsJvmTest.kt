package dev.mirolab.teslable

import dev.mirolab.teslable.model.KeyFormFactor
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyFormFactorsJvmTest {
    @Test
    fun jvmDefaultIsCloudKey() {
        assertEquals(KeyFormFactor.CLOUD_KEY, platformKeyFormFactor())
    }
}
