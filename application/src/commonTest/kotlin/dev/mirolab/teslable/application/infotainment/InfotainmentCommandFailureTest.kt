package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.common.PreconditionSchedule
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.CabinOverheatProtectionTemperature
import dev.mirolab.teslable.model.ChargingPolicy
import dev.mirolab.teslable.model.ClimateKeeperMode
import dev.mirolab.teslable.model.SeatClimateLevel
import dev.mirolab.teslable.model.SeatPosition
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 공조·충전 명령(FR-052, FR-060~083)의 공통 실패 경로. 모든 명령이 `InfotainmentCommands.execute`의 명령 경로(`SendWithRetry.send`)를
 * 지나므로 구현을 바꾸지 않고 현재 동작을 고정하는 특성 시험이다. 응답 유실·세션 없음·거부는 PR A의 공개 명령 31개 전부에 돌려, 어느
 * 메서드도 조회 경로(`SendWithRetry.query`)나 다른 경로로 새지 않음을 본다(M5 계획 Review Focus 3·4).
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class InfotainmentCommandFailureTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val replyLost = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true))

    /** 공개 명령 하나. [call]은 넘겨준 `timeout`을 그대로 쓴다. */
    private class Command(
        val name: String,
        val call: suspend InfotainmentCommands.(Duration) -> VehicleResult<Unit>,
    )

    /** PR A의 공개 명령 메서드 31개 전부(인자는 로컬 검증을 통과하는 값). */
    private val everyCommand: List<Command> =
        listOf(
            Command("climateOn") { climateOn(it) },
            Command("climateOff") { climateOff(it) },
            Command("setSeatHeater") { setSeatHeater(mapOf(SeatPosition.FRONT_LEFT to SeatClimateLevel.LOW), it) },
            Command("setClimateTemp") { setClimateTemp(21.0f, 21.0f, it) },
            Command("setSeatCooler") { setSeatCooler(SeatClimateLevel.LOW, SeatPosition.FRONT_LEFT, it) },
            Command("setSteeringWheelHeater") { setSteeringWheelHeater(true, it) },
            Command("autoSeatAndClimate") { autoSeatAndClimate(listOf(SeatPosition.FRONT_LEFT), true, it) },
            Command("setPreconditioningMax") { setPreconditioningMax(true, false, it) },
            Command("setClimateKeeperMode") { setClimateKeeperMode(ClimateKeeperMode.DOG, false, it) },
            Command("setBioweaponDefenseMode") { setBioweaponDefenseMode(true, false, it) },
            Command("setCabinOverheatProtection") { setCabinOverheatProtection(true, false, it) },
            Command("setCabinOverheatProtectionTemperature") {
                setCabinOverheatProtectionTemperature(CabinOverheatProtectionTemperature.LOW, it)
            },
            Command("chargeStart") { chargeStart(it) },
            Command("chargeStop") { chargeStop(it) },
            Command("setChargeLimit") { setChargeLimit(80, it) },
            Command("setChargingAmps") { setChargingAmps(16, it) },
            Command("chargeStandardRange") { chargeStandardRange(it) },
            Command("chargeMaxRange") { chargeMaxRange(it) },
            Command("scheduleCharging") { scheduleCharging(true, 120, it) },
            Command("scheduleDeparture") { scheduleDeparture(480, 360, ChargingPolicy.ALL_DAYS, ChargingPolicy.OFF, it) },
            Command("clearScheduledDeparture") { clearScheduledDeparture(it) },
            Command("setLowPowerMode") { setLowPowerMode(true, it) },
            Command("setKeepAccessoryPowerMode") { setKeepAccessoryPowerMode(true, it) },
            Command("addChargeSchedule") { addChargeSchedule(ChargeSchedule(id = 1, enabled = true), it) },
            Command("removeChargeSchedule") { removeChargeSchedule(1, it) },
            Command("batchRemoveChargeSchedules") { batchRemoveChargeSchedules(true, false, false, it) },
            Command("addPreconditionSchedule") { addPreconditionSchedule(PreconditionSchedule(id = 2, enabled = true), it) },
            Command("removePreconditionSchedule") { removePreconditionSchedule(2, it) },
            Command("batchRemovePreconditionSchedules") { batchRemovePreconditionSchedules(false, true, false, it) },
            Command("openChargePort") { openChargePort(it) },
            Command("closeChargePort") { closeChargePort(it) },
        )

    private suspend fun connected(h: DispatcherHarness): InfotainmentCommands {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
        return session.infotainment
    }

    /** 인가된(서명된) 명령 프레임. 같은 바이트를 다시 쓴 사본도 하나씩 센다. 세션정보 요청은 세지 않는다. */
    private fun signedCopies(h: DispatcherHarness): List<RoutableMessage> =
        h.fake.received.filter { it.signature_data?.AES_GCM_Personalized_data != null }

    @Test
    fun everyCommandIsUncertainAndSignedOnceWhenTheReplyIsLost() =
        // NFR-007, FR-100: 응답이 없으면 실행됐을 수 있으므로 Uncertain(Timeout(afterSend = true))이고 다시 보내지 않는다(Go trySend의
        // PossibleSuccess, vehicle.go:211-212). 명령마다 서명된 프레임이 정확히 하나 늘어야 한다
        runTest {
            assertEquals(31, everyCommand.size)
            val h = dispatcherHarness()
            val c = connected(h)

            for ((index, command) in everyCommand.withIndex()) {
                h.fake.dropNextReplies(1)
                assertEquals(replyLost, command.call(c, 1.seconds), command.name)
                assertEquals(index + 1, signedCopies(h).size, command.name)
                assertEquals(index + 1, h.fake.authenticatedPayloads(infotainment).size, command.name)
            }
        }

    @Test
    fun everyCommandWithoutASessionIsNoSessionAndSendsNothing() =
        // dispatcher.go:415-427: 세션이 없으면 보내기 전에 ErrNoSession. 명령은 세션을 시작하지 않는다(핸드셰이크는 호출자 몫, Go와 같다)
        runTest {
            val h = dispatcherHarness()
            val c = VehicleSession(h.dispatcher).infotainment

            for (command in everyCommand) {
                assertEquals(VehicleResult.Failure(VehicleError.NoSession), command.call(c, 1.seconds), command.name)
            }

            assertTrue(h.transport.sent.isEmpty())
        }

    @Test
    fun everyCommandReturnsTheVehiclesRejectionReason() =
        // infotainment.go:37-43: actionStatus.result == ERROR → NominalError("car could not execute command: <plain_text>")
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)

            for (command in everyCommand) {
                h.fake.script(infotainment, listOf(FakeVehicle.infotainmentError("refused ${command.name}")))
                assertEquals(
                    VehicleResult.Failure(VehicleError.InfotainmentRejected("refused ${command.name}")),
                    command.call(c, 1.seconds),
                    command.name,
                )
            }

            assertEquals(everyCommand.size, signedCopies(h).size) // 거부는 재시도하지 않는다
        }

    @Test
    fun rejectionWithoutAReasonIsUnspecifiedError() =
        // infotainment.go:38-41: plain_text가 비면 "unspecified error"
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.script(infotainment, listOf(FakeVehicle.infotainmentError(null)))

            assertEquals(VehicleResult.Failure(VehicleError.InfotainmentRejected("unspecified error")), c.chargeStart())
        }

    @Test
    fun undecodableReplyIsUncertainBadResponseAndNotResent() =
        // infotainment.go:33-35: CommandError{PossibleSuccess: true}. 실행됐을 수 있으므로 다시 보내지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.script(infotainment, listOf(FakeVehicle.ScriptedReply(payload = byteArrayOf(0xFF.toByte()))))

            val result = assertIs<VehicleResult.Uncertain>(c.setChargeLimit(80))

            assertTrue(assertIs<VehicleError.BadResponse>(result.error).mayHaveSucceeded)
            assertEquals(1, signedCopies(h).size)
        }

    @Test
    fun localValidationFailsBeforeTheSessionCheckAndSendsNothing() =
        // climate.go:17-20, charge.go:216-218: 인자 검사는 executeCarServerAction보다 먼저다 — 세션이 없어도 InvalidArgument가 먼저 나온다
        runTest {
            val h = dispatcherHarness()
            val c = VehicleSession(h.dispatcher).infotainment

            val badSeat = VehicleError.InvalidArgument("invalid seat position for seat cooler: SECOND_ROW_LEFT")
            val badDeparture = VehicleError.InvalidArgument("invalid departure time: 1441 minutes after midnight (expected 0..1440)")

            assertEquals(VehicleResult.Failure(badSeat), c.setSeatCooler(SeatClimateLevel.LOW, SeatPosition.SECOND_ROW_LEFT))
            assertEquals(VehicleResult.Failure(badDeparture), c.scheduleDeparture(1441, 0, ChargingPolicy.OFF, ChargingPolicy.OFF))
            assertTrue(h.transport.sent.isEmpty())
        }

    @Test
    fun busyIsRetriedWithAFreshlySignedCopy() =
        // FR-101, vehicle.go:236-257: BUSY는 실행되지 않은 것이 확실한 재시도 대상이고, 시도마다 새 counter·nonce로 다시 인가한다.
        // 재시도 간격을 BLE 값(1초)으로 두어야 초 단위인 expires_at도 달라진다
        runTest {
            val h = dispatcherHarness(retryInterval = 1.seconds)
            val c = connected(h)
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY)),
                listOf(FakeVehicle.infotainmentOk()),
            )

            assertEquals(VehicleResult.Success(Unit), c.climateOn())

            val requests = signedCopies(h).mapNotNull { it.signature_data?.AES_GCM_Personalized_data }
            assertEquals(2, requests.size)
            val (first, second) = requests
            assertNotEquals(first.counter, second.counter)
            assertNotEquals(first.nonce, second.nonce)
            assertNotEquals(first.expires_at, second.expires_at)
        }

    @Test
    fun noNewSignedCopyAfterAFailedWriteThatReachedTheVehicle() =
        // ADR-0010 2026-11-08 개정: 첫 사본은 차량에 닿아 실행됐고 응답이 유실됐다. 같은 바이트를 다시 쓴 사본은 재전송 검사로 거부된다
        // (INVALID_TOKEN_OR_COUNTER). 명령은 그 거부로 새 사본을 인가해 보내지 않고 시간 제한까지 첫 사본의 응답을 기다린다(NFR-007)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.dropNextReplies(1)
            h.transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("gatt 133"))

            assertEquals(replyLost, c.chargeStart(timeout = 1.seconds))

            assertEquals(1, h.fake.authenticatedPayloads(infotainment).size) // 실행된 것은 첫 사본 하나
            val copies = signedCopies(h)
            assertEquals(2, copies.size)
            assertEquals(copies[0], copies[1]) // 같은 바이트를 다시 썼을 뿐 새로 서명하지 않았다
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun cancelledCommandLeavesNoPendingRequestAndTheNextCommandSucceeds() =
        // 취소는 값이 아니라 CancellationException으로 전파된다(ADR-0010): 결과 대입이 일어나지 않고, 응답 대기 등록이 풀려 다음 명령을 막지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.script(infotainment, emptyList()) // 첫 명령에는 답하지 않는다
            var result: VehicleResult<Unit>? = null
            val job = launch { result = c.chargeStart() }
            runCurrent()
            assertEquals(1, h.dispatcher.pendingCount())

            job.cancel()
            runCurrent()

            assertTrue(job.isCompleted)
            assertNull(result)
            assertEquals(0, h.dispatcher.pendingCount())
            assertEquals(VehicleResult.Success(Unit), c.chargeStop())
            assertEquals(listOf("120432021200", "120432022a00"), h.fake.authenticatedPayloads(infotainment).map { it.toHex() })
        }
}
