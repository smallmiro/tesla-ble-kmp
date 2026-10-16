package dev.mirolab.teslable.model

/** 키 역할(`Keys.Role`). 모르는 값은 [UNKNOWN]. */
public enum class KeyRole {
    /** 없음. */
    NONE,

    /** 서비스. */
    SERVICE,

    /** 소유자. */
    OWNER,

    /** 운전자. */
    DRIVER,

    /** 플릿 매니저. */
    FLEET_MANAGER,

    /** 차량 모니터. */
    VEHICLE_MONITOR,

    /** 충전 매니저. */
    CHARGING_MANAGER,

    /** 게스트. */
    GUEST,

    /** 알 수 없음(더 새 펌웨어). */
    UNKNOWN,
}

/** 키 형태(`VCSEC.KeyFormFactor`). */
public enum class KeyFormFactor {
    /** 알 수 없음. */
    UNKNOWN,

    /** NFC 키카드. */
    NFC_CARD,

    /** iOS 기기. */
    IOS_DEVICE,

    /** Android 기기. */
    ANDROID_DEVICE,

    /** 클라우드 키. */
    CLOUD_KEY,
}

/** 화이트리스트 요약(FR-032, `VCSEC.WhitelistInfo`). */
public data class WhitelistSummary(
    /** 등록된 키 수. */
    val numberOfEntries: Int,
    /** 슬롯 비트마스크(비트 i = 슬롯 i 사용 중). */
    val slotMask: UInt,
) {
    /** 사용 중인 슬롯 번호(오름차순). */
    val occupiedSlots: List<Int> get() = (0 until UInt.SIZE_BITS).filter { (slotMask shr it) and 1u == 1u }
}

/** 화이트리스트 항목 하나(FR-025, `VCSEC.WhitelistEntryInfo`). */
public data class WhitelistEntry(
    /** 슬롯 번호. */
    val slot: Int,
    /** 공개키(65바이트). 차량이 보내지 않거나 형식이 틀리면 null. */
    val publicKey: PublicKeyBytes?,
    /** 공개키 SHA-1(hex). 보내지 않으면 null. */
    val keyIdSha1Hex: String?,
    /** 역할. */
    val role: KeyRole,
    /** 형태. */
    val formFactor: KeyFormFactor,
)
