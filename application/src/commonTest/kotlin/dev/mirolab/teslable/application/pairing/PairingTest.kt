package dev.mirolab.teslable.application.pairing

import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistInfo
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.WhitelistSummary
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.hexToBytes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class PairingTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val key = TestCrypto.clientPublicKey
    private val none = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE

    @Test
    fun sendsThePresentKeyEnvelopeLikeGoWithoutASession() =
        // security.go:330-357. 키 없이 연결해도(Go R4는 NewVehicle(conn, nil, nil)) 보낼 수 있다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.scriptPresentKey(FakeVehicle.presentKeyResult(none))
            VehicleSession(h.dispatcher).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            val inner =
                UnsignedMessage.ADAPTER.decode(
                    h.fake.presentKeys
                        .single()
                        .signedMessage!!
                        .protobufMessageAsBytes,
                )
            val change = inner.VCSEC_WhitelistOperation!!.addKeyToWhitelistAndAddPermissions!!
            assertEquals(
                key.toByteArray().toList(),
                change.key!!
                    .PublicKeyRaw
                    .toByteArray()
                    .toList(),
            )
            assertEquals(Role.ROLE_OWNER, change.keyRole)
        }

    @Test
    fun waitingForTapThenAccepted() =
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptPresentKey(FakeVehicle.presentKeyWait(), FakeVehicle.presentKeyResult(none))
            val events =
                VehicleSession(
                    h.dispatcher,
                ).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.WaitingForTap, PairingEvent.Accepted), events)
        }

    @Test
    fun ignoresBroadcastsAndAuthenticationRequestsWhilePairing() =
        // Review Focus 1
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptPresentKey(
                "4a00".hexToBytes(),
                FromVCSECMessage(vehicleStatus = VehicleStatus()).encode(),
                FakeVehicle.presentKeyWait(),
                "4a00".hexToBytes(),
                FakeVehicle.presentKeyResult(none),
            )
            val events =
                VehicleSession(
                    h.dispatcher,
                ).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.WaitingForTap, PairingEvent.Accepted), events)
        }

    @Test
    fun undecodableFrameIsUnknownAndPairingContinues() =
        runTest {
            val h = dispatcherHarness()
            h.fake.scriptPresentKey(byteArrayOf(0xFF.toByte()), FakeVehicle.presentKeyResult(none))
            val events =
                VehicleSession(
                    h.dispatcher,
                ).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.Unknown(byteArrayOf(0xFF.toByte())), PairingEvent.Accepted), events)
        }

    @Test
    fun noAnswerTimesOutAfterTheTimeout() =
        runTest {
            val h = dispatcherHarness()
            val start = currentTime
            val events =
                VehicleSession(
                    h.dispatcher,
                ).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.TimedOut), events)
            assertEquals(60_000, currentTime - start)
        }

    @Test
    fun sendFailureIsFailedAndNotRetried() =
        runTest {
            val h = dispatcherHarness()
            h.transport.enqueueSendError(VehicleError.TransportError.WriteFailed("gatt 133"))
            val events =
                VehicleSession(
                    h.dispatcher,
                ).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertEquals(listOf<PairingEvent>(PairingEvent.Failed(VehicleError.TransportError.WriteFailed("gatt 133"))), events)
            assertEquals(1, h.transport.sent.size)
        }

    @Test
    fun unknownRoleFailsWithoutSending() =
        runTest {
            val h = dispatcherHarness()
            val events =
                VehicleSession(
                    h.dispatcher,
                ).pairing.addKeyRequest(key, KeyRole.UNKNOWN, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
            assertIs<VehicleError.InvalidArgument>(assertIs<PairingEvent.Failed>(events.single()).error)
            assertEquals(0, h.transport.sent.size)
        }

    @Test
    fun presentKeyWaitsForAnInFlightVcsecRequestButDoesNotHoldTheLock() =
        // Review Focus 4, FR-049: 보낼 때만 직렬화 락을 쓰고, 60초 대기 중에는 다른 VCSEC 조회가 지나간다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            h.fake.script(vcsec, emptyList()) // 첫 조회는 답이 없다 → 5초 뒤 Uncertain
            val query = async { session.info.keySummary(timeout = 5.seconds) }
            runCurrent()
            val pairing = async { session.pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList() }
            runCurrent()
            assertTrue(h.fake.presentKeys.isEmpty()) // 앞선 VCSEC 요청이 끝나기 전에는 보내지 않는다
            advanceTimeBy(5.seconds + 1.seconds)
            assertIs<VehicleResult.Uncertain>(query.await())
            assertEquals(1, h.fake.presentKeys.size)
            // 페어링이 기다리는 동안 다른 VCSEC 조회가 막히지 않는다
            h.fake.script(
                vcsec,
                listOf(FakeVehicle.vcsecPayload(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 1, slotMask = 1)))),
            )
            assertEquals(VehicleResult.Success(WhitelistSummary(1, 1u)), session.info.keySummary())
            h.transport.deliver(FakeVehicle.presentKeyResult(none))
            assertEquals(PairingEvent.Accepted, pairing.await().last())
        }

    @Test
    fun linkLossDuringTheWaitFailsImmediately() =
        // codex 검토: 링크가 끊기면 60초 TimedOut이 아니라 바로 Failed
        runTest {
            val h = dispatcherHarness()
            val start = currentTime
            val pairing =
                async {
                    VehicleSession(
                        h.dispatcher,
                    ).pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList()
                }
            runCurrent()
            h.transport.close() // FakeTransport: Disconnected(reason = null)
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.Failed(VehicleError.NotConnected)), pairing.await())
            assertEquals(0, currentTime - start)
        }

    @Test
    fun concurrentPairingsRunOneAfterAnother() =
        // codex 검토: 동시에 수집해도 한 응답을 두 요청이 나눠 갖지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            h.fake.scriptPresentKey(FakeVehicle.presentKeyResult(none))
            h.fake.scriptPresentKey(
                FakeVehicle.presentKeyResult(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL),
            )
            val first = async { session.pairing.addKeyRequest(key, KeyRole.OWNER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList() }
            val second = async { session.pairing.addKeyRequest(key, KeyRole.DRIVER, KeyFormFactor.IOS_DEVICE, 60.seconds).toList() }
            assertEquals(listOf(PairingEvent.Sent, PairingEvent.Accepted), first.await())
            assertEquals(
                PairingEvent.Rejected(
                    VehicleError.KeychainRejected(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL),
                ),
                second.await().last(),
            )
            assertEquals(2, h.fake.presentKeys.size)
        }
}
