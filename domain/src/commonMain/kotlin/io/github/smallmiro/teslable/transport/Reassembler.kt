// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0) — Connection.rx, flush
package io.github.smallmiro.teslable.transport

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 수신 청크를 메시지 단위로 재조립한다. 청크 간격이 [rxTimeout]을 넘으면 버퍼를 버리고,
 * 길이 헤더가 [maxMessageSize]를 넘으면 버퍼를 버린다. 스레드 안전하지 않다(수신 코루틴 하나에서만 호출).
 */
public class Reassembler(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val rxTimeout: Duration = 1.seconds,
    private val maxMessageSize: Int = Framer.MAX_MESSAGE_SIZE,
) {
    private var buffer = ByteArray(0)
    private var lastRx: TimeMark? = null

    public fun push(chunk: ByteArray): List<ByteArray> {
        val last = lastRx
        if (last != null && last.elapsedNow() > rxTimeout) buffer = ByteArray(0)
        lastRx = timeSource.markNow()
        buffer += chunk
        val out = ArrayList<ByteArray>(1)
        var processMore = true
        while (buffer.size >= HEADER_SIZE && processMore) {
            val length = ((buffer[0].toInt() and BYTE_MASK) shl BYTE_1_SHIFT) or (buffer[1].toInt() and BYTE_MASK)
            when {
                length > maxMessageSize -> {
                    buffer = ByteArray(0)
                    processMore = false
                }

                buffer.size >= HEADER_SIZE + length -> {
                    out += buffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
                    buffer = buffer.copyOfRange(HEADER_SIZE + length, buffer.size)
                }

                else -> {
                    processMore = false
                }
            }
        }
        return out
    }

    public fun reset() {
        buffer = ByteArray(0)
    }

    private companion object {
        const val HEADER_SIZE = 2
        const val BYTE_MASK = 0xff
        const val BYTE_1_SHIFT = 8
    }
}
