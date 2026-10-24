package dev.mirolab.teslable.port

import dev.mirolab.teslable.model.VehicleResult

/** 개인키가 실제로 보관된 곳(FR-021). */
public enum class KeyProtection(
    /** 하드웨어 보안 영역인지. */
    public val isHardware: Boolean,
) {
    /** iOS Secure Enclave. */
    SECURE_ENCLAVE(true),

    /** Android StrongBox. */
    STRONGBOX(true),

    /** Android TEE(Trusted Execution Environment). */
    TEE(true),

    /** 소프트웨어(플랫폼 키스토어가 감싸 보관하거나 메모리). */
    SOFTWARE(false),
}

/** 키 생성 정책(D10). */
public enum class KeyPolicy {
    /** 하드웨어를 먼저 시도하고 안 되면 소프트웨어(기본). */
    PREFER_HARDWARE,

    /** 하드웨어만. 없으면 `KeyStoreError.HardwareUnavailable`. */
    REQUIRE_HARDWARE,

    /** 소프트웨어만(테스트·에뮬레이터). */
    SOFTWARE_ONLY,
}

/**
 * 개인키를 언제 쓸 수 있는지(FR-037, ADR-0014). 키를 만들 때 정해지고 바꿀 수 없다.
 * 선택은 iOS에서만 의미가 있다(Keychain 보호 등급, 둘 다 `ThisDeviceOnly`라 백업·기기 이전으로 다른 기기에 가지 않는다).
 * Android·JVM은 요청을 무시하고 실제 값 [AFTER_FIRST_UNLOCK]을 보고한다.
 */
public enum class KeyAccessibility {
    /**
     * 기기 잠금이 풀린 동안만(iOS `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`). 기본값. 잠긴 iPhone에서는 키를 읽을 수 없고
     * (`KeyStoreError.Locked`), 잠기기 전에 읽어 둔 Secure Enclave 키로도 핸드셰이크할 수 없다(`startSession`이 `Locked`, #96).
     */
    WHEN_UNLOCKED,

    /**
     * 재시동 뒤 한 번 잠금을 푼 다음에는 잠긴 동안에도(iOS `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
     * 잠긴 휴대폰을 가진 사람이 앱을 통해 키를 쓸 수 있다는 위험을 받아들이는 앱만 고른다(ADR-0014).
     */
    AFTER_FIRST_UNLOCK,
}

/** 키스토어에 보관된 차량 키 핸들. 개인키를 내보내는 API는 없다(NFR-004). */
public interface VehicleKey : EcdhPrivateKey {
    /** 키 별칭. */
    public val alias: String

    /** 실제 보관 수준. */
    public val protection: KeyProtection

    /**
     * 이 키를 실제로 쓸 수 있는 때(FR-037). iOS는 저장된 항목의 보호 등급을 다시 읽은 값이다(Keychain이 알려 주지 않으면
     * [KeyAccessibility.WHEN_UNLOCKED] — 잠긴 동안 쓸 수 있다고 과장하지 않는다). Android·JVM은 항상 [KeyAccessibility.AFTER_FIRST_UNLOCK].
     */
    public val accessibility: KeyAccessibility
}

/** 차량 키 보관소(FR-020, FR-021, FR-028, FR-037). 실패는 값이다(설계 구체화 8). */
public interface VehicleKeyStore {
    /**
     * [alias]의 키를 돌려주고, 없으면 [policy]대로 [KeyAccessibility.WHEN_UNLOCKED] 키를 만든다.
     * `getOrCreate(alias, policy, KeyAccessibility.WHEN_UNLOCKED)`와 같다(Swift: `getOrCreate(alias:policy:)`).
     */
    public suspend fun getOrCreate(
        alias: String = DEFAULT_ALIAS,
        policy: KeyPolicy = KeyPolicy.PREFER_HARDWARE,
    ): VehicleResult<VehicleKey> = getOrCreate(alias, policy, KeyAccessibility.WHEN_UNLOCKED)

    /**
     * [alias]의 키를 돌려주고, 없으면 [policy]와 [accessibility]로 만든다(FR-037, ADR-0014). Swift에서는 모든 인자를 넘기는 이 판을 쓴다.
     *
     * 이미 있는 키는 [accessibility]가 달라도 **그대로** 돌려준다 — 접근성은 만들 때 정해지고 기존 키를 옮기는 마이그레이션은 없다.
     * 실제 값은 [VehicleKey.accessibility]로 확인하고, 다른 값이 필요하면 새 별칭으로 만들어 차량에 다시 등록한다.
     * Android·JVM은 [accessibility]를 무시한다. 기기가 잠겨 키에 접근할 수 없으면 `Failure(KeyStoreError.Locked)`:
     * 키를 지우거나 새로 만들지 말고 잠금이 풀린 뒤 다시 부른다.
     */
    public suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
        accessibility: KeyAccessibility,
    ): VehicleResult<VehicleKey>

    /** [alias]의 키. 없으면 `Success(null)`. */
    public suspend fun get(alias: String = DEFAULT_ALIAS): VehicleResult<VehicleKey?>

    /** [alias]의 키를 지운다. 없어도 성공. */
    public suspend fun delete(alias: String = DEFAULT_ALIAS): VehicleResult<Unit>

    /** 상수와 검증. */
    public companion object {
        /** 기본 별칭(SDD §7.2). */
        public const val DEFAULT_ALIAS: String = "teslable-default"

        private val ALIAS = Regex("[A-Za-z0-9._-]{1,64}")

        /**
         * 별칭은 파일 이름과 Keychain account로 쓰이므로 `[A-Za-z0-9._-]{1,64}`이고 `.`/`..`이 아니어야 한다.
         * @throws IllegalArgumentException 규칙 위반(프로그래밍 오류).
         */
        public fun requireValidAlias(alias: String) {
            require(ALIAS.matches(alias) && alias != "." && alias != "..") { "invalid key alias" }
        }
    }
}
