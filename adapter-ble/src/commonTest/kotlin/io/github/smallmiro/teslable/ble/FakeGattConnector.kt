package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
    val links = mutableListOf<FakeGattLink>()

    override fun scan(localName: LocalName): Flow<ConnectorScan> =
        flow {
            scans++
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
        return openResults.removeFirstOrNull() ?: OpenResult.Opened(FakeGattLink().also { links += it })
    }
}
