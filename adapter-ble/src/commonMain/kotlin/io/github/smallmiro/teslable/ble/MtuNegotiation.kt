package io.github.smallmiro.teslable.ble

import com.juul.kable.Peripheral
import io.github.smallmiro.teslable.port.TeslaLogger

/** 연결된 [peripheral]의 블록 길이. 실패하면 [BlockLength.DEFAULT](Go `ble.go:350-352`, 재시도 없음). */
internal expect suspend fun negotiateBlockLength(
    peripheral: Peripheral,
    logger: TeslaLogger,
): Int

/** 현재 플랫폼이 Android인지(CCCD 직접 쓰기는 Android에서만 가능). */
internal expect val isAndroid: Boolean
