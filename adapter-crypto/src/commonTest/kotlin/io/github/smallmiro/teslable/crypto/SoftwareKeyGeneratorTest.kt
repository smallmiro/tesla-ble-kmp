package io.github.smallmiro.teslable.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SoftwareKeyGeneratorTest {
    @Test
    fun generatedKeysAgreeOnTheSameSharedX() =
        runTest {
            val a = generateSoftwareKeyPair()
            val b = generateSoftwareKeyPair()
            assertEquals(32, a.privateScalar.size)
            assertEquals(4, a.publicKey.toByteArray()[0].toInt())
            assertFalse(a.publicKey == b.publicKey)
            val ab = SoftwareVehicleKey("a", a).sharedX(b.publicKey)
            val ba = SoftwareVehicleKey("b", b).sharedX(a.publicKey)
            assertEquals(32, ab.size)
            assertContentEquals(ab, ba)
        }

    @Test
    fun externalRepresentationRoundTrips() {
        val pair = generateSoftwareKeyPair()
        val external = pair.toExternal()
        assertEquals(97, external.size) // 04‖X‖Y‖d (SecKeyCopyExternalRepresentation과 같은 형식)
        val back = SoftwareKeyPair.fromExternal(external)
        assertEquals(pair.publicKey, back?.publicKey)
        assertContentEquals(pair.privateScalar, back?.privateScalar)
        assertNull(SoftwareKeyPair.fromExternal(external.copyOf(96)))
        assertNull(SoftwareKeyPair.fromExternal(ByteArray(97)))
    }
}
