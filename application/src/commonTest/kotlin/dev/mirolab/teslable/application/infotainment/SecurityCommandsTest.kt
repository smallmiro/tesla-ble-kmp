package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 보안 명령(FR-084, FR-085)이 보내는 `CarServer.Action` 평문과 발레 PIN 검증, `disableValetMode`의 "already off" 규칙. 기대값은 Go
 * `pkg/vehicle/security.go`의 빌더를 손으로 옮긴 독립 hex다. 태그: 28 → `e2 01`, 30 → `f2 01`, 31 → `fa 01`.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class SecurityCommandsTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT
    private val invalidPin = VehicleResult.Failure(VehicleError.InvalidArgument("PIN codes must be four digits"))

    private suspend fun connected(h: DispatcherHarness): InfotainmentCommands {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
        return session.infotainment
    }

    /** [calls]를 차례로 보낸다. 모두 `Success(Unit)`이고, 차량이 검증한 평문(받은 순서)이 각 hex와 같아야 한다. */
    private suspend fun DispatcherHarness.assertSends(calls: List<Pair<String, suspend () -> VehicleResult<Unit>>>) {
        for ((hex, call) in calls) assertEquals(VehicleResult.Success(Unit), call(), hex)
        assertEquals(calls.map { it.first }, fake.authenticatedPayloads(infotainment).map { it.toHex() })
    }

    /** 로그 어디에도 [pin]이 따로 떨어진 낱말로도, 평문 바이트의 hex로도 나오지 않는다(우연히 hex 안에 같은 숫자가 끼는 경우는 낱말이 아니다). */
    private fun DispatcherHarness.assertNotLogged(pin: String) {
        val word = Regex("(?<![0-9A-Za-z])${Regex.escape(pin)}(?![0-9A-Za-z])")
        val asciiHex = pin.encodeToByteArray().toHex()
        val leaks = logger.entries.filter { word.containsMatchIn(it.message) || it.message.contains(asciiHex) }
        assertTrue(leaks.isEmpty(), "PIN in logs: ${leaks.map { it.tag }}")
    }

    @Test
    fun sentryModeSendsItsFlag() =
        // security.go SetSentryMode: vehicleControlSetSentryModeAction(30){on(1)}. false는 생략되어 빈 멤버가 된다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1205f201020801" to { c.setSentryMode(true) }, // 12 05 | f2 01 02 | 08 01
                    "1203f20100" to { c.setSentryMode(false) }, // 12 03 | f2 01 00
                ),
            )
        }

    @Test
    fun valetModeCommandsSendTheGoPayloads() =
        // security.go EnableValetMode → SetValetMode(true, pin): vehicleControlSetValetModeAction(31){on(1), password(2)};
        // DisableValetMode → SetValetMode(false, ""): 빈 멤버; ResetValetPin: vehicleControlResetValetPinAction(28) {}
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    // 12 0b | fa 01 08 | 08 01 | 12 04 "1234"
                    "120bfa0108" + "0801" + "120431323334" to { c.enableValetMode("1234") },
                    "120bfa0108" + "0801" + "120430303030" to { c.enableValetMode("0000") }, // "0000"도 네 자리 숫자다
                    "1203fa0100" to { c.disableValetMode() }, // 12 03 | fa 01 00
                    "1203e20100" to { c.resetValetPin() }, // 12 03 | e2 01 00
                ),
            )
            h.assertNotLogged("1234")
        }

    @Test
    fun enableValetModeRejectsAnythingButFourAsciiDigitsWithoutSending() =
        // security.go IsValidPIN: len(pin) == 4(바이트)이고 모든 문자가 '0'..'9' — ASCII 숫자 네 개만 통과한다. Kotlin Char.isDigit()은
        // 다른 문자 체계의 숫자도 참이라 쓰지 않는다. 오류는 Go ErrInvalidPIN 문구 그대로이고 PIN을 담지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val framesBefore = h.transport.sent.size
            val rejected = listOf("", "123", "12345", "12a4", " 123", "123 ", "12.4", "-123", "١٢٣٤", "１２３４", "12３4")

            for (pin in rejected) {
                val result = c.enableValetMode(pin)
                assertEquals(invalidPin, result, "pin of length ${pin.length}")
                if (pin.isNotEmpty()) assertFalse(invalidPin.error.message.contains(pin))
            }

            assertEquals(framesBefore, h.transport.sent.size)
            assertTrue(h.fake.authenticatedPayloads(infotainment).isEmpty())
            for (pin in rejected.filter { it.length == 4 }) h.assertNotLogged(pin)
        }

    @Test
    fun goTestValidPinCasesKeepTheirVerdicts() =
        // security_test.go TestValidPIN의 표 그대로: 유효 "0000", "0123", "4569"는 보내고, 무효 "", "123a", "12345", "1", "four"는
        // 보내기 전에 거부한다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val goInvalid = listOf("", "123a", "12345", "1", "four")

            for (pin in goInvalid) assertEquals(invalidPin, c.enableValetMode(pin), "pin of length ${pin.length}")
            assertTrue(h.fake.authenticatedPayloads(infotainment).isEmpty())

            h.assertSends(
                listOf(
                    "120bfa0108" + "0801" + "120430303030" to { c.enableValetMode("0000") }, // password "0000"
                    "120bfa0108" + "0801" + "120430313233" to { c.enableValetMode("0123") }, // "0123"
                    "120bfa0108" + "0801" + "120434353639" to { c.enableValetMode("4569") }, // "4569"
                ),
            )
        }

    @Test
    fun disableValetModeTreatsAReasonEndingInAlreadyOffAsSuccess() =
        // security.go:63-65: 끄기에서 오류 문자열이 "already off"로 끝나면 nil. 이 라이브러리는 차량 거부(InfotainmentRejected)의 사유에만
        // 적용한다(M5 계획 결정 기록) — 이미 꺼져 있다는 차량의 답은 원하는 상태라는 뜻이다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.infotainmentError("valet mode already off")),
                listOf(FakeVehicle.infotainmentError("already off")),
            )

            assertEquals(VehicleResult.Success(Unit), c.disableValetMode())
            assertEquals(VehicleResult.Success(Unit), c.disableValetMode())
            assertEquals(2, h.fake.authenticatedPayloads(infotainment).size) // 다시 보내지 않았다
        }

    @Test
    fun alreadyOffRuleIsCaseSensitiveExactSuffixAndOnlyForDisable() =
        // Go strings.HasSuffix는 대소문자를 구분한다. 끝에 다른 글자가 붙으면 접미사가 아니다. enable·reset에는 규칙이 없다(Go도 !on에서만)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.infotainmentError("valet mode Already Off")),
                listOf(FakeVehicle.infotainmentError("valet mode already off.")),
                listOf(FakeVehicle.infotainmentError("valet mode already off")),
                listOf(FakeVehicle.infotainmentError("valet mode already off")),
            )

            assertEquals(VehicleResult.Failure(VehicleError.InfotainmentRejected("valet mode Already Off")), c.disableValetMode())
            assertEquals(VehicleResult.Failure(VehicleError.InfotainmentRejected("valet mode already off.")), c.disableValetMode())
            assertEquals(VehicleResult.Failure(VehicleError.InfotainmentRejected("valet mode already off")), c.enableValetMode("1234"))
            assertEquals(VehicleResult.Failure(VehicleError.InfotainmentRejected("valet mode already off")), c.resetValetPin())
        }

    @Test
    fun disableValetModeLeavesOtherResultsAlone() =
        // 규칙은 차량 거부의 사유만 본다: 다른 거부는 Failure, 응답 유실은 Uncertain 그대로다(Go의 시간 초과 오류도 "already off"로 끝나지 않는다)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.script(infotainment, listOf(FakeVehicle.infotainmentError("car is in drive")))

            assertEquals(VehicleResult.Failure(VehicleError.InfotainmentRejected("car is in drive")), c.disableValetMode())

            h.fake.dropNextReplies(1)
            assertEquals(VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)), c.disableValetMode(timeout = 1.seconds))
        }
}
