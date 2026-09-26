package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.transport.LocalName

/**
 * M0 임시 파사드: 샘플 앱이 라이브러리 링크와 SKIE 변환을 확인하는 데 쓴다.
 * M3에서 `TeslaBle` 파사드로 대체된다.
 */
public object Teslable {
    public const val VERSION: String = "0.1.0-SNAPSHOT"

    /** VIN의 BLE 광고 이름 (`"S" + hex(SHA1(VIN)[:8]) + "C"`). */
    public fun localNameFor(vin: String): String = LocalName.of(Vin(vin), platformCryptoPrimitives()).value
}
