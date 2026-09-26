package io.github.smallmiro.teslable.transport

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(InternalTeslableApi::class)
class FramingTest {
    private val message = ProtocolVectors.LIST_KEYS_TX.hexToBytes() // 49 bytes

    @Test
    fun framePrefixesBigEndianLengthAndSplitsByBlockLength() {
        val chunks = Framer.frame(message, blockLength = 20) // 51 bytes → 20 + 20 + 11
        assertEquals(3, chunks.size)
        assertEquals("0031", chunks[0].copyOf(2).toHex())
        assertEquals(listOf(20, 20, 11), chunks.map { it.size })
        assertEquals(message.toHex(), chunks.reduce { a, b -> a + b }.copyOfRange(2, 51).toHex())
    }

    @Test
    fun frameSmallerThanBlockIsSingleChunk() { // Review Focus 2: MTU 517 협상 후 짧은 명령
        val chunks = Framer.frame(byteArrayOf(1, 2, 3), blockLength = 514)
        assertEquals(1, chunks.size)
        assertEquals("0003010203", chunks.single().toHex())
    }

    @Test
    fun acceptsMessageOfExactlyMaxSize() { // Review Focus 1: 길이 1024는 최대값이므로 수용
        val r = Reassembler(TestTimeSource())
        val big = ByteArray(1024) { it.toByte() }
        val got = Framer.frame(big, 244).flatMap { r.push(it) }
        assertEquals(big.toHex(), got.single().toHex())
    }

    @Test
    fun frameRejectsMessageLargerThanMaxSize() { // 수신 측 maxBLEMessageSize(1024)를 넘으면 차량이 버린다
        assertEquals(1026, Framer.frame(ByteArray(Framer.MAX_MESSAGE_SIZE), blockLength = 2048).single().size)
        assertFailsWith<IllegalArgumentException> { Framer.frame(ByteArray(Framer.MAX_MESSAGE_SIZE + 1), blockLength = 244) }
    }

    @Test
    fun frameOfEmptyMessageIsLengthHeaderOnly() {
        assertEquals("0000", Framer.frame(ByteArray(0), blockLength = 20).single().toHex())
    }

    @Test
    fun reassemblesMessageSplitAcrossThreeChunks() {
        val r = Reassembler(TestTimeSource())
        val got = Framer.frame(message, 20).flatMap { r.push(it) }
        assertEquals(message.toHex(), got.single().toHex())
    }

    @Test
    fun reassemblesTwoMessagesInOneChunk() {
        val r = Reassembler(TestTimeSource())
        val two = Framer.frame(byteArrayOf(1, 2), 100).single() + Framer.frame(byteArrayOf(3), 100).single()
        assertEquals(listOf("0102", "03"), r.push(two).map { it.toHex() })
    }

    @Test
    fun resetsBufferWhenChunksAreMoreThanOneSecondApart() {
        val time = TestTimeSource()
        val r = Reassembler(time, rxTimeout = 1.seconds)
        val chunks = Framer.frame(message, 20)
        assertTrue(r.push(chunks[0]).isEmpty())
        time += 1500.milliseconds
        // 1초가 지나 오래된 chunks[0]이 버려졌다면 다시 보낸 [0],[1],[2]가 정확히 한 메시지가 된다.
        // 남아 있었다면 앞부분이 중복되어 스트림이 어긋나므로 이 단언이 실패한다.
        assertEquals(listOf(message.toHex()), chunks.flatMap { r.push(it) }.map { it.toHex() })
    }

    @Test
    fun dropsOversizedMessageAndRecovers() {
        val r = Reassembler(TestTimeSource())
        assertTrue(r.push(byteArrayOf(0x04, 0x01, 0x00)).isEmpty()) // 1025 > 1024 → 버퍼 폐기
        assertEquals("0102", r.push(Framer.frame(byteArrayOf(1, 2), 100).single()).single().toHex())
    }
}
