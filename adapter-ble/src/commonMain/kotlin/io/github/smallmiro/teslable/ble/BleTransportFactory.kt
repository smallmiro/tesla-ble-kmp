// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// NewConnection, NewConnectionFromScanResult, ScanVehicleBeacon (retry loop and fail-fast rules)
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * BLE [TransportFactory]. Go `NewConnectionFromScanResult`의 재시도 루프를 옮기되 시도 사이에 [retryInterval]을 둔다(FR-004,
 * 설계 구체화 3). 재시도하지 않는 실패: [BleAvailability] 검사 실패, 스캔 실패(권한·블루투스 꺼짐), 다른 VIN의 광고,
 * connectable=false(`MaxConnectionsExceeded`), `OpenResult.Failed(retry = false)`. 시간이 다 되면 마지막 오류(없으면 `ScanTimeout`).
 */
@Suppress("LongParameterList") // Go NewConnection(ctx, vin) + 주입 포트(connector/availability/scope/crypto/시간/로거)
internal class BleTransportFactory(
    private val connector: GattConnector,
    private val availability: BleAvailability,
    private val scope: CoroutineScope,
    private val crypto: CryptoPrimitives,
    private val defaultTimeout: Duration = 30.seconds,
    private val retryInterval: Duration = 1.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val logger: TeslaLogger = TeslaLogger.NoOp,
) : TransportFactory {
    private val recentHits = LinkedHashMap<String, ScanHit>()
    private val hitsMutex = Mutex()

    override fun scan(localName: LocalName): Flow<ScanEvent> =
        flow {
            val unavailable = availability.check()
            if (unavailable != null) {
                emit(ScanEvent.Failed(unavailable))
                return@flow
            }
            connector
                .scan(localName)
                .transformWhile { item ->
                    when (item) {
                        is ConnectorScan.Hit -> {
                            remember(item.hit)
                            emit(ScanEvent.Found(item.hit.advertisement))
                            true
                        }

                        is ConnectorScan.Failed -> {
                            emit(ScanEvent.Failed(item.error))
                            false
                        }
                    }
                }.collect { emit(it) }
        }

    override suspend fun connect(
        vin: Vin,
        target: VehicleAdvertisement?,
        options: ConnectOptions,
    ): VehicleResult<Transport> {
        availability.check()?.let { return VehicleResult.Failure(it) }
        val localName = LocalName.of(vin, crypto)
        if (target != null && target.localName != localName) {
            return VehicleResult.Failure(VehicleError.InvalidArgument("advertisement ${target.localName} is not for this VIN"))
        }
        val attempts = Attempts()
        return withTimeoutOrNull(options.timeout ?: defaultTimeout) { connectLoop(vin, localName, target, attempts) }
            ?: VehicleResult.Failure(attempts.lastError ?: VehicleError.TransportError.ScanTimeout)
    }

    private class Attempts {
        var count = 0
        var lastError: VehicleError.TransportError? = null
    }

    private suspend fun connectLoop(
        vin: Vin,
        localName: LocalName,
        target: VehicleAdvertisement?,
        attempts: Attempts,
    ): VehicleResult<Transport> {
        while (true) {
            val hit =
                when (val found = findHit(localName, target)) {
                    is ConnectorScan.Failed -> return VehicleResult.Failure(found.error)
                    is ConnectorScan.Hit -> found.hit
                }
            if (hit.advertisement.isConnectable == false) {
                return VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded)
            }
            attempts.count++
            when (val opened = connector.open(hit)) {
                is OpenResult.Opened -> {
                    return VehicleResult.Success(BleTransport(vin, opened.link, scope, timeSource, logger))
                }

                is OpenResult.Failed -> {
                    if (!opened.retry) return VehicleResult.Failure(opened.error)
                    attempts.lastError = opened.error
                    logger.log(LogLevel.WARN, TAG) { "Failed to connect to vehicle (attempt ${attempts.count}): ${opened.error.message}" }
                    delay(retryInterval)
                }
            }
        }
    }

    private suspend fun findHit(
        localName: LocalName,
        target: VehicleAdvertisement?,
    ): ConnectorScan {
        if (target != null) hitsMutex.withLock { recentHits[target.identifier] }?.let { return ConnectorScan.Hit(it) }
        return connector.scan(localName).first().also { if (it is ConnectorScan.Hit) remember(it.hit) }
    }

    private suspend fun remember(hit: ScanHit) {
        hitsMutex.withLock {
            recentHits.remove(hit.advertisement.identifier)
            recentHits[hit.advertisement.identifier] = hit
            while (recentHits.size > MAX_REMEMBERED_HITS) recentHits.remove(recentHits.keys.first())
        }
    }

    private companion object {
        const val MAX_REMEMBERED_HITS = 16
        const val TAG = "ble"
    }
}
