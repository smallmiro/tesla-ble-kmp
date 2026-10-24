package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFGetRetainCount
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFNumberIntType
import platform.Foundation.NSThread
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyRef
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrKeyClassPrivate
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecValueRef
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

private const val P384_BITS = 384
private const val MAX_GC_ROUNDS = 500
private const val STABLE_GC_ROUNDS = 5
private const val ROUND_PAUSE_SECONDS = 0.01

/**
 * #97 (FR-020, ADR-0008): 키 객체가 가진 SecKeyRef의 수명. 키 객체는 생성자에 넘어온 +1 참조 하나를 맡고(생성자가 실패해도),
 * 더는 쓰이지 않아 수거되면(GC) 그 참조를 정확히 한 번 해제한다. `VehicleKey`에는 `close()`가 없다(Go `ECDHPrivateKey`도 GC에 맡긴다).
 *
 * 관찰용 참조를 따로 잡고 `CFGetRetainCount`로 본다: 키가 살아 있는 동안 줄지 않고(조기 해제 없음), 수거 뒤 정확히 하나 준다(누수·이중
 * 해제 없음). 관찰용 참조는 둘을 잡아, 이중 해제가 일어나도 객체가 살아 있어 숫자로 드러나게 한다. 키는 별도 함수에서 만들고 버린다 —
 * 테스트 함수의 지역 변수가 키를 붙잡지 않게. 시뮬레이터에는 Secure Enclave가 없어 소프트웨어 SecKeyRef가 그 자리에 선다(소유 규칙은 같다).
 */
@OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)
class KeyHandleLifetimeTest {
    @Test
    fun secureEnclaveKeyReleasesItsReferenceOnceWhenCollected() {
        val ref = newPrivateKeyRef() // 이 +1은 키에 넘긴다
        holdForObservation(ref)
        val alive = useAndDropSecureEnclaveKey(ref)
        awaitRetainCount(ref, alive - 1)
        assertRetainCountStays(ref, alive - 1)
        releaseObservation(ref)
    }

    @Test
    fun softwareKeyReleasesItsReferenceOnceWhenCollected() {
        val pair = generateSoftwareKeyPair()
        val ref = createSecKey(pair.toExternal(), kSecAttrKeyClassPrivate) // 이 +1은 키에 넘긴다
        holdForObservation(ref)
        val alive = useAndDropSoftwareKey(ref, pair.publicKey)
        awaitRetainCount(ref, alive - 1)
        assertRetainCountStays(ref, alive - 1)
        releaseObservation(ref)
    }

    @Test
    fun enclaveQueryResultKeyReleasesTheReferenceItRetained() {
        // enclaveFoundOf는 질의 결과 딕셔너리가 가진 참조에 키 몫을 retain한다 — 키가 수거되면 그 몫만 돌려준다
        val result = queryResultHolding(newPrivateKeyRef())
        val ref = keyReferenceIn(result)
        holdForObservation(ref)
        val before = CFGetRetainCount(ref)
        useAndDropFoundKey(result)
        awaitRetainCount(ref, before)
        assertRetainCountStays(ref, before)
        CFRelease(result)
        releaseObservation(ref)
    }

    @Test
    fun repeatedReadsThroughTheKeyStoreDoNotAccumulateReferences() {
        // 오래 켜 두는 모니터처럼 연결마다 get으로 키를 다시 읽어도(13-lib-pairing §Key accessibility) 버린 핸들의 참조가 쌓이지 않는다.
        // runTest 대신 runBlocking: runTest의 스케줄러는 마지막 결과를 다음 작업 때까지 붙잡아 키 하나가 남는 것처럼 보인다(라이브러리와 무관)
        val result = queryResultHolding(newPrivateKeyRef())
        val ref = keyReferenceIn(result)
        holdForObservation(ref)
        val before = CFGetRetainCount(ref)
        runBlocking { readAndDrop(AppleVehicleKeyStore(UnusedKeychain, QueryResultEnclave(result)), times = 20) }
        awaitRetainCount(ref, before)
        assertRetainCountStays(ref, before)
        CFRelease(result)
        releaseObservation(ref)
    }

