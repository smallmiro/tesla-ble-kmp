package dev.mirolab.teslable.sample

import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.MessageFault_E
import dev.mirolab.teslable.model.ClosureState
import dev.mirolab.teslable.model.Closures
import dev.mirolab.teslable.model.Gear
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.SleepStatus
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.UserPresence
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * 차 없이 키·제어 탭의 Infotainment 공조·충전 규칙(SFR-025, `05-key-control-tab.md` §Infotainment controls)을 시험한다.
 * 실행 순서, 재전송 없음, `Uncertain` 표시, 늦은 결과 버리기, 오류 종류 이름, 값이 줄에 들어가지 않는 것.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InfotainmentControlTest {
    private fun inputs(
        keyReady: Boolean = true,
        connected: Boolean = true,
        hasKey: Boolean = true,
        enrollment: Enrollment = Enrollment.ENROLLED,
        busy: Boolean = false,
    ) = KeyControlInputs(keyReady, keyLocked = false, vinSaved = true, connected, hasKey, enrollment, busy)

    private fun status(sleep: SleepStatus) =
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
            sleepStatus = sleep,
            userPresence = UserPresence.NOT_PRESENT,
            tonneauPercentOpen = null,
            gear = Gear.PARK,
        )

    // --- 버튼 활성 규칙: 4단계와 같다 ----------------------------------------------------------------------------------------

    @Test
    fun buttonsAreEnabledOnlyWithKeyConnectionEnrollmentAndNoBusyCall() {
        assertTrue(InfotainmentControl.enabled(inputs()))
        assertFalse(InfotainmentControl.enabled(inputs(connected = false)), "no connection")
        assertFalse(InfotainmentControl.enabled(inputs(hasKey = false)), "connected without a key")
        assertFalse(InfotainmentControl.enabled(inputs(keyReady = false)), "key not ready")
        assertFalse(InfotainmentControl.enabled(inputs(enrollment = Enrollment.NOT_ENROLLED)))
        assertFalse(InfotainmentControl.enabled(inputs(enrollment = Enrollment.UNKNOWN)))
        assertFalse(InfotainmentControl.enabled(inputs(enrollment = Enrollment.CHECKING)))
        assertFalse(InfotainmentControl.enabled(inputs(busy = true)), "another call runs")
    }

    @Test
    fun enablingIsExactlyTheStepFourRule() {
        val all =
            listOf(true, false).flatMap { ready ->
                listOf(true, false).flatMap { connected ->
                    listOf(true, false).flatMap { hasKey ->
                        Enrollment.entries.flatMap { e ->
                            listOf(true, false).map { busy -> inputs(ready, connected, hasKey, e, busy) }
                        }
                    }
                }
            }
        all.forEach {
            assertEquals(KeyControlSteps.of(it).control.buttonEnabled, InfotainmentControl.enabled(it), "$it")
            assertEquals(KeyControlSteps.commandBlockedReason(it), InfotainmentControl.blockedReason(it), "$it")
        }
    }

    @Test
    fun noSessionStateIsNeededToEnableTheButtons() {
        // 세션은 탭이 시작한다: 입력에는 세션 항목이 없고, 등록된 키로 연결돼 있으면 바로 켜진다.
        assertNull(InfotainmentControl.blockedReason(inputs()))
    }

    // --- 버튼 표 ------------------------------------------------------------------------------------------------------------

    @Test
    fun buttonTableMatchesTheSpecAndTheIosLabels() {
        assertEquals(
            mapOf(
                InfoCommand.CLIMATE_ON to "공조 켜기",
                InfoCommand.CLIMATE_OFF to "공조 끄기",
                InfoCommand.READ_CLIMATE to "공조 상태 읽기",
                InfoCommand.CHARGE_START to "충전 시작",
                InfoCommand.CHARGE_STOP to "충전 중지",
                InfoCommand.READ_CHARGE to "충전 상태 읽기",
            ),
            InfoCommand.entries.associateWith { it.label },
        )
        assertEquals(setOf(InfoCommand.READ_CLIMATE, InfoCommand.READ_CHARGE), InfoCommand.entries.filter { it.isRead }.toSet())
        assertEquals(StateCategory.CLIMATE, InfoCategory.CLIMATE.stateCategory)
        assertEquals(StateCategory.CHARGE, InfoCategory.CHARGE.stateCategory)
        assertEquals(InfoCategory.CLIMATE, InfoCommand.CLIMATE_OFF.category)
        assertEquals(InfoCategory.CHARGE, InfoCommand.CHARGE_STOP.category)
        assertEquals(
            InfoCommand.entries.size,
            InfoCommand.entries
                .map { it.label }
                .toSet()
                .size,
        )
    }

    // --- 한 번 누르기의 순서 ------------------------------------------------------------------------------------------------

    /** 호출 순서와 횟수를 적는 가짜. */
    private class Fakes(
        val status: VehicleResult<VehicleStatus>,
        val session: VehicleResult<Unit> = VehicleResult.Success(Unit),
        val call: VehicleResult<Unit> = VehicleResult.Success(Unit),
    ) {
        val calls = mutableListOf<String>()

        suspend fun run(): InfoOutcome<Unit> =
            InfotainmentRunner.run(
                readStatus = { status.also { calls += "status" } },
                ensureSession = { session.also { calls += "session" } },
                call = { call.also { calls += "call" } },
                timeSource = TestTimeSource(),
            )
    }

    private val awake = VehicleResult.Success(status(SleepStatus.AWAKE))

    @Test
    fun awakeCarRunsStatusThenSessionThenOneCallAndNothingElse() =
        runTest {
            val f = Fakes(status = awake)

            val out = f.run()

            assertEquals(listOf("status", "session", "call"), f.calls)
            assertEquals(InfoKind.SUCCESS, out.kind)
            assertEquals(status(SleepStatus.AWAKE), out.status)
        }

    @Test
    fun aSleepingCarSendsNothingStartsNoSessionAndNeverWakes() =
        runTest {
            val f = Fakes(status = VehicleResult.Success(status(SleepStatus.ASLEEP)))

            val out = f.run()

            assertEquals(listOf("status"), f.calls)
            assertEquals(InfoKind.NOT_SENT, out.kind)
            assertEquals(NotSentReason.Asleep, out.notSent)
            assertEquals(status(SleepStatus.ASLEEP), out.status, "a successful read still refreshes the status card")
        }

    @Test
    fun unknownSleepIsNotSentEither() =
        runTest {
            val f = Fakes(status = VehicleResult.Success(status(SleepStatus.UNKNOWN)))

            val out = f.run()

            assertEquals(listOf("status"), f.calls)
            assertEquals(NotSentReason.SleepUnknown, out.notSent)
        }

    @Test
    fun aFailedOrUncertainStatusReadIsNotSentAndNamesTheErrorType() =
        runTest {
            val failed = Fakes(status = VehicleResult.Failure(VehicleError.NotConnected))
            val uncertain = Fakes(status = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)))

            val a = failed.run()
            val b = uncertain.run()

            assertEquals(listOf("status"), failed.calls)
            assertEquals(listOf("status"), uncertain.calls)
            assertEquals(NotSentReason.SleepReadFailed("NotConnected"), a.notSent)
            assertEquals(NotSentReason.SleepReadFailed("Timeout(afterSend=true)"), b.notSent)
            assertNull(a.status)
        }

    @Test
    fun aFailedSessionSendsNoCommand() =
        runTest {
            val f = Fakes(status = awake, session = VehicleResult.Failure(VehicleError.NoSession))

            val out = f.run()

            assertEquals(listOf("status", "session"), f.calls)
            assertEquals(InfoKind.NOT_SENT, out.kind)
            assertEquals(NotSentReason.SessionFailed("NoSession"), out.notSent)
        }

    @Test
    fun anUncertainSessionIsAlsoNotSentBecauseNothingWasSentYet() =
        runTest {
            val f = Fakes(status = awake, session = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)))

            val out = f.run()

            assertEquals(listOf("status", "session"), f.calls)
            assertEquals(NotSentReason.SessionFailed("Timeout(afterSend=true)"), out.notSent)
        }

    @Test
    fun keyNotPairedFromTheSessionMeansNotEnrolledAndNothingIsSent() =
        runTest {
            val f = Fakes(status = awake, session = VehicleResult.Failure(VehicleError.KeyNotPaired))

            val out = f.run()

            assertEquals(listOf("status", "session"), f.calls)
            assertEquals(Enrollment.NOT_ENROLLED, out.enrollment)
        }

    @Test
    fun keyNotPairedFromTheCommandAlsoMeansNotEnrolled() =
        runTest {
            val f = Fakes(status = awake, call = VehicleResult.Failure(VehicleError.KeyNotPaired))

            val out = f.run()

            assertEquals(InfoKind.FAILURE, out.kind)
            assertEquals(Enrollment.NOT_ENROLLED, out.enrollment)
        }

    @Test
    fun lockedKeyStoreInTheSessionSaysToUnlockThePhone() =
        runTest {
            val f = Fakes(status = awake, session = VehicleResult.Failure(VehicleError.KeyStoreError.Locked))

            val line = InfotainmentRunner.resultLine(InfoCommand.CLIMATE_ON, f.run())

            assertTrue(line.contains("잠금 해제"), line)
        }

    @Test
    fun uncertainIsNeverResentAndNoGetStateRunsByItself() =
        runTest {
            val f = Fakes(status = awake, call = VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)))

            val out = f.run()

            assertEquals(listOf("status", "session", "call"), f.calls, "exactly one call, no re-query")
            assertEquals(InfoKind.UNCERTAIN, out.kind)
            assertEquals("Timeout(afterSend=true)", out.errorType)
        }

    @Test
    fun aRejectionIsAFailureWithoutTheVehicleText() =
        runTest {
            val rejected = VehicleError.InfotainmentRejected("secret reason text from the car")
            val f = Fakes(status = awake, call = VehicleResult.Failure(rejected))

            val out = f.run()
            val line = InfotainmentRunner.resultLine(InfoCommand.CHARGE_START, out)

            assertEquals(InfoKind.FAILURE, out.kind)
            assertEquals("InfotainmentRejected", out.errorType)
            assertFalse(line.contains("secret"), line)
            assertTrue(line.contains("InfotainmentRejected"), line)
        }

    @Test
    fun aReadReturnsTheValueOnSuccess() =
        runTest {
            val data = VehicleData(climate_state = ClimateState(is_climate_on = true))

            val out =
                InfotainmentRunner.run(
                    readStatus = { awake },
                    ensureSession = { VehicleResult.Success(Unit) },
                    call = { VehicleResult.Success(data) },
                    timeSource = TestTimeSource(),
                )

            assertEquals(InfoKind.SUCCESS, out.kind)
            assertEquals(data, out.value)
        }

    @Test
    fun durationCoversTheWholeTap() =
        runTest {
            val clock = TestTimeSource()
            val out =
                InfotainmentRunner.run(
                    readStatus = { awake.also { clock += 100.milliseconds } },
                    ensureSession = { VehicleResult.Success(Unit).also { clock += 250.milliseconds } },
                    call = { VehicleResult.Success(Unit).also { clock += 1000.milliseconds } },
                    timeSource = clock,
                )

            assertEquals(1350L, out.durationMs)
        }

    // --- 오류 종류 이름(로그에 가는 유일한 오류 정보) -----------------------------------------------------------------------------

    @Test
    fun errorTypesAreLibraryTypeNamesNeverMessages() {
        assertEquals("NoSession", InfotainmentErrors.typeName(VehicleError.NoSession))
        assertEquals("NotConnected", InfotainmentErrors.typeName(VehicleError.NotConnected))
        assertEquals("Locked", InfotainmentErrors.typeName(VehicleError.KeyStoreError.Locked))
        assertEquals("KeyNotPaired", InfotainmentErrors.typeName(VehicleError.KeyNotPaired))
        assertEquals("InfotainmentRejected", InfotainmentErrors.typeName(VehicleError.InfotainmentRejected("x")))
        assertEquals("Timeout(afterSend=true)", InfotainmentErrors.typeName(VehicleError.Timeout(afterSend = true)))
        assertEquals("Timeout(afterSend=false)", InfotainmentErrors.typeName(VehicleError.Timeout(afterSend = false)))
        assertEquals(
            "ResponseTooLarge",
            InfotainmentErrors.typeName(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED)),
        )
        assertEquals("ProtocolFault", InfotainmentErrors.typeName(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_BUSY)))
    }

    // --- 결과 줄 ------------------------------------------------------------------------------------------------------------

    private fun outcome(
        kind: InfoKind,
        type: String? = null,
        notSent: NotSentReason? = null,
        ms: Long = 42,
    ) = InfoOutcome<Unit>(kind, errorType = type, notSent = notSent, durationMs = ms)

    @Test
    fun successLineHasLabelOutcomeAndDurationOnly() {
        val line = InfotainmentRunner.resultLine(InfoCommand.CLIMATE_ON, outcome(InfoKind.SUCCESS))

        assertEquals("공조 켜기: 성공 42 ms", line)
    }

    @Test
    fun uncertainLineShowsTheTypeSaysNotResentAndPointsToTheRead() {
        val line = InfotainmentRunner.resultLine(InfoCommand.CHARGE_STOP, outcome(InfoKind.UNCERTAIN, "Timeout(afterSend=true)", ms = 5012))

        val first = line.lines().first()
        assertEquals("충전 중지: 불확실 (Timeout(afterSend=true)) 5012 ms", first)
        assertTrue(line.contains("다시 보내지 않습니다"), line)
        assertTrue(line.contains("충전 상태 읽기"), line)
    }

    @Test
    fun climateUncertainPointsToTheClimateRead() {
        val line = InfotainmentRunner.resultLine(InfoCommand.CLIMATE_OFF, outcome(InfoKind.UNCERTAIN, "Timeout(afterSend=true)"))

        assertTrue(line.contains("공조 상태 읽기"), line)
    }

    @Test
    fun uncertainReadSaysTheValueIsUnconfirmedAndAskingAgainIsSafe() {
        val line = InfotainmentRunner.resultLine(InfoCommand.READ_CLIMATE, outcome(InfoKind.UNCERTAIN, "ResponseTooLarge"))

        assertTrue(line.startsWith("공조 상태 읽기: 불확실 (ResponseTooLarge)"), line)
        assertFalse(line.contains("다시 보내지 않습니다"), "a read changes nothing, the command wording does not apply")
    }

    @Test
    fun notSentLineExplainsAsleepWithoutWaking() {
        val line = InfotainmentRunner.resultLine(InfoCommand.CLIMATE_ON, outcome(InfoKind.NOT_SENT, notSent = NotSentReason.Asleep))

        assertTrue(line.startsWith("공조 켜기: 보내지 않음"), line)
        assertTrue(line.contains("깨우기"), line)
        assertTrue(line.contains("자동으로 깨우지 않"), line)
    }

    @Test
    fun noLineCarriesAFieldValueOrAnErrorMessage() {
        val kinds = listOf(InfoKind.SUCCESS, InfoKind.UNCERTAIN, InfoKind.FAILURE, InfoKind.NOT_SENT)
        InfoCommand.entries.forEach { cmd ->
            kinds.forEach { kind ->
                val line =
                    InfotainmentRunner.resultLine(
                        cmd,
                        outcome(kind, "Timeout(afterSend=true)", NotSentReason.SessionFailed("NoSession")),
                    )
                assertFalse(line.contains("message"), line)
                assertFalse(Regex("is_climate_on|battery_level|charger_power|reason").containsMatchIn(line), line)
            }
        }
    }

    // --- 읽은 값(화면에만, 줄에는 가지 않는다) ---------------------------------------------------------------------------------

    private val t0 = Instant.parse("2026-11-08T09:00:00Z")

    @Test
    fun climateReadoutShowsOnlyTheNamedFieldsAndAbsentIsADash() {
        val data = VehicleData(climate_state = ClimateState(is_climate_on = true, inside_temp_celsius = 21.5f))

        val r = InfoReading.of(InfoCategory.CLIMATE, data, t0, previous = null)

        assertEquals(
            listOf(
                "is_climate_on" to "true",
                "inside_temp_celsius" to "21.5",
                "driver_temp_setting" to "—",
            ),
            r.fields.map { it.label to it.value },
        )
        assertNull(r.timestampSeconds)
        assertFalse(r.sameTimestampAsPrevious)
    }

    @Test
    fun chargeReadoutKeepsZeroAsZero() {
        val data = VehicleData(charge_state = ChargeState(battery_level = 0, charger_power = 0, charge_port_door_open = false))

        val r = InfoReading.of(InfoCategory.CHARGE, data, t0, previous = null)

        assertEquals(
            listOf(
                "battery_level" to "0",
                "charge_limit_soc" to "—",
                "charger_power" to "0",
                "charge_port_door_open" to "false",
            ),
            r.fields.map { it.label to it.value },
        )
    }

    @Test
    fun anEmptyReplyGivesAllDashes() {
        val r = InfoReading.of(InfoCategory.CHARGE, VehicleData(), t0, previous = null)

        assertTrue(r.fields.all { it.value == "—" })
    }

    @Test
    fun anUnchangedTimestampIsFlaggedAsNotNecessarilyNew() {
        val ts = Instant.ofEpochSecond(1_000)
        val data = VehicleData(climate_state = ClimateState(timestamp = ts))
        val first = InfoReading.of(InfoCategory.CLIMATE, data, t0, previous = null)

        val second = InfoReading.of(InfoCategory.CLIMATE, data, t0.plusSeconds(30), previous = first)
        val moved =
            InfoReading.of(
                InfoCategory.CLIMATE,
                VehicleData(climate_state = ClimateState(timestamp = ts.plusSeconds(5))),
                t0.plusSeconds(60),
                previous = second,
            )

        assertEquals(1_000L, first.timestampSeconds)
        assertFalse(first.sameTimestampAsPrevious)
        assertTrue(second.sameTimestampAsPrevious)
        assertFalse(moved.sameTimestampAsPrevious)
    }

    @Test
    fun aMissingTimestampIsNeverCalledUnchanged() {
        val first = InfoReading.of(InfoCategory.CLIMATE, VehicleData(), t0, previous = null)
        val second = InfoReading.of(InfoCategory.CLIMATE, VehicleData(), t0, previous = first)

        assertFalse(second.sameTimestampAsPrevious)
    }

    // --- 미확인 표시와 늦은 결과 ----------------------------------------------------------------------------------------------

    private val uncertainOutcome = outcome(InfoKind.UNCERTAIN, "Timeout(afterSend=true)")
    private val okOutcome = outcome(InfoKind.SUCCESS)

    @Test
    fun anUncertainCommandMarksOnlyItsCategoryUnconfirmed() {
        val s = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CLIMATE_ON, uncertainOutcome, current = true)

        assertEquals(setOf(InfoCategory.CLIMATE), s.unconfirmed)
    }

    @Test
    fun aLaterSuccessOfTheSameCategoryClearsTheMarker() {
        val marked = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CHARGE_START, uncertainOutcome, current = true)

        val cleared = InfotainmentReducer.afterCommand(marked, InfoCommand.CHARGE_STOP, okOutcome, current = true)
        val other = InfotainmentReducer.afterCommand(marked, InfoCommand.CLIMATE_ON, okOutcome, current = true)

        assertEquals(emptySet(), cleared.unconfirmed)
        assertEquals(setOf(InfoCategory.CHARGE), other.unconfirmed, "another category does not settle it")
    }

    @Test
    fun otherFailuresAndNotSentNeitherSetNorClearTheMarker() {
        val marked = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CLIMATE_ON, uncertainOutcome, current = true)

        val failed =
            InfotainmentReducer.afterCommand(
                marked,
                InfoCommand.CLIMATE_OFF,
                outcome(InfoKind.FAILURE, "InfotainmentRejected"),
                true,
            )
        val notSent = InfotainmentReducer.afterCommand(marked, InfoCommand.CLIMATE_OFF, outcome(InfoKind.NOT_SENT), true)
        val fresh = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CLIMATE_ON, outcome(InfoKind.FAILURE, "NoSession"), true)

        assertEquals(marked.unconfirmed, failed.unconfirmed)
        assertEquals(marked.unconfirmed, notSent.unconfirmed)
        assertEquals(emptySet(), fresh.unconfirmed)
    }

    private fun readOutcome(
        kind: InfoKind,
        data: VehicleData? = null,
    ) = InfoOutcome(kind, value = data, errorType = if (kind == InfoKind.SUCCESS) null else "Timeout(afterSend=true)", durationMs = 7)

    @Test
    fun aSuccessfulReadStoresTheReadoutAndClearsTheMarker() {
        val marked = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CLIMATE_ON, uncertainOutcome, current = true)
        val data = VehicleData(climate_state = ClimateState(is_climate_on = true))

        val s = InfotainmentReducer.afterRead(marked, InfoCategory.CLIMATE, readOutcome(InfoKind.SUCCESS, data), current = true, at = t0)

        assertEquals(emptySet(), s.unconfirmed)
        assertNotNull(s.readings[InfoCategory.CLIMATE])
        assertEquals(
            "true",
            s.readings
                .getValue(InfoCategory.CLIMATE)
                .fields
                .first { it.label == "is_climate_on" }
                .value,
        )
    }

    @Test
    fun anUncertainReadKeepsTheMarkerAndTheLastReadout() {
        val data = VehicleData(climate_state = ClimateState(is_climate_on = true))
        val base = InfotainmentReducer.afterRead(InfoUiState(), InfoCategory.CLIMATE, readOutcome(InfoKind.SUCCESS, data), true, t0)
        val marked = InfotainmentReducer.afterCommand(base, InfoCommand.CLIMATE_OFF, uncertainOutcome, current = true)

        val s = InfotainmentReducer.afterRead(marked, InfoCategory.CLIMATE, readOutcome(InfoKind.UNCERTAIN), current = true, at = t0)

        assertEquals(setOf(InfoCategory.CLIMATE), s.unconfirmed)
        assertEquals(base.readings, s.readings, "last value stays")
    }

    @Test
    fun aSecondReadRemembersThePreviousTimestamp() {
        val ts = Instant.ofEpochSecond(2_000)
        val data = VehicleData(charge_state = ChargeState(timestamp = ts))
        val one = InfotainmentReducer.afterRead(InfoUiState(), InfoCategory.CHARGE, readOutcome(InfoKind.SUCCESS, data), true, t0)

        val two = InfotainmentReducer.afterRead(one, InfoCategory.CHARGE, readOutcome(InfoKind.SUCCESS, data), true, t0.plusSeconds(10))

        assertTrue(two.readings.getValue(InfoCategory.CHARGE).sameTimestampAsPrevious)
    }

    @Test
    fun aResultFromAnOldConnectionIsDroppedCompletely() {
        val marked = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CLIMATE_ON, uncertainOutcome, current = true)
        val data = VehicleData(charge_state = ChargeState(battery_level = 50))

        val lateCommand = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CHARGE_START, uncertainOutcome, current = false)
        val lateSuccess = InfotainmentReducer.afterCommand(marked, InfoCommand.CLIMATE_OFF, okOutcome, current = false)
        val lateRead = InfotainmentReducer.afterRead(InfoUiState(), InfoCategory.CHARGE, readOutcome(InfoKind.SUCCESS, data), false, t0)

        assertEquals(InfoUiState(), lateCommand, "no marker from a dropped Uncertain")
        assertEquals(marked, lateSuccess, "a dropped success does not clear a marker")
        assertEquals(InfoUiState(), lateRead, "no readout from a dropped read")
    }

    @Test
    fun droppedLineNamesTheCommandOnlyAndSaysToReadAgainForACommand() {
        val command = InfotainmentRunner.droppedLine(InfoCommand.CHARGE_START)
        val read = InfotainmentRunner.droppedLine(InfoCommand.READ_CHARGE)

        assertTrue(command.startsWith("충전 시작: 연결이 바뀌어 결과를 버렸습니다"), command)
        assertTrue(command.contains("차량이 실행했을 수 있"), command)
        assertTrue(read.startsWith("충전 상태 읽기: 연결이 바뀌어 결과를 버렸습니다"), read)
        assertFalse(read.contains("실행했을 수"), read)
    }

    // --- 결과가 아직 "그 연결·그 키"의 것인가 -----------------------------------------------------------------------------

    @Test
    fun aResultIsCurrentOnlyForTheSameVehicleAndTheSameGeneration() {
        val vehicle = Any()

        assertTrue(InfotainmentGuard.isCurrent(vehicle, vehicle, startGeneration = 3, nowGeneration = 3))
        assertFalse(InfotainmentGuard.isCurrent(vehicle, Any(), 3, 3), "another connection")
        assertFalse(InfotainmentGuard.isCurrent(vehicle, null, 3, 3), "disconnected")
        assertFalse(InfotainmentGuard.isCurrent(null, null, 3, 3), "no connection at the start either")
    }

    @Test
    fun aKeyChangeOnTheSameConnectionMakesAnInFlightResultStale() {
        val vehicle = Any()
        val generation = InfoGeneration()
        val started = generation.value

        generation.bump() // observe(): the key (public key) changed, same Vehicle instance

        assertFalse(InfotainmentGuard.isCurrent(vehicle, vehicle, started, generation.value))
    }

    @Test
    fun aStaleResultLeavesTheNewKeysStateAlone() {
        val vehicle = Any()
        val generation = InfoGeneration()
        val started = generation.value
        generation.bump()
        val current = InfotainmentGuard.isCurrent(vehicle, vehicle, started, generation.value)
        val fresh = InfoUiState.AFTER_CONNECTION_CHANGE
        val data = VehicleData(climate_state = ClimateState(is_climate_on = true))

        val afterCommand = InfotainmentReducer.afterCommand(fresh, InfoCommand.CLIMATE_ON, uncertainOutcome, current)
        val afterRead = InfotainmentReducer.afterRead(fresh, InfoCategory.CLIMATE, readOutcome(InfoKind.SUCCESS, data), current, t0)

        assertEquals(fresh, afterCommand)
        assertEquals(fresh, afterRead)
    }

    @Test
    fun everyBumpGivesANewGeneration() {
        val generation = InfoGeneration()

        val seen = (1..3).map { generation.bump() } + generation.value

        assertEquals(seen.toSet().size, 3, "three bumps, three new values")
        assertEquals(seen.last(), seen[2])
    }

    @Test
    fun aConnectionChangeStartsFromAnEmptyState() {
        val s = InfotainmentReducer.afterCommand(InfoUiState(), InfoCommand.CLIMATE_ON, uncertainOutcome, current = true)

        assertEquals(InfoUiState(), InfoUiState.AFTER_CONNECTION_CHANGE)
        assertTrue(s.unconfirmed.isNotEmpty())
    }
}
