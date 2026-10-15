package io.github.smallmiro.teslable.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import io.github.smallmiro.teslable.model.VehicleError

/** Android 12+(API 31) BLE 런타임 권한 검사(SDD §8.4). 권한 요청은 앱이 한다. */
public fun androidBleAvailability(context: Context): BleAvailability {
    val app = context.applicationContext
    return BleAvailability {
        val granted =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (granted) null else VehicleError.TransportError.PermissionDenied
    }
}
