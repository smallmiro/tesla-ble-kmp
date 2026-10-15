package io.github.smallmiro.teslable.crypto

import java.io.File
import java.io.FileOutputStream

/** `<directory>/<alias>.enc` = `"TBK1" ‖ wrap(04‖X‖Y‖d)`(설계 구체화 10). 쓰기는 임시 파일 + rename으로 원자적. */
internal class WrappedKeyFiles(
    private val directory: File,
    private val wrapperFor: (alias: String) -> KeyWrapper,
) {
    sealed interface ReadResult {
        data object Missing : ReadResult

        data object Corrupted : ReadResult

        class Found(
            val pair: SoftwareKeyPair,
        ) : ReadResult
    }

    fun read(alias: String): ReadResult {
        val file = fileFor(alias)
        if (!file.exists()) return ReadResult.Missing
        val bytes = file.readBytes()
        if (bytes.size <= MAGIC.size || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return ReadResult.Corrupted
        val plain = wrapperFor(alias).unwrap(bytes.copyOfRange(MAGIC.size, bytes.size)) ?: return ReadResult.Corrupted
        return try {
            SoftwareKeyPair.fromExternal(plain)?.let { ReadResult.Found(it) } ?: ReadResult.Corrupted
        } finally {
            plain.fill(0)
        }
    }

    fun write(
        alias: String,
        pair: SoftwareKeyPair,
    ) {
        directory.mkdirs()
        val plain = pair.toExternal()
        val wrapped =
            try {
                wrapperFor(alias).wrap(plain)
            } finally {
                plain.fill(0)
            }
        val target = fileFor(alias)
        val temp = File(directory, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(MAGIC + wrapped)
            out.fd.sync() // rename 전에 디스크에 내린다
        }
        check(temp.renameTo(target)) { "could not move key file into place" }
    }

    fun delete(alias: String) {
        val file = fileFor(alias)
        check(!file.exists() || file.delete()) { "could not delete key file" }
        wrapperFor(alias).destroy()
    }

    private fun fileFor(alias: String) = File(directory, "$alias.enc")

    private companion object {
        val MAGIC = "TBK1".encodeToByteArray()
    }
}
