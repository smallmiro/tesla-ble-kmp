package dev.mirolab.teslable.ble

import com.juul.kable.Peripheral
import dev.mirolab.teslable.port.TeslaLogger

internal actual val isAndroid: Boolean = false

/** Kable JVM(btleplug)은 MTU가 23 고정이다. */
internal actual suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int = BlockLength.DEFAULT
