package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.port.KeyProtection
import kotlinx.coroutines.test.runTest
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidKeystoreKeyTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun platformEcdhFailureIsAProgrammingErrorNotAPlatformException() =
        // 최종 리뷰 I3: Keystore ECDH의 GeneralSecurityException/ProviderException이 수신 코루틴까지 새지 않게
        // IllegalArgumentException으로 바꾼다(SecureEnclaveKey와 같은 계약). 호스트에는 AndroidKeyStore 공급자가 없어
        // KeyAgreement.getInstance가 NoSuchProviderException(GeneralSecurityException)을 던진다.
        runTest {
            val own = keyPair()
            val peer = keyPair().public as ECPublicKey
            val key =
                AndroidKeystoreKey(
                    alias = "test",
                    privateKey = own.private,
                    publicKey = EcPublicKeyEncoding.encode((own.public as ECPublicKey).w.affineX, (own.public as ECPublicKey).w.affineY),
                    protection = KeyProtection.TEE,
                )
            val error =
                assertFailsWith<IllegalArgumentException> {
                    key.sharedX(EcPublicKeyEncoding.encode(peer.w.affineX, peer.w.affineY))
                }
            assertEquals("ECDH failed: NoSuchProviderException", error.message)
        }
}
