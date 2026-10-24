package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** -25308 `errSecInteractionNotAllowed`(잠긴 기기), -25291 `errSecNotAvailable`(그 밖의 플랫폼 실패 예), -25300 `errSecItemNotFound`. */
private const val LOCKED = -25308
private const val NOT_AVAILABLE = -25291

class AppleVehicleKeyStoreTest {
    private class FakeKeychain : KeychainItems {
        val items = HashMap<String, ByteArray>()

        /** 항목의 보호 등급. 없으면 Keychain이 알려 주지 않은 것으로 읽는다. */
        val accessibilities = HashMap<String, KeyAccessibility>()
        var failStatus: Int? = null
        var writeStatus: Int = 0
        var deleteStatus: Int = 0

        /** Security.framework 호출이 실행된 스레드(최종 리뷰 M7). */
        val threads = mutableListOf<NSThread>()

        override fun read(account: String): KeychainRead {
            threads += NSThread.currentThread
            return failStatus?.let { KeychainRead.Failed(it) }
                ?: items[account]?.let { KeychainRead.Found(it.copyOf(), accessibilities[account]) }
                ?: KeychainRead.Missing
        }

        override fun write(
            account: String,
            data: ByteArray,
            accessibility: KeyAccessibility,
        ): Int {
            if (writeStatus != 0) return writeStatus
            items[account] = data.copyOf()
            accessibilities[account] = accessibility
            return 0
        }

        override fun delete(account: String): Int {
            if (deleteStatus != 0) return deleteStatus
            items.remove(account)
            accessibilities.remove(account)
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

        /** true면 [create]가 잠긴 기기처럼 실패한다(Secure Enclave CFError -25308). */
        var locked = false

        /** [create]가 받은 접근성(순서대로). */
        val requested = mutableListOf<KeyAccessibility>()

        val threads = mutableListOf<NSThread>()

        override fun find(alias: String): EnclaveFind {
            threads += NSThread.currentThread
            return findStatus?.let { EnclaveFind.Failed(it) }
                ?: keys[alias]?.let { EnclaveFind.Found(it, it.accessibility) }
                ?: EnclaveFind.Missing
        }

        override fun create(
            alias: String,
            accessibility: KeyAccessibility,
        ): VehicleKey? {
            threads += NSThread.currentThread
            createCalls++
            requested += accessibility
            if (locked) throw KeyStoreLockedException()
            if (!available) return null
            return Enclave(SoftwareVehicleKey(alias, generateSoftwareKeyPair(), accessibility)).also { keys[alias] = it }
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

    private class RecordingLogger : TeslaLogger {
        val lines = mutableListOf<String>()

        override fun log(
            level: LogLevel,
            tag: String,
            message: () -> String,
        ) {
            lines += "$level $tag ${message()}"
        }
    }

    private fun <T> success(result: VehicleResult<T>): T = assertIs<VehicleResult.Success<T>>(result).value

    @Test
    fun simulatorWithoutEnclaveStoresSoftwareKeyInKeychainAndReloadsIt() =
        runTest {
            val keychain = FakeKeychain()
            val store = AppleVehicleKeyStore(keychain, FakeEnclave(available = false))
            val created = success(store.getOrCreate())
            assertEquals(KeyProtection.SOFTWARE, created.protection)
            assertEquals(97, keychain.items.getValue("teslable-default").size)
            val reloaded = success(AppleVehicleKeyStore(keychain, FakeEnclave(false)).get())
            assertEquals(created.publicKey, reloaded?.publicKey)
            val peer = generateSoftwareKeyPair().publicKey
            assertContentEquals(created.sharedX(peer), assertIsNotNull(reloaded).sharedX(peer))
        }

    @Test
    fun enclaveKeyIsPreferredAndNotWrittenToKeychain() =
        runTest {
            val keychain = FakeKeychain()
            val key = success(AppleVehicleKeyStore(keychain, FakeEnclave(true)).getOrCreate())
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
            val keychain = FakeKeychain().apply { failStatus = NOT_AVAILABLE }
            val failure = assertIs<VehicleResult.Failure>(AppleVehicleKeyStore(keychain, FakeEnclave(false)).get())
            assertEquals(VehicleError.KeyStoreError.PlatformFailure("keychain status -25291"), failure.error)
        }

    @Test
    fun deleteRemovesBothEnclaveAndKeychainCopies() =
        runTest {
            val keychain = FakeKeychain().apply { items["teslable-default"] = ByteArray(97) }
            val enclave = FakeEnclave(true).apply { create("teslable-default", KeyAccessibility.WHEN_UNLOCKED) }
            assertEquals(VehicleResult.Success(Unit), AppleVehicleKeyStore(keychain, enclave).delete())
            assertTrue(keychain.items.isEmpty())
            assertTrue(enclave.keys.isEmpty())
        }

    @Test
    fun enclaveFindFailureIsReportedAndCreatesNothing() =
        runTest {
            val keychain = FakeKeychain()
            val enclave = FakeEnclave(true).apply { findStatus = NOT_AVAILABLE }
            val store = AppleVehicleKeyStore(keychain, enclave)
            val expected = VehicleError.KeyStoreError.PlatformFailure("keychain status -25291")
            assertEquals(expected, assertIs<VehicleResult.Failure>(store.getOrCreate()).error)
            assertEquals(expected, assertIs<VehicleResult.Failure>(store.get()).error)
            assertTrue(keychain.items.isEmpty())
            assertEquals(0, enclave.createCalls)
            assertTrue(enclave.keys.isEmpty())
        }

    @Test
    fun failedDeleteIsReportedAndNotFoundIsSuccess() =
        runTest {
            val enclave = FakeEnclave(false).apply { deleteStatus = NOT_AVAILABLE }
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("keychain status -25291")),
                AppleVehicleKeyStore(FakeKeychain(), enclave).delete(),
            )
            val keychain = FakeKeychain().apply { deleteStatus = -25300 } // errSecItemNotFound
            assertEquals(VehicleResult.Success(Unit), AppleVehicleKeyStore(keychain, FakeEnclave(false)).delete())
            keychain.deleteStatus = NOT_AVAILABLE
            assertEquals(
                VehicleResult.Failure(VehicleError.KeyStoreError.PlatformFailure("keychain status -25291")),
                AppleVehicleKeyStore(keychain, FakeEnclave(false)).delete(),
            )
        }

