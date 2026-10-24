package dev.mirolab.teslable.sample

import dev.mirolab.teslable.model.AuthenticationLevel
import dev.mirolab.teslable.model.ClosureState
import dev.mirolab.teslable.model.Closures
import dev.mirolab.teslable.model.Gear
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.SleepStatus
import dev.mirolab.teslable.model.UserPresence
import dev.mirolab.teslable.model.VehicleEvent
import dev.mirolab.teslable.model.VehicleStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveStateTest {
    private val t0 = Instant.parse("2026-10-24T01:00:00Z")

    private fun closures(driverDoor: ClosureState = ClosureState.CLOSED) =
        Closures(
            frontDriverDoor = driverDoor,
            frontPassengerDoor = ClosureState.CLOSED,
            rearDriverDoor = ClosureState.CLOSED,
            rearPassengerDoor = ClosureState.CLOSED,
            rearTrunk = ClosureState.CLOSED,
            frontTrunk = ClosureState.CLOSED,
            chargePort = ClosureState.CLOSED,
            tonneau = ClosureState.CLOSED,
        )

    private fun status(
        lock: LockState = LockState.LOCKED,
        gear: Gear = Gear.PARK,
        driverDoor: ClosureState = ClosureState.CLOSED,
    ) = VehicleEvent.Status(
        VehicleStatus(lock, closures(driverDoor), SleepStatus.AWAKE, UserPresence.NOT_PRESENT, null, gear),
        byteArrayOf(1),
        emptyList(),
    )

    @Test
    fun firstStatusFillsEveryItemWithoutClaimingAChange() {
        val s = LiveReducer.reduce(LiveState(), status(), t0)

        assertEquals(LiveItemKey.entries.size, s.items.size)
        assertEquals("LOCKED", s.items.getValue(LiveItemKey.LOCK).value)
        assertEquals("PARK", s.items.getValue(LiveItemKey.GEAR).value)
        assertTrue(s.items.values.all { it.since == t0 && !it.changed })
        assertEquals(t0, s.lastStatusAt)
    }

    @Test
    fun equalRepeatIsNotAChange() {
        val first = LiveReducer.reduce(LiveState(), status(), t0)

        val second = LiveReducer.reduce(first, status(), t0.plusSeconds(5))

        assertEquals(first.items, second.items)
        assertEquals(first.recent, second.recent)
        assertEquals(t0.plusSeconds(5), second.lastStatusAt)
    }

    @Test
    fun onlyTheChangedItemGetsANewTime() {
        val first = LiveReducer.reduce(LiveState(), status(), t0)

        val second = LiveReducer.reduce(first, status(gear = Gear.DRIVE), t0.plusSeconds(9))

        val gear = second.items.getValue(LiveItemKey.GEAR)
        assertEquals("DRIVE", gear.value)
        assertEquals(t0.plusSeconds(9), gear.since)
        assertTrue(gear.changed)
        assertEquals(t0, second.items.getValue(LiveItemKey.LOCK).since)
        assertEquals(1, second.recent.size)
        assertTrue(
            second.recent
                .first()
                .text
                .contains("PARK") &&
                second.recent
                    .first()
                    .text
                    .contains("DRIVE"),
        )
    }

    @Test
    fun changeBackAndForthIsEachAChange() {
        var s = LiveReducer.reduce(LiveState(), status(), t0)
        s = LiveReducer.reduce(s, status(driverDoor = ClosureState.OPEN), t0.plusSeconds(1))
        s = LiveReducer.reduce(s, status(driverDoor = ClosureState.CLOSED), t0.plusSeconds(2))

        assertEquals(t0.plusSeconds(2), s.items.getValue(LiveItemKey.DRIVER_DOOR).since)
        assertEquals(2, s.recent.size)
    }

    @Test
    fun authenticationRequestAndUnrecognizedAreListed() {
        var s =
            LiveReducer.reduce(
                LiveState(),
                VehicleEvent.AuthenticationRequest(AuthenticationLevel.DRIVE, emptyList(), byteArrayOf(2), emptyList()),
                t0,
            )
        s = LiveReducer.reduce(s, VehicleEvent.Unrecognized(byteArrayOf(3), listOf("53")), t0.plusSeconds(1))

        assertNull(s.lastStatusAt)
        assertTrue(s.items.isEmpty())
        assertEquals(2, s.recent.size)
        assertTrue(
            s.recent
                .last()
                .text
                .contains("DRIVE"),
        )
        assertTrue(
            s.recent
                .first()
                .text
                .contains("53"),
        )
    }

    @Test
    fun recentListIsCapped() {
        var s = LiveState()
        repeat(LiveReducer.MAX_RECENT + 20) { i ->
            s = LiveReducer.reduce(s, VehicleEvent.Unrecognized(byteArrayOf(1), listOf("$i")), t0.plusSeconds(i.toLong()))
        }
        assertEquals(LiveReducer.MAX_RECENT, s.recent.size)
        assertFalse(s.recent.any { it.text.endsWith("[0]") })
    }
}
