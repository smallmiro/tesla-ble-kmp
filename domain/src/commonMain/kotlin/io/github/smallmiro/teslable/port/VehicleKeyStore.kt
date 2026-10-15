package io.github.smallmiro.teslable.port

import io.github.smallmiro.teslable.model.VehicleResult

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

/** 키스토어에 보관된 차량 키 핸들. 개인키를 내보내는 API는 없다(NFR-004). */
public interface VehicleKey : EcdhPrivateKey {
    /** 키 별칭. */
    public val alias: String

    /** 실제 보관 수준. */
    public val protection: KeyProtection
}

/** 차량 키 보관소(FR-020, FR-021, FR-028). 실패는 값이다(설계 구체화 8). */
public interface VehicleKeyStore {
    /** [alias]의 키를 돌려주고, 없으면 [policy]대로 만든다. */
    public suspend fun getOrCreate(
        alias: String = DEFAULT_ALIAS,
        policy: KeyPolicy = KeyPolicy.PREFER_HARDWARE,
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
