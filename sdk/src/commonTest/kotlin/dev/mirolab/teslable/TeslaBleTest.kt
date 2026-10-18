package dev.mirolab.teslable

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.WhitelistInfo
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.ConnectOptions
import dev.mirolab.teslable.port.ScanEvent
import dev.mirolab.teslable.port.SessionCache
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.TransportFactory
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.port.VehicleAdvertisement
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.testing.FakeTransportFactory
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.FakeVehicleKeyStore
import dev.mirolab.teslable.testing.RecordingSessionCache
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.transport.LocalName
import dev.mirolab.teslable.util.toHex
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
import kotlinx.coroutines.test.currentTime
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
import com.tesla.generated.vcsec.KeyFormFactor as WireFormFactor
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
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
                assertIs<VehicleResult.Success<dev.mirolab.teslable.model.VehicleStatus>>(vehicle.vehicleStatus()).value.lockState,
            )
            assertEquals(
                listOf(0),
                assertIs<VehicleResult.Success<dev.mirolab.teslable.model.WhitelistSummary>>(
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

    /** 링크를 잃은 전송(이유 있는 Disconnected)을 흉내 내고 close() 호출 수를 센다. close()는 BleTransport처럼 Connected일 때만 reason = null. */
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
            mutableState.compareAndSet(TransportState.Connected, TransportState.Disconnected(reason = null))
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
            // 최종 리뷰 I1: 감시자의 close()가 차량이 끊은 이유를 지우지 않는다(의도한 disconnect와 구별)
            assertEquals(TransportState.Disconnected(VehicleError.TransportError.Disconnected), vehicle.connection.value)
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
    fun closeDisconnectsConnectedVehiclesAndStoresTheCacheOnce() =
        // 최종 리뷰 I2: close()는 살아 있는 차량을 disconnect()한다(링크 해제, 세션 키 제거 NFR-005, 마지막 캐시 저장)
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(timeout = 1.seconds))
            val storesBefore = r.cache.stores.size
            r.ble.close()
            assertEquals(TransportState.Disconnected(null), checkNotNull(r.factory.lastTransport).state.value)
            assertEquals(TransportState.Disconnected(null), vehicle.connection.value)
            assertEquals(storesBefore + 1, r.cache.stores.size) // disconnect 시점 저장 한 번
            assertEquals(
                2,
                r.cache.stores
                    .last()
                    .entries.size,
            )
            assertEquals(VehicleDomain.ALL.associateWith { SessionStatus.None }, vehicle.sessions.value)
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.vehicleStatus(timeout = 1.seconds))
            r.ble.close() // 멱등
            assertEquals(storesBefore + 1, r.cache.stores.size)
        }

    @Test
    fun vehicleDisconnectedBeforeCloseIsNotDisconnectedAgain() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            vehicle.startSession(timeout = 1.seconds)
            vehicle.disconnect()
            val storesBefore = r.cache.stores.size
            r.ble.close()
            assertEquals(storesBefore, r.cache.stores.size) // disconnect()한 차량은 목록에서 빠진다
        }

    @Test
    fun closeDuringAnInFlightConnectFailsTheConnectAndReleasesTheTransport() =
        // 최종 리뷰 I2: 연결 도중 close()되면 죽은 Vehicle을 돌려주지 않는다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val gated = GatedCache()
            val factory = FakeTransportFactory(fake)
            val ble =
                TeslaBle(
                    transports = factory,
                    keys = FakeVehicleKeyStore(),
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
            val key = assertIs<VehicleResult.Success<VehicleKey>>(FakeVehicleKeyStore().getOrCreate()).value
            val pending = async { ble.connect(fake.vin, key) }
            runCurrent() // 캐시 load에서 멈춘다
            val transport = checkNotNull(factory.lastTransport)
            ble.close()
            gated.gate.complete(Unit)
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), pending.await())
            assertEquals(TransportState.Disconnected(null), transport.state.value)
        }

    @Test
    fun appScopeCancelledByCloseTurnsAnInFlightConnectIntoNotConnected() =
        // 최종 리뷰 I2: 앱 스코프 취소(호출자 취소 아님)로 난 CancellationException은 값으로 바꾼다(ADR-0010은 호출자 취소만 전파)
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            val real = FakeTransportFactory(fake)
            val appScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
            val gate = CompletableDeferred<Unit>()
            val factory =
                object : TransportFactory by real {
                    // BleTransportFactory.openOwned처럼 앱 스코프에서 열기를 기다린다
                    override suspend fun connect(
                        vin: Vin,
                        target: VehicleAdvertisement?,
                        options: ConnectOptions,
                    ): VehicleResult<Transport> =
                        appScope
                            .async {
                                gate.await()
                                real.connect(vin, target, options)
                            }.await()
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
                    vehicleScope = { CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job])) },
                    ownScope = appScope,
                )
            val pending = async { ble.connect(fake.vin, key = null) }
            runCurrent()
            ble.close()
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), pending.await())
        }

    @Test
    fun scanAfterCloseEmitsOneDisconnectedFailureWithoutBleWork() =
        runTest {
            val r = rig()
            r.factory.scanFailure = VehicleError.TransportError.PermissionDenied // 팩토리에 닿으면 이 오류가 나온다
            r.ble.close()
            assertEquals(
                listOf<ScanEvent>(ScanEvent.Failed(VehicleError.TransportError.Disconnected)),
                r.ble.scan(r.fake.vin).toList(),
            )
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

    @Test
    fun lockUnlockAndTrunkGoThroughTheVcsecSession() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.lock()) // 핸드셰이크는 앱 몫
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.VCSEC), timeout = 1.seconds))
            assertEquals(VehicleResult.Success(Unit), vehicle.lock())
            assertEquals(VehicleResult.Success(Unit), vehicle.unlock())
            assertEquals(VehicleResult.Success(Unit), vehicle.openTrunk())
            assertEquals(listOf("1001", "1000", "22022801"), r.fake.authenticatedPayloads(vcsec).map { it.toHex() })
        }

    @Test
    fun commandsAfterDisconnectAreNotConnectedAndPairingFails() =
        runTest {
            val r = rig()
            val vehicle = r.connected()
            vehicle.disconnect()
            assertEquals(VehicleResult.Failure(VehicleError.NotConnected), vehicle.unlock())
            assertEquals(
                listOf<PairingEvent>(PairingEvent.Failed(VehicleError.NotConnected)),
                vehicle.addKeyRequest(r.key().publicKey).toList(),
            )
        }

    @Test
    fun addKeyRequestUsesThePlatformFormFactorAndConfiguredTimeout() =
        runTest {
            val r = rig(config = TeslaBleConfig(pairingTimeout = 10.seconds))
            val vehicle = r.connected(withKey = false)
            val start = currentTime
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.TimedOut), vehicle.addKeyRequest(r.key().publicKey).toList())
            assertEquals(10_000, currentTime - start)
            val inner =
                UnsignedMessage.ADAPTER.decode(
                    r.fake.presentKeys
                        .single()
                        .signedMessage!!
                        .protobufMessageAsBytes,
                )
            val expected =
                when (platformKeyFormFactor()) {
                    KeyFormFactor.ANDROID_DEVICE -> WireFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE
                    KeyFormFactor.IOS_DEVICE -> WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE
                    KeyFormFactor.CLOUD_KEY -> WireFormFactor.KEY_FORM_FACTOR_CLOUD_KEY
                    else -> error("unexpected platform default")
                }
            assertEquals(expected, inner.VCSEC_WhitelistOperation!!.metadataForKey!!.keyFormFactor)
        }

    @Test
    fun sessionInfoWithOwnKeyMakesTheDomainReady() =
        // 설계 구체화 3: 페어링 확인 폴링이 세션을 만들면 sessions에도 보인다
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertIs<VehicleResult.Success<SessionInfoSummary>>(vehicle.sessionInfo(r.key().publicKey, VehicleDomain.INFOTAINMENT))
            runCurrent()
            assertEquals(SessionStatus.Ready(fromCache = false), vehicle.sessions.value[VehicleDomain.INFOTAINMENT])
        }

    @Test
    fun noSessionResultReconcilesTheShownStatusEvenIfTheEventWasMissed() =
        // codex 검토: 표시는 사건만이 아니라 실제 세션 상태를 원천으로 한다
        runTest {
            val r = rig()
            val vehicle = r.connected()
            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.VCSEC])
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.lock())
            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.VCSEC]) // 바뀌지 않음(원래 None)
        }

    @Test
    fun staleCacheTurnsTheDomainBackToNone() =
        // Review Focus 3: 다른 차량 키로 저장된 캐시 → 첫 명령 NoSession, 표시 None, startSession으로 회복
        runTest {
            val cache = RecordingSessionCache()
            val other = rig(fake = FakeVehicle(vehicleKey = TestCrypto.goKnownVerifierKey(), timeSource = testTimeSource), cache = cache)
            val first = other.connected()
            first.startSession(timeout = 1.seconds)
            first.disconnect()
            val r = rig(cache = cache)
            val vehicle = r.connected()
            assertEquals(SessionStatus.Ready(fromCache = true), vehicle.sessions.value[VehicleDomain.VCSEC])
            assertEquals(VehicleResult.Failure(VehicleError.NoSession), vehicle.lock())
            runCurrent()
            assertEquals(SessionStatus.None, vehicle.sessions.value[VehicleDomain.VCSEC])
            assertIs<VehicleResult.Success<Unit>>(vehicle.startSession(setOf(VehicleDomain.VCSEC), timeout = 1.seconds))
            assertEquals(VehicleResult.Success(Unit), vehicle.lock())
        }
}
