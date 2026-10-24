package dev.mirolab.teslable.sample

import dev.mirolab.teslable.model.ClosureState
import dev.mirolab.teslable.model.Closures
import dev.mirolab.teslable.model.Gear
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.SleepStatus
import dev.mirolab.teslable.model.UserPresence
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import dev.mirolab.teslable.model.WhitelistEntry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 차 없이 키·제어 탭의 규칙(SFR-018)을 시험한다. 단계 표는 `05-key-control-tab.md` §Steps and enabling rules 의 행마다 하나. */
class KeyControlTest {
    private fun inputs(
        keyReady: Boolean = true,
        keyLocked: Boolean = false,
        vinSaved: Boolean = true,
        connected: Boolean = true,
        enrollment: Enrollment = Enrollment.ENROLLED,
        busy: Boolean = false,
    ) = KeyControlInputs(keyReady, keyLocked, vinSaved, connected, enrollment, busy)

    // --- 1 키 -----------------------------------------------------------------------------------------------------------

    @Test
    fun keyStepIsDoneWhenTheKeyIsReady() {
        val step = KeyControlSteps.of(inputs(keyReady = true)).key

        assertEquals(StepStatus.DONE, step.status)
        assertTrue(step.buttonEnabled)
    }

    @Test
    fun keyStepIsTodoWithoutKeyAndNotLocked() {
        val step = KeyControlSteps.of(inputs(keyReady = false)).key

        assertEquals(StepStatus.TODO, step.status)
        assertTrue(step.buttonEnabled)
    }

    @Test
    fun keyStepIsBlockedWhenThePhoneLockBlocksTheKey() {
        val step = KeyControlSteps.of(inputs(keyReady = false, keyLocked = true)).key

        assertEquals(StepStatus.BLOCKED, step.status)
        assertEquals("폰을 잠금 해제한 뒤 다시 누르세요", step.hint)
        assertTrue(step.buttonEnabled) // 누르면 다시 읽는다
    }

    @Test
    fun keyButtonIsDisabledWhileBusy() {
        assertFalse(KeyControlSteps.of(inputs(keyReady = false, busy = true)).key.buttonEnabled)
    }

    // --- 2 연결 ---------------------------------------------------------------------------------------------------------

    @Test
    fun connectStepIsDoneWhenConnected() {
        val step = KeyControlSteps.of(inputs(connected = true)).connect

        assertEquals(StepStatus.DONE, step.status)
        assertFalse(step.buttonEnabled) // 이미 연결됨
    }

    @Test
    fun connectStepIsBlockedWithoutSavedVin() {
        val step = KeyControlSteps.of(inputs(vinSaved = false, connected = false)).connect

        assertEquals(StepStatus.BLOCKED, step.status)
        assertEquals("Live 탭에서 VIN을 저장하세요", step.hint)
        assertFalse(step.buttonEnabled)
    }

    @Test
    fun connectStepIsTodoAndEnabledWithVinWhenNotConnected() {
        val step = KeyControlSteps.of(inputs(vinSaved = true, connected = false)).connect

        assertEquals(StepStatus.TODO, step.status)
        assertTrue(step.buttonEnabled)
    }

    @Test
    fun connectButtonIsDisabledWhileBusy() {
        assertFalse(KeyControlSteps.of(inputs(connected = false, busy = true)).connect.buttonEnabled)
    }

    // --- 3 등록 ---------------------------------------------------------------------------------------------------------

    @Test
    fun enrollStepIsDoneWhenEnrolled() {
        val step = KeyControlSteps.of(inputs(enrollment = Enrollment.ENROLLED)).enroll

        assertEquals(StepStatus.DONE, step.status)
        assertFalse(step.buttonEnabled)
    }

    @Test
    fun enrollStepIsBlockedWithoutReadyKey() {
        val step = KeyControlSteps.of(inputs(keyReady = false, enrollment = Enrollment.UNKNOWN)).enroll

        assertEquals(StepStatus.BLOCKED, step.status)
        assertEquals("1·2단계를 먼저 끝내세요", step.hint)
        assertFalse(step.buttonEnabled)
    }

    @Test
    fun enrollStepIsBlockedWhenNotConnected() {
        val step = KeyControlSteps.of(inputs(connected = false, enrollment = Enrollment.UNKNOWN)).enroll

        assertEquals(StepStatus.BLOCKED, step.status)
        assertFalse(step.buttonEnabled)
    }

    @Test
    fun enrollStepIsTodoAndEnabledWhenNotEnrolledOrUnknown() {
        for (e in listOf(Enrollment.UNKNOWN, Enrollment.NOT_ENROLLED)) {
            val step = KeyControlSteps.of(inputs(enrollment = e)).enroll

            assertEquals(StepStatus.TODO, step.status, "enrollment=$e")
            assertTrue(step.buttonEnabled, "enrollment=$e")
        }
    }

