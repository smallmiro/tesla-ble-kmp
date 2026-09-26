package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.port.RandomSource

/** 미리 정한 바이트열을 순서대로 돌려주는 결정적 난수원. nonce·uuid·routing_address 고정용. */
public class FixedRandom(
    vararg sequence: ByteArray,
) : RandomSource {
    private val queue = ArrayDeque(sequence.map { it.copyOf() })

    override fun nextBytes(count: Int): ByteArray {
        val next = queue.removeFirstOrNull() ?: error("FixedRandom exhausted")
        require(next.size == count) { "FixedRandom expected ${next.size} bytes but $count requested" }
        return next
    }
}
