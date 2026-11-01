package dev.mirolab.teslable.ble

import dev.mirolab.teslable.port.VehicleAdvertisement
import dev.mirolab.teslable.transport.LocalName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/** 스크립트 가능한 [GattConnector]. */
internal class FakeGattConnector(
    private val advertisement: VehicleAdvertisement?,
) : GattConnector {
    val openResults = ArrayDeque<OpenResult>()
    var scanFailure: ConnectorScan.Failed? = null
    var scans = 0
        private set
    var opens = 0
        private set

    /** 이 수만큼의 스캔은 광고 없이 곧바로 끝난다. */
    var emptyScans = 0

    /** 스캔이 시작되고 광고가 나올 때까지 걸리는 시간. */
    var hitDelay: Duration = Duration.ZERO

    /** 스캔을 멈추는 데 걸리는 시간(수집자가 광고를 받은 뒤 흐름이 끝날 때까지). */
    var scanTeardown: Duration = Duration.ZERO

    /** 비어 있지 않으면 스캔마다 앞에서 하나씩 꺼내 광고의 RSSI로 쓴다. */
    val rssiByScan = ArrayDeque<Int>()

    /** non-null이면 [open]이 취소를 무시하고 이 게이트를 기다린다(취소와 완료의 경합을 재현). */
    var openGate: CompletableDeferred<Unit>? = null
    val links = mutableListOf<FakeGattLink>()

    override fun scan(localName: LocalName): Flow<ConnectorScan> =
        flow {
            scans++
            if (emptyScans > 0) {
                emptyScans--
                return@flow
            }
            scanFailure?.let {
                emit(it)
                return@flow
            }
            val ad = advertisement
            try {
                if (ad != null && ad.localName == localName) {
                    delay(hitDelay)
                    val seen = rssiByScan.removeFirstOrNull()?.let { ad.copy(rssi = it) } ?: ad
                    emit(ConnectorScan.Hit(ScanHit(seen, platform = null)))
                }
                awaitCancellation() // 실제 스캔처럼 수집자가 멈출 때까지 계속된다
            } finally {
                withContext(NonCancellable) { delay(scanTeardown) }
            }
        }

    override suspend fun open(hit: ScanHit): OpenResult {
        opens++
        openGate?.let { withContext(NonCancellable) { it.await() } }
        return openResults.removeFirstOrNull() ?: OpenResult.Opened(FakeGattLink().also { links += it })
    }
}
