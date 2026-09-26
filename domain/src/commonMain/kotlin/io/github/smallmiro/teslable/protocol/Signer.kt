// Ported from vehicle-command@a4b43c1 internal/authentication/signer.go (Apache-2.0) — Signer, Peer (peer.go), epochStartTime (crypto.go)
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.util.toHex
import okio.ByteString.Companion.toByteString
import okio.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val EPOCH_SIZE = 16
private const val TO_STRING_EPOCH_PREVIEW_BYTES = 4

/**
 * 클라이언트 측 세션 상태 기계(Go `Signer`, FR-013~FR-016, FR-019). 도메인(VCSEC/Infotainment)마다 하나.
 * counter·epoch·시계 원점을 보관하고, 세션정보를 검증·갱신하며, 명령을 암호화하고 응답을 복호화한다.
 *
 * 시간은 주입된 [TimeSource]로만 흐른다(단조, 벽시계 없음). `timeZero`는 차량 시계 원점의 로컬 대응점이다.
 * 스레드 안전하지 않다: 인스턴스 하나는 하나의 코루틴에 한정한다(M2 `SessionState`의 Mutex).
 */
@OptIn(InternalTeslableApi::class)
public class Signer
    @Suppress("LongParameterList") // Go Signer/Peer 필드와 1:1 대응
    private constructor(
        private val session: Session,
        /** 이 세션의 차량 VIN(TLV PERSONALIZATION). */
        public val vin: Vin,
        initialEpoch: ByteArray,
        initialCounter: UInt,
        initialClockTime: UInt,
        private val crypto: CryptoPrimitives,
        private val random: RandomSource,
        private val timeSource: TimeSource,
        age: Duration = Duration.ZERO,
    ) : AutoCloseable {
        private var epochBytes: ByteArray = normalizeEpoch(initialEpoch)
        private var counterValue: UInt = initialCounter
        private var timeZero: TimeMark = timeSource.markNow() - age - initialClockTime.toLong().seconds
        private var setTime: UInt = initialClockTime // Go Signer.setTime: 마지막으로 반영한 세션정보의 clock_time

        /** 차량 공개키(Go `RemotePublicKeyBytes`). */
        public val vehiclePublicKey: PublicKeyBytes get() = session.vehiclePublicKey

        /** 클라이언트 공개키(`signer_identity.public_key`에 실린다). */
        public val localPublicKey: PublicKeyBytes get() = session.localPublicKey

        /** 마지막으로 사용한 counter. 다음 명령은 `counter + 1`을 쓴다. */
        public val counter: UInt get() = counterValue

        /** 현재 epoch(16바이트 사본). */
        public val epoch: ByteArray get() = epochBytes.copyOf()

        /** Go `timestamp()`: 차량 시계 기준 현재 초. */
        public fun timestamp(): UInt =
            timeZero
                .elapsedNow()
                .inWholeSeconds
                .coerceAtLeast(0)
                .toUInt()

        /**
         * Go `UpdateSessionInfo`: 공개키가 다르면 `UNKNOWN_KEY_ID`. epoch가 바뀌었거나 `setTime <= clock_time`일 때만
         * 갱신하며 counter는 절대 내리지 않는다. 과거 시각의 정보는 오류 없이 무시한다(FR-014, FR-018).
         * epoch는 Go `copy(s.epoch[:], info.Epoch)`처럼 **이미 있는 배열 위에** 덮어쓴다: [info]의 epoch가
         * 16바이트보다 짧으면 앞부분만 바뀌고 나머지 바이트는 갱신 전 epoch 값이 그대로 남는다 — 생성 시점에만 쓰는
         * (0으로 채운 새 배열에 복사하는) [normalizeEpoch]와는 다르다(FR-019).
         */
        public fun updateSessionInfo(info: SessionInfo): SignerResult<Unit> {
            if (!info.publicKey.toByteArray().contentEquals(vehiclePublicKey.toByteArray())) {
                return SignerResult.Fault(
                    MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID,
                    "public key in SessionInfo doesn't match value used to initialize Signer",
                )
            }
            val infoEpoch = info.epoch.toByteArray()
            val infoClock = info.clock_time.toUInt()
            val infoCounter = info.counter.toUInt()
            if (!epochBytes.contentEquals(infoEpoch) || setTime <= infoClock) {
                if (counterValue < infoCounter) counterValue = infoCounter
                epochBytes = copyEpochOver(epochBytes, infoEpoch)
                setTime = infoClock
                timeZero = timeSource.markNow() - infoClock.toLong().seconds
            }
            return SignerResult.Ok(Unit)
        }

        /** Go `UpdateSignedSessionInfo`: 태그(상수 시간) → 디코딩 → [updateSessionInfo]. */
        public fun updateSignedSessionInfo(
            challenge: ByteArray,
            encodedInfo: ByteArray,
            tag: ByteArray,
        ): SignerResult<Unit> {
            if (!session.verifySessionInfoTag(vin.toByteArray(), challenge, encodedInfo, tag)) {
                return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, "session info hmac invalid")
            }
            val info =
                decodeSessionInfo(encodedInfo)
                    ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_DECODING, "invalid session info protobuf")
            return updateSessionInfo(info)
        }

        /** Go `ExportSessionInfo`: 캐시용 `SessionInfo`(clock_time = 지금의 [timestamp]). */
        public fun exportSessionInfo(): ByteArray =
            SessionInfo.ADAPTER.encode(
                SessionInfo(
                    counter = counterValue.toInt(),
                    publicKey = vehiclePublicKey.toByteArray().toByteString(),
                    epoch = epochBytes.toByteString(),
                    clock_time = timestamp().toInt(),
                ),
            )

        /** 세션 키를 0으로 덮는다. 이후 모든 연산은 실패한다. */
        override fun close() {
            session.close()
        }

        override fun toString(): String {
            val epochPreview = epochBytes.copyOf(TO_STRING_EPOCH_PREVIEW_BYTES).toHex()
            return "Signer(vin=$vin, counter=$counterValue, epoch=$epochPreview…)"
        }

        /** 생성 팩토리. */
        public companion object {
            /** Go `NewSigner`: ECDH → K, 세션정보 상태 복사. 차량 공개키가 잘못됐으면 `BAD_PARAMETER`. */
            @Suppress("LongParameterList") // Go NewSigner 인자(+crypto/random/timeSource 주입)와 1:1 대응
            public suspend fun create(
                privateKey: EcdhPrivateKey,
                vin: Vin,
                info: SessionInfo,
                crypto: CryptoPrimitives,
                random: RandomSource,
                timeSource: TimeSource = TimeSource.Monotonic,
            ): SignerResult<Signer> = build(privateKey, vin, info, crypto, random, timeSource, age = Duration.ZERO)

            /** Go `NewAuthenticatedSigner`: 디코딩 → 생성 → 태그 검증. */
            @Suppress("LongParameterList") // Go NewAuthenticatedSigner 인자(+crypto/random/timeSource 주입)와 1:1 대응
            public suspend fun createAuthenticated(
                privateKey: EcdhPrivateKey,
                vin: Vin,
                challenge: ByteArray,
                encodedInfo: ByteArray,
                tag: ByteArray,
                crypto: CryptoPrimitives,
                random: RandomSource,
                timeSource: TimeSource = TimeSource.Monotonic,
            ): SignerResult<Signer> {
                val info =
                    decodeSessionInfo(encodedInfo)
                        ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_DECODING, "invalid session info protobuf")
                val created = build(privateKey, vin, info, crypto, random, timeSource, age = Duration.ZERO)
                val signer = (created as? SignerResult.Ok)?.value ?: return created
                var accepted = false
                try {
                    if (!signer.session.verifySessionInfoTag(vin.toByteArray(), challenge, encodedInfo, tag)) {
                        return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, "session info hmac invalid")
                    }
                    accepted = true
                    return SignerResult.Ok(signer)
                } finally {
                    // 태그 검증이 통과하지 못했거나(위) 예외를 던졌을 때도 K를 반드시 0으로 덮는다.
                    if (!accepted) signer.close()
                }
            }

            /**
             * Go `ImportSessionInfo(generatedAt)`: 캐시된 세션정보로 핸드셰이크 없이 생성.
             * [age]는 캐시 저장 시각부터 지금까지의 경과(= Go `now - generatedAt`).
             *
             * @throws IllegalArgumentException [age]가 음수이면 발생한다. 벽시계가 뒤로 흐르는 등의 이유로 저장
             * 시각이 지금보다 미래로 계산되면, 호출자(M2 세션 캐시)가 [age]를 0으로 자르거나 캐시 항목을 버려야 한다 —
             * 이 함수는 값을 보정하지 않는다.
             */
            @Suppress("LongParameterList") // Go ImportSessionInfo 인자(+crypto/random/timeSource 주입)와 1:1 대응
            public suspend fun importSessionInfo(
                privateKey: EcdhPrivateKey,
                vin: Vin,
                encodedInfo: ByteArray,
                age: Duration,
                crypto: CryptoPrimitives,
                random: RandomSource,
                timeSource: TimeSource = TimeSource.Monotonic,
            ): SignerResult<Signer> {
                require(!age.isNegative()) { "age must not be negative" }
                val info =
                    decodeSessionInfo(encodedInfo)
                        ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_DECODING, "invalid session info protobuf")
                return build(privateKey, vin, info, crypto, random, timeSource, age)
            }

            @Suppress("LongParameterList") // 위 세 팩토리가 공유하는 내부 헬퍼, Go NewSigner 인자와 1:1 대응
            private suspend fun build(
                privateKey: EcdhPrivateKey,
                vin: Vin,
                info: SessionInfo,
                crypto: CryptoPrimitives,
                random: RandomSource,
                timeSource: TimeSource,
                age: Duration,
            ): SignerResult<Signer> {
                val vehiclePublic =
                    try {
                        PublicKeyBytes(info.publicKey.toByteArray())
                    } catch (ignored: IllegalArgumentException) {
                        return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, "invalid public key")
                    }
                val session =
                    try {
                        Session.establish(privateKey, vehiclePublic, crypto)
                    } catch (ignored: IllegalArgumentException) {
                        return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, "invalid public key")
                    }
                return SignerResult.Ok(
                    Signer(
                        session,
                        vin,
                        info.epoch.toByteArray(),
                        info.counter.toUInt(),
                        info.clock_time.toUInt(),
                        crypto,
                        random,
                        timeSource,
                        age,
                    ),
                )
            }

            private fun decodeSessionInfo(encoded: ByteArray): SessionInfo? =
                try {
                    SessionInfo.ADAPTER.decode(encoded)
                } catch (ignored: IOException) {
                    null
                }

            /**
             * Go `NewSigner`: `copy(signer.epoch[:], info.Epoch)`를 새로 만든 (0으로 채워진) 배열에 적용한다 —
             * **생성 전용**. 짧으면 나머지가 0으로 채워지고 길면 자른다. 갱신 시점에는 [copyEpochOver]를 쓴다.
             */
            private fun normalizeEpoch(epoch: ByteArray): ByteArray =
                ByteArray(EPOCH_SIZE).also { epoch.copyInto(it, 0, 0, minOf(EPOCH_SIZE, epoch.size)) }

            /**
             * Go `UpdateSessionInfo`: `copy(s.epoch[:], info.Epoch)`를 **이미 있는** epoch 배열의 사본 위에 적용한다.
             * [incoming]이 16바이트보다 짧으면 앞부분만 바뀌고 [current]의 나머지 바이트는 그대로 남는다(0으로 채우지
             * 않는다). `epoch` getter가 항상 사본을 돌려주므로 공유 배열을 직접 변형하지 않고 새 배열을 만든다.
             */
            private fun copyEpochOver(
                current: ByteArray,
                incoming: ByteArray,
            ): ByteArray {
                val next = current.copyOf()
                incoming.copyInto(next, 0, 0, minOf(EPOCH_SIZE, incoming.size))
                return next
            }
        }
    }
