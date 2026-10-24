package dev.mirolab.teslable.sample

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import dev.mirolab.teslable.model.WhitelistEntry

/** 이 기기의 키가 차량에 등록돼 있는지(키·제어 탭, SFR-018). 확인 전에는 [UNKNOWN]. */
enum class Enrollment { UNKNOWN, CHECKING, NOT_ENROLLED, ENROLLED }

enum class StepStatus { DONE, TODO, BLOCKED }

/** 단계 하나의 표시: 상태, 한 줄 안내, 그 단계 버튼을 누를 수 있는지. */
data class StepView(
    val status: StepStatus,
    val hint: String,
    val buttonEnabled: Boolean,
)

/** 단계 규칙의 입력(`05-key-control-tab.md` §Steps and enabling rules). */
data class KeyControlInputs(
    /** 모니터 키가 있고 읽을 수 있다. */
    val keyReady: Boolean,
    /** 폰 잠금 때문에 키를 읽지 못한다. */
    val keyLocked: Boolean,
    val vinSaved: Boolean,
    val connected: Boolean,
    /** 연결이 개인키를 갖고 있다(`Vehicle.hasKey`). 연결할 때 키 저장소 오류가 있었다면 키 없이 연결된다. */
    val hasKey: Boolean,
    val enrollment: Enrollment,
    /** 이 탭의 라이브러리 호출이 진행 중이다. */
    val busy: Boolean,
)

/** 네 단계의 상태와 버튼 활성 규칙. 차량 없이 시험하는 순수 함수다. */
data class KeyControlSteps(
    val key: StepView,
    val connect: StepView,
    val enroll: StepView,
    val control: StepView,
    /** "등록 상태 확인" 버튼. */
    val checkEnabled: Boolean,
) {
    companion object {
        const val HINT_KEY_LOCKED = "폰을 잠금 해제한 뒤 다시 누르세요"
        const val HINT_NO_VIN = "Live 탭에서 VIN을 저장하세요"
        const val HINT_FINISH_1_2 = "1·2단계를 먼저 끝내세요"
        const val HINT_NOT_ENROLLED_YET = "키를 차량에 등록하면 버튼이 켜집니다"
        const val HINT_CONNECTED_WITHOUT_KEY = "키 없이 연결됨 — 연결 끊기 후 다시 연결하세요"
        const val REASON_BUSY = "다른 호출이 진행 중입니다"

        fun of(i: KeyControlInputs): KeyControlSteps {
            val ready = i.keyReady && i.connected && i.hasKey
            val idle = !i.busy
            return KeyControlSteps(
                key = keyStep(i),
                connect = connectStep(i),
                enroll = enrollStep(i, ready),
                control = controlStep(i, ready),
                checkEnabled = ready && i.enrollment != Enrollment.CHECKING && idle,
            )
        }

        /** "상태 새로고침" 버튼: 연결돼 있고 이 탭의 호출이 진행 중이 아니다. */
        fun refreshEnabled(
            connected: Boolean,
            busy: Boolean,
        ): Boolean = connected && !busy

        private fun keyStep(i: KeyControlInputs): StepView =
            when {
                i.keyReady -> StepView(StepStatus.DONE, "키 준비됨", !i.busy)
                i.keyLocked -> StepView(StepStatus.BLOCKED, HINT_KEY_LOCKED, !i.busy)
                else -> StepView(StepStatus.TODO, "키를 만들거나 불러오세요", !i.busy)
            }

        private fun connectStep(i: KeyControlInputs): StepView =
            when {
                i.connected && i.hasKey -> StepView(StepStatus.DONE, "차량에 연결됨", false)
                i.connected -> StepView(StepStatus.TODO, HINT_CONNECTED_WITHOUT_KEY, !i.busy)
                !i.vinSaved -> StepView(StepStatus.BLOCKED, HINT_NO_VIN, false)
                else -> StepView(StepStatus.TODO, "연결 버튼을 누르세요", !i.busy)
            }

        private fun enrollStep(
            i: KeyControlInputs,
            ready: Boolean,
        ): StepView =
            when {
                i.enrollment == Enrollment.ENROLLED -> {
                    StepView(StepStatus.DONE, "차량에 등록됨", false)
                }

                !ready -> {
                    StepView(StepStatus.BLOCKED, HINT_FINISH_1_2, false)
                }

                i.enrollment == Enrollment.CHECKING -> {
                    StepView(StepStatus.TODO, "등록 상태 확인 중…", false)
                }

                i.enrollment == Enrollment.NOT_ENROLLED -> {
                    StepView(StepStatus.TODO, "아직 등록되지 않았습니다. 등록을 요청하고 중앙 콘솔에 키카드를 대세요", !i.busy)
                }

                else -> {
                    StepView(StepStatus.TODO, "등록 상태를 확인하거나 등록을 요청하세요", !i.busy)
                }
            }

        private fun controlStep(
            i: KeyControlInputs,
            ready: Boolean,
        ): StepView =
            when {
                i.enrollment != Enrollment.ENROLLED -> StepView(StepStatus.BLOCKED, HINT_NOT_ENROLLED_YET, false)
                !ready -> StepView(StepStatus.BLOCKED, HINT_FINISH_1_2, false)
                else -> StepView(StepStatus.TODO, "버튼을 눌러 차량을 제어하세요", !i.busy)
            }

        /**
         * 제어 명령을 지금 보내도 되는가. 보낼 수 없으면 그 이유 한 줄(확인 창을 누른 사이 조건이 바뀌었을 때 결과에 남긴다), 되면 `null`.
         */
        fun commandBlockedReason(i: KeyControlInputs): String? =
            when {
                i.busy -> REASON_BUSY
                !of(i).control.buttonEnabled -> of(i).control.hint
                else -> null
            }
    }
}

