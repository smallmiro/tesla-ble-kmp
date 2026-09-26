package io.github.smallmiro.teslable.port

/** CSPRNG. routing_address·uuid 16B, nonce 12B 생성에 쓴다. */
public fun interface RandomSource {
    public fun nextBytes(count: Int): ByteArray
}
