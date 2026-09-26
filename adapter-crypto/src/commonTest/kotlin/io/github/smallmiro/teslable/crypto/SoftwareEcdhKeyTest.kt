package io.github.smallmiro.teslable.crypto

import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.util.hexToBytes
import io.github.smallmiro.teslable.util.toHex
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalTeslableApi::class)
class SoftwareEcdhKeyTest {
    // protocol.md client.key / vehicle.pem (03-protocol.md §6). 실차에 절대 등록하지 말 것.
    private val clientScalar = "2538cdc29a97c19c1e99a637d6cf4f8c970c118b56ede1e6323e6d162c4b30db".hexToBytes()
    private val clientPub =
        PublicKeyBytes(
            (
                "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
                    "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e"
            ).hexToBytes(),
        )
    private val vehiclePub =
        PublicKeyBytes(
            (
                "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                    "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97"
            ).hexToBytes(),
        )

    @Test
    fun sharedXMatchesProtocolDocViaSha1Prefix() =
        runTest {
            // K = SHA1(sharedX)[:16] = 1b2fce19967b79db696f909cff89ea9a (03-protocol.md §7.3)
            val key = softwareEcdhKey(clientScalar, clientPub)
            val sharedX = key.sharedX(vehiclePub)
            assertEquals(32, sharedX.size)
            assertEquals("1b2fce19967b79db696f909cff89ea9a", platformCryptoPrimitives().sha1(sharedX).copyOf(16).toHex())
            assertEquals(clientPub, key.publicKey)
        }

    @Test
    fun rejectsPeerKeyNotOnCurve() =
        runTest {
            // Review Focus 5: 플랫폼 예외를 IllegalArgumentException 으로 통일
            val key = softwareEcdhKey(clientScalar, clientPub)
            val offCurve = PublicKeyBytes(ByteArray(65).also { it[0] = 0x04 })
            kotlin.test.assertFailsWith<IllegalArgumentException> { key.sharedX(offCurve) }
        }

    @Test
    fun sharedXIsZeroPaddedTo32Bytes() =
        runTest {
            // Go native_test.go TestSharedSecretPadding: scalar 0x013f 와 아래 공개키의 공유 X는 32바이트 미만 → 앞을 0으로 채움
            val scalar =
                ByteArray(32).also {
                    it[30] = 0x01
                    it[31] = 0x3f
                }
            val peer =
                PublicKeyBytes(
                    (
                        "04c4ff45b968e87469af648ed34c34a934d74a1d76f272d153fc81114ddfecc178" +
                            "f688e62bec19c9b144e9415361fa4fecab5ded4336336b9751c9a78ffa270fae"
                    ).hexToBytes(),
                )
            // 이 스칼라의 공개키는 테스트에 필요 없으므로 peer 공개키를 자리로 넣는다(ECDH는 개인키 스칼라만 쓴다)
            val key = softwareEcdhKey(scalar, peer)
            val sharedX = key.sharedX(peer)
            assertEquals(32, sharedX.size)
            assertEquals(0x00.toByte(), sharedX[0])
        }
}
