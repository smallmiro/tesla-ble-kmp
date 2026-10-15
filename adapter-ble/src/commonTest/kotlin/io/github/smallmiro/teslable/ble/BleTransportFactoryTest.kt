package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportFactoryTest {
    private val vin = Vin("5YJ30123456789ABC")
    private val localName = LocalName.of(vin, TestCrypto.primitives)
    private val ad = VehicleAdvertisement(localName, "AA:BB", rssi = -50, isConnectable = true)

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
}
