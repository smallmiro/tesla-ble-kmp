package io.github.smallmiro.teslable.storage

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class InMemorySessionCacheTest {
    private val vin = Vin(ProtocolVectors.VIN)
    private val keyId = KeyId(ByteArray(20) { 1 })
    private val otherKeyId = KeyId(ByteArray(20) { 2 })
    private var now = 10_000L
    private val cache = InMemorySessionCache { now }
    private val snapshot = SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, byteArrayOf(0x08, 0x06))

    @Test
    fun storeStampsWallClockAndLoadComputesAge() =
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            now = 40_000L
            val loaded = cache.load(vin, keyId).single()
            assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, loaded.domain)
            assertContentEquals(byteArrayOf(0x08, 0x06), loaded.sessionInfo)
            assertEquals(30.seconds, loaded.age)
        }

    @Test
    fun entryFromTheFutureYieldsNegativeAgeWithoutThrowing() =
        // 인계 항목 5: 벽시계가 뒤로 감 → age < 0. 클램프는 Signer.importSessionInfo 몫(설계 구체화 7)
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            now = 5_000L
            assertEquals((-5).seconds, cache.load(vin, keyId).single().age)
        }

    @Test
    fun loadWithAnotherKeyDiscardsTheCache() =
        // ADR-0007: keyId가 현재 키와 다르면 캐시를 무시하고 삭제한다
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            assertTrue(cache.load(vin, otherKeyId).isEmpty())
            assertTrue(cache.load(vin, keyId).isEmpty()) // 삭제됨
        }

    @Test
    fun storeOverwritesAndClearRemoves() =
        runTest {
            cache.store(vin, keyId, listOf(snapshot))
            cache.store(vin, keyId, listOf(SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(1))))
            assertEquals(listOf(Domain.DOMAIN_INFOTAINMENT), cache.load(vin, keyId).map { it.domain })
            cache.clear(vin)
            assertTrue(cache.load(vin, keyId).isEmpty())
            assertTrue(cache.load(Vin(ProtocolVectors.LOCAL_NAME_VIN), keyId).isEmpty())
        }
}
