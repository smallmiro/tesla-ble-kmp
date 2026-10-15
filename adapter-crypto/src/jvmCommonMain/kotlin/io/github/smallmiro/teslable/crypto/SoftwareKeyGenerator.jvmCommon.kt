package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.crypto.EcPublicKeyEncoding.toFixed
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

internal actual fun generateSoftwareKeyPair(): SoftwareKeyPair {
    val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
    val pair = generator.generateKeyPair()
    val publicKey = pair.public as ECPublicKey
    val privateKey = pair.private as ECPrivateKey
    return SoftwareKeyPair(privateKey.s.toFixed(), EcPublicKeyEncoding.encode(publicKey.w.affineX, publicKey.w.affineY))
}
