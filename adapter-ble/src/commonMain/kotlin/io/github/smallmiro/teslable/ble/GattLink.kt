// Ported from vehicle-command@a4b43c1 pkg/connector/ble/ble.go (Apache-2.0)
// Connection.txChar/rxChar/blockLength/client as an internal port (Kable binding in KableGattLink)
package io.github.smallmiro.teslable.ble

import io.github.smallmiro.teslable.model.VehicleError
import kotlinx.coroutines.flow.Flow

/** 연결된 GATT 링크 하나(서비스 0211, TX 0212, RX 0213). Kable 없이 [BleTransport]를 시험하기 위한 내부 포트. */
internal interface GattLink {
    /** 쓰기 블록 길이(`min(MTU, 1024) - 3`, MTU 교환 실패 시 20 — `ble.go:349-354`). */
    val blockLength: Int

    /** 0213 알림·지시 청크. 링크가 끊기면 정상 완료하거나 예외로 끝난다. 한 번만 수집한다. */
    val notifications: Flow<ByteArray>

    /** 청크 하나를 write-with-response로 쓴다(`ble.go:121`). null이면 성공. 취소는 전파한다. */
    suspend fun write(chunk: ByteArray): VehicleError.TransportError?

    /** 링크를 끊는다. 멱등. */
    suspend fun disconnect()
}
