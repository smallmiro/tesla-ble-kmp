package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.crypto.platformRandomSource
import io.github.smallmiro.teslable.crypto.softwareEcdhKey
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.testing.fixtures.GoVectors
import io.github.smallmiro.teslable.testing.fixtures.ProtocolVectors
import io.github.smallmiro.teslable.util.hexToBytes

/** protocol.md 테스트 키·벡터를 실제 플랫폼 암호 원시연산 위에서 재현하기 위한 헬퍼. */
@OptIn(InternalTeslableApi::class)
public object TestCrypto {
    /** 현재 플랫폼의 [CryptoPrimitives] 구현. */
    public val primitives: CryptoPrimitives by lazy { platformCryptoPrimitives() }

    /** 현재 플랫폼의 [RandomSource] 구현 (nonce가 벡터로 고정되지 않는 테스트용). */
    public val random: RandomSource by lazy { platformRandomSource() }

    /** protocol.md client 공개키. */
    public val clientPublicKey: PublicKeyBytes = PublicKeyBytes(ProtocolVectors.CLIENT_PUBLIC_KEY.hexToBytes())

    /** protocol.md vehicle 공개키. */
    public val vehiclePublicKey: PublicKeyBytes = PublicKeyBytes(ProtocolVectors.VEHICLE_PUBLIC_KEY.hexToBytes())

    /** protocol.md client.key */
    public fun clientKey(): EcdhPrivateKey = softwareEcdhKey(ProtocolVectors.CLIENT_PRIVATE_SCALAR.hexToBytes(), clientPublicKey)

    /** protocol.md vehicle.key (FakeVehicle 용) */
    public fun vehicleKey(): EcdhPrivateKey = softwareEcdhKey(ProtocolVectors.VEHICLE_PRIVATE_SCALAR.hexToBytes(), vehiclePublicKey)

    /** Go `verifier_test.go TestGCMKnown`의 검증자 키 (사기꾼 차량 시나리오에도 쓴다). */
    public fun goKnownVerifierKey(): EcdhPrivateKey =
        softwareEcdhKey(
            GoVectors.GCM_KNOWN_VERIFIER_SCALAR.hexToBytes(),
            PublicKeyBytes(GoVectors.GCM_KNOWN_VERIFIER_PUBLIC_KEY.hexToBytes()),
        )
}
