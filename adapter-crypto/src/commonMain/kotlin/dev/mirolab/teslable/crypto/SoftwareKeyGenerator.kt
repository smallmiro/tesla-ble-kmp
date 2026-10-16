// Ported from vehicle-command@a4b43c1 pkg/protocol/key.go (Apache-2.0)
// NewECDHPrivateKey -> generateSoftwareKeyPair, SoftwareKeyPair
@file:Suppress("ktlint:standard:filename") // expect 선언 SoftwareKeyGenerator + 보조 타입 SoftwareKeyPair(브리프가 정한 파일명)

package dev.mirolab.teslable.crypto

import dev.mirolab.teslable.model.PublicKeyBytes

private const val SCALAR_SIZE = 32
private const val UNCOMPRESSED_TAG = 4
private const val EXTERNAL_SIZE = PublicKeyBytes.SIZE + SCALAR_SIZE

/** 소프트웨어 P-256 키쌍. [privateScalar]는 이 모듈 밖으로 나가지 않는다(NFR-004). */
internal class SoftwareKeyPair(
    val privateScalar: ByteArray,
    val publicKey: PublicKeyBytes,
) {
    init {
        require(privateScalar.size == SCALAR_SIZE) { "P-256 scalar must be 32 bytes" }
    }

    /** `04‖X‖Y‖d`(97바이트, Apple `SecKeyCopyExternalRepresentation` 개인키 형식). 저장 직전에만 만든다. */
    fun toExternal(): ByteArray = publicKey.toByteArray() + privateScalar

    companion object {
        /** [toExternal]의 역. 길이·형식이 틀리면 null. */
        fun fromExternal(bytes: ByteArray): SoftwareKeyPair? {
            if (bytes.size != EXTERNAL_SIZE || bytes[0].toInt() != UNCOMPRESSED_TAG) return null
            val publicKey = runCatching { PublicKeyBytes(bytes.copyOfRange(0, PublicKeyBytes.SIZE)) }.getOrNull() ?: return null
            return SoftwareKeyPair(bytes.copyOfRange(PublicKeyBytes.SIZE, EXTERNAL_SIZE), publicKey)
        }
    }
}

/** 플랫폼 CSPRNG로 P-256 키쌍을 만든다(JCA `KeyPairGenerator("EC")` / `SecKeyCreateRandomKey`). */
internal expect fun generateSoftwareKeyPair(): SoftwareKeyPair
