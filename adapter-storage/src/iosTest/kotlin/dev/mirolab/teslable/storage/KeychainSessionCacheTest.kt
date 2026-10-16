package dev.mirolab.teslable.storage

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KeychainSessionCacheTest {
    private class FakeKeychain : KeychainItems {
        val items = HashMap<String, ByteArray>()
        var writeStatus = 0

        /** Keychain 호출이 실행된 스레드(최종 리뷰 M7). */
        val threads = mutableListOf<NSThread>()

        override fun read(account: String): KeychainRead {
            threads += NSThread.currentThread
            return items[account]?.let { KeychainRead.Found(it.copyOf()) } ?: KeychainRead.Missing
        }

        override fun write(
            account: String,
            data: ByteArray,
        ): Int {
            threads += NSThread.currentThread
            if (writeStatus == 0) items[account] = data.copyOf()
            return writeStatus
        }

        override fun delete(account: String): Int {
            threads += NSThread.currentThread
            items.remove(account)
            return 0
        }
    }

    private val vin = Vin("5YJ30123456789ABC")
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
    private var now = 5_000L

    @Test
    fun storesUnderSha1AccountAndLoadsWithAge() =
        runTest {
            val keychain = FakeKeychain()
            val cache = KeychainSessionCache(keychain, TestCrypto.primitives, RecordingLogger()) { now }
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(8, 2))))
            assertEquals(setOf(cacheFileName(vin, TestCrypto.primitives)), keychain.items.keys)
            now += 2_000
            assertEquals(2.seconds, cache.load(vin, keyId).single().age)
        }

    @Test
    fun otherKeyOrCorruptItemIsDeleted() =
        runTest {
            val keychain = FakeKeychain()
            val cache = KeychainSessionCache(keychain, TestCrypto.primitives, RecordingLogger()) { now }
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(8, 2))))
            assertEquals(emptyList(), cache.load(vin, KeyId(ByteArray(KeyId.SIZE))))
            assertTrue(keychain.items.isEmpty())
        }

    @Test
    fun keychainWriteFailureIsLoggedNotThrown() =
        runTest {
            val logger = RecordingLogger()
            val keychain = FakeKeychain().apply { writeStatus = -25308 }
            KeychainSessionCache(keychain, TestCrypto.primitives, logger) { now }.store(vin, keyId, emptyList())
            assertTrue(logger.contains("Failed to store session cache: keychain status -25308"))
        }

    @Test
    fun keychainWorkRunsOffTheCallersThread() =
        // 최종 리뷰 M7: Swift 메인 스레드에서 불려도 Keychain I/O가 그 스레드를 막지 않는다
        runTest {
            val keychain = FakeKeychain()
            val caller = NSThread.currentThread
            val cache = KeychainSessionCache(keychain, TestCrypto.primitives, RecordingLogger()) { now }
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(8, 2))))
            cache.load(vin, keyId)
            cache.clear(vin)
            assertEquals(3, keychain.threads.size)
            assertFalse(keychain.threads.any { it == caller })
        }
}
