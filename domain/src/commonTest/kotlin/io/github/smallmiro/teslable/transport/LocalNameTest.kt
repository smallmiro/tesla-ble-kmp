package io.github.smallmiro.teslable.transport

import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalNameTest {
    @Test
    fun computesLocalNameFromVin() { // 02-ble-transport.md §2
        assertEquals(ProtocolVectors.LOCAL_NAME, LocalName.of(Vin(ProtocolVectors.LOCAL_NAME_VIN), TestCrypto.primitives).value)
    }
}
