package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.FixedRandom
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.testing.TestVerifier
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(InternalTeslableApi::class)
class SignerCryptoTest {
    private val crypto = TestCrypto.primitives
    private val vin = Vin(ProtocolVectors.VIN)
    private val challenge = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
    private val plaintext = "hello world".encodeToByteArray() // peer_test.go testMessagePlaintext

    /** verifier.encryptResponse가 요구하는, 실제 요청과 무관한 임의의 16바이트 태그 (request hash 생성용). */
    private val arbitraryResponseTag = ByteArray(16) { 9 }

    /** [arbitraryResponseTag]로 만든, 실제 요청과 무관한 request hash. */
    private fun arbitraryRequestHash(domain: Domain = Domain.DOMAIN_VEHICLE_SECURITY): ByteArray =
        RequestHash.of(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED, arbitraryResponseTag, domain)

    /** [index]번째 바이트를 뒤집는다 — AAD/서명 불일치를 만드는 최소 변형. */
    private fun ByteArray.flipByte(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

    private fun testMessage(domain: Domain = Domain.DOMAIN_VEHICLE_SECURITY): RoutableMessage =
        // peer_test.go getTestMessage
        RoutableMessage(to_destination = Destination(domain = domain), protobuf_message_as_bytes = plaintext.toByteString())

    private suspend fun pair(
        time: TestTimeSource = TestTimeSource(),
        signerRandom: io.github.smallmiro.teslable.port.RandomSource = TestCrypto.random,
    ): Pair<TestVerifier, Signer> {
        val verifier =
            TestVerifier.create(
                TestCrypto.vehicleKey(),
                vin.toByteArray(),
                Domain.DOMAIN_VEHICLE_SECURITY,
                TestCrypto.clientPublicKey,
                crypto,
                FixedRandom(ProtocolVectors.EPOCH.hexToBytes(), fallback = TestCrypto.random),
                time,
            )
        val signed = verifier.signedSessionInfo(challenge)
        val signer =
            assertIs<SignerResult.Ok<Signer>>(
                Signer.createAuthenticated(TestCrypto.clientKey(), vin, challenge, signed.encoded, signed.tag, crypto, signerRandom, time),
            ).value
        return verifier to signer
    }

    /** verifier_test.go runVerifyTest */
    private fun assertVerifies(
        verifier: TestVerifier,
        message: RoutableMessage,
        expected: MessageFault_E,
        expectSessionInfo: Boolean,
    ) {
        when (val result = verifier.verify(message)) {
            is TestVerifier.VerifyResult.Ok -> {
                assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_NONE, expected)
                assertContentEquals(plaintext, result.plaintext)
            }

            is TestVerifier.VerifyResult.Fault -> {
                assertEquals(expected, result.fault)
                assertEquals(expectSessionInfo, result.sessionInfo != null)
            }
        }
    }

