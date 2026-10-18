package dev.mirolab.teslable

import dev.mirolab.teslable.application.dispatcher.SessionEvent
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.SessionInfoSummary
import dev.mirolab.teslable.model.VehicleDomain
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.VehicleStatus
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.model.WhitelistEntry
import dev.mirolab.teslable.model.WhitelistSummary
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** [Vehicle] 구현: [VehicleSession] + 상태 흐름. [scope]는 연결 하나의 수명이며 [disconnect]가 취소하고 [onDisconnected]로 알린다. */
@Suppress("LongParameterList", "TooManyFunctions") // 조각들은 TeslaBle.connect만 만들고, 메서드는 Vehicle 공개 API와 1:1
internal class DefaultVehicle(
    override val vin: Vin,
    override val hasKey: Boolean,
    private val transport: Transport,
    private val session: VehicleSession,
    restored: Set<VehicleDomain>,
    private val scope: CoroutineScope,
    private val config: TeslaBleConfig,
    private val onDisconnected: suspend (DefaultVehicle) -> Unit = {},
) : Vehicle {
    private val states =
        MutableStateFlow(
            VehicleDomain.ALL.associateWith { if (it in restored) SessionStatus.Ready(fromCache = true) else SessionStatus.None },
        )

    override val connection: StateFlow<TransportState> = transport.state
    override val sessions: StateFlow<Map<VehicleDomain, SessionStatus>> = states.asStateFlow()

    private val closed = MutableStateFlow(false)

    private fun resetSessions() {
        states.value = VehicleDomain.ALL.associateWith { SessionStatus.None }
    }

    init {
        // 차량이 링크를 끊었는데(이유 있는 Disconnected) 아무도 close()하지 않으면 플랫폼 링크가 샌다 — 전송만 닫는다(멱등).
        // 의도한 종료는 reason == null이라 여기 걸리지 않으며, 세션 정리·캐시 저장은 disconnect()의 몫이다.
        scope.launch {
            connection.first { it is TransportState.Disconnected && it.reason != null }
            transport.close()
            resetSessions() // connection과 sessions가 어긋나지 않게
        }
        // 세션 사건이 오면 그 도메인의 표시를 실제 SessionState에 맞춘다(설계 구체화 2·3).
        // 사건은 신호일 뿐이고 원천은 session.isReady(domain)이다 — 사건이 유실돼도 다음 사건이나 NoSession 결과가 다시 맞춘다.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.sessionEvents.collect { event ->
                val domain =
                    when (event) {
                        is SessionEvent.StaleCache -> event.domain
                        is SessionEvent.Updated -> event.domain
                    }
                VehicleDomain.of(domain)?.let(::reconcile)
            }
        }
    }

    /** [domain]의 표시를 실제 세션에 맞춘다. 핸드셰이크 중이거나 disconnect 뒤에는 건드리지 않는다. */
    private fun reconcile(domain: VehicleDomain) {
        if (closed.value) return
        val ready = session.isReady(domain.wire)
        states.update { current ->
            when (current[domain]) {
                SessionStatus.Handshaking -> current
                is SessionStatus.Ready -> if (ready) current else current + (domain to SessionStatus.None)
                else -> if (ready) current + (domain to SessionStatus.Ready(fromCache = false)) else current
            }
        }
    }

    /** 명령이 `NoSession`으로 끝나면 표시를 다시 맞춘다(사건 유실 대비). */
    private fun <T> VehicleResult<T>.reconciling(domain: VehicleDomain): VehicleResult<T> =
        also { if (it is VehicleResult.Failure && it.error == VehicleError.NoSession) reconcile(domain) }

    override suspend fun startSession(
        domains: Set<VehicleDomain>,
        timeout: Duration?,
    ): VehicleResult<Unit> {
        states.update { current ->
            current.mapValues { (d, s) -> if (d in domains && s !is SessionStatus.Ready) SessionStatus.Handshaking else s }
        }
        var success = false
        try {
            val result = session.startSession(domains.map { it.wire }.toSet(), timeout ?: config.handshakeTimeout)
            success = result is VehicleResult.Success
            return result
        } finally {
            // 취소·예외·실패는 None으로 되돌린다(Ready는 유지). disconnect와 겹쳤으면(closed) 전부 None — 같은 update 안에서 읽는다.
            states.update { current ->
                current.mapValues { (d, s) ->
                    when {
                        closed.value -> SessionStatus.None
                        d !in domains || s is SessionStatus.Ready -> s
                        success -> SessionStatus.Ready(fromCache = false)
                        else -> SessionStatus.None
                    }
                }
            }
        }
    }

    override suspend fun disconnect() {
        closed.value = true // 상태를 지우기 전에 — 진행 중인 startSession이 Ready를 되쓰지 못하게
        try {
            session.disconnect()
        } finally {
            resetSessions()
            scope.cancel()
            withContext(NonCancellable) { onDisconnected(this@DefaultVehicle) } // TeslaBle.close()가 다시 끊지 않게 목록에서 뺀다
        }
    }

    override suspend fun vehicleStatus(timeout: Duration?): VehicleResult<VehicleStatus> =
        session.info.vehicleStatus(timeout ?: config.commandTimeout)

    override suspend fun keySummary(timeout: Duration?): VehicleResult<WhitelistSummary> =
        session.info.keySummary(timeout ?: config.commandTimeout)

    override suspend fun listKeys(timeout: Duration?): VehicleResult<List<WhitelistEntry>> =
        session.info.listKeys(timeout ?: config.commandTimeout)

    override suspend fun sessionInfo(
        publicKey: PublicKeyBytes,
        domain: VehicleDomain,
        timeout: Duration?,
    ): VehicleResult<SessionInfoSummary> = session.keys.sessionInfo(publicKey, domain.wire, timeout ?: config.commandTimeout)

    override fun addKeyRequest(
        publicKey: PublicKeyBytes,
        role: KeyRole,
        formFactor: KeyFormFactor,
        timeout: Duration?,
    ): Flow<PairingEvent> = session.pairing.addKeyRequest(publicKey, role, formFactor, timeout ?: config.pairingTimeout)

    override suspend fun addKey(
        publicKey: PublicKeyBytes,
        role: KeyRole,
        formFactor: KeyFormFactor,
        timeout: Duration?,
    ): VehicleResult<Unit> =
        session.keys.addKey(publicKey, role, formFactor, timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun removeKey(
        publicKey: PublicKeyBytes,
        timeout: Duration?,
    ): VehicleResult<Unit> = session.keys.removeKey(publicKey, timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun lock(timeout: Duration?): VehicleResult<Unit> =
        session.control.lock(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun unlock(timeout: Duration?): VehicleResult<Unit> =
        session.control.unlock(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun wakeUp(timeout: Duration?): VehicleResult<Unit> =
        session.control.wakeUp(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun remoteDrive(timeout: Duration?): VehicleResult<Unit> =
        session.control.remoteDrive(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun autoSecureVehicle(timeout: Duration?): VehicleResult<Unit> =
        session.control.autoSecureVehicle(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun openTrunk(timeout: Duration?): VehicleResult<Unit> =
        session.control.openTrunk(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun actuateTrunk(timeout: Duration?): VehicleResult<Unit> =
        session.control.actuateTrunk(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun closeTrunk(timeout: Duration?): VehicleResult<Unit> =
        session.control.closeTrunk(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun openFrunk(timeout: Duration?): VehicleResult<Unit> =
        session.control.openFrunk(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun openTonneau(timeout: Duration?): VehicleResult<Unit> =
        session.control.openTonneau(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun closeTonneau(timeout: Duration?): VehicleResult<Unit> =
        session.control.closeTonneau(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)

    override suspend fun stopTonneau(timeout: Duration?): VehicleResult<Unit> =
        session.control.stopTonneau(timeout ?: config.commandTimeout).reconciling(VehicleDomain.VCSEC)
}
