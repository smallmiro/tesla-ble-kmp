package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class FakeTransportFactoryTest {
    private val vehicle = FakeVehicle()
    private val localName = LocalName.of(vehicle.vin, TestCrypto.primitives)

    @Test
    fun scanFindsTheVehicleByItsLocalNameOnly() =
        runTest {
            val factory = FakeTransportFactory(vehicle)
            val found = factory.scan(localName).toList().single()
            val advertisement = assertIs<ScanEvent.Found>(found).advertisement
            assertEquals(localName, advertisement.localName)
            assertEquals(true, advertisement.isConnectable)
            val other = LocalName.of(Vin("5YJ30123456789ABC"), TestCrypto.primitives)
            assertEquals(emptyList(), factory.scan(other).toList())
        }

    @Test
    fun scanReportsConfiguredFailureInsteadOfThrowing() =
        runTest {
            val factory = FakeTransportFactory(vehicle).apply { scanFailure = VehicleError.TransportError.PermissionDenied }
            val expected = listOf<ScanEvent>(ScanEvent.Failed(VehicleError.TransportError.PermissionDenied))
            assertEquals(expected, factory.scan(localName).toList())
        }

    @Test
    fun connectWrapsFakeVehicleAndReplaysQueuedFailures() =
        runTest {
            val factory = FakeTransportFactory(vehicle)
            factory.enqueueConnectFailure(VehicleError.TransportError.ConnectFailed("gatt 133"))
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.ConnectFailed("gatt 133")),
                factory.connect(vehicle.vin, target = null, options = ConnectOptions()),
            )
            val connected = assertIs<VehicleResult.Success<*>>(factory.connect(vehicle.vin, null, ConnectOptions()))
            assertSame(factory.lastTransport, connected.value)
            assertEquals(2, factory.connectAttempts)
        }

    @Test
    fun notConnectableVehicleRefusesLikeGo() =
        runTest {
            vehicle.setConnectable(false)
            val factory = FakeTransportFactory(vehicle)
            val advertisement = assertIs<ScanEvent.Found>(factory.scan(localName).toList().single()).advertisement
            assertEquals(false, advertisement.isConnectable)
            assertEquals(
                VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded),
                factory.connect(vehicle.vin, advertisement, ConnectOptions()),
            )
        }
}
