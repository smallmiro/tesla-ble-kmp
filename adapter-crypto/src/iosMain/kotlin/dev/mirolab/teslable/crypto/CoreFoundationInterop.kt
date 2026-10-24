package dev.mirolab.teslable.crypto

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
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.CFBridgingRelease
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.createCleaner

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

/**
 * 키 객체가 맡은 Core Foundation 참조(+1) 하나를, 그 객체가 더는 쓰이지 않아 수거될 때(GC) 정확히 한 번 `CFRelease`한다(#97, FR-020).
 * 키 객체의 첫 필드로 둔다: 생성자의 나머지 초기화가 실패해도 넘겨받은 참조가 새지 않으므로, 넘긴 쪽은 그 참조를 해제하지 않는다.
 * 해제는 GC 뒤 다른 스레드에서 비동기로 일어난다(Core Foundation retain/release는 스레드 안전). 앱이나 연결이 키를 붙잡고 있는 동안에는
 * 일어나지 않는다. 그래서 `VehicleKey`에는 `close()`가 없다 — Go `ECDHPrivateKey`도 수명을 GC에 맡긴다.
 * 호출 하나 안에서 쓰고 버리는 참조(질의 딕셔너리, 상대 공개키, CFData)는 이것 대신 [releasing]·[use]로 바로 해제한다.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal class ReleaseWhenCollected(
    reference: CFTypeRef,
) {
    // 소유자와 함께 수거되도록 붙잡아 두기만 한다. 정리 함수는 아무것도 캡처하지 않는다(createCleaner 규칙)
    @Suppress("unused")
    private val cleaner = createCleaner(reference) { CFRelease(it) }
}
