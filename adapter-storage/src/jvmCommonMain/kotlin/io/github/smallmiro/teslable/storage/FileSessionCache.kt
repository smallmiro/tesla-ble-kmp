package io.github.smallmiro.teslable.storage

import io.github.smallmiro.teslable.cache.CachedSession
import io.github.smallmiro.teslable.cache.SessionCacheCodec
import io.github.smallmiro.teslable.cache.SessionSnapshot
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TeslaLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 앱 전용 디렉터리의 파일 세션 캐시(SDD §2.6). `<directory>/<hex(SHA1(vin))>.bin` = [SessionCacheCodec] v1.
 * 쓰기는 임시 파일(fsync) + rename(원자적). I/O 실패는 로그만 남기고 빈 결과(포트 계약). 벽시계는 [wallClockMillis]로만.
 */
public class FileSessionCache(
    private val directory: File,
    private val crypto: CryptoPrimitives,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
) : SessionCache {
    private val mutex = Mutex()

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> =
        locked {
            val file = fileFor(vin)
            if (!file.exists()) return@locked emptyList()
            val decoded =
                try {
                    SessionCacheCodec.decodeSessions(file.readBytes(), keyId, wallClockMillis())
                } catch (e: IOException) {
                    logger.log(LogLevel.WARN, TAG) { "Failed to read session cache: ${e::class.simpleName}" }
                    return@locked emptyList()
                }
            if (decoded == null) file.delete()
            decoded.orEmpty()
        }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        locked {
            try {
                directory.mkdirs()
                val target = fileFor(vin)
                val temp = File(directory, "${target.name}.tmp")
                FileOutputStream(temp).use { out ->
                    out.write(SessionCacheCodec.encodeSnapshots(keyId, entries, wallClockMillis()))
                    out.fd.sync() // rename 전에 디스크에 반영(전원 손실 시 빈 파일 방지)
                }
                if (!temp.renameTo(target)) throw IOException("rename failed")
            } catch (e: IOException) {
                logger.log(LogLevel.WARN, TAG) { "Failed to store session cache: ${e::class.simpleName}" }
            }
        }
    }

    override suspend fun clear(vin: Vin) {
        locked { fileFor(vin).delete() }
    }

    private fun fileFor(vin: Vin) = File(directory, cacheFileName(vin, crypto) + ".bin")

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val TAG = "cache"
    }
}
