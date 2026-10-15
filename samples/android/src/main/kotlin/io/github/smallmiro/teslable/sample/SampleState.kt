package io.github.smallmiro.teslable.sample

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import io.github.smallmiro.teslable.TeslaBle
import io.github.smallmiro.teslable.Vehicle
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.flow.first

/** 샘플 화면 상태(iOS SampleModel과 같은 동작). */
class SampleState(
    private val ble: TeslaBle,
) {
    val vinText = mutableStateOf("")
    val keyInfo = mutableStateOf("키 없음")
    val connection = mutableStateOf("연결 안 됨")
    val output = mutableStateListOf<String>()
    private var key: VehicleKey? = null
    private var vehicle: Vehicle? = null

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
        output.add(
            when (val e = ble.scan(vin).first()) {
                is ScanEvent.Found ->
                    "광고: ${e.advertisement.localName.value} rssi=${e.advertisement.rssi} " +
                        "connectable=${e.advertisement.isConnectable}"
                is ScanEvent.Failed -> "스캔 실패: ${e.error.message}"
            },
        )
    }

    suspend fun connect() {
        val vin =
            vin() ?: run {
                output.add("VIN 형식 오류")
                return
            }
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
}
