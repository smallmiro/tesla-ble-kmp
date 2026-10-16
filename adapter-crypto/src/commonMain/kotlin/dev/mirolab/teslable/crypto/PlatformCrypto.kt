package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.CryptoPrimitives
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.RandomSource

/** 플랫폼 기본 원시연산 (Android/JVM: JCA, iOS: CommonCrypto + CryptoKit 프로바이더). */
public expect fun platformCryptoPrimitives(): CryptoPrimitives

/** 플랫폼 기본 CSPRNG. */
public expect fun platformRandomSource(): RandomSource

/**
 * 소프트웨어 P-256 개인키 (하드웨어 대체 경로와 테스트 벡터용, D10).
 * [privateScalar] 32바이트 big-endian, [publicKey] 65바이트. 공개키는 호출자가 알고 있어야 한다.
 */
public expect fun softwareEcdhKey(
    privateScalar: ByteArray,
    publicKey: PublicKeyBytes,
): EcdhPrivateKey
