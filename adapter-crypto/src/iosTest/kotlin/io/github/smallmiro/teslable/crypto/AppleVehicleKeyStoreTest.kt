package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppleVehicleKeyStoreTest {
    private class FakeKeychain : KeychainItems {
        val items = HashMap<String, ByteArray>()
        var failStatus: Int? = null

        override fun read(account: String): KeychainRead =
            failStatus?.let { KeychainRead.Failed(it) } ?: items[account]?.let { KeychainRead.Found(it.copyOf()) } ?: KeychainRead.Missing

        override fun write(
            account: String,
            data: ByteArray,
        ): Int {
            items[account] = data.copyOf()
            return 0
        }

        override fun delete(account: String): Int {
            items.remove(account)
            return 0
        }
    }

    private class FakeEnclave(
        var available: Boolean,
    ) : SecureEnclaveKeys {
        val keys = HashMap<String, VehicleKey>()

        override fun find(alias: String): VehicleKey? = keys[alias]

        override fun create(alias: String): VehicleKey? =
            if (!available) null else SoftwareVehicleKey(alias, generateSoftwareKeyPair()).let { Enclave(it) }.also { keys[alias] = it }

        override fun delete(alias: String) {
            keys.remove(alias)
        }

        private class Enclave(
            private val d: VehicleKey,
        ) : VehicleKey by d {
            override val protection: KeyProtection = KeyProtection.SECURE_ENCLAVE
        }
    }

    @Test
    fun simulatorWithoutEnclaveStoresSoftwareKeyInKeychainAndReloadsIt() =
        runTest {
            val keychain = FakeKeychain()
            val store = AppleVehicleKeyStore(keychain, FakeEnclave(available = false))
            val created = assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate()).value
            assertEquals(KeyProtection.SOFTWARE, created.protection)
            assertEquals(97, keychain.items.getValue("teslable-default").size)
            val reloaded = assertIs<VehicleResult.Success<VehicleKey?>>(AppleVehicleKeyStore(keychain, FakeEnclave(false)).get()).value
            assertEquals(created.publicKey, reloaded?.publicKey)
            val peer = generateSoftwareKeyPair().publicKey
            assertContentEquals(created.sharedX(peer), assertIsNotNull(reloaded).sharedX(peer))
        }

    @Test
    fun enclaveKeyIsPreferredAndNotWrittenToKeychain() =
        runTest {
            val keychain = FakeKeychain()
            val key = assertIs<VehicleResult.Success<VehicleKey>>(AppleVehicleKeyStore(keychain, FakeEnclave(true)).getOrCreate()).value
            assertEquals(KeyProtection.SECURE_ENCLAVE, key.protection)
            assertTrue(keychain.items.isEmpty())
        }

    @Test
    fun requireHardwareFailsOnSimulator() =
        runTest {
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable),
                AppleVehicleKeyStore(FakeKeychain(), FakeEnclave(false)).getOrCreate(policy = KeyPolicy.REQUIRE_HARDWARE),
            )
        }

    @Test
    fun corruptedKeychainItemIsReportedNotReplaced() =
        runTest {
            val keychain = FakeKeychain().apply { items["teslable-default"] = ByteArray(10) }
            val store = AppleVehicleKeyStore(keychain, FakeEnclave(false))
            val failure = assertIs<VehicleResult.Failure>(store.getOrCreate())
            assertEquals(VehicleError.KeyStoreError.PlatformFailure("stored key is corrupted"), failure.error)
            assertEquals(10, keychain.items.getValue("teslable-default").size)
        }

    @Test
    fun keychainErrorIsPlatformFailureWithStatus() =
        runTest {
            val keychain = FakeKeychain().apply { failStatus = -25308 } // errSecInteractionNotAllowed(잠긴 기기)
            val failure = assertIs<VehicleResult.Failure>(AppleVehicleKeyStore(keychain, FakeEnclave(false)).get())
            assertEquals(VehicleError.KeyStoreError.PlatformFailure("keychain status -25308"), failure.error)
        }

    @Test
    fun deleteRemovesBothEnclaveAndKeychainCopies() =
        runTest {
            val keychain = FakeKeychain().apply { items["teslable-default"] = ByteArray(97) }
            val enclave = FakeEnclave(true).apply { create("teslable-default") }
            assertEquals(VehicleResult.Success(Unit), AppleVehicleKeyStore(keychain, enclave).delete())
            assertTrue(keychain.items.isEmpty())
            assertTrue(enclave.keys.isEmpty())
        }

    private fun <T : Any> assertIsNotNull(value: T?): T = kotlin.test.assertNotNull(value)
}
