package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.testing.FixedRandom
import io.github.smallmiro.teslable.testing.TestCrypto
import io.github.smallmiro.teslable.testing.TestVerifier
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(InternalTeslableApi::class)
class SignerTest {
    private val crypto = TestCrypto.primitives
    private val vin = Vin(ProtocolVectors.VIN)
    private val challenge = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7) // peer_test.go getGCMVerifierAndSigner

    /** Go `getGCMVerifierAndSigner`: 차량 = protocol.md vehicle.key, 클라이언트 = client.key, 같은 TestTimeSource. */
    private suspend fun pair(
        time: TestTimeSource = TestTimeSource(),
        verifierRandom: FixedRandom = FixedRandom(ProtocolVectors.EPOCH.hexToBytes(), fallback = TestCrypto.random),
    ): Pair<TestVerifier, Signer> {
        val verifier =
            TestVerifier.create(
                TestCrypto.vehicleKey(),
                vin.toByteArray(),
                Domain.DOMAIN_VEHICLE_SECURITY,
                TestCrypto.clientPublicKey,
                crypto,
                verifierRandom,
                time,
            )
        val signed = verifier.signedSessionInfo(challenge)
        val signer =
            Signer.createAuthenticated(
                TestCrypto.clientKey(),
                vin,
                challenge,
                signed.encoded,
                signed.tag,
                crypto,
                TestCrypto.random,
                time,
            )
        return verifier to assertIs<SignerResult.Ok<Signer>>(signer).value
    }

    /** publicKey(2번 필드, 65바이트) 안에서 잘라 Wire 디코딩이 확실히 EOF로 실패하게 만든다(Go `info[0] ^= 1`의 대체). */
    private fun corrupt(encoded: ByteArray): ByteArray = encoded.copyOf(10)

    private fun assertFault(
        expected: MessageFault_E,
        result: SignerResult<*>,
    ) {
        assertEquals(expected, assertIs<SignerResult.Fault>(result).fault)
    }

    @Test
    fun acceptsValidSignedSessionInfo() =
        // signer_test.go TestUpdateSessionInfo
        runTest {
            val (verifier, signer) = pair()
            val signed = verifier.signedSessionInfo(challenge)
            assertIs<SignerResult.Ok<Unit>>(signer.updateSignedSessionInfo(challenge, signed.encoded, signed.tag))
        }

    @Test
    fun rejectsTamperedSessionInfoProto() =
        // TestBadSessionInfoProto (Wire가 확실히 실패하는 절단으로 손상)
        runTest {
            val (verifier, signer) = pair()
            val signed = verifier.signedSessionInfo(challenge)
            val truncated = corrupt(signed.encoded)
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                signer.updateSignedSessionInfo(challenge, truncated, signed.tag),
            )
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_DECODING,
                Signer.createAuthenticated(
                    TestCrypto.clientKey(),
                    vin,
                    challenge,
                    truncated,
                    signed.tag,
                    crypto,
                    TestCrypto.random,
                    TestTimeSource(),
                ),
            )
            val vehicleSession = Session.establish(TestCrypto.vehicleKey(), TestCrypto.clientPublicKey, crypto)
            val retagged = vehicleSession.sessionInfoTag(vin.toByteArray(), challenge, truncated)
            assertFault(MessageFault_E.MESSAGEFAULT_ERROR_DECODING, signer.updateSignedSessionInfo(challenge, truncated, retagged))
        }

    @Test
    fun rejectsTamperedTagAndChallenge() =
        // TestBadSessionInfoTag, TestUpdateSessionInfoBadChallenge
        runTest {
            val (verifier, signer) = pair()
            val signed = verifier.signedSessionInfo(challenge)
            val badTag = signed.tag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                signer.updateSignedSessionInfo(challenge, signed.encoded, badTag),
            )
            val badChallenge = challenge.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                signer.updateSignedSessionInfo(badChallenge, signed.encoded, signed.tag),
            )
        }

    @Test
    fun rejectsReencodedCounterAndEpoch() =
        // TestUpdateSessionInfoBadCounter, TestUpdateSessionInfoBadEpoch
        runTest {
            val (verifier, signer) = pair()
            val signed = verifier.signedSessionInfo(challenge)
            val decoded = SessionInfo.ADAPTER.decode(signed.encoded)
            val bumped = SessionInfo.ADAPTER.encode(decoded.copy(counter = decoded.counter + 1))
            assertFault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, signer.updateSignedSessionInfo(challenge, bumped, signed.tag))
            val epoch = decoded.epoch.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            val flipped = SessionInfo.ADAPTER.encode(decoded.copy(epoch = epoch.toByteString()))
            assertFault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, signer.updateSignedSessionInfo(challenge, flipped, signed.tag))
        }

    @Test
    fun rejectsImposterVehicleKey() =
        // TestUpdateSessionInfoBadPublicKey
        runTest {
            val time = TestTimeSource()
            val (_, signer) = pair(time)
            // 사기꾼: 다른 개인키(Go 테스트 스칼라)로 만든 검증자. 클라이언트 세션 K가 다르므로 태그 불일치.
            val imposter =
                TestVerifier
                    .create(
                        TestCrypto.goKnownVerifierKey(),
                        vin.toByteArray(),
                        Domain.DOMAIN_VEHICLE_SECURITY,
                        TestCrypto.clientPublicKey,
                        crypto,
                        FixedRandom(
                            ByteArray(
                                16,
                            ) {
                                7
                            },
                        ),
                        time,
                    )
            val signed = imposter.signedSessionInfo(challenge)
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                signer.updateSignedSessionInfo(challenge, signed.encoded, signed.tag),
            )
            // 진짜 차량 키로 사기꾼의 info에 서명하면 HMAC은 통과하지만 공개키가 달라 UNKNOWN_KEY_ID
            val vehicleSession = Session.establish(TestCrypto.vehicleKey(), TestCrypto.clientPublicKey, crypto)
            val retagged = vehicleSession.sessionInfoTag(vin.toByteArray(), challenge, signed.encoded)
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID,
                signer.updateSignedSessionInfo(challenge, signed.encoded, retagged),
            )
        }

    @Test
    fun exposesVehiclePublicKey() =
        // TestRemotePublicKey
        runTest {
            val (verifier, signer) = pair()
            assertEquals(verifier.sessionInfo().publicKey.hex(), signer.vehiclePublicKey.toByteArray().toHex())
            assertEquals(TestCrypto.clientPublicKey, signer.localPublicKey)
        }

    @Test
    fun authenticatedCreationChecksProtoThenTag() =
        // TestNewAuthenticatedSigner
        runTest {
            val (verifier, _) = pair()
            val signed = verifier.signedSessionInfo(challenge)
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_DECODING,
                Signer.createAuthenticated(
                    TestCrypto.clientKey(),
                    vin,
                    challenge,
                    corrupt(signed.encoded),
                    signed.tag,
                    crypto,
                    TestCrypto.random,
                    TestTimeSource(),
                ),
            )
            val badTag = signed.tag.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
                Signer.createAuthenticated(
                    TestCrypto.clientKey(),
                    vin,
                    challenge,
                    signed.encoded,
                    badTag,
                    crypto,
                    TestCrypto.random,
                    TestTimeSource(),
                ),
            )
        }

    @Test
    fun acceptsSessionInfoAttachedToMessage() =
        // TestSetSessionInfo
        runTest {
            val (verifier, signer) = pair()
            val challenge2 = byteArrayOf(1, 2, 3, 4, 5)
            val message =
                verifier.setSessionInfo(
                    challenge2,
                    com.tesla.generated.universalmessage
                        .RoutableMessage(),
                )
            val encoded = kotlin.test.assertNotNull(message.session_info).toByteArray()
            val tag =
                kotlin.test
                    .assertNotNull(message.signature_data?.session_info_tag)
                    .tag
                    .toByteArray()
            assertIs<SignerResult.Ok<Unit>>(signer.updateSignedSessionInfo(challenge2, encoded, tag))
            challenge2[0] = (challenge2[0].toInt() xor 1).toByte()
            assertFault(MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE, signer.updateSignedSessionInfo(challenge2, encoded, tag))
        }

    @Test
    fun neverRollsCounterBack() =
        // Review Focus 4; signer.go UpdateSessionInfo "if s.counter < info.Counter"
        runTest {
            val (verifier, signer) = pair()
            val base = verifier.sessionInfo()
            assertIs<SignerResult.Ok<Unit>>(signer.updateSessionInfo(base.copy(counter = 50, clock_time = base.clock_time + 1)))
            assertEquals(50u, signer.counter)
            assertIs<SignerResult.Ok<Unit>>(signer.updateSessionInfo(base.copy(counter = 10, clock_time = base.clock_time + 2)))
            assertEquals(50u, signer.counter)
        }

    @Test
    fun ignoresOlderClockTimeInSameEpoch() =
        // FR-014 "같은 epoch에서 clock 역행이면 폐기"; signer.go "s.setTime <= info.ClockTime"
        runTest {
            val time = TestTimeSource()
            val (verifier, signer) = pair(time)
            time += 100.seconds
            val fresh = verifier.sessionInfo() // clock_time = 100
            assertIs<SignerResult.Ok<Unit>>(signer.updateSessionInfo(fresh.copy(counter = 5)))
            assertEquals(100u, signer.timestamp())
            assertIs<SignerResult.Ok<Unit>>(signer.updateSessionInfo(fresh.copy(counter = 9, clock_time = 40))) // 과거 시각 → 무시(오류는 아님)
            assertEquals(5u, signer.counter)
            assertEquals(100u, signer.timestamp())
        }

    @Test
    fun padsShortEpochToSixteenBytes() =
        // Review Focus 1; signer.go copy(signer.epoch[:], info.Epoch)
        runTest {
            val (verifier, signer) = pair()
            val base = verifier.sessionInfo()
            val shortEpoch = byteArrayOf(0x11, 0x22, 0x33)
            assertIs<SignerResult.Ok<Unit>>(
                signer.updateSessionInfo(
                    base.copy(
                        epoch = shortEpoch.toByteString(),
                        clock_time =
                            base.clock_time + 1,
                    ),
                ),
            )
            assertContentEquals(shortEpoch + ByteArray(13), signer.epoch)
        }

    @Test
    fun exportRoundTripsThroughImportWithAge() =
        // TestExportImport의 상태 부분 (암호화 왕복은 Task 6)
        runTest {
            val time = TestTimeSource()
            val (_, signer) = pair(time)
            time += 30.seconds
            val exported = signer.exportSessionInfo()
            val info = SessionInfo.ADAPTER.decode(exported)
            assertEquals(30, info.clock_time)
            assertEquals(ProtocolVectors.EPOCH, info.epoch.hex())
            time += 1800.seconds
            val imported =
                assertIs<SignerResult.Ok<Signer>>(
                    Signer.importSessionInfo(TestCrypto.clientKey(), vin, exported, age = 1800.seconds, crypto, TestCrypto.random, time),
                ).value
            assertEquals(1830u, imported.timestamp())
            assertEquals(signer.counter, imported.counter)
        }

    @Test
    fun rejectsInvalidVehiclePublicKeyAtCreation() =
        // verifier_test.go TestInvalidVerifierPublicKey (곡선 밖 점: 0x04 + 0×64, M0 Review Focus 5와 같은 입력)
        runTest {
            val (verifier, _) = pair()
            val info = verifier.sessionInfo()
            val offCurve = ByteArray(65).also { it[0] = 0x04 }
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER,
                Signer.create(
                    TestCrypto.clientKey(),
                    vin,
                    info.copy(publicKey = offCurve.toByteString()),
                    crypto,
                    TestCrypto.random,
                    TestTimeSource(),
                ),
            )
            assertFault(
                MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER,
                Signer.create(
                    TestCrypto.clientKey(),
                    vin,
                    info.copy(publicKey = ByteArray(10).toByteString()),
                    crypto,
                    TestCrypto.random,
                    TestTimeSource(),
                ),
            )
        }
}
