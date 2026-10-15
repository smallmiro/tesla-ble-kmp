package io.github.smallmiro.teslable.crypto

import kotlin.test.Test
import kotlin.test.assertNotEquals

class KeystoreAliasesTest {
    private val aliases = listOf("car", "car.wrap", "teslable-default", "a")

    @Test
    fun keyAndWrapAliasesNeverCollide() {
        for (a in aliases) {
            for (b in aliases) {
                assertNotEquals(KeystoreAliases.key(a), KeystoreAliases.wrap(b))
                if (a != b) {
                    assertNotEquals(KeystoreAliases.key(a), KeystoreAliases.key(b))
                    assertNotEquals(KeystoreAliases.wrap(a), KeystoreAliases.wrap(b))
                }
            }
        }
    }
}
