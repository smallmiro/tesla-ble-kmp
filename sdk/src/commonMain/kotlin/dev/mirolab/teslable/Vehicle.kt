package dev.mirolab.teslable

import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleEvent
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

    /**
     * 차량이 요청 없이 보낸 **평문** VCSEC 브로드캐스트(FR-035). 원본 Go는 이 프레임을 버린다 — 원본에 없는 동작이다. 키도 세션도
     * 필요 없다(차량이 처음부터 평문으로 보낸 프레임만 나오고, 라이브러리가 복호화한 것은 나오지 않는다 — FR-112).
     *
     * - 연결 하나의 hot 스트림이다. 수집을 시작할 때 구독하며 그 전에 온 브로드캐스트는 받지 못한다(replay 0). 여러 곳에서 수집하면
     *   각자 같은 이벤트를 받는다.
     * - 프레임 하나에서 `vehicleStatus`는 [VehicleEvent.Status], `authenticationRequest`는 [VehicleEvent.AuthenticationRequest]가
     *   된다(둘 다 있으면 이 순서로 둘). 둘 다 없으면 [VehicleEvent.Unrecognized]. 차량은 바뀐 것이 없어도 상태를 반복해 보낸다 —
     *   반복은 변화가 아니며 비교는 앱이 한다.
     * - [connection]이 `Disconnected`가 되거나(이유와 상관없이) [disconnect]가 불리면, 먼저 온 쪽에서 **정상 완료**한다(예외 없음).
     *   이미 끝난 연결에서 수집하면 바로 끝난다. 그 순간 아직 넘겨주지 못한 프레임은 버려질 수 있다. 끊긴 이유와 재연결 판단은
     *   [connection]으로 하고(흐름 완료로 판단하지 않는다), 링크 손실 뒤에도 [disconnect]는 불러야 한다. 새 연결은 새 [Vehicle]의 새 흐름이다.
     * - 수집이 느리면 라이브러리가 프레임 64개까지 쌓아 두고, 넘치면 새 프레임을 버리고 WARN 로그를 남긴다. 버린 프레임은 이 연결의 모든
     *   수집자에게서 빠진다. 오류가 아니다.
     * - [VehicleEvent.raw]는 받은 프레임 그대로이며 VIN을 가리지 않는다 — 저장·공유 전에 앱이 가린다.
     */
    public val events: Flow<VehicleEvent>

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

    /**
     * 키를 바로 등록한다(FR-027, 이미 승인된 세션에서). VCSEC(GCM). 세션이 없으면 `NoSession`.
     * 실패는 `InsufficientPrivileges`/`KeychainRejected`. `Uncertain`이면 다시 보내지 말고 `listKeys()`로 확인.
     */
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
