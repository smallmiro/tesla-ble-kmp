package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.SessionEvent
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.testing.FakeVehicle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `getState`의 실패 경로(FR-033, FR-101, #116). 구현을 바꾸지 않고 현재 동작을 고정하는 특성 시험이다.
 * 대본의 `FakeVehicle.ScriptedReply(fault = ...)`는 암호화된 응답으로 나가고, `attachSessionInfoOnce`를 함께 쓰면 평문 + 태그로 나간다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetStateFailureTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val mtuFault = FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED)
    private val mtuUncertain = VehicleResult.Uncertain(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED))
    private val replyLost = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true))

    private suspend fun connected(h: DispatcherHarness): VehicleSession {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
        return session
    }

    private fun replyWith(data: VehicleData): List<FakeVehicle.ScriptedReply> =
        listOf(FakeVehicle.infotainmentPayload(Response(vehicleData = data)))

    private suspend fun VehicleSession.state(category: StateCategory): VehicleData =
        assertIs<VehicleResult.Success<VehicleData>>(infotainment.getState(category)).value

    /**
     * [from]번째부터 보낸 프레임의 목적지. 재전송, 재핸드셰이크, 깨우기는 모두 여기서 프레임으로 드러난다.
     * `authenticatedPayloads`는 세션정보 요청을 세지 않고, 잠든 도메인으로 간 요청도 세지 않는다(`FakeVehicle.handle`).
     */
    private fun DispatcherHarness.destinationsSince(from: Int): List<Domain?> =
        transport.sent.drop(from).map { frame ->
            val destination = RoutableMessage.ADAPTER.decode(frame).to_destination
            destination?.domain
        }

    private fun assertSessionAndLinkKept(
        h: DispatcherHarness,
        session: VehicleSession,
    ) {
        assertTrue(session.isReady(infotainment))
        assertEquals(TransportState.Connected, h.transport.state.value)
        assertTrue(h.dispatcher.isListening)
    }

    @Test
    fun mtuFaultIsUncertainWithoutResendOrSessionLoss() =
        // error.go:198-214: MayHaveSucceeded이고 retriableErrors에 없어 ShouldRetry가 거짓이다(vehicle.go:246). 경로가 있으면 fault가 수십 초
        // 이어지므로(#116) 조회마다 요청 하나로 끝나야 한다. 두 번째 fault까지 보는 이유다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val framesBefore = h.transport.sent.size
            val drive = DriveState(speed = 30)
            h.fake.script(infotainment, listOf(mtuFault), listOf(mtuFault), replyWith(VehicleData(drive_state = drive)))

            val result = session.infotainment.getState(StateCategory.DRIVE)

            assertEquals(mtuUncertain, result)
            assertEquals(1, h.fake.authenticatedPayloads(infotainment).size)
            assertEquals(listOf(infotainment), h.destinationsSince(framesBefore))
            assertSessionAndLinkKept(h, session)

            assertEquals(mtuUncertain, session.infotainment.getState(StateCategory.DRIVE))
            assertEquals(2, h.fake.authenticatedPayloads(infotainment).size)
            assertEquals(listOf(infotainment, infotainment), h.destinationsSince(framesBefore))
            assertSessionAndLinkKept(h, session)

            assertEquals(drive, session.state(StateCategory.DRIVE).drive_state)
        }

    @Test
    fun mtuFaultWithAttachedSessionInfoStillRefreshesTheSession() =
        // dispatcher.go:295-299: 세션정보 반영은 응답 전달과 GetError보다 먼저이고 fault를 가리지 않는다. 금지되는 것은 fault 때문에 세션을 지우거나
        // 다시 연결하거나 깨우는 일이다. isReady는 갱신 전에도 참이므로, 차량 시계를 옮겨 두고 반영 여부를 timestamp로 구별한다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val state = assertNotNull(h.dispatcher.session(infotainment))
            val clockBefore = assertNotNull(state.timestamp())
            val framesBefore = h.transport.sent.size
            val events = mutableListOf<SessionEvent>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { session.sessionEvents.collect { events += it } }
            h.fake.shiftClock(infotainment, 2.seconds)
            h.fake.script(infotainment, listOf(mtuFault))
            h.fake.attachSessionInfoOnce(infotainment)

            val result = session.infotainment.getState(StateCategory.DRIVE)
            runCurrent()

            assertEquals(mtuUncertain, result)
            assertEquals(clockBefore + 2u, state.timestamp())
            assertEquals(listOf<SessionEvent>(SessionEvent.Updated(infotainment)), events) // 세션 캐시 저장의 신호
            assertEquals(1, h.fake.authenticatedPayloads(infotainment).size)
            assertEquals(listOf(infotainment), h.destinationsSince(framesBefore))
            assertSessionAndLinkKept(h, session)
            assertIs<VehicleResult.Success<VehicleData>>(session.infotainment.getState(StateCategory.DRIVE))
        }

    @Test
    fun mtuFaultIsDistinctFromTimeout() =
        // 둘 다 Uncertain이지만 MTU는 차량이 답한 것이고 Timeout은 답이 없는 것이다(vehicle.go:211-212). 호출자는 오류 타입으로 구분한다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(infotainment, listOf(mtuFault))

            val answered = session.infotainment.getState(StateCategory.DRIVE)
            h.fake.dropNextReplies(1)
            val lost = session.infotainment.getState(StateCategory.DRIVE)

            assertEquals(mtuUncertain, answered)
            assertEquals(replyLost, lost)
        }

    @Test
    fun sleepingInfotainmentTimesOutWithoutWakeOrResend() =
        // Go Send는 깨우지 않는다. Wakeup은 호출자가 따로 부른다(vehicle.go:259). 시간 초과는 PossibleSuccess라 재전송도 없다(vehicle.go:212, :246)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val framesBefore = h.transport.sent.size
            h.fake.sleep(setOf(infotainment))

            val result = session.infotainment.getState(StateCategory.CHARGE)

            assertEquals(replyLost, result)
            assertEquals(listOf(infotainment), h.destinationsSince(framesBefore)) // VCSEC 깨우기와 재전송이 없다
            assertTrue(session.isReady(infotainment))
        }

    @Test
    fun noSessionFailsBeforeSending() =
        // dispatcher.go:415-427: 세션이 없으면 응답 대기를 만들기 전에 ErrNoSession으로 끝난다. getState는 세션을 시작하지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)

            val result = session.infotainment.getState(StateCategory.CHARGE)

            assertEquals(VehicleResult.Failure(VehicleError.NoSession), result)
            assertTrue(h.transport.sent.isEmpty())
        }

    @Test
    fun busyFaultIsRetriedWithFreshAuthorization() =
        // FR-101, vehicle.go:236-257: BUSY는 재시도 대상이고 trySend마다 Dispatcher.Send가 다시 인가한다.
        // 재시도 간격을 BLE 값(1초)으로 두어야 초 단위인 expires_at도 달라진다
        runTest {
            val h = dispatcherHarness(retryInterval = 1.seconds)
            val session = connected(h)
            val charge = ChargeState(battery_level = 72)
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY)),
                replyWith(VehicleData(charge_state = charge)),
            )

            val data = session.state(StateCategory.CHARGE)

            assertEquals(charge, data.charge_state)
            val requests = h.fake.received.mapNotNull { it.signature_data?.AES_GCM_Personalized_data }
            assertEquals(2, requests.size)
            val (first, second) = requests
            assertNotEquals(first.counter, second.counter)
            assertNotEquals(first.nonce, second.nonce)
            assertNotEquals(first.expires_at, second.expires_at)
        }

    @Test
    fun cancelledQueryLeavesNoPendingRequest() =
        // 취소된 뒤의 job.isCancelled는 값으로 삼켜도 참이다(SendWithRetryTest). 전파의 증거는 결과 대입이 일어나지 않는 것이다.
        // 대기 등록은 uuid별이라 남아도 다음 조회를 막지 않으므로 pendingCount를 따로 본다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val charge = ChargeState(battery_level = 72)
            h.fake.script(infotainment, emptyList(), replyWith(VehicleData(charge_state = charge)))
            var result: VehicleResult<VehicleData>? = null
            val query = launch { result = session.infotainment.getState(StateCategory.CHARGE) }
            runCurrent()
            assertEquals(1, h.dispatcher.pendingCount())

            query.cancel()
            runCurrent()

            assertTrue(query.isCompleted)
            assertNull(result)
            assertEquals(0, h.dispatcher.pendingCount())
            assertEquals(charge, session.state(StateCategory.CHARGE).charge_state)
        }
}
