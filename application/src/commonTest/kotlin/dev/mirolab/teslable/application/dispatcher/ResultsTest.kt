package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.shouldRetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [retryWhileRetriable]와 [withAttemptTimeout] 직접 테스트. `SendWithRetry.send`와
 * `VehicleSession.startSession`이 공유하는 재시도·시간초과 로직이라 여기서 한 번에 고정한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultsTest {
    @Test
    fun retryWhileRetriableReturnsFirstSuccessWithoutRetrying() =
        runTest {
            var calls = 0
            val result =
                retryWhileRetriable(1.milliseconds) {
                    calls++
                    VehicleResult.Success(Unit)
                }
            assertIs<VehicleResult.Success<Unit>>(result)
            assertEquals(1, calls)
        }

    @Test
    fun retryWhileRetriableStopsImmediatelyOnNonRetriableError() =
        runTest {
            var calls = 0
            val result =
                retryWhileRetriable(1.milliseconds) {
                    calls++
                    VehicleResult.Failure(VehicleError.NoSession)
                }
            assertEquals(VehicleError.NoSession, assertIs<VehicleResult.Failure>(result).error)
            assertEquals(1, calls)
        }

    @Test
    fun retryWhileRetriableReturnsLockedWithoutRetrying() =
        // FR-037(#96): Locked는 temporary(잠금이 풀린 뒤 다시 부르면 된다)여서 shouldRetry()가 true지만, 잠금 해제는 사용자 몫이다.
        // 되풀이하면 시간 제한 뒤 Timeout으로 바뀌어 앱이 원인을 모른다 — 첫 결과를 그대로 돌려준다.
        runTest {
            var calls = 0
            val result =
                withTimeoutOrNull(10.milliseconds) {
                    retryWhileRetriable(1.milliseconds) {
                        calls++
                        VehicleResult.Failure(VehicleError.KeyStoreError.Locked)
                    }
                }
            assertTrue(VehicleError.KeyStoreError.Locked.shouldRetry())
            assertEquals(VehicleError.KeyStoreError.Locked, assertIs<VehicleResult.Failure>(result).error)
            assertEquals(1, calls)
        }

    @Test
    fun retryWhileRetriableWaitsIntervalBetweenRetriableAttempts() =
        runTest {
            var calls = 0
            val start = testTimeSource.markNow()
            val result =
                retryWhileRetriable(3.milliseconds) {
                    calls++
                    if (calls < 3) VehicleResult.Failure(VehicleError.Busy) else VehicleResult.Success(Unit)
                }
            assertIs<VehicleResult.Success<Unit>>(result)
            assertEquals(3, calls)
            assertEquals(6.milliseconds, start.elapsedNow())
        }

    @Test
    fun withAttemptTimeoutReturnsBlockResultWhenItCompletesInTime() =
        runTest {
            val result =
                withAttemptTimeout<Unit>(1.seconds) { setAfterSend ->
                    setAfterSend(true)
                    VehicleResult.Success(Unit)
                }
            assertIs<VehicleResult.Success<Unit>>(result)
        }

    @Test
    fun withAttemptTimeoutIsFailureBeforeSetAfterSendIsCalledTrue() =
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            val result =
                withAttemptTimeout<Unit>(1.milliseconds) {
                    neverCompletes.await()
                    VehicleResult.Success(Unit)
                }
            val error = assertIs<VehicleResult.Failure>(result).error
            assertEquals(VehicleError.Timeout(afterSend = false), error)
            assertFalse(error.mayHaveSucceeded)
        }

    @Test
    fun withAttemptTimeoutIsUncertainAfterSetAfterSendIsCalledTrue() =
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            val result =
                withAttemptTimeout<Unit>(1.milliseconds) { setAfterSend ->
                    setAfterSend(true)
                    neverCompletes.await()
                    VehicleResult.Success(Unit)
                }
            val error = assertIs<VehicleResult.Uncertain>(result).error
            assertEquals(VehicleError.Timeout(afterSend = true), error)
            assertTrue(error.mayHaveSucceeded)
        }

    @Test
    fun withAttemptTimeoutUsesTheLastSetAfterSendValueBeforeTimingOut() =
        // M6: setAfterSend는 마지막 값이 이긴다 — true(응답 대기)로 갔다가 false(다음 재시도 전)로 돌아온 뒤 시간이
        // 초과되면 afterSend는 false다(재시도마다 초기화된다는 것과 같은 성질).
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            val result =
                withAttemptTimeout<Unit>(1.milliseconds) { setAfterSend ->
                    setAfterSend(true)
                    setAfterSend(false)
                    neverCompletes.await()
                    VehicleResult.Success(Unit)
                }
            val error = assertIs<VehicleResult.Failure>(result).error
            assertEquals(VehicleError.Timeout(afterSend = false), error)
        }

    @Test
    fun withAttemptTimeoutPropagatesOuterCancellationInsteadOfReturningAValue() =
        // M1(2차 리뷰): withAttemptTimeout이 자신의 withTimeoutOrNull이 만드는 내부 타임아웃과, 호출자가 바깥에서 보낸
        // job.cancel()을 구분하지 못하면 취소가 Failure(Timeout(...)) 값으로 둔갑한다. job.getCompletionExceptionOrNull()은
        // job.cancel() 뒤에는 항상 CancellationException이므로(블록이 취소를 삼켜 값으로 바꿔 정상 반환해도 Job 자체는
        // 여전히 Cancelled로 끝난다) 그것만으로는 아무 것도 증명하지 못한다 — 블록의 반환값이 실제로 대입되는지를 본다.
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            var result: VehicleResult<Unit>? = null
            val job =
                launch {
                    result =
                        withAttemptTimeout<Unit>(1.seconds) { setAfterSend ->
                            setAfterSend(true)
                            neverCompletes.await()
                            VehicleResult.Success(Unit)
                        }
                }
            runCurrent()
            job.cancel()
            runCurrent()
            assertTrue(job.isCancelled)
            assertNull(result)
        }

    @Test
    fun afterAFailedWriteOnlyRetryTriggersAndProtocolVerdictsAreInconclusive() {
        // ADR-0010 2026-11-08: 새 시도를 시작할 오류와 메시지에 대한 프로토콜 계층 판정은 중복 사본에 대한 답일 수 있다(결과로 받지 않음).
        // mayHaveSucceeded 오류(RESPONSE_MTU_EXCEEDED, 해석할 수 없는 응답)는 이미 Uncertain이라 결과를 정하고, 페이로드 안의 애플리케이션
        // 결과는 그 사본이 counter 검사를 통과했다는 뜻이라 결과를 정한다
        val inconclusive =
            listOf(
                VehicleError.Busy,
                VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER),
                VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH),
                VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_REPEATED_COUNTER),
                VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_COMMAND),
                VehicleError.KeyNotPaired,
                VehicleError.InsufficientPrivileges,
                VehicleError.UnknownFault(rawCode = 99),
                VehicleError.TransportError.WriteFailed("gatt 133"),
            )
        for (error in inconclusive) assertTrue(error.isInconclusiveAfterFailedWrite(), "$error")
        val conclusive =
            listOf(
                VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED),
                VehicleError.BadResponse("vcsec: undecodable response", mayHaveSucceeded = true),
                VehicleError.Timeout(afterSend = true),
                VehicleError.VcsecRejected(GenericError_E.GENERICERROR_VEHICLE_NOT_IN_PARK),
                VehicleError.KeychainRejected(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL),
                VehicleError.InfotainmentRejected("car is in drive"),
                VehicleError.UnknownResponse,
                VehicleError.KeyStoreError.Locked,
                VehicleError.TransportError.Disconnected,
            )
        for (error in conclusive) assertFalse(error.isInconclusiveAfterFailedWrite(), "$error")
    }
}
