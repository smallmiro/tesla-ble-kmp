package dev.mirolab.teslable.port

import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VehicleKeyStoreTest {
    /** 3인자 판만 구현한 저장소. 받은 인자를 기록한다. */
    private class RecordingStore : VehicleKeyStore {
        val calls = mutableListOf<Triple<String, KeyPolicy, KeyAccessibility>>()

        override suspend fun getOrCreate(
            alias: String,
            policy: KeyPolicy,
            accessibility: KeyAccessibility,
        ): VehicleResult<VehicleKey> {
            calls += Triple(alias, policy, accessibility)
            return VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable)
        }

        override suspend fun get(alias: String): VehicleResult<VehicleKey?> = VehicleResult.Success(null)

        override suspend fun delete(alias: String): VehicleResult<Unit> = VehicleResult.Success(Unit)
    }

    @Test
    fun twoArgOverloadIsWhenUnlocked() =
        // FR-037, ADR-0014: 기존 호출(Kotlin 기본 인자, Swift getOrCreate(alias:policy:))은 지금과 같은 WHEN_UNLOCKED로 만든다
        runTest {
            val store = RecordingStore()
            store.getOrCreate()
            store.getOrCreate("car-2", KeyPolicy.SOFTWARE_ONLY)
            store.getOrCreate(policy = KeyPolicy.REQUIRE_HARDWARE)
            assertEquals(
                listOf(
                    Triple(VehicleKeyStore.DEFAULT_ALIAS, KeyPolicy.PREFER_HARDWARE, KeyAccessibility.WHEN_UNLOCKED),
                    Triple("car-2", KeyPolicy.SOFTWARE_ONLY, KeyAccessibility.WHEN_UNLOCKED),
                    Triple(VehicleKeyStore.DEFAULT_ALIAS, KeyPolicy.REQUIRE_HARDWARE, KeyAccessibility.WHEN_UNLOCKED),
                ),
                store.calls,
            )
        }

    @Test
    fun accessibilityHasExactlyTheTwoThisDeviceOnlyClasses() {
        // 공개 enum의 값 순서는 Swift(SKIE)와 문서 표가 그대로 쓴다
        assertEquals(listOf(KeyAccessibility.WHEN_UNLOCKED, KeyAccessibility.AFTER_FIRST_UNLOCK), KeyAccessibility.entries)
    }

    @Test
    fun acceptsAliasesThatAreSafeAsFileNamesAndKeychainAccounts() {
        VehicleKeyStore.requireValidAlias(VehicleKeyStore.DEFAULT_ALIAS)
        VehicleKeyStore.requireValidAlias("car-2.backup_1")
        VehicleKeyStore.requireValidAlias("a".repeat(64))
    }

    @Test
    fun rejectsAliasesThatCouldEscapeTheKeyDirectory() {
        // 설계 구체화 10: 별칭은 파일 이름(filesDir/teslable/keys/<alias>.enc)과 Keychain account로 쓰인다
        listOf("", "../x", "a/b", "a b", "a".repeat(65), ".", "..").forEach { alias ->
            assertFailsWith<IllegalArgumentException>(alias) { VehicleKeyStore.requireValidAlias(alias) }
        }
    }

    @Test
    fun onlySecureEnclaveStrongBoxAndTeeAreHardware() {
        assertTrue(KeyProtection.SECURE_ENCLAVE.isHardware)
        assertTrue(KeyProtection.STRONGBOX.isHardware)
        assertTrue(KeyProtection.TEE.isHardware)
        assertFalse(KeyProtection.SOFTWARE.isHardware)
        assertEquals("teslable-default", VehicleKeyStore.DEFAULT_ALIAS)
    }
}
