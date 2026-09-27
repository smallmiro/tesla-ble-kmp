package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PendingRequestTest {
    private val key = PendingKey(ByteArray(16) { 1 }.toByteString(), ByteString.EMPTY, Domain.DOMAIN_VEHICLE_SECURITY)

    @Test
    fun buffersTenResponsesThenDropsLikeGo() =
        // receiver.go receiverBufferSize = 10; dispatcher.go process: select { handler.ch <- message; default: drop }
        runTest {
            val pending = PendingRequest(key, requestHash = null, sentAt = testTimeSource.markNow())
            repeat(PendingRequest.BUFFER_SIZE) { assertTrue(pending.deliver(RoutableMessage(flags = it))) }
            assertFalse(pending.deliver(RoutableMessage(flags = 99)))
            repeat(PendingRequest.BUFFER_SIZE) { assertEquals(it, pending.receive().flags) }
            assertNull(pending.tryReceive())
        }

    @Test
    fun expiresOnceLifetimeHasPassed() =
        // receiver.go expired: time.Now().After(requestSentAt.Add(lifetime))
        runTest {
            val pending = PendingRequest(key, requestHash = null, sentAt = testTimeSource.markNow())
            assertFalse(pending.expired(4.seconds))
            advanceTimeBy(4_000)
            assertFalse(pending.expired(4.seconds)) // 정확히 4초는 아직 유효
            advanceTimeBy(1)
            assertTrue(pending.expired(4.seconds))
        }

    @Test
    fun closeMarksClosedClosesChannelAndIsIdempotent() =
        runTest {
            val pending = PendingRequest(key, requestHash = ByteArray(17) { 5 }, sentAt = testTimeSource.markNow())
            assertFalse(pending.isClosed)
            pending.close()
            pending.close()
            assertTrue(pending.isClosed)
            assertFalse(pending.deliver(RoutableMessage()))
            assertNull(pending.tryReceive())
            assertFailsWith<ClosedReceiveChannelException> { pending.receive() }
        }

    @Test
    fun requestHashIsCopiedDefensively() =
        runTest {
            val original = ByteArray(17) { 7 }
            val pending = PendingRequest(key, requestHash = original, sentAt = testTimeSource.markNow())
            original[0] = 0
            assertEquals(7, pending.requestHash?.get(0))
            pending.requestHash?.set(1, 0)
            assertEquals(7, pending.requestHash?.get(1))
            // Intentional difference from receiver.go receiverKey.String(): Go's zero-value 16-byte uuid prints as 32
            // hex zeros; this uses ByteString.EMPTY for VCSEC's uuid, so it prints as an empty string instead.
            assertEquals("<01010101010101010101010101010101-: DOMAIN_VEHICLE_SECURITY>", key.toString())
        }
}
