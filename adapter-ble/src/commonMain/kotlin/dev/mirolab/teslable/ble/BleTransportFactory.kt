// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// NewConnection, NewConnectionFromScanResult, ScanVehicleBeacon (retry loop and fail-fast rules)
package dev.mirolab.teslable.ble

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.ConnectOptions
import dev.mirolab.teslable.port.ConnectScanObservation
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * BLE [TransportFactory]. Go `NewConnectionFromScanResult`의 재시도 루프를 옮기되 시도 사이에 [retryInterval]을 둔다(FR-004,
 * 설계 구체화 3). 재시도하지 않는 실패: [BleAvailability] 검사 실패, 스캔 실패(권한·블루투스 꺼짐), 다른 VIN의 광고,
 * connectable=false(`MaxConnectionsExceeded`), `OpenResult.Failed(retry = false)`. 시간이 다 되면 마지막 오류(없으면 `ScanTimeout`).
 *
 * 시간이 다 되면 진행 중인 시도의 정리를 기다리지 않고 바로 돌아온다([openOwned]). 시간을 넘겨 기다리는 곳은 시간이 다 된 바로 그 순간 만들어진
 * 전송을 닫을 때뿐이고 그 대기는 [DISCONNECT_TIMEOUT]으로 제한된다. 프로세스가 돌지 않는 동안에는 타이머도 울리지 않는다(#130, 18-lib).
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
        val startedAt = timeSource.markNow()
        availability.check()?.let { return VehicleResult.Failure(it) }
        val localName = LocalName.of(vin, crypto)
        if (target != null && target.localName != localName) {
            return VehicleResult.Failure(VehicleError.InvalidArgument("advertisement ${target.localName} is not for this VIN"))
        }
        val attempts = Attempts(startedAt)
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

    private class Attempts(
        val startedAt: TimeMark,
    ) {
        var count = 0
        var lastError: VehicleError.TransportError? = null
        var created: Transport? = null

        // 이 호출의 스캔이 찾은 광고와 그때까지 걸린 시간(FR-038). 호출 하나의 값이라 여러 호출이 공유하는 ScanHit에 붙이지 않는다
        var scannedHit: ScanHit? = null
        var scannedAfterMillis = 0L

        /** [hit]이 이 호출의 스캔이 찾은 광고일 때만 값이 있다: 앱의 `scan`이 기억을 바꾼 광고로 열렸으면 이 호출이 본 것이 없다. */
        fun observationOf(hit: ScanHit): ConnectScanObservation? =
            if (hit === scannedHit) ConnectScanObservation(scannedAfterMillis, hit.advertisement.measuredRssi, count) else null
    }

    private suspend fun connectLoop(
        vin: Vin,
        localName: LocalName,
        target: VehicleAdvertisement?,
        attempts: Attempts,
    ): VehicleResult<Transport> {
        while (true) {
            val hit =
                when (val found = findHit(localName, target, attempts)) {
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
                    val transport = BleTransport(vin, opened.link, scope, timeSource, logger, attempts.observationOf(hit))
                    return VehicleResult.Success(transport.also { attempts.created = it })
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
        attempts: Attempts,
    ): ConnectorScan? {
        if (target != null) {
            val cached = hitsMutex.withLock { recentHits[target.identifier] }
            if (cached != null && cached.advertisement.localName == localName) return ConnectorScan.Hit(cached)
        }
        return scanOnce(localName, attempts)
    }

    private suspend fun scanOnce(
        localName: LocalName,
        attempts: Attempts,
    ): ConnectorScan? =
        connector
            .scan(localName)
            .onEach { if (it is ConnectorScan.Hit) noteScanHit(it.hit, attempts) } // 스캔을 정리하는 시간이 들어가지 않게 받자마자 잰다
            .firstOrNull()
            ?.also { if (it is ConnectorScan.Hit) remember(it.hit) }

    /**
     * connect가 스스로 돌린 스캔의 발견을 기록한다(FR-038). 실패로 끝나는 연결에는 공개 결과가 없으므로 관찰 로그 한 줄로도 남긴다 — 앱 쪽 로그는
     * 이 줄의 도착 시각으로 벽시계 시각을 얻는다. 광고 이름과 기기 식별자는 VIN 규칙 때문에 쓰지 않는다.
     */
    private fun noteScanHit(
        hit: ScanHit,
        attempts: Attempts,
    ) {
        val after = attempts.startedAt.elapsedNow().inWholeMilliseconds
        attempts.scannedHit = hit
        attempts.scannedAfterMillis = after
        val rssi = hit.advertisement.measuredRssi ?: "unavailable"
        val connectable = hit.advertisement.isConnectable ?: "unknown"
        val attempt = attempts.count + 1
        logger.log(LogLevel.DEBUG, OBSERVE_TAG) {
            "[connect:scan-hit] after=${after}ms rssi=$rssi connectable=$connectable attempt=$attempt"
        }
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

        // :application의 ObservationLog.TAG와 같은 값이다. :adapter-ble은 :domain에만 의존하고, 앱은 이 문자열로 관찰 줄을 고른다(18-lib)
        const val OBSERVE_TAG = "observe"
        const val RSSI_UNAVAILABLE_COREBLUETOOTH = 127
        const val RSSI_UNAVAILABLE_BTLEPLUG = Int.MIN_VALUE

        /** 측정값이 없을 때 플랫폼이 넣는 숫자를 null로 바꾼다. */
        private val VehicleAdvertisement.measuredRssi: Int?
            get() = rssi.takeUnless { it == RSSI_UNAVAILABLE_COREBLUETOOTH || it == RSSI_UNAVAILABLE_BTLEPLUG }
    }
}
