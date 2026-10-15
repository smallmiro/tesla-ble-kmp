package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
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
        val dataRef = data.toCFData()
        return releasing(serviceRef, accountRef, dataRef) {
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

    override fun delete(account: String): Int {
        val serviceRef = service.toCFString()
        val accountRef = account.toCFString()
        return releasing(serviceRef, accountRef) {
            val query = cfDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef, kSecAttrAccount to accountRef)
            releasing(query) { SecItemDelete(query) }
        }
    }
}
