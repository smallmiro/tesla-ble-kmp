package io.github.smallmiro.teslable

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.WhitelistInfo
import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.LockState
import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.TransportState
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.testing.FakeTransportFactory
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.FakeVehicleKeyStore
import io.github.smallmiro.teslable.testing.RecordingSessionCache
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

@OptIn(ExperimentalCoroutinesApi::class)
class TeslaBleTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    private class Rig(
        val fake: FakeVehicle,
        val factory: FakeTransportFactory,
        val cache: RecordingSessionCache,
        val keys: FakeVehicleKeyStore,
        val ble: TeslaBle,
    )

    private fun TestScope.rig(
        config: TeslaBleConfig = TeslaBleConfig(),
        fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource),
        cache: RecordingSessionCache = RecordingSessionCache(),
    ): Rig {
        val factory = FakeTransportFactory(fake)
        val keys = FakeVehicleKeyStore()
        val ble =
            TeslaBle(
                transports = factory,
                keys = keys,
                sessionCache = cache,
                crypto = TestCrypto.primitives,
                random = TestCrypto.random,
                config = config,
                timeSource = testTimeSource,
                vehicleScope = { CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])) },
            )
        return Rig(fake, factory, cache, keys, ble)
    }

    private suspend fun Rig.key(): VehicleKey = assertIs<VehicleResult.Success<VehicleKey>>(keys.getOrCreate()).value

    private suspend fun Rig.connected(withKey: Boolean = true): Vehicle =
        assertIs<VehicleResult.Success<Vehicle>>(ble.connect(fake.vin, if (withKey) key() else null)).value

    @Test
    fun scanUsesTheVinLocalName() =
        runTest {
            val r = rig()
            val found =
                assertIs<ScanEvent.Found>(
                    r.ble
                        .scan(r.fake.vin)
                        .toList()
                        .single(),
                )
            assertEquals(LocalName.of(r.fake.vin, TestCrypto.primitives), found.advertisement.localName)
        }

    @Test
    fun connectFailureIsAValueNotAnException() =
        runTest {
            val r = rig()
            r.factory.enqueueConnectFailure(VehicleError.TransportError.BluetoothOff)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.BluetoothOff), r.ble.connect(r.fake.vin, r.key()))
        }

    @Test
    fun handshakeUpdatesSessionsAndDisconnectStoresTheCacheUnderTheKeysId() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertTrue(vehicle.hasKey)
            assertEquals(TransportState.Connected, vehicle.connection.value)
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value)
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(timeout = 1.seconds))
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.Ready(fromCache = false) }, vehicle.sessions.value)
            vehicle.disconnect()
            val stored = r.cache.stores.last()
            assertEquals(KeyId.of(r.key().publicKey, TestCrypto.primitives), stored.keyId) // 설계 구체화 15: keyId는 파사드가 키에서 유도
            assertEquals(2, stored.entries.size)
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value)
        }

    @Test
    fun reconnectRestoresSessionsFromCacheWithoutHandshake() =
        runTest {
            val r = rig()
            val first = r.connected()
            first.startSession(timeout = 1.seconds)
            first.disconnect()
            val requestsBefore = r.fake.sessionInfoRequests
            val second = r.connected()
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.Ready(fromCache = true) }, second.sessions.value)
            assertIs<VehicleResult.Success<Unit>>(second.startSession(timeout = 1.seconds))
            assertEquals(requestsBefore, r.fake.sessionInfoRequests)
        }

    @Test
    fun vehicleStatusAndKeySummaryWorkWithoutAKey() =
        // FR-025, FR-031: 인증 없는 조회는 키 없이도
        runTest {
            val r = rig()
            r.fake.script(
                vcsec,
                listOf(
                    FakeVehicle.vcsecPayload(
                        FromVCSECMessage(vehicleStatus = WireVehicleStatus(vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED)),
                    ),
                ),
            )
            r.fake.script(
                vcsec,
                listOf(FakeVehicle.vcsecPayload(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 1, slotMask = 1)))),
            )
            val vehicle = r.connected(withKey = false)
            assertFalse(vehicle.hasKey)
            assertEquals(
                LockState.LOCKED,
                assertIs<VehicleResult.Success<io.github.smallmiro.teslable.model.VehicleStatus>>(vehicle.vehicleStatus()).value.lockState,
            )
            assertEquals(
                listOf(0),
                assertIs<VehicleResult.Success<io.github.smallmiro.teslable.model.WhitelistSummary>>(
                    vehicle.keySummary(),
                ).value.occupiedSlots,
            )
            assertEquals(VehicleResult.Failure(VehicleError.RequiresKey), vehicle.startSession(timeout = 1.seconds))
        }

    @Test
    fun commandTimeoutDefaultsComeFromConfig() =
        // ADR-0010: timeout = null → TeslaBleConfig.commandTimeout
        runTest {
            val r = rig(config = TeslaBleConfig(commandTimeout = 2.seconds))
            val vehicle = r.connected(withKey = false)
            r.fake.sleep(setOf(vcsec))
            val start = testTimeSource.markNow()
            assertIs<VehicleResult.Uncertain>(vehicle.vehicleStatus())
            assertEquals(2.seconds, start.elapsedNow())
        }

    @Test
    fun linkDropDuringCommandIsUncertainThenNotConnected() =
        // Review Focus 1
        runTest {
            val r = rig()
            val vehicle = r.connected(withKey = false)
            r.fake.sleep(setOf(vcsec))
            val pending = async { vehicle.vehicleStatus(timeout = 3.seconds) }
            runCurrent()
            checkNotNull(r.factory.lastTransport).close() // 링크 끊김 — incoming 완료
            advanceTimeBy(3.seconds + 1.milliseconds)
            val uncertain = assertIs<VehicleResult.Uncertain>(pending.await())
            assertEquals(VehicleError.Timeout(afterSend = true), uncertain.error)
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.vehicleStatus(timeout = 1.seconds))
            assertIs<TransportState.Disconnected>(vehicle.connection.value)
        }

    @Test
    fun commandsAfterDisconnectReturnNotConnectedAndKeepCache() =
        // Review Focus 4
        runTest {
            val r = rig()
            val vehicle = r.connected()
            vehicle.startSession(timeout = 1.seconds)
            vehicle.disconnect()
            val stores = r.cache.stores.size
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.listKeys(timeout = 1.seconds))
            vehicle.disconnect() // 멱등
            assertEquals(stores, r.cache.stores.size)
            assertEquals(
                2,
                r.cache.stores
                    .last()
                    .entries.size,
            )
        }

    @Test
    fun throwingAppLoggerDoesNotBreakTheConnection() =
        // 설계 구체화 15: SafeLogger
        runTest {
            val r = rig(config = TeslaBleConfig(logger = TeslaLogger { _, _, _ -> error("app logger bug") }))
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(timeout = 1.seconds))
        }

    /** 링크를 잃은 전송(이유 있는 Disconnected)을 흉내 내고 close() 호출 수를 센다. */
    private class DroppableTransport(
        private val inner: Transport,
    ) : Transport by inner {
        private val mutableState = MutableStateFlow<TransportState>(TransportState.Connected)
        var closeCalls = 0
            private set

        override val state: StateFlow<TransportState> = mutableState

        fun dropLink() {
            mutableState.value = TransportState.Disconnected(VehicleError.TransportError.Disconnected)
        }

        override suspend fun close() {
            closeCalls++
            inner.close()
        }
    }

    @Test
    fun linkLostByTheVehicleReleasesTheTransportExactlyOnce() =
        // 컨트롤러 이월(Task 5 리뷰): 차량이 링크를 끊고 아무도 close()하지 않으면 플랫폼 링크가 샌다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val real = FakeTransportFactory(fake)
            var wrapped: DroppableTransport? = null
            val factory =
                object : TransportFactory by real {
                    override suspend fun connect(
                        vin: Vin,
                        target: VehicleAdvertisement?,
                        options: ConnectOptions,
                    ): VehicleResult<Transport> =
                        when (val r = real.connect(vin, target, options)) {
                            is VehicleResult.Success -> VehicleResult.Success(DroppableTransport(r.value).also { wrapped = it })
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
                    vehicleScope = {
                        CoroutineScope(
                            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
                        )
                    },
                )
            val key = assertIs<VehicleResult.Success<VehicleKey>>(FakeVehicleKeyStore().getOrCreate()).value
            val vehicle = assertIs<VehicleResult.Success<Vehicle>>(ble.connect(fake.vin, key)).value
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(timeout = 1.seconds))
            val transport = checkNotNull(wrapped)
            assertEquals(0, transport.closeCalls)
            transport.dropLink()
            runCurrent()
            assertEquals(1, transport.closeCalls)
            assertIs<TransportState.Disconnected>(vehicle.connection.value)
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value) // connection과 일치
            runCurrent()
            assertEquals(1, transport.closeCalls) // 한 번만
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.vehicleStatus(timeout = 1.seconds))
        }

    private class GatedCache(
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : SessionCache {
        override suspend fun load(
            vin: Vin,
            keyId: KeyId,
        ): List<CachedSession> {
            gate.await()
            return emptyList()
        }

        override suspend fun store(
            vin: Vin,
            keyId: KeyId,
            entries: List<SessionSnapshot>,
        ) = Unit

        override suspend fun clear(vin: Vin) = Unit
    }

    @Test
    fun cancelledConnectReleasesTheTransport() =
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val gated = GatedCache()
            val r = rig(fake = fake)
            val factory = FakeTransportFactory(fake)
            val ble =
                TeslaBle(
                    transports = factory,
                    keys = r.keys,
                    sessionCache = gated,
                    crypto = TestCrypto.primitives,
                    random = TestCrypto.random,
                    config = TeslaBleConfig(),
                    timeSource = testTimeSource,
                    vehicleScope = {
                        CoroutineScope(
                            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]),
                        )
                    },
                )
            val key = r.key()
            val job = launch { ble.connect(fake.vin, key) }
            runCurrent()
            val transport = checkNotNull(factory.lastTransport)
            assertEquals(TransportState.Connected, transport.state.value)
            job.cancelAndJoin()
            assertIs<TransportState.Disconnected>(transport.state.value)
            assertTrue(job.isCancelled)
        }

    @Test
    fun cancelledHandshakeResetsHandshakingToNone() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            r.fake.sleep(setOf(vcsec, Domain.DOMAIN_INFOTAINMENT))
            val job = launch { vehicle.startSession(timeout = 10.seconds) }
            runCurrent()
            assertEquals(SessionStatus.Handshaking, vehicle.sessions.value.getValue(VehicleDomain.VCSEC))
            job.cancelAndJoin()
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value)
        }

    @Test
    fun handshakeRacingDisconnectNeverLeavesReady() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            r.fake.sleep(setOf(vcsec, Domain.DOMAIN_INFOTAINMENT))
            val pending = async { vehicle.startSession(timeout = 10.seconds) }
            runCurrent()
            vehicle.disconnect()
            r.fake.wake()
            advanceTimeBy(11.seconds)
            pending.await()
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value)
        }

    @Test
    fun connectAfterCloseFailsWithoutTouchingTheFactory() =
        runTest {
            val r = rig()
            val key = r.key()
            r.ble.close()
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), r.ble.connect(r.fake.vin, key))
            assertEquals(0, r.factory.connectAttempts)
        }
}
