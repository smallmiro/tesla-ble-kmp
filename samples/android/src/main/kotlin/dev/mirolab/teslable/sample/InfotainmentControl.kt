package dev.mirolab.teslable.sample

import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.MessageFault_E
import dev.mirolab.teslable.model.SleepStatus
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import java.time.Instant
import kotlin.time.TimeSource

// 키·제어 탭의 Infotainment 공조·충전(SFR-025)의 순수 규칙. 규칙의 정본: `samples/documents/md/05-key-control-tab.md` §Infotainment controls.
// 라이브러리 호출 순서의 정본: `documents/md/15-lib-commands.md` §Call order, `14-lib-reading-state.md` A8.2, `16-lib-errors.md` §Per-command re-query.
// 값(`VehicleData`)은 화면에만 가고 줄(로그)에는 가지 않는다(FR-112).

private const val READ_CLIMATE_LABEL = "공조 상태 읽기"
private const val READ_CHARGE_LABEL = "충전 상태 읽기"
private const val NO_VALUE = "—"

/** 명령이 바꾸는 상태 범주. 명령 뒤 재조회와 읽기 버튼이 `getState(stateCategory)` 를 쓴다. */
enum class InfoCategory(
    val stateCategory: StateCategory,
    val title: String,
    val readLabel: String,
    val unconfirmedText: String,
) {
    CLIMATE(StateCategory.CLIMATE, "공조", READ_CLIMATE_LABEL, "공조 결과 미확인"),
    CHARGE(StateCategory.CHARGE, "충전", READ_CHARGE_LABEL, "충전 결과 미확인"),
}

/** 버튼 표(`05-key-control-tab.md` §Infotainment controls). [isRead]는 `getState` 읽기 버튼이고 나머지는 명령이다. */
enum class InfoCommand(
    val label: String,
    val category: InfoCategory,
    val isRead: Boolean = false,
) {
    CLIMATE_ON("공조 켜기", InfoCategory.CLIMATE),
    CLIMATE_OFF("공조 끄기", InfoCategory.CLIMATE),
    READ_CLIMATE(READ_CLIMATE_LABEL, InfoCategory.CLIMATE, isRead = true),
    CHARGE_START("충전 시작", InfoCategory.CHARGE),
    CHARGE_STOP("충전 중지", InfoCategory.CHARGE),
    READ_CHARGE(READ_CHARGE_LABEL, InfoCategory.CHARGE, isRead = true),
}

/** 버튼 활성 규칙: 4단계 규칙과 같다. 세션은 누르면 시작하므로 입력에 세션 항목이 없다. */
object InfotainmentControl {
    fun enabled(i: KeyControlInputs): Boolean = KeyControlSteps.of(i).control.buttonEnabled

    /** 지금 보내도 되는가. 안 되면 이유 한 줄(VCSEC 버튼과 같은 문구), 되면 `null`. */
    fun blockedReason(i: KeyControlInputs): String? = KeyControlSteps.commandBlockedReason(i)
}

/** 한 번 누르기의 끝. */
enum class InfoKind { SUCCESS, UNCERTAIN, FAILURE, NOT_SENT }

/** 차량에 보내지 않고 멈춘 이유. 오류는 **종류 이름**만 담는다. */
sealed interface NotSentReason {
    data object Asleep : NotSentReason

    data object SleepUnknown : NotSentReason

    data class SleepReadFailed(
        val errorType: String,
    ) : NotSentReason

    data class SessionFailed(
        val errorType: String,
    ) : NotSentReason
}

/**
 * 한 번 누르기의 결과. [errorType]은 라이브러리 오류 종류 이름이고 `message`나 차량이 보낸 글은 담지 않는다.
 * [status]는 이번에 읽은 `vehicleStatus()`(상태 카드용), [enrollment]는 이 결과로 알게 된 등록 상태, [durationMs]는 첫 호출부터 끝까지.
 */
data class InfoOutcome<out T>(
    val kind: InfoKind,
    val value: T? = null,
    val errorType: String? = null,
    val notSent: NotSentReason? = null,
    val enrollment: Enrollment? = null,
    val status: VehicleStatus? = null,
    val durationMs: Long = 0,
)

