// Ported from vehicle-command@a4b43c1 internal/authentication/native.go (Apache-2.0) — NativeECDHKey.sharedSecret
package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

private const val FIELD_SIZE_BYTES = 32

/** [EcdhPrivateKey]의 소프트웨어(JCA) 구현. 개인키 바이트는 저장 즉시 [PrivateKey]로 변환되고 밖으로 노출되지 않는다. */
internal class JcaSoftwareEcdhKey(
    privateScalar: ByteArray,
    override val publicKey: PublicKeyBytes,
) : EcdhPrivateKey {
    private val params: ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    private val privateKey: PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, privateScalar), params))

    override suspend fun sharedX(peer: PublicKeyBytes): ByteArray {
        // 프로바이더/환경 문제(getInstance)나 이 키 자체의 문제(init)는 그대로 전파한다. 오직 peer가 곡선 위에
        // 없는 등 peer 공개키 자체의 문제만 IllegalArgumentException으로 통일한다 (Review Focus 5).
        val keyFactory = KeyFactory.getInstance("EC")
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        try {
            val peerKey =
                keyFactory.generatePublic(
                    ECPublicKeySpec(ECPoint(BigInteger(1, peer.x), BigInteger(1, peer.y)), params),
                )
            keyAgreement.doPhase(peerKey, true)
        } catch (e: GeneralSecurityException) {
            // 곡선 위에 없는 점 등: InvalidKeySpecException / InvalidKeyException
            throw IllegalArgumentException("invalid peer public key", e)
        }
        val secret = keyAgreement.generateSecret()
        // JCA는 필드 크기(32바이트)로 돌려주지만, 짧게 주는 구현을 대비해 앞을 0으로 채운다 (Go FillBytes).
        return if (secret.size == FIELD_SIZE_BYTES) {
            secret
        } else {
            ByteArray(FIELD_SIZE_BYTES).also { secret.copyInto(it, FIELD_SIZE_BYTES - secret.size) }
        }
    }
}
