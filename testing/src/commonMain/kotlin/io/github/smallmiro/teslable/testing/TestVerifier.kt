// Ported from vehicle-command@a4b43c1 internal/authentication/verifier.go (Apache-2.0) — GCM path only (no HMAC, no adjustClock)
package io.github.smallmiro.teslable.testing

import com.tesla.generated.signatures.AES_GCM_Personalized_Signature_Data
import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.protocol.CommandMetadata
import io.github.smallmiro.teslable.protocol.ResponseMetadata
import io.github.smallmiro.teslable.protocol.Session
import io.github.smallmiro.teslable.protocol.SlidingWindow
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val EPOCH_SIZE = 16
private const val NONCE_SIZE = 12
private const val MAX_SECONDS_WITHOUT_COUNTER = 30u
private const val MAX_PERSONALIZATION_LENGTH = 255

/** 서명된 세션정보(`SessionInfo` 인코딩 + HMAC 태그). Go `SignedSessionInfo`의 반환값 쌍. */
public class SignedSessionInfo(
    /** protobuf 인코딩된 `SessionInfo`. */
    public val encoded: ByteArray,
    /** `Session.sessionInfoTag`로 만든 32바이트 태그. */
    public val tag: ByteArray,
)

/**
 * 차량 측 검증자(테스트 전용). Go `Verifier`의 AES-GCM 경로를 결정적으로 재현한다.
 * 시간은 [timeSource]로만 흐르고(`adjustClock` 미포팅 — 테스트 시계는 잠들지 않는다), nonce·epoch는 [random]에서 나온다.
 *
 * [rotateEpoch]는 [SlidingWindow]도 새로 만든다. Go는 epoch 회전 뒤에도 `v.window`를 유지하지만 `counter = 0`에서
 * 다시 시작하므로 남은 비트는 다시 참조되지 않는다. 윈도우를 새로 만드는 것은 관찰상 동일하다.
 *
 * M2 `FakeVehicle`이 이 클래스를 감싼다. 스레드 안전하지 않다.
 */
