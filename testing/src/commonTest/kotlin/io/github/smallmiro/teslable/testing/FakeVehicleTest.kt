package io.github.smallmiro.teslable.testing

import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.protocol.CommandMetadata
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.protocol.Session
import io.github.smallmiro.teslable.protocol.Signer
import io.github.smallmiro.teslable.protocol.SignerResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalTeslableApi::class, ExperimentalCoroutinesApi::class)
class FakeVehicleTest {
    private val crypto = TestCrypto.primitives
    private val plaintext = "hello world".encodeToByteArray() // peer_test.go testMessagePlaintext
    private val domain = Domain.DOMAIN_VEHICLE_SECURITY

    /** dispatcher 없이 FakeVehicle과 직접 대화하는 최소 클라이언트. 주소·uuid는 TestCrypto.random으로 뽑는다. */
    private class Client(
        val fake: FakeVehicle,
        val transport: FakeTransport,
        val scope: TestScope,
    ) {
        val crypto = TestCrypto.primitives

        suspend fun exchange(message: RoutableMessage): RoutableMessage? {
            val before = transport.delivered
            assertIs<VehicleResult.Success<Unit>>(transport.send(RoutableMessage.ADAPTER.encode(message)))
            if (transport.delivered == before) return null
            return RoutableMessage.ADAPTER.decode(transport.incoming.first())
        }

        suspend fun handshake(domain: Domain): Signer {
            val request =
                RoutableMessage(
                    to_destination = Destination(domain = domain),
                    from_destination = Destination(routing_address = TestCrypto.random.nextBytes(16).toByteString()),
                    uuid = TestCrypto.random.nextBytes(16).toByteString(),
                    session_info_request = SessionInfoRequest(public_key = TestCrypto.clientPublicKey.toByteArray().toByteString()),
                )
            val reply = assertNotNull(exchange(request))
            val info = assertNotNull(reply.session_info).toByteArray()
            val tag = assertNotNull(reply.signature_data?.session_info_tag).tag.toByteArray()
            val created =
                Signer.createAuthenticated(
                    TestCrypto.clientKey(),
                    fake.vin,
                    request.uuid.toByteArray(),
                    info,
                    tag,
                    crypto,
                    TestCrypto.random,
                    scope.testTimeSource,
                )
            return assertIs<SignerResult.Ok<Signer>>(created).value
        }

        fun command(
            domain: Domain,
            payload: ByteArray,
            flags: Int = 0,
        ): RoutableMessage =
            RoutableMessage(
                to_destination = Destination(domain = domain),
                from_destination = Destination(routing_address = TestCrypto.random.nextBytes(16).toByteString()),
                uuid = TestCrypto.random.nextBytes(16).toByteString(),
                protobuf_message_as_bytes = payload.toByteString(),
                flags = flags,
            )

        fun encrypt(
            signer: Signer,
            message: RoutableMessage,
            lifetime: Duration = 1.minutes,
        ): RoutableMessage = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(message, lifetime)).value
    }

    private fun TestScope.client(fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource)): Client =
        Client(fake, fake.transport(), this)

    /** verifier_test.go runVerifyTest의 응답판: fault와 세션정보 동봉 여부를 본다. */
    private fun assertFault(
        reply: RoutableMessage?,
        expected: MessageFault_E,
        expectSessionInfo: Boolean,
    ) {
        val message = assertNotNull(reply, "vehicle must reply")
        assertEquals(expected, message.signedMessageStatus?.signed_message_fault ?: MessageFault_E.MESSAGEFAULT_ERROR_NONE)
        assertEquals(expectSessionInfo, message.session_info != null, "session info attached")
        if (expectSessionInfo) assertNotNull(message.signature_data?.session_info_tag)
    }

    @Test
    fun handshakeReplyMirrorsGoInitReply() =
        runTest {
            val c = client()
            val request =
                RoutableMessage(
                    to_destination = Destination(domain = domain),
                    from_destination = Destination(routing_address = ByteArray(16) { 5 }.toByteString()),
                    uuid = ByteArray(16) { 7 }.toByteString(),
                    session_info_request = SessionInfoRequest(public_key = TestCrypto.clientPublicKey.toByteArray().toByteString()),
                )
            val reply = assertNotNull(c.exchange(request))
            // dispatcher_test.go initReply: to = request.from, from = request.to, request_uuid = request.uuid, uuid = testUUID()
            assertContentEquals(ByteArray(16) { 5 }, assertNotNull(reply.to_destination?.routing_address).toByteArray())
            assertEquals(domain, reply.from_destination?.domain)
            assertContentEquals(ByteArray(16) { 7 }, reply.request_uuid.toByteArray())
            assertContentEquals(FakeVehicle.TEST_UUID, reply.uuid.toByteArray())
            assertNull(reply.session_info_request)
            assertEquals(1, c.fake.sessionInfoRequests)
            // 태그는 클라이언트 세션으로 검증된다(TestVerifier.setSessionInfo 경로)
            val session = Session.establish(TestCrypto.clientKey(), TestCrypto.vehiclePublicKey, crypto)
            assertTrue(
                session.verifySessionInfoTag(
                    c.fake.vin.toByteArray(),
                    ByteArray(16) { 7 },
                    assertNotNull(reply.session_info).toByteArray(),
                    assertNotNull(reply.signature_data?.session_info_tag).tag.toByteArray(),
                ),
            )
        }

    @Test
    fun acceptsThenRejectsReplayedCommand() =
        // verifier_test.go TestValidGCMEncryption
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            assertFault(c.exchange(message), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
            assertFault(c.exchange(message), MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, expectSessionInfo = true)
        }

    @Test
    fun rejectsTamperedFlags() =
        // verifier_test.go TestGCMFlags
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            assertFault(c.exchange(message.copy(flags = 1)), MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, expectSessionInfo = true)
            assertFault(c.exchange(message.copy(flags = 0)), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
        }

    @Test
    fun rejectsMissingDestinationAsInvalidDomains() =
        // verifier_test.go TestGCMMissingDestination — FakeVehicle은 도메인 없는 메시지를 라우팅할 수 없어 검증자를 직접 부른다
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val result = assertIs<TestVerifier.VerifyResult.Fault>(c.fake.verifier(domain).verify(message.copy(to_destination = null)))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_DOMAINS, result.fault)
            assertNull(result.sessionInfo)
            assertNull(c.exchange(message.copy(to_destination = null))) // 라우팅 불가 → 응답 없음
        }

    @Test
    fun rejectsOutOfOrderMessageWithTtlTooLong() =
        // verifier_test.go TestGCMOutOfOrderMessage
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val first = c.encrypt(signer, c.command(domain, plaintext))
            val second = c.encrypt(signer, c.command(domain, plaintext))
            assertFault(c.exchange(second), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
            assertFault(c.exchange(first), MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG, expectSessionInfo = true)
        }

    @Test
    fun rejectsAfterRebootThenResyncsWithAttachedSessionInfo() =
        // verifier_test.go TestEpochChange (차량 재부팅 = rotateEpoch)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val firstReply = c.exchange(c.encrypt(signer, c.command(domain, plaintext), 1.seconds))
            assertFault(firstReply, MessageFault_E.MESSAGEFAULT_ERROR_NONE, false)
            val oldEpoch = c.fake.epoch(domain)
            c.fake.rotateEpoch(domain)
            assertFalse(oldEpoch.contentEquals(c.fake.epoch(domain)))
            val stale = c.encrypt(signer, c.command(domain, plaintext), 1.seconds)
            val reply = assertNotNull(c.exchange(stale))
            assertFault(reply, MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH, expectSessionInfo = true)
            // 동봉 세션정보로 재동기화 (challenge = 요청 uuid = 응답 request_uuid)
            val update =
                signer.updateSignedSessionInfo(
                    reply.request_uuid.toByteArray(),
                    assertNotNull(reply.session_info).toByteArray(),
                    assertNotNull(reply.signature_data?.session_info_tag).tag.toByteArray(),
                )
            assertIs<SignerResult.Ok<Unit>>(update)
            assertContentEquals(c.fake.epoch(domain), signer.epoch)
            val resynced = c.exchange(c.encrypt(signer, c.command(domain, plaintext), 1.seconds))
            assertFault(resynced, MessageFault_E.MESSAGEFAULT_ERROR_NONE, false)
        }

    @Test
    fun rejectsExpiredCommand() =
        // verifier_test.go TestGCMExpired: verifier.timeZero -1h → 차량 시계가 1시간 앞선다
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            c.fake.shiftClock(domain, 1.hours)
            assertFault(c.exchange(message), MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED, expectSessionInfo = true)
        }

    @Test
    fun rejectsWrongEpoch() =
        // verifier_test.go TestGCMInvalidEpoch (검증자 epoch를 뒤집는 대신 메시지 epoch를 뒤집는다 — 같은 분기)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val gcm = assertNotNull(message.signature_data?.AES_GCM_Personalized_data)
            val flipped = gcm.epoch.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val tampered =
                message.copy(
                    signature_data =
                        SignatureData(
                            signer_identity = message.signature_data?.signer_identity,
                            AES_GCM_Personalized_data = gcm.copy(epoch = flipped.toByteString()),
                        ),
                )
            assertFault(c.exchange(tampered), MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH, expectSessionInfo = true)
        }

    @Test
    fun rejectsCorruptedCiphertext() =
        // verifier_test.go TestGCMCorruptedCiphertext
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val ct = assertNotNull(message.protobuf_message_as_bytes).toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val corrupted = c.exchange(message.copy(protobuf_message_as_bytes = ct.toByteString()))
            assertFault(corrupted, MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, true)
        }

    @Test
    fun rejectsExpirationBeyondEpochLength() =
        // verifier_test.go TestGCMInvalidTime: expires_at > epochLength → BAD_PARAMETER (서명 검사보다 앞서므로 세션정보 동봉)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            val message = c.encrypt(signer, c.command(domain, plaintext))
            val gcm = assertNotNull(message.signature_data?.AES_GCM_Personalized_data)
            val tooLate = gcm.copy(expires_at = (CommandMetadata.EPOCH_LENGTH_SECONDS + 1u).toInt())
            val tampered =
                message.copy(
                    signature_data =
                        SignatureData(
                            signer_identity = message.signature_data?.signer_identity,
                            AES_GCM_Personalized_data = tooLate,
                        ),
                )
            assertFault(c.exchange(tampered), MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, expectSessionInfo = true)
        }

    @Test
    fun encryptsResponseThatSignerDecrypts() =
        // verifier_test.go TestVerifierEncryption (flags & 2 → AES_GCM_Response)
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            c.fake.script(domain, listOf(FakeVehicle.ScriptedReply(payload = "hello".encodeToByteArray())))
            val request = c.encrypt(signer, c.command(domain, plaintext, flags = FakeVehicle.FLAG_ENCRYPT_RESPONSE))
            val reply = assertNotNull(c.exchange(request))
            val gcm = assertNotNull(reply.signature_data?.AES_GCM_Response_data)
            assertEquals(1, gcm.counter)
            assertFalse("hello".encodeToByteArray().contentEquals(assertNotNull(reply.protobuf_message_as_bytes).toByteArray()))
            val id = assertNotNull(RequestHash.of(request))
            assertIs<SignerResult.Fault>(signer.decrypt(reply, id.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
            val decrypted = assertIs<SignerResult.Ok<Signer.DecryptedResponse>>(signer.decrypt(reply, id)).value
            assertEquals(1u, decrypted.counter)
            assertContentEquals("hello".encodeToByteArray(), assertNotNull(decrypted.message.protobuf_message_as_bytes).toByteArray())
        }

    @Test
    fun enforcesSlidingWindowLikeGo() =
        // verifier_test.go TestGCMWindow (windowSize 32, maxSecondsWithoutCounter 30)
        runTest {
            val windowSize = 32
            val duration = 28.seconds
            val c = client()
            val signer = c.handshake(domain)
            for (k in 0 until 3 * windowSize) {
                val prepared = List(windowSize) { c.encrypt(signer, c.command(domain, plaintext), duration) }
                repeat(k + 1) {
                    val fresh = c.exchange(c.encrypt(signer, c.command(domain, plaintext), duration))
                    assertFault(fresh, MessageFault_E.MESSAGEFAULT_ERROR_NONE, false)
                }
                for (i in 0 until windowSize) {
                    val j = ((i + 1) * 97) % windowSize
                    if (j >= k) assertFault(c.exchange(prepared[j]), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
                    val replay = c.exchange(prepared[j])
                    assertFault(replay, MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, expectSessionInfo = true)
                }
            }
        }

    @Test
    fun scriptsHandshakeFaultsDropsAndCorruptedTags() =
        runTest {
            val c = client()
            c.fake.scriptHandshake(domain, MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID)
            val request =
                c.command(domain, plaintext).copy(
                    protobuf_message_as_bytes = null,
                    session_info_request = SessionInfoRequest(public_key = TestCrypto.clientPublicKey.toByteArray().toByteString()),
                )
            assertFault(c.exchange(request), MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID, expectSessionInfo = false)
            c.fake.corruptNextSessionInfoTag(domain)
            val corrupted = assertNotNull(c.exchange(request))
            val session = Session.establish(TestCrypto.clientKey(), TestCrypto.vehiclePublicKey, crypto)
            assertFalse(
                session.verifySessionInfoTag(
                    c.fake.vin.toByteArray(),
                    request.uuid.toByteArray(),
                    assertNotNull(corrupted.session_info).toByteArray(),
                    assertNotNull(corrupted.signature_data?.session_info_tag).tag.toByteArray(),
                ),
            )
            c.fake.dropNextReplies(1)
            assertNull(c.exchange(request))
            c.fake.sleep()
            assertNull(c.exchange(request))
            c.fake.wake()
            assertNotNull(c.exchange(request))
            assertEquals(5, c.fake.sessionInfoRequests)
        }

    @Test
    fun replaysLastResponseAndAttachesSessionInfoOnce() =
        runTest {
            val c = client()
            val signer = c.handshake(domain)
            c.fake.attachSessionInfoOnce(domain)
            val first = assertNotNull(c.exchange(c.encrypt(signer, c.command(domain, plaintext, FakeVehicle.FLAG_ENCRYPT_RESPONSE))))
            assertNotNull(first.session_info) // 선제적 세션정보는 평문 + 태그(암호화하지 않는다)
            assertNull(first.signature_data?.AES_GCM_Response_data)
            val second = assertNotNull(c.exchange(c.encrypt(signer, c.command(domain, plaintext, FakeVehicle.FLAG_ENCRYPT_RESPONSE))))
            assertNull(second.session_info)
            assertEquals(1, assertNotNull(second.signature_data?.AES_GCM_Response_data).counter)
            c.fake.replayLastResponse(domain)
            val replayed = RoutableMessage.ADAPTER.decode(c.transport.incoming.first())
            assertEquals(second, replayed)
        }

    @Test
    fun refusesConnectionWhenNotConnectable() =
        // 슬롯 초과(M2 수준): 광고가 connectable=false면 연결 자체가 MaxConnectionsExceeded로 실패하고 재시도하지 않는다
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            assertIs<VehicleResult.Success<FakeTransport>>(fake.connect())
            fake.setConnectable(false)
            val refused = assertIs<VehicleResult.Failure>(fake.connect())
            assertEquals(VehicleError.TransportError.MaxConnectionsExceeded, refused.error)
            assertFalse(refused.error.shouldRetry())
        }
}
