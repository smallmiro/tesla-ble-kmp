package dev.mirolab.teslable

import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.model.WhitelistEntry
import dev.mirolab.teslable.model.WhitelistSummary
import dev.mirolab.teslable.port.TransportState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * 연결된 차량(SDD §2.3). 연결 하나에 객체 하나 — [disconnect] 뒤에는 명령이 `Failure(NotConnected)`이고, 다시 쓰려면
 * `TeslaBle.connect`로 새로 연결한다(설계 구체화 13). M5가 Infotainment 제어·조회 메서드를 더한다. 모든 `timeout`은 null이면 설정 기본값.
 */
@Suppress("TooManyFunctions") // 공개 API 1:1 — 조회, 키, VCSEC 제어 12종(M5가 더 늘린다)
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

    /**
     * [publicKey]에 대한 [domain]의 세션정보(FR-024). 인증 없음. 등록돼 있지 않으면 `Failure(KeyNotPaired)`.
     * 페어링 확정은 `sessionInfo(publicKey, INFOTAINMENT)`로 확인한다. 이 연결의 키로 물으면 세션이 만들어져 [sessions]에도 반영된다.
     */
    public suspend fun sessionInfo(
        publicKey: PublicKeyBytes,
        domain: VehicleDomain,
        timeout: Duration? = null,
    ): VehicleResult<SessionInfoSummary>

    /**
     * 키 등록을 요청한다(FR-022, FR-023). 사용자가 키카드를 태그하고 차량 화면에서 승인해야 한다. 차가운 Flow — 수집할 때마다 한 번 보낸다.
     * 이벤트: `Sent`, `WaitingForTap`, `Unknown(raw)`가 중간에 오고, 끝 이벤트(`Accepted`, `Rejected`, `Failed`, `TimedOut`) 뒤 Flow가 끝난다.
     * 등록 확정은 `sessionInfo(publicKey, INFOTAINMENT)`. [timeout]이 null이면 [TeslaBleConfig.pairingTimeout].
     */
    public fun addKeyRequest(
        publicKey: PublicKeyBytes,
        role: KeyRole = KeyRole.OWNER,
        formFactor: KeyFormFactor = platformKeyFormFactor(),
        timeout: Duration? = null,
    ): Flow<PairingEvent>

    /** 키를 바로 등록한다(FR-027, 이미 승인된 세션에서). VCSEC(GCM). 세션이 없으면 `NoSession`. 실패는 `InsufficientPrivileges`/`KeychainRejected`. `Uncertain`이면 `listKeys()`로 확인. */
    public suspend fun addKey(
        publicKey: PublicKeyBytes,
        role: KeyRole = KeyRole.OWNER,
        formFactor: KeyFormFactor = platformKeyFormFactor(),
        timeout: Duration? = null,
    ): VehicleResult<Unit>

    /** 키를 삭제한다(FR-026). VCSEC(GCM). 실패는 `InsufficientPrivileges`/`KeychainRejected`. `Uncertain`이면 `listKeys()`로 확인. */
    public suspend fun removeKey(
        publicKey: PublicKeyBytes,
        timeout: Duration? = null,
    ): VehicleResult<Unit>

    /** 잠금 (FR-040). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun lock(timeout: Duration? = null): VehicleResult<Unit>

    /** 잠금 해제 (FR-041). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun unlock(timeout: Duration? = null): VehicleResult<Unit>

    /** 차량 깨우기(VCSEC 세션만 필요) (FR-042). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun wakeUp(timeout: Duration? = null): VehicleResult<Unit>

    /** 원격 주행 허용 (FR-043). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun remoteDrive(timeout: Duration? = null): VehicleResult<Unit>

    /** 자동 잠금(Auto Secure) (FR-044). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun autoSecureVehicle(timeout: Duration? = null): VehicleResult<Unit>

    /** 트렁크 열기(`actuateTrunk`와 같다 — 토글) (FR-045). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun openTrunk(timeout: Duration? = null): VehicleResult<Unit>

    /** 트렁크 토글 (FR-045). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun actuateTrunk(timeout: Duration? = null): VehicleResult<Unit>

    /** 트렁크 닫기 (FR-045). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun closeTrunk(timeout: Duration? = null): VehicleResult<Unit>

    /** 프렁크 열기(닫기 명령은 없다) (FR-046). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun openFrunk(timeout: Duration? = null): VehicleResult<Unit>

    /** 토노 커버 열기(Cybertruck) (FR-047). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun openTonneau(timeout: Duration? = null): VehicleResult<Unit>

    /** 토노 커버 닫기(Cybertruck) (FR-047). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun closeTonneau(timeout: Duration? = null): VehicleResult<Unit>

    /** 토노 커버 멈춤(Cybertruck) (FR-047). VCSEC(GCM). 세션이 없으면 `NoSession`. `Uncertain`이면 다시 보내지 말고 `vehicleStatus()`로 확인(FR-102). */
    public suspend fun stopTonneau(timeout: Duration? = null): VehicleResult<Unit>
}
