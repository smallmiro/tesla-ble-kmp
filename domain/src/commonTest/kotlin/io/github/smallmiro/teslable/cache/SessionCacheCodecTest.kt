package io.github.smallmiro.teslable.cache

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(InternalTeslableApi::class)
class SessionCacheCodecTest {
    private val keyId = KeyId(ByteArray(20) { (it + 1).toByte() })
    private val createdAt = 1_700_000_000_000L // 0x0000018bcfe56800 (printf '%016x')
    private val entry = SessionCacheCodec.Entry(Domain.DOMAIN_VEHICLE_SECURITY, createdAt, "0806".hexToBytes()) // SessionInfo{counter=6}

    // SDD §7.1: magic "TBSC" | version 1 | keyId(20) | count | domain u8 | createdAt i64 BE | len u16 BE | info
    private val expectedHex =
        "54425343" + "01" + "0102030405060708090a0b0c0d0e0f1011121314" + "01" +
            "02" + "0000018bcfe56800" + "0002" + "0806"

    @Test
    fun encodesVersion1LayoutFromSdd() {
        assertEquals(expectedHex, SessionCacheCodec.encode(keyId, listOf(entry)).toHex())
    }

    @Test
    fun decodesTheSameBytesBack() {
        val decoded = assertNotNull(SessionCacheCodec.decode(expectedHex.hexToBytes(), keyId))
        assertEquals(1, decoded.size)
        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, decoded[0].domain)
        assertEquals(createdAt, decoded[0].createdAtEpochMillis)
        assertContentEquals("0806".hexToBytes(), decoded[0].sessionInfo)
    }

    @Test
    fun roundTripsTwoDomainsInOrder() {
        // cache_test.go TestImportExport의 자체 형식판
        val second = SessionCacheCodec.Entry(Domain.DOMAIN_INFOTAINMENT, createdAt + 1, ByteArray(300) { it.toByte() })
        val decoded = assertNotNull(SessionCacheCodec.decode(SessionCacheCodec.encode(keyId, listOf(entry, second)), keyId))
        assertEquals(listOf(Domain.DOMAIN_VEHICLE_SECURITY, Domain.DOMAIN_INFOTAINMENT), decoded.map { it.domain })
        assertEquals(createdAt + 1, decoded[1].createdAtEpochMillis)
        assertContentEquals(ByteArray(300) { it.toByte() }, decoded[1].sessionInfo)
        assertEquals(0, assertNotNull(SessionCacheCodec.decode(SessionCacheCodec.encode(keyId, emptyList()), keyId)).size)
    }

    @Test
    fun roundTripsAtTheAcceptedEntryCountAndInfoLengthLimits() {
        // decode()의 count/infoLen은 부호 없는 값을 `and BYTE_MASK`/`and SHORT_MASK`로 풀어낸다(SessionCacheCodec.kt).
        // 255(u8 최댓값)와 65535(u16 최댓값)는 부호 있는 Byte/Short로 읽으면 각각 -1이 되는 경계값이라 별도로 고정한다.
        val maxEntryCount = 255
        val maxInfoLength = 65_535
        val maxEntries = List(maxEntryCount) { SessionCacheCodec.Entry(Domain.DOMAIN_VEHICLE_SECURITY, createdAt, byteArrayOf(1)) }
        assertEquals(maxEntryCount, assertNotNull(SessionCacheCodec.decode(SessionCacheCodec.encode(keyId, maxEntries), keyId)).size)

        val info = ByteArray(maxInfoLength) { it.toByte() }
        val maxInfoEntry = SessionCacheCodec.Entry(Domain.DOMAIN_INFOTAINMENT, createdAt, info)
        val decoded = assertNotNull(SessionCacheCodec.decode(SessionCacheCodec.encode(keyId, listOf(maxInfoEntry)), keyId)).single()
        assertContentEquals(info, decoded.sessionInfo)
    }

    @Test
    fun rejectsOtherKeyIdBadMagicBadVersionTruncationAndTrailingBytes() {
        val bytes = expectedHex.hexToBytes()
        assertNull(SessionCacheCodec.decode(bytes, KeyId(ByteArray(20) { 9 }))) // 다른 키 → 무시(ADR-0007)
        assertNull(SessionCacheCodec.decode(("54425344" + expectedHex.substring(8)).hexToBytes(), keyId)) // "TBSD"
        assertNull(SessionCacheCodec.decode(("54425343" + "02" + expectedHex.substring(10)).hexToBytes(), keyId)) // version 2
        assertNull(SessionCacheCodec.decode(bytes.copyOf(bytes.size - 1), keyId)) // 잘림
        assertNull(SessionCacheCodec.decode(bytes + byteArrayOf(0), keyId)) // 꼬리 바이트
        assertNull(SessionCacheCodec.decode(ByteArray(0), keyId))
    }

    @Test
    fun skipsEntriesWithUnknownDomainValue() {
        // 신형 펌웨어 도메인 7 — Go LoadCache는 universal.Domain(7)을 그대로 세션 맵에 넣지만 우리는 세션을 만들 수 없어 건너뛴다(설계 구체화 6)
        val withUnknown =
            expectedHex.replace(
                "01" + "02" + "0000018bcfe56800",
                "02" + "07" + "0000018bcfe56800" + "0002" + "0806" + "02" + "0000018bcfe56800",
            )
        val decoded = assertNotNull(SessionCacheCodec.decode(withUnknown.hexToBytes(), keyId))
        assertEquals(listOf(Domain.DOMAIN_VEHICLE_SECURITY), decoded.map { it.domain })
    }

    @Test
    fun keyIdIsSha1OfThePublicKeyAndRejectsOtherLengths() {
        val crypto = TestCrypto.primitives
        val id = KeyId.of(TestCrypto.clientPublicKey, crypto)
        assertContentEquals(crypto.sha1(TestCrypto.clientPublicKey.toByteArray()), id.toByteArray())
        assertEquals(id, KeyId.of(TestCrypto.clientPublicKey, crypto))
        assertFailsWith<IllegalArgumentException> { KeyId(ByteArray(19)) }
        assertFailsWith<IllegalArgumentException> { SessionCacheCodec.encode(keyId, List(256) { entry }) }
        val tooLong = SessionCacheCodec.Entry(Domain.DOMAIN_INFOTAINMENT, 0L, ByteArray(65_536))
        assertFailsWith<IllegalArgumentException> { SessionCacheCodec.encode(keyId, listOf(tooLong)) }
    }

    // encodeSnapshots/decodeSessions(SessionSnapshot/CachedSession 매핑 헬퍼) 전용 케이스.
    @Test
    fun encodeSnapshotsStampsNowAndDecodeSessionsComputesAge() {
        val snapshot = SessionSnapshot(Domain.DOMAIN_VEHICLE_SECURITY, "0806".hexToBytes())
        val bytes = SessionCacheCodec.encodeSnapshots(keyId, listOf(snapshot), createdAt)
        val decoded = assertNotNull(SessionCacheCodec.decodeSessions(bytes, keyId, createdAt + 30_000))
        assertEquals(1, decoded.size)
        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, decoded[0].domain)
        assertContentEquals("0806".hexToBytes(), decoded[0].sessionInfo)
        assertEquals(30_000.milliseconds, decoded[0].age)
    }

    @Test
    fun decodeSessionsYieldsNegativeAgeForFutureEntryAndNullForWrongKeyId() {
        // 설계 구체화 7: 벽시계가 뒤로 감 → age < 0, 클램프하지 않는다(Signer.importSessionInfo 몫).
        val snapshot = SessionSnapshot(Domain.DOMAIN_INFOTAINMENT, byteArrayOf(1))
        val bytes = SessionCacheCodec.encodeSnapshots(keyId, listOf(snapshot), createdAt)
        assertEquals((-5_000).milliseconds, assertNotNull(SessionCacheCodec.decodeSessions(bytes, keyId, createdAt - 5_000)).single().age)
        assertNull(SessionCacheCodec.decodeSessions(bytes, KeyId(ByteArray(20) { 9 }), createdAt)) // 다른 키 → null
    }
}