    @Test
    fun keysKeepTheirReferenceWhileReachable() =
        runTest {
            // 수거 전에 해제하면 쓰는 중인 핸들이 사라진다 — 키를 붙잡고 있는 동안에는 GC가 돌아도 참조가 그대로이고 ECDH가 된다
            val pair = generateSoftwareKeyPair()
            val enclaveRef = createSecKey(pair.toExternal(), kSecAttrKeyClassPrivate)
            val softwareRef = createSecKey(pair.toExternal(), kSecAttrKeyClassPrivate)
            holdForObservation(enclaveRef)
            holdForObservation(softwareRef)
            val enclaveKey = SecureEnclaveKey("a", enclaveRef, KeyAccessibility.WHEN_UNLOCKED)
            val softwareKey = AppleSoftwareEcdhKey(softwareRef, pair.publicKey)
            assertRetainCountStays(enclaveRef, CFGetRetainCount(enclaveRef))
            assertRetainCountStays(softwareRef, CFGetRetainCount(softwareRef))
            val peer = generateSoftwareKeyPair().publicKey
            assertEquals(pair.publicKey, enclaveKey.publicKey)
            assertContentEquals(softwareKey.sharedX(peer), enclaveKey.sharedX(peer))
            releaseObservation(enclaveRef)
            releaseObservation(softwareRef)
        }

    @Test
    fun failedConstructionStillReleasesTheHandedOverReferenceOnce() {
        // P-384 키는 공개키 외부 표현이 97바이트라 SecureEnclaveKey 생성자(공개키 읽기)가 실패한다. 넘긴 참조는 키 몫이라 호출자가 해제하지 않는다
        val ref = p384PrivateKeyRef()
        holdForObservation(ref)
        val before = CFGetRetainCount(ref)
        assertIs<IllegalArgumentException>(constructSecureEnclaveKeyExpectingFailure(ref))
        awaitRetainCount(ref, before - 1)
        assertRetainCountStays(ref, before - 1)
        releaseObservation(ref)
    }

    @Test
    fun enclaveQueryResultWithAnUnusableKeyReleasesTheRetainedReferenceOnce() {
        val result = queryResultHolding(p384PrivateKeyRef())
        val ref = keyReferenceIn(result)
        holdForObservation(ref)
        val before = CFGetRetainCount(ref)
        assertIs<IllegalArgumentException>(findExpectingFailure(result))
        awaitRetainCount(ref, before)
        assertRetainCountStays(ref, before)
        CFRelease(result)
        releaseObservation(ref)
    }

    /** 질의 결과 키(Secure Enclave 대신 소프트웨어 참조)를 늘 돌려주는 가짜. 실제 [enclaveFoundOf]가 참조를 retain한다. */
    private class QueryResultEnclave(
        private val result: CFDictionaryRef,
    ) : SecureEnclaveKeys {
        override fun find(alias: String): EnclaveFind = enclaveFoundOf(alias, result)

        override fun create(
            alias: String,
            accessibility: KeyAccessibility,
        ): VehicleKey? = fail("not expected: create")

        override fun delete(alias: String): Int = fail("not expected: delete")
    }

    /** Secure Enclave에서 키를 찾으면 Keychain은 읽지 않는다. */
    private object UnusedKeychain : KeychainItems {
        override fun read(account: String): KeychainRead = fail("not expected: read")

        override fun write(
            account: String,
            data: ByteArray,
            accessibility: KeyAccessibility,
        ): Int = fail("not expected: write")

        override fun delete(account: String): Int = fail("not expected: delete")
    }

    private suspend fun readAndDrop(
        store: AppleVehicleKeyStore,
        times: Int,
    ) {
        repeat(times) { assertIs<VehicleResult.Success<VehicleKey?>>(store.get("monitor")) }
    }

    /** [ref]를 넘겨받은 키를 쓰고 버린다. 키가 살아 있는 동안의 참조 수를 돌려준다. */
    private fun useAndDropSecureEnclaveKey(ref: SecKeyRef): Long {
        val key = SecureEnclaveKey("a", ref, KeyAccessibility.WHEN_UNLOCKED)
        assertEquals(PublicKeyBytes.SIZE, key.publicKey.toByteArray().size)
        return CFGetRetainCount(ref)
    }

