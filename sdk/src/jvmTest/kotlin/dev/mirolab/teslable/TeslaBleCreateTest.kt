package dev.mirolab.teslable

import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TeslaBleCreateTest {
    @Test
    fun createWiresJvmDefaultsAndCloseIsSafe() =
        runTest {
            // 스캔은 하지 않는다(JVM에는 실제 BLE가 없다).
            val ble = TeslaBle.create(PlatformContext(), TeslaBleConfig())
            assertIs<VehicleResult.Success<*>>(ble.keys.getOrCreate())
            ble.close()
            ble.close()
            assertEquals(
                VehicleResult.Failure(dev.mirolab.teslable.model.VehicleError.NotConnected),
                ble.connect(Vin("5YJS0000000000000"), null),
            )
        }
}
