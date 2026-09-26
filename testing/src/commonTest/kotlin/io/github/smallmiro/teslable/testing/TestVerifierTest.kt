package io.github.smallmiro.teslable.testing

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.protocol.Session
import io.github.smallmiro.teslable.testing.fixtures.GoVectors
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(InternalTeslableApi::class)
class TestVerifierTest {
    private val crypto = TestCrypto.primitives
    private val vin = ProtocolVectors.VIN.encodeToByteArray()

    @Test
    fun decryptsMessageProducedByGoSigner() =
        // verifier_test.go TestGCMKnown
        runTest {
            val verifier =
                TestVerifier.create(
                    privateKey = TestCrypto.goKnownVerifierKey(),
                    personalization = GoVectors.GCM_KNOWN_PERSONALIZATION.encodeToByteArray(),
                    domain = Domain.DOMAIN_VEHICLE_SECURITY,
                    signerPublic = PublicKeyBytes(GoVectors.GCM_KNOWN_SIGNER_PUBLIC_KEY.hexToBytes()),
                    crypto = crypto,
                    random = FixedRandom(GoVectors.GCM_KNOWN_EPOCH.hexToBytes()), // 생성 시 epoch = 첫 random 16바이트
                    timeSource = TestTimeSource(),
                )
            assertEquals(GoVectors.GCM_KNOWN_EPOCH, verifier.epoch.toHex())
            val message = RoutableMessage.ADAPTER.decode(GoVectors.GCM_KNOWN_MESSAGE.decodeHex())
            val result = verifier.verify(message)
            val ok = assertIs<TestVerifier.VerifyResult.Ok>(result)
            assertEquals(GoVectors.GCM_KNOWN_PLAINTEXT, ok.plaintext.toHex())
        }

    @Test
    fun signedSessionInfoTagVerifiesWithClientSession() =
        runTest {
            val time = TestTimeSource()
            val verifier = docVerifier(time)
            val challenge = ProtocolVectors.CHALLENGE.hexToBytes()
            val signed = verifier.signedSessionInfo(challenge)
            val clientSession = Session.establish(TestCrypto.clientKey(), TestCrypto.vehiclePublicKey, crypto)
            assertTrue(clientSession.verifySessionInfoTag(vin, challenge, signed.encoded, signed.tag))
            val info =
                com.tesla.generated.signatures.SessionInfo.ADAPTER
                    .decode(signed.encoded)
            assertEquals(TestCrypto.vehiclePublicKey.toByteArray().toHex(), info.publicKey.hex())
            assertEquals(0, info.counter)
            assertEquals(0, info.clock_time)
            time += 42.seconds
            assertEquals(
                42,
                com.tesla.generated.signatures.SessionInfo.ADAPTER
                    .decode(verifier.signedSessionInfo(challenge).encoded)
                    .clock_time,
            )
        }

    @Test
    fun rotatesEpochWhenCounterIsExhausted() =
        runTest {
            val verifier = docVerifier(TestTimeSource(), random = FixedRandom(ByteArray(16) { 1 }, ByteArray(16) { 2 }))
            assertEquals(ByteArray(16) { 1 }.toHex(), verifier.epoch.toHex())
            verifier.forceCounter(UInt.MAX_VALUE)
            verifier.sessionInfo() // rotateEpochIfNeeded(false): counter == 0xFFFFFFFF → 새 epoch, counter 0
            assertEquals(ByteArray(16) { 2 }.toHex(), verifier.epoch.toHex())
            assertEquals(0u, verifier.counter)
        }

    @Test
    fun encryptsResponseThatClientSessionDecrypts() =
        runTest {
            val verifier = docVerifier(TestTimeSource(), random = FixedRandom(ByteArray(16), "dbf79447fa156674dae1caed".hexToBytes()))
            val requestHash =
                RequestHash.of(
                    com.tesla.generated.signatures.SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED,
                    ProtocolVectors.HVAC_TAG_FLAGS2.hexToBytes(),
                    Domain.DOMAIN_INFOTAINMENT,
                )
            val response =
                RoutableMessage(
                    from_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
                    protobuf_message_as_bytes = "0a00".decodeHex(),
                )
            val encrypted = verifier.encryptResponse(response, requestHash, counter = 8u)
            val gcm = assertNotNull(encrypted.signature_data?.AES_GCM_Response_data)
            assertEquals(8, gcm.counter)
            val clientSession = Session.establish(TestCrypto.clientKey(), TestCrypto.vehiclePublicKey, crypto)
            val meta =
                io.github.smallmiro.teslable.protocol.ResponseMetadata.build(
                    Domain.DOMAIN_INFOTAINMENT,
                    vin,
                    8u,
                    0u,
                    requestHash,
                    0u,
                )
            assertContentEquals(
                "0a00".hexToBytes(),
                clientSession.decrypt(
                    gcm.nonce.toByteArray(),
                    assertNotNull(encrypted.protobuf_message_as_bytes).toByteArray(),
                    gcm.tag.toByteArray(),
                    crypto.sha256(meta.serialize()),
                ),
            )
        }

    @Test
    fun rejectsMessageWithoutSignatureDataAsPlainError() =
        // verifier.go Verify: "signature data missing" — 세션정보 동봉 없음
        runTest {
            val verifier = docVerifier(TestTimeSource())
            val noSignature = RoutableMessage(to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT))
            val fault = assertIs<TestVerifier.VerifyResult.Fault>(verifier.verify(noSignature))
            assertEquals(MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER, fault.fault)
            assertNull(fault.sessionInfo)
        }

    private suspend fun docVerifier(
        time: TestTimeSource,
        random: FixedRandom = FixedRandom(ProtocolVectors.EPOCH.hexToBytes(), fallback = TestCrypto.random),
    ): TestVerifier =
        TestVerifier.create(
            privateKey = TestCrypto.vehicleKey(),
            personalization = vin,
            domain = Domain.DOMAIN_VEHICLE_SECURITY,
            signerPublic = TestCrypto.clientPublicKey,
            crypto = crypto,
            random = random,
            timeSource = time,
        )
}
