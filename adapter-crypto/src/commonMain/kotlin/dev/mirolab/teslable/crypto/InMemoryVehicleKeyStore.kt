package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.VehicleKey
import dev.mirolab.teslable.port.VehicleKeyStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 비영속 소프트웨어 키스토어(설계 구체화 8): JVM 기본값, 테스트용. 프로세스가 끝나면 키가 사라진다.
 * 메모리 키는 프로세스가 살아 있는 한 기기 잠금과 무관하게 쓸 수 있으므로 요청한 접근성을 무시하고
 * [KeyAccessibility.AFTER_FIRST_UNLOCK]을 보고한다(FR-037, ADR-0014).
 */
public fun inMemoryVehicleKeyStore(): VehicleKeyStore = InMemoryVehicleKeyStore()

internal class InMemoryVehicleKeyStore : VehicleKeyStore {
    private val keys = HashMap<String, VehicleKey>()
    private val mutex = Mutex()

    override suspend fun getOrCreate(
        alias: String,
        policy: KeyPolicy,
        accessibility: KeyAccessibility,
    ): VehicleResult<VehicleKey> {
        VehicleKeyStore.requireValidAlias(alias)
        return mutex.withLock {
            keys[alias]?.let { return@withLock VehicleResult.Success(it) }
            KeyCreationChain
                .create(policy, hardware = emptyList(), software = {
                    SoftwareVehicleKey(alias, generateSoftwareKeyPair(), KeyAccessibility.AFTER_FIRST_UNLOCK)
                })
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
