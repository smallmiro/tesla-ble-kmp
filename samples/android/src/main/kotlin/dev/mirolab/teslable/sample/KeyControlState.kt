package dev.mirolab.teslable.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** 결과 목록의 한 줄(시각 포함, VIN 없음). */
data class ResultLine(
    val at: Instant,
    val text: String,
)

/** 마지막 `vehicleStatus()` 와 읽은 시각(상태 카드). [vehicle]은 읽은 연결: 연결이 바뀌면 카드를 지운다. */
data class StatusSnapshot(
    val status: VehicleStatus,
    val at: Instant,
    val vehicle: Vehicle,
)

/**
 * 키·제어 탭의 상태(SFR-018, `05-key-control-tab.md`). 연결과 모니터 키는 모니터([MonitorEngine])가 가진 것을 쓴다.
 * 이 탭의 라이브러리 호출은 [exclusive]로 한 번에 하나만 돈다. 규칙(단계, 명령 순서)은 [KeyControlSteps], [KeyControlRunner]에 있다.
 * 동작은 화면이 아니라 [scope](ViewModel 범위)에서 돌려, 탭을 바꾸거나 화면을 돌려도 등록·명령·재조회가 끊기지 않는다.
 */
@Suppress("TooManyFunctions") // 한 화면의 상태와 동작(키, 등록, 명령, 상태 새로고침)을 한 곳에 모은 구조라 일부러 나누지 않는다
class KeyControlState(
    private val engine: MonitorEngine,
    private val scope: CoroutineScope,
) {
    /** 이 탭의 호출이 진행 중이면 true: 버튼을 막는다. */
    val busy = mutableStateOf(false)
    val enrollment = mutableStateOf(Enrollment.UNKNOWN)
    val status = mutableStateOf<StatusSnapshot?>(null)

    /** 결과 줄, 최신이 앞. */
    val results = mutableStateListOf<ResultLine>()

    /** 확인 창을 기다리는 명령. */
    val pendingConfirm = mutableStateOf<KeyCommand?>(null)

    private val vehicle: Vehicle? get() = engine.currentVehicle()

    /** [body]를 한 번에 하나만 실행한다. 이미 진행 중이면 실행하지 않고 `false`. */
    suspend fun exclusive(body: suspend KeyControlState.() -> Unit): Boolean {
        if (busy.value) return false
        busy.value = true
        try {
            body()
        } finally {
            busy.value = false
        }
        return true
    }

    /** [body]를 이 상태의 범위에서 한 번에 하나만 실행한다. 이미 진행 중이면 [busyLine]을 결과에 남기고 실행하지 않는다. */
    fun launchExclusive(
        busyLine: String? = null,
        body: suspend KeyControlState.() -> Unit,
    ) {
        scope.launch { if (!exclusive(body) && busyLine != null) log("$busyLine: 보내지 않음 — ${KeyControlSteps.REASON_BUSY}") }
    }

    /** 화면과 [requestCommand]가 같은 규칙으로 단계를 계산하도록 입력을 모은다. */
    fun inputs(
        link: LinkState<Vehicle>,
        keyStatus: KeyStatus,
        vinSaved: Boolean = engine.parsedVin() != null,
    ): KeyControlInputs {
        val v = (link as? LinkState.Connected)?.vehicle
        return KeyControlInputs(
            keyReady = keyStatus.state == KeyState.READY,
            keyLocked = keyStatus.state == KeyState.LOCKED,
            vinSaved = vinSaved,
            connected = v != null,
            hasKey = v?.hasKey == true,
            enrollment = enrollment.value,
            busy = busy.value,
        )
    }

    private fun inputsNow(): KeyControlInputs = inputs(engine.link.value, engine.keyStatus.value)

    /** 결과 목록 맨 앞에 한 줄을 더한다. */
    fun log(text: String) {
        results.add(0, ResultLine(Instant.now(), text))
        if (results.size > MAX_RESULTS) results.removeRange(MAX_RESULTS, results.size)
    }

    // --- 연결과 키를 지켜보며 등록 상태를 맞춘다 ---------------------------------------------------------------------------

    private data class Seen(
        val vehicle: Vehicle?,
        val keyReady: Boolean,
        val publicKey: PublicKeyBytes?,
    )

    /**
     * 연결이 끊기거나 키가 바뀌면 등록 상태를 `UNKNOWN`으로 되돌리고, 키가 준비된 새 연결마다 한 번 `listKeys()`로 확인한다.
     * `distinctUntilChanged`가 같은 연결을 한 번만 통과시키고 `collectLatest`가 연결이 바뀌면 이전 확인을 취소한다.
     * 끝나지 않는 수집이니 앱이 살아 있는 동안 한 번만 시작한다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun observe() {
        combine(engine.link, engine.keyStatus) { link, keyStatus ->
            Seen((link as? LinkState.Connected)?.vehicle, keyStatus.state == KeyState.READY, engine.currentKey()?.publicKey)
        }.distinctUntilChanged()
            .collectLatest { seen ->
                enrollment.value = Enrollment.UNKNOWN
                if (status.value?.vehicle !== seen.vehicle) status.value = null // 상태 카드는 그 연결에서 읽은 것만 보여 준다
                if (seen.vehicle?.hasKey == true && seen.keyReady) {
                    // 연결 직후엔 모니터의 첫 조회가 VCSEC을 쓰고 있어 시간이 넘을 수 있다: 모르겠으면 5초 뒤 한 번 더 확인한다.
                    KeyControlRunner.autoCheck(check = { checkWhenIdle() }, wait = { delay(AUTO_RETRY_DELAY) })
                }
            }
    }

    /** 다른 호출이 끝나기를 기다렸다가 자동 확인을 한 번 한다(자동 확인은 오류 줄을 남기지 않는다). */
    private suspend fun checkWhenIdle(): Enrollment {
        var result = Enrollment.UNKNOWN
        while (!exclusive { result = checkEnrollment(manual = false) }) {
            snapshotFlow { busy.value }.first { !it } // 다른 호출이 끝나기를 기다렸다 다시 시도한다
        }
        return result
    }

    /** 탭이 보일 때: 다른 탭에서 등록이나 삭제를 했을 수 있으니, 지금 확인할 수 있으면 다시 확인한다(결과가 바뀔 때만 줄을 남긴다). */
    fun onShown() {
        if (!KeyControlSteps.of(inputsNow()).checkEnabled) return
        scope.launch { exclusive { checkEnrollment(manual = false, logUnchanged = false) } }
    }

    /** Control 탭이 이 기기 키의 등록 상태를 바꿨다(등록 확인 → [Enrollment.ENROLLED], 키 삭제 → [Enrollment.UNKNOWN]: 탭이 보일 때 다시 확인). */
    fun onEnrollmentChangedElsewhere(newState: Enrollment) {
        if (enrollment.value != Enrollment.CHECKING) enrollment.value = newState
    }

    // --- 1 키 ------------------------------------------------------------------------------------------------------------

    /** 모니터 키(`teslable-monitor`)를 읽거나 만든다. */
    suspend fun ensureKey() {
        when (val r = engine.ensureKey()) {
            is VehicleResult.Success -> {
                log("키 준비됨: ${r.value.protection}, 접근성 ${r.value.accessibility}")
            }

            is VehicleResult.Uncertain -> {
                log("키 불확실: ${r.error.message}")
            }

            is VehicleResult.Failure -> {
                log(if (r.error == VehicleError.KeyStoreError.Locked) "키 잠김: 폰을 잠금 해제한 뒤 다시 누르세요" else "키 오류: ${r.error.message}")
            }
        }
    }

    // --- 3 등록 ----------------------------------------------------------------------------------------------------------

    /**
     * `listKeys()`로 이 기기의 키가 차량에 있는지 확인한다(`13-lib-pairing.md` §Checking whether a key is enrolled).
     * [manual]이 아니면(자동 확인) 연결이나 키가 없어도 줄을 남기지 않는다. [logUnchanged]가 `false`이면 결과가 이전 상태와 같을 때 줄을 남기지 않는다.
     */
    suspend fun checkEnrollment(
        manual: Boolean = true,
        logUnchanged: Boolean = true,
    ): Enrollment {
        val v = vehicle
        val k = engine.currentKey()
        if (v == null || k == null) {
            if (manual) log(if (v == null) "등록 상태 확인: 먼저 연결하세요" else "등록 상태 확인: 먼저 키를 만드세요")
            return Enrollment.UNKNOWN
        }
        val before = enrollment.value
        enrollment.value = Enrollment.CHECKING
        try {
            val e = KeyControlRunner.enrollmentFrom(v.listKeys(), k.publicKey)
            enrollment.value = e
            if (logUnchanged || e != before) log(enrollmentLine(e))
            return e
        } catch (e: CancellationException) {
            if (enrollment.value == Enrollment.CHECKING) enrollment.value = Enrollment.UNKNOWN
            throw e
        }
    }

    private fun enrollmentLine(e: Enrollment): String =
        when (e) {
            Enrollment.ENROLLED -> "등록 상태: 이 키가 차량에 등록되어 있습니다"
            Enrollment.NOT_ENROLLED -> "등록 상태: 이 키는 차량에 등록되어 있지 않습니다"
            else -> "등록 상태: 확인하지 못했습니다(다시 시도하세요)"
        }

    /**
     * 키 등록 요청. 이벤트마다 한 줄을 남기고, `Accepted`/`TimedOut`이면 3초 간격 최대 2분 동안 `sessionInfo`로 등록을 확인한다
     * (`13-lib-pairing.md` §Enrolling a key).
     */
    suspend fun enroll() {
        val v = vehicle ?: return log("키 등록: 먼저 연결하세요")
        val k = engine.currentKey() ?: return log("키 등록: 먼저 키를 만드세요")
        if (requestAndConfirmEnrollment(v, k.publicKey, ::log)) enrollment.value = Enrollment.ENROLLED
    }

    // --- 4 제어 ----------------------------------------------------------------------------------------------------------

    /**
     * 명령 버튼(확인 창을 거친 뒤 포함). 누른 시점의 규칙으로 다시 확인해 보낼 수 없으면 이유를 결과에 남기고 보내지 않는다.
     */
    fun requestCommand(cmd: KeyCommand) {
        pendingConfirm.value = null
        KeyControlSteps.commandBlockedReason(inputsNow())?.let { return log("${cmd.label}: 보내지 않음 — $it") }
        launchExclusive(busyLine = cmd.label) { command(cmd) }
    }

    /**
     * 명령 하나. 먼저 VCSEC 세션을 확인하고(이미 `Ready`면 요청 없이 성공), 명령을 한 번만 보내고, 끝나면 `vehicleStatus()`로 재조회한다.
     * `Uncertain`은 다시 보내지 않는다(`16-lib-errors.md` §Per-command re-query). 결과는 한 줄(재조회 실패는 같은 줄의 둘째 줄)이다.
     */
    suspend fun command(cmd: KeyCommand) {
        val v = vehicle ?: return log("${cmd.label}: 먼저 연결하세요")
        val outcome =
            KeyControlRunner.run(
                ensureSession = { v.startSession(setOf(VehicleDomain.VCSEC)) },
                command = { send(v, cmd) },
                requery = { v.vehicleStatus() },
            )
        if (vehicle !== v) return log("${cmd.label}: ${outcome.message}\n  (그 사이 연결이 바뀌어 등록·상태 결과는 버림)")
        outcome.enrollment?.let { enrollment.value = it }
        outcome.status?.let { status.value = StatusSnapshot(it, Instant.now(), v) }
        log("${cmd.label}: ${outcome.message}" + (outcome.requeryMessage?.let { "\n  $it" } ?: ""))
    }

    private suspend fun send(
        v: Vehicle,
        cmd: KeyCommand,
    ): VehicleResult<Unit> =
        when (cmd) {
            KeyCommand.LOCK -> v.lock()
            KeyCommand.UNLOCK -> v.unlock()
            KeyCommand.WAKE_UP -> v.wakeUp()
            KeyCommand.ACTUATE_TRUNK -> v.actuateTrunk()
            KeyCommand.CLOSE_TRUNK -> v.closeTrunk()
            KeyCommand.OPEN_FRUNK -> v.openFrunk()
            KeyCommand.REMOTE_DRIVE -> v.remoteDrive()
            KeyCommand.AUTO_SECURE -> v.autoSecureVehicle()
            KeyCommand.OPEN_TONNEAU -> v.openTonneau()
            KeyCommand.CLOSE_TONNEAU -> v.closeTonneau()
            KeyCommand.STOP_TONNEAU -> v.stopTonneau()
        }

    /** 키 없이 연결된 경우(2단계 버튼): 연결을 끊고 다시 연결한다. [connect]는 화면이 넘긴 "연결" 동작(권한, 서비스 시작 포함). */
    suspend fun reconnect(connect: () -> Unit) {
        log("키 없이 연결돼 있어 연결을 끊고 다시 연결합니다")
        engine.disconnect()
        connect()
    }

    /** 상태 새로고침 버튼. */
    suspend fun refreshStatus() {
        val v = vehicle ?: return log("상태 새로고침: 먼저 연결하세요")
        when (val r = v.vehicleStatus()) {
            is VehicleResult.Success -> if (vehicle === v) status.value = StatusSnapshot(r.value, Instant.now(), v)
            is VehicleResult.Uncertain -> log("상태 새로고침 불확실: ${r.error.message}")
            is VehicleResult.Failure -> log("상태 새로고침 실패: ${r.error.message}")
        }
    }

    private companion object {
        const val MAX_RESULTS = 200
        val AUTO_RETRY_DELAY = 5.seconds
    }
}
