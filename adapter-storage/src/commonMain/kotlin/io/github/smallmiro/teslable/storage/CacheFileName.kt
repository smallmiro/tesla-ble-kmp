package io.github.smallmiro.teslable.storage

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.util.toHex

/** VIN 원문 대신 `hex(SHA1(VIN))`을 파일 이름·Keychain account로 쓴다(SDD §7.1). */
@OptIn(InternalTeslableApi::class)
internal fun cacheFileName(
    vin: Vin,
    crypto: CryptoPrimitives,
): String = crypto.sha1(vin.toByteArray()).toHex()
