// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// NewConnection, NewConnectionFromScanResult, ScanVehicleBeacon (retry loop and fail-fast rules)
package dev.mirolab.teslable.ble

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.ConnectOptions
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.ScanEvent
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.Transport
import dev.mirolab.teslable.port.TransportFactory
import dev.mirolab.teslable.port.VehicleAdvertisement
import dev.mirolab.teslable.transport.LocalName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        // withTimeoutOrNull은 블록이 값을 돌려준 직후에도 시간 초과로 null을 낼 수 있다(다른 스레드의 타이머) — 만든 전송을 밖에서 쥐고 잃으면 닫는다
        val result =
            try {
                withTimeoutOrNull(options.timeout ?: defaultTimeout) { connectLoop(vin, localName, target, attempts) }
            } catch (e: CancellationException) {
                attempts.created?.let { withContext(NonCancellable) { it.close() } }
                throw e
            }
        if (result == null) attempts.created?.let { withContext(NonCancellable) { it.close() } }
        return result ?: VehicleResult.Failure(attempts.lastError ?: VehicleError.TransportError.ScanTimeout)
    }

    private class Attempts {
        var count = 0
        var lastError: VehicleError.TransportError? = null
        var created: Transport? = null
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
                    null -> {
                        delay(retryInterval) // 광고 없이 끝난 스캔은 재시도 가능한 놓침
                        continue
                    }

                    is ConnectorScan.Failed -> {
                        return VehicleResult.Failure(found.error)
                    }

                    is ConnectorScan.Hit -> {
                        found.hit
                    }
                }
            if (hit.advertisement.isConnectable == false) {
                return VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded)
            }
            attempts.count++
            when (val opened = openOwned(hit)) {
                is OpenResult.Opened -> {
                    return VehicleResult.Success(BleTransport(vin, opened.link, scope, timeSource, logger).also { attempts.created = it })
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
    ): ConnectorScan? {
        if (target != null) {
            val cached = hitsMutex.withLock { recentHits[target.identifier] }
            if (cached != null && cached.advertisement.localName == localName) return ConnectorScan.Hit(cached)
        }
        return connector.scan(localName).firstOrNull()?.also { if (it is ConnectorScan.Hit) remember(it.hit) }
    }

    /**
     * [GattConnector.open]을 팩토리 스코프에서 돌린다. 호출자가 사라진 뒤(타임아웃·취소) 열린 링크는 받을 사람이 없으므로
     * 여기서 정확히 한 번 끊고, 아직 도는 열기는 취소해 커넥터가 실패한 시도를 정리하게 한다.
     */
    @OptIn(DelicateCoroutinesApi::class) // ATOMIC: 스코프가 이미 취소돼도 끊기는 실행된다
    private suspend fun openOwned(hit: ScanHit): OpenResult {
        var result: OpenResult? = null
        val deferred = scope.async(start = CoroutineStart.UNDISPATCHED) { connector.open(hit).also { result = it } }
        try {
            return deferred.await()
        } catch (e: CancellationException) {
            deferred.cancel()
            deferred.invokeOnCompletion {
                (result as? OpenResult.Opened)?.let { opened ->
                    scope.launch(start = CoroutineStart.ATOMIC) { withContext(NonCancellable) { opened.link.disconnect() } }
                }
            }
            throw e
        }
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
