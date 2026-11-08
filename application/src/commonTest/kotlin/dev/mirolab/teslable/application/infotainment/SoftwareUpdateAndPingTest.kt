package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.Ping
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * 소프트웨어 업데이트 명령(FR-092)과 `ping`(FR-034)이 보내는 `CarServer.Action` 평문. 기대값은 Go `pkg/vehicle/infotainment.go`의
 * 빌더를 손으로 옮긴 독립 hex다. 태그: 25 → `ca 01`, 29 → `ea 01`, 46 → `f2 02`.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class SoftwareUpdateAndPingTest {
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
    fun softwareUpdateScheduleCarriesTheDelayInSeconds() =
        // infotainment.go ScheduleSoftwareUpdate: vehicleControlScheduleSoftwareUpdateAction(29){offset_sec(1) = int32(delay / s)}.
        // 0초는 proto3 기본값이라 생략되어 빈 멤버가 된다. Go는 값을 검사하지 않는다. CancelSoftwareUpdate(25) {}
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1206ea0103" + "08a038" to { c.scheduleSoftwareUpdate(7200) }, // 12 06 | ea 01 03 | 08 a038 (2시간)
                    "1203ea0100" to { c.scheduleSoftwareUpdate(0) }, // 12 03 | ea 01 00
                    "1203ca0100" to { c.cancelSoftwareUpdate() }, // 12 03 | ca 01 00
                ),
            )
        }

    @Test
    fun pingSendsPingIdOneAndIgnoresTheReplyContent() =
        // infotainment.go Ping: ping(46){ping_id(1) = 1}, executeCarServerAction이라 명령 경로이고 응답 내용은 버린다 — 응답에 다른 Ping이
        // 있거나 Ping이 없어도 성공이다(Go는 응답의 Ping을 보지 않는다)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.infotainmentPayload(Response(ping = Ping(ping_id = 99)))),
                listOf(FakeVehicle.infotainmentOk()),
            )
            h.assertSends(
                listOf(
                    "1205f20202" + "0801" to { c.ping() }, // 12 05 | f2 02 02 | 08 01
                    "1205f20202" + "0801" to { c.ping() },
                ),
            )
        }
}
