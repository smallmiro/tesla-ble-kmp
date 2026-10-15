package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class KeyCreationChainTest {
    private class StubKey(
        override val protection: KeyProtection,
    ) : VehicleKey {
        override val alias: String = "a"
        override val publicKey: PublicKeyBytes = PublicKeyBytes(ByteArray(65).also { it[0] = 4 })

        override suspend fun sharedX(peer: PublicKeyBytes): ByteArray = ByteArray(32)
    }

    private val strongBox = StubKey(KeyProtection.STRONGBOX)
    private val tee = StubKey(KeyProtection.TEE)
    private val software = StubKey(KeyProtection.SOFTWARE)
    private var calls = mutableListOf<String>()

    private fun attempt(
        name: String,
        key: VehicleKey?,
    ) = KeyAttempt {
        calls += name
        key
    }

    @Test
    fun preferHardwareTakesTheFirstAvailableLevel() =
        runTest {
            val result =
                KeyCreationChain.create(
                    KeyPolicy.PREFER_HARDWARE,
                    listOf(attempt("sb", null), attempt("tee", tee)),
                    attempt("sw", software),
                )
            assertSame(tee, assertIs<VehicleResult.Success<VehicleKey>>(result).value)
            assertEquals(listOf("sb", "tee"), calls)
        }

    @Test
    fun preferHardwareFallsBackToSoftware() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.PREFER_HARDWARE, listOf(attempt("sb", null)), attempt("sw", software))
            assertSame(software, assertIs<VehicleResult.Success<VehicleKey>>(result).value)
        }

    @Test
    fun requireHardwareFailsWithoutTouchingSoftware() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.REQUIRE_HARDWARE, listOf(attempt("sb", null)), attempt("sw", software))
            assertEquals(VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable), result)
            assertEquals(listOf("sb"), calls)
        }

    @Test
    fun softwareOnlySkipsHardware() =
        runTest {
            KeyCreationChain.create(KeyPolicy.SOFTWARE_ONLY, listOf(attempt("sb", strongBox)), attempt("sw", software))
            assertEquals(listOf("sw"), calls)
        }

    @Test
    fun throwingHardwareAttemptCountsAsUnavailableAndIsLogged() =
        runTest {
            val logs = mutableListOf<String>()
            val result =
                KeyCreationChain.create(
                    KeyPolicy.PREFER_HARDWARE,
                    listOf(KeyAttempt { error("StrongBoxUnavailableException") }),
                    attempt("sw", software),
                    logger = { logs += it },
                )
            assertSame(software, assertIs<VehicleResult.Success<VehicleKey>>(result).value)
            assertEquals(1, logs.size)
        }

    @Test
    fun softwareFailureIsPlatformFailure() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.SOFTWARE_ONLY, emptyList(), KeyAttempt { error("no entropy") })
            assertIs<VehicleError.KeyStoreError.PlatformFailure>(assertIs<VehicleResult.Failure>(result).error)
        }

    @Test
    fun cancellationPropagates() =
        runTest {
            assertFailsWith<CancellationException> {
                KeyCreationChain.create(
                    KeyPolicy.PREFER_HARDWARE,
                    listOf(KeyAttempt { throw CancellationException("stop") }),
                    attempt("sw", software),
                )
            }
        }
}
