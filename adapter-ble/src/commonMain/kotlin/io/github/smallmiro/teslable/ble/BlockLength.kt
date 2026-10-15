// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// tryToConnect MTU -> blockLength (ble.go:349-354)
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.transport.Framer

/** MTU에서 쓰기 블록 길이를 구한다(`ble.go:349-354`). */
internal object BlockLength {
    private const val ATT_HEADER = 3
    private const val MIN_ATT_MTU = 23

    /** Go `ble.DefaultMTU - 3`. MTU 교환 실패 시 쓴다. */
    const val DEFAULT: Int = MIN_ATT_MTU - ATT_HEADER

    /** ATT MTU(Android `requestMtu` 반환값) -> `min(mtu, 1024) - 3`. */
    fun fromAttMtu(mtu: Int): Int = if (mtu < MIN_ATT_MTU) DEFAULT else minOf(mtu, Framer.MAX_MESSAGE_SIZE) - ATT_HEADER

    /** iOS `maximumWriteValueLength(.withResponse)`(이미 `MTU - 3`) -> `min(length, 1021)`. */
    fun fromMaxWriteLength(length: Int): Int = if (length < DEFAULT) DEFAULT else minOf(length, Framer.MAX_MESSAGE_SIZE - ATT_HEADER)
}
