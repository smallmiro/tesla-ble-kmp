package dev.mirolab.teslable.ble

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockLengthTest {
    @Test
    fun attMtuFollowsGoFormula() {
        // ble.go:354 blockLength = min(txMtu, maxBLEMessageSize) - 3
        assertEquals(514, BlockLength.fromAttMtu(517))
        assertEquals(182, BlockLength.fromAttMtu(185))
        assertEquals(1021, BlockLength.fromAttMtu(2000))
        assertEquals(20, BlockLength.fromAttMtu(23))
    }

    @Test
    fun belowSpecMinimumFallsBackToDefault() {
        // ble.go:350-352: 실패 시 ble.DefaultMTU(23) - 3
        assertEquals(20, BlockLength.DEFAULT)
        assertEquals(20, BlockLength.fromAttMtu(10))
    }

    @Test
    fun appleMaxWriteLengthIsAlreadyMtuMinusThree() {
        // iOS maximumWriteValueLength(.withResponse) = ATT MTU - 3 (설계 구체화 6)
        assertEquals(182, BlockLength.fromMaxWriteLength(182))
        assertEquals(512, BlockLength.fromMaxWriteLength(512))
        assertEquals(1021, BlockLength.fromMaxWriteLength(4096))
        assertEquals(20, BlockLength.fromMaxWriteLength(0))
    }
}
