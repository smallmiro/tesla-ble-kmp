package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 미디어 명령(FR-090, FR-091)이 보내는 `CarServer.Action` 평문과 볼륨 검증. 기대값은 Go `pkg/vehicle/infotainment.go`의 빌더를 손으로
 * 옮긴 독립 hex다. 태그: 15 → `7a`, 16 → `82 01`, 17 → `8a 01`, 18 → `92 01`, 19 → `9a 01`, 20 → `a2 01`. `volume_delta`는 sint32라
 * zigzag(+1 → 2, -1 → 1)이고, `volume_absolute_float`는 fixed32 리틀엔디언(5.5 = `0000b040`, 10 = `00002041`)이다.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class MediaCommandsTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

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

    @Test
    fun volumeStepsSendTheSignedDelta() =
        // infotainment.go adjustVolume: mediaUpdateVolume(16){volume_delta(1) sint32}. VolumeUp은 +1, VolumeDown은 -1(부호만 의미가 있다)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1205820102" + "0802" to { c.volumeUp() }, // 12 05 | 82 01 02 | 08 02 (zigzag +1)
                    "1205820102" + "0801" to { c.volumeDown() }, // 08 01 (zigzag -1)
                ),
            )
        }

    @Test
    fun setVolumeSendsTheAbsoluteFloatEvenAtZero() =
        // infotainment.go SetVolume: mediaUpdateVolume{volume_absolute_float(3) float}. oneof 멤버라 0도 `1d 00000000`으로 나간다.
        // -0.0은 Go처럼 범위 안이고(-0.0 < 0은 거짓) 부호 비트가 그대로 나간다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "1208820105" + "1d0000b040" to { c.setVolume(5.5f) }, // 12 08 | 82 01 05 | 1d 0000b040
                    "1208820105" + "1d00000000" to { c.setVolume(0.0f) },
                    "1208820105" + "1d00002041" to { c.setVolume(10.0f) },
                    "1208820105" + "1d00000080" to { c.setVolume(-0.0f) },
                ),
            )
        }

    @Test
    fun setVolumeOutsideZeroToTenOrNaNIsRejectedWithoutSending() =
        // infotainment.go:98-100: volume < 0 || volume > 10이면 보내기 전에 오류. NaN은 Go에서는 두 비교가 모두 거짓이라 그대로 나가지만
        // 이 라이브러리는 거부한다(의도된 차이, SDD §12)
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            val framesBefore = h.transport.sent.size
            val invalid = VehicleResult.Failure(VehicleError.InvalidArgument("invalid volume (should be in [0, 10])"))

            for (volume in listOf(-0.1f, 10.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertEquals(invalid, c.setVolume(volume), "$volume")
            }

            assertEquals(framesBefore, h.transport.sent.size)
            assertTrue(h.fake.authenticatedPayloads(infotainment).isEmpty())
        }

    @Test
    fun trackFavoriteAndPlaybackCommandsSendTheirEmptyMember() =
        // infotainment.go MediaNextTrack(19), MediaPreviousTrack(20), MediaNextFavorite(17), MediaPreviousFavorite(18),
        // ToggleMediaPlayback: mediaPlayAction(15) {} — TESLEMETRY-EXT media_playback_status는 보내지 않는다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.assertSends(
                listOf(
                    "12039a0100" to { c.mediaNextTrack() }, // 12 03 | 9a 01 00
                    "1203a20100" to { c.mediaPreviousTrack() }, // a2 01 00
                    "12038a0100" to { c.mediaNextFavorite() }, // 8a 01 00
                    "1203920100" to { c.mediaPreviousFavorite() }, // 92 01 00
                    "12027a00" to { c.toggleMediaPlayback() }, // 12 02 | 7a 00
                ),
            )
        }

    @Test
    fun toggleMediaPlaybackIsNotResentAfterALostReply() =
        // FR-091, NFR-007: 토글은 두 번 실행되면 원래 상태로 돌아간다. 응답이 없으면 Uncertain(Timeout(afterSend = true))이고, 서명한 사본은
        // 하나뿐이며 시간이 더 지나도 다시 보내지 않는다. 앱은 getState(MEDIA)로 확인한다
        runTest {
            val h = dispatcherHarness()
            val c = connected(h)
            h.fake.dropNextReplies(1)

            assertEquals(VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)), c.toggleMediaPlayback(timeout = 1.seconds))
            advanceTimeBy(30.seconds)

            assertEquals(listOf("12027a00"), h.fake.authenticatedPayloads(infotainment).map { it.toHex() })
            assertEquals(1, h.fake.received.count { it.signature_data?.AES_GCM_Personalized_data != null })
        }
}