/** 버튼 묶음. [collapsed]이면 "다른 차종" 접이식 영역에 둔다. */
enum class KeyGroup(
    val title: String,
    val collapsed: Boolean = false,
) {
    DOORS("문"),
    TRUNK("트렁크"),
    DRIVE("주행"),
    MODEL_X("Model X", collapsed = true),
    CYBERTRUCK("Cybertruck", collapsed = true),
}

/** 제어 버튼 표(`05-key-control-tab.md` §Buttons). [confirm]이 있으면 누르기 전에 확인 창을 띄운다. */
enum class KeyCommand(
    val label: String,
    val group: KeyGroup,
    val confirm: String? = null,
) {
    LOCK("잠금", KeyGroup.DOORS),
    UNLOCK("잠금 해제", KeyGroup.DOORS),
    WAKE_UP("깨우기", KeyGroup.DOORS),
    ACTUATE_TRUNK("트렁크 열기/닫기", KeyGroup.TRUNK),
    CLOSE_TRUNK("트렁크 닫기", KeyGroup.TRUNK),
    OPEN_FRUNK("프렁크 열기", KeyGroup.TRUNK, "프렁크는 원격으로 닫을 수 없습니다. 열까요?"),
    REMOTE_DRIVE("원격 주행 허용", KeyGroup.DRIVE, "원격 주행을 허용하면 차가 키 없이 움직일 수 있는 상태가 됩니다. 허용할까요?"),
    AUTO_SECURE("자동 잠금", KeyGroup.MODEL_X),
    OPEN_TONNEAU("토노 열기", KeyGroup.CYBERTRUCK),
    CLOSE_TONNEAU("토노 닫기", KeyGroup.CYBERTRUCK),
    STOP_TONNEAU("토노 정지", KeyGroup.CYBERTRUCK),
}

/**
 * 명령 한 번의 결과. [enrollment]는 이 결과로 알게 된 등록 상태(없으면 null), [status]는 재조회로 읽은 상태,
 * [requeryMessage]는 재조회가 실패했을 때의 한 줄. 세션 실패면 명령을 보내지 않았고 [status]도 없다.
 */
