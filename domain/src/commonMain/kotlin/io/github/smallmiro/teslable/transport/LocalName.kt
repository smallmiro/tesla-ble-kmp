// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0) — VehicleLocalName
package io.github.smallmiro.teslable.transport

import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives

/**
 * 광고 Local Name `"S" + hex(SHA1(VIN)[:8]) + "C"` (소문자 hex 16자).
 */
public class LocalName(
    /**
     * BLE 광고 Local Name 문자열 (`"S" + hex(SHA1(VIN)[:8]) + "C"`).
     */
    public val value: String,
) {
    override fun equals(other: Any?): Boolean = other is LocalName && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    public companion object {
        /**
         * VIN에서 Local Name을 생성한다 (SHA1 다이제스트 기반).
         */
        public fun of(
            vin: Vin,
            crypto: CryptoPrimitives,
        ): LocalName {
            val digest = crypto.sha1(vin.toByteArray()).copyOf(PREFIX_LENGTH)
            return LocalName(
                "S" + digest.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_PADDING, '0') } + "C",
            )
        }

        private const val PREFIX_LENGTH = 8
        private const val BYTE_MASK = 0xff
        private const val HEX_RADIX = 16
        private const val HEX_PADDING = 2
    }
}
