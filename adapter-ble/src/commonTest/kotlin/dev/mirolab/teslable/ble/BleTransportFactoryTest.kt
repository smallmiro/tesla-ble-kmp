package dev.mirolab.teslable.ble

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.model.shouldRetry
import dev.mirolab.teslable.port.ConnectOptions
import dev.mirolab.teslable.port.ConnectScanObservation
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.ScanEvent
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.VehicleAdvertisement
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.transport.LocalName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportFactoryTest {
    private val vin = Vin("5YJ30123456789ABC")
    private val localName = LocalName.of(vin, TestCrypto.primitives)
    private val ad = VehicleAdvertisement(localName, "AA:BB", rssi = -50, isConnectable = true)

    // 차는 찾았지만 시간 안에 연결을 끝내지 못했고 실패한 시도도 없는 연결의 결과(#140)
    private val foundButNotConnected =
        VehicleResult.Failure(
            VehicleError.TransportError.ConnectFailed("vehicle found but the connection did not complete before the deadline"),
        )

    // 스캔 없이 기억한 target을 열다가 시간이 다 된 연결의 결과. 차가 아직 있는지 모르므로 "찾았다"고 말하지 않는다(#140)
    private val rememberedButNotConnected =
        VehicleResult.Failure(
            VehicleError.TransportError.ConnectFailed(
                "connection to the remembered target did not complete before the deadline (no scan ran)",
            ),
        )

    private fun TestScope.factory(
        connector: FakeGattConnector,
        availability: BleAvailability = BleAvailability.ALWAYS,
        logger: RecordingLogger = RecordingLogger(),
    ) = BleTransportFactory(connector, availability, backgroundScope, TestCrypto.primitives, timeSource = testTimeSource, logger = logger)

    @Test
    fun connectsToFirstScannedAdvertisement() =
        runTest {
            val connector = FakeGattConnector(ad)
            val result = factory(connector).connect(vin, target = null, options = ConnectOptions())
            val transport = assertIs<VehicleResult.Success<*>>(result).value
            assertIs<BleTransport>(transport)
            assertEquals(vin, transport.vin)
            assertEquals(1, connector.opens)
        }

    @Test
    fun retriesRetriableOpenFailuresEverySecondThenSucceeds() =
        // FR-004: 1초 간격(Go는 sleep 없음 — 설계 구체화 3)
        runTest {
            val connector = FakeGattConnector(ad)
            repeat(
                2,
            ) { connector.openResults.addLast(OpenResult.Failed(VehicleError.TransportError.ConnectFailed("discover"), retry = true)) }
            val logger = RecordingLogger()
            val pending = async { factory(connector, logger = logger).connect(vin, null, ConnectOptions()) }
            runCurrent()
            assertEquals(1, connector.opens)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(2, connector.opens)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertIs<VehicleResult.Success<*>>(pending.await())
            assertEquals(3, connector.opens)
            assertEquals(2, logger.count("Failed to connect to vehicle"))
        }

    @Test
    fun failsFastWithoutRetryOnPermissionOrBluetoothOff() =
        // Review Focus 3, ble.go:285-287 IsAdapterError → retry=false
        runTest {
            val noPermission = FakeGattConnector(ad)
            val denied = factory(noPermission, availability = { VehicleError.TransportError.PermissionDenied })
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.PermissionDenied), denied.connect(vin, null, ConnectOptions()))
            assertEquals(0, noPermission.scans)

            val off = FakeGattConnector(ad).apply { scanFailure = ConnectorScan.Failed(VehicleError.TransportError.BluetoothOff) }
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.BluetoothOff), factory(off).connect(vin, null, ConnectOptions()))
            assertEquals(0, off.opens)

            val fatalOpen = FakeGattConnector(ad)
            fatalOpen.openResults.addLast(OpenResult.Failed(VehicleError.TransportError.PermissionDenied, retry = false))
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.PermissionDenied),
                factory(fatalOpen).connect(vin, null, ConnectOptions()),
            )
            assertEquals(1, fatalOpen.opens)
        }

    @Test
    fun notConnectableAdvertisementFailsWithMaxConnectionsExceeded() =
        // ble.go:302-304
        runTest {
            val connector = FakeGattConnector(ad.copy(isConnectable = false))
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded),
                factory(connector).connect(vin, null, ConnectOptions()),
            )
            assertEquals(0, connector.opens)
        }

    @Test
    fun unknownConnectabilityIsAttempted() =
        // 설계 구체화 2
        runTest {
            val connector = FakeGattConnector(ad.copy(isConnectable = null))
            assertIs<VehicleResult.Success<*>>(factory(connector).connect(vin, null, ConnectOptions()))
            assertEquals(1, connector.opens)
        }

    @Test
    fun timesOutWithLastErrorWhenAttemptsKeepFailing() =
        // ble.go:270-275: ctx 만료 시 마지막 오류
        runTest {
            val connector = FakeGattConnector(ad)
            repeat(
                10,
            ) { connector.openResults.addLast(OpenResult.Failed(VehicleError.TransportError.ConnectFailed("gatt 133"), retry = true)) }
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 2500.milliseconds)) }
            advanceTimeBy(3.seconds)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.ConnectFailed("gatt 133")), pending.await())
            assertEquals(3, connector.opens) // t=0, 1s, 2s
        }

    @Test
    fun timesOutWithScanTimeoutWhenNothingIsFound() =
        runTest {
            val connector = FakeGattConnector(advertisement = null)
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 5.seconds)) }
            advanceTimeBy(6.seconds)
            assertEquals(VehicleResult.Failure(VehicleError.TransportError.ScanTimeout), pending.await())
        }

    @Test
    fun anOpenThatNeverFinishesIsConnectFailedBecauseTheCarWasFound() =
        // #140: 광고를 찾았으니 원인은 스캔이 아니다. Go는 둘을 나누지 않고 ctx.Err()를 돌려준다(ble.go:270-275). 재시도 가능한 것은 ScanTimeout과 같다
        runTest {
            val connector = FakeGattConnector(ad).apply { openGate = CompletableDeferred() }
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 2.seconds)) }
            advanceTimeBy(3.seconds)
            val failure = assertIs<VehicleResult.Failure>(pending.await())
            assertEquals(foundButNotConnected, failure)
            assertTrue(failure.error.shouldRetry())
            connector.openGate?.complete(Unit)
            runCurrent()
        }

    @Test
    fun aRememberedTargetWhoseOpenNeverFinishesDoesNotClaimTheCarWasFound() =
        // #140: 이 호출은 스캔하지 않았다(scan-hit 줄이 없다). 기억한 광고는 낡았을 수 있어 범위 밖의 차도 여기로 온다. 스캔을 돌리지 않았으니 ScanTimeout도 아니다
        runTest {
            val connector = FakeGattConnector(ad)
            val f = factory(connector)
            val found =
                assertIs<ScanEvent.Found>(
                    f
                        .scan(localName)
                        .take(1)
                        .toList()
                        .single(),
                ).advertisement
            connector.openGate = CompletableDeferred()
            val pending = async { f.connect(vin, found, ConnectOptions(timeout = 2.seconds)) }
            advanceTimeBy(3.seconds)
            assertEquals(rememberedButNotConnected, pending.await())
            assertEquals(1, connector.scans)
            connector.openGate?.complete(Unit)
            runCurrent()
        }

    @Test
    fun aHitBeforeTheDeadlineIsNotScanTimeoutEvenIfNoOpenStarted() =
        // #140: ScanTimeout은 광고가 없을 때만이다. 스캔을 멈추는 동안 시간이 다 되어 열기를 시작하지 못해도 차는 찾았다
        runTest {
            val connector =
                FakeGattConnector(ad).apply {
                    hitDelay = 1.seconds
                    scanTeardown = 5.seconds
                }
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 2.seconds)) }
            advanceTimeBy(3.seconds)
            assertEquals(foundButNotConnected, pending.await())
            assertEquals(0, connector.opens)
        }

    @Test
    fun targetForAnotherVinIsInvalidArgument() =
        // ble.go:298-300: 이름 불일치는 재시도 없음
        runTest {
            val other = ad.copy(localName = LocalName.of(Vin("5YJS0000000000000"), TestCrypto.primitives))
            val result = factory(FakeGattConnector(ad)).connect(vin, other, ConnectOptions())
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(result).error)
        }

    @Test
    fun scannedTargetIsReusedWithoutRescanning() =
        // ble.go:259-278: target이 있으면 다시 스캔하지 않는다
        runTest {
            val connector = FakeGattConnector(ad)
            val f = factory(connector)
            val found =
                assertIs<ScanEvent.Found>(
                    f
                        .scan(localName)
                        .take(1)
                        .toList()
                        .single(),
                ).advertisement
            assertIs<VehicleResult.Success<*>>(f.connect(vin, found, ConnectOptions()))
            assertEquals(1, connector.scans)
        }

    @Test
    fun scanReportsAvailabilityFailureAsAnEvent() =
        runTest {
            val f = factory(FakeGattConnector(ad), availability = { VehicleError.TransportError.PermissionDenied })
            assertEquals(listOf<ScanEvent>(ScanEvent.Failed(VehicleError.TransportError.PermissionDenied)), f.scan(localName).toList())
        }

    @Test
    fun scanStopsAfterAFailureEvent() =
        runTest {
            val connector = FakeGattConnector(ad).apply { scanFailure = ConnectorScan.Failed(VehicleError.TransportError.BluetoothOff) }
            assertEquals(
                listOf<ScanEvent>(ScanEvent.Failed(VehicleError.TransportError.BluetoothOff)),
                factory(connector).scan(localName).toList(),
            )
        }

    @Test
    fun callerCancellationPropagatesInsteadOfBecomingAValue() =
        // ADR-0010
        runTest {
            val connector = FakeGattConnector(advertisement = null)
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 60.seconds)) }
            runCurrent()
            pending.cancel()
            runCurrent()
            assertTrue(pending.isCancelled)
        }

    @Test
    fun linkOpenedAfterTimeoutIsDisconnectedOnce() =
        // 타임아웃이 먼저 터진 뒤 open이 Opened로 끝나도 슬롯이 새지 않는다. 결과가 ScanTimeout이던 것은 #140에서 고쳤다: open이 멈춘 것이지 광고가 없던 것이 아니다
        runTest {
            val connector = FakeGattConnector(ad).apply { openGate = CompletableDeferred() }
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 2.seconds)) }
            advanceTimeBy(3.seconds)
            assertEquals(foundButNotConnected, pending.await())
            connector.openGate?.complete(Unit)
            runCurrent()
            assertEquals(1, connector.links.size)
            assertEquals(1, connector.links.single().disconnects)
        }

    @Test
    fun budgetExpiryDoesNotWaitForAnAttemptStillCleaningUp() =
        // #130 H5: 열기는 팩토리 스코프에서 돈다 — 정리(끊김 대기 최대 5초)를 기다리지 않고 마지막 오류를 돌려준다(ble.go:270-275)
        runTest {
            val lastError = VehicleError.TransportError.ConnectFailed("NotConnectedException: Disconnect detected")
            val connector = FakeGattConnector(ad)
            connector.openResults.addLast(OpenResult.Failed(lastError, retry = true))
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 30.seconds)) }
            runCurrent()
            connector.openGate = CompletableDeferred() // 두 번째 시도는 취소를 무시하고 정리 중인 것처럼 멈춘다
            advanceTimeBy(30.seconds)
            runCurrent()
            assertEquals(2, connector.opens)
            assertTrue(pending.isCompleted)
            assertEquals(30_000, currentTime)
            assertEquals(VehicleResult.Failure(lastError), pending.await())
            connector.openGate?.complete(Unit)
            runCurrent()
            assertEquals(1, connector.links.single().disconnects) // 정리는 connect가 돌아간 뒤에 끝난다
        }

    @Test
    fun linkOpenedAfterCallerCancellationIsDisconnectedOnce() =
        runTest {
            val connector = FakeGattConnector(ad).apply { openGate = CompletableDeferred() }
            val pending = async { factory(connector).connect(vin, null, ConnectOptions(timeout = 60.seconds)) }
            runCurrent()
            pending.cancel()
            runCurrent()
            connector.openGate?.complete(Unit)
            runCurrent()
            assertTrue(pending.isCancelled)
            assertEquals(1, connector.links.single().disconnects)
        }

    @Test
    fun scanEndingWithoutAdvertisementIsRetriedAfterTheInterval() =
        runTest {
            val connector = FakeGattConnector(ad).apply { emptyScans = 1 }
            val pending = async { factory(connector).connect(vin, null, ConnectOptions()) }
            runCurrent()
            assertEquals(0, connector.opens)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertIs<VehicleResult.Success<*>>(pending.await())
            assertEquals(2, connector.scans)
        }

    private fun VehicleResult<Transport>.scanObservation(): ConnectScanObservation? =
        assertIs<VehicleResult.Success<Transport>>(this).value.scanObservation

    private fun RecordingLogger.observeLines(): List<RecordingLogger.Entry> = entries.filter { it.tag == "observe" }

    private fun retriableOpenFailure() = OpenResult.Failed(VehicleError.TransportError.ConnectFailed("discover"), retry = true)

    @Test
    fun scanHitTimeAndRssiReachTheTransport() =
        // #134: 라이브러리가 스캔한 연결의 발견 시각과 신호 세기
        runTest {
            val connector = FakeGattConnector(ad).apply { hitDelay = 400.milliseconds }
            val result = factory(connector).connect(vin, target = null, options = ConnectOptions())
            assertEquals(ConnectScanObservation(hitAfterMillis = 400, rssi = -50, openAttempt = 1), result.scanObservation())
        }

    @Test
    fun hitTimeIsTakenBeforeTheScanIsTornDown() =
        // 스캔을 멈추는 시간은 발견까지 걸린 시간이 아니다
        runTest {
            val connector =
                FakeGattConnector(ad).apply {
                    hitDelay = 100.milliseconds
                    scanTeardown = 300.milliseconds
                }
            val result = factory(connector).connect(vin, target = null, options = ConnectOptions())
            assertEquals(ConnectScanObservation(hitAfterMillis = 100, rssi = -50, openAttempt = 1), result.scanObservation())
        }

    @Test
    fun retryReportsTheHitTheLinkOpenedFrom() =
        // target = null이면 재시도마다 다시 스캔한다: t=100 스캔 1 → 열기 실패 → 1초 → t=1200 스캔 2
        runTest {
            val connector = FakeGattConnector(ad).apply { hitDelay = 100.milliseconds }
            connector.rssiByScan.addAll(listOf(-70, -55))
            connector.openResults.addLast(retriableOpenFailure())
            val result = factory(connector).connect(vin, target = null, options = ConnectOptions())
            assertEquals(2, connector.scans)
            assertEquals(ConnectScanObservation(hitAfterMillis = 1200, rssi = -55, openAttempt = 2), result.scanObservation())
        }

    @Test
    fun knownTargetRunsNoScanAndHasNoObservation() =
        // 스캔 없이 기억해 둔 광고로 연결했다: 이 호출이 본 것이 없으므로 앱이 가진 광고의 값을 다시 쓰지 않는다
        runTest {
            val connector = FakeGattConnector(ad)
            val f = factory(connector)
            val found =
                assertIs<ScanEvent.Found>(
                    f
                        .scan(localName)
                        .take(1)
                        .toList()
                        .single(),
                ).advertisement
            assertNull(f.connect(vin, found, ConnectOptions()).scanObservation())
            assertEquals(1, connector.scans)
        }

    @Test
    fun targetTheFactoryDoesNotRememberIsScannedAndObserved() =
        // findHit: 기억에 없는 target은 connect가 직접 스캔한다
        runTest {
            val connector = FakeGattConnector(ad).apply { hitDelay = 250.milliseconds }
            val result = factory(connector).connect(vin, target = ad, options = ConnectOptions())
            assertEquals(1, connector.scans)
            assertEquals(ConnectScanObservation(hitAfterMillis = 250, rssi = -50, openAttempt = 1), result.scanObservation())
        }

    @Test
    fun hitReusedOnRetryKeepsItsOriginalTimeAndOpenAttemptMovesOn() =
        // openAttempt > 1은 다시 스캔했다는 뜻이 아니다: 첫 시도의 스캔을 기억해 두었다가 그대로 쓴다
        runTest {
            val connector = FakeGattConnector(ad).apply { hitDelay = 100.milliseconds }
            connector.openResults.addLast(retriableOpenFailure())
            val result = factory(connector).connect(vin, target = ad, options = ConnectOptions())
            assertEquals(1, connector.scans)
            assertEquals(ConnectScanObservation(hitAfterMillis = 100, rssi = -50, openAttempt = 2), result.scanObservation())
        }

    @Test
    fun hitReplacedByAnotherScanIsNotReportedAsThisConnectsHit() =
        // 재시도 사이에 앱의 scan()이 같은 기기의 기억을 새 광고로 바꾸면 링크는 이 호출이 보지 않은 광고로 열린다
        runTest {
            val connector = FakeGattConnector(ad)
            connector.rssiByScan.addAll(listOf(-70, -55))
            connector.openResults.addLast(retriableOpenFailure())
            val f = factory(connector)
            val pending = async { f.connect(vin, target = ad, options = ConnectOptions()) }
            runCurrent()
            assertEquals(1, connector.opens)
            f.scan(localName).take(1).toList()
            advanceTimeBy(1.seconds)
            runCurrent()
            assertNull(pending.await().scanObservation())
            assertEquals(2, connector.opens)
        }

    @Test
    fun unavailableRssiSentinelsBecomeNull() =
        // CoreBluetooth는 127, btleplug는 Int.MIN_VALUE로 "값 없음"을 알린다. VehicleAdvertisement.rssi는 그대로 둔다
        runTest {
            for (sentinel in listOf(127, Int.MIN_VALUE)) {
                val logger = RecordingLogger()
                val connector = FakeGattConnector(ad.copy(rssi = sentinel))
                val result = factory(connector, logger = logger).connect(vin, target = null, options = ConnectOptions())
                assertEquals(ConnectScanObservation(hitAfterMillis = 0, rssi = null, openAttempt = 1), result.scanObservation())
                assertEquals(
                    "[connect:scan-hit] after=0ms rssi=unavailable connectable=true attempt=1",
                    logger.observeLines().single().message,
                )
            }
        }

    @Test
    fun everyScanHitOfAConnectIsOneObserveLineWithoutNameOrIdentifier() =
        // 실패하는 연결의 발견도 관찰 로그에는 남는다(공개 결과는 Success에만 있다). VIN에서 나온 광고 이름과 기기 식별자는 넣지 않는다
        runTest {
            val logger = RecordingLogger()
            val connector = FakeGattConnector(ad).apply { hitDelay = 100.milliseconds }
            connector.rssiByScan.addAll(listOf(-70, -55))
            connector.openResults.addLast(retriableOpenFailure())
            assertIs<VehicleResult.Success<*>>(factory(connector, logger = logger).connect(vin, null, ConnectOptions()))
            val lines = logger.observeLines()
            assertEquals(
                listOf(
                    "[connect:scan-hit] after=100ms rssi=-70 connectable=true attempt=1",
                    "[connect:scan-hit] after=1200ms rssi=-55 connectable=true attempt=2",
                ),
                lines.map { it.message },
            )
            assertTrue(lines.all { it.level == LogLevel.DEBUG })
            assertFalse(logger.observeLines().any { localName.value in it.message || ad.identifier in it.message })
        }

    @Test
    fun aHitIsLoggedEvenWhenTheOpenNeverFinishes() =
        // 실패한 연결에는 공개 결과가 없어도 앞선 관찰 줄이 발견 시각을 알려 준다. 결과는 #140부터 ScanTimeout이 아니다: 광고를 찾았다
        runTest {
            val logger = RecordingLogger()
            val connector = FakeGattConnector(ad).apply { openGate = CompletableDeferred() }
            val pending = async { factory(connector, logger = logger).connect(vin, null, ConnectOptions(timeout = 2.seconds)) }
            advanceTimeBy(3.seconds)
            assertEquals(foundButNotConnected, pending.await())
            assertEquals("[connect:scan-hit] after=0ms rssi=-50 connectable=true attempt=1", logger.observeLines().single().message)
            connector.openGate?.complete(Unit)
            runCurrent()
        }

    @Test
    fun aNotConnectableHitIsLoggedBeforeTheRejection() =
        runTest {
            val logger = RecordingLogger()
            val connector = FakeGattConnector(ad.copy(isConnectable = false))
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded),
                factory(connector, logger = logger).connect(vin, null, ConnectOptions()),
            )
            assertEquals("[connect:scan-hit] after=0ms rssi=-50 connectable=false attempt=1", logger.observeLines().single().message)
        }

    @Test
    fun unknownConnectabilityIsPrintedAsUnknown() =
        runTest {
            val logger = RecordingLogger()
            val connector = FakeGattConnector(ad.copy(isConnectable = null))
            assertIs<VehicleResult.Success<*>>(factory(connector, logger = logger).connect(vin, null, ConnectOptions()))
            assertEquals("[connect:scan-hit] after=0ms rssi=-50 connectable=unknown attempt=1", logger.observeLines().single().message)
        }

    @Test
    fun theAppsOwnScanAndAReusedTargetWriteNoScanHitLine() =
        // 이 줄은 connect가 직접 돌린 스캔의 발견만 적는다
        runTest {
            val logger = RecordingLogger()
            val connector = FakeGattConnector(ad)
            val f = factory(connector, logger = logger)
            val found =
                assertIs<ScanEvent.Found>(
                    f
                        .scan(localName)
                        .take(1)
                        .toList()
                        .single(),
                ).advertisement
            assertIs<VehicleResult.Success<*>>(f.connect(vin, found, ConnectOptions()))
            assertTrue(logger.observeLines().isEmpty())
        }
}
