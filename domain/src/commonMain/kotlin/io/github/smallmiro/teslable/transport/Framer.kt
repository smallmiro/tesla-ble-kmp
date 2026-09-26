// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0) — Connection.Send
package io.github.smallmiro.teslable.transport

/**
 * `2바이트 BE 길이 || 메시지`를 블록 길이(= min(MTU,1024) - 3)로 나눈다.
 */
public object Framer {
    public const val MAX_MESSAGE_SIZE: Int = 1024

    public fun frame(
        message: ByteArray,
        blockLength: Int,
    ): List<ByteArray> {
        require(blockLength >= 1) { "blockLength must be positive" }
        val framed = ByteArray(2 + message.size)
        framed[0] = (message.size shr BYTE_1_SHIFT).toByte()
        framed[1] = message.size.toByte()
        message.copyInto(framed, destinationOffset = 2)
        val chunks = ArrayList<ByteArray>((framed.size + blockLength - 1) / blockLength)
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + blockLength, framed.size)
            chunks += framed.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    private const val BYTE_1_SHIFT = 8
}
