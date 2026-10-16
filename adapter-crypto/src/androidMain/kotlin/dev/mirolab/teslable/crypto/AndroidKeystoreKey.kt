package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.crypto.EcPublicKeyEncoding.toFixed
import dev.mirolab.teslable.model.PublicKeyBytes
import dev.mirolab.teslable.port.KeyProtection
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.ProviderException
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * AndroidKeyStore 개인키 핸들. ECDH는 Keystore 안에서 한다(SDD §8.2: `KeyAgreement("ECDH", "AndroidKeyStore")`).
 * Keystore의 `GeneralSecurityException`/`ProviderException`은 `IllegalArgumentException("ECDH failed: <Class>")`로 바꾼다.
 */
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
            // 합의할 수 없는 StrongBox/TEE 키 등 플랫폼 실패는 프로그래밍 오류로 바꾼다(SecureEnclaveKey와 같은 계약 — Signer.build가 잡는다)
            val secret =
                try {
                    KeyAgreement.getInstance("ECDH", "AndroidKeyStore").run {
                        init(privateKey)
                        doPhase(peerKey, true)
                        generateSecret()
                    }
                } catch (e: GeneralSecurityException) {
                    throw IllegalArgumentException("ECDH failed: ${e::class.simpleName}", e)
                } catch (e: ProviderException) {
                    throw IllegalArgumentException("ECDH failed: ${e::class.simpleName}", e)
                }
            BigInteger(1, secret).toFixed() // Go FillBytes(32)
        }
}
