package dev.mirolab.teslable.port

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VehicleKeyStoreTest {
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
