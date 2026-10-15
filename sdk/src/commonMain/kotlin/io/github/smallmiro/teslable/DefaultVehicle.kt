package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** [Vehicle] 구현: [VehicleSession] + 상태 흐름. [scope]는 연결 하나의 수명이며 [disconnect]가 취소하고 [onDisconnected]로 알린다. */
@Suppress("LongParameterList") // 연결 하나를 이루는 조각들 — TeslaBle.connect만 만든다
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
    }

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
}
