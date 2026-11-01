package dev.mirolab.teslable

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.GetClimateState
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.testing.FakeTransportFactory
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.FakeVehicleKeyStore
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** FR-033: 공개 `Vehicle.getState`. 응답 해석과 실패 경로는 `:application`의 `GetStateTest`·`GetStateFailureTest`가 고정하고, 여기서는 파사드의 몫만 본다. */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleStateTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    private class Rig(
        val fake: FakeVehicle,
        val keys: FakeVehicleKeyStore,
        val ble: TeslaBle,
    )

    private fun TestScope.rig(
        config: TeslaBleConfig = TeslaBleConfig(),
        fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource),
        cache: RecordingSessionCache = RecordingSessionCache(),
    ): Rig {
        val keys = FakeVehicleKeyStore()
        val ble =
            TeslaBle(
                transports = FakeTransportFactory(fake),
                keys = keys,
                sessionCache = cache,
                crypto = TestCrypto.primitives,
                random = TestCrypto.random,
                config = config,
                timeSource = testTimeSource,
                vehicleScope = { CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])) },
            )
        return Rig(fake, keys, ble)
    }

    private suspend fun FakeVehicleKeyStore.key(): VehicleKey = assertIs<VehicleResult.Success<VehicleKey>>(getOrCreate()).value

    private suspend fun Rig.connected(): Vehicle = assertIs<VehicleResult.Success<Vehicle>>(ble.connect(fake.vin, keys.key())).value

    private fun selector(payload: ByteArray): GetVehicleData? {
        val action = Action.ADAPTER.decode(payload)
        return action.vehicleAction?.getVehicleData
    }

    @Test
    fun getStateUsesTheCommandTimeoutAndReturnsVehicleData() =
        // ADR-0010: timeout = null → TeslaBleConfig.commandTimeout. 넘겨준 시간은 그대로 쓴다
        runTest {
            val r = rig(config = TeslaBleConfig(commandTimeout = 2.seconds))
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(timeout = 1.seconds))
            val data = VehicleData(drive_state = DriveState(speed = 0))
            r.fake.script(infotainment, listOf(FakeVehicle.infotainmentPayload(Response(vehicleData = data))))

            assertEquals(VehicleResult.Success(data), vehicle.getState(StateCategory.DRIVE))
            assertEquals(VehicleResult.Success(VehicleData()), vehicle.getState(StateCategory.CLIMATE))

            assertEquals(
                listOf(GetVehicleData(getDriveState = GetDriveState()), GetVehicleData(getClimateState = GetClimateState())),
                r.fake.authenticatedPayloads(infotainment).map(::selector),
            )

            r.fake.sleep(setOf(infotainment))
            val timedOut = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true))
            val sentBefore = r.fake.received.size
            val byConfig = testTimeSource.markNow()
            assertEquals(timedOut, vehicle.getState(StateCategory.CHARGE))
            assertEquals(2.seconds, byConfig.elapsedNow())
            val byArgument = testTimeSource.markNow()
            assertEquals(timedOut, vehicle.getState(StateCategory.CHARGE, timeout = 300.milliseconds))
            assertEquals(300.milliseconds, byArgument.elapsedNow())
            assertEquals(sentBefore + 2, r.fake.received.size) // 조회마다 프레임 하나: 다시 보내지도 깨우지도 않는다
        }

    @Test
    fun noSessionResultReconcilesTheInfotainmentStatus() =
        // SDD "세션 표시": 사건이 유실돼도 NoSession 결과가 표시를 맞춘다. TeslaBle로 연결하면 사건이 명령보다 먼저 수집되어 이 보정을 볼 수 없다
        // (TeslaBleTest.noSessionResultKeepsAnUnreadyDomainNone). 복원됐다고 표시만 하고 실제 세션은 없는 차량을 직접 만들어 사건이 없는 상태를 재현한다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val transport = fake.transport()
            val key = FakeVehicleKeyStore().key()
            val dispatcher = Dispatcher(transport, key, TestCrypto.primitives, TestCrypto.random, backgroundScope, testTimeSource)
            val session = VehicleSession(dispatcher)
            session.connect()
            val vehicle =
                DefaultVehicle(
                    fake.vin,
                    hasKey = true,
                    transport,
                    session,
                    restored = VehicleDomain.ALL,
                    backgroundScope,
                    TeslaBleConfig(),
                    broadcasts = dispatcher.plainBroadcasts,
                )
            val cached = SessionStatus.Ready(fromCache = true)
            assertEquals(cached, vehicle.sessions.value[VehicleDomain.INFOTAINMENT])

            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.getState(StateCategory.CHARGE))

            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.INFOTAINMENT])
            assertEquals(cached, vehicle.sessions.value[VehicleDomain.VCSEC]) // 조회한 도메인만 맞춘다
            assertEquals(emptyList(), fake.received) // 세션을 시작하지 않는다
        }

    @Test
    fun staleCachedInfotainmentSessionEndsInNoSessionAndStartSessionRecovers() =
        // TeslaBleTest.staleCacheTurnsTheDomainBackToNone의 Infotainment 경로(SendWithRetry): 다른 차량 키로 저장된 캐시
        runTest {
            val cache = RecordingSessionCache()
            val other = rig(fake = FakeVehicle(vehicleKey = TestCrypto.goKnownVerifierKey(), timeSource = testTimeSource), cache = cache)
            val first = other.connected()
            assertIs<VehicleResult.Success<Unit>>(first.startSession(timeout = 1.seconds))
            first.disconnect()
            val r = rig(cache = cache)
            val vehicle = r.connected()
            assertEquals(SessionStatus.Ready(fromCache = true), vehicle.sessions.value[VehicleDomain.INFOTAINMENT])

            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.getState(StateCategory.CHARGE))

            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.INFOTAINMENT])
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.INFOTAINMENT), timeout = 1.seconds))
            assertIs<VehicleResult.Success<VehicleData>>(vehicle.getState(StateCategory.CHARGE))
        }
}
