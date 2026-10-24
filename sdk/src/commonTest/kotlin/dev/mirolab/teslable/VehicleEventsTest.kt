package dev.mirolab.teslable

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.VehicleLockState_E
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleEvent
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.ConnectOptions
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.TransportFactory
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.port.VehicleAdvertisement
import dev.mirolab.teslable.testing.FakeTransport
import dev.mirolab.teslable.testing.FakeTransportFactory
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.FakeVehicleKeyStore
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

/** FR-035(계획 Task 6): `Vehicle.events`는 연결 하나의 평문 VCSEC 브로드캐스트를 내고, 링크 손실이나 `disconnect()`에서 정상 완료한다. */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleEventsTest {
    private class Rig(
        private val factory: FakeTransportFactory,
        val ble: TeslaBle,
        val vin: Vin,
    ) {
        /** 마지막 연결의 가짜 전송. 차량 → 앱 프레임을 [FakeTransport.deliver]로 넣는다. */
        val transport: FakeTransport get() = checkNotNull(factory.lastTransport)
    }

    /** [wrap]은 연결마다 전송을 감싼다(링크 손실 흉내). 차량 스코프는 테스트의 backgroundScope 아래에 둔다. */
    private fun TestScope.rig(wrap: (FakeTransport) -> Transport = { it }): Rig {
        val fake = FakeVehicle(timeSource = testTimeSource)
        val real = FakeTransportFactory(fake)
        val factory =
            object : TransportFactory by real {
                override suspend fun connect(
                    vin: Vin,
                    target: VehicleAdvertisement?,
                    options: ConnectOptions,
                ): VehicleResult<Transport> =
                    when (val r = real.connect(vin, target, options)) {
                        is VehicleResult.Success -> VehicleResult.Success(wrap(checkNotNull(real.lastTransport)))
                        is VehicleResult.Failure -> r
                        is VehicleResult.Uncertain -> r
                    }
            }
        val ble =
            TeslaBle(
                transports = factory,
                keys = FakeVehicleKeyStore(),
                sessionCache = RecordingSessionCache(),
                crypto = TestCrypto.primitives,
                random = TestCrypto.random,
                config = TeslaBleConfig(),
                timeSource = testTimeSource,
                vehicleScope = { CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])) },
            )
        return Rig(real, ble, fake.vin)
    }

    /** 키 없이 연결한다 — 브로드캐스트는 평문이라 키도 세션도 필요 없다. */
    private suspend fun Rig.connected(): Vehicle = assertIs<VehicleResult.Success<Vehicle>>(ble.connect(vin, key = null)).value

    /** 차량이 보내는 모양의 평문 브로드캐스트(14-lib B1): from VCSEC, to BROADCAST, 요청 UUID·서명·세션 정보 없음. */
    private fun broadcast(lock: VehicleLockState_E): ByteArray {
        val payload = FromVCSECMessage(vehicleStatus = WireVehicleStatus(vehicleLockState = lock))
        return RoutableMessage(
            from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
            to_destination = Destination(domain = Domain.DOMAIN_BROADCAST),
            protobuf_message_as_bytes = payload.encode().toByteString(),
        ).encode()
    }

    private class Collected(
        val events: List<VehicleEvent>,
        val job: Job,
    ) {
        /** 예외도 취소도 없이 끝났는지. */
        val completedNormally: Boolean get() = job.isCompleted && !job.isCancelled
    }

    /** 앱처럼 [vehicle]의 events를 수집하기 시작한다. 구독이 실제로 열린 뒤 돌아온다(replay 0이라 그 전 프레임은 받지 않는다). */
    private fun TestScope.collect(vehicle: Vehicle): Collected {
        val events = mutableListOf<VehicleEvent>()
        val job = backgroundScope.launch { vehicle.events.collect { events += it } }
        runCurrent()
        return Collected(events, job)
    }

    /** 이미 끝난 연결에서 새로 수집하면 시간이 흐르지 않고 바로 끝난다. 끝나지 않으면 가상 1초 뒤 실패한다(테스트가 매달리지 않게). */
    private suspend fun TestScope.collectAfterTheEnd(vehicle: Vehicle): List<VehicleEvent> {
        val start = currentTime
        val events = withTimeout(1.seconds) { vehicle.events.toList() }
        assertEquals(start, currentTime)
        return events
    }

    /** 차량이 링크를 잃은 전송(이유 있는 Disconnected). 수신 흐름은 닫지 않는다. [close]는 BleTransport처럼 Connected일 때만 reason = null. */
    private class LinkLossTransport(
        private val inner: FakeTransport,
    ) : Transport by inner {
        private val mutableState = MutableStateFlow<TransportState>(TransportState.Connected)

        override val state: StateFlow<TransportState> = mutableState

        fun loseLink() {
            mutableState.value = TransportState.Disconnected(VehicleError.TransportError.Disconnected)
        }

        override suspend fun close() {
            mutableState.compareAndSet(TransportState.Connected, TransportState.Disconnected(reason = null))
            inner.close()
        }
    }

    @Test
    fun eventsDeliverBroadcast() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            val collected = collect(vehicle)
            val frame = broadcast(VehicleLockState_E.VEHICLELOCKSTATE_LOCKED)
            r.transport.deliver(frame)
            runCurrent()
            val event = assertIs<VehicleEvent.Status>(collected.events.single())
            assertEquals(LockState.LOCKED, event.status.lockState)
            assertContentEquals(frame, event.raw)
            assertEquals(emptyList(), event.unknownFields)
            assertFalse(collected.job.isCompleted) // 연결이 살아 있는 동안은 계속 열려 있다
        }

    @Test
    fun eventsCompleteOnLinkLoss() =
        // Review Focus 3: 링크 손실 뒤 수집이 매달리면 안 된다. 거리 이탈이 어느 Disconnected를 주는지 문서에 없어(스파이크) 둘 다 본다
        runTest {
            // (a) 전송이 이유 없이 닫힘: Disconnected(reason = null), 수신 흐름도 끝난다
            val closed = rig()
            val first = closed.connected()
            val firstEvents = collect(first)
            closed.transport.deliver(broadcast(VehicleLockState_E.VEHICLELOCKSTATE_LOCKED))
            runCurrent()
            closed.transport.close()
            runCurrent()
            assertEquals(TransportState.Disconnected(reason = null), first.connection.value)
            assertTrue(firstEvents.completedNormally)
            assertEquals(1, firstEvents.events.size)
            assertEquals(emptyList(), collectAfterTheEnd(first)) // 앱이 아직 disconnect()하지 않았어도

            // (b) 차량 쪽에서 링크를 잃음: Disconnected(TransportError.Disconnected), 수신 흐름은 열린 채
            var lost: LinkLossTransport? = null
            val dropped = rig(wrap = { inner -> LinkLossTransport(inner).also { lost = it } })
            val second = dropped.connected()
            val secondEvents = collect(second)
            dropped.transport.deliver(broadcast(VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED))
            runCurrent()
            checkNotNull(lost).loseLink()
            runCurrent()
            assertEquals(TransportState.Disconnected(VehicleError.TransportError.Disconnected), second.connection.value)
            assertTrue(secondEvents.completedNormally)
            assertEquals(LockState.UNLOCKED, assertIs<VehicleEvent.Status>(secondEvents.events.single()).status.lockState)
            assertEquals(emptyList(), collectAfterTheEnd(second))
        }

    @Test
    fun eventsCompleteOnDisconnect() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            val collected = collect(vehicle)
            r.transport.deliver(broadcast(VehicleLockState_E.VEHICLELOCKSTATE_LOCKED))
            runCurrent()
            vehicle.disconnect()
            runCurrent()
            assertTrue(collected.completedNormally)
            assertEquals(1, collected.events.size)
            assertEquals(emptyList(), collectAfterTheEnd(vehicle)) // disconnect() 뒤에 시작한 수집도 바로 끝난다
        }

    @Test
    fun noReplayBeforeSubscribe() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            val early = broadcast(VehicleLockState_E.VEHICLELOCKSTATE_LOCKED)
            r.transport.deliver(early) // 아무도 구독하지 않을 때 — 버려진다
            runCurrent()
            val first = collect(vehicle)
            val middle = broadcast(VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED)
            r.transport.deliver(middle)
            runCurrent()
            val second = collect(vehicle) // 늦게 온 수집자는 그 뒤의 것만
            val late = broadcast(VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED)
            r.transport.deliver(late)
            runCurrent()
            assertEquals(listOf(middle.toList(), late.toList()), first.events.map { it.raw.toList() })
            assertEquals(listOf(late.toList()), second.events.map { it.raw.toList() })
            vehicle.disconnect()
            runCurrent()
            assertTrue(first.completedNormally)
            assertTrue(second.completedNormally)
        }

    @Test
    fun newConnectionHasNewEvents() =
        runTest {
            val r = rig()
            val first = r.connected()
            val firstEvents = collect(first)
            first.disconnect()
            runCurrent()
            assertTrue(firstEvents.completedNormally)
            val second = r.connected()
            val secondEvents = collect(second)
            r.transport.deliver(broadcast(VehicleLockState_E.VEHICLELOCKSTATE_LOCKED)) // 새 연결의 전송
            runCurrent()
            assertEquals(LockState.LOCKED, assertIs<VehicleEvent.Status>(secondEvents.events.single()).status.lockState)
            assertFalse(secondEvents.job.isCompleted)
            assertEquals(emptyList(), firstEvents.events)
            assertEquals(emptyList(), collectAfterTheEnd(first)) // 끝난 연결은 계속 끝난 상태
        }
}
