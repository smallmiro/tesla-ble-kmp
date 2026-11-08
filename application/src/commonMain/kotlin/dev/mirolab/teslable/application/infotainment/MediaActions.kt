// Ported from vehicle-command@a4b43c1 pkg/vehicle/infotainment.go (Apache-2.0) — adjustVolume, VolumeUp, VolumeDown, SetVolume,
// MediaNextTrack, MediaPreviousTrack, MediaNextFavorite, MediaPreviousFavorite, ToggleMediaPlayback
package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.MediaNextFavorite
import com.tesla.generated.carserver.server.MediaNextTrack
import com.tesla.generated.carserver.server.MediaPlayAction
import com.tesla.generated.carserver.server.MediaPreviousFavorite
import com.tesla.generated.carserver.server.MediaPreviousTrack
import com.tesla.generated.carserver.server.MediaUpdateVolume
import com.tesla.generated.carserver.server.VehicleAction
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult

/**
 * Go infotainment.go의 미디어 `VehicleAction` 빌더(FR-090, FR-091). [InfotainmentCommands]가 `Action`에 담아 보낸다.
 * 절대 볼륨은 oneof 멤버라 0도 그대로 나간다. `ToggleMediaPlayback`은 토글이므로 결과가 불확실하면 다시 보내지 않는다(호출자 규칙, FR-091).
 */
internal object MediaActions {
    /** Go `SetVolume`의 오류 문구. */
    const val INVALID_VOLUME: String = "invalid volume (should be in [0, 10])"

    /** Go `SetVolume`의 상한. */
    private const val MAX_VOLUME = 10f

    /** Go `VolumeUp`: `mediaUpdateVolume{volume_delta: +1}`(부호만 의미가 있다). */
    fun volumeUp(): VehicleAction = volumeDelta(1)

    /** Go `VolumeDown`: `mediaUpdateVolume{volume_delta: -1}`. */
    fun volumeDown(): VehicleAction = volumeDelta(-1)

    /**
     * Go `SetVolume`: 0..10 밖이면 보내기 전에 오류. Go는 NaN을 통과시키지만(두 비교가 모두 거짓) 여기서는 NaN도 거부한다(의도된 차이,
     * SDD §12). -0.0은 Go처럼 범위 안이다.
     */
    fun setVolume(volume: Float): VehicleResult<VehicleAction> {
        if (volume.isNaN() || volume < 0f || volume > MAX_VOLUME) {
            return VehicleResult.Failure(VehicleError.InvalidArgument(INVALID_VOLUME))
        }
        return VehicleResult.Success(VehicleAction(mediaUpdateVolume = MediaUpdateVolume(volume_absolute_float = volume)))
    }

    /** Go `MediaNextTrack`: `mediaNextTrack {}`. */
    fun nextTrack(): VehicleAction = VehicleAction(mediaNextTrack = MediaNextTrack())

    /** Go `MediaPreviousTrack`: `mediaPreviousTrack {}`. */
    fun previousTrack(): VehicleAction = VehicleAction(mediaPreviousTrack = MediaPreviousTrack())

    /** Go `MediaNextFavorite`: `mediaNextFavorite {}`. */
    fun nextFavorite(): VehicleAction = VehicleAction(mediaNextFavorite = MediaNextFavorite())

    /** Go `MediaPreviousFavorite`: `mediaPreviousFavorite {}`. */
    fun previousFavorite(): VehicleAction = VehicleAction(mediaPreviousFavorite = MediaPreviousFavorite())

    /** Go `ToggleMediaPlayback`: `mediaPlayAction {}`. TESLEMETRY-EXT `media_playback_status`는 쓰지 않는다. */
    fun togglePlayback(): VehicleAction = VehicleAction(mediaPlayAction = MediaPlayAction())

    /** Go `adjustVolume`: `mediaUpdateVolume{volume_delta}`(sint32). */
    private fun volumeDelta(delta: Int): VehicleAction = VehicleAction(mediaUpdateVolume = MediaUpdateVolume(volume_delta = delta))
}
