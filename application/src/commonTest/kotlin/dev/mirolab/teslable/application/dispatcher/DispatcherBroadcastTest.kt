package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleStatus
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.observe.ObservationLog
import dev.mirolab.teslable.application.observe.ObservedAt
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.testing.FakeTransport
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.mirolab.teslable.model.VehicleStatus as ModelVehicleStatus

/** FR-035(계획 Task 4): 디스패처가 버리는 `TO_DOMAIN` 프레임 중 평문 VCSEC 브로드캐스트만 `plainBroadcasts`로 내보낸다. */
@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class DispatcherBroadcastTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    private fun locked() = FromVCSECMessage(vehicleStatus = VehicleStatus(vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED))

    /** 게이트를 통과하는 실차 모양(14-lib B2): from VCSEC, to BROADCAST, 요청 UUID·서명·세션 정보 없음. */
    private fun broadcast(): RoutableMessage =
        RoutableMessage(
            from_destination = Destination(domain = vcsec),
            to_destination = Destination(domain = Domain.DOMAIN_BROADCAST),
            protobuf_message_as_bytes = locked().encode().toByteString(),
        )

    /** 받은 원소를 모은다. [stall]이면 첫 원소를 받은 뒤 영원히 멈춘다(느린 구독자). */
    private fun TestScope.collectBroadcasts(
        h: DispatcherHarness,
        stall: Boolean = false,
    ): List<ByteArray> {
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.dispatcher.plainBroadcasts.collect {
                received += it
                if (stall) awaitCancellation()
            }
        }
        return received
    }

    private fun RecordingLogger.overflowWarnings(): List<RecordingLogger.Entry> = entries.filter { it.message.contains(OVERFLOW) }

    @Test
    fun plainBroadcastIsEmitted() =
        runTest {
            val h = dispatcherHarness()
            val received = collectBroadcasts(h)
            val frame = encode(broadcast())
            h.transport.deliver(frame)
            runCurrent()
            assertEquals(1, received.size)
            assertContentEquals(frame, received.single())
        }

    @Test
    fun signedOrAddressedFrameIsNotEmitted() =
        runTest {
            val h = dispatcherHarness()
            val received = collectBroadcasts(h)
            val encrypted =
                SignatureData(
                    AES_GCM_Response_data =
                        AES_GCM_Response_Signature_Data(
                            nonce = ByteArray(12).toByteString(),
                            counter = 1,
                            tag = ByteArray(16).toByteString(),
                        ),
                )
            val rejected =
                listOf(
                    broadcast().copy(signature_data = encrypted),
                    broadcast().copy(to_destination = Destination(routing_address = ByteArray(16) { 9 }.toByteString())),
                    broadcast().copy(request_uuid = ByteArray(16) { 7 }.toByteString()),
                    // payload oneof: 세션 정보 프레임에는 protobuf_message_as_bytes가 없다
                    broadcast().copy(protobuf_message_as_bytes = null, session_info = SessionInfo(counter = 1).encode().toByteString()),
                )
            for (message in rejected) h.transport.deliver(encode(message))
            runCurrent()
            assertEquals(0, received.size)
            // 대조: 같은 구독자가 게이트를 통과한 프레임은 받는다
            val plain = encode(broadcast())
            h.transport.deliver(plain)
            runCurrent()
            assertContentEquals(plain, received.single())
        }

    @Test
    fun frameFromOrToAnotherDomainIsNotEmitted() =
        // 게이트의 나머지 두 조건(from VCSEC, to BROADCAST). 모두 TO_DOMAIN으로 버려지는 프레임이다.
        runTest {
            val h = dispatcherHarness()
            val received = collectBroadcasts(h)
            val rejected =
                listOf(
                    broadcast().copy(from_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT)),
                    broadcast().copy(from_destination = Destination(routing_address = ByteArray(16) { 3 }.toByteString())),
                    broadcast().copy(to_destination = Destination(domain = vcsec)),
                    broadcast().copy(to_destination = Destination()),
                )
            for (message in rejected) h.transport.deliver(encode(message))
            runCurrent()
            assertEquals(0, received.size)
            val toDomainDrops = h.logger.entries.count { it.tag == ObservationLog.TAG && it.message.startsWith("[drop:to-domain] ") }
            assertEquals(rejected.size, toDomainDrops)
            val plain = encode(broadcast())
            h.transport.deliver(plain)
            runCurrent()
            assertContentEquals(plain, received.single())
        }

    @Test
    fun broadcastStillDroppedFromRouting() =
        // 원본(dispatcher.go:270-272)처럼 응답 매칭에는 들어가지 않는다. 관찰 로그 줄도 그대로다.
        runTest {
            val h = dispatcherHarness()
            val received = collectBroadcasts(h)
            h.fake.script(vcsec, emptyList()) // 열린 VCSEC 요청(응답 없음)
            val pending = assertIs<VehicleResult.Success<PendingRequest>>(h.dispatcher.send(testCommand(vcsec), AuthMethod.NONE)).value
            val message = broadcast()
            val frame = encode(message)
            h.transport.deliver(frame)
            runCurrent()
            assertEquals(1, received.size)
            assertNull(pending.tryReceive()) // 기다리던 VCSEC 요청에 배달되지 않는다
            pending.close()
            val expected = ObservationLog(TestCrypto.primitives).routable(ObservedAt.TO_DOMAIN, message, frame.size, owner = null)
            assertEquals(
                listOf(expected),
                h.logger.entries
                    .filter { it.tag == ObservationLog.TAG }
                    .map { it.message },
            )
            assertTrue(h.logger.contains("Dropping message to DOMAIN_BROADCAST"))
        }

    @Test
    fun slowCollectorDoesNotDelayReplies() =
        // Review Focus 1: 구독자가 멈춰 있어도 수신 코루틴은 기다리지 않는다(tryEmit). 응답은 브로드캐스트 100건 뒤에 도착한다.
        runTest {
            val h = dispatcherHarness()
            collectBroadcasts(h, stall = true)
            repeat(100) { h.transport.deliver(encode(broadcast())) }
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(locked())))
            val status = VehicleSession(h.dispatcher).info.vehicleStatus()
            assertIs<VehicleResult.Success<ModelVehicleStatus>>(status)
            assertEquals(0L, currentTime) // 가상 시간이 흐르지 않았다: 응답이 늦어지지 않았다
            assertTrue(h.logger.overflowWarnings().isNotEmpty()) // 버퍼가 실제로 가득 찼다(시나리오 확인)
        }

    @Test
    fun overflowCountsAndWarns() =
        // 버퍼 64 + 수집기가 이미 꺼낸 1건: 66번째 프레임이 처음으로 버려진다. 수집기가 돌기 전에 한꺼번에 오면 첫 값도
        // 버퍼에 남아 있으므로 65번째가 첫 drop이다(실측) — 그래서 첫 프레임을 먼저 꺼내게 한다.
        runTest {
            val h = dispatcherHarness()
            val received = collectBroadcasts(h, stall = true)
            val frame = encode(broadcast())
            h.transport.deliver(frame)
            runCurrent()
            assertEquals(1, received.size) // 수집기가 첫 값을 꺼내고 멈췄다
            repeat(BUFFER) { h.transport.deliver(frame) }
            runCurrent()
            assertEquals(emptyList(), h.logger.overflowWarnings().map { it.message }) // 65건까지는 버리지 않는다
            h.transport.deliver(frame)
            runCurrent()
            val warning = h.logger.overflowWarnings().single()
            assertEquals(LogLevel.WARN, warning.level)
            assertEquals("Dispatcher", warning.tag)
            assertEquals("$OVERFLOW (dropped 1 since connect)", warning.message)
            assertEquals(1, received.size)
        }

    @Test
    fun overflowWarnsAgainEveryHundredthDrop() =
        runTest {
            val h = dispatcherHarness()
            collectBroadcasts(h, stall = true)
            val frame = encode(broadcast())
            h.transport.deliver(frame)
            runCurrent()
            repeat(BUFFER + 200) { h.transport.deliver(frame) } // 버퍼를 채운 뒤 200건을 버린다
            runCurrent()
            assertEquals(
                listOf(1, 100, 200).map { "$OVERFLOW (dropped $it since connect)" },
                h.logger.overflowWarnings().map { it.message },
            )
        }

    @Test
    fun emittedBytesAreACopyOfTheReceivedFrame() =
        runTest {
            val frame = encode(broadcast())
            val fake = FakeTransport()
            val passThrough =
                object : Transport by fake {
                    override val incoming: Flow<ByteArray> = flowOf(frame)
                }
            val dispatcher = Dispatcher(passThrough, null, TestCrypto.primitives, TestCrypto.random, backgroundScope, testTimeSource)
            val received = mutableListOf<ByteArray>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.plainBroadcasts.collect { received += it } }
            dispatcher.start()
            runCurrent()
            assertContentEquals(frame, received.single())
            assertNotSame(frame, received.single())
        }

    private companion object {
        /** 계획 Task 4: `extraBufferCapacity = 64`. */
        const val BUFFER = 64
        const val OVERFLOW = "Dropping plain broadcast because the broadcast buffer is full"
    }
}
