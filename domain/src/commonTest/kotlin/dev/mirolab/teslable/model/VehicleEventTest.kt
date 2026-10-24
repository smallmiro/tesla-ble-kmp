package dev.mirolab.teslable.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VehicleEventTest {
    private val closed = ClosureState.CLOSED
    private val status =
        VehicleStatus(
            lockState = LockState.LOCKED,
            closures = Closures(closed, closed, closed, closed, closed, closed, closed, closed),
            sleepStatus = SleepStatus.AWAKE,
            userPresence = UserPresence.NOT_PRESENT,
            tonneauPercentOpen = null,
            gear = Gear.PARK,
        )

    private fun request(raw: ByteArray) =
        VehicleEvent.AuthenticationRequest(AuthenticationLevel.DRIVE, listOf(AuthenticationReason.WALK_UP_UNLOCK), raw, listOf("53"))

    @Test
    fun comparesByContent() {
        assertEquals(
            VehicleEvent.Status(status, byteArrayOf(1, 2), listOf("1.8")),
            VehicleEvent.Status(status, byteArrayOf(1, 2), listOf("1.8")),
        )
        assertEquals(
            VehicleEvent.Status(status, byteArrayOf(1, 2), listOf("1.8")).hashCode(),
            VehicleEvent.Status(status, byteArrayOf(1, 2), listOf("1.8")).hashCode(),
        )
        assertEquals(request(byteArrayOf(1)), request(byteArrayOf(1)))
        assertEquals(request(byteArrayOf(1)).hashCode(), request(byteArrayOf(1)).hashCode())
        assertEquals(VehicleEvent.Unrecognized(byteArrayOf(1), listOf("48")), VehicleEvent.Unrecognized(byteArrayOf(1), listOf("48")))
        assertNotEquals(VehicleEvent.Unrecognized(byteArrayOf(1), emptyList()), VehicleEvent.Unrecognized(byteArrayOf(2), emptyList()))
        assertNotEquals(VehicleEvent.Unrecognized(byteArrayOf(1), emptyList()), VehicleEvent.Unrecognized(byteArrayOf(1), listOf("53")))
        assertNotEquals(
            VehicleEvent.Status(status, byteArrayOf(1), emptyList()),
            VehicleEvent.Status(status.copy(gear = Gear.DRIVE), byteArrayOf(1), emptyList()),
        )
        // 같은 프레임에서 나온 두 이벤트(raw 같음)라도 종류가 다르면 다르다
        assertNotEquals<VehicleEvent>(VehicleEvent.Unrecognized(byteArrayOf(1), listOf("53")), request(byteArrayOf(1)))
    }

    @Test
    fun rawIsACopy() {
        val input = byteArrayOf(1, 2)
        val event = VehicleEvent.Unrecognized(input, emptyList())
        input[0] = 9
        event.raw[1] = 9
        assertEquals(listOf<Byte>(1, 2), event.raw.toList())
    }

    @Test
    fun listsAreCopied() {
        val unknown = mutableListOf("53")
        val reasons = mutableListOf(AuthenticationReason.IDENTIFICATION)
        val event = VehicleEvent.AuthenticationRequest(AuthenticationLevel.UNLOCK, reasons, byteArrayOf(1), unknown)
        unknown += "48"
        reasons += AuthenticationReason.IMMOBILIZER
        assertEquals(listOf("53"), event.unknownFields)
        assertEquals(listOf(AuthenticationReason.IDENTIFICATION), event.reasons)
    }

    @Test
    fun toStringShowsRawByLengthOnly() {
        assertEquals(
            "Unrecognized(unknownFields=[53], raw=3B)",
            VehicleEvent.Unrecognized(byteArrayOf(0x0A, 0x0B, 0x0C), listOf("53")).toString(),
        )
        assertEquals(
            "AuthenticationRequest(requestedLevel=DRIVE, reasons=[WALK_UP_UNLOCK], unknownFields=[53], raw=2B)",
            request(byteArrayOf(0x0A, 0x0B)).toString(),
        )
        assertEquals(
            "Status(status=$status, unknownFields=[], raw=1B)",
            VehicleEvent.Status(status, byteArrayOf(0x0A), emptyList()).toString(),
        )
    }
}
