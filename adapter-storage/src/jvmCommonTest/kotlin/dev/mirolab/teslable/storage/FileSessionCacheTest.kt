package dev.mirolab.teslable.storage

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.cache.SessionSnapshot
import dev.mirolab.teslable.model.KeyId
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.testing.RecordingLogger
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@Suppress("MagicNumber") // jvmCommonTest는 detekt 기본 테스트 제외 경로에 없다
class FileSessionCacheTest {
    private val dir: File = Files.createTempDirectory("teslable-sessions").toFile()
    private val vin = Vin("5YJ30123456789ABC")
    private val keyId = KeyId.of(TestCrypto.clientPublicKey, TestCrypto.primitives)
    private var now = 1_000_000L
    private val logger = RecordingLogger()
    private val cache = FileSessionCache(dir, TestCrypto.primitives, logger) { now }
    private val info = byteArrayOf(8, 1)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun storesUnderSha1OfVinAndLoadsWithAge() =
        runTest {
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            val files = dir.list()?.toList().orEmpty()
            assertEquals(listOf(cacheFileName(vin, TestCrypto.primitives) + ".bin"), files)
            assertFalse(files.single().contains(vin.value)) // SDD §7.1: VIN 원문 없음
            now += 30_000
            val loaded = cache.load(vin, keyId).single()
            assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, loaded.domain)
            assertEquals(30.seconds, loaded.age)
            assertContentEquals(info, loaded.sessionInfo)
        }

    @Test
    fun otherKeysCacheIsDeletedOnLoad() =
        // ADR-0007, 설계 구체화 9(FR-028): keyId가 다르면 버리고 지운다
        runTest {
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            val otherKey = KeyId(ByteArray(KeyId.SIZE) { 7 })
            assertEquals(emptyList(), cache.load(vin, otherKey))
            assertTrue(dir.list().isNullOrEmpty())
        }

    @Test
    fun corruptedFileIsIgnoredAndDeleted() =
        runTest {
            dir.mkdirs()
            File(dir, cacheFileName(vin, TestCrypto.primitives) + ".bin").writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(emptyList(), cache.load(vin, keyId))
            assertTrue(dir.list().isNullOrEmpty())
        }

    @Test
    fun ioFailureIsLoggedNotThrown() =
        // SessionCache 포트 계약: 쓰기 실패는 로그로만
        runTest {
            val blocker = Files.createTempFile("not-a-dir", "").toFile()
            val broken = FileSessionCache(blocker, TestCrypto.primitives, logger) { now }
            broken.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            assertEquals(emptyList(), broken.load(vin, keyId))
            assertTrue(logger.contains("Failed to store session cache"))
            blocker.delete()
        }

    @Test
    fun emptyStoreOverwritesAndClearRemoves() =
        runTest {
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, info)))
            cache.store(vin, keyId, emptyList())
            assertEquals(emptyList(), cache.load(vin, keyId))
            cache.clear(vin)
            assertTrue(dir.list().isNullOrEmpty())
        }
}