/** 로그에 가는 유일한 오류 정보: 종류 이름(`ResponseTooLarge`, `Timeout(afterSend=…)`, `NoSession`, 아니면 클래스 이름). */
object InfotainmentErrors {
    fun typeName(error: VehicleError): String =
        when {
            error is VehicleError.Timeout -> {
                "Timeout(afterSend=${error.afterSend})"
            }

            error is VehicleError.ProtocolFault && error.fault == MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED -> {
                "ResponseTooLarge"
            }

            else -> {
                error::class.simpleName ?: "UnknownError"
            }
        }
}

private fun VehicleResult<*>.errorOrNull(): VehicleError? =
    when (this) {
        is VehicleResult.Success -> null
        is VehicleResult.Failure -> error
        is VehicleResult.Uncertain -> error
    }

/** 누르기 한 번의 실행 규칙과 결과 줄. 호출은 모두 람다로 받아 차량 없이 시험한다. */
object InfotainmentRunner {
    /**
     * 1. [readStatus]: `AWAKE`일 때만 계속한다(아니면 NotSent, 세션도 시작하지 않고 깨우지도 않는다).
     * 2. [ensureSession]: 실패나 `Uncertain`이면 NotSent.
     * 3. [call]을 **정확히 한 번** 부른다(`Uncertain`도 다시 보내지 않고, 끝난 뒤 `getState`를 따로 부르지 않는다).
     */
    suspend fun <T> run(
        readStatus: suspend () -> VehicleResult<VehicleStatus>,
        ensureSession: suspend () -> VehicleResult<Unit>,
        call: suspend () -> VehicleResult<T>,
        timeSource: TimeSource = TimeSource.Monotonic,
    ): InfoOutcome<T> {
        val mark = timeSource.markNow()

        fun ms() = mark.elapsedNow().inWholeMilliseconds

        val read = readStatus()
        val status = (read as? VehicleResult.Success)?.value
        val stop = sleepStop(read)
        if (stop !=
            null
        ) {
            return InfoOutcome(InfoKind.NOT_SENT, notSent = stop.first, errorType = stop.second, status = status, durationMs = ms())
        }

        val session = ensureSession().errorOrNull()
        if (session != null) {
            val type = InfotainmentErrors.typeName(session)
            val enrollment = if (session == VehicleError.KeyNotPaired) Enrollment.NOT_ENROLLED else null
            return InfoOutcome(
                InfoKind.NOT_SENT,
                notSent = NotSentReason.SessionFailed(type),
                errorType = type,
                enrollment = enrollment,
                status = status,
                durationMs = ms(),
            )
        }

        return when (val r = call()) {
            is VehicleResult.Success -> {
                InfoOutcome(InfoKind.SUCCESS, value = r.value, status = status, durationMs = ms())
            }

            is VehicleResult.Uncertain -> {
                InfoOutcome(InfoKind.UNCERTAIN, errorType = InfotainmentErrors.typeName(r.error), status = status, durationMs = ms())
            }

            is VehicleResult.Failure -> {
                val enrollment = if (r.error == VehicleError.KeyNotPaired) Enrollment.NOT_ENROLLED else null
                InfoOutcome(
                    InfoKind.FAILURE,
                    errorType = InfotainmentErrors.typeName(r.error),
                    enrollment = enrollment,
                    status = status,
                    durationMs = ms(),
                )
            }
        }
    }

    /** 수면 확인 결과가 "보내지 않음"이면 (이유, 오류 종류), 계속해도 되면 `null`. */
    private fun sleepStop(read: VehicleResult<VehicleStatus>): Pair<NotSentReason, String?>? {
        if (read !is VehicleResult.Success) {
            val type = InfotainmentErrors.typeName(checkNotNull(read.errorOrNull()))
            return NotSentReason.SleepReadFailed(type) to type
        }
        return when (read.value.sleepStatus) {
            SleepStatus.AWAKE -> null
            SleepStatus.ASLEEP -> NotSentReason.Asleep to null
            SleepStatus.UNKNOWN -> NotSentReason.SleepUnknown to null
        }
    }

