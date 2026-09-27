package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Ping
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import io.github.smallmiro.teslable.application.dispatcher.DispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class VcsecCommandsTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val lock = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_LOCK).encode() // vcsec.go executeRKEAction payload (M4가 빌더로 만든다)
    private val addKey = "whitelist operation".encodeToByteArray() // FakeVehicle은 payload를 해석하지 않으므로 임의 바이트로 충분하다

    private suspend fun connected(h: DispatcherHarness): VehicleSession {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
        return session
    }

    @Test
    fun nominalErrorFailsWhitelistAndRkeCommands() =
        // vcsec_test.go TestNominalVSCECError: AddKey, RemoveKey, Lock 모두 NominalVCSECError(VEHICLE_NOT_IN_PARK), !MHS, !Temporary
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val nominal = listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK))
            h.fake.script(vcsec, nominal, nominal, nominal)
            val expected = VehicleError.VcsecRejected(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK)
            for (done in listOf(
                VcsecResponses.WHITELIST_OPERATION_COMPLETE,
                VcsecResponses.WHITELIST_OPERATION_COMPLETE,
                VcsecResponses.COMMAND_STATUS_ABSENT,
            )) {
                val payload = if (done === VcsecResponses.COMMAND_STATUS_ABSENT) lock else addKey
                val result = assertIs<VehicleResult.Failure>(session.vcsec.execute(payload, AuthMethod.GCM, done))
                assertEquals(expected, result.error)
                assertFalse(result.error.mayHaveSucceeded)
                assertFalse(result.error.temporary)
            }
        }

    @Test
    fun gibberishResponseIsUncertainBadResponse() =
        // vcsec_test.go TestGibberishVCSECResponse: errors.Is(err, ErrBadResponse)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecGibberish()), listOf(FakeVehicle.vcsecGibberish()))
            for (done in listOf(VcsecResponses.WHITELIST_OPERATION_COMPLETE, VcsecResponses.COMMAND_STATUS_ABSENT)) {
                val result = assertIs<VehicleResult.Uncertain>(session.vcsec.execute(lock, AuthMethod.GCM, done))
                assertIs<VehicleError.BadResponse>(result.error)
            }
        }

    @Test
    fun whitelistOperationRetriesBusyReadsIntermediateThenReportsKeychainError() =
        // vcsec_test.go TestWhitelistOperationError: WAIT, WAIT(재시도), [authSuccess(중간), whitelistStatus(WHITELIST_FULL)] → KeychainError
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val full = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
            h.fake.script(
                vcsec,
                listOf(FakeVehicle.vcsecBusy()),
                listOf(FakeVehicle.vcsecBusy()),
                listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecWhitelistStatus(full)),
            )
            val result =
                assertIs<VehicleResult.Failure>(
                    session.vcsec.execute(addKey, AuthMethod.GCM, VcsecResponses.WHITELIST_OPERATION_COMPLETE, timeout = 5.seconds),
                )
            assertEquals(VehicleError.KeychainRejected(full), result.error)
            assertEquals(3, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun rkeSucceedsOnEmptyMessageAfterIntermediateCommandStatus() =
        // FR-048: RKE는 commandStatus 없는 메시지가 최종. 한 요청에 응답 두 개(중간 + 최종)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecEmpty()))
            val result =
                assertIs<VehicleResult.Success<FromVCSECMessage>>(
                    session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT),
                )
            assertEquals(null, result.value.commandStatus)
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun serializesVcsecCommandsOnOneConnection() =
        // FR-049 / SDD §5: 두 번째 VCSEC 명령은 첫 번째가 끝날(응답 유실 → 시간 초과) 때까지 전송되지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, emptyList(), listOf(FakeVehicle.vcsecEmpty()))
            val first =
                async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 100.milliseconds) }
            val second = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            advanceTimeBy(50)
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
            assertFalse(second.isCompleted)
            assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(first.await()).error)
            assertIs<VehicleResult.Success<*>>(second.await())
            assertEquals(2, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun timeoutWhileWaitingForTheVcsecLockIsFailureNotUncertain() =
        // D29: 락 대기 중 만료 = 전송 전 → Failure(Timeout(afterSend = false))
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, emptyList())
            val blocker = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            runCurrent()
            val waiter = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 10.milliseconds)
            assertEquals(VehicleError.Timeout(afterSend = false), assertIs<VehicleResult.Failure>(waiter).error)
            assertTrue(blocker.await() is VehicleResult.Uncertain)
        }

    @Test
    fun infotainmentPingRoundTripsAndErrorsCarryTheReason() =
        // infotainment.go Ping + getCarServerResponse 통합
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val ping = Action(vehicleAction = VehicleAction(ping = Ping(ping_id = 1)))
            assertIs<VehicleResult.Success<*>>(session.infotainment.execute(ping))
            h.fake.script(Domain.DOMAIN_INFOTAINMENT, listOf(FakeVehicle.infotainmentError("car is in drive")))
            val rejected = assertIs<VehicleResult.Failure>(session.infotainment.execute(ping))
            assertEquals(VehicleError.InfotainmentRejected("car is in drive"), rejected.error)
        }
}
