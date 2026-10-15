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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import kotlin.time.Clock

/** iOS 기본 세션 캐시(SDD §2.6): Keychain generic password, account = `hex(SHA1(vin))`. Keychain I/O는 `Dispatchers.IO`에서 한다. */
@OptIn(ExperimentalForeignApi::class)
public fun appleSessionCache(
    crypto: CryptoPrimitives,
    logger: TeslaLogger = TeslaLogger.NoOp,
): SessionCache =
    KeychainSessionCache(AppleKeychainItems(SESSION_SERVICE, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly), crypto, logger) {
        Clock.System.now().toEpochMilliseconds()
    }

private const val SESSION_SERVICE = "io.github.smallmiro.teslable.session"

internal class KeychainSessionCache(
    private val keychain: KeychainItems,
    private val crypto: CryptoPrimitives,
    private val logger: TeslaLogger,
    private val wallClockMillis: () -> Long,
) : SessionCache {
    private val mutex = Mutex()

    override suspend fun load(
        vin: Vin,
        keyId: KeyId,
    ): List<CachedSession> =
        locked {
            val account = cacheFileName(vin, crypto)
            when (val read = keychain.read(account)) {
                KeychainRead.Missing -> {
                    emptyList()
                }

                is KeychainRead.Failed -> {
                    logger.log(LogLevel.WARN, TAG) { "Failed to read session cache: keychain status ${read.status}" }
                    emptyList()
                }

                is KeychainRead.Found -> {
                    SessionCacheCodec.decodeSessions(read.bytes, keyId, wallClockMillis()) ?: emptyList<CachedSession>().also {
                        keychain.delete(account)
                    }
                }
            }
        }

    override suspend fun store(
        vin: Vin,
        keyId: KeyId,
        entries: List<SessionSnapshot>,
    ) {
        locked {
            val status = keychain.write(cacheFileName(vin, crypto), SessionCacheCodec.encodeSnapshots(keyId, entries, wallClockMillis()))
            if (status != 0) logger.log(LogLevel.WARN, TAG) { "Failed to store session cache: keychain status $status" }
        }
    }

    override suspend fun clear(vin: Vin) {
        locked { keychain.delete(cacheFileName(vin, crypto)) }
    }

    // Keychain I/O는 호출자(Swift에서는 흔히 메인) 스레드를 막지 않게 IO에서 한다(최종 리뷰 M7).
    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private companion object {
        const val TAG = "cache"
    }
}
