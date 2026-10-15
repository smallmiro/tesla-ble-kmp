package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

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
            if (ad != null && ad.localName == localName) emit(ConnectorScan.Hit(ScanHit(ad, platform = null)))
            awaitCancellation() // 실제 스캔처럼 수집자가 멈출 때까지 계속된다
        }

    override suspend fun open(hit: ScanHit): OpenResult {
        opens++
        openGate?.let { withContext(NonCancellable) { it.await() } }
        return openResults.removeFirstOrNull() ?: OpenResult.Opened(FakeGattLink().also { links += it })
    }
}
