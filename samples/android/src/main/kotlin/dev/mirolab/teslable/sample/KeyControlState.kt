package dev.mirolab.teslable.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 결과 목록의 한 줄(시각 포함, VIN 없음). */
data class ResultLine(
    val at: Instant,
    val text: String,
)

/** 마지막 `vehicleStatus()` 와 읽은 시각(상태 카드). */
data class StatusSnapshot(
    val status: VehicleStatus,
    val at: Instant,
)

/**
 * 키·제어 탭의 상태(SFR-018, `05-key-control-tab.md`). 연결과 모니터 키는 모니터([MonitorEngine])가 가진 것을 쓴다.
 * 이 탭의 라이브러리 호출은 [exclusive]로 한 번에 하나만 돈다. 규칙(단계, 명령 순서)은 [KeyControlSteps], [KeyControlRunner]에 있다.
 */
@Suppress("TooManyFunctions") // 한 화면의 상태와 동작(키, 등록, 명령, 상태 새로고침)을 한 곳에 모은 구조라 일부러 나누지 않는다
class KeyControlState(
    private val engine: MonitorEngine,
) {
    /** 이 탭의 호출이 진행 중이면 true: 버튼을 막는다. */
    val busy = mutableStateOf(false)
    val enrollment = mutableStateOf(Enrollment.UNKNOWN)
    val status = mutableStateOf<StatusSnapshot?>(null)

    /** 결과 줄, 최신이 앞. */
    val results = mutableStateListOf<ResultLine>()

    /** 확인 창을 기다리는 명령. */
    val pendingConfirm = mutableStateOf<KeyCommand?>(null)

    /** 등록 상태를 이미 자동 확인한 연결. */
    private var checkedConnection: Any? = null

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
     * 끝나지 않는 수집이니 앱이 살아 있는 동안 한 번만 시작한다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun observe() {
        combine(engine.link, engine.keyStatus) { link, keyStatus ->
            Seen((link as? LinkState.Connected)?.vehicle, keyStatus.ready, engine.currentKey()?.publicKey)
        }.distinctUntilChanged()
            .collectLatest { seen ->
                enrollment.value = Enrollment.UNKNOWN
                if (seen.vehicle == null) checkedConnection = null
                if (KeyControlSteps.needsAutoCheck(seen.vehicle, seen.keyReady, checkedConnection)) {
                    while (!exclusive { checkEnrollment(auto = seen.vehicle) }) {
                        snapshotFlow { busy.value }.first { !it } // 다른 호출이 끝나기를 기다렸다 다시 시도한다
                    }
                }
            }
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
     * [auto]는 자동 확인일 때 그 연결(끝까지 못 하고 취소되면 다음에 다시 확인하도록 기록을 지운다).
     */
    suspend fun checkEnrollment(auto: Any? = null) {
        val v = vehicle ?: return log("등록 상태 확인: 먼저 연결하세요")
        val k = engine.currentKey() ?: return log("등록 상태 확인: 먼저 키를 만드세요")
        if (auto != null) checkedConnection = auto
        enrollment.value = Enrollment.CHECKING
        var finished = false
        try {
            val e = KeyControlRunner.enrollmentFrom(v.listKeys(), k.publicKey)
            enrollment.value = e
            finished = true
            log(
                when (e) {
                    Enrollment.ENROLLED -> "등록 상태: 이 키가 차량에 등록되어 있습니다"
                    Enrollment.NOT_ENROLLED -> "등록 상태: 이 키는 차량에 등록되어 있지 않습니다"
                    else -> "등록 상태: 확인하지 못했습니다(다시 시도하세요)"
                },
            )
        } finally {
            if (!finished) {
                if (enrollment.value == Enrollment.CHECKING) enrollment.value = Enrollment.UNKNOWN
                if (auto != null) checkedConnection = null
            }
        }
    }

    /**
     * 키 등록 요청. 이벤트마다 한 줄을 남기고, `Accepted`/`TimedOut`이면 3초 간격 최대 2분 동안 `sessionInfo`로 등록을 확인한다
     * (`13-lib-pairing.md` §Enrolling a key).
     */
    @Suppress("CyclomaticComplexMethod") // PairingEvent 7종을 한 `when` 으로 문장에 옮기는 표라 갈래 수가 곧 복잡도다
    suspend fun enroll() {
        val v = vehicle ?: return log("키 등록: 먼저 연결하세요")
        val k = engine.currentKey() ?: return log("키 등록: 먼저 키를 만드세요")
        var confirm = false
        v.addKeyRequest(k.publicKey).collect { event ->
            log(
                when (event) {
                    PairingEvent.Sent -> "요청 보냄: 중앙 콘솔에 키카드를 대고 화면에서 승인하세요"
                    PairingEvent.WaitingForTap -> "카드 태그 대기 중"
                    PairingEvent.Accepted -> "차량이 승인함: 동기화 확인 중".also { confirm = true }
                    is PairingEvent.Rejected -> "거부: ${event.error.message}"
                    is PairingEvent.Unknown -> "모르는 응답을 받음(무시)"
                    is PairingEvent.Failed -> "실패: ${event.error.message}"
                    PairingEvent.TimedOut -> "시간 초과: 등록 여부를 확인합니다".also { confirm = true }
                },
            )
        }
        if (confirm) confirmEnrollment(v, k.publicKey)
    }

    private suspend fun confirmEnrollment(
        v: Vehicle,
        publicKey: PublicKeyBytes,
    ) {
        var last = ""
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow() < ENROLL_WINDOW) {
            val line =
                when (val r = v.sessionInfo(publicKey, VehicleDomain.INFOTAINMENT, ENROLL_CALL_TIMEOUT)) {
                    is VehicleResult.Success -> {
                        enrollment.value = Enrollment.ENROLLED
                        return log("등록 확인됨")
                    }

                    is VehicleResult.Failure -> {
                        if (r.error is VehicleError.KeyNotPaired) "아직 등록되지 않음: 2분까지 반복 확인" else "등록 확인 실패: ${r.error.message}"
                    }

                    is VehicleResult.Uncertain -> {
                        "등록 확인 불확실: ${r.error.message}"
                    }
                }
            if (line != last) log(line)
            last = line
            delay(ENROLL_INTERVAL)
        }
        log("2분 안에 등록이 확인되지 않았습니다")
    }

    // --- 4 제어 ----------------------------------------------------------------------------------------------------------

    /**
     * 명령 하나. 먼저 VCSEC 세션을 확인하고(이미 `Ready`면 요청 없이 성공), 명령을 한 번만 보내고, 끝나면 `vehicleStatus()`로 재조회한다.
     * `Uncertain`은 다시 보내지 않는다(`16-lib-errors.md` §Per-command re-query).
     */
    suspend fun command(cmd: KeyCommand) {
        val v = vehicle ?: return log("${cmd.label}: 먼저 연결하세요")
        val outcome =
            KeyControlRunner.run(
                ensureSession = { v.startSession(setOf(VehicleDomain.VCSEC)) },
                command = { send(v, cmd) },
                requery = { v.vehicleStatus() },
            )
        outcome.enrollment?.let { enrollment.value = it }
        outcome.status?.let { status.value = StatusSnapshot(it, Instant.now()) }
        log("${cmd.label}: ${outcome.message}")
        outcome.requeryMessage?.let { log("  $it") }
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

    /** 상태 새로고침 버튼. */
    suspend fun refreshStatus() {
        val v = vehicle ?: return log("상태 새로고침: 먼저 연결하세요")
        when (val r = v.vehicleStatus()) {
            is VehicleResult.Success -> status.value = StatusSnapshot(r.value, Instant.now())
            is VehicleResult.Uncertain -> log("상태 새로고침 불확실: ${r.error.message}")
            is VehicleResult.Failure -> log("상태 새로고침 실패: ${r.error.message}")
        }
    }

    private companion object {
        const val MAX_RESULTS = 200
        val ENROLL_WINDOW = 120.seconds
        val ENROLL_INTERVAL = 3.seconds
        val ENROLL_CALL_TIMEOUT = 5.seconds
    }
}