    private fun useAndDropSoftwareKey(
        ref: SecKeyRef,
        publicKey: PublicKeyBytes,
    ): Long {
        val key = AppleSoftwareEcdhKey(ref, publicKey)
        assertEquals(publicKey, key.publicKey)
        return CFGetRetainCount(ref)
    }

    private fun useAndDropFoundKey(result: CFDictionaryRef) {
        assertEquals(KeyAccessibility.AFTER_FIRST_UNLOCK, enclaveFoundOf("a", result).key.accessibility)
    }

    private fun constructSecureEnclaveKeyExpectingFailure(ref: SecKeyRef): Throwable? =
        runCatching { SecureEnclaveKey("p384", ref, KeyAccessibility.WHEN_UNLOCKED) }.exceptionOrNull()

    private fun findExpectingFailure(result: CFDictionaryRef): Throwable? = runCatching { enclaveFoundOf("p384", result) }.exceptionOrNull()

    /** `SecItemCopyMatching`(참조 + 속성) 결과처럼 생긴 딕셔너리. [ref]의 +1은 딕셔너리로 옮긴다(딕셔너리가 retain한 뒤 놓는다). */
    private fun queryResultHolding(ref: SecKeyRef): CFDictionaryRef {
        val result = cfDictionary(kSecValueRef to ref, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        CFRelease(ref)
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun keyReferenceIn(result: CFDictionaryRef): SecKeyRef =
        checkNotNull(platform.CoreFoundation.CFDictionaryGetValue(result, kSecValueRef)) as SecKeyRef

    private fun newPrivateKeyRef(): SecKeyRef = createSecKey(generateSoftwareKeyPair().toExternal(), kSecAttrKeyClassPrivate)

    /** 소프트웨어 비영속 P-384 개인키. 공개키 외부 표현이 97바이트라 `PublicKeyBytes`(65바이트)가 거부한다. */
    private fun p384PrivateKeyRef(): SecKeyRef =
        memScoped {
            val bits = alloc<IntVar>().apply { value = P384_BITS }
            val bitsNumber = checkNotNull(CFNumberCreate(null, kCFNumberIntType, bits.ptr))
            val attributes = cfDictionary(kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits to bitsNumber)
            CFRelease(bitsNumber)
            try {
                val error = alloc<CFErrorRefVar>()
                checkNotNull(SecKeyCreateRandomKey(attributes, error.ptr)) { "P-384 key: ${error.takeDescription()}" }
            } finally {
                CFRelease(attributes)
            }
        }

    private fun holdForObservation(ref: SecKeyRef) {
        CFRetain(ref)
        CFRetain(ref)
    }

    private fun releaseObservation(ref: SecKeyRef) {
        CFRelease(ref)
        CFRelease(ref)
    }

    /** GC를 돌리며 해제를 기다린다. Cleaner는 GC 뒤 다른 스레드에서 비동기로 돈다. 기대보다 줄면 바로 실패(이중 해제). */
    private fun awaitRetainCount(
        ref: SecKeyRef,
        expected: Long,
    ) {
        repeat(MAX_GC_ROUNDS) {
            GC.collect()
            val count = CFGetRetainCount(ref)
            if (count == expected) return
            if (count < expected) fail("released too often: retain count $count, expected $expected")
            NSThread.sleepForTimeInterval(ROUND_PAUSE_SECONDS)
        }
        fail("not released: retain count ${CFGetRetainCount(ref)}, expected $expected")
    }

    /** GC를 몇 번 더 돌려도 [expected] 그대로다(조기 해제·이중 해제 없음). */
    private fun assertRetainCountStays(
        ref: SecKeyRef,
        expected: Long,
    ) {
        repeat(STABLE_GC_ROUNDS) {
            GC.collect()
            NSThread.sleepForTimeInterval(ROUND_PAUSE_SECONDS)
            assertEquals(expected, CFGetRetainCount(ref), "retain count changed")
        }
    }
}
