package io.github.smallmiro.teslable.ble

import com.juul.kable.NotConnectedException
import io.github.smallmiro.teslable.model.VehicleError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KableErrorsTest {
    // UnmetRequirementException(BluetoothDisabled)는 Kable 생성자가 internal이라 만들 수 없다. 그 분기는 실기기에서 확인한다(Task 14).
    @Test
    fun mapsKableExceptionsToTransportErrors() {
        // Kable Android ScanPermissions: error("Missing required android.permission.BLUETOOTH_SCAN for scanning")
        assertEquals(
            VehicleError.TransportError.PermissionDenied,
            KableErrors.map(IllegalStateException("Missing required android.permission.BLUETOOTH_SCAN for scanning")),
        )
        // Kable BluetoothDevice.connectGatt: SecurityException을 IllegalStateException(e.message, e)로 다시 던진다
        assertEquals(
            VehicleError.TransportError.PermissionDenied,
            KableErrors.map(IllegalStateException("x", IllegalStateException("Need android.permission.BLUETOOTH_CONNECT permission"))),
        )
        assertIs<VehicleError.TransportError.ConnectFailed>(KableErrors.map(NotConnectedException("link lost")))
        assertEquals(
            VehicleError.TransportError.ConnectFailed("IllegalArgumentException: boom"),
            KableErrors.map(IllegalArgumentException("boom")),
        )
    }

    @Test
    fun permissionAndBluetoothOffAreNotRetriedButConnectFailuresAre() {
        // ble.go:285-287 IsAdapterError → retry=false; :308-347 dial/discover/subscribe → retry=true
        assertFalse(KableErrors.isRetriable(VehicleError.TransportError.PermissionDenied))
        assertFalse(KableErrors.isRetriable(VehicleError.TransportError.BluetoothOff))
        assertTrue(KableErrors.isRetriable(VehicleError.TransportError.ConnectFailed("gatt 133")))
    }
}
