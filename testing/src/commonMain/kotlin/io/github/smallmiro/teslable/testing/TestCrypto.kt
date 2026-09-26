package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.crypto.softwareEcdhKey
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes

/** protocol.md 테스트 키·벡터를 실제 플랫폼 암호 원시연산 위에서 재현하기 위한 헬퍼. */
@OptIn(InternalTeslableApi::class)
public object TestCrypto {
    /** 현재 플랫폼의 [CryptoPrimitives] 구현. */
    public val primitives: CryptoPrimitives by lazy { platformCryptoPrimitives() }

    /** protocol.md client 공개키. */
    public val clientPublicKey: PublicKeyBytes = PublicKeyBytes(ProtocolVectors.CLIENT_PUBLIC_KEY.hexToBytes())

    /** protocol.md vehicle 공개키. */
    public val vehiclePublicKey: PublicKeyBytes = PublicKeyBytes(ProtocolVectors.VEHICLE_PUBLIC_KEY.hexToBytes())

    /** protocol.md client.key */
    public fun clientKey(): EcdhPrivateKey = softwareEcdhKey(ProtocolVectors.CLIENT_PRIVATE_SCALAR.hexToBytes(), clientPublicKey)

    /** protocol.md vehicle.key (FakeVehicle 용) */
    public fun vehicleKey(): EcdhPrivateKey = softwareEcdhKey(ProtocolVectors.VEHICLE_PRIVATE_SCALAR.hexToBytes(), vehiclePublicKey)
}
