package dev.mirolab.teslable.sample

import dev.mirolab.teslable.model.VehicleEvent
import java.time.Instant

/** Live 탭에 보이는 항목(SFR-014). 순서는 화면 순서다. */
enum class LiveItemKey(
    val label: String,
) {
    LOCK("잠금"),
    GEAR("기어"),
    PRESENCE("사용자 존재"),
    SLEEP("수면"),
    DRIVER_DOOR("운전석 문"),
    PASSENGER_DOOR("조수석 문"),
    REAR_DRIVER_DOOR("뒤 운전석 문"),
    REAR_PASSENGER_DOOR("뒤 조수석 문"),
    FRUNK("프렁크"),
    TRUNK("트렁크"),
    CHARGE_PORT("충전 포트"),
}

/**
 * 항목의 현재 값과 그 값이 시작된 시각. [changed]가 false 이면 처음 관측한 값이다(바뀐 것인지 모른다).
 * 같은 값이 다시 와도 [since]는 그대로다.
 */
data class LiveItem(
    val value: String,
    val since: Instant,
    val changed: Boolean,
)

data class RecentEvent(
    val at: Instant,
    val text: String,
)

data class LiveState(
    val items: Map<LiveItemKey, LiveItem> = emptyMap(),
    /** 마지막 `Status` 가 온 시각(같은 값이어도 갱신). */
    val lastStatusAt: Instant? = null,
    /** 최근 이벤트, 최신이 앞. 상태는 바뀐 것만 올라간다. */
    val recent: List<RecentEvent> = emptyList(),
)

/** `Vehicle.events` 의 이벤트를 [LiveState]로 줄인다. 차량 없이 시험한다. */
object LiveReducer {
    const val MAX_RECENT = 100

    fun reduce(
        state: LiveState,
        event: VehicleEvent,
        now: Instant,
    ): LiveState =
        when (event) {
            is VehicleEvent.Status -> reduceStatus(state, event, now)
            is VehicleEvent.AuthenticationRequest ->
                state.withRecent(now, "인증 요청 level=${event.requestedLevel} reasons=${event.reasons}")
            is VehicleEvent.Unrecognized ->
                state.withRecent(now, "알 수 없는 방송 unknownFields=${event.unknownFields}")
        }

    private fun reduceStatus(
        state: LiveState,
        event: VehicleEvent.Status,
        now: Instant,
    ): LiveState {
        val seen = state.items.isNotEmpty()
        val items = LinkedHashMap<LiveItemKey, LiveItem>()
        val changes = mutableListOf<String>()
        for ((key, value) in values(event)) {
            val old = state.items[key]
            items[key] =
                when {
                    old == null -> LiveItem(value, now, changed = false)
                    old.value == value -> old // 같은 값 반복은 변화가 아니다
                    else -> {
                        changes += "${key.label} ${old.value} → $value"
                        LiveItem(value, now, changed = true)
                    }
                }
        }
        val next = state.copy(items = items, lastStatusAt = now)
        return if (seen && changes.isNotEmpty()) next.withRecent(now, changes.joinToString(", ")) else next
    }

    private fun values(event: VehicleEvent.Status): List<Pair<LiveItemKey, String>> {
        val s = event.status
        val c = s.closures
        return listOf(
            LiveItemKey.LOCK to s.lockState.toString(),
            LiveItemKey.GEAR to s.gear.toString(),
            LiveItemKey.PRESENCE to s.userPresence.toString(),
            LiveItemKey.SLEEP to s.sleepStatus.toString(),
            LiveItemKey.DRIVER_DOOR to c.frontDriverDoor.toString(),
            LiveItemKey.PASSENGER_DOOR to c.frontPassengerDoor.toString(),
            LiveItemKey.REAR_DRIVER_DOOR to c.rearDriverDoor.toString(),
            LiveItemKey.REAR_PASSENGER_DOOR to c.rearPassengerDoor.toString(),
            LiveItemKey.FRUNK to c.frontTrunk.toString(),
            LiveItemKey.TRUNK to c.rearTrunk.toString(),
            LiveItemKey.CHARGE_PORT to c.chargePort.toString(),
        )
    }

    private fun LiveState.withRecent(
        at: Instant,
        text: String,
    ): LiveState = copy(recent = (listOf(RecentEvent(at, text)) + recent).take(MAX_RECENT))
}
