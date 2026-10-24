package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.port.KeyAccessibility
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreateWithBytesNoCopy
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorNull
import platform.CoreFoundation.kCFBooleanTrue
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** Keychain generic password 읽기 결과. */
internal sealed interface KeychainRead {
    data object Missing : KeychainRead

    /** [accessibility]는 항목이 보고한 보호 등급. null이면 Keychain이 알려 주지 않았다(FR-037). */
    class Found(
        val bytes: ByteArray,
        val accessibility: KeyAccessibility?,
    ) : KeychainRead

    data class Failed(
        val status: Int,
    ) : KeychainRead
}

/** 서비스 하나의 generic password 항목(설계 구체화 11). 반환 `Int`는 OSStatus. */
internal interface KeychainItems {
    fun read(account: String): KeychainRead

    /** 덮어쓴다(지우고 추가). 보호 등급은 [accessibility]의 `ThisDeviceOnly` 값(FR-037). */
    fun write(
        account: String,
        data: ByteArray,
        accessibility: KeyAccessibility,
    ): Int

    fun delete(account: String): Int
}

/**
 * `SecItemCopyMatching`(값 + 속성)이 돌려준 딕셔너리에서 값 바이트와 보호 등급(`kSecAttrAccessible`)을 읽는다. [result]는 호출자가 해제한다.
 * 값 `CFData`는 Security.framework가 만든 사본이라 우리가 지울 수 없다. 우리 쪽 바이트 배열 사본만 호출자가 지운다.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun keychainReadOf(result: CFDictionaryRef): KeychainRead.Found {
    @Suppress("UNCHECKED_CAST")
    val data = CFDictionaryGetValue(result, kSecValueData) as CFDataRef?
    val accessibility = KeychainAccessibility.fromSecAttr(CFDictionaryGetValue(result, kSecAttrAccessible))
    return KeychainRead.Found(data?.toByteArray() ?: ByteArray(0), accessibility)
}

/**
 * Security.framework 구현. 보호 등급은 항목마다 [write]가 정하고 [read]가 다시 읽는다(키 별칭마다 다를 수 있음, ADR-0014).
 * `:adapter-storage`의 같은 이름 복사본은 서비스 하나에 고정 보호 등급을 쓴다 — 그 차이 말고는 같은 코드다.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppleKeychainItems(
    private val service: String,
) : KeychainItems {
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
                        kSecReturnAttributes to kCFBooleanTrue,
                        kSecMatchLimit to kSecMatchLimitOne,
                    )
                releasing(query) {
                    val result = alloc<CFTypeRefVar>()
                    when (val status = SecItemCopyMatching(query, result.ptr)) {
                        errSecSuccess -> {
                            // 반환 형식을 둘 이상 요청하면 결과는 딕셔너리다(kSecValueData + 속성)
                            @Suppress("UNCHECKED_CAST")
                            val dictionary = result.value as CFDictionaryRef
                            releasing(dictionary) { keychainReadOf(dictionary) }
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
        accessibility: KeyAccessibility,
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
                            kSecAttrAccessible to KeychainAccessibility.secAttrOf(accessibility),
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
