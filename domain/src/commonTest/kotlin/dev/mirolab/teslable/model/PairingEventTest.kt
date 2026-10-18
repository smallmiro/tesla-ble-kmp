package dev.mirolab.teslable.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PairingEventTest {
    @Test
    fun unknownComparesByContent() {
        assertEquals(PairingEvent.Unknown(byteArrayOf(1, 2)), PairingEvent.Unknown(byteArrayOf(1, 2)))
        assertEquals(PairingEvent.Unknown(byteArrayOf(1, 2)).hashCode(), PairingEvent.Unknown(byteArrayOf(1, 2)).hashCode())
        assertNotEquals<PairingEvent>(PairingEvent.Unknown(byteArrayOf(1, 2)), PairingEvent.Unknown(byteArrayOf(1, 3)))
    }

    @Test
    fun unknownRawIsACopy() {
        val input = byteArrayOf(1, 2)
        val event = PairingEvent.Unknown(input)
        input[0] = 9
        event.raw[1] = 9
        assertEquals(listOf<Byte>(1, 2), event.raw.toList())
    }

    @Test
    fun unknownToStringHidesBytes() = assertEquals("Unknown(2B)", PairingEvent.Unknown(byteArrayOf(1, 2)).toString())

    @Test
    fun onlyEndEventsAreTerminal() {
        val error = VehicleError.NotConnected
        assertTrue(PairingEvent.Accepted.isTerminal)
        assertTrue(PairingEvent.Rejected(error).isTerminal)
        assertTrue(PairingEvent.Failed(error).isTerminal)
        assertTrue(PairingEvent.TimedOut.isTerminal)
        assertFalse(PairingEvent.Sent.isTerminal)
        assertFalse(PairingEvent.WaitingForTap.isTerminal)
        assertFalse(PairingEvent.Unknown(byteArrayOf(1)).isTerminal)
    }
}
