package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource

/** CommonCrypto/CryptoKit 기반 구현을 돌려준다. */
public actual fun platformCryptoPrimitives(): CryptoPrimitives = AppleCryptoPrimitives()

/** `SecRandomCopyBytes` 기반 구현을 돌려준다. */
public actual fun platformRandomSource(): RandomSource = AppleRandomSource()

/** Security.framework 기반 소프트웨어 P-256 개인키를 돌려준다. */
public actual fun softwareEcdhKey(
    privateScalar: ByteArray,
    publicKey: PublicKeyBytes,
): EcdhPrivateKey = AppleSoftwareEcdhKey(privateScalar, publicKey)
