package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppleVehicleKeyStoreTest {
    private class FakeKeychain : KeychainItems {
        val items = HashMap<String, ByteArray>()
        var failStatus: Int? = null
        var writeStatus: Int = 0
        var deleteStatus: Int = 0

        /** Security.framework 호출이 실행된 스레드(최종 리뷰 M7). */
        val threads = mutableListOf<NSThread>()

        override fun read(account: String): KeychainRead {
            threads += NSThread.currentThread
            return failStatus?.let { KeychainRead.Failed(it) } ?: items[account]?.let { KeychainRead.Found(it.copyOf()) }
                ?: KeychainRead.Missing
        }

        override fun write(
            account: String,
            data: ByteArray,
        ): Int {
            if (writeStatus != 0) return writeStatus
            items[account] = data.copyOf()
            return 0
        }

        override fun delete(account: String): Int {
            if (deleteStatus != 0) return deleteStatus
            items.remove(account)
            return 0
        }
    }

    private class FakeEnclave(
        var available: Boolean,
    ) : SecureEnclaveKeys {
        val keys = HashMap<String, VehicleKey>()
        var findStatus: Int? = null
        var deleteStatus: Int = 0
        var createCalls = 0

        val threads = mutableListOf<NSThread>()

        override fun find(alias: String): EnclaveFind {
            threads += NSThread.currentThread
            return findStatus?.let { EnclaveFind.Failed(it) } ?: keys[alias]?.let { EnclaveFind.Found(it) } ?: EnclaveFind.Missing
        }

        override fun create(alias: String): VehicleKey? {
            threads += NSThread.currentThread
            createCalls++
            if (!available) return null
            return Enclave(SoftwareVehicleKey(alias, generateSoftwareKeyPair())).also { keys[alias] = it }
        }

        override fun delete(alias: String): Int {
            if (deleteStatus != 0) return deleteStatus
            keys.remove(alias)
            return 0
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

    @Test
    fun enclaveFindFailureIsReportedAndCreatesNothing() =
        runTest {
            val keychain = FakeKeychain()
            val enclave = FakeEnclave(true).apply { findStatus = -25308 }
            val store = AppleVehicleKeyStore(keychain, enclave)
            val expected = VehicleError.KeyStoreError.PlatformFailure("keychain status -25308")
            assertEquals(expected, assertIs<VehicleResult.Failure>(store.getOrCreate()).error)
            assertEquals(expected, assertIs<VehicleResult.Failure>(store.get()).error)
            assertTrue(keychain.items.isEmpty())
            assertEquals(0, enclave.createCalls)
            assertTrue(enclave.keys.isEmpty())
        }

    @Test
    fun failedDeleteIsReportedAndNotFoundIsSuccess() =
        runTest {
            val enclave = FakeEnclave(false).apply { deleteStatus = -25308 }
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("keychain status -25308")),
                AppleVehicleKeyStore(FakeKeychain(), enclave).delete(),
            )
            val keychain = FakeKeychain().apply { deleteStatus = -25300 } // errSecItemNotFound
            assertEquals(VehicleResult.Success(Unit), AppleVehicleKeyStore(keychain, FakeEnclave(false)).delete())
            keychain.deleteStatus = -25308
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("keychain status -25308")),
                AppleVehicleKeyStore(keychain, FakeEnclave(false)).delete(),
            )
        }

    @Test
    fun keychainWriteFailureYieldsPlatformFailureAndNoKey() =
        runTest {
            val keychain = FakeKeychain().apply { writeStatus = -25308 }
            val result = AppleVehicleKeyStore(keychain, FakeEnclave(false)).getOrCreate()
            assertEquals(
                VehicleError.KeyStoreError.PlatformFailure("software key generation failed: IllegalStateException"),
                assertIs<VehicleResult.Failure>(result).error,
            )
            assertTrue(keychain.items.isEmpty())
        }

    private fun <T : Any> assertIsNotNull(value: T?): T = kotlin.test.assertNotNull(value)

    @Test
    fun securityFrameworkWorkRunsOffTheCallersThread() =
        // 최종 리뷰 M7: Swift에서는 흔히 메인 스레드가 호출한다 — Secure Enclave 생성(~100 ms)·Keychain I/O가 그 스레드를 막지 않는다
        runTest {
            val keychain = FakeKeychain()
            val enclave = FakeEnclave(available = true)
            val caller = NSThread.currentThread
            val store = AppleVehicleKeyStore(keychain, enclave)
            assertIs<VehicleResult.Success<VehicleKey>>(store.getOrCreate())
            assertIs<VehicleResult.Success<VehicleKey?>>(AppleVehicleKeyStore(keychain, FakeEnclave(false)).get())
            assertTrue(enclave.threads.isNotEmpty())
            assertTrue(keychain.threads.isNotEmpty())
            assertFalse((enclave.threads + keychain.threads).any { it == caller })
        }
}
