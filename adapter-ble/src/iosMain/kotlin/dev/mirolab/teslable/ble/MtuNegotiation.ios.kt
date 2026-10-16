package dev.mirolab.teslable.ble

import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import dev.mirolab.teslable.port.TeslaLogger

internal actual val isAndroid: Boolean = false

/** iOS는 MTU 협상 API가 없다(ADR-0003). 시스템이 협상한 값에서 쓰기 최대 길이를 읽는다. */
internal actual suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int = BlockLength.fromMaxWriteLength(peripheral.maximumWriteValueLengthForType(WriteType.WithResponse))
