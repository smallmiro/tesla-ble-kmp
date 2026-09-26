package io.github.smallmiro.teslable.port

/** CSPRNG. routing_address·uuid 16B, nonce 12B 생성에 쓴다. */
public fun interface RandomSource {
    /** 암호학적으로 안전한 난수 [count] 바이트를 새 배열로 돌려준다. */
    public fun nextBytes(count: Int): ByteArray
}
