package dev.mirolab.teslable.testing

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.KeyLockedException
import kotlin.concurrent.Volatile

/**
 * 잠긴 iPhone에서 쓰는 `WHEN_UNLOCKED` 키 핸들 흉내(FR-037, #96): 잠기기 전에 얻은 핸들이라 공개키는 언제나 읽히지만,
 * [locked]인 동안 [sharedX]는 [KeyLockedException]을 던진다. [locked]를 false로 바꾸면 [delegate]로 계산한다.
 */
public class LockableEcdhKey(
    private val delegate: EcdhPrivateKey = TestCrypto.clientKey(),
    locked: Boolean = true,
) : EcdhPrivateKey {
    /** true면 [sharedX]가 [KeyLockedException]을 던진다(기기 잠김). */
    @Volatile
    public var locked: Boolean = locked

    /** [sharedX]가 불린 횟수(잠긴 동안 포함). */
    @Volatile
    public var exchanges: Int = 0
        private set

    override val publicKey: PublicKeyBytes get() = delegate.publicKey

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray {
        exchanges++
        if (locked) throw KeyLockedException()
        return delegate.sharedX(peer)
    }
}
