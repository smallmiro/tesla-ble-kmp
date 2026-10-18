package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class VcsecControlTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    @Test
    fun everyCommandSendsItsGoPayloadAuthenticated() =
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(vcsec), timeout = 1.seconds))
            val c = session.control
            val calls: List<Pair<String, suspend () -> VehicleResult<Unit>>> =
                listOf(
                    "1001" to { c.lock() },
                    "1000" to { c.unlock() },
                    "101e" to { c.wakeUp() },
                    "1014" to { c.remoteDrive() },
                    "101d" to { c.autoSecureVehicle() },
                    "22022801" to { c.openTrunk() },
                    "22022801" to { c.actuateTrunk() },
                    "22022804" to { c.closeTrunk() },
                    "22023001" to { c.openFrunk() },
                    "22024003" to { c.openTonneau() },
                    "22024004" to { c.closeTonneau() },
                    "22024002" to { c.stopTonneau() },
                )
            for ((_, call) in calls) assertEquals(VehicleResult.Success(Unit), call())
            assertEquals(calls.map { it.first }, h.fake.authenticatedPayloads(vcsec).map { it.toHex() })
        }

    @Test
    fun waitThenEmptyIsSuccessAfterRetry() =
        // vcsec_test.go 다중 응답: WAIT(Busy) → 재시도 → 빈 메시지 = 성공 (FR-048)
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            session.startSession(setOf(vcsec), timeout = 1.seconds)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy()), listOf(FakeVehicle.vcsecEmpty()))
            assertEquals(VehicleResult.Success(Unit), session.control.lock())
            assertEquals(2, h.fake.authenticatedPayloads(vcsec).size)
        }

    @Test
    fun nominalErrorIsFailureWithItsCode() =
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            session.startSession(setOf(vcsec), timeout = 1.seconds)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_CLOSURES_OPEN)))
            assertEquals(
                VehicleResult.Failure(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_CLOSURES_OPEN)),
                session.control.closeTrunk(),
            )
        }

    @Test
    fun lostReplyIsUncertainAndNotResent() =
        // NFR-007, FR-100: 응답이 없으면 Uncertain(Timeout(afterSend = true)), 다시 보내지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            session.startSession(setOf(vcsec), timeout = 1.seconds)
            h.fake.script(vcsec, emptyList())
            assertEquals(VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)), session.control.unlock(timeout = 2.seconds))
            assertEquals(1, h.fake.authenticatedPayloads(vcsec).size)
        }

    @Test
    fun withoutASessionIsNoSessionAndNothingIsSent() =
        runTest {
            val h = dispatcherHarness()
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), VehicleSession(h.dispatcher).control.lock())
            assertEquals(0, h.transport.sent.size)
        }
}
