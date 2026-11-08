package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * 차체 명령(FR-050, FR-051, FR-053)이 보내는 `CarServer.Action` 평문. 기대값은 Go `pkg/vehicle/actions.go`의 빌더를 손으로 옮긴 독립
 * hex다(구현 코드로 만들지 않는다). 읽는 법은 `ClimateCommandsTest`와 같다: `12 len` 다음이 `VehicleAction` oneof 멤버의 태그
 * `(번호 << 3) | 2`(26 → `d2 01`, 27 → `da 01`, 32 → `82 02`, 34 → `92 02`)다.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class VehicleControlCommandsTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    private suspend fun connected(h: DispatcherHarness): InfotainmentCommands {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
        return session.infotainment
    }

    /** [calls]를 차례로 보낸다. 모두 `Success(Unit)`이고, 차량이 검증한 평문(받은 순서)이 각 hex와 같아야 한다. */
    private suspend fun DispatcherHarness.assertSends(calls: List<Pair<String, suspend () -> VehicleResult<Unit>>>) {
        for ((hex, call) in calls) assertEquals(VehicleResult.Success(Unit), call(), hex)
        assertEquals(calls.map { it.first }, fake.authenticatedPayloads(infotainment).map { it.toHex() })
    }

    @Test
    fun windowsSelectTheirVoidMember() =
        // actions.go VentWindows/CloseWindows: vehicleControlWindowAction(34){vent 3 | close 4}. unknown(2)은 쓰지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1205920202" + "1a00" to { c.ventWindows() }, // 12 05 | 92 02 02 | 1a 00
                    "1205920202" + "2200" to { c.closeWindows() }, // 22 00
                ),
            )
        }

    @Test
    fun sunroofSendsTheAbsoluteLevelEvenWhenItIsZero() =
        // actions.go ChangeSunroofState: vehicleControlSunroofOpenCloseAction(32){absolute_level(1) int32}. oneof 멤버라 0도 `08 00`으로
        // 나간다 — 빠지면 차량은 단계 없는 명령을 받는다. Go는 값을 검사하지 않는다(음수는 int32 10바이트 varint)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1205820202" + "0800" to { c.setSunroof(0) }, // 12 05 | 82 02 02 | 08 00
                    "1205820202" + "0864" to { c.setSunroof(100) }, // 08 64
                    "120e82020b" + "08ffffffffffffffffff01" to { c.setSunroof(-1) }, // 12 0e | 82 02 0b | 08 + int32 -1
                ),
            )
        }

    @Test
    fun hornAndLightsSendTheirEmptyMember() =
        // actions.go HonkHorn: vehicleControlHonkHornAction(27) {}, FlashLights: vehicleControlFlashLightsAction(26) {}
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1203da0100" to { c.honkHorn() }, // 12 03 | da 01 00
                    "1203d20100" to { c.flashLights() }, // 12 03 | d2 01 00
                ),
            )
        }
}