    /** 결과 한 줄: `<버튼>: <결과> (<오류 종류>) <n> ms` 와, `Uncertain`·실패·NotSent 에는 고정 문장 하나. 값과 `message` 는 담지 않는다. */
    fun resultLine(
        cmd: InfoCommand,
        outcome: InfoOutcome<*>,
    ): String {
        val word =
            when (outcome.kind) {
                InfoKind.SUCCESS -> "성공"
                InfoKind.UNCERTAIN -> "불확실"
                InfoKind.FAILURE -> "실패"
                InfoKind.NOT_SENT -> "보내지 않음"
            }
        val type = outcome.errorType?.let { " ($it)" }.orEmpty()
        val head = "${cmd.label}: $word$type ${outcome.durationMs} ms"
        return note(cmd, outcome)?.let { "$head\n  $it" } ?: head
    }

    private fun note(
        cmd: InfoCommand,
        outcome: InfoOutcome<*>,
    ): String? =
        when (outcome.kind) {
            InfoKind.SUCCESS -> null
            InfoKind.UNCERTAIN -> uncertainNote(cmd)
            InfoKind.FAILURE -> failureNote(outcome.errorType)
            InfoKind.NOT_SENT -> notSentNote(outcome.notSent)
        }

    private fun uncertainNote(cmd: InfoCommand): String =
        if (cmd.isRead) {
            "값을 확인하지 못했습니다. 조회는 차량을 바꾸지 않으니 다시 읽어도 됩니다. 마지막 값은 그대로 둡니다"
        } else {
            "차량이 실행했을 수 있습니다. 다시 보내지 않습니다. '${cmd.category.readLabel}'로 상태를 확인하세요"
        }

    private fun failureNote(type: String?): String? =
        when (type) {
            "InfotainmentRejected" -> "차량이 거부했습니다"
            "NoSession" -> "세션이 없어 보내지 않았습니다"
            "KeyNotPaired" -> "이 키가 차량에 등록되어 있지 않습니다"
            else -> null
        }

    private fun notSentNote(reason: NotSentReason?): String? =
        when (reason) {
            NotSentReason.Asleep -> {
                "차량이 자고 있습니다. 앱은 차를 자동으로 깨우지 않습니다. 문 그룹의 깨우기 버튼으로 깨우고, 상태 카드가 깨어 있음으로 바뀌면 다시 누르세요"
            }

            NotSentReason.SleepUnknown -> {
                "수면 상태를 알 수 없어 보내지 않았습니다. 상태 새로고침 뒤 다시 누르세요"
            }

            is NotSentReason.SleepReadFailed -> {
                "수면 상태를 읽지 못해 보내지 않았습니다"
            }

            is NotSentReason.SessionFailed -> {
                when (reason.errorType) {
                    "KeyNotPaired" -> "이 키가 차량에 등록되어 있지 않아 보내지 않았습니다"
                    "Locked" -> "폰을 잠금 해제한 뒤 다시 누르세요(보내지 않음)"
                    else -> "Infotainment 세션을 시작하지 못해 보내지 않았습니다"
                }
            }

            null -> {
                null
            }
        }

    /** 연결이 바뀐 뒤에 끝난 호출의 줄: 결과는 버리고 명령 이름만 남긴다. */
    fun droppedLine(cmd: InfoCommand): String =
        if (cmd.isRead) {
            "${cmd.label}: 연결이 바뀌어 결과를 버렸습니다"
        } else {
            "${cmd.label}: 연결이 바뀌어 결과를 버렸습니다. 차량이 실행했을 수 있으니 다시 연결한 뒤 상태를 읽으세요"
        }
}

/** 화면에 보이는 한 줄(값은 화면에만 간다). */
data class InfoField(
    val label: String,
    val value: String,
)

