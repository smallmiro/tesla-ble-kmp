package dev.mirolab.teslable.application.pairing

import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.errors.NominalError
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.PairingEvent
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(InternalTeslableApi::class)
class PairingEventsTest {
    private fun frame(m: FromVCSECMessage) = m.encode()

    @Test
    fun waitIsWaitingForTap() =
        assertEquals(
            PairingEvent.WaitingForTap,
            PairingEvents.interpret(
                frame(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT))),
            ),
        )

    @Test
    fun whitelistNoneIsAccepted() =
        assertEquals(
            PairingEvent.Accepted,
            PairingEvents.interpret(FakeVehicle.presentKeyResult(WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE)),
        )

    @Test
    fun whitelistCodeIsRejected() {
        val code = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_WHITELIST_FULL
        assertEquals(
            PairingEvent.Rejected(VehicleError.KeychainRejected(code)),
            PairingEvents.interpret(FakeVehicle.presentKeyResult(code)),
        )
    }

    @Test
    fun nominalErrorIsRejected() =
        assertEquals(
            PairingEvent.Rejected(VehicleError.VcsecRejected(GenericError_E.GENERICERROR_UNAUTHORIZED)),
            PairingEvents.interpret(
                frame(FromVCSECMessage(nominalError = NominalError(genericError = GenericError_E.GENERICERROR_UNAUTHORIZED))),
            ),
        )

    @Test
    fun framesWithoutCommandStatusOrNominalErrorAreIgnored() {
        // Review Focus 1: 브로드캐스트 vehicleStatus, authenticationRequest(모르는 필드), 중간 OK·ERROR 응답
        assertNull(PairingEvents.interpret(frame(FromVCSECMessage(vehicleStatus = VehicleStatus()))))
        assertNull(PairingEvents.interpret("4a00".hexToBytes()))
        assertNull(
            PairingEvents.interpret(
                frame(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_OK))),
            ),
        )
        assertNull(
            PairingEvents.interpret(
                frame(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_ERROR))),
            ),
        )
    }

    @Test
    fun undecodableIsUnknownWithTheRawBytes() =
        assertEquals(PairingEvent.Unknown(byteArrayOf(0xFF.toByte())), PairingEvents.interpret(byteArrayOf(0xFF.toByte())))
}
