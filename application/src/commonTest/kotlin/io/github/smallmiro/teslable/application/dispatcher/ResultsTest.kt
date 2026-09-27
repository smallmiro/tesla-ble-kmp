package io.github.smallmiro.teslable.application.dispatcher

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [retryWhileRetriable]와 [withAttemptTimeout] 직접 테스트(컨트롤러 판정 R1). `SendWithRetry.send`와
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
}
