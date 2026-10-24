package dev.mirolab.teslable.model

import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VehicleErrorTest {
    // Go error_test.go TestRetriableError: 29개 fault 전부에 대한 기대 재시도 여부 (retriableErrors 8개만 true)
    private val retriable =
        setOf(
            MessageFault_E.MESSAGEFAULT_ERROR_BUSY,
            MessageFault_E.MESSAGEFAULT_ERROR_TIMEOUT,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
            MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
            MessageFault_E.MESSAGEFAULT_ERROR_INTERNAL,
            MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
            MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
            MessageFault_E.MESSAGEFAULT_ERROR_TIME_TO_LIVE_TOO_LONG,
        )

    // 140자 줄바꿈 규칙(ktlint_official) 때문에 반복되는 긴 열거값을 상수로 뺀다.
    private val keyfobSlotsFull = WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_KEYFOB_SLOTS_FULL

    @Test
    fun classifiesEveryMessageFaultLikeGo() {
        // 공식 Go 29개(0..28) + Teslemetry 스냅샷 29(COMMAND_REQUIRES_PHYSICAL_PROXIMITY, UNCONFIRMED). Go는 29를 모르므로 재시도 대상이 아니다.
        assertEquals(30, MessageFault_E.entries.size, "proto에 fault가 추가되면 이 표를 갱신한다")
        for (fault in MessageFault_E.entries) {
            val error = VehicleError.ProtocolFault(fault)
            assertEquals(fault in retriable, error.temporary, "temporary for $fault")
            // error.go RoutableMessageError.MayHaveSucceeded: NONE 또는 RESPONSE_MTU_EXCEEDED
            val mhs =
                fault == MessageFault_E.MESSAGEFAULT_ERROR_NONE ||
                    fault == MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED
            assertEquals(mhs, error.mayHaveSucceeded, "mayHaveSucceeded for $fault")
            assertEquals(fault in retriable && !mhs, error.shouldRetry(), "shouldRetry for $fault")
        }
    }

    @Test
    fun newErrorTypesAreNeitherTemporaryNorMaybeSucceeded() {
        val unknown = VehicleError.UnknownVcsecError(99)
        for (error in listOf(VehicleError.InsufficientPrivileges, unknown)) {
            assertFalse(error.temporary)
            assertFalse(error.mayHaveSucceeded)
        }
        assertEquals("vcsec could not execute command: unrecognized error code 99", unknown.message)
    }

    @Test
    fun lockedKeyStoreIsTemporaryAndNeverExecuted() {
        // FR-037: 잠긴 기기에서 키에 접근할 수 없음 — 잠금을 풀면 풀리는 일시적 조건이고, 차량에는 아무것도 보내지 않았다
        val locked: VehicleError = VehicleError.KeyStoreError.Locked
        assertTrue(locked.temporary)
        assertFalse(locked.mayHaveSucceeded)
        assertIs<VehicleResult.Failure>(locked.toResult())
        assertEquals("vehicle key is not accessible while the device is locked", locked.message)
    }

    @Test
    fun shouldRetryIsFalseWhenCommandMayHaveSucceeded() { // Go TestWrappedErrorClassification
        assertTrue(VehicleError.Busy.shouldRetry())
        val possiblySucceeded = VehicleError.Timeout(afterSend = true)
        assertTrue(possiblySucceeded.mayHaveSucceeded)
        assertTrue(possiblySucceeded.temporary)
        assertFalse(possiblySucceeded.shouldRetry())
        assertTrue(VehicleError.Timeout(afterSend = false).shouldRetry())
    }

    @Test
    fun mapsMayHaveSucceededToUncertain() { // ADR-0006
        assertIs<VehicleResult.Uncertain>(VehicleError.Timeout(afterSend = true).toResult())
        assertIs<VehicleResult.Failure>(VehicleError.Busy.toResult())
        assertIs<VehicleResult.Uncertain>(VehicleError.BadResponse("payload missing", mayHaveSucceeded = true).toResult())
        assertIs<VehicleResult.Failure>(VehicleError.KeychainRejected(keyfobSlotsFull).toResult())
    }

    @Test
    fun messagesAreEnglishAndCarryCodes() { // D19, NFR-013
        assertEquals("MESSAGEFAULT_ERROR_BUSY", VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_BUSY).message)
        assertEquals("vehicle rejected request: your public key has not been paired with the vehicle", VehicleError.KeyNotPaired.message)
        assertEquals("vehicle busy or finishing wake-up", VehicleError.Busy.message)
        assertTrue(VehicleError.KeychainRejected(keyfobSlotsFull).message.contains("KEYFOB_SLOTS_FULL"))
    }

    @Test
    fun unknownFaultCarriesRawCodeLikeGo() { // error.go RoutableMessageError{Code}: proto에 없는 코드
        val error = VehicleError.UnknownFault(99)
        assertEquals(99, error.rawCode)
        assertEquals("unrecognized error code 99", error.message) // error.go 229
        assertFalse(error.temporary)
        assertFalse(error.mayHaveSucceeded)
        assertFalse(error.shouldRetry())
        assertIs<VehicleResult.Failure>(error.toResult())
    }

    @Test
    fun unknownKeychainCodeCarriesRawCodeLikeGoKeychainError() {
        // error.go KeychainError.Error(): 등록되지 않은 코드도 Code를 보존한다. 설계 구체화 11.
        val error = VehicleError.UnknownKeychainCode(99)
        assertEquals("keychain operation failed: unrecognized code 99", error.message)
        assertFalse(error.temporary)
        assertFalse(error.mayHaveSucceeded)
        assertIs<VehicleResult.Failure>(error.toResult())
    }

    @Test
    fun protocolFaultAndSentinelsNeverRetryTwice() {
        assertFalse(VehicleError.ProtocolFault(MessageFault_E.MESSAGEFAULT_ERROR_RESPONSE_MTU_EXCEEDED).shouldRetry())
        assertFalse(VehicleError.KeyNotPaired.shouldRetry())
        assertFalse(VehicleError.UnknownResponse.shouldRetry())
        assertTrue(VehicleError.TransportError.WriteFailed("gatt write failed").shouldRetry())
        assertFalse(VehicleError.TransportError.MaxConnectionsExceeded.shouldRetry())
    }
}
