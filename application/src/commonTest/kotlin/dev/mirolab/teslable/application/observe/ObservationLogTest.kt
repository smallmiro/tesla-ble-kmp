package dev.mirolab.teslable.application.observe

import com.tesla.generated.keys.Role
import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistEntryInfo
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.hexToBytes
import dev.mirolab.teslable.util.toHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalTeslableApi::class)
class ObservationLogTest {
    private val log = ObservationLog(TestCrypto.primitives)
    private val vcsecFrom = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY)

    @Test
    fun addresslessVehicleStatusShowsStateAndWhichMessageFieldsAreAbsent() {
        // PR #178: 잠긴 채 잠든 차량의 브로드캐스트에는 closureStatuses가 없었다
        val status =
            FromVCSECMessage(
                vehicleStatus =
                    VehicleStatus(
                        vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                        vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
                        userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
                    ),
            )
        val message = RoutableMessage(from_destination = vcsecFrom, protobuf_message_as_bytes = status.encode().toByteString())
        val line = log.routable(ObservedAt.MISSING_DESTINATION, message, size = 20, owner = null)
        assertEquals(
            "[drop:missing-destination] 20B from=domain(DOMAIN_VEHICLE_SECURITY) to=none request_uuid=empty " +
                "payload=FromVCSECMessage{vehicleStatus{lock=VEHICLELOCKSTATE_LOCKED, sleep=VEHICLE_SLEEP_STATUS_ASLEEP, " +
                "presence=VEHICLE_USER_PRESENCE_NOT_PRESENT, absent=[closureStatuses, detailedClosureStatus]}}",
            line,
        )
    }

    @Test
    fun closuresAreListedByShortName() {
        val status =
            FromVCSECMessage(
                vehicleStatus = VehicleStatus(closureStatuses = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN)),
            )
        val text = log.vcsec(status)
        assertTrue(text.contains("closures={frontDriverDoor=OPEN, frontPassengerDoor=CLOSED"), text)
        assertTrue(text.contains("tonneau=CLOSED}"), text)
        assertTrue(text.contains("absent=[detailedClosureStatus]"), text)
    }

    @Test
    fun encryptedPayloadIsLengthOnly() {
        val ciphertext = ByteArray(32) { 0xAB.toByte() }
        val message =
            RoutableMessage(
                from_destination = vcsecFrom,
                to_destination = Destination(routing_address = ByteArray(16).toByteString()),
                protobuf_message_as_bytes = ciphertext.toByteString(),
                signature_data =
                    SignatureData(
                        AES_GCM_Response_data =
                            AES_GCM_Response_Signature_Data(
                                nonce = ByteArray(12).toByteString(),
                                counter = 1,
                                tag = ByteArray(16).toByteString(),
                            ),
                    ),
            )
        val line = log.routable(ObservedAt.VCSEC_REPLY, message, size = 80, owner = AddressOwner.OPEN_REQUEST)
        assertTrue(line.contains("to=address(16B, open-request)"), line)
        assertTrue(line.endsWith("payload=encrypted(32B)"), line)
        assertFalse(line.contains("abab"), line)
    }

    @Test
    fun publicKeysAreSha1PrefixOnly() {
        val key = TestCrypto.clientPublicKey.toByteArray()
        val entry =
            FromVCSECMessage(
                whitelistEntryInfo =
                    WhitelistEntryInfo(publicKey = PublicKey(PublicKeyRaw = key.toByteString()), slot = 3, keyRole = Role.ROLE_OWNER),
            )
        val text = log.vcsec(entry)
        val prefix =
            TestCrypto.primitives
                .sha1(key)
                .copyOf(4)
                .toHex()
        assertEquals("FromVCSECMessage{whitelistEntryInfo{slot=3, role=ROLE_OWNER, key=sha1:$prefix}}", text)
        assertFalse(text.contains(key.toHex()))
    }

    @Test
    fun unwrappedFrameIsReinterpretedAndUnknownFieldsAreListedByNumber() {
        // PR #179: 공개 proto에 없는 authenticationRequest — 여기서는 FromVCSECMessage의 reserved 9번으로 흉내 낸다
        val frame =
            FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT)).encode() +
                "4a00".hexToBytes()
        assertEquals("FromVCSECMessage{commandStatus{op=OPERATIONSTATUS_WAIT}, unknownFields=[9]}", log.reinterpret(frame))
    }

    @Test
    fun undecodableBytesAreLengthOnly() {
        assertEquals("[drop:unparseable] 1B reinterpreted=undecodable", log.unparseable(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun sessionInfoIsLengthOnly() {
        val message = RoutableMessage(from_destination = vcsecFrom, session_info = ByteArray(40) { 7 }.toByteString())
        val line = log.routable(ObservedAt.NO_HANDLER, message, size = 60, owner = AddressOwner.OTHER)
        assertTrue(line.contains("session_info=40B"), line)
        assertFalse(line.contains("0707"), line)
    }

    @Test
    fun emptyUnknownFieldsListNothing() {
        assertEquals(emptyList(), unknownFieldNumbers(okio.ByteString.EMPTY))
    }
}