    @Test
    fun enrollButtonIsDisabledWhileCheckingAndStepStaysTodo() {
        val steps = KeyControlSteps.of(inputs(enrollment = Enrollment.CHECKING))

        assertEquals(StepStatus.TODO, steps.enroll.status)
        assertFalse(steps.enroll.buttonEnabled)
        assertFalse(steps.checkEnabled)
    }

    @Test
    fun enrollButtonIsDisabledWhileBusy() {
        assertFalse(KeyControlSteps.of(inputs(enrollment = Enrollment.NOT_ENROLLED, busy = true)).enroll.buttonEnabled)
    }

    @Test
    fun refreshButtonNeedsConnectionAndNoBusyCall() {
        assertTrue(KeyControlSteps.refreshEnabled(connected = true, busy = false))
        assertFalse(KeyControlSteps.refreshEnabled(connected = false, busy = false))
        assertFalse(KeyControlSteps.refreshEnabled(connected = true, busy = true))
        assertFalse(KeyControlSteps.refreshEnabled(connected = false, busy = true))
    }

    @Test
    fun checkButtonFollowsKeyConnectionAndBusyButNotEnrollment() {
        assertTrue(KeyControlSteps.of(inputs(enrollment = Enrollment.ENROLLED)).checkEnabled) // 다시 확인할 수 있다
        assertTrue(KeyControlSteps.of(inputs(enrollment = Enrollment.UNKNOWN)).checkEnabled)
        assertFalse(KeyControlSteps.of(inputs(connected = false, enrollment = Enrollment.UNKNOWN)).checkEnabled)
        assertFalse(KeyControlSteps.of(inputs(keyReady = false, enrollment = Enrollment.UNKNOWN)).checkEnabled)
        assertFalse(KeyControlSteps.of(inputs(busy = true)).checkEnabled)
    }

    // --- 4 제어 ---------------------------------------------------------------------------------------------------------

    @Test
    fun controlStepIsBlockedUntilEnrolled() {
        for (e in listOf(Enrollment.UNKNOWN, Enrollment.CHECKING, Enrollment.NOT_ENROLLED)) {
            val step = KeyControlSteps.of(inputs(enrollment = e)).control

            assertEquals(StepStatus.BLOCKED, step.status, "enrollment=$e")
            assertEquals("키를 차량에 등록하면 버튼이 켜집니다", step.hint)
            assertFalse(step.buttonEnabled, "enrollment=$e")
        }
    }

    @Test
    fun controlButtonsAreEnabledWhenKeyConnectedEnrolledAndNotBusy() {
        val step = KeyControlSteps.of(inputs(enrollment = Enrollment.ENROLLED)).control

        assertEquals(StepStatus.TODO, step.status) // 4단계는 끝나는 일이 없다
        assertTrue(step.buttonEnabled)
    }

    @Test
    fun controlButtonsAreDisabledWhileBusy() {
        assertFalse(KeyControlSteps.of(inputs(enrollment = Enrollment.ENROLLED, busy = true)).control.buttonEnabled)
    }

    @Test
    fun controlButtonsAreDisabledWithoutConnectionOrKeyEvenIfEnrolled() {
        assertFalse(KeyControlSteps.of(inputs(connected = false, enrollment = Enrollment.ENROLLED)).control.buttonEnabled)
        assertFalse(KeyControlSteps.of(inputs(keyReady = false, enrollment = Enrollment.ENROLLED)).control.buttonEnabled)
    }

    // --- 단계 안내 한 줄은 비어 있지 않다 ----------------------------------------------------------------------------------

    @Test
    fun everyStepAlwaysHasAHint() {
        val flags = listOf(true, false)
        val all =
            flags.flatMap { key ->
                flags.flatMap { locked ->
                    flags.flatMap { vin ->
                        flags.flatMap { conn -> Enrollment.entries.map { e -> inputs(key, locked, vin, conn, e, busy = false) } }
                    }
                }
            }
        for (i in all) {
            val s = KeyControlSteps.of(i)
            listOf(s.key, s.connect, s.enroll, s.control).forEach { assertTrue(it.hint.isNotBlank(), "$i") }
        }
    }

    // --- 명령 실행 규칙 ---------------------------------------------------------------------------------------------------

    private val statusOk =
        VehicleStatus(
            lockState = LockState.LOCKED,
            closures =
                Closures(
                    ClosureState.CLOSED,
                    ClosureState.CLOSED,
                    ClosureState.CLOSED,
                    ClosureState.CLOSED,
                    ClosureState.CLOSED,
                    ClosureState.CLOSED,
                    ClosureState.CLOSED,
                    ClosureState.CLOSED,
                ),
            sleepStatus = SleepStatus.AWAKE,
            userPresence = UserPresence.NOT_PRESENT,
            tonneauPercentOpen = null,
            gear = Gear.PARK,
        )

