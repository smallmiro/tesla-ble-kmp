// jvmMain / androidMain 역할: JCA로 CryptoPrimitives 구현 + protocol.md 테스트 벡터 검증
package tesla.poc

import tesla.protocol.*
import java.math.BigInteger
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class JcaCrypto(private val privateScalar: BigInteger) : CryptoPrimitives {
    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
    }
    override fun sha1(data: ByteArray) = MessageDigest.getInstance("SHA-1").digest(data)
    override fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    override fun hmacSha256(key: ByteArray, data: ByteArray) =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }
    override fun ecdhRawX(peerPublicUncompressed: ByteArray): ByteArray {
        require(peerPublicUncompressed.size == 65 && peerPublicUncompressed[0] == 4.toByte())
        val x = BigInteger(1, peerPublicUncompressed.copyOfRange(1, 33))
        val y = BigInteger(1, peerPublicUncompressed.copyOfRange(33, 65))
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        val priv = kf.generatePrivate(ECPrivateKeySpec(privateScalar, params))
        return KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
    }
    override fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(plaintext)
        }
        return out.copyOfRange(0, out.size - 16) to out.copyOfRange(out.size - 16, out.size)
    }
    override fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(ciphertext + tag)
        }
    override fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}

// 아주 작은 protobuf 리더 (실제 앱에서는 Wire 생성 코드 사용)
fun readFields(b: ByteArray): Map<Int, Any> {
    val out = LinkedHashMap<Int, Any>(); var i = 0
    fun varint(): Long { var r = 0L; var s = 0; while (true) { val x = b[i++].toInt() and 0xff; r = r or ((x and 0x7f).toLong() shl s); if (x < 0x80) return r; s += 7 } }
    while (i < b.size) {
        val key = varint(); val f = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            0 -> out[f] = varint()
            2 -> { val n = varint().toInt(); out[f] = b.copyOfRange(i, i + n); i += n }
            5 -> { out[f] = (0..3).fold(0L) { a, k -> a or ((b[i + k].toLong() and 0xff) shl (8 * k)) }; i += 4 }
            else -> error("wire type")
        }
    }
    return out
}

var fails = 0
fun check(name: String, actual: String, expected: String) {
    val ok = actual == expected; if (!ok) fails++
    println((if (ok) "PASS " else "FAIL ") + name + if (ok) "" else "\n   got      $actual\n   expected $expected")
}

fun main() {
    // protocol.md 테스트 키
    val c = JcaCrypto(BigInteger("2538CDC29A97C19C1E99A637D6CF4F8C970C118B56EDE1E6323E6D162C4B30DB", 16))
    val vehiclePub = "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97".hex()
    val vin = "5YJ30123456789ABC"

    // 1. BLE 광고 이름
    check("BLE local name", BleTransport.localName("5YJS0000000000000", c), "S1a87a5a75f3df858C")

    // 2. 핸드셰이크 응답 session_info 디코딩
    val sessionInfo = "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d971a104c463f9cc0d3d26906e982ed224adde6255a0a0000".hex()
    val si = readFields(sessionInfo)
    check("session_info.counter", si[1].toString(), "6")
    check("session_info.publicKey", (si[2] as ByteArray).toHex(), vehiclePub.toHex())
    check("session_info.epoch", (si[3] as ByteArray).toHex(), "4c463f9cc0d3d26906e982ed224adde6")
    check("session_info.clock_time", si[4].toString(), "2650")

    // 3. ECDH -> K
    val s = Session(c, si[2] as ByteArray, vin)
    check("shared key K", s.key.toHex(), "1b2fce19967b79db696f909cff89ea9a")
    check("SESSION_INFO_KEY", s.subkey("session info").toHex(), "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300")

    // 4. 세션정보 HMAC 태그 검증 (MITM 방지)
    val challenge = "1588d5a30eabc6f8fc9a951b11f6fd11".hex()
    check("session info HMAC tag", s.sessionInfoTag(challenge, sessionInfo).toHex(), "996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002")

    // 5. 명령 메타데이터 (HVAC on 예제)
    val epoch = si[3] as ByteArray
    val meta = s.commandMetadata(Domain.INFOTAINMENT, epoch, expiresAt = 2655, counter = 7, flags = 0)
    check("command metadata TLV", meta.serialize().toHex(), "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007ff")

    // 6. AES-GCM 암호화 → 외부(Python cryptography)에서 복호화 검증용으로 출력
    val hvacOn = "120452020801".hex()
    val nonce = "dbf79447fa156674dae1caed".hex()
    val enc = s.encryptCommand(hvacOn, meta, nonce)
    println("GCM nonce=${enc.nonce.toHex()} ct=${enc.ciphertext.toHex()} tag=${enc.tag.toHex()}")
    check("GCM ciphertext (matches protocol.md example w/ same nonce)", enc.ciphertext.toHex() + enc.tag.toHex(), "38038e8c0f2e" + "8e128da165f162f4d7d2c8da866cf82a")

    // 7. 응답 복호화 왕복 (차량 측 역할을 흉내내 암호화 후 복호화)
    val reqHash = s.requestHash(SignatureType.AES_GCM_PERSONALIZED, enc.tag, Domain.INFOTAINMENT)
    val respMeta = Metadata().add(Tag.SIGNATURE_TYPE, byteArrayOf(SignatureType.AES_GCM_RESPONSE.toByte()))
        .add(Tag.DOMAIN, byteArrayOf(Domain.INFOTAINMENT.toByte())).add(Tag.PERSONALIZATION, vin.encodeToByteArray())
        .addUint32(Tag.COUNTER, 8).addUint32(Tag.FLAGS, 0).add(Tag.REQUEST_HASH, reqHash).addUint32(Tag.FAULT, 0)
    val rn = c.randomBytes(12)
    val (rct, rtag) = c.aesGcmEncrypt(s.key, rn, "0a00".hex(), c.sha256(respMeta.serialize()))
    check("response decrypt roundtrip", s.decryptResponse(Domain.INFOTAINMENT, 8, 0, reqHash, 0, rn, rct, rtag).toHex(), "0a00")

    // 8. BLE 프레이밍 + 청크 재조립 (MTU 23 → payload 20)
    val msg = "320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805".hex()
    val chunks = BleTransport.frame(msg, 20)
    val r = BleTransport.Reassembler()
    val got = chunks.flatMap { r.push(it) }
    check("BLE frame/reassemble (${chunks.size} chunks)", got.single().toHex(), msg.toHex())

    // 9. protocol.md 의 실제 TX 로그 디코딩 (list-keys 요청)
    val rm = readFields(msg)
    val toDomain = readFields(rm[6] as ByteArray)[1]
    val routingAddr = readFields(rm[7] as ByteArray)[2] as ByteArray
    check("TX log: to_destination.domain", toDomain.toString(), Domain.VEHICLE_SECURITY.toString())
    check("TX log: routing_address", routingAddr.toHex(), "0a7962c10d38b61dd2a7722780a4f096")
    check("TX log: payload (VCSEC GET_WHITELIST_INFO)", (rm[10] as ByteArray).toHex(), "0a020805")

    // 10. 슬라이딩 윈도우 (replay 방지)
    val w = SlidingWindow()
    check("sliding window", listOf(w.update(10), w.update(12), w.update(11), w.update(11), w.update(12), w.update(50)).toString(), "[true, true, true, false, false, true]")

    println(if (fails == 0) "\nALL PASSED" else "\n$fails FAILED")
}