    @Test
    fun reproducesProtocolDocHvacVector() =
        // 03-protocol.md §8.2.1 정본: nonce dbf7…, counter 7, expires_at 2655, flags 2
        runTest {
            val time = TestTimeSource()
            val info =
                SessionInfo(
                    counter = ProtocolVectors.COUNTER,
                    publicKey = TestCrypto.vehiclePublicKey.toByteArray().toByteString(),
                    epoch = ProtocolVectors.EPOCH.hexToBytes().toByteString(),
                    clock_time = ProtocolVectors.CLOCK_TIME,
                )
            val signer =
                assertIs<SignerResult.Ok<Signer>>(
                    Signer.create(TestCrypto.clientKey(), vin, info, crypto, FixedRandom(ProtocolVectors.HVAC_NONCE.hexToBytes()), time),
                ).value
            val message =
                RoutableMessage(
                    to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
                    protobuf_message_as_bytes = ProtocolVectors.HVAC_ON_PLAINTEXT.decodeHex(),
                    flags = 2,
                )
            // 2650 + 5 = 2655
            val encrypted = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(message, expiresIn = 5.seconds)).value
            val gcm = assertNotNull(encrypted.signature_data?.AES_GCM_Personalized_data)
            assertEquals(ProtocolVectors.HVAC_COUNTER, gcm.counter)
            assertEquals(ProtocolVectors.HVAC_EXPIRES_AT, gcm.expires_at)
            assertEquals(ProtocolVectors.EPOCH, gcm.epoch.hex())
            assertEquals(ProtocolVectors.HVAC_NONCE, gcm.nonce.hex())
            assertEquals(ProtocolVectors.HVAC_CIPHERTEXT, assertNotNull(encrypted.protobuf_message_as_bytes).hex())
            assertEquals(ProtocolVectors.HVAC_TAG_FLAGS2, gcm.tag.hex())
            assertEquals(
                TestCrypto.clientPublicKey.toByteArray().toHex(),
                assertNotNull(encrypted.signature_data.signer_identity?.public_key).hex(),
            )
            assertEquals(2, encrypted.flags)
            assertEquals(7u, signer.counter)
        }

    @Test
    fun verifierAcceptsThenRejectsReplay() =
        // verifier_test.go TestValidGCMEncryption
        runTest {
            val (verifier, signer) = pair()
            val message = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(testMessage(), 1.minutes)).value
            assertVerifies(verifier, message, MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
            assertVerifies(verifier, message, MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, expectSessionInfo = true)
        }

    @Test
    fun flagsAreAuthenticated() =
        // TestGCMFlags: MITM이 flags를 바꾸면 해시가 어긋난다
        runTest {
            val (verifier, signer) = pair()
            val message = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(testMessage(), 1.minutes)).value
            assertVerifies(verifier, message.copy(flags = 1), MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, expectSessionInfo = true)
            assertVerifies(verifier, message.copy(flags = 0), MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
        }

    @Test
    fun refusesToEncryptAfterCounterRollover() =
        // TestSignerCounterRollover; Review Focus 5 (Wire uint32 → Int -1)
        runTest {
            val (verifier, signer) = pair()
            assertIs<SignerResult.Ok<Unit>>(signer.updateSessionInfo(verifier.sessionInfo().copy(counter = -1, clock_time = 1)))
            assertEquals(UInt.MAX_VALUE, signer.counter)
            val first = signer.encrypt(testMessage(), 1.seconds)
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, assertIs<SignerResult.Fault>(first).fault)
            val second = signer.encrypt(testMessage(), 1.seconds) // 이 상태에 갇힌다
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, assertIs<SignerResult.Fault>(second).fault)
        }

    @Test
    fun staleSessionInfoDoesNotRollBackVerifier() =
        // TestUpdateInvalidSessionInfo
        runTest {
            val (verifier, signer) = pair()
            val signed = verifier.signedSessionInfo("challenge".encodeToByteArray())
            val message = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(testMessage(), 1.seconds)).value
            assertVerifies(verifier, message, MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
            val message2 = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(testMessage(), 1.seconds)).value
            // 오래된 정보
            assertIs<SignerResult.Ok<Unit>>(signer.updateSignedSessionInfo("challenge".encodeToByteArray(), signed.encoded, signed.tag))
            assertVerifies(verifier, message, MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER, expectSessionInfo = true)
            assertVerifies(verifier, message2, MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
        }

    @Test
    fun exportedSessionResumesAfterThirtyMinutes() =
        // TestExportImport
        runTest {
            val time = TestTimeSource()
            val (verifier, signer) = pair(time)
            val message = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(testMessage(), 1.minutes)).value
            assertVerifies(verifier, message, MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
            val cache = signer.exportSessionInfo()
            // 차량과 클라이언트의 시계가 함께 흐름 = Go verifier.timeZero -30min + ImportSessionInfo(now - 30min)
            time += 30.minutes
            val resumed =
                assertIs<SignerResult.Ok<Signer>>(
                    Signer.importSessionInfo(TestCrypto.clientKey(), vin, cache, age = 30.minutes, crypto, TestCrypto.random, time),
                ).value
            val later = assertIs<SignerResult.Ok<RoutableMessage>>(resumed.encrypt(testMessage(), 1.minutes)).value
            assertVerifies(verifier, later, MessageFault_E.MESSAGEFAULT_ERROR_NONE, expectSessionInfo = false)
        }

    @Test
    fun importWithWrongAgeExpiresImmediately() =
        // TestImportWrongTime
        runTest {
            val time = TestTimeSource()
            val (verifier, signer) = pair(time)
            val cache = signer.exportSessionInfo()
            time += 30.minutes
            val resumed =
                assertIs<SignerResult.Ok<Signer>>(
                    Signer.importSessionInfo(TestCrypto.clientKey(), vin, cache, age = 0.seconds, crypto, TestCrypto.random, time),
                ).value
            val stale = assertIs<SignerResult.Ok<RoutableMessage>>(resumed.encrypt(testMessage(), 1.minutes)).value
            assertVerifies(verifier, stale, MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED, expectSessionInfo = true)
        }

    @Test
    fun rejectsExpirationBeyondEpochLength() =
        // TestInvalidExpirationTime (AuthorizeHMAC 대신 encrypt; Review Focus 2)
        runTest {
            val (_, signer) = pair()
            val before = signer.counter
            val result = signer.encrypt(testMessage(), expiresIn = (2L * CommandMetadata.EPOCH_LENGTH_SECONDS.toLong()).seconds)
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, assertIs<SignerResult.Fault>(result).fault)
            assertEquals(before + 1u, signer.counter) // signer.go Encrypt: counter++ 뒤에 encryptWithCounter가 실패한다
        }

    @Test
    fun encryptAfterCloseThrowsWithoutConsumingCounter() =
        // Final review M-4 (ADR-0006): use after close is a programming error. The closed check runs before the rollover
        // check and counter++, so a closed signer never consumes a counter.
        runTest {
            val (_, signer) = pair()
            val before = signer.counter
            signer.close()
            assertFailsWith<IllegalStateException> { signer.encrypt(testMessage(), 1.minutes) }
            assertEquals(before, signer.counter)
        }

    @Test
    fun decryptAfterCloseThrows() =
        // Final review M-4: a closed signer used to answer Fault(INVALID_SIGNATURE) (Session.decrypt returns null when
        // closed), which masked a programming error as a wire error. Now it throws like encrypt.
        runTest {
            val (verifier, signer) = pair()
            val requestHash = arbitraryRequestHash()
            val response =
                RoutableMessage(
                    from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                    protobuf_message_as_bytes = "0a00".decodeHex(),
                )
            val encrypted = verifier.encryptResponse(response, requestHash, counter = 1u)
            signer.close()
            assertFailsWith<IllegalStateException> { signer.decrypt(encrypted, requestHash) }
        }

    @Test
    fun rejectsMessageWithoutDomainOrPayload() =
        // peer.go "domain missing", signer.go "Missing protobuf message"
        runTest {
            val (_, signer) = pair()
            assertEquals(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_DOMAINS,
                assertIs<SignerResult.Fault>(
                    signer.encrypt(RoutableMessage(protobuf_message_as_bytes = plaintext.toByteString()), 1.seconds),
                ).fault,
            )
            assertEquals(
                MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER,
                assertIs<SignerResult.Fault>(
                    signer.encrypt(RoutableMessage(to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY)), 1.seconds),
                ).fault,
            )
        }

    @Test
    fun decryptsVerifierResponseAndReturnsCounter() =
        // signer.go Decrypt + verifier.go Encrypt 왕복
        runTest {
            val (verifier, signer) = pair()
            val request = assertIs<SignerResult.Ok<RoutableMessage>>(signer.encrypt(testMessage(), 1.minutes)).value
            val requestHash = assertNotNull(RequestHash.of(request))
            val response =
                RoutableMessage(
                    from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                    protobuf_message_as_bytes = "0a00".decodeHex(),
                    flags = 0,
                )
            val encrypted = verifier.encryptResponse(response, requestHash, counter = 1u)
            val decrypted = assertIs<SignerResult.Ok<Signer.DecryptedResponse>>(signer.decrypt(encrypted, requestHash)).value
            assertEquals(1u, decrypted.counter)
            assertEquals("0a00", assertNotNull(decrypted.message.protobuf_message_as_bytes).hex())
            assertNull(decrypted.message.signature_data)
            // 다른 요청의 hash → AAD 불일치 → INVALID_SIGNATURE (드롭 대상)
            assertEquals(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                assertIs<SignerResult.Fault>(signer.decrypt(encrypted, requestHash.flipByte(3))).fault,
            )
            // GCM 응답 데이터 없음
            assertEquals(
                MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER,
                assertIs<SignerResult.Fault>(signer.decrypt(response, requestHash)).fault,
            )
        }

    @Test
    fun decryptReplacesWholePayloadOneofLikeGo() =
        // Fix round 1, Important: signer.go Decrypt는 message.Payload 전체(oneof)를 교체한다.
        // MITM은 인증된 payload가 비어 있는 응답에서 session_info로 바이트를 옮길 수 있다(태그는 여전히 맞는다) — decrypt는
        // 예외 없이 값으로 성공을 돌려주고, 옮겨 붙은 session_info를 지워야 한다(ADR-0006).
        runTest {
            val (verifier, signer) = pair()
            val requestHash = arbitraryRequestHash()
            val emptyResponse = RoutableMessage(from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY))
            val encrypted = verifier.encryptResponse(emptyResponse, requestHash, counter = 5u)
            // 인증된 payload가 비어 있으므로, 어느 oneof 멤버가 그 바이트를 담는지는 태그로 검증되지 않는다.
            val swapped = encrypted.copy(protobuf_message_as_bytes = null, session_info = "x".encodeUtf8())
            val decrypted = assertIs<SignerResult.Ok<Signer.DecryptedResponse>>(signer.decrypt(swapped, requestHash)).value
            assertNull(decrypted.message.session_info)
            assertEquals(0, assertNotNull(decrypted.message.protobuf_message_as_bytes).size)
        }

    @Test
    fun decryptRejectsMalformedGcmLengthsWithoutThrowing() =
        // Fix round 1, minor 2: M0 Session.decrypt는 길이가 틀린 nonce/tag를 null로 처리한다 (Go gcm.Open은 panic한다).
        runTest {
            val (_, signer) = pair()
            val requestHash = arbitraryRequestHash()
            val malformed =
                RoutableMessage(
                    from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                    signature_data =
                        SignatureData(
                            AES_GCM_Response_data =
                                AES_GCM_Response_Signature_Data(
                                    nonce = ByteString.EMPTY,
                                    counter = 1,
                                    tag = ByteArray(15).toByteString(),
                                ),
                        ),
                )
            val result = signer.decrypt(malformed, requestHash)
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, assertIs<SignerResult.Fault>(result).fault)
        }

    @Test
    fun decryptUsesBroadcastDomainWhenFromDestinationMissing() =
        // Review Focus 3; Go GetFromDestination().GetDomain() == 0
        runTest {
            val (verifier, signer) = pair()
            val requestHash = arbitraryRequestHash()
            val response = RoutableMessage(protobuf_message_as_bytes = "0a00".decodeHex())
            val encrypted = verifier.encryptResponse(response, requestHash, counter = 3u)
            assertIs<SignerResult.Ok<Signer.DecryptedResponse>>(signer.decrypt(encrypted, requestHash))
        }

    @Test
    fun faultIsPartOfResponseAad() =
        // 03-protocol.md §9.2 TLV FAULT
        runTest {
            val (verifier, signer) = pair()
            val requestHash = arbitraryRequestHash()
            val response =
                RoutableMessage(
                    from_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                    protobuf_message_as_bytes = "0a00".decodeHex(),
                    signedMessageStatus = MessageStatus(signed_message_fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY),
                )
            val encrypted = verifier.encryptResponse(response, requestHash, counter = 4u)
            assertIs<SignerResult.Ok<Signer.DecryptedResponse>>(signer.decrypt(encrypted, requestHash))
            val tampered =
                encrypted.copy(signedMessageStatus = MessageStatus(signed_message_fault = MessageFault_E.MESSAGEFAULT_ERROR_NONE))
            assertEquals(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                assertIs<SignerResult.Fault>(signer.decrypt(tampered, requestHash)).fault,
            )
        }
}
