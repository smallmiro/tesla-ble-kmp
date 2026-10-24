// 의도적으로 :adapter-crypto 의 KeychainItems.kt / KeychainInterop.kt / CoreFoundationInterop.kt(일부)와 같은 코드다.
// 모듈 간 의존이 금지되어(ADR-0001) 공유하지 않고 복사한다. 한쪽을 고치면 다른 쪽도 같이 고친다.
// 차이 하나(ADR-0014): :adapter-crypto 판은 키 별칭마다 보호 등급을 정하고(write 인자) 다시 읽는다(read가 속성도 요청).
// 세션 캐시는 서비스 하나에 고정 보호 등급(생성자 인자)을 쓰고 읽을 필요가 없다.
package dev.mirolab.teslable.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreateWithBytesNoCopy
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorNull
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Keychain generic password 읽기 결과. */
internal sealed interface KeychainRead {
    data object Missing : KeychainRead

    class Found(
        val bytes: ByteArray,
    ) : KeychainRead

    data class Failed(
        val status: Int,
    ) : KeychainRead
}

/** 서비스 하나의 generic password 항목(설계 구체화 11). 반환 `Int`는 OSStatus. */
internal interface KeychainItems {
    fun read(account: String): KeychainRead

    /** 덮어쓴다(지우고 추가). */
    fun write(
        account: String,
        data: ByteArray,
    ): Int

    fun delete(account: String): Int
}

/** Security.framework 구현. 접근성은 [accessible](기본 `WhenUnlockedThisDeviceOnly`, 백업·이전 불가). */
@OptIn(ExperimentalForeignApi::class)
internal class AppleKeychainItems(
    private val service: String,
    private val accessible: CFStringRef? = kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
) : KeychainItems {
    /** 읽기 결과의 `CFData`는 Security.framework가 만든 사본이라 우리가 지울 수 없다. 우리 쪽 바이트 배열 사본만 호출자가 지운다. */
    override fun read(account: String): KeychainRead =
        memScoped {
            val serviceRef = service.toCFString()
            val accountRef = account.toCFString()
            releasing(serviceRef, accountRef) {
                val query =
                    cfDictionary(
                        kSecClass to kSecClassGenericPassword,
                        kSecAttrService to serviceRef,
                        kSecAttrAccount to accountRef,
                        kSecReturnData to kCFBooleanTrue,
                        kSecMatchLimit to kSecMatchLimitOne,
                    )
                releasing(query) {
                    val result = alloc<CFTypeRefVar>()
                    when (val status = SecItemCopyMatching(query, result.ptr)) {
                        errSecSuccess -> {
                            @Suppress("UNCHECKED_CAST")
                            val data = result.value as CFDataRef
                            data.use { KeychainRead.Found(it.toByteArray()) }
                        }

                        errSecItemNotFound -> {
                            KeychainRead.Missing
                        }

                        else -> {
                            KeychainRead.Failed(status)
                        }
                    }
                }
            }
        }

    override fun write(
        account: String,
        data: ByteArray,
    ): Int {
        delete(account)
        val serviceRef = service.toCFString()
        val accountRef = account.toCFString()
        return releasing(serviceRef, accountRef) {
            // 값 CFData는 복사 없이 고정된 배열을 가리킨다(지워지지 않는 사본을 만들지 않는다). 고정된 동안만 쓰이고, 배열은 호출자가 지운다.
            data.usePinned { pinned ->
                val dataRef = noCopyCFData(pinned, data.size)
                releasing(dataRef) {
                    val attributes =
                        cfDictionary(
                            kSecClass to kSecClassGenericPassword,
                            kSecAttrService to serviceRef,
                            kSecAttrAccount to accountRef,
                            kSecAttrAccessible to accessible,
                            kSecValueData to dataRef,
                        )
                    releasing(attributes) { SecItemAdd(attributes, null) }
                }
            }
        }
    }

    private fun noCopyCFData(
        pinned: Pinned<ByteArray>,
        size: Int,
    ): CFDataRef {
        val address = if (size == 0) null else pinned.addressOf(0).reinterpret<UByteVar>()
        return checkNotNull(CFDataCreateWithBytesNoCopy(null, address, size.toLong(), kCFAllocatorNull)) { "CFDataCreate failed" }
    }

    override fun delete(account: String): Int {
        val serviceRef = service.toCFString()
        val accountRef = account.toCFString()
        return releasing(serviceRef, accountRef) {
            val query = cfDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef, kSecAttrAccount to accountRef)
            releasing(query) { SecItemDelete(query) }
        }
    }
}

/** 키-값 쌍으로 CF 딕셔너리를 만든다(값은 딕셔너리가 retain). 호출자가 `CFRelease`. */
@OptIn(ExperimentalForeignApi::class)
internal fun cfDictionary(vararg entries: Pair<CFStringRef?, CFTypeRef?>): CFMutableDictionaryRef {
    val dictionary =
        checkNotNull(
            CFDictionaryCreateMutable(null, entries.size.toLong(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
        ) { "CFDictionaryCreateMutable failed" }
    entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
    return dictionary
}

/** Kotlin 문자열 -> retain된 `CFStringRef`. 호출자가 `CFRelease`. */
@OptIn(ExperimentalForeignApi::class)
internal fun String.toCFString(): CFStringRef {
    @Suppress("UNCHECKED_CAST")
    return checkNotNull(CFBridgingRetain(this)) { "CFBridgingRetain failed" } as CFStringRef
}

/** [block]이 끝나면(예외 포함) [refs]를 모두 해제한다. */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> releasing(
    vararg refs: CFTypeRef?,
    block: () -> T,
): T =
    try {
        block()
    } finally {
        refs.forEach { if (it != null) CFRelease(it) }
    }

@OptIn(ExperimentalForeignApi::class)
internal fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val pointer = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return pointer.readBytes(length)
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> CFDataRef.use(block: (CFDataRef) -> T): T =
    try {
        block(this)
    } finally {
        CFRelease(this)
    }
