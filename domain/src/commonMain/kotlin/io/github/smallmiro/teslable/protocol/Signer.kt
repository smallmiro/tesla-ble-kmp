// Ported from vehicle-command@a4b43c1 internal/authentication/signer.go (Apache-2.0) — Signer, Peer (peer.go), epochStartTime (crypto.go)
package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.AES_GCM_Personalized_Signature_Data
import com.tesla.generated.signatures.KeyIdentity
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
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
        private var closed = false

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

        /**
         * Go `UpdateSignedSessionInfo`: 태그(상수 시간) → 디코딩 → [updateSessionInfo].
         *
         * @throws IllegalStateException [close]로 닫힌 뒤 호출하면 발생한다(프로그래밍 오류, ADR-0006).
         */
        public fun updateSignedSessionInfo(
            challenge: ByteArray,
            encodedInfo: ByteArray,
            tag: ByteArray,
        ): SignerResult<Unit> {
            checkOpen()
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

        /** 복호화된 응답(평문 페이로드, `signature_data` 제거)과 anti-replay counter. */
        public class DecryptedResponse(
            /** 페이로드가 평문으로 바뀐 메시지. */
            public val message: RoutableMessage,
            /** 응답 counter. M2 `PendingRequest`가 `SlidingWindow`로 재사용을 검사한다. */
            public val counter: UInt,
        )

        /**
         * Go `Encrypt`: counter가 0xFFFFFFFF면 `INVALID_TOKEN_OR_COUNTER`(롤오버; 재핸드셰이크 필요). 그 외에는 counter를
         * 먼저 올린 뒤 메타데이터를 만들므로, 이후 단계가 실패해도 counter는 소비된다(원본과 동일).
         * AAD = SHA256(TLV{5, domain, VIN, epoch, expires_at, counter, [flags≠0]}), nonce는 [RandomSource]에서 12바이트(ADR-0008).
         *
         * `expiresAt` 상한은 Go처럼 `uint32(...)`로 감싼 뒤 범위를 검사하지 않는다 — Go는 2^32 이상의 값을 감싸고 나서
         * 검사하지만, 여기서는 `Long`으로 계산한 뒤 음수이거나 [CommandMetadata.EPOCH_LENGTH_SECONDS]를 초과하면 바로
         * 거부한다. 더 안전하고, 정상적인 명령 수명 안에서는 두 방식이 관측 가능하게 다르지 않다.
         *
         * @throws IllegalStateException [close]로 닫힌 뒤 호출하면 발생한다(프로그래밍 오류, ADR-0006). 이 검사는 롤오버
         *   검사와 counter 증가보다 먼저 일어나므로, 닫힌 뒤의 호출은 counter를 소비하지 않는다.
         */
        public fun encrypt(
            message: RoutableMessage,
            expiresIn: Duration,
        ): SignerResult<RoutableMessage> {
            checkOpen()
            if (counterValue == UInt.MAX_VALUE) {
                return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, "counter rollover")
            }
            counterValue++
            val domain =
                message.to_destination?.domain
                    ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_DOMAINS, "domain missing")
            val expiresSeconds = (timeZero.elapsedNow() + expiresIn).inWholeSeconds
            if (expiresSeconds < 0 || expiresSeconds > CommandMetadata.EPOCH_LENGTH_SECONDS.toLong()) {
                return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, "out of bounds expiration time")
            }
            val expiresAt = expiresSeconds.toUInt()
            val payload =
                message.protobuf_message_as_bytes?.toByteArray()
                    ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, "Missing protobuf message")
            val meta = CommandMetadata.build(domain, vin.toByteArray(), epochBytes, expiresAt, counterValue, message.flags.toUInt())
            val nonce = random.nextBytes(Session.NONCE_SIZE)
            val out = session.encrypt(payload, crypto.sha256(meta.serialize()), nonce)
            return SignerResult.Ok(
                message.copy(
                    protobuf_message_as_bytes = out.ciphertext.toByteString(),
                    signature_data =
                        SignatureData(
                            signer_identity = KeyIdentity(public_key = localPublicKey.toByteArray().toByteString()),
                            AES_GCM_Personalized_data =
                                AES_GCM_Personalized_Signature_Data(
                                    epoch = epochBytes.toByteString(),
                                    nonce = nonce.toByteString(),
                                    counter = counterValue.toInt(),
                                    expires_at = expiresAt.toInt(),
                                    tag = out.tag.toByteString(),
                                ),
                        ),
                ),
            )
        }

        /**
         * Go `Decrypt`: AAD = SHA256(TLV{9, from_domain(없으면 0), VIN, counter, flags(항상), request_hash, fault}).
         * 인증 실패는 `INVALID_SIGNATURE`(원본은 암호 라이브러리 오류를 그대로 돌려주지만 의미는 같다), GCM 데이터가 없으면 `BAD_PARAMETER`.
         *
         * @throws IllegalStateException [close]로 닫힌 뒤 호출하면 발생한다(프로그래밍 오류, ADR-0006). 와이어 오류와
         *   구별되도록 `INVALID_SIGNATURE` 값으로 돌려주지 않는다.
         */
        public fun decrypt(
            message: RoutableMessage,
            requestHash: ByteArray,
        ): SignerResult<DecryptedResponse> {
            checkOpen()
            val gcm =
                message.signature_data?.AES_GCM_Response_data
                    ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, "missing AES-GCM data")
            val fromDomain = message.from_destination?.domain ?: Domain.DOMAIN_BROADCAST
            val fault = message.signedMessageStatus?.signed_message_fault?.value ?: 0
            val meta =
                ResponseMetadata.build(
                    fromDomain,
                    vin.toByteArray(),
                    gcm.counter.toUInt(),
                    message.flags.toUInt(),
                    requestHash,
                    fault.toUInt(),
                )
            val plaintext =
                session.decrypt(
                    gcm.nonce.toByteArray(),
                    message.protobuf_message_as_bytes?.toByteArray() ?: ByteArray(0),
                    gcm.tag.toByteArray(),
                    crypto.sha256(meta.serialize()),
                )
                    ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, "response authentication failed")
            return SignerResult.Ok(
                DecryptedResponse(
                    message.copy(
                        // Go `message.Payload = &RoutableMessage_ProtobufMessageAsBytes{...}`는 oneof 전체를 교체한다.
                        // `session_info`/`session_info_request`를 명시적으로 지우지 않으면, 인증된 payload가 비어 있는
                        // 응답에서 MITM이 다른 oneof 멤버로 바이트를 옮겨도 태그가 여전히 맞아 이 copy()가 Wire의
                        // "at most one of ..." 불변조건을 어겨 IllegalArgumentException을 던진다(와이어 입력이 예외를
                        // 던지면 안 된다 — ADR-0006).
                        protobuf_message_as_bytes = plaintext.toByteString(),
                        session_info_request = null,
                        session_info = null,
                        signature_data = null,
                    ),
                    gcm.counter.toUInt(),
                ),
            )
        }

        /** 세션 키를 0으로 덮는다. 이후 [encrypt]·[decrypt]·[updateSignedSessionInfo]는 `IllegalStateException`을 던진다. */
        override fun close() {
            closed = true
            session.close()
        }

        private fun checkOpen() {
            check(!closed) { "signer closed" }
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
             * [age]가 음수이면(캐시 저장과 불러오기 사이에 벽시계가 뒤로 감) 0으로 본다. 이는 프로그래밍 오류가 아닌
             * 런타임 조건이므로 예외를 던지지 않는다(ADR-0006). Go는 미래의 `generatedAt`을 그대로 받아 `timeZero`가
             * 뒤로 간다.
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
                val effectiveAge = age.coerceAtLeast(Duration.ZERO)
                val info =
                    decodeSessionInfo(encodedInfo)
                        ?: return SignerResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_DECODING, "invalid session info protobuf")
                return build(privateKey, vin, info, crypto, random, timeSource, effectiveAge)
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
