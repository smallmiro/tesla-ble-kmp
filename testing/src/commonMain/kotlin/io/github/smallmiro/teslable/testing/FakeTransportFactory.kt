package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.model.toResult
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [FakeVehicle]을 감싼 [TransportFactory](M2 설계 구체화 2). 스캔은 차량의 로컬 이름과 같을 때만 광고 하나를 내고,
 * 연결은 대기열의 실패를 먼저 돌려준 뒤 [FakeVehicle.connect]를 부른다(connectable=false → `MaxConnectionsExceeded`). 단일 스레드 전용.
 */
public class FakeTransportFactory(
    private val vehicle: FakeVehicle,
    crypto: CryptoPrimitives = TestCrypto.primitives,
    private val rssi: Int = DEFAULT_RSSI,
) : TransportFactory {
    private val vehicleName = LocalName.of(vehicle.vin, crypto)
    private val connectFailures = ArrayDeque<VehicleError>()
    private var attempts = 0

    /** null이 아니면 [scan]이 이 오류 하나를 내고 끝난다. */
    public var scanFailure: VehicleError.TransportError? = null

    /** [connect] 호출 수. */
    public val connectAttempts: Int get() = attempts

    /** 마지막으로 연결에 성공한 전송. */
    public var lastTransport: FakeTransport? = null
        private set

    /** 다음 [connect]가 [error]를 돌려주게 한다(순서대로 소비). */
    public fun enqueueConnectFailure(error: VehicleError) {
        connectFailures.addLast(error)
    }

    override fun scan(localName: LocalName): Flow<ScanEvent> =
        flow {
            val failure = scanFailure
            if (failure != null) {
                emit(ScanEvent.Failed(failure))
            } else if (localName == vehicleName) {
                emit(ScanEvent.Found(VehicleAdvertisement(vehicleName, "fake-${vehicleName.value}", rssi, vehicle.isConnectable)))
            }
        }

    override suspend fun connect(
        vin: Vin,
        target: VehicleAdvertisement?,
        options: ConnectOptions,
    ): VehicleResult<Transport> {
        attempts++
        connectFailures.removeFirstOrNull()?.let { return it.toResult() }
        if (vin != vehicle.vin) return VehicleResult.Failure(VehicleError.TransportError.ScanTimeout)
        val result = vehicle.connect()
        if (result is VehicleResult.Success) lastTransport = result.value
        return result
    }

    /** 기본값. */
    public companion object {
        /** 가짜 광고의 기본 신호 세기(dBm). */
        public const val DEFAULT_RSSI: Int = -60
    }
}