    /** 호출 횟수를 세는 가짜. */
    private class Fakes(
        val session: VehicleResult<Unit> = VehicleResult.Success(Unit),
        val command: VehicleResult<Unit> = VehicleResult.Success(Unit),
        val requery: VehicleResult<VehicleStatus>,
    ) {
        val calls = mutableListOf<String>()

        suspend fun run(): CommandOutcome =
            KeyControlRunner.run(
                ensureSession = { session.also { calls += "session" } },
                command = { command.also { calls += "command" } },
                requery = { requery.also { calls += "requery" } },
            )
    }

    @Test
    fun successSendsTheCommandOnceThenRequeries() =
        runTest {
            val f = Fakes(requery = VehicleResult.Success(statusOk))

            val out = f.run()

            assertEquals(listOf("session", "command", "requery"), f.calls)
            assertEquals("성공", out.message)
            assertEquals(statusOk, out.status)
            assertNull(out.enrollment)
        }

    @Test
    fun sessionFailureDoesNotSendTheCommandNorRequery() =
        runTest {
            val f = Fakes(session = VehicleResult.Failure(VehicleError.NotConnected), requery = VehicleResult.Success(statusOk))

            val out = f.run()

            assertEquals(listOf("session"), f.calls)
            assertNull(out.status)
            assertNull(out.enrollment)
            assertTrue(out.message.startsWith("세션 실패"), out.message)
        }

