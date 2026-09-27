package io.github.smallmiro.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.application.dispatcher.HandshakeFlow
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.encode
import io.github.smallmiro.teslable.application.dispatcher.replyTo
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SendWithRetryTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val payload = "payload".encodeToByteArray()

    private val retriableFaults =
        listOf(
            MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
            MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
            MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL,
            MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
            MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
            MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG,
        )

    @Test
    fun returnsTerminalFailureAfterTransientSendError() =
        // vehicle_test.go TestVehicleSendError: 일시 전송 오류 뒤 치명 오류 → 치명 오류 반환
        runTest {
            val h = dispatcherHarness()
            val send = SendWithRetry(h.dispatcher)
            h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("synergize"))
            h.transport.enqueueSendError(VehicleError.TransportError.Disconnected)
            val result = assertIs<VehicleResult.Failure>(send.send(vcsec, payload, AuthMethod.NONE))
            assertEquals(VehicleError.TransportError.Disconnected, result.error)
            assertEquals(2, h.transport.sent.size)
        }

    @Test
    fun timesOutBeforeSendWhenTransportKeepsFailingTransiently() =
        // vehicle_test.go TestVehicleSendTimeout: 전송 전 만료 → Failure(Timeout(afterSend = false)), temporary, !mayHaveSucceeded
        runTest {
            val h = dispatcherHarness()
            repeat(100) { h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("libations")) }
            val result =
                assertIs<VehicleResult.Failure>(
                    SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 1.milliseconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertTrue(result.error.temporary)
            assertFalse(result.error.mayHaveSucceeded)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun retriesWhileVehicleAnswersBusyThenTimesOutBetweenAttempts() =
        // vehicle_test.go TestVehicleRetryTimeout: WAIT+BUSY 고정 응답 → 재시도 반복, 재시도 대기 중 만료 → Go ctx.Err() = 부작용 없음.
        // retryInterval 3ms, timeout 10ms: 시도는 t=0,3,6,9ms에 즉시 응답을 받고 만료(t=10ms)는 항상 delay 중에 걸린다(결정적).
        runTest {
            val h = dispatcherHarness(retryInterval = 3.milliseconds)
            val busy =
                FakeVehicle.ScriptedReply(
                    fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
                    operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT,
                )
            h.fake.script(vcsec, *Array(100) { listOf(busy) })
            val result =
                assertIs<VehicleResult.Failure>(
                    SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 10.milliseconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertEquals(4, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun noResponseTimesOutAsUncertainAndIsNeverResent() =
        // vehicle_test.go TestVehicleNoResponseTimeout + NFR-007: 응답 없음 → Uncertain(Timeout(afterSend = true)), 자동 재전송 금지
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList())
            val result =
                assertIs<VehicleResult.Uncertain>(
                    SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 1.milliseconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertTrue(result.error.mayHaveSucceeded)
            assertEquals(1, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun retriesEveryRetriableFaultThenReturnsTheTerminalOne() =
        // vehicle_test.go TestVehicleRetryFail: retriableErrors 8개 뒤 INSUFFICIENT_PRIVILEGES → 5초 안에 그 오류로 끝난다
        runTest {
            val h = dispatcherHarness()
            val scripts =
                retriableFaults.map { listOf(FakeVehicle.ScriptedReply(fault = it)) } +
                    listOf(listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES)))
            h.fake.script(vcsec, *scripts.toTypedArray())
            val start = testTimeSource.markNow()
            val result =
                assertIs<VehicleResult.Failure>(SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 5.seconds))
            assertEquals(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES), result.error)
            assertEquals(9, h.transport.sent.size)
            assertEquals(h.dispatcher.retryInterval * 8, start.elapsedNow())
        }

    @Test
    fun reauthorizesEachRetryWithFreshCounterAndNonce() =
        // FR-101: 재시도는 새 counter·nonce·expires_at으로 재인가한다(Go Vehicle.Send는 trySend마다 getReceiver → Dispatcher.Send → Encrypt)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(vcsec))
            h.fake.script(
                vcsec,
                listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL)),
                listOf(FakeVehicle.vcsecEmpty()),
            )
            val result = SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.GCM)
            assertIs<VehicleResult.Success<*>>(result)
            val authenticated = h.fake.received.filter { it.signature_data?.AES_GCM_Personalized_data != null }
            assertEquals(2, authenticated.size)
            val first = assertNotNull(authenticated[0].signature_data?.AES_GCM_Personalized_data)
            val second = assertNotNull(authenticated[1].signature_data?.AES_GCM_Personalized_data)
            assertEquals(first.counter + 1, second.counter)
            assertFalse(first.nonce == second.nonce)
            assertTrue(second.expires_at >= first.expires_at)
            assertEquals(SendWithRetry.DEFAULT_FLAGS, authenticated[1].flags) // FR-010: FLAG_ENCRYPT_RESPONSE 항상
        }

    @Test
    fun retriesReuseThePayloadCopiedBeforeTheFirstAttempt() =
        // N1 / Go vehicle.go 236~238: Send copies payload into payloadCopy once, before any attempt, and every
        // trySend call reuses that same copy. If a retry instead re-copied the caller's array on each attempt, a
        // caller that mutates its buffer after the first (failed) attempt returns would leak that mutation into
        // the retry — this pins the frozen-once behaviour with a mutation between the two attempts.
        runTest {
            val h = dispatcherHarness(retryInterval = 1.milliseconds)
            h.fake.script(
                vcsec,
                listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY)),
                listOf(FakeVehicle.vcsecEmpty()),
            )
            val mutablePayload = "AAAA".encodeToByteArray()
            val job = launch { SendWithRetry(h.dispatcher).send(vcsec, mutablePayload, AuthMethod.NONE) }
            runCurrent() // first attempt sent + BUSY reply processed; job is now suspended in delay(retryInterval)
            mutablePayload[0] = 'Z'.code.toByte()
            advanceUntilIdle() // let the retry fire and the job finish
            job.join()
            assertEquals(2, h.transport.sent.size)
            val decoded = h.transport.sent.map { RoutableMessage.ADAPTER.decode(it) }
            val sentPayloads = decoded.map { it.protobuf_message_as_bytes?.utf8() }
            assertEquals(listOf("AAAA", "AAAA"), sentPayloads)
        }

    @Test
    fun cancelledCommandReleasesPendingRequest() =
        // Review Focus 3 / ADR-0010: 취소는 CancellationException으로 전파되고 PendingRequest는 finally에서 풀린다
        runTest {
            val h = dispatcherHarness()
            h.fake.script(vcsec, emptyList())
            val job = launch { SendWithRetry(h.dispatcher).send(vcsec, payload, AuthMethod.NONE, timeout = 10.seconds) }
            runCurrent()
            assertEquals(1, h.dispatcher.pendingCount())
            job.cancel()
            runCurrent()
            assertTrue(job.isCancelled)
            assertEquals(0, h.dispatcher.pendingCount())
            val late = replyTo(h.fake.received.last(), "late".encodeToByteArray())
            h.transport.deliver(encode(late))
            runCurrent()
            assertTrue(h.logger.contains("Dropping message without registered handler"))
        }
}
