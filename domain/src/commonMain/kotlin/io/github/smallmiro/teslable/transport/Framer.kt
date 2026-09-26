// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0) — Connection.Send
package io.github.smallmiro.teslable.transport

/**
 * `2바이트 BE 길이 || 메시지`를 블록 길이(= min(MTU,1024) - 3)로 나눈다.
 */
public object Framer {
    /**
     * 최대 메시지 크기 (바이트).
     */
    public const val MAX_MESSAGE_SIZE: Int = 1024

    /**
     * 메시지를 2바이트 BE 길이 헤더와 함께 blockLength 단위 청크로 나눈다.
     * Go `Send`에는 없는 검사: 수신 측(`maxBLEMessageSize`)이 [MAX_MESSAGE_SIZE]를 넘는 메시지를 버리므로 보내기 전에 거부한다.
     * @throws IllegalArgumentException [message]가 [MAX_MESSAGE_SIZE] 바이트보다 크거나 [blockLength]가 1 미만이면 발생한다.
     */
    public fun frame(
        message: ByteArray,
        blockLength: Int,
    ): List<ByteArray> {
        require(message.size <= MAX_MESSAGE_SIZE) { "message must be at most $MAX_MESSAGE_SIZE bytes" }
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
