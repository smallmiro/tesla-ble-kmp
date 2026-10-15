package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCreateRandomKey
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom

private const val P256 = 256
private const val ATTRIBUTE_CAPACITY = 2L

/** 토큰 없는(소프트웨어) 비영속 P-256 키를 만들고 외부 표현 97바이트에서 키쌍을 읽는다. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun generateSoftwareKeyPair(): SoftwareKeyPair =
    memScoped {
        val attributes =
            checkNotNull(
                CFDictionaryCreateMutable(null, ATTRIBUTE_CAPACITY, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
            )
        val bits = alloc<IntVar>().apply { value = P256 }
        val bitsNumber = checkNotNull(CFNumberCreate(null, kCFNumberIntType, bits.ptr))
        try {
            CFDictionaryAddValue(attributes, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
            CFDictionaryAddValue(attributes, kSecAttrKeySizeInBits, bitsNumber)
            val error = alloc<CFErrorRefVar>()
            val key = SecKeyCreateRandomKey(attributes, error.ptr) ?: error("SecKeyCreateRandomKey failed: ${error.takeDescription()}")
            try {
                val external = SecKeyCopyExternalRepresentation(key, error.ptr) ?: error("export failed: ${error.takeDescription()}")
                external.use { data ->
                    val bytes = data.toByteArray()
                    try {
                        checkNotNull(SoftwareKeyPair.fromExternal(bytes)) { "unexpected key representation" }
                    } finally {
                        bytes.fill(0)
                    }
                }
            } finally {
                CFRelease(key)
            }
        } finally {
            CFRelease(bitsNumber)
            CFRelease(attributes)
        }
    }
