package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.port.VehicleKeyStore
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
    fun existingAliasKeepsItsAccessibility() =
        // FR-037 계약: 이미 있는 별칭은 다른 접근성을 요청해도 같은 키를 돌려주고 실제 값을 알린다.
        // 메모리 키는 프로세스가 살아 있는 한 잠금과 무관하게 쓸 수 있어 요청을 무시하고 AFTER_FIRST_UNLOCK을 보고한다(ADR-0014).
        runTest {
            val store = inMemoryVehicleKeyStore()
            val first = store.created("a", KeyAccessibility.WHEN_UNLOCKED)
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, first.accessibility)
            val again = store.created("a", KeyAccessibility.AFTER_FIRST_UNLOCK)
            assertEquals(first.publicKey, again.publicKey)
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, again.accessibility)
            val defaulted = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate()).value
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, defaulted.accessibility)
            val loaded = assertIs<VehicleResult.Success<VehicleKey?>>(store.get("a")).value
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, loaded?.accessibility)
        }

    private suspend fun VehicleKeyStore.created(
        alias: String,
        accessibility: KeyAccessibility,
    ): VehicleKey = assertIs<VehicleResult.Success<VehicleKey>>(getOrCreate(alias, KeyPolicy.SOFTWARE_ONLY, accessibility)).value

    @Test
    fun invalidAliasIsAProgrammingError() =
        runTest {
            assertFailsWith<IllegalArgumentException> { inMemoryVehicleKeyStore().getOrCreate("../x") }
        }
}
