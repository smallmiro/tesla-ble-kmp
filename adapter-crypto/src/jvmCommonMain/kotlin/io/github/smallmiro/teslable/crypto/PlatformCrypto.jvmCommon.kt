package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource

/** JCA 기반 구현을 돌려준다. */
public actual fun platformCryptoPrimitives(): CryptoPrimitives = JcaCryptoPrimitives()

/** [SecureRandom][java.security.SecureRandom] 기반 구현을 돌려준다. */
public actual fun platformRandomSource(): RandomSource = JcaRandomSource()

/** JCA 기반 소프트웨어 P-256 개인키를 돌려준다. */
public actual fun softwareEcdhKey(
    privateScalar: ByteArray,
    publicKey: PublicKeyBytes,
): EcdhPrivateKey = JcaSoftwareEcdhKey(privateScalar, publicKey)
