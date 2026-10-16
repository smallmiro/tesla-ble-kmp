// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// ScanResult, ScanVehicleBeacon, NewConnectionFromScanResult as a port (values instead of errors, ADR-0006)
package dev.mirolab.teslable.port

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.transport.LocalName
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** 스캔으로 찾은 차량 광고(Go `ScanResult`, FR-001/FR-030). */
public data class VehicleAdvertisement(
    /** 광고 로컬 이름(`S…C`). */
    public val localName: LocalName,
    /** 플랫폼 기기 식별자(Android MAC, iOS UUID 문자열). 표시·재연결용, 비밀 아님. */
    public val identifier: String,
    /** 수신 신호 세기(dBm). */
    public val rssi: Int,
    /** 연결 가능 여부. false면 슬롯 초과(`MaxConnectionsExceeded`), null이면 플랫폼이 알려주지 않음(시도한다, 설계 구체화 2). */
    public val isConnectable: Boolean?,
)

/** 스캔 흐름의 사건. 오류도 값으로 흐른다(설계 구체화 1). */
public sealed interface ScanEvent {
    /** 광고를 찾음. */
    public data class Found(
        /** 찾은 광고. */
        public val advertisement: VehicleAdvertisement,
    ) : ScanEvent

    /** 스캔을 시작하거나 계속할 수 없음(권한·블루투스 꺼짐 등). 이 사건 뒤 흐름은 끝난다. */
    public data class Failed(
        /** 원인. */
        public val error: VehicleError.TransportError,
    ) : ScanEvent
}

/** 연결 옵션. */
public data class ConnectOptions(
    /** 스캔·연결·구독 전체 시간. null이면 파사드 설정(`TeslaBleConfig.connectTimeout`). */
    public val timeout: Duration? = null,
)

/** 차량 스캔과 연결(Go `ble.ScanVehicleBeacon` + `ble.NewConnectionFromScanResult`). */
public interface TransportFactory {
    /** [localName]과 정확히 같은 이름의 광고를 흘려보낸다. 수집을 멈추면 스캔도 멈춘다. */
    public fun scan(localName: LocalName): Flow<ScanEvent>

    /**
     * [vin] 차량에 연결한다. [target]이 있으면 그 광고로 바로 연결하고, 없으면 스캔해서 첫 광고를 쓴다.
     * 재시도 가능한 실패는 [options]의 시간 안에서 다시 시도한다(FR-004). 실패는 값이다.
     */
    public suspend fun connect(
        vin: Vin,
        target: VehicleAdvertisement?,
        options: ConnectOptions,
    ): VehicleResult<Transport>
}
