package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.EcdhPrivateKey
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey

/**
 * 소프트웨어 키 핸들. ECDH는 기존 `softwareEcdhKey`(JCA/Security.framework)에 맡긴다.
 * [accessibility]는 저장소가 정한다: iOS는 Keychain 항목의 보호 등급, Android·메모리는 [KeyAccessibility.AFTER_FIRST_UNLOCK](FR-037).
 */
internal class SoftwareVehicleKey(
    override val alias: String,
    pair: SoftwareKeyPair,
    override val accessibility: KeyAccessibility,
) : VehicleKey {
    private val delegate: EcdhPrivateKey = softwareEcdhKey(pair.privateScalar.copyOf(), pair.publicKey)

    override val publicKey: PublicKeyBytes = pair.publicKey
    override val protection: KeyProtection = KeyProtection.SOFTWARE

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray = delegate.sharedX(peer)
}
