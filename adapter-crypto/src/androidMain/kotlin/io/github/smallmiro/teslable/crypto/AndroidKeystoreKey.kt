package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.crypto.EcPublicKeyEncoding.toFixed
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.KeyProtection
import io.github.smallmiro.teslable.port.VehicleKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/** AndroidKeyStore 개인키 핸들. ECDH는 Keystore 안에서 한다(SDD §8.2: `KeyAgreement("ECDH", "AndroidKeyStore")`). */
internal class AndroidKeystoreKey(
    override val alias: String,
    private val privateKey: PrivateKey,
    override val publicKey: PublicKeyBytes,
    override val protection: KeyProtection,
) : VehicleKey {
    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray =
        withContext(Dispatchers.IO) {
            val params =
                AlgorithmParameters.getInstance("EC").run {
                    init(ECGenParameterSpec("secp256r1"))
                    getParameterSpec(ECParameterSpec::class.java)
                }
            val peerKey =
                try {
                    KeyFactory
                        .getInstance(
                            "EC",
                        ).generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, peer.x), BigInteger(1, peer.y)), params))
                } catch (e: GeneralSecurityException) {
                    throw IllegalArgumentException("invalid peer public key", e)
                }
            val agreement = KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
            agreement.init(privateKey)
            agreement.doPhase(peerKey, true)
            BigInteger(1, agreement.generateSecret()).toFixed() // Go FillBytes(32)
        }
}
