package io.github.smallmiro.teslable.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFErrorRef
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

@OptIn(ExperimentalForeignApi::class)
internal inline fun <T> CFDataRef.use(block: (CFDataRef) -> T): T =
    try {
        block(this)
    } finally {
        CFRelease(this)
    }
