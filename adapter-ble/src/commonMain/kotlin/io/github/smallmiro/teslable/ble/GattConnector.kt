// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// ScanVehicleBeacon, tryToConnect (as an internal port)
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.flow.Flow

/** 스캔으로 찾은 광고와 플랫폼 객체(Kable `PlatformAdvertisement`; 가짜에서는 null). */
internal class ScanHit(
    val advertisement: VehicleAdvertisement,
    val platform: Any?,
)

/** 커넥터 스캔 사건. */
internal sealed interface ConnectorScan {
    data class Hit(
        val hit: ScanHit,
    ) : ConnectorScan

    data class Failed(
        val error: VehicleError.TransportError,
    ) : ConnectorScan
}

/** 링크 열기 결과. [Failed.retry]는 Go `tryToConnect`의 `retry` 반환값. */
internal sealed interface OpenResult {
    data class Opened(
        val link: GattLink,
    ) : OpenResult

    data class Failed(
        val error: VehicleError.TransportError,
        val retry: Boolean,
    ) : OpenResult
}

/** 플랫폼 BLE 스택(Kable)에 대한 내부 포트. 실패한 열기 시도에서 연 연결은 구현이 닫는다(설계 구체화 3). */
internal interface GattConnector {
    /** [localName]과 정확히 같은 광고를 흘려보낸다. 수집을 멈추면 스캔이 멈춘다. */
    fun scan(localName: LocalName): Flow<ConnectorScan>

    /** 연결 → 서비스·특성 탐색 → 0213 구독 → MTU. */
    suspend fun open(hit: ScanHit): OpenResult
}