/** 한 범주의 마지막 읽기. [timestampSeconds]는 응답의 `timestamp`(없으면 `null`), [at]은 읽은 시각. */
data class InfoReading(
    val category: InfoCategory,
    val fields: List<InfoField>,
    val timestampSeconds: Long?,
    val at: Instant,
    val sameTimestampAsPrevious: Boolean,
) {
    companion object {
        /** 이름 붙은 필드만 읽는다. 없는 값은 `—`, `0` 은 `0`(`14-lib-reading-state.md` A8.3). */
        fun of(
            category: InfoCategory,
            data: VehicleData,
            at: Instant,
            previous: InfoReading?,
        ): InfoReading {
            val climate = data.climate_state
            val charge = data.charge_state
            val (fields, ts) =
                when (category) {
                    InfoCategory.CLIMATE -> {
                        listOf(
                            field("is_climate_on", climate?.is_climate_on),
                            field("inside_temp_celsius", climate?.inside_temp_celsius),
                            field("driver_temp_setting", climate?.driver_temp_setting),
                        ) to climate?.timestamp?.getEpochSecond()
                    }

                    InfoCategory.CHARGE -> {
                        listOf(
                            field("battery_level", charge?.battery_level),
                            field("charge_limit_soc", charge?.charge_limit_soc),
                            field("charger_power", charge?.charger_power),
                            field("charge_port_door_open", charge?.charge_port_door_open),
                        ) to charge?.timestamp?.getEpochSecond()
                    }
                }
            return InfoReading(category, fields, ts, at, sameTimestampAsPrevious = ts != null && previous?.timestampSeconds == ts)
        }

        private fun field(
            label: String,
            value: Any?,
        ) = InfoField(label, value?.toString() ?: NO_VALUE)
    }
}

/** 화면 상태: 결과가 미확인인 범주와 범주별 마지막 읽기. 연결이 바뀌면 [AFTER_CONNECTION_CHANGE]로 되돌린다. */
data class InfoUiState(
    val unconfirmed: Set<InfoCategory> = emptySet(),
    val readings: Map<InfoCategory, InfoReading> = emptyMap(),
) {
    companion object {
        val AFTER_CONNECTION_CHANGE = InfoUiState()
    }
}

/** 결과가 화면 상태에 미치는 영향. [current]가 `false`(연결이 바뀐 뒤 늦게 끝난 호출)이면 상태를 전혀 바꾸지 않는다. */
object InfotainmentReducer {
    /** `Uncertain` → 그 범주를 미확인으로, `Success` → 미확인 해제. 그 밖의 결과는 표시를 바꾸지 않는다. */
    fun afterCommand(
        state: InfoUiState,
        cmd: InfoCommand,
        outcome: InfoOutcome<*>,
        current: Boolean,
    ): InfoUiState {
        if (!current) return state
        return when (outcome.kind) {
            InfoKind.UNCERTAIN -> state.copy(unconfirmed = state.unconfirmed + cmd.category)
            InfoKind.SUCCESS -> state.copy(unconfirmed = state.unconfirmed - cmd.category)
            InfoKind.FAILURE, InfoKind.NOT_SENT -> state
        }
    }

    /** `Success` → 읽은 값을 저장하고 미확인 해제. `Uncertain`/`Failure`는 마지막 값과 표시를 그대로 둔다. */
    fun afterRead(
        state: InfoUiState,
        category: InfoCategory,
        outcome: InfoOutcome<VehicleData>,
        current: Boolean,
        at: Instant,
    ): InfoUiState {
        if (!current || outcome.kind != InfoKind.SUCCESS) return state
        val reading = InfoReading.of(category, outcome.value ?: VehicleData(), at, state.readings[category])
        return InfoUiState(state.unconfirmed - category, state.readings + (category to reading))
    }
}

/** 연결(`Vehicle`)이나 키가 바뀔 때마다 올라가는 세대. 누르기가 시작할 때의 값을 기억해 두었다가 끝날 때 비교한다. */
class InfoGeneration {
    var value: Int = 0
        private set

    /** 새 세대로 넘어간다(연결이 바뀌었거나 같은 연결에서 키가 바뀌었다). 새 값을 돌려준다. */
    fun bump(): Int = ++value
}

/** 끝난 호출의 결과가 아직 그 연결·그 키의 것인지 가린다. 같은 연결에서 키만 바뀐 경우도 낡은 결과로 본다(`05-key-control-tab.md` §Connection change). */
object InfotainmentGuard {
    fun isCurrent(
        startVehicle: Any?,
        nowVehicle: Any?,
        startGeneration: Int,
        nowGeneration: Int,
    ): Boolean = startVehicle != null && startVehicle === nowVehicle && startGeneration == nowGeneration
}
