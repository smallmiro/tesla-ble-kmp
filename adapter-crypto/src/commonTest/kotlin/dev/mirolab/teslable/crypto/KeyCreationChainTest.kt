package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey
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
        override val accessibility: KeyAccessibility = KeyAccessibility.WHEN_UNLOCKED
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
    fun lockedHardwareAttemptIsLockedAndNeverFallsBackToSoftware() =
        // FR-037: 기기가 잠겨 하드웨어 키를 못 만든 것은 "하드웨어 없음"이 아니다. 일시적인 잠금 때문에
        // PREFER_HARDWARE가 영구 SOFTWARE 키를 남기면 안 되므로 다음 수준으로 넘어가지 않는다.
        runTest {
            val logs = mutableListOf<String>()
            val result =
                KeyCreationChain.create(
                    KeyPolicy.PREFER_HARDWARE,
                    listOf(KeyAttempt { throw KeyStoreLockedException() }, attempt("tee", tee)),
                    attempt("sw", software),
                    logger = { logs += it },
                )
            assertEquals(VehicleResult.Failure(VehicleError.KeyStoreError.Locked), result)
            assertEquals(emptyList(), calls)
            assertEquals(emptyList(), logs)
        }

    @Test
    fun lockedSoftwareAttemptIsLocked() =
        runTest {
            val result = KeyCreationChain.create(KeyPolicy.SOFTWARE_ONLY, emptyList(), KeyAttempt { throw KeyStoreLockedException() })
            assertEquals(VehicleResult.Failure(VehicleError.KeyStoreError.Locked), result)
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
