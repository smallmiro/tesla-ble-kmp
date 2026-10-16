package dev.mirolab.teslable.crypto

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val MAGIC_SIZE = 4
private const val IV_SIZE = 12
private const val TAG_BITS = 128
private const val AES_BITS = 256
private const val SCALAR_SIZE = 32
private const val FOREIGN_BODY_SIZE = 40

class WrappedKeyFilesTest {
    private val dir: File = Files.createTempDirectory("teslable-keys").toFile()

    /** 테스트용 JCA AES-GCM 래퍼(운영은 AndroidKeyStore AES 키). */
    private class JcaWrapper : KeyWrapper {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(AES_BITS) }.generateKey()
        var destroyed = false

        override fun wrap(plain: ByteArray): ByteArray {
            val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
            return iv + cipher.doFinal(plain)
        }

        override fun unwrap(wrapped: ByteArray): ByteArray? =
            runCatching {
                val cipher =
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, IV_SIZE)))
                    }
                cipher.doFinal(wrapped.copyOfRange(IV_SIZE, wrapped.size))
            }.getOrNull()

        override fun destroy() {
            destroyed = true
        }
    }

    private val wrappers = HashMap<String, JcaWrapper>()
    private val files = WrappedKeyFiles(dir) { alias -> wrappers.getOrPut(alias) { JcaWrapper() } }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun writesWrappedKeyAndReadsItBack() {
        val pair = generateSoftwareKeyPair()
        files.write("teslable-default", pair)
        val stored = File(dir, "teslable-default.enc").readBytes()
        assertEquals("TBK1", stored.copyOfRange(0, MAGIC_SIZE).decodeToString())
        assertFalse(stored.toList().windowed(SCALAR_SIZE).any { it.toByteArray().contentEquals(pair.privateScalar) }) // 평문 스칼라가 파일에 없다
        val read = assertIs<WrappedKeyFiles.ReadResult.Found>(files.read("teslable-default"))
        assertEquals(pair.publicKey, read.pair.publicKey)
        assertContentEquals(pair.privateScalar, read.pair.privateScalar)
    }

    @Test
    fun missingFileIsMissing() {
        assertEquals(WrappedKeyFiles.ReadResult.Missing, files.read("nope"))
    }

    @Test
    fun tamperedOrForeignFileIsCorruptedNotReplaced() {
        files.write("a", generateSoftwareKeyPair())
        val file = File(dir, "a.enc")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertEquals(WrappedKeyFiles.ReadResult.Corrupted, files.read("a"))
        File(dir, "b.enc").writeBytes("XXXX".encodeToByteArray() + ByteArray(FOREIGN_BODY_SIZE))
        assertEquals(WrappedKeyFiles.ReadResult.Corrupted, files.read("b"))
        assertTrue(file.exists()) // 지우지 않는다(설계 메모)
    }

    @Test
    fun deleteRemovesFileAndDestroysWrapperKey() {
        files.write("a", generateSoftwareKeyPair())
        files.delete("a")
        assertFalse(File(dir, "a.enc").exists())
        assertTrue(wrappers.getValue("a").destroyed)
        files.delete("a") // 멱등
    }

    @Test
    fun overwriteLeavesNoTemporaryFiles() {
        files.write("a", generateSoftwareKeyPair())
        files.write("a", generateSoftwareKeyPair())
        assertEquals(listOf("a.enc"), dir.list()?.sorted())
    }

    @Test
    fun failedFileDeleteKeepsWrapperKey() {
        files.write("a", generateSoftwareKeyPair())
        wrappers.getValue("a").destroyed = false
        val file = File(dir, "a.enc")
        file.delete()
        file.mkdir() // 비어 있지 않은 디렉터리는 어느 OS에서도 delete()가 실패한다
        File(file, "x").writeBytes(ByteArray(1))
        assertFailsWith<IllegalStateException> { files.delete("a") }
        assertFalse(wrappers.getValue("a").destroyed)
    }
}
