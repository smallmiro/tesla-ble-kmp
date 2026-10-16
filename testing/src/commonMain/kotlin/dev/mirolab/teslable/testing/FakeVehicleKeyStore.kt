package dev.mirolab.teslable.testing

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.toResult
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.port.VehicleKeyStore

/** 메모리 [VehicleKeyStore] 가짜. 모든 별칭이 같은 [key]를 감싼다(파사드 테스트용). 단일 스레드 전용. */
public class FakeVehicleKeyStore(
    private val key: EcdhPrivateKey = TestCrypto.clientKey(),
    private val protection: KeyProtection = KeyProtection.SOFTWARE,
) : VehicleKeyStore {
    private val keys = HashMap<String, VehicleKey>()
    private val failures = ArrayDeque<VehicleError>()
    private val deletedAliases = mutableListOf<String>()

    /** [delete]된 별칭(순서대로). */
    public val deleted: List<String> get() = deletedAliases.toList()

    /** 다음 호출 하나가 [error]를 돌려주게 한다. */
    public fun failNext(error: VehicleError) {
        failures.addLast(error)
    }

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        failures.removeFirstOrNull()?.let { return it.toResult() }
        if (policy == KeyPolicy.REQUIRE_HARDWARE && !protection.isHardware) {
            return VehicleResult.Failure(VehicleError.KeyStoreError.HardwareUnavailable)
        }
        return VehicleResult.Success(keys.getOrPut(alias) { Handle(alias, key, protection) })
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        failures.removeFirstOrNull()?.let { return it.toResult() }
        return VehicleResult.Success(keys[alias])
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        failures.removeFirstOrNull()?.let { return it.toResult() }
        keys.remove(alias)
        deletedAliases += alias
        return VehicleResult.Success(Unit)
    }

    private class Handle(
        override val alias: String,
        private val delegate: EcdhPrivateKey,
        override val protection: KeyProtection,
    ) : VehicleKey {
        override val publicKey: PublicKeyBytes get() = delegate.publicKey

        override suspend fun sharedX(peer: PublicKeyBytes): ByteArray = delegate.sharedX(peer)
    }
}
