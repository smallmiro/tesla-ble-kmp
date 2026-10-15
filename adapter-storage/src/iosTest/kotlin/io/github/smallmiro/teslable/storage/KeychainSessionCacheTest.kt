package io.github.smallmiro.teslable.storage

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KeychainSessionCacheTest {
    private class FakeKeychain : KeychainItems {
        val items = HashMap<String, ByteArray>()
        var writeStatus = 0

        override fun read(account: String): KeychainRead = items[account]?.let { KeychainRead.Found(it.copyOf()) } ?: KeychainRead.Missing

        override fun write(
            account: String,
            data: ByteArray,
        ): Int {
            if (writeStatus == 0) items[account] = data.copyOf()
            return writeStatus
        }

        override fun delete(account: String): Int {
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
}