@Suppress("TooManyFunctions") // Go Verifier/Peer의 공개·비공개 메서드와 1:1 대응 + 테스트 전용 조작 3개(exhaustCounter, shiftTimeZero, assignHandle)
public class TestVerifier private constructor(
    private val session: Session,
    /** TLV PERSONALIZATION 값(운영에서는 VIN 17자; Go 테스트는 임의 바이트). */
    public val personalization: ByteArray,
    /** 이 검증자가 강제하는 도메인. `DOMAIN_BROADCAST`면 검사하지 않는다. */
    public val domain: Domain,
    private val crypto: CryptoPrimitives,
    private val random: RandomSource,
    private val timeSource: TimeSource,
) {
    private var epochBytes = ByteArray(EPOCH_SIZE)
    private var counterValue = 0u
    private var timeZero: TimeMark = timeSource.markNow()
    private var window = SlidingWindow()
    private var handle = 0u

    /** 현재 epoch(16바이트 사본). */
    public val epoch: ByteArray get() = epochBytes.copyOf()

    /** 지금까지 수용한 최대 counter. */
    public val counter: UInt get() = counterValue

    /** 검증 결과. [Fault]의 [Fault.sessionInfo]가 null이 아니면 Go `InvalidSignatureError`(세션정보 동봉)다. */
    public sealed interface VerifyResult {
        /** 인증 통과, 복호화된 페이로드. */
        public class Ok(
            /** 평문 페이로드. */
            public val plaintext: ByteArray,
        ) : VerifyResult

        /** 인증 실패. */
        public class Fault(
            /** 차량이 응답에 실을 fault. */
            public val fault: MessageFault_E,
            /** `signatureError`면 동봉되는 최신 세션정보, 일반 오류면 null. */
            public val sessionInfo: SignedSessionInfo?,
        ) : VerifyResult
    }

    init {
        rotateEpoch()
    }

    /** Go `timestamp()`: timeZero 이후 초. */
    public fun timestamp(): UInt =
        timeZero
            .elapsedNow()
            .inWholeSeconds
            .coerceAtLeast(0)
            .toUInt()

    /** Go `rotateEpochIfNeeded(true)`: 새 epoch, timeZero = now, counter = 0 (차량 재부팅 시나리오). */
    public fun rotateEpoch() {
        epochBytes = random.nextBytes(EPOCH_SIZE)
        timeZero = timeSource.markNow()
        counterValue = 0u
        window = SlidingWindow()
    }

    private fun rotateEpochIfNeeded() {
        if (counterValue == UInt.MAX_VALUE || timestamp() > CommandMetadata.EPOCH_LENGTH_SECONDS) rotateEpoch()
    }

    /** counter를 `0xFFFFFFFF`로 놓는다(Go `TestGCMEpochRotation`의 `signer.counter = 0xFFFFFFFE` 이후 상태). 다음 [sessionInfo]/[verify]가 epoch를 돌린다. */
    @InternalTeslableApi
    public fun exhaustCounter() {
        counterValue = UInt.MAX_VALUE
    }

    /** Go `AssignHandle`: 이후 [sessionInfo]의 `handle`에 실린다. */
    public fun assignHandle(handle: UInt) {
        this.handle = handle
    }

    /**
     * Go 테스트의 `verifier.timeZero = verifier.timeZero.Add(by)`. [by]가 양수면 시계 원점이 뒤로 밀려 [timestamp]가
     * 줄어들고(시계 역행 시나리오), 음수면 [timestamp]가 커진다(`TestGCMExpired`의 `-time.Hour`).
     */
    @InternalTeslableApi
    public fun shiftTimeZero(by: Duration) {
        timeZero = timeZero + by
    }

    /** Go `sessionInfo`: 필요하면 epoch를 돌린 뒤 현재 상태. */
    public fun sessionInfo(): SessionInfo {
        rotateEpochIfNeeded()
        return SessionInfo(
            counter = counterValue.toInt(),
            publicKey = session.localPublicKey.toByteArray().toByteString(),
            epoch = epochBytes.toByteString(),
            clock_time = timestamp().toInt(),
            handle = handle.toInt(),
        )
    }

    /** Go `SignedSessionInfo`: 인코딩 + `SessionInfoHMAC(personalization, challenge, encoded)`. */
    public fun signedSessionInfo(challenge: ByteArray): SignedSessionInfo {
        val encoded = SessionInfo.ADAPTER.encode(sessionInfo())
        return SignedSessionInfo(encoded, session.sessionInfoTag(personalization, challenge, encoded))
    }

    /** Go `SetSessionInfo`: 오류 응답에 세션정보와 태그를 싣는다. payload oneof의 다른 멤버는 Go처럼 지운다. */
    public fun setSessionInfo(
        challenge: ByteArray,
        message: RoutableMessage,
    ): RoutableMessage {
        val signed = signedSessionInfo(challenge)
        return message.copy(
            protobuf_message_as_bytes = null,
            session_info_request = null,
            session_info = signed.encoded.toByteString(),
            signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = signed.tag.toByteString())),
        )
    }

    private fun signatureError(
        fault: MessageFault_E,
        challenge: ByteString,
    ): VerifyResult.Fault = VerifyResult.Fault(fault, signedSessionInfo(challenge.toByteArray()))

    /** Go `Verify` + `verifyGCM`: 세션정보 검사 → 메타데이터 → 복호화 → 슬라이딩 윈도우. */
    public fun verify(message: RoutableMessage): VerifyResult {
        rotateEpochIfNeeded()
        val signature = message.signature_data ?: return VerifyResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, null)
        val gcm = signature.AES_GCM_Personalized_data ?: return VerifyResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, null)
        verifySessionInfo(message, gcm)?.let { return it }
        val toDomain = message.to_destination?.domain ?: return VerifyResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_DOMAINS, null)
        val meta =
            CommandMetadata.build(
                domain = toDomain,
                personalization = personalization,
                epoch = epochBytes, // verifier.go extractMetadata: p.epoch (검증자 자신의 epoch)
                expiresAt = gcm.expires_at.toUInt(),
                counter = gcm.counter.toUInt(),
                flags = message.flags.toUInt(),
            )
        val plaintext =
            session.decrypt(
                nonce = gcm.nonce.toByteArray(),
                ciphertext = message.protobuf_message_as_bytes?.toByteArray() ?: ByteArray(0),
                tag = gcm.tag.toByteArray(),
                aad = crypto.sha256(meta.serialize()),
            ) ?: return signatureError(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, message.uuid)
        val counter = gcm.counter.toUInt()
        if (counter > 0u) {
            if (!window.update(counter)) return signatureError(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, message.uuid)
            if (counter > counterValue) counterValue = counter
        }
        return VerifyResult.Ok(plaintext)
    }

    // verifier.go verifySessionInfo
    private fun verifySessionInfo(
        message: RoutableMessage,
        gcm: AES_GCM_Personalized_Signature_Data,
    ): VerifyResult? {
        val toDomain = message.to_destination?.domain
        if (toDomain != domain && domain != Domain.DOMAIN_BROADCAST) {
            return VerifyResult.Fault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_DOMAINS, null)
        }
        if (gcm.epoch.size > 0 && !gcm.epoch.toByteArray().contentEquals(epochBytes)) {
            return signatureError(MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH, message.uuid)
        }
        val expiresAt = gcm.expires_at.toUInt()
        val now = timestamp()
        if (expiresAt != 0u && expiresAt < now) return signatureError(MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED, message.uuid)
        if (expiresAt >
            CommandMetadata.EPOCH_LENGTH_SECONDS
        ) {
            return signatureError(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, message.uuid)
        }
        val counter = gcm.counter.toUInt()
        if (counter == 0u || counter < counterValue) {
            if (expiresAt == 0u || expiresAt - now > MAX_SECONDS_WITHOUT_COUNTER) {
                return signatureError(MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG, message.uuid)
            }
        }
        return null
    }

    /** Go `Verifier.Encrypt`: 응답 페이로드를 AES-GCM으로 감싼다. AAD = SHA256(responseMetadata). */
    public fun encryptResponse(
        message: RoutableMessage,
        requestHash: ByteArray,
        counter: UInt,
    ): RoutableMessage {
        val fromDomain = message.from_destination?.domain ?: Domain.DOMAIN_BROADCAST
        val fault = message.signedMessageStatus?.signed_message_fault?.value ?: 0
        val meta = ResponseMetadata.build(fromDomain, personalization, counter, message.flags.toUInt(), requestHash, fault.toUInt())
        val nonce = random.nextBytes(NONCE_SIZE)
        val out = session.encrypt(message.protobuf_message_as_bytes?.toByteArray() ?: ByteArray(0), crypto.sha256(meta.serialize()), nonce)
        return message.copy(
            protobuf_message_as_bytes = out.ciphertext.toByteString(),
            session_info = null,
            session_info_request = null,
            signature_data =
                SignatureData(
                    AES_GCM_Response_data =
                        AES_GCM_Response_Signature_Data(
                            nonce = nonce.toByteString(),
                            counter = counter.toInt(),
                            tag = out.tag.toByteString(),
                        ),
                ),
        )
    }

    /** 생성. */
    public companion object {
        /** Go `NewVerifier`: ECDH → 세션, 첫 epoch 생성. */
        @Suppress("LongParameterList") // Go NewVerifier 인자(+crypto/random/timeSource 주입)와 1:1 대응
        public suspend fun create(
            privateKey: EcdhPrivateKey,
            personalization: ByteArray,
            domain: Domain,
            signerPublic: PublicKeyBytes,
            crypto: CryptoPrimitives,
            random: RandomSource,
            timeSource: TimeSource = TimeSource.Monotonic,
        ): TestVerifier {
            require(personalization.size <= MAX_PERSONALIZATION_LENGTH) { "metadata fields can't be more than 255 bytes long" }
            val session = Session.establish(privateKey, signerPublic, crypto)
            return TestVerifier(session, personalization.copyOf(), domain, crypto, random, timeSource)
        }
    }
}