    @Test
    fun sessionUncertainIsAlsoNotSentBecauseNothingWasSentYet() =
        runTest {
            val f =
                Fakes(
                    session = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)),
                    requery = VehicleResult.Success(statusOk),
                )

            val out = f.run()

            assertEquals(listOf("session"), f.calls)
            assertTrue(out.message.startsWith("세션 실패"), out.message)
            assertNull(out.status)
        }

    @Test
    fun keyNotPairedFromTheSessionMeansNotEnrolled() =
        runTest {
            val f = Fakes(session = VehicleResult.Failure(VehicleError.KeyNotPaired), requery = VehicleResult.Success(statusOk))

            val out = f.run()

            assertEquals(listOf("session"), f.calls)
            assertEquals(Enrollment.NOT_ENROLLED, out.enrollment)
            assertTrue(out.message.contains("등록"), out.message)
        }

    @Test
    fun lockedKeyStoreInTheSessionSaysToUnlockThePhone() =
        runTest {
            val f =
                Fakes(session = VehicleResult.Failure(VehicleError.KeyStoreError.Locked), requery = VehicleResult.Success(statusOk))

            val out = f.run()

            assertEquals(listOf("session"), f.calls)
            assertNull(out.enrollment) // 잠금은 등록 여부와 상관없다
            assertTrue(out.message.contains("잠금 해제"), out.message)
        }

    @Test
    fun failureIsShownAndTheCommandIsNotResent() =
        runTest {
            val f =
                Fakes(
                    command = VehicleResult.Failure(VehicleError.InsufficientPrivileges),
                    requery = VehicleResult.Success(statusOk),
                )

            val out = f.run()

            assertEquals(listOf("session", "command", "requery"), f.calls)
            assertEquals("실패: ${VehicleError.InsufficientPrivileges.message}", out.message)
        }

    @Test
    fun uncertainIsNeverResentAndAlwaysRequeried() =
        runTest {
            val error = VehicleError.Timeout(afterSend = true)
            val f = Fakes(command = VehicleResult.Uncertain(error), requery = VehicleResult.Success(statusOk))

            val out = f.run()

            assertEquals(1, f.calls.count { it == "command" })
            assertEquals(listOf("session", "command", "requery"), f.calls)
            assertEquals("불확실: ${error.message}", out.message)
            assertEquals(statusOk, out.status)
        }

    @Test
    fun aFailedRequeryKeepsTheCommandResultAndReportsTheRequeryProblem() =
        runTest {
            val f =
                Fakes(
                    command = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)),
                    requery = VehicleResult.Failure(VehicleError.NotConnected),
                )

            val out = f.run()

            assertNull(out.status)
            assertNotNull(out.requeryMessage)
            assertTrue(out.requeryMessage.orEmpty().startsWith("재조회 실패"), out.requeryMessage)
        }

    @Test
    fun requeryRunsAfterEveryCommandOutcome() =
        runTest {
            val commandResults =
                listOf(
                    VehicleResult.Success(Unit),
                    VehicleResult.Failure(VehicleError.NoSession),
                    VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)),
                )
            for (c in commandResults) {
                val f = Fakes(command = c, requery = VehicleResult.Success(statusOk))

                f.run()

                assertEquals("requery", f.calls.last(), "command=$c")
                assertEquals(1, f.calls.count { it == "command" })
            }
        }

    // --- 등록 확인(listKeys) ---------------------------------------------------------------------------------------------

    private fun keyBytes(seed: Int) = PublicKeyBytes(ByteArray(PublicKeyBytes.SIZE) { if (it == 0) 0x04 else (seed + it).toByte() })

    private fun entry(
        slot: Int,
        key: PublicKeyBytes?,
    ) = WhitelistEntry(slot, key, null, KeyRole.OWNER, KeyFormFactor.ANDROID_DEVICE)

    @Test
    fun listKeysWithOwnPublicKeyIsEnrolled() {
        val mine = keyBytes(1)

        val e = KeyControlRunner.enrollmentFrom(VehicleResult.Success(listOf(entry(0, keyBytes(9)), entry(1, keyBytes(1)))), mine)

        assertEquals(Enrollment.ENROLLED, e)
    }

    @Test
    fun listKeysWithoutOwnPublicKeyIsNotEnrolled() {
        val e = KeyControlRunner.enrollmentFrom(VehicleResult.Success(listOf(entry(0, keyBytes(9)), entry(1, null))), keyBytes(1))

        assertEquals(Enrollment.NOT_ENROLLED, e)
    }

    @Test
    fun emptyKeyListIsNotEnrolled() {
        assertEquals(Enrollment.NOT_ENROLLED, KeyControlRunner.enrollmentFrom(VehicleResult.Success(emptyList()), keyBytes(1)))
    }

    @Test
    fun uncertainOrFailedKeyListIsUnknown() {
        val mine = keyBytes(1)

        assertEquals(
            Enrollment.UNKNOWN,
            KeyControlRunner.enrollmentFrom(VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)), mine),
        )
        assertEquals(Enrollment.UNKNOWN, KeyControlRunner.enrollmentFrom(VehicleResult.Failure(VehicleError.NotConnected), mine))
    }

    @Test
    fun publicKeysMatchByContentAndNeverWhenMissing() {
        assertTrue(keyBytes(1).isSameKeyAs(keyBytes(1)))
        assertFalse(keyBytes(1).isSameKeyAs(keyBytes(2)))
        assertFalse(null.isSameKeyAs(null))
        assertFalse(keyBytes(1).isSameKeyAs(null))
        assertFalse(null.isSameKeyAs(keyBytes(1)))
    }

    // --- 연결마다 한 번 자동 확인 ------------------------------------------------------------------------------------------

    @Test
    fun autoCheckRunsOncePerNewConnectionWithAReadyKey() {
        val a = Any()
        val b = Any()

        assertTrue(KeyControlSteps.needsAutoCheck(connection = a, keyReady = true, checked = null))
        assertFalse(KeyControlSteps.needsAutoCheck(connection = a, keyReady = true, checked = a))
        assertTrue(KeyControlSteps.needsAutoCheck(connection = b, keyReady = true, checked = a)) // 재연결 = 새 연결
    }

    @Test
    fun autoCheckWaitsForTheKeyAndNeedsAConnection() {
        assertFalse(KeyControlSteps.needsAutoCheck(connection = Any(), keyReady = false, checked = null))
        assertFalse(KeyControlSteps.needsAutoCheck(connection = null, keyReady = true, checked = null))
    }

    // --- 버튼 표 ---------------------------------------------------------------------------------------------------------

    @Test
    fun onlyFrunkAndRemoteDriveAskForConfirmation() {
        val asking = KeyCommand.entries.filter { it.confirm != null }.toSet()

        assertEquals(setOf(KeyCommand.OPEN_FRUNK, KeyCommand.REMOTE_DRIVE), asking)
        assertTrue(
            KeyCommand.OPEN_FRUNK.confirm
                .orEmpty()
                .contains("원격으로 닫을 수 없습니다"),
        )
    }

    @Test
    fun otherModelsGroupsHoldAutoSecureAndTonneauOnly() {
        val other = KeyCommand.entries.filter { it.group.collapsed }.toSet()

        assertEquals(
            setOf(KeyCommand.AUTO_SECURE, KeyCommand.OPEN_TONNEAU, KeyCommand.CLOSE_TONNEAU, KeyCommand.STOP_TONNEAU),
            other,
        )
    }

    @Test
    fun everyButtonLabelIsUnique() {
        assertEquals(
            KeyCommand.entries.size,
            KeyCommand.entries
                .map { it.label }
                .toSet()
                .size,
        )
    }
}
