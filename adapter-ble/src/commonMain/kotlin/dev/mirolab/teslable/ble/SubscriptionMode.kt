package dev.mirolab.teslable.ble

/** 0213 구독 방식(설계 구체화 4, ADR-0003 스파이크 결과). */
internal enum class SubscriptionMode {
    /** Kable 기본(`observe`): 특성이 notify를 지원하면 notify, 아니면 indication. */
    KABLE_DEFAULT,

    /** Android에서 구독 직후 CCCD에 `0x0200`(indication)을 쓴다. iOS에서는 쓰지 않는다(CoreBluetooth가 금지). */
    ANDROID_FORCE_INDICATION,
}

/**
 * 기본 구독 방식. 예상되는 두 판정 모두 [SubscriptionMode.KABLE_DEFAULT]이므로 이 값으로 둔다.
 * 이 값은 ADR-0003 스파이크(2026-10-17, iOS)로 확정되었다.
 */
internal val DEFAULT_SUBSCRIPTION: SubscriptionMode = SubscriptionMode.KABLE_DEFAULT
