package io.github.smallmiro.teslable.application.dispatcher

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
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
                withAttemptTimeout<Unit>(1.seconds) { setAwaiting ->
                    setAwaiting(true)
                    VehicleResult.Success(Unit)
                }
            assertIs<VehicleResult.Success<Unit>>(result)
        }

    @Test
    fun withAttemptTimeoutIsFailureBeforeSetAwaitingIsCalledTrue() =
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
    fun withAttemptTimeoutIsUncertainAfterSetAwaitingIsCalledTrue() =
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            val result =
                withAttemptTimeout<Unit>(1.milliseconds) { setAwaiting ->
                    setAwaiting(true)
                    neverCompletes.await()
                    VehicleResult.Success(Unit)
                }
            val error = assertIs<VehicleResult.Uncertain>(result).error
            assertEquals(VehicleError.Timeout(afterSend = true), error)
            assertTrue(error.mayHaveSucceeded)
        }

    @Test
    fun withAttemptTimeoutUsesTheLastSetAwaitingValueBeforeTimingOut() =
        // M6: setAwaiting은 마지막 값이 이긴다 — true(응답 대기)로 갔다가 false(다음 재시도 전)로 돌아온 뒤 시간이
        // 초과되면 afterSend는 false다(재시도마다 초기화된다는 것과 같은 성질).
        runTest {
            val neverCompletes = CompletableDeferred<Unit>()
            val result =
                withAttemptTimeout<Unit>(1.milliseconds) { setAwaiting ->
                    setAwaiting(true)
                    setAwaiting(false)
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
                        withAttemptTimeout<Unit>(1.seconds) { setAwaiting ->
                            setAwaiting(true)
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
}
