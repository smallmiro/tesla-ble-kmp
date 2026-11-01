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

/**
 * 연결이 스스로 돌린 스캔에서 본 값(FR-038, #134). 원본 Go에 없는 관찰 값이며 연결 동작을 바꾸지 않는다.
 * 광고 이름(VIN에서 나온 값)과 기기 식별자는 일부러 담지 않는다 — 앱이 그대로 로그에 남겨도 VIN 규칙을 어기지 않게.
 */
public data class ConnectScanObservation(
    /**
     * 연결 호출을 시작하고 이 광고를 받을 때까지의 시간(밀리초, 라이브러리가 받은 시각이지 전파 수신 시각이 아니다). 앞선 시도와
     * 그 사이 대기를 포함한다. 단조 시계라 기기가 잠들면 멈출 수 있어 벽시계 시각으로 되돌릴 수 없다. Swift에서 쓰려고 `Long`이다
     * (non-null `Duration`은 Swift에 내부 표현 그대로 `Int64`로 간다).
     */
    public val hitAfterMillis: Long,
    /** 수신 신호 세기(dBm). 플랫폼이 값을 주지 않았으면 null(iOS 127, JVM `Int.MIN_VALUE`. [VehicleAdvertisement.rssi]는 그대로 둔다). */
    public val rssi: Int?,
    /** 이 링크를 연 열기 시도 번호(1부터). 1보다 크면 앞선 열기가 실패했다는 뜻이지, 다시 스캔했다는 뜻은 아니다. */
    public val openAttempt: Int,
)

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