    @Test
    fun keychainWriteFailureYieldsPlatformFailureAndNoKey() =
        runTest {
            val keychain = FakeKeychain().apply { writeStatus = NOT_AVAILABLE }
            val result = AppleVehicleKeyStore(keychain, FakeEnclave(false)).getOrCreate()
            assertEquals(
                VehicleError.KeyStoreError.PlatformFailure("software key generation failed: IllegalStateException"),
                assertIs<VehicleResult.Failure>(result).error,
            )
            assertTrue(keychain.items.isEmpty())
        }

    @Test
    fun lockedKeychainMapsToLocked() =
        // FR-037, 계획 Review Focus 4: 잠긴 iPhone의 errSecInteractionNotAllowed가 일반 PlatformFailure에 섞이면 앱이 키를 지우고
        // 다시 만들게 유도할 수 있다. Locked로 구별하고, 잠긴 항목 위에 새 키를 만들지 않는다.
        runTest {
            assertEquals(VehicleError.KeyStoreError.Locked, keychainFailure(LOCKED))
            assertEquals(VehicleError.KeyStoreError.PlatformFailure("keychain status -25291"), keychainFailure(NOT_AVAILABLE))
            val locked = VehicleResult.Failure(VehicleError.KeyStoreError.Locked)

            val keychain = FakeKeychain().apply { failStatus = LOCKED }
            val enclave = FakeEnclave(available = true)
            val store = AppleVehicleKeyStore(keychain, enclave)
            assertEquals(locked, store.get())
            assertEquals(locked, store.getOrCreate("monitor", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK))
            assertEquals(0, enclave.createCalls)

            val lockedEnclave = FakeEnclave(available = true).apply { findStatus = LOCKED }
            val enclaveStore = AppleVehicleKeyStore(FakeKeychain(), lockedEnclave)
            assertEquals(locked, enclaveStore.getOrCreate())
            assertEquals(locked, enclaveStore.get())
            assertEquals(0, lockedEnclave.createCalls)

            assertEquals(locked, AppleVehicleKeyStore(FakeKeychain().apply { deleteStatus = LOCKED }, FakeEnclave(false)).delete())
            assertEquals(locked, AppleVehicleKeyStore(FakeKeychain(), FakeEnclave(false).apply { deleteStatus = LOCKED }).delete())
        }

    @Test
    fun creatingAKeyWhileLockedIsLockedAndLeavesNoSoftwareKey() =
        // 일시적인 잠금 때문에 PREFER_HARDWARE가 영구 SOFTWARE 키를 남기면 안 된다(KeyCreationChain이 다음 수준으로 넘어가지 않음)
        runTest {
            val locked = VehicleResult.Failure(VehicleError.KeyStoreError.Locked)
            val keychain = FakeKeychain()
            val enclave = FakeEnclave(available = true).apply { this.locked = true }
            assertEquals(locked, AppleVehicleKeyStore(keychain, enclave).getOrCreate())
            assertTrue(keychain.items.isEmpty())

            val lockedWrite = FakeKeychain().apply { writeStatus = LOCKED }
            assertEquals(locked, AppleVehicleKeyStore(lockedWrite, FakeEnclave(false)).getOrCreate())
            assertTrue(lockedWrite.items.isEmpty())
        }

