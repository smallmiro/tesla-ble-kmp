package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey

/** 소프트웨어 키 핸들. ECDH는 기존 `softwareEcdhKey`(JCA/Security.framework)에 맡긴다. */
internal class SoftwareVehicleKey(
    override val alias: String,
    pair: SoftwareKeyPair,
) : VehicleKey {
    private val delegate: EcdhPrivateKey = softwareEcdhKey(pair.privateScalar.copyOf(), pair.publicKey)

    override val publicKey: PublicKeyBytes = pair.publicKey
    override val protection: KeyProtection = KeyProtection.SOFTWARE

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray = delegate.sharedX(peer)
}
