package dev.mirolab.teslable.transport

import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.testing.fixtures.ProtocolVectors
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalNameTest {
    @Test
    fun computesLocalNameFromVin() { // 02-ble-transport.md §2
        assertEquals(ProtocolVectors.LOCAL_NAME, LocalName.of(Vin(ProtocolVectors.LOCAL_NAME_VIN), TestCrypto.primitives).value)
    }
}
