package dev.mirolab.teslable.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class BleUuidsTest {
    @Test
    fun matchesGoGattUuids() {
        // ble.go:33-35 vehicleServiceUUID, toVehicleUUID, fromVehicleUUID
        assertEquals("00000211-b2d1-43f0-9b88-960cebf8b91e", BleUuids.SERVICE.toString())
        assertEquals("00000212-b2d1-43f0-9b88-960cebf8b91e", BleUuids.TO_VEHICLE.toString())
        assertEquals("00000213-b2d1-43f0-9b88-960cebf8b91e", BleUuids.FROM_VEHICLE.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", BleUuids.CCCD.toString())
    }
}
