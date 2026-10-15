@file:OptIn(ExperimentalUuidApi::class)

package io.github.smallmiro.teslable.ble

import com.juul.kable.Filter
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import com.juul.kable.descriptorOf
import com.juul.kable.indicate
import com.juul.kable.notify
import com.juul.kable.write
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.transport.Framer
import io.github.smallmiro.teslable.transport.Reassembler
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

private const val MAX_BLOCK_LENGTH = 1021 // min(MTU, 1024) - 3 의 상한(ble.go:354)

/**
 * ADR-0003 실차 스파이크 전용 진단(설계 구체화 17). 스캔 → 연결 → 0213 properties 로그 → 기본 `observe` 구독 →
 * request 전송 → listenFor 동안 수신 청크를 로그로 남기고 재조립한 메시지를 onMessage로 넘긴다. M3 Task 14에서 지운다.
 */
@InternalTeslableApi
public object GattProbe {
    /** 프로브를 한 번 실행한다. 예외는 로그로 바꾸고 취소만 전파한다. */
    @Suppress("LongParameterList", "LongMethod", "TooGenericExceptionCaught", "ThrowsCount") // 스파이크 진단: 실패 원인을 전부 로그로 남긴다
    public suspend fun run(
        localName: String,
        request: ByteArray,
        forceIndication: Boolean,
        log: (String) -> Unit,
        onMessage: (ByteArray) -> Unit,
        listenFor: Duration = 10.seconds,
        scanTimeout: Duration = 30.seconds,
    ) {
        log("scan: looking for $localName")
        val advertisement =
            try {
                withTimeoutOrNull(scanTimeout) {
                    Scanner { filters { match { name = Filter.Name.Exact(localName) } } }.advertisements.first()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("error: ${e::class.simpleName}: ${e.message}")
                return
            }
        if (advertisement == null) {
            log("scan: timeout after $scanTimeout")
            return
        }
        log("scan: found rssi=${advertisement.rssi} connectable=${advertisement.isConnectable}")
        val peripheral = Peripheral(advertisement)
        try {
            peripheral.connect()
            log("connect: ok")
            peripheral.services.value
                .orEmpty()
                .filter { it.serviceUuid == BleUuids.SERVICE }
                .flatMap { it.characteristics }
                .forEach { c ->
                    val p = c.properties
                    log("char ${c.characteristicUuid}: write=${p.write} notify=${p.notify} indicate=${p.indicate}")
                }
            val maxWrite = peripheral.maximumWriteValueLengthForType(WriteType.WithResponse)
            val blockLength = minOf(maxWrite, MAX_BLOCK_LENGTH)
            log("mtu: maximumWriteValueLength(WithResponse)=$maxWrite blockLength=$blockLength")
            val tx = characteristicOf(BleUuids.SERVICE, BleUuids.TO_VEHICLE)
            val rx = characteristicOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE)
            val cccd = descriptorOf(BleUuids.SERVICE, BleUuids.FROM_VEHICLE, BleUuids.CCCD)
            val reassembler = Reassembler()
            withTimeoutOrNull(listenFor) {
                peripheral
                    .observe(rx, onSubscription = {
                        log("subscribe: enabled (forceIndication=$forceIndication)")
                        if (forceIndication) {
                            try {
                                peripheral.write(cccd, byteArrayOf(0x02, 0x00))
                                log("cccd: wrote 0x0200 (indication)")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log("cccd: write failed: ${e::class.simpleName}: ${e.message}")
                            }
                        }
                        val chunks = Framer.frame(request, blockLength)
                        log("TX: ${chunks.joinToString("") { it.toHex() }}")
                        chunks.forEach { peripheral.write(tx, it, WriteType.WithResponse) }
                        log("TX: sent ${chunks.size} chunk(s)")
                    })
                    .collect { chunk ->
                        log("RX chunk (${chunk.size}B): ${chunk.toHex()}")
                        reassembler.push(chunk).forEach(onMessage)
                    }
            }
            log("listen: done after $listenFor")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("error: ${e::class.simpleName}: ${e.message}")
        } finally {
            withContext(NonCancellable) {
                peripheral.disconnect()
                peripheral.close()
            }
            log("disconnected")
        }
    }
}
