package dev.mirolab.teslable.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.mirolab.teslable.TeslaBle
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.model.WhitelistEntry
import dev.mirolab.teslable.port.ScanEvent
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 샘플 화면 상태(iOS SampleModel과 같은 동작). */
class SampleState(
    private val ble: TeslaBle,
) {
    val vinText = mutableStateOf("")
    val keyInfo = mutableStateOf("키 없음")
    val connection = mutableStateOf("연결 안 됨")
    val output = mutableStateListOf<String>()

    /** 호출이 진행 중이면 true — 버튼을 막아 호출이 겹치지 않게 한다. */
    val busy = mutableStateOf(false)

    /** 마지막 키 목록(항목마다 "삭제" 버튼). */
    val keyEntries = mutableStateListOf<WhitelistEntry>()

    /** 삭제 확인 대기 중인 항목. */
    val pendingRemoval = mutableStateOf<WhitelistEntry?>(null)
    private var key: VehicleKey? = null
    private var vehicle: Vehicle? = null

    /** 연결 표시를 `vehicle.connection`에 맞추는 수집기(링크 끊김도 보인다). */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectionWatch: Job? = null

    /** [body]를 한 번에 하나만 실행한다. */
    suspend fun exclusive(body: suspend SampleState.() -> Unit) {
        if (busy.value) return
        busy.value = true
        try {
            body()
        } finally {
            busy.value = false
        }
    }

    /**
     * 앱이 끝날 때 라이브러리를 닫는다(연결된 차량도 끊는다). `close()`는 suspend이고 ViewModel 스코프는 이미 취소됐으므로
     * ViewModel보다 오래 사는 스코프에서 실행한다(`GlobalScope` 금지). `close()` 안의 끊기는 취소되지 않는다.
     */
    fun close() {
        uiScope.cancel()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { ble.close() }
    }

    private fun vin(): Vin? = Vin.parseOrNull(vinText.value)

    suspend fun createKey() {
        keyInfo.value =
            when (val r = ble.keys.getOrCreate()) {
                is VehicleResult.Success -> r.value.also { key = it }.let { "${it.protection} ${it.publicKey}" }
                is VehicleResult.Uncertain -> "불확실: ${r.error.message}"
                is VehicleResult.Failure -> "실패: ${r.error.message}"
            }
    }

    suspend fun scan() {
        val vin =
            vin() ?: run {
                output.add("VIN 형식 오류")
                return
            }
        val event = withTimeoutOrNull(SCAN_TIMEOUT) { ble.scan(vin).first() }
        output.add(
            when (event) {
                null -> "스캔 시간 초과 (30초)"
                is ScanEvent.Found ->
                    "광고: ${event.advertisement.localName.value} rssi=${event.advertisement.rssi} " +
                        "connectable=${event.advertisement.isConnectable}"
                is ScanEvent.Failed -> "스캔 실패: ${event.error.message}"
            },
        )
    }

    suspend fun connect() {
        val vin =
            vin() ?: run {
                output.add("VIN 형식 오류")
                return
            }
        disconnect() // 이미 연결돼 있으면 먼저 끊는다
        connection.value = "연결 중…"
        when (val r = ble.connect(vin, key)) {
            is VehicleResult.Success -> watch(r.value.also { vehicle = it })
            is VehicleResult.Uncertain -> connection.value = "불확실: ${r.error.message}"
            is VehicleResult.Failure -> connection.value = "실패: ${r.error.message}"
        }
    }

    /** 연결 상태 표시가 [Vehicle.connection]을 따른다 — 차량이 링크를 끊으면 이유와 함께 보인다. */
    private fun watch(v: Vehicle) {
        val keyLabel = if (v.hasKey) "있음" else "없음"
        connectionWatch?.cancel()
        connectionWatch =
            uiScope.launch {
                v.connection.collect { state ->
                    connection.value =
                        when (state) {
                            TransportState.Connected -> "연결됨 (키: $keyLabel)"
                            is TransportState.Disconnected -> state.reason?.let { "링크 끊김: ${it.message} — 연결 끊기를 누르세요" } ?: "연결 안 됨"
                        }
                }
            }
    }

    suspend fun vehicleStatus() = report("VehicleStatus") { it.vehicleStatus() }

    suspend fun listKeys() {
        val v = vehicle ?: return output.add("키 목록: 먼저 연결하세요").let { }
        when (val r = v.listKeys()) {
            is VehicleResult.Success -> {
                keyEntries.clear()
                keyEntries.addAll(r.value)
                output.add("키 목록: ${r.value.size}개")
            }
            is VehicleResult.Uncertain -> output.add("키 목록 불확실: ${r.error.message}")
            is VehicleResult.Failure -> output.add("키 목록 실패: ${r.error.message}")
        }
    }

    /** 이 기기의 키인지. */
    fun isOwn(entry: WhitelistEntry): Boolean = entry.publicKey != null && entry.publicKey == key?.publicKey

    fun entryLabel(entry: WhitelistEntry): String =
        "슬롯 ${entry.slot} ${entry.formFactor} ${entry.role}${if (isOwn(entry)) " (이 기기)" else ""}"

    /** 확인 뒤에 호출한다. 삭제 후 목록을 다시 조회한다. */
    suspend fun removeKey(entry: WhitelistEntry) {
        val pk = entry.publicKey ?: return run { output.add("키 삭제: 공개키 없는 항목") }
        control("키 삭제(슬롯 ${entry.slot})") { it.removeKey(pk) }
        listKeys()
    }

    suspend fun startSession(vcsecOnly: Boolean) {
        val domains = if (vcsecOnly) setOf(VehicleDomain.VCSEC) else VehicleDomain.ALL
        val label = if (vcsecOnly) "세션(VCSEC만)" else "세션(전체)"
        val v = vehicle ?: return run { output.add("$label: 먼저 연결하세요") }
        val mark = TimeSource.Monotonic.markNow()
        report(label) { v.startSession(domains) }
        output.add("  ${sessionsText()} [${mark.elapsedNow().inWholeMilliseconds / MS_PER_SECOND}초]")
    }

    fun sessionsText(): String {
        val map = vehicle?.sessions?.value ?: return "세션: 연결 안 됨"
        return "세션: " + (map.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "없음" })
    }

    fun showSessions() {
        output.add(sessionsText())
    }

    /** 키 등록 요청. 이벤트마다 한 줄을 `output`에 남기고, `Accepted`/`TimedOut`이면 등록 확인을 잇는다. */
    suspend fun pair() {
        val v = vehicle ?: return run { output.add("키 등록: 먼저 연결하세요") }
        val k = key ?: return run { output.add("키 등록: 먼저 키를 만드세요") }
        var confirm = false
        v.addKeyRequest(k.publicKey).collect { event ->
            output.add(
                when (event) {
                    PairingEvent.Sent -> "요청 보냄 — 중앙 콘솔에 키카드를 대고 화면에서 승인하세요"
                    PairingEvent.WaitingForTap -> "카드 태그 대기 중"
                    PairingEvent.Accepted -> "차량이 승인함 — Infotainment 동기화 확인 중".also { confirm = true }
                    is PairingEvent.Rejected -> "거부: ${event.error.message}"
                    is PairingEvent.Unknown -> "모르는 응답 ${event.raw.joinToString("") { "%02x".format(it) }}"
                    is PairingEvent.Failed -> "실패: ${event.error.message}"
                    PairingEvent.TimedOut -> "시간 초과 — 등록 여부를 확인합니다".also { confirm = true }
                },
            )
        }
        if (confirm) confirmEnrollment()
    }

    /** 3초 간격, 최대 2분 동안 Infotainment `sessionInfo`로 등록을 확인한다. */
    suspend fun confirmEnrollment() {
        val v = vehicle ?: return run { output.add("등록 확인: 먼저 연결하세요") }
        val k = key ?: return run { output.add("등록 확인: 먼저 키를 만드세요") }
        var last = "등록 확인: 시도하지 못함"
        val mark = TimeSource.Monotonic.markNow()
        while (mark.elapsedNow() < ENROLL_WINDOW) {
            when (val r = v.sessionInfo(k.publicKey, VehicleDomain.INFOTAINMENT, ENROLL_CALL_TIMEOUT)) {
                is VehicleResult.Success -> return run { output.add("등록 확인됨(Infotainment)") }
                is VehicleResult.Failure ->
                    last =
                        if (r.error is VehicleError.KeyNotPaired) "아직 등록되지 않음(KeyNotPaired) — 2분까지 반복" else "등록 확인 실패: ${r.error.message}"
                is VehicleResult.Uncertain -> last = "등록 확인 불확실: ${r.error.message}"
            }
            delay(ENROLL_INTERVAL)
        }
        output.add("$last (2분 안에 확인되지 않음)")
    }

    /** 제어 명령. `Uncertain`이면 다시 보내지 않고 곧바로 `vehicleStatus()`로 재조회한다(FR-102). */
    private suspend fun control(
        title: String,
        call: suspend (Vehicle) -> VehicleResult<Unit>,
    ) {
        val v = vehicle ?: return run { output.add("$title: 먼저 연결하세요") }
        val mark = TimeSource.Monotonic.markNow()
        val line =
            when (val r = call(v)) {
                is VehicleResult.Success -> "$title: 성공"
                is VehicleResult.Failure -> "$title 실패: ${r.error.message}"
                is VehicleResult.Uncertain -> "$title 불확실: ${r.error.message} → 재조회: ${statusText(v)}"
            }
        output.add("$line [${mark.elapsedNow().inWholeMilliseconds / MS_PER_SECOND}초]")
    }

    private suspend fun statusText(v: Vehicle): String =
        when (val r = v.vehicleStatus()) {
            is VehicleResult.Success ->
                r.value.let { "잠금 ${it.lockState}, 트렁크 ${it.closures.rearTrunk}, 프렁크 ${it.closures.frontTrunk}, 수면 ${it.sleepStatus}" }
            is VehicleResult.Uncertain -> "불확실: ${r.error.message}"
            is VehicleResult.Failure -> "실패: ${r.error.message}"
        }

    suspend fun lock() = control("잠금") { it.lock() }

    suspend fun unlock() = control("해제") { it.unlock() }

    suspend fun openTrunk() = control("트렁크 열기") { it.openTrunk() }

    suspend fun closeTrunk() = control("트렁크 닫기") { it.closeTrunk() }

    suspend fun openFrunk() = control("프렁크 열기") { it.openFrunk() }

    suspend fun wakeUp() = control("깨우기") { it.wakeUp() }

    suspend fun disconnect() {
        connectionWatch?.cancel()
        connectionWatch = null
        vehicle?.disconnect()
        vehicle = null
        keyEntries.clear()
        connection.value = "연결 안 됨"
    }

    private suspend fun report(
        title: String,
        call: suspend (Vehicle) -> VehicleResult<Any>,
    ) {
        val v =
            vehicle ?: run {
                output.add("$title: 먼저 연결하세요")
                return
            }
        output.add(
            when (val r = call(v)) {
                is VehicleResult.Success -> "$title: ${r.value}"
                is VehicleResult.Uncertain -> "$title 불확실: ${r.error.message}"
                is VehicleResult.Failure -> "$title 실패: ${r.error.message}"
            },
        )
    }

    private companion object {
        val SCAN_TIMEOUT = 30.seconds
        val ENROLL_WINDOW = 120.seconds
        val ENROLL_INTERVAL = 3.seconds
        val ENROLL_CALL_TIMEOUT = 5.seconds
        const val MS_PER_SECOND = 1000.0
    }
}
