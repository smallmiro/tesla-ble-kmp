// Ported from vehicle-command@a4b43c1 internal/dispatcher/receiver.go (Apache-2.0)
// receiverKey, receiver, expired
package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.protocol.SlidingWindow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectClause1
import okio.ByteString
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeMark

/** Go `receiverKey`: `(routing_address, request_uuid | VCSEC면 EMPTY, from domain)`. `toString`은 Go `String()`과 같은 꼴. */
internal data class PendingKey(
    val address: ByteString,
    val uuid: ByteString,
    val domain: Domain,
) {
    override fun toString(): String = "<${address.hex()}-${uuid.hex()}: $domain>"
}

/**
 * 보낸 요청 하나에 대한 응답 대기(Go `receiver`). 응답은 [receive]로 순서대로 받는다(버퍼 [BUFFER_SIZE], 가득 차면 디스패처가 드롭).
 * 다 쓰면 반드시 [close]한다(`use {}` 권장) — 닫지 않으면 디스패처가 뒤늦은 응답을 이 요청에 계속 배달한다.
 * [close]는 suspend하지 않는 플래그 + 채널 닫기라서 취소 중 `finally`에서도 안전하다; 맵 제거는 디스패처가 지연 수행한다.
 */
public class PendingRequest internal constructor(
    internal val key: PendingKey,
    requestHash: ByteArray?,
    private val sentAt: TimeMark,
) : AutoCloseable {
    private val channel = Channel<RoutableMessage>(BUFFER_SIZE)
    private val requestHashBytes: ByteArray? = requestHash?.copyOf()

    @Volatile
    private var closed = false

    /** Go `receiver.antireplay`: 이 요청의 응답 counter 슬라이딩 윈도우(32). 수신 코루틴만 갱신한다. */
    internal val antiReplay: SlidingWindow = SlidingWindow()

    /** 응답 AAD의 REQUEST_HASH(Go `receiver.requestID`, `RequestHash.of(요청)`). 인증하지 않은 요청이면 null. 사본. */
    public val requestHash: ByteArray? get() = requestHashBytes?.copyOf()

    /** 매칭 키의 uuid(Infotainment 16바이트, VCSEC는 빈 배열). 로그·테스트용. */
    public val uuid: ByteArray get() = key.uuid.toByteArray()

    /** [close] 뒤 true. 디스패처가 맵에서 지운다. */
    internal val isClosed: Boolean get() = closed

    /** Go `Recv()`의 `select` 용 절. */
    internal val onReceive: SelectClause1<RoutableMessage> get() = channel.onReceive

    /**
     * 다음 응답을 기다린다(Go `<-recv.Recv()`).
     * @throws kotlinx.coroutines.channels.ClosedReceiveChannelException [close] 뒤에 부르면 발생한다(프로그래밍 오류).
     */
    public suspend fun receive(): RoutableMessage = channel.receive()

    /** 기다리지 않고 다음 응답을 꺼낸다. 없거나 닫혔으면 null. */
    public fun tryReceive(): RoutableMessage? = channel.tryReceive().getOrNull()

    /** 디스패처가 부른다: 버퍼가 가득 찼거나 닫혔으면 false(드롭). Go `select { case handler.ch <- message: default: }`. */
    internal fun deliver(message: RoutableMessage): Boolean = channel.trySend(message).isSuccess

    /** Go `expired`: 요청을 보낸 지 [lifetime]이 지났으면 동봉 세션정보를 버려야 한다. */
    internal fun expired(lifetime: Duration): Boolean = sentAt.elapsedNow() > lifetime

    /** Go `Close()`: 더 이상 응답을 받지 않는다. 멱등. */
    override fun close() {
        closed = true
        channel.close()
    }

    /** 상수. */
    public companion object {
        /** Go `receiverBufferSize`: 요청당 응답 버퍼. */
        public const val BUFFER_SIZE: Int = 10
    }
}
