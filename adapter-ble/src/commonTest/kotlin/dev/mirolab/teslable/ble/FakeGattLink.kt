package dev.mirolab.teslable.ble

import dev.mirolab.teslable.model.VehicleError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.yield

/** 스크립트 가능한 [GattLink]. `runTest` 단일 스레드 전용. */
internal class FakeGattLink(
    override val blockLength: Int = 20,
) : GattLink {
    private val chunks = Channel<ByteArray>(Channel.UNLIMITED)
    val written = mutableListOf<ByteArray>()
    val writeErrors = ArrayDeque<VehicleError.TransportError?>()
    var disconnects = 0
        private set

    /** non-null이면 [write]가 이 게이트를 기다린다(쓰기 도중 취소 시험). */
    var writeGate: CompletableDeferred<Unit>? = null

    /** 이미 쓴 청크가 이 수 이상일 때만 [writeGate]를 기다린다(0 = 첫 쓰기부터). */
    var gateAfterWrites: Int = 0

    override val notifications: Flow<ByteArray> = chunks.receiveAsFlow()

    fun notify(chunk: ByteArray) {
        chunks.trySend(chunk)
    }

    /** 링크가 끊김(정상 완료). */
    fun drop() {
        chunks.close()
    }

    /** 링크가 오류로 끊김. */
    fun fail(cause: Throwable) {
        chunks.close(cause)
    }

    override suspend fun write(chunk: ByteArray): VehicleError.TransportError? {
        yield() // 실제 GATT 쓰기처럼 중단점을 둔다 — 동시 send가 직렬화되지 않으면 청크가 섞여 테스트가 실패한다
        if (written.size >= gateAfterWrites) writeGate?.await()
        val error = writeErrors.removeFirstOrNull()
        if (error == null) written += chunk.copyOf()
        return error
    }

    override suspend fun disconnect() {
        disconnects++
        chunks.close()
    }
}
