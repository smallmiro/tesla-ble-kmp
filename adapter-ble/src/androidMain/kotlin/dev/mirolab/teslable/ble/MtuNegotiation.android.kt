package dev.mirolab.teslable.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import kotlin.coroutines.cancellation.CancellationException

private const val REQUESTED_MTU = 517 // Android 상한(SDD §2.4). Go는 ble.MaxMTU를 요청한다(ble.go:349)

internal actual val isAndroid: Boolean = true

internal actual suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int =
    try {
        BlockLength.fromAttMtu((peripheral as AndroidPeripheral).requestMtu(REQUESTED_MTU))
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        // Go ble.go:350-352: 경고 후 기본값
        logger.log(LogLevel.WARN, BLE_LOG_TAG) { "Failed to set BLE MTU, using ${BlockLength.DEFAULT}-byte blocks: ${e::class.simpleName}" }
        BlockLength.DEFAULT
    }
