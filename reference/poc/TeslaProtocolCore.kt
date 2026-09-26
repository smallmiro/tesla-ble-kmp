// commonMain 에 그대로 들어갈 수 있는 순수 Kotlin 로직 (플랫폼 API 의존 없음).
// Go 원본: internal/authentication/{metadata.go, native.go, peer.go, signer.go, window.go},
//          pkg/connector/ble/ble.go (framing, local name)
package tesla.protocol

// ---- 플랫폼별로 구현할 암호화 원시 연산 (KMP에서는 expect/actual 또는 cryptography-kotlin) ----
interface CryptoPrimitives {
    fun sha1(data: ByteArray): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    /** 표준 ECDH: 공유점의 X좌표 32바이트 (Secure Enclave / Android Keystore 모두 이 형태를 반환) */
    fun ecdhRawX(peerPublicUncompressed: ByteArray): ByteArray
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> // (ciphertext, tag)
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray
    fun randomBytes(n: Int): ByteArray
}

// ---- signatures.proto 의 Tag / SignatureType 값 ----
object Tag {
    const val SIGNATURE_TYPE = 0; const val DOMAIN = 1; const val PERSONALIZATION = 2; const val EPOCH = 3
    const val EXPIRES_AT = 4; const val COUNTER = 5; const val CHALLENGE = 6; const val FLAGS = 7
    const val REQUEST_HASH = 8; const val FAULT = 9; const val END = 255
}
object SignatureType { const val AES_GCM = 0; const val AES_GCM_PERSONALIZED = 5; const val HMAC = 6; const val HMAC_PERSONALIZED = 8; const val AES_GCM_RESPONSE = 9 }
object Domain { const val BROADCAST = 0; const val VEHICLE_SECURITY = 2; const val INFOTAINMENT = 3 }

// ---- metadata.go: TLV 직렬화 ----
class Metadata {
    private val buf = ArrayList<Byte>()
    private var last = -1
    fun add(tag: Int, value: ByteArray?): Metadata {
        require(tag >= last) { "metadata items need to be added in increasing tag order" }
        if (value == null) return this
        require(value.size <= 255) { "metadata field too long" }
        last = tag
        buf += tag.toByte(); buf += value.size.toByte(); value.forEach { buf += it }
        return this
    }
    fun addUint32(tag: Int, v: Long) = add(tag, u32be(v))
    /** Checksum 입력: 메타데이터 || 0xFF || message */
    fun serialize(message: ByteArray = ByteArray(0)): ByteArray = buf.toByteArray() + byteArrayOf(Tag.END.toByte()) + message
}

