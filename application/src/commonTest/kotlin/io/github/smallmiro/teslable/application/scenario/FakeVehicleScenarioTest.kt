package io.github.smallmiro.teslable.application.scenario

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage
import io.github.smallmiro.teslable.application.dispatcher.DispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.PendingRequest
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.dispatcher.encode
import io.github.smallmiro.teslable.application.dispatcher.manualHandshake
import io.github.smallmiro.teslable.application.dispatcher.replyTo
import io.github.smallmiro.teslable.application.dispatcher.testCommand
import io.github.smallmiro.teslable.application.vcsec.VcsecResponses
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.shouldRetry
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.testing.FakeTransport
import io.github.smallmiro.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * NFR-003의 FakeVehicle 종단 시나리오 7종(`{{WORKFLOW_FILE}}` §2.4). 앞 Task들이 구현을 이미 마쳤으므로 여기서는 Red가
 * 없다 — 실패하면 해당 시나리오가 가리키는 Task의 결함이다. Go 참조: `verifier_test.go`(`TestEpochChange`,
 * `TestGCMExpired`), `dispatcher_test.go`(`TestVehicleDropsReply`, `TestCorruptedSessionInfo`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleScenarioTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val lock = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_LOCK).encode()

    private suspend fun DispatcherHarness.connectedSession(): VehicleSession {
        val session = VehicleSession(dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(vcsec), timeout = 1.seconds))
        return session
    }

    private fun DispatcherHarness.authenticatedRequests() = fake.received.filter { it.signature_data?.AES_GCM_Personalized_data != null }

    @Test
    fun scenario1VcsecWaitThenFinalSucceedsWithReauthorizedRetry() =
        // 시나리오 1: VCSEC 다중 응답 WAIT → 최종. WAIT는 Busy로 재시도(새 요청·새 counter), 최종은 빈 메시지.
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecBusy()), listOf(FakeVehicle.vcsecAuthSuccess(), FakeVehicle.vcsecEmpty()))
            val result = session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT)
            // I1: 성공했다는 것만이 아니라 최종으로 실려온 값(대본의 마지막 응답 vcsecEmpty() = 빈 FromVCSECMessage)까지 확인한다 —
            // 그러지 않으면 COMMAND_STATUS_ABSENT가 (버그로) 중간 응답에서 이미 끝내도 이 테스트는 여전히 통과한다.
            assertEquals(FromVCSECMessage(), assertIs<VehicleResult.Success<FromVCSECMessage>>(result).value)
            val requests = h.authenticatedRequests()
            assertEquals(2, requests.size)
            val c1 = assertNotNull(requests[0].signature_data?.AES_GCM_Personalized_data).counter
            val c2 = assertNotNull(requests[1].signature_data?.AES_GCM_Personalized_data).counter
            assertEquals(c1 + 1, c2)
            assertFalse(requests[0].from_destination?.routing_address == requests[1].from_destination?.routing_address) // VCSEC: 요청마다 새 주소
        }

    @Test
    fun scenario2LostResponseIsUncertainAndNeverResent() =
        // 시나리오 2: 응답 유실 → Uncertain(Timeout(afterSend = true)), 자동 재전송 없음(NFR-007), 등록 해제
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            h.fake.dropNextReplies(1)
            val result =
                assertIs<VehicleResult.Uncertain>(
                    session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT, timeout = 100.milliseconds),
                )
            assertEquals(VehicleError.Timeout(afterSend = true), result.error)
            assertEquals(1, h.authenticatedRequests().size)
            assertEquals(0, h.dispatcher.pendingCount())
            // 앱은 재조회로 확인한다(FR-102). 다음 명령은 정상.
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
        }

    @Test
    fun scenario3EpochChangeRecoversViaAttachedSessionInfo() =
        // 시나리오 3: 차량 재부팅(epoch 변경) → INCORRECT_EPOCH + 세션정보 동봉 → 갱신 → 재시도 성공(FR-018)
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
            val oldEpoch = h.fake.epoch(vcsec)
            h.fake.rotateEpoch(vcsec)
            val before = h.authenticatedRequests().size
            // 컨트롤러 판정 R3: 초기 핸드셰이크가 이미 이 로그를 남기므로 contains()는 항상 true다 — 회전 후
            // 실행 직전의 카운트를 잡아 두고, 정확히 1번 더 늘었는지로 갱신 로그가 이번 재시도에서 나왔는지 확인한다.
            val updatedBefore = h.logger.count("Updated session info for DOMAIN_VEHICLE_SECURITY")
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
            val retried = h.authenticatedRequests().drop(before)
            assertEquals(2, retried.size)
            assertContentEquals(oldEpoch, assertNotNull(retried[0].signature_data?.AES_GCM_Personalized_data).epoch.toByteArray())
            assertContentEquals(
                h.fake.epoch(vcsec),
                assertNotNull(retried[1].signature_data?.AES_GCM_Personalized_data).epoch.toByteArray(),
            )
            assertEquals(updatedBefore + 1, h.logger.count("Updated session info for DOMAIN_VEHICLE_SECURITY"))
        }

    @Test
    fun scenario4ClockRegressionInSameEpochIsIgnored() =
        // 시나리오 4: 같은 epoch에서 차량 시계가 뒤로 간 세션정보(선제 동봉)는 폐기(FR-014 3항) — 시계 추정이 그대로이고 다음 명령도 성공
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            advanceTimeBy(30.seconds)
            h.manualHandshake(vcsec) // clock_time 30인 세션정보를 반영해 setTime = 30 (FR-014 3항의 기준점)
            val state = assertNotNull(h.dispatcher.session(vcsec))
            assertEquals(30u, state.timestamp())
            h.fake.shiftClock(vcsec, (-10).seconds) // 차량: 20초
            h.fake.attachSessionInfoOnce(vcsec)
            val pending =
                assertIs<VehicleResult.Success<PendingRequest>>(
                    h.dispatcher.send(testCommand(vcsec, flags = 2), AuthMethod.GCM),
                ).value
            pending.use { assertNotNull(it.receive().session_info) }
            assertEquals(30u, state.timestamp()) // 20으로 되돌아가지 않았다
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
        }

    @Test
    fun scenario5BadHmacOnSessionInfoIsDiscarded() =
        // 시나리오 5: 잘못된 HMAC → 폐기 후 재전송으로 회복; 계속 잘못되면 시간 초과, 세션 없음
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            h.fake.corruptNextSessionInfoTag(infotainment)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            assertEquals(2, h.fake.sessionInfoRequests)
            assertEquals(1, h.logger.count("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
            val h2 = dispatcherHarness()
            h2.fake.corruptNextSessionInfoTag(vcsec, count = 100)
            val result =
                assertIs<VehicleResult.Failure>(VehicleSession(h2.dispatcher).startSession(setOf(vcsec), timeout = 20.milliseconds))
            assertEquals(VehicleError.Timeout(afterSend = false), result.error)
            assertFalse(assertNotNull(h2.dispatcher.session(vcsec)).isReady)
            assertEquals(
                VehicleError.NoSession,
                assertIs<VehicleResult.Failure>(h2.dispatcher.send(testCommand(vcsec), AuthMethod.GCM)).error,
            )

            // Fold: 이미 자리 잡은 세션에서도 같은 폐기가 일어난다 — epoch 회전 뒤 INCORRECT_EPOCH 응답에 다시 동봉되는
            // 세션정보의 태그가 손상돼 있으면 established-session 경로(Signer.updateSignedSessionInfo)가 거부하고
            // 세션은 이전 epoch를 그대로 유지한다(FakeVehicle.kt attachSessionInfo → maybeCorruptTag 경로).
            val h3 = dispatcherHarness()
            h3.connectedSession()
            val oldEpoch = h3.fake.epoch(vcsec)
            h3.fake.rotateEpoch(vcsec)
            h3.fake.corruptNextSessionInfoTag(vcsec)
            val rejected =
                assertIs<VehicleResult.Success<PendingRequest>>(h3.dispatcher.send(testCommand(vcsec, flags = 2), AuthMethod.GCM)).value
            rejected.use { assertNotNull(it.receive()) }
            assertEquals(1, h3.logger.count("Session info error: MESSAGEFAULT_ERROR_INVALID_SIGNATURE"))
            assertIs<VehicleResult.Success<PendingRequest>>(h3.dispatcher.send(testCommand(vcsec, flags = 2), AuthMethod.GCM)).value.use {}
            val stillOldEpochRequest = h3.authenticatedRequests().last()
            assertContentEquals(
                oldEpoch,
                assertNotNull(stillOldEpochRequest.signature_data?.AES_GCM_Personalized_data).epoch.toByteArray(),
            )
        }

    @Test
    fun scenario6ReplayedResponseIsDroppedWhileRequestIsStillOpen() =
        // 시나리오 6: 같은 counter의 응답 재전송은 요청이 열려 있어도 드롭되고, 그 뒤의 정상 응답은 전달된다
        runTest {
            val h = dispatcherHarness()
            val session = h.connectedSession()
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecAuthSuccess())) // 중간 응답만, 최종은 아래서 직접
            // Fold: 드롭을 로그 문자열 하나로만이 아니라 readUntil이 실제로 본 메시지 수로도 증명한다 — 재전송된 응답이
            // 여기 세어지면(3) 드롭되지 않고 readUntil까지 새어 들어왔다는 뜻이다.
            val seen = mutableListOf<FromVCSECMessage>()
            val countingDone =
                VcsecResponses.TerminalTest { message ->
                    seen += message
                    VcsecResponses.COMMAND_STATUS_ABSENT.check(message)
                }
            val command = async { session.vcsec.execute(lock, AuthMethod.GCM, countingDone, timeout = 1.seconds) }
            runCurrent()
            assertFalse(command.isCompleted)
            h.fake.replayLastResponse(vcsec)
            runCurrent()
            assertEquals(1, h.logger.count("Dropping duplicate vehicle response"))
            assertFalse(command.isCompleted)
            val request = h.authenticatedRequests().last()
            val final =
                h.fake.verifier(vcsec).encryptResponse(
                    replyTo(request, ByteArray(0)).copy(protobuf_message_as_bytes = null),
                    assertNotNull(RequestHash.of(request)),
                    counter = 2u,
                )
            h.transport.deliver(encode(final))
            assertIs<VehicleResult.Success<*>>(command.await())
            assertEquals(2, seen.size) // 중간 응답 1 + 최종 응답 1 — 재전송된 사본은 세어지지 않는다
        }

    @Test
    fun scenario7NotConnectableVehicleRefusesConnectionWithoutRetry() =
        // 시나리오 7: 슬롯 초과(connectable=false) — M2 수준: connect() 자체의 즉시 실패(a), 그 무재시도 분류를 실제
        // Dispatcher.transmit()으로(b), 슬롯이 열리면 connect()가 돌려준 그 전송 위에서 세션이 동작함을(c) 확인한다.
        // BLE 스캔·실제 연결 수립은 M3(TransportFactory) — Task 12가 이 사실을 기록한다.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource)
            fake.setConnectable(false)
            val refused = assertIs<VehicleResult.Failure>(fake.connect())
            assertEquals(VehicleError.TransportError.MaxConnectionsExceeded, refused.error)
            assertFalse(refused.error.shouldRetry())

            // (b) 컨트롤러 판정 I2-a: 무재시도 분류를 프로덕션 Dispatcher.transmit()으로 확인한다 — 전송 계층에 실제
            // MaxConnectionsExceeded를 주입하면 재시도 없이 그대로 실패한다(단일 도메인 → 전송 1회; 두 도메인이면 병렬
            // 핸드셰이크가 2회 보낸다).
            val h = dispatcherHarness()
            h.transport.enqueueSendError(VehicleError.TransportError.MaxConnectionsExceeded)
            val handshake =
                assertIs<VehicleResult.Failure>(VehicleSession(h.dispatcher).startSession(setOf(vcsec), timeout = 1.seconds))
            assertEquals(VehicleError.TransportError.MaxConnectionsExceeded, handshake.error)
            assertEquals(1, h.transport.sent.size)

            // (c) 컨트롤러 판정 I2-b: 슬롯이 열리면 fake.connect()가 돌려준 실제 전송 위에서 세션을 만들어야 한다
            // (connect()의 결과를 버리고 별도 전송을 새로 만들면 "연결로 얻은 전송이 실제로 쓰인다"를 증명하지 못한다).
            fake.setConnectable(true)
            val recovered = assertIs<VehicleResult.Success<FakeTransport>>(fake.connect()).value
            val h2 = dispatcherHarness(fake = fake, transport = recovered)
            val session = VehicleSession(h2.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            assertIs<VehicleResult.Success<*>>(session.vcsec.execute(lock, AuthMethod.GCM, VcsecResponses.COMMAND_STATUS_ABSENT))
        }
}
