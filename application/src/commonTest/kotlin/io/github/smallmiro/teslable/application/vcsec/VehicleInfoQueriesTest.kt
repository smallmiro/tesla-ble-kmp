package io.github.smallmiro.teslable.application.vcsec

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.DetailedClosureStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.KeyMetadata
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import io.github.smallmiro.teslable.application.dispatcher.dispatcherHarness
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.ClosureState
import io.github.smallmiro.teslable.model.KeyFormFactor
import io.github.smallmiro.teslable.model.KeyRole
import io.github.smallmiro.teslable.model.LockState
import io.github.smallmiro.teslable.model.SleepStatus
import io.github.smallmiro.teslable.model.UserPresence
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.VehicleStatus
import io.github.smallmiro.teslable.model.WhitelistEntry
import io.github.smallmiro.teslable.model.WhitelistSummary
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import com.tesla.generated.vcsec.KeyFormFactor as WireFormFactor
import com.tesla.generated.vcsec.VehicleStatus as WireVehicleStatus

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleInfoQueriesTest {
    private val vcsec = Domain.DOMAIN_VEHICLE_SECURITY

    private fun reply(message: FromVCSECMessage) = listOf(FakeVehicle.vcsecPayload(message))

    private fun hex(bytes: okio.ByteString?) = bytes?.hex()

    @Test
    fun informationRequestBytesMatchGo() {
        // vcsec.go:107-130 getVCSECInfo; GET_STATUS(0)는 proto3 기본값이라 빈 InformationRequest
        assertEquals(
            "0a00",
            VehicleInfoQueries.informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS).toByteString().hex(),
        )
        // protocol.md:78 list-keys TX의 payload
        assertEquals(
            "0a020805",
            VehicleInfoQueries.informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO).toByteString().hex(),
        )
        // Go는 slot 0도 oneof로 싣는다(vcsec.go:117-121)
        assertEquals(
            "0a0408062000",
            VehicleInfoQueries
                .informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO, slot = 0)
                .toByteString()
                .hex(),
        )
        assertEquals(
            "0a0408062002",
            VehicleInfoQueries
                .informationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO, slot = 2)
                .toByteString()
                .hex(),
        )
    }

    @Test
    fun vehicleStatusIsUnauthenticatedAndMapsEveryField() =
        // FR-031: 핸드셰이크 없이(개인키도 없이) 동작
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val session = VehicleSession(h.dispatcher)
            h.fake.script(
                vcsec,
                reply(
                    FromVCSECMessage(
                        vehicleStatus =
                            WireVehicleStatus(
                                closureStatuses =
                                    ClosureStatuses(
                                        frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN,
                                        tonneau = ClosureState_E.CLOSURESTATE_AJAR,
                                    ),
                                vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                                vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
                                userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
                                detailedClosureStatus = DetailedClosureStatus(tonneauPercentOpen = 40),
                            ),
                    ),
                ),
            )
            val status = assertIs<VehicleResult.Success<VehicleStatus>>(session.info.vehicleStatus()).value
            assertEquals(LockState.LOCKED, status.lockState)
            assertEquals(ClosureState.OPEN, status.closures?.frontDriverDoor)
            assertEquals(ClosureState.CLOSED, status.closures?.rearTrunk)
            assertEquals(ClosureState.AJAR, status.closures?.tonneau)
            assertEquals(SleepStatus.AWAKE, status.sleepStatus)
            assertEquals(UserPresence.PRESENT, status.userPresence)
            assertEquals(40, status.tonneauPercentOpen)
            val request = h.fake.received.last()
            assertEquals(vcsec, request.to_destination?.domain)
            assertEquals("0a00", hex(request.protobuf_message_as_bytes))
            assertNull(request.signature_data) // AuthMethod.NONE
        }

    @Test
    fun unknownLockAndClosureValuesBecomeUnknownNotTheWireDefault() =
        // Wire는 모르는 enum을 기본값(UNLOCKED/CLOSED)으로 디코딩한다 — 잠금 상태를 "열림"으로 보이면 안 된다(SDD §7.3)
        runTest {
            val h = dispatcherHarness(privateKey = null)
            // FromVCSECMessage{vehicleStatus(1){vehicleLockState(2)=9, closureStatuses(1){frontDriverDoor(1)=42}}}
            val raw = byteArrayOf(0x0a, 0x06, 0x10, 0x09, 0x0a, 0x02, 0x08, 0x2a)
            h.fake.script(vcsec, listOf(FakeVehicle.ScriptedReply(payload = raw)))
            val status = assertIs<VehicleResult.Success<VehicleStatus>>(VehicleSession(h.dispatcher).info.vehicleStatus()).value
            assertEquals(LockState.UNKNOWN, status.lockState)
            assertEquals(ClosureState.UNKNOWN, status.closures?.frontDriverDoor)
        }

    @Test
    fun replyWithoutVehicleStatusIsBadResponse() =
        // 설계 구체화 12: Go는 (nil, nil) — 우리는 값 객체를 만들 수 없으므로 실패
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(vcsec, listOf(FakeVehicle.vcsecEmpty()))
            val failure = assertIs<VehicleResult.Failure>(VehicleSession(h.dispatcher).info.vehicleStatus())
            assertEquals(VehicleError.BadResponse("missing vehicleStatus"), failure.error)
            assertFalse(failure.error.mayHaveSucceeded)
        }

    @Test
    fun keySummaryExposesOccupiedSlots() =
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(vcsec, reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 2, slotMask = 0b101))))
            val summary = assertIs<VehicleResult.Success<WhitelistSummary>>(VehicleSession(h.dispatcher).info.keySummary()).value
            assertEquals(2, summary.numberOfEntries)
            assertEquals(5u, summary.slotMask)
            assertEquals(listOf(0, 2), summary.occupiedSlots)
            assertEquals(
                "0a020805",
                hex(
                    h.fake.received
                        .last()
                        .protobuf_message_as_bytes,
                ),
            )
        }

    @Test
    fun listKeysQueriesEachOccupiedSlotInOrder() =
        // commands.go:489-517: slotMask 비트마다 KeyInfoBySlot
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val key = TestCrypto.clientPublicKey.toByteArray().toByteString()
            h.fake.script(
                vcsec,
                reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 2, slotMask = 0b101))),
                reply(
                    FromVCSECMessage(
                        whitelistEntryInfo =
                            WhitelistEntryInfo(
                                publicKey = PublicKey(PublicKeyRaw = key),
                                metadataForKey = KeyMetadata(keyFormFactor = WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE),
                                slot = 0,
                                keyRole = Role.ROLE_OWNER,
                            ),
                    ),
                ),
                reply(FromVCSECMessage(whitelistEntryInfo = WhitelistEntryInfo(slot = 2, keyRole = Role.ROLE_DRIVER))),
            )
            val entries = assertIs<VehicleResult.Success<List<WhitelistEntry>>>(VehicleSession(h.dispatcher).info.listKeys()).value
            assertEquals(listOf(0, 2), entries.map { it.slot })
            assertEquals(TestCrypto.clientPublicKey, entries[0].publicKey)
            assertEquals(KeyRole.OWNER, entries[0].role)
            assertEquals(KeyFormFactor.IOS_DEVICE, entries[0].formFactor)
            assertEquals(KeyRole.DRIVER, entries[1].role)
            assertNull(entries[1].publicKey)
            assertEquals(
                listOf("0a020805", "0a0408062000", "0a0408062002"),
                h.fake.received
                    .takeLast(3)
                    .map { hex(it.protobuf_message_as_bytes) },
            )
        }

    @Test
    fun listKeysReturnsTheFirstSlotFailure() =
        // 설계 구체화 12: Go는 실패 슬롯을 출력하고 건너뛴다 — 우리는 부분 목록 대신 실패를 돌려준다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(
                vcsec,
                reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 2, slotMask = 0b11))),
                listOf(FakeVehicle.vcsecNominalError(GenericError_E.GENERICERROR_UNKNOWN)),
            )
            val failure = assertIs<VehicleResult.Failure>(VehicleSession(h.dispatcher).info.listKeys())
            assertEquals(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_UNKNOWN), failure.error)
        }

    @Test
    fun listKeysSharesOneTimeoutAcrossAllRequests() =
        // Go list-keys: ctx 하나가 KeySummary + 모든 KeyInfoBySlot을 덮는다.
        // dropNextReplies는 대본 응답(요약)까지 버리고, sleep은 요약 응답 전에 걸 수 없다. 그래서 요약만 응답하고
        // 이후 슬롯 요청은 "응답 없음"(빈 리스트) 대본으로 소비시킨다(재전송이 대본을 더 소비하므로 넉넉히).
        runTest {
            val h = dispatcherHarness(privateKey = null)
            h.fake.script(
                vcsec,
                reply(FromVCSECMessage(whitelistInfo = WhitelistInfo(numberOfEntries = 3, slotMask = 0b111))),
                *Array(100) { emptyList<FakeVehicle.ScriptedReply>() },
            )
            val start = testTimeSource.markNow()
            val result = VehicleSession(h.dispatcher).info.listKeys(timeout = 3.seconds)
            assertIs<VehicleError.Timeout>(assertIs<VehicleResult.Uncertain>(result).error)
            assertEquals(3.seconds, start.elapsedNow())
        }

    @Test
    fun concurrentInfoQueriesAreSerialized() =
        // Review Focus 5 / FR-049: 한 연결의 VCSEC 요청은 한 번에 하나만 차량에 간다
        runTest {
            val h = dispatcherHarness(privateKey = null)
            val session = VehicleSession(h.dispatcher)
            h.fake.sleep(setOf(vcsec))
            val first = async { session.info.vehicleStatus(timeout = 2.seconds) }
            val second = async { session.info.keySummary(timeout = 5.seconds) } // 락 대기도 시간 제한에 든다 — 첫 요청이 끝난 뒤 보낼 여유
            runCurrent()
            assertEquals(1, h.fake.received.count { it.to_destination?.domain == vcsec })
            first.await()
            second.await()
            assertEquals(2, h.fake.received.count { it.to_destination?.domain == vcsec })
        }
}
