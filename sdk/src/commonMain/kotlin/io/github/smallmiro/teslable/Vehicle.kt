package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * 연결된 차량(SDD §2.3). 연결 하나에 객체 하나 — [disconnect] 뒤에는 명령이 `Failure(NotConnected)`이고, 다시 쓰려면
 * `TeslaBle.connect`로 새로 연결한다(설계 구체화 13). M4/M5가 제어·조회 메서드를 더한다. 모든 `timeout`은 null이면 설정 기본값.
 */
public interface Vehicle {
    /** VIN. */
    public val vin: Vin

    /** 개인키로 연결했는지(인증 명령·핸드셰이크 가능 여부). */
    public val hasKey: Boolean

    /** BLE 연결 상태. 링크가 끊겨도(`Disconnected`) 세션 키와 스코프를 풀려면 [disconnect]를 불러야 한다. */
    public val connection: StateFlow<TransportState>

    /** 도메인별 세션 상태. */
    public val sessions: StateFlow<Map<VehicleDomain, SessionStatus>>

    /** 핸드셰이크(FR-012). 캐시로 준비된 도메인은 요청 없이 성공. */
    public suspend fun startSession(
        domains: Set<VehicleDomain> = VehicleDomain.ALL,
        timeout: Duration? = null,
    ): VehicleResult<Unit>

    /** 연결을 끊는다(FR-005). 세션 캐시를 저장한 뒤 세션 키를 지운다. 멱등. */
    public suspend fun disconnect()

    /** 잠금·개폐·수면 상태(FR-031). 인증 없음. */
    public suspend fun vehicleStatus(timeout: Duration? = null): VehicleResult<VehicleStatus>

    /** 화이트리스트 요약(FR-032). 인증 없음. */
    public suspend fun keySummary(timeout: Duration? = null): VehicleResult<WhitelistSummary>

    /** 등록된 키 목록(FR-025). 인증 없음. [timeout]은 전체 시간. */
    public suspend fun listKeys(timeout: Duration? = null): VehicleResult<List<WhitelistEntry>>
}