    @Test
    fun existingAliasKeepsItsAccessibility() =
        // 접근성은 만들 때 정해진다. 다른 값을 요청해도 기존 키를 그대로 돌려주고 실제 값을 알린다(마이그레이션 없음, ADR-0014).
        runTest {
            val enclave = FakeEnclave(available = true)
            val store = AppleVehicleKeyStore(FakeKeychain(), enclave)
            val created = success(store.getOrCreate("monitor", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK))
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, created.accessibility)
            val again = success(store.getOrCreate("monitor", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.WHEN_UNLOCKED))
            assertEquals(created.publicKey, again.publicKey)
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, again.accessibility)
            assertEquals(1, enclave.createCalls)
            val old = success(store.getOrCreate())
            assertEquals(KeyAccessibility.WHEN_UNLOCKED, old.accessibility)
            val oldAgain = success(store.getOrCreate(old.alias, KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK))
            assertEquals(KeyAccessibility.WHEN_UNLOCKED, oldAgain.accessibility)

            // 시뮬레이터(소프트웨어 키): 항목의 보호 등급을 다시 읽는다
            val keychain = FakeKeychain()
            val simulator = AppleVehicleKeyStore(keychain, FakeEnclave(false))
            val software = success(simulator.getOrCreate("monitor", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK))
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, keychain.accessibilities.getValue("monitor"))
            val reloaded = AppleVehicleKeyStore(keychain, FakeEnclave(false))
            val reopened = success(reloaded.getOrCreate("monitor", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.WHEN_UNLOCKED))
            assertEquals(software.publicKey, reopened.publicKey)
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, reopened.accessibility)
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, success(reloaded.get("monitor"))?.accessibility)
        }

    @Test
    fun twoArgOverloadIsWhenUnlocked() =
        runTest {
            val enclave = FakeEnclave(available = true)
            assertEquals(KeyAccessibility.WHEN_UNLOCKED, success(AppleVehicleKeyStore(FakeKeychain(), enclave).getOrCreate()).accessibility)
            assertEquals(listOf(KeyAccessibility.WHEN_UNLOCKED), enclave.requested)
            val keychain = FakeKeychain()
            success(AppleVehicleKeyStore(keychain, FakeEnclave(false)).getOrCreate("sim", KeyPolicy.SOFTWARE_ONLY))
            assertEquals(KeyAccessibility.WHEN_UNLOCKED, keychain.accessibilities.getValue("sim"))
        }

    @Test
    fun requestedAccessibilityReachesTheSecureEnclaveAndTheKeychain() =
        runTest {
            val enclave = FakeEnclave(available = true)
            success(
                AppleVehicleKeyStore(
                    FakeKeychain(),
                    enclave,
                ).getOrCreate("m", KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK),
            )
            assertEquals(listOf(KeyAccessibility.AFTER_FIRST_UNLOCK), enclave.requested)
            val keychain = FakeKeychain()
            success(
                AppleVehicleKeyStore(
                    keychain,
                    FakeEnclave(false),
                ).getOrCreate("m", KeyPolicy.SOFTWARE_ONLY, KeyAccessibility.AFTER_FIRST_UNLOCK),
            )
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, keychain.accessibilities.getValue("m"))
        }

    @Test
    fun unreportedProtectionClassReadsAsWhenUnlockedWithAWarning() =
        // Keychain이 보호 등급을 알려 주지 않으면(예상하지 않음, 실기기 확인 대상) 잠긴 사용을 과장하지 않고 WARN으로 드러낸다
        runTest {
            val keychain = FakeKeychain()
            success(
                AppleVehicleKeyStore(
                    keychain,
                    FakeEnclave(false),
                ).getOrCreate("x", KeyPolicy.SOFTWARE_ONLY, KeyAccessibility.AFTER_FIRST_UNLOCK),
            )
            keychain.accessibilities.remove("x")
            val logger = RecordingLogger()
            val key = success(AppleVehicleKeyStore(keychain, FakeEnclave(false), logger).get("x"))
            assertEquals(KeyAccessibility.WHEN_UNLOCKED, key?.accessibility)
            assertEquals(
                listOf("WARN keystore Keychain did not report the protection class of key x; reporting WHEN_UNLOCKED"),
                logger.lines,
            )
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
