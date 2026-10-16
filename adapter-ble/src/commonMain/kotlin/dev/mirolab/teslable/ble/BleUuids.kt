// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// vehicleServiceUUID, toVehicleUUID, fromVehicleUUID
@file:OptIn(ExperimentalUuidApi::class)

package dev.mirolab.teslable.ble

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Tesla 차량 GATT UUID(Go `ble.go:33-35`)와 CCCD. */
internal object BleUuids {
    val SERVICE: Uuid = Uuid.parse("00000211-b2d1-43f0-9b88-960cebf8b91e")
    val TO_VEHICLE: Uuid = Uuid.parse("00000212-b2d1-43f0-9b88-960cebf8b91e")
    val FROM_VEHICLE: Uuid = Uuid.parse("00000213-b2d1-43f0-9b88-960cebf8b91e")

    /** Client Characteristic Configuration descriptor(0x2902). Android에서만 직접 쓸 수 있다(iOS CoreBluetooth는 금지). */
    val CCCD: Uuid = Uuid.parse("00002902-0000-1000-8000-00805f9b34fb")
}
