package dev.mirolab.teslable.application.vehicle

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.HandshakeFlow
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.dispatcher.encode
import dev.mirolab.teslable.application.dispatcher.replyTo
import dev.mirolab.teslable.application.infotainment.InfotainmentCommands
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.testing.FakeVehicle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SendWithRetryTest {
    // vehicle_test.go는 VCSEC로 보내지만 Kotlin은 SendWithRetry에서 VCSEC를 금지한다(설계 구체화 13) — 같은 흐름을 Infotainment로 검증
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
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
            val result = assertIs<VehicleResult.Failure>(send.send(infotainment, payload, AuthMethod.NONE))
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
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 1.milliseconds),
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
            h.fake.script(infotainment, *Array(100) { listOf(busy) })
            val result =
                assertIs<VehicleResult.Failure>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 10.milliseconds),
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
            h.fake.script(infotainment, emptyList())
            val result =
                assertIs<VehicleResult.Uncertain>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 1.milliseconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertTrue(result.error.mayHaveSucceeded)
            assertEquals(1, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun deadlineWhileTheWriteIsStillRunningIsUncertain() =
        // H1(2026-11-07 F4): 첫 BLE 쓰기를 부른 뒤의 만료는 쓰기가 끝나지 않았어도 Uncertain — 차량은 요청을 받았다
        runTest {
            val h = dispatcherHarness()
            h.transport.writeGate = CompletableDeferred()
            val result =
                assertIs<VehicleResult.Uncertain>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 1.seconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(1, h.fake.received.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun deadlineInTheTransportRetryAfterAFailedWriteIsUncertain() =
        // H1: 전송 재시도(Dispatcher.transmit의 delay)는 표시를 지우지 않는다. 쓰기 전 실패만 이어지면 그대로 Failure다
        // (timesOutBeforeSendWhenTransportKeepsFailingTransiently)
        runTest {
            val h = dispatcherHarness(retryInterval = 4.milliseconds)
            h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
            val result =
                assertIs<VehicleResult.Uncertain>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 2.milliseconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(1, h.transport.writesInvoked)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun aLaterTransportAttemptThatDoesNotWriteKeepsTheMark() =
        // 표시는 시도 안에서 되돌리지 않는다: 쓰기를 부른 뒤 실패하고, 다음 전송 시도들이 쓰기 전에 실패해도 만료는 Uncertain
        // (retryInterval 4ms, timeout 10ms: t=0 쓰기 실패, t=4·8 쓰기 전 실패, 만료 t=10은 delay 중)
        runTest {
            val h = dispatcherHarness(retryInterval = 4.milliseconds)
            h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
            repeat(10) { h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("not written")) }
            val result =
                assertIs<VehicleResult.Uncertain>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 10.milliseconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(1, h.transport.writesInvoked)
            assertEquals(3, h.transport.sent.size)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    private fun signedCopies(h: DispatcherHarness) = h.fake.received.filter { it.signature_data?.AES_GCM_Personalized_data != null }

    @Test
    fun aReplayFaultForTheResentCopyAfterAFailedWriteIsUncertainAndNoNewCopyIsSigned() =
        // VcsecCommandsTest와 같은 경로의 Infotainment 판: 첫 사본은 실행됐고 응답이 유실됐다. 재전송 사본의 INVALID_TOKEN_OR_COUNTER로 새 사본을
        // 인가해 보내지 않고(NFR-007) 시간 제한까지 첫 사본의 응답을 기다려 Uncertain(ADR-0010 2026-11-08 개정)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.fake.dropNextReplies(1)
            h.transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("gatt 133"))
            val result =
                assertIs<VehicleResult.Uncertain>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.GCM, timeout = 1.seconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(1, h.fake.authenticatedPayloads(infotainment).size)
            val copies = signedCopies(h)
            assertEquals(2, copies.size)
            assertEquals(copies[0], copies[1])
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun theDecisionIsAWarnLineOfTheDispatcherTag() =
        // 16·18쪽이 적은 로그 수준과 태그: WARN, Dispatcher, 시도 번호와 "possible duplicate"
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.fake.dropNextReplies(1)
            h.transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("gatt 133"))
            assertIs<VehicleResult.Uncertain>(SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.GCM, timeout = 1.seconds))
            val line = h.logger.entries.single { it.message.contains("possible duplicate") }
            assertEquals(LogLevel.WARN, line.level)
            assertEquals("Dispatcher", line.tag)
            assertTrue(line.message.startsWith("[attempt="))
            assertTrue(line.message.contains("MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER"))
        }

    @Test
    fun theFirstCopysReplyArrivingAfterTheReplayFaultIsTheResult() =
        // Infotainment 판: 같은 요청(uuid)을 계속 읽으므로 첫 사본의 늦은 응답이 결과가 된다(새 사본 없이)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.fake.holdNextReplies(1)
            h.transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("gatt 133"))
            val result = async { SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.GCM, timeout = 1.seconds) }
            advanceTimeBy(100.milliseconds)
            assertFalse(result.isCompleted)
            assertEquals(1, h.fake.releaseHeldReplies())
            assertIs<VehicleResult.Success<RoutableMessage>>(result.await())
            assertEquals(1, h.fake.authenticatedPayloads(infotainment).size)
            assertEquals(2, signedCopies(h).size)
        }

    @Test
    fun keyAndPrivilegeRefusalsAfterAFailedWriteAreUncertainNotFailure() =
        // 자기 키를 지운 뒤의 UNKNOWN_KEY_ID처럼 첫 사본이 바꾼 상태에 대한 답일 수 있다 — "실행 안 됨"으로 단정하지 않는다.
        // 첫 사본은 닿지 않아 차량이 재전송 사본을 처음 검증하고 대본으로 답한다
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            for (fault in listOf(
                MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID,
                MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES,
            )) {
                val before = signedCopies(h).size
                h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
                h.fake.script(infotainment, listOf(FakeVehicle.ScriptedReply(fault = fault)))
                val result = SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.GCM, timeout = 100.milliseconds)
                assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(result, "$fault").error)
                assertEquals(before + 1, signedCopies(h).size, "$fault")
            }
        }

    @Test
    fun aMayHaveSucceededReplyAfterAFailedWriteEndsTheCallAtOnce() =
        // RESPONSE_MTU_EXCEEDED는 mayHaveSucceeded라 이미 Uncertain이다 — 시간 제한까지 기다리지 않고 그 결과로 끝난다(#116 구분 유지)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
            h.fake.script(infotainment, listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED)))
            val start = testTimeSource.markNow()
            val result = SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.GCM, timeout = 5.seconds)
            assertEquals(
                VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED),
                assertIs<VehicleResult.Uncertain>(result).error,
            )
            assertEquals(h.dispatcher.retryInterval, start.elapsedNow()) // 전송 재시도 대기 한 번뿐
        }

    @Test
    fun getStateIsRepeatSafeSoItSignsANewCopyAfterTheReplayFault() =
        // 반복해도 안전한 조회는 면제한다(ADR-0010 2026-11-08 개정): 같은 경로에서도 Go처럼 새 사본으로 다시 물어 데이터를 받는다.
        // 조회는 차량 상태를 바꾸지 않으므로 두 번 처리돼도 해가 없다
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.fake.dropNextReplies(1)
            h.transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("gatt 133"))
            val result = InfotainmentCommands(SendWithRetry(h.dispatcher)).getState(StateCategory.CHARGE, timeout = 1.seconds)
            assertIs<VehicleResult.Success<*>>(result)
            assertEquals(2, h.fake.authenticatedPayloads(infotainment).size)
            assertEquals(3, signedCopies(h).size)
            assertFalse(h.logger.contains("possible duplicate"))
        }

    @Test
    fun deadlineWhileWaitingForTheTransportToWriteIsFailure() =
        // 전송 직렬화 대기(BleTransport sendMutex)에서 끝나면 쓰기를 부른 적이 없다 → Failure(Timeout(afterSend = false))
        runTest {
            val h = dispatcherHarness()
            h.transport.preWriteGate = CompletableDeferred()
            val result =
                assertIs<VehicleResult.Failure>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 1.seconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertEquals(0, h.transport.writesInvoked)
            assertEquals(0, h.fake.received.size)
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
            h.fake.script(infotainment, *scripts.toTypedArray())
            val start = testTimeSource.markNow()
            val result =
                assertIs<VehicleResult.Failure>(
                    SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 5.seconds),
                )
            assertEquals(VehicleError.InsufficientPrivileges, result.error)
            assertEquals(9, h.transport.sent.size)
            assertEquals(h.dispatcher.retryInterval * 8, start.elapsedNow())
        }

    @Test
    fun reauthorizesEachRetryWithFreshCounterAndNonce() =
        // FR-101: 재시도는 새 counter·nonce·expires_at으로 재인가한다(Go Vehicle.Send는 trySend마다 getReceiver → Dispatcher.Send → Encrypt)
        runTest {
            val h = dispatcherHarness()
            assertIs<VehicleResult.Success<Unit>>(HandshakeFlow(h.dispatcher).startSession(infotainment))
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL)),
                listOf(FakeVehicle.vcsecEmpty()),
            )
            val result = SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.GCM)
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
        // Go vehicle.go 236~238: Send copies payload into payloadCopy once, before any attempt, and every
        // trySend call reuses that same copy. If a retry instead re-copied the caller's array on each attempt, a
        // caller that mutates its buffer after the first (failed) attempt returns would leak that mutation into
        // the retry — this pins the frozen-once behaviour with a mutation between the two attempts.
        runTest {
            val h = dispatcherHarness(retryInterval = 1.milliseconds)
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.ScriptedReply(fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY)),
                listOf(FakeVehicle.vcsecEmpty()),
            )
            val mutablePayload = "AAAA".encodeToByteArray()
            val job = launch { SendWithRetry(h.dispatcher).send(infotainment, mutablePayload, AuthMethod.NONE) }
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
        // Review Focus 3 / ADR-0010: 취소는 CancellationException으로 전파되고 PendingRequest는 finally에서 풀린다.
        // M1(2차 리뷰): job.cancel() 뒤에는 job.getCompletionExceptionOrNull()이 항상 CancellationException이다 —
        // send()가 취소를 삼켜 값으로 바꿔 정상 반환해도 Job 자체는 여전히 Cancelled로 끝나므로 그 어설션은 아무 것도
        // 증명하지 못한다(withAttemptTimeout이 취소를 삼켜 값으로 바꾸는 뮤턴트로 확인했다 — trySend 안에서 삼켜도
        // withTimeoutOrNull이 자신의 타임아웃이 아니라 바깥 Job의 취소로 판단해 다시 던지므로 가려진다; 이 테스트가
        // 실제로 잡는 것은 withAttemptTimeout 단계의 스왈로우다). 대신 결과 대입이 실제로 일어나는지를 본다: 취소가
        // 제대로 전파되면 대입 줄에 도달하지 못해 result는 계속 null이다.
        runTest {
            val h = dispatcherHarness()
            h.fake.script(infotainment, emptyList())
            var result: VehicleResult<*>? = null
            val job = launch { result = SendWithRetry(h.dispatcher).send(infotainment, payload, AuthMethod.NONE, timeout = 10.seconds) }
            runCurrent()
            assertEquals(1, h.dispatcher.pendingCount())
            job.cancel()
            runCurrent()
            assertTrue(job.isCancelled)
            assertNull(result)
            assertEquals(0, h.dispatcher.pendingCount())
            val late = replyTo(h.fake.received.last(), "late".encodeToByteArray())
            h.transport.deliver(encode(late))
            runCurrent()
            assertTrue(h.logger.contains("Dropping message without registered handler"))
        }

    @Test
    fun rejectsVcsecSoSerializationCannotBeBypassed() =
        // M2 최종 리뷰 Minor 9: VCSEC 직렬화(FR-049)는 VcsecCommands로만 보장된다
        runTest {
            val h = dispatcherHarness()
            val result = SendWithRetry(h.dispatcher).send(Domain.DOMAIN_VEHICLE_SECURITY, byteArrayOf(1), AuthMethod.NONE)
            assertIs<VehicleError.InvalidArgument>(assertIs<VehicleResult.Failure>(result).error)
            assertTrue(h.transport.sent.isEmpty())
        }
}
