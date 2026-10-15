// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// ScanVehicleBeacon, tryToConnect (Kable binding)
package io.github.smallmiro.teslable.ble

import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.descriptorOf
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Kable [GattConnector]. 스캔 필터는 로컬 이름 정확 일치(Go `ble.go:222`), 쓰기는 항상 with response.
 * [open]은 취소 외에는 던지지 않고 모든 실패를 [OpenResult.Failed]로 돌려준다. [scope]는 `SupervisorJob`을 가져야 한다.
 */
@OptIn(ExperimentalUuidApi::class)
internal class KableGattConnector(
    private val scope: CoroutineScope,
    private val subscription: SubscriptionMode,
    private val logger: TeslaLogger,
) : GattConnector {
    private val rx = characteristicOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE)

    override fun scan(localName: LocalName): Flow<ConnectorScan> =
        Scanner { filters { match { name = Filter.Name.Exact(localName.value) } } }
            .advertisements
            .map<PlatformAdvertisement, ConnectorScan> { ad ->
                val advertisement = VehicleAdvertisement(localName, "${ad.identifier}", ad.rssi, ad.isConnectable)
                ConnectorScan.Hit(ScanHit(advertisement, ad))
            }.catch { e ->
                if (e is CancellationException) throw e
                emit(ConnectorScan.Failed(KableErrors.map(e)))
            }

    @Suppress("TooGenericExceptionCaught") // 플랫폼 예외는 KableErrors가 값으로 바꾼다(ADR-0006)
    override suspend fun open(hit: ScanHit): OpenResult =
        try {
            openInternal(hit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = KableErrors.map(e)
            OpenResult.Failed(error, retry = KableErrors.isRetriable(error))
        }

    @Suppress("TooGenericExceptionCaught") // 취소 외 모든 실패는 정리 후 호출자가 값으로 바꾼다
    private suspend fun openInternal(hit: ScanHit): OpenResult {
        val advertisement =
            hit.platform as? PlatformAdvertisement
                ?: return OpenResult.Failed(VehicleError.TransportError.ConnectFailed("advertisement from another scanner"), retry = false)
        val peripheral = Peripheral(advertisement)
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val observeJob = observe(peripheral, chunks)
        try {
            peripheral.connect()
            if (!hasVehicleService(peripheral)) {
                cleanup(peripheral, observeJob, chunks)
                return OpenResult.Failed(VehicleError.TransportError.ConnectFailed("vehicle GATT service not found"), retry = true)
            }
            val blockLength = negotiateBlockLength(peripheral, logger)
            logger.log(LogLevel.INFO, TAG) { "Connected to ${hit.advertisement.localName} (block length $blockLength)" }
            return OpenResult.Opened(KableGattLink(peripheral, blockLength, chunks, observeJob, scope))
        } catch (e: Throwable) {
            cleanup(peripheral, observeJob, chunks) // 취소여도 정리는 NonCancellable
            throw e
        }
    }

    private fun observe(
        peripheral: Peripheral,
        chunks: Channel<ByteArray>,
    ): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                peripheral.observe(rx, onSubscription = { onSubscribed(peripheral) }).collect { chunks.send(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                chunks.close(e)
            }
        }

    private suspend fun onSubscribed(peripheral: Peripheral) {
        if (subscription == SubscriptionMode.ANDROID_FORCE_INDICATION && isAndroid) {
            peripheral.write(descriptorOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE, BleUuids.CCCD), byteArrayOf(0x02, 0x00))
        }
    }

    private fun hasVehicleService(peripheral: Peripheral): Boolean =
        peripheral.services.value.orEmpty().any { service ->
            service.serviceUuid == BleUuids.SERVICE &&
                service.characteristics.map { it.characteristicUuid }.containsAll(listOf(BleUuids.TO_VEHICLE, BleUuids.FROM_VEHICLE))
        }

    /** 실패한 시도의 연결을 정리한다. 취소 중에도 끝까지 돌고, 끊김 대기는 [DISCONNECT_TIMEOUT]으로 제한한다. */
    private suspend fun cleanup(
        peripheral: Peripheral,
        observeJob: Job,
        chunks: Channel<ByteArray>,
    ) {
        withContext(NonCancellable) {
            observeJob.cancel()
            chunks.close()
            @Suppress("TooGenericExceptionCaught", "SwallowedException") // 정리 중 이미 끊긴 연결
            try {
                withTimeoutOrNull(DISCONNECT_TIMEOUT) { peripheral.disconnect() }
            } catch (e: Exception) {
                // 정리 계속
            }
            peripheral.close()
        }
    }

    private companion object {
        const val TAG = "ble"
    }
}