fun u32be(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

// ---- ble.go: 광고 이름, 2바이트 길이 프레이밍 ----
object BleTransport {
    const val SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e"
    const val TX_CHAR_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e" // write with response
    const val RX_CHAR_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e" // indicate
    const val MAX_MESSAGE = 1024

    fun localName(vin: String, c: CryptoPrimitives) = "S" + c.sha1(vin.encodeToByteArray()).copyOf(8).toHex() + "C"

    fun frame(msg: ByteArray, mtuPayload: Int): List<ByteArray> {
        val out = byteArrayOf((msg.size shr 8).toByte(), msg.size.toByte()) + msg
        return out.toList().chunked(mtuPayload) { it.toByteArray() }
    }

    /** 수신 청크 재조립 (Go: Connection.rx / flush). 1초 이상 끊기면 버퍼 리셋은 호출측에서. */
    class Reassembler {
        private var buffer = ByteArray(0)
        fun push(chunk: ByteArray): List<ByteArray> {
            buffer += chunk
            val out = mutableListOf<ByteArray>()
            while (buffer.size >= 2) {
                val len = ((buffer[0].toInt() and 0xff) shl 8) or (buffer[1].toInt() and 0xff)
                if (len > MAX_MESSAGE) { buffer = ByteArray(0); break }
                if (buffer.size < 2 + len) break
                out += buffer.copyOfRange(2, 2 + len)
                buffer = buffer.copyOfRange(2 + len, buffer.size)
            }
            return out
        }
        fun reset() { buffer = ByteArray(0) }
    }
}

// ---- native.go + signer.go: 세션 키, 세션정보 HMAC, 명령 암호화, 응답 복호화 ----
class Session(
    private val c: CryptoPrimitives,
    vehiclePublicKey: ByteArray,
    private val vin: String,
) {
    /** K = SHA1(ECDH_X)[:16] */
    val key: ByteArray = c.sha1(c.ecdhRawX(vehiclePublicKey)).copyOf(16)

    fun subkey(label: String) = c.hmacSha256(key, label.encodeToByteArray())

    /** 핸드셰이크 응답 검증 태그 (Go: SessionInfoHMAC) */
    fun sessionInfoTag(challengeUuid: ByteArray, encodedSessionInfo: ByteArray): ByteArray {
        val m = Metadata()
            .add(Tag.SIGNATURE_TYPE, byteArrayOf(SignatureType.HMAC.toByte()))
            .add(Tag.PERSONALIZATION, vin.encodeToByteArray())
            .add(Tag.CHALLENGE, challengeUuid)
        return c.hmacSha256(subkey("session info"), m.serialize(encodedSessionInfo))
    }

    fun commandMetadata(domain: Int, epoch: ByteArray, expiresAt: Long, counter: Long, flags: Long, sigType: Int = SignatureType.AES_GCM_PERSONALIZED): Metadata {
        val m = Metadata()
            .add(Tag.SIGNATURE_TYPE, byteArrayOf(sigType.toByte()))
            .add(Tag.DOMAIN, byteArrayOf(domain.toByte()))
            .add(Tag.PERSONALIZATION, vin.encodeToByteArray())
            .add(Tag.EPOCH, epoch)
            .addUint32(Tag.EXPIRES_AT, expiresAt)
            .addUint32(Tag.COUNTER, counter)
        if (flags > 0) m.addUint32(Tag.FLAGS, flags)
        return m
    }

    data class Encrypted(val nonce: ByteArray, val ciphertext: ByteArray, val tag: ByteArray)

    /** BLE 기본 인증 방식: AES-GCM, AAD = SHA256(metadata || 0xFF) */
    fun encryptCommand(plaintext: ByteArray, meta: Metadata, nonce: ByteArray = c.randomBytes(12)): Encrypted {
        val (ct, tag) = c.aesGcmEncrypt(key, nonce, plaintext, c.sha256(meta.serialize()))
        return Encrypted(nonce, ct, tag)
    }

    /** 요청 해시: sigType 1바이트 + 태그 (VCSEC + HMAC이면 태그 16바이트로 절단) */
    fun requestHash(sigType: Int, tag: ByteArray, domain: Int): ByteArray {
        val t = if (sigType == SignatureType.HMAC_PERSONALIZED && domain == Domain.VEHICLE_SECURITY) tag.copyOf(16) else tag
        return byteArrayOf(sigType.toByte()) + t
    }

    fun decryptResponse(
        fromDomain: Int, counter: Long, responseFlags: Long, requestHash: ByteArray, fault: Long,
        nonce: ByteArray, ciphertext: ByteArray, tag: ByteArray,
    ): ByteArray {
        val m = Metadata()
            .add(Tag.SIGNATURE_TYPE, byteArrayOf(SignatureType.AES_GCM_RESPONSE.toByte()))
            .add(Tag.DOMAIN, byteArrayOf(fromDomain.toByte()))
            .add(Tag.PERSONALIZATION, vin.encodeToByteArray())
            .addUint32(Tag.COUNTER, counter)
            .addUint32(Tag.FLAGS, responseFlags)   // 응답에서는 0이어도 항상 포함
            .add(Tag.REQUEST_HASH, requestHash)
            .addUint32(Tag.FAULT, fault)
        return c.aesGcmDecrypt(key, nonce, ciphertext, tag, c.sha256(m.serialize()))
    }
}

// ---- window.go: 응답 counter 재사용(replay) 방지 슬라이딩 윈도우 ----
class SlidingWindow(private val size: Int = 32) {
    private var used = false; private var counter = 0L; private var history = 0UL
    fun update(newCounter: Long): Boolean {
        if (!used) { used = true; counter = newCounter; return true }
        if (newCounter == counter) return false
        if (newCounter < counter) {
            val age = counter - newCounter
            if (age > size) return false
            val bit = 1UL shl (age - 1).toInt()
            if (history and bit != 0UL) return false
            history = history or bit; return true
        }
        val shift = (newCounter - counter).toInt()
        history = if (shift >= 64) 0UL else history shl shift
        if (shift <= 64) history = history or (1UL shl (shift - 1))
        counter = newCounter; return true
    }
}

fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
