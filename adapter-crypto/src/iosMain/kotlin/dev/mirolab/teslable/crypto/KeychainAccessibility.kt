package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyLockedException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorGetCode
import platform.CoreFoundation.CFErrorGetDomain
import platform.CoreFoundation.CFErrorRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFStringCompare
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFCompareEqualTo
import platform.CoreFoundation.kCFErrorDomainOSStatus
import platform.Security.errSecInteractionNotAllowed
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly

/**
 * [KeyAccessibility] ↔ Keychain 보호 등급(`kSecAttrAccessible`, 속성 이름 `pdmn`)과 잠긴 기기 판정(FR-037, ADR-0014).
 * 이 라이브러리가 만드는 항목은 언제나 `ThisDeviceOnly`(백업·기기 이전 불가)다.
 */
@OptIn(ExperimentalForeignApi::class)
internal object KeychainAccessibility {
    /** 새 Secure Enclave 키의 접근 제어와 새 소프트웨어 키 항목에 쓰는 보호 등급. */
    fun secAttrOf(accessibility: KeyAccessibility): CFStringRef? =
        when (accessibility) {
            KeyAccessibility.WHEN_UNLOCKED -> kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            KeyAccessibility.AFTER_FIRST_UNLOCK -> kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }

    /**
     * 저장된 항목이 보고한 보호 등급 → [KeyAccessibility]. 잠긴 동안 쓸 수 있는지만 본다: AfterFirstUnlock 계열은
     * [KeyAccessibility.AFTER_FIRST_UNLOCK], WhenUnlocked·WhenPasscodeSet 계열은 [KeyAccessibility.WHEN_UNLOCKED].
     * 값이 없거나 모르는 값이면 null(알 수 없음) — 저장소가 [KeyAccessibility.WHEN_UNLOCKED]로 보고하고 WARN을 남긴다.
     */
    fun fromSecAttr(value: CFTypeRef?): KeyAccessibility? =
        when {
            value == null -> {
                null
            }

            matches(value, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, kSecAttrAccessibleAfterFirstUnlock) -> {
                KeyAccessibility.AFTER_FIRST_UNLOCK
            }

            matches(
                value,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                kSecAttrAccessibleWhenUnlocked,
                kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
            ) -> {
                KeyAccessibility.WHEN_UNLOCKED
            }

            else -> {
                null
            }
        }

    /**
     * Security.framework가 `CFError`로 알린 실패가 잠긴 기기인지: 도메인이 OSStatus이고 코드가 `errSecInteractionNotAllowed`(-25308).
     * 설명 문자열은 보지 않는다. 다른 코드(`errSecAuthFailed` 등)는 잠금으로 단정하지 않는다.
     */
    fun isDeviceLocked(error: CFErrorRef?): Boolean {
        if (error == null) return false
        val domain = CFErrorGetDomain(error) ?: return false
        return sameString(domain, kCFErrorDomainOSStatus) && CFErrorGetCode(error) == errSecInteractionNotAllowed.toLong()
    }

    private fun matches(
        value: CFTypeRef,
        vararg candidates: CFStringRef?,
    ): Boolean = candidates.any { sameString(value, it) }

    // 질의 결과의 문자열은 XPC로 받은 새 객체라 상수와 포인터가 같지 않다 — 내용을 비교한다. CFEqual은 Kotlin/Native에서 가져올 수 없다.
    // 상수는 런타임에 null이 아니지만 Kotlin 타입이 nullable이라 걸러 내고, 문자열이 아닌 값은 비교하지 않는다(CFStringCompare 크래시 방지).
    private fun sameString(
        value: CFTypeRef,
        string: CFStringRef?,
    ): Boolean {
        if (string == null || CFGetTypeID(value) != CFStringGetTypeID()) return false
        @Suppress("UNCHECKED_CAST")
        return CFStringCompare(value as CFStringRef, string, 0UL) == kCFCompareEqualTo
    }
}

/**
 * `SecKeyCopyKeyExchangeResult`가 실패했을 때 던질 예외(Secure Enclave 키와 소프트웨어 키가 같이 쓴다). out-파라미터의 CFError는
 * 여기서 한 번 해제한다([takeDescription]). `memScoped` 안에서, 실패 직후 한 번만 부른다.
 *
 * 잠긴 기기([KeychainAccessibility.isDeviceLocked]: OSStatus 도메인의 -25308)면 [KeyLockedException] — 핸드셰이크는
 * `KeyStoreError.Locked`로 끝나고 캐시 복원은 캐시를 지우지 않는다(FR-037, #96). 그 밖의 실패는 이전처럼 `IllegalArgumentException`
 * (`Signer`가 `BAD_PARAMETER`로 다룬다). 잠금 예외에는 CFError 설명을 싣지 않는다. 실기기 확인 전: 잠긴 iPhone의 Secure Enclave ECDH가
 * 실제로 이 도메인·코드로 실패를 알리는지(다른 형태면 이전처럼 핸드셰이크 시간 초과로 끝난다).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CFErrorRefVar.takeKeyExchangeFailure(): Exception {
    val locked = KeychainAccessibility.isDeviceLocked(value)
    val description = takeDescription()
    return if (locked) KeyLockedException() else IllegalArgumentException("ECDH failed: $description")
}
