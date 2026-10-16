package dev.mirolab.teslable.storage

import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.util.toHex

/** VIN 원문 대신 `hex(SHA1(VIN))`을 파일 이름·Keychain account로 쓴다(SDD §7.1). */
@OptIn(InternalTeslableApi::class)
internal fun cacheFileName(
    vin: Vin,
    crypto: CryptoPrimitives,
): String = crypto.sha1(vin.toByteArray()).toHex()
