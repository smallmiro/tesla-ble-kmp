package dev.mirolab.teslable.ble

import com.juul.kable.NotConnectedException
import dev.mirolab.teslable.model.VehicleError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KableErrorsTest {
    // UnmetRequirementException(BluetoothDisabled, "Bluetooth was Unauthorized, ...")는 생성자가 internal이라 만들 수 없다.
    // 대신 분기 순서(권한 판정이 BluetoothDisabled보다 먼저)와 같은 문구 판정을 아래 테스트가 덮는다. 실기기는 Task 14.
    @Test
    fun iosAuthorizationFailuresArePermissionDenied() {
        // CentralManagerCoreBluetoothScanner.kt:89
        val scan = IllegalStateException("Application isn't authorized to use the Bluetooth low energy role")
        assertEquals(VehicleError.TransportError.PermissionDenied, KableErrors.map(scan))
        val connect = IllegalStateException("Bluetooth was Unauthorized, but PoweredOn was required")
        assertEquals(VehicleError.TransportError.PermissionDenied, KableErrors.map(connect))
    }

    @Test
    fun unsupportedAdaptersAreDetected() {
        assertTrue(KableErrors.isUnsupported(IllegalStateException("Bluetooth not supported")))
        assertTrue(KableErrors.isUnsupported(IllegalStateException("Device doesn't support the Bluetooth low energy central role")))
        assertFalse(KableErrors.isUnsupported(IllegalStateException("gatt 133")))
    }

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
