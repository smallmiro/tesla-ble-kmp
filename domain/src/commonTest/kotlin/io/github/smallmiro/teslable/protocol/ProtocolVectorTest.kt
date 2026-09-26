package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.port.AesGcmOutput
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors as V

/**
 * [TestCrypto.primitives]에 위임하면서 [aesGcmEncrypt]에 전달된 키 배열의 참조를 기록하는 테스트 전용 페이크.
 * `Session.close()`가 세션 자신의 키 배열(전달받은 것과 동일한 참조)을 0으로 덮는지 검증하는 데 쓴다.
 */
private class RecordingCryptoPrimitives(
    private val delegate: CryptoPrimitives,
) : CryptoPrimitives {
    var recordedEncryptKey: ByteArray? = null
        private set

    override fun sha1(data: ByteArray): ByteArray = delegate.sha1(data)

    override fun sha256(data: ByteArray): ByteArray = delegate.sha256(data)

    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = delegate.hmacSha256(key, data)

    override fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): AesGcmOutput {
        recordedEncryptKey = key
        return delegate.aesGcmEncrypt(key, nonce, plaintext, aad)
    }

    override fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray? = delegate.aesGcmDecrypt(key, nonce, ciphertext, tag, aad)

    override fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = delegate.constantTimeEquals(a, b)
}

@OptIn(InternalTeslableApi::class)
class ProtocolVectorTest {
    private val crypto = TestCrypto.primitives
    private val vin = V.VIN.encodeToByteArray()

    private fun docSession(): Session =
        Session.fromSharedKey(V.SHARED_KEY_K.hexToBytes(), TestCrypto.clientPublicKey, TestCrypto.vehiclePublicKey, crypto)

    @Test
    fun derivesSharedKeyFromProtocolDocKeys() =
        runTest {
            // 03-protocol.md §7.3
            val session = Session.establish(TestCrypto.clientKey(), TestCrypto.vehiclePublicKey, crypto)
            assertEquals(V.SESSION_INFO_KEY, session.subkey(SessionKeys.LABEL_SESSION_INFO).toHex())
            val sharedX = TestCrypto.clientKey().sharedX(TestCrypto.vehiclePublicKey)
            assertEquals(V.SHARED_KEY_K, SessionKeys.deriveK(sharedX, crypto).toHex())
        }

    @Test
    fun derivesSessionInfoSubkey() { // §7.4
        assertEquals(V.SESSION_INFO_KEY, docSession().subkey(SessionKeys.LABEL_SESSION_INFO).toHex())
    }

    @Test
    fun computesAndVerifiesSessionInfoTag() { // §7.4 (MITM 방지)
        val session = docSession()
        val tag = session.sessionInfoTag(vin, V.CHALLENGE.hexToBytes(), V.SESSION_INFO.hexToBytes())
        assertEquals(V.SESSION_INFO_TAG, tag.toHex())
        assertTrue(session.verifySessionInfoTag(vin, V.CHALLENGE.hexToBytes(), V.SESSION_INFO.hexToBytes(), tag))
        val tampered = tag.copyOf().also { it[5] = (it[5].toInt() xor 0x80).toByte() }
        assertFalse(session.verifySessionInfoTag(vin, V.CHALLENGE.hexToBytes(), V.SESSION_INFO.hexToBytes(), tampered))
        assertFalse(session.verifySessionInfoTag(vin, V.CHALLENGE.hexToBytes(), V.SESSION_INFO.hexToBytes(), tag.copyOf(31)))
    }

    @Test
    fun serializesCommandMetadataWithFlags() { // §8.2.1 정본 (flags = 2)
        val meta =
            CommandMetadata.build(
                domain = Domain.DOMAIN_INFOTAINMENT,
                personalization = vin,
                epoch = V.EPOCH.hexToBytes(),
                expiresAt = V.HVAC_EXPIRES_AT.toUInt(),
                counter = V.HVAC_COUNTER.toUInt(),
                flags = 2u,
            )
        assertEquals(V.HVAC_METADATA_FLAGS2, meta.serialize().toHex())
    }

    @Test
    fun omitsFlagsTagWhenFlagsZero() { // PoC 보조 케이스, peer.go extractMetadata "if message.Flags > 0"
        val meta =
            CommandMetadata.build(
                Domain.DOMAIN_INFOTAINMENT,
                vin,
                V.EPOCH.hexToBytes(),
                V.HVAC_EXPIRES_AT.toUInt(),
                V.HVAC_COUNTER.toUInt(),
                flags = 0u,
            )
        assertEquals(V.HVAC_METADATA_FLAGS0, meta.serialize().toHex())
    }

