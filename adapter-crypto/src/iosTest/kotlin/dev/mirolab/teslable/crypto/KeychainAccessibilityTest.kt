package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyProtection
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.CFErrorCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFErrorDomainCocoa
import platform.CoreFoundation.kCFErrorDomainOSStatus
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecNotAvailable
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccessibleWhenUnlocked
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecValueData
import platform.Security.kSecValueRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FR-037, ADR-0014. 일반 Kotlin/Native 시뮬레이터 테스트 바이너리는 Keychain을 쓸 수 없어(-25291, 17-lib-platform-notes),
 * Keychain에 넘기는 값과 Keychain이 돌려준 결과를 해석하는 부분만 여기서 검증한다. 실제 왕복은 샘플 앱(실기기)에서 확인한다.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainAccessibilityTest {
    @Test
    fun accessibilityMapsToSecAttr() {
        // 두 값 모두 ThisDeviceOnly: 백업·기기 이전으로 다른 기기에 가지 않는다. 같은 전역 상수(포인터)를 돌려준다.
        assertEquals(kSecAttrAccessibleWhenUnlockedThisDeviceOnly, KeychainAccessibility.secAttrOf(KeyAccessibility.WHEN_UNLOCKED))
        assertEquals(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, KeychainAccessibility.secAttrOf(KeyAccessibility.AFTER_FIRST_UNLOCK))
    }

    @Test
    fun storedProtectionClassReadsBackAsAccessibility() {
        assertEquals(
            KeyAccessibility.AFTER_FIRST_UNLOCK,
            KeychainAccessibility.fromSecAttr(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly),
        )
        assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, KeychainAccessibility.fromSecAttr(kSecAttrAccessibleAfterFirstUnlock))
        assertEquals(KeyAccessibility.WHEN_UNLOCKED, KeychainAccessibility.fromSecAttr(kSecAttrAccessibleWhenUnlockedThisDeviceOnly))
        assertEquals(KeyAccessibility.WHEN_UNLOCKED, KeychainAccessibility.fromSecAttr(kSecAttrAccessibleWhenUnlocked))
        assertEquals(KeyAccessibility.WHEN_UNLOCKED, KeychainAccessibility.fromSecAttr(kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly))
    }

    @Test
    fun protectionClassIsComparedByContentNotIdentity() {
        // 실제 질의 결과의 값은 XPC로 받은 새 CFString이다(상수와 포인터가 다름). pdmn 원시 값: cku, ck, aku, ak, akpu
        val expected =
            mapOf(
                "cku" to KeyAccessibility.AFTER_FIRST_UNLOCK,
                "ck" to KeyAccessibility.AFTER_FIRST_UNLOCK,
                "aku" to KeyAccessibility.WHEN_UNLOCKED,
                "ak" to KeyAccessibility.WHEN_UNLOCKED,
                "akpu" to KeyAccessibility.WHEN_UNLOCKED,
            )
        for ((raw, accessibility) in expected) {
            val fresh = raw.toCFString()
            try {
                assertEquals(accessibility, KeychainAccessibility.fromSecAttr(fresh), raw)
            } finally {
                CFRelease(fresh)
            }
        }
        // 문자열이 아닌 값은 비교하지 않고 "알 수 없음"이다
        val notAString = byteArrayOf(1).toCFData()
        try {
            assertEquals(null, KeychainAccessibility.fromSecAttr(notAString))
        } finally {
            CFRelease(notAString)
        }
    }

    @Test
    fun missingOrUnknownProtectionClassIsNotReported() {
        // 값이 없거나 모르는 값은 "알 수 없음"(null)이다. 저장소가 WHEN_UNLOCKED로 보고하고 WARN을 남긴다(AppleVehicleKeyStoreTest)
        assertEquals(null, KeychainAccessibility.fromSecAttr(null))
        val unknown = "zz".toCFString()
        try {
            assertEquals(null, KeychainAccessibility.fromSecAttr(unknown))
        } finally {
            CFRelease(unknown)
        }
    }

    @Test
    fun secureEnclaveLockedErrorIsRecognized() {
        // SecKeyCreateRandomKey 등은 잠긴 기기를 OSStatus 도메인의 CFError(-25308)로 알린다
        val locked = checkNotNull(CFErrorCreate(null, kCFErrorDomainOSStatus, errSecInteractionNotAllowed.toLong(), null))
        val other = checkNotNull(CFErrorCreate(null, kCFErrorDomainOSStatus, errSecNotAvailable.toLong(), null))
        val sameCodeOtherDomain = checkNotNull(CFErrorCreate(null, kCFErrorDomainCocoa, errSecInteractionNotAllowed.toLong(), null))
        try {
            assertTrue(KeychainAccessibility.isDeviceLocked(locked))
            assertFalse(KeychainAccessibility.isDeviceLocked(other))
            assertFalse(KeychainAccessibility.isDeviceLocked(sameCodeOtherDomain))
            assertFalse(KeychainAccessibility.isDeviceLocked(null))
        } finally {
            CFRelease(locked)
            CFRelease(other)
            CFRelease(sameCodeOtherDomain)
        }
    }

    @Test
    fun keychainQueryResultCarriesTheItemsAccessibility() {
        val bytes = byteArrayOf(4, 1, 2, 3)
        val data = bytes.toCFData()
        val withClass = cfDictionary(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val withoutClass = cfDictionary(kSecValueData to data)
        CFRelease(data) // 딕셔너리가 retain한다
        try {
            val found = keychainReadOf(withClass)
            assertContentEquals(bytes, found.bytes)
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, found.accessibility)
            assertEquals(null, keychainReadOf(withoutClass).accessibility)
        } finally {
            CFRelease(withClass)
            CFRelease(withoutClass)
        }
    }

    @Test
    fun enclaveQueryResultCarriesTheKeysAccessibilityAndOutlivesTheResult() =
        // 시뮬레이터에는 Secure Enclave가 없어 소프트웨어 SecKeyRef가 그 자리에 선다. 키 핸들은 질의 결과 딕셔너리를 해제한 뒤에도 써야 한다(retain).
        runTest {
            val pair = generateSoftwareKeyPair()
            val reference = SoftwareVehicleKey("a", pair, KeyAccessibility.WHEN_UNLOCKED)
            val ref = createSecKey(pair.toExternal(), kSecAttrKeyClassPrivate)
            val result = cfDictionary(kSecValueRef to ref, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val unreported = cfDictionary(kSecValueRef to ref)
            CFRelease(ref)
            val found =
                try {
                    enclaveFoundOf("a", result)
                } finally {
                    CFRelease(result)
                }
            val key = found.key
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, found.reportedAccessibility)
            assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, key.accessibility)
            assertEquals(KeyProtection.SECURE_ENCLAVE, key.protection)
            assertEquals(pair.publicKey, key.publicKey)
            val peer = generateSoftwareKeyPair().publicKey
            assertContentEquals(reference.sharedX(peer), key.sharedX(peer))
            // 보호 등급이 없으면 보고되지 않은 것으로 남기고, 키는 잠긴 사용을 과장하지 않는 WHEN_UNLOCKED로 보고한다
            val withoutClass =
                try {
                    enclaveFoundOf("a", unreported)
                } finally {
                    CFRelease(unreported)
                }
            assertEquals(null, withoutClass.reportedAccessibility)
            assertEquals(KeyAccessibility.WHEN_UNLOCKED, withoutClass.key.accessibility)
        }
}
