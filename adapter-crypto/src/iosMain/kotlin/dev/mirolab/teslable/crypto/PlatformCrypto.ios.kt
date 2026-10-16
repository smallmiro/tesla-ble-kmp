package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.RandomSource

/** CommonCrypto/CryptoKit 기반 구현을 돌려준다. */
public actual fun platformCryptoPrimitives(): CryptoPrimitives = AppleCryptoPrimitives()

/** `SecRandomCopyBytes` 기반 구현을 돌려준다. */
public actual fun platformRandomSource(): RandomSource = AppleRandomSource()

/** Security.framework 기반 소프트웨어 P-256 개인키를 돌려준다. */
public actual fun softwareEcdhKey(
    privateScalar: ByteArray,
    publicKey: PublicKeyBytes,
): EcdhPrivateKey = AppleSoftwareEcdhKey(privateScalar, publicKey)
