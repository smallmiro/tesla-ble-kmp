package io.github.smallmiro.teslable.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import io.github.smallmiro.teslable.TeslaBle
import io.github.smallmiro.teslable.Vehicle
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

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
    private var key: VehicleKey? = null
    private var vehicle: Vehicle? = null

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
        connection.value =
            when (val r = ble.connect(vin, key)) {
                is VehicleResult.Success -> "연결됨 (키: ${if (key == null) "없음" else "있음"})".also { vehicle = r.value }
                is VehicleResult.Uncertain -> "불확실: ${r.error.message}"
                is VehicleResult.Failure -> "실패: ${r.error.message}"
            }
    }

    suspend fun vehicleStatus() = report("VehicleStatus") { it.vehicleStatus() }

    suspend fun listKeys() = report("키 목록") { it.listKeys() }

    suspend fun disconnect() {
        vehicle?.disconnect()
        vehicle = null
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
    }
}
