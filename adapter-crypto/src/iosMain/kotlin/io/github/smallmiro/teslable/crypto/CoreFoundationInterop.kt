package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFErrorRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.Foundation.CFBridgingRelease

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toCFData(): CFDataRef {
    if (isEmpty()) return checkNotNull(CFDataCreate(null, null, 0)) { "CFDataCreate failed" }
    return usePinned { pinned ->
        checkNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong())) { "CFDataCreate failed" }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val pointer = CFDataGetBytePtr(this) ?: return ByteArray(0)
    return pointer.readBytes(length)
}

@OptIn(ExperimentalForeignApi::class)
internal fun CFErrorRef?.describe(): String {
    if (this == null) return "unknown CoreFoundation error"
    val description = CFErrorCopyDescription(this) ?: return "unknown CoreFoundation error"
    return (CFBridgingRelease(description) as? String) ?: "unknown CoreFoundation error"
}

/**
 * `error` out-파라미터(`CFErrorRefVar`)에서 설명 문자열을 읽고, 채워져 있으면 그 `CFErrorRef` 자체도 해제한다.
 * Security.framework의 `error: CFErrorRef*` 관례상 실패 시 채워지는 값은 Create Rule을 따라 호출자가 소유한다
 * (Swift 오버레이의 `Unmanaged<CFError>?.takeRetainedValue()`와 동일). `memScoped` 안에서, 실패 직후 한 번만 호출한다.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CFErrorRefVar.takeDescription(): String {
    val error = value
    val description = error.describe()
    if (error != null) CFRelease(error)
    return description
}

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> CFDataRef.use(block: (CFDataRef) -> T): T =
    try {
        block(this)
    } finally {
        CFRelease(this)
    }
