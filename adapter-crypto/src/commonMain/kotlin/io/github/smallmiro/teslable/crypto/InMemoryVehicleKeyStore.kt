package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.KeyPolicy
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 비영속 소프트웨어 키스토어(설계 구체화 8): JVM 기본값, 테스트용. 프로세스가 끝나면 키가 사라진다. */
public fun inMemoryVehicleKeyStore(): VehicleKeyStore = InMemoryVehicleKeyStore()

internal class InMemoryVehicleKeyStore : VehicleKeyStore {
    private val keys = HashMap<String, VehicleKey>()
    private val mutex = Mutex()

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        return mutex.withLock {
            keys[alias]?.let { return@withLock VehicleResult.Success(it) }
            KeyCreationChain
                .create(policy, hardware = emptyList(), software = { SoftwareVehicleKey(alias, generateSoftwareKeyPair()) })
                .also { if (it is VehicleResult.Success) keys[alias] = it.value }
        }
    }

    override suspend fun get(alias: String): VehicleResult<VehicleKey?> {
        VehicleKeyStore.requireValidAlias(alias)
        return VehicleResult.Success(mutex.withLock { keys[alias] })
    }

    override suspend fun delete(alias: String): VehicleResult<Unit> {
        VehicleKeyStore.requireValidAlias(alias)
        mutex.withLock { keys.remove(alias) }
        return VehicleResult.Success(Unit)
    }
}
