package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource

public actual fun platformCryptoPrimitives(): CryptoPrimitives = AppleCryptoPrimitives()

public actual fun platformRandomSource(): RandomSource = AppleRandomSource()

public actual fun softwareEcdhKey(
    privateScalar: ByteArray,
    publicKey: PublicKeyBytes,
): EcdhPrivateKey = AppleSoftwareEcdhKey(privateScalar, publicKey)
