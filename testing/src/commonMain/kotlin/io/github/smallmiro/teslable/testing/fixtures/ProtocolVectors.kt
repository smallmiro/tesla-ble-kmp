package io.github.smallmiro.teslable.testing.fixtures

/**
 * `vehicle-command/pkg/protocol/protocol.md` 의 테스트 키와 벡터 (03-protocol.md §6~§8, 10-porting-guide.md §0.3).
 * 개인키가 공개된 키이므로 실차에 등록하지 않는다. 모든 값은 hex 문자열.
 */
public object ProtocolVectors {
    /** 03-protocol.md §7.4, §8.2.1 예시의 테스트 VIN. */
    public const val VIN: String = "5YJ30123456789ABC"

    /** 02-ble-transport.md §2 Local Name 예시의 입력 VIN. */
    public const val LOCAL_NAME_VIN: String = "5YJS0000000000000"

    /** 02-ble-transport.md §2: [LOCAL_NAME_VIN] 으로 만든 광고 Local Name (SHA1 기반). */
    public const val LOCAL_NAME: String = "S1a87a5a75f3df858C"

    /** 03-protocol.md §6.2 client.key 의 개인 스칼라 c (hex). */
    public const val CLIENT_PRIVATE_SCALAR: String = "2538cdc29a97c19c1e99a637d6cf4f8c970c118b56ede1e6323e6d162c4b30db"

    /** 03-protocol.md §6.2 client.pem 의 비압축 공개키 C = ENCODE_PUBLIC(c) (hex). */
    public const val CLIENT_PUBLIC_KEY: String =
        "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
            "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e"

    /** 03-protocol.md §6.1 vehicle.key 의 개인 스칼라 v (hex). */
    public const val VEHICLE_PRIVATE_SCALAR: String = "344ee5b466a7cf1eeb12b6f50331db2e5ec5834ef5f4befcfd8cbe55c2528d70"

    /** 03-protocol.md §6.1 vehicle.pem 의 비압축 공개키 V = ENCODE_PUBLIC(v) (hex). */
    public const val VEHICLE_PUBLIC_KEY: String =
        "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
            "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97"

    /** 03-protocol.md §7.3: K = SHA1(ECDH(c, V).x)[:16] (hex). */
    public const val SHARED_KEY_K: String = "1b2fce19967b79db696f909cff89ea9a"

    /** 03-protocol.md §7.4: SESSION_INFO_KEY = HMAC-SHA256(K, "session info") (hex). */
    public const val SESSION_INFO_KEY: String = "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300"

    /** 핸드셰이크 응답의 session_info (Signatures.SessionInfo: counter 6, vehicle pub, epoch, clock_time 2650) */
    public const val SESSION_INFO: String =
        "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
            "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97" +
            "1a104c463f9cc0d3d26906e982ed224adde6255a0a0000"

    /** 03-protocol.md §7.2 핸드셰이크 응답 session_info 의 epoch (hex). */
    public const val EPOCH: String = "4c463f9cc0d3d26906e982ed224adde6"

    /** 03-protocol.md §7.2 핸드셰이크 응답 session_info 의 clock_time. */
    public const val CLOCK_TIME: Int = 2650

    /** 03-protocol.md §7.2 핸드셰이크 응답 session_info 의 counter. */
    public const val COUNTER: Int = 6

    /** 03-protocol.md §7.1 핸드셰이크 요청의 uuid = challenge (hex). */
    public const val CHALLENGE: String = "1588d5a30eabc6f8fc9a951b11f6fd11"

    /** 03-protocol.md §7.4: 세션정보 태그 계산용으로 직렬화된 METADATA (hex). */
    public const val HANDSHAKE_METADATA: String =
        "000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff"

    /** protocol.md §7.4 세션정보 HMAC 태그 (hex). */
    public const val SESSION_INFO_TAG: String = "996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002"

    /** "Turn HVAC on" 예시 (flags = 2). protocol_doc_test.go 와 동일한 정본. */
    public const val HVAC_ON_PLAINTEXT: String = "120452020801"

    /** 03-protocol.md §8.2.1 "Turn HVAC on" 예시의 TAG_EXPIRES_AT (t=2655). */
    public const val HVAC_EXPIRES_AT: Int = 2655

    /** 03-protocol.md §8.2.1 "Turn HVAC on" 예시의 TAG_COUNTER. */
    public const val HVAC_COUNTER: Int = 7

    /** 03-protocol.md §8.2.1: flags = 2 일 때 직렬화된 메타데이터 M (hex). */
    public const val HVAC_METADATA_FLAGS2: String =
        "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6" +
            "040400000a5f050400000007070400000002ff"

    /** 03-protocol.md §8.2.1 AES-GCM nonce (hex). */
    public const val HVAC_NONCE: String = "dbf79447fa156674dae1caed"

    /** 03-protocol.md §8.2.1 AES-GCM 암호문 (hex). */
    public const val HVAC_CIPHERTEXT: String = "38038e8c0f2e"

    /** 03-protocol.md §8.2.1: flags = 2 일 때 AES-GCM 인증 태그 (hex). */
    public const val HVAC_TAG_FLAGS2: String = "c228e0ff64991481db3a7bbc133696c5"

    /** PoC 보조 케이스: flags = 0 이면 TAG_FLAGS 생략 (reference/poc/PocTest.kt) */
    public const val HVAC_METADATA_FLAGS0: String =
        "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6" +
            "040400000a5f050400000007ff"

    /** PoC 보조 케이스: flags = 0 일 때의 AES-GCM 인증 태그 (hex, reference/poc/PocTest.kt). */
    public const val HVAC_TAG_FLAGS0: String = "8e128da165f162f4d7d2c8da866cf82a"

    /** tesla-control -ble -debug list-keys 의 TX (03-protocol.md §15). uuid(51)가 payload(10)보다 앞에 있음에 주의. */
    public const val LIST_KEYS_TX: String =
        "320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805"

    /** 03-protocol.md §15: 위 TX 디코딩의 from_destination.routing_address (hex). */
    public const val LIST_KEYS_ROUTING_ADDRESS: String = "0a7962c10d38b61dd2a7722780a4f096"

    /** 03-protocol.md §15: 위 TX 디코딩의 uuid (hex). */
    public const val LIST_KEYS_UUID: String = "05514f57616bcc81a8ce0f9d7b483229"

    /** 03-protocol.md §15: 위 TX 디코딩의 protobuf_message_as_bytes (hex). */
    public const val LIST_KEYS_PAYLOAD: String = "0a020805"

    /** Go metadata_test.go TestCheckSum */
    public const val GO_CHECKSUM_EPOCH: String = "aada928a4f215f55f9e6e45e66b6521e"

    /** Go metadata_test.go TestCheckSum 기대 SHA-256 (hex). */
    public const val GO_CHECKSUM_SHA256: String = "abab04d804499813382efd74a06791ce2de777439603246dfbaa8392ca05868e"
}
