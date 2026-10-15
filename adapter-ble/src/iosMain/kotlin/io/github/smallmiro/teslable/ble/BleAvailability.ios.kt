package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted

/** iOS 블루투스 권한 검사(SDD §8.4). `notDetermined`면 통과시켜 첫 스캔이 시스템 권한 창을 띄우게 한다. */
public fun appleBleAvailability(): BleAvailability =
    BleAvailability {
        when (CBManager.authorization) {
            CBManagerAuthorizationDenied, CBManagerAuthorizationRestricted -> VehicleError.TransportError.PermissionDenied
            else -> null
        }
    }
