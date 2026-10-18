// Ported from vehicle-command@a4b43c1 pkg/vehicle/security.go (Apache-2.0) — SendAddKeyRequestWithRole (+ response reading, ADR-0009)
package dev.mirolab.teslable.application.pairing

import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.vcsec.VcsecCommands
import dev.mirolab.teslable.application.vcsec.VcsecInfoMapping
import dev.mirolab.teslable.application.vcsec.VcsecPayloads
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.isTerminal
import dev.mirolab.teslable.port.TransportState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Go `SendAddKeyRequestWithRole` + 응답 읽기(ADR-0009, SDD §4, 설계 구체화 4). 차가운 Flow — 수집할 때마다 한 번 보낸다.
 * 순서: [Dispatcher.unroutedFrames] 구독 → VCSEC 직렬화 락 안에서 전송만(FR-049) → `Sent` → [timeout] 동안 프레임을 [PairingEvents]로 해석.
 */
public class Pairing internal constructor(
    private val dispatcher: Dispatcher,
    private val vcsec: VcsecCommands,
) {
    private val pairingMutex = Mutex()

    /** [publicKey]의 등록을 요청한다. 사용자가 키카드를 태그하고 차량 화면에서 승인해야 한다. */
    public fun addKeyRequest(
        publicKey: PublicKeyBytes,
        role: KeyRole,
        formFactor: KeyFormFactor,
        timeout: Duration,
    ): Flow<PairingEvent> {
        val wireRole =
            VcsecInfoMapping.wireRole(role)
                ?: return flowOf(PairingEvent.Failed(VehicleError.InvalidArgument("role UNKNOWN cannot be enrolled")))
        val envelope =
            VcsecPayloads.presentKeyEnvelope(
                VcsecPayloads.addKey(publicKey, wireRole, VcsecInfoMapping.wireFormFactor(formFactor)),
            )
        return channelFlow {
            // 페어링끼리는 전송부터 끝 이벤트까지 직렬화한다 — 두 요청이 같은 감싸지 않은 응답을 자기 결과로 보지 않게(codex 검토).
            // 일반 VCSEC 명령과 공유하는 락(vcsec.exclusive)은 전송하는 동안만 잡는다(Review Focus 4).
            pairingMutex.withLock {
                val inputs = Channel<Input>(Channel.UNLIMITED)
                val tap = launch(start = CoroutineStart.UNDISPATCHED) { dispatcher.unroutedFrames.collect { inputs.send(Input.Frame(it)) } }
                val link =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        val down = dispatcher.transportState.first { it is TransportState.Disconnected } as TransportState.Disconnected
                        inputs.send(Input.LinkLost(down.reason ?: VehicleError.NotConnected))
                    }
                try {
                    run(envelope, timeout, inputs)
                } finally {
                    tap.cancel()
                    link.cancel()
                }
            }
        }
    }

    private sealed interface Input {
        class Frame(
            val bytes: ByteArray,
        ) : Input

        class LinkLost(
            val error: VehicleError,
        ) : Input
    }

    private suspend fun ProducerScope<PairingEvent>.run(
        envelope: ByteArray,
        timeout: Duration,
        inputs: Channel<Input>,
    ) {
        val sent =
            vcsec.exclusive {
                discardStaleFrames(inputs)
                dispatcher.sendUnrouted(envelope)
            }
        if (sent is VehicleResult.Failure) {
            send(PairingEvent.Failed(sent.error))
            return
        }
        send(PairingEvent.Sent) // Uncertain(차량이 받았을 수 있음)도 기다린다
        val finished =
            withTimeoutOrNull(timeout) {
                for (input in inputs) {
                    val event =
                        when (input) {
                            is Input.Frame -> PairingEvents.interpret(input.bytes) ?: continue
                            is Input.LinkLost -> PairingEvent.Failed(input.error) // 60초를 기다리지 않고 바로 끝낸다
                        }
                    send(event)
                    if (event.isTerminal) return@withTimeoutOrNull true
                }
                false
            }
        if (finished != true) send(PairingEvent.TimedOut)
    }

    /** 락을 기다리는 동안 들어온 프레임은 이전 페어링의 늦은 결과일 수 있다 — 버린다(탭은 이미 구독 중). 링크 끊김은 남긴다. */
    private fun discardStaleFrames(inputs: Channel<Input>) {
        var linkLost: Input.LinkLost? = null
        while (true) {
            when (val stale = inputs.tryReceive().getOrNull() ?: break) {
                is Input.Frame -> Unit
                is Input.LinkLost -> linkLost = stale
            }
        }
        linkLost?.let { inputs.trySend(it) }
    }
}
