package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Kable 기반 BLE [TransportFactory]를 만든다.
 *
 * [scope]는 앱 수명의 스코프이며 **반드시 `SupervisorJob`을 가져야 한다**(연결 하나의 실패가 다른 연결·구독을 취소하지 않도록).
 * 링크 구독·끊김 감시·정리 작업이 이 스코프에서 돈다. [availability]는 플랫폼 권한 검사다
 * (Android: [androidBleAvailability], iOS: [appleBleAvailability], 그 밖: [BleAvailability.ALWAYS]).
 */
public fun kableTransportFactory(
    scope: CoroutineScope,
    crypto: CryptoPrimitives,
    availability: BleAvailability,
    logger: TeslaLogger = TeslaLogger.NoOp,
    defaultTimeout: Duration = 30.seconds,
): TransportFactory =
    BleTransportFactory(
        connector = KableGattConnector(scope, DEFAULT_SUBSCRIPTION, logger),
        availability = availability,
        scope = scope,
        crypto = crypto,
        defaultTimeout = defaultTimeout,
        logger = logger,
    )
