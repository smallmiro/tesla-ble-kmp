package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Ping
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.SignedMessage_information_E
import com.tesla.generated.vcsec.SignedMessage_status
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.WhitelistOperation_status
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.testing.FakeVehicle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.tesla.generated.vcsec.OperationStatus_E as VcsecOperationStatus

@OptIn(ExperimentalCoroutinesApi::class)
class VcsecCommandsTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val lock = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_LOCK).encode() // vcsec.go executeRKEAction payload (M4가 빌더로 만든다)
    private val addKey = "whitelist operation".encodeToByteArray() // FakeVehicle은 payload를 해석하지 않으므로 임의 바이트로 충분하다

    private suspend fun connected(h: DispatcherHarness): VehicleSession {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
        return session
    }

    @Test
    fun nominalErrorFailsWhitelistAndRkeCommands() =
        // vcsec_test.go TestNominalVSCECError: AddKey, RemoveKey, Lock 모두 NominalVCSECError(VEHICLE_NOT_IN_PARK), !MHS, !Temporary
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val nominal = listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK))
            h.fake.script(vcsec, nominal, nominal, nominal)
            val expected = VehicleError.VcsecRejected(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK)
            for (done in listOf(
                VcsecResponses.WHITELIST_OPERATION_COMPLETE,
                VcsecResponses.WHITELIST_OPERATION_COMPLETE,
                VcsecResponses.COMMAND_STATUS_ABSENT,
            )) {
                val payload = if (done === VcsecResponses.COMMAND_STATUS_ABSENT) lock else addKey
                val result = assertIs<VehicleResult.Failure>(session.vcsec.execute(payload, AuthMethod.GCM, done))
                assertEquals(expected, result.error)
                assertFalse(result.error.mayHaveSucceeded)
                assertFalse(result.error.temporary)
            }
        }

    @Test
    fun gibberishResponseIsUncertainBadResponse() =
        // vcsec_test.go TestGibberishVCSECResponse: errors.Is(err, ErrBadResponse)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecGibberish()), listOf(FakeVehicle.vcsecGibberish()))
            for (done in listOf(VcsecResponses.WHITELIST_OPERATION_COMPLETE, VcsecResponses.COMMAND_STATUS_ABSENT)) {
                val result = assertIs<VehicleResult.Uncertain>(session.vcsec.execute(lock, AuthMethod.GCM, done))
                assertIs<VehicleError.BadResponse>(result.error)
            }
        }

    @Test
    fun whitelistOperationRetriesBusyReadsIntermediateThenReportsKeychainError() =
        // vcsec_test.go TestWhitelistOperationError: WAIT, WAIT(재시도), [authSuccess(중간), whitelistStatus(WHITELIST_FULL)] → KeychainError
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val full = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
            h.fake.script(
                vcsec,
                listOf(FakeVehicle.vcsecBusy()),
                listOf(FakeVehicle.vcsecBusy()),
                listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecWhitelistStatus(full)),
            )
            val result =
                assertIs<VehicleResult.Failure>(
                    session.vcsec.execute(addKey, AuthMethod.GCM, VcsecResponses.WHITELIST_OPERATION_COMPLETE, timeout = 5.seconds),
                )
            assertEquals(VehicleError.KeychainRejected(full), result.error)
            assertEquals(3, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun whitelistCompleteFailsWhenCodeArrivesWithoutAnErrorOperationStatus() =
        // vcsec.go isWhitelistOperationComplete 141행 "이 코드는 도달할 수 없어야 한다" 방어 분기.
        // operationStatus는 OK(기본값)인데 whitelistOperationStatus의 코드가 NONE이 아닌 경우 — unmarshalVCSECResponse의
        // commandStatusError는 ERROR가 아니므로 통과시키고, readUntil의 TerminalCheck.Fail 분기(VcsecResponses.kt)가
        // 실제로 실패로 끝내야 한다. readUntil(Fail) -> Success로 되돌리는 뮤턴트를 이 테스트 없이는 17개 테스트 전부 통과했다.
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val full = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
            val okWithWhitelistCode =
                FakeVehicle.vcsecPayload(
                    FromVCSECMessage(
                        commandStatus =
                            CommandStatus(
                                whitelistOperationStatus = WhitelistOperation_status(whitelistOperationInformation = full),
                            ),
                    ),
                )
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess(), okWithWhitelistCode))
            val result =
                assertIs<VehicleResult.Failure>(session.vcsec.execute(addKey, AuthMethod.GCM, VcsecResponses.WHITELIST_OPERATION_COMPLETE))
            assertEquals(VehicleError.KeychainRejected(full), result.error)
        }

    @Test
    fun cancellingExecuteReleasesTheLockAndThePendingRequest() =
        // Task 9의 교훈: job.isCancelled·getCompletionExceptionOrNull()은 공허한 어설션이다(취소를
        // 삼켜 값으로 바꿔도 Job 자체는 Cancelled로 끝난다). 결과 대입이 실제로 일어나는지로 취소 전파를 확인하고,
        // 락과 PendingRequest 등록이 실제로 풀렸는지(두 번째 execute가 진행되는지, pendingCount == 0)까지 본다.
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, emptyList(), listOf(FakeVehicle.vcsecEmpty()))
            var result: VehicleResult<FromVCSECMessage>? = null
            val job =
                launch {
                    result =
                        session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 10.seconds)
                }
            runCurrent()
            job.cancel()
            runCurrent()
            assertNull(result)
            assertEquals(0, h.dispatcher.pendingCount())
            val second = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            assertIs<VehicleResult.Success<FromVCSECMessage>>(second)
        }

    @Test
    fun rkeSucceedsOnEmptyMessageAfterIntermediateCommandStatus() =
        // FR-048: RKE는 commandStatus 없는 메시지가 최종. 한 요청에 응답 두 개(중간 + 최종)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecEmpty()))
            val result =
                assertIs<VehicleResult.Success<FromVCSECMessage>>(
                    session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT),
                )
            assertEquals(null, result.value.commandStatus)
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun serializesVcsecCommandsOnOneConnection() =
        // FR-049 / SDD §5: 두 번째 VCSEC 명령은 첫 번째가 끝날(응답 유실 → 시간 초과) 때까지 전송되지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, emptyList(), listOf(FakeVehicle.vcsecEmpty()))
            val first =
                async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 100.milliseconds) }
            val second = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            advanceTimeBy(50)
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
            assertFalse(second.isCompleted)
            assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(first.await()).error)
            assertIs<VehicleResult.Success<*>>(second.await())
            assertEquals(2, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }

    @Test
    fun timeoutWhileWaitingForTheVcsecLockIsFailureNotUncertain() =
        // D29: 락 대기 중 만료 = 전송 전 → Failure(Timeout(afterSend = false))
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, emptyList())
            val blocker = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            runCurrent()
            val waiter = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 10.milliseconds)
            assertEquals(VehicleError.Timeout(afterSend = false), assertIs<VehicleResult.Failure>(waiter).error)
            assertTrue(blocker.await() is VehicleResult.Uncertain)
        }

    @Test
    fun deadlineWhileTheWriteIsStillRunningIsUncertain() =
        // H1(2026-11-07 F4): 쓰기 완료가 오기 전에 시간이 다 됐지만 차량은 요청을 받고 실행했다 — "보내기 전"(Failure)이 아니다.
        // Go는 BLE 쓰기에서 ctx를 무시하므로(ble.go:108) 같은 만료가 응답 대기 중에 걸려 PossibleSuccess다(vcsec.go:78)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecEmpty()))
            h.transport.writeGate = CompletableDeferred() // write-with-response의 완료가 오지 않는다
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(result).error)
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun deadlineInTheTransportRetryAfterAFailedWriteIsUncertain() =
        // H1: 실패한 쓰기도 바이트가 닿았을 수 있다 — 전송 재시도는 표시를 지우지 않는다
        // (Go dispatcher.go:454-457은 여기서 PossibleSuccess: false — 취소 가능한 쓰기에 맞춘 의도한 차이, ADR-0010 2026-11-07 개정)
        runTest {
            val h = dispatcherHarness(retryInterval = 4.milliseconds)
            val session = connected(h)
            h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 2.milliseconds)
            assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(result).error)
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun deadlineInTheRetryWaitAfterAVcsecWaitIsFailure() =
        // vcsec.go:94-103: WAIT(Busy)는 실행되지 않았다는 답 — 응답 처리를 마치면 표시를 지우므로 재시도 대기 중 만료는 Failure(Timeout(false)).
        // retryInterval 3ms, timeout 10ms: 시도는 t=0,3,6,9ms에 바로 WAIT를 받고 만료(t=10ms)는 늘 delay 중에 걸린다(결정적)
        runTest {
            val h = dispatcherHarness(retryInterval = 3.milliseconds)
            val session = connected(h)
            h.fake.script(vcsec, *Array(100) { listOf(FakeVehicle.vcsecBusy()) })
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 10.milliseconds)
            assertEquals(VehicleError.Timeout(afterSend = false), assertIs<VehicleResult.Failure>(result).error)
            assertEquals(4, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
            assertEquals(0, h.dispatcher.pendingCount())
        }

    @Test
    fun deadlineWhileWaitingForTheTransportToWriteIsFailure() =
        // 전송 직렬화 대기(BleTransport sendMutex)에서 끝나면 쓰기를 부른 적이 없다 → Failure(Timeout(afterSend = false))
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val writesBefore = h.transport.writesInvoked
            h.transport.preWriteGate = CompletableDeferred()
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            assertEquals(VehicleError.Timeout(afterSend = false), assertIs<VehicleResult.Failure>(result).error)
            assertEquals(writesBefore, h.transport.writesInvoked)
            assertEquals(0, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
            assertEquals(0, h.dispatcher.pendingCount())
        }

    private fun DispatcherHarness.signedCopies() = fake.received.filter { it.signature_data?.AES_GCM_Personalized_data != null }

    @Test
    fun aReplayFaultForTheResentCopyAfterAFailedWriteIsUncertainAndNoNewCopyIsSigned() =
        // 실패로 보인 첫 쓰기가 차량에 닿아 실행됐고 그 응답이 유실됐다. 같은 바이트의 재전송은 같은 counter라 차량이 재전송으로 거부한다
        // (Go verifier.go:217-220 INVALID_TOKEN_OR_COUNTER). 그 fault로 새 counter의 사본을 인가해 보내면 두 번 실행된다(NFR-007) —
        // 새 사본 없이 첫 사본의 응답을 시간 제한까지 기다려 Uncertain. Go는 실패한 BLE 쓰기에서 끝난다(ble.go:121, ADR-0010 2026-11-08 개정)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.dropNextReplies(1)
            h.transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("gatt 133"))
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds)
            assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(result).error)
            assertEquals(1, h.fake.authenticatedPayloads(vcsec).size) // 차량은 한 번만 실행했다
            val copies = h.signedCopies()
            assertEquals(2, copies.size) // 첫 사본과 같은 바이트의 재전송뿐이다
            assertEquals(copies[0], copies[1])
            assertEquals(0, h.dispatcher.pendingCount())
            assertTrue(h.logger.contains("possible duplicate"))
            // 표시는 그 요청에만 남는다: 다음 명령은 재시도할 오류에 평소처럼 새 사본을 보낸다
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy()), listOf(FakeVehicle.vcsecEmpty()))
            assertIs<VehicleResult.Success<FromVCSECMessage>>(
                session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT),
            )
            assertEquals(4, h.signedCopies().size)
        }

    @Test
    fun theFirstCopysReplyArrivingAfterTheReplayFaultIsTheResult() =
        // 같은 요청을 계속 읽으므로 첫 사본의 응답이 재전송 사본의 fault보다 늦게 와도 결과가 된다(같은 routing address·request hash)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.holdNextReplies(1)
            h.transport.enqueueWriteErrorAfterDelivery(VehicleError.TransportError.WriteFailed("gatt 133"))
            val result = async { session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds) }
            advanceTimeBy(100.milliseconds)
            assertFalse(result.isCompleted)
            assertEquals(1, h.fake.releaseHeldReplies())
            assertEquals(FromVCSECMessage(), assertIs<VehicleResult.Success<FromVCSECMessage>>(result.await()).value)
            assertEquals(1, h.fake.authenticatedPayloads(vcsec).size)
            assertEquals(2, h.signedCopies().size)
        }

    @Test
    fun protocolFaultsForTheResentCopyAfterAFailedWriteAreNotTakenAsTheOutcome() =
        // 쓰기 실패 뒤 다시 보낸 사본에 대한 프로토콜 계층의 판정은 중복 사본에 대한 답일 수 있다: 재시도 대상이 아닌 REPEATED_COUNTER,
        // 첫 사본이 자기 키를 지웠다면 나올 UNKNOWN_KEY_ID(KeyNotPaired), INSUFFICIENT_PRIVILEGES도 "실행 안 됨"(Failure)으로 단정하지 않는다.
        // 여기서는 첫 사본이 닿지 않아 차량이 재전송 사본을 처음으로 검증하고 대본으로 답한다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val faults =
                listOf(
                    MessageFault_E.MESSAGEFAULT_ERROR_REPEATED_COUNTER,
                    MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID,
                    MessageFault_E.MESSAGEFAULT_ERROR_INSUFFICIENT_PRIVILEGES,
                )
            for (fault in faults) {
                val before = h.signedCopies().size
                h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
                h.fake.script(vcsec, listOf(FakeVehicle.ScriptedReply(fault = fault)))
                val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 100.milliseconds)
                assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(result, "$fault").error)
                assertEquals(before + 1, h.signedCopies().size, "$fault") // 새 사본 없음
            }
        }

    @Test
    fun anApplicationLevelRejectionAfterAFailedWriteIsStillAFailure() =
        // 페이로드 안의 결과(nominalError)는 그 사본이 재전송 검사를 통과했다는 뜻이다 — 같은 counter의 사본은 하나만 통과하므로
        // (verifier.go:217-220) 다른 사본은 실행되지 않았다. 그래서 쓰기 실패 뒤에도 Failure다(vcsec_test.go TestNominalVSCECError)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK)))
            val result =
                assertIs<VehicleResult.Failure>(
                    session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 1.seconds),
                )
            assertEquals(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK), result.error)
        }

    @Test
    fun aLegacySignedMessageFaultInTheCommandStatusIsNotTheOutcome() =
        // vcsec.go:54-57: commandStatus ERROR + signedMessageStatus는 오류가 아니고(Go는 그 값을 해석하지 않는다, 08-errors §3.8), RKE 종료
        // 판정은 commandStatus가 있으면 계속 읽는다 — 그 안의 counter 거부도 결과가 되지 않고 새 사본도 없다(현재 동작 고정)
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.transport.enqueueWriteError(VehicleError.TransportError.WriteFailed("gatt 133"))
            val counterFault = SignedMessage_information_E.SIGNEDMESSAGE_INFORMATION_FAULT_TOKEN_AND_COUNTER_INVALID
            val legacy =
                FromVCSECMessage(
                    commandStatus =
                        CommandStatus(
                            operationStatus = VcsecOperationStatus.OPERATIONSTATUS_ERROR,
                            signedMessageStatus = SignedMessage_status(signedMessageInformation = counterFault),
                        ),
                )
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecPayload(legacy)))
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 100.milliseconds)
            assertEquals(VehicleError.Timeout(afterSend = true), assertIs<VehicleResult.Uncertain>(result).error)
            assertEquals(1, h.signedCopies().size)
        }

    @Test
    fun infotainmentPingRoundTripsAndErrorsCarryTheReason() =
        // infotainment.go Ping + getCarServerResponse 통합
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val ping = Action(vehicleAction = VehicleAction(ping = Ping(ping_id = 1)))
            assertIs<VehicleResult.Success<*>>(session.infotainment.execute(ping))
            h.fake.script(Domain.DOMAIN_INFOTAINMENT, listOf(FakeVehicle.infotainmentError("car is in drive")))
            val rejected = assertIs<VehicleResult.Failure>(session.infotainment.execute(ping))
            assertEquals(VehicleError.InfotainmentRejected("car is in drive"), rejected.error)
        }
}