data class CommandOutcome(
    val message: String,
    val enrollment: Enrollment? = null,
    val status: VehicleStatus? = null,
    val requeryMessage: String? = null,
)

private fun VehicleResult<*>.errorOrNull(): VehicleError? =
    when (this) {
        is VehicleResult.Success -> null
        is VehicleResult.Failure -> error
        is VehicleResult.Uncertain -> error
    }

/** 명령 실행 규칙(`05-key-control-tab.md` §Running a command). 호출은 모두 람다로 받아 차량 없이 시험한다. */
object KeyControlRunner {
    /**
     * 1. [ensureSession] 이 실패하면 명령을 보내지 않는다. 2. [command]는 정확히 한 번 부른다(`Uncertain`도 다시 보내지 않는다).
     * 3. 명령을 보냈다면 결과와 상관없이 [requery]를 부른다.
     */
    suspend fun run(
        ensureSession: suspend () -> VehicleResult<Unit>,
        command: suspend () -> VehicleResult<Unit>,
        requery: suspend () -> VehicleResult<VehicleStatus>,
    ): CommandOutcome {
        ensureSession().errorOrNull()?.let { return sessionFailed(it) }

        // 세션이 이미 Ready라 startSession이 아무것도 보내지 않았어도, 명령 자체가 KeyNotPaired면 등록되지 않은 것이다.
        var enrollment: Enrollment? = null
        val message =
            when (val r = command()) {
                is VehicleResult.Success -> {
                    "성공"
                }

                is VehicleResult.Failure -> {
                    if (r.error == VehicleError.KeyNotPaired) enrollment = Enrollment.NOT_ENROLLED
                    "실패: ${r.error.message}"
                }

                is VehicleResult.Uncertain -> {
                    "불확실: ${r.error.message}"
                }
            }
        val q = requery()
        return if (q is VehicleResult.Success) {
            CommandOutcome(message, enrollment = enrollment, status = q.value)
        } else {
            CommandOutcome(message, enrollment = enrollment, requeryMessage = "재조회 실패: ${q.errorOrNull()?.message}")
        }
    }

    /**
     * 자동 등록 확인. 첫 확인이 `UNKNOWN`(`Uncertain`/`Failure`)이면 모니터의 첫 조회와 VCSEC 호출이 겹쳤을 수 있으니
     * [wait] 뒤 한 번만 더 확인한다.
     */
    suspend fun autoCheck(
        check: suspend () -> Enrollment,
        wait: suspend () -> Unit,
    ): Enrollment {
        val first = check()
        if (first != Enrollment.UNKNOWN) return first
        wait()
        return check()
    }

    private fun sessionFailed(error: VehicleError): CommandOutcome =
        when (error) {
            VehicleError.KeyNotPaired -> {
                CommandOutcome("세션 실패: 이 키가 차량에 등록되어 있지 않습니다(명령은 보내지 않음)", enrollment = Enrollment.NOT_ENROLLED)
            }

            VehicleError.KeyStoreError.Locked -> {
                CommandOutcome("세션 실패: 폰을 잠금 해제한 뒤 다시 누르세요(명령은 보내지 않음)")
            }

            else -> {
                CommandOutcome("세션 실패: ${error.message}(명령은 보내지 않음)")
            }
        }

    /** `listKeys()` 결과에서 [mine]과 같은 공개키가 있으면 등록됨. `Uncertain`/`Failure`는 모른다(`13-lib-pairing.md` §Checking whether a key is enrolled). */
    fun enrollmentFrom(
        result: VehicleResult<List<WhitelistEntry>>,
        mine: PublicKeyBytes,
    ): Enrollment =
        when (result) {
            is VehicleResult.Success -> {
                if (result.value.any { it.publicKey.isSameKeyAs(mine) }) Enrollment.ENROLLED else Enrollment.NOT_ENROLLED
            }

            is VehicleResult.Uncertain, is VehicleResult.Failure -> {
                Enrollment.UNKNOWN
            }
        }
}
