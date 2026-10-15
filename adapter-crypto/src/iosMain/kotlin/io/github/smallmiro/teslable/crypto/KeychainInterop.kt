package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain

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
