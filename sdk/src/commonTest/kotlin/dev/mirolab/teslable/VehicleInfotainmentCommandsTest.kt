package dev.mirolab.teslable

import com.tesla.generated.carserver.common.ChargeSchedule
import com.tesla.generated.carserver.common.PreconditionSchedule
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.CabinOverheatProtectionTemperature
import dev.mirolab.teslable.model.ChargingPolicy
import dev.mirolab.teslable.model.ClimateKeeperMode
import dev.mirolab.teslable.model.SeatClimateLevel
import dev.mirolab.teslable.model.SeatPosition
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.testing.FakeTransportFactory
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.FakeVehicleKeyStore
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.toHex
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

/**
 * FR-034, FR-050~053, FR-060~085, FR-090~092: 공개 `Vehicle`의 Infotainment 명령 51개. 페이로드 유도와 실패 경로는 `:application`의
 * `*CommandsTest`·`InfotainmentCommandFailureTest`가 고정하고, 여기서는 파사드의 몫(메서드마다 맞는 명령으로 잇기, 기본/사용자
 * timeout, `NoSession`이면 세션 표시 맞추기)만 본다.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class VehicleInfotainmentCommandsTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    private class Rig(
        val fake: FakeVehicle,
        val keys: FakeVehicleKeyStore,
        val ble: TeslaBle,
    )

    /** 파사드 명령 하나와 그 평문 hex(유도는 `:application` 시험에 있다). */
    private class Call(
        val name: String,
        val hex: String,
        val call: suspend Vehicle.() -> VehicleResult<Unit>,
    )

    /** 공개 Infotainment 명령 51개 전부. 인자마다 다른 바이트가 나오게 골라 메서드가 엉뚱한 명령에 이어지면 드러난다. */
    private val everyCall: List<Call> =
        listOf(
            Call("climateOn", "120452020801") { climateOn() },
            Call("climateOff", "12025200") { climateOff() },
            Call("setSeatHeater", "1209a202060a042a003a00") { setSeatHeater(mapOf(SeatPosition.FRONT_LEFT to SeatClimateLevel.HIGH)) },
            Call("setClimateTemp", "1210720e2a021a00350000b0413d0000ac41") { setClimateTemp(22.0f, 21.5f) },
            Call("setSeatCooler", "12098a03060a0408021002") { setSeatCooler(SeatClimateLevel.LOW, SeatPosition.FRONT_RIGHT) },
            Call("setSteeringWheelHeater", "12046a020801") { setSteeringWheelHeater(true) },
            Call("autoSeatAndClimate", "1209820306" + "0a021002" + "0a00") {
                autoSeatAndClimate(listOf(SeatPosition.FRONT_RIGHT, SeatPosition.UNKNOWN), enabled = false)
            },
            Call("setPreconditioningMax", "1206620408011001") { setPreconditioningMax(enabled = true, manualOverride = true) },
            Call("setClimateKeeperMode", "1207e2020408021001") { setClimateKeeperMode(ClimateKeeperMode.DOG, manualOverride = true) },
            Call("setBioweaponDefenseMode", "12059a02020801") { setBioweaponDefenseMode(enabled = true, manualOverride = false) },
            Call("setCabinOverheatProtection", "120792030408011001") { setCabinOverheatProtection(enabled = true, fanOnly = true) },
            Call("setCabinOverheatProtectionTemperature", "12059204020803") {
                setCabinOverheatProtectionTemperature(CabinOverheatProtectionTemperature.HIGH)
            },
            Call("chargeStart", "120432021200") { chargeStart() },
            Call("chargeStop", "120432022a00") { chargeStop() },
            Call("setChargeLimit", "12042a020850") { setChargeLimit(80) },
            Call("setChargingAmps", "1205da02020820") { setChargingAmps(32) },
            Call("chargeStandardRange", "120432021a00") { chargeStandardRange() },
            Call("chargeMaxRange", "120432022200") { chargeMaxRange() },
            Call("scheduleCharging", "1207ca020408011078") { scheduleCharging(enabled = true, minutesAfterMidnight = 120) },
            Call("scheduleDeparture", "1213d20210" + "0801" + "10e003" + "1a020a00" + "22021200" + "28e802") {
                scheduleDeparture(480, 360, ChargingPolicy.ALL_DAYS, ChargingPolicy.WEEKDAYS)
            },
            Call("clearScheduledDeparture", "1203d20200") { clearScheduledDeparture() },
            Call("setLowPowerMode", "12059208020801") { setLowPowerMode(true) },
            Call("setKeepAccessoryPowerMode", "1205d208020801") { setKeepAccessoryPowerMode(true) },
            // 12 07 | 8a 06 04 | 08 01 (id) 48 01 (enabled)
            Call("addChargeSchedule", "12078a060408014801") { addChargeSchedule(ChargeSchedule(id = 1, enabled = true)) },
            Call("removeChargeSchedule", "1209920606" + "0880e2cfaa06") { removeChargeSchedule(1_700_000_000L) },
            Call(
                "batchRemoveChargeSchedules",
                "1207e2060408011801",
            ) { batchRemoveChargeSchedules(home = true, work = false, other = true) },
            // 12 07 | 9a 06 04 | 08 02 (id) 30 01 (enabled)
            Call("addPreconditionSchedule", "12079a060408023001") { addPreconditionSchedule(PreconditionSchedule(id = 2, enabled = true)) },
            Call("removePreconditionSchedule", "1205a206020802") { removePreconditionSchedule(2L) },
            Call("batchRemovePreconditionSchedules", "1207da060410011801") {
                batchRemovePreconditionSchedules(home = false, work = true, other = true)
            },
            Call("openChargePort", "1203f20300") { openChargePort() },
            Call("closeChargePort", "1203ea0300") { closeChargePort() },
            Call("ping", "1205f20202" + "0801") { ping() },
            Call("ventWindows", "1205920202" + "1a00") { ventWindows() },
            Call("closeWindows", "1205920202" + "2200") { closeWindows() },
            Call("setSunroof", "1205820202" + "0800") { setSunroof(0) }, // 0도 absolute_level로 나간다
            Call("honkHorn", "1203da0100") { honkHorn() },
            Call("flashLights", "1203d20100") { flashLights() },
            Call("setSentryMode", "1205f20102" + "0801") { setSentryMode(true) },
            Call("enableValetMode", "120bfa0108" + "0801" + "120431323334") { enableValetMode("1234") },
            Call("disableValetMode", "1203fa0100") { disableValetMode() },
            Call("resetValetPin", "1203e20100") { resetValetPin() },
            Call("volumeUp", "1205820102" + "0802") { volumeUp() },
            Call("volumeDown", "1205820102" + "0801") { volumeDown() },
            Call("setVolume", "1208820105" + "1d00000000") { setVolume(0.0f) }, // 0도 volume_absolute_float로 나간다
            Call("mediaNextTrack", "12039a0100") { mediaNextTrack() },
            Call("mediaPreviousTrack", "1203a20100") { mediaPreviousTrack() },
            Call("mediaNextFavorite", "12038a0100") { mediaNextFavorite() },
            Call("mediaPreviousFavorite", "1203920100") { mediaPreviousFavorite() },
            Call("toggleMediaPlayback", "12027a00") { toggleMediaPlayback() },
            Call("scheduleSoftwareUpdate", "1206ea0103" + "08a038") { scheduleSoftwareUpdate(7200) },
            Call("cancelSoftwareUpdate", "1203ca0100") { cancelSoftwareUpdate() },
        )

    private fun TestScope.rig(
        config: TeslaBleConfig = TeslaBleConfig(),
        fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource),
    ): Rig {
        val keys = FakeVehicleKeyStore()
        val ble =
            TeslaBle(
                transports = FakeTransportFactory(fake),
                keys = keys,
                sessionCache = RecordingSessionCache(),
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

    /**
     * 복원됐다고 표시만 하고 실제 세션은 없는 차량(`VehicleStateTest.noSessionResultReconcilesTheInfotainmentStatus`와 같은 구성). TeslaBle로
     * 연결하면 세션 사건이 명령보다 먼저 수집되어 `NoSession` 결과의 보정을 볼 수 없다.
     */
    private suspend fun TestScope.restoredWithoutASession(fake: FakeVehicle): Vehicle {
        val transport = fake.transport()
        val key = FakeVehicleKeyStore().key()
        val dispatcher = Dispatcher(transport, key, TestCrypto.primitives, TestCrypto.random, backgroundScope, testTimeSource)
        val session = VehicleSession(dispatcher)
        session.connect()
        return DefaultVehicle(
            fake.vin,
            hasKey = true,
            transport,
            session,
            restored = VehicleDomain.ALL,
            backgroundScope,
            TeslaBleConfig(),
            broadcasts = dispatcher.plainBroadcasts,
        )
    }

    @Test
    fun everyCommandReachesTheVehicleAsItsOwnGoPayload() =
        runTest {
            assertEquals(51, everyCall.size)
            val r = rig()
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.INFOTAINMENT), timeout = 1.seconds))

            for (call in everyCall) assertEquals(VehicleResult.Success(Unit), call.call(vehicle), call.name)

            assertEquals(everyCall.map { it.hex }, r.fake.authenticatedPayloads(infotainment).map { it.toHex() })
        }

    @Test
    fun commandsUseTheConfiguredCommandTimeoutUnlessOneIsGiven() =
        // ADR-0010: timeout = null → TeslaBleConfig.commandTimeout. 넘겨준 시간은 그대로 쓴다. Infotainment가 잠들면 깨우지도 다시 보내지도
        // 않는다(Uncertain, 명령마다 프레임 하나)
        runTest {
            val r = rig(config = TeslaBleConfig(commandTimeout = 2.seconds))
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.INFOTAINMENT), timeout = 1.seconds))
            r.fake.sleep(setOf(infotainment))
            val timedOut = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true))
            val sentBefore = r.fake.received.size

            val byConfig = testTimeSource.markNow()
            assertEquals(timedOut, vehicle.climateOn())
            assertEquals(2.seconds, byConfig.elapsedNow())

            val byArgument = testTimeSource.markNow()
            assertEquals(timedOut, vehicle.chargeStart(timeout = 300.milliseconds))
            assertEquals(300.milliseconds, byArgument.elapsedNow())

            val validated = testTimeSource.markNow()
            assertEquals(timedOut, vehicle.scheduleDeparture(480, 0, ChargingPolicy.OFF, ChargingPolicy.OFF, timeout = 400.milliseconds))
            assertEquals(400.milliseconds, validated.elapsedNow())

            assertEquals(sentBefore + 3, r.fake.received.size)
        }

    @Test
    fun everyCommandWithoutATimeoutUsesTheConfiguredCommandTimeout() =
        // 리뷰 m1: 파사드 메서드 하나라도 고정 시간을 넘기면 드러나도록 51개 전부를 timeout 없이 부른다. 잠든 Infotainment라 매번 설정값에서 끝난다
        runTest {
            val r = rig(config = TeslaBleConfig(commandTimeout = 2.seconds))
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.INFOTAINMENT), timeout = 1.seconds))
            r.fake.sleep(setOf(infotainment))
            val timedOut = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true))

            for (call in everyCall) {
                val started = testTimeSource.markNow()
                assertEquals(timedOut, call.call(vehicle), call.name)
                assertEquals(2.seconds, started.elapsedNow(), call.name)
            }
        }

    @Test
    fun noSessionResultOfEveryCommandReconcilesTheInfotainmentStatus() =
        // SDD "세션 표시": 사건이 유실돼도 NoSession 결과가 표시를 맞춘다. 메서드마다 새 차량으로 보아 모든 메서드가 INFOTAINMENT를 맞추는지 본다
        runTest {
            val cached = SessionStatus.Ready(fromCache = true)
            for (call in everyCall) {
                val fake = FakeVehicle(timeSource = testTimeSource)
                val vehicle = restoredWithoutASession(fake)
                assertEquals(cached, vehicle.sessions.value[VehicleDomain.INFOTAINMENT], call.name)

                assertEquals(VehicleResult.Failure(VehicleError.NoSession), call.call(vehicle), call.name)

                assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.INFOTAINMENT], call.name)
                assertEquals(cached, vehicle.sessions.value[VehicleDomain.VCSEC], call.name) // 명령의 도메인만 맞춘다
                assertEquals(emptyList(), fake.received, call.name) // 세션을 시작하지 않는다
            }
        }

    @Test
    fun invalidArgumentsAreRejectedBeforeAnythingIsSent() =
        // 로컬 검증은 Go에 있는 것만: 좌석 쿨러는 앞좌석만, 출발 시각은 0..1440분, 발레 PIN은 ASCII 숫자 네 개, 볼륨은 0..10(NaN 거부는 Go와 다르다)
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.INFOTAINMENT), timeout = 1.seconds))
            val sentBefore = r.fake.received.size

            assertIs<VehicleError.InvalidArgument>(
                assertIs<VehicleResult.Failure>(vehicle.setSeatCooler(SeatClimateLevel.LOW, SeatPosition.SECOND_ROW_LEFT)).error,
            )
            assertIs<VehicleError.InvalidArgument>(
                assertIs<VehicleResult.Failure>(vehicle.scheduleDeparture(1441, 0, ChargingPolicy.OFF, ChargingPolicy.OFF)).error,
            )
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(vehicle.enableValetMode("123")).error)
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(vehicle.setVolume(Float.NaN)).error)

            assertEquals(sentBefore, r.fake.received.size)
            assertEquals(SessionStatus.Ready(fromCache = false), vehicle.sessions.value[VehicleDomain.INFOTAINMENT]) // 표시는 그대로
        }
}
