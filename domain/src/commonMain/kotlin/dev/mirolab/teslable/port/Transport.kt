// Ported from vehicle-command@a4b43c1 pkg/connector/connector.go (Apache-2.0) — Connector, AuthMethod (GCM only, D6)
package dev.mirolab.teslable.port

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** Go `connector.AuthMethod`. BLE는 AES-GCM만 쓰므로 HMAC은 없다(D6). */
public enum class AuthMethod {
    /** 인증 없음(핸드셰이크, 정보 요청). Go `AuthMethodNone`. */
    NONE,

    /** AES-GCM 개인화 인증(`Signer.encrypt`). Go `AuthMethodGCM`. */
    GCM,
}

/** 전송 연결 상태. M3 `Vehicle.connection`이 노출한다. */
public sealed interface TransportState {
    /** 연결됨. */
    public data object Connected : TransportState

    /** 끊김. [reason]은 원인을 알 때만. */
    public data class Disconnected(
        /** 끊긴 원인. */
        public val reason: VehicleError.TransportError?,
    ) : TransportState
}

/**
 * 차량과 데이터그램(직렬화된 `RoutableMessage`)을 주고받는 포트. Go `connector.Connector`.
 * [incoming]은 재조립된 메시지 단위이며 수집자는 `Dispatcher`의 수신 코루틴 하나뿐이다.
 * 오류는 예외가 아니라 값이다(ADR-0006): [send]가 `Uncertain`이면 차량이 받았을 수 있다는 뜻이다(Go `MayHaveSucceeded`).
 * 구현은 연결 직후부터 수신 메시지를 모아 두어야 한다([incoming] 참고).
 */
public interface Transport {
    /** 연결된 차량의 VIN(TLV PERSONALIZATION). Go `VIN()`. */
    public val vin: Vin

    /**
     * 차량이 보낸 메시지(재조립된 단위). 연결이 끝나면 완료된다. Go `Receive()`.
     * **버퍼링 계약:** 구현은 연결 직후부터 메시지를 모아 두어야 한다 — 수집자(디스패처의 수신 코루틴)가 구독하기 전에 도착한
     * 메시지도 버리지 않고 넘긴다(M3 `BleTransport`는 `Channel(64)`). 버퍼가 가득 차면 버리고 로그를 남긴다(Go `flush`).
     */
    public val incoming: Flow<ByteArray>

    /** 연결 상태. */
    public val state: StateFlow<TransportState>

    /** 재전송 간격(BLE 1초). Go `RetryInterval()`. */
    public val retryInterval: Duration

    /** 요청 전송 후 세션정보를 받아들이는 최대 지연(BLE 4초). Go `AllowedLatency()`. */
    public val allowedLatency: Duration

    /** 메시지 하나를 보낸다(프레이밍·분할 포함). Go `Send`. */
    public suspend fun send(message: ByteArray): VehicleResult<Unit>

    /** 연결을 닫는다. 멱등. Go `Close()`. */
    public suspend fun close()
}
