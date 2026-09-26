package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(InternalTeslableApi::class)
class CryptoPrimitivesKatTest {
    private val crypto = platformCryptoPrimitives()

    @Test
    fun sha1Abc() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", crypto.sha1("abc".encodeToByteArray()).toHex())
    }

    @Test
    fun sha256Abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto.sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha256OfEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            crypto.sha256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun hmacSha256Rfc4231Case2() {
        val mac = crypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.toHex())
    }

    @Test
    fun aesGcmNistTestCase4() {
        // NIST GCM spec test case 4 (AES-128, 12-byte IV, AAD 20 bytes, 60-byte plaintext)
        val key = "feffe9928665731c6d6a8f9467308308".hexToBytes()
        val iv = "cafebabefacedbaddecaf888".hexToBytes()
        val aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2".hexToBytes()
        val plaintext =
            (
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                    "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"
            ).hexToBytes()
        val out = crypto.aesGcmEncrypt(key, iv, plaintext, aad)
        assertEquals(
            "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
                "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091",
            out.ciphertext.toHex(),
        )
        assertEquals("5bc94fbc3221a5db94fae95ae7121a47", out.tag.toHex())
        assertContentEquals(plaintext, crypto.aesGcmDecrypt(key, iv, out.ciphertext, out.tag, aad))
    }

    @Test
    fun aesGcmDecryptReturnsNullOnTamperedTag() {
        val key = "1b2fce19967b79db696f909cff89ea9a".hexToBytes()
        val iv = "dbf79447fa156674dae1caed".hexToBytes()
        val out = crypto.aesGcmEncrypt(key, iv, "120452020801".hexToBytes(), "aad".encodeToByteArray())
        val badTag = out.tag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(crypto.aesGcmDecrypt(key, iv, out.ciphertext, badTag, "aad".encodeToByteArray()))
        assertNull(crypto.aesGcmDecrypt(key, iv, out.ciphertext, out.tag, "other".encodeToByteArray()))
    }

    @Test
    fun constantTimeEqualsComparesContentAndLength() {
        assertTrue(crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    @Test
    fun randomSourceReturnsRequestedLengthAndVaries() {
        val random = platformRandomSource()
        val a = random.nextBytes(16)
        val b = random.nextBytes(16)
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }
}
