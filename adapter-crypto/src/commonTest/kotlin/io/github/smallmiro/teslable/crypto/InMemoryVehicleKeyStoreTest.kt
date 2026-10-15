package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class InMemoryVehicleKeyStoreTest {
    @Test
    fun getOrCreateIsStablePerAliasAndSoftware() =
        runTest {
            val store = inMemoryVehicleKeyStore()
            val first = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate()).value
            val again = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate()).value
            val other = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate("other")).value
            assertEquals(first.publicKey, again.publicKey)
            assertNotEquals(first.publicKey, other.publicKey)
            assertEquals(KeyProtection.SOFTWARE, first.protection)
            assertEquals("teslable-default", first.alias)
        }

    @Test
    fun getReturnsNullWhenMissingAndDeleteIsIdempotent() =
        runTest {
            val store = inMemoryVehicleKeyStore()
            assertEquals(VehicleResult.Success(null), store.get())
            store.getOrCreate()
            assertEquals(VehicleResult.Success(Unit), store.delete())
            assertEquals(VehicleResult.Success(Unit), store.delete())
            assertEquals(VehicleResult.Success(null), store.get())
        }

    @Test
    fun requireHardwareIsUnavailable() =
        runTest {
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable),
                inMemoryVehicleKeyStore().getOrCreate(policy = KeyPolicy.REQUIRE_HARDWARE),
            )
        }

    @Test
    fun invalidAliasIsAProgrammingError() =
        runTest {
            assertFailsWith<IllegalArgumentException> { inMemoryVehicleKeyStore().getOrCreate("../x") }
        }
}