    @Test
    fun rejectsExpirationBeyondEpochLength() { // peer.go "out of bounds expiration time"
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            CommandMetadata.build(
                Domain.DOMAIN_INFOTAINMENT,
                vin,
                V.EPOCH.hexToBytes(),
                CommandMetadata.EPOCH_LENGTH_SECONDS + 1u,
                1u,
                2u,
            )
        }
    }

    @Test
    fun encryptsHvacOnLikeProtocolDoc() { // protocol_doc_test.go TestProtocolDocAESGCMExample
        val session = docSession()
        val aad = crypto.sha256(V.HVAC_METADATA_FLAGS2.hexToBytes())
        val out = session.encrypt(V.HVAC_ON_PLAINTEXT.hexToBytes(), aad, V.HVAC_NONCE.hexToBytes())
        assertEquals(V.HVAC_CIPHERTEXT, out.ciphertext.toHex())
        assertEquals(V.HVAC_TAG_FLAGS2, out.tag.toHex())
    }

    @Test
    fun encryptsWithFlagsZeroMetadataLikePoc() {
        val session = docSession()
        val aad = crypto.sha256(V.HVAC_METADATA_FLAGS0.hexToBytes())
        val out = session.encrypt(V.HVAC_ON_PLAINTEXT.hexToBytes(), aad, V.HVAC_NONCE.hexToBytes())
        assertEquals(V.HVAC_CIPHERTEXT, out.ciphertext.toHex())
        assertEquals(V.HVAC_TAG_FLAGS0, out.tag.toHex())
    }

    // §9.2: AAD = SHA256(TLV{9, domain, VIN, counter, flags(항상), request_hash, fault})
    @Test
    fun decryptsResponseRoundTripWithResponseMetadata() {
        val session = docSession()
        val requestHash =
            RequestHash.of(
                SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED,
                V.HVAC_TAG_FLAGS2.hexToBytes(),
                Domain.DOMAIN_INFOTAINMENT,
            )
        val meta = ResponseMetadata.build(Domain.DOMAIN_INFOTAINMENT, vin, counter = 8u, flags = 0u, requestHash = requestHash, fault = 0u)
        assertEquals(
            "000109010103021135594a333031323334353637383941424305040000000807040000000008" +
                "1105c228e0ff64991481db3a7bbc133696c5090400000000ff",
            meta.serialize().toHex(),
        )
        val aad = crypto.sha256(meta.serialize())
        val nonce = "000102030405060708090a0b".hexToBytes()
        val enc = session.encrypt("0a00".hexToBytes(), aad, nonce)
        assertContentEquals("0a00".hexToBytes(), session.decrypt(nonce, enc.ciphertext, enc.tag, aad))
        assertNull(session.decrypt(nonce, enc.ciphertext, enc.tag, crypto.sha256(byteArrayOf(1))))
    }

    @Test
    fun rejectsNonceOfWrongLength() { // Review Focus 3
        val session = docSession()
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            session.encrypt(V.HVAC_ON_PLAINTEXT.hexToBytes(), ByteArray(32), ByteArray(16))
        }
    }

    @Test
    fun closeZeroesKeyAndDisablesSession() {
        val session = docSession()
        session.close()
        assertFalse(
            session.verifySessionInfoTag(vin, V.CHALLENGE.hexToBytes(), V.SESSION_INFO.hexToBytes(), V.SESSION_INFO_TAG.hexToBytes()),
        )
    }

    @Test
    fun closeDisablesEncryptDecryptAndSubkey() {
        val session = docSession()
        session.close()
        assertNull(
            session.decrypt(V.HVAC_NONCE.hexToBytes(), V.HVAC_CIPHERTEXT.hexToBytes(), V.HVAC_TAG_FLAGS2.hexToBytes(), ByteArray(32)),
        )
        kotlin.test.assertFailsWith<IllegalStateException> {
            session.encrypt(V.HVAC_ON_PLAINTEXT.hexToBytes(), ByteArray(32), V.HVAC_NONCE.hexToBytes())
        }
        kotlin.test.assertFailsWith<IllegalStateException> { session.subkey(SessionKeys.LABEL_SESSION_INFO) }
        kotlin.test.assertFailsWith<IllegalStateException> {
            session.sessionInfoTag(vin, V.CHALLENGE.hexToBytes(), V.SESSION_INFO.hexToBytes())
        }
    }

    @Test
    fun closeZeroesKeyMaterial() {
        val recording = RecordingCryptoPrimitives(crypto)
        val session =
            Session.fromSharedKey(V.SHARED_KEY_K.hexToBytes(), TestCrypto.clientPublicKey, TestCrypto.vehiclePublicKey, recording)
        session.encrypt(V.HVAC_ON_PLAINTEXT.hexToBytes(), ByteArray(32), V.HVAC_NONCE.hexToBytes())
        session.close()
        val recordedKey = recording.recordedEncryptKey
        assertTrue(recordedKey != null && recordedKey.all { it == 0.toByte() })
    }

    @Test
    fun acceptsExpirationExactlyAtEpochLength() {
        CommandMetadata.build(
            Domain.DOMAIN_INFOTAINMENT,
            vin,
            V.EPOCH.hexToBytes(),
            CommandMetadata.EPOCH_LENGTH_SECONDS,
            1u,
            2u,
        )
    }

    @Test
    fun rejectsEpochOfWrongLength() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            CommandMetadata.build(
                Domain.DOMAIN_INFOTAINMENT,
                vin,
                ByteArray(15),
                V.HVAC_EXPIRES_AT.toUInt(),
                V.HVAC_COUNTER.toUInt(),
                flags = 2u,
            )
        }
    }

    @Test
    fun decryptReturnsNullForWrongNonceLength() {
        val session = docSession()
        val aad = crypto.sha256(V.HVAC_METADATA_FLAGS2.hexToBytes())
        val out = session.encrypt(V.HVAC_ON_PLAINTEXT.hexToBytes(), aad, V.HVAC_NONCE.hexToBytes())
        assertNull(session.decrypt(ByteArray(11), out.ciphertext, out.tag, aad))
    }
}
