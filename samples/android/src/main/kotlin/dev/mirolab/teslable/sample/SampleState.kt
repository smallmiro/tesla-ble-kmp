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
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Control 탭의 상태(iOS SampleModel과 같은 동작). 연결과 키는 모니터가 가진 것([MonitorEngine])을 그대로 쓴다:
 * 차량에는 연결 하나만 열고, 이 탭의 버튼은 그 연결 위에서 호출을 보낸다.
 */
class SampleState(
    private val engine: MonitorEngine,
) {
    private val ble: TeslaBle = engine.ble
    val keyInfo = mutableStateOf("키 없음")
    val output = mutableStateListOf<String>()

    /** 호출이 진행 중이면 true — 버튼을 막아 호출이 겹치지 않게 한다. */
    val busy = mutableStateOf(false)

    /** 마지막 키 목록(항목마다 "삭제" 버튼). */
    val keyEntries = mutableStateListOf<WhitelistEntry>()

    /** 삭제 확인 대기 중인 항목. */
    val pendingRemoval = mutableStateOf<WhitelistEntry?>(null)
    private val key: VehicleKey? get() = engine.currentKey()
    private val vehicle: Vehicle? get() = engine.currentVehicle()

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

    private fun vin(): Vin? = engine.parsedVin()

    /** 모니터 키(`teslable-monitor`, `AFTER_FIRST_UNLOCK`)를 읽거나 만든다. */
    suspend fun createKey() {
        keyInfo.value =
            when (val r = engine.ensureKey()) {
                is VehicleResult.Success -> "${r.value.alias}: ${r.value.protection}, 접근성 ${r.value.accessibility}\n${r.value.publicKey}"
                is VehicleResult.Uncertain -> "불확실: ${r.error.message}"
                is VehicleResult.Failure -> "실패: ${r.error.message}"
            }
    }

    suspend fun scan() {
        val vin =
            vin() ?: run {
                output.add("저장된 VIN이 없습니다(Live 탭에서 저장)")
                return
            }
        val event = withTimeoutOrNull(SCAN_TIMEOUT) { ble.scan(vin).first() }
        output.add(
            when (event) {
                null -> {
                    "스캔 시간 초과 (30초)"
                }

                is ScanEvent.Found -> {
                    "광고: ${event.advertisement.localName.value} rssi=${event.advertisement.rssi} " +
                        "connectable=${event.advertisement.isConnectable}"
                }

                is ScanEvent.Failed -> {
                    "스캔 실패: ${event.error.message}"
                }
            },
        )
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

            is VehicleResult.Uncertain -> {
                output.add("키 목록 불확실: ${r.error.message}")
            }

            is VehicleResult.Failure -> {
                output.add("키 목록 실패: ${r.error.message}")
            }
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
                is VehicleResult.Success -> {
                    return run { output.add("등록 확인됨(Infotainment)") }
                }

                is VehicleResult.Failure -> {
                    last =
                        if (r.error is VehicleError.KeyNotPaired) "아직 등록되지 않음(KeyNotPaired) — 2분까지 반복" else "등록 확인 실패: ${r.error.message}"
                }

                is VehicleResult.Uncertain -> {
                    last = "등록 확인 불확실: ${r.error.message}"
                }
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
            is VehicleResult.Success -> {
                r.value.let { "잠금 ${it.lockState}, 트렁크 ${it.closures.rearTrunk}, 프렁크 ${it.closures.frontTrunk}, 수면 ${it.sleepStatus}" }
            }

            is VehicleResult.Uncertain -> {
                "불확실: ${r.error.message}"
            }

            is VehicleResult.Failure -> {
                "실패: ${r.error.message}"
            }
        }

    suspend fun lock() = control("잠금") { it.lock() }

    suspend fun unlock() = control("해제") { it.unlock() }

    suspend fun openTrunk() = control("트렁크 열기") { it.openTrunk() }

    suspend fun closeTrunk() = control("트렁크 닫기") { it.closeTrunk() }

    suspend fun openFrunk() = control("프렁크 열기") { it.openFrunk() }

    suspend fun wakeUp() = control("깨우기") { it.wakeUp() }

    /** 연결을 끊고 자동 재연결도 멈춘다(SFR-012). 다시 연결할 때까지 재연결하지 않는다. */
    suspend fun disconnect() {
        engine.disconnect()
        keyEntries.clear()
        output.add("연결 끊음 — 자동 재연결 중지")
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
